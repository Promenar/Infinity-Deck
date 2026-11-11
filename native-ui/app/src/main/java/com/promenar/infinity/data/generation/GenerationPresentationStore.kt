package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.PostProcessStatus
import com.promenar.infinity.data.model.PostProcessTask
import com.promenar.infinity.data.model.PostProcessType
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.RagPhase
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRetry
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class GenerationFailureSurface {
    INLINE_MESSAGE,
    OVERLAY,
}

/** 草稿在生成接受前由应用级状态持有，避免导航销毁页面后无法回滚。 */
internal data class SubmittedGenerationDraft(
    val sessionId: String,
    val text: String,
    val documents: List<MessageDocumentAttachment>,
    val imageUris: List<String>,
    /** 写了批注的引用；回滚时还原成引用卡。 */
    val quotes: List<com.promenar.infinity.data.model.MessageQuote> = emptyList(),
    val submissionId: String = UUID.randomUUID().toString(),
    val userMessageId: String? = null,
    val taskId: String? = null,
    val recoveryReady: Boolean = false,
    val recoveryApplied: Boolean = false,
)

internal data class SessionComposerAttachments(
    val documents: List<MessageDocumentAttachment>,
    val imageUris: List<String>,
    /** 尚未发送的引用卡，含未写批注的。 */
    val quotes: List<com.promenar.infinity.data.chat.queue.ComposerQuoteDraft> = emptyList(),
)

data class GenerationPresentationState(
    val taskId: String,
    val sessionId: String,
    val ragPhases: List<RagPhase> = emptyList(),
    val streamingContent: String = "",
    val error: GenerationFailure? = null,
    val failureSurface: GenerationFailureSurface? = null,
    val providerFailure: ProviderResolution.Failure? = null,
    val generating: Boolean = false,
    val handledFailure: Boolean = false,
    val phase: GenerationPhase? = null,
    val promptAccepted: Boolean = false,
    val postProcessTasks: List<PostProcessTask> = emptyList(),
    /** 正在等待自动重发的请求；新一轮开始输出或生成结束时清除。 */
    val retry: GenerationRetry? = null,
    /** 发送前等待内置 Tailscale 接入的状态；接入后的确认在首个输出到达时清除。 */
    val tailnetWait: TailnetWaitState? = null,
) {
    override fun toString(): String =
        "GenerationPresentationState(taskId=$taskId, sessionId=$sessionId, " +
            "ragPhaseCount=${ragPhases.size}, streamingContentLength=${streamingContent.length}, " +
            "errorCode=${error?.code}, providerFailureReason=${providerFailure?.reason}, " +
            "generating=$generating, handledFailure=$handledFailure, phase=$phase, " +
            "promptAccepted=$promptAccepted, " +
            "postProcessTaskCount=${postProcessTasks.size}, retryAttempt=${retry?.attempt}, " +
            "tailnetWait=${tailnetWait?.let { it::class.simpleName }})"
}

/** 重发前仍在连接或等待首个输出的阶段；进入其它阶段即说明重试已有结果。 */
internal val RETRY_PENDING_PHASES = setOf(
    GenerationPhase.PREPARING,
    GenerationPhase.BUILDING_CONTEXT,
    GenerationPhase.CONNECTING,
    GenerationPhase.THINKING,
)

/** 应用级、按会话隔离的生成展示状态；导航不会改变正在生成任务的写入目标。 */
class GenerationPresentationStore {
    private val entries = ConcurrentHashMap<String, MutableStateFlow<GenerationPresentationState?>>()
    private val submittedDrafts = ConcurrentHashMap<String, MutableStateFlow<SubmittedGenerationDraft?>>()
    private val draftRecoveryLocks = ConcurrentHashMap<String, Mutex>()
    private val composerAttachments = ConcurrentHashMap<String, SessionComposerAttachments>()
    private val composerTexts = ConcurrentHashMap<String, String>()

    private fun submittedDraftEntry(sessionId: String) =
        submittedDrafts.getOrPut(sessionId) { MutableStateFlow(null) }
    internal fun observeSubmittedDraft(sessionId: String): StateFlow<SubmittedGenerationDraft?> = submittedDraftEntry(sessionId)
    internal fun retainSubmittedDraft(draft: SubmittedGenerationDraft) { submittedDraftEntry(draft.sessionId).value = draft }
    internal fun submittedDraft(sessionId: String): SubmittedGenerationDraft? = submittedDrafts[sessionId]?.value
    internal fun markSubmittedDraftRecoverable(sessionId: String) {
        submittedDraftEntry(sessionId).update { current -> current?.copy(recoveryReady = true) }
    }
    internal fun discardSubmittedDraft(draft: SubmittedGenerationDraft) {
        submittedDraftEntry(draft.sessionId).update { current -> if (current == draft) null else current }
    }
    internal fun discardSubmittedDraft(sessionId: String) { submittedDrafts[sessionId]?.value = null }
    internal suspend fun <T> withDraftRecovery(sessionId: String, block: suspend () -> T): T =
        draftRecoveryLocks.getOrPut(sessionId) { Mutex() }.withLock { block() }
    internal fun retainComposerAttachments(sessionId: String, value: SessionComposerAttachments) {
        if (value.documents.isEmpty() && value.imageUris.isEmpty() && value.quotes.isEmpty()) composerAttachments.remove(sessionId)
        else composerAttachments[sessionId] = value
    }
    internal fun composerAttachments(sessionId: String): SessionComposerAttachments? = composerAttachments[sessionId]
    internal fun discardComposerAttachments(sessionId: String) { composerAttachments.remove(sessionId) }
    internal fun retainComposerText(sessionId: String, text: String) { composerTexts[sessionId] = text }
    internal fun composerText(sessionId: String): String? = composerTexts[sessionId]
    internal fun discardComposerText(sessionId: String) { composerTexts.remove(sessionId) }

    fun observe(sessionId: String): StateFlow<GenerationPresentationState?> = entry(sessionId)

    fun begin(sessionId: String, taskId: String) {
        entries.compute(sessionId) { _, existing ->
            (existing ?: MutableStateFlow<GenerationPresentationState?>(null)).also { flow ->
                flow.value = GenerationPresentationState(taskId = taskId, sessionId = sessionId)
            }
        }
    }

    fun queued(sessionId: String, taskId: String) = update(sessionId, taskId) {
        it.copy(phase = GenerationPhase.QUEUED, generating = true)
    }

    fun finish(sessionId: String, taskId: String) {
        val flow = entries[sessionId] ?: return
        if (flow.value?.taskId != taskId) return
        flow.value = null
    }

    fun release(sessionId: String) {
        // 页面生命周期不得替换按会话订阅的 flow；终态内容由 finish 清空。
        if (!entries.containsKey(sessionId)) return
    }

    fun port(sessionId: String, taskId: String): GenerationUiPort = SessionPort(sessionId, taskId)

    suspend fun accept(sessionId: String, taskId: String, event: GenerationEvent) {
        update(sessionId, taskId) { current ->
            when (event) {
                is GenerationEvent.ProgressChanged -> current
                is GenerationEvent.RetryScheduled -> current.copy(retry = event.retry)
                is GenerationEvent.PhaseChanged -> current.copy(
                    phase = event.phase,
                    retry = current.retry.takeIf { event.phase in RETRY_PENDING_PHASES },
                    promptAccepted = current.promptAccepted ||
                        event.phase == GenerationPhase.CONNECTING,
                    generating = event.phase !in setOf(
                        GenerationPhase.COMPLETED,
                        GenerationPhase.FAILED,
                        GenerationPhase.CANCELLED,
                        GenerationPhase.PERSISTENCE_FAILED,
                        GenerationPhase.WAITING_APPROVAL,
                        GenerationPhase.WAITING_INPUT,
                    ),
                )
                is GenerationEvent.SnapshotChanged -> current.copy(
                    tailnetWait = current.tailnetWait.takeUnless { it == TailnetWaitState.Connected },
                    streamingContent = event.snapshot.content,
                    error = event.snapshot.failure,
                    failureSurface = if (event.snapshot.failure != null) {
                        GenerationFailureSurface.INLINE_MESSAGE
                    } else null,
                )
                is GenerationEvent.TargetChanged -> current
                is GenerationEvent.Rejected -> current.copy(
                    retry = null,
                    error = event.failure,
                    generating = false,
                    handledFailure = true,
                    phase = GenerationPhase.FAILED,
                )
                is GenerationEvent.PersistenceFailed -> current.copy(
                    error = event.failure,
                    failureSurface = GenerationFailureSurface.OVERLAY,
                    generating = false,
                    handledFailure = true,
                    phase = GenerationPhase.PERSISTENCE_FAILED,
                )
                is GenerationEvent.Failed -> current.copy(
                    retry = null,
                    error = event.failure,
                    generating = false,
                    phase = GenerationPhase.FAILED,
                )
            }
        }
    }

    fun publishTerminal(
        sessionId: String,
        taskId: String,
        phase: GenerationPhase,
        failure: GenerationFailure?,
    ) = update(sessionId, taskId) { current ->
        current.copy(
            phase = phase,
            generating = false,
            error = failure ?: current.error,
            retry = null,
        )
    }

    private fun entry(sessionId: String): MutableStateFlow<GenerationPresentationState?> =
        entries.getOrPut(sessionId) { MutableStateFlow(null) }

    internal fun retainedSessionStateCount(): Int = entries.values.count { it.value != null }

    private fun update(
        sessionId: String,
        taskId: String,
        transform: (GenerationPresentationState) -> GenerationPresentationState,
    ) = entry(sessionId).update { current ->
        if (current?.taskId == taskId) transform(current) else current
    }

    private inner class SessionPort(
        private val sessionId: String,
        private val taskId: String,
    ) : GenerationUiPort {
        override fun replaceRagPhases(phases: List<RagPhase>) =
            update(sessionId, taskId) { it.copy(ragPhases = phases) }

        override fun updateRagPhases(transform: (List<RagPhase>) -> List<RagPhase>) =
            update(sessionId, taskId) { it.copy(ragPhases = transform(it.ragPhases)) }

        override fun setGenerating(value: Boolean) = update(sessionId, taskId) {
            it.copy(generating = value, handledFailure = if (value) false else it.handledFailure)
        }

        override fun setStreamingContent(value: String) {
            val previous = entry(sessionId).value
            val firstText = previous?.taskId == taskId && previous.streamingContent.isEmpty() && value.isNotEmpty()
            update(sessionId, taskId) { it.copy(streamingContent = value) }
            if (firstText && entry(sessionId).value?.taskId == taskId) {
                com.promenar.infinity.utils.InfinityLogger.diagnostic("generation.presentation_first_text", mapOf(
                    "sessionId" to sessionId, "taskId" to taskId,
                    "monotonicMs" to System.nanoTime() / 1_000_000,
                ))
            }
        }

        override fun setError(failure: GenerationFailure?) =
            update(sessionId, taskId) { it.copy(error = failure) }

        override fun setFailureSurface(surface: GenerationFailureSurface?) =
            update(sessionId, taskId) { it.copy(failureSurface = surface) }

        override fun setProviderFailure(failure: ProviderResolution.Failure?) =
            update(sessionId, taskId) { it.copy(providerFailure = failure) }

        override fun onHandledFailure() = update(sessionId, taskId) {
            it.copy(generating = false, handledFailure = true)
        }

        override fun addPostProcessTask(type: PostProcessType, detail: String): String {
            val id = UUID.randomUUID().toString()
            update(sessionId, taskId) {
                it.copy(
                    postProcessTasks = it.postProcessTasks + PostProcessTask(
                        id = id,
                        type = type,
                        status = PostProcessStatus.RUNNING,
                        progress = 0f,
                        detail = detail,
                    ),
                )
            }
            return id
        }

        override fun updatePostProcessTask(
            id: String,
            status: PostProcessStatus?,
            progress: Float?,
            detail: String?,
        ) = update(sessionId, taskId) { current ->
            current.copy(
                postProcessTasks = current.postProcessTasks.map { task ->
                    if (task.id == id) task.copy(
                        status = status ?: task.status,
                        progress = progress ?: task.progress,
                        detail = detail ?: task.detail,
                    ) else task
                },
            )
        }

        override fun removePostProcessTask(id: String) = update(sessionId, taskId) {
            it.copy(postProcessTasks = it.postProcessTasks.filterNot { task -> task.id == id })
        }

        override fun setTailnetWait(state: TailnetWaitState?) = update(sessionId, taskId) { it.copy(tailnetWait = state) }
    }
}
