package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionStats
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import com.promenar.infinity.data.remote.protocol.PromptRequest
import io.mockk.mockk
import org.junit.Test

class UserAttachmentPromptContentTest {
    private val image = "data:image/png;base64,fixture"
    private val reference = WorkspaceDocumentReference("/session/attachments/picture.png", 0, 2048,
        sourceSessionId = "session", isPrivate = true, linuxNamespace = false)
    private val user = Message("u1", MessageRole.USER, "分析图片", userImages = listOf(image), userImageReferences = listOf(reference))
    private val session = Session("session", "agent", modelId = "provider::model", messages = listOf(user))
    private val strategy = DefaultChatGenerationContentStrategy(mockk<SharedPreferences>(relaxed = true), null)

    @Test fun `运行时插入的用户指令不改变真实当前附件轮`() {
        val source = session.copy(messages = session.messages + Message("a", MessageRole.ASSISTANT, "回答", requestInstruction = "运行指令"))
        val prompt = strategy.buildProtocolMessages(source, "")
        assertThat(prompt.last { it.role == "user" }.isRuntimeInstruction).isTrue()
        assertThat(prompt.first { it.role == "user" }.imageUrls?.single()?.url).isEqualTo(image)
    }

    @Test fun `新真实用户轮令旧附件转路径且原消息保持不变`() {
        val original = strategy.buildProtocolMessages(session, "")
        val next = session.copy(messages = session.messages + Message("u2", MessageRole.USER, "再比较"))
        val prompt = strategy.buildProtocolMessages(next, "")
        assertThat(original.first().imageUrls).hasSize(1)
        assertThat(prompt.first().imageUrls).isNull()
        assertThat(prompt.first().content).contains(reference.path)
        assertThat(prompt.first().content).contains("view_media")
        assertThat(strategy.buildProtocolMessages(next, "")).isEqualTo(prompt)
        assertThat(user.userImages).containsExactly(image)
    }

    @Test fun `固定归档历史用户仍按最新真实用户轮决定是否内联`() {
        val source = session.copy(messages = listOf(user.copy(isArchived = true), Message("u2", MessageRole.USER, "新问题", userImages = listOf(image))))
        val prompt = strategy.buildProtocolMessages(source, "", pinnedUserMessageId = "u1")
        assertThat(prompt[0].imageUrls).isNull()
        assertThat(prompt[0].content).contains(reference.path)
        assertThat(prompt[1].imageUrls).hasSize(1)
    }

    @Test fun `工具循环只在当前用户消息保留一份图片`() {
        val source = session.copy(messages = session.messages + listOf(
            Message("a", MessageRole.ASSISTANT, "", toolCalls = listOf(ToolCall("call", "read_file", "{}"))),
            Message("t", MessageRole.TOOL, "工具结果", name = "read_file", toolCallId = "call")))
        val prompt = strategy.buildProtocolMessages(source, "")
        assertThat(prompt.sumOf { it.imageUrls.orEmpty().size }).isEqualTo(1)
        assertThat(prompt.single { it.role == "tool" }.imageUrls).isNull()
    }

    @Test fun `真实转换使旧校准前缀失效且历史图片只计路径说明`() {
        val original = PromptRequest(strategy.buildProtocolMessages(session, ""), "model")
        val baseline = PromptUsageCalibration.capture(session, original, 100000, session.modelId)
        val next = session.copy(stats = SessionStats(contextUsageBaseline = baseline),
            messages = session.messages + Message("u2", MessageRole.USER, "新问题"))
        val prompt = PromptRequest(strategy.buildProtocolMessages(next, ""), "model")

        assertThat(ApproximateTokenEstimator.mediaTokens(original.messages.first())).isEqualTo(2048L)
        assertThat(ApproximateTokenEstimator.mediaTokens(prompt.messages.first())).isEqualTo(0L)
        assertThat(prompt.messages.first().content).contains(reference.path)
        assertThat(PromptUsageCalibration.estimate(next, prompt, session.modelId)).isEqualTo(ApproximateTokenEstimator.estimate(prompt))
    }

    @Test fun `自动文档实际转成历史路径后重新估算且没有原全文预留`() {
        val document = MessageDocumentAttachment("doc", "source.md", "text/markdown", "长".repeat(5000), 15000, "hash", 5000,
            workspaceReference = reference.copy(path = "/session/attachments/source.md", lineCount = 1, estimatedTokens = 5000),
            inlineInCurrentTurn = true)
        val source = session.copy(messages = listOf(user.copy(userImages = null, userImageReferences = null, userDocuments = listOf(document))))
        val original = PromptRequest(strategy.buildProtocolMessages(source, ""), "model")
        val baseline = PromptUsageCalibration.capture(source, original, 100000, source.modelId)
        val next = source.copy(stats = SessionStats(contextUsageBaseline = baseline), messages = source.messages + Message("u2", MessageRole.USER, "新问题"))
        val prompt = PromptRequest(strategy.buildProtocolMessages(next, ""), "model")

        assertThat(original.messages.first().content).contains(document.content)
        assertThat(prompt.messages.first().content).doesNotContain(document.content)
        assertThat(prompt.messages.first().content).contains("read_file")
        assertThat(ApproximateTokenEstimator.mediaTokens(prompt.messages.first())).isEqualTo(0L)
        assertThat(PromptUsageCalibration.estimate(next, prompt, source.modelId)).isEqualTo(ApproximateTokenEstimator.estimate(prompt))
        assertThat(ApproximateTokenEstimator.estimate(prompt)).isLessThan(ApproximateTokenEstimator.estimate(original))
    }
}
