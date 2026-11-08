package com.promenar.infinity.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.generation.ApproximateTokenEstimator
import com.promenar.infinity.data.generation.ChatProviderRouteGate
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderResolution
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.UnifiedLlmClient
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.LlmProtocol
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.PromptResponse
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.provider.LlmProvider
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.data.remote.protocol.ProtocolUsage
import com.promenar.infinity.data.remote.protocol.ReasoningReplay
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** 子代理运行循环：受限工具集、继承父会话的范围权限、运行中审批、超时与取消。 */
class SubagentRunnerTest {
    @Test fun `旧缓存聚合追加首个请求快照不重复计算旧用量`() = runTest {
        val legacy = com.promenar.infinity.domain.repository.CacheUsageAggregate(1, 1, 100, 80)
        val result = runner(ScriptedModels(listOf(text("完成"), StreamChunk.Usage(ProtocolUsage(100, 2, 102, cacheReadInput = 20)))), FakeHost(emptyList()))
            .run(parent(), session("auto"), "legacy", run().copy(inputTokens = 100, cache = legacy), null) { _, _ -> }
        assertThat(result.cache).isEqualTo(com.promenar.infinity.domain.repository.CacheUsageAggregate(2, 2, 200, 100))
        assertThat(result.cacheRequests).hasSize(1)
        assertThat(result.legacyCache()).isEqualTo(legacy)
        assertThat(result.cacheSince(SubagentUsageWatermark())).isEqualTo(result.cache)
    }

    @Test fun `缓存快照逐轮取最大并只给已报告请求累计分母`() = runTest {
        val models = ScriptedModels(
            listOf(toolCall("a", "read_file", "{}"), StreamChunk.Usage(ProtocolUsage(100, 2, 102, cacheReadInput = 80, cacheWriteInput = 20, cacheWriteLongTtlInput = 5)),
                StreamChunk.Usage(ProtocolUsage(90, 1, 91, cacheReadInput = 60, cacheWriteInput = 10, cacheWriteLongTtlInput = 2))),
            listOf(toolCall("b", "read_file", "{}"), StreamChunk.Usage(ProtocolUsage(200, 2, 202, cacheWriteInput = 10))),
            listOf(text("完成"), StreamChunk.Usage(ProtocolUsage(50, 2, 52, cacheReadInput = 0))),
        )
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(result.cacheReadInput).isEqualTo(80)
        assertThat(result.cacheWriteInput).isEqualTo(30)
        assertThat(result.cacheWriteLongTtlInput).isEqualTo(5)
        assertThat(result.cache).isEqualTo(com.promenar.infinity.domain.repository.CacheUsageAggregate(3, 2, 150, 80, 30, 5))
        val plain = runner(ScriptedModels(listOf(text("完成"), usage(100, 2))), FakeHost(emptyList()))
            .run(parent(), session("auto"), "plain", run(), null) { _, _ -> }
        assertThat(plain.cacheReadInput).isNull()
        assertThat(plain.cache.reportedRequests).isEqualTo(0)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun `子代理云端和本地每轮先路由再注入实际模型且发送不重复路由`() = runTest {
        for (local in listOf(false, true)) {
            val requests = mutableListOf<PromptRequest>()
            var resolutions = 0
            val type = if (local) ProtocolType.Local else ProtocolType.Generic_OpenAI_Compat
            val protocol = object : LlmProtocol {
                override val protocolType = type
                override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> {
                    requests += request
                    return flow {
                        if (requests.size == 1) {
                            emit(toolCall("read", "read_file", "{}"))
                            emit(StreamChunk.Completed(CompletionReason.TOOL_CALLS, listOf("read")))
                        } else {
                            emit(text("完成"))
                            emit(StreamChunk.Completed(CompletionReason.END_TURN))
                        }
                    }
                }
                override suspend fun sendPromptSync(request: PromptRequest) = PromptResponse("")
                override fun cancel() = Unit
            }
            val config = UnifiedProviderConfig(type, "https://unused.invalid", "", "child")
            val router = object : ProviderRequestRouter {
                override fun resolve(modelId: String): ProviderResolution {
                    assertThat(modelId).isEqualTo("provider::child-key")
                    resolutions++
                    return ProviderResolution.Success(ResolvedProviderModel(
                        modelId, "remote-child-$resolutions", "provider", "Provider", config,
                    ))
                }
                override fun createClient(resolved: ResolvedProviderModel) =
                    UnifiedLlmClient({ config }, protocolFactory = { protocol })
            }
            val models = RoutedSubagentModelClient(ChatProviderRouteGate(router,
                kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler))) { LlmProvider(protocol) }
            val result = runner(models, FakeHost(listOf(read))).run(parent(),
                { sessionOf("auto").copy(modelId = "parent-model") }, "step",
                run().copy(modelId = "provider::child-key"), null) { _, _ -> }
            assertThat(result.error).isNull()
            assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
            assertThat(requests).hasSize(2)
            assertThat(resolutions).isEqualTo(2)
            requests.forEachIndexed { index, request ->
                val modelId = "remote-child-${index + 1}"
                assertThat(request.model).isEqualTo(modelId)
                val prompt = request.messages.first().content
                assertThat(prompt).contains("## Runtime Environment\n- Environment: Infinity Deck\n")
                assertThat(prompt).contains("- Model: $modelId\n")
                assertThat(prompt).doesNotContain("provider::child-key")
                assertThat(prompt).doesNotContain("parent-model")
                assertThat(prompt.indexOf("## Runtime Environment")).isLessThan(prompt.indexOf("## Permissions and Runtime Data"))
            }
            assertThat(requests.last().messages.first().content).doesNotContain("remote-child-1")
            assertThat(requests.first().messages.first().content).doesNotContain("remote-child-2")
        }
    }

    @Test fun `子代理准备路由失败显示实际模型和禁止回退且不发送请求`() = runTest {
        var sent = false
        val models = object : SubagentModelClient {
            override suspend fun prepare(modelId: String): PreparedSubagentModel = throw SubagentModelUnavailable("会话模型当前不可用")
            override suspend fun stream(modelId: String, messages: List<ProtocolMessage>, tools: List<ProtocolTool>,
                maxOutputTokens: Int, reasoningEffort: com.promenar.infinity.data.model.params.ReasoningEffort?, sessionId: String): Flow<StreamChunk> {
                sent = true
                error("路由失败不得发送")
            }
        }
        val result = runner(models, FakeHost(emptyList())).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(result.error).isEqualTo("子代理模型“provider::model”当前不可用；没有改用其它模型。")
        assertThat(sent).isFalse()
    }

    @Test fun `子代理系统提示词包含自身运行环境和模型`() = runTest {
        val models = ScriptedModels(listOf(text("完成")))
        val initial = run().copy(modelId = "child-model")
        val result = runner(models, FakeHost(emptyList())).run(parent(), session("auto"), "step", initial, null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        val prompt = models.requests.single().messages.first().content
        assertThat(prompt).contains("## Runtime Environment\n- Environment: Infinity Deck\n")
        assertThat(prompt).contains("- Model: child-model\n")
    }

    @Test fun `流式用量只按时间节流检查点且结束强制保存最终用量`() = runTest {
        val model = SubagentModelClient { _, _, _, _, _, _ -> flow {
            repeat(100) { emit(usage(it + 1, it + 1)) }
            emit(text("完成"))
            emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } }
        val persisted = mutableListOf<SubagentRun>()
        val saved = mutableListOf<SubagentExecutionState>()
        val result = runner(model, FakeHost(emptyList())).run(parent(), session("auto"), "step", run(), null,
            SubagentExecution(save = { saved += it })) { run, persist -> if (persist) persisted += run }
        assertThat(persisted.size).isAtMost(6)
        assertThat(saved.size).isAtMost(6)
        assertThat(saved.last().inputTokens).isEqualTo(result.inputTokens.toLong())
        assertThat(persisted.last().totalTokens).isEqualTo(result.totalTokens)
    }

    @Test
    fun `不限步数和token时超过十二步与四十万token仍可调用工具`() = runTest {
        val rounds = (1..14).map { listOf(toolCall("c$it", "read_file", "{}"), usage(40_000, 1)) } + listOf(listOf(text("完整完成")))
        val models = ScriptedModels(*rounds.toTypedArray())
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 0, maxTokens = 0), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(result.result).isEqualTo("完整完成")
        assertThat(result.step).isEqualTo(15)
        assertThat(models.requests).hasSize(15)
        assertThat(models.requests.all { it.tools.isNotEmpty() }).isTrue()
    }

    @Test
    fun `maxTurns用最终无工具轮交回未完成前缀`() = runTest {
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}")), listOf(toolCall("c2", "read_file", "{}")), listOf(text("已经核对两个文件")))
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 0).copy(maxTurns = 2), null) { _, _ -> }
        assertThat(models.requests).hasSize(3)
        assertThat(models.requests.last().tools).isEmpty()
        assertThat(result.result).isEqualTo("未完成：已到达轮数上限\n\n已经核对两个文件")
    }

    @Test
    fun `结论不按一万二千字裁切`() = runTest {
        val conclusion = "完整结论".repeat(4_000)
        val result = runner(ScriptedModels(listOf(text(conclusion))), FakeHost(emptyList())).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(result.result).isEqualTo(conclusion)
    }

    @Test
    fun `下一步消费父指令并持久化全文且不重复消费相同id`() = runTest {
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}")), listOf(toolCall("c2", "read_file", "{}")), listOf(text("完成")))
        val saved = mutableListOf<SubagentExecutionState>()
        val instruction = SubagentInstruction("核对配置文件", at = 22L, id = "inbox-1")
        var boundaries = 0
        val execution = SubagentExecution(save = { saved += it }, drainInbox = { if (++boundaries >= 2) listOf(instruction) else emptyList() })
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 0), null, execution) { _, _ -> }
        assertThat(models.requests[1].messages.last().content).isEqualTo("核对配置文件")
        assertThat(models.requests.last().messages.count { it.content == "核对配置文件" }).isEqualTo(1)
        val event = result.events.single { it.kind == SubagentEvent.Kind.PARENT_MESSAGE }
        assertThat(event.by).isEqualTo(SubagentEvent.Actor.PARENT)
        assertThat(event.at).isEqualTo(22L)
        assertThat(saved.last().consumedInstructionIds).contains("inbox-1")
        assertThat(saved.last().messages.last().content).isEqualTo("完成")
        assertThat(saved.all { it.pendingInstructions.isEmpty() }).isTrue()
    }

    @Test
    fun `结论返回期间收到的指令在完成前进入下一步`() = runTest {
        var inbox = emptyList<SubagentInstruction>()
        var requests = 0
        val messages = mutableListOf<List<ProtocolMessage>>()
        val models = SubagentModelClient { _, history, _, _, _, _ -> flow {
            messages += history.toList()
            if (++requests == 1) {
                inbox = listOf(SubagentInstruction("追加核对", id = "late-1"))
                emit(text("初次结论"))
            } else emit(text("追加指令已完成"))
            emit(StreamChunk.Completed(CompletionReason.END_TURN))
        } }
        val result = runner(models, FakeHost(emptyList())).run(parent(), session("auto"), "step", run(maxSteps = 0), null,
            SubagentExecution(drainInbox = { inbox })) { _, _ -> }
        assertThat(requests).isEqualTo(2)
        assertThat(messages.last().last().content).isEqualTo("追加核对")
        assertThat(result.result).isEqualTo("追加指令已完成")
    }

    @Test
    fun `软停止至多两正常步后只允许最终结论且保持收尾状态`() = runTest {
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}")), listOf(toolCall("c2", "read_file", "{}")), listOf(text("阶段结论")))
        val states = mutableListOf<SubagentRun>()
        val saved = mutableListOf<SubagentExecutionState>()
        val instruction = SubagentInstruction("已经取得足够证据", wrapUp = true, at = 3L, id = "wrap-1")
        val execution = SubagentExecution(save = { saved += it }, drainInbox = { listOf(instruction) })
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 0), null, execution) { run, _ -> states += run }
        assertThat(models.requests.take(2).all { it.tools.isNotEmpty() }).isTrue()
        assertThat(models.requests.last().tools).isEmpty()
        assertThat(models.requests.first().messages.last().content).contains("停止当前工作，交回阶段结论")
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(result.events.single { it.kind == SubagentEvent.Kind.WRAP_UP }.by).isEqualTo(SubagentEvent.Actor.PARENT)
        assertThat(states.filter { it.status.isActive }.all { it.status == SubagentStatus.WRAPPING_UP }).isTrue()
        assertThat(saved.last().wrapUpAfterStep).isEqualTo(2)
    }

    @Test
    fun `完整工具结果和协议思考状态持久化并从原上下文恢复`() = runTest {
        val full = "原始工具正文".repeat(7_000)
        val host = FakeHost(listOf(read)).apply { resultContent = full }
        val replay = ReasoningReplay.ChatField("reasoning_content")
        var saved: SubagentExecutionState? = null
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}"), StreamChunk.ReasoningState(replay)), listOf(text("初次结论")))
        val first = runner(models, host).run(parent(), session("auto"), "step", run(maxSteps = 0), null, SubagentExecution(save = { saved = it })) { _, _ -> }
        assertThat(saved!!.messages.single { it.role == "tool" }.content).isEqualTo(full)
        assertThat(first.events.single { it.kind == SubagentEvent.Kind.TOOL_RESULT }.text.length).isEqualTo(SubagentLimits.MAX_EVENT_CHARS)
        assertThat(saved!!.messages.first { it.role == "assistant" }.reasoningReplay).isEqualTo(replay)
        val encoded = kotlinx.serialization.json.Json.encodeToString(SubagentExecutionState.serializer(), saved!!)
        val restored = kotlinx.serialization.json.Json.decodeFromString(SubagentExecutionState.serializer(), encoded)
        val resumed = ScriptedModels(listOf(text("补充结论")))
        val instruction = SubagentInstruction("补充核对", id = "resume-1")
        val result = runner(resumed, host).run(parent(), session("auto"), "step", first.copy(status = SubagentStatus.RUNNING, maxTurns = 1), null,
            SubagentExecution(load = { restored }, save = { saved = it }, drainInbox = { listOf(instruction) }, contextWindow = { 128_000 }, resume = true)) { _, _ -> }
        assertThat(resumed.requests.single().messages.map { it.content }).containsAtLeast(full, "初次结论", "补充核对")
        assertThat(resumed.requests.single().tools).isNotEmpty()
        assertThat(result.result).isEqualTo("补充结论")
        assertThat(result.step).isEqualTo(3)
    }

    @Test
    fun `接近上下文窗口只压缩发送副本的较早工具结果并保持配对`() = runTest {
        val full = "a".repeat(40_000)
        val history = listOf(ProtocolMessage("system", "system"), ProtocolMessage("user", "任务")) + (1..4).flatMap {
            listOf(ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("c$it", "read_file", "{}"))), ProtocolMessage("tool", if (it == 1) full else "ok", toolCallId = "c$it", name = "read_file"))
        }
        val models = ScriptedModels(listOf(text("完成")))
        var saved: SubagentExecutionState? = null
        runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 0), null,
            SubagentExecution(load = { SubagentExecutionState(messages = history, completedSteps = 4) }, save = { saved = it }, contextWindow = { 12_000 })) { _, _ -> }
        val sent = models.requests.single().messages
        assertThat(sent.single { it.toolCallId == "c1" }.content.length).isLessThan(full.length)
        assertThat(sent.single { it.toolCallId == "c1" }.content).contains("original_chars=40000")
        assertThat(saved!!.messages.single { it.toolCallId == "c1" }.content).isEqualTo(full)
        assertThat(sent.flatMap { it.toolCalls.orEmpty().map(ProtocolToolCall::id) }).containsExactlyElementsIn(sent.filter { it.role == "tool" }.map { it.toolCallId })
    }

    @Test
    fun `最近三轮巨型结果也逐步折叠且请求与输出总量不越窗`() = runTest {
        val full = "完整工具正文".repeat(6_000)
        val history = listOf(ProtocolMessage("system", "system"), ProtocolMessage("user", "任务")) + (1..3).flatMap {
            listOf(ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("c$it", "read_file", "{}"))),
                ProtocolMessage("tool", full, toolCallId = "c$it", name = "read_file"))
        }
        val models = ScriptedModels(listOf(text("完成")))
        var saved: SubagentExecutionState? = null
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 0), null,
            SubagentExecution(load = { SubagentExecutionState(messages = history) }, save = { saved = it }, contextWindow = { 4_096 })) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        val request = models.requests.single()
        val input = request.messages.sumOf { ApproximateTokenEstimator.estimate(it) } + request.tools.sumOf {
            ApproximateTokenEstimator.estimate(it.function.name + it.function.description + it.function.parameters)
        }
        assertThat(input + request.maxOutputTokens).isAtMost(4_096)
        assertThat(request.maxOutputTokens).isAtMost(1_024)
        assertThat(request.messages.flatMap { it.toolCalls.orEmpty().map(ProtocolToolCall::id) })
            .containsExactlyElementsIn(request.messages.filter { it.role == "tool" }.map { it.toolCallId })
        assertThat(saved!!.messages.filter { it.role == "tool" }.map { it.content }).containsExactly(full, full, full)
    }

    @Test
    fun `历史助手正文超窗时只折叠请求副本`() = runTest {
        val full = "历史助手正文".repeat(5_000)
        val history = listOf(ProtocolMessage("system", "system"), ProtocolMessage("user", "最初任务"),
            ProtocolMessage("assistant", full, reasoning = full), ProtocolMessage("user", "当前指令"))
        val models = ScriptedModels(listOf(text("完成")))
        var saved: SubagentExecutionState? = null
        val result = runner(models, FakeHost(emptyList())).run(parent(), session("auto"), "step", run(maxSteps = 0), null,
            SubagentExecution(load = { SubagentExecutionState(messages = history) }, save = { saved = it }, contextWindow = { 4_096 })) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(models.requests.single().messages.sumOf { ApproximateTokenEstimator.estimate(it) } + models.requests.single().maxOutputTokens).isAtMost(4_096)
        assertThat(models.requests.single().messages.last().content).isEqualTo("当前指令")
        assertThat(saved!!.messages.first { it.role == "assistant" }.content).isEqualTo(full)
        assertThat(saved!!.messages.first { it.role == "assistant" }.reasoning).isEqualTo(full)
    }

    @Test
    fun `巨型工具参数整轮折叠时不删除同id后续轮的配对结果`() = runTest {
        val arguments = """{"text":"${"完整参数".repeat(3_000)}"}"""
        val history = listOf(ProtocolMessage("system", "system"), ProtocolMessage("user", "任务"),
            ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("same", "read_file", arguments))),
            ProtocolMessage("tool", "早期结果", toolCallId = "same", name = "read_file"),
            ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("same", "read_file", "{}"))),
            ProtocolMessage("tool", "近期结果", toolCallId = "same", name = "read_file"))
        val models = ScriptedModels(listOf(text("完成")))
        var saved: SubagentExecutionState? = null
        runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 0), null,
            SubagentExecution(load = { SubagentExecutionState(messages = history) }, save = { saved = it }, contextWindow = { 4_096 })) { _, _ -> }
        val sent = models.requests.single().messages
        assertThat(sent.flatMap { it.toolCalls.orEmpty().map(ProtocolToolCall::id) }).containsExactly("same")
        assertThat(sent.filter { it.role == "tool" }.map { it.content }).containsExactly("近期结果")
        assertThat(saved!!.messages.first { !it.toolCalls.isNullOrEmpty() }.toolCalls!!.single().arguments).isEqualTo(arguments)
    }

    @Test
    fun `必要系统和当前指令放不下时明确失败且不发模型请求`() = runTest {
        val models = ScriptedModels(listOf(text("不应调用")))
        val result = runner(models, FakeHost(emptyList())).run(parent(), session("auto"), "step", run(maxSteps = 0).copy(prompt = "当前必要指令".repeat(2_000)), null,
            SubagentExecution(contextWindow = { 4_096 })) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(result.error).contains("上下文")
        assertThat(models.requests).isEmpty()
    }

    @Test
    fun `恢复中断的工具调用补充未知结果且不自动重做工具`() = runTest {
        val history = listOf(ProtocolMessage("system", "system"), ProtocolMessage("user", "任务"),
            ProtocolMessage("assistant", "", toolCalls = listOf(ProtocolToolCall("c1", "read_file", "{}"))))
        val host = FakeHost(listOf(read))
        val models = ScriptedModels(listOf(text("说明中断状态")))
        runner(models, host).run(parent(), session("auto"), "step", run(maxSteps = 0), null,
            SubagentExecution(load = { SubagentExecutionState(messages = history) }, resume = true)) { _, _ -> }
        assertThat(models.requests.single().messages.last().toolCallId).isEqualTo("c1")
        assertThat(models.requests.single().messages.last().content).contains("状态未知")
        assertThat(host.executed).isEmpty()
    }

    @Test
    fun `子代理只提供主会话读取发送并隐藏派发停止等待及控制别名`() = runTest {
        val controls = listOf("agent_wait", "session_read", "session_send", "session_search", "session_stop", "session_list", "session_branch")
        val host = FakeHost(listOf(read) + controls.map { tool(it, ToolRisk.SAFE_READ) } + tool("control_alias", ToolRisk.SAFE_READ).copy(runtimeToolId = "session_send"))
        val models = ScriptedModels(listOf(text("完成")))
        runner(models, host).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(models.requests.single().tools.map { it.function.name }).containsExactly("read_file", "session_read", "session_send", "session_search")
    }

    @Test fun `子代理隐藏提问工具并对误调用返回可继续的结构化错误`() = runTest {
        val host = FakeHost(listOf(read, tool("ask_user", ToolRisk.SAFE_READ)))
        val models = ScriptedModels(listOf(toolCall("q", "ask_user", "{}")), listOf(text("按默认方案完成")))
        val result = runner(models, host).run(parent(), session("full_access"), "step", run(), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(host.executed).isEmpty()
        assertThat(models.requests.all { it.tools.none { tool -> tool.function.name == "ask_user" } }).isTrue()
        val failure = kotlinx.serialization.json.Json.parseToJsonElement(models.requests.last().messages.last().content!!)
            as kotlinx.serialization.json.JsonObject
        assertThat(failure["error"].toString()).isEqualTo("\"ASK_USER_UNAVAILABLE\"")
        assertThat(failure["suggestion"].toString()).contains("合理默认")
    }
    @Test
    fun `生产子代理以父会话步骤和索引派生稳定身份并覆盖工具续轮`() = kotlinx.coroutines.runBlocking {
        com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint().use { endpoint ->
            val models = RoutedSubagentModelClient(com.promenar.infinity.data.generation.ChatProviderRouteGate(endpoint.router)) { error("cloud") }
            val host = FakeHost(listOf(read))
            suspend fun execute(step: String, index: Int, parentId: String = "parent"): List<String?> {
                var round = 0
                endpoint.requests.clear()
                endpoint.reply = {
                    io.ktor.http.HttpStatusCode.OK to if (round++ == 0)
                        com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.toolStream()
                    else com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.textStream("done")
                }
                val context = object : SkillExecutionContext by parent() { override val sessionId = parentId }
                val result = runner(models, host).run(context, { sessionOf("auto").copy(id = parentId) }, step,
                    run().copy(index = index, modelId = com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.MODEL), null) { _, _ -> }
                assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
                assertThat(endpoint.requests).hasSize(2)
                return endpoint.requests.map { it.headers["x-opencode-session"] }
            }
            val first = execute("step-1", 0)
            val firstBodies = endpoint.requests.map { com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.body(it) }
            assertThat(first).containsExactly(endpoint.identifier("subagent:parent:step-1:0"), endpoint.identifier("subagent:parent:step-1:0"))
            assertThat(first.first()).isNotEqualTo(endpoint.identifier("parent"))
            assertThat(execute("step-1", 0)).isEqualTo(first)
            assertThat(execute("step-2", 0).first()).isNotEqualTo(first.first())
            assertThat(execute("step-1", 1).first()).isNotEqualTo(first.first())
            assertThat(execute("step-1", 0, "other-parent").first()).isNotEqualTo(first.first())
            endpoint.enabled = false
            assertThat(execute("step-1", 0)).containsExactly(null, null)
            assertThat(endpoint.requests.map { com.promenar.infinity.data.remote.SessionHeaderWiringEndpoint.body(it) }).isEqualTo(firstBodies)
            assertThat(endpoint.requests.none { it.headers["User-Agent"]?.startsWith("Infinity-Deck/") == true }).isTrue()
        }
    }

    private val read = tool("read_file", ToolRisk.SAFE_READ)
    private val shell = tool("shell_exec", ToolRisk.PROCESS)
    private val plan = tool("update_plan", ToolRisk.SESSION_STATE)
    private val agentTask = tool(SubagentLimits.TOOL_NAME, ToolRisk.SESSION_STATE)

    @Test
    fun `两档全部类别与生成链路审批一致且不读取记忆授权`() = runTest {
        listOf("workspace", "full_access").forEach { mode ->
            com.promenar.infinity.domain.tool.permissionPolicyCases.forEach { case ->
                val broker = SubagentApprovalBroker()
                val host = FakeHost(listOf(tool(case.tool, case.risk)))
                val models = ScriptedModels(listOf(toolCall("c1", case.tool, case.arguments)), listOf(text("完成")))
                val task = launch {
                    val result = runner(models, host, broker).run(parent(), session(mode), "step", run(), null) { _, _ -> }
                    assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
                }
                runCurrent()
                val needsApproval = case.alwaysApprove || case.command && mode == "workspace"
                assertThat(broker.pending.value.size).isEqualTo(if (needsApproval) 1 else 0)
                if (needsApproval) {
                    assertThat(host.executed).isEmpty()
                    broker.resolve(broker.pending.value.single().id, approved = true, remember = true)
                }
                task.join()
                assertThat(host.executed.map { it.first }).containsExactly(case.tool)
                assertThat(models.requests.first().messages.first().content).contains(
                    if (mode == "workspace") "requires user approval" else "without per-command approval",
                )
            }
        }
    }

    @Test
    fun `工具集不含 agent_task 与会话状态工具，只读档只保留读取工具`() {
        val parent = listOf(read, shell, plan, agentTask)
        assertThat(SubagentLimits.toolset(parent, SubagentToolProfile.INHERIT).map { it.function.name })
            .containsExactly("read_file", "shell_exec")
        assertThat(SubagentLimits.toolset(parent, SubagentToolProfile.READ_ONLY).map { it.function.name })
            .containsExactly("read_file")
    }

    @Test
    fun `调用工具后给出结论，模型只看到受限工具集，用量逐轮累计`() = runTest {
        val models = ScriptedModels(
            listOf(text("先读文件"), toolCall("c1", "read_file", """{"path":"/a.txt"}"""), usage(100, 20)),
            listOf(text("结论：a.txt 内容是 hello"), usage(150, 30)),
        )
        val host = FakeHost(listOf(read, shell, agentTask, plan))
        val published = mutableListOf<SubagentRun>()

        val result = runner(models, host).run(parent(), session("auto"), "step-1", run(), null) { run, _ -> published += run }

        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(result.result).isEqualTo("结论：a.txt 内容是 hello")
        assertThat(models.requests.first().tools.map { it.function.name }).containsExactly("read_file", "shell_exec")
        assertThat(models.requests.last().messages.map { it.role }).containsExactly("system", "user", "assistant", "tool").inOrder()
        assertThat(models.requests.last().messages.last().content).isEqualTo("read:/a.txt")
        assertThat(result.totalTokens).isEqualTo(300)
        assertThat(result.events.map { it.kind }).containsExactly(SubagentEvent.Kind.TEXT, SubagentEvent.Kind.TOOL_CALL, SubagentEvent.Kind.TOOL_RESULT).inOrder()
        // 最终正文只出现在结果区，不在时间线重复。
        assertThat(result.events.first().text).isEqualTo("先读文件")
        assertThat(published.last()).isEqualTo(result)
    }

    @Test
    fun `完全访问模式执行命令工具不出现审批`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", """{"command":"ls"}""")), listOf(text("done")))

        val result = runner(models, host, broker).run(parent(), session("auto"), "step", run(), null) { _, _ -> }

        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(host.executed.single().second.isApprovalBoundExecution).isFalse()
        assertThat(broker.pending.value).isEmpty()
    }

    @Test
    fun `工作区模式逐条审批且记住标志不会产生授权`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", """{"command":"ls"}""")), listOf(text("done")))
        val statuses = mutableListOf<SubagentStatus>()
        val job = launch {
            val result = runner(models, host, broker).run(parent(), session("semi"), "step", run(), null) { run, _ -> statuses += run.status }
            assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        }
        runCurrent()
        val request = broker.pending.value.single()
        assertThat(request.sessionId).isEqualTo("parent")
        assertThat(request.toolName).isEqualTo("shell_exec")
        assertThat(request.subagentDescription).isEqualTo("查找入口")
        assertThat(host.executed).isEmpty()
        assertThat(statuses).contains(SubagentStatus.WAITING_APPROVAL)

        broker.resolve(request.id, approved = true, remember = true)
        job.join()

        assertThat(host.remembered).isEmpty()
        val context = host.executed.single().second
        assertThat(context.isApprovalBoundExecution).isTrue()
        assertThat(context.approvedExecutionTarget).isEqualTo(TARGET)
        assertThat(context.sessionId).isEqualTo("parent")
    }

    @Test
    fun `已有记忆授权不跳过审批且拒绝只影响当前调用`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell), granted = true)
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", "{}")), listOf(text("换了方式完成")))
        val job = launch {
            val result = runner(models, host, broker).run(parent(), session("workspace"), "step", run(), null) { _, _ -> }
            assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        }
        runCurrent()
        broker.resolve(broker.pending.value.single().id, approved = false, remember = true)
        job.join()
        assertThat(host.executed).isEmpty()
        assertThat(models.requests.last().messages.last().content).isEqualTo(SubagentRunner.DENIED)
    }

    @Test
    fun `审批 10 分钟内未处理即以失败交回`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", "{}")))
        var result: SubagentRun? = null
        val job = launch { result = runner(models, host, broker).run(parent(), session("semi"), "step", run(), null) { _, _ -> } }
        runCurrent()
        assertThat(broker.pending.value).hasSize(1)

        advanceTimeBy(SubagentLimits.APPROVAL_TIMEOUT_MS + 1)
        job.join()

        assertThat(result!!.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(result!!.error).isEqualTo(SubagentRunner.APPROVAL_TIMEOUT)
        assertThat(broker.pending.value).isEmpty()
        assertThat(host.executed).isEmpty()
    }

    @Test
    fun `执行协程取消时停止模型请求并记录为取消`() = runTest {
        val started = CompletableDeferred<Unit>()
        val models = SubagentModelClient { _, _, _, _, _, _ -> flow { started.complete(Unit); awaitCancellation() } }
        val published = mutableListOf<SubagentRun>()
        val job = launch { runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(), null) { run, _ -> published += run } }
        started.await()

        job.cancel()
        job.join()

        assertThat(job.isCancelled).isTrue()
        assertThat(published.last().status).isEqualTo(SubagentStatus.CANCELLED)
        assertThat(published.last().endedAt).isNotNull()
    }

    @Test
    fun `取消模型请求也保存已报告用量且完成后不重复累计快照`() = runTest {
        val reported = CompletableDeferred<Unit>()
        val models = SubagentModelClient { _, _, _, _, _, _ -> flow {
            emit(usage(80, 20))
            emit(usage(100, 30))
            reported.complete(Unit)
            awaitCancellation()
        } }
        var saved: SubagentExecutionState? = null
        val published = mutableListOf<SubagentRun>()
        val initial = run().copy(inputTokens = 10, outputTokens = 5, totalTokens = 15)
        val job = launch {
            runner(models, FakeHost(emptyList())).run(parent(), session("auto"), "step", initial, null,
                SubagentExecution(save = { saved = it })) { run, _ -> published += run }
        }
        reported.await()
        job.cancel()
        job.join()
        assertThat(saved!!.inputTokens).isEqualTo(110L)
        assertThat(saved!!.outputTokens).isEqualTo(35L)
        assertThat(published.last().totalTokens).isEqualTo(145)

        val completed = ScriptedModels(listOf(text("完成"), usage(80, 20), usage(100, 30)))
        val result = runner(completed, FakeHost(emptyList())).run(parent(), session("auto"), "step", initial, null) { _, _ -> }
        assertThat(result.totalTokens).isEqualTo(145)
    }

    @Test
    fun `完整助手历史落盘前先持久化本轮最终用量`() = runTest {
        listOf(listOf(text("完整结论")), listOf(usage(100, 30), text("快照后的新增正文"))).forEach { chunks ->
            val persisted = mutableListOf<SubagentRun>()
            val checkpoints = mutableListOf<Pair<SubagentExecutionState, SubagentRun?>>()
            val initial = run().copy(inputTokens = 10, outputTokens = 5, totalTokens = 15)
            val result = runner(ScriptedModels(chunks), FakeHost(emptyList())).run(parent(), session("auto"), "step", initial, null,
                SubagentExecution(save = { state ->
                    if (state.messages.any { it.role == "assistant" }) checkpoints += state to persisted.lastOrNull()
                })) { run, persist -> if (persist) persisted += run }

            assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
            assertThat(checkpoints).isNotEmpty()
            checkpoints.forEach { (state, durableRun) ->
                assertThat(durableRun).isNotNull()
                assertThat(durableRun!!.inputTokens.toLong()).isEqualTo(state.inputTokens)
                assertThat(durableRun.outputTokens.toLong()).isEqualTo(state.outputTokens)
                assertThat(durableRun.totalTokens.toLong()).isEqualTo(state.inputTokens + state.outputTokens)
                assertThat(durableRun.tokensEstimated).isTrue()
            }
            assertThat(result.totalTokens).isEqualTo(checkpoints.last().second!!.totalTokens)
        }
    }

    @Test
    fun `未报告Usage时取消也估算已发送输入和已观察输出`() = runTest {
        val observed = CompletableDeferred<Unit>()
        var inputEstimate = 0
        val output = "已观察输出".repeat(30)
        val models = SubagentModelClient { _, messages, tools, _, _, _ -> flow {
            inputEstimate = messages.sumOf { ApproximateTokenEstimator.estimate(it) } + tools.sumOf {
                ApproximateTokenEstimator.estimate(it.function.name + it.function.description + it.function.parameters)
            }
            emit(text(output))
            observed.complete(Unit)
            awaitCancellation()
        } }
        var saved: SubagentExecutionState? = null
        val published = mutableListOf<SubagentRun>()
        val job = launch {
            runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(), null,
                SubagentExecution(save = { saved = it })) { run, _ -> published += run }
        }
        observed.await()
        job.cancel()
        job.join()
        assertThat(saved!!.inputTokens).isEqualTo(inputEstimate.toLong())
        assertThat(saved!!.outputTokens).isEqualTo(ApproximateTokenEstimator.estimate(output).toLong())
        assertThat(saved!!.tokensEstimated).isTrue()
        assertThat(published.last().totalTokens).isEqualTo(inputEstimate + ApproximateTokenEstimator.estimate(output))
        assertThat(published.last().tokensEstimated).isTrue()
    }

    @Test
    fun `取消时真实Usage只叠加快照之后未覆盖的输出估算`() = runTest {
        val observed = CompletableDeferred<Unit>()
        val suffix = "新增未覆盖正文"
        val models = SubagentModelClient { _, _, _, _, _, _ -> flow {
            emit(text("已覆盖正文"))
            emit(usage(80, 20))
            emit(usage(100, 30))
            emit(text(suffix))
            observed.complete(Unit)
            awaitCancellation()
        } }
        var saved: SubagentExecutionState? = null
        val initial = run().copy(inputTokens = 10, outputTokens = 5, totalTokens = 15)
        val job = launch {
            runner(models, FakeHost(emptyList())).run(parent(), session("auto"), "step", initial, null,
                SubagentExecution(save = { saved = it })) { _, _ -> }
        }
        observed.await()
        job.cancel()
        job.join()
        assertThat(saved!!.inputTokens).isEqualTo(110L)
        assertThat(saved!!.outputTokens).isEqualTo(35L + ApproximateTokenEstimator.estimate(suffix))
        assertThat(saved!!.tokensEstimated).isTrue()
    }

    @Test
    fun `工具已返回后取消也保留完整结果检查点`() = runTest {
        val full = "完整工具结果".repeat(6_000)
        val host = FakeHost(listOf(read)).apply { resultContent = full }
        val resultPublished = CompletableDeferred<Unit>()
        var saved: SubagentExecutionState? = null
        val job = launch {
            runner(ScriptedModels(listOf(toolCall("c1", "read_file", "{}"))), host).run(parent(), session("auto"), "step", run(), null,
                SubagentExecution(save = { saved = it })) { run, _ ->
                    if (run.events.lastOrNull()?.kind == SubagentEvent.Kind.TOOL_RESULT && run.status.isActive) {
                        resultPublished.complete(Unit)
                        throw kotlinx.coroutines.CancellationException("测试取消")
                    }
                }
        }
        resultPublished.await()
        job.join()
        assertThat(saved!!.messages.single { it.role == "tool" }.content).isEqualTo(full)
    }

    @Test
    fun `达到步数上限后不再提供工具并要求给出结论`() = runTest {
        val models = ScriptedModels(
            listOf(toolCall("c1", "read_file", "{}")),
            listOf(toolCall("c2", "read_file", "{}")),
            listOf(text("最终结论")),
        )
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxSteps = 2), null) { _, _ -> }

        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(result.result).isEqualTo("最终结论")
        assertThat(models.requests).hasSize(3)
        assertThat(models.requests.last().tools).isEmpty()
        assertThat(models.requests.last().messages.last().role).isEqualTo("user")
    }

    @Test
    fun `模型请求失败或没有结论时以失败交回且不暴露异常原文`() = runTest {
        val failing = SubagentModelClient { _, _, _, _, _, _ -> flow { throw java.io.IOException("https://secret.example/token=abc") } }
        val failed = runner(failing, FakeHost(emptyList())).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(failed.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(failed.error).doesNotContain("secret")

        val empty = runner(ScriptedModels(listOf(text("  "))), FakeHost(emptyList())).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(empty.error).isEqualTo(SubagentRunner.EMPTY_RESULT)
    }

    @Test
    fun `调用不在工具集内的工具时回报可用工具而不执行`() = runTest {
        val host = FakeHost(listOf(read, agentTask))
        val models = ScriptedModels(listOf(toolCall("c1", SubagentLimits.TOOL_NAME, "{}")), listOf(text("ok")))
        runner(models, host).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(host.executed).isEmpty()
        assertThat(models.requests.last().messages.last().content).contains("可用工具：read_file")
    }

    @Test
    fun `父会话切换到工作区后后续命令需要审批`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        var mode = "auto"
        val models = ScriptedModels(
            listOf(toolCall("c1", "shell_exec", "{}")),
            listOf(toolCall("c2", "shell_exec", "{}")),
            listOf(text("done")),
        )
        val job = launch {
            runner(models, host, broker).run(parent(), { sessionOf(mode) }, "step", run(), null) { run, _ ->
                // 第一次调用执行后，用户切换为工作区范围。
                if (run.events.count { it.kind == SubagentEvent.Kind.TOOL_RESULT } == 1) mode = "manual"
            }
        }
        runCurrent()
        assertThat(host.executed).hasSize(1)
        assertThat(broker.pending.value).hasSize(1)
        assertThat(models.requests[0].messages.first().content).contains("权限档：完全访问")
        assertThat(models.requests[1].messages.first().content).contains("权限档：工作区")
        broker.resolve(broker.pending.value.single().id, approved = false)
        job.join()
        assertThat(host.executed).hasSize(1)
    }

    @Test
    fun `审批期间目标变化时不执行也不写入白名单`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell))
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", "{}")), listOf(text("done")))
        val job = launch { runner(models, host, broker).run(parent(), session("semi"), "step", run(), null) { _, _ -> } }
        runCurrent()
        host.shellTarget = { """["shell_exec","rm -rf /"]""" }
        broker.resolve(broker.pending.value.single().id, approved = true, remember = true)
        job.join()
        assertThat(host.executed).isEmpty()
        assertThat(host.remembered).isEmpty()
        assertThat(models.requests.last().messages.last().content).isEqualTo(SubagentRunner.TARGET_CHANGED)
    }

    @Test
    fun `没有专门预览的工具在审批卡上展示完整参数`() = runTest {
        val broker = SubagentApprovalBroker()
        val host = FakeHost(listOf(shell)).apply { previewText = { "" } }
        val models = ScriptedModels(listOf(toolCall("c1", "shell_exec", """{"command":"curl https://example.com/?q=1"}""")), listOf(text("done")))
        val job = launch { runner(models, host, broker).run(parent(), session("manual"), "step", run(), null) { _, _ -> } }
        runCurrent()
        assertThat(broker.pending.value.single().preview).contains("\"command\": \"curl https://example.com/?q=1\"")
        broker.resolve(broker.pending.value.single().id, approved = false)
        job.join()
    }

    @Test
    fun `工具宿主内部出错时以失败交回，不抛出也不暴露原文`() = runTest {
        val host = FakeHost(listOf(read)).apply { failToolsOnCall = 3 }
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}")), listOf(text("done")))
        val result = runner(models, host).run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.FAILED)
        assertThat(result.error).isEqualTo("子代理内部错误（IOException）")
        assertThat(result.events.joinToString { it.text }).doesNotContain("/data/user")
    }

    @Test
    fun `父会话删除后子代理停止`() = runTest {
        var present = true
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}")), listOf(text("done")))
        val host = FakeHost(listOf(read))
        val result = runner(models, host).run(parent(), { if (present) sessionOf("auto").also { present = false } else null }, "step", run(), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.CANCELLED)
        assertThat(result.error).isEqualTo(SubagentRunner.SESSION_GONE)
        assertThat(host.executed).isEmpty()
    }

    @Test
    fun `达到本子代理的 token 上限后收尾`() = runTest {
        val models = ScriptedModels(listOf(toolCall("c1", "read_file", "{}"), usage(30, 10)), listOf(text("结论")))
        val result = runner(models, FakeHost(listOf(read))).run(parent(), session("auto"), "step", run(maxTokens = 40), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(models.requests[1].tools).isEmpty()
    }

    @Test fun `限流重试保留用量并丢弃失败输出`() = runTest {
        val requests = mutableListOf<List<ProtocolMessage>>()
        val waits = mutableListOf<Long>()
        var saved: SubagentExecutionState? = null
        val models = SubagentModelClient { _, messages, _, _, _, _ ->
            requests += messages.toList()
            if (requests.size == 1) flowOf(text("失败片段"), usage(10, 3), StreamChunk.Error(GenerationFailureCode.RATE_LIMIT, retryAfterSeconds = 7))
            else flowOf(text("完成"), usage(20, 4), StreamChunk.Completed(CompletionReason.END_TURN))
        }
        val result = SubagentRunner(models, FakeHost(emptyList()), SubagentApprovalBroker(), awaitRetry = { waits += it })
            .run(parent(), session("auto"), "step", run(), null, SubagentExecution(save = { saved = it })) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(waits).containsExactly(7000L)
        assertThat(requests[1]).isEqualTo(requests[0])
        assertThat(saved!!.messages.filter { it.role == "assistant" }.map { it.content }).containsExactly("完成")
        assertThat(saved!!.completedSteps).isEqualTo(1)
        assertThat(result.inputTokens).isEqualTo(30)
        assertThat(result.outputTokens).isEqualTo(7)
        assertThat(result.events.filter { it.text.contains("重试") }).hasSize(1)
        assertThat(result.events.any { it.text.contains("失败片段") || it.failed }).isFalse()
    }

    @Test fun `重试达到上限或不可重试时保持失败文字`() = runTest {
        for ((code, expectedRequests) in listOf(GenerationFailureCode.NETWORK to 3, GenerationFailureCode.AUTH to 1)) {
            var calls = 0
            val waits = mutableListOf<Long>()
            val models = SubagentModelClient { _, _, _, _, _, _ -> calls++; flowOf(StreamChunk.Error(code)) }
            val result = SubagentRunner(models, FakeHost(emptyList()), SubagentApprovalBroker(), maxStreamRetries = { 2 }, awaitRetry = { waits += it })
                .run(parent(), session("auto"), "step", run(), null) { _, _ -> }
            assertThat(calls).isEqualTo(expectedRequests)
            assertThat(waits).hasSize(expectedRequests - 1)
            assertThat(result.status).isEqualTo(SubagentStatus.FAILED)
            assertThat(result.error).isEqualTo("模型请求失败（${code.name}）")
        }
    }

    @Test fun `流意外结束按网络错误重试`() = runTest {
        var calls = 0
        val waits = mutableListOf<Long>()
        val models = SubagentModelClient { _, _, _, _, _, _ ->
            calls++
            if (calls == 1) flowOf(text("片段")) else flowOf(text("完成"), StreamChunk.Completed(CompletionReason.END_TURN))
        }
        val result = SubagentRunner(models, FakeHost(emptyList()), SubagentApprovalBroker(), awaitRetry = { waits += it })
            .run(parent(), session("auto"), "step", run(), null) { _, _ -> }
        assertThat(result.status).isEqualTo(SubagentStatus.COMPLETED)
        assertThat(calls).isEqualTo(2)
        assertThat(waits).containsExactly(1000L)
    }

    @Test fun `停止等待中的重试不会再次请求`() = runTest {
        var calls = 0
        val waiting = CompletableDeferred<Unit>()
        val published = mutableListOf<SubagentRun>()
        val models = SubagentModelClient { _, _, _, _, _, _ -> calls++; flowOf(StreamChunk.Error(GenerationFailureCode.NETWORK)) }
        val job = launch {
            SubagentRunner(models, FakeHost(emptyList()), SubagentApprovalBroker(), awaitRetry = { waiting.complete(Unit); awaitCancellation() })
                .run(parent(), session("auto"), "step", run(), null) { value, _ -> published += value }
        }
        waiting.await()
        job.cancel()
        job.join()
        assertThat(calls).isEqualTo(1)
        assertThat(published.last().status).isEqualTo(SubagentStatus.CANCELLED)
    }

    private fun runner(models: SubagentModelClient, host: SubagentToolHost, broker: SubagentApprovalBroker = SubagentApprovalBroker()) =
        SubagentRunner(models, host, broker, liveOutput = null, clock = { 1_000L })

    private fun run(maxSteps: Int = SubagentLimits.DEFAULT_MAX_STEPS, maxTokens: Int = SubagentLimits.MAX_TOTAL_TOKENS) = SubagentRun(
        index = 0, description = "查找入口", prompt = "找到应用入口", toolProfile = SubagentToolProfile.INHERIT,
        modelId = "provider::model", maxSteps = maxSteps, maxTokens = maxTokens, startedAt = 0,
    )

    private fun session(mode: String): suspend () -> Session? = { sessionOf(mode) }
    private fun sessionOf(mode: String) = Session(id = "parent", agentId = "agent", executionMode = mode, workspacePath = "/workspace")

    private fun parent() = object : SkillExecutionContext {
        override val sessionId = "parent"
        override val agentId = "agent"
        override val workspacePath = "/workspace"
        override val workspaceRootUuid = "root"
    }

    private fun tool(name: String, risk: ToolRisk) = ProtocolTool(
        function = ProtocolToolFunction(name, name, """{"type":"object","properties":{}}"""), risk = risk, runtimeToolId = name,
    )

    private fun text(content: String) = StreamChunk.TextDelta(content)
    private fun usage(input: Int, output: Int) = StreamChunk.Usage(ProtocolUsage(input = input, output = output, total = input + output))
    private fun toolCall(id: String, name: String, args: String) = StreamChunk.ToolCallDelta(id, name, args, 0)

    private class Request(val messages: List<ProtocolMessage>, val tools: List<ProtocolTool>, val maxOutputTokens: Int)

    /** 每次请求按顺序返回一轮脚本，并在末尾补上显式完成分片。 */
    private class ScriptedModels(vararg rounds: List<StreamChunk>) : SubagentModelClient {
        private val pending = ArrayDeque(rounds.toList())
        val requests = mutableListOf<Request>()
        override suspend fun stream(
            modelId: String,
            messages: List<ProtocolMessage>,
            tools: List<ProtocolTool>,
            maxOutputTokens: Int,
            reasoningEffort: com.promenar.infinity.data.model.params.ReasoningEffort?,
            sessionId: String,
        ): Flow<StreamChunk> {
            requests += Request(messages.toList(), tools, maxOutputTokens)
            val round = pending.removeFirstOrNull() ?: listOf(StreamChunk.TextDelta("（脚本结束）"))
            val reason = if (round.any { it is StreamChunk.ToolCallDelta }) CompletionReason.TOOL_CALLS else CompletionReason.END_TURN
            return flowOf(*(round + StreamChunk.Completed(reason)).toTypedArray())
        }
    }

    private class FakeHost(private val tools: List<ProtocolTool>, private val granted: Boolean = false) : SubagentToolHost {
        val executed = mutableListOf<Pair<String, SkillExecutionContext>>()
        val remembered = mutableListOf<Triple<String, String, String>>()
        var shellTarget: () -> String? = { TARGET }
        var previewText: (String) -> String = { "preview:$it" }
        var toolsCalls = 0
        var failToolsOnCall: Int? = null
        var resultContent: String? = null
        override suspend fun tools(session: Session): List<ProtocolTool> {
            if (++toolsCalls == failToolsOnCall) throw java.io.IOException("/data/user/0/secret/path")
            return tools
        }
        override suspend fun target(session: Session, tool: ProtocolTool, arguments: String) = if (tool.function.name == "shell_exec") shellTarget() else null
        override suspend fun isGranted(sessionId: String, toolName: String, target: String): Boolean = error("不得读取记忆授权")
        override suspend fun remember(sessionId: String, toolName: String, target: String) { error("不得保存记忆授权") }
        override fun preview(toolName: String, arguments: JsonObject) = previewText(toolName)
        override suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext): SubagentToolOutcome {
            executed += tool.function.name to context
            val path = (arguments["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            return SubagentToolOutcome(resultContent ?: if (path != null) "read:$path" else "ok", failed = false)
        }
    }

    private companion object {
        const val TARGET = """["shell_exec","ls"]"""
    }
}
