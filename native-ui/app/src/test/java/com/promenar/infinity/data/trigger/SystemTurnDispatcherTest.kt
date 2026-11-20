package com.promenar.infinity.data.trigger

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.trigger.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemTurnDispatcherTest {
    private class Runtime : SystemTurnRuntime {
        val ready = CompletableDeferred<Unit>()
        var exists = true
        var busy = false
        val busySessions = mutableSetOf<String>()
        val brokenSessions = mutableSetOf<String>()
        val sessionChecks = mutableMapOf<String, CompletableDeferred<Unit>>()
        var loads = 0
        var loadWait: CompletableDeferred<Unit>? = null
        var beforeStart: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<SystemTurnRequest>()
        var finish: CompletableDeferred<Unit>? = null
        val sessionFinishes = mutableMapOf<String, CompletableDeferred<Unit>>()
        var rejectedOnce = false
        var rejectedSource: String? = null
        var rejectionGate: CompletableDeferred<Unit>? = null
        var cancelled = false
        val completionCalls = mutableListOf<String>()
        var completionFinish: CompletableDeferred<Unit>? = null
        override suspend fun awaitReady() { ready.await() }
        override suspend fun sessionExists(sessionId: String): Boolean {
            sessionChecks[sessionId]?.await()
            check(sessionId !in brokenSessions) { "session query failed" }
            return exists
        }
        override suspend fun loadSession(sessionId: String): Boolean { loads++; loadWait?.await(); return exists }
        override suspend fun isBusy(sessionId: String) = busy || sessionId in busySessions
        override suspend fun runSubagentCompletion(request: SystemTurnRequest, ticket: SystemTurnAdmissionTicket): SystemTurnOutcome? {
            completionCalls += request.sourceId
            completionFinish?.await()
            return if (ticket.tryAccept("completion-${request.sourceId}")) SystemTurnOutcome.Completed else null
        }
        override suspend fun run(request: SystemTurnRequest, ticket: SystemTurnAdmissionTicket): SystemTurnOutcome? {
            beforeStart?.await()
            if (request.sourceId == rejectedSource) {
                rejectionGate?.await()
                rejectedSource = null
                return null
            }
            if (rejectedOnce) { rejectedOnce = false; busy = true; return null }
            if (!ticket.tryAccept("test-${request.sourceId}")) return null
            calls += request
            try { (sessionFinishes[request.sessionId] ?: finish)?.await() } catch (error: CancellationException) { cancelled = true; throw error }
            return SystemTurnOutcome.Completed
        }
    }
    private fun request(source: String = "a", body: String = "检查", session: String = "session") =
        SystemTurnRequest(session, SystemTurnKind.SCHEDULE, source, "检查", body)

    @Test fun `忙碌父会话的多个完成通知均进入步骤边界桥且不受三十分钟超时影响`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true; completionFinish = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val first = async { dispatcher.request(request("sa_first").copy(kind = SystemTurnKind.SUBAGENT_DONE)) }
        val second = async { dispatcher.request(request("sa_second").copy(kind = SystemTurnKind.SUBAGENT_DONE)) }
        runCurrent()
        assertThat(runtime.completionCalls).containsExactly("sa_first", "sa_second").inOrder()
        advanceTimeBy(30 * 60 * 1000L); runCurrent()
        assertThat(first.isCompleted).isFalse()
        assertThat(second.isCompleted).isFalse()
        runtime.completionFinish!!.complete(Unit); runCurrent()
        assertThat(first.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(second.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `忙碌会话的后台任务结束转交步骤边界投递桥`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        assertThat(dispatcher.request(request("job-a").copy(kind = SystemTurnKind.JOB_EXIT)))
            .isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.completionCalls).containsExactly("job-a")
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `同一子代理恢复后的新通知不会覆盖尚未送达的旧通知`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true; completionFinish = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val request = request("sa_same").copy(kind = SystemTurnKind.SUBAGENT_DONE, sequence = 1)
        val first = async { dispatcher.request(request) }
        val resumed = async { dispatcher.request(request.copy(sequence = 2)) }
        runCurrent()
        assertThat(first.isCompleted).isFalse()
        assertThat(resumed.isCompleted).isFalse()
        assertThat(runtime.completionCalls).containsExactly("sa_same", "sa_same")
        runtime.completionFinish!!.complete(Unit); runCurrent()
        assertThat(first.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(resumed.await()).isEqualTo(SystemTurnOutcome.Completed)
    }

    @Test fun `其它会话繁忙不阻挡空闲会话的系统轮`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busySessions += "a" }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val first = async { dispatcher.request(request("first", session = "a")) }; runCurrent()
        val second = async { dispatcher.request(request("second", session = "b")) }; runCurrent()
        assertThat(second.isCompleted).isTrue()
        assertThat(second.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(first.isCompleted).isFalse()
        assertThat(runtime.calls.map { it.sessionId }).containsExactly("b")
        first.cancel()
    }

    @Test fun `其它会话运行时空闲会话的系统轮仍可完成`() = runTest {
        val runtime = Runtime().apply {
            ready.complete(Unit)
            sessionFinishes["a"] = CompletableDeferred()
        }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val first = async { dispatcher.request(request("first", session = "a")) }; runCurrent()
        val second = async { dispatcher.request(request("second", session = "b")) }; runCurrent()
        assertThat(second.isCompleted).isTrue()
        assertThat(second.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(first.isCompleted).isFalse()
        runtime.sessionFinishes.getValue("a").complete(Unit); runCurrent()
        assertThat(first.await()).isEqualTo(SystemTurnOutcome.Completed)
    }

    @Test fun `一个会话的准入查询挂起不阻挡其它会话`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = Runtime().apply { ready.complete(Unit); sessionChecks["a"] = gate }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val first = async { dispatcher.request(request("first", session = "a")) }; runCurrent()
        val second = async { dispatcher.request(request("second", session = "b")) }; runCurrent()
        val completedBeforeRelease = second.isCompleted
        gate.complete(Unit); runCurrent()
        assertThat(completedBeforeRelease).isTrue()
        assertThat(second.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(first.await()).isEqualTo(SystemTurnOutcome.Completed)
    }

    @Test fun `查询挂起时超时会撤回且不会事后启动`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = Runtime().apply { ready.complete(Unit); sessionChecks["a"] = gate }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断", busyTimeoutMillis = 1000)
        val waiting = async { dispatcher.request(request("first", session = "a")) }; runCurrent()
        advanceTimeBy(1000); runCurrent()
        assertThat(waiting.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
        gate.complete(Unit); runCurrent()
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `查询挂起时同来源新请求能够取代旧请求`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = Runtime().apply { ready.complete(Unit); sessionChecks["a"] = gate }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val old = async { dispatcher.request(request("same", body = "旧", session = "a")) }; runCurrent()
        val latest = async { dispatcher.request(request("same", body = "新", session = "b")) }; runCurrent()
        assertThat(old.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
        assertThat(latest.await()).isEqualTo(SystemTurnOutcome.Completed)
        gate.complete(Unit); runCurrent()
        assertThat(runtime.calls.map { it.body }).containsExactly("新")
    }

    @Test fun `同会话较早请求被抢占重排后仍先于较晚请求`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = Runtime().apply { ready.complete(Unit); rejectedSource = "first"; rejectionGate = gate }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val first = async { dispatcher.request(request("first", session = "s")) }; runCurrent()
        val second = async { dispatcher.request(request("second", session = "s")) }; runCurrent()
        gate.complete(Unit); runCurrent()
        assertThat(first.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(second.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls.map { it.sourceId }).containsExactly("first", "second").inOrder()
    }

    @Test fun `单个会话准入查询失败不影响其它系统轮`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); brokenSessions += "broken" }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val broken = async { dispatcher.request(request("bad", session = "broken")) }
        val healthy = async { dispatcher.request(request("good", session = "healthy")) }
        runCurrent()
        assertThat(broken.await()).isEqualTo(SystemTurnOutcome.Failed("未完成"))
        assertThat(healthy.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls.map { it.sessionId }).containsExactly("healthy")
    }

    @Test fun `数据未就绪不能读取会话或发出生成`() = runTest {
        val runtime = Runtime()
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }
        runCurrent()
        assertThat(runtime.loads).isEqualTo(0)
        assertThat(runtime.calls).isEmpty()
        runtime.ready.complete(Unit); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.loads).isEqualTo(1)
    }

    @Test fun `缺失会话跳过且不载入`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); exists = false }
        val outcome = async { DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断").request(request()) }
        runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING))
        assertThat(runtime.loads).isEqualTo(0)
    }

    @Test fun `忙时同来源仅保留最新请求`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val old = async { dispatcher.request(request(body = "旧")) }; runCurrent()
        val latest = async { dispatcher.request(request(body = "新")) }; runCurrent()
        assertThat(old.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
        assertThat(runtime.loads).isEqualTo(0)
        runtime.busy = false; advanceTimeBy(250); runCurrent()
        assertThat(latest.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls.map { it.body }).containsExactly("新")
    }

    @Test fun `三十分钟繁忙超时使用虚拟时间`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true }
        val outcome = async { DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断").request(request()) }
        runCurrent(); advanceTimeBy(30 * 60 * 1000L); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `其它系统轮次长时间运行时排队请求也能准时超时`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); finish = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val running = async { dispatcher.request(request("first")) }; runCurrent()
        val pending = async { dispatcher.request(request("second")) }; runCurrent()
        advanceTimeBy(30 * 60 * 1000L); runCurrent()
        assertThat(pending.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
        runtime.finish!!.complete(Unit); runCurrent(); running.await()
    }

    @Test fun `调用方取消只撤回尚未发出的请求`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val pending = launch { dispatcher.request(request()) }; runCurrent()
        pending.cancelAndJoin(); runtime.busy = false; advanceTimeBy(250); runCurrent()
        assertThat(runtime.calls).isEmpty()
        runtime.finish = CompletableDeferred()
        val active = launch { dispatcher.request(request()) }; runCurrent()
        active.cancelAndJoin()
        assertThat(runtime.cancelled).isFalse()
        runtime.finish!!.complete(Unit); runCurrent()
    }

    @Test fun `系统撤回执行窗口只取消匹配来源`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); finish = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }; runCurrent()
        dispatcher.stopSource("other")
        assertThat(runtime.cancelled).isFalse()
        dispatcher.stopSource("a"); runCurrent()
        assertThat(runtime.cancelled).isTrue()
        assertThat(outcome.await()).isInstanceOf(SystemTurnOutcome.Failed::class.java)
    }

    @Test fun `撤回未受理来源后不能重新入队或事后启动`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runtime = Runtime().apply { ready.complete(Unit); beforeStart = gate }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }; runCurrent()
        dispatcher.stopSource("a"); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Failed("系统中断"))
        gate.complete(Unit); advanceTimeBy(1000); runCurrent()
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `载入尚未打开的会话期间取消不会发出生成`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); loadWait = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val waiting = launch { dispatcher.request(request()) }; runCurrent()
        assertThat(runtime.loads).isEqualTo(1)
        waiting.cancelAndJoin(); runtime.loadWait!!.complete(Unit); runCurrent()
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `载入期间同来源替换也只发出最新请求`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); loadWait = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val old = async { dispatcher.request(request(body = "旧")) }; runCurrent()
        val latest = async { dispatcher.request(request(body = "新")) }; runCurrent()
        assertThat(old.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
        runtime.loadWait!!.complete(Unit); runCurrent()
        assertThat(latest.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls.map { it.body }).containsExactly("新")
    }

    @Test fun `等待生成准入期间三十分钟到期仍返回繁忙超时`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); beforeStart = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }; runCurrent()
        advanceTimeBy(30 * 60 * 1000L); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
        runtime.beforeStart!!.complete(Unit); runCurrent()
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `暂停只撤回待发而保留已发轮次的真实结果`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); finish = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val active = async { dispatcher.request(request()) }; runCurrent()
        dispatcher.withdrawPendingSource("a")
        assertThat(runtime.cancelled).isFalse()
        runtime.finish!!.complete(Unit); runCurrent()
        assertThat(active.await()).isEqualTo(SystemTurnOutcome.Completed)
    }

    @Test fun `开始时失去生成槽位会重新排队不重复完成`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); rejectedOnce = true }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }; runCurrent()
        assertThat(outcome.isCompleted).isFalse()
        runtime.busy = false; advanceTimeBy(250); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls).hasSize(1)
    }
}
