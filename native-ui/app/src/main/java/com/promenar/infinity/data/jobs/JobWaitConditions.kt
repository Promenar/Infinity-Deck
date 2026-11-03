package com.promenar.infinity.data.jobs

import android.os.FileObserver
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

internal enum class JobWaitReason { TERMINAL, TIMEOUT, OUTPUT, FILE, STEERING, RELEASED }
internal data class JobWaitOutcome(val reason: JobWaitReason, val matchedLine: String? = null)

/** 匹配器只接收登记之后的新输出，跨分块保留当前行。 */
internal class JobOutputCondition(private val pattern: Regex) {
    val matched = CompletableDeferred<String>()
    private val line = StringBuilder()
    @Synchronized fun accept(text: String) {
        for (char in text) {
            if (char == '\n' || char == '\r') {
                checkLine()
                line.clear()
            } else if (char == '\b') {
                if (line.isNotEmpty()) line.deleteCharAt(line.lastIndex)
            } else {
                line.append(char)
                // 与实时输出使用相同的有界尾部，避免无换行输出无限增长。
                if (line.length > 32_000) line.delete(0, line.length - 32_000)
            }
        }
        checkLine()
    }
    private fun checkLine() { if (!matched.isCompleted && pattern.containsMatchIn(line)) matched.complete(line.toString()) }
}

internal fun jobWaitFile(root: File, path: String): File {
    require(path.isNotBlank() && '\u0000' !in path) { "until_file 不能为空" }
    val relative = when {
        path == "/workspace" -> "."
        path.startsWith("/workspace/") -> path.removePrefix("/workspace/")
        File(path).isAbsolute -> throw IllegalArgumentException("until_file 必须为工作区内的相对路径或 /workspace 路径")
        else -> path
    }
    val canonicalRoot = root.canonicalFile
    val candidate = File(canonicalRoot, relative).canonicalFile
    require(candidate.toPath().startsWith(canonicalRoot.toPath())) { "until_file 越出工作区" }
    return candidate
}

internal fun interface JobDirectoryWatcher {
    fun watch(directory: File, changed: () -> Unit): () -> Unit
}

private val androidJobDirectoryWatcher = JobDirectoryWatcher { directory, changed ->
    val observer = object : FileObserver(directory, FileObserver.CREATE or FileObserver.MOVED_TO or FileObserver.CLOSE_WRITE or
        FileObserver.ATTRIB or FileObserver.DELETE_SELF or FileObserver.MOVE_SELF) {
        override fun onEvent(event: Int, path: String?) { changed() }
    }
    observer.startWatching()
    ({ observer.stopWatching() })
}

/** 监听最近存在的父目录；创建中间目录后重新向目标靠近，注册后再检查以封闭竞态。 */
internal suspend fun awaitJobFile(root: File, path: String, watcher: JobDirectoryWatcher = androidJobDirectoryWatcher) = withContext(Dispatchers.IO) {
    val canonicalRoot = root.canonicalFile
    callbackFlow<Unit> {
        val lock = Any()
        var stop: (() -> Unit)? = null
        var closed = false
        fun inspect(): Unit = synchronized(lock) {
            if (!closed) try {
                require(canonicalRoot.canonicalFile == canonicalRoot) { "until_file 工作区目录已发生变化" }
                val target = jobWaitFile(canonicalRoot, path)
                if (target.exists()) { trySend(Unit); Unit }
                else {
                    var parent = target.parentFile
                    while (parent != null && !parent.isDirectory) parent = parent.parentFile
                    stop?.invoke()
                    stop = parent?.let { directory -> watcher.watch(directory) { inspect() } }
                    if (jobWaitFile(canonicalRoot, path).exists()) trySend(Unit)
                    Unit
                }
            } catch (failure: Exception) { close(failure); Unit }
        }
        inspect()
        awaitClose { synchronized(lock) { closed = true; stop?.invoke() } }
    }.first()
}

/** 日志快照按字节位置推进；相同的有界尾部仍可表示实际产生了新输出。 */
internal fun newJobOutput(previous: String, previousBytes: Long?, current: String, currentBytes: Long?): String {
    if (currentBytes == null || previousBytes == null) return com.promenar.infinity.data.exec.newOutputSuffix(previous, current)
    if (currentBytes < previousBytes) return current
    val count = (currentBytes - previousBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    if (count == 0) return if (current == previous) "" else com.promenar.infinity.data.exec.newOutputSuffix(previous, current)
    val bytes = current.toByteArray(Charsets.UTF_8)
    var start = (bytes.size - count).coerceAtLeast(0)
    while (start < bytes.size && (bytes[start].toInt() and 0xc0) == 0x80) start++
    return String(bytes, start, bytes.size - start, Charsets.UTF_8)
}
