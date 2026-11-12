package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.math.BigDecimal
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 同一 endpoint 的上传与提交共用进程级锁；远端执行由 ComfyUI 自身队列串行化。 */
class ComfyBackend(
    private val repository: ComfyRepository,
    private val http: ComfyHttp,
    private val resolver: WorkspaceMediaResolver,
    private val store: WorkspaceMediaStore,
    private val progress: ComfyProgress = ComfyProgress.None,
) : MediaGenerationBackend {
    override fun handles(channel: MediaChannel) = channel.id.startsWith("comfy:")
    override suspend fun estimate(channel: MediaChannel, request: MediaGenerationRequest) = ZERO
    override fun actualCost(channel: MediaChannel, usage: Map<String, Long>) = ZERO
    override suspend fun submit(channel: MediaChannel, request: MediaGenerationRequest): String = withContext(Dispatchers.IO) {
        val initial = repository.templateByChannel(channel.id)
        val firstHost = repository.host(initial.template.hostId)
        hostLocks.getOrPut(firstHost.endpoint.lowercaseHost()) { Mutex() }.withLock {
            val checked = repository.preflight(initial.template.id)
            val template = checked.template
            val host = repository.host(template.hostId)
            check(host == firstHost) { "主机配置已变化，请重新提交" }
            check(template.locked) { "模板未锁定，请先保存模板" }
            check(checked.preflight.status == PreflightStatus.PASS) { "模板预检未通过，请复核节点、模型和槽位" }
            require(channel == template.channel()) { "通道能力已变化，请刷新通道" }
            val validation = validateRequest(channel, request).let {
                // 纯派生处理的输入合同由来源视频和参数组成。
                if (template.role == TemplateRole.DERIVE && template.slots.none { slot -> slot.kind == SlotKind.PROMPT })
                    it.copy(specErrors = it.specErrors.filterNot { field -> field == "prompt" }) else it
            }
            require(validation.valid) { "生成请求不满足通道能力：超出 ${validation.excessInputs.size} 项，无效输入 ${validation.invalidInputs.size} 项；${validation.specErrors.joinToString("；")}" }
            require(request.sessionId.isNotBlank()) { "生成请求需要会话 id" }
            if (request.outputDir.isNotEmpty()) productionPath(request.outputDir)
            require(request.specs.durationSeconds == null || request.specs.frames != null || template.slots.any { it.kind == SlotKind.DURATION }) { "ComfyUI 时长需要 DURATION 槽位或显式指定 frames" }
            val params = template.resolveParams(request.params)
            require(template.role != TemplateRole.DERIVE || request.inputs.count { it.kind == InputKind.VIDEO } == 1) { "INPUT_VIDEO: DERIVE 需要一个来源视频" }
            val values = requestValues(template, request)
            // 先检查数值和数量，再读取或上传媒体。
            validateFilledComfySpecs(template, fillComfyWorkflow(template, values + inputPlaceholders(request), params))
            request.inputs.forEach { input ->
                val kind = slotKind(input)
                val path = productionPath(input.location)
                val resolution = resolver.resolve(request.sessionId, resolver.productionPath(request.sessionId, path)) as? MediaResolution.Available
                    ?: throw ComfyFailure("输入媒体不可读取，请检查会话工作区路径")
                val expected = when (input.kind) { InputKind.IMAGE -> MediaKind.IMAGE; InputKind.VIDEO -> MediaKind.VIDEO; InputKind.AUDIO -> MediaKind.AUDIO }
                require(resolution.ref.kind == expected) { "输入文件的媒体类型与请求不一致" }
                val mime = resolution.ref.mimeType.takeIf { it.isNotBlank() && it != "application/octet-stream" }
                    ?: inputMime(path, input.kind)
                val extension = extension(mime, input.kind)
                val filename = "input-${UUID.randomUUID()}.$extension"
                val remoteFile = resolver.open(resolution).use { source -> http.upload(host, filename, mime, source, input.kind) }
                values[kind] = values[kind].orEmpty() + remoteFile
            }
            val workflow = fillComfyWorkflow(template, values, params)
            validateFilledComfySpecs(template, workflow)
            val info = http.objectInfoFor(host, workflow)
            val filledCheck = preflightComfy(ComfyTemplate(template.id, workflow, emptyList()), info)
            if (filledCheck.status != PreflightStatus.PASS) {
                repository.markReview(template.id, "填充后的动态输入预检未通过，请复核槽位连线与主机 schema")
                throw ComfyFailure("动态输入预检未通过：${(filledCheck.missing + filledCheck.uncertain).take(8).joinToString("；")}")
            }
            // 锁定状态和主机身份在上传期间可能被用户改动，提交前重新核对。
            check(repository.templateByChannel(channel.id) == checked && repository.host(host.id) == host) { "模板或主机已变化，请重新提交" }
            val clientId = UUID.randomUUID().toString()
            progress.connect(host, clientId)
            val response = try { http.post(host, "/prompt", buildJsonObject { put("prompt", workflow); put("client_id", clientId) }) }
            catch (e: Exception) { progress.close(clientId); throw e }
            if ((response["error"] != null && response["error"] != JsonNull) || (response["node_errors"] as? JsonObject)?.isNotEmpty() == true) {
                progress.close(clientId)
                repository.markReview(template.id, "主机拒绝模板，请重新预检节点参数")
                throw ComfyFailure("ComfyUI 拒绝工作流，请复核节点与输入参数")
            }
            val promptId = (response["prompt_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: run { progress.close(clientId); throw ComfyFailure("ComfyUI 提交响应缺少 prompt_id，请检查主机队列后重试") }
            ComfyRemoteTicket(promptId, template.id, host.id, fingerprint(host.endpoint), clientId,
                template.slots.filter { it.kind == SlotKind.OUTPUT }).encode()
        }
    }
    override suspend fun poll(channel: MediaChannel, remoteId: String): RemoteStatus = withContext(Dispatchers.IO) {
        try {
            val ticket = ComfyRemoteTicket.decode(remoteId)
            require(channel.id == "comfy:${ticket.templateId}") { "远端任务与通道不匹配" }
            val host = ticketHost(ticket)
            val history = http.get(host, "/history/${ticket.promptId}")
            val parsed = parseComfyHistory(history, ticket.promptId, ticket.outputs)
            if (parsed is RemoteStatus.Running) {
                progress.connect(host, ticket.clientId)
                val queue = http.get(host, "/queue")
                val pending = queueContains(queue, "queue_pending", ticket.promptId)
                val running = queueContains(queue, "queue_running", ticket.promptId)
                if (!pending && !running && history[ticket.promptId] == null) {
                    // history 与 queue 分开读取，完成恰好发生在两次请求之间时再查一次。
                    val latest = parseComfyHistory(http.get(host, "/history/${ticket.promptId}"), ticket.promptId, ticket.outputs)
                    if (latest !is RemoteStatus.Running) { progress.close(ticket.clientId); return@withContext latest }
                    progress.close(ticket.clientId)
                    return@withContext RemoteStatus.Failed("主机未保留该任务，请检查主机是否重启或清理了历史记录", retryable = true)
                }
                RemoteStatus.Running(if (pending) 0f else progress.value(ticket.clientId, ticket.promptId))
            } else { progress.close(ticket.clientId); parsed }
        } catch (e: CancellationException) { throw e }
        catch (e: ComfyFailure) { runCatching { progress.close(ComfyRemoteTicket.decode(remoteId).clientId) }; RemoteStatus.Failed(e.message ?: "ComfyUI 请求失败", e.retryable) }
        catch (_: Exception) { runCatching { progress.close(ComfyRemoteTicket.decode(remoteId).clientId) }; RemoteStatus.Failed("ComfyUI 任务状态无法读取，请检查主机与任务配置") }
    }
    override suspend fun download(channel: MediaChannel, request: MediaGenerationRequest, remoteId: String, outputs: List<RemoteOutput>): List<String> = withContext(Dispatchers.IO) {
        val ticket = ComfyRemoteTicket.decode(remoteId)
        require(channel.id == request.channelId && channel.id == "comfy:${ticket.templateId}") { "产物与请求通道不匹配" }
        require(request.sessionId.isNotBlank() && outputs.isNotEmpty()) { "下载需要会话和产物" }
        val host = ticketHost(ticket)
        val directory = if (request.outputDir.isEmpty()) "media" else productionPath(request.outputDir)
        outputs.map { output ->
            validateComfyView(output.location)
            val url = ("http://comfy.invalid${output.location}").toHttpUrl()
            val original = url.queryParameter("filename").orEmpty()
            val suffix = original.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
                ?: extension(null, output.kind)
            val relative = "$directory/comfy-${UUID.randomUUID()}.$suffix"
            val target = resolver.productionPath(request.sessionId, relative)
            http.download(host, output.location) { input, mime ->
                store.save(request.sessionId, MediaPayload.Stream { input }, MediaOrigin.GENERATED, mime, original, target)
                relative
            }
        }
    }
    private suspend fun ticketHost(ticket: ComfyRemoteTicket): ComfyHost {
        val host = repository.host(ticket.hostId)
        if (fingerprint(host.endpoint) != ticket.endpointFingerprint) {
            repository.markReview(ticket.templateId, "主机地址与任务提交时不一致，请恢复地址后查询")
            throw ComfyFailure("主机地址已变化，无法安全查询原任务，请恢复提交时地址")
        }
        return host
    }
    private fun requestValues(template: ComfyTemplate, request: MediaGenerationRequest): MutableMap<SlotKind, List<String>> = mutableMapOf<SlotKind, List<String>>().apply {
        if (template.slots.any { it.kind == SlotKind.PROMPT }) put(SlotKind.PROMPT, listOf(request.prompt))
        request.specs.width?.let { put(SlotKind.WIDTH, listOf(it.toString())) }
        request.specs.height?.let { put(SlotKind.HEIGHT, listOf(it.toString())) }
        if (request.specs.resolution != null && (request.specs.width == null || request.specs.height == null)) {
            val match = Regex("([0-9]+)[xX×]([0-9]+)").matchEntire(request.specs.resolution)
            require(match != null) { "分辨率需要明确宽高，例如 1024x1024" }
            if (request.specs.width == null) put(SlotKind.WIDTH, listOf(match.groupValues[1]))
            if (request.specs.height == null) put(SlotKind.HEIGHT, listOf(match.groupValues[2]))
        }
        request.specs.frames?.let { put(SlotKind.FRAMES, listOf(it.toString())) }
        if (template.slots.any { it.kind == SlotKind.DURATION }) request.specs.durationSeconds?.let { put(SlotKind.DURATION, listOf(it.toString())) }
        if (template.slots.any { it.kind == SlotKind.SEED }) put(SlotKind.SEED, listOf((request.specs.seed ?: kotlin.random.Random.nextLong(0, Long.MAX_VALUE)).toString()))
        else require(request.specs.seed == null) { "模板未声明种子槽位" }
    }
    private fun inputPlaceholders(request: MediaGenerationRequest): Map<SlotKind, List<String>> = request.inputs.groupBy(::slotKind).mapValues { (_, v) -> v.map { "input-placeholder" } }
    private fun slotKind(input: MediaInput): SlotKind = when {
        input.kind == InputKind.VIDEO -> SlotKind.INPUT_VIDEO
        input.kind == InputKind.IMAGE && input.role == InputRole.REFERENCE -> SlotKind.REFERENCE_IMAGES
        input.kind == InputKind.IMAGE && input.role == InputRole.FIRST_FRAME -> SlotKind.FIRST_FRAME
        input.kind == InputKind.IMAGE && input.role == InputRole.LAST_FRAME -> SlotKind.LAST_FRAME
        input.kind == InputKind.AUDIO && input.role in setOf(InputRole.REFERENCE, InputRole.REFERENCE_VOICE) -> SlotKind.REFERENCE_AUDIO
        else -> throw IllegalArgumentException("ComfyUI 模板不支持此输入类型与角色")
    }
    private companion object {
        val hostLocks = ConcurrentHashMap<String, Mutex>()
        val ZERO = Money(BigDecimal.ZERO, "CNY")
    }
}
private fun String.lowercaseHost(): String = toHttpUrl().let { "${it.scheme}://${it.host}:${it.port}${it.encodedPath.trimEnd('/')}" }
internal fun productionPath(path: String): String {
    require(path.isNotEmpty() && !path.startsWith('/') && !path.contains('\\') && !path.contains(':') &&
        path.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") } && path.none { it < ' ' || it == '\u007f' }) { "媒体路径需要工作区相对路径" }
    return path
}
private fun extension(mime: String?, kind: InputKind): String = when (mime) {
    "image/jpeg" -> "jpg"; "image/webp" -> "webp"; "image/png" -> "png"; "audio/wav", "audio/x-wav" -> "wav"; "audio/mpeg" -> "mp3"; "audio/flac" -> "flac"; "audio/ogg" -> "ogg"; "audio/mp4" -> "m4a"
    "video/mp4" -> "mp4"; "video/webm" -> "webm"; "video/quicktime" -> "mov"
    else -> when (kind) { InputKind.IMAGE -> "png"; InputKind.VIDEO -> "mp4"; InputKind.AUDIO -> "wav" }
}
private fun inputMime(path: String, kind: InputKind): String = when (kind) {
    InputKind.VIDEO -> when (path.substringAfterLast('.', "").lowercase()) { "mov" -> "video/quicktime"; "webm" -> "video/webm"; else -> "video/mp4" }
    InputKind.IMAGE -> "image/png"
    InputKind.AUDIO -> "audio/wav"
}

/** 产出声明随远端 ID 持久化，模板修改不会改变旧任务的下载解释。 */
internal data class ComfyRemoteTicket(val promptId: String, val templateId: String, val hostId: String, val endpointFingerprint: String, val clientId: String, val outputs: List<ComfySlot>) {
    fun encode(): String = "comfy1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(buildJsonObject {
        put("prompt", promptId); put("template", templateId); put("host", hostId); put("endpoint", endpointFingerprint); put("client", clientId)
        put("outputs", JsonArray(outputs.map { buildJsonObject { put("node", it.nodeId); put("field", it.input); it.outputKind?.let { kind -> put("kind", kind.name) } } }))
    }.toString().toByteArray())
    companion object {
        fun decode(raw: String): ComfyRemoteTicket {
            require(raw.startsWith("comfy1:") && raw.length <= 64000) { "ComfyUI 任务凭据无效" }
            val json = Json.parseToJsonElement(Base64.getUrlDecoder().decode(raw.removePrefix("comfy1:")).decodeToString()).jsonObject
            fun text(key: String) = json.getValue(key).jsonPrimitive.content
            val id = text("prompt")
            require(id.matches(Regex("[A-Za-z0-9_-]{1,200}"))) { "ComfyUI prompt_id 格式无效" }
            return ComfyRemoteTicket(id, text("template"), text("host"), text("endpoint"), text("client"), json.getValue("outputs").jsonArray.map {
                val value = it.jsonObject
                ComfySlot(value.getValue("node").jsonPrimitive.content, value.getValue("field").jsonPrimitive.content, SlotKind.OUTPUT,
                    outputKind = value["kind"]?.jsonPrimitive?.content?.let(InputKind::valueOf))
            })
        }
    }
}
internal fun queueContains(queue: JsonObject, name: String, promptId: String): Boolean = (queue[name] as? JsonArray).orEmpty().any {
    ((it as? JsonArray)?.getOrNull(1) as? JsonPrimitive)?.contentOrNull == promptId
}
fun parseComfyHistory(history: JsonObject, promptId: String, slots: List<ComfySlot>): RemoteStatus {
    val entry = history[promptId] as? JsonObject ?: return RemoteStatus.Running()
    val status = entry["status"] as? JsonObject
    val messages = status?.get("messages") as? JsonArray
    val failed = messages.orEmpty().mapNotNull { it as? JsonArray }.any { (it.firstOrNull() as? JsonPrimitive)?.contentOrNull in setOf("execution_error", "execution_interrupted") }
    if (failed || (status?.get("status_str") as? JsonPrimitive)?.contentOrNull == "error") return RemoteStatus.Failed("ComfyUI 执行失败，请检查节点、模型、输入与主机资源")
    if ((status?.get("completed") as? JsonPrimitive)?.booleanOrNull != true) return RemoteStatus.Running()
    val outputs = entry["outputs"] as? JsonObject ?: return RemoteStatus.Failed("ComfyUI 任务完成但缺少产物")
    val files = slots.flatMap { slot ->
        val node = outputs[slot.nodeId] as? JsonObject
        val kind = slot.outputKind ?: when (slot.input) { "images" -> InputKind.IMAGE; "gifs", "videos", "video" -> InputKind.VIDEO; "audio" -> InputKind.AUDIO; else -> return@flatMap emptyList() }
        val fields = when (kind) { InputKind.IMAGE -> listOf("images"); InputKind.VIDEO -> listOf("gifs", "videos", "video"); InputKind.AUDIO -> listOf("audio") }
        // 自定义历史字段保留原合同；标准字段按媒体种类选择，避免混入预览图。
        val selected = if (slot.input in setOf("images", "gifs", "videos", "video", "audio")) fields else listOf(slot.input)
        selected.flatMap { field ->
            when (val value = node?.get(field)) {
                is JsonArray -> value.toList()
                is JsonObject -> listOf(value)
                else -> emptyList()
            }
        }.mapNotNull { raw ->
            val value = raw as? JsonObject ?: return@mapNotNull null
            val filename = (value["filename"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
            val folder = (value["subfolder"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            val type = (value["type"] as? JsonPrimitive)?.contentOrNull ?: "output"
            require(safeComfyFile(filename, false) && safeComfyFile(folder, true) && type in setOf("output", "temp", "input")) { "产物路径无效" }
            val view = "http://comfy.invalid/view".toHttpUrl().newBuilder().addQueryParameter("filename", filename).addQueryParameter("subfolder", folder).addQueryParameter("type", type).build()
            RemoteOutput("/view?${view.encodedQuery}", kind)
        }
    }.distinct()
    return if (files.isEmpty()) RemoteStatus.Failed("ComfyUI 任务完成但声明的产出节点没有文件") else RemoteStatus.Succeeded(files)
}

internal fun validateFilledComfySpecs(template: ComfyTemplate, workflow: JsonObject) {
    val sizes = template.channel().capabilities.resolutions
    if (sizes.isEmpty() || sizes.any { !it.matches(Regex("[0-9]+[xX×][0-9]+")) }) return
    fun dimension(kind: SlotKind): Int? = template.slots.firstOrNull { it.kind == kind }?.let { slot ->
        (((workflow[slot.nodeId] as? JsonObject)?.get("inputs") as? JsonObject)?.get(slot.input) as? JsonPrimitive)?.intOrNull
    }
    val width = dimension(SlotKind.WIDTH)
    val height = dimension(SlotKind.HEIGHT)
    require(width != null && height != null && sizes.any {
        val pair = it.split(Regex("[xX×]")); pair[0].toIntOrNull() == width && pair[1].toIntOrNull() == height
    }) { "对齐后的宽高不在模板允许的分辨率内" }
}
