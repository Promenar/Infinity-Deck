package com.promenar.infinity.data.storage

import com.promenar.infinity.data.ssh.*
import com.promenar.infinity.data.storage.RemoteTransferJournal.Record
import com.promenar.infinity.data.storage.RemoteTransferJournal.Source
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.domain.storage.RemoteTransferDirection.*
import com.promenar.infinity.domain.storage.RemoteTransferState.*
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RemoteTransferEndpoint(
    val transport: SftpMountTransport,
    val hostIdentityDigest: String,
    val rootPath: String,
    val readOnly: Boolean,
)

/** 由应用进程持有一个实例，用 noBackupFilesDir 中的检查点恢复任务。 */
class RemoteTransferManager(
    private val journal: RemoteTransferJournal,
    private val resolveEndpoint: suspend (RemoteTransferTask) -> RemoteTransferEndpoint,
    private val previewCache: RemotePreviewCache? = null,
    private val registerActivity: suspend (key: String, active: Boolean) -> Unit = { _, _ -> },
    private val timeMillis: () -> Long = System::currentTimeMillis,
) : RemoteTransferController, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val records = journal.load().associateByTo(linkedMapOf()) { it.task.id }
    private val jobs = mutableMapOf<String, Job>()
    private val cleanupAttempted = mutableSetOf<String>()
    private val reconcileAttempted = mutableSetOf<String>()
    private val mutableTasks = MutableStateFlow<List<RemoteTransferTask>>(emptyList())
    override val tasks = mutableTasks.asStateFlow()
    private val mutableSummary = MutableStateFlow(RemoteTransferSummary(0, 0, 0, 0, 0))
    override val summary = mutableSummary.asStateFlow()

    init {
        publish()
        scope.launch { lock.withLock { schedule() } }
    }

    override suspend fun enqueueUpload(request: RemoteTransferRequest) = enqueue(UPLOAD, request)
    override suspend fun enqueueDownload(request: RemoteTransferRequest) = enqueue(DOWNLOAD, request)
    override suspend fun enqueuePreviewCache(request: RemoteTransferRequest) = enqueue(PREVIEW_CACHE, request)

    private suspend fun enqueue(direction: RemoteTransferDirection, request: RemoteTransferRequest): RemoteTransferTask = io {
        val remote = SftpRemoteMountSpec(request.hostAlias, request.remoteFullPath).normalized().rootPath
        val name = remote.substringAfterLast('/')
        ExternalFolderPaths.validateName(name)
        if (name.startsWith(".infinity-part-")) throw SshFailure("INVALID_REMOTE_PATH")
        if (direction == UPLOAD && request.overwritePolicy == RemoteOverwritePolicy.ALLOW_OVERWRITE) throw SshFailure("UNSUPPORTED_OVERWRITE")
        val local = if (direction == PREVIEW_CACHE) null else validateLocalPath(request.localPath)
        val timestamp = timeMillis()
        val id = UUID.randomUUID().toString()
        val task = RemoteTransferTask(
            id, direction, QUEUED, request.mountId, request.hostAlias, name, request.sourceLabel, request.targetLabel,
            request.mountVirtualPath, remote, if (direction == DOWNLOAD) local else null,
            0, 0, 0, null, request.overwritePolicy, true, null, null, null,
            request.sessionId, timestamp, timestamp,
        )
        lock.withLock {
            set(Record(task, localSourcePath = if (direction == UPLOAD) local else null))
            schedule()
        }
        task
    }

    override suspend fun pause(taskId: String) = change(setOf(taskId)) { record ->
        when (record.task.state) {
            QUEUED -> record.state(PAUSED)
            PREFLIGHT, TRANSFERRING, VERIFYING -> record.state(PAUSING)
            else -> record
        }
    }

    override suspend fun pauseAll() = change(null) { record ->
        when (record.task.state) {
            QUEUED -> record.state(PAUSED)
            PREFLIGHT, TRANSFERRING, VERIFYING -> record.state(PAUSING)
            else -> record
        }
    }

    override suspend fun resume(taskId: String) = change(setOf(taskId), ::resumeRecord)
    override suspend fun resumeAll() = change(null, ::resumeRecord)

    private fun resumeRecord(record: Record) =
        if (record.task.state == PAUSED) record.state(QUEUED) else record

    override suspend fun retry(taskId: String) = io { lock.withLock {
        val record = records[taskId] ?: return@withLock
        if (record.hidden) return@withLock
        when (record.task.state) {
            FAILED -> set(record.state(QUEUED).copy(verifiedSha256 = null, verificationMethod = null))
            RECONCILING -> if (taskId !in jobs) {
                reconcileAttempted.remove(taskId)
                set(record.state(RECONCILING))
            }
            else -> Unit
        }
        schedule()
    } }

    override suspend fun cancel(taskId: String): Unit = io {
        val job = lock.withLock {
            val record = records[taskId] ?: return@withLock null
            when (record.task.state) {
                COMMITTING, RECONCILING, CANCELLING, CANCELLED, SUCCEEDED -> null
                else -> {
                    val running = jobs[taskId]
                    if (running != null) {
                        set(record.state(CANCELLING))
                    } else {
                        set(record.state(CANCELLED).copy(cleanupPending = record.hasTemporary))
                        schedule()
                    }
                    running
                }
            }
        }
        job?.cancelAndJoin()
    }

    override suspend fun remove(taskId: String) = dropFinished(setOf(taskId))
    override suspend fun clearFinished() = dropFinished(null)

    private suspend fun dropFinished(ids: Set<String>?) = io { lock.withLock {
        val next = records.toMutableMap()
        for (record in records.values) {
            val task = record.task
            if ((ids == null || task.id in ids) && task.state in finished) {
                if (record.hasTemporary || record.cleanupPending) {
                    next[task.id] = record.copy(hidden = true, cleanupPending = true)
                    cleanupAttempted.remove(task.id)
                } else next.remove(task.id)
            }
        }
        save(next)
        schedule()
    } }

    override suspend fun awaitTask(taskId: String, waitMs: Long): RemoteTransferTask? {
        require(waitMs >= 0)
        fun List<RemoteTransferTask>.task() = firstOrNull { it.id == taskId }
        return withTimeoutOrNull(waitMs) {
            tasks.first { list ->
                val task = list.task()
                task == null || task.state in finished || task.state == PAUSED ||
                    (task.state == RECONCILING && task.errorCode != null)
            }.task()
        } ?: tasks.value.task()
    }

    /** 网络恢复时允许重扫此前离线的临时文件清理项。 */
    suspend fun retryPendingCleanup() = io { lock.withLock {
        cleanupAttempted.clear()
        schedule()
    } }

    /** 前台服务的停止回调可提交暂停，已开始的提交步骤将完成对账。 */
    fun pauseFromService(taskId: String) { scope.launch { pause(taskId) } }

    override fun close() { scope.cancel() }

    private suspend fun change(ids: Set<String>?, transform: (Record) -> Record) = io { lock.withLock {
        save(records.mapValues { (id, record) -> if (!record.hidden && (ids == null || id in ids)) transform(record) else record })
        schedule()
    } }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun schedule() {
        if (!scope.isActive) return
        val occupied = jobs.keys.mapNotNull { records[it]?.task?.hostAlias }.toMutableSet()
        for (record in records.values) {
            if (jobs.size >= 2) return
            val task = record.task
            val cleanup = record.cleanupPending && task.id !in cleanupAttempted
            val work = !record.hidden && (task.state == QUEUED ||
                task.state == RECONCILING && task.id !in reconcileAttempted)
            if ((!cleanup && !work) || task.id in jobs || !occupied.add(task.hostAlias)) continue
            if (cleanup) cleanupAttempted.add(task.id)
            if (work && task.state == RECONCILING) reconcileAttempted.add(task.id)
            // ATOMIC 确保入队后立即取消时也执行清理与释放并发槽位。
            jobs[task.id] = scope.launch(start = CoroutineStart.ATOMIC) { run(task.id, cleanup, work) }
        }
    }

    private suspend fun run(id: String, cleanup: Boolean, work: Boolean) {
        var activity = false
        try {
            if (!work) {
                currentCoroutineContext().ensureActive()
                clearTemporary(id)
                return
            }
            registerActivity(id, true)
            activity = true
            if (cleanup) clearTemporary(id)
            val initial = record(id)
            if (initial.task.state == PAUSED) return
            val endpoint = resolveEndpoint(initial.task)
            validateEndpoint(initial, endpoint)
            if (initial.task.state == RECONCILING) {
                edit(id) { it.state(RECONCILING) }
                reconcile(id, endpoint)
            } else {
                advance(id, PREFLIGHT)
                when (initial.task.direction) {
                    UPLOAD -> upload(id, endpoint)
                    DOWNLOAD, PREVIEW_CACHE -> download(id, endpoint)
                }
            }
        } catch (_: PauseTransfer) {
            withContext(NonCancellable) {
                val cancelling = lock.withLock {
                    val current = records.getValue(id)
                    if (current.task.state == CANCELLING) true
                    else { set(current.state(PAUSED)); false }
                }
                if (cancelling) finishCancellation(id)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (record(id).task.state == CANCELLING) {
                    finishCancellation(id)
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            if (work) {
                val code = when (failure) {
                    is SshFailure -> failure.code
                    is ExternalFolderException -> failure.code
                    else -> "IO_FAILED"
                }
                edit(id) { current ->
                    when (current.task.state) {
                        CANCELLING -> current.state(CANCELLED).copy(cleanupPending = current.hasTemporary)
                        PAUSING -> current.state(PAUSED)
                        COMMITTING, RECONCILING -> if (code in knownPublishFailures) current.state(FAILED, code)
                            else current.state(RECONCILING, code)
                        SUCCEEDED -> current
                        else -> current.state(FAILED, code)
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                if (activity) runCatching { registerActivity(id, false) }
                lock.withLock {
                    jobs.remove(id)
                    schedule()
                }
            }
        }
    }

    private suspend fun finishCancellation(id: String) {
        edit(id) { it.copy(cleanupPending = it.hasTemporary) }
        clearTemporary(id)
        edit(id) { it.state(CANCELLED) }
    }

    private suspend fun upload(id: String, endpoint: RemoteTransferEndpoint) {
        if (endpoint.readOnly) throw SshFailure("READ_ONLY")
        val initial = record(id)
        val task = initial.task
        val file = File(requireNotNull(initial.localSourcePath))
        val source = localSource(file)
        requireUnchanged(initial.source, source)
        bind(id, endpoint, source)
        val transport = endpoint.transport
        val temp = sftpPartPath(task.remoteFullPath, id)
        resumePart(id, endpoint) { transport.stat(temp)?.also {
            if (!it.isRegular || it.isSymlink || (it.permissions and 511) != 384) throw SshFailure("INVALID_TEMPORARY_FILE")
        }?.size }
        if (!record(id).ownsTemporary) createTemporary(id) { transport.createTemporary(temp) }
        advance(id, TRANSFERRING)
        val out = transport.sftpResumableUploadChunked(temp, file, source.size, record(id).task.bytesTransferred,
            { directive(id) }, progress(id))
        if (out.paused) throw PauseTransfer()
        advance(id, VERIFYING)
        requireUnchanged(source, localSource(file))
        val hash = requireNotNull(source.sha256)
        val receipt = transport.sha256(temp)
        if (receipt.sha256 != hash) throw SshFailure("CHECKSUM_MISMATCH")
        revalidateEndpoint(id, endpoint, writing = true)
        prepareCommit(id, receipt)
        val committed = transport.publish(temp, task.remoteFullPath, hash)
        success(id, committed.alreadyExistedSameHash)
        clearTemporary(id)
    }

    private suspend fun download(id: String, endpoint: RemoteTransferEndpoint) {
        val initial = record(id)
        val task = initial.task
        val cache = if (task.direction == PREVIEW_CACHE) previewCache ?: throw SshFailure("PREVIEW_CACHE_UNAVAILABLE") else null
        val epoch = cache?.generationToken()
        val transport = endpoint.transport
        val source = remoteSource(transport, task.remoteFullPath)
        if (cache != null && source.size > cache.stats.value.maxBytes) throw SshFailure("TOO_LARGE")
        requireUnchanged(initial.source, source)
        bind(id, endpoint, source)
        val part = localPart(task)
        resumePart(id, endpoint) { if (Files.exists(part.toPath(), NOFOLLOW_LINKS)) localAttributes(part).size() else null }
        if (!record(id).ownsTemporary) createTemporary(id) { Files.createFile(part.toPath()) }
        advance(id, TRANSFERRING)
        val out = transport.sftpResumableDownloadChunked(task.remoteFullPath, part, source.size, record(id).task.bytesTransferred,
            { directive(id) }, progress(id))
        if (out.paused) throw PauseTransfer()
        advance(id, VERIFYING)
        requireUnchanged(source, remoteSource(transport, task.remoteFullPath))
        val remoteHash = transport.sha256(task.remoteFullPath)
        if (remoteHash.sha256 != sha256File(part)) throw SshFailure("CHECKSUM_MISMATCH")
        requireUnchanged(source, remoteSource(transport, task.remoteFullPath))
        revalidateEndpoint(id, endpoint, writing = false)
        prepareCommit(id, remoteHash)
        val deduplicated = commitDownload(record(id), endpoint, part, epoch)
        success(id, deduplicated)
        clearTemporary(id)
    }

    private suspend fun createTemporary(id: String, create: suspend () -> Unit) {
        edit(id) {
            checkDirective(it.task)
            it.copy(creatingTemporary = true)
        }
        try {
            create()
            withContext(NonCancellable) { edit(id) { it.copy(ownsTemporary = true, creatingTemporary = false) } }
        } catch (failure: Exception) {
            if (failure is FileAlreadyExistsException || failure is SshFailure && failure.code == "TEMPORARY_CONFLICT") {
                withContext(NonCancellable) { edit(id) { it.copy(creatingTemporary = false) } }
                throw SshFailure("TEMPORARY_CONFLICT")
            }
            throw failure
        }
    }

    private suspend fun resumePart(id: String, endpoint: RemoteTransferEndpoint, size: suspend () -> Long?) {
        val current = record(id)
        if (current.creatingTemporary) {
            when (size()) {
                null -> edit(id) { it.copy(creatingTemporary = false) }
                0L -> edit(id) { it.copy(ownsTemporary = true, creatingTemporary = false) }
                else -> throw SshFailure("TEMPORARY_CONFLICT")
            }
        }
        val checkpoint = record(id)
        if (!checkpoint.ownsTemporary) {
            if (checkpoint.task.bytesTransferred != 0L) edit(id) { it.copy(task = it.task.copy(bytesTransferred = 0)) }
            return
        }
        if (size() == checkpoint.task.bytesTransferred) return
        stopBeforeCommit(id)
        clearTemporary(id, endpoint)
        if (record(id).ownsTemporary) throw SshFailure("TEMPORARY_CLEANUP_FAILED")
        edit(id) { it.copy(task = it.task.copy(bytesTransferred = 0)) }
    }

    private suspend fun reconcile(id: String, endpoint: RemoteTransferEndpoint) {
        val current = record(id)
        val task = current.task
        val hash = current.verifiedSha256 ?: throw SshFailure("CHECKPOINT_INVALID")
        val deduplicated = when (task.direction) {
            UPLOAD -> {
                val transport = endpoint.transport
                val target = transport.stat(task.remoteFullPath)
                if (target != null) {
                    if (!target.isRegular || target.isSymlink || transport.sha256(task.remoteFullPath).sha256 != hash) {
                        throw SshFailure("TARGET_CONFLICT")
                    }
                    true
                } else {
                    if (endpoint.readOnly) throw SshFailure("READ_ONLY")
                    requireUnchanged(current.source, localSource(File(requireNotNull(current.localSourcePath))))
                    transport.publish(sftpPartPath(task.remoteFullPath, id), task.remoteFullPath, hash).alreadyExistedSameHash
                }
            }
            PREVIEW_CACHE -> {
                val cache = previewCache ?: throw SshFailure("PREVIEW_CACHE_UNAVAILABLE")
                val entry = cache.lookup(previewKey(current, endpoint))
                if (entry != null && sha256File(entry) == hash) true
                else commitVerifiedDownload(current, endpoint, hash)
            }
            DOWNLOAD -> {
                val target = localTarget(task)
                if (Files.exists(target.toPath(), NOFOLLOW_LINKS) && localAttributes(target).size() == task.bytesTotal && sha256File(target) == hash) {
                    true
                } else commitVerifiedDownload(current, endpoint, hash)
            }
        }
        success(id, deduplicated)
        clearTemporary(id)
    }

    private suspend fun commitVerifiedDownload(record: Record, endpoint: RemoteTransferEndpoint, hash: String): Boolean {
        val part = localPart(record.task)
        if (localAttributes(part).size() != record.task.bytesTotal || sha256File(part) != hash) throw SshFailure("CHECKPOINT_INVALID")
        requireUnchanged(record.source, remoteSource(endpoint.transport, record.task.remoteFullPath))
        if (endpoint.transport.sha256(record.task.remoteFullPath).sha256 != hash) throw SshFailure("SOURCE_CHANGED")
        return commitDownload(record, endpoint, part, previewCache?.generationToken())
    }

    private suspend fun commitDownload(record: Record, endpoint: RemoteTransferEndpoint, part: File, epoch: Long?): Boolean {
        val task = record.task
        val hash = requireNotNull(record.verifiedSha256)
        if (task.direction == PREVIEW_CACHE) {
            val cache = previewCache ?: throw SshFailure("PREVIEW_CACHE_UNAVAILABLE")
            val key = previewKey(record, endpoint)
            return cache.publishVerified(key, part, hash, requireNotNull(epoch)) ?: throw SshFailure("PREVIEW_CACHE_UNAVAILABLE")
        }

        val target = localTarget(task)
        try {
            Files.createLink(target.toPath(), part.toPath())
            return false
        } catch (_: FileAlreadyExistsException) {
            val attributes = localAttributes(target)
            if (attributes.size() == task.bytesTotal && sha256File(target) == hash) return true
            if (task.overwritePolicy != RemoteOverwritePolicy.ALLOW_OVERWRITE) throw SshFailure("TARGET_CONFLICT")
            Files.move(part.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
            return false
        }
    }

    private fun previewKey(record: Record, endpoint: RemoteTransferEndpoint): RemotePreviewKey {
        val source = requireNotNull(record.source)
        return RemotePreviewKey(endpoint.hostIdentityDigest, record.task.remoteFullPath, source.size, source.modifiedAt)
    }

    private suspend fun clearTemporary(id: String, existingEndpoint: RemoteTransferEndpoint? = null) {
        val current = record(id)
        if (!current.hasTemporary) return
        val task = current.task
        try {
            if (task.direction == UPLOAD) {
                val endpoint = existingEndpoint ?: resolveEndpoint(task)
                // 清理必须绑定到产生临时文件的主机和挂载根。
                validateEndpoint(current, endpoint)
                if (endpoint.readOnly) throw SshFailure("READ_ONLY")
                val path = sftpPartPath(task.remoteFullPath, id)
                val part = endpoint.transport.stat(path)
                if (part != null) {
                    if (!part.isRegular || part.isSymlink || (part.permissions and 511) != 384 ||
                        (!current.ownsTemporary && part.size != 0L)
                    ) throw SshFailure("INVALID_TEMPORARY_FILE")
                    endpoint.transport.removeFile(path)
                }
            } else {
                val path = localPart(task).toPath()
                if (Files.exists(path, NOFOLLOW_LINKS) && (!Files.isRegularFile(path, NOFOLLOW_LINKS) ||
                        (!current.ownsTemporary && Files.size(path) != 0L))
                ) throw SshFailure("INVALID_TEMPORARY_FILE")
                Files.deleteIfExists(path)
            }
            lock.withLock {
                val latest = records.getValue(id)
                if (latest.hidden) save(records - id)
                else set(latest.copy(ownsTemporary = false, creatingTemporary = false, cleanupPending = false))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            edit(id) { next -> next.copy(cleanupPending = true) }
        }
    }

    private suspend fun revalidateEndpoint(id: String, initial: RemoteTransferEndpoint, writing: Boolean) {
        val current = record(id)
        val latest = resolveEndpoint(current.task)
        validateEndpoint(current, latest)
        if (SftpRemoteMountSpec.canonicalPath(initial.rootPath) != SftpRemoteMountSpec.canonicalPath(latest.rootPath)) {
            throw SshFailure("MOUNT_CONFIGURATION_CHANGED")
        }
        if (writing && latest.readOnly) throw SshFailure("READ_ONLY")
    }

    private fun validateEndpoint(record: Record, endpoint: RemoteTransferEndpoint) {
        val root = SftpRemoteMountSpec.canonicalPath(endpoint.rootPath)
        if (record.mountRoot != null && record.mountRoot != root) throw SshFailure("MOUNT_CONFIGURATION_CHANGED")
        val path = record.task.remoteFullPath
        if (!path.startsWith(root.trimEnd('/') + "/") || path == root) throw SshFailure("INVALID_REMOTE_PATH")
        val relative = path.removePrefix(root.trimEnd('/')).removePrefix("/")
        relative.split('/').forEach {
            ExternalFolderPaths.validateName(it)
            if (it.startsWith(".infinity-part-")) throw SshFailure("INVALID_REMOTE_PATH")
        }
        if (endpoint.hostIdentityDigest.isBlank() || (record.hostIdentity != null && record.hostIdentity != endpoint.hostIdentityDigest)) {
            throw SshFailure("HOST_CONFIGURATION_CHANGED")
        }
    }

    private suspend fun bind(id: String, endpoint: RemoteTransferEndpoint, source: Source) = edit(id) {
        it.copy(source = source, hostIdentity = endpoint.hostIdentityDigest, mountRoot = SftpRemoteMountSpec.canonicalPath(endpoint.rootPath),
            task = it.task.copy(bytesTotal = source.size))
    }

    private suspend fun prepareCommit(id: String, receipt: RemoteSha256Receipt) = edit(id) {
        checkDirective(it.task)
        it.state(COMMITTING).copy(verifiedSha256 = receipt.sha256, verificationMethod = receipt.verificationMethod)
    }

    private suspend fun success(id: String, deduplicated: Boolean) = edit(id) {
        val task = it.task
        val receipt = RemoteTransferReceipt(task.mountVirtualPath, task.remoteFullPath, task.bytesTotal,
            requireNotNull(it.verifiedSha256), requireNotNull(it.verificationMethod), task.id, deduplicated)
        val done = it.state(SUCCEEDED)
        done.copy(cleanupPending = it.hasTemporary, task = done.task.copy(receipt = receipt, bytesTransferred = task.bytesTotal))
    }

    private fun progress(id: String): suspend (Long) -> Unit {
        var lastTime = System.nanoTime()
        var lastBytes: Long? = null
        return { bytes ->
            val now = System.nanoTime()
            edit(id) { current ->
                val task = current.task
                val delta = bytes - (lastBytes ?: task.bytesTransferred)
                val elapsed = (now - lastTime).coerceAtLeast(1)
                val rate = (delta.toDouble() * 1_000_000_000 / elapsed).toLong()
                current.copy(task = task.copy(bytesTransferred = bytes, bytesPerSecond = rate,
                    etaSeconds = if (rate > 0) (task.bytesTotal - bytes) / rate else null, updatedAt = timeMillis()))
            }
            lastTime = now
            lastBytes = bytes
        }
    }

    private suspend fun stopBeforeCommit(id: String) = checkDirective(record(id).task)

    private fun checkDirective(task: RemoteTransferTask) {
        if (task.state == PAUSING || task.state == PAUSED) throw PauseTransfer()
        if (task.state == CANCELLING) throw CancellationException("远程传输已取消")
    }

    private fun directive(id: String): SftpChunkDirective = when (tasks.value.firstOrNull { it.id == id }?.state) {
        PAUSING, PAUSED -> SftpChunkDirective.PAUSE
        CANCELLING, CANCELLED -> SftpChunkDirective.CANCEL
        else -> SftpChunkDirective.CONTINUE
    }

    private suspend fun advance(id: String, state: RemoteTransferState) = edit(id) {
        checkDirective(it.task)
        it.state(state)
    }

    private suspend fun record(id: String) = lock.withLock { records.getValue(id) }
    private suspend fun edit(id: String, transform: (Record) -> Record) = lock.withLock { set(transform(records.getValue(id))) }
    private fun set(record: Record) = save(records + (record.task.id to record))

    private fun save(next: Map<String, Record>) {
        if (next == records) return
        journal.save(next.values)
        records.clear()
        records.putAll(next)
        publish()
    }

    private fun publish() {
        val visible = records.values.filterNot { it.hidden }.map { it.task }
        mutableTasks.value = visible
        mutableSummary.value = RemoteTransferSummary(
            visible.count { it.state !in finished && it.state != PAUSED },
            visible.count { it.state == PAUSED },
            visible.count { it.state == SUCCEEDED },
            visible.count { it.state == FAILED },
            visible.sumOf { it.bytesPerSecond },
        )
    }

    private fun Record.state(state: RemoteTransferState, code: String? = null) = copy(task = task.copy(
        state = state, bytesPerSecond = 0, etaSeconds = null, errorCode = code,
        errorMessage = code?.let { "远程传输失败（$it）" },
        canResume = state !in setOf(CANCELLED, SUCCEEDED) && code !in setOf("SOURCE_CHANGED", "HOST_CONFIGURATION_CHANGED"),
        updatedAt = timeMillis(),
    ))

    private fun localTarget(task: RemoteTransferTask) = File(validateLocalPath(task.localTargetPath))

    private fun localPart(task: RemoteTransferTask): File {
        val parent = task.localTargetPath?.let { localTarget(task).parentFile } ?: journal.directory
        return File(parent, ".infinity-part-${task.id}.tmp")
    }

    private fun validateLocalPath(raw: String?): String {
        val file = raw?.let(::File) ?: throw SshFailure("INVALID_LOCAL_PATH")
        val path = file.toPath()
        if (!file.isAbsolute || path != path.normalize() || file.name.startsWith(".infinity-part-")) throw SshFailure("INVALID_LOCAL_PATH")
        val parent = path.parent ?: throw SshFailure("INVALID_LOCAL_PATH")
        if (!Files.isDirectory(parent) || parent.toRealPath() != parent || Files.isSymbolicLink(path)) throw SshFailure("INVALID_LOCAL_PATH")
        return file.path
    }

    private fun localAttributes(file: File): BasicFileAttributes =
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            .also { if (!it.isRegularFile) throw SshFailure("UNSUPPORTED_FILE_TYPE") }

    private fun localSource(file: File): Source {
        validateLocalPath(file.path)
        val before = localAttributes(file)
        val hash = sha256File(file)
        val after = localAttributes(file)
        if (before.size() != after.size() || before.lastModifiedTime() != after.lastModifiedTime() || before.fileKey() != after.fileKey()) throw SshFailure("SOURCE_CHANGED")
        return Source(before.size(), before.lastModifiedTime().toMillis(), hash)
    }

    private suspend fun remoteSource(transport: SftpMountTransport, path: String): Source {
        val entry = transport.stat(path) ?: throw SshFailure("NOT_FOUND")
        if (entry.isSymlink || !entry.isRegular) throw SshFailure("UNSUPPORTED_FILE_TYPE")
        return Source(entry.size, entry.modifiedAtMillis)
    }

    private fun requireUnchanged(expected: Source?, current: Source) {
        if (expected != null && current != expected) throw SshFailure("SOURCE_CHANGED")
    }

    private suspend fun <T> io(action: suspend () -> T): T = withContext(Dispatchers.IO) { action() }

    private val Record.hasTemporary get() = ownsTemporary || creatingTemporary

    private class PauseTransfer : Exception()
    private companion object {
        val finished = setOf(CANCELLED, SUCCEEDED, FAILED)
        val knownPublishFailures = setOf("TARGET_CONFLICT", "ATOMIC_PUBLISH_UNSUPPORTED", "CHECKSUM_MISMATCH",
            "INVALID_TEMPORARY_FILE", "ACCESS_DENIED", "NOT_FOUND", "PREVIEW_CACHE_UNAVAILABLE")
    }
}
