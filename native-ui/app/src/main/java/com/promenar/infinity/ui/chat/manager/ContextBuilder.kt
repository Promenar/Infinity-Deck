package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalMode
import com.promenar.infinity.data.memory.LongTermMemoryAccess
import com.promenar.infinity.data.memory.LongTermMemoryRepository
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.RagRetrievalTrace
import com.promenar.infinity.data.model.RagUsage
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.TaskState
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.data.model.json
import com.promenar.infinity.domain.repository.ITaskRepository
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

data class ContextBuilderResult(
    val searchContext: String,
    val ragContext: String,
    val citations: List<com.promenar.infinity.data.model.Citation>,
    val ragReferences: List<RagReference>,
    val ragUsage: RagUsage?,
    val finalSystemPrompt: String,
    val kgPaths: List<com.promenar.infinity.data.model.KgPath> = emptyList(),
    /** 知识库检索过程记录；未执行检索时为 null。 */
    val ragTrace: RagRetrievalTrace? = null,
    /** 本次构建所用的检索结果；同一次生成的后续工具轮次以 [ContextBuilderParams.cachedRetrieval] 复用。 */
    val retrieval: ContextRetrieval = ContextRetrieval(
        searchContext = searchContext,
        searchCitations = citations,
        ragContext = ragContext,
        ragReferences = ragReferences,
        ragUsage = ragUsage,
        kgContext = "",
        kgPaths = kgPaths,
        ragTrace = ragTrace,
    ),
    /** 模型请求所见的状态版本，工具续轮创建新助手消息时继承此值。 */
    val stateBoardRevision: Long = 0,
    /** 仅发送给模型、随用户消息持久化的本轮上下文原文。 */
    val turnContext: String = "",
)

/** 依赖用户问题的检索结果（联网搜索、文档与记忆、知识图谱），与工具轮次无关。 */
data class ContextRetrieval(
    val searchContext: String,
    val searchCitations: List<com.promenar.infinity.data.model.Citation>,
    val ragContext: String,
    val ragReferences: List<RagReference>,
    val ragUsage: RagUsage?,
    val kgContext: String,
    val kgPaths: List<com.promenar.infinity.data.model.KgPath>,
    val ragTrace: RagRetrievalTrace? = null,
    /** 本轮实际生效的检索选项（全局门控、会话范围与助手记忆上限合并后）；轮后写入记忆按它判断。 */
    val effectiveRagOptions: com.promenar.infinity.data.model.RagOptions? = null,
)

data class ContextBuilderParams(
    val sessionId: String,
    val content: String,
    val images: String? = null,
    val assistantMsgId: String,
    val session: Session,
    val ragOptions: com.promenar.infinity.data.model.RagOptions? = null,
    val onRagProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null,
    val agentSystemPrompt: String? = null,
    val sessionCustomPrompt: String? = null,
    val retrievalMode: AgentRetrievalMode = AgentRetrievalMode.AUTO,
    val knowledgeCatalog: String = "",
    /** 助手的记忆权限；会话档位超出上限时按上限执行。 */
    val agentMemoryPolicy: AgentMemoryPolicy = AgentMemoryPolicy(),
    /** 本轮实际广告给模型的工具名；null 表示调用方未提供，按会话开关输出通用工具说明。 */
    val availableToolNames: Set<String>? = null,
    /** 当前会话两级技能扫描结果；目录按可用状态和名称确定性排列。 */
    val availableSkills: List<com.promenar.infinity.data.skills.DiscoveredSkill> = emptyList(),
    /** 同一次生成中首轮的检索结果；非空时跳过联网搜索、RAG 与 KG 检索。 */
    val cachedRetrieval: ContextRetrieval? = null,
    /** 会话当前工作区；null 表示调用方未解析，按私有工作区说明。 */
    val workspace: com.promenar.infinity.data.workspace.SessionWorkspace? = null,
    /** 会话当前可见的挂载，用于说明各挂载对命令是否可见。 */
    val mounts: List<com.promenar.infinity.domain.storage.ExternalMount> = emptyList(),
    /** 已路由模型的上下文窗口，未知时技能目录使用默认字符预算。 */
    val modelContextTokens: Int? = null,
    /** 本轮用户以 `/技能名` 点名并已由客户端加载的技能；null 表示本轮没有斜杠技能命令。 */
    val requestedSkill: com.promenar.infinity.ui.chat.manager.skills.RequestedSkill? = null,
    val stateExcludedMessageIds: Set<String> = emptySet(),
    /** 本轮请求实际发送的模型 ID，已完成提供商路由解析。 */
    val runtimeModelId: String? = null,
    /** 非空时连同空字符串一起视为已冻结，不重新读取本轮易变资料。 */
    val cachedTurnContext: String? = null,
    /** 用户通过 $名称点名并已加载的技能正文。 */
    val requestedSkills: List<com.promenar.infinity.ui.chat.manager.skills.RequestedSkill> = emptyList(),
)

interface WebSearchProvider {
    suspend fun search(query: String): Pair<String, List<com.promenar.infinity.data.model.Citation>>
}

/** 知识库检索结果；[trace] 为 null 表示提供方未记录检索过程。 */
data class RagRetrievalResult(
    val context: String,
    val references: List<RagReference>,
    val usage: RagUsage?,
    val trace: RagRetrievalTrace? = null,
)

interface RagProvider {
    suspend fun retrieveContext(
        query: String,
        sessionId: String,
        options: com.promenar.infinity.data.model.RagOptions,
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null
    ): RagRetrievalResult

    suspend fun retrieveForTool(
        query: String,
        sessionId: String,
        options: com.promenar.infinity.data.model.RagOptions,
        onProgress: ((stage: String, percentage: Int, subStage: String?) -> Unit)? = null,
    ): RagRetrievalResult = throw UnsupportedOperationException("检索提供者未实现工具检索")
}

data class KgContextResult(
    val context: String,
    val paths: List<com.promenar.infinity.data.model.KgPath> = emptyList(),
    val status: com.promenar.infinity.data.rag.KgExtractionStatus = com.promenar.infinity.data.rag.KgExtractionStatus.SUCCESS,
    val error: String? = null,
    val canonicalPaths: Boolean = false,
    val maxContextTokens: Int = 2048,
    /** 本次是否调用了临时抽取（JIT）及其模型；持久图足够时为 0。 */
    val jitExtractionCount: Int = 0,
    val jitModel: String? = null,
)

interface KgProvider {
    /** 未声明支持持久图读取的提供者不参与工具检索，避免间接触发抽取。 */
    suspend fun retrieveForTool(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>,
        options: com.promenar.infinity.data.model.RagOptions,
    ): KgContextResult? = null

    suspend fun retrieveContext(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>,
        options: com.promenar.infinity.data.model.RagOptions,
    ): KgContextResult? = extractContext(query, sessionId, topKResults)

    suspend fun extractContext(
        query: String,
        sessionId: String,
        topKResults: List<RagReference>
    ): KgContextResult?
}

class ContextBuilder(
    private val ragProvider: RagProvider? = null,
    private val kgProvider: KgProvider? = null,
    private val taskRepository: ITaskRepository? = null,
    /** 记忆或文档检索已开启但未配置嵌入模型时调用，用于给出一次性配置引导。 */
    private val onEmbeddingNotConfigured: () -> Unit = {},
    private val stateBoardRepository: com.promenar.infinity.data.repository.StateBoardRepository? = null,
    private val longTermMemoryRepository: LongTermMemoryRepository? = null,
    private val longTermMemoryAccess: LongTermMemoryAccess? = null,
) {
    suspend fun buildContext(params: ContextBuilderParams): ContextBuilderResult {
        val frozen = params.cachedTurnContext
        val retrieval = params.cachedRetrieval ?: if (frozen == null) retrieve(params) else
            ContextRetrieval("", emptyList(), "", emptyList(), null, "", emptyList(), effectiveRagOptions = computeEffectiveRagOptions(params))

        // 预取任务计划（suspend 调用）
        val activePlan: TaskState? = if (frozen != null || !params.session.options.toolsEnabled) null else try {
            taskRepository?.getPlan(params.sessionId)
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            InfinityLogger.logError("ContextBuilder.TaskPlanFetch", e)
            null
        }

        val stateSnapshot = if (frozen == null && params.session.options.toolsEnabled) {
            stateBoardRepository?.get(params.sessionId, params.stateExcludedMessageIds)
        } else null
        val stateRevision = if (frozen == null) stateSnapshot?.revision ?: 0 else turnContextMetadata(frozen)?.stateRevision ?: 0
        stateBoardRepository?.recordContextRead(params.sessionId, params.assistantMsgId, stateRevision)
        val memoryContext = if (frozen == null && params.session.options.toolsEnabled && longTermMemoryRepository != null && longTermMemoryAccess != null) {
            try {
                if (longTermMemoryAccess.isEnabled(params.session)) {
                    longTermMemoryContext(
                        longTermMemoryRepository.getAll(),
                        canReadFullContent = params.availableToolNames?.contains("memory_read") == true,
                    )
                } else ""
            } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 读取失败时跳过记忆，异常中可能包含私有内容，不输出日志正文。
                ""
            }
        } else ""
        val systemPrompt = buildSystemPrompt(params)
        val turnContext = frozen ?: buildTurnContext(params, retrieval, activePlan, memoryContext,
            stateBoardContext(stateSnapshot?.board,
                params.session.options.toolsEnabled && (params.availableToolNames == null || "state_update" in params.availableToolNames)),
            stateRevision)

        return ContextBuilderResult(
            searchContext = retrieval.searchContext,
            ragContext = retrieval.ragContext,
            citations = retrieval.searchCitations,
            ragReferences = retrieval.ragReferences,
            ragUsage = retrieval.ragUsage,
            finalSystemPrompt = systemPrompt,
            kgPaths = retrieval.kgPaths,
            ragTrace = retrieval.ragTrace,
            retrieval = retrieval,
            stateBoardRevision = stateRevision,
            turnContext = turnContext,
        )
    }

    private suspend fun retrieve(params: ContextBuilderParams): ContextRetrieval {
        val searchContext = ""
        val searchCitations = emptyList<com.promenar.infinity.data.model.Citation>()

        // RAG 与 KG 共用会话开关、范围及智能体上限。
        val effectiveRagOptions = computeEffectiveRagOptions(params)

        if (contextRetrievalMode(params) == AgentRetrievalMode.TOOL) {
            return ContextRetrieval("", emptyList(), "", emptyList(), null, "", emptyList(),
                effectiveRagOptions = effectiveRagOptions)
        }
        val ragResult = performRagRetrieval(params, effectiveRagOptions)

        // KG 启用判定与 RAG 共享同一份生效配置。
        val kgEnabled = effectiveRagOptions.enableKnowledgeGraph == true
        var jitExtractionCount = 0
        var jitModel: String? = null
        val (kgContext, kgPaths) = if (kgProvider != null && kgEnabled) {
            try {
                params.onRagProgress?.invoke("KG retrieval", 95, null)
                val kgResult = kgProvider.retrieveContext(params.content, params.sessionId, ragResult.references, effectiveRagOptions)
                if (kgResult == null || kgResult.status != com.promenar.infinity.data.rag.KgExtractionStatus.SUCCESS || kgResult.error != null) {
                    params.onRagProgress?.invoke("KG failed", 95, kgResult?.status?.name ?: "PROVIDER_ERROR")
                    "" to emptyList<com.promenar.infinity.data.model.KgPath>()
                } else {
                    params.onRagProgress?.invoke("Context ready", 100, null)
                    jitExtractionCount = kgResult.jitExtractionCount
                    jitModel = kgResult.jitModel
                    val paths = snapshotKgPaths(kgResult.paths)
                    if (kgResult.canonicalPaths) {
                        val fitted = com.promenar.infinity.data.rag.KgContextBudget.fit(paths, kgResult.maxContextTokens)
                        fitted.context to fitted.paths
                    } else kgResult.context to paths
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                InfinityLogger.logError("ContextBuilder.KGExtract", e)
                params.onRagProgress?.invoke("KG failed", 95, "PROVIDER_ERROR")
                "" to emptyList<com.promenar.infinity.data.model.KgPath>()
            }
        } else "" to emptyList<com.promenar.infinity.data.model.KgPath>()

        return ContextRetrieval(
            searchContext = searchContext,
            searchCitations = searchCitations,
            ragContext = ragResult.context,
            ragReferences = ragResult.references,
            ragUsage = ragResult.usage,
            kgContext = kgContext,
            kgPaths = kgPaths,
            // 未配置嵌入模型或没有检索范围时检索并未执行：不记录轨迹，回复中不出现检索步骤。
            ragTrace = ragResult.trace?.takeUnless { it.isSkipped }?.copy(
                memoryScope = effectiveRagOptions.effectiveMemoryScope.takeIf { effectiveRagOptions.enableMemory },
                graphScope = effectiveRagOptions.effectiveGraphScope.takeIf { kgEnabled },
                documentScope = effectiveRagOptions.effectiveDocumentScope.takeIf { effectiveRagOptions.enableDocs },
                jitExtractionCount = jitExtractionCount,
                jitModel = jitModel,
            ),
            effectiveRagOptions = effectiveRagOptions,
        )
    }


    /** 本轮未广告 knowledge_search（工具关闭、模型不支持工具调用或工具被全局停用）时回到自动检索，避免检索丢失。 */
    private fun contextRetrievalMode(params: ContextBuilderParams): AgentRetrievalMode = when {
        !params.session.options.toolsEnabled -> AgentRetrievalMode.AUTO
        params.availableToolNames != null && "knowledge_search" !in params.availableToolNames -> AgentRetrievalMode.AUTO
        else -> params.retrievalMode
    }

    private fun computeEffectiveRagOptions(params: ContextBuilderParams) =
        com.promenar.infinity.data.rag.resolveEffectiveRagOptions(
            params.session.ragOptions, params.ragOptions, params.agentMemoryPolicy,
        )

    /**
     * 对 provider 返回的 paths 做嵌套防御性快照：复制每条 path 及其内部列表，
     * 使 provider 后续对内部可变集合的修改不影响 [ContextBuilderResult]。
     */
    internal fun snapshotKgPaths(
        paths: List<com.promenar.infinity.data.model.KgPath>?
    ): List<com.promenar.infinity.data.model.KgPath> {
        if (paths.isNullOrEmpty()) return emptyList()
        val accepted = mutableListOf<com.promenar.infinity.data.model.KgPath>()
        for (path in paths.take(MAX_KG_PATHS)) {
            val nodes = path.nodes.asSequence()
                .take(MAX_KG_NODES_PER_PATH)
                .map { node ->
                    node.copy(
                        id = node.id.bounded(MAX_KG_TEXT_CHARS),
                        label = node.label.bounded(MAX_KG_TEXT_CHARS),
                        type = node.type.bounded(MAX_KG_TEXT_CHARS),
                        metadata = node.metadata?.bounded(MAX_KG_LONG_TEXT_CHARS),
                    )
                }
                .toList()
            val nodeIds = nodes.mapTo(hashSetOf()) { it.id }
            val candidate = path.copy(
                queryKeywords = path.queryKeywords.asSequence()
                    .take(MAX_KG_KEYWORDS_PER_PATH)
                    .map { it.bounded(MAX_KG_TEXT_CHARS) }
                    .toList(),
                nodes = nodes,
                edges = path.edges.asSequence()
                    .map { edge ->
                        edge.copy(
                            sourceId = edge.sourceId.bounded(MAX_KG_TEXT_CHARS),
                            targetId = edge.targetId.bounded(MAX_KG_TEXT_CHARS),
                            relation = edge.relation.bounded(MAX_KG_TEXT_CHARS),
                        )
                    }
                    .filter { it.sourceId in nodeIds && it.targetId in nodeIds }
                    .take(MAX_KG_EDGES_PER_PATH)
                    .toList(),
                reasoning = path.reasoning?.bounded(MAX_KG_LONG_TEXT_CHARS),
            )
            val candidateSnapshot = accepted + candidate
            if (json.encodeToString(candidateSnapshot).toByteArray(Charsets.UTF_8).size > MAX_KG_SERIALIZED_BYTES) {
                break
            }
            accepted += candidate
        }
        val omitted = paths.size - accepted.size
        return when {
            omitted == 0 -> accepted
            accepted.isEmpty() -> listOf(com.promenar.infinity.data.model.KgPath(
                origins = emptyList(), truncated = true, omittedPathCount = omitted,
            ))
            else -> accepted.map { path -> path.copy(truncated = true, omittedPathCount = path.omittedPathCount + omitted) }
        }
    }

    private fun String.bounded(maxChars: Int): String {
        if (length <= maxChars) return this
        val safeEnd = if (
            maxChars > 0 && this[maxChars - 1].isHighSurrogate() && this[maxChars].isLowSurrogate()
        ) maxChars - 1 else maxChars
        return substring(0, safeEnd)
    }

    private companion object {
        const val MAX_KG_PATHS = 32
        const val MAX_KG_KEYWORDS_PER_PATH = 32
        const val MAX_KG_NODES_PER_PATH = 128
        const val MAX_KG_EDGES_PER_PATH = 256
        const val MAX_KG_TEXT_CHARS = 512
        const val MAX_KG_LONG_TEXT_CHARS = 4096
        const val MAX_KG_SERIALIZED_BYTES = 512 * 1024
    }

    private suspend fun performRagRetrieval(
        params: ContextBuilderParams,
        effectiveRagOptions: com.promenar.infinity.data.model.RagOptions
    ): RagRetrievalResult {
        val skipped = RagRetrievalResult("", emptyList(), null)
        val isRagEnabled = effectiveRagOptions.enableMemory || effectiveRagOptions.enableDocs
        if (!isRagEnabled) return skipped
        if (ragProvider == null) {
            InfinityLogger.log("[ContextBuilder] 检索 Provider 不可用，跳过检索")
            return skipped
        }

        InfinityLogger.log("[ContextBuilder] effective ragOptions: docs=${effectiveRagOptions.enableDocs}/memory=${effectiveRagOptions.enableMemory}, scope=${effectiveRagOptions.memoryScope}/${effectiveRagOptions.graphScope}/${effectiveRagOptions.documentScope}, rerank=${effectiveRagOptions.enableRerank}, kg=${effectiveRagOptions.enableKnowledgeGraph}")

        return try {
            val result = ragProvider.retrieveContext(
                params.content, params.sessionId, effectiveRagOptions, params.onRagProgress
            )
            if (result.trace?.outcome == RagRetrievalTrace.OUTCOME_UNAVAILABLE) onEmbeddingNotConfigured()
            if (result.trace != null) result else result.copy(
                trace = RagRetrievalTrace(
                    outcome = if (result.context.isNotBlank() || result.references.isNotEmpty()) {
                        RagRetrievalTrace.OUTCOME_HIT
                    } else RagRetrievalTrace.OUTCOME_EMPTY,
                    selectedCount = result.references.size,
                ),
            )
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Exception) {
            InfinityLogger.logError("ContextBuilder.RAGRetrieval", e)
            skipped.copy(trace = RagRetrievalTrace(outcome = RagRetrievalTrace.OUTCOME_FAILED))
        }
    }

    private fun buildSystemPrompt(params: ContextBuilderParams): String {
        val sb = StringBuilder()
        val session = params.session

        // 1. 助手角色与风格来自用户提示词；应用宿主身份由固定运行环境段声明。
        params.agentSystemPrompt?.takeIf(String::isNotBlank)?.let { sb.appendLine(it.trim()) }

        // 2. 会话级指令
        val customPrompt = params.sessionCustomPrompt ?: session.customPrompt
        if (!customPrompt.isNullOrBlank()) {
            if (sb.isNotEmpty()) sb.appendLine()
            sb.appendLine("## Session Instructions")
            sb.appendLine(customPrompt.trim())
        }

        if (sb.isNotEmpty()) sb.appendLine()
        sb.append(SystemPromptSections.runtimeEnvironment(
            params.runtimeModelId,
            mode = com.promenar.infinity.domain.model.ExecutionModeCodec.parseOrWorkspace(session.executionMode),
            runMode = session.runMode ?: com.promenar.infinity.domain.model.SessionRunMode.BUILTIN,
            workspace = params.workspace,
        ))
        sb.appendLine()
        sb.append(SystemPromptSections.permissions(
            com.promenar.infinity.domain.model.ExecutionModeCodec.parseOrWorkspace(session.executionMode),
            runMode = session.runMode ?: com.promenar.infinity.domain.model.SessionRunMode.BUILTIN,
        ))

        // 3. 工具与工作区运行时契约
        val toolGuidance = when (val names = params.availableToolNames) {
            null -> if (session.options.toolsEnabled) SystemPromptSections.toolGuidance(emptySet(), generic = true) else ""
            else -> if (names.isEmpty()) "" else SystemPromptSections.toolGuidance(
                names,
                generic = false,
                workspace = params.workspace,
                workspaceChanged = true,
                workspaceSkillsShadowed = params.workspace is com.promenar.infinity.data.workspace.SessionWorkspace.Directory,
                mounts = params.mounts,
                runMode = session.runMode ?: com.promenar.infinity.domain.model.SessionRunMode.BUILTIN,
            )
        }
        if (toolGuidance.isNotEmpty()) {
            sb.appendLine()
            sb.append(toolGuidance)
        }

        // 3.1 内置与文件技能目录：只在模型有读取入口时披露元数据，正文按需加载。
        if (SystemPromptSections.canReadSkills(params.availableToolNames)) {
            SystemPromptSections.skillCatalog(params.availableSkills, modelContextTokens = params.modelContextTokens)
                .takeIf(String::isNotEmpty)?.let {
                    sb.appendLine()
                    sb.append(it)
                }
        }

        // 范围来自配置；目录中的数量、标题与索引状态属于本轮资料快照。
        if (contextRetrievalMode(params) != AgentRetrievalMode.AUTO) {
            val options = computeEffectiveRagOptions(params)
            sb.appendLine()
            sb.appendLine("## Knowledge Catalog")
            sb.appendLine("- Document scope: ${options.effectiveDocumentScope}; history scope: ${options.effectiveMemoryScope}.")
            sb.appendLine("- Selected documents: ${options.activeDocIds.sorted().joinToString(", ")}")
            sb.appendLine("- Selected folders: ${options.activeFolderIds.sorted().joinToString(", ")}")
            sb.appendLine("- Material titles and index status, when available, are in the user turn context. Use knowledge tools to inspect current material.")
        }

        // 6. 历史摘要
        session.summary?.takeIf(String::isNotBlank)?.let { summary ->
            sb.appendLine()
            sb.appendLine("## History Summary")
            sb.appendLine("Earlier turns of this conversation were condensed into the summary below.")
            sb.appendLine("<history_summary>")
            sb.appendLine(summary.trim())
            sb.appendLine("</history_summary>")
        }

        return sb.toString().trimEnd() + "\n"
    }

    /** 段落顺序固定；空块也由调用方保存，避免续轮重新采样时间与状态。 */
    private fun buildTurnContext(
        params: ContextBuilderParams,
        retrieval: ContextRetrieval,
        activePlan: TaskState?,
        memoryContext: String,
        stateContext: String,
        stateRevision: Long,
    ): String {
        val body = buildString {
            if (params.session.options.enableTimeInjection) {
                val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", java.util.Locale.US).format(java.util.Date())
                appendLine("[System Time: $now (${java.util.TimeZone.getDefault().id})]")
            }
            val references = buildString {
                if (contextRetrievalMode(params) != AgentRetrievalMode.AUTO && params.knowledgeCatalog.isNotBlank()) {
                    appendLine("### Available Material Snapshot")
                    appendLine(params.knowledgeCatalog)
                }
                if (memoryContext.isNotEmpty()) appendLine(memoryContext)
                if (retrieval.ragContext.isNotBlank() || retrieval.ragReferences.isNotEmpty()) {
                    appendLine("### Retrieved Context")
                    if (retrieval.ragContext.isNotBlank()) appendLine(retrieval.ragContext.trim()) else
                        retrieval.ragReferences.forEach { appendLine("- [${it.source}] ${it.content.take(400)}") }
                }
                if (retrieval.kgContext.isNotEmpty()) {
                    appendLine("### Knowledge Graph Relations")
                    appendLine(retrieval.kgContext.trim())
                }
                if (retrieval.searchContext.isNotEmpty()) {
                    appendLine("### Web Search Results")
                    appendLine(retrieval.searchContext.trim())
                }
            }
            if (references.isNotEmpty()) {
                appendLine("## Reference Material")
                appendLine(SystemPromptSections.DATA_BOUNDARY)
                appendLine("<reference_material>")
                append(references)
                appendLine("</reference_material>")
            }
            if (params.session.options.toolsEnabled && activePlan != null && activePlan.status !in listOf("idle", "dropped"))
                appendTaskContext(this, activePlan)
            if (stateContext.isNotEmpty()) {
                appendLine("<state_board revision=\"$stateRevision\">")
                append(stateContext)
                appendLine("</state_board>")
            }
            (listOfNotNull(params.requestedSkill) + params.requestedSkills).distinctBy { it.name }.forEach {
                append(com.promenar.infinity.ui.chat.manager.skills.SlashSkillActivation.promptSection(it))
            }
        }
        return "<turn_context>\n<snapshot_metadata>${json.encodeToString(TurnContextMetadata(stateRevision, params.assistantMsgId))}</snapshot_metadata>\n$body</turn_context>"
    }

    /** 已完成步骤仅保留标题，当前与未完成步骤保留完整说明。 */
    private fun appendTaskContext(
        sb: StringBuilder,
        plan: TaskState
    ) {
        val (completed, total) = countLeaves(plan.steps)
        sb.appendLine("## Active Task Plan")
        sb.appendLine("- **Goal**: \"${plan.title}\"")
        sb.appendLine("- **Progress**: $completed/$total leaf steps done")
        plan.currentFocusStepId?.let { focusId ->
            val focus = findStepById(plan.steps, focusId)
            if (focus != null) {
                val description = if (focus.status in setOf("completed", "done")) "" else " — ${focus.description}"
                sb.appendLine("- **Current Focus**: \"${focus.title}\"$description")
            }
        }
        sb.appendLine()
        sb.appendLine("### Task Tree")
        sb.appendLine("```")
        renderTaskTree(sb, plan.steps, indent = 0)
        sb.appendLine("```")
        sb.appendLine()

        // 断点重连提示
        val doingLeaf = findDoingLeaf(plan.steps)
        if (doingLeaf != null) {
            sb.appendLine("[Resume Hint]: Step \"${doingLeaf.title}\" was in progress. Resume from where you left off.")
            sb.appendLine()
        }

        // 下一个待办步骤
        val nextTodos = findNextTodos(plan.steps, maxCount = 3)
        if (nextTodos.isNotEmpty()) {
            sb.appendLine("**Next**: ${nextTodos.joinToString(" → ") { "\"${it.title}\"" }}")
            sb.appendLine()
        }

        sb.appendLine("[Use update_plan to modify status, add/remove/move steps, or set notes.]")
        sb.appendLine("[Use get_plan to re-read the full task tree when context is truncated.]")
        sb.appendLine()
    }

    private fun renderTaskTree(sb: StringBuilder, steps: List<TaskStep>, indent: Int) {
        val prefix = "  ".repeat(indent)
        for (step in steps) {
            val icon = when (step.status) {
                "completed", "done" -> "✅"
                "in_progress", "doing" -> "⟳"
                "failed", "error" -> "✕"
                "dropped" -> "⊗"
                else -> "○"
            }
            sb.appendLine("$prefix$icon ${step.title}")
            if (step.status !in setOf("completed", "done")) {
                if (step.description.isNotBlank()) sb.appendLine("$prefix   ${step.description}")
                step.note?.let { sb.appendLine("$prefix   📝 $it") }
                step.artifactFileUuids?.takeIf(List<String>::isNotEmpty)?.let {
                    sb.appendLine("$prefix   Artifacts: ${it.joinToString(", ")}")
                }
            }
            if (step.children.isNotEmpty()) {
                renderTaskTree(sb, step.children, indent + 1)
            }
        }
    }

    private fun findStepById(steps: List<TaskStep>, targetId: String): TaskStep? {
        for (step in steps) {
            if (step.id == targetId) return step
            findStepById(step.children, targetId)?.let { return it }
        }
        return null
    }

    private fun findDoingLeaf(steps: List<TaskStep>): TaskStep? {
        for (step in steps) {
            if (step.status in setOf("in_progress", "doing") && step.children.isEmpty()) return step
            findDoingLeaf(step.children)?.let { return it }
        }
        return null
    }

    private fun findNextTodos(steps: List<TaskStep>, maxCount: Int): List<TaskStep> {
        val result = mutableListOf<TaskStep>()
        for (step in steps) {
            if (result.size >= maxCount) break
            if (step.status in setOf("pending", "todo") && step.children.isEmpty()) {
                result.add(step)
            }
            if (step.children.isNotEmpty()) {
                result.addAll(findNextTodos(step.children, maxCount - result.size))
            }
        }
        return result
    }

    private fun countLeaves(steps: List<TaskStep>): Pair<Int, Int> {
        var completed = 0
        var total = 0
        for (step in steps) {
            if (step.children.isEmpty()) {
                total++
                if (step.status in setOf("completed", "done")) completed++
            } else {
                val (c, t) = countLeaves(step.children)
                completed += c
                total += t
            }
        }
        return Pair(completed, total)
    }
}
