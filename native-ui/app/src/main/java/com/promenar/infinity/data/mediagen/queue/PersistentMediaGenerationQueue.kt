package com.promenar.infinity.data.mediagen.queue

import com.promenar.infinity.domain.mediagen.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 持久化是状态变更的唯一发布点；网络异常不能证明付费提交未发生。 */
class PersistentMediaGenerationQueue(
    private val store: MediaTaskStore,
    private val registry: MediaChannelRegistry,
    bindings: List<MediaBackendBinding>,
    private val concurrency: MediaQueueConcurrency = MediaQueueConcurrency(),
    private val polling: MediaPollingPolicy = MediaPollingPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val messages: MediaQueueMessages = MediaQueueMessages { it.name },
    private val wake: () -> Unit = {},
    private val requestAvailable: suspend (MediaGenerationRequest) -> Boolean = { true },
) : MediaGenerationQueue, MediaOutputReservationReader {
    private val bindings = bindings.toList().also { require(it.map { binding -> binding.id }.distinct().size == it.size) }
    private val mutations = Mutex()
    private val runner = Mutex()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    /** 与状态写入共用 mutations，取消可立即找到正在查询或下载的协程。 */
    private val activeJobs = mutableMapOf<String, Job>()
    private var recovered = false

    override suspend fun submit(request: MediaGenerationRequest): MediaTask = withContext(ioDispatcher) {
        mutations.withLock {
            val frozen = request.copy(inputs = request.inputs.toList())
            val channel = registry.channel(frozen.channelId) ?: throw IllegalArgumentException("MEDIA_CHANNEL_NOT_FOUND")
            validate(channel, frozen)
            val binding = selectBinding(channel)
            val fingerprint = binding.configurationFor(channel)
            val providerId = binding.providerFor(channel).also { require(it.isNotBlank()) }
            val modelId = binding.modelId(channel).also { require(it.isNotBlank()) }
            val expectedOutputs = binding.expectedOutputsFor(channel).also { require(it > 0) }
            val now = clock()
            val task = MediaTask(UUID.randomUUID().toString(), frozen, createdAt = now)
            require(binding.configurationFor(channel) == fingerprint && binding.providerFor(channel) == providerId && binding.modelId(channel) == modelId && binding.expectedOutputsFor(channel) == expectedOutputs) { "MEDIA_CONFIGURATION_CHANGED" }
            store.save(PersistentMediaTask(task, channel, binding.id, providerId, modelId, now, configurationFingerprint = fingerprint, expectedOutputs = expectedOutputs))
            signal()
            task
        }
    }

    override suspend fun outputReservations(taskIds: Set<String>): Map<String, Int> = withContext(ioDispatcher) {
        store.all().filter { it.task.id in taskIds }.associate { it.task.id to it.expectedOutputs }
    }

    override fun observeTasks(): Flow<List<MediaTask>> = store.observe().map { records -> records.map { it.task } }
    override fun observeTask(id: String): Flow<MediaTask?> = store.observe().map { records -> records.find { it.task.id == id }?.task }.distinctUntilChanged()

    override suspend fun cancel(id: String): Unit = withContext(ioDispatcher) {
        // 持久化返回前调用方也可能取消；提交后的停止与远端清理必须完整执行。
        withContext(NonCancellable) {
            val canceled = mutations.withLock {
                val record = required(id)
                // 已结束或只读历史上的迟到点击保持幂等。
                if (record.historyOnly || record.task.state !in cancellableStates) return@withLock null
                val next = record.copy(task = record.task.canceled(), updatedAt = clock())
                store.save(next)
                activeJobs[id]?.cancel()
                signal()
                next
            }
            if (canceled != null) cancelRemote(canceled)
        }
    }

    suspend fun hasRunnableTasks(): Boolean = withContext(ioDispatcher) {
        recover()
        val records = store.all()
        val occupied = records.filter(::unresolvedRemote)
        records.any { uncertain(it) || (runnable(it) && (it.task.remoteId != null || canStart(it, occupied))) }
    }

    /** 用户从提供商历史核实远端编号后接续查询；不会发出新的生成请求。 */
    suspend fun attachRemoteId(id: String, remoteId: String) = withContext(ioDispatcher) {
        require(remoteId.isNotBlank())
        mutations.withLock {
            val record = required(id)
            check(record.task.state == MediaTaskState.RESULT_UNKNOWN && !record.historyOnly)
            store.save(record.copy(task = record.task.copy(state = MediaTaskState.RUNNING, remoteId = remoteId,
                failure = null, error = null), updatedAt = clock()))
            signal()
        }
    }

    /** 仅在用户确认已核对提供商并处理远端任务后调用，解除保守占用；记录保留用于审计。 */
    suspend fun archiveUnknown(id: String) = withContext(ioDispatcher) {
        mutations.withLock {
            val record = required(id)
            check(record.task.state == MediaTaskState.RESULT_UNKNOWN)
            store.save(record.copy(historyOnly = true, updatedAt = clock()))
            signal()
        }
    }

    /** 由前台服务持有运行周期；服务中断保留快照，用户取消持久化为终态。 */
    suspend fun runUntilIdle(): Unit = withContext(ioDispatcher) {
        runner.withLock {
            recover()
            try {
                coroutineScope {
                    signals.trySend(Unit)
                    while (true) {
                        signals.receive()
                        val idle = mutations.withLock {
                            activeJobs.entries.removeAll { it.value.isCompleted }
                            val all = store.all()
                            // 查询失败或提交未知时，远端可能仍在执行，继续占用提交额度。
                            val occupied = all.filter { unresolvedRemote(it) || it.task.id in activeJobs }.toMutableList()
                            val records = all.filter(::runnable).sortedBy { if (it.task.remoteId != null) 0 else 1 }
                            for (record in records) {
                                if (record.task.id in activeJobs || activeJobs.size >= concurrency.maxTotal) continue
                                if (record.task.remoteId == null && !canStart(record, occupied)) continue
                                val job = launch(start = CoroutineStart.LAZY) { execute(record.task.id) }
                                activeJobs[record.task.id] = job
                                if (occupied.none { it.task.id == record.task.id }) occupied += record
                                job.invokeOnCompletion { signals.trySend(Unit) }
                                job.start()
                            }
                            // 未决远端任务阻挡的排队项等待核实或归档后唤醒。
                            activeJobs.isEmpty()
                        }
                        if (idle) break
                    }
                }
            } finally {
                withContext(NonCancellable) { mutations.withLock { activeJobs.clear() } }
            }
        }
    }

    private suspend fun recover() = mutations.withLock {
        if (recovered) return@withLock
        store.all().forEach { record ->
            when {
                record.task.state == MediaTaskState.AWAITING_APPROVAL -> {
                    // 旧快照只有更新时间，用它补齐兼容记录的时序依据。
                    val createdAt = record.task.createdAt.takeIf { it != 0L } ?: record.updatedAt
                    store.save(record.copy(task = record.task.copy(state = MediaTaskState.CANCELED,
                        createdAt = createdAt, failure = null, error = "升级后取消"), updatedAt = clock()))
                }
                uncertain(record) -> store.save(asUnknown(record))
                !record.historyOnly && record.task.remoteId != null && record.task.state in setOf(MediaTaskState.QUEUED, MediaTaskState.SUBMITTING) ->
                    store.save(record.copy(task = record.task.copy(state = MediaTaskState.RUNNING), updatedAt = clock()))
            }
        }
        recovered = true
    }

    private suspend fun execute(id: String) {
        var record = required(id)
        if (!runnable(record)) return
        val binding = bindings.find { it.id == record.bindingId }
        if (binding == null) {
            if (record.remoteSucceeded) downloadFailed(record, MediaTaskFailure.DOWNLOAD)
            else saveActive(record.copy(task = record.task.copy(state = MediaTaskState.FAILED,
                failure = if (record.task.remoteId != null) MediaTaskFailure.POLL else MediaTaskFailure.CHANNEL_UNAVAILABLE,
                error = messages.message(MediaTaskFailure.CHANNEL_UNAVAILABLE)), updatedAt = clock()))
            return
        }
        val backend = binding.backend
        if (record.task.state == MediaTaskState.QUEUED) {
            val available = try {
                registry.channel(record.channel.id) == record.channel && backend.handles(record.channel) && bindingMatches(binding, record) && requestAvailable(record.task.request)
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
            if (!available) {
                saveActive(record.copy(task = record.task.copy(state = MediaTaskState.FAILED,
                    failure = MediaTaskFailure.CHANNEL_UNAVAILABLE, error = messages.message(MediaTaskFailure.CHANNEL_UNAVAILABLE)), updatedAt = clock()))
                return
            }
            record = record.copy(task = record.task.submitting(), updatedAt = clock())
            if (!saveActive(record)) return
            val remoteId = try {
                currentCoroutineContext().ensureActive()
                backend.submit(record.channel, record.task.request).also { require(it.isNotBlank()) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { unknown(id) }
                throw cancelled
            } catch (_: Exception) {
                unknown(id)
                return
            }
            // 迟到的提交编号仍落盘；已取消记录只补编号并尝试远端取消。
            val started = withContext(NonCancellable) {
                val next = mutations.withLock {
                    val current = required(id)
                    val task = if (current.task.state == MediaTaskState.CANCELED) current.task.copy(remoteId = remoteId)
                        else current.task.started(remoteId)
                    current.copy(task = task, updatedAt = clock()).also { store.save(it) }
                }
                if (next.task.state == MediaTaskState.CANCELED) { cancelRemote(next); null } else next
            } ?: return
            record = started
        }
        while (record.task.state == MediaTaskState.RUNNING) {
            currentCoroutineContext().ensureActive()
            val wait = record.nextPollAt - clock()
            if (wait > 0) delay(wait)
            val remoteId = requireNotNull(record.task.remoteId)
            val result = try {
                backend.poll(record.channel, remoteId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                val errors = record.consecutivePollErrors + 1
                record = if (errors >= polling.maxConsecutiveErrors) record.copy(
                    task = record.task.copy(state = MediaTaskState.FAILED, failure = MediaTaskFailure.POLL, error = messages.message(MediaTaskFailure.POLL)),
                    consecutivePollErrors = errors, updatedAt = clock(),
                ) else nextPoll(record.copy(consecutivePollErrors = errors))
                if (!saveActive(record)) return
                continue
            }
            when (result) {
                is RemoteStatus.Running -> {
                    val progress = result.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    record = nextPoll(record.copy(task = record.task.copy(progress = progress), consecutivePollErrors = 0))
                    if (!saveActive(record)) return
                }
                is RemoteStatus.Failed -> {
                    record = record.copy(task = record.task.copy(state = MediaTaskState.FAILED, failure = MediaTaskFailure.REMOTE,
                        error = messages.message(MediaTaskFailure.REMOTE)), updatedAt = clock())
                    if (!saveActive(record)) return
                }
                is RemoteStatus.Succeeded -> {
                    // 已确认发生的用量即使与取消竞态，也须保留实际费用且只入账一次。
                    record = withContext(NonCancellable) {
                        val cost = try { if (bindingMatches(binding, record)) backend.actualCost(record.channel, result.usage) else null } catch (_: Exception) { null }
                        mutations.withLock {
                            val current = required(id)
                            val canceled = current.task.state == MediaTaskState.CANCELED
                            val task = current.task.copy(state = if (canceled) MediaTaskState.CANCELED else MediaTaskState.DOWNLOADING,
                                actualCost = cost, progress = 1f)
                            val next = current.copy(task = task, remoteSucceeded = true, remoteOutputs = result.outputs.toList(),
                                usage = result.usage.toMap(), updatedAt = clock(), nextPollAt = 0)
                            store.save(next, MediaUsageEntry(id, next.providerId, next.modelId, task.request.sourceRef, cost, clock()))
                            next.takeUnless { canceled }
                        }
                    } ?: return
                }
            }
        }
        if (record.task.state == MediaTaskState.DOWNLOADING) {
            currentCoroutineContext().ensureActive()
            val paths = try {
                check(record.remoteSucceeded && record.remoteOutputs.isNotEmpty())
                backend.download(record.channel, record.task.request, requireNotNull(record.task.remoteId), record.remoteOutputs)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                downloadFailed(record, MediaTaskFailure.DOWNLOAD)
                return
            }
            if (paths.isEmpty() || paths.any { !isOutputPath(record.task.request.outputDir, it) }) {
                downloadFailed(record, MediaTaskFailure.INVALID_OUTPUT)
                return
            }
            saveActive(record.copy(task = record.task.completed(paths, record.task.actualCost), remoteOutputs = emptyList(), updatedAt = clock()))
        }
    }

    /** 所有执行结果与取消串行写入，旧快照不能覆盖取消终态。 */
    private suspend fun saveActive(record: PersistentMediaTask): Boolean = mutations.withLock {
        val current = required(record.task.id)
        if (current.historyOnly || current.task.state == MediaTaskState.CANCELED) return@withLock false
        store.save(record)
        true
    }
    private suspend fun downloadFailed(record: PersistentMediaTask, failure: MediaTaskFailure) {
        saveActive(record.copy(task = record.task.copy(state = MediaTaskState.DOWNLOAD_FAILED, failure = failure, error = messages.message(failure)), updatedAt = clock()))
    }
    private suspend fun unknown(id: String) = mutations.withLock {
        val record = required(id)
        if (uncertain(record)) store.save(asUnknown(record))
    }
    private fun asUnknown(record: PersistentMediaTask) = record.copy(task = record.task.copy(state = MediaTaskState.RESULT_UNKNOWN,
        failure = MediaTaskFailure.SUBMISSION_UNKNOWN, error = messages.message(MediaTaskFailure.SUBMISSION_UNKNOWN)), updatedAt = clock())
    private suspend fun cancelRemote(record: PersistentMediaTask) {
        val remoteId = record.task.remoteId ?: return
        val binding = bindings.find { it.id == record.bindingId } ?: return
        try {
            withTimeoutOrNull(10_000) {
                if (bindingMatches(binding, record)) binding.backend.cancel(record.channel, remoteId)
            }
        } catch (_: Exception) {
            // 后端自身超时也属于取消失败；调用方取消仍按当前协程传播。
            currentCoroutineContext().ensureActive()
        }
    }
    private fun nextPoll(record: PersistentMediaTask): PersistentMediaTask = record.copy(
        pollAttempt = record.pollAttempt.coerceIn(0, 62) + 1, nextPollAt = clock() + polling.delayAfter(record.pollAttempt), updatedAt = clock(),
    )
    private suspend fun bindingMatches(binding: MediaBackendBinding, record: PersistentMediaTask): Boolean = try {
        binding.modelId(record.channel) == record.modelId && binding.providerFor(record.channel) == record.providerId &&
            (record.configurationFingerprint == null || binding.configurationFor(record.channel) == record.configurationFingerprint)
    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    private fun selectBinding(channel: MediaChannel): MediaBackendBinding = bindings.filter { it.backend.handles(channel) }.singleOrNull()
        ?: throw IllegalArgumentException("MEDIA_BACKEND_MISSING_OR_AMBIGUOUS")
    private suspend fun required(id: String): PersistentMediaTask = store.get(id) ?: throw IllegalArgumentException("MEDIA_TASK_NOT_FOUND")
    private fun signal() {
        signals.trySend(Unit)
        // 调度失败不会撤销已经持久化的提交；下一次前台恢复会重新唤醒。
        runCatching(wake)
    }
    private fun validate(channel: MediaChannel, request: MediaGenerationRequest) {
        val result = validateRequest(channel, request)
        val errors = buildList {
            addAll(result.specErrors)
            if (request.sessionId.isBlank()) add("sessionId")
            if (!relativePath(request.outputDir)) add("outputDir")
        }
        val complete = result.copy(specErrors = errors)
        if (!complete.valid) throw InvalidMediaRequestException(complete)
    }
    private fun unresolvedRemote(record: PersistentMediaTask): Boolean = !record.historyOnly && !record.remoteSucceeded &&
        (record.task.state in setOf(MediaTaskState.SUBMITTING, MediaTaskState.RESULT_UNKNOWN, MediaTaskState.RUNNING) ||
            (record.task.remoteId != null && record.task.state == MediaTaskState.FAILED && record.task.failure == MediaTaskFailure.POLL))
    private fun canStart(record: PersistentMediaTask, occupied: List<PersistentMediaTask>): Boolean =
        occupied.size < concurrency.maxTotal &&
            occupied.count { it.providerId == record.providerId } < (concurrency.providerLimits[record.providerId] ?: concurrency.defaultProvider) &&
            occupied.count { it.channel.id == record.channel.id } < (concurrency.channelLimits[record.channel.id] ?: concurrency.defaultChannel)
    private fun runnable(record: PersistentMediaTask) = !record.historyOnly && record.task.state in setOf(MediaTaskState.QUEUED, MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING)
    private fun uncertain(record: PersistentMediaTask) = !record.historyOnly && record.task.remoteId == null &&
        record.task.state in setOf(MediaTaskState.SUBMITTING, MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING)

    companion object {
        private val cancellableStates = setOf(MediaTaskState.QUEUED, MediaTaskState.SUBMITTING, MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING)
        internal fun relativePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
            !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
        internal fun isOutputPath(directory: String, path: String): Boolean = relativePath(path) && path.startsWith("$directory/")
    }
}
