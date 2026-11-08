package com.promenar.infinity.data.subagent

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.TokenUsage
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.StreamChunk
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.AgentTaskSkill
import com.promenar.infinity.ui.chat.manager.skills.SubagentAddressHandler
import com.promenar.infinity.ui.chat.manager.skills.SessionSubagentSkill
import kotlinx.serialization.json.*
import com.promenar.infinity.ui.chat.subagentResultSummary
import java.nio.file.Files
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/** 子代理记录归档在父会话下：运行中可实时读取，结束后落盘，父会话删除时一并删除。 */
class SubagentRunStoreTest {
    private val root = Files.createTempDirectory("subagents").toFile()
    private val store = SubagentRunStore(root, UnconfinedTestDispatcher())

    @Test
    fun `结束后从磁盘读回，进程重启后仍在运行的记录显示为已中断`() = runTest {
        val running = batch(SubagentStatus.RUNNING)
        store.update(running, persist = true)
        assertThat(store.observe("s1", "step").first()).isEqualTo(running)

        val restarted = SubagentRunStore(root, UnconfinedTestDispatcher())
        assertThat(restarted.load("s1", "step")!!.runs.single().status).isEqualTo(SubagentStatus.INTERRUPTED)

        val done = batch(SubagentStatus.COMPLETED)
        store.finish(done)
        assertThat(store.snapshot("s1", "step")).isNull()
        assertThat(store.observe("s1", "step").first()).isEqualTo(done)
        assertThat(store.load("s1", "other")).isNull()
        assertThat(store.load("s2", "step")).isNull()
    }

    @Test
    fun `删除父会话时删除其全部子代理记录与待汇入用量`() = runTest {
        store.finish(batch(SubagentStatus.COMPLETED))
        store.finish(batch(SubagentStatus.COMPLETED, sessionId = "s2"))
        store.add("s1", TokenUsage(input = 10, output = 5, total = 15))

        store.deleteSession("s1")

        assertThat(store.load("s1", "step")).isNull()
        assertThat(store.load("s2", "step")).isNotNull()
        assertThat(store.drain("s1")).isNull()
        assertThat(root.walkTopDown().filter { it.isFile }.count()).isEqualTo(1)
    }

    @Test
    fun `用量按会话累加，汇入一次后清空`() {
        store.add("s1", TokenUsage(input = 10, output = 5, total = 15))
        store.add("s1", TokenUsage(input = 1, output = 1, total = 2, estimated = true))
        store.add("s1", TokenUsage())
        assertThat(store.drain("s1")).isEqualTo(TokenUsage(input = 11, output = 6, total = 17, estimated = true))
        assertThat(store.drain("s1")).isNull()
    }

    @Test
    fun `并行运行多个子代理，结束后归档并把用量记到父会话`() = runTest {
        val bothStarted = CompletableDeferred<Unit>()
        var started = 0
        val models = SubagentModelClient { _, messages, _, _, _, _ ->
            flow {
                // 两个子代理都开始请求后才返回，证明是并行而不是依次执行。
                if (++started == 2) bothStarted.complete(Unit)
                bothStarted.await()
                emit(StreamChunk.TextDelta("答案：" + messages.last().content))
                emit(StreamChunk.Usage(com.promenar.infinity.data.remote.protocol.ProtocolUsage(10, 5, 15)))
                emit(StreamChunk.Completed(CompletionReason.END_TURN))
            }
        }
        val launcher = launcher(models)
        val specs = listOf(
            SubagentSpec("任务一", "问题一", SubagentPreset.EXPLORER, SubagentToolProfile.READ_ONLY),
            SubagentSpec("任务二", "问题二", null, SubagentToolProfile.INHERIT),
        )

        val runs = launcher.launch(context(), "step", specs, background = false)

        assertThat(runs.map { it.result }).containsExactly("答案：问题一", "答案：问题二").inOrder()
        val archived = store.load("s1", "step")!!
        assertThat(archived.runs.map { it.status }).containsExactly(SubagentStatus.COMPLETED, SubagentStatus.COMPLETED)
        assertThat(archived.runs.first().preset).isEqualTo("explorer")
        assertThat(archived.runs.first().modelId).isEqualTo("provider::model")
        assertThat(archived.runs.sumOf { it.totalTokens }).isEqualTo(30)
        assertThat(store.drain("s1")).isNull()
    }

    @Test
    fun `agent_task 校验任务数量与必填字段，探索者档位不能被放宽`() {
        val skill = AgentTaskSkill { error("参数解析不应启动运行器") }
        fun parse(json: String) = skill.parse(Json.parseToJsonElement(json).jsonObject)

        assertThat(parse("""{"tasks":[]}""")).isNull()
        assertThat(parse("""{"tasks":[{"description":"a","prompt":" "}]}""")).isNull()
        assertThat(parse("""{"tasks":[${List(SubagentLimits.MAX_PARALLEL + 1) { """{"description":"a","prompt":"b"}""" }.joinToString(",")}]}""")).isNull()
        assertThat(parse("""{"tasks":[{"description":"a","prompt":"b","preset":"writer"}]}""")).isNull()
        val parsed = parse("""{"tasks":[{"description":"a","prompt":"b","preset":"explorer","tool_profile":"inherit"},{"description":"c","prompt":"d"}]}""")!!
        assertThat(parsed.map { it.toolProfile }).containsExactly(SubagentToolProfile.READ_ONLY, SubagentToolProfile.INHERIT).inOrder()
        assertThat(parsed.first().preset).isEqualTo(SubagentPreset.EXPLORER)
    }

    @Test
    fun `交回主 Agent 的结果按子代理分节并保留完整结论`() {
        val long = "x".repeat(SubagentLimits.MAX_RESULT_CHARS)
        val text = AgentTaskSkill.formatResults(
            listOf(
                run(0, SubagentStatus.COMPLETED).copy(result = long),
                run(1, SubagentStatus.FAILED).copy(error = SubagentRunner.APPROVAL_TIMEOUT),
            ),
        )
        assertThat(text).contains("## Subagent 1: 任务")
        assertThat(text).contains(long)
        assertThat(text).contains("Status: failed.")
        assertThat(text).contains(SubagentRunner.APPROVAL_TIMEOUT)
        assertThat(text.length).isGreaterThan(SubagentLimits.MAX_RESULT_CHARS)
    }

    @Test
    fun `卡片结论跳过 Markdown 标题取第一行正文`() {
        assertThat(subagentResultSummary("## 结论\n\n共 412 个测试。\n其余略")).isEqualTo("共 412 个测试。")
        assertThat(subagentResultSummary("# 只有标题")).isEqualTo("只有标题")
        assertThat(subagentResultSummary("  \n ")).isNull()
        assertThat(subagentResultSummary("###")).isNull()
        assertThat(subagentResultSummary("**未找到任何 Markdown 文件。**")).isEqualTo("未找到任何 Markdown 文件。")
        assertThat(subagentResultSummary("- [DIR] `/media`")).isEqualTo("[DIR] /media")
        assertThat(subagentResultSummary("1. 共 3 项")).isEqualTo("共 3 项")
    }

    @Test
    fun `父会话删除后收尾中的子代理不再写回记录与用量`() = runTest {
        store.deleteSession("s1")
        store.update(batch(SubagentStatus.RUNNING), persist = true)
        store.finish(batch(SubagentStatus.COMPLETED))
        store.add("s1", TokenUsage(input = 1, output = 1, total = 2))
        assertThat(store.load("s1", "step")).isNull()
        assertThat(store.snapshot("s1", "step")).isNull()
        assertThat(store.drain("s1")).isNull()
        assertThat(root.walkTopDown().filter { it.isFile }.count()).isEqualTo(0)
    }

    @Test
    fun `一个子代理出错不影响同批其它子代理交回结果`() = runTest {
        val host = object : SubagentToolHost by NoTools {
            override suspend fun tools(session: Session): List<ProtocolTool> = emptyList()
        }
        val models = SubagentModelClient { _, messages, _, _, _, _ ->
            flow {
                if (messages.last().content == "坏任务") throw IllegalStateException("https://secret.example/key")
                emit(StreamChunk.TextDelta("好结果"))
                emit(StreamChunk.Completed(CompletionReason.END_TURN))
            }
        }
        val launcher = SubagentLauncher(SubagentRunner(models, host, SubagentApprovalBroker(), clock = { 5L }), store, { id -> Session(id = id, agentId = "agent", executionMode = "auto") }, { "m" }, clock = { 5L }, applicationScope = backgroundScope)
        val runs = launcher.launch(context(), "step", listOf(
            SubagentSpec("a", "坏任务", null, SubagentToolProfile.INHERIT),
            SubagentSpec("b", "好任务", null, SubagentToolProfile.INHERIT),
        ), background = false)
        assertThat(runs.map { it.status }).containsExactly(SubagentStatus.FAILED, SubagentStatus.COMPLETED).inOrder()
        assertThat(runs.first().error).doesNotContain("secret")
        assertThat(runs.last().result).isEqualTo("好结果")
    }

    @Test
    fun `父轮取消时已派生子代理继续运行并由删除父会话清理`() = runTest {
        val models = SubagentModelClient { _, _, _, _, _, _ -> flow { kotlinx.coroutines.awaitCancellation() } }
        val launcher = launcher(models)
        val job = launch { launcher.launch(context(), "step", listOf(SubagentSpec("a", "p", null, SubagentToolProfile.INHERIT), SubagentSpec("b", "q", null, SubagentToolProfile.INHERIT))) }
        testScheduler.runCurrent()
        job.cancel()
        job.join()
        assertThat(store.snapshot("s1", "step")!!.runs.all { it.status.isActive }).isTrue()
        launcher.deleteSession("s1")
        assertThat(store.load("s1", "step")).isNull()
    }

    @Test
    fun `任务 token 上限按子代理数分摊，不超过单个子代理的固定上限`() {
        assertThat(SubagentLimits.tokenBudget(0, 3)).isEqualTo(SubagentLimits.MAX_TOTAL_TOKENS)
        assertThat(SubagentLimits.tokenBudget(90_000, 3)).isEqualTo(30_000)
        assertThat(SubagentLimits.tokenBudget(10_000_000, 2)).isEqualTo(SubagentLimits.MAX_TOTAL_TOKENS)
        assertThat(SubagentLimits.tokenBudget(2, 3)).isEqualTo(1)
    }

    @Test
    fun `逐次授权的目标不能记住`() = runTest {
        val broker = SubagentApprovalBroker()
        val oneShot = """["ssh_host","prod",{"oneShot":true}]"""
        val outcome = async { broker.request("s1", run(0, SubagentStatus.RUNNING), "ssh_host_save", com.promenar.infinity.domain.tool.ToolRisk.EXTERNAL_WRITE, oneShot, "") }
        testScheduler.runCurrent()
        val pending = broker.pending.value.single()
        assertThat(pending.canRemember).isFalse()
        broker.resolve(pending.id, approved = true, remember = true)
        assertThat(outcome.await()).isEqualTo(SubagentApprovalOutcome.APPROVED)
    }

    @Test fun `子代理有分页参数时读取检查点且默认输出保持状态格式`() = runTest {
        val stored = batch(SubagentStatus.COMPLETED).let { it.copy(runs = listOf(it.runs.single().copy(id = "sa_123abc"))) }
        store.finish(stored)
        store.saveExecution("s1", "step", "sa_123abc", SubagentExecutionState(messages = listOf(
            ProtocolMessage("user", "第一条"), ProtocolMessage("assistant", "第二条"), ProtocolMessage("tool", "第三条"))))
        val model = SubagentModelClient { _, _, _, _, _, _ -> flow { } }
        val launcher = launcher(model)
        val skill = SessionSubagentSkill("session_read", listOf(SubagentAddressHandler({ launcher })))
        val plain = skill.execute(buildJsonObject { put("id", "sa_123abc") }, context())
        assertThat(plain.content).contains("Recent events:")
        val page = skill.execute(buildJsonObject { put("id", "sa_123abc"); put("after", "0"); put("limit", 1) }, context())
        val body = Json.parseToJsonElement(page.content).jsonObject
        assertThat(body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonPrimitive.content).isEqualTo("第二条")
        assertThat(body["has_more_before"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(body["has_more_after"]!!.jsonPrimitive.boolean).isTrue()
        assertThat(skill.execute(buildJsonObject { put("id", "sa_123abc"); put("before", "99") }, context()).status).isEqualTo("error")
    }

    private fun kotlinx.coroutines.test.TestScope.launcher(models: SubagentModelClient) = SubagentLauncher(
        runner = SubagentRunner(models, NoTools, SubagentApprovalBroker(), clock = { 5L }),
        store = store,
        session = { id -> Session(id = id, agentId = "agent", executionMode = "auto") },
        modelFor = { "provider::model" },
        applicationScope = backgroundScope,
        clock = { 5L },
    )

    private fun context() = object : SkillExecutionContext {
        override val sessionId = "s1"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
    }

    private fun batch(status: SubagentStatus, sessionId: String = "s1") = SubagentBatch(sessionId, "step", listOf(run(0, status)))

    private fun run(index: Int, status: SubagentStatus) = SubagentRun(
        index = index, description = "任务", prompt = "提示", toolProfile = SubagentToolProfile.INHERIT,
        modelId = "m", maxSteps = 12, status = status, startedAt = 1,
    )

    private object NoTools : SubagentToolHost {
        override suspend fun tools(session: Session) = emptyList<ProtocolTool>()
        override suspend fun target(session: Session, tool: ProtocolTool, arguments: String): String? = null
        override suspend fun isGranted(sessionId: String, toolName: String, target: String) = false
        override suspend fun remember(sessionId: String, toolName: String, target: String) = Unit
        override fun preview(toolName: String, arguments: JsonObject) = ""
        override suspend fun execute(tool: ProtocolTool, arguments: JsonObject, context: SkillExecutionContext) = SubagentToolOutcome("", false)
    }
}
