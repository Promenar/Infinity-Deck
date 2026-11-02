package com.promenar.infinity.data.artifact

import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.data.canvas.CanvasStore
import com.promenar.infinity.domain.media.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
private data class StoredArtifact(
    val id: String, val title: String, val kind: ArtifactKind, val origin: ArtifactOrigin,
    val createdAt: Long, val updatedAt: Long, val currentVersion: Int, val createdByMessageId: String?,
    val sourcePath: String, val fileName: String, val mimeType: String, val versions: List<StoredVersion>,
)
@Serializable
private data class StoredVersion(val version: Int, val hash: String, val size: Long)
private fun StoredArtifact.summary() = ArtifactSummary(id, kind, origin, title, createdAt, updatedAt, currentVersion,
    createdByMessageId, fileName = fileName, mimeType = mimeType, sizeBytes = versions.last().size, sourcePath = sourcePath)

class FileSessionArtifacts(
    private val root: File,
    private val canvases: CanvasStore,
    private val resolver: WorkspaceMediaResolver,
    private val limit: Long = 25L * 1024 * 1024,
    private val quota: Long = 256L * 1024 * 1024,
) : SessionArtifactRepository {
    private val mutex = Mutex()
    private val states = mutableMapOf<String, MutableStateFlow<List<StoredArtifact>>>()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    override fun observe(sessionId: String): Flow<List<ArtifactSummary>> = flow {
        val state = try { locked { load(sessionId) } } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val available = canvases.list(sessionId).map { it.toArtifactSummary() }
            emit(available.sortedWith(compareByDescending<ArtifactSummary> { it.updatedAt }.thenBy { it.id }))
            throw e
        }
        emitAll(combine(canvases.observe(sessionId), state) { canvas, files ->
            (canvas.map { it.toArtifactSummary() } + files.map { it.summary() }).sortedWith(compareByDescending<ArtifactSummary> { it.updatedAt }.thenBy { it.id })
        }.catch { e ->
            emit(state.value.map { it.summary() }.sortedWith(compareByDescending<ArtifactSummary> { it.updatedAt }.thenBy { it.id }))
            throw e
        })
    }

    override suspend fun publishFile(sessionId: String, path: String, title: String?, kind: ArtifactKind?, origin: ArtifactOrigin, messageId: String?): ArtifactSummary = locked {
        active(sessionId)
        if (path.isBlank() || title != null && (title.isBlank() || title.length > 120) || kind == ArtifactKind.PROTOTYPE || origin == ArtifactOrigin.CANVAS) fail("INVALID_ARGUMENTS", "路径、标题、类别或来源无效。")
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId)
        val available = resolve(sessionId, path)
        if (available.ref.sizeBytes > limit) fail("FILE_TOO_LARGE", "文件超过 25 MiB。")
        if (available.source is MediaSource.LocalFile) {
            if (Files.isSymbolicLink(available.source.file.toPath())) fail("INVALID_PATH", "不允许发布符号链接。")
            if (available.source.file.isDirectory) fail("IS_DIRECTORY", "不能发布目录。")
        }
        val bytes = try { resolver.open(available).use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(65536)
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                if (out.size().toLong() + count > limit) fail("FILE_TOO_LARGE", "文件超过 25 MiB。")
                out.write(buffer, 0, count)
            }
            out.toByteArray()
        } } catch (e: ArtifactException) { throw e } catch (e: CancellationException) { throw e } catch (e: Exception) { fail("UNREADABLE", "无法读取工作区文件。") }
        val source = available.ref.path
        val name = source.substringAfterLast('/')
        if (name.isBlank()) fail("INVALID_PATH", "文件路径无效。")
        val state = load(sessionId)
        val existing = state.value.find { it.sourcePath == source }
        val hash = sha(bytes)
        if (existing?.versions?.last()?.hash == hash) {
            unignore(sessionId, source)
            return@locked existing.summary()
        }
        if (existing?.currentVersion == Int.MAX_VALUE) fail("INVALID_ARGUMENTS", "产物版本已达到上限。")
        val next = (existing?.currentVersion ?: 0) + 1
        val retained = (existing?.versions.orEmpty() + StoredVersion(next, hash, bytes.size.toLong())).takeLast(5)
        val total = snapshotBytes(sessionId) + bytes.size
        if (total > quota) fail("SESSION_QUOTA_EXCEEDED", "会话快照超过 256 MiB。")
        val now = System.currentTimeMillis()
        val record = StoredArtifact(existing?.id ?: UUID.randomUUID().toString(), title?.trim() ?: existing?.title ?: name,
            kind ?: existing?.kind ?: artifactKindFor(name, available.ref.mimeType), existing?.origin ?: origin,
            existing?.createdAt ?: now, maxOf(existing?.updatedAt ?: now, now), next,
            if (existing == null) (if (origin == ArtifactOrigin.CLAIMED) null else messageId) else existing.createdByMessageId, source, name, available.ref.mimeType, retained)
        val dir = child(sessionDir(sessionId), record.id)
        atomic(child(dir, "$next.bin"), bytes)
        try { atomic(child(dir, "metadata.json"), json.encodeToString(record).toByteArray()) }
        catch (e: Exception) { child(dir, "$next.bin").delete(); throw e }
        state.value = state.value.filterNot { it.id == record.id } + record
        dir.listFiles().orEmpty().filter { it.extension == "bin" && it.nameWithoutExtension.toIntOrNull() !in retained.map { v -> v.version } }.forEach { it.delete() }
        unignore(sessionId, source)
        record.summary()
    }

    override suspend fun readFile(sessionId: String, id: String, version: Int?): ArtifactFileSnapshot? = locked {
        if (!idPattern.matches(id) || version != null && version < 1) fail("INVALID_ARGUMENTS", "产物 ID 或版本无效。")
        val record = load(sessionId).value.find { it.id == id } ?: return@locked null
        val selected = version ?: record.currentVersion
        if (record.versions.none { it.version == selected }) return@locked null
        val file = child(child(sessionDir(sessionId), id), "$selected.bin")
        if (!file.isFile) return@locked null
        ArtifactFileSnapshot(record.summary(), selected, file)
    }

    override suspend fun candidates(sessionId: String, changes: List<WorkspaceChangedFile>): List<ArtifactCandidate> = locked {
        val published = load(sessionId).value.map { it.sourcePath }.toSet()
        val ignored = ignored(sessionId)
        val candidates = mutableMapOf<String, ArtifactCandidate>()
        val seen = mutableSetOf<String>()
        for (change in changes.asReversed()) {
            if (!seen.add(change.path) || isSkillPath(change.path)) continue
            val result = resolver.resolve(sessionId, change.path) as? MediaResolution.Available ?: continue
            val path = result.ref.path
            if (path != change.path && (!seen.add(path) || isSkillPath(path))) continue
            val parts = path.split('/').filter { it.isNotEmpty() }
            val name = parts.lastOrNull() ?: continue
            if (path in published || path in ignored || result.ref.sizeBytes > limit || parts.any { it.startsWith('.') || it == "node_modules" || it == "__pycache__" || it == "canvas" } || name.substringAfterLast('.', "").lowercase() in setOf("log", "tmp", "pyc", "o", "class", "lock")) continue
            if (result.source is MediaSource.LocalFile && (Files.isSymbolicLink(result.source.file.toPath()) || result.source.file.isDirectory)) continue
            candidates[path] = ArtifactCandidate(path, name, artifactKindFor(name, result.ref.mimeType), result.ref.sizeBytes, change.messageId, change.changedAt)
        }
        candidates.values.sortedByDescending { it.changedAt }.take(50)
    }
    /** 用户技能目录（内置模式 `/skills`，Linux 模式 `/mnt/skills`；`skills` 是保留挂载别名）由审批卡管理，不是工作区产物。 */
    private fun isSkillPath(path: String): Boolean =
        listOf("/skills", LinuxSessionPaths.SKILLS_ROOT).any { root -> path == root || path.startsWith("$root/") }

    override suspend fun ignoreCandidate(sessionId: String, path: String) = locked {
        active(sessionId)
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId)
        val resolved = resolve(sessionId, path)
        atomic(child(sessionDir(sessionId), "ignored.json"), json.encodeToString(ignored(sessionId) + resolved.ref.path).toByteArray())
    }
    private fun ignored(sessionId: String): Set<String> {
        val file = child(sessionDir(sessionId), "ignored.json")
        if (!file.isFile) return emptySet()
        return try { json.decodeFromString<Set<String>>(file.readText()) } catch (_: Exception) { emptySet() }
    }
    private fun unignore(sessionId: String, path: String) {
        val current = ignored(sessionId)
        if (path in current) atomic(child(sessionDir(sessionId), "ignored.json"), json.encodeToString(current - path).toByteArray())
    }
    private suspend fun resolve(sessionId: String, path: String): MediaResolution.Available = when (val result = resolver.resolve(sessionId, path)) {
        is MediaResolution.Available -> result
        is MediaResolution.Unavailable -> fail(result.reason.name, "工作区文件不可用：${result.reason.name}。")
    }
    suspend fun deleteSession(sessionId: String) = locked {
        atomic(child(child(root, ".deleted"), "${sessionDir(sessionId).name}.tombstone"), byteArrayOf(1))
        states.getOrPut(sessionId) { MutableStateFlow(emptyList()) }.value = emptyList()
        val dir = sessionDir(sessionId)
        if (dir.exists() && !dir.deleteRecursively()) fail("STORAGE_ERROR", "快照清理失败。")
    }
    suspend fun copySession(sourceSessionId: String, targetSessionId: String, messageIdMap: Map<String, String>) = locked {
        active(targetSessionId)
        val target = sessionDir(targetSessionId)
        if (sourceSessionId == targetSessionId || target.exists()) fail("SESSION_EXISTS", "目标会话已有快照。")
        val source = load(sourceSessionId).value.filter { it.createdByMessageId == null || it.createdByMessageId in messageIdMap }
        val stage = child(root, ".${target.name}.tmp-${UUID.randomUUID()}")
        try {
            ensureDir(stage)
            source.forEach { item ->
                val mapped = item.copy(createdByMessageId = item.createdByMessageId?.let { messageIdMap.getValue(it) })
                val sourceDir = child(sessionDir(sourceSessionId), item.id)
                val dest = child(stage, item.id)
                item.versions.forEach { v -> atomic(child(dest, "${v.version}.bin"), child(sourceDir, "${v.version}.bin").readBytes()) }
                atomic(child(dest, "metadata.json"), json.encodeToString(mapped).toByteArray())
            }
            Files.move(stage.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            states.getOrPut(targetSessionId) { MutableStateFlow(emptyList()) }.value = source.map { it.copy(createdByMessageId = it.createdByMessageId?.let(messageIdMap::getValue)) }
        } finally { if (stage.exists()) stage.deleteRecursively() }
    }
    private fun load(sessionId: String): MutableStateFlow<List<StoredArtifact>> = states.getOrPut(sessionId) {
        if (tombstone(sessionId).exists()) return@getOrPut MutableStateFlow(emptyList())
        val sessionDirectory = sessionDir(sessionId)
        val entries = if (!sessionDirectory.exists()) emptyArray() else sessionDirectory.listFiles() ?: fail("STORAGE_ERROR", "无法读取会话快照目录。")
        val records = entries.mapNotNull { dir ->
            try {
                if (!idPattern.matches(dir.name) || !dir.isDirectory || Files.isSymbolicLink(dir.toPath())) return@mapNotNull null
                val meta = child(dir, "metadata.json")
                if (!meta.isFile || meta.length() > 65536) return@mapNotNull null
                val item = json.decodeFromString<StoredArtifact>(meta.readText())
                if (item.id != dir.name || item.title.isBlank() || item.title.length > 120 || item.sourcePath.isBlank() || item.versions.isEmpty() || item.versions.size > 5 || item.versions.map { it.version } != item.versions.map { it.version }.distinct().sorted() || item.currentVersion != item.versions.last().version || item.versions.any { it.version < 1 || it.size !in 0..limit || !it.hash.matches(Regex("[0-9a-f]{64}")) || !child(dir, "${it.version}.bin").isFile || child(dir, "${it.version}.bin").length() != it.size }) return@mapNotNull null
                item
            } catch (_: Exception) { null }
        }
        MutableStateFlow(records)
    }
    private fun snapshotBytes(sessionId: String): Long {
        val directory = sessionDir(sessionId)
        if (!directory.exists()) return 0
        val entries = directory.listFiles() ?: fail("STORAGE_ERROR", "无法读取会话快照目录。")
        return entries.filter { idPattern.matches(it.name) }.sumOf { entry ->
            if (Files.isSymbolicLink(entry.toPath()) || !entry.isDirectory) fail("STORAGE_ERROR", "快照目录不安全。")
            (entry.listFiles() ?: fail("STORAGE_ERROR", "无法读取快照版本目录。")).filter { it.extension == "bin" }.sumOf { file ->
                if (Files.isSymbolicLink(file.toPath()) || !file.isFile) fail("STORAGE_ERROR", "快照版本文件不安全。")
                file.length()
            }
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun sessionDir(id: String): File { if (id.isBlank()) fail("INVALID_ARGUMENTS", "会话 ID 不能为空。") ; return child(root, sha(id.toByteArray())) }
    private fun tombstone(id: String) = child(child(root, ".deleted"), "${sessionDir(id).name}.tombstone")
    private fun active(id: String) { if (tombstone(id).exists()) fail("SESSION_DELETED", "会话已删除。") }
    private fun child(parent: File, name: String) = File(parent, name).also { if (Files.isSymbolicLink(it.toPath()) || it.canonicalFile.parentFile != parent.canonicalFile) fail("STORAGE_ERROR", "快照路径不安全。") }
    private fun ensureDir(dir: File) { if (!dir.isDirectory && !dir.mkdirs()) fail("STORAGE_ERROR", "无法创建快照目录。") }
    private fun atomic(file: File, bytes: ByteArray) {
        ensureDir(file.parentFile!!)
        val temp = child(file.parentFile!!, ".${file.name}.tmp-${UUID.randomUUID()}")
        try { FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }; Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
        finally { temp.delete() }
    }
    private fun fail(code: String, message: String): Nothing = throw ArtifactException(code, message)
    private suspend fun <T> locked(block: suspend () -> T): T = withContext(NonCancellable + Dispatchers.IO) { mutex.withLock { try { block() } catch (e: IOException) { fail("STORAGE_ERROR", "快照文件读写失败。") } } }
    private companion object { val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}") }
}
