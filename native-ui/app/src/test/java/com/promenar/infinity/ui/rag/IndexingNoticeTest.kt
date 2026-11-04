package com.promenar.infinity.ui.rag

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.VectorizationTask
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.UiStatusNotice
import org.junit.jupiter.api.Test

class IndexingNoticeTest {

    @Test
    fun `部分KG进度以类型化参数显示且无需回显诊断文本`() {
        val notice = IndexingNotice.fromTask(VectorizationTask(
            id = "kg-partial", type = "document_reference", status = "partial", kgStrategy = "full",
            lastChunkIndex = 2, totalChunks = 5, error = "kg.budget.calls", subStatus = "内部诊断",
        ))

        assertThat(notice.formatArgs).containsExactly("2", "5").inOrder()
        assertThat(IndexingNotice.template(notice)!!.resourceId).isEqualTo(R.string.kg_extraction_partial)
    }

    @Test
    fun `fromTask maps vectorizing status to typed args`() {
        val notice = IndexingNotice.fromTask(
            VectorizationTask(
                id = "task-vectorizing",
                type = "document",
                status = "vectorizing",
                totalChunks = 20,
            )
        )

        assertThat(notice.code).isEqualTo(IndexingNotice.CODE_VECTORIZING)
        assertThat(notice.severity).isEqualTo(NoticeSeverity.Info)
        assertThat(notice.formatArgs).containsExactly("20")
        assertThat(notice.technical).isNull()
    }

    @Test
    fun `fromTask converts unknown status to stable fallback code`() {
        val notice = IndexingNotice.fromTask(
            VectorizationTask(
                id = "task-unknown",
                type = "document",
                status = "legacy_unknown_state",
                error = "legacy queue message",
                subStatus = "legacy_sub"
            )
        )

        assertThat(notice.code).isEqualTo(IndexingNotice.CODE_UNKNOWN)
        assertThat(notice.severity).isEqualTo(NoticeSeverity.Warning)
        assertThat(notice.technical).isEqualTo("legacy_unknown_state | legacy queue message | legacy_sub")
    }

    @Test
    fun `template returns unknown for unhandled code`() {
        assertThat(IndexingNotice.template(UiStatusNotice(NoticeSeverity.Info, IndexingNotice.CODE_UNKNOWN))).isNull()
    }

    @Test
    fun `importFailed keeps code and moves message to technical`() {
        val technical = "文件导入器异常"
        val notice = IndexingNotice.importFailed(technical)

        assertThat(notice.code).isEqualTo(IndexingNotice.CODE_IMPORT_FAILED)
        assertThat(notice.severity).isEqualTo(NoticeSeverity.Error)
        assertThat(notice.technical).isEqualTo(technical)
    }

    @Test
    fun `template maps known warning code to retry resource`() {
        val notice = UiStatusNotice(NoticeSeverity.Warning, IndexingNotice.CODE_WARNING)
        val resolved = IndexingNotice.template(notice)

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.resourceId).isEqualTo(R.string.rag_index_phase_warning)
    }

    @Test
    fun `删除失败使用RAG专用本地化typed notice而不是通用失败`() {
        val notice = UiStatusNotice(NoticeSeverity.Error, IndexingNotice.CODE_DELETE_FAILED)

        val resolved = IndexingNotice.template(notice)

        assertThat(resolved).isNotNull()
        assertThat(resolved!!.resourceId).isEqualTo(R.string.permanent_delete_rag_failed)
    }
}
