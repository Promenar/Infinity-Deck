package com.promenar.infinity.data.agent

import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import kotlinx.serialization.Serializable

@Serializable
data class AgentRagConfig(
    val docChunkSize: Int = 800,
    val chunkOverlap: Int = 100,
    val memoryChunkSize: Int = 1000,
    val summaryTemplate: String = "",
    val currentPreset: String? = null
)

@Serializable
enum class AgentRetrievalMode { AUTO, TOOL, BOTH }

@Serializable
enum class KnowledgeFileAccess { OFF, ON_REQUEST, ALWAYS }

@Serializable
data class AgentRetrievalConfig(
    val memoryLimit: Int = 5,
    val memoryThreshold: Float = 0.7f,
    val docLimit: Int = 8,
    val docThreshold: Float = 0.45f,
    val enableRerank: Boolean = true,
    val rerankTopK: Int = 30,
    val rerankFinalK: Int = 5,
    val enableQueryRewrite: Boolean = false,
    val queryRewriteStrategy: String = "multi-query",
    val queryRewriteCount: Int = 3,
    val enableHybridSearch: Boolean = true,
    val hybridAlpha: Float = 0.6f,
    val hybridBM25Boost: Float = 1.0f,
    val enableMemory: Boolean = true,
    val enableDocs: Boolean = true,
    val enableKnowledgeGraph: Boolean = false,
    val queryRewriteModel: String? = null,
    val kgExtractionModel: String? = null,
    val kgExtractionPrompt: String? = null,
    val kgEntityTypes: List<String> = emptyList(),
    val kgFreeMode: Boolean = false,
    val kgDomainAuto: Boolean = false,
    val kgDomainHint: String? = null,
    val kgExtractionTimeoutSeconds: Int = 120,
    val jitMaxChunks: Int = 0,
    /** 助手自身的记忆权限；即使检索参数继承全局设置，也按助手单独保存。 */
    val memoryPolicy: AgentMemoryPolicy = AgentMemoryPolicy(),
    val retrievalMode: AgentRetrievalMode = AgentRetrievalMode.AUTO,
    val knowledgeFileAccess: KnowledgeFileAccess = KnowledgeFileAccess.ON_REQUEST,
    val subagentModels: AgentSubagentModelPolicy = AgentSubagentModelPolicy(),
)

/** 智能体的子代理模型设置；默认跟随会话模型，且不允许按任务换模型。 */
@Serializable
data class AgentSubagentModelPolicy(
    /** 子代理默认模型；null 表示跟随会话模型。 */
    val defaultModelId: String? = null,
    /** 允许智能体在 agent_task 的 task.model 中挑选模型。 */
    val allowPerTaskModel: Boolean = false,
    /** 允许智能体挑选的模型 ID，由用户勾选。 */
    val selectableModelIds: List<String> = emptyList(),
)

/**
 * 助手级记忆权限：新会话的默认档位与会话可选的上限。
 * [shareWithOtherAgents] 关闭即私密助手，其他助手的“全部助手”档不会读取本助手的对话记忆。
 */
@Serializable
data class AgentMemoryPolicy(
    val memoryDefault: ConversationScope = ConversationScope.AGENT,
    val memoryCap: ConversationScope = ConversationScope.ALL_AGENTS,
    val graphDefault: ConversationScope = ConversationScope.AGENT,
    val graphCap: ConversationScope = ConversationScope.ALL_AGENTS,
    val documentDefault: DocumentScope = DocumentScope.KNOWLEDGE_BASE,
    val documentCap: DocumentScope = DocumentScope.ALL_INDEXED,
    val shareWithOtherAgents: Boolean = true,
) {
    /** 上限调低时默认档位随之收紧，保证默认值始终可选。 */
    fun normalized(): AgentMemoryPolicy = copy(
        memoryDefault = minOf(memoryDefault, memoryCap),
        documentDefault = minOf(documentDefault, documentCap),
    )

    fun defaultsFor(options: RagOptions): RagOptions = normalized().let { policy ->
        options.copy(
            memoryScope = policy.memoryDefault,
            documentScope = policy.documentDefault,
        )
    }

    /** 会话档位超出上限时按上限执行；会话保存的选择不被改写，助手上限放宽后自动恢复。 */
    fun clamp(options: RagOptions): RagOptions = options.copy(
        memoryScope = minOf(options.effectiveMemoryScope, memoryCap),
        documentScope = minOf(options.effectiveDocumentScope, documentCap),
    )
}
