package com.promenar.infinity.data.git

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 按规范路径和访问上下文共享只读状态。 */
class DefaultGitStatusCache(
    private val locate: suspend (String) -> GitWorkspaceResolution,
    private val read: suspend (GitWorkspaceTarget) -> GitInspectResult<GitStatus>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fingerprint: suspend (GitWorkspaceTarget) -> String? = ::repositoryFingerprint,
) : GitStatusCache {
    private data class Key(val path: String, val kind: String, val mappings: Map<String, String>?)
    private data class ReadOutcome(val result: GitInspectResult<GitStatus>, val revision: Long, val stable: Boolean)
    private class Entry(val target: GitWorkspaceTarget) {
        val state = MutableStateFlow(GitStatusSnapshot(null, null, false))
        var revision = 0L
        var dirty = true
        var fingerprint: String? = null
        var inFlight: Deferred<ReadOutcome>? = null
        var explicitRead = false
        var subscribers = 0
    }

    private val entries = ConcurrentHashMap<Key, Entry>()
    private val sessions = ConcurrentHashMap<String, Key>()

    private suspend fun entry(target: GitWorkspaceTarget): Pair<Key, Entry> {
        val canonical = withContext(io) { target.root.canonicalFile }
        val key = Key(canonical.path, target.kind, target.linuxPathMappings?.toMap())
        return key to entries.computeIfAbsent(key) { Entry(target.copy(root = canonical)) }
    }

    override suspend fun status(sessionId: String, maxAgeMillis: Long): GitInspectResult<GitStatus> {
        val resolution = locate(sessionId)
        if (resolution is GitWorkspaceResolution.Unavailable) {
            sessions.remove(sessionId)
            return GitInspectResult.Failure(GitInspectError.WorkspaceUnavailable(resolution.reason))
        }
        val (key, item) = entry((resolution as GitWorkspaceResolution.Ready).target)
        sessions[sessionId] = key
        return status(item, maxAgeMillis)
    }

    /** 资源管理器中未绑定会话的项目目录也使用同一份缓存。 */
    suspend fun status(target: GitWorkspaceTarget, maxAgeMillis: Long = GitStatusCache.DEFAULT_MAX_AGE_MILLIS): GitInspectResult<GitStatus> =
        status(entry(target).second, maxAgeMillis)

    /** 先展示已有结果，读取时间和失效状态由后续 status/observe 处理。 */
    suspend fun cachedStatus(sessionId: String): GitInspectResult<GitStatus>? {
        val resolution = locate(sessionId) as? GitWorkspaceResolution.Ready ?: return null
        val (key, item) = entry(resolution.target)
        sessions[sessionId] = key
        return item.state.value.result
    }

    suspend fun cachedStatus(target: GitWorkspaceTarget): GitInspectResult<GitStatus>? = entry(target).second.state.value.result

    private suspend fun status(item: Entry, maxAgeMillis: Long): GitInspectResult<GitStatus> {
        while (true) {
            val job = refreshIfNeeded(item, maxAgeMillis, explicit = true)
            if (job == null) {
                synchronized(item) { if (!item.dirty) item.state.value.result?.let { return it } }
                continue
            }
            val outcome = try {
                job.await()
            } catch (cancelled: CancellationException) {
                if (!currentCoroutineContext().isActive) throw cancelled
                continue
            }
            val current = synchronized(item) { item.revision == outcome.revision }
            if (current && outcome.stable) return outcome.result
        }
    }

    override fun observe(sessionId: String, maxAgeMillis: Long): Flow<GitStatusSnapshot> = flow {
        val resolution = locate(sessionId)
        if (resolution is GitWorkspaceResolution.Unavailable) {
            sessions.remove(sessionId)
            emit(GitStatusSnapshot(GitInspectResult.Failure(GitInspectError.WorkspaceUnavailable(resolution.reason)), clockMillis(), false))
            return@flow
        }
        val (key, item) = entry((resolution as GitWorkspaceResolution.Ready).target)
        sessions[sessionId] = key
        synchronized(item) { item.subscribers++ }
        try {
            coroutineScope {
                val watcher = launch {
                    while (isActive) {
                        refreshIfNeeded(item, maxAgeMillis, explicit = false)
                        delay(POLL_MILLIS)
                    }
                }
                try { emitAll(item.state) } finally { watcher.cancel() }
            }
        } finally {
            synchronized(item) {
                item.subscribers--
                if (item.subscribers == 0 && !item.explicitRead) {
                    val job = item.inFlight
                    if (job != null) {
                        item.dirty = true
                        job.cancel()
                    }
                }
            }
        }
    }

    private suspend fun refreshIfNeeded(item: Entry, maxAgeMillis: Long, explicit: Boolean): Deferred<ReadOutcome>? {
        val before = withContext(io) { fingerprint(item.target) }
        synchronized(item) {
            val snapshot = item.state.value
            val age = snapshot.readAtMillis?.let { clockMillis() - it }
            if (!item.dirty && before == item.fingerprint && age != null && age >= 0 && age <= maxAgeMillis) return null
            item.inFlight?.let {
                if (explicit) item.explicitRead = true
                return it
            }
            val revision = item.revision
            item.explicitRead = explicit
            item.state.value = snapshot.copy(refreshing = true)
            lateinit var job: Deferred<ReadOutcome>
            job = scope.async(start = CoroutineStart.LAZY) {
                val result = try {
                    read(item.target)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    GitInspectResult.Failure(GitInspectError.CommandFailed(null, "Git 状态读取失败"))
                }
                val after = withContext(io) { fingerprint(item.target) }
                val stable = before == after
                synchronized(item) {
                    if (item.inFlight === job && item.revision == revision && stable) {
                        item.dirty = false
                        item.fingerprint = after
                        item.state.value = GitStatusSnapshot(result, clockMillis(), false)
                    }
                }
                ReadOutcome(result, revision, stable)
            }
            item.inFlight = job
            job.invokeOnCompletion {
                val retry = synchronized(item) {
                    if (item.inFlight !== job) false else {
                        item.inFlight = null
                        item.explicitRead = false
                        if (item.state.value.refreshing) item.state.value = item.state.value.copy(refreshing = false)
                        item.subscribers > 0 && item.dirty
                    }
                }
                if (retry) scope.launch { refreshIfNeeded(item, GitStatusCache.DEFAULT_MAX_AGE_MILLIS, explicit = false) }
            }
            job.start()
            return job
        }
    }

    override fun invalidate(sessionId: String) {
        sessions[sessionId]?.let { key -> entries[key]?.let(::invalidateEntry) }
    }

    override fun invalidateAll() { entries.values.forEach(::invalidateEntry) }

    private fun invalidateEntry(item: Entry) {
        val refresh = synchronized(item) {
            item.revision++
            item.dirty = true
            item.subscribers > 0 && item.inFlight == null
        }
        if (refresh) scope.launch { refreshIfNeeded(item, GitStatusCache.DEFAULT_MAX_AGE_MILLIS, explicit = false) }
    }

    companion object {
        private const val POLL_MILLIS = 2_000L

        /** 读取普通仓库和宿主 worktree 元数据；Linux 映射无法安全确定时依靠 TTL。 */
        private suspend fun repositoryFingerprint(target: GitWorkspaceTarget): String? = runCatching {
            val git = File(target.root, ".git")
            fun metadata(file: File): String = try {
                val a = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                "${a.fileKey()}:${a.size()}:${a.lastModifiedTime().to(TimeUnit.NANOSECONDS)}:${a.creationTime().to(TimeUnit.NANOSECONDS)}"
            } catch (_: java.nio.file.NoSuchFileException) { "missing" }
            fun readSmall(file: File): String = file.inputStream().use { input ->
                val buffer = ByteArray(4096)
                val count = input.read(buffer).coerceAtLeast(0)
                String(buffer, 0, count, Charsets.UTF_8)
            }.trim()
            fun resolve(parent: File, path: String): File = (if (File(path).isAbsolute) File(path) else File(parent, path)).canonicalFile
            val control = if (Files.isDirectory(git.toPath(), LinkOption.NOFOLLOW_LINKS)) git else {
                if (target.linuxPathMappings != null || !Files.isRegularFile(git.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
                val pointer = readSmall(git)
                if (!pointer.startsWith("gitdir: ")) return null
                resolve(target.root, pointer.removePrefix("gitdir: "))
            }
            val commonPointer = File(control, "commondir")
            val common = if (commonPointer.isFile) resolve(control, readSmall(commonPointer)) else control
            val head = File(control, "HEAD")
            val headText = readSmall(head)
            val reference = headText.removePrefix("ref: ").takeIf {
                headText.startsWith("ref: ") && it.startsWith("refs/") && it.split('/').none { part -> part == "." || part == ".." || part.isEmpty() }
            }
            listOf(metadata(git), metadata(commonPointer), common.path, metadata(File(control, "index")), metadata(head), headText,
                reference?.let { metadata(File(control, it)) }, reference?.let { metadata(File(common, it)) },
                metadata(File(common, "packed-refs")), metadata(File(control, "FETCH_HEAD"))).joinToString("|")
        }.getOrNull()
    }
}
