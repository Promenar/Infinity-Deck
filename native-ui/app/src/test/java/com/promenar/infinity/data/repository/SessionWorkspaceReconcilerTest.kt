package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionWorkspaceReconcilerTest {
    @Test fun `连续请求合并且等待覆盖进行中和待跑扫描`() = runTest {
        val releases = List(2) { CompletableDeferred<Unit>() }
        var calls = 0
        val reconciler = SessionWorkspaceReconciler(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler)) { releases[calls++].await() }
        repeat(50) { reconciler.request("s") }
        runCurrent()
        assertThat(calls).isEqualTo(1)
        repeat(50) { reconciler.request("s") }
        val waiter = async { reconciler.awaitSettled("s") }
        runCurrent()
        assertThat(waiter.isCompleted).isFalse()
        releases[0].complete(Unit)
        runCurrent()
        assertThat(calls).isEqualTo(2)
        assertThat(waiter.isCompleted).isFalse()
        assertThat(reconciler.sessions.value).containsExactly("s")
        releases[1].complete(Unit)
        waiter.await()
        assertThat(reconciler.sessions.value).isEmpty()
        assertThat(calls).isEqualTo(2)
    }

    @Test fun `不同会话独立执行且失败保留请求并重试`() = runTest {
        val held = CompletableDeferred<Unit>()
        var attempts = 0
        val reconciler = SessionWorkspaceReconciler(backgroundScope, retryDelayMillis = 100, dispatcher = StandardTestDispatcher(testScheduler)) { id ->
            if (id == "held") held.await() else if (++attempts == 1) error("暂不可用")
        }
        reconciler.request("held"); reconciler.request("retry")
        runCurrent()
        assertThat(reconciler.failures.value["retry"]).contains("已安排重试")
        assertThat(reconciler.sessions.value).containsAtLeast("held", "retry")
        advanceTimeBy(100)
        runCurrent()
        reconciler.awaitSettled("retry")
        assertThat(attempts).isEqualTo(2)
        assertThat(reconciler.failures.value).isEmpty()
        assertThat(reconciler.sessions.value).containsExactly("held")
        held.complete(Unit)
        reconciler.awaitSettled("held")
    }

    @Test fun `等待者收到失败且后台保留重试`() = runTest {
        val reconciler = SessionWorkspaceReconciler(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler)) { error("不可用") }
        reconciler.request("s")
        runCurrent()
        assertThat(runCatching { reconciler.awaitSettled("s") }.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(reconciler.sessions.value).containsExactly("s")
        assertThat(reconciler.failures.value["s"]).contains("已安排重试")
    }

    @Test fun `无所属会话的共享根合并执行且不会误扫同名会话`() = runTest {
        val releases = List(2) { CompletableDeferred<Unit>() }
        var rootCalls = 0
        val sessions = mutableListOf<String>()
        val reconciler = SessionWorkspaceReconciler(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler),
            reconcileSharedRoot = { id ->
                assertThat(id).isEqualTo("shared")
                releases[rootCalls++].await()
            }) { sessions += it }
        repeat(50) { reconciler.requestSharedRoot("shared") }
        reconciler.request("shared")
        runCurrent()
        repeat(50) { reconciler.requestSharedRoot("shared") }
        assertThat(rootCalls).isEqualTo(1)
        assertThat(sessions).containsExactly("shared")
        assertThat(reconciler.sessions.value).isEmpty()
        assertThat(reconciler.sharedRoots.value).containsExactly("shared")
        releases[0].complete(Unit)
        runCurrent()
        assertThat(rootCalls).isEqualTo(2)
        releases[1].complete(Unit)
        reconciler.awaitSharedRootSettled("shared")
        assertThat(reconciler.sharedRoots.value).isEmpty()
    }

    @Test fun `父会话和分支请求汇入同一个共享根执行者`() = runTest {
        val releases = List(2) { CompletableDeferred<Unit>() }
        var calls = 0
        lateinit var reconciler: SessionWorkspaceReconciler
        reconciler = SessionWorkspaceReconciler(backgroundScope, dispatcher = StandardTestDispatcher(testScheduler),
            reconcileSharedRoot = { releases[calls++].await() }) {
            reconciler.requestSharedRoot("root")
            reconciler.awaitSharedRootSettled("root")
        }
        reconciler.request("parent")
        runCurrent()
        repeat(50) { reconciler.requestSharedRoot("root") }
        assertThat(calls).isEqualTo(1)
        assertThat(reconciler.sessions.value).containsExactly("parent")
        releases[0].complete(Unit)
        runCurrent()
        assertThat(calls).isEqualTo(2)
        releases[1].complete(Unit)
        reconciler.awaitSettled("parent")
        assertThat(reconciler.sessions.value).isEmpty()
        assertThat(reconciler.sharedRoots.value).isEmpty()
    }

    @Test fun `执行前取消不会留下永久等待`() = runTest {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val reconciler = SessionWorkspaceReconciler(scope, dispatcher = StandardTestDispatcher(testScheduler)) { error("不应执行") }
        reconciler.request("s")
        job.cancel()
        runCurrent()
        withTimeout(100) { reconciler.awaitSettled("s") }
        assertThat(reconciler.sessions.value).isEmpty()
    }

    @Test fun `早期扫描不能清掉活跃写入的持久请求`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("workspace-dirty").toFile()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        try {
            val reconciler = SessionWorkspaceReconciler(scope, directory) { entered.complete(Unit); release.await() }
            reconciler.request("s")
            entered.await()
            reconciler.markDirty("s")
            release.complete(Unit)
            reconciler.awaitSettled("s")
            assertThat(directory.listFiles()).hasLength(1)
            reconciler.mutationFinished("s")
            reconciler.request("s")
            reconciler.awaitSettled("s")
            assertThat(directory.listFiles()).isEmpty()
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); directory.deleteRecursively() }
    }

    @Test fun `未执行和中断的持久请求在重启后收敛`() = runBlocking<Unit> {
        val directory = Files.createTempDirectory("workspace-pending").toFile()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val entered = CompletableDeferred<Unit>()
        try {
            val first = SessionWorkspaceReconciler(firstScope, directory) { entered.complete(Unit); awaitCancellation() }
            first.markDirty("not-started")
            first.request("interrupted")
            first.markSharedRootDirty("retained-shared-root")
            withTimeout(2_000) { entered.await() }
            firstScope.coroutineContext[Job]?.cancelAndJoin()
            val restored = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val restoredRoots = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val second = SessionWorkspaceReconciler(secondScope, directory,
                reconcileSharedRoot = { restoredRoots += it }) { restored += it }
            second.resumePending()
            withTimeout(2_000) { second.awaitSettled("not-started"); second.awaitSettled("interrupted"); second.awaitSharedRootSettled("retained-shared-root") }
            assertThat(restored).containsExactly("not-started", "interrupted")
            assertThat(restoredRoots).containsExactly("retained-shared-root")
            assertThat(directory.listFiles()).isEmpty()
        } finally {
            firstScope.coroutineContext[Job]?.cancelAndJoin()
            secondScope.coroutineContext[Job]?.cancelAndJoin()
            directory.deleteRecursively()
        }
    }
}
