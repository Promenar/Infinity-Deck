package com.promenar.infinity.data.document.external

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.document.ComposerDocumentInbox
import com.promenar.infinity.data.document.DocumentTextDecoding
import com.promenar.infinity.data.document.DocumentTokenEstimator
import com.promenar.infinity.data.document.DocumentWorkspaceBytesSaveResult
import com.promenar.infinity.data.document.DocumentWorkspaceSaveFailure
import com.promenar.infinity.data.document.DocumentWorkspaceSaver
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import java.io.File
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExternalDocumentSenderTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun document(text: String, renderText: Boolean = true, sizeBytes: Long? = null): ExternalDocument {
        val bytes = text.toByteArray()
        val file = File(temporary.newFolder(), "周报.md").apply { writeBytes(bytes) }
        return ExternalDocument(
            id = "00000000-0000-0000-0000-000000000001",
            displayName = "周报.md",
            file = file,
            sizeBytes = sizeBytes ?: bytes.size.toLong(),
            sha256 = DocumentTextDecoding.sha256(bytes),
            text = text.takeIf { renderText },
        )
    }

    @Test
    fun `附件方式把全文放进会话草稿 不写工作区`() = runTest {
        val inbox = ComposerDocumentInbox()
        val saver = mockk<DocumentWorkspaceSaver>()
        val sender = ExternalDocumentSender(inbox, saver, StandardTestDispatcher(testScheduler))
        val doc = document("# 周报\n正文")

        val result = sender.send(doc, "s1", ExternalSendMode.Attachment)

        assertThat(result).isEqualTo(ExternalSendResult.Sent("s1", ExternalSendMode.Attachment))
        val attachment = inbox.take("s1").single()
        assertThat(attachment.content).isEqualTo("# 周报\n正文")
        assertThat(attachment.mimeType).isEqualTo("text/markdown")
        assertThat(attachment.sha256).isEqualTo(doc.sha256)
        assertThat(attachment.estimatedTokens).isEqualTo(DocumentTokenEstimator.estimate("# 周报\n正文"))
        assertThat(attachment.workspaceReference).isNull()
        coVerify(exactly = 0) { saver.saveBytes(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `引用方式把文件存进会话文件夹 草稿只带路径`() = runTest {
        val inbox = ComposerDocumentInbox()
        val saver = mockk<DocumentWorkspaceSaver>()
        val bytes = slot<ByteArray>()
        val reference = WorkspaceDocumentReference(path = "/attachments/2026-11-10/周报.md", lineCount = 3, estimatedTokens = 12)
        coEvery { saver.saveBytes("s1", "周报.md", "text/markdown", capture(bytes), 3, any(), any()) } returns
            DocumentWorkspaceBytesSaveResult.Success(reference)
        val sender = ExternalDocumentSender(inbox, saver, StandardTestDispatcher(testScheduler))
        val doc = document("a\nb\nc")

        val result = sender.send(doc, "s1", ExternalSendMode.Reference)

        assertThat(result).isEqualTo(ExternalSendResult.Sent("s1", ExternalSendMode.Reference))
        assertThat(bytes.captured).isEqualTo("a\nb\nc".toByteArray())
        val attachment = inbox.take("s1").single()
        assertThat(attachment.content).isEmpty()
        assertThat(attachment.workspaceReference).isEqualTo(reference)
        assertThat(attachment.inlineInCurrentTurn).isFalse()
        assertThat(attachment.sizeBytes).isEqualTo(doc.sizeBytes)
    }

    @Test
    fun `超过 1 MiB 时请求附件也自动改为引用`() = runTest {
        val inbox = ComposerDocumentInbox()
        val saver = mockk<DocumentWorkspaceSaver>()
        coEvery { saver.saveBytes(any(), any(), any(), any(), any(), any(), any()) } returns
            DocumentWorkspaceBytesSaveResult.Success(WorkspaceDocumentReference("/a.md", 1, 1))
        val sender = ExternalDocumentSender(inbox, saver, StandardTestDispatcher(testScheduler))
        val large = document("x", sizeBytes = ExternalSendPolicy.ATTACHMENT_LIMIT_BYTES + 1)
        val unrendered = document("y", renderText = false)

        assertThat(ExternalSendPolicy.attachmentAllowed(large)).isFalse()
        assertThat(ExternalSendPolicy.attachmentAllowed(unrendered)).isFalse()
        assertThat(ExternalSendPolicy.attachmentAllowed(document("z", sizeBytes = ExternalSendPolicy.ATTACHMENT_LIMIT_BYTES))).isTrue()
        assertThat(sender.send(large, "s1", ExternalSendMode.Attachment)).isEqualTo(ExternalSendResult.Sent("s1", ExternalSendMode.Reference))
        assertThat(sender.send(unrendered, "s2", ExternalSendMode.Attachment)).isEqualTo(ExternalSendResult.Sent("s2", ExternalSendMode.Reference))
        assertThat(inbox.take("s1").single().workspaceReference).isNotNull()
    }

    @Test
    fun `引用写入失败时不放进草稿`() = runTest {
        val inbox = ComposerDocumentInbox()
        val saver = mockk<DocumentWorkspaceSaver>()
        coEvery { saver.saveBytes(any(), any(), any(), any(), any(), any(), any()) } returns
            DocumentWorkspaceBytesSaveResult.Failure(DocumentWorkspaceSaveFailure.ReadOnly)
        val sender = ExternalDocumentSender(inbox, saver, StandardTestDispatcher(testScheduler))

        val result = sender.send(document("a"), "s1", ExternalSendMode.Reference)

        assertThat(result).isEqualTo(ExternalSendResult.Failed(DocumentWorkspaceSaveFailure.ReadOnly))
        assertThat(inbox.pending.value).isEmpty()
    }

    @Test
    fun `行数与按行读取一致`() {
        assertThat(ExternalDocumentSender.lineCount(ByteArray(0))).isEqualTo(0)
        assertThat(ExternalDocumentSender.lineCount("a".toByteArray())).isEqualTo(1)
        assertThat(ExternalDocumentSender.lineCount("a\nb\n".toByteArray())).isEqualTo("a\nb\n".lineSequence().count())
    }
}
