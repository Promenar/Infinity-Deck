package com.promenar.infinity.data.jobs

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import org.junit.Test
import kotlinx.coroutines.*

class JobWaitConditionsTest {
    @Test fun `Termux相同尾部通过字节位置识别后续输出并保护UTF8边界`() {
        assertThat(newJobOutput("tick\n", 10, "tick\n", 15)).isEqualTo("tick\n")
        assertThat(newJobOutput("你好", 6, "你好你好", 12)).isEqualTo("你好")
        assertThat(newJobOutput("same", 10, "same", 10)).isEmpty()
    }

    @Test fun `旧日志中用户自己的时间戳正文保持原样`() {
        val root = Files.createTempDirectory("legacy-log-time").toFile()
        try {
            val log = root.resolve("output.log").apply { writeText("[+00:01.2] user-output\n") }
            assertThat(readJobLogTail(log).text).isEqualTo("[+00:01.2] user-output\n")
        } finally { root.deleteRecursively() }
    }

    @Test fun `目录创建事件重新挂接父目录且文件出现结束等待`() = runBlocking {
        val root = Files.createTempDirectory("wait-watch").toFile()
        val registrations = kotlinx.coroutines.channels.Channel<Pair<java.io.File, () -> Unit>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        var released = 0
        val watcher = JobDirectoryWatcher { directory, changed ->
            registrations.trySend(directory to changed)
            ({ released++ })
        }
        try {
            val waiting = async { awaitJobFile(root, "nested/ready", watcher) }
            val initial = withTimeout(5_000) { registrations.receive() }
            assertThat(initial.first).isEqualTo(root.canonicalFile)
            root.resolve("nested").mkdir()
            initial.second()
            val nested = withTimeout(5_000) { registrations.receive() }
            assertThat(nested.first).isEqualTo(root.resolve("nested").canonicalFile)
            root.resolve("nested/ready").createNewFile()
            nested.second()
            withTimeout(5_000) { waiting.await() }
            assertThat(released).isEqualTo(2)
        } finally { root.deleteRecursively(); registrations.close() }
    }
    @Test fun `注册监听期间文件出现也会返回且取消撤销监听`() = runBlocking {
        val root = Files.createTempDirectory("wait-watch-race").toFile()
        val registered = CompletableDeferred<Unit>()
        val stopped = CompletableDeferred<Unit>()
        try {
            awaitJobFile(root, "ready", JobDirectoryWatcher { _, _ ->
                root.resolve("ready").createNewFile()
                ({ })
            })
            val waiting = launch { awaitJobFile(root, "another", JobDirectoryWatcher { _, _ ->
                registered.complete(Unit)
                ({ stopped.complete(Unit); Unit })
            }) }
            withTimeout(5_000) { registered.await() }
            waiting.cancelAndJoin()
            assertThat(stopped.isCompleted).isTrue()
        } finally { root.deleteRecursively() }
    }

    @Test fun `新输出跨分块匹配并返回所在行`() {
        val condition = JobOutputCondition(Regex("ready: [0-9]+"))
        condition.accept("boot\nservice rea")
        assertThat(condition.matched.isCompleted).isFalse()
        condition.accept("dy: 42\n")
        assertThat(condition.matched.isCompleted).isTrue()
    }
    @Test fun `工作区虚拟路径与相对路径相同且拒绝穿越和符号链接`() {
        val root = Files.createTempDirectory("wait-files").toFile()
        val outside = Files.createTempDirectory("wait-outside").toFile()
        try {
            assertThat(jobWaitFile(root, "/workspace/a/b")).isEqualTo(jobWaitFile(root, "a/b"))
            assertThat(runCatching { jobWaitFile(root, "../outside") }.isFailure).isTrue()
            assertThat(runCatching { jobWaitFile(root, "/etc/passwd") }.isFailure).isTrue()
            Files.createSymbolicLink(root.resolve("escape").toPath(), outside.toPath())
            assertThat(runCatching { jobWaitFile(root, "escape/file") }.isFailure).isTrue()
        } finally { root.deleteRecursively(); outside.deleteRecursively() }
    }
    @Test fun `时间前缀跨写入保持行边界且模型尾部保留原文`() {
        val root = Files.createTempDirectory("timed-log").toFile()
        val file = root.resolve("output.log")
        var now = 1_000L
        try {
            RotatingJobLog(file, startedAtMillis = 1_000L, now = { now }).use { log ->
                log.write("hello".toByteArray())
                now = 13_300L
                log.write(" world\nnext\n".toByteArray())
            }
            assertThat(file.readText()).isEqualTo("[+00:00.0] hello world\n[+00:12.3] next\n")
            assertThat(readJobLogTail(file).text).isEqualTo("hello world\nnext\n")
            assertThat(readJobLogTail(file, stripTimestamps = false).text).contains("[+00:12.3]")
        } finally { root.deleteRecursively() }
    }
}
