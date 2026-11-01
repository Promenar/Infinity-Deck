package com.promenar.infinity.data.repository

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.security.MessageDigest
import java.util.UUID

/** 调用者必须持有真实工作区根锁；finished 仅在命令及其子进程均已停止后使用。 */
internal class BackgroundWorkspace(
    private val fileOps: WorkspaceFileOps,
    private val directoryOpener: (Path) -> SecureDirectoryStream<Path> = { root ->
        val stream = Files.newDirectoryStream(root)
        stream as? SecureDirectoryStream<Path> ?: run {
            stream.close()
            throw SecurityException("当前文件系统不支持安全后台工作区快照")
        }
    },
) {
    @Serializable private data class Node(val directory: Boolean, val hash: String = "", val size: Long = 0)
    @Serializable private data class Publication(val target: String, val temporary: String, val backup: String)
    @Serializable private data class State(
        val rootUuid: String,
        val sessionId: String?,
        val jobId: String,
        val stagingKey: String,
        val baseline: Map<String, Node>,
        val publishedLogs: Map<String, Node> = emptyMap(),
        val publication: Publication? = null,
        val warnings: List<String> = emptyList(),
        val completed: Boolean = false,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    fun prepare(root: File, rootUuid: String, sessionId: String?, jobId: String, staging: File) {
        validateJobId(jobId)
        require(!staging.exists()) { "后台任务执行目录已存在" }
        require(!staging.toPath().toAbsolutePath().normalize().startsWith(root.toPath().toAbsolutePath().normalize())) {
            "后台任务执行目录必须位于工作区之外"
        }
        Files.createDirectories(staging.parentFile.toPath())
        Files.createDirectory(staging.toPath())
        val baseline = snapshot(root.toPath(), jobId, staging.toPath())
        save(staging, State(rootUuid, sessionId, jobId, directoryKey(staging.toPath()), baseline))
        val rawLog = File(staging.parentFile, "output.log").toPath()
        if (!Files.exists(rawLog, LinkOption.NOFOLLOW_LINKS)) Files.createFile(rawLog)
    }

    fun synchronize(
        root: File,
        rootUuid: String,
        sessionId: String?,
        jobId: String,
        staging: File,
        finished: Boolean,
        interrupted: Boolean = false,
    ): String? {
        validateJobId(jobId)
        var state = load(staging)
        require(state.rootUuid == rootUuid && state.sessionId == sessionId && state.jobId == jobId) {
            "后台任务工作区身份不匹配"
        }
        val rootPath = root.toPath()
        state = recoverPublication(rootPath, staging, state)
        if (state.completed) return state.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
        val warnings = state.warnings.toMutableList()
        if (finished && !interrupted) {
            if (directoryKey(staging.toPath()) != state.stagingKey) throw SecurityException("后台任务执行目录已被替换")
            val result = snapshot(staging.toPath(), jobId)
            val current = snapshot(rootPath, jobId)
            val blocked = mutableSetOf<String>()
            val paths = (state.baseline.keys + result.keys).sortedWith(compareBy({ it.count { c -> c == '/' } }, { it }))
            for (path in paths) {
                if (blocked.any { path == it || path.startsWith("$it/") }) continue
                val before = state.baseline[path]
                val after = result[path]
                if (before == after) continue
                val now = current[path]
                val replacingDirectory = before?.directory == true && after?.directory != true
                val sameSubtree = subtree(current, path) == subtree(state.baseline, path)
                val ancestorsAvailable = parents(path).all { parent ->
                    val actual = if (fileOps.exists(rootPath, parts(parent))) fileOps.inspect(rootPath, parts(parent)) else null
                    actual?.kind == "directory"
                }
                val compatible = when {
                    now == after -> true
                    replacingDirectory -> sameSubtree && path != "jobs"
                    after?.directory == true && now?.directory == true -> true
                    else -> now == before && ancestorsAvailable
                }
                if (!compatible) {
                    warnings += "合入冲突：/$path（前台版本已保留）"
                    if (after != null) state = copyConflict(rootPath, staging, state, path, result)
                    else writeDeletionConflict(rootPath, jobId, path)
                    blocked += path
                    continue
                }
                if (now == after) continue
                if (replacingDirectory) {
                    if (now != null) fileOps.delete(rootPath, parts(path))
                    blocked += path
                } else if (before != null && after == null && now != null) {
                    fileOps.deleteNonRecursive(rootPath, parts(path))
                }
                when {
                    after == null -> Unit
                    after.directory -> {
                        if (now != null && !now.directory) fileOps.deleteNonRecursive(rootPath, parts(path))
                        ensureDirectories(rootPath, parts(path))
                    }
                    else -> state = publishFile(rootPath, staging, state, File(staging, path), path)
                }
            }
        }
        // 原始日志与基线均由应用任务目录持久化，不发布到用户文件树。
        state = state.copy(warnings = warnings.distinct(), completed = finished || interrupted)
        save(staging, state)
        return state.warnings.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    /** 物理合入完成且索引同步已有恢复保障后清理，失败时保留恢复证据。 */
    fun complete(root: File, jobId: String) {
        if (fileOps.exists(root.toPath(), listOf(marker(jobId)))) fileOps.delete(root.toPath(), listOf(marker(jobId)))
    }

    private fun copyConflict(root: Path, staging: File, initial: State, path: String, result: Map<String, Node>): State {
        var state = initial
        val destination = conflictPath(root, state.jobId, path)
        if (result.getValue(path).directory) {
            ensureDirectories(root, parts(destination))
            subtree(result, path).entries.sortedWith(compareBy({ it.key.count { c -> c == '/' } }, { it.key })).forEach { (child, value) ->
                if (child != path) {
                    val target = destination + child.removePrefix(path)
                    if (value.directory) ensureDirectories(root, parts(target))
                    else state = publishFile(root, staging, state, File(staging, child), target)
                }
            }
        } else state = publishFile(root, staging, state, File(staging, path), destination)
        return state
    }

    private fun writeDeletionConflict(root: Path, jobId: String, path: String) {
        val target = conflictPath(root, jobId, "$path.background-delete.txt")
        ensureDirectories(root, parts(target).dropLast(1))
        fileOps.createFile(root, parts(target), "后台任务请求删除 /$path；前台内容已保留。\n".toByteArray(Charsets.UTF_8))
    }

    private fun conflictPath(root: Path, jobId: String, path: String): String {
        val requested = "background-conflicts/$jobId/$path"
        return if (!fileOps.exists(root, parts(requested))) requested else "$requested.${UUID.randomUUID()}"
    }

    private fun publishFile(root: Path, staging: File, initial: State, source: File, target: String): State {
        val sourceAttributes = Files.readAttributes(source.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!sourceAttributes.isRegularFile || sourceAttributes.isSymbolicLink) throw SecurityException("后台任务发布源不是普通文件")
        ensureDirectories(root, parts(target).dropLast(1))
        val relative = parts(target)
        val nonce = UUID.randomUUID().toString()
        val internal = ensureWorkspaceInternalDirectory(fileOps, root)
        val temporary = internal + "job-write-$nonce"
        val backup = internal + "job-previous-$nonce"
        Files.newByteChannel(source.toPath(), setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { input ->
            val limit = input.size()
            fileOps.createFileStreaming(root, temporary, limit.coerceAtLeast(1)) { output ->
                val bytes = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                var remaining = limit
                while (remaining > 0) {
                    bytes.clear()
                    bytes.limit(minOf(bytes.capacity().toLong(), remaining).toInt())
                    val count = input.read(bytes)
                    if (count < 0) break
                    output.write(bytes.array(), 0, count)
                    remaining -= count
                }
            }
        }
        val publication = Publication(target, temporary.joinToString("/"), backup.joinToString("/"))
        val active = initial.copy(publication = publication)
        save(staging, active)
        if (fileOps.exists(root, relative)) fileOps.move(root, relative, backup).commit()
        fileOps.move(root, temporary, relative).commit()
        if (fileOps.exists(root, backup)) fileOps.delete(root, backup)
        return active.copy(publication = null).also { save(staging, it) }
    }

    private fun recoverPublication(root: Path, staging: File, state: State): State {
        val publication = state.publication ?: return state
        if (fileOps.exists(root, parts(publication.backup))) {
            if (!fileOps.exists(root, parts(publication.target))) {
                fileOps.move(root, parts(publication.backup), parts(publication.target)).commit()
            } else fileOps.delete(root, parts(publication.backup))
        }
        if (fileOps.exists(root, parts(publication.temporary))) fileOps.delete(root, parts(publication.temporary))
        return state.copy(publication = null).also { save(staging, it) }
    }

    private fun snapshot(root: Path, jobId: String, copyTo: Path? = null): Map<String, Node> {
        directoryKey(root)
        val nodes = linkedMapOf<String, Node>()
        directoryOpener(root).use { walk(it, "", jobId, copyTo, nodes) }
        return nodes
    }

    private fun walk(directory: SecureDirectoryStream<Path>, prefix: String, jobId: String, copyTo: Path?, nodes: MutableMap<String, Node>) {
        for (name in directory.map { it.fileName }.sortedBy { it.toString() }) {
            val text = name.toString()
            val path = if (prefix.isEmpty()) text else "$prefix/$text"
            if (isReservedWorkspaceName(text) || prefix.isEmpty() && text in setOf("mnt", "skills") ||
                path == "jobs/$jobId.log" || path == "jobs/$jobId.log.1" || path == "jobs/$jobId-conflicts") continue
            if (!isIndexableWorkspaceName(text)) throw SecurityException("后台工作区包含不支持的名称")
            val attributes = directory.getFileAttributeView(name, BasicFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes()
            if (attributes.isSymbolicLink || attributes.isOther) throw SecurityException("后台工作区不能包含符号链接或特殊文件：/$path")
            if (attributes.isDirectory) {
                nodes[path] = Node(true)
                copyTo?.resolve(path)?.let { Files.createDirectory(it) }
                directory.newDirectoryStream(name, LinkOption.NOFOLLOW_LINKS).use { walk(it, path, jobId, copyTo, nodes) }
            } else if (attributes.isRegularFile) {
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                directory.newByteChannel(name, setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { input ->
                    val output = copyTo?.resolve(path)?.let {
                        Channels.newOutputStream(Files.newByteChannel(it, setOf<OpenOption>(StandardOpenOption.WRITE, StandardOpenOption.CREATE_NEW, LinkOption.NOFOLLOW_LINKS)))
                    }
                    try {
                        val bytes = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            bytes.clear()
                            val count = input.read(bytes)
                            if (count < 0) break
                            digest.update(bytes.array(), 0, count)
                            output?.write(bytes.array(), 0, count)
                            size += count
                        }
                    } finally { output?.close() }
                }
                copyTo?.resolve(path)?.let { target ->
                    runCatching {
                        val permissions = directory.getFileAttributeView(name, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS).readAttributes().permissions()
                        Files.setPosixFilePermissions(target, permissions)
                    }
                }
                nodes[path] = Node(false, digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }, size)
            } else throw SecurityException("后台工作区包含不支持的文件：/$path")
        }
    }

    private fun directoryKey(root: Path): String {
        val attributes = Files.readAttributes(root, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (!attributes.isDirectory || attributes.isSymbolicLink) throw SecurityException("后台工作区根目录无效")
        return PersistentFileIdentity.read(root)
    }

    private fun ensureDirectories(root: Path, directories: List<String>) {
        directories.indices.forEach { fileOps.ensureDirectory(root, directories.take(it + 1)) }
    }
    private fun node(identity: WorkspaceNodeIdentity) = Node(identity.kind == "directory", identity.sha256.orEmpty(), identity.sizeBytes)
    private fun subtree(nodes: Map<String, Node>, path: String) = nodes.filterKeys { it == path || it.startsWith("$path/") }
    private fun parts(path: String): List<String> = path.split('/').also { names ->
        require(names.all { isIndexableWorkspaceName(it) }) { "后台工作区相对路径无效" }
    }
    private fun parents(path: String) = parts(path).dropLast(1).indices.map { parts(path).take(it + 1).joinToString("/") }
    private fun marker(jobId: String) = ".infinity_command_pending-$jobId"
    private fun validateJobId(jobId: String) = require(jobId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "后台任务编号无效" }
    private fun load(staging: File): State = json.decodeFromString(File(staging.parentFile, "baseline.json").readText())
    private fun save(staging: File, state: State) {
        val target = File(staging.parentFile, "baseline.json")
        val temporary = File(staging.parentFile, "baseline.json.tmp")
        java.io.FileOutputStream(temporary).use { it.write(json.encodeToString(state).toByteArray(Charsets.UTF_8)); it.fd.sync() }
        Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
