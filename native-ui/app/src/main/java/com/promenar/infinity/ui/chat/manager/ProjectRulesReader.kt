package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.linux.LinuxFileIo
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.utils.InfinityLogger
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ProjectRulesFile(
    val fileName: String,
    val content: String,
    val originalBytes: Long,
    val includedBytes: Int,
) {
    val truncated: Boolean get() = includedBytes.toLong() < originalBytes
}

/** 仅读取已解析工作区的根级规则；Android 复用按段固定父目录 fd 的只读访问。 */
class ProjectRulesReader(
    private val diagnostic: (String, String) -> Unit = { name, reason ->
        InfinityLogger.diagnostic("context.project_rules_read_failed", mapOf("file" to name, "reason" to reason))
    },
) {
    private val access = LinuxFileIo(System.getProperty("java.vendor") == "The Android Project")
    private data class Revision(val modified: java.nio.file.attribute.FileTime, val size: Long, val key: Any?)
    private data class Cached(val revision: Revision, val bytes: ByteArray, val rendered: ProjectRulesFile)
    private val cache = object : LinkedHashMap<Path, Cached>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Path, Cached>?): Boolean = size > 32
    }

    suspend fun read(workspace: SessionWorkspace?): List<ProjectRulesFile> {
        // 私有空间与无工作区不读文件，也不切换线程。
        val root = (workspace as? SessionWorkspace.Directory)?.root?.toPath() ?: return emptyList()
        return withContext(Dispatchers.IO) { readRoot(root) }
    }

    private fun readRoot(root: Path): List<ProjectRulesFile> =
        synchronized(cache) {
            var remaining = MAX_BYTES
            listOf("AGENTS.md", "CLAUDE.md").mapNotNull { name ->
                val path = root.resolve(name)
                try {
                    checkRoot(root)
                    val attributes = try { access.attributes(path) } catch (_: java.nio.file.NoSuchFileException) {
                        cache.remove(path)
                        return@mapNotNull null
                    }
                    check(attributes.isRegularFile && !attributes.isSymbolicLink)
                    val revision = Revision(attributes.lastModifiedTime(), attributes.size(), attributes.fileKey())
                    val cached = cache[path]?.takeIf { it.revision == revision } ?: access.openRead(path).use { handle ->
                        val before = handle.revision()
                        val buffer = ByteBuffer.allocate(minOf(revision.size, MAX_BYTES.toLong()).toInt())
                        while (buffer.hasRemaining()) if (handle.channel.read(buffer) < 0) break
                        check(before == handle.revision())
                        checkRoot(root)
                        val after = access.attributes(path)
                        check(revision == Revision(after.lastModifiedTime(), after.size(), after.fileKey()))
                        val bytes = buffer.array().copyOf(buffer.position())
                        Cached(revision, bytes, render(name, revision.size, bytes, MAX_BYTES)).also { cache[path] = it }
                    }
                    val rendered = if (remaining >= cached.bytes.size) cached.rendered else render(name, revision.size, cached.bytes, remaining)
                    remaining -= rendered.includedBytes
                    rendered
                } catch (failure: Exception) {
                    cache.remove(path)
                    // 文件名固定，错误只记录类型；不输出规则正文、宿主路径或异常消息。
                    runCatching { diagnostic(name, failure.javaClass.simpleName) }
                    null
                }
            }
        }

    private fun checkRoot(root: Path) {
        check(root.isAbsolute && root == root.normalize())
        var current = root.root
        for (part in root) {
            current = current.resolve(part)
            val attributes = access.attributes(current)
            check(attributes.isDirectory && !attributes.isSymbolicLink)
        }
    }

    private fun render(name: String, size: Long, bytes: ByteArray, limit: Int): ProjectRulesFile {
        val input = ByteBuffer.wrap(bytes, 0, minOf(limit, bytes.size))
        val output = CharBuffer.allocate(input.remaining())
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        // 截断尾部的半个 UTF-8 字符留在输入缓冲区，下一文件可使用剩余字节预算。
        decoder.decode(input, output, size <= input.limit())
        val included = input.position()
        output.flip()
        return ProjectRulesFile(name, output.toString(), size, included)
    }

    companion object { const val MAX_BYTES = 32 * 1024 }
}
