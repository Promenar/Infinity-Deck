package com.promenar.infinity.data.generation

import com.promenar.infinity.domain.generation.CancellationReason
import com.promenar.infinity.domain.generation.GenerationCoordinator
import com.promenar.infinity.domain.generation.GenerationError
import com.promenar.infinity.domain.generation.GenerationEvent
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationRequest
import com.promenar.infinity.domain.generation.GenerationRunner
import com.promenar.infinity.domain.generation.GenerationTaskSnapshot
import com.promenar.infinity.domain.generation.MAX_CONCURRENT_GENERATIONS
import com.promenar.infinity.domain.generation.StartGenerationResult
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

fun interface GenerationRunnerFactory {
    fun create(request: GenerationRequest, taskId: String): GenerationRunner
}

/** 排队时尚未运行的任务也需要结清已写入的消息和发送租约。 */
internal interface QueuedGenerationCancellation {
    suspend fun cancelBeforeStart(request: GenerationRequest)
}

class DefaultGenerationCoordinator(
    private val applicationScope: CoroutineScope,
    private val runnerFactory: GenerationRunnerFactory,
    private val presentationStore: GenerationPresentationStore = GenerationPresentationStore(),
    private val taskIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val terminalRetentionLimit: Int = 32,
    private val executionGate: com.promenar.infinity.data.session.SessionExecutionGate? = null,
    private val compactionService: ContextCompactionService? = null,
    private val maxConcurrent: Int = MAX_CONCURRENT_GENERATIONS,
) : GenerationCoordinator {
    private class Task(
        val taskId: String,
        val request: GenerationRequest,
        val runner: GenerationRunner,
        var snapshot: GenerationTaskSnapshot,
    ) {
        var job: Job? = null
        var occupiesSlot = false
        var terminalAcknowledged = false
        var queuedCleanup = false
        var runnerEntered = false
        var finished = false
        val completion = CompletableDeferred<GenerationTaskSnapshot?>()
    }

    private val lifecycleLock = Any()
    private val tasks = linkedMapOf<String, Task>()
    private val queue = ArrayDeque<Task>()
    private val terminalOrder = ArrayDeque<String>()
    private val recent = linkedMapOf<String, Unit>()
    private val completions = linkedMapOf<String, CompletableDeferred<GenerationTaskSnapshot?>>()
    private val sessionStates = ConcurrentHashMap<String, MutableStateFlow<GenerationTaskSnapshot?>>()
    private val mutableSessions = MutableStateFlow<Map<String, GenerationTaskSnapshot>>(emptyMap())
    private val mutableActive = MutableStateFlow<GenerationTaskSnapshot?>(null)
    private var startSequence = 0L
    private var queueDrainSuspensions = 0

    override val sessions: StateFlow<Map<String, GenerationTaskSnapshot>> = mutableSessions
    override val active: StateFlow<GenerationTaskSnapshot?> = mutableActive

    override fun observe(sessionId: String): StateFlow<GenerationTaskSnapshot?> =
        sessionStates.getOrPut(sessionId) { MutableStateFlow(null) }

    override fun isSessionBusy(sessionId: String): Boolean = synchronized(lifecycleLock) {
        tasks.containsKey(sessionId)
    }

    override suspend fun start(request: GenerationRequest): StartGenerationResult = start(request) { true }

    override suspend fun start(request: GenerationRequest, onAdmission: (String) -> Boolean): StartGenerationResult {
        val operation: suspend () -> StartGenerationResult = {
            try {
                val gate = executionGate
                if (gate == null) startAdmitted(request, onAdmission)
                else gate.withSessionMutation(request.sessionId) { startAdmitted(request, onAdmission) }
            } catch (failure: com.promenar.infinity.data.session.SessionDeletingException) {
                StartGenerationResult.Rejected(GenerationError(
                    com.promenar.infinity.domain.generation.GenerationFailure.unknown(
                        technical = "session_deleting", cause = failure,
                    ),
                ))
            }
        }
        return compactionService?.withGenerationStart(request.sessionId, operation) ?: operation()
    }

    private fun startAdmitted(request: GenerationRequest, onAdmission: (String) -> Boolean): StartGenerationResult = synchronized(lifecycleLock) {
        tasks[request.sessionId]?.let { current ->
            return@synchronized if (current.request.requestId == request.requestId) {
                try {
                    if (onAdmission(current.taskId)) StartGenerationResult.Existing(current.snapshot)
                    else StartGenerationResult.Busy(request.sessionId)
                } catch (failure: Throwable) {
                    StartGenerationResult.Rejected(GenerationError(failure.failureOrUnknown()))
                }
            } else {
                StartGenerationResult.Busy(request.sessionId)
            }
        }
        val taskId = taskIdFactory()
        val runner = try {
            presentationStore.begin(request.sessionId, taskId)
            runnerFactory.create(request, taskId)
        } catch (failure: Throwable) {
            presentationStore.finish(request.sessionId, taskId)
            return@synchronized StartGenerationResult.Rejected(GenerationError(failure.failureOrUnknown()))
        }
        try {
            if (!onAdmission(taskId)) {
                presentationStore.finish(request.sessionId, taskId)
                return@synchronized StartGenerationResult.Busy(request.sessionId)
            }
        } catch (failure: Throwable) {
            presentationStore.finish(request.sessionId, taskId)
            return@synchronized StartGenerationResult.Rejected(GenerationError(failure.failureOrUnknown()))
        }
        terminalOrder.remove(request.sessionId)
        val queued = occupiedSlotsLocked() >= maxConcurrent || queue.isNotEmpty()
        val snapshot = GenerationTaskSnapshot(
            taskId = taskId,
            sessionId = request.sessionId,
            assistantMessageId = request.assistantMessageId,
            phase = if (queued) GenerationPhase.QUEUED else GenerationPhase.PREPARING,
            generatedChars = 0,
            startedAt = clock(),
            queuePosition = if (queued) queue.size + 1 else null,
            runtimePolicy = request.runtimePolicy,
        )
        val task = Task(taskId, request, runner, snapshot)
        tasks[request.sessionId] = task
        completions[taskId] = task.completion
        if (queued) {
            presentationStore.queued(request.sessionId, taskId)
            queue.addLast(task)
            publishLocked(task, snapshot)
            StartGenerationResult.Queued(taskId, snapshot.queuePosition ?: 1)
        } else {
            launchLocked(task)
            StartGenerationResult.Started(taskId)
        }
    }

    private fun launchLocked(task: Task) {
        task.occupiesSlot = true
        val wasQueued = task.snapshot.phase == GenerationPhase.QUEUED
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                val run: suspend () -> Unit = {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    synchronized(lifecycleLock) { task.runnerEntered = true }
                    task.runner.run(task.request) { event -> handleEvent(task, event) }
                }
                val gate = executionGate
                if (gate == null) run() else gate.withNewSessionAdmission(task.request.sessionId, run)
            } catch (cancelled: CancellationException) {
                synchronized(lifecycleLock) { if (!task.runnerEntered) task.queuedCleanup = true }
                publishTerminal(task, GenerationPhase.CANCELLED, GenerationError(
                    com.promenar.infinity.domain.generation.GenerationFailure.unknown(
                        technical = cancelled.message, cause = cancelled,
                    ),
                ))
                throw cancelled
            } catch (failure: Throwable) {
                publishTerminal(task, GenerationPhase.FAILED, GenerationError(failure.failureOrUnknown()))
            }
        }
        task.job = job
        job.invokeOnCompletion { cause ->
            synchronized(lifecycleLock) {
                if (cause is CancellationException && !task.runnerEntered && !task.finished) {
                    startPreRunCleanupLocked(task)
                } else finishTask(task, cause)
            }
        }
        publishLocked(task, task.snapshot.copy(
            phase = GenerationPhase.PREPARING, queuePosition = null,
            startedAt = if (wasQueued) clock() else task.snapshot.startedAt,
            startedOrder = ++startSequence,
        ))
        job.start()
    }

    private suspend fun handleEvent(task: Task, event: GenerationEvent) {
        presentationStore.accept(task.request.sessionId, task.taskId, event)
        synchronized(lifecycleLock) {
            if (tasks[task.request.sessionId] !== task || task.finished) return
            val previous = task.snapshot
            val next = when (event) {
                is GenerationEvent.ProgressChanged -> previous.copy(progress = event.progress)
                is GenerationEvent.RetryScheduled -> previous.copy(retry = event.retry)
                is GenerationEvent.PhaseChanged -> previous.copy(
                    phase = event.phase,
                    retry = previous.retry.takeIf { event.phase in RETRY_PENDING_PHASES },
                )
                is GenerationEvent.SnapshotChanged -> previous.copy(
                    generatedChars = event.snapshot.content.length + event.snapshot.reasoning.length,
                )
                is GenerationEvent.TargetChanged -> previous.copy(assistantMessageId = event.assistantMessageId)
                is GenerationEvent.Rejected -> previous.copy(phase = GenerationPhase.FAILED, error = GenerationError(event.failure))
                is GenerationEvent.PersistenceFailed -> previous.copy(phase = GenerationPhase.PERSISTENCE_FAILED, error = GenerationError(event.failure))
                is GenerationEvent.Failed -> previous.copy(phase = GenerationPhase.FAILED, error = GenerationError(event.failure))
            }
            if (next.phase in SLOT_RELEASE_PHASES) releaseSlotLocked(task)
            publishLocked(task, next)
        }
    }

    private fun publishTerminal(task: Task, phase: GenerationPhase, error: GenerationError?) = synchronized(lifecycleLock) {
        if (tasks[task.request.sessionId] !== task || task.finished || task.snapshot.phase == GenerationPhase.PERSISTENCE_FAILED) return@synchronized
        val next = task.snapshot.copy(phase = phase, error = error, queuePosition = null)
        presentationStore.publishTerminal(task.request.sessionId, task.taskId, phase, error?.failure)
        releaseSlotLocked(task)
        publishLocked(task, next)
    }

    private fun finishTask(task: Task, cause: Throwable?) = synchronized(lifecycleLock) {
        if (task.finished || tasks[task.request.sessionId] !== task) return@synchronized
        if (cause != null && task.snapshot.phase !in SLOT_RELEASE_PHASES) {
            val phase = if (cause is CancellationException) GenerationPhase.CANCELLED else GenerationPhase.FAILED
            val error = GenerationError(cause.failureOrUnknown())
            presentationStore.publishTerminal(task.request.sessionId, task.taskId, phase, error.failure)
            publishLocked(task, task.snapshot.copy(phase = phase, error = error, queuePosition = null))
        }
        task.finished = true
        tasks.remove(task.request.sessionId)
        releaseSlotLocked(task)
        if (task.terminalAcknowledged) {
            presentationStore.finish(task.request.sessionId, task.taskId)
            clearSessionLocked(task.request.sessionId, task.taskId)
        } else if (task.snapshot.phase in TERMINAL_PHASES) {
            retainTerminalLocked(task.request.sessionId, task.taskId)
        } else {
            presentationStore.finish(task.request.sessionId, task.taskId)
            clearSessionLocked(task.request.sessionId, task.taskId)
        }
        task.completion.complete(task.snapshot)
        while (completions.size > terminalRetentionLimit.coerceAtLeast(0) + maxConcurrent + queue.size) {
            val completed = completions.entries.firstOrNull { it.value.isCompleted && it.key != task.taskId } ?: break
            completions.remove(completed.key)
        }
    }

    private fun releaseSlotLocked(task: Task) {
        if (!task.occupiesSlot) return
        task.occupiesSlot = false
        drainQueueLocked()
    }

    private fun drainQueueLocked() {
        if (queueDrainSuspensions > 0) return
        while (queue.isNotEmpty() && occupiedSlotsLocked() < maxConcurrent) {
            val task = queue.removeFirst()
            launchLocked(task)
        }
        updateQueuePositionsLocked()
    }

    private fun updateQueuePositionsLocked() {
        queue.forEachIndexed { index, task ->
            if (task.snapshot.queuePosition != index + 1) {
                publishLocked(task, task.snapshot.copy(queuePosition = index + 1))
            }
        }
    }

    private fun occupiedSlotsLocked() = tasks.values.count { it.occupiesSlot }

    override suspend fun awaitSessionIdle(sessionId: String) {
        val completion = synchronized(lifecycleLock) { tasks[sessionId]?.completion }
        completion?.await()
    }

    override suspend fun awaitTaskCompletion(sessionId: String, taskId: String): GenerationTaskSnapshot? {
        val completion = synchronized(lifecycleLock) { completions[taskId] }
        return completion?.await()?.takeIf { it.sessionId == sessionId }
    }

    override fun cancel(taskId: String, reason: CancellationReason): Boolean = synchronized(lifecycleLock) {
        val task = tasks.values.firstOrNull { it.taskId == taskId } ?: return@synchronized false
        if (task.snapshot.phase in SLOT_RELEASE_PHASES) return@synchronized false
        if (queue.remove(task)) {
            startPreRunCleanupLocked(task)
            updateQueuePositionsLocked()
        } else {
            task.job?.cancel(CancellationException("Generation cancelled: $reason")) ?: return@synchronized false
        }
        true
    }

    private fun startPreRunCleanupLocked(task: Task) {
        task.queuedCleanup = true
        if (task.snapshot.phase != GenerationPhase.CANCELLED) {
            presentationStore.publishTerminal(task.request.sessionId, task.taskId, GenerationPhase.CANCELLED, null)
            publishLocked(task, task.snapshot.copy(phase = GenerationPhase.CANCELLED, queuePosition = null))
            releaseSlotLocked(task)
        }
        val job = applicationScope.launch(start = CoroutineStart.LAZY) {
            try {
                val cleanup: suspend () -> Unit = {
                    (task.runner as? QueuedGenerationCancellation)?.cancelBeforeStart(task.request)
                }
                val gate = executionGate
                if (gate == null) cleanup() else gate.withNewSessionAdmission(task.request.sessionId, cleanup)
            } catch (_: com.promenar.infinity.data.session.SessionDeletingException) {
                // 删除已经关闭写入准入，会话数据由删除事务处理。
            } catch (failure: Throwable) {
                synchronized(lifecycleLock) { task.terminalAcknowledged = false }
                publishTerminal(task, GenerationPhase.PERSISTENCE_FAILED, GenerationError(
                    com.promenar.infinity.domain.generation.GenerationFailure.persistence(
                        technical = failure.message, cause = failure,
                    ),
                ))
            }
        }
        task.job = job
        job.invokeOnCompletion { cause -> finishTask(task, cause) }
        job.start()
    }

    override fun cancelAll(reason: CancellationReason): Int = cancelMatching(reason) { true }

    override fun cancelForPolicy(
        policy: com.promenar.infinity.domain.generation.GenerationRuntimePolicy,
        reason: CancellationReason,
    ): Int = cancelMatching(reason) { it.request.runtimePolicy == policy }

    private fun cancelMatching(reason: CancellationReason, include: (Task) -> Boolean): Int = synchronized(lifecycleLock) {
        val ids = queue.filter(include).map { it.taskId } +
            tasks.values.filter { it !in queue && include(it) }.map { it.taskId }
        queueDrainSuspensions += 1
        try {
            ids.count { cancel(it, reason) }
        } finally {
            queueDrainSuspensions -= 1
            drainQueueLocked()
        }
    }

    override suspend fun cancelAndJoinSession(sessionId: String, reason: CancellationReason): Boolean {
        val task = synchronized(lifecycleLock) { tasks[sessionId] } ?: return false
        val cancelled = cancel(task.taskId, reason)
        task.completion.await()
        return cancelled
    }

    override fun acknowledgeTerminal(taskId: String): Boolean = synchronized(lifecycleLock) {
        val entry = mutableSessions.value.entries.firstOrNull { it.value.taskId == taskId && it.value.phase in TERMINAL_PHASES }
            ?: return@synchronized false
        val task = tasks[entry.key]?.takeIf { it.taskId == taskId }
        task?.terminalAcknowledged = true
        if (task?.queuedCleanup == true && !task.finished) return@synchronized true
        presentationStore.finish(entry.key, taskId)
        clearSessionLocked(entry.key, taskId)
        true
    }

    override fun release(sessionId: String, discardTerminal: Boolean) =
        releaseIfMatching(sessionId, null, discardTerminal)

    override fun release(sessionId: String, expectedTaskId: String, discardTerminal: Boolean) =
        releaseIfMatching(sessionId, expectedTaskId, discardTerminal)

    private fun releaseIfMatching(sessionId: String, expectedTaskId: String?, discardTerminal: Boolean) = synchronized(lifecycleLock) {
        val snapshot = mutableSessions.value[sessionId]
        if (expectedTaskId != null && snapshot?.taskId != expectedTaskId) return@synchronized
        if (tasks[sessionId]?.snapshot?.phase !in SLOT_RELEASE_PHASES && tasks[sessionId] != null) return@synchronized
        if (snapshot?.phase in TERMINAL_PHASES && !discardTerminal) return@synchronized
        snapshot?.taskId?.let { presentationStore.finish(sessionId, it) }
        presentationStore.release(sessionId)
        clearSessionLocked(sessionId, snapshot?.taskId)
    }

    private fun publishLocked(task: Task, snapshot: GenerationTaskSnapshot) {
        task.snapshot = snapshot
        recent.remove(snapshot.sessionId)
        if (snapshot.phase !in TERMINAL_PHASES) recent[snapshot.sessionId] = Unit
        mutableSessions.value = mutableSessions.value + (snapshot.sessionId to snapshot)
        if (task.snapshot !== snapshot) return
        refreshActiveLocked()
        if (task.snapshot !== snapshot) return
        sessionStates.getOrPut(snapshot.sessionId) { MutableStateFlow(null) }.value = snapshot
    }

    private fun refreshActiveLocked() {
        mutableActive.value = recent.keys.lastOrNull()?.let { mutableSessions.value[it] }
    }

    private fun clearSessionLocked(sessionId: String, taskId: String?) {
        val current = mutableSessions.value[sessionId]
        if (taskId != null && current != null && current.taskId != taskId) return
        sessionStates[sessionId]?.value = null
        mutableSessions.value = mutableSessions.value - sessionId
        terminalOrder.remove(sessionId)
        recent.remove(sessionId)
        refreshActiveLocked()
    }

    private fun retainTerminalLocked(sessionId: String, taskId: String) {
        if (mutableSessions.value[sessionId]?.taskId != taskId) return
        terminalOrder.remove(sessionId)
        terminalOrder.addLast(sessionId)
        while (terminalOrder.size > terminalRetentionLimit.coerceAtLeast(0)) {
            val evicted = terminalOrder.removeFirst()
            val snapshot = mutableSessions.value[evicted] ?: continue
            if (snapshot.phase !in TERMINAL_PHASES) continue
            presentationStore.finish(evicted, snapshot.taskId)
            presentationStore.release(evicted)
            clearSessionLocked(evicted, snapshot.taskId)
        }
    }

    internal fun retainedSessionStateCount(): Int = mutableSessions.value.size

    private companion object {
        val TERMINAL_PHASES = setOf(
            GenerationPhase.COMPLETED, GenerationPhase.FAILED,
            GenerationPhase.CANCELLED, GenerationPhase.PERSISTENCE_FAILED,
        )
        val SLOT_RELEASE_PHASES = TERMINAL_PHASES + setOf(GenerationPhase.WAITING_APPROVAL, GenerationPhase.WAITING_INPUT)
    }
}
