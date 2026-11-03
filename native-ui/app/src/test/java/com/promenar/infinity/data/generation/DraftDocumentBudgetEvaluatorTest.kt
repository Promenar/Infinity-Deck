package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.protocol.*
import org.junit.Test

class DraftDocumentBudgetEvaluatorTest {
    private val session = Session("s", "a")
    private val prompt = PromptRequest(model = "model", messages = listOf(ProtocolMessage("system", "规则")))
    private fun document(id: String, content: String) = MessageDocumentAttachment(id, "$id.txt", "text/plain", content,
        content.toByteArray().size.toLong(), "hash-$id", 999999)
    private fun route(window: PromptWindow) = DraftBudgetContext("provider::model", prompt, window)

    @Test fun `草稿预算区分放得下超出及未知窗口且不信任附件旧预留值`() {
        val doc = document("a", "小文档")
        val fits = DraftDocumentBudgetEvaluator.evaluate(session, route(PromptWindow(128000, 1024)), "问题", listOf(doc))
        assertThat(fits.status).isEqualTo(DraftBudgetStatus.FITS)
        assertThat(fits.documents.single().tokens).isLessThan(100)
        val exceeded = DraftDocumentBudgetEvaluator.evaluate(session, route(PromptWindow(2000, 256)), "问题", listOf(document("large", "中".repeat(2000))))
        assertThat(exceeded.status).isEqualTo(DraftBudgetStatus.EXCEEDED)
        assertThat(exceeded.individuallyOversizedDocumentIds).containsExactly("large")
        assertThat(exceeded.excessTokens).isGreaterThan(0)
        val unknown = DraftDocumentBudgetEvaluator.evaluate(session, route(PromptWindow(null, 1024)), "", listOf(doc))
        assertThat(unknown.status).isEqualTo(DraftBudgetStatus.UNKNOWN_WINDOW)
        assertThat(unknown.availableTokens).isNull()
    }

    @Test fun `各文档单独可放下但合计超出时标记组合与参与文档`() {
        val result = DraftDocumentBudgetEvaluator.evaluate(session, route(PromptWindow(2400, 256)), "",
            listOf(document("a", "中".repeat(800)), document("b", "中".repeat(800))))
        assertThat(result.status).isEqualTo(DraftBudgetStatus.EXCEEDED)
        assertThat(result.individuallyOversizedDocumentIds).isEmpty()
        assertThat(result.combinedOverflow).isTrue()
        assertThat(result.overflowDocumentIds).containsExactly("a", "b")
    }

    @Test fun `当前用量取校准值且工作区引用只计说明不计正文`() {
        val model = route(PromptWindow(8000, 1024))
        val calibrated = session.copy(stats = SessionStats(contextUsageBaseline = PromptUsageCalibration.capture(session, prompt, 1000, model.modelId)))
        val reference = document("reference", "测试正文".repeat(20000)).copy(workspaceReference = WorkspaceDocumentReference("/session/attachments/reference.txt", 20, 50000,
            sourceSessionId = session.id, isPrivate = true, linuxNamespace = false))
        val result = DraftDocumentBudgetEvaluator.evaluate(calibrated, model, "问题", listOf(reference))
        assertThat(result.currentUsedTokens).isEqualTo(1000)
        assertThat(result.estimatedTotalTokens).isLessThan(1500)
        assertThat(result.documents.single().inline).isFalse()
        assertThat(result.overflowDocumentIds).isEmpty()
    }
}
