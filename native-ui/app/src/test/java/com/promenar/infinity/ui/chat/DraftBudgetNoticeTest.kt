package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.DraftBudgetStatus
import com.promenar.infinity.data.generation.DraftDocumentBudget
import com.promenar.infinity.data.generation.DraftDocumentTokens
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import org.junit.jupiter.api.Test

class DraftBudgetNoticeTest {

    private fun doc(id: String, inWorkspace: Boolean = false) = MessageDocumentAttachment(
        id = id, name = "$id.md", mimeType = "text/markdown", content = "", sizeBytes = 1, sha256 = id, estimatedTokens = 1,
        workspaceReference = if (inWorkspace) WorkspaceDocumentReference("/session/attachments/$id.md", 1, 1) else null,
    )

    private fun budget(
        status: DraftBudgetStatus,
        tokens: Map<String, Int>,
        limit: Long? = 100_000,
        oversized: Set<String> = emptySet(),
        overflow: Set<String> = emptySet(),
        combined: Boolean = false,
        inline: Set<String> = tokens.keys,
    ) = DraftDocumentBudget(
        status = status,
        documents = tokens.map { (id, value) -> DraftDocumentTokens(id, value, id in inline) },
        currentUsedTokens = 20_000,
        estimatedTotalTokens = 20_000 + tokens.values.sum(),
        inputBudgetTokens = limit,
        availableTokens = limit?.minus(20_000),
        excessTokens = 0,
        individuallyOversizedDocumentIds = oversized,
        overflowDocumentIds = overflow,
        combinedOverflow = combined,
    )

    @Test
    fun `放得下或预算缺失时不提示`() {
        val docs = listOf(doc("a"))
        assertThat(draftBudgetNotice(null, docs)).isNull()
        assertThat(draftBudgetNotice(budget(DraftBudgetStatus.FITS, mapOf("a" to 500)), docs)).isNull()
    }

    @Test
    fun `单份文档自身超过预算时只标该份并拦下发送`() {
        val notice = draftBudgetNotice(
            budget(DraftBudgetStatus.EXCEEDED, mapOf("big" to 312_000, "small" to 800), oversized = setOf("big"), overflow = setOf("big", "small")),
            listOf(doc("big"), doc("small")),
        )!!
        assertThat(notice.kind).isEqualTo(DraftBudgetNoticeKind.EXCEEDED)
        assertThat(notice.flaggedDocumentIds).containsExactly("big")
        assertThat(notice.blocksSend).isTrue()
        assertThat(notice.budgetTokens).isEqualTo(100_000)
    }

    @Test
    fun `每份放得下但合计放不下时标出全部并提示合计`() {
        val notice = draftBudgetNotice(
            budget(DraftBudgetStatus.EXCEEDED, mapOf("a" to 60_000, "b" to 60_000), overflow = setOf("a", "b"), combined = true),
            listOf(doc("a"), doc("b")),
        )!!
        assertThat(notice.kind).isEqualTo(DraftBudgetNoticeKind.COMBINED)
        assertThat(notice.flaggedDocumentIds).containsExactly("a", "b")
        assertThat(notice.blocksSend).isTrue()
    }

    @Test
    fun `文档本身放得下而加上历史才超出时提示压缩但允许发送`() {
        val notice = draftBudgetNotice(
            budget(DraftBudgetStatus.EXCEEDED, mapOf("a" to 90_000), oversized = setOf("a"), overflow = setOf("a")),
            listOf(doc("a")),
        )!!
        assertThat(notice.kind).isEqualTo(DraftBudgetNoticeKind.COMPACTABLE)
        assertThat(notice.blocksSend).isFalse()
    }

    @Test
    fun `窗口未知时只对内联文档提示并拦下发送`() {
        val notice = draftBudgetNotice(
            budget(DraftBudgetStatus.UNKNOWN_WINDOW, mapOf("a" to 500, "saved" to 40), limit = null, inline = setOf("a")),
            listOf(doc("a"), doc("saved", inWorkspace = true)),
        )!!
        assertThat(notice.kind).isEqualTo(DraftBudgetNoticeKind.UNKNOWN_WINDOW)
        assertThat(notice.flaggedDocumentIds).containsExactly("a")
        assertThat(notice.blocksSend).isTrue()
        // 没有内联文档时，窗口未知不影响发送。
        assertThat(draftBudgetNotice(budget(DraftBudgetStatus.UNKNOWN_WINDOW, emptyMap(), limit = null), emptyList())).isNull()
    }

    @Test
    fun `预算未重算时已转存或已移除的文档不再标警示`() {
        val stale = budget(DraftBudgetStatus.EXCEEDED, mapOf("big" to 312_000), oversized = setOf("big"), overflow = setOf("big"))
        assertThat(draftBudgetNotice(stale, listOf(doc("big", inWorkspace = true)))).isNull()
        assertThat(draftBudgetNotice(stale, emptyList())).isNull()
    }
}
