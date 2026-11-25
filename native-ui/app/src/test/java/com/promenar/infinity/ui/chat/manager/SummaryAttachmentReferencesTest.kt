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
        val tail = "正文末尾保留"
        val body = "甲".repeat(1600) + "应省略的中段" + "乙".repeat(1600 - tail.length) + tail
        val message = Message("u", MessageRole.USER, body,
            userImages = listOf("data:image/png;base64,fixture"), userImageReferences = listOf(image),
            userDocuments = listOf(MessageDocumentAttachment("doc", "source.md", "text/markdown", "附件全文不放摘要", 100, "hash", 30,
                workspaceReference = document, inlineInCurrentTurn = true)))
        var system = ""
        var payload = ""
        val manager = SummaryManager(mockk(relaxed = true)) { request, _ ->
            assertThat(request.messages.map { it.role }).containsExactly("system", "user").inOrder()
            system = request.messages.first().content
            payload = request.messages.last().content
            PromptResponse("摘要")
        }

        manager.summarizeOrNull("既有附件 /session/attachments/old.md 用于旧分析", listOf(message), "model", sourceSession = session)

        assertThat(system).contains("用户附件的工作区路径及一句用途")
        assertThat(system).contains("只使用记录或既有摘要提供的路径，不编造路径")
        assertThat(system).contains("既有摘要中的附件路径和用途")
        assertThat(payload).startsWith("<existing_summary>\n既有附件 /session/attachments/old.md 用于旧分析\n</existing_summary>")
        assertThat(payload).endsWith(
            "$tail\n[INFINITY_USER_ATTACHMENT_REFERENCES]\n" +
                "- 图片 1：${image.path}；用途：本条用户消息的参考图片，使用 view_media 查看。\n" +
                "- 文档 1：${document.path}；用途：本条用户消息的参考文档，使用 read_file 分页或按行范围读取。\n" +
                "</conversation_history>",
        )
        assertThat(payload).contains("[...已省略中间内容...]")
        assertThat(payload).doesNotContain("应省略的中段")
        assertThat(payload).doesNotContain("附件全文不放摘要")
        assertThat(payload).doesNotContain("base64")
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
        val manager = SummaryManager(mockk(relaxed = true)) { request, _ ->
            prompt = request.messages.single { it.role == "user" }.content
            PromptResponse("摘要")
        }
        manager.summarizeOrNull(null, messages, "model", sourceSession = session)

        assertThat(prompt).contains(valid.path)
        assertThat(prompt).contains("引用已失效")
        assertThat(prompt).doesNotContain(invalid.path)
        assertThat(prompt).doesNotContain("assistant.png")
        assertThat(prompt.indexOf(valid.path)).isLessThan(prompt.indexOf("ASSISTANT: 回答"))
    }

    @Test fun `旧摘要调用缺少来源会话时不猜测引用根`() = runTest {
        var prompt = ""
        val manager = SummaryManager(mockk(relaxed = true)) { request, _ ->
            prompt = request.messages.single { it.role == "user" }.content
            PromptResponse("摘要")
        }
        manager.summarizeOrNull(null, listOf(Message("u", MessageRole.USER, "问题", userImageReferences = listOf(reference("secret.png")))), "model")
        assertThat(prompt).contains("引用已失效")
        assertThat(prompt).doesNotContain("secret.png")
    }
}
