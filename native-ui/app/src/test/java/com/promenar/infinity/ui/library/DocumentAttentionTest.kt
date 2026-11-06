package com.promenar.infinity.ui.library

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.library.DocumentGraphState
import com.promenar.infinity.domain.library.GraphEstimate
import com.promenar.infinity.domain.library.PendingKind
import org.junit.Test

class DocumentAttentionTest {
    private val confirm = DocumentGraphState.ConfirmationRequired(
        GraphEstimate(chunks = 40, calls = 12, maxOutputTokens = 24_000, requiresConfirmation = true, modelDisplayName = null),
    )

    @Test
    fun `索引失败与过期分别对应重试与重建`() {
        assertThat(documentAttention(PendingKind.FAILED, DocumentGraphState.NotExtracted)).isEqualTo(DocumentAttention.INDEX_FAILED)
        assertThat(documentAttention(PendingKind.STALE, DocumentGraphState.Complete(3, 2))).isEqualTo(DocumentAttention.STALE)
    }

    @Test
    fun `索引问题优先于图谱确认`() {
        assertThat(documentAttention(PendingKind.FAILED, confirm)).isEqualTo(DocumentAttention.INDEX_FAILED)
        assertThat(documentAttention(PendingKind.STALE, confirm)).isEqualTo(DocumentAttention.STALE)
    }

    @Test
    fun `图谱等待确认用量时提示确认`() {
        assertThat(documentAttention(null, confirm)).isEqualTo(DocumentAttention.GRAPH_CONFIRM)
        assertThat(documentAttention(PendingKind.GRAPH_ATTENTION, confirm)).isEqualTo(DocumentAttention.GRAPH_CONFIRM)
    }

    @Test
    fun `排队或索引中不提示`() {
        assertThat(documentAttention(PendingKind.QUEUED, confirm)).isNull()
        assertThat(documentAttention(PendingKind.INDEXING, confirm)).isNull()
    }

    @Test
    fun `状态解除后不再提示`() {
        assertThat(documentAttention(null, DocumentGraphState.Complete(3, 2))).isNull()
        assertThat(documentAttention(null, null)).isNull()
        assertThat(documentAttention(PendingKind.GRAPH_ATTENTION, DocumentGraphState.Failed("超时"))).isNull()
    }
}
