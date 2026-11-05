package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.agent.KnowledgeFileAccess
import com.promenar.infinity.data.knowledge.*
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.rag.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.KgProvider
import com.promenar.infinity.ui.chat.manager.RagProvider
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException

class KnowledgeSearchSkill(
    private val loadSession: suspend (String) -> Session?,
    private val loadConfig: suspend (String) -> AgentRetrievalConfig?,
    private val catalog: KnowledgeCatalogSource,
    private val rag: RagProvider,
    private val kg: KgProvider?,
    private val turns: KnowledgeTurnState,
) : SkillDefinition {
    override val id = "knowledge_search"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val description = "只读检索用户导入的文档、知识库和过往对话，可附带持久知识图谱关系。查询词使用完整问题或关键短语。memory_* 管理主动记忆中的事实与偏好，其目录已在上下文中；需要知识库文档全文时，通过只读挂载后使用 read_file、list_files、search_files。每轮最多检索6次，达到上限后基于已有结果作答。"
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"完整的问题或关键短语"},"sources":{"type":"array","items":{"type":"string","enum":["documents","history"]}},"limit":{"type":"integer","description":"返回条数，1 至 10，默认 5"},"as_of":{"type":"integer","description":"可选：图谱合集的篇目序号（从 1 开始）。给出时图谱关系按读到该篇为止的状态返回，用于回答某章时的人物关系或位置"}},"required":["query"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            require(args.keys.all { it in setOf("query", "sources", "limit", "as_of") }) { "存在未支持的参数" }
            val query = args.string("query")?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext error("查询词不能为空")
            val limit = if ("limit" in args) (args["limit"] as? JsonPrimitive)?.intOrNull
                ?.takeIf { it in 1..10 } ?: return@withContext error("limit 必须为1至10的整数") else 5
            val requested = if ("sources" in args) {
                val values = args["sources"] as? JsonArray ?: return@withContext error("sources 必须为来源数组")
                values.map { (it as? JsonPrimitive)?.takeIf { item -> item.isString }?.content
                    ?.takeIf { item -> item in setOf("documents", "history") } ?: return@withContext error("检索来源无效") }.distinct()
            } else listOf("documents", "history")
            val asOf = if ("as_of" in args) (args["as_of"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 1 }
                ?.let(com.promenar.infinity.data.rag.KgAsOf::Ordinal) ?: return@withContext error("as_of 必须为从 1 开始的篇目序号") else null
            val session = loadSession(context.sessionId) ?: return@withContext error("会话不存在")
            val config = loadConfig(session.agentId)
            if (effectiveRetrievalMode(config, session.options.toolsEnabled) == AgentRetrievalMode.AUTO) return@withContext error("当前会话未启用工具检索")
            val effective = resolveEffectiveRagOptions(session.ragOptions, memoryPolicy = config?.memoryPolicy ?: com.promenar.infinity.data.agent.AgentMemoryPolicy())
            val disabled = requested.filter { if (it == "documents") !effective.enableDocs else !effective.enableMemory }
            val sources = requested - disabled.toSet()
            val options = effective.copy(enableDocs = "documents" in sources, enableMemory = "history" in sources,
                enableKnowledgeGraph = effective.enableKnowledgeGraph == true && sources.isNotEmpty())
            val turnId = knowledgeTurnId(session, context.assistantMessageId)
            turns.seed(session, turnId)
            if (!turns.consume(session.id, turnId, context.toolStepId ?: java.util.UUID.randomUUID().toString())) return@withContext success(
                "本轮已达到6次知识检索上限，请停止检索并基于已有结果直接作答。",
                KnowledgeSearchData(query, sources, disabled, limitReached = true),
            )
            if (sources.isEmpty()) return@withContext success("未找到相关内容。${disabled.joinToString { "$it 已关闭" }}", KnowledgeSearchData(query, sources, disabled, empty = true))
            val retrieved = rag.retrieveForTool(query, session.id, options)
            turns.addUsage(session.id, turnId, retrieved.usage)
            if (retrieved.trace?.outcome == RagRetrievalTrace.OUTCOME_UNAVAILABLE) return@withContext error("未配置嵌入模型，请在设置 → 默认模型中配置嵌入模型后再检索。")
            if (retrieved.trace?.outcome == RagRetrievalTrace.OUTCOME_FAILED) return@withContext error("知识检索失败，请检查嵌入模型配置。")
            val references = retrieved.references.distinctBy { it.id }.take(limit)
            val graph = if (options.enableKnowledgeGraph != true) null
            else if (asOf != null) kg?.retrieveForToolAt(query, session.id, references, options, retrieved.resolvedScope, asOf)
            else if (retrieved.resolvedScope == null)
                kg?.retrieveForTool(query, session.id, references, options)
            else kg?.retrieveWithScope(query, session.id, references, options, retrieved.resolvedScope, true)
            val paths = graph?.takeIf { it.status == KgExtractionStatus.SUCCESS && it.error == null }?.paths.orEmpty()
            val results = references.map { reference ->
                val location = catalog.documentLocation(session.id, reference)
                KnowledgeSearchHit(reference.id, turns.citation(session.id, turnId, reference.id),
                    alreadyInContext = config?.retrievalMode == AgentRetrievalMode.BOTH && turns.isInjected(session.id, turnId, reference.id),
                    pathType = location?.kind, path = location?.path,
                    requiresAccess = location?.kind == KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED &&
                        effectiveKnowledgeFileAccess(config, session.options.toolsEnabled) == KnowledgeFileAccess.ON_REQUEST)
            }
            val data = KnowledgeSearchData(query, sources, disabled, references, results, paths,
                empty = references.isEmpty() && paths.isEmpty(), usage = retrieved.usage)
            success(buildString {
                if (disabled.isNotEmpty()) appendLine(disabled.joinToString { "$it 已关闭，未检索该来源。" })
                appendLine("已检索来源：${sources.joinToString()}。")
                if (data.empty) appendLine("未找到相关内容。")
                references.zip(results).forEach { (reference, hit) ->
                    if (hit.alreadyInContext) appendLine("[${hit.citationId}] 已在上方参考资料中") else {
                        val score = String.format(java.util.Locale.ROOT, "%.2f", reference.score)
                        append(if (reference.documentId != null)
                            "[${hit.citationId}] 文档《${reference.source.removePrefix("文档: ")}》 片段 ${reference.chunkIndex} · 相关度 $score"  // wrap-check: allow 工具结果文本，非界面排版
                        else "[${hit.citationId}] 历史对话（${reference.source}）${reference.occurredAt?.let { " " + java.time.Instant.ofEpochMilli(it)
                            .atZone(java.time.ZoneId.systemDefault()).toLocalDate() }.orEmpty()} · 相关度 $score")
                        hit.path?.let { append(" · 路径 $it") }
                        if (hit.pathType == KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED) {
                            append(" · 知识库未挂载")
                            if (hit.requiresAccess) append("，需要全文时调用 request_knowledge_access")
                        }
                        if (hit.pathType == KnowledgeDocumentLocationKind.SNIPPET_ONLY) append(" · 只提供片段")
                        appendLine()
                        appendLine(reference.content)
                    }
                }
                if (paths.isNotEmpty()) {
                    appendLine("图谱：")
                    paths.forEach { path -> path.edges.forEach { edge ->
                        val source = path.nodes.firstOrNull { it.id == edge.sourceId }?.label ?: ""
                        val target = path.nodes.firstOrNull { it.id == edge.targetId }?.label ?: ""
                        appendLine("$source —${edge.relation}→ $target" + (path.reasoning?.let { "（$it）" } ?: ""))
                    } }
                }
            }.trim(), data)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (invalid: IllegalArgumentException) { error(invalid.message ?: "检索参数无效") }
        catch (_: Exception) { error("知识检索暂不可用，请根据已有资料作答。") }
    }
    private fun success(content: String, data: KnowledgeSearchData) = ToolResult(id, content, data = json.encodeToString(data))
    private fun error(content: String) = ToolResult(id, content, "error")
}

class RequestKnowledgeAccessSkill(
    private val planner: KnowledgeAccessPlanner,
    private val loadSession: suspend (String) -> Session?,
    private val loadConfig: suspend (String) -> AgentRetrievalConfig?,
    private val turns: KnowledgeTurnState,
) : SkillDefinition {
    override val id = "request_knowledge_access"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.ACCESS_GRANT
    override val description = "申请将可见知识库只读挂载，以便使用 read_file、list_files、search_files 读取全文。reason 说明原因，knowledge_base 可指定知识库名称，省略表示全部可见知识库。所有执行模式都需要用户批准，可读取获批知识库的全部文件。用户拒绝后本轮不得重复申请。memory_* 用于用户长期事实与偏好，knowledge_search 用于只读检索片段。"
    override val parametersSchema = """{"type":"object","properties":{"reason":{"type":"string"},"knowledge_base":{"type":"string"}},"required":["reason"],"additionalProperties":false}"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            require(args.keys.all { it in setOf("reason", "knowledge_base") } && !args.string("reason").isNullOrBlank()) { "必须说明申请原因" }
            val base = if ("knowledge_base" in args) args.string("knowledge_base")?.trim()?.takeIf { it.isNotBlank() }
                ?: return@withContext ToolResult(id, "知识库名称无效", "error") else null
            val session = loadSession(context.sessionId) ?: return@withContext ToolResult(id, "会话不存在", "error")
            val turnId = knowledgeTurnId(session, context.assistantMessageId)
            turns.seed(session, turnId)
            if (turns.isDenied(session.id, turnId)) return@withContext ToolResult(id, "用户未授权", "error")
            if (effectiveKnowledgeFileAccess(loadConfig(session.agentId), session.options.toolsEnabled) != KnowledgeFileAccess.ON_REQUEST)
                return@withContext ToolResult(id, "当前会话未开放知识库访问申请", "error")
            if (!context.isApprovalBoundExecution || context.approvedExecutionTarget == null)
                return@withContext ToolResult(id, "需要用户批准后才能挂载知识库", "error")
            val approved = json.decodeFromString<KnowledgeAccessPreview>(requireNotNull(context.approvedExecutionTarget))
            require(approved.sessionId == session.id && approved.knowledgeBase == base) { "审批目标与申请不一致" }
            if (!approved.allowed) return@withContext ToolResult(id, approved.reason ?: "文档范围不允许挂载", data = json.encodeToString(approved))
            val mounts = planner.mount(approved)
            ToolResult(id, if (mounts.isEmpty()) "当前范围内没有可挂载的知识库" else
                mounts.joinToString("\n") { "/mnt/${it.alias} · 知识库 · 只读" }, data = json.encodeToString(approved))  // wrap-check: allow 工具结果文本，非界面排版
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { ToolResult(id, "知识库授权目标已变化或无法挂载，请重新核对可见范围。", "error") }
    }
}

private fun JsonObject.string(key: String): String? = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
