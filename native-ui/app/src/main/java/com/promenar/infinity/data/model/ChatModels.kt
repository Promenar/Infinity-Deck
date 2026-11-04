package com.promenar.infinity.data.model

import kotlinx.serialization.Serializable

enum class PostProcessType {
    ARCHIVE_TO_RAG,
    AUTO_SUMMARY
}

enum class PostProcessStatus {
    RUNNING,
    DONE,
    ERROR
}

data class PostProcessTask(
    val id: String,
    val type: PostProcessType,
    val status: PostProcessStatus,
    val progress: Float = 0f,
    val detail: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class Agent(
    val id: String,
    val name: String,
    val description: String = "",
    val systemPrompt: String = "",
    val model: String = "",
    val icon: String = "✨",
    val color: String = "#C0C1FF",
    val avatarPath: String? = null,
    val isPinned: Boolean = false,
    val temperature: Double? = 0.7,
    val topP: Double? = 0.9,
    val maxTokens: Int? = 4096,
    val ragConfig: com.promenar.infinity.data.agent.AgentRagConfig? = null,
    val retrievalConfig: com.promenar.infinity.data.agent.AgentRetrievalConfig? = null,
    val useInheritedConfig: Boolean = true,
    val createdAt: Long = 0L
)

/**
 * 单条助手消息的 Token 用量。`input` 为本轮全部输入（含缓存读写部分），`output` 含推理部分；
 * 缓存与推理字段是对应总量的子集，null 表示服务商未报告（旧数据同样为 null），0 表示报告为 0。
 * 可空字段为默认值时不写入 JSON，保持旧记录格式与 `json_extract` 汇总键不变。
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class TokenUsage(
    val input: Int = 0,
    val output: Int = 0,
    val total: Int = 0,
    val estimated: Boolean = false,
    val summaryInput: Int = 0,
    val summaryOutput: Int = 0,
    val summaryTotal: Int = 0,
    val summaryEstimated: Boolean = false,
    /** [input] 中的缓存读取部分。 */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val cacheReadInput: Int? = null,
    /** [input] 中的缓存写入部分。 */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val cacheWriteInput: Int? = null,
    /** [output] 中的推理部分。 */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val reasoningOutput: Int? = null,
    /** 本条消息对应模型轮次的时间点；旧消息与未发出请求的消息为 null。 */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val timing: GenerationTimingRecord? = null,
    /** [cacheWriteInput] 中长 TTL（1 小时或 12 小时）档的写入部分。 */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val cacheWriteLongTtlInput: Int? = null,
)

/** 一轮模型生成的时间点（epoch 毫秒），未知时间点为 null 且不写入 JSON。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class GenerationTimingRecord(
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val requestStartedAt: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val firstOutputAt: Long? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val endedAt: Long? = null,
)

@Serializable
data class GeneratedImageData(
    val thumbnail: String,
    val original: String,
    val mime: String
)

@Serializable
data class Citation(
    val title: String,
    val url: String,
    val source: String? = null,
    val snippet: String? = null
)

@Serializable
data class InferenceParams(
    val temperature: Double? = null,
    val topP: Double? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Double? = null,
    val presencePenalty: Double? = null,
    val topK: Int? = null,
    val repetitionPenalty: Double? = null,
    /** 推理强度（[com.promenar.infinity.data.model.params.ReasoningEffort.wireName]）；null 为模型默认。 */
    val reasoningEffort: String? = null,
    /**
     * 会话采样参数所依据的方案：参数方案 id，或 [com.promenar.infinity.data.model.params.MODEL_DEFAULT_SCHEME_ID]。
     * 非空时采样参数完全由会话决定，不再逐项沿用助手设置；null 时逐项回退到助手。
     */
    val parameterSchemeId: String? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val streamTimeout: Int? = 120,
    val autoSummaryThreshold: Double = 0.8,
    val keepRecentTurns: Int? = null,
    /** 仅用于读取旧会话中的消息窗口，保留轮数由领域层统一解析。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val activeContextWindow: Int? = null
)

@Serializable
data class TokenMetric(
    val count: Int = 0,
    val isEstimated: Boolean = false
)

@Serializable
data class BillingUsage(
    val chatInput: TokenMetric = TokenMetric(),
    val chatOutput: TokenMetric = TokenMetric(),
    val ragSystem: TokenMetric = TokenMetric(),
    val total: Int = 0,
    val costUSD: Double = 0.0
)

@Serializable
data class RagReference(
    val id: String = "",
    val content: String = "",
    val source: String = "",
    /** 召回分：向量相似度，或启用混合检索时的融合分（0–1）。 */
    val score: Float = 0f,
    /** 重排模型给出的相关性分（0–1）；未重排时为 null。 */
    val rerankScore: Float? = null,
    /** 重排带来的名次变化，正数表示上升；未重排时为 null。 */
    val rankChange: Int? = null,
    val documentId: String? = null,
    val chunkIndex: Int = 0,
    /** 在去重后的召回候选中的名次（从 1 开始）。 */
    val recallRank: Int? = null,
    /** 来源类型，取值见 [RagReferenceKind]。 */
    val kind: String? = null,
    val documentContentHash: String? = null,
    /** 对话记录所属消息的发生时间，状态为其最近一次更新的时间；文档为 null。 */
    val occurredAt: Long? = null,
)

object RagReferenceKind {
    const val MEMORY = "memory"
    const val DOCUMENT = "document"
    /** 对话事件摘要。 */
    const val SUMMARY = "summary"
    /** 对话状态（对象 · 属性的当前值）。 */
    const val STATE = "state"
}

/**
 * 一次知识库检索的过程记录，随回复持久化，用于展示是否命中、如何排序以及重排的影响。
 * 字符串取值见 [RagRetrievalTrace.Companion] 中的常量。
 */
@Serializable
data class RagRetrievalTrace(
    val outcome: String = OUTCOME_HIT,
    /** 去重后的召回候选数。 */
    val recallCount: Int = 0,
    /** 最终注入上下文的条数。 */
    val selectedCount: Int = 0,
    val scoring: String = SCORING_VECTOR,
    val rerank: String = RERANK_OFF,
    val rerankModel: String? = null,
    val searchTimeMs: Long = 0,
    val rerankTimeMs: Long = 0,
    val memoryCount: Int = 0,
    val documentCount: Int = 0,
    /** 本次实际执行的档位（已按助手上限收紧）；对应资源未参与检索时为 null。 */
    val memoryScope: ConversationScope? = null,
    val graphScope: ConversationScope? = null,
    val documentScope: DocumentScope? = null,
    /** 对话记忆范围覆盖的会话数与助手数；未检索对话记忆时为 null。 */
    val memorySessionCount: Int? = null,
    val memoryAgentCount: Int? = null,
    /** 图谱覆盖不足时临时抽取（JIT）的次数与所用模型。 */
    val jitExtractionCount: Int = 0,
    val jitModel: String? = null,
) {
    companion object {
        const val OUTCOME_HIT = "hit"
        /** 已检索但没有候选达到阈值。 */
        const val OUTCOME_EMPTY = "empty"
        /** 未配置嵌入模型（无远程配置、无本地模型），检索未执行。 */
        const val OUTCOME_UNAVAILABLE = "unavailable"
        /** 未选择可检索的文档且未启用记忆。 */
        const val OUTCOME_NO_SCOPE = "no_scope"
        /** 已尝试检索但出错（含嵌入请求全部失败），属于真正的失败。 */
        const val OUTCOME_FAILED = "failed"

        const val SCORING_VECTOR = "vector"
        const val SCORING_HYBRID = "hybrid"

        const val RERANK_OFF = "off"
        const val RERANK_API = "api"
        const val RERANK_LLM = "llm"
        /** 候选不足两条，无需重排。 */
        const val RERANK_SKIPPED = "skipped"
        /** 已启用但重排失败，保持召回顺序。 */
        const val RERANK_FAILED = "failed"
    }

    /** 未配置或无检索范围：检索没有执行，不属于失败，会话中不显示检索步骤。 */
    val isSkipped: Boolean
        get() = outcome == OUTCOME_UNAVAILABLE || outcome == OUTCOME_NO_SCOPE
}

@Serializable
data class KgNode(
    val id: String,
    val label: String,
    val type: String,
    val metadata: String? = null
)

@Serializable
data class KgEdge(
    val sourceId: String,
    val targetId: String,
    val relation: String,
    val weight: Double = 1.0
)

@Serializable
data class KgPath(
    val queryKeywords: List<String> = emptyList(),
    val nodes: List<KgNode> = emptyList(),
    val edges: List<KgEdge> = emptyList(),
    val reasoning: String? = null,
    val sourceDocumentIds: List<String> = emptyList(),
    val origins: List<String> = listOf("jit"),
    val score: Double = 0.0,
    val evidenceCount: Int = 1,
    val distance: Int = 1,
    val truncated: Boolean = false,
    val omittedPathCount: Int = 0,
)

@Serializable
data class RagProgress(
    val stage: String = "",
    val percentage: Int = 0,
    val subStage: String? = null,
    val networkStats: String? = null
)

@Serializable
data class RagMetadata(
    val chunkCount: Int = 0,
    val totalTokens: Int = 0,
    val retrievalTimeMs: Long = 0,
    val trace: RagRetrievalTrace? = null,
)

@Serializable
data class RagUsage(
    val ragSystem: Int = 0,
    val isEstimated: Boolean = false
)

@Serializable
data class TaskStep(
    val id: String = "",
    val parentId: String? = null,
    val title: String = "",
    val description: String = "",
    val status: String = "pending",
    val sortOrder: Int = 0,
    val note: String? = null,
    val artifactFileUuids: List<String>? = null,
    val children: List<TaskStep> = emptyList(),
    val isCollapsed: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
)

@Serializable
data class TaskState(
    val id: String = "",
    val title: String = "",
    val status: String = "idle",
    val progress: Int = 0,
    val steps: List<TaskStep> = emptyList(),
    val currentFocusStepId: String? = null,
    val createdAt: Long = 0
)

@Serializable
data class ExecutionStep(
    val id: String,
    val type: String,
    val toolName: String? = null,
    val toolArgs: String? = null,
    val toolCallId: String? = null,
    val content: String? = null,
    val data: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
    val throttledUntil: Long? = null,
    val taskStepId: String? = null,
    /** 文件工具成功执行时的改动摘要，仅供界面展示；为空时不写入 JSON。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fileChanges: List<FileChangeSummary>? = null,
    /** 工具产出或引用的工作区媒体，供界面渲染卡片；为空时不写入 JSON。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val media: List<com.promenar.infinity.domain.media.MediaRef>? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String
)

@Serializable
data class ToolResult(
    val id: String,
    val content: String,
    val status: String = "success",
    val data: String? = null,
    /** 文件改动摘要，只复制到执行步骤供界面展示，不进入发给模型的 [content]。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val fileChanges: List<FileChangeSummary>? = null,
    /** 工具产出或引用的工作区媒体，只复制到执行步骤供界面渲染，不进入发给模型的 [content]。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val media: List<com.promenar.infinity.domain.media.MediaRef>? = null,
    /** 模型已查看的媒体内容身份与查看方式，经执行账本保存到最终工具消息。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val viewedMedia: List<com.promenar.infinity.domain.media.ViewedMedia>? = null,
)

@Serializable
data class ToolResultArtifact(
    val type: String,
    val content: String,
    val name: String? = null
)

@Serializable
data class UpdateMessageOptions(
    val status: String? = null,
    val tokens: TokenUsage? = null,
    val reasoning: String? = null,
    val citations: List<Citation>? = null,
    val ragReferences: List<RagReference>? = null,
    val kgPaths: List<KgPath>? = null,
    val ragReferencesLoading: Boolean? = null,
    val ragMetadata: RagMetadata? = null,
    val thoughtSignature: String? = null,
    val planningTask: TaskState? = null,
    val toolCalls: List<ToolCall>? = null,
    val clearToolCalls: Boolean = false,
    val executionSteps: List<ExecutionStep>? = null,
    val pendingApprovalToolIds: List<String>? = null,
    val toolResults: List<ToolResultArtifact>? = null,
    val isError: Boolean? = null,
    val errorMessage: String? = null,
    val clearError: Boolean = false,
    val isLongWait: Boolean? = null,
    val loopCount: Int? = null
)

@Serializable
data class ApprovalCallIdentity(
    val toolCallId: String,
    val runtimeToolId: String,
    val toolName: String,
    val argumentsDigest: String,
    val definitionDigest: String,
    val requiresApproval: Boolean,
    val argumentsSummary: String,
    val risk: String,
    val executionTarget: String? = null,
    val grantCategory: com.promenar.infinity.domain.tool.ApprovalGrantCategory? = null,
)

@Serializable
data class ApprovalRequest(
    val assistantMessageId: String? = null,
    val identityHash: String? = null,
    val calls: List<ApprovalCallIdentity> = emptyList(),
    // 旧字段仅用于兼容解码历史记录；新工具审批只写入上面的精确身份队列。
    val toolName: String? = null,
    val args: String? = null,
    val reason: String? = null,
    val type: String? = null
)

/** 对话记忆与图谱对话证据的可见范围，按声明顺序逐级扩大。 */
@Serializable
enum class ConversationScope { SESSION, AGENT, ALL_AGENTS }

/** 文档检索范围，按声明顺序逐级扩大。 */
@Serializable
enum class DocumentScope {
    /** 本会话工作区与显式选择的文档。 */
    WORKSPACE,
    /** 再加入全局知识库。 */
    KNOWLEDGE_BASE,
    /** 全部已索引文档。 */
    ALL_INDEXED,
}

@Serializable
data class RagOptions(
    val enableMemory: Boolean = true,
    val enableDocs: Boolean = true,  // 默认开启：用户导入文档的意图就是检索，不应要求手动再开开关
    val activeDocIds: List<String> = emptyList(),
    val activeFolderIds: List<String> = emptyList(),
    /** 仅兼容旧会话解码；范围以下面三个字段为准，见 [effectiveMemoryScope]。 */
    val isGlobal: Boolean = false,
    val memoryScope: ConversationScope? = null,
    val graphScope: ConversationScope? = null,
    val documentScope: DocumentScope? = null,
    val enableKnowledgeGraph: Boolean? = null,
    val enableRerank: Boolean = true,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val memoryLimit: Int? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val memoryThreshold: Float? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val docLimit: Int? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val docThreshold: Float? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val rerankTopK: Int? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val rerankFinalK: Int? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val enableQueryRewrite: Boolean? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val queryRewriteStrategy: String? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val queryRewriteCount: Int? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val enableHybridSearch: Boolean? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val hybridAlpha: Float? = null,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val hybridBM25Boost: Float? = null
) {
    // 旧会话只有 isGlobal：开启时迁移为“本助手”，关闭时为“仅本会话”；文档统一为“工作区 + 全局知识库”。
    val effectiveMemoryScope: ConversationScope
        get() = memoryScope ?: if (isGlobal) ConversationScope.AGENT else ConversationScope.SESSION
    val effectiveGraphScope: ConversationScope
        get() = graphScope ?: if (isGlobal) ConversationScope.AGENT else ConversationScope.SESSION
    val effectiveDocumentScope: DocumentScope
        get() = documentScope ?: DocumentScope.KNOWLEDGE_BASE
}

@Serializable
data class SessionOptions(
    val toolsEnabled: Boolean = true,
    val enableTimeInjection: Boolean = true,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val webSearch: Boolean = false,
    val ragOptions: RagOptions? = null,
    val fontSize: Int = 15,
    /** 仅兼容已保存会话的解码，不参与上下文构建。 */
    val economyMode: Boolean = false,
    /** 仅为旧数据解码保留，运行时不读取。 */
    val enableGeminiSearch: Boolean = true,
    val keepWebSearchWhenToolsOff: Boolean = true
)

@Serializable
data class SessionStats(
    val totalTokens: Int = 0,
    val billing: BillingUsage = BillingUsage(),
    /** 最近单次主模型请求的实际输入基线，与计费用量独立持久化。 */
    val contextUsageBaseline: PromptUsageBaseline? = null,
)

@Serializable
enum class LoopStatus {
    IDLE, RUNNING, PAUSED, WAITING_FOR_APPROVAL, WAITING_FOR_INPUT, INPUT_ANSWERED, COMPLETED;

    fun toSerializedName(): String = name.lowercase()

    companion object {
        fun fromSerializedName(name: String): LoopStatus =
            entries.find { it.name.lowercase() == name } ?: IDLE
    }
}

@Serializable
data class MessageDocumentAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val content: String,
    val sizeBytes: Long,
    val sha256: String,
    val estimatedTokens: Int,
    val workspaceReference: WorkspaceDocumentReference? = null,
    /** 自动保存的文档在当前轮保留全文；手动转存的引用默认不内联。 */
    val inlineInCurrentTurn: Boolean = false,
)

@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class MessageDocumentEnvelope(
    val schema: String = SCHEMA,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val documents: List<MessageDocumentAttachment>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val viewedMedia: List<com.promenar.infinity.domain.media.ViewedMedia>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val legacyFilesPayload: String? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val userImageReferences: List<WorkspaceDocumentReference?>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val userImageAnnotations: List<com.promenar.infinity.domain.media.ImageAnnotation?>? = null,
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val userImageAnnotationContext: com.promenar.infinity.data.media.annotation.ImageAnnotationContext? = null,
) {
    companion object {
        const val SCHEMA = "infinity-full-context-documents-v1"
    }
}

enum class MessageRole {
    USER, ASSISTANT, SYSTEM, TOOL;

    fun toSerializedName(): String = name.lowercase()

    companion object {
        fun fromSerializedName(name: String): MessageRole =
            entries.find { it.name.lowercase() == name } ?: USER
    }
}

@Serializable
data class Message(
    val id: String,
    val role: MessageRole,
    val content: String,
    val modelId: String? = null,
    val status: String? = null,
    val reasoning: String? = null,
    val thoughtSignature: String? = null,
    val images: String? = null,
    val userDocuments: List<MessageDocumentAttachment>? = null,
    /** 用户消息附带的引用批注，按添加顺序排列。 */
    val quotes: List<MessageQuote>? = null,
    val legacyFilesPayload: String? = null,
    val legacyAttachmentsPayload: String? = null,
    val userImages: List<String>? = null,
    val tokens: TokenUsage? = null,
    val citations: List<Citation>? = null,
    val ragReferences: List<RagReference>? = null,
    val kgPaths: List<KgPath>? = null,
    val ragProgress: RagProgress? = null,
    val ragMetadata: RagMetadata? = null,
    val ragReferencesLoading: Boolean = false,
    val executionSteps: List<ExecutionStep>? = null,
    val toolCalls: List<ToolCall>? = null,
    val pendingApprovalToolIds: List<String>? = null,
    val toolCallId: String? = null,
    val parentMessageId: String? = null,
    val name: String? = null,
    val planningTask: TaskState? = null,
    val isArchived: Boolean = false,
    val vectorizationStatus: String? = null,
    val layoutHeight: Double? = null,
    val toolResults: List<ToolResultArtifact>? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val isLongWait: Boolean = false,
    val loopCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** 模型已查看的工作区媒体描述；媒体字节由生成链路按需读取。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val viewedMedia: List<com.promenar.infinity.domain.media.ViewedMedia>? = null,
    /** 仅供模型使用的轮次原文上下文，与用户正文分别保存。 */
    val turnContext: String? = null,
    /** 生成该助手消息前发送给模型的尾部提醒。 */
    val requestInstruction: String? = null,
    /** 与 userImages 按位置对应；null 项表示未成功保存，继续沿用内联输入。 */
    val userImageReferences: List<WorkspaceDocumentReference?>? = null,
    /** 画板元数据与 userImages 按位置对应；普通图片保留 null 占位。 */
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val userImageAnnotations: List<com.promenar.infinity.domain.media.ImageAnnotation?>? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    val userImageAnnotationContext: com.promenar.infinity.data.media.annotation.ImageAnnotationContext? = null,
)

@Serializable
data class Session(
    val id: String,
    val agentId: String,
    val summary: String? = null,
    val title: String = "New Chat",
    val lastMessage: String? = null,
    val time: String? = null,
    val unread: Int = 0,
    val modelId: String? = null,
    val customPrompt: String? = null,
    val isPinned: Boolean = false,
    val scrollOffset: Double? = null,
    val draft: String? = null,
    val executionMode: String = "workspace",
    val loopStatus: LoopStatus = LoopStatus.IDLE,
    val pendingIntervention: String? = null,
    val approvalRequest: ApprovalRequest? = null,
    val messages: List<Message> = emptyList(),
    val ragOptions: RagOptions? = null,
    val inferenceParams: InferenceParams? = null,
    /** 旧版会话列的兼容字段，当前不再写入；活跃计划以 TaskRepository 为准。 */
    val activeTask: TaskState? = null,
    val stats: SessionStats? = null,
    val options: SessionOptions = SessionOptions(),
    val activeMcpServerIds: List<String> = emptyList(),
    val activeSkillIds: List<String> = emptyList(),
    val workspacePath: String? = null,
    val workspaceRootUuid: String? = null,
    val activeTaskTreeId: String? = null,
    /** 自选工作区；为空时使用会话私有工作区。 */
    val workspaceId: String? = null,
    val runMode: com.promenar.infinity.domain.model.SessionRunMode? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val parentSessionId: String? = null,
    val branchPlanJson: String? = null,
    val worktreeDirectory: String? = null,
    val worktreeBranch: String? = null,
    val worktreeBase: String? = null,
    val parentSessionDeleted: Boolean = false,
    val sessionAutoWakeCount: Int = 0,
    val sessionAutoProcessingPaused: Boolean = false,
)

enum class PhaseStatus {
    PENDING, ACTIVE, DONE
}

data class RagPhase(
    val id: String,
    val name: String,
    val status: PhaseStatus = PhaseStatus.PENDING,
    val progress: Int = 0,
    val detail: String? = null,
    val durationMs: Long? = null
)
