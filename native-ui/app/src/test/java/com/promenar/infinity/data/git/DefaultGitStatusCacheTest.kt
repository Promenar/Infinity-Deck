package com.promenar.infinity.data.git

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultGitStatusCacheTest {
    private val result = GitInspectResult.Failure(GitInspectError.NotRepository)

    @Test fun `规范路径相同的调用合并，取消等待者不取消读取`() = runTest {
        val pending = CompletableDeferred<GitInspectResult<GitStatus>>()
        var reads = 0
        val cache = DefaultGitStatusCache(
            locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(File(if (it == "one") "repo" else "repo/../repo"), "project")) },
            read = { reads++; pending.await() }, scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler), fingerprint = { "same" },
        )
        val one = async { cache.status("one") }
        val two = async { cache.status("two") }
        runCurrent()
        assertThat(reads).isEqualTo(1)
        one.cancelAndJoin()
        pending.complete(result)
        runCurrent()
        assertThat(two.await()).isEqualTo(result)
        assertThat(reads).isEqualTo(1)
    }

    @Test fun `TTL 到期或外部索引变动后重新读取`() = runTest {
        var now = 1_000L
        var identity = "one"
        var reads = 0
        val cache = DefaultGitStatusCache(
            locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(File("repo"), "project")) },
            read = { reads++; result }, scope = backgroundScope, clockMillis = { now },
            io = StandardTestDispatcher(testScheduler), fingerprint = { identity },
        )
        assertThat(cache.status("s", 100)).isEqualTo(result)
        cache.status("s", 100)
        assertThat(reads).isEqualTo(1)
        now += 101
        cache.status("s", 100)
        assertThat(reads).isEqualTo(2)
        identity = "two"
        cache.status("s", 100)
        assertThat(reads).isEqualTo(3)
    }

    @Test fun `失效时订阅者保留旧值直到后台读取完成`() = runTest {
        val pending = CompletableDeferred<GitInspectResult<GitStatus>>()
        val replacement = GitInspectResult.Failure(GitInspectError.PythonUnavailable)
        var reads = 0
        val cache = DefaultGitStatusCache(
            locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(File("repo"), "project")) },
            read = { if (++reads == 1) result else pending.await() }, scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler), fingerprint = { "same" },
        )
        cache.status("s")
        val snapshots = mutableListOf<GitStatusSnapshot>()
        val subscription = backgroundScope.launch { cache.observe("s").collect { snapshots += it } }
        runCurrent()
        cache.invalidate("s")
        runCurrent()
        assertThat(snapshots.last().result).isEqualTo(result)
        assertThat(snapshots.last().refreshing).isTrue()
        pending.complete(replacement)
        runCurrent()
        assertThat(snapshots.last().result).isEqualTo(replacement)
        assertThat(snapshots.last().refreshing).isFalse()
        subscription.cancelAndJoin()
    }

    @Test fun `invalidateAll 刷新所有已缓存仓库`() = runTest {
        var reads = 0
        val cache = DefaultGitStatusCache(
            locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(File(it), "project")) },
            read = { reads++; result }, scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler), fingerprint = { "same" },
        )
        cache.status("one")
        cache.status("two")
        val one = backgroundScope.launch { cache.observe("one").collect {} }
        val two = backgroundScope.launch { cache.observe("two").collect {} }
        runCurrent()
        cache.invalidateAll()
        runCurrent()
        assertThat(reads).isEqualTo(4)
        cache.status("one")
        cache.status("two")
        assertThat(reads).isEqualTo(4)
        one.cancelAndJoin()
        two.cancelAndJoin()
    }

    @Test fun `读取期间失效不会交付旧读取结果`() = runTest {
        val first = CompletableDeferred<GitInspectResult<GitStatus>>()
        val second = CompletableDeferred<GitInspectResult<GitStatus>>()
        val replacement = GitInspectResult.Failure(GitInspectError.PythonUnavailable)
        var reads = 0
        val cache = DefaultGitStatusCache(
            locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(File("repo"), "project")) },
            read = { if (++reads == 1) first.await() else second.await() }, scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler), fingerprint = { "same" },
        )
        val waiter = async { cache.status("s") }
        runCurrent()
        cache.invalidate("s")
        first.complete(result)
        runCurrent()
        assertThat(reads).isEqualTo(2)
        second.complete(replacement)
        assertThat(waiter.await()).isEqualTo(replacement)
    }

    @Test fun `最后一个订阅者离开时取消仅由观察触发的读取`() = runTest {
        var cancelled = false
        val cache = DefaultGitStatusCache(
            locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(File("repo"), "project")) },
            read = { try { awaitCancellation() } finally { cancelled = true } }, scope = backgroundScope,
            io = StandardTestDispatcher(testScheduler), fingerprint = { "same" },
        )
        val subscription = backgroundScope.launch { cache.observe("s").collect {} }
        runCurrent()
        subscription.cancelAndJoin()
        runCurrent()
        assertThat(cancelled).isTrue()
    }

    @Test fun `真实索引和分支引用变化使缓存失效`() = runTest {
        val root = Files.createTempDirectory("git-cache-test").toFile()
        try {
            val git = File(root, ".git").apply { mkdirs() }
            val ref = File(git, "refs/heads/main").apply { parentFile.mkdirs(); writeText("first") }
            File(git, "HEAD").writeText("ref: refs/heads/main\n")
            val index = File(git, "index").apply { writeText("index") }
            var reads = 0
            val cache = DefaultGitStatusCache(
                locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(root, "project")) },
                read = { reads++; result }, scope = backgroundScope,
                io = StandardTestDispatcher(testScheduler),
            )
            cache.status("s")
            cache.status("s")
            assertThat(reads).isEqualTo(1)
            index.writeText("different-index")
            cache.status("s")
            ref.writeText("another-reference")
            cache.status("s")
            assertThat(reads).isEqualTo(3)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun `取消观察后的进程清理完成前不启动第二次读取`() = runTest {
        val cleanup = CompletableDeferred<Unit>()
        var reads = 0
        val cache = DefaultGitStatusCache(
            locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(File("repo"), "project")) },
            read = {
                if (++reads == 1) {
                    try { awaitCancellation() } finally { withContext(NonCancellable) { cleanup.await() } }
                } else result
            }, scope = backgroundScope, io = StandardTestDispatcher(testScheduler), fingerprint = { "same" },
        )
        val subscription = backgroundScope.launch { cache.observe("s").collect {} }
        runCurrent()
        subscription.cancelAndJoin()
        val waiter = async { cache.status("s") }
        runCurrent()
        assertThat(reads).isEqualTo(1)
        cleanup.complete(Unit)
        assertThat(waiter.await()).isEqualTo(result)
        assertThat(reads).isEqualTo(2)
    }
}
