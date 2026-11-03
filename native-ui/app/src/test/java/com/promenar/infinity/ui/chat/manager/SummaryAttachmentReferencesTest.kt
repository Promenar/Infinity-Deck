package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import com.promenar.infinity.data.remote.protocol.PromptResponse
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SummaryAttachmentReferencesTest {
    private val session = Session("session", "agent")
    private fun reference(name: String) = WorkspaceDocumentReference("/session/attachments/$name", 4, 30,
        sourceSessionId = session.id, isPrivate = true, linuxNamespace = false)

    @Test fun `摘要附件路径和用途独立于每条正文截断并保留旧摘要路径要求`() = runTest {
        val image = reference("picture.png")
        val document = reference("source.md")
        val message = Message("u", MessageRole.USER, "甲".repeat(2000) + "正文尾部不发送",
            userImages = listOf("data:image/png;base64,fixture"), userImageReferences = listOf(image),
            userDocuments = listOf(MessageDocumentAttachment("doc", "source.md", "text/markdown", "附件全文不放摘要", 100, "hash", 30,
                workspaceReference = document, inlineInCurrentTurn = true)))
        var prompt = ""
        val manager = SummaryManager(mockk(relaxed = true)) { request, _ -> prompt = request.messages.single().content; PromptResponse("摘要") }

        manager.summarizeOrNull("既有附件 /session/attachments/old.md 用于旧分析", listOf(message), "model", sourceSession = session)

        assertThat(prompt).contains(image.path)
        assertThat(prompt).contains(document.path)
        assertThat(prompt).contains("用途：")
        assertThat(prompt).contains("保留用户附件的工作区路径及一句用途")
        assertThat(prompt).contains("不编造路径")
        assertThat(prompt).contains("保留既有摘要中的附件路径和用途")
        assertThat(prompt).contains("/session/attachments/old.md")
        assertThat(prompt).doesNotContain("正文尾部不发送")
        assertThat(prompt).doesNotContain("附件全文不放摘要")
        assertThat(prompt).doesNotContain("base64")
    }

    @Test fun `摘要引用按每条用户消息核验且不从助手或失效根暴露路径`() = runTest {
        val valid = reference("valid.png")
        val invalid = reference("secret.png").copy(sourceSessionId = "other")
        val messages = listOf(
            Message("u1", MessageRole.USER, "第一个问题", userImageReferences = listOf(valid)),
            Message("a", MessageRole.ASSISTANT, "回答", userImageReferences = listOf(reference("assistant.png"))),
            Message("u2", MessageRole.USER, "第二个问题", userImageReferences = listOf(invalid)),
        )
        var prompt = ""
        val manager = SummaryManager(mockk(relaxed = true)) { request, _ -> prompt = request.messages.single().content; PromptResponse("摘要") }
        manager.summarizeOrNull(null, messages, "model", sourceSession = session)

        assertThat(prompt).contains(valid.path)
        assertThat(prompt).contains("引用已失效")
        assertThat(prompt).doesNotContain(invalid.path)
        assertThat(prompt).doesNotContain("assistant.png")
        assertThat(prompt.indexOf(valid.path)).isLessThan(prompt.indexOf("ASSISTANT: 回答"))
    }

    @Test fun `旧摘要调用缺少来源会话时不猜测引用根`() = runTest {
        var prompt = ""
        val manager = SummaryManager(mockk(relaxed = true)) { request, _ -> prompt = request.messages.single().content; PromptResponse("摘要") }
        manager.summarizeOrNull(null, listOf(Message("u", MessageRole.USER, "问题", userImageReferences = listOf(reference("secret.png")))), "model")
        assertThat(prompt).contains("引用已失效")
        assertThat(prompt).doesNotContain("secret.png")
    }
}
