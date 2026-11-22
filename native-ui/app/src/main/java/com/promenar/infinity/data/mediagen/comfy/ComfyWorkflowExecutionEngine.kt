package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ComfyWorkflowExecutionRequest(
    val sessionId: String,
    val workflowId: String,
    val contentInputs: Map<String, JsonElement> = emptyMap(),
    val fixedOverrides: Map<String, JsonElement> = emptyMap(),
    val stage: String = "single",
    val chainId: String? = null,
    val parentNodeId: String? = null,
    val retryNodeId: String? = null,
    val branchPolicy: StatefulBranchPolicy? = null,
    val outputDir: String? = null,
    val shotRef: String? = null,
    val onProgress: suspend (Float?, String) -> Unit = { _, _ -> },
)

data class ComfyWorkflowExecutionResult(
    val workflow: ComfyWorkflow,
    val host: ComfyHost,
    val savedMedia: List<MediaRef>,
    val promptId: String? = null,
    val summary: String,
    val appliedFixedDiffs: List<WorkflowFixedOverrideDiff> = emptyList(),
    val chainRecord: StatefulChainRecord? = null,
    val appendedNode: StatefulChainNode? = null,
    val invalidatedNodeIds: List<String> = emptyList(),
)

/**
 * ComfyUI 兼容层统一执行协调器：
 * - 被 `comfy_run_workflow` Agent 工具与制作面板快捷操作共同调用；
 * - 统一完成字段验证、工作区媒体分拣、本机回环认证代理启动、内置 Python 3.14 / 通用 JSON 执行、产物回写与有状态链 DAG 更新。
 */
class ComfyWorkflowExecutionEngine(
    private val repository: ComfyRepository,
    private val chainStore: StatefulChainStore,
    private val credentials: ComfyCredentials,
    private val http: ComfyHttp,
    private val proxyClient: OkHttpClient,
    private val runner: ComfySubmitterRunner,
    private val resolver: WorkspaceMediaResolver,
    private val store: WorkspaceMediaStore,
    private val scratchRoot: File,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun execute(request: ComfyWorkflowExecutionRequest): ComfyWorkflowExecutionResult = withContext(Dispatchers.IO) {
        require(request.sessionId.isNotBlank()) { "执行工作流需要有效的 sessionId" }
        val wfState = repository.workflowState(request.workflowId)
        val workflow = wfState.workflow
        val host = repository.host(workflow.hostId)

        // 校验入参字段归属、类型与固定基线差异（绝不改写原基线）。
        val fixedDiffs = workflow.detectFixedOverrides(request.fixedOverrides)
        val mergedInputs = resolveAndValidateFields(workflow, request.contentInputs, request.fixedOverrides)

        // 解析有状态链参数与只读继承锁定上下文。
        val isChain = workflow.executionMode == WorkflowExecutionMode.STATEFUL_CHAIN
        var existingChain: StatefulChainRecord? = null
        var resolvedStage = request.stage
        var resolvedParentId = request.parentNodeId
        var resolvedChainId = request.chainId
        var inheritedState = JsonObject(emptyMap())

        if (isChain) {
            val retryId = request.retryNodeId
            if (!retryId.isNullOrBlank()) {
                val targetChainId = requireNotNull(request.chainId) { "指定 retry_node_id 时必须提供 chain_id" }
                existingChain = chainStore.get(request.sessionId, targetChainId)
                    ?: throw ComfyFailure("有状态链不存在：$targetChainId")
                val targetNode = existingChain.node(retryId)
                    ?: throw ComfyFailure("重试目标节点不存在：$retryId")
                if (targetNode.parentNodeId == null) {
                    resolvedStage = "first"
                    resolvedParentId = null
                } else {
                    resolvedStage = "next"
                    resolvedParentId = targetNode.parentNodeId
                }
            } else {
                if (resolvedStage == "single") {
                    resolvedStage = if (resolvedChainId.isNullOrBlank()) "first" else "next"
                }
                if (resolvedStage == "next") {
                    val targetChainId = requireNotNull(resolvedChainId) { "续接阶段 stage=next 需要提供 chain_id" }
                    existingChain = chainStore.get(request.sessionId, targetChainId)
                        ?: throw ComfyFailure("有状态链不存在：$targetChainId")
                    resolvedParentId = resolvedParentId ?: existingChain.activeTipNodeId
                } else if (!resolvedChainId.isNullOrBlank()) {
                    existingChain = chainStore.get(request.sessionId, resolvedChainId)
                }
            }

            if (resolvedStage == "next") {
                val chain = requireNotNull(existingChain)
                val parentId = requireNotNull(resolvedParentId)
                inheritedState = chain.inheritedContextForParent(parentId)
                // 防参数漂移：对于被锁定的上下文键，宿主直接锁定继承自父节点的值（除非此次已通过审批显式 fixedOverrides 覆盖）。
                val lockedKeys = workflow.chainSpec?.lockedInheritedKeys.orEmpty()
                lockedKeys.forEach { key ->
                    if (key !in request.fixedOverrides) {
                        inheritedState[key]?.let { inheritedVal ->
                            if (key !in request.contentInputs) {
                                mergedInputs[key] = inheritedVal
                            }
                        }
                    }
                }
            } else if (resolvedChainId.isNullOrBlank()) {
                resolvedChainId = "chain_${UUID.randomUUID().toString().take(8)}"
            }
        }

        val stagingDir = File(scratchRoot, "stage_${UUID.randomUUID().toString().take(12)}")
        val proxyTempDir = File(scratchRoot, "proxy_${UUID.randomUUID().toString().take(12)}")
        stagingDir.mkdirs()
        try {
            val stagedFiles = stageWorkspaceInputs(workflow, mergedInputs, request.sessionId, stagingDir)
            var previousWorkflowFile: File? = null
            val snapshotObj = inheritedState["workflow_snapshot"] as? JsonObject
            if (snapshotObj != null) {
                previousWorkflowFile = File(stagingDir, "previous_workflow.json").apply {
                    writeText(snapshotObj.toString(), Charsets.UTF_8)
                }
            }

            val branchPolicyEffective = request.branchPolicy
                ?: workflow.chainSpec?.defaultBranchPolicy
                ?: StatefulBranchPolicy.ISOLATED_DIR
            val branchId = if (resolvedParentId == null) "main"
            else "b_${resolvedParentId.takeLast(6)}_${UUID.randomUUID().toString().take(4)}"

            val submitterId = workflow.submitterScriptId
            val customOutputDir = request.outputDir?.takeIf { it.isNotBlank() }?.let(::productionPath)

            if (submitterId != null) {
                val submitter = repository.submitter(host.id, submitterId)
                    ?: throw ComfyFailure("工作流绑定的提交器脚本不存在：$submitterId")
                var cleanContinuation = JsonObject(emptyMap())
                val execResult = ComfyLocalAuthProxy.open(host, credentials, proxyClient, proxyTempDir).use { proxy ->
                    runner.execute(
                        SubmitterRunRequest(
                            submitter = submitter,
                            workflow = workflow,
                            proxyBaseUrl = proxy.baseUrl,
                            resolvedInputs = mergedInputs,
                            stagedFilesByPath = stagedFiles,
                            stage = resolvedStage,
                            branchId = branchId,
                            inheritedState = inheritedState,
                            previousWorkflowFile = previousWorkflowFile,
                            dryRun = false,
                            timeoutSeconds = maxOf(host.timeoutSeconds, 600),
                            onProgress = request.onProgress,
                        ),
                    )
                }
                try {
                    cleanContinuation = JsonObject(execResult.continuationState.filterKeys { it != "__run_dir_cleanup" })
                    val savedRefs = execResult.outputs.mapIndexed { idx, artifact ->
                        val ext = artifact.file.extension.lowercase().ifBlank { "bin" }
                        val targetPath = customOutputDir?.let { dir ->
                            val targetRel = "$dir/comfy-${UUID.randomUUID().toString().take(10)}-${idx + 1}.$ext"
                            try {
                                resolver.productionPath(request.sessionId, targetRel)
                            } catch (_: UnsupportedOperationException) {
                                targetRel
                            }
                        }
                        artifact.file.inputStream().use { input ->
                            store.save(
                                sessionId = request.sessionId,
                                payload = MediaPayload.Stream { input },
                                origin = MediaOrigin.GENERATED,
                                mimeTypeHint = artifact.mimeType,
                                suggestedName = artifact.file.name,
                                targetPath = targetPath,
                            )
                        }
                    }
                    if (savedRefs.isEmpty()) {
                        throw ComfyFailure("ComfyUI 提交器执行完成，但未在输出目录检测到任何有效媒体文件")
                    }
                    if (!isChain) {
                        return@withContext ComfyWorkflowExecutionResult(
                            workflow = workflow,
                            host = host,
                            savedMedia = savedRefs,
                            promptId = execResult.promptId,
                            summary = execResult.summary,
                            appliedFixedDiffs = fixedDiffs,
                        )
                    }
                    val chainIdNonNull = requireNotNull(resolvedChainId)
                    val segIdx = if (resolvedParentId == null) 1 else (existingChain?.node(resolvedParentId)?.segmentIndex ?: 0) + 1
                    val attemptLabel = StatefulChainDag.nextAttemptLabel(
                        existingNodes = existingChain?.nodes.orEmpty(),
                        parentNodeId = resolvedParentId,
                        segmentIndex = segIdx,
                    )
                    val nodeId = "seg_${segIdx.toString().padStart(2, '0')}_$attemptLabel"
                    val promptSummary = workflow.fields.firstOrNull {
                        it.semanticRole == FieldSemanticRole.PROMPT || it.key == "prompt"
                    }?.let { f -> (mergedInputs[f.key] as? JsonPrimitive)?.contentOrNull }.orEmpty().take(240)
                    val framesVal = workflow.fields.firstOrNull {
                        it.semanticRole == FieldSemanticRole.FRAMES || it.key == "frames"
                    }?.let { f -> (mergedInputs[f.key] as? JsonPrimitive)?.intOrNull }

                    val appended = StatefulChainNode(
                        nodeId = nodeId,
                        parentNodeId = resolvedParentId,
                        stage = resolvedStage,
                        segmentIndex = segIdx,
                        attemptLabel = attemptLabel,
                        branchId = branchId,
                        branchPolicy = branchPolicyEffective,
                        outputMediaPath = savedRefs.first().path,
                        frames = framesVal,
                        promptSummary = promptSummary,
                        contentInputsJson = JsonObject(request.contentInputs),
                        fixedOverridesJson = JsonObject(request.fixedOverrides),
                        continuationStateJson = cleanContinuation,
                        serverLatentSlotKey = execResult.serverLatentSlotKey,
                        stateValidity = ChainNodeValidity.ACTIVE,
                        createdAt = clock(),
                    )
                    val appendResult = StatefulChainDag.appendNode(
                        existing = existingChain,
                        sessionId = request.sessionId,
                        workflowId = workflow.workflowId,
                        hostId = host.id,
                        chainId = chainIdNonNull,
                        shotRef = request.shotRef,
                        newNode = appended,
                    )
                    chainStore.save(appendResult.record)
                    return@withContext ComfyWorkflowExecutionResult(
                        workflow = workflow,
                        host = host,
                        savedMedia = savedRefs,
                        promptId = execResult.promptId,
                        summary = execResult.summary,
                        appliedFixedDiffs = fixedDiffs,
                        chainRecord = appendResult.record,
                        appendedNode = appendResult.appendedNode,
                        invalidatedNodeIds = appendResult.invalidatedNodeIds,
                    )
                } finally {
                    ComfySubmitterRunner.cleanupRunDirectory(execResult.continuationState)
                }
            } else {
                // 内置通用 API Format JSON 工作流直连执行（BUG 1 修复在 parseComfyHistory 自动生效）。
                return@withContext executeRawJsonWorkflow(
                    request = request,
                    workflow = workflow,
                    host = host,
                    mergedInputs = mergedInputs,
                    stagedFiles = stagedFiles,
                    fixedDiffs = fixedDiffs,
                    customOutputDir = customOutputDir,
                )
            }
        } finally {
            stagingDir.deleteRecursively()
            proxyTempDir.deleteRecursively()
        }
    }

    private suspend fun executeRawJsonWorkflow(
        request: ComfyWorkflowExecutionRequest,
        workflow: ComfyWorkflow,
        host: ComfyHost,
        mergedInputs: MutableMap<String, JsonElement>,
        stagedFiles: Map<String, File>,
        fixedDiffs: List<WorkflowFixedOverrideDiff>,
        customOutputDir: String?,
    ): ComfyWorkflowExecutionResult {
        val templateGraph = requireNotNull(workflow.rawWorkflowJson) { "工作流缺少 API Format 图与提交器脚本" }
        val nodes = templateGraph.toMutableMap()

        fun mutateNodeInput(nodeId: String, inputKey: String, value: JsonElement) {
            val node = (nodes[nodeId] as? JsonObject)?.toMutableMap()
                ?: throw ComfyFailure("工作流缺少节点 $nodeId")
            val inputs = (node["inputs"] as? JsonObject)?.toMutableMap()
                ?: throw ComfyFailure("节点 $nodeId 缺少 inputs 对象")
            inputs[inputKey] = value
            node["inputs"] = JsonObject(inputs)
            nodes[nodeId] = JsonObject(node)
        }

        // 先上传所有媒体文件，再回填上传节点输入。
        for (field in workflow.fields) {
            val value = mergedInputs[field.key] ?: continue
            if (value is JsonNull) continue
            val targetNode = field.uploadNodeId ?: field.nodeId ?: continue
            val targetInput = field.uploadInputKey ?: field.inputKey ?: continue
            if (field.valueType.isMediaFileType) {
                val relPath = (value as? JsonPrimitive)?.contentOrNull
                    ?: ((value as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.contentOrNull
                    ?: continue
                val staged = stagedFiles[relPath] ?: throw ComfyFailure("未找到暂存输入文件：$relPath")
                val inputKind = when (field.valueType) {
                    FieldValueType.VIDEO_FILE -> InputKind.VIDEO
                    FieldValueType.AUDIO_FILE -> InputKind.AUDIO
                    else -> InputKind.IMAGE
                }
                val mime = ComfySubmitterRunner.classifyMediaFile(staged.name, inputKind)?.second ?: "application/octet-stream"
                val remoteName = staged.inputStream().use { stream ->
                    http.upload(host, "in_${UUID.randomUUID().toString().take(8)}_${staged.name}", mime, stream, inputKind)
                }
                mutateNodeInput(targetNode, targetInput, JsonPrimitive(remoteName))
            } else {
                mutateNodeInput(targetNode, targetInput, value)
            }
        }

        val filledGraph = JsonObject(nodes)
        val clientId = UUID.randomUUID().toString()
        val submitResp = http.post(host, "/prompt", buildJsonObject {
            put("prompt", filledGraph)
            put("client_id", clientId)
        })
        if ((submitResp["error"] != null && submitResp["error"] != JsonNull) ||
            (submitResp["node_errors"] as? JsonObject)?.isNotEmpty() == true
        ) {
            throw ComfyFailure("ComfyUI 拒绝工作流，请核对节点与参数")
        }
        val promptId = (submitResp["prompt_id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw ComfyFailure("ComfyUI 提交响应缺少 prompt_id")

        val outputSlots = workflow.outputNodeIds.map { outId ->
            val defaultField = when (workflow.mediaCategory) {
                ComfyMediaCategory.AUDIO -> "audio"
                ComfyMediaCategory.VIDEO, ComfyMediaCategory.POST_PROCESS -> "videos"
                ComfyMediaCategory.IMAGE -> "images"
            }
            val outputKind = when (workflow.mediaCategory) {
                ComfyMediaCategory.AUDIO -> InputKind.AUDIO
                ComfyMediaCategory.VIDEO, ComfyMediaCategory.POST_PROCESS -> InputKind.VIDEO
                ComfyMediaCategory.IMAGE -> InputKind.IMAGE
            }
            ComfySlot(outId, defaultField, SlotKind.OUTPUT, outputKind = outputKind)
        }
        if (outputSlots.isEmpty()) throw ComfyFailure("未声明任何产出节点 outputNodeIds")

        val deadline = clock() + maxOf(host.timeoutSeconds, 120) * 1000L
        var remoteOutputs: List<RemoteOutput>? = null
        while (clock() < deadline) {
            val history = http.get(host, "/history/$promptId")
            when (val status = parseComfyHistory(history, promptId, outputSlots)) {
                is RemoteStatus.Succeeded -> {
                    remoteOutputs = status.outputs
                    break
                }
                is RemoteStatus.Failed -> throw ComfyFailure(status.message, status.retryable)
                is RemoteStatus.Running -> {
                    request.onProgress(status.progress, "ComfyUI 生成中…")
                    delay(1500)
                }
            }
        }
        val finalOutputs = remoteOutputs ?: throw ComfyFailure("等待 ComfyUI 任务结果超时", retryable = true)
        val savedRefs = finalOutputs.mapIndexed { idx, out ->
            val ext = out.location.substringAfter("filename=").substringBefore('&')
                .substringAfterLast('.', "").lowercase().ifBlank {
                    when (out.kind) {
                        InputKind.IMAGE -> "png"
                        InputKind.VIDEO -> "mp4"
                        InputKind.AUDIO -> "wav"
                    }
                }
            val targetPath = customOutputDir?.let { dir ->
                val rel = "$dir/comfy-${UUID.randomUUID().toString().take(10)}-${idx + 1}.$ext"
                try {
                    resolver.productionPath(request.sessionId, rel)
                } catch (_: UnsupportedOperationException) {
                    rel
                }
            }
            var savedRef: MediaRef? = null
            http.download(host, out.location) { input, mime ->
                savedRef = store.save(request.sessionId, MediaPayload.Stream { input }, MediaOrigin.GENERATED, mime, "comfy.$ext", targetPath)
                savedRef!!.path
            }
            requireNotNull(savedRef) { "媒体文件保存失败" }
        }
        return ComfyWorkflowExecutionResult(
            workflow = workflow,
            host = host,
            savedMedia = savedRefs,
            promptId = promptId,
            summary = "已生成 ${savedRefs.size} 个媒体文件",
            appliedFixedDiffs = fixedDiffs,
        )
    }

    private fun resolveAndValidateFields(
        workflow: ComfyWorkflow,
        contentInputs: Map<String, JsonElement>,
        fixedOverrides: Map<String, JsonElement>,
    ): MutableMap<String, JsonElement> {
        val knownKeys = workflow.fields.mapTo(mutableSetOf()) { it.key }
        (contentInputs.keys + fixedOverrides.keys).forEach { key ->
            if (key !in knownKeys) throw IllegalArgumentException("未知工作流字段：$key")
        }
        contentInputs.keys.forEach { key ->
            val spec = workflow.field(key)!!
            require(spec.mutability == FieldMutability.CONTENT) {
                "字段 $key 属于工作流固定字段，如需单次实验覆写请传入 fixed_overrides（将触发确认审批）"
            }
        }
        val merged = LinkedHashMap<String, JsonElement>()
        for (field in workflow.fields) {
            val chosen = when (field.mutability) {
                FieldMutability.CONTENT -> contentInputs[field.key] ?: field.defaultValue
                FieldMutability.FIXED -> fixedOverrides[field.key] ?: field.defaultValue
            }
            field.validateValue(chosen)
            if (chosen != null && chosen !is JsonNull) {
                merged[field.key] = chosen
            }
        }
        return merged
    }

    private suspend fun stageWorkspaceInputs(
        workflow: ComfyWorkflow,
        mergedInputs: Map<String, JsonElement>,
        sessionId: String,
        stagingDir: File,
    ): MutableMap<String, File> {
        val map = LinkedHashMap<String, File>()
        var fileIndex = 0

        suspend fun resolveWorkspaceMedia(path: String): MediaResolution {
            require(!path.contains("://")) { "$path：不接受外部 URI，请使用工作区文件路径" }
            val direct = resolver.resolve(sessionId, path)
            if (direct is MediaResolution.Available) return direct
            if (!path.startsWith('/')) {
                val mapped = try {
                    resolver.productionPath(sessionId, path)
                } catch (_: UnsupportedOperationException) {
                    null
                }
                if (mapped != null && mapped != path) {
                    return resolver.resolve(sessionId, mapped)
                }
            }
            return direct
        }

        suspend fun stageOne(rawPath: String, expectedKind: MediaKind?) {
            if (rawPath in map) return
            val resolved = resolveWorkspaceMedia(rawPath) as? MediaResolution.Available
                ?: throw ComfyFailure("输入媒体文件不存在或不可读取：$rawPath")
            if (expectedKind != null && resolved.ref.kind != expectedKind) {
                throw IllegalArgumentException("输入文件 $rawPath 类型为 ${resolved.ref.kind}，与字段要求 $expectedKind 不符")
            }
            val safeName = rawPath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "input.bin" }
            val dest = File(stagingDir, "${fileIndex++}_$safeName")
            resolver.open(resolved).use { src ->
                dest.outputStream().use { out -> src.copyTo(out) }
            }
            map[rawPath] = dest
        }

        for (field in workflow.fields) {
            if (!field.valueType.isMediaFileType) continue
            val v = mergedInputs[field.key] ?: continue
            val singleExpected = when (field.valueType) {
                FieldValueType.IMAGE_FILE -> MediaKind.IMAGE
                FieldValueType.VIDEO_FILE -> MediaKind.VIDEO
                FieldValueType.AUDIO_FILE -> MediaKind.AUDIO
                else -> null
            }
            when (v) {
                is JsonPrimitive -> if (v.isString && v.content.isNotBlank()) stageOne(v.content, singleExpected)
                is JsonArray -> for (elem in v) {
                    when (elem) {
                        is JsonPrimitive -> if (elem.isString && elem.content.isNotBlank()) stageOne(elem.content, null)
                        is JsonObject -> {
                            val path = (elem["path"] as? JsonPrimitive)?.contentOrNull
                                ?: (elem["image_path"] as? JsonPrimitive)?.contentOrNull
                                ?: (elem["file"] as? JsonPrimitive)?.contentOrNull
                            if (!path.isNullOrBlank()) stageOne(path, null)
                        }
                        else -> Unit
                    }
                }
                else -> Unit
            }
        }
        return map
    }

    private fun defaultMediaDayDirectory(nowMs: Long): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        return "media/$day"
    }
}
