package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.media.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import java.io.File
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ComfyWorkflowExecutionRequest(
    val sessionId: String,
    val workflowId: String,
    val contentInputs: Map<String, JsonElement> = emptyMap(),
    val fixedOverrides: Map<String, JsonElement> = emptyMap(),
    val runMode: ComfyWorkflowRunMode = ComfyWorkflowRunMode.EXECUTE,
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
    val runtime: ComfySubmitterRuntime? = null,
    val runMode: ComfyWorkflowRunMode = ComfyWorkflowRunMode.EXECUTE,
    val promptId: String? = null,
    val summary: String,
    val appliedFixedDiffs: List<WorkflowFixedOverrideDiff> = emptyList(),
    val appliedSeeds: Map<String, Long> = emptyMap(),
    val emittedWorkflowPath: String? = null,
    val emittedWorkflowJson: JsonObject? = null,
    val preflightReport: JsonObject? = null,
    val warnings: List<String> = emptyList(),
    val chainRecord: StatefulChainRecord? = null,
    val appendedNode: StatefulChainNode? = null,
    val invalidatedNodeIds: List<String> = emptyList(),
)

/**
 * ComfyUI 兼容层统一执行协调器：
 * - 被 `comfy_run_workflow` Agent 工具与制作面板快捷操作共同调用；
 * - 统一完成字段验证、种子策略、主机元数据插值、工作区文件/目录暂存、本机回环认证代理启动、
 *   双执行环境（Linux / 内置 Python）与通用 JSON 执行、服务端历史产物补拉及有状态链 DAG 回验更新。
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
    private val stageWorkspaceDirectory: (suspend (sessionId: String, virtualDirPath: String, destDir: File) -> Boolean)? = null,
) {
    private val random = SecureRandom()

    suspend fun execute(request: ComfyWorkflowExecutionRequest): ComfyWorkflowExecutionResult = withContext(Dispatchers.IO) {
        require(request.sessionId.isNotBlank()) { "执行工作流需要有效的 sessionId" }
        val wfState = repository.workflowState(request.workflowId)
        val workflow = wfState.workflow
        val host = repository.host(workflow.hostId)

        // 校验入参字段归属、类型、随机种子策略与固定基线差异（绝不改写原基线）。
        val fixedDiffs = workflow.detectFixedOverrides(request.fixedOverrides)
        val (mergedInputs, appliedSeeds) = resolveAndValidateFields(workflow, request.contentInputs, request.fixedOverrides)

        // 按需探测主机元数据（仅当工作流配置或入参引用了 {host.comfyui_version} 时读取 /system_stats）。
        val hostMetadata = resolveHostMetadata(host, workflow, mergedInputs)

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
                // 防参数漂移：对于被锁定的上下文键，仅回填工作流已声明字段（避免未声明宿主托管键覆盖 CLI 注入）。
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
            val previousSidecars = linkedMapOf<String, String>()
            val snapshotObj = inheritedState["workflow_snapshot"] as? JsonObject
            val rawSnapshotText = (inheritedState["__workflow_snapshot_raw"] as? JsonPrimitive)?.contentOrNull
            if (snapshotObj != null || rawSnapshotText != null) {
                previousWorkflowFile = File(stagingDir, "previous_workflow.json").apply {
                    writeText(rawSnapshotText ?: snapshotObj!!.toString(), Charsets.UTF_8)
                }
                (inheritedState["__sidecar_files"] as? JsonObject)?.forEach { (suffix, elem) ->
                    val content = (elem as? JsonPrimitive)?.contentOrNull
                    if (content != null && suffix.startsWith('.')) {
                        previousSidecars[suffix] = content
                    }
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
            val effectiveTimeoutSec = workflow.timeoutSeconds ?: maxOf(host.timeoutSeconds, 600)

            if (submitter != null) {
                val needsProxy = request.runMode != ComfyWorkflowRunMode.EMIT_WORKFLOW
                val execResult = if (needsProxy) {
                    ComfyLocalAuthProxy.open(host, credentials, proxyClient, proxyTempDir).use { proxy ->
                        runner.execute(
                            SubmitterRunRequest(
                                submitter = submitter,
                                workflow = workflow,
                                proxyBaseUrl = proxy.baseUrl,
                                sessionId = request.sessionId,
                                runMode = request.runMode,
                                resolvedInputs = mergedInputs,
                                stagedFilesByPath = stagedFiles,
                                stagedRootDir = stagingDir,
                                hostMetadata = hostMetadata,
                                stage = resolvedStage,
                                branchId = branchId,
                                branchPolicy = branchPolicyEffective,
                                parentNodeId = resolvedParentId,
                                segmentIndex = segIdx,
                                readLatentDir = readLatentDir,
                                writeLatentDir = writeLatentDir,
                                inheritedState = inheritedState,
                                previousWorkflowFile = previousWorkflowFile,
                                previousSidecars = previousSidecars,
                                dryRun = false,
                                timeoutSeconds = effectiveTimeoutSec,
                                onProgress = request.onProgress,
                            ),
                        )
                    }
                } else {
                    runner.execute(
                        SubmitterRunRequest(
                            submitter = submitter,
                            workflow = workflow,
                            proxyBaseUrl = null,
                            sessionId = request.sessionId,
                            runMode = request.runMode,
                            resolvedInputs = mergedInputs,
                            stagedFilesByPath = stagedFiles,
                            stagedRootDir = stagingDir,
                            hostMetadata = hostMetadata,
                            stage = resolvedStage,
                            branchId = branchId,
                            branchPolicy = branchPolicyEffective,
                            parentNodeId = resolvedParentId,
                            segmentIndex = segIdx,
                            readLatentDir = readLatentDir,
                            writeLatentDir = writeLatentDir,
                            inheritedState = inheritedState,
                            previousWorkflowFile = previousWorkflowFile,
                            previousSidecars = previousSidecars,
                            dryRun = true,
                            timeoutSeconds = effectiveTimeoutSec,
                            onProgress = request.onProgress,
                        ),
                    )
                }

                try {
                    // G12: 审阅导图模式（EMIT_WORKFLOW）
                    if (request.runMode == ComfyWorkflowRunMode.EMIT_WORKFLOW) {
                        var emittedSavedPath: String? = null
                        val emittedFile = execResult.emittedWorkflowFile
                        val emittedText = emittedFile?.takeIf { it.isFile }?.readText(Charsets.UTF_8)
                            ?: execResult.emittedWorkflowJson?.toString()
                        if (!emittedText.isNullOrBlank()) {
                            val targetPath = customOutputDir?.let { dir ->
                                val rel = "$dir/emitted-${workflow.workflowId}-${UUID.randomUUID().toString().take(8)}.json"
                                runCatching { resolver.productionPath(request.sessionId, rel) }.getOrDefault(rel)
                            }
                            val saved = store.save(
                                sessionId = request.sessionId,
                                payload = MediaPayload.Bytes(emittedText.toByteArray(Charsets.UTF_8)),
                                origin = MediaOrigin.GENERATED,
                                mimeTypeHint = "application/json",
                                suggestedName = "${workflow.workflowId}-emitted.json",
                                targetPath = targetPath,
                            )
                            emittedSavedPath = saved.path
                        }
                        return@withContext ComfyWorkflowExecutionResult(
                            workflow = workflow,
                            host = host,
                            savedMedia = emptyList(),
                            runtime = execResult.runtime,
                            runMode = ComfyWorkflowRunMode.EMIT_WORKFLOW,
                            summary = execResult.summary,
                            appliedFixedDiffs = fixedDiffs,
                            appliedSeeds = appliedSeeds,
                            emittedWorkflowPath = emittedSavedPath,
                            emittedWorkflowJson = execResult.emittedWorkflowJson,
                            warnings = execResult.warnings,
                        )
                    }

                    // G12: 远端预检模式（PREFLIGHT_ONLY）
                    if (request.runMode == ComfyWorkflowRunMode.PREFLIGHT_ONLY) {
                        return@withContext ComfyWorkflowExecutionResult(
                            workflow = workflow,
                            host = host,
                            savedMedia = emptyList(),
                            runtime = execResult.runtime,
                            runMode = ComfyWorkflowRunMode.PREFLIGHT_ONLY,
                            summary = execResult.summary,
                            appliedFixedDiffs = fixedDiffs,
                            appliedSeeds = appliedSeeds,
                            preflightReport = execResult.preflightReport,
                            warnings = execResult.warnings,
                        )
                    }

                    val savedRefs = ArrayList<MediaRef>()
                    val savedFileNames = mutableSetOf<String>()
                    execResult.outputs.forEachIndexed { idx, artifact ->
                        val ext = artifact.file.extension.lowercase().ifBlank { "bin" }
                        val targetPath = customOutputDir?.let { dir ->
                            val targetRel = "$dir/comfy-${UUID.randomUUID().toString().take(10)}-${idx + 1}.$ext"
                            try {
                                resolver.productionPath(request.sessionId, targetRel)
                            } catch (_: UnsupportedOperationException) {
                                targetRel
                            }
                        }
                        val ref = artifact.file.inputStream().use { input ->
                            store.save(
                                sessionId = request.sessionId,
                                payload = MediaPayload.Stream { input },
                                origin = MediaOrigin.GENERATED,
                                mimeTypeHint = artifact.mimeType,
                                suggestedName = artifact.file.name,
                                targetPath = targetPath,
                            )
                        }
                        savedRefs += ref
                        savedFileNames += artifact.file.name
                    }

                    // G10: 若脚本仅提交（如 --submit-only）或未下载 3D/通用产物（如 SaveGLB），自动按 prompt_id 轮询 /history 并补拉遗漏产物。
                    val pid = execResult.promptId?.takeIf { it.isNotBlank() }
                    val shouldBackfill = workflow.backfillOutputsFromHistory && pid != null && (
                        savedRefs.isEmpty() ||
                            workflow.mediaCategory in setOf(ComfyMediaCategory.MODEL_3D, ComfyMediaCategory.FILE) ||
                            workflow.historyOutputFields.isNotEmpty()
                        )
                    if (shouldBackfill) {
                        val backfilled = backfillOutputsFromHistory(
                            host = host,
                            workflow = workflow,
                            promptId = requireNotNull(pid),
                            alreadySavedNames = savedFileNames,
                            sessionId = request.sessionId,
                            customOutputDir = customOutputDir,
                            timeoutSeconds = effectiveTimeoutSec,
                            onProgress = request.onProgress,
                        )
                        savedRefs += backfilled
                    }

                    if (savedRefs.isEmpty() && !workflow.allowEmptyOutputs) {
                        throw ComfyFailure("ComfyUI 提交器执行完成，但未在输出目录或服务端历史检测到任何有效产物文件")
                    }

                    if (!isChain) {
                        return@withContext ComfyWorkflowExecutionResult(
                            workflow = workflow,
                            host = host,
                            savedMedia = savedRefs,
                            runtime = execResult.runtime,
                            runMode = ComfyWorkflowRunMode.EXECUTE,
                            promptId = execResult.promptId,
                            summary = execResult.summary,
                            appliedFixedDiffs = fixedDiffs,
                            appliedSeeds = appliedSeeds,
                            warnings = execResult.warnings,
                        )
                    }

                    // 有状态链执行后回验：核对实际回报目录与槽位是否遵守物理隔离，绝不向调用方伪报 ACTIVE。
                    val warnings = execResult.warnings.toMutableList()
                    val ancestorIds = buildSet {
                        var cur: String? = resolvedParentId
                        while (cur != null && add(cur)) {
                            cur = existingChain?.node(cur)?.parentNodeId
                        }
                    }
                    val reportedDir = execResult.reportedLatentDir
                    val slotKey = execResult.serverLatentSlotKey
                    if (downgradedFromIsolation) {
                        warnings += "当前工作流提交器不具备成对读写目录分离能力，分叉已按 IN_PLACE_OVERWRITE 执行"
                    }
                    if (isBranching && branchPolicyEffective == StatefulBranchPolicy.ISOLATED_DIR) {
                        val failedToDivergeDir = reportedDir != null &&
                            readLatentDir != null &&
                            writeLatentDir != null &&
                            writeLatentDir != readLatentDir &&
                            reportedDir == readLatentDir
                        val collidesWithSibling = !slotKey.isNullOrBlank() &&
                            existingChain?.nodes.orEmpty().any {
                                it.nodeId !in ancestorIds &&
                                    it.serverLatentSlotKey == slotKey &&
                                    it.stateValidity == ChainNodeValidity.ACTIVE
                            }
                        if (failedToDivergeDir || collidesWithSibling) {
                            branchPolicyEffective = StatefulBranchPolicy.IN_PLACE_OVERWRITE
                            downgradedFromIsolation = true
                            warnings += if (collidesWithSibling) {
                                "执行后回验发现实际潜空间槽位（$slotKey）与既有活跃兄弟分支冲突，已诚实降级为 IN_PLACE_OVERWRITE"
                            } else {
                                "执行后回验发现脚本未写入宿主分配的隔离目录（write_latent_dir=$writeLatentDir，实际回报=$reportedDir），已诚实降级为 IN_PLACE_OVERWRITE"
                            }
                        }
                    }
                    if (!slotKey.isNullOrBlank() &&
                        existingChain?.nodes.orEmpty().any { it.nodeId in ancestorIds && it.serverLatentSlotKey == slotKey }
                    ) {
                        warnings += "当前节点槽位（$slotKey）与父/祖先节点重合（疑似提交器未随段落递增 clip_index）；已保护祖先节点不被误标失效，请核对提交器回执"
                    }

                    val continuationMap = execResult.continuationState
                        .filterKeys { it != "__run_dir_cleanup" }
                        .toMutableMap()
                    if (appliedSeeds.isNotEmpty()) {
                        continuationMap["applied_seeds"] = JsonObject(appliedSeeds.mapValues { JsonPrimitive(it.value) })
                    }
                    val cleanContinuation = JsonObject(continuationMap)

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

                    val primaryOutputPath = savedRefs.firstOrNull()?.path
                        ?: "comfy_chain/$chainIdNonNull/$nodeId.empty"
                    val appended = StatefulChainNode(
                        nodeId = nodeId,
                        parentNodeId = resolvedParentId,
                        stage = resolvedStage,
                        segmentIndex = segIdx,
                        attemptLabel = attemptLabel,
                        branchId = branchId,
                        branchPolicy = branchPolicyEffective,
                        outputMediaPath = primaryOutputPath,
                        frames = framesVal,
                        promptSummary = promptSummary,
                        contentInputsJson = JsonObject(request.contentInputs),
                        fixedOverridesJson = JsonObject(request.fixedOverrides),
                        continuationStateJson = cleanContinuation,
                        assignedReadLatentDir = readLatentDir,
                        assignedWriteLatentDir = writeLatentDir,
                        isolationWarning = warnings.distinct().joinToString("；").takeIf { it.isNotBlank() },
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
                    val finalWarnings = buildList {
                        addAll(warnings)
                        appendResult.appendedNode.isolationWarning?.let { add(it) }
                    }.distinct()
                    val downgradeNotice = if (downgradedFromIsolation || appendResult.invalidatedNodeIds.isNotEmpty()) {
                        "（已按实际物理槽位诚实记为 IN_PLACE_OVERWRITE" +
                            if (appendResult.invalidatedNodeIds.isNotEmpty()) {
                                "，并失效受冲突节点：${appendResult.invalidatedNodeIds.joinToString("、")}）"
                            } else "）"
                    } else ""
                    return@withContext ComfyWorkflowExecutionResult(
                        workflow = workflow,
                        host = host,
                        savedMedia = savedRefs,
                        runtime = execResult.runtime,
                        runMode = ComfyWorkflowRunMode.EXECUTE,
                        promptId = execResult.promptId,
                        summary = execResult.summary + downgradeNotice,
                        appliedFixedDiffs = fixedDiffs,
                        appliedSeeds = appliedSeeds,
                        warnings = finalWarnings,
                        chainRecord = appendResult.record,
                        appendedNode = appendResult.appendedNode,
                        invalidatedNodeIds = appendResult.invalidatedNodeIds,
                    )
                } finally {
                    ComfySubmitterRunner.cleanupRunDirectory(execResult.continuationState)
                }
            } else {
                // 内置通用 API Format JSON 工作流直连执行
                return@withContext executeRawJsonWorkflow(
                    request = request,
                    workflow = workflow,
                    host = host,
                    mergedInputs = mergedInputs,
                    appliedSeeds = appliedSeeds,
                    hostMetadata = hostMetadata,
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

    private suspend fun resolveHostMetadata(
        host: ComfyHost,
        workflow: ComfyWorkflow,
        mergedInputs: Map<String, JsonElement>,
    ): Map<String, String> {
        val meta = linkedMapOf(
            "id" to host.id,
            "name" to host.name,
        )
        val needsVersion = workflow.cliBaseArgs.any { "{host.comfyui_version}" in it } ||
            workflow.fields.any { it.cliValueTemplate?.contains("{host.comfyui_version}") == true } ||
            mergedInputs.values.any { containsPlaceholder(it, "{host.comfyui_version}") }
        if (needsVersion) {
            val stats = runCatching { http.get(host, "/system_stats") }.getOrNull()
            val version = ((stats?.get("system") as? JsonObject)?.get("comfyui_version") as? JsonPrimitive)
                ?.contentOrNull?.takeIf { it.isNotBlank() }
            if (version != null) {
                meta["comfyui_version"] = version
            }
        }
        return meta
    }

    private fun containsPlaceholder(elem: JsonElement, token: String): Boolean = when (elem) {
        is JsonPrimitive -> elem.isString && token in elem.content
        is JsonArray -> elem.any { containsPlaceholder(it, token) }
        is JsonObject -> elem.values.any { containsPlaceholder(it, token) }
    }

    private suspend fun backfillOutputsFromHistory(
        host: ComfyHost,
        workflow: ComfyWorkflow,
        promptId: String,
        alreadySavedNames: MutableSet<String>,
        sessionId: String,
        customOutputDir: String?,
        timeoutSeconds: Int,
        onProgress: suspend (Float?, String) -> Unit,
    ): List<MediaRef> {
        val deadline = clock() + timeoutSeconds.coerceAtLeast(30) * 1000L
        var historyEntry: JsonObject? = null
        while (clock() < deadline) {
            val history = http.get(host, "/history/$promptId")
            val entry = history[promptId] as? JsonObject
            if (entry != null) {
                val status = entry["status"] as? JsonObject
                val messages = status?.get("messages") as? JsonArray
                val failed = messages.orEmpty().mapNotNull { it as? JsonArray }.any {
                    (it.firstOrNull() as? JsonPrimitive)?.contentOrNull in setOf("execution_error", "execution_interrupted")
                }
                if (failed || (status?.get("status_str") as? JsonPrimitive)?.contentOrNull == "error") {
                    val detail = ComfyErrorDiagnostics.summarizeHistoryError(history, promptId)
                    throw ComfyFailure(if (detail.isNullOrBlank()) "ComfyUI 服务端执行失败" else "ComfyUI 服务端执行失败：$detail")
                }
                if ((status?.get("completed") as? JsonPrimitive)?.booleanOrNull == true) {
                    historyEntry = entry
                    break
                }
            }
            onProgress(null, "等待 ComfyUI 服务端产物落盘…")
            delay(1500)
        }
        val entry = historyEntry ?: throw ComfyFailure("等待 ComfyUI 历史产物超时（prompt_id=$promptId）", retryable = true)
        val outputsObj = entry["outputs"] as? JsonObject ?: return emptyList()
        val allowedFields = workflow.historyOutputFields.ifEmpty { DEFAULT_HISTORY_OUTPUT_FIELDS }.toSet()
        val targetNodeIds = workflow.outputNodeIds.toSet()

        val saved = ArrayList<MediaRef>()
        var idx = alreadySavedNames.size
        outputsObj.forEach { (nodeId, nodeVal) ->
            if (targetNodeIds.isNotEmpty() && nodeId !in targetNodeIds) return@forEach
            val nodeOutputs = nodeVal as? JsonObject ?: return@forEach
            nodeOutputs.forEach { (field, fieldVal) ->
                if (field !in allowedFields) return@forEach
                val items = when (fieldVal) {
                    is JsonArray -> fieldVal.toList()
                    is JsonObject -> listOf(fieldVal)
                    else -> emptyList()
                }
                items.forEach { rawItem ->
                    val item = rawItem as? JsonObject ?: return@forEach
                    val filename = (item["filename"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@forEach
                    val subfolder = (item["subfolder"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val type = (item["type"] as? JsonPrimitive)?.contentOrNull ?: "output"
                    if (type != "output") return@forEach
                    if (!safeComfyFile(filename, false) || !safeComfyFile(subfolder, true)) return@forEach
                    if (filename in alreadySavedNames) return@forEach

                    val viewUrl = "http://comfy.invalid/view".toHttpUrl().newBuilder()
                        .addQueryParameter("filename", filename)
                        .addQueryParameter("subfolder", subfolder)
                        .addQueryParameter("type", type)
                        .build()
                    val ext = filename.substringAfterLast('.', "").lowercase().ifBlank { "bin" }
                    val guessedMime = ComfySubmitterRunner.classifyOutputFile(filename, MediaKind.FILE)?.second
                    val targetPath = customOutputDir?.let { dir ->
                        val rel = "$dir/comfy-${UUID.randomUUID().toString().take(10)}-${++idx}.$ext"
                        runCatching { resolver.productionPath(sessionId, rel) }.getOrDefault(rel)
                    }
                    var ref: MediaRef? = null
                    http.download(host, "/view?${viewUrl.encodedQuery}") { input, mime ->
                        ref = store.save(
                            sessionId = sessionId,
                            payload = MediaPayload.Stream { input },
                            origin = MediaOrigin.GENERATED,
                            mimeTypeHint = guessedMime ?: mime,
                            suggestedName = filename,
                            targetPath = targetPath,
                        )
                        ref!!.path
                    }
                    ref?.let {
                        saved += it
                        alreadySavedNames += filename
                    }
                }
            }
        }
        return saved
    }

    private suspend fun executeRawJsonWorkflow(
        request: ComfyWorkflowExecutionRequest,
        workflow: ComfyWorkflow,
        host: ComfyHost,
        mergedInputs: MutableMap<String, JsonElement>,
        appliedSeeds: Map<String, Long>,
        hostMetadata: Map<String, String>,
        stagedFiles: Map<String, File>,
        fixedDiffs: List<WorkflowFixedOverrideDiff>,
        customOutputDir: String?,
    ): ComfyWorkflowExecutionResult {
        if (request.runMode == ComfyWorkflowRunMode.PREFLIGHT_ONLY) {
            val state = repository.preflightWorkflow(workflow.workflowId)
            val report = buildJsonObject {
                put("status", state.preflight.status.name)
                put("missing", JsonArray(state.preflight.missing.map(::JsonPrimitive)))
                put("uncertain", JsonArray(state.preflight.uncertain.map(::JsonPrimitive)))
            }
            return ComfyWorkflowExecutionResult(
                workflow = workflow,
                host = host,
                savedMedia = emptyList(),
                runMode = ComfyWorkflowRunMode.PREFLIGHT_ONLY,
                summary = "内置 JSON 工作流预检完成：${state.preflight.status.name}",
                appliedFixedDiffs = fixedDiffs,
                appliedSeeds = appliedSeeds,
                preflightReport = report,
            )
        }

        val templateGraph = requireNotNull(workflow.rawWorkflowJson) { "工作流缺少 API Format 图与提交器脚本" }
        val nodes = templateGraph.toMutableMap()

        fun mutateNodeInput(nodeId: String, inputKey: String, value: JsonElement) {
            val node = (nodes[nodeId] as? JsonObject)?.toMutableMap()
                ?: throw ComfyFailure("工作流缺少节点 $nodeId")
            val inputs = (node["inputs"] as? JsonObject)?.toMutableMap()
                ?: throw ComfyFailure("节点 $nodeId 缺少 inputs 对象")
            val resolvedValue = if (value is JsonPrimitive && value.isString) {
                JsonPrimitive(ComfySubmitterRunner.interpolateHostMetadata(value.content, hostMetadata))
            } else value
            inputs[inputKey] = resolvedValue
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
                    is JsonObject -> listOf("path", "image_path", "file", "video", "audio")
                        .firstNotNullOfOrNull { k -> (elem[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
                    else -> null
                }
            }
            else -> emptyList()
        }

        fun inferUploadKind(field: WorkflowFieldSpec, fileName: String): InputKind = when (field.valueType) {
            FieldValueType.VIDEO_FILE -> InputKind.VIDEO
            FieldValueType.AUDIO_FILE -> InputKind.AUDIO
            FieldValueType.IMAGE_FILE -> InputKind.IMAGE
            else -> when (ComfySubmitterRunner.classifyOutputFile(fileName, null)?.first) {
                MediaKind.VIDEO -> InputKind.VIDEO
                MediaKind.AUDIO -> InputKind.AUDIO
                MediaKind.IMAGE -> InputKind.IMAGE
                else -> when (workflow.mediaCategory) {
                    ComfyMediaCategory.SPEECH, ComfyMediaCategory.MUSIC -> InputKind.AUDIO
                    else -> InputKind.IMAGE
                }
            }
        }

        suspend fun uploadStagedMedia(field: WorkflowFieldSpec, relPath: String): Pair<String, InputKind> {
            val staged = stagedFiles[relPath] ?: throw ComfyFailure("未找到暂存输入文件：$relPath")
            val inputKind = inferUploadKind(field, staged.name)
            if (request.runMode == ComfyWorkflowRunMode.EMIT_WORKFLOW) {
                return "DRY_RUN_${staged.name}" to inputKind
            }
            val mime = ComfySubmitterRunner.classifyOutputFile(staged.name, null)?.second ?: "application/octet-stream"
            val remoteName = staged.inputStream().use { stream ->
                http.upload(host, "in_${UUID.randomUUID().toString().take(8)}_${staged.name}", mime, stream, inputKind)
            }
            return remoteName to inputKind
        }

        val unusedUploadNodeIds = mutableSetOf<String>()
        var nextNumericNodeId = (nodes.keys.mapNotNull { it.toIntOrNull() }.maxOrNull() ?: 0) + 1

        for (field in workflow.fields) {
            val value = mergedInputs[field.key]
            if (field.valueType.isMediaFileType || field.valueType == FieldValueType.ANY_FILE) {
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
        if (request.runMode == ComfyWorkflowRunMode.EMIT_WORKFLOW) {
            val targetPath = customOutputDir?.let { dir ->
                val rel = "$dir/emitted-${workflow.workflowId}-${UUID.randomUUID().toString().take(8)}.json"
                runCatching { resolver.productionPath(request.sessionId, rel) }.getOrDefault(rel)
            }
            val saved = store.save(
                sessionId = request.sessionId,
                payload = MediaPayload.Bytes(filledGraph.toString().toByteArray(Charsets.UTF_8)),
                origin = MediaOrigin.GENERATED,
                mimeTypeHint = "application/json",
                suggestedName = "${workflow.workflowId}-emitted.json",
                targetPath = targetPath,
            )
            return ComfyWorkflowExecutionResult(
                workflow = workflow,
                host = host,
                savedMedia = emptyList(),
                runMode = ComfyWorkflowRunMode.EMIT_WORKFLOW,
                summary = "内置 JSON 工作流导出完成（未提交 GPU）",
                appliedFixedDiffs = fixedDiffs,
                appliedSeeds = appliedSeeds,
                emittedWorkflowPath = saved.path,
                emittedWorkflowJson = filledGraph,
            )
        }

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

        val rawTimeoutSec = workflow.timeoutSeconds ?: maxOf(host.timeoutSeconds, 120)
        // G10: 3D 模型或通用文件类（或自定义 historyOutputFields）直接走通用历史回收，支持 SaveGLB 等任意产物。
        if (workflow.mediaCategory in setOf(ComfyMediaCategory.MODEL_3D, ComfyMediaCategory.FILE) ||
            workflow.historyOutputFields.isNotEmpty()
        ) {
            val savedRefs = backfillOutputsFromHistory(
                host = host,
                workflow = workflow,
                promptId = promptId,
                alreadySavedNames = mutableSetOf(),
                sessionId = request.sessionId,
                customOutputDir = customOutputDir,
                timeoutSeconds = rawTimeoutSec,
                onProgress = request.onProgress,
            )
            if (savedRefs.isEmpty() && !workflow.allowEmptyOutputs) {
                throw ComfyFailure("ComfyUI 任务完成但声明的产出节点没有文件")
            }
            return ComfyWorkflowExecutionResult(
                workflow = workflow,
                host = host,
                savedMedia = savedRefs,
                runMode = ComfyWorkflowRunMode.EXECUTE,
                promptId = promptId,
                summary = "已生成 ${savedRefs.size} 个产物文件",
                appliedFixedDiffs = fixedDiffs,
                appliedSeeds = appliedSeeds,
            )
        }

        val outputSlots = workflow.outputNodeIds.map { outId ->
            val defaultField = when (workflow.mediaCategory) {
                ComfyMediaCategory.SPEECH, ComfyMediaCategory.MUSIC -> "audio"
                ComfyMediaCategory.VIDEO, ComfyMediaCategory.POST_PROCESS -> "videos"
                else -> "images"
            }
            val outputKind = when (workflow.mediaCategory) {
                ComfyMediaCategory.SPEECH, ComfyMediaCategory.MUSIC -> InputKind.AUDIO
                ComfyMediaCategory.VIDEO, ComfyMediaCategory.POST_PROCESS -> InputKind.VIDEO
                else -> InputKind.IMAGE
            }
            ComfySlot(outId, defaultField, SlotKind.OUTPUT, outputKind = outputKind)
        }
        if (outputSlots.isEmpty()) throw ComfyFailure("未声明任何产出节点 outputNodeIds")

        val deadline = clock() + rawTimeoutSec * 1000L
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
            runMode = ComfyWorkflowRunMode.EXECUTE,
            promptId = promptId,
            summary = "已生成 ${savedRefs.size} 个媒体文件",
            appliedFixedDiffs = fixedDiffs,
            appliedSeeds = appliedSeeds,
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
    ): Pair<MutableMap<String, JsonElement>, Map<String, Long>> {
        val knownKeys = workflow.fields.mapTo(mutableSetOf()) { it.key }
        contentInputs.keys.forEach { key ->
            if (key == ComfyWorkflow.CHARACTER_REFERENCE_MODE_OVERRIDE_KEY && key !in knownKeys) {
                throw IllegalArgumentException("字段 $key 属于工作流固定基线能力，如需单次实验覆写请传入 fixed_overrides（将触发确认审批）")
            }
            if (key !in knownKeys) throw IllegalArgumentException("未知工作流字段：$key")
        }
        fixedOverrides.forEach { (key, value) ->
            if (key !in knownKeys) {
                if (key == ComfyWorkflow.CHARACTER_REFERENCE_MODE_OVERRIDE_KEY) {
                    require(GenerationMode.REFERENCE in workflow.capabilities.modes) {
                        "当前工作流未声明参考模式，不支持覆写 $key"
                    }
                    ComfyWorkflow.parseCharacterReferenceModeOverride(value)
                } else {
                    throw IllegalArgumentException("未知工作流字段：$key")
                }
            }
        }
        contentInputs.keys.forEach { key ->
            val spec = workflow.field(key)!!
            require(spec.mutability == FieldMutability.CONTENT) {
                "字段 $key 属于工作流固定字段，如需单次实验覆写请传入 fixed_overrides（将触发确认审批）"
            }
        }
        val merged = LinkedHashMap<String, JsonElement>()
        val appliedSeeds = LinkedHashMap<String, Long>()
        for (field in workflow.fields) {
            val callerValue = when (field.mutability) {
                FieldMutability.CONTENT -> contentInputs[field.key]
                FieldMutability.FIXED -> fixedOverrides[field.key]
            }
            val chosen = when {
                callerValue != null && callerValue !is JsonNull -> callerValue
                field.seedPolicy == SeedPolicy.RANDOM_EACH_RUN && field.valueType == FieldValueType.INT -> {
                    val generated = generateRandomSeed(field.min, field.max)
                    appliedSeeds[field.key] = generated
                    JsonPrimitive(generated)
                }
                else -> field.defaultValue
            }
            field.validateValue(chosen)
            if (chosen != null && chosen !is JsonNull) {
                merged[field.key] = chosen
                if (field.key !in appliedSeeds &&
                    (field.semanticRole == FieldSemanticRole.SEED || field.seedPolicy != SeedPolicy.CALLER)
                ) {
                    (chosen as? JsonPrimitive)?.longOrNull?.let { appliedSeeds[field.key] = it }
                }
            }
        }
        return merged to appliedSeeds
    }

    private fun generateRandomSeed(min: Double?, max: Double?): Long {
        val lo = (min?.toLong() ?: 1L).coerceAtLeast(0L)
        val hi = (max?.toLong() ?: MAX_SAFE_JSON_SEED).coerceAtMost(MAX_SAFE_JSON_SEED)
        if (hi <= lo) return lo
        val bound = hi - lo + 1L
        val raw = (random.nextLong() and Long.MAX_VALUE) % bound
        return lo + raw
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

        suspend fun stageDirectory(rawDirPath: String): File {
            map[rawDirPath]?.let { return it }
            require(!rawDirPath.contains("://")) { "$rawDirPath：不接受外部 URI，请使用工作区目录路径" }
            val safeName = rawDirPath.trimEnd('/').substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "dir" }
            val destDir = File(stagingDir, "dir_${fileIndex++}_$safeName").apply { mkdirs() }
            val staged = stageWorkspaceDirectory?.invoke(sessionId, rawDirPath, destDir) == true
            if (!staged) {
                throw ComfyFailure("输入工作区目录不存在或不可读取：$rawDirPath")
            }
            map[rawDirPath] = destDir
            return destDir
        }

        suspend fun stageOne(rawPath: String, expectedKind: MediaKind?, stageParentDir: Boolean = false) {
            if (rawPath in map) return
            if (stageParentDir) {
                val clean = rawPath.trimEnd('/')
                val parentVirtual = clean.substringBeforeLast('/', "")
                val fileName = clean.substringAfterLast('/')
                if (stageWorkspaceDirectory != null && parentVirtual.isNotBlank() && fileName.isNotBlank()) {
                    val dirDest = stageDirectory(parentVirtual)
                    val target = File(dirDest, fileName)
                    if (target.isFile) {
                        map[rawPath] = target
                        return
                    }
                }
                val resolved = resolveWorkspaceMedia(rawPath) as? MediaResolution.Available
                    ?: throw ComfyFailure("输入文件不存在或不可读取：$rawPath")
                val localParent = (resolved.source as? MediaSource.LocalFile)?.file?.parentFile
                if (localParent != null && localParent.isDirectory) {
                    val dirDest = File(stagingDir, "parent_${fileIndex++}_${localParent.name.replace(Regex("[^A-Za-z0-9._-]"), "_")}").apply { mkdirs() }
                    copyDirectoryBounded(localParent, dirDest)
                    val target = File(dirDest, (resolved.source as MediaSource.LocalFile).file.name)
                    if (target.isFile) {
                        map[rawPath] = target
                        return
                    }
                }
            }
            val resolved = resolveWorkspaceMedia(rawPath) as? MediaResolution.Available
                ?: throw ComfyFailure("输入文件不存在或不可读取：$rawPath")
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
            val v = mergedInputs[field.key] ?: continue
            val isTemplateWithFile = field.cliValueTemplate?.contains("{file}") == true
            if (!field.valueType.isWorkspacePathType && !isTemplateWithFile) continue
            if (field.valueType == FieldValueType.DIRECTORY) {
                val rawDir = (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } ?: continue
                stageDirectory(rawDir)
                continue
            }
            val singleExpected = when (field.valueType) {
                FieldValueType.IMAGE_FILE -> MediaKind.IMAGE
                FieldValueType.VIDEO_FILE -> MediaKind.VIDEO
                FieldValueType.AUDIO_FILE -> MediaKind.AUDIO
                else -> null
            }
            val pathKeys = listOf("file", "path", "image_path", "video", "audio")
            when (v) {
                is JsonPrimitive -> if (v.isString && v.content.isNotBlank()) {
                    stageOne(v.content, singleExpected, stageParentDir = field.stageParentDirectory)
                }
                is JsonObject -> {
                    val path = pathKeys
                        .firstNotNullOfOrNull { k -> (v[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
                    if (!path.isNullOrBlank()) stageOne(path, null, stageParentDir = field.stageParentDirectory)
                }
                is JsonArray -> for (elem in v) {
                    when (elem) {
                        is JsonPrimitive -> if (elem.isString && elem.content.isNotBlank()) {
                            stageOne(elem.content, null, stageParentDir = field.stageParentDirectory)
                        }
                        is JsonObject -> {
                            val path = pathKeys
                                .firstNotNullOfOrNull { k -> (elem[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } }
                            if (!path.isNullOrBlank()) stageOne(path, null, stageParentDir = field.stageParentDirectory)
                        }
                        else -> Unit
                    }
                }
            }
        }
        return map
    }

    private fun copyDirectoryBounded(sourceDir: File, destDir: File) {
        var filesCopied = 0
        var bytesCopied = 0L
        val rootPath = sourceDir.canonicalFile.toPath()
        sourceDir.walkTopDown().maxDepth(4).forEach { file ->
            if (file == sourceDir || file.isDirectory) return@forEach
            val name = file.name
            if (name == ".git" || name == "__pycache__" || name.endsWith(".pyc")) return@forEach
            val rel = runCatching { rootPath.relativize(file.canonicalFile.toPath()).toString() }.getOrNull() ?: return@forEach
            if (rel.startsWith("..") || filesCopied >= MAX_STAGED_DIR_FILES) return@forEach
            val size = file.length()
            if (bytesCopied + size > MAX_STAGED_DIR_BYTES) return@forEach
            val target = File(destDir, rel)
            target.parentFile?.mkdirs()
            file.copyTo(target, overwrite = true)
            filesCopied++
            bytesCopied += size
        }
    }

    private fun defaultMediaDayDirectory(nowMs: Long): String {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(nowMs))
        return "media/$day"
    }

    companion object {
        private const val MAX_SAFE_JSON_SEED = 9_007_199_254_740_991L
        private const val MAX_STAGED_DIR_FILES = 256
        private const val MAX_STAGED_DIR_BYTES = 512L * 1024L * 1024L
        private val DEFAULT_HISTORY_OUTPUT_FIELDS = listOf(
            "images", "gifs", "videos", "video", "audio", "audios",
            "3d", "mesh", "meshes", "glb", "gltf", "files", "model", "models",
        )
    }
}
