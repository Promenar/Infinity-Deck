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

/** 界面持有完整仓库；工作流/提交器变更与模板变更均需经过审批或明确用户操作。 */
class ComfyRepository(
    private val storage: ComfyConfigurationStorage,
    private val credentials: ComfyCredentials,
    private val http: ComfyHttp,
    private val clock: () -> Long = System::currentTimeMillis,
    val submitterFiles: ComfySubmitterFileStore = InMemoryComfySubmitterFileStore(),
) : ComfyTemplateChannelSource {
    // 所有实例共同串行化同一设置键的读改写，包括设置页与工具。
    private companion object { val mutation = Mutex() }
    fun observeHosts(): Flow<List<ComfyHost>> = storage.observe().map { it.hosts }.distinctUntilChanged()
    fun observeTemplates(): Flow<List<ComfyTemplateState>> = storage.observe().map { it.templates }.distinctUntilChanged()
    fun observeSubmitters(): Flow<List<ComfySubmitterScript>> = storage.observe().map { it.submitters }.distinctUntilChanged()
    fun observeWorkflows(): Flow<List<ComfyWorkflowState>> = storage.observe().map { it.workflows }.distinctUntilChanged()
    fun observeEffectiveWorkflows(): Flow<List<ComfyWorkflowState>> = storage.observe().map { config ->
        mergeEffectiveWorkflows(config)
    }.distinctUntilChanged()

    override fun observeTemplateChannels(): Flow<List<MediaChannel>> = storage.observe().map { config ->
        val workflowChannels = config.workflows.map { it.workflow.toMediaChannel() }
        val workflowIds = config.workflows.mapTo(mutableSetOf()) { it.workflow.channelId }
        val legacyChannels = config.templates.map { it.template.channel() }.filter { it.id !in workflowIds }
        workflowChannels + legacyChannels
    }.distinctUntilChanged()

    suspend fun hosts(): List<ComfyHost> = storage.read().hosts
    suspend fun hasHostCredential(hostId: String): Boolean = credentials.contains(hostId)
    suspend fun templates(): List<ComfyTemplateState> = storage.read().templates
    suspend fun submitters(hostId: String? = null): List<ComfySubmitterScript> = storage.read().submitters.let { all ->
        if (hostId == null) all else all.filter { it.hostId == hostId }
    }
    suspend fun submitter(hostId: String, scriptId: String): ComfySubmitterScript? =
        storage.read().submitters.firstOrNull { it.hostId == hostId && it.scriptId == scriptId }

    suspend fun workflowStates(hostId: String? = null): List<ComfyWorkflowState> = storage.read().workflows.let { all ->
        if (hostId == null) all else all.filter { it.workflow.hostId == hostId }
    }

    suspend fun effectiveWorkflowStates(hostId: String? = null): List<ComfyWorkflowState> =
        mergeEffectiveWorkflows(storage.read()).let { all ->
            if (hostId == null) all else all.filter { it.workflow.hostId == hostId }
        }

    suspend fun workflowState(workflowId: String): ComfyWorkflowState =
        effectiveWorkflowStates().firstOrNull { it.workflow.workflowId == workflowId }
            ?: throw ComfyFailure("ComfyUI 工作流不存在：$workflowId")

    suspend fun workflowStateOrNull(workflowId: String): ComfyWorkflowState? =
        effectiveWorkflowStates().firstOrNull { it.workflow.workflowId == workflowId }

    suspend fun host(id: String): ComfyHost = hosts().firstOrNull { it.id == id } ?: throw ComfyFailure("ComfyUI 主机不存在，请用户配置主机")
    suspend fun templateByChannel(channelId: String): ComfyTemplateState = templates().firstOrNull { it.template.channelId == channelId }
        ?: throw ComfyFailure("ComfyUI 工作流通道不存在")

    /** credential 为空保留原凭据；NONE 会清除密钥。主机删除会同时删除其工作流与提交器。 */
    suspend fun saveHost(host: ComfyHost, credential: ComfyCredential? = null) = mutation.withLock {
        val old = storage.read()
        require(old.hosts.any { it.id == host.id } || old.hosts.size < 100) { "最多配置 100 台主机" }
        if (credential != null) credentials.put(host, credential)
        val previous = old.hosts.firstOrNull { it.id == host.id }
        val changed = previous != host || credential != null
        val states = old.templates.map { state ->
            if (changed && state.template.hostId == host.id) state.copy(preflight = PreflightResult(PreflightStatus.REVIEW, uncertain = listOf("主机配置已变化，请重新预检"))) else state
        }
        val workflows = old.workflows.map { state ->
            if (changed && state.workflow.hostId == host.id) state.copy(preflight = PreflightResult(PreflightStatus.REVIEW, uncertain = listOf("主机配置已变化，请重新预检"))) else state
        }
        storage.write(old.copy(hosts = old.hosts.filterNot { it.id == host.id } + host, templates = states, workflows = workflows))
        if (host.authKind == ComfyAuthKind.NONE) credentials.remove(host.id)
    }
    suspend fun removeHost(hostId: String) {
        mutation.withLock {
            val old = storage.read()
            storage.write(
                old.copy(
                    hosts = old.hosts.filterNot { it.id == hostId },
                    templates = old.templates.filterNot { it.template.hostId == hostId },
                    workflows = old.workflows.filterNot { it.workflow.hostId == hostId },
                    submitters = old.submitters.filterNot { it.hostId == hostId },
                ),
            )
            credentials.remove(hostId)
        }
        submitterFiles.deleteHost(hostId)
    }

    suspend fun saveSubmitter(
        hostId: String,
        scriptId: String,
        fileName: String,
        displayName: String = fileName,
        description: String = "",
        sourceKind: SubmitterSourceKind = SubmitterSourceKind.AGENT_AUTHORED,
        protocol: SubmitterInvocationProtocol = SubmitterInvocationProtocol.PAYLOAD_JSON,
        sourceText: String,
        supportFiles: Map<String, ByteArray> = emptyMap(),
    ): ComfySubmitterScript {
        host(hostId)
        val sha256 = submitterFiles.saveFiles(hostId, scriptId, fileName, sourceText, supportFiles)
        val record = ComfySubmitterScript(
            scriptId = scriptId,
            hostId = hostId,
            fileName = fileName,
            displayName = displayName,
            description = description,
            sourceKind = sourceKind,
            protocol = protocol,
            sha256 = sha256,
            supportFiles = supportFiles.keys.sorted(),
            lastDryRunStatus = SubmitterDryRunStatus.UNTESTED,
            lastDryRunMessage = "",
            lastDryRunLatencyMs = null,
            lastDryRunAt = null,
            updatedAt = clock(),
        )
        return mutation.withLock {
            val old = storage.read()
            require(old.hosts.any { it.id == hostId }) { "主机不存在：$hostId" }
            val existing = old.submitters.firstOrNull { it.hostId == hostId && it.scriptId == scriptId }
            require(existing != null || old.submitters.size < 200) { "最多配置 200 个提交器脚本" }
            val next = old.submitters.filterNot { it.hostId == hostId && it.scriptId == scriptId } + record
            storage.write(old.copy(submitters = next))
            record
        }
    }

    suspend fun updateSubmitterDryRun(
        hostId: String,
        scriptId: String,
        status: SubmitterDryRunStatus,
        message: String,
        latencyMs: Long?,
    ): ComfySubmitterScript = mutation.withLock {
        val old = storage.read()
        val existing = old.submitters.firstOrNull { it.hostId == hostId && it.scriptId == scriptId }
            ?: throw ComfyFailure("提交器脚本不存在：$scriptId")
        val updated = existing.copy(
            lastDryRunStatus = status,
            lastDryRunMessage = message.take(600),
            lastDryRunLatencyMs = latencyMs,
            lastDryRunAt = clock(),
        )
        storage.write(
            old.copy(
                submitters = old.submitters.map {
                    if (it.hostId == hostId && it.scriptId == scriptId) updated else it
                },
            ),
        )
        updated
    }

    suspend fun deleteSubmitter(hostId: String, scriptId: String) {
        mutation.withLock {
            val old = storage.read()
            val referencing = old.workflows.filter {
                it.workflow.hostId == hostId && it.workflow.submitterScriptId == scriptId
            }
            if (referencing.isNotEmpty()) {
                val names = referencing.joinToString("、") { "${it.workflow.name}(${it.workflow.workflowId})" }
                throw ComfyFailure("无法删除提交器 $scriptId：以下工作流仍在引用：$names")
            }
            storage.write(
                old.copy(submitters = old.submitters.filterNot { it.hostId == hostId && it.scriptId == scriptId }),
            )
        }
        submitterFiles.deleteScript(hostId, scriptId)
    }

    suspend fun saveWorkflow(workflow: ComfyWorkflow, runPreflight: Boolean = true): ComfyWorkflowState {
        val snapshot = storage.read()
        val host = snapshot.hosts.firstOrNull { it.id == workflow.hostId }
            ?: throw ComfyFailure("主机不存在，请用户先配置主机：${workflow.hostId}")
        if (workflow.submitterScriptId != null) {
            val submitter = snapshot.submitters.firstOrNull {
                it.hostId == workflow.hostId && it.scriptId == workflow.submitterScriptId
            } ?: throw ComfyFailure("提交器脚本不存在：${workflow.submitterScriptId}")
            check(submitter.hostId == workflow.hostId)
        }
        val stamped = workflow.copy(updatedAt = clock())
        val state = if (runPreflight) inspectWorkflow(stamped, host, snapshot.submitters)
        else ComfyWorkflowState(stamped, PreflightResult(PreflightStatus.REVIEW, uncertain = listOf("尚未预检")), clock())
        return mutation.withLock {
            val current = storage.read()
            require(current.workflows.any { it.workflow.workflowId == stamped.workflowId } || current.workflows.size < 500) {
                "最多配置 500 个工作流"
            }
            storage.write(
                current.copy(
                    workflows = current.workflows.filterNot { it.workflow.workflowId == stamped.workflowId } + state,
                ),
            )
            state
        }
    }

    suspend fun preflightWorkflow(workflowId: String): ComfyWorkflowState {
        val snapshot = storage.read()
        val existing = snapshot.workflows.firstOrNull { it.workflow.workflowId == workflowId }
        if (existing == null) {
            // 若仍是旧 ComfyTemplate，转调旧 preflight 并投影返回。
            if (snapshot.templates.any { it.template.id == workflowId }) {
                val tState = preflight(workflowId)
                return ComfyWorkflowState(ComfyWorkflow.fromLegacyTemplate(tState.template), tState.preflight, tState.checkedAt)
            }
            throw ComfyFailure("工作流不存在：$workflowId")
        }
        val host = snapshot.hosts.firstOrNull { it.id == existing.workflow.hostId }
            ?: throw ComfyFailure("主机不存在：${existing.workflow.hostId}")
        val next = inspectWorkflow(existing.workflow, host, snapshot.submitters)
        return mutation.withLock {
            val current = storage.read()
            storage.write(
                current.copy(
                    workflows = current.workflows.map { if (it.workflow.workflowId == workflowId) next else it },
                ),
            )
            next
        }
    }

    suspend fun deleteWorkflow(workflowId: String) = mutation.withLock {
        val old = storage.read()
        storage.write(
            old.copy(
                workflows = old.workflows.filterNot { it.workflow.workflowId == workflowId },
                templates = old.templates.filterNot { it.template.id == workflowId },
            ),
        )
    }

    private fun mergeEffectiveWorkflows(config: ComfyConfiguration): List<ComfyWorkflowState> {
        val workflowIds = config.workflows.mapTo(mutableSetOf()) { it.workflow.workflowId }
        val projected = config.templates
            .filter { it.template.id !in workflowIds }
            .map { state ->
                ComfyWorkflowState(
                    workflow = ComfyWorkflow.fromLegacyTemplate(state.template),
                    preflight = state.preflight,
                    checkedAt = state.checkedAt,
                )
            }
        return config.workflows + projected
    }

    private suspend fun inspectWorkflow(
        workflow: ComfyWorkflow,
        host: ComfyHost,
        submitters: List<ComfySubmitterScript>,
    ): ComfyWorkflowState {
        val missing = mutableListOf<String>()
        val uncertain = mutableListOf<String>()
        val submitter = workflow.submitterScriptId?.let { id ->
            submitters.firstOrNull { it.hostId == workflow.hostId && it.scriptId == id }
        }
        if (workflow.submitterScriptId != null) {
            if (submitter == null) {
                missing += "缺少提交器脚本 ${workflow.submitterScriptId}"
            } else {
                when (submitter.lastDryRunStatus) {
                    SubmitterDryRunStatus.PASSED -> Unit
                    SubmitterDryRunStatus.FAILED -> missing += "提交器干跑失败：${submitter.lastDryRunMessage.take(120)}"
                    SubmitterDryRunStatus.UNTESTED -> uncertain += "提交器 ${submitter.fileName} 尚未完成干跑自检"
                }
            }
        }
        val raw = workflow.rawWorkflowJson
        if (raw != null) {
            try {
                val info = http.objectInfoFor(host, raw)
                raw.forEach { (id, elem) ->
                    val node = elem as? JsonObject
                    val classType = (node?.get("class_type") as? JsonPrimitive)?.contentOrNull
                    if (classType.isNullOrBlank()) {
                        missing += "节点 $id 缺少 class_type"
                    } else if (info[classType] == null) {
                        missing += "节点 $classType"
                    }
                }
                workflow.outputNodeIds.forEach { outId ->
                    val node = raw[outId] as? JsonObject
                    if (node == null) missing += "产出节点 $outId"
                    else {
                        val ct = (node["class_type"] as? JsonPrimitive)?.contentOrNull
                        val outputNode = (ct?.let { info[it] as? JsonObject }?.get("output_node") as? JsonPrimitive)?.booleanOrNull
                        if (outputNode == false) missing += "节点 $outId 不是产出节点"
                    }
                }
                workflow.fields.forEach { field ->
                    val nodeId = field.nodeId
                    val inputKey = field.inputKey
                    if (nodeId != null) {
                        val node = raw[nodeId] as? JsonObject
                        if (node == null) missing += "字段 ${field.key} 引用节点 $nodeId 不存在"
                        else if (inputKey != null && field.dynamicInputPrefix == null && (node["inputs"] as? JsonObject)?.containsKey(inputKey) != true) {
                            uncertain += "字段 ${field.key} 输入 $nodeId.$inputKey 未在原始图 inputs 中显式列出"
                        }
                    }
                    field.effectiveUploadTargets().forEach { target ->
                        val node = raw[target.nodeId] as? JsonObject
                        if (node == null) missing += "字段 ${field.key} 上传节点 ${target.nodeId} 不存在"
                        else if ((node["inputs"] as? JsonObject)?.containsKey(target.input) != true) {
                            missing += "上传输入 ${target.nodeId}.${target.input}"
                        }
                    }
                    if (field.valueType == FieldValueType.FILE_LIST &&
                        field.dynamicInputPrefix == null &&
                        field.effectiveUploadTargets().size <= 1
                    ) {
                        uncertain += "多文件字段 ${field.key} 未声明多目标 uploadTargets 或 dynamicInputPrefix（传入多项时将被拒绝）"
                    }
                }
                validateComfyDynamicInputs(raw, info, missing, uncertain)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                uncertain += "无法读取主机节点信息，请检查连接与鉴权后重新预检"
            }
        } else {
            try {
                checkConnection(host.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                uncertain += "主机当前离线或鉴权失败，请检查主机连接"
            }
        }
        val status = when {
            missing.isNotEmpty() -> PreflightStatus.MISSING
            uncertain.isNotEmpty() -> PreflightStatus.REVIEW
            else -> PreflightStatus.PASS
        }
        return ComfyWorkflowState(workflow, PreflightResult(status, missing.distinct(), uncertain.distinct()), clock())
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
        require(template.role == TemplateRole.DERIVE || template.slots.any { it.kind == SlotKind.PROMPT }) { "slots.PROMPT: GENERATE 必须声明提示词槽位" }
        template.slots.filter { it.kind == SlotKind.OUTPUT }.forEach {
            require(it.outputKind != null || it.input in setOf("images", "gifs", "videos", "video", "audio")) { "自定义产出字段需要声明 outputKind" }
        }
        if (GenerationMode.REFERENCE in caps.modes) require(caps.maxImages > 0 || caps.maxAudio > 0 || caps.maxVideos > 0) { "参考模式需要参考槽位" }
        if (GenerationMode.FIRST_FRAME in caps.modes || GenerationMode.FIRST_LAST_FRAME in caps.modes) require(template.slots.any { it.kind == SlotKind.FIRST_FRAME }) { "首帧模式需要首帧槽位" }
        if (GenerationMode.LAST_FRAME in caps.modes || GenerationMode.FIRST_LAST_FRAME in caps.modes) require(template.slots.any { it.kind == SlotKind.LAST_FRAME }) { "尾帧或首尾帧模式需要尾帧槽位" }
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
        val info = if (encodedType == null) {
            try {
                http.getBounded(host, "/object_info", minOf(host.timeoutSeconds, 20))
            } catch (e: ComfyFailure) {
                if (e.retryable) throw ComfyFailure("${e.message}；全量节点清单过大或中转较慢，请改传 node_type 或 node_types 分批探测", true)
                throw e
            }
        } else {
            http.get(host, "/object_info/$encodedType")
        }
        return summarizeComfyHost(info, nodeType)
    }

    suspend fun probeNodes(hostId: String, nodeTypes: List<String>): JsonObject {
        val host = host(hostId)
        require(nodeTypes.size in 1..30) { "node_types 需要 1～30 个节点类型" }
        val valid = nodeTypes.map { type ->
            require(type.isNotBlank() && type.length <= 200 && type.none { it < ' ' || it == '\u007f' }) { "node_types 包含无效节点类型" }
            type
        }.distinct()
        val info = http.objectInfoForTypes(host, valid)
        val summary = summarizeComfyHostByFilters(info, valid.toSet())
        val missingTypes = valid.filter { it !in info }
        return JsonObject(summary + ("missing_types" to JsonArray(missingTypes.map(::JsonPrimitive))))
    }
}
internal fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }

/** 枚举选项是模型发现候选；只有模板 models 声明参与模型预检。 */
fun summarizeComfyHost(info: JsonObject, filter: String? = null): JsonObject =
    summarizeComfyHostByFilters(info, filter?.let(::setOf))

private fun summarizeComfyHostByFilters(info: JsonObject, filters: Set<String>?): JsonObject {
    val selected = info.entries.filter { filters == null || it.key in filters }.sortedBy { it.key }
    var remaining = 16000
    val nodes = mutableListOf<JsonElement>()
    for ((type, raw) in selected.take(60)) {
        val definition = raw as? JsonObject ?: continue
        val fields = definition["input"] as? JsonObject
        val enums = buildJsonObject {
            listOf("required", "optional").forEach { section ->
                (fields?.get(section) as? JsonObject).orEmpty().entries.take(40).forEach { (name, descriptor) ->
                    val options = extractComfyComboOptions(descriptor as? JsonArray) ?: return@forEach
                    put(name.take(120), buildJsonObject {
                        put("count", options.size)
                        put("values", JsonArray(options.take(12).map { JsonPrimitive(it.take(120)) }))
                        put("truncated", options.size > 12)
                    })
                }
            }
        }
        val node = buildJsonObject {
            put("type", type.take(200)); put("output_node", (definition["output_node"] as? JsonPrimitive)?.booleanOrNull ?: false)
            put("file_or_enum_options", enums)
            if (filters != null) {
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
