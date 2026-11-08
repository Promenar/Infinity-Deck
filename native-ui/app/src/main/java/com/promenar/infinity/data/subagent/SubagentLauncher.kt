package com.promenar.infinity.data.subagent

import com.promenar.infinity.domain.repository.plus
import com.promenar.infinity.domain.repository.minus
import com.promenar.infinity.domain.repository.sumCacheUsage
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.trigger.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.utils.InfinityLogger
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 应用级 FIFO 运行池。父工具只等待回执，执行者始终由应用作用域持有。 */
class SubagentLauncher(
    private val runner: SubagentRunner,
    private val store: SubagentRunStore,
    private val session: suspend (String) -> Session?,
    private val modelFor: suspend (Session) -> String?,
    private val reasoningEffortFor: suspend (Session) -> String? = { it.inferenceParams?.reasoningEffort },
    private val taskTokenLimit: () -> Long = { 0L },
    private val clock: () -> Long = System::currentTimeMillis,
    private val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val dispatcher: () -> SystemTurnDispatcher? = { null },
    private val contextWindow: suspend (String) -> Int? = { null },
    private val writeResult: suspend (Session, SubagentRun, String) -> String = { _, _, _ -> error("结论文件存储不可用") },
    private val texts: SubagentRuntimeTexts = SubagentRuntimeTexts(),
    private val modelPolicyFor: suspend (Session) -> com.promenar.infinity.data.agent.AgentSubagentModelPolicy = { com.promenar.infinity.data.agent.AgentSubagentModelPolicy() },
) : SubagentControls {
    private data class Work(val sessionId: String, val stepId: String, val id: String, val parent: SkillExecutionContext, val resume: Boolean = false)
    private class Waiter(val ids: Set<String>) { val release = CompletableDeferred<SubagentEvent.Actor>() }
    private val mutex = Mutex()
    private val initialization = Mutex()
    private val checkpoints = SubagentCheckpointWriter(applicationScope, store::persist)
    private val dirty = linkedMapOf<String, SubagentBatch>()
    private val controls = ConcurrentHashMap<String, Mutex>()
    private val batches = linkedMapOf<String, SubagentBatch>()
    private val queue = ArrayDeque<Work>()
    private val jobs = mutableMapOf<String, Job>()
    private val waits = ConcurrentHashMap<String, Waiter>()
    private val reservedWaits = mutableSetOf<String>()
    private val notifying = mutableMapOf<String, Job>()
    private val deliveryLocks = ConcurrentHashMap<String, Mutex>()
    private val deleted = mutableSetOf<String>()
    private val changes = MutableStateFlow(0L)
    private val mutableActiveRuns = MutableStateFlow<List<SubagentRun>>(emptyList())
    val activeRuns: StateFlow<List<SubagentRun>> = mutableActiveRuns.asStateFlow()
    @Volatile private var initialized = false
    private var nextNoticeOrder = 0L

    /** 在启动恢复门禁通过后调用，补送持久化的通知并将失去执行者的任务标为中断。 */
    suspend fun recover() {
        initialize()
        notifyPending()
    }

    suspend fun launch(
        context: SkillExecutionContext,
        stepId: String,
        specs: List<SubagentSpec>,
        background: Boolean = true,
        timeoutMillis: Long = SubagentLimits.DEFAULT_WAIT_TIMEOUT_MS,
        maxTurns: Int? = null,
    ): List<SubagentRun> {
        require(specs.size in 1..SubagentLimits.MAX_PARALLEL) { "一次最多派生 ${SubagentLimits.MAX_PARALLEL} 个子代理" }
        require(maxTurns == null || maxTurns > 0) { "max_turns 必须为正整数" }
        initialize()
        val parent = session(context.sessionId) ?: throw SubagentModelUnavailable("会话不存在")
        val policy = modelPolicyFor(parent)
        val sessionModel = if (specs.any { it.model.isNullOrBlank() } && policy.defaultModelId.isNullOrBlank()) modelFor(parent) else null
        val models = specs.map { SubagentModelSelection.resolve(it.model, policy, sessionModel) }
        val effort = reasoningEffortFor(parent)
        val tokenLimit = taskTokenLimit()
        val ignoredTools = specs.map { runner.ignoredTools(parent, it) }
        var ownsBatch = false
        val runs = withContext(NonCancellable) {
            try {
                val created = withState {
                    check(parent.id !in deleted) { "父会话已删除" }
                    val key = key(parent.id, stepId)
                    require(key !in batches) { "子代理批次已存在" }
                    val allocated = batches.values.flatMap { it.runs }.mapTo(mutableSetOf()) { it.id }
                    val created = specs.mapIndexed { index, spec ->
                        var id: String
                        do { id = "sa_" + kotlin.random.Random.nextLong(2_176_782_336L).toString(36).padStart(6, '0') } while (!allocated.add(id))
                        SubagentRun(index, id = id, preset = spec.preset?.wireName, description = spec.description, prompt = spec.prompt,
                            toolProfile = spec.toolProfile, modelId = models[index].modelId, modelChosenByAgent = models[index].chosenByAgent,
                            reasoningEffort = effort, maxSteps = 0, maxTurns = maxTurns,
                            maxTokens = if (tokenLimit <= 0) 0 else (tokenLimit / specs.size).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                            status = SubagentStatus.QUEUED, startedAt = clock(),
                            allowedTools = spec.allowedTools?.distinct()?.filterNot { it in ignoredTools[index] },
                            ignoredTools = ignoredTools[index])
                    }
                    ownsBatch = true
                    putLocked(SubagentBatch(parent.id, stepId, created))
                    created
                }
                mutex.withLock {
                    created.forEach { run ->
                        if (batches[key(parent.id, stepId)]?.runs?.find { it.id == run.id }?.status?.isActive == true) {
                            if (!background) reservedWaits += run.id
                            queue += Work(parent.id, stepId, run.id, detachedContext(context))
                        }
                    }
                    pumpLocked()
                }
                created
            } catch (failure: Exception) {
                if (ownsBatch) mutex.withLock { failQueuedLocked(parent.id, stepId) }
                throw failure
            }
        }
        return if (background) mutex.withLock { runs.map { findLocked(parent.id, it.id).second } }
        else try { waitFor(parent.id, runs.map { it.id }, timeoutMillis) }
        finally { withContext(NonCancellable) {
            mutex.withLock { reservedWaits.removeAll(runs.map { it.id }.toSet()) }
            notifyPending()
        } }
    }

    suspend fun waitFor(sessionId: String, ids: List<String>? = null, timeoutMillis: Long = SubagentLimits.DEFAULT_WAIT_TIMEOUT_MS): List<SubagentRun> {
        require(timeoutMillis > 0) { "等待超时必须为正数" }
        initialize()
        lateinit var waiter: Waiter
        var selected = emptyList<Pair<String, SubagentRun>>()
        val installed = mutableListOf<String>()
        var by: SubagentEvent.Actor? = null
        var readyToReturn = false
        val waitReplies = mutableMapOf<String, SubagentRun>()
        try {
            withState {
                selected = if (ids == null) batches.values.filter { it.sessionId == sessionId }.flatMap { b -> b.runs.filter { it.status.isActive }.map { b.stepId to it } }
                    else ids.distinct().map { id -> findLocked(sessionId, id) }
                waiter = Waiter(selected.mapTo(mutableSetOf()) { it.second.id })
                val now = clock()
                val steps = selected.map { it.first }.distinct()
                check(steps.none { waits.containsKey(key(sessionId, it)) }) { "这批子代理已有等待" }
                steps.forEach { step ->
                    installed += step
                    waits[key(sessionId, step)] = waiter
                    putLocked(checkNotNull(batches[key(sessionId, step)]).copy(wait = SubagentWait(now, now + timeoutMillis)))
                }
            }
            val steering = currentCoroutineContext()[com.promenar.infinity.data.exec.SteeringContext]
            val completed = com.promenar.infinity.data.exec.ToolExecutionControls.controlled(com.promenar.infinity.data.exec.ToolExecutionControls.Kind.WAITING) { release ->
                coroutineScope {
                    val ended = async(start = CoroutineStart.UNDISPATCHED) {
                        changes.first { mutex.withLock { selected.all { (_, run) -> !findLocked(sessionId, run.id).second.status.isActive } } }
                    }
                    val insertion = steering?.let { async(start = CoroutineStart.UNDISPATCHED) { it.await() } }
                    try {
                        withTimeoutOrNull(timeoutMillis) {
                            kotlinx.coroutines.selects.select<Unit> {
                                ended.onAwait { }
                                waiter.release.onAwait { }
                                release.onAwait { waiter.release.complete(SubagentEvent.Actor.USER); Unit }
                                insertion?.onAwait { waiter.release.complete(SubagentEvent.Actor.USER); Unit }
                            }
                            true
                        } ?: false
                    } finally { ended.cancel(); insertion?.cancel() }
                }
            }
            by = if (!completed) SubagentEvent.Actor.TIMEOUT else if (waiter.release.isCompleted) waiter.release.await() else null
            readyToReturn = true
        } catch (cancelled: CancellationException) {
            by = SubagentEvent.Actor.PARENT
            throw cancelled
        } finally {
            withContext(NonCancellable) {
                if (readyToReturn && by != SubagentEvent.Actor.PARENT) {
                    val completed = mutex.withLock {
                        selected.mapNotNull { (_, initial) ->
                            batches.values.firstOrNull { it.sessionId == sessionId && it.runs.any { run -> run.id == initial.id } }
                                ?.runs?.find { it.id == initial.id }?.takeIf { !it.status.isActive }
                        }
                    }
                    for (run in completed) {
                        val claim = delivery(sessionId, run.id, run.completionSequence).claim() ?: continue
                        try {
                            claim.confirm()
                            waitReplies[run.id] = run.copy(deliveredSequence = maxOf(run.deliveredSequence, run.completionSequence),
                                pendingNotices = run.pendingNotices.filter { it.sequence != run.completionSequence })
                        } catch (_: Exception) {
                            // 部分确认失败只影响该目标，已确认的结论仍须实际交回父工具。
                            waitReplies[run.id] = run.copy(result = null, resultPath = null, error = texts.persistenceFailed)
                        } finally { claim.release() }
                    }
                }
                try { withState {
                    // 先撤销内存等待所有权，磁盘失败也不能把后台通知永久压住。
                    installed.forEach { step -> waits.remove(key(sessionId, step), waiter) }
                    selected.forEach { (step, initial) ->
                        if (step !in installed) return@forEach
                        reservedWaits -= initial.id
                        val run = batches[key(sessionId, step)]?.runs?.find { it.id == initial.id } ?: return@forEach
                        val next = if (by != null && run.status.isActive) run.copy(events = (run.events + SubagentEvent(SubagentEvent.Kind.DETACH, at = clock(), by = by)).takeLast(SubagentLimits.MAX_EVENTS))
                            else run
                        replaceLocked(sessionId, step, next)
                    }
                    installed.forEach { step ->
                        batches[key(sessionId, step)]?.let { batch ->
                            putLocked(batch.copy(wait = null))
                        }
                    }
                } } catch (_: Exception) {
                    // 内存等待所有权已撤销；未落盘状态由下一次检查点或恢复处理。
                }
                try { notifyPending() }
                catch (failure: Exception) { InfinityLogger.log("[SubagentCompletion] 通知排队失败，将在后续恢复：${failure::class.java.simpleName}") }
            }
        }
        return mutex.withLock { selected.map { (_, run) ->
            waitReplies[run.id] ?: findLocked(sessionId, run.id).second.let { current ->
                if (!current.status.isActive && current.completionSequence > 0)
                    current.copy(result = null, resultPath = null, error = null) else current
            }
        } }
    }

    override fun releaseWait(sessionId: String, stepId: String) { waits[key(sessionId, stepId)]?.release?.complete(SubagentEvent.Actor.USER); changes.update { it + 1 } }

    suspend fun read(sessionId: String, id: String): SubagentRun {
        initialize()
        return mutex.withLock { findLocked(sessionId, id).second }
    }

    suspend fun readHistory(sessionId: String, id: String): List<com.promenar.infinity.data.remote.protocol.ProtocolMessage> {
        initialize()
        val step = mutex.withLock { findLocked(sessionId, id).first }
        return store.execution(sessionId, step, id)?.messages.orEmpty()
    }

    suspend fun send(sessionId: String, id: String, message: String, wrapUp: Boolean = false): SubagentRun {
        require(message.isNotBlank() || wrapUp) { "message 不能为空" }
        initialize()
        return controls.getOrPut(id) { Mutex() }.withLock {
            val parent = session(sessionId) ?: throw SubagentModelUnavailable("父会话已删除")
            mutex.withLock {
                val run = findLocked(sessionId, id).second
                require(run.status != SubagentStatus.STOPPED && run.status != SubagentStatus.CANCELLED) { "已停止或取消的子代理不能恢复" }
            }
            val step = mutex.withLock { findLocked(sessionId, id).first }
            val instruction = SubagentInstruction(message, wrapUp, clock(), UUID.randomUUID().toString())
            store.enqueue(sessionId, step, id, instruction)
            var resumed = false
            val next = try { withState {
                val run = findLocked(sessionId, id).second
                require(run.status != SubagentStatus.STOPPED && run.status != SubagentStatus.CANCELLED) { "已停止或取消的子代理不能恢复" }
                if (run.status.isActive) {
                    val next = if (wrapUp) run.copy(status = SubagentStatus.WRAPPING_UP,
                        events = (run.events + SubagentEvent(SubagentEvent.Kind.WRAP_UP, message.take(SubagentLimits.MAX_EVENT_CHARS), at = instruction.at, by = SubagentEvent.Actor.PARENT)).takeLast(SubagentLimits.MAX_EVENTS)) else run
                    replaceLocked(sessionId, step, next)
                    next
                } else {
                    resumed = true
                    val next = run.copy(status = SubagentStatus.QUEUED, result = null, resultPath = null, error = null, endedAt = null,
                        events = (run.events + SubagentEvent(SubagentEvent.Kind.RESUME, message.take(SubagentLimits.MAX_EVENT_CHARS), at = clock(), by = SubagentEvent.Actor.PARENT)).takeLast(SubagentLimits.MAX_EVENTS))
                    replaceLocked(sessionId, step, next)
                    next
                }
            } } catch (failure: Exception) {
                if (resumed) mutex.withLock { failQueuedLocked(sessionId, step, setOf(id)) }
                throw failure
            }
            mutex.withLock {
                if (resumed && batches[key(sessionId, step)]?.runs?.find { it.id == id }?.status?.isActive == true) {
                    queue += Work(sessionId, step, id, contextFor(parent), resume = true)
                }
                pumpLocked()
            }
            next
        }
    }

    override suspend fun stop(sessionId: String, runId: String) = stop(sessionId, runId, SubagentEvent.Actor.USER)

    suspend fun stop(sessionId: String, runId: String, by: SubagentEvent.Actor) {
        initialize()
        var job: Job? = null
        try { withState {
            val (step, run) = findLocked(sessionId, runId)
            if (!run.status.isActive) return@withState
            queue.removeAll { it.id == runId }
            val running = jobs[runId]
            job = running
            // 先取消执行，磁盘阻塞或失败不能延长网络请求与工具的生命期。
            running?.cancel()
            val stopped = run.copy(status = SubagentStatus.STOPPED, currentTool = null, liveOutputKey = null, endedAt = clock(),
                pendingNotices = emptyList(), deliveredSequence = run.completionSequence,
                events = (run.events + SubagentEvent(SubagentEvent.Kind.STOP, at = clock(), by = by)).takeLast(SubagentLimits.MAX_EVENTS))
            replaceLocked(sessionId, step, stopped)
        } } catch (_: Exception) {
            mutex.withLock {
                val (step, current) = findLocked(sessionId, runId)
                replaceLocked(sessionId, step, current.copy(error = texts.stopPersistenceFailed), persist = false)
            }
        }
        job?.join()
    }

    suspend fun deleteSession(sessionId: String) {
        initialize()
        val cancelled = mutex.withLock {
            deleted += sessionId
            queue.removeAll { it.sessionId == sessionId }
            val ids = batches.values.filter { it.sessionId == sessionId }.flatMap { it.runs }.map { it.id }.toSet()
            notifying.keys.filter { it.substringBefore(':') in ids }.forEach { key -> notifying.remove(key)?.cancel() }
            waits.filterKeys { it.startsWith("$sessionId\u0000") }.values.forEach { it.release.complete(SubagentEvent.Actor.PARENT) }
            val active = ids.mapNotNull { jobs[it] }
            active.forEach { it.cancel() }
            batches.entries.removeAll { it.value.sessionId == sessionId }
            reservedWaits.removeAll(ids)
            store.markDeleted(sessionId)
            changedLocked()
            active
        }
        store.deleteSession(sessionId)
        cancelled.joinAll()
    }

    private suspend fun initialize() {
        if (initialized) return
        initialization.withLock {
            if (initialized) return@withLock
            val stored = store.all()
            withState { initializeLocked(stored) }
        }
    }

    private fun initializeLocked(stored: List<SubagentBatch>) {
        if (initialized) return
        nextNoticeOrder = stored.flatMap { it.runs }.flatMap { it.pendingNotices }.maxOfOrNull { it.order } ?: 0L
        stored.forEach { batch ->
            val recovered = batch.copy(wait = null, runs = batch.runs.map { run ->
                when {
                    run.status.isActive -> run.copy(status = SubagentStatus.INTERRUPTED, endedAt = clock(), currentTool = null, liveOutputKey = null,
                        completionSequence = run.completionSequence + 1, error = texts.interrupted)
                    run.id.isNotBlank() && run.completionSequence == 0 && run.status != SubagentStatus.STOPPED && run.status != SubagentStatus.CANCELLED -> run.copy(completionSequence = 1)
                    else -> run
                }
            })
            val durable = recovered.copy(runs = recovered.runs.map { run ->
                if (run.id.isNotEmpty() && run.completionSequence > run.deliveredSequence && run.pendingNotices.none { it.sequence == run.completionSequence })
                    run.copy(pendingNotices = run.pendingNotices + notice(run)) else run
            })
            putLocked(durable)
        }
        initialized = true
    }

    private fun pumpLocked() {
        while (jobs.size < SubagentLimits.MAX_BACKGROUND_CONCURRENT && queue.isNotEmpty()) {
            if (queue.first().id in jobs) break
            val work = queue.removeFirst()
            val job = applicationScope.launch(start = CoroutineStart.LAZY) { execute(work) }
            jobs[work.id] = job
            job.invokeOnCompletion {
                applicationScope.launch {
                    mutex.withLock {
                        if (jobs[work.id] === job) jobs.remove(work.id)
                        pumpLocked()
                        changedLocked()
                    }
                    notifyPending()
                }
            }
            job.start()
        }
    }

    private suspend fun execute(work: Work) {
        try {
            val initial = withState {
                val run = findLocked(work.sessionId, work.id).second
                run.copy(status = if (run.status == SubagentStatus.WRAPPING_UP) run.status else SubagentStatus.RUNNING).also { replaceLocked(work.sessionId, work.stepId, it) }
            }
            val execution = SubagentExecution(
                load = { store.execution(work.sessionId, work.stepId, work.id) },
                save = { store.saveExecution(work.sessionId, work.stepId, work.id, it) },
                drainInbox = { store.execution(work.sessionId, work.stepId, work.id)?.pendingInstructions.orEmpty() },
                contextWindow = contextWindow, resume = work.resume,
            )
            val finished = runner.run(work.parent, { session(work.sessionId) }, work.stepId, initial, SubagentPreset.parse(initial.preset), execution) { run, persist ->
                withState {
                    if (work.sessionId in deleted) return@withState
                    val current = findLocked(work.sessionId, work.id).second
                    val merged = if (current.status == SubagentStatus.STOPPED) current.copy(
                        inputTokens = maxOf(current.inputTokens, run.inputTokens), outputTokens = maxOf(current.outputTokens, run.outputTokens), totalTokens = maxOf(current.totalTokens, run.totalTokens),
                        cacheReadInput = run.cacheReadInput, cacheWriteInput = run.cacheWriteInput, cacheWriteLongTtlInput = run.cacheWriteLongTtlInput, cache = run.cache, cacheRequests = run.cacheRequests)
                    else run.copy(events = mergeEvents(current.events, run.events),
                        completionSequence = current.completionSequence, deliveredSequence = current.deliveredSequence,
                        pendingNotices = current.pendingNotices, dailyUsage = current.dailyUsage,
                        status = if (!run.status.isActive || current.status == SubagentStatus.WRAPPING_UP) current.status else run.status)
                    replaceLocked(work.sessionId, work.stepId, merged, persist)
                }
            }
            var result = finished
            val parent = session(work.sessionId)
            val text = result.result
            val threshold = resultThreshold(parent?.let { modelFor(it) })
            if (parent != null && text != null && text.length > threshold) {
                try {
                    val path = writeResult(parent, result, text)
                    result = result.copy(resultPath = path, result = text.take(minOf(2_000, threshold / 2)) + "\n\n完整结论：$path")
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { result = result.copy(status = SubagentStatus.FAILED, error = texts.resultWriteFailed, result = null) }
            }
            controls.getOrPut(work.id) { Mutex() }.withLock {
                val pending = store.execution(work.sessionId, work.stepId, work.id)?.pendingInstructions.orEmpty()
                withState {
                if (work.sessionId in deleted) return@withState
                val current = findLocked(work.sessionId, work.id).second
                if (current.status != SubagentStatus.STOPPED) {
                    if (pending.isNotEmpty()) {
                        replaceLocked(work.sessionId, work.stepId, result.copy(status = SubagentStatus.QUEUED, result = null,
                            resultPath = null, error = null, endedAt = null, events = current.events,
                            completionSequence = current.completionSequence, deliveredSequence = current.deliveredSequence,
                            pendingNotices = current.pendingNotices, dailyUsage = current.dailyUsage))
                        queue += work.copy(resume = true)
                    } else replaceLocked(work.sessionId, work.stepId,
                        result.copy(events = current.events, completionSequence = current.completionSequence + 1, deliveredSequence = current.deliveredSequence,
                            dailyUsage = current.dailyUsage, pendingNotices = current.pendingNotices + notice(result.copy(completionSequence = current.completionSequence + 1))))
                }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            try { withState {
                if (work.sessionId !in deleted) {
                    val current = findLocked(work.sessionId, work.id).second
                    if (current.status != SubagentStatus.STOPPED) {
                        val failed = current.copy(status = SubagentStatus.FAILED, currentTool = null, liveOutputKey = null,
                            error = texts.executionFailed, endedAt = clock(), completionSequence = current.completionSequence + 1)
                        val next = failed.copy(pendingNotices = current.pendingNotices + notice(failed))
                        replaceLocked(work.sessionId, work.stepId, next)
                    }
                }
            } } catch (_: Exception) {
                mutex.withLock {
                    if (work.sessionId !in deleted) {
                        val (step, current) = findLocked(work.sessionId, work.id)
                        replaceLocked(work.sessionId, step, current.copy(error = texts.persistenceFailed), persist = false)
                    }
                }
            }
        }
    }

    private suspend fun resultThreshold(modelId: String?): Int = ((modelId?.let { contextWindow(it) }?.takeIf { it > 0 } ?: 32_768) / 8).coerceAtLeast(512)

    private fun notice(run: SubagentRun): SubagentCompletionNotice {
        nextNoticeOrder = maxOf(nextNoticeOrder + 1, clock())
        return SubagentCompletionNotice(run.completionSequence, run.status,
        "状态：${run.status}；用时：${((run.endedAt ?: clock()) - run.startedAt).coerceAtLeast(0) / 1000} 秒\n\n" +
            (run.result ?: run.error.orEmpty()) + (run.resultPath?.let { "\n完整结论：$it" }.orEmpty()), run.endedAt ?: clock(), order = nextNoticeOrder)
    }

    /** 每个完成序号独立持久化；投递回执只在消息已持久化后消费。 */
    private suspend fun notifyPending() {
        data class Scheduled(val batch: SubagentBatch, val run: SubagentRun, val pending: SubagentCompletionNotice, val marker: Job)
        val scheduled = mutex.withLock {
            if (dispatcher() == null) return
            batches.values.flatMap { b -> b.runs.flatMap { run -> run.pendingNotices.map { Triple(b, run, it) } } }
                .sortedBy { it.third.order }.mapNotNull { (batch, run, pending) ->
                    val noticeKey = "${run.id}:${pending.sequence}"
                    if (run.id.isBlank() || run.status == SubagentStatus.STOPPED || run.status == SubagentStatus.CANCELLED ||
                        run.id in reservedWaits || waits[key(batch.sessionId, batch.stepId)]?.ids?.contains(run.id) == true || noticeKey in notifying) null
                    else Job().let { marker ->
                        notifying[noticeKey] = marker
                        Scheduled(batch, run, pending, marker)
                    }
                }
        }
        for ((batch, run, pending, marker) in scheduled) {
            val noticeKey = "${run.id}:${pending.sequence}"
            // 离开状态锁后按完成顺序进入调度器；首次挂起之前完成步骤队列登记。
            val job = applicationScope.launch(start = CoroutineStart.UNDISPATCHED) {
                var retryMillis = 1_000L
                try {
                    while (true) {
                        val stillPending = mutex.withLock {
                            batch.sessionId !in deleted && findLocked(batch.sessionId, run.id).second.pendingNotices.any { it.sequence == pending.sequence }
                        }
                        if (!stillPending) break
                        try {
                            val outcome = dispatcher()?.request(SystemTurnRequest(batch.sessionId, SystemTurnKind.SUBAGENT_DONE, run.id, run.description,
                                pending.body, sequence = pending.sequence, delivery = delivery(batch.sessionId, run.id, pending.sequence)))
                            if (outcome == SystemTurnOutcome.Completed || outcome == SystemTurnOutcome.NeedsUser || outcome == SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING)) {
                                val claim = delivery(batch.sessionId, run.id, pending.sequence).claim()
                                if (claim != null) try { claim.confirm() } finally { claim.release() }
                                break
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            // 未获持久消息回执时保留 outbox，按相同序号退避重试。
                        }
                        delay(retryMillis)
                        retryMillis = (retryMillis * 2).coerceAtMost(30_000L)
                    }
                } finally {
                    withContext(NonCancellable) { mutex.withLock { notifying.remove(noticeKey) } }
                }
            }
            mutex.withLock {
                if (notifying[noticeKey] === marker) notifying[noticeKey] = job
                else job.cancel()
            }
        }
    }

    private fun delivery(sessionId: String, id: String, sequence: Int) = object : SystemTurnDelivery {
        override suspend fun claim(): SystemTurnDelivery.Claim? {
            val lane = deliveryLocks.getOrPut("$id:$sequence") { Mutex() }
            lane.lock()
            val pending = mutex.withLock {
                sessionId !in deleted && findLocked(sessionId, id).second.pendingNotices.any { it.sequence == sequence }
            }
            if (!pending) { lane.unlock(); return null }
            return object : SystemTurnDelivery.Claim {
                override suspend fun confirm() = confirm(restoreOnFailure = true)
                override suspend fun confirmPersistedMessage() = confirm(restoreOnFailure = false)

                private suspend fun confirm(restoreOnFailure: Boolean) {
                    var original: Pair<String, SubagentRun>? = null
                    try {
                        withState {
                            if (sessionId in deleted) return@withState
                            val (step, current) = findLocked(sessionId, id)
                            original = step to current
                            replaceLocked(sessionId, step, current.copy(deliveredSequence = maxOf(current.deliveredSequence, sequence),
                                pendingNotices = current.pendingNotices.filter { it.sequence != sequence }))
                        }
                    } catch (failure: Exception) {
                        if (!restoreOnFailure) throw failure
                        // 等待尚未交回结论时，确认失败须恢复领取资格；通知已有持久消息时保持已交付。
                        withContext(NonCancellable) {
                            runCatching { withState {
                                val (step, before) = original ?: return@withState
                                if (sessionId in deleted) return@withState
                                val current = findLocked(sessionId, id).second
                                replaceLocked(sessionId, step, current.copy(
                                    deliveredSequence = if (current.deliveredSequence == sequence) before.deliveredSequence else current.deliveredSequence,
                                    pendingNotices = (current.pendingNotices + before.pendingNotices.filter { it.sequence == sequence })
                                        .distinctBy { it.sequence }.sortedBy { it.order },
                                ))
                            } }
                        }
                        throw failure
                    }
                }
                override fun release() { lane.unlock() }
            }
        }
    }

    /** 仅抵消已发布的快照事件；同时间、同正文的多条真实事件保留次数。 */
    private fun mergeEvents(current: List<SubagentEvent>, incoming: List<SubagentEvent>): List<SubagentEvent> {
        val existing = current.groupingBy { it }.eachCount().toMutableMap()
        val additions = incoming.filter { event ->
            val count = existing[event] ?: 0
            if (count > 0) { existing[event] = count - 1; false } else true
        }
        return (current + additions).takeLast(SubagentLimits.MAX_EVENTS)
    }

    /** 未持久化的派生或恢复不进入执行池，失败后也不能被其它批次补位启动。 */
    private fun failQueuedLocked(sessionId: String, stepId: String, ids: Set<String>? = null) {
        val batch = batches[key(sessionId, stepId)] ?: return
        val failedIds = batch.runs.filter { it.status == SubagentStatus.QUEUED && (ids == null || it.id in ids) }.mapTo(mutableSetOf()) { it.id }
        queue.removeAll { it.id in failedIds }
        reservedWaits.removeAll(failedIds)
        putLocked(batch.copy(runs = batch.runs.map {
            if (it.id in failedIds) it.copy(status = SubagentStatus.FAILED, error = texts.persistenceFailed, endedAt = clock()) else it
        }), persist = false)
    }

    private fun findLocked(sessionId: String, id: String): Pair<String, SubagentRun> {
        require(id.matches(Regex("sa_[a-z0-9]{6}"))) { "未找到该会话或子代理" }
        return batches.values.firstNotNullOfOrNull { batch ->
            if (batch.sessionId == sessionId) batch.runs.find { it.id == id }?.let { batch.stepId to it } else null
        } ?: throw IllegalArgumentException("未找到该会话或子代理")
    }

    private fun replaceLocked(sessionId: String, stepId: String, run: SubagentRun, persist: Boolean = true) {
        val batch = batches[key(sessionId, stepId)] ?: return
        val current = batch.runs.first { it.id == run.id }
        val input = (run.inputTokens - current.inputTokens).coerceAtLeast(0)
        val output = (run.outputTokens - current.outputTokens).coerceAtLeast(0)
        val day = java.time.Instant.ofEpochMilli(clock()).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString()
        val old = current.dailyUsage[day] ?: com.promenar.infinity.data.model.TokenUsage()
        val requestDays = run.cacheRequests.filter { it.usage != null }.groupBy { it.day }
        val dailyCache = (current.dailyCacheUsage.keys + requestDays.keys).associateWith { requestDay ->
            current.legacyCache(requestDay) + requestDays[requestDay].orEmpty().map { it.cache }.sumCacheUsage()
        }
        val dailyUsage = current.dailyUsage.toMutableMap()
        if (input + output > 0) dailyUsage[day] = old.copy(
            input = old.input + input, output = old.output + output, total = old.total + input + output,
            estimated = old.estimated || run.tokensEstimated,
        )
        // 字段是否报告由该日实际请求决定；累计总值不变不能被解释为这一天报告了零。
        requestDays.forEach { (requestDay, requests) ->
            val usage = dailyUsage[requestDay] ?: com.promenar.infinity.data.model.TokenUsage()
            fun reported(selector: (com.promenar.infinity.data.model.TokenUsage) -> Int?): Int? {
                val stored = current.dailyUsage[requestDay]?.let(selector)
                val tracked = current.cacheRequests.filter { it.day == requestDay }.map { it.usage?.let(selector) }.sumReportedCache()
                val legacy = stored?.takeIf { tracked == null || it > tracked }?.let { it - (tracked ?: 0) }
                return (listOf(legacy) + requests.map { it.usage?.let(selector) }).sumReportedCache()
            }
            dailyUsage[requestDay] = usage.copy(
                cacheReadInput = reported { it.cacheReadInput },
                cacheWriteInput = reported { it.cacheWriteInput },
                cacheWriteLongTtlInput = reported { it.cacheWriteLongTtlInput },
            )
        }
        val next = run.copy(dailyCacheUsage = dailyCache, dailyUsage = dailyUsage)
        putLocked(batch.copy(runs = batch.runs.map { if (it.id == run.id) next else it }), persist)
    }
    private fun putLocked(batch: SubagentBatch, persist: Boolean = true) {
        store.publish(batch)
        if (persist) dirty[key(batch.sessionId, batch.stepId)] = batch
        batches[key(batch.sessionId, batch.stepId)] = batch
        changedLocked()
    }
    /** 快照在锁内冻结，串行写盘与持久化回执在锁外等待。 */
    private suspend fun <T> withState(block: () -> T): T {
        val (result, snapshots) = mutex.withLock {
            val result = runCatching(block)
            val snapshots = dirty.values.map(checkpoints::enqueue)
            dirty.clear()
            result to snapshots
        }
        snapshots.forEach { it.start() }
        snapshots.forEach { it.receipt.await() }
        return result.getOrThrow()
    }

    private fun changedLocked() {
        mutableActiveRuns.value = batches.values.flatMap { it.runs }.filter { it.status.isActive }
        changes.update { it + 1 }
    }
    private fun key(sessionId: String, stepId: String) = "$sessionId\u0000$stepId"
    private fun detachedContext(parent: SkillExecutionContext) = object : SkillExecutionContext {
        override val sessionId = parent.sessionId
        override val agentId = parent.agentId
        override val workspacePath = parent.workspacePath
        override val workspaceRootUuid = parent.workspaceRootUuid
        override val runMode = parent.runMode
    }
    private fun contextFor(parent: Session) = object : SkillExecutionContext {
        override val sessionId = parent.id
        override val agentId = parent.agentId
        override val workspacePath = parent.workspacePath
        override val workspaceRootUuid = parent.workspaceRootUuid.orEmpty()
        override val runMode = parent.runMode
    }
}
