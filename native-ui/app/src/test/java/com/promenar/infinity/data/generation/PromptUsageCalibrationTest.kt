package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionStats
import com.promenar.infinity.data.model.json
import com.promenar.infinity.data.remote.protocol.*
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.junit.Test

class PromptUsageCalibrationTest {
    private val session = Session("session", "agent", modelId = "provider::model")
    private val original = PromptRequest(model = "model", messages = listOf(
        ProtocolMessage("system", "系统规则"), ProtocolMessage("user", "输入")))

    private fun withBaseline(prompt: PromptRequest = original, input: Int = 1000): Session = session.copy(
        stats = SessionStats(contextUsageBaseline = PromptUsageCalibration.capture(session, prompt, input, session.modelId)))

    @Test fun `服务端基线加新增消息与工具结果的估算`() {
        val additions = listOf(ProtocolMessage("assistant", "回复", reasoning = "思考",
            toolCalls = listOf(ProtocolToolCall("call", "read", "{}"))), ProtocolMessage("tool", "工具结果", toolCallId = "call"))
        val next = original.copy(messages = original.messages + additions)
        assertThat(PromptUsageCalibration.estimate(withBaseline(), next, session.modelId))
            .isEqualTo(1000 + additions.sumOf(ApproximateTokenEstimator::estimate))
    }

    @Test fun `首轮模型切换分支摘要历史与工具变化均回退完整估算`() {
        val calibrated = withBaseline()
        val tools = listOf(ProtocolTool(function = ProtocolToolFunction("read", "读取", "{}")))
        val cases = listOf(session to original, calibrated.copy(id = "branch") to original,
            calibrated.copy(summary = "新摘要") to original, calibrated to original.copy(model = "different"),
            calibrated to original.copy(messages = original.messages.take(1)),
            calibrated to original.copy(messages = original.messages.map { it.copy(content = "修改正文") }),
            calibrated to original.copy(tools = tools))
        cases.forEach { (s, p) -> assertThat(PromptUsageCalibration.estimate(s, p, session.modelId))
            .isEqualTo(ApproximateTokenEstimator.estimate(p)) }
        assertThat(PromptUsageCalibration.estimate(calibrated, original, "different-provider::model"))
            .isEqualTo(ApproximateTokenEstimator.estimate(original))
    }

    @Test fun `基线持久化后重启可复用且工具多轮只保留最后请求`() = runTest {
        var persisted = session
        val save: suspend (com.promenar.infinity.data.model.PromptUsageBaseline?) -> Unit = { baseline ->
            val stats = (persisted.stats ?: SessionStats()).copy(contextUsageBaseline = baseline)
            persisted = persisted.copy(stats = json.decodeFromString<SessionStats>(json.encodeToString(stats)))
        }
        val first = RequestInputUsageRecorder(session, original, session.modelId, save)
        first.start()
        first.observe(ProtocolUsage(input = 1200))
        first.observe(ProtocolUsage(input = 1300))
        assertThat(PromptUsageCalibration.estimate(persisted, original, session.modelId)).isEqualTo(1300)
        val next = original.copy(messages = original.messages + ProtocolMessage("assistant", "上一轮输出"))
        val second = RequestInputUsageRecorder(persisted, next, session.modelId, save)
        second.start()
        assertThat(persisted.stats?.contextUsageBaseline).isNull()
        second.observe(ProtocolUsage(input = 0, output = 100, inputReported = false))
        assertThat(PromptUsageCalibration.estimate(persisted, next, session.modelId))
            .isEqualTo(ApproximateTokenEstimator.estimate(next))
        second.observe(ProtocolUsage(input = 900, cacheReadInput = 800))
        assertThat(PromptUsageCalibration.estimate(persisted, next, session.modelId)).isEqualTo(900)
    }

    @Test fun `已发送文档由服务端基线覆盖只估算新增文档和媒体`() {
        val document = pdf(2)
        val sent = original.copy(messages = original.messages.map { if (it.role == "user") it.copy(documentData = listOf(document)) else it })
        val calibrated = withBaseline(sent, 500)
        assertThat(PromptUsageCalibration.estimate(calibrated, sent, session.modelId)).isEqualTo(500)
        val extra = sent.copy(messages = sent.messages.map { if (it.role == "user") it.copy(
            documentData = listOf(document, pdf(3)), imageUrls = listOf(ImageInput(url = "https://example.invalid/image"))) else it })
        assertThat(PromptUsageCalibration.estimate(calibrated, extra, session.modelId))
            .isEqualTo(500 + 3 * PdfInputTokenEstimator.TOKENS_PER_PAGE + ApproximateTokenEstimator.IMAGE_TOKENS)
        val changed = sent.copy(messages = sent.messages.map { if (it.role == "user") it.copy(documentData = listOf(pdf(4))) else it })
        assertThat(PromptUsageCalibration.estimate(calibrated, changed, session.modelId)).isEqualTo(ApproximateTokenEstimator.estimate(changed))
    }

    @Test fun `明确报告的零输入保留零基线并只增加后续内容`() = runTest {
        var saved = session
        val recorder = RequestInputUsageRecorder(session, original, session.modelId) {
            saved = saved.copy(stats = SessionStats(contextUsageBaseline = it))
        }
        recorder.start()
        recorder.observe(ProtocolUsage(input = 0, inputReported = true))
        assertThat(saved.stats?.contextUsageBaseline?.inputTokens).isEqualTo(0)
        assertThat(PromptUsageCalibration.estimate(saved, original, session.modelId)).isEqualTo(0)
        val added = ProtocolMessage("assistant", "回复")
        assertThat(PromptUsageCalibration.estimate(saved, original.copy(messages = original.messages + added), session.modelId))
            .isEqualTo(ApproximateTokenEstimator.estimate(added))
    }

    @Test fun `PDF按页计量且无法可靠识别时使用保守预留`() {
        assertThat(PdfInputTokenEstimator.estimate(pdf(5))).isEqualTo(5 * PdfInputTokenEstimator.TOKENS_PER_PAGE)
        val compressed = DocumentInput(base64 = Base64.getEncoder().encodeToString("%PDF-1.7\n/Type /ObjStm\n".toByteArray()))
        assertThat(PdfInputTokenEstimator.estimate(compressed)).isEqualTo(PdfInputTokenEstimator.FALLBACK_TOKENS)
        assertThat(PdfInputTokenEstimator.estimate(DocumentInput(base64 = "invalid"))).isEqualTo(PdfInputTokenEstimator.FALLBACK_TOKENS)
        assertThat(PdfInputTokenEstimator.estimate(DocumentInput(url = "https://example.invalid/doc.pdf"))).isEqualTo(PdfInputTokenEstimator.FALLBACK_TOKENS)
    }

    @Test fun `叶子按比例分配余数且所有父子及旧字段和等于校准总量`() {
        val local = ContextUsageBreakdown.estimate(original, emptyList(), 2, 128000)
        listOf(1, 999, 20000, Int.MAX_VALUE).forEach { total ->
            val result = ContextUsageCalibration.scale(local, total)
            assertThat(result.totalTokens).isEqualTo(total)
            assertThat(result.categories.sumOf { it.tokens }).isEqualTo(total)
            result.categories.forEach { group ->
                assertThat(group.children.sumOf { it.tokens }).isEqualTo(group.tokens)
                assertThat(group.children.map { it.id }).isEqualTo(local.categories.single { it.id == group.id }.children.map { it.id })
            }
            assertThat(result.foldedToolResults).isEqualTo(2)
            assertThat(result.systemTokens.toLong() + result.summaryTokens + result.conversationTokens + result.ragTokens).isEqualTo(total.toLong())
        }
        val empty = ContextUsageBreakdown.estimate(original.copy(messages = emptyList()), emptyList(), 0, 128000)
        assertThat(ContextUsageCalibration.scale(empty, 1000).totalTokens).isEqualTo(1000)
    }

    @Test fun `压缩预览门禁和面板共享同一校准总数并按实际值触发压缩`() = runTest {
        val s = withBaseline(input = 20000)
        val pipeline = ContextCompactionPipeline()
        val preview = pipeline.preview(s, original, session.modelId)
        val breakdown = ContextUsageCalibration.scale(ContextUsageBreakdown.estimate(original, emptyList(), 0, 10000), preview.afterTokens)
        assertThat(preview.afterTokens).isEqualTo(PromptUsageCalibration.estimate(s, original, session.modelId))
        assertThat(breakdown.totalTokens).isEqualTo(preview.afterTokens)
        var summarized = false
        val historyPrompt = original.copy(messages = original.messages + listOf(ProtocolMessage("assistant", "旧回复".repeat(1000)), ProtocolMessage("user", "新问题")))
        val history = withBaseline(historyPrompt, 20000).copy(messages = listOf(
            com.promenar.infinity.data.model.Message("old-user", com.promenar.infinity.data.model.MessageRole.USER, "旧问题"),
            com.promenar.infinity.data.model.Message("old-assistant", com.promenar.infinity.data.model.MessageRole.ASSISTANT, "旧回复".repeat(1000)),
            com.promenar.infinity.data.model.Message("new-user", com.promenar.infinity.data.model.MessageRole.USER, "新问题")))
        val result = pipeline.compact(ContextCompactionInput(history, "new-user", 1, 0.5, PromptWindow(10000, 1000), stableModelId = session.modelId),
            buildPrompt = { if (it.summary == null) historyPrompt else original.copy(messages = listOf(ProtocolMessage("system", "摘要"))) },
            summarizeHistory = { previous, _ -> summarized = true; previous.copy(summary = "压缩摘要") })
        assertThat(result.beforeTokens).isEqualTo(20000)
        assertThat(summarized).isTrue()
        assertThat(result.afterTokens).isEqualTo(ApproximateTokenEstimator.estimate(result.prompt))
    }

    private fun pdf(pages: Int) = DocumentInput(base64 = Base64.getEncoder().encodeToString(
        "%PDF-1.4\n1 0 obj\n<< /Type /Pages /Count $pages /Kids [] >>\nendobj\n%%EOF".toByteArray()))
}
