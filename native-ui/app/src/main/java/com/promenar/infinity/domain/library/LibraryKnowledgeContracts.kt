package com.promenar.infinity.domain.library

import com.promenar.infinity.domain.files.FileRef
import kotlinx.coroutines.flow.Flow

/*
 * 资料页知识层的数据契约：概览、待处理、最近、文档的“智能体视角”与对话片段。
 * 长期记忆沿用 LongTermMemoryViewModel 的数据层，这里只补“新记住的”标记。
 */

data class LibraryOverview(
    /** 已完成索引的存储空间文档数。 */
    val indexedDocuments: Int,
    val longTermMemories: Int,
    /** 近 7 天新增且用户尚未在资料页看过的长期记忆数。 */
    val newLongTermMemories: Int,
    val graphEntities: Int,
    val graphRelations: Int,
    /** 对话片段总数。 */
    val fragments: Int,
)

enum class PendingKind { FAILED, QUEUED, INDEXING, STALE, GRAPH_ATTENTION }
enum class PendingStage { PREPARING, VECTORIZING, EXTRACTING_GRAPH, SAVING }

/** 全量图谱抽取的基础调用估算；解析失败后的拆分请求会产生额外用量。 */
data class GraphEstimate(
    val chunks: Int,
    val calls: Int,
    val maxOutputTokens: Long,
    val requiresConfirmation: Boolean,
    val modelDisplayName: String?,
)

enum class GraphStopReason { CALLS, OUTPUT_TOKENS, DURATION }

sealed interface DocumentGraphState {
    data object NotExtracted : DocumentGraphState
    data class Extracting(val processed: Int?, val total: Int?) : DocumentGraphState
    data class Complete(val entities: Int, val relations: Int) : DocumentGraphState
    data class Partial(val processed: Int, val total: Int, val reason: GraphStopReason) : DocumentGraphState
    data class ConfirmationRequired(val estimate: GraphEstimate) : DocumentGraphState
    data class Failed(val reason: String) : DocumentGraphState
}

/** 需要用户处理或正在处理的存储空间文档。 */
data class PendingDocument(
    val documentId: String,
    val ref: FileRef,
    val name: String,
    /** 所在文件夹的显示路径，例如“存储空间 › 产品调研”。 */
    val folderPath: String,
    val kind: PendingKind,
    /** 失败原因（可读）；其它状态为 null。 */
    val reason: String? = null,
    /** 排队位置，从 1 开始；非排队状态为 null。 */
    val queuePosition: Int? = null,
    /** 正在执行的索引阶段；非进行中为 null。 */
    val stage: PendingStage? = null,
    /** 当前阶段已处理和总切块数；无法确定时为 null。 */
    val processed: Int? = null,
    val total: Int? = null,
    /** 最近一次可读错误，重试期间仍可展示。 */
    val lastError: String? = null,
    /** [PendingKind.GRAPH_ATTENTION] 中属于“图谱等待确认用量”的一类；为假时是图谱失败或预算停止。 */
    val graphConfirmation: Boolean = false,
)

data class LibraryDocumentSummary(
    val documentId: String,
    val ref: FileRef,
    val name: String,
    val folderPath: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val lastModified: Long,
)

/** 文档页“智能体视角”所需信息。 */
data class DocumentInsight(
    val documentId: String,
    val ref: FileRef,
    val name: String,
    val folderPath: String,
    val mimeType: String?,
    val sizeBytes: Long,
    val lastModified: Long,
    val pending: PendingKind?,
    val failureReason: String?,
    val chunkCount: Int,
    /** 从该文档抽取的图谱实体名，按出现次数降序。 */
    val entities: List<String>,
    /** 来源描述，例如“手动上传”“分享导入”“从对话保存”；未知为 null。 */
    val source: String?,
    /** 历史版本数，没有版本记录时为 0。 */
    val versionCount: Int,
    val graph: DocumentGraphState = DocumentGraphState.NotExtracted,
)

/** 可阅读的正文；PDF、DOCX 等为提取后的文本。 */
data class DocumentBody(
    val text: String,
    val markdown: Boolean,
    /** 正文来自解析提取（而非原文本文件）。 */
    val extracted: Boolean,
    val truncated: Boolean,
)

data class FragmentGroup(
    val sessionId: String,
    /** 会话已删除时为 null，界面显示“已删除的会话”。 */
    val sessionTitle: String?,
    val agentName: String?,
    val fragmentCount: Int,
    val lastAt: Long,
)

data class ConversationFragment(
    val id: String,
    val sessionId: String,
    val content: String,
    val createdAt: Long,
)

sealed interface LibraryActionResult {
    data object Done : LibraryActionResult
    data class Failed(val reason: String) : LibraryActionResult
}

interface LibraryKnowledgeSource {
    fun observeOverview(): Flow<LibraryOverview>
    fun observePending(): Flow<List<PendingDocument>>

    /** 存储空间里最近修改或加入的文档，按时间降序。 */
    fun observeRecent(limit: Int = 50): Flow<List<LibraryDocumentSummary>>

    /** 失败文档重试解析与索引；可换用备用解析器。 */
    suspend fun retry(documentId: String): LibraryActionResult

    /** 内容已改动但索引过旧的文档重建索引。 */
    suspend fun rebuild(documentId: String): LibraryActionResult

    /** 估算该文档全量抽取图谱的调用次数与输出上限；文档未索引或无正文时为 null。 */
    suspend fun graphEstimate(documentId: String): GraphEstimate?

    /** 只抽取图谱，不重新向量化；confirmed 为真时越过大文档确认门槛。 */
    suspend fun extractGraph(documentId: String, confirmed: Boolean): LibraryActionResult

    suspend fun insight(documentId: String): DocumentInsight?
    suspend fun body(documentId: String, maxChars: Int = 200_000): DocumentBody?

    /** 文件引用对应的存储空间文档 id；不在存储空间或尚未登记时为 null。 */
    suspend fun documentIdFor(ref: FileRef): String?

    fun observeFragmentGroups(): Flow<List<FragmentGroup>>
    fun observeFragments(sessionId: String): Flow<List<ConversationFragment>>
    suspend fun deleteFragment(id: String)
    suspend fun clearFragments(sessionId: String)

    /** 近 7 天新增且未看过的长期记忆 id。 */
    fun observeNewMemoryIds(): Flow<Set<String>>

    /** 用户打开长期记忆页后调用，清除“新”标记。 */
    suspend fun markMemoriesSeen()
}
