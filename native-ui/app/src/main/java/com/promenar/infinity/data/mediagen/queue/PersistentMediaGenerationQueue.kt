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
    private val autoSubmitPolicy: AutoSubmitPolicy = RequireApproval,
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
            val estimate = binding.backend.estimate(channel, frozen)
            val task = MediaTask(UUID.randomUUID().toString(), frozen, estimate, initialState(frozen, estimate))
            require(binding.configurationFor(channel) == fingerprint && binding.providerFor(channel) == providerId && binding.modelId(channel) == modelId && binding.expectedOutputsFor(channel) == expectedOutputs) { "MEDIA_CONFIGURATION_CHANGED" }
            store.save(PersistentMediaTask(task, channel, binding.id, providerId, modelId, clock(), configurationFingerprint = fingerprint, expectedOutputs = expectedOutputs))
            signal()
            task
        }
    }

    override suspend fun outputReservations(taskIds: Set<String>): Map<String, Int> = withContext(ioDispatcher) {
        store.all().filter { it.task.id in taskIds }.associate { it.task.id to it.expectedOutputs }
    }

    override fun observeTasks(): Flow<List<MediaTask>> = store.observe().map { records -> records.map { it.task } }
    override fun observeTask(id: String): Flow<MediaTask?> = store.observe().map { records -> records.find { it.task.id == id }?.task }.distinctUntilChanged()

    override suspend fun approve(id: String) = withContext(ioDispatcher) {
        mutations.withLock {
            val record = required(id)
            check(!record.historyOnly)
            store.save(record.copy(task = record.task.approve(), updatedAt = clock()))
            signal()
        }
    }
    override suspend fun reject(id: String) = withContext(ioDispatcher) {
        mutations.withLock {
            check(required(id).task.state == MediaTaskState.AWAITING_APPROVAL)
            store.delete(id)
            signal()
        }
    }
    override suspend fun retry(id: String) = withContext(ioDispatcher) {
        mutations.withLock {
            val record = required(id)
            check(!record.historyOnly) { "MEDIA_HISTORY_ONLY" }
            val task = record.task
            val next = when {
                task.state == MediaTaskState.DOWNLOAD_FAILED -> record.copy(task = task.retry())
                task.state == MediaTaskState.FAILED && task.failure == MediaTaskFailure.POLL && task.remoteId != null -> record.copy(task = task.retry())
                task.state == MediaTaskState.FAILED && task.failure in setOf(MediaTaskFailure.REMOTE, MediaTaskFailure.CHANNEL_UNAVAILABLE) -> {
                    // 明确失败的远端请求重做时，重新验证、估价与批准。
                    val channel = registry.channel(task.request.channelId) ?: throw IllegalArgumentException("MEDIA_CHANNEL_NOT_FOUND")
                    validate(channel, task.request)
                    val binding = selectBinding(channel)
                    val fingerprint = binding.configurationFor(channel)
                    val providerId = binding.providerFor(channel)
                    val modelId = binding.modelId(channel)
                    val expectedOutputs = binding.expectedOutputsFor(channel).also { require(it > 0) }
                    val estimate = binding.backend.estimate(channel, task.request)
                    val state = initialState(task.request, estimate)
                    require(binding.configurationFor(channel) == fingerprint && binding.providerFor(channel) == providerId && binding.modelId(channel) == modelId && binding.expectedOutputsFor(channel) == expectedOutputs) { "MEDIA_CONFIGURATION_CHANGED" }
                    PersistentMediaTask(
                        task.copy(estimate = estimate, state = state, remoteId = null,
                            progress = null, outputPaths = emptyList(), actualCost = null, error = null, failure = null),
                        channel, binding.id, providerId, modelId, clock(), configurationFingerprint = fingerprint, expectedOutputs = expectedOutputs,
                    )
                }
                else -> error("MEDIA_TASK_CANNOT_RETRY")
            }
            store.save(next.copy(updatedAt = clock(), pollAttempt = 0, consecutivePollErrors = 0, nextPollAt = 0))
            signal()
        }
    }

    suspend fun hasRunnableTasks(): Boolean = withContext(ioDispatcher) {
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

    /** 由前台服务持有运行周期；取消后保留查询/下载快照，下次启动接续。 */
    suspend fun runUntilIdle(): Unit = withContext(ioDispatcher) {
        runner.withLock {
            recover()
            coroutineScope {
                val jobs = mutableMapOf<String, Job>()
                signals.trySend(Unit)
                while (true) {
                    signals.receive()
                    jobs.entries.removeAll { it.value.isCompleted }
                    val all = store.all()
                    // 查询失败或提交未知时，远端可能仍在执行，继续占用提交额度。
                    val occupied = all.filter { unresolvedRemote(it) || it.task.id in jobs }.toMutableList()
                    val records = all.filter(::runnable).sortedBy { if (it.task.remoteId != null) 0 else 1 }
                    for (record in records) {
                        if (record.task.id in jobs || jobs.size >= concurrency.maxTotal) continue
                        if (record.task.remoteId == null && !canStart(record, occupied)) continue
                        val job = launch(start = CoroutineStart.LAZY) { execute(record.task.id) }
                        jobs[record.task.id] = job
                        if (occupied.none { it.task.id == record.task.id }) occupied += record
                        job.invokeOnCompletion { signals.trySend(Unit) }
                        job.start()
                    }
                    // 只剩被未决远端任务阻挡的排队项时释放前台服务，批准/重试会再次唤醒。
                    if (jobs.isEmpty()) break
                }
            }
        }
    }

    private suspend fun recover() {
        if (recovered) return
        store.all().filterNot { it.historyOnly }.forEach { record ->
            when {
                uncertain(record) -> unknown(record)
                record.task.remoteId != null && record.task.state in setOf(MediaTaskState.QUEUED, MediaTaskState.SUBMITTING) ->
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
            else store.save(record.copy(task = record.task.copy(state = MediaTaskState.FAILED,
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
                store.save(record.copy(task = record.task.copy(state = MediaTaskState.FAILED,
                    failure = MediaTaskFailure.CHANNEL_UNAVAILABLE, error = messages.message(MediaTaskFailure.CHANNEL_UNAVAILABLE)), updatedAt = clock()))
                return
            }
            record = record.copy(task = record.task.submitting(), updatedAt = clock())
            store.save(record)
            try {
                val remoteId = backend.submit(record.channel, record.task.request)
                require(remoteId.isNotBlank())
                // 返回远端 ID 后不允许取消打断落盘。
                record = record.copy(task = record.task.started(remoteId), updatedAt = clock())
                withContext(NonCancellable) { store.save(record) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { unknown(required(id)) }
                throw cancelled
            } catch (_: Exception) {
                unknown(required(id))
                return
            }
        }
        while (record.task.state == MediaTaskState.RUNNING) {
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
                store.save(record)
                continue
            }
            when (result) {
                is RemoteStatus.Running -> {
                    val progress = result.progress?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
                    record = nextPoll(record.copy(task = record.task.copy(progress = progress), consecutivePollErrors = 0))
                    store.save(record)
                }
                is RemoteStatus.Failed -> {
                    record = record.copy(task = record.task.copy(state = MediaTaskState.FAILED, failure = MediaTaskFailure.REMOTE,
                        error = messages.message(MediaTaskFailure.REMOTE)), updatedAt = clock())
                    store.save(record)
                }
                is RemoteStatus.Succeeded -> {
                    val cost = try { if (bindingMatches(binding, record)) backend.actualCost(record.channel, result.usage) else null } catch (_: Exception) { null }
                    record = record.copy(task = record.task.copy(state = MediaTaskState.DOWNLOADING, actualCost = cost, progress = 1f),
                        remoteSucceeded = true, remoteOutputs = result.outputs.toList(), usage = result.usage.toMap(), updatedAt = clock(), nextPollAt = 0)
                    store.save(record, MediaUsageEntry(record.task.id, record.providerId, record.modelId, record.task.request.sourceRef, cost, clock()))
                }
            }
        }
        if (record.task.state == MediaTaskState.DOWNLOADING) {
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
            store.save(record.copy(task = record.task.completed(paths, record.task.actualCost), remoteOutputs = emptyList(), updatedAt = clock()))
        }
    }

    private suspend fun downloadFailed(record: PersistentMediaTask, failure: MediaTaskFailure) {
        store.save(record.copy(task = record.task.copy(state = MediaTaskState.DOWNLOAD_FAILED, failure = failure, error = messages.message(failure)), updatedAt = clock()))
    }
    private suspend fun unknown(record: PersistentMediaTask) {
        if (record.task.remoteId != null) return
        store.save(record.copy(task = record.task.copy(state = MediaTaskState.RESULT_UNKNOWN,
            failure = MediaTaskFailure.SUBMISSION_UNKNOWN, error = messages.message(MediaTaskFailure.SUBMISSION_UNKNOWN)), updatedAt = clock()))
    }
    private fun nextPoll(record: PersistentMediaTask): PersistentMediaTask = record.copy(
        pollAttempt = record.pollAttempt.coerceIn(0, 62) + 1, nextPollAt = clock() + polling.delayAfter(record.pollAttempt), updatedAt = clock(),
    )
    private suspend fun initialState(request: MediaGenerationRequest, estimate: Money?): MediaTaskState =
        if (estimate != null && autoSubmitPolicy.allowsAutoSubmit(request.sourceRef, estimate)) MediaTaskState.QUEUED else MediaTaskState.AWAITING_APPROVAL
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
        internal fun relativePath(path: String): Boolean = path.isNotBlank() && !path.startsWith('/') &&
            !path.contains('\\') && !path.contains(':') && !path.contains('\u0000') && path.split('/').all { it.isNotBlank() && it != "." && it != ".." }
        internal fun isOutputPath(directory: String, path: String): Boolean = relativePath(path) && path.startsWith("$directory/")
    }
}
