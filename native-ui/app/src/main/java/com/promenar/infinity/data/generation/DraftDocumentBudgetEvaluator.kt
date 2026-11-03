package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.FullContextDocumentFormatter
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage

/** prompt 是当前已存上下文的只读快照，模型 ID 使用稳定路由 ID。 */
data class DraftBudgetContext(
    val modelId: String,
    val prompt: PromptRequest,
    val window: PromptWindow,
    /** 新用户轮开始后的历史副本；旧附件按发送路径转引用，不包含草稿本身。 */
    val historyForNextTurn: PromptRequest? = null,
)
enum class DraftBudgetStatus { FITS, EXCEEDED, UNKNOWN_WINDOW }
data class DraftDocumentTokens(val documentId: String, val tokens: Int, val inline: Boolean)
data class DraftDocumentBudget(
    val status: DraftBudgetStatus,
    val documents: List<DraftDocumentTokens>,
    val currentUsedTokens: Int,
    val estimatedTotalTokens: Int,
    val inputBudgetTokens: Long?,
    val availableTokens: Long?,
    val excessTokens: Long,
    val individuallyOversizedDocumentIds: Set<String>,
    val overflowDocumentIds: Set<String>,
    val combinedOverflow: Boolean,
)

/** 纯预算函数；附件正文、模型包装与引用说明均经发送路径的格式化和统一估算入口。 */
object DraftDocumentBudgetEvaluator {
    fun evaluate(session: Session, model: DraftBudgetContext, draftText: String,
                 documents: List<MessageDocumentAttachment>): DraftDocumentBudget {
        val history = model.historyForNextTurn ?: model.prompt
        fun total(docs: List<MessageDocumentAttachment>) = PromptUsageCalibration.estimate(session, history.copy(
            messages = history.messages + ProtocolMessage("user", FullContextDocumentFormatter.appendToUserContent(draftText, docs, session))), model.modelId)
        val used = PromptUsageCalibration.estimate(session, model.prompt, model.modelId)
        val base = total(emptyList())
        val estimated = if (draftText.isEmpty() && documents.isEmpty()) used else total(documents)
        val estimates = documents.map { document -> DraftDocumentTokens(document.id,
            (ApproximateTokenEstimator.estimate(FullContextDocumentFormatter.appendToUserContent("", listOf(document), session))),
            document.workspaceReference == null || document.inlineInCurrentTurn) }
        val budget = model.window.inputBudgetTokens
        val excess = budget?.let { (estimated - it).coerceAtLeast(0) } ?: 0
        val individual = if (budget == null) emptySet() else documents.filter { (it.workspaceReference == null || it.inlineInCurrentTurn) && total(listOf(it)) > budget }
            .mapTo(linkedSetOf()) { it.id }
        val contributors = if (excess > 0) estimates.filter { it.inline && it.tokens > 0 }.mapTo(linkedSetOf()) { it.documentId } else emptySet()
        return DraftDocumentBudget(
            when { budget == null -> DraftBudgetStatus.UNKNOWN_WINDOW; excess > 0 -> DraftBudgetStatus.EXCEEDED; else -> DraftBudgetStatus.FITS },
            estimates, used, estimated, budget, budget?.let { (it - base).coerceAtLeast(0) }, excess, individual, contributors,
            excess > 0 && individual.isEmpty() && contributors.size > 1 && base <= (budget ?: 0),
        )
    }
}
