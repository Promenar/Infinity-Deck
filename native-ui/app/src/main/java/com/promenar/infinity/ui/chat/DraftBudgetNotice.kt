package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.generation.DraftBudgetStatus
import com.promenar.infinity.data.generation.DraftDocumentBudget
import com.promenar.infinity.data.model.MessageDocumentAttachment

/** 附件条超限提示的文案类型。 */
internal enum class DraftBudgetNoticeKind {
    /** 单份文档放不下。 */
    EXCEEDED,
    /** 每份都放得下，合在一起放不下。 */
    COMBINED,
    /** 文档本身放得下，加上较早对话才超出；发送时运行时会先压缩历史。 */
    COMPACTABLE,
    /** 模型未提供上下文窗口，运行时对完整文档失败关闭。 */
    UNKNOWN_WINDOW,
}

internal data class DraftBudgetNotice(
    val kind: DraftBudgetNoticeKind,
    /** 标警示色、点“存入工作区”时转存的文档。 */
    val flaggedDocumentIds: Set<String>,
    val estimatedTokens: Int,
    val budgetTokens: Long?,
) {
    /** 压缩较早对话也救不回来时，发送键只闪提示，不发出必然被拒的请求。 */
    val blocksSend: Boolean get() = kind != DraftBudgetNoticeKind.COMPACTABLE
}

/**
 * 由草稿预算得出附件条提示；只看当前仍内联的草稿文档，
 * 预算尚未按最新草稿重算时，已转存或已移除的文档也不会继续标警示。
 */
internal fun draftBudgetNotice(
    budget: DraftDocumentBudget?,
    documents: List<MessageDocumentAttachment>,
): DraftBudgetNotice? {
    if (budget == null) return null
    val inlineIds = documents.filter { it.workspaceReference == null }.mapTo(linkedSetOf()) { it.id }
    val inline = budget.documents.filter { it.inline && it.tokens > 0 && it.documentId in inlineIds }
    if (inline.isEmpty()) return null
    return when (budget.status) {
        DraftBudgetStatus.FITS -> null
        DraftBudgetStatus.UNKNOWN_WINDOW -> DraftBudgetNotice(
            DraftBudgetNoticeKind.UNKNOWN_WINDOW,
            inline.mapTo(linkedSetOf()) { it.documentId },
            budget.estimatedTotalTokens,
            null,
        )
        DraftBudgetStatus.EXCEEDED -> {
            val limit = budget.inputBudgetTokens ?: return null
            val oversized = budget.individuallyOversizedDocumentIds.filterTo(linkedSetOf()) { it in inlineIds }
            val flagged = oversized.ifEmpty { budget.overflowDocumentIds.filterTo(linkedSetOf()) { it in inlineIds } }
            if (flagged.isEmpty()) return null
            val flaggedTokens = inline.filter { it.documentId in flagged }.sumOf { it.tokens.toLong() }
            val kind = when {
                flaggedTokens <= limit -> DraftBudgetNoticeKind.COMPACTABLE
                oversized.isEmpty() && flagged.size > 1 -> DraftBudgetNoticeKind.COMBINED
                else -> DraftBudgetNoticeKind.EXCEEDED
            }
            DraftBudgetNotice(kind, flagged, budget.estimatedTotalTokens, limit)
        }
    }
}
