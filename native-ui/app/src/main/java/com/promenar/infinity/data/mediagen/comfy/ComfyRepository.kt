package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import java.security.MessageDigest

/** 界面持有完整仓库；工具只调用需审批的模板定义与只读探测、列表。 */
class ComfyRepository(
    private val storage: ComfyConfigurationStorage,
    private val credentials: ComfyCredentials,
    private val http: ComfyHttp,
    private val clock: () -> Long = System::currentTimeMillis,
) : ComfyTemplateChannelSource {
    // 所有实例共同串行化同一设置键的读改写，包括设置页与工具。
    private companion object { val mutation = Mutex() }
    fun observeHosts(): Flow<List<ComfyHost>> = storage.observe().map { it.hosts }.distinctUntilChanged()
    fun observeTemplates(): Flow<List<ComfyTemplateState>> = storage.observe().map { it.templates }.distinctUntilChanged()
    override fun observeTemplateChannels(): Flow<List<MediaChannel>> = observeTemplates().map { list -> list.map { it.template.channel() } }.distinctUntilChanged()
    suspend fun hosts(): List<ComfyHost> = storage.read().hosts
    suspend fun hasHostCredential(hostId: String): Boolean = credentials.contains(hostId)
    suspend fun templates(): List<ComfyTemplateState> = storage.read().templates
    suspend fun host(id: String): ComfyHost = hosts().firstOrNull { it.id == id } ?: throw ComfyFailure("ComfyUI 主机不存在，请用户配置主机")
    suspend fun templateByChannel(channelId: String): ComfyTemplateState = templates().firstOrNull { it.template.channelId == channelId }
        ?: throw ComfyFailure("ComfyUI 模板通道不存在")

    /** credential 为空保留原凭据；NONE 会清除密钥。主机删除会同时删除其模板。 */
    suspend fun saveHost(host: ComfyHost, credential: ComfyCredential? = null) = mutation.withLock {
        val old = storage.read()
        require(old.hosts.any { it.id == host.id } || old.hosts.size < 100) { "最多配置 100 台主机" }
        if (credential != null) credentials.put(host, credential)
        val previous = old.hosts.firstOrNull { it.id == host.id }
        val changed = previous != host || credential != null
        val states = old.templates.map { state ->
            if (changed && state.template.hostId == host.id) state.copy(preflight = PreflightResult(PreflightStatus.REVIEW, uncertain = listOf("主机配置已变化，请重新预检"))) else state
        }
        storage.write(old.copy(hosts = old.hosts.filterNot { it.id == host.id } + host, templates = states))
        if (host.authKind == ComfyAuthKind.NONE) credentials.remove(host.id)
    }
    suspend fun removeHost(hostId: String) = mutation.withLock {
        val old = storage.read()
        storage.write(old.copy(hosts = old.hosts.filterNot { it.id == hostId }, templates = old.templates.filterNot { it.template.hostId == hostId }))
        credentials.remove(hostId)
    }
    suspend fun removeTemplateByUser(templateId: String) = mutation.withLock {
        val old = storage.read()
        storage.write(old.copy(templates = old.templates.filterNot { it.template.id == templateId }))
    }
    suspend fun defineTemplateByAgent(template: ComfyTemplate): ComfyTemplateState {
        validateDefinition(template)
        val before = storage.read()
        val previous = before.templates.firstOrNull { it.template.id == template.id }
        val host = before.hosts.firstOrNull { it.id == template.hostId } ?: throw ComfyFailure("主机不存在，请用户配置")
        val normalized = template.copy(capabilities = template.channel().capabilities)
        val locked = previous?.template?.updateByAgent(normalized) ?: normalized.copy(locked = true)
        val checked = inspect(locked, host, previous?.passedFingerprint)
        return mutation.withLock {
            val current = storage.read()
            check(current.templates.firstOrNull { it.template.id == template.id } == previous && current.hosts.firstOrNull { it.id == host.id } == host) { "配置已变化，请重新读取模板" }
            require(previous != null || current.templates.size < 500) { "最多配置 500 个模板" }
            storage.write(current.copy(templates = current.templates.filterNot { it.template.id == template.id } + checked))
            checked
        }
    }
    suspend fun preflight(templateId: String): ComfyTemplateState {
        val before = storage.read()
        val state = before.templates.firstOrNull { it.template.id == templateId } ?: throw ComfyFailure("模板不存在")
        val host = before.hosts.firstOrNull { it.id == state.template.hostId } ?: throw ComfyFailure("主机不存在")
        val next = inspect(state.template, host, state.passedFingerprint)
        return mutation.withLock {
            val current = storage.read()
            check(current.templates.firstOrNull { it.template.id == templateId } == state && current.hosts.firstOrNull { it.id == host.id } == host) { "配置已变化，请重新预检" }
            storage.write(current.copy(templates = current.templates.map { if (it.template.id == templateId) next else it }))
            next
        }
    }
    suspend fun markReview(templateId: String, reason: String) = mutation.withLock {
        val current = storage.read()
        storage.write(current.copy(templates = current.templates.map { if (it.template.id == templateId) it.copy(
            preflight = PreflightResult(PreflightStatus.REVIEW, uncertain = listOf(reason))) else it }))
    }
    private suspend fun inspect(template: ComfyTemplate, host: ComfyHost, passed: String?): ComfyTemplateState = try {
        val info = http.objectInfoFor(host, template.workflow)
        val result = preflightComfy(template, info)
        val reviewed = if (passed != null && result.status != PreflightStatus.PASS) result.copy(status = PreflightStatus.REVIEW,
            uncertain = result.uncertain + "主机节点或模型可能已变化，请复核缺项") else result
        val fingerprint = if (result.status == PreflightStatus.PASS) fingerprint(info.toString()) else passed
        ComfyTemplateState(template, reviewed, fingerprint, clock())
    } catch (e: CancellationException) { throw e }
    catch (_: Exception) { ComfyTemplateState(template, PreflightResult(PreflightStatus.REVIEW, uncertain = listOf("无法读取主机节点信息，请检查连接与鉴权后重新预检")), passed, clock()) }

    private fun validateDefinition(template: ComfyTemplate) {
        require(template.workflow.isNotEmpty() && template.workflow.size <= 2000 && template.workflow.toString().toByteArray().size <= 1024 * 1024) { "工作流需包含 1～2000 个节点且不超过 1 MiB" }
        // ComfyUI 拒绝非节点条目；部分模型中转会给自由对象补占位键，需原样告知智能体移除。
        template.workflow.forEach { (id, node) ->
            val type = ((node as? JsonObject)?.get("class_type") as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (type.isNullOrBlank() || (node as JsonObject)["inputs"] !is JsonObject)
                throw ComfyFailure("workflow 条目 ${id.take(40)} 不是 API Format 节点（需要 class_type 与 inputs 对象），请移除该键或补全后重试")
        }
        val caps = template.channel().capabilities
        require(template.slots.size in 1..200 && template.models.size <= 2000) { "模板最多包含 200 个槽位和 2000 个模型声明" }
        require(template.slots.count { it.kind == SlotKind.OUTPUT } in 1..32) { "模板需要 1～32 个产出槽位" }
        require(template.slots.any { it.kind == SlotKind.PROMPT }) { "模板必须声明提示词槽位" }
        template.slots.filter { it.kind == SlotKind.OUTPUT }.forEach {
            require(it.outputKind != null || it.input in setOf("images", "gifs", "videos", "audio")) { "自定义产出字段需要声明 outputKind" }
        }
        if (GenerationMode.REFERENCE in caps.modes) require(caps.maxImages > 0 || caps.maxAudio > 0) { "参考模式需要参考槽位" }
        if (GenerationMode.FIRST_FRAME in caps.modes || GenerationMode.FIRST_LAST_FRAME in caps.modes) require(template.slots.any { it.kind == SlotKind.FIRST_FRAME }) { "首帧模式需要首帧槽位" }
        if (GenerationMode.FIRST_LAST_FRAME in caps.modes) require(template.slots.any { it.kind == SlotKind.LAST_FRAME }) { "首尾帧模式需要尾帧槽位" }
        require(caps.referenceOrder.size <= caps.maxImages && caps.referenceOrder.all { it.isNotBlank() }) { "参考位顺序超过容量或含空名称" }
    }
    /** 连接检测只读轻量的 /system_stats；完整节点清单留给预检与探测。 */
    suspend fun checkConnection(hostId: String) {
        val stats = http.get(host(hostId), "/system_stats")
        if (stats["system"] !is JsonObject) throw ComfyFailure("主机响应不是 ComfyUI 系统信息")
    }
    suspend fun probe(hostId: String, nodeType: String? = null): JsonObject {
        val host = host(hostId)
        require(nodeType == null || (nodeType.isNotBlank() && nodeType.length <= 200 && nodeType.none { it < ' ' || it == '\u007f' })) { "node_type 格式无效" }
        val encodedType = nodeType?.let { java.net.URLEncoder.encode(it, Charsets.UTF_8.name()).replace("+", "%20") }
        val info = http.get(host, if (encodedType == null) "/object_info" else "/object_info/$encodedType")
        return summarizeComfyHost(info, nodeType)
    }
}
internal fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

/** 枚举选项是模型发现候选；只有模板 models 声明参与模型预检。 */
fun summarizeComfyHost(info: JsonObject, filter: String? = null): JsonObject {
    val selected = info.entries.filter { filter == null || it.key == filter }.sortedBy { it.key }
    var remaining = 16000
    val nodes = mutableListOf<JsonElement>()
    for ((type, raw) in selected.take(60)) {
        val definition = raw as? JsonObject ?: continue
        val fields = definition["input"] as? JsonObject
        val enums = buildJsonObject {
            listOf("required", "optional").forEach { section ->
                (fields?.get(section) as? JsonObject).orEmpty().entries.take(40).forEach { (name, descriptor) ->
                    val options = (descriptor as? JsonArray)?.firstOrNull() as? JsonArray ?: return@forEach
                    put(name.take(120), buildJsonObject {
                        put("count", options.size)
                        put("values", JsonArray(options.take(12).map { JsonPrimitive((it as? JsonPrimitive)?.contentOrNull.orEmpty().take(120)) }))
                        put("truncated", options.size > 12)
                    })
                }
            }
        }
        val node = buildJsonObject {
            put("type", type.take(200)); put("output_node", (definition["output_node"] as? JsonPrimitive)?.booleanOrNull ?: false)
            put("file_or_enum_options", enums)
            if (filter != null) {
                val schema = trimComfySchema(fields ?: JsonObject(emptyMap()))
                put("input_schema", schema); put("schema_truncated", schema != fields)
                put("outputs", trimComfySchema(definition["output"] ?: JsonArray(emptyList())))
            }
        }
        remaining -= node.toString().length
        if (remaining < 0) break
        nodes += node
    }
    return buildJsonObject { put("node_count", info.size); put("matched_count", selected.size); put("nodes", JsonArray(nodes)); put("truncated", nodes.size < selected.size) }
}

private fun trimComfySchema(element: JsonElement, depth: Int = 0): JsonElement {
    if (depth >= 8) return JsonPrimitive("…")
    return when (element) {
        is JsonObject -> JsonObject(element.entries.take(24).associate { it.key.take(160) to trimComfySchema(it.value, depth + 1) })
        is JsonArray -> JsonArray(element.take(12).map { trimComfySchema(it, depth + 1) })
        is JsonPrimitive -> if (element.isString) JsonPrimitive(element.content.take(160)) else element
    }
}
