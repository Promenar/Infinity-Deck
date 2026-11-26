package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.mediagen.comfy.ComfyErrorDiagnostics
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionResult
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionRunService
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionSubmissionMutex
import com.promenar.infinity.data.production.ProductionWorkStore
import com.promenar.infinity.data.production.RunTarget
import com.promenar.infinity.data.production.RunState
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.acceptsVideoDerivation
import com.promenar.infinity.data.production.isVideoInput
import com.promenar.infinity.data.production.occupiedShotCandidates
import com.promenar.infinity.data.production.releaseShotCandidate
import com.promenar.infinity.data.production.reserveShotCandidate
import com.promenar.infinity.data.production.retryProductionRunRevision
import com.promenar.infinity.data.production.shotTakesDirectory
import com.promenar.infinity.data.production.videoInputField
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.mediagen.ComfyMediaCategory
import com.promenar.infinity.domain.mediagen.ComfyWorkflow
import com.promenar.infinity.domain.mediagen.FieldMutability
import com.promenar.infinity.domain.mediagen.FieldSemanticRole
import com.promenar.infinity.domain.mediagen.FieldValueType
import com.promenar.infinity.domain.mediagen.MediaGenerationQueue
import com.promenar.infinity.ui.production.ShotLocation
import com.promenar.infinity.ui.production.shotLocations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.roundToInt

/**
 * 通用桥接：把 `comfy_run_workflow` 与当前会话绑定的制作项目衔接起来（不硬编码任何具体工作流）。
 * 1. 若目标工作流声明了 `GUIDES / TIMED_FILES` 槽且调用方未显式传入，自动从绑定镜头的 `InputRole.ANCHOR` 透传；
 * 2. 在 `EXECUTE` 模式下自动向总览「任务」队列回报开始、实时进度与结束/失败状态，并在视频落盘于项目目录时自动登记候选。
 */
class ComfyProductionRunBridge(
    private val workStore: ProductionWorkStore,
    private val runService: ProductionRunService,
    private val now: () -> Long = System::currentTimeMillis,
    private val queue: () -> MediaGenerationQueue? = { null },
) {
    data class ResolvedShotBinding(
        val snapshot: ProductionSnapshot,
        val location: ShotLocation,
    ) {
        val production get() = snapshot.production
    }

    data class ActiveRunHandle(
        val sessionId: String,
        val runId: String,
        val productionId: String,
        val rootPath: String,
        val workflow: ComfyWorkflow,
        val sourceTakeId: String?,
        val derivationParams: Map<String, JsonElement>,
    ) {
        @Volatile
        internal var lastReportedSteps: Int = 0

        @Volatile
        internal var lastReportedAt: Long = 0L

        @Volatile
        internal var lastMessage: String = ""
    }

    data class CompletionSync(
        val runId: String,
        val registeredTakeId: String? = null,
        val registrationError: String? = null,
    )

    suspend fun resolveShot(sessionId: String, shotRef: String?): ResolvedShotBinding? {
        val raw = shotRef?.trim()?.takeIf(String::isNotBlank) ?: return null
        val snapshot = workStore.load(sessionId) ?: return null
        val loc = matchShotLocation(snapshot.production, raw) ?: return null
        return ResolvedShotBinding(snapshot, loc)
    }

    fun defaultOutputDir(binding: ResolvedShotBinding): String =
        shotTakesDirectory(binding.production, binding.location.episode.id)

    /**
     * 若工作流声明了 `GUIDES` / `TIMED_FILES` 字段且 `contentInputs` 未显式提供该键，
     * 自动将镜头中登记的 `InputRole.ANCHOR` 输入转为通用 `[{frame_idx, file}]` 数组。
     */
    fun mergeAnchorGuidesIfMissing(
        workflow: ComfyWorkflow,
        shot: Shot?,
        contentInputs: Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        if (shot == null) return contentInputs
        val guideField = workflow.fields.firstOrNull {
            it.mutability == FieldMutability.CONTENT &&
                (it.semanticRole == FieldSemanticRole.GUIDES || it.valueType == FieldValueType.TIMED_FILES)
        } ?: return contentInputs
        if (guideField.key in contentInputs) return contentInputs
        val anchors = shot.inputs.filter { it.role == InputRole.ANCHOR }
        if (anchors.isEmpty()) return contentInputs
        val fps = workflow.fps?.takeIf { it.isFinite() && it > 0.0 } ?: 24.0
        val guideArray = JsonArray(
            anchors.map { anchor ->
                val frameIdx = ((anchor.atSeconds ?: 0.0) * fps).roundToInt().coerceAtLeast(0)
                buildJsonObject {
                    put("frame_idx", frameIdx)
                    put("file", anchor.path)
                }
            },
        )
        return contentInputs + (guideField.key to guideArray)
    }

    suspend fun onStart(
        sessionId: String,
        binding: ResolvedShotBinding,
        workflow: ComfyWorkflow,
        stage: String,
        contentInputs: Map<String, JsonElement> = emptyMap(),
        fixedOverrides: Map<String, JsonElement> = emptyMap(),
    ): ActiveRunHandle {
        val stageSuffix = when (stage) {
            "first" -> "（首段）"
            "next" -> "（续写）"
            else -> ""
        }
        val label = "${workflow.name}$stageSuffix".take(120)
        val field = workflow.videoInputField() ?: workflow.fields.firstOrNull { it.isVideoInput() }
        val sourceTakeId = field?.let {
            val inputs = if (it.mutability == FieldMutability.FIXED) fixedOverrides else contentInputs
            val explicit = inputs[it.key]?.takeUnless { value -> value == JsonNull }
            val default = if (it.key in workflow.chainSpec?.lockedInheritedKeys.orEmpty()) null else it.defaultValue
            val input = (explicit ?: default) as? JsonPrimitive
            val path = input?.takeIf { value -> value.isString }?.content?.takeIf(String::isNotBlank) ?: return@let null
            val shot = binding.location.shot
            val normalized = try {
                runService.normalizeRunPath(sessionId, path, binding.production.rootPath)
            } catch (_: IllegalArgumentException) {
                null
            }
            shot.takes.filter { take -> take.path == normalized }
                .maxWithOrNull(compareBy({ take -> take.id == shot.selectedTakeId }, { take -> take.createdAt }))?.id
        }
        val episodeId = binding.location.episode.id
        val shotCode = binding.location.shot.code
        val reserveCandidate = workflow.mediaCategory == ComfyMediaCategory.VIDEO || workflow.acceptsVideoDerivation()
        val derivationParams = if (sourceTakeId == null) emptyMap() else workflow.fields
            .filter { it.mutability == FieldMutability.CONTENT && !it.isVideoInput() }
            .mapNotNull { spec -> contentInputs[spec.key]?.takeUnless { it == JsonNull }?.let { spec.key to it } }
            .toMap()
        return ProductionSubmissionMutex.withLock {
            val current = workStore.load(sessionId)
            require(current != null && current.production.id == binding.production.id && current.revision == binding.snapshot.revision) {
                "制作项目或镜头已变化，请重新读取后执行。"
            }
            if (reserveCandidate) {
                require(occupiedShotCandidates(binding.production, episodeId, shotCode, queue()) < 30) { "候选容量不足。" }
            }
            withContext(NonCancellable) {
                val run = runService.start(sessionId, RunTarget(episodeId, shotCode), label, steps = 100, expected = binding.snapshot)
                if (reserveCandidate) run.reserveShotCandidate(binding.production.id)
                ActiveRunHandle(sessionId, run.id, binding.production.id, binding.production.rootPath, workflow, sourceTakeId, derivationParams)
            }
        }
    }

    suspend fun onProgress(handle: ActiveRunHandle?, pct: Float?, message: String) {
        if (handle == null) return
        val cleanMsg = message.trim().take(1000).ifBlank { "执行中" }
        val targetSteps = pct?.takeIf { it.isFinite() }?.let { (it * 100f).roundToInt().coerceIn(0, 99) } ?: handle.lastReportedSteps
        val monotonicSteps = maxOf(handle.lastReportedSteps, targetSteps)
        val currentNow = now()
        val shouldSync = monotonicSteps >= handle.lastReportedSteps + 2 ||
            (cleanMsg != handle.lastMessage && currentNow - handle.lastReportedAt >= 800L)
        if (!shouldSync) return
        try {
            retryProductionRunRevision {
                runService.progress(handle.sessionId, handle.runId, monotonicSteps, cleanMsg, handle.productionId)
            }
            handle.lastReportedSteps = monotonicSteps
            handle.lastReportedAt = currentNow
            handle.lastMessage = cleanMsg
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 进度回报不阻断工作流执行
        }
    }

    suspend fun onComplete(handle: ActiveRunHandle?, result: ComfyWorkflowExecutionResult): CompletionSync? {
        if (handle == null) return null
        return try {
            completeRun(handle, result)
        } finally {
            releaseShotCandidate(handle.runId)
        }
    }

    private suspend fun completeRun(handle: ActiveRunHandle, result: ComfyWorkflowExecutionResult): CompletionSync {
        return try {
            val projectVideo = result.savedMedia.firstOrNull { media ->
                media.kind == MediaKind.VIDEO && try {
                    runService.normalizeRunPath(handle.sessionId, media.path, handle.rootPath).startsWith("${handle.rootPath}/")
                } catch (_: IllegalArgumentException) {
                    false
                }
            }
            val finished = retryProductionRunRevision {
                if (projectVideo == null) {
                    runService.completeWithoutMedia(handle.sessionId, handle.runId, result.summary, handle.productionId)
                } else {
                    runService.finish(
                        sessionId = handle.sessionId,
                        runId = handle.runId,
                        path = projectVideo.path,
                        sourceTakeId = handle.sourceTakeId,
                        operation = handle.sourceTakeId?.let { handle.workflow.name },
                        params = handle.derivationParams,
                        serviceName = "ComfyUI（${handle.workflow.name}）".take(120),
                        expectedProductionId = handle.productionId,
                    )
                }
            }
            CompletionSync(handle.runId, finished.resultTakeId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val reason = ComfyErrorDiagnostics.redactSensitive("制作候选登记失败。 ${failure.message.orEmpty()}").trim().take(1000)
            val run = runService.reportWorkflowFailure(handle.sessionId, handle.productionId, handle.runId, reason)
            if (run?.state == RunState.COMPLETED) CompletionSync(handle.runId, run.resultTakeId)
            else CompletionSync(handle.runId, registrationError = run?.failure ?: reason)
        }
    }

    suspend fun onFail(handle: ActiveRunHandle?, reason: String) {
        if (handle == null) return
        try {
            val detail = ComfyErrorDiagnostics.redactSensitive(reason).trim().take(1000).ifBlank { "ComfyUI 工作流执行失败" }
            runService.reportWorkflowFailure(handle.sessionId, handle.productionId, handle.runId, detail)
        } finally {
            releaseShotCandidate(handle.runId)
        }
    }

    companion object {
        internal fun matchShotLocation(production: Production, shotRef: String): ShotLocation? {
            val clean = shotRef.trim()
            if (clean.isEmpty()) return null
            val parts = clean.split('/', ':', limit = 2).map(String::trim).filter(String::isNotEmpty)
            val all = production.shotLocations().toList()
            if (parts.size == 2) {
                return all.firstOrNull {
                    it.episode.id.equals(parts[0], ignoreCase = true) &&
                        it.shot.code.equals(parts[1], ignoreCase = true)
                }
            }
            val matches = all.filter { it.shot.code.equals(clean, ignoreCase = true) }
            return matches.singleOrNull()
        }
    }
}
