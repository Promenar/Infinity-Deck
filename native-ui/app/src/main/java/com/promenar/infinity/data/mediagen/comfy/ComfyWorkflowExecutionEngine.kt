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
                val targetChainId = resolvedChainId ?: resolveSingleActiveChainId(
                    sessionId = request.sessionId,
                    workflowId = workflow.workflowId,
                    shotRef = request.shotRef,
                    requiredNodeId = retryId,
                )
                resolvedChainId = targetChainId
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
                    resolvedStage = if (resolvedChainId.isNullOrBlank() && resolvedParentId.isNullOrBlank()) "first" else "next"
                }
                if (resolvedStage == "next") {
                    val targetChainId = resolvedChainId ?: resolveSingleActiveChainId(
                        sessionId = request.sessionId,
                        workflowId = workflow.workflowId,
                        shotRef = request.shotRef,
                        requiredNodeId = resolvedParentId,
                    )
                    resolvedChainId = targetChainId
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
                    if (key !in request.fixedOverrides && workflow.field(key) != null) {
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

            val submitterId = workflow.submitterScriptId
            val submitter = submitterId?.let { id ->
                repository.submitter(host.id, id)
                    ?: throw ComfyFailure("工作流绑定的提交器脚本不存在：$id")
            }

            val parentNode = if (resolvedParentId != null) existingChain?.node(resolvedParentId) else null
            val isBranching = resolvedParentId != null && (
                !request.retryNodeId.isNullOrBlank() ||
                    existingChain?.nodes.orEmpty().any {
                        it.parentNodeId == resolvedParentId && it.stateValidity == ChainNodeValidity.ACTIVE
                    }
                )
            val branchId = when {
                resolvedParentId == null -> "main"
                !isBranching -> parentNode?.branchId?.ifBlank { "main" } ?: "main"
                else -> "b_${resolvedParentId.takeLast(6)}_${UUID.randomUUID().toString().take(4)}"
            }
            val segIdx = if (resolvedParentId == null) 1 else (parentNode?.segmentIndex ?: 0) + 1

            val requestedPolicy = request.branchPolicy
                ?: workflow.chainSpec?.defaultBranchPolicy
                ?: StatefulBranchPolicy.ISOLATED_DIR
            val supportsIsolation = workflow.chainSpec?.supportsPhysicalBranchIsolation(submitter?.protocol) == true

            val explicitLatentDir = (mergedInputs["latent_dir"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val inheritedLatentDir = (inheritedState["latent_dir"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val defaultChainMainDir = resolvedChainId?.let { "comfy_chain/${workflow.workflowId}/$it/main" }

            var branchPolicyEffective = requestedPolicy
            var downgradedFromIsolation = false
            val readLatentDir: String?
            val writeLatentDir: String?
            if (!isChain) {
                readLatentDir = null
                writeLatentDir = null
            } else if (resolvedStage == "first" || resolvedParentId == null) {
                val baseDir = explicitLatentDir ?: defaultChainMainDir
                readLatentDir = baseDir
                writeLatentDir = baseDir
            } else {
                val parentDir = inheritedLatentDir ?: defaultChainMainDir ?: "comfy_chain/${workflow.workflowId}/main"
                val overrideDir = if ("latent_dir" in request.fixedOverrides) explicitLatentDir else null
                readLatentDir = parentDir
                if (overrideDir != null) {
                    writeLatentDir = overrideDir
                } else if (isBranching) {
                    if (requestedPolicy == StatefulBranchPolicy.ISOLATED_DIR && supportsIsolation) {
                        branchPolicyEffective = StatefulBranchPolicy.ISOLATED_DIR
                        writeLatentDir = "comfy_chain/${workflow.workflowId}/${resolvedChainId}/$branchId"
                    } else {
                        branchPolicyEffective = StatefulBranchPolicy.IN_PLACE_OVERWRITE
                        downgradedFromIsolation = requestedPolicy == StatefulBranchPolicy.ISOLATED_DIR
                        writeLatentDir = parentDir
                    }
                } else {
                    writeLatentDir = parentDir
                }
            }

            val customOutputDir = request.outputDir?.takeIf { it.isNotBlank() }?.let(::productionPath)

            if (submitter != null) {
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
                            branchPolicy = branchPolicyEffective,
                            parentNodeId = resolvedParentId,
                            segmentIndex = segIdx,
                            readLatentDir = readLatentDir,
                            writeLatentDir = writeLatentDir,
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
                    val downgradeNotice = if (downgradedFromIsolation) {
                        "（当前提交器不支持物理分支隔离，分叉已按 IN_PLACE_OVERWRITE 写入原目录并将被覆盖节点标为失效）"
                    } else ""
                    return@withContext ComfyWorkflowExecutionResult(
                        workflow = workflow,
                        host = host,
                        savedMedia = savedRefs,
                        promptId = execResult.promptId,
                        summary = execResult.summary + downgradeNotice,
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

        fun removeNodeInputIfMatches(nodeId: String, inputKey: String?, targetUploadNodeId: String? = null) {
            val node = (nodes[nodeId] as? JsonObject)?.toMutableMap() ?: return
            val inputs = (node["inputs"] as? JsonObject)?.toMutableMap() ?: return
            if (inputKey != null) {
                val link = inputs[inputKey] as? JsonArray
                val linkedSource = (link?.getOrNull(0) as? JsonPrimitive)?.contentOrNull
                if (targetUploadNodeId == null || linkedSource == targetUploadNodeId) {
                    inputs.remove(inputKey)
                }
            }
            if (targetUploadNodeId != null) {
                val keysToDrop = inputs.entries.filter { (_, v) ->
                    val arr = v as? JsonArray
                    (arr?.getOrNull(0) as? JsonPrimitive)?.contentOrNull == targetUploadNodeId
                }.map { it.key }
                keysToDrop.forEach { inputs.remove(it) }
            }
            node["inputs"] = JsonObject(inputs)
            nodes[nodeId] = JsonObject(node)
        }

        fun extractMediaPaths(value: JsonElement?): List<String> = when (value) {
            null, is JsonNull -> emptyList()
            is JsonPrimitive -> if (value.isString && value.content.isNotBlank()) listOf(value.content) else emptyList()
            is JsonArray -> value.mapNotNull { elem ->
                when (elem) {
                    is JsonPrimitive -> elem.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                    is JsonObject -> (elem["path"] as? JsonPrimitive)?.contentOrNull
                        ?: (elem["image_path"] as? JsonPrimitive)?.contentOrNull
                        ?: (elem["file"] as? JsonPrimitive)?.contentOrNull
                    else -> null
                }
            }
            else -> emptyList()
        }

        fun inferMediaKind(field: WorkflowFieldSpec, fileName: String): InputKind = when (field.valueType) {
            FieldValueType.VIDEO_FILE -> InputKind.VIDEO
            FieldValueType.AUDIO_FILE -> InputKind.AUDIO
            FieldValueType.IMAGE_FILE -> InputKind.IMAGE
            else -> ComfySubmitterRunner.classifyMediaFile(fileName, null)?.first ?: when (workflow.mediaCategory) {
                ComfyMediaCategory.AUDIO -> InputKind.AUDIO
                else -> InputKind.IMAGE
            }
        }

        suspend fun uploadStagedMedia(field: WorkflowFieldSpec, relPath: String): Pair<String, InputKind> {
            val staged = stagedFiles[relPath] ?: throw ComfyFailure("未找到暂存输入文件：$relPath")
            val inputKind = inferMediaKind(field, staged.name)
            val mime = ComfySubmitterRunner.classifyMediaFile(staged.name, inputKind)?.second ?: "application/octet-stream"
            val remoteName = staged.inputStream().use { stream ->
                http.upload(host, "in_${UUID.randomUUID().toString().take(8)}_${staged.name}", mime, stream, inputKind)
            }
            return remoteName to inputKind
        }

        val unusedUploadNodeIds = mutableSetOf<String>()
        var nextNumericNodeId = (nodes.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1

        // 先上传所有媒体文件，再回填静态多目标 / 动态前缀槽位或单节点输入，并回收未用上传节点。
        for (field in workflow.fields) {
            val value = mergedInputs[field.key]
            if (field.valueType.isMediaFileType) {
                val paths = extractMediaPaths(value)
                val dynamicPrefix = field.dynamicInputPrefix
                val staticTargets = field.effectiveUploadTargets()
                when {
                    dynamicPrefix != null && field.nodeId != null -> {
                        val targetNode = (nodes[field.nodeId] as? JsonObject)?.toMutableMap()
                            ?: throw ComfyFailure("工作流缺少节点 ${field.nodeId}")
                        val inputs = (targetNode["inputs"] as? JsonObject)?.toMutableMap()
                            ?: throw ComfyFailure("节点 ${field.nodeId} 缺少 inputs 对象")
                        val dynamicRegex = Regex("^${Regex.escape(dynamicPrefix)}\\d+$")
                        val oldKeys = inputs.keys.filter { it == field.inputKey || dynamicRegex.matches(it) }
                        oldKeys.forEach { oldKey ->
                            val linkedId = (inputs.remove(oldKey) as? JsonArray)?.getOrNull(0)?.let {
                                (it as? JsonPrimitive)?.contentOrNull
                            }
                            if (!linkedId.isNullOrBlank() && linkedId != field.nodeId) unusedUploadNodeIds += linkedId
                        }
                        paths.forEachIndexed { idx, relPath ->
                            val (remoteName, kind) = uploadStagedMedia(field, relPath)
                            val newId = (nextNumericNodeId++).toString()
                            val loadClass = field.uploadNodeClassType ?: when (kind) {
                                InputKind.IMAGE -> "LoadImage"
                                InputKind.AUDIO -> "LoadAudio"
                                InputKind.VIDEO -> "LoadVideo"
                            }
                            val uploadKey = field.uploadInputKey ?: when {
                                loadClass == "LoadImage" || kind == InputKind.IMAGE -> "image"
                                loadClass == "LoadAudio" || kind == InputKind.AUDIO -> "audio"
                                else -> "file"
                            }
                            nodes[newId] = buildJsonObject {
                                put("class_type", loadClass)
                                putJsonObject("inputs") { put(uploadKey, remoteName) }
                            }
                            inputs["$dynamicPrefix${field.dynamicStartIndex + idx}"] = buildJsonArray {
                                add(newId)
                                add(0)
                            }
                        }
                        targetNode["inputs"] = JsonObject(inputs)
                        nodes[field.nodeId] = JsonObject(targetNode)
                    }
                    staticTargets.isNotEmpty() -> {
                        if (paths.size > staticTargets.size) {
                            throw ComfyFailure(
                                "字段 ${field.key} 传入了 ${paths.size} 个媒体文件，但工作流仅声明了 ${staticTargets.size} 个上传目标；" +
                                    "请补齐 uploadTargets 或配置 dynamicInputPrefix，禁止静默丢弃输入文件",
                            )
                        }
                        paths.forEachIndexed { idx, relPath ->
                            val (remoteName, _) = uploadStagedMedia(field, relPath)
                            val target = staticTargets[idx]
                            mutateNodeInput(target.nodeId, target.input, JsonPrimitive(remoteName))
                        }
                        // 未用到的可选上传节点移除父节点连线并纳入裁剪池（避免遗留占位图被 ComfyUI 执行）。
                        if (field.nodeId != null && (paths.isNotEmpty() || (field.mutability == FieldMutability.CONTENT && !field.required))) {
                            for (idx in paths.size until staticTargets.size) {
                                val unusedTarget = staticTargets[idx]
                                removeNodeInputIfMatches(field.nodeId, field.inputKey?.takeIf { staticTargets.size == 1 }, unusedTarget.nodeId)
                                unusedUploadNodeIds += unusedTarget.nodeId
                            }
                        }
                    }
                    else -> {
                        val targetNode = field.nodeId ?: continue
                        val targetInput = field.inputKey ?: continue
                        if (paths.size > 1) {
                            throw ComfyFailure(
                                "字段 ${field.key} 传入了 ${paths.size} 个媒体文件，但仅绑定单个节点输入 $targetNode.$targetInput；" +
                                    "请声明多目标 uploadTargets 或 dynamicInputPrefix",
                            )
                        }
                        val firstPath = paths.firstOrNull() ?: continue
                        val (remoteName, _) = uploadStagedMedia(field, firstPath)
                        mutateNodeInput(targetNode, targetInput, JsonPrimitive(remoteName))
                    }
                }
            } else {
                if (value == null || value is JsonNull) continue
                val targetNode = field.nodeId ?: continue
                val targetInput = field.inputKey ?: continue
                mutateNodeInput(targetNode, targetInput, value)
            }
        }

        if (unusedUploadNodeIds.isNotEmpty()) {
            val protectedOutputs = workflow.outputNodeIds.toSet()
            fun referencedNodeIds(): Set<String> = buildSet {
                nodes.forEach { (nodeId, element) ->
                    val nodeInputs = (element as? JsonObject)?.get("inputs") as? JsonObject ?: return@forEach
                    nodeInputs.values.forEach { inputValue ->
                        val link = inputValue as? JsonArray ?: return@forEach
                        val sourceId = (link.getOrNull(0) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return@forEach
                        val outIndex = (link.getOrNull(1) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: return@forEach
                        if (link.size == 2 && outIndex >= 0 && sourceId != nodeId) add(sourceId)
                    }
                }
            }
            while (true) {
                val referenced = referencedNodeIds()
                val removable = unusedUploadNodeIds.filter { it in nodes && it !in protectedOutputs && it !in referenced }
                if (removable.isEmpty()) break
                removable.forEach { nodes.remove(it) }
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
            val detail = ComfyErrorDiagnostics.summarizePromptRejection(submitResp)
            throw ComfyFailure(
                if (detail.isNullOrBlank()) "ComfyUI 拒绝工作流，请核对节点与参数"
                else "ComfyUI 拒绝工作流：$detail",
            )
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
            when (val status = parseComfyHistory(history, promptId, outputSlots, includeDiagnostics = true)) {
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

    private suspend fun resolveSingleActiveChainId(
        sessionId: String,
        workflowId: String,
        shotRef: String?,
        requiredNodeId: String?,
    ): String {
        val candidates = chainStore.listSession(sessionId, workflowId = workflowId, shotRef = shotRef)
            .filter { chain ->
                val active = chain.activeNodes()
                active.isNotEmpty() && (requiredNodeId == null || active.any { it.nodeId == requiredNodeId })
            }
        return when {
            candidates.size == 1 -> candidates.single().chainId
            candidates.isEmpty() -> throw ComfyFailure(
                if (requiredNodeId != null) {
                    "当前会话未找到包含活跃节点 $requiredNodeId 的工作流 $workflowId 有状态链，请确认 chain_id"
                } else {
                    "当前会话尚无工作流 $workflowId 的活跃有状态链，请先传 stage=first 启动首段"
                },
            )
            else -> throw ComfyFailure(
                "当前会话存在 ${candidates.size} 条工作流 $workflowId 活跃链，请显式传入 chain_id：" +
                    candidates.joinToString("；") { "${it.chainId}(tip=${it.activeTipNodeId})" },
            )
        }
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
