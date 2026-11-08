package com.promenar.infinity.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.protocol.*
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.trigger.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** 应用作用域与父轮分离，覆盖真实运行器、持久化存储及独立用量统计。 */
@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundSubagentTest {
    private class Fixture(val scope: TestScope, val model: SubagentModelClient) {
        val root = Files.createTempDirectory("bg-subagent-test").toFile()
        var clockOffset = 0L
        val store = SubagentRunStore(root, UnconfinedTestDispatcher(scope.testScheduler))
        val notices = mutableListOf<SystemTurnRequest>()
        var onNotice: suspend (SystemTurnRequest) -> SystemTurnOutcome = { SystemTurnOutcome.Completed }
        val dispatcher = object : SystemTurnDispatcher {
            override suspend fun request(request: SystemTurnRequest): SystemTurnOutcome { notices += request; return onNotice(request) }
        }
        var writer: suspend (Session, SubagentRun, String) -> String = { _, _, _ -> "/subagent-results/result.md" }
        val runtime = create(store)
        fun create(storage: SubagentRunStore = store) = SubagentLauncher(
            SubagentRunner(model, NoTools, SubagentApprovalBroker(), clock = { clockOffset + scope.testScheduler.currentTime }), storage, { id -> Session(id = id, agentId = "a", executionMode = "full_access") }, { "model" },
            applicationScope = scope.backgroundScope, dispatcher = { dispatcher }, clock = { clockOffset + scope.testScheduler.currentTime },
            contextWindow = { 32768 }, writeResult = { session, run, text -> writer(session, run, text) },
        )
    }
    private fun answer(gate: CompletableDeferred<Unit>? = null, reported: ProtocolUsage = ProtocolUsage(10, 5, 15)) = SubagentModelClient { _, messages, _, _, _, _ -> flow {
        gate?.await()
        emit(StreamChunk.TextDelta("结论：${messages.last().content}"))
        emit(StreamChunk.Usage(reported))
        emit(StreamChunk.Completed(CompletionReason.END_TURN))
    } }
    private fun parent(id: String = "parent") = object : SkillExecutionContext {
        override val sessionId = id
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }
    private fun spec(prompt: String = "任务") = SubagentSpec(prompt, prompt, null, SubagentToolProfile.INHERIT)

    @Test fun `父轮插队立即解除子代理等待且子代理继续执行`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val fixture = Fixture(this, answer(gate))
        val queue = MutableStateFlow(com.promenar.infinity.data.chat.queue.ComposerQueueState())
        val waiting = async(com.promenar.infinity.data.exec.SteeringContext(queue)) {
            fixture.runtime.launch(parent(), "steering", listOf(spec()), background = false)
        }
        runCurrent()
        queue.value = com.promenar.infinity.data.chat.queue.ComposerQueueState(items = listOf(
            com.promenar.infinity.data.chat.queue.QueuedMessage("q", "新指令", createdAt = 0, steering = true)))
        runCurrent()
        assertThat(waiting.await().single().status.isActive).isTrue()
        assertThat(fixture.store.snapshot("parent", "steering")!!.wait).isNull()
        gate.complete(Unit)
        runCurrent()
        assertThat(fixture.notices).hasSize(1)
    }

    @Test fun `usage 检查点中断能恢复缓存且运行中重置不会结算旧请求`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flow {
            emit(StreamChunk.TextDelta("完成"))
            emit(StreamChunk.Usage(ProtocolUsage(100, 2, 102, cacheReadInput = 80, cacheWriteInput = 20, cacheWriteLongTtlInput = 10)))
            gate.await()
            emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } })
        f.runtime.launch(parent(), "inflight", listOf(spec())); runCurrent()
        val expected = com.promenar.infinity.domain.repository.CacheUsageAggregate(1, 1, 100, 80, 20, 10)
        val reopened = SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)).load("parent", "inflight")!!.runs.single()
        assertThat(reopened.status).isEqualTo(SubagentStatus.INTERRUPTED)
        assertThat(reopened.cache).isEqualTo(expected)
        val stats = SubagentTokenStatsRepository(EmptyStats, f.store)
        assertThat(stats.getTotalUsage().cache).isEqualTo(expected)
        stats.resetStats()
        gate.complete(Unit); runCurrent()
        assertThat(stats.getTotalUsage().inputTokens).isEqualTo(0)
        assertThat(stats.getTotalUsage().cache).isEqualTo(com.promenar.infinity.domain.repository.CacheUsageAggregate())
    }

    @Test fun `跨日已报告后未报告的请求不伪造每日缓存零值`() = runTest {
        var calls = 0
        val f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flow {
            emit(StreamChunk.TextDelta("完成"))
            emit(StreamChunk.Usage(if (++calls == 1) ProtocolUsage(100, 2, 102, cacheReadInput = 80, cacheWriteInput = 20, cacheWriteLongTtlInput = 10)
                else ProtocolUsage(100, 2, 102)))
            emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } })
        val run = f.runtime.launch(parent(), "days", listOf(spec())).single(); runCurrent()
        f.clockOffset = 86_400_000L
        f.runtime.send("parent", run.id, "后续任务"); runCurrent()
        val day2 = f.runtime.read("parent", run.id).dailyUsage.getValue("1970-01-02")
        assertThat(day2.cacheReadInput).isNull()
        assertThat(day2.cacheWriteInput).isNull()
        assertThat(day2.cacheWriteLongTtlInput).isNull()
    }

    @Test fun `请求发出后首帧用量前重置也排除在途请求`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flow {
            entered.complete(Unit)
            gate.await()
            emit(StreamChunk.TextDelta("完成"))
            emit(StreamChunk.Usage(ProtocolUsage(100, 2, 102, cacheReadInput = 80)))
            emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } })
        f.runtime.launch(parent(), "before-usage", listOf(spec())); runCurrent()
        assertThat(entered.isCompleted).isTrue()
        val stats = SubagentTokenStatsRepository(EmptyStats, f.store)
        assertThat(stats.getTotalUsage().cache.requests).isEqualTo(0)
        stats.resetStats()
        gate.complete(Unit); runCurrent()
        assertThat(stats.getTotalUsage().inputTokens).isEqualTo(100)
        assertThat(stats.getTotalUsage().cache).isEqualTo(com.promenar.infinity.domain.repository.CacheUsageAggregate())
    }

    @Test fun `运行中重置排除后来首次报告的缓存且连续重置保持一致`() = runTest {
        for (firstRead in listOf<Int?>(null, 80)) {
            val gate = CompletableDeferred<Unit>()
            val f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flow {
                emit(StreamChunk.TextDelta("完成"))
                emit(StreamChunk.Usage(ProtocolUsage(50, 2, 52, cacheReadInput = firstRead)))
                gate.await()
                emit(StreamChunk.Usage(ProtocolUsage(100, 2, 102, cacheReadInput = 80)))
                emit(StreamChunk.Completed(CompletionReason.END_TURN))
            } })
            val run = f.runtime.launch(parent(), "late-report", listOf(spec())).single(); runCurrent()
            val stats = SubagentTokenStatsRepository(EmptyStats, f.store)
            stats.resetStats(); stats.resetStats()
            gate.complete(Unit); runCurrent()
            assertThat(stats.getTotalUsage().inputTokens).isEqualTo(50)
            assertThat(stats.getTotalUsage().cache).isEqualTo(com.promenar.infinity.domain.repository.CacheUsageAggregate())
            f.runtime.send("parent", run.id, "新请求"); runCurrent()
            val cache = stats.getTotalUsage().cache
            assertThat(cache.requests).isEqualTo(1)
            assertThat(cache.reportedRequests).isEqualTo(1)
            assertThat(cache.reportedInputTokens).isEqualTo(100)
            assertThat(cache.anomalousRequests).isEqualTo(0)
        }
    }

    @Test fun `请求跨日首次报告保留缓存起始日且不丢失每日聚合`() = runTest {
        lateinit var f: Fixture
        f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flow {
            f.clockOffset = 86_400_000L
            emit(StreamChunk.TextDelta("完成"))
            emit(StreamChunk.Usage(ProtocolUsage(100, 2, 102, cacheReadInput = 80)))
            emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } })
        f.runtime.launch(parent(), "overnight", listOf(spec())); runCurrent()
        val daily = SubagentTokenStatsRepository(EmptyStats, f.store).getDailyTrend(30_000)
        assertThat(daily.single { it.day == "1970-01-01" }.cache.reportedInputTokens).isEqualTo(100)
        assertThat(daily.single { it.day == "1970-01-02" }.inputTokens).isEqualTo(100)
        assertThat(daily.single { it.day == "1970-01-02" }.cache.requests).isEqualTo(0)
    }

    @Test fun `缓存每日累计重启与重置水位保持报告口径`() = runTest {
        val reported = ProtocolUsage(10, 5, 15, cacheReadInput = 0, cacheWriteInput = 4, cacheWriteLongTtlInput = 2)
        val f = Fixture(this, answer(reported = reported))
        val run = f.runtime.launch(parent(), "cache", listOf(spec())).single(); runCurrent()
        val stats = SubagentTokenStatsRepository(EmptyStats, f.store)
        val expected = com.promenar.infinity.domain.repository.CacheUsageAggregate(1, 1, 10, 0, 4, 2)
        val stored = f.runtime.read("parent", run.id)
        assertThat(stored.cacheReadInput).isEqualTo(0)
        assertThat(stored.dailyUsage.values.single().cacheWriteLongTtlInput).isEqualTo(2)
        assertThat(stored.dailyCacheUsage.values.single()).isEqualTo(expected)
        assertThat(stats.getTotalUsage().cache).isEqualTo(expected)
        assertThat(stats.getDailyTrend(30_000).single().cache).isEqualTo(expected)
        assertThat(stats.getUsageByModel().single().usage.cache).isEqualTo(expected)
        assertThat(stats.getUsageByCategory().single { it.category == "subagent" }.usage.cache).isEqualTo(expected)
        val reopened = SubagentTokenStatsRepository(EmptyStats, SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        assertThat(reopened.getTotalUsage().cache).isEqualTo(expected)
        stats.resetStats()
        assertThat(stats.getTotalUsage().cache).isEqualTo(com.promenar.infinity.domain.repository.CacheUsageAggregate())
        assertThat(stats.getDailyTrend(30_000).single().cache.requests).isEqualTo(0)
        f.runtime.send("parent", run.id, "后续任务"); runCurrent()
        assertThat(stats.getTotalUsage().cache).isEqualTo(expected)
        assertThat(stats.getDailyTrend(30_000).single().cache).isEqualTo(expected)
    }

    @Test fun `旧子代理水位缺少缓存数据仍能解码`() = runTest {
        val f = Fixture(this, answer())
        f.root.resolve("usage-watermarks.json").writeText("""{"old":{"input":10,"output":5,"total":15,"estimated":false}}""")
        val old = f.store.usageWatermarks().getValue("old")
        assertThat(old.input).isEqualTo(10)
        assertThat(old.cache.requests).isEqualTo(0)
    }

    @Test fun `后台立即返回全局地址且父轮取消后完成只通知一次`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, answer(gate))
        var id = ""
        val parentJob = launch { id = f.runtime.launch(parent(), "s", listOf(spec())).single().id; awaitCancellation() }
        runCurrent()
        assertThat(id).matches("sa_[a-z0-9]{6}")
        parentJob.cancelAndJoin()
        assertThat(f.runtime.read("parent", id).status.isActive).isTrue()
        gate.complete(Unit); runCurrent()
        assertThat(f.runtime.read("parent", id).status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(f.notices.map { it.kind }).containsExactly(SystemTurnKind.SUBAGENT_DONE)
        f.runtime.recover(); runCurrent()
        assertThat(f.notices).hasSize(1)
        val restarted = f.create(SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        restarted.recover(); runCurrent()
        assertThat(f.notices).hasSize(1)
    }

    @Test fun `同时间同正文的两条父指令都保留独立事件与上下文`() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        f.runtime.send("parent", run.id, "相同指令")
        f.runtime.send("parent", run.id, "相同指令")
        gate.complete(Unit); runCurrent()
        assertThat(f.runtime.read("parent", run.id).events.count { it.kind == SubagentEvent.Kind.PARENT_MESSAGE }).isEqualTo(2)
        assertThat(f.store.execution("parent", "s", run.id)!!.messages.count { it.role == "user" && it.content == "相同指令" }).isEqualTo(2)
    }

    @Test fun `前台等待结论并清除wait不发通知`() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
        val waiting = async { f.runtime.launch(parent(), "s", listOf(spec()), background = false) }
        runCurrent()
        assertThat(f.store.snapshot("parent", "s")!!.wait).isNotNull()
        gate.complete(Unit); runCurrent()
        assertThat(waiting.await().single().result).isEqualTo("结论：任务")
        assertThat(f.store.snapshot("parent", "s")!!.wait).isNull()
        assertThat(f.notices).isEmpty()
    }

    @Test fun `超时和用户转后台只解除等待并分别记录来源`() = runTest {
        for (by in listOf(SubagentEvent.Actor.TIMEOUT, SubagentEvent.Actor.USER, SubagentEvent.Actor.PARENT)) {
            val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
            val waiting = async { f.runtime.launch(parent(), "s", listOf(spec()), background = false, timeoutMillis = 1000) }
            runCurrent()
            when (by) {
                SubagentEvent.Actor.TIMEOUT -> { advanceTimeBy(1001); runCurrent() }
                SubagentEvent.Actor.USER -> { f.runtime.releaseWait("parent", "s"); runCurrent() }
                else -> waiting.cancelAndJoin()
            }
            if (by != SubagentEvent.Actor.PARENT) assertThat(waiting.await().single().status.isActive).isTrue()
            val run = f.store.snapshot("parent", "s")!!.runs.single()
            assertThat(run.events.last().by).isEqualTo(by)
            assertThat(f.store.snapshot("parent", "s")!!.wait).isNull()
            gate.complete(Unit); runCurrent()
            assertThat(f.notices).hasSize(1)
        }
    }

    @Test fun `超出上限排队并按FIFO补位取消未启动任务也释放槽位`() = runTest {
        val limit = SubagentLimits.MAX_BACKGROUND_CONCURRENT
        val gates = (0..limit + 2).associate { "p$it" to CompletableDeferred<Unit>() }
        val started = mutableListOf<String>()
        val f = Fixture(this, SubagentModelClient { _, messages, _, _, _, _ -> flow {
            val prompt = messages.first { it.role == "user" }.content!!
            started += prompt; gates.getValue(prompt).await()
            emit(StreamChunk.TextDelta(prompt)); emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } })
        val first = f.runtime.launch(parent(), "first", listOf(spec("p0"))).single()
        f.runtime.stop("parent", first.id)
        runCurrent()
        val runs = (1..limit + 2).map { f.runtime.launch(parent(), "s$it", listOf(spec("p$it"))).single() }
        runCurrent()
        assertThat(started).containsExactlyElementsIn((1..limit).map { "p$it" }).inOrder()
        assertThat(f.runtime.read("parent", runs[limit].id).status).isEqualTo(SubagentStatus.QUEUED)
        gates.getValue("p2").complete(Unit); runCurrent()
        assertThat(started.last()).isEqualTo("p${limit + 1}")
        gates.getValue("p1").complete(Unit); runCurrent()
        assertThat(started.last()).isEqualTo("p${limit + 2}")
        f.runtime.deleteSession("parent"); runCurrent()
        assertThat(f.runtime.activeRuns.value).isEmpty()
        assertThat(f.store.all()).isEmpty()
    }

    @Test fun `恢复沿用完整历史再次通知硬停止不可恢复且禁止跨父会话寻址`() = runTest {
        val f = Fixture(this, answer())
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        f.runtime.send("parent", run.id, "追加指令"); runCurrent(); advanceTimeBy(251); runCurrent()
        val resumed = f.runtime.read("parent", run.id)
        assertThat(resumed.result).isEqualTo("结论：追加指令")
        assertThat(resumed.events.any { it.kind == SubagentEvent.Kind.RESUME }).isTrue()
        assertThat(f.notices).hasSize(2)
        assertThat(f.store.execution("parent", "s", run.id)!!.messages.map { it.content }).contains("任务")
        assertThat(runCatching { f.runtime.read("foreign", run.id) }.isFailure).isTrue()
        val gate = CompletableDeferred<Unit>()
        val stopped = Fixture(this, answer(gate))
        val live = stopped.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        stopped.runtime.stop("parent", live.id, SubagentEvent.Actor.PARENT); runCurrent()
        assertThat(stopped.runtime.read("parent", live.id).status).isEqualTo(SubagentStatus.STOPPED)
        assertThat(runCatching { stopped.runtime.send("parent", live.id, "不能恢复") }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(stopped.notices).isEmpty()
    }

    @Test fun `重启中断只补送一次并可用持久上下文恢复`() = runTest {
        val f = Fixture(this, answer())
        val run = SubagentRun(0, "sa_000001", description = "任务", prompt = "初始任务", toolProfile = SubagentToolProfile.INHERIT,
            modelId = "model", maxSteps = 0, maxTokens = 0, startedAt = 0)
        f.store.update(SubagentBatch("parent", "s", listOf(run)), true)
        f.store.saveExecution("parent", "s", run.id, SubagentExecutionState(messages = listOf(ProtocolMessage("system", "s"), ProtocolMessage("user", "初始任务"))))
        val restored = f.create(SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        restored.recover(); runCurrent()
        assertThat(restored.read("parent", run.id).status).isEqualTo(SubagentStatus.INTERRUPTED)
        assertThat(f.notices.single().body).contains("session_send")
        restored.recover(); runCurrent(); assertThat(f.notices).hasSize(1)
        restored.send("parent", run.id, "恢复工作"); runCurrent(); advanceTimeBy(251); runCurrent()
        assertThat(restored.read("parent", run.id).result).isEqualTo("结论：恢复工作")
        assertThat(f.notices).hasSize(2)
    }

    @Test fun `完整结论文件落盘后才解除前台等待`() = runTest {
        val gate = CompletableDeferred<Unit>(); val text = "长".repeat(20000)
        val f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flowOf(StreamChunk.TextDelta(text), StreamChunk.Completed(CompletionReason.END_TURN)) })
        var saved = ""
        f.writer = { _, _, content -> saved = content; gate.await(); "/subagent-results/full.md" }
        val waiting = async { f.runtime.launch(parent(), "s", listOf(spec()), background = false) }
        runCurrent()
        assertThat(waiting.isCompleted).isFalse(); assertThat(saved).isEqualTo(text)
        gate.complete(Unit); runCurrent()
        val result = waiting.await().single()
        assertThat(result.resultPath).isEqualTo("/subagent-results/full.md")
        assertThat(result.result!!.length).isLessThan(text.length)
        assertThat(f.notices).isEmpty()
    }

    @Test fun `独立用量在父轮结束后持久计入统计并可重置且恢复不双计`() = runTest {
        val f = Fixture(this, answer())
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        val stats = SubagentTokenStatsRepository(EmptyStats, f.store)
        assertThat(f.store.drain("parent")).isNull()
        assertThat(stats.getUsageBySession("parent").totalTokens).isEqualTo(15)
        assertThat(stats.getTotalUsage().totalTokens).isEqualTo(15)
        f.runtime.send("parent", run.id, "继续"); runCurrent(); advanceTimeBy(251); runCurrent()
        assertThat(stats.getUsageBySession("parent").totalTokens).isEqualTo(30)
        assertThat(stats.getUsageBySession("parent").totalTokens).isEqualTo(30)
        stats.resetStats()
        assertThat(stats.getTotalUsage().totalTokens).isEqualTo(0)
        f.runtime.send("parent", run.id, "继续"); runCurrent()
        assertThat(stats.getUsageBySession("parent").totalTokens).isEqualTo(15)
        val afterRestart = SubagentTokenStatsRepository(EmptyStats, SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        assertThat(afterRestart.getTotalUsage().totalTokens).isEqualTo(15)
    }

    @Test fun `跨批次等待冲突和写入失败均不残留等待所有权`() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
        val a = f.runtime.launch(parent(), "a", listOf(spec())).single()
        val b = f.runtime.launch(parent(), "b", listOf(spec())).single()
        runCurrent()
        val first = async { f.runtime.waitFor("parent", listOf(b.id)) }; runCurrent()
        assertThat(runCatching { f.runtime.waitFor("parent", listOf(a.id, b.id)) }.isFailure).isTrue()
        assertThat(f.store.snapshot("parent", "a")!!.wait).isNull()
        f.runtime.releaseWait("parent", "b"); runCurrent(); first.await()
        val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" && it.readText().contains("\"stepId\":\"b\"") }
        file.delete(); file.mkdir()
        assertThat(runCatching { f.runtime.waitFor("parent", listOf(a.id, b.id)) }.isFailure).isTrue()
        assertThat(f.store.snapshot("parent", "a")!!.wait).isNull()
        assertThat(f.store.snapshot("parent", "b")!!.wait).isNull()
        file.delete(); f.store.update(f.store.snapshot("parent", "b")!!, true)
        gate.complete(Unit); runCurrent()
        assertThat(f.notices).hasSize(2)
    }

    @Test fun `持续持久化失败也发布可观察失败终态而非永久运行中`() = runTest {
        val gate = CompletableDeferred<Unit>(); val f = Fixture(this, answer(gate))
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
        file.delete(); file.mkdir()
        gate.complete(Unit); runCurrent()
        assertThat(f.runtime.read("parent", run.id).status).isEqualTo(SubagentStatus.FAILED)
        assertThat(f.runtime.activeRuns.value).isEmpty()
        assertThat(f.runtime.read("parent", run.id).error).contains("持久化失败")
    }

    @Test fun `硬停止不受持久化失败阻断并立即取消模型`() = runTest {
        val cancelled = CompletableDeferred<Unit>()
        val f = Fixture(this, SubagentModelClient { _, _, _, _, _, _ -> flow {
            try { awaitCancellation() } finally { cancelled.complete(Unit) }
        } })
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
        file.delete(); file.mkdir()
        f.runtime.stop("parent", run.id); runCurrent()
        assertThat(cancelled.isCompleted).isTrue()
        assertThat(f.runtime.read("parent", run.id).status).isEqualTo(SubagentStatus.STOPPED)
        assertThat(f.notices).isEmpty()
    }

    @Test fun `重启保留旧结论并追加中断通知且按顺序补送`() = runTest {
        val f = Fixture(this, answer())
        val old = SubagentCompletionNotice(1, SubagentStatus.COMPLETED, "旧结论", 10, 1)
        val run = SubagentRun(0, "sa_000001", description = "任务", prompt = "任务", toolProfile = SubagentToolProfile.INHERIT,
            modelId = "model", maxSteps = 0, maxTokens = 0, startedAt = 0, completionSequence = 1, pendingNotices = listOf(old))
        f.store.update(SubagentBatch("parent", "s", listOf(run)), true)
        val restarted = f.create(SubagentRunStore(f.root, UnconfinedTestDispatcher(testScheduler)))
        restarted.recover(); runCurrent()
        assertThat(f.notices.map { it.sequence }).containsExactly(1, 2).inOrder()
        assertThat(f.notices.last().body).contains("INTERRUPTED")
    }

    @Test fun `跨日用量不搬移且基础统计重置失败补偿子代理水位`() = runTest {
        val f = Fixture(this, answer())
        val now = java.time.LocalDate.now(java.time.ZoneOffset.UTC)
        val day1 = now.minusDays(1).toString(); val day2 = now.toString()
        val run = SubagentRun(0, "sa_000001", description = "任务", prompt = "任务", toolProfile = SubagentToolProfile.INHERIT,
            modelId = "model", maxSteps = 0, startedAt = 0, status = SubagentStatus.COMPLETED, inputTokens = 110, totalTokens = 110,
            dailyUsage = mapOf(day1 to TokenUsage(input = 100, total = 100), day2 to TokenUsage(input = 10, total = 10)))
        f.store.update(SubagentBatch("parent", "s", listOf(run)), true)
        val broken = object : com.promenar.infinity.domain.repository.ITokenStatsRepository by EmptyStats {
            override suspend fun resetStats(): Unit = throw java.io.IOException("reset")
        }
        val stats = SubagentTokenStatsRepository(broken, f.store)
        assertThat(stats.getDailyTrend(3).map { it.totalTokens() }).containsExactly(100L, 10L).inOrder()
        assertThat(runCatching { stats.resetStats() }.isFailure).isTrue()
        assertThat(stats.getTotalUsage().totalTokens).isEqualTo(110)
    }
    @Test fun `等待已交回结论时撤回已入步骤队列的同序号通知`() = runTest {
        val f = Fixture(this, answer())
        val bridge = com.promenar.infinity.data.trigger.SubagentCompletionSteering(backgroundScope)
        val chat = com.promenar.infinity.ui.chat.ChatStore().apply { upsertSession(Session("parent", "a")) }
        val messages = io.mockk.mockk<com.promenar.infinity.ui.chat.manager.MessageManager>(relaxed = true)
        f.onNotice = { request ->
            bridge.enqueue(request, com.promenar.infinity.data.trigger.SystemTurnAdmissionTicket()).outcome.await()
        }
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        assertThat(f.notices).hasSize(1)
        assertThat(f.runtime.waitFor("parent", listOf(run.id)).single().result).isEqualTo("结论：任务")
        assertThat(bridge.deliver("parent", chat, messages)).isNull()
        io.mockk.coVerify(exactly = 0) { messages.addMessage(any(), any()) }
    }

    @Test fun `通知先确认后等待不重复交回相同结论`() = runTest {
        val f = Fixture(this, answer())
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        assertThat(f.notices).hasSize(1)
        val waited = f.runtime.waitFor("parent", listOf(run.id)).single()
        assertThat(waited.result).isNull()
        assertThat(waited.resultPath).isNull()
    }

    @Test fun `失败通知指数退避且长时间等待不持续空转`() = runTest {
        val f = Fixture(this, answer())
        f.onNotice = { SystemTurnOutcome.Failed("暂不可送达") }
        f.runtime.launch(parent(), "s", listOf(spec())); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertThat(f.notices.size).isAtMost(8)
        assertThat(f.notices.size).isAtLeast(3)
        assertThat(f.store.snapshot("parent", "s")!!.runs.single().pendingNotices).hasSize(1)
    }

    @Test fun `持久化暂停不占用Launcher全局状态锁`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val f = Fixture(this, answer(gate))
        val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        var blocked = false
        val disk = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (blocked) queued.add(block) else block.run()
            }
        }
        val runtime = f.create(SubagentRunStore(f.root, disk))
        val run = runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        blocked = true
        val waiting = backgroundScope.async { runtime.waitFor("parent", listOf(run.id)) }; runCurrent()
        val reading = backgroundScope.async { runtime.read("parent", run.id) }; runCurrent()
        val readable = reading.isCompleted
        blocked = false
        while (true) (queued.poll() ?: break).run()
        waiting.cancel(); runCurrent()
        runtime.stop("parent", run.id); runCurrent()
        assertThat(readable).isTrue()
    }

    @Test fun `等待确认持久化失败不交回结论且保留未交付通知`() = runTest {
        val f = Fixture(this, answer())
        f.onNotice = { SystemTurnOutcome.Failed("暂不可送达") }
        lateinit var storage: SubagentRunStore
        var failConfirmation = false
        val disk = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                val batch = storage.snapshot("parent", "s")
                if (failConfirmation && batch?.wait != null && batch.runs.single().pendingNotices.isEmpty()) {
                    failConfirmation = false
                    val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
                    file.delete(); file.mkdir()
                }
                block.run()
            }
        }
        storage = SubagentRunStore(f.root, disk)
        val runtime = f.create(storage)
        val run = runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        failConfirmation = true
        val waited = runtime.waitFor("parent", listOf(run.id)).single()
        assertThat(waited.result).isNull()
        assertThat(waited.error).contains("持久化失败")
        assertThat(runtime.read("parent", run.id).pendingNotices).hasSize(1)
        assertThat(storage.snapshot("parent", "s")!!.wait).isNull()
    }

    @Test fun `创建检查点失败的派生不会在磁盘恢复后被其它批次启动`() = runTest {
        val started = mutableListOf<String>()
        val f = Fixture(this, SubagentModelClient { _, messages, _, _, _, _ -> flow {
            started += messages.last().content
            emit(StreamChunk.TextDelta("完成")); emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } })
        f.runtime.recover()
        f.root.delete(); f.root.writeText("不可写的根")
        assertThat(runCatching { f.runtime.launch(parent(), "failed-create", listOf(spec("失败派生"))) }.isFailure).isTrue()
        f.root.delete(); f.root.mkdir()
        f.runtime.launch(parent(), "valid-create", listOf(spec("正常派生"))); runCurrent()
        assertThat(started).containsExactly("正常派生")
        assertThat(f.runtime.activeRuns.value).isEmpty()
    }

    @Test fun `恢复检查点失败的子代理不会被其它批次带入运行队列`() = runTest {
        val started = mutableListOf<String>()
        val f = Fixture(this, SubagentModelClient { _, messages, _, _, _, _ -> flow {
            started += messages.last().content
            emit(StreamChunk.TextDelta("完成")); emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } })
        val run = f.runtime.launch(parent(), "s", listOf(spec("初次派生"))).single(); runCurrent()
        val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
        file.delete(); file.mkdir()
        assertThat(runCatching { f.runtime.send("parent", run.id, "失败恢复") }.isFailure).isTrue()
        file.delete()
        f.runtime.launch(parent(), "valid", listOf(spec("正常派生"))); runCurrent()
        assertThat(started).containsExactly("初次派生", "正常派生").inOrder()
        assertThat(f.runtime.activeRuns.value).isEmpty()
    }

    @Test fun `通知正在写入时等待在回执后不重复交回结论`() = runTest {
        val f = Fixture(this, answer())
        val bridge = com.promenar.infinity.data.trigger.SubagentCompletionSteering(backgroundScope)
        val chat = com.promenar.infinity.ui.chat.ChatStore().apply { upsertSession(Session("parent", "a")) }
        val disk = CompletableDeferred<Unit>()
        val messages = io.mockk.mockk<com.promenar.infinity.ui.chat.manager.MessageManager>(relaxed = true)
        io.mockk.coEvery { messages.addMessage(any(), any()) } coAnswers {
            val message = secondArg<Message>()
            disk.await()
            chat.updateSession("parent") { it.copy(messages = it.messages + message) }
        }
        f.onNotice = { request ->
            bridge.enqueue(request, com.promenar.infinity.data.trigger.SystemTurnAdmissionTicket()).outcome.await()
        }
        val run = f.runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        val delivering = backgroundScope.async { bridge.deliver("parent", chat, messages) }; runCurrent()
        val waiting = backgroundScope.async { f.runtime.waitFor("parent", listOf(run.id)) }; runCurrent()
        assertThat(waiting.isCompleted).isFalse()
        disk.complete(Unit); runCurrent()
        assertThat(delivering.await()).isNotNull()
        assertThat(waiting.await().single().result).isNull()
        assertThat(chat.getSession("parent")!!.messages).hasSize(1)
    }

    @Test fun `多个等待目标部分确认失败仍交回成功目标并保留失败目标通知`() = runTest {
        val f = Fixture(this, answer())
        f.onNotice = { SystemTurnOutcome.Failed("暂不可送达") }
        lateinit var storage: SubagentRunStore
        var failConfirmation = false
        val disk = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                val batch = storage.snapshot("parent", "s")
                if (failConfirmation && batch?.wait != null && batch.runs.all { it.pendingNotices.isEmpty() }) {
                    failConfirmation = false
                    val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
                    file.delete(); file.mkdir()
                }
                block.run()
            }
        }
        storage = SubagentRunStore(f.root, disk)
        val runtime = f.create(storage)
        val runs = runtime.launch(parent(), "s", listOf(spec("目标一"), spec("目标二"))); runCurrent()
        failConfirmation = true
        val waited = runtime.waitFor("parent", runs.map { it.id })
        assertThat(waited[0].result).isEqualTo("结论：目标一")
        assertThat(waited[1].result).isNull()
        assertThat(waited[1].error).contains("持久化失败")
        assertThat(runtime.read("parent", runs[0].id).pendingNotices).isEmpty()
        assertThat(runtime.read("parent", runs[1].id).pendingNotices).hasSize(1)
    }

    @Test fun `注册等待检查点失败不会在finally消费尚未交回的结论`() = runTest {
        val f = Fixture(this, answer())
        f.onNotice = { SystemTurnOutcome.Failed("暂不可送达") }
        lateinit var storage: SubagentRunStore
        var failRegistration = false
        var broken: java.io.File? = null
        val disk = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                if (broken != null) { broken!!.delete(); broken = null }
                val batch = storage.snapshot("parent", "s")
                if (failRegistration && batch?.wait != null) {
                    failRegistration = false
                    broken = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
                    broken!!.delete(); broken!!.mkdir()
                }
                block.run()
            }
        }
        storage = SubagentRunStore(f.root, disk)
        val runtime = f.create(storage)
        val run = runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        failRegistration = true
        assertThat(runCatching { runtime.waitFor("parent", listOf(run.id)) }.isFailure).isTrue()
        assertThat(runtime.read("parent", run.id).pendingNotices).hasSize(1)
        assertThat(runtime.waitFor("parent", listOf(run.id)).single().result).isEqualTo("结论：任务")
    }

    @Test fun `通知消息已写但outbox确认失败后等待不会重新领取同一结论`() = runTest {
        val f = Fixture(this, answer())
        val bridge = com.promenar.infinity.data.trigger.SubagentCompletionSteering(backgroundScope)
        val chat = com.promenar.infinity.ui.chat.ChatStore().apply { upsertSession(Session("parent", "a")) }
        val messages = io.mockk.mockk<com.promenar.infinity.ui.chat.manager.MessageManager>(relaxed = true)
        io.mockk.coEvery { messages.addMessage(any(), any()) } coAnswers {
            val message = secondArg<Message>()
            chat.updateSession("parent") { it.copy(messages = it.messages + message) }
        }
        val dispatcherReceipt = CompletableDeferred<Unit>()
        f.onNotice = { request ->
            val pending = bridge.enqueue(request, com.promenar.infinity.data.trigger.SystemTurnAdmissionTicket())
            pending.outcome.await()
            dispatcherReceipt.await()
            SystemTurnOutcome.Completed
        }
        lateinit var storage: SubagentRunStore
        var failAck = false
        val disk = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                val batch = storage.snapshot("parent", "s")
                if (failAck && batch != null && batch.runs.single().pendingNotices.isEmpty()) {
                    failAck = false
                    val file = f.root.walkTopDown().first { it.isFile && it.extension == "json" }
                    file.delete(); file.mkdir()
                }
                block.run()
            }
        }
        storage = SubagentRunStore(f.root, disk)
        val runtime = f.create(storage)
        val run = runtime.launch(parent(), "s", listOf(spec())).single(); runCurrent()
        failAck = true
        assertThat(bridge.deliver("parent", chat, messages)).isNotNull()
        f.root.walkTopDown().filter { it.isDirectory && it.extension == "json" }.toList().forEach { it.delete() }
        val waited = runtime.waitFor("parent", listOf(run.id)).single()
        assertThat(waited.result).isNull()
        assertThat(chat.getSession("parent")!!.messages).hasSize(1)
        dispatcherReceipt.complete(Unit); runCurrent()
    }

    private fun com.promenar.infinity.domain.repository.DailyTokenStats.totalTokens() = inputTokens + outputTokens

    private object NoTools : SubagentToolHost {
        override suspend fun tools(session: Session) = emptyList<ProtocolTool>()
        override suspend fun target(session: Session, tool: ProtocolTool, arguments: String): String? = null
        override suspend fun isGranted(sessionId: String, toolName: String, target: String) = false
        override suspend fun remember(sessionId: String, toolName: String, target: String) = Unit
        override fun preview(toolName: String, arguments: JsonObject) = ""
        override suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext) = SubagentToolOutcome("", false)
    }
    private object EmptyStats : com.promenar.infinity.domain.repository.ITokenStatsRepository {
        override suspend fun getTotalUsage() = com.promenar.infinity.domain.repository.TokenUsageAggregate()
        override suspend fun getUsageBySession(sessionId: String) = getTotalUsage()
        override suspend fun getUsageByModel() = emptyList<com.promenar.infinity.domain.repository.ModelTokenStats>()
        override suspend fun getTopSessions(limit: Int) = emptyList<com.promenar.infinity.domain.repository.SessionTokenUsage>()
        override suspend fun getDailyTrend(days: Int) = emptyList<com.promenar.infinity.domain.repository.DailyTokenStats>()
        override suspend fun resetStats() = Unit
    }
}
