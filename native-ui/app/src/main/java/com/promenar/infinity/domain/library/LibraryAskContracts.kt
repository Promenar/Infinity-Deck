package com.promenar.infinity.domain.library

import kotlinx.coroutines.flow.Flow

/*
 * 问资料与引用记录的数据契约。
 * 输入时只走 [LibraryAskService.keywordSearch]（本机、不计费）；按搜索键走 [LibraryAskService.ask]：
 * 语义检索（1 次 Embedding）与关键词融合，再用快速模型生成带编号引用的答案（1 次 LLM）。
 * 快速模型为空时不生成答案；向量模型也未设置时只做关键词匹配。不回落到任何会话或智能体的模型。
 */

/** 检索范围：默认全部已索引文档，可收窄到存储空间或某个工作区根。 */
sealed interface AskScope {
    data object AllIndexed : AskScope
    data object Library : AskScope
    data class Workspace(val workspaceRootUuid: String, val title: String) : AskScope
}

enum class AskHitKind { DOCUMENT, MEMORY, ENTITY }

/** 命中方式：界面标“语义 0.82”“关键词”或“语义 + 关键词”。 */
enum class AskMatch { SEMANTIC, KEYWORD, BOTH }

data class AskHit(
    /** 稳定键：文档为 vectors.id 或 `file:<documentId>`，记忆为 `memory:<id>`，实体为 `entity:<nodeId>`。 */
    val key: String,
    val kind: AskHitKind,
    val title: String,
    /** 位置描述，例如“存储空间 · 架构”“工作区 Notes · 会议”“Infinity · 2 天前”。 */
    val location: String,
    /** 摘录，最多约 240 字。 */
    val snippet: String,
    /** 摘录中需要高亮的词（按出现顺序去重）。 */
    val highlightTerms: List<String>,
    val match: AskMatch,
    /** 语义相似度 0–1；纯关键词命中为 null。 */
    val similarity: Float?,
    /** 文档命中：资料文档 id（workspace_files uuid），用于打开文档页。 */
    val documentId: String? = null,
    /** 文档命中：分块序号与分块原文，用于在正文中定位。 */
    val chunkIndex: Int? = null,
    val chunkContent: String? = null,
    /** 记忆命中：长期记忆 id。 */
    val memoryId: String? = null,
    /** 实体命中：图谱节点 id。 */
    val entityId: String? = null,
)

/** 检索模式：决定界面是否显示答案卡与降级说明。 */
enum class AskMode {
    /** 语义 + 关键词，并生成答案。 */
    FULL,
    /** 未设置快速模型：语义 + 关键词，不生成答案。 */
    NO_QUICK_MODEL,
    /** 未设置向量模型：只有关键词，不生成答案。 */
    KEYWORD_ONLY,
}

enum class AskErrorCode { EMBEDDING_FAILED, MODEL_FAILED, NETWORK, CANCELLED, UNKNOWN }

sealed interface AskEvent {
    data object Retrieving : AskEvent
    /** 检索完成；[hits] 已排序，编号即下标 + 1。 */
    data class Hits(val hits: List<AskHit>, val mode: AskMode) : AskEvent
    /** 答案流式片段（追加）。 */
    data class AnswerDelta(val text: String) : AskEvent
    /**
     * 答案完成。[text] 为完整答案，引用标记形如 `[1]`、`[2]`；
     * [citedHitIndexes] 为答案里实际出现且有效的命中下标（从 0 起，去重、按首次出现排序）。
     * [noAnswer] 为真表示模型判断资料里没有能回答的内容。
     */
    data class AnswerDone(
        val text: String,
        val citedHitIndexes: List<Int>,
        val modelLabel: String,
        val noAnswer: Boolean,
    ) : AskEvent
    /** 检索或作答失败；已发出的 [Hits] 仍然有效。 */
    data class Failed(val code: AskErrorCode, val message: String?) : AskEvent
}

interface LibraryAskService {
    /** 当前设置下按搜索键会走的模式；用于输入中提示与降级卡。 */
    fun observeMode(): Flow<AskMode>

    /** 本机关键词匹配：文档 FTS + 文件名 + 长期记忆标题与正文。不调用任何模型。 */
    suspend fun keywordSearch(query: String, scope: AskScope, limit: Int = 20): List<AskHit>

    /** 语义检索并作答。收集方取消即停止请求；同一时刻界面只保留最新一次。 */
    fun ask(query: String, scope: AskScope): Flow<AskEvent>
}

/** 在正文中定位分块：先精确查找，再按空白归一化查找，失败时退回首个关键词位置。找不到返回 null。 */
fun interface ChunkLocator {
    fun locate(body: String, chunkContent: String, fallbackTerms: List<String>): IntRange?
}

enum class CitationSource { CHAT, ASK }

data class DocumentCitation(
    val documentId: String,
    val chunkIndex: Int?,
    val source: CitationSource,
    /** 对话引用：所在会话与消息；问资料引用为 null。 */
    val sessionId: String?,
    val sessionTitle: String?,
    val messageId: String?,
    /** 对话里的编号（如 “K2”）或问资料答案里的编号（如 “1”）。 */
    val label: String,
    /** 问资料引用：当时的问题。 */
    val askQuery: String?,
    /** 对话引用：所用智能体名；未知为 null。 */
    val agentName: String?,
    val citedAt: Long,
)

data class CitedDocument(
    val documentId: String,
    val name: String,
    val count: Int,
    val lastCitedAt: Long,
    /** 最近一次引用所在会话标题；问资料引用为 null。 */
    val lastSessionTitle: String?,
    val lastAgentName: String?,
)

interface KnowledgeCitationRepository {
    /** 文档页“智能体怎么用它”：按时间倒序。 */
    fun observeForDocument(documentId: String, limit: Int = 20): Flow<List<DocumentCitation>>

    /** 概览“智能体最近用到”：按最近引用时间倒序。 */
    fun observeRecentDocuments(limit: Int = 5): Flow<List<CitedDocument>>

    /** 视图“智能体常用”：按引用次数降序，次数相同按最近时间。 */
    fun observeTopDocuments(limit: Int = 50): Flow<List<CitedDocument>>
}
