package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionResult
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionRunService
import com.promenar.infinity.data.production.ProductionWorkStore
import com.promenar.infinity.data.production.RunTarget
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.mediagen.ComfyWorkflow
import com.promenar.infinity.domain.mediagen.FieldSemanticRole
import com.promenar.infinity.domain.mediagen.FieldValueType
import com.promenar.infinity.ui.production.ShotLocation
import com.promenar.infinity.ui.production.selectedTake
import com.promenar.infinity.ui.production.shotLocations
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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
) {
    data class ResolvedShotBinding(
        val production: Production,
        val location: ShotLocation,
    )

    data class ActiveRunHandle(
        val sessionId: String,
        val runId: String,
        val location: ShotLocation,
        val workflow: ComfyWorkflow,
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
    )

    suspend fun resolveShot(sessionId: String, shotRef: String?): ResolvedShotBinding? {
        val raw = shotRef?.trim()?.takeIf(String::isNotBlank) ?: return null
        val production = runCatching { workStore.load(sessionId)?.production }.getOrNull() ?: return null
        val loc = matchShotLocation(production, raw) ?: return null
        return ResolvedShotBinding(production, loc)
    }

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
            it.semanticRole == FieldSemanticRole.GUIDES || it.valueType == FieldValueType.TIMED_FILES
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
    ): ActiveRunHandle? = try {
        val stageSuffix = when (stage) {
            "first" -> "（首段）"
            "next" -> "（续写）"
            else -> ""
        }
        val label = "${workflow.name}$stageSuffix".take(120)
        val run = runService.start(
            sessionId = sessionId,
            target = RunTarget(episodeId = binding.location.episode.id, shotCode = binding.location.shot.code),
            label = label,
            steps = 100,
        )
        ActiveRunHandle(sessionId, run.id, binding.location, workflow)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    suspend fun onProgress(handle: ActiveRunHandle?, pct: Float?, message: String) {
        if (handle == null) return
        val cleanMsg = message.trim().take(1000).ifBlank { "执行中" }
        val targetSteps = pct?.let { (it * 100f).roundToInt().coerceIn(0, 99) } ?: handle.lastReportedSteps
        val monotonicSteps = maxOf(handle.lastReportedSteps, targetSteps)
        val currentNow = now()
        val shouldSync = monotonicSteps >= handle.lastReportedSteps + 2 ||
            (cleanMsg != handle.lastMessage && currentNow - handle.lastReportedAt >= 800L)
        if (!shouldSync) return
        try {
            runService.progress(handle.sessionId, handle.runId, monotonicSteps, cleanMsg)
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
        val videoRef = result.savedMedia.firstOrNull { it.kind == MediaKind.VIDEO }
        if (videoRef != null) {
            try {
                val latest = workStore.load(handle.sessionId)?.production
                val shot = latest?.let { matchShotLocation(it, "${handle.location.episode.id}/${handle.location.shot.code}")?.shot }
                val isVideoDerive = handle.workflow.fields.any {
                    it.semanticRole == FieldSemanticRole.INPUT_VIDEO || it.valueType == FieldValueType.VIDEO_FILE
                }
                val sourceTakeId = if (isVideoDerive && shot != null) {
                    shot.selectedTake()?.id ?: shot.takes.filter { it.derivedFrom == null }.maxByOrNull { it.createdAt }?.id
                } else null
                val operation = if (sourceTakeId != null) handle.workflow.name.take(120) else null
                val finished = runService.finish(
                    sessionId = handle.sessionId,
                    runId = handle.runId,
                    path = videoRef.path,
                    sourceTakeId = sourceTakeId,
                    operation = operation,
                    serviceName = "ComfyUI（${handle.workflow.name}）".take(120),
                )
                return CompletionSync(handle.runId, finished.resultTakeId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 若输出不在项目根目录下或无有效视频规格，仍完成队列任务状态而不抛错
            }
        }
        return try {
            runService.completeWithoutMedia(handle.sessionId, handle.runId, result.summary)
            CompletionSync(handle.runId, null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    suspend fun onFail(handle: ActiveRunHandle?, reason: String) {
        if (handle == null) return
        try {
            runService.fail(handle.sessionId, handle.runId, reason.trim().take(1000).ifBlank { "ComfyUI 工作流执行失败" })
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 避免掩盖原始执行异常
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
