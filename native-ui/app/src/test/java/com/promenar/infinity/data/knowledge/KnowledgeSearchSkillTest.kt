package com.promenar.infinity.data.knowledge

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.*
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.ui.chat.manager.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.skills.*
import com.promenar.infinity.data.rag.*
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Test

class KnowledgeSearchSkillTest {
    private var config = AgentRetrievalConfig(retrievalMode = AgentRetrievalMode.BOTH)
    private var session = Session("s", "a", workspaceRootUuid = "root", ragOptions = RagOptions(enableKnowledgeGraph = true),
        messages = listOf(Message(id = "u", role = MessageRole.USER, content = "q", createdAt = 1),
            Message(id = "m", role = MessageRole.ASSISTANT, content = "", createdAt = 2)))
    private val turns = KnowledgeTurnState()
    private val catalog = mockk<KnowledgeCatalogSource> {
        coEvery { documentLocation(any(), any()) } returns null
    }
    private val options = mutableListOf<RagOptions>()
    private var refs = listOf(RagReference(id = "r", content = "命中正文", source = "文档", kind = RagReferenceKind.DOCUMENT, documentId = "doc", score = .8f))
    private var trace: RagRetrievalTrace? = null
    private var resolvedScope: ResolvedRetrievalScope? = null
    private var graphScope: ResolvedRetrievalScope? = null
    private var automaticCalls = 0
    private var toolCalls = 0
    private var kgCalls = 0
    private val rag = object : RagProvider {
        override suspend fun retrieveContext(query: String, sessionId: String, options: RagOptions,
            onProgress: ((String, Int, String?) -> Unit)?): RagRetrievalResult { automaticCalls++; error("工具调用了自动入口") }
        override suspend fun retrieveForTool(query: String, sessionId: String, options: RagOptions,
            onProgress: ((String, Int, String?) -> Unit)?): RagRetrievalResult {
            toolCalls++; this@KnowledgeSearchSkillTest.options += options
            return RagRetrievalResult("context", refs, RagUsage(10, true), trace, resolvedScope)
        }
    }
    private val kg = object : KgProvider {
        override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult? = error("工具触发了抽取")
        override suspend fun retrieveForTool(query: String, sessionId: String, topKResults: List<RagReference>, options: RagOptions): KgContextResult {
            kgCalls++; return KgContextResult("", listOf(KgPath()))
        }
        override suspend fun retrieveWithScope(query: String, sessionId: String, topKResults: List<RagReference>,
            options: RagOptions, scope: ResolvedRetrievalScope?, forTool: Boolean): KgContextResult {
            graphScope = scope
            return retrieveForTool(query, sessionId, topKResults, options)
        }
        override suspend fun retrieveForToolAt(query: String, sessionId: String, topKResults: List<RagReference>,
            options: RagOptions, scope: ResolvedRetrievalScope?, asOf: com.promenar.infinity.data.rag.KgAsOf?): KgContextResult {
            graphAsOf = asOf
            return retrieveWithScope(query, sessionId, topKResults, options, scope, true)
        }
    }
    private var graphAsOf: com.promenar.infinity.data.rag.KgAsOf? = null
    private fun context(step: String = "step") = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspaceRootUuid = "root"
        override val workspacePath: String? = null
        override val assistantMessageId = "m"
        override val toolStepId = step
    }
    private fun skill() = KnowledgeSearchSkill({ session }, { config }, catalog, rag, kg, turns)
    private suspend fun search(step: String = "step", args: String = """{"query":"完整问题"}""") = skill().execute(Json.parseToJsonElement(args).jsonObject, context(step))
    private fun ToolResult.body() = json.decodeFromString<KnowledgeSearchData>(requireNotNull(data))

    @Test fun `工具检索把向量检索同一生效范围交给图谱`() = runTest {
        val scope = ResolvedRetrievalScope("s", ConversationReach.Session("s"), ConversationReach.Session("s"),
            DocumentReach.Scoped(emptySet(), setOf("doc")), ResolvedGraphBinding("work:a", GraphBindingMode.READ_ONLY))
        resolvedScope = scope
        assertThat(search().status).isEqualTo("success")
        assertThat(graphScope).isSameInstanceAs(scope)
    }

    @Test fun `篇目时间点交给图谱且非法序号直接拒绝`() = runTest {
        assertThat(search(args = """{"query":"完整问题","as_of":3}""").status).isEqualTo("success")
        assertThat(graphAsOf).isEqualTo(com.promenar.infinity.data.rag.KgAsOf.Ordinal(3))
        val invalid = search(step = "step-2", args = """{"query":"完整问题","as_of":0}""")
        assertThat(invalid.status).isEqualTo("error")
        assertThat(invalid.content).contains("as_of")
        assertThat(toolCalls).isEqualTo(1)
    }

    @Test fun automaticCitationMappingSurvivesColdRestore() {
        session = session.copy(messages = session.messages.map {
            if (it.id == "m") it.copy(ragReferences = listOf(RagReference(id = "auto", citationId = "K4"))) else it
        })
        val restored = KnowledgeTurnState()
        restored.seed(session, "u")
        assertThat(restored.citationMapping("s", "u")).containsEntry("auto", "K4")
        assertThat(restored.citation("s", "u", "next")).isEqualTo("K5")
    }

    @Test fun `工具仅使用工具入口且保留图谱与用量`() = runTest {
        val result = search()
        assertThat(result.status).isEqualTo("success")
        assertThat(result.body().references).isEqualTo(refs)
        assertThat(result.body().kgPaths).hasSize(1)
        assertThat(automaticCalls).isEqualTo(0)
        assertThat(toolCalls).isEqualTo(1)
        assertThat(kgCalls).isEqualTo(1)
        assertThat(turns.usage("s", "u")).isEqualTo(RagUsage(10, true))
    }

    @Test fun `结果文本区分文档与历史对话并保留两位相关度`() = runTest {
        refs = listOf(
            RagReference(id = "d", content = "部署前检查配置。", source = "文档: 部署手册.md", kind = RagReferenceKind.DOCUMENT,
                documentId = "doc", chunkIndex = 12, score = .8234f),
            RagReference(id = "h", content = "讨论过接口迁移。", source = "对话记忆", kind = RagReferenceKind.MEMORY, score = .74f),
        )
        val content = search().content
        assertThat(content).contains("[K1] 文档《部署手册.md》 片段 12 · 相关度 0.82")
        assertThat(content).contains("[K2] 历史对话（对话记忆） · 相关度 0.74")
        assertThat(content).doesNotContain("文档: ")
    }

    @Test fun `来源关闭不会被请求打开且结果说明原因`() = runTest {
        session = session.copy(ragOptions = RagOptions(enableDocs = false, enableMemory = true))
        val result = search(args = """{"query":"q","sources":["documents","history"]}""")
        assertThat(options.single().enableDocs).isFalse()
        assertThat(options.single().enableMemory).isTrue()
        assertThat(result.body().disabledSources).containsExactly("documents")
        assertThat(result.content).contains("documents 已关闭")
        assertThat(result.body().sources).containsExactly("history")
        session = session.copy(ragOptions = RagOptions(enableDocs = false, enableMemory = false))
        assertThat(search("next").status).isEqualTo("success")
        assertThat(toolCalls).isEqualTo(1)
    }

    @Test fun `四类读取路径与OFF不提供申请提示`() = runTest {
        for (kind in KnowledgeDocumentLocationKind.entries) {
            val path = when (kind) {
                KnowledgeDocumentLocationKind.WORKSPACE -> "/file.md"
                KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED -> "/mnt/knowledge/file.md"
                else -> null
            }
            coEvery { catalog.documentLocation(any(), any()) } returns KnowledgeDocumentLocation("doc", "file.md", kind, path = path)
            val result = search(kind.name)
            assertThat(result.body().results.single().path).isEqualTo(path)
            assertThat(result.body().results.single().pathType).isEqualTo(kind)
            if (kind == KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED) assertThat(result.content).contains("request_knowledge_access")
        }
        config = config.copy(knowledgeFileAccess = KnowledgeFileAccess.OFF)
        coEvery { catalog.documentLocation(any(), any()) } returns KnowledgeDocumentLocation("doc", "file.md", KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED)
        val result = search("off")
        assertThat(result.content).doesNotContain("request_knowledge_access")
        assertThat(result.body().results.single().requiresAccess).isFalse()
    }

    @Test fun `同轮编号稳定BOTH不重复正文且下一轮重置次数`() = runTest {
        turns.seed(session, "u", refs)
        val first = search("1")
        assertThat(first.body().results.single().citationId).isEqualTo("K1")
        assertThat(first.content.lines().filter { it.startsWith("[K") }).containsExactly("[K1] 已在上方参考资料中")
        assertThat(first.content).doesNotContain("命中正文")
        refs = refs + RagReference("new", "新正文", "source")
        val second = search("2")
        assertThat(second.body().results.map { it.citationId }).containsExactly("K1", "K2").inOrder()
        (3..6).forEach { search(it.toString()) }
        val limited = search("7")
        assertThat(limited.status).isEqualTo("success")
        assertThat(limited.body().limitReached).isTrue()
        assertThat(limited.content).contains("基于已有结果")
        assertThat(toolCalls).isEqualTo(6)
        session = session.copy(messages = session.messages + Message(id = "u2", role = MessageRole.USER, content = "new", createdAt = 3) +
            Message(id = "m2", role = MessageRole.ASSISTANT, content = "", createdAt = 4))
        val newContext = object : SkillExecutionContext by context("8") { override val assistantMessageId = "m2" }
        assertThat(skill().execute(buildJsonObject { put("query", "q") }, newContext).body().limitReached).isFalse()
        assertThat(toolCalls).isEqualTo(7)
    }

    @Test fun `内部通知插入后续轮仍沿用用户父消息的编号与去重`() = runTest {
        val firstReference = RagReference("first", "第一条正文", "文档")
        turns.seed(session, "u", listOf(firstReference) + refs)
        assertThat(turns.citation("s", "u", "first")).isEqualTo("K1")
        assertThat(turns.citation("s", "u", "r")).isEqualTo("K2")
        session = session.copy(messages = session.messages +
            Message("tool", MessageRole.TOOL, "工具结果", toolCallId = "call") +
            Message("notice", MessageRole.USER, "任务通知", name = "system_trigger") +
            Message("continued", MessageRole.ASSISTANT, "", parentMessageId = "u"))
        val continued = object : SkillExecutionContext by context("continued-step") {
            override val assistantMessageId = "continued"
        }
        val result = skill().execute(buildJsonObject { put("query", "q") }, continued)
        assertThat(knowledgeTurnId(session, "continued")).isEqualTo("u")
        assertThat(result.body().results.single().citationId).isEqualTo("K2")
        assertThat(result.body().results.single().alreadyInContext).isTrue()
        assertThat(result.content.lines().filter { it.startsWith("[K") })
            .containsExactly("[K2] 已在上方参考资料中")
        assertThat(result.content).doesNotContain("命中正文")
    }

    @Test fun `系统触发的独立任务和无效父消息仍按用户边界区分轮次`() {
        session = session.copy(messages = session.messages +
            Message("notice", MessageRole.USER, "定时任务", name = "system_trigger") +
            Message("triggered", MessageRole.ASSISTANT, "", parentMessageId = "notice") +
            Message("invalid-parent", MessageRole.ASSISTANT, "", parentMessageId = "m"))
        assertThat(knowledgeTurnId(session, "triggered")).isEqualTo("notice")
        assertThat(knowledgeTurnId(session, "invalid-parent")).isEqualTo("notice")
        assertThat(knowledgeTurnId(session, "missing-assistant")).isEqualTo("notice")
    }

    @Test fun `缺失或未来用户父消息不改变既有回溯边界`() {
        session = session.copy(messages = session.messages +
            Message("missing-parent", MessageRole.ASSISTANT, "", parentMessageId = "missing") +
            Message("future-parent", MessageRole.ASSISTANT, "", parentMessageId = "future") +
            Message("future", MessageRole.USER, "未来用户消息"))
        assertThat(knowledgeTurnId(session, "missing-parent")).isEqualTo("u")
        assertThat(knowledgeTurnId(session, "future-parent")).isEqualTo("u")
        assertThat(knowledgeTurnId(session, "future")).isEqualTo("u")
    }

    @Test fun `冷恢复读取通知后的同轮工具记录且隔离通知独立任务`() {
        val sameTurn = KnowledgeSearchData("q", results = listOf(KnowledgeSearchHit("later", "K9")), usage = RagUsage(7, true))
        val independent = KnowledgeSearchData("q", results = listOf(KnowledgeSearchHit("other", "K80")), usage = RagUsage(99))
        session = session.copy(messages = session.messages.map { if (it.id == "m") it.copy(executionSteps = (1..5).map { n ->
            ExecutionStep("step$n", "tool_call", toolName = "knowledge_search", toolCallId = "c$n", timestamp = n.toLong())
        }) else it } +
            Message("notice", MessageRole.USER, "通知", name = "system_trigger") +
            Message("independent", MessageRole.ASSISTANT, "", parentMessageId = "notice", executionSteps = listOf(
                ExecutionStep("other", "tool_result", toolName = "knowledge_search", data = json.encodeToString(independent)))) +
            Message("denied-notice", MessageRole.TOOL, "用户未授权", name = "request_knowledge_access") +
            Message("continued", MessageRole.ASSISTANT, "", parentMessageId = "u", ragReferences = refs, executionSteps = listOf(
                ExecutionStep("step6", "tool_call", toolName = "knowledge_search", toolCallId = "c6"),
                ExecutionStep("result6", "tool_result", toolName = "knowledge_search", toolCallId = "c6", data = json.encodeToString(sameTurn)))))
        val recovered = KnowledgeTurnState()
        recovered.seed(session, "u")
        assertThat(recovered.citation("s", "u", "later")).isEqualTo("K9")
        assertThat(recovered.citation("s", "u", "fresh")).isEqualTo("K10")
        assertThat(recovered.usage("s", "u")).isEqualTo(RagUsage(7, true))
        assertThat(recovered.isInjected("s", "u", "r")).isTrue()
        assertThat(recovered.isDenied("s", "u")).isFalse()
        assertThat(recovered.consume("s", "u", "step7")).isFalse()
        session = session.copy(messages = session.messages +
            Message("denied-original", MessageRole.TOOL, "用户未授权", name = "request_knowledge_access"))
        recovered.seed(session, "u")
        assertThat(recovered.isDenied("s", "u")).isTrue()
    }

    @Test fun `恢复时当前调用已写入步骤不会重复计数`() = runTest {
        session = session.copy(messages = session.messages.map { if (it.id == "m") it.copy(executionSteps = (1..6).map { n ->
            ExecutionStep(id = "$n", type = "tool_call", toolName = "knowledge_search", toolCallId = "c$n", timestamp = n.toLong())
        }) else it })
        assertThat(search("6").body().limitReached).isFalse()
        assertThat(search("7").body().limitReached).isTrue()
    }

    @Test fun `无结果成功未配置嵌入模型返回配置引导并且限制最终条数`() = runTest {
        refs = emptyList()
        session = session.copy(ragOptions = RagOptions(enableKnowledgeGraph = false))
        assertThat(search("empty").body().empty).isTrue()
        trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_UNAVAILABLE)
        val unavailable = search("unavailable")
        assertThat(unavailable.status).isEqualTo("error")
        assertThat(unavailable.content).contains("配置嵌入模型")
        trace = null
        refs = (1..12).map { RagReference("$it", "text", "s") }
        assertThat(search("limit", """{"query":"q","limit":3}""").body().references).hasSize(3)
        assertThat(search("bad", """{"query":"q","limit":11}""").status).isEqualTo("error")
    }

    @Test fun `工具范围与自动入口解析一致并收紧智能体上限`() = runTest {
        session = session.copy(ragOptions = RagOptions(memoryScope = ConversationScope.ALL_AGENTS, graphScope = ConversationScope.ALL_AGENTS,
            documentScope = DocumentScope.ALL_INDEXED, enableKnowledgeGraph = true))
        config = config.copy(memoryPolicy = AgentMemoryPolicy(memoryCap = ConversationScope.SESSION, graphCap = ConversationScope.AGENT, documentCap = DocumentScope.WORKSPACE))
        search()
        assertThat(options.single()).isEqualTo(com.promenar.infinity.data.rag.resolveEffectiveRagOptions(session.ragOptions, memoryPolicy = config.memoryPolicy))
    }

    @Test fun `恢复引用编号和用量不混入下一轮拒绝且保留自动引用`() {
        val body = KnowledgeSearchData("q", references = refs, results = listOf(KnowledgeSearchHit("r", "K7")), usage = RagUsage(10, true))
        val stored = session.copy(messages = session.messages.map { if (it.id == "m") it.copy(ragReferences = refs,
            executionSteps = listOf(ExecutionStep(id = "res", type = "tool_result", toolName = "knowledge_search", toolCallId = "call", data = json.encodeToString(body), timestamp = 2))) else it } +
            Message(id = "later", role = MessageRole.USER, content = "next", createdAt = 3) +
            Message(id = "denied", role = MessageRole.TOOL, content = "用户未授权", name = "request_knowledge_access", createdAt = 4))
        turns.seed(stored, "u")
        assertThat(turns.citation("s", "u", "new")).isEqualTo("K8")
        assertThat(turns.usage("s", "u")).isEqualTo(RagUsage(10, true))
        assertThat(turns.isDenied("s", "u")).isFalse()
        assertThat(turns.isInjected("s", "u", "r")).isTrue()
    }

    @Test fun `新增访问风险在所有执行模式需要审批`() {
        for (mode in ExecutionMode.entries) assertThat(ToolExecutionPolicy.requiresApproval(mode, "request_knowledge_access", ToolRisk.ACCESS_GRANT)).isTrue()
    }

    @Test fun `拒绝记录在助手续轮保持且新用户消息隔离`() {
        turns.seed(session, "u")
        session = session.copy(messages = session.messages + Message(id = "denied", role = MessageRole.TOOL,
            name = "request_knowledge_access", content = "用户未授权", createdAt = 3))
        turns.seed(session, "u")
        assertThat(turns.isDenied("s", "u")).isTrue()
        assertThat(turns.isDenied("s", "new-user")).isFalse()
        assertThat(mergeKnowledgeUsage(RagUsage(2), RagUsage(3, true))).isEqualTo(RagUsage(5, true))
    }
}
