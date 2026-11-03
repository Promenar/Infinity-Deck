package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class UserAttachmentModelContentTest {
    private val session = Session("session", "agent")
    private val images = listOf("data:image/png;base64,first", "data:image/jpeg;base64,second", "data:image/png;base64,third")

    @Test fun `当前轮全部原图保留且不因引用数组部分为空丢失位置`() {
        val message = Message("u", MessageRole.USER, "比较图片", userImages = images,
            userImageReferences = listOf(reference("first.png"), null, reference("third.png").copy(sourceSessionId = "other")))
        val result = UserAttachmentModelContent.format(message, session, isCurrentTurn = true)

        assertThat(result.imageUrls?.map { it.url }).containsExactlyElementsIn(images).inOrder()
        assertThat(result.imageUrls?.map { it.mimeType }).containsExactly("image/png", "image/jpeg", "image/png").inOrder()
        assertThat(result.content).isEqualTo("比较图片")
    }

    @Test fun `历史图片按原下标逐个引用或回退并隐藏失效来源`() {
        val message = Message("u", MessageRole.USER, "比较图片", userImages = images,
            userImageReferences = listOf(reference("first.png"), null, reference("secret.png").copy(sourceSessionId = "other")))
        val result = UserAttachmentModelContent.format(message, session, isCurrentTurn = false)

        assertThat(result.imageUrls?.map { it.url }).containsExactly(images[1])
        assertThat(result.content).contains("index=\"1\" path=\"/session/attachments/first.png\"")
        assertThat(result.content).contains("index=\"3\"")
        assertThat(result.content).contains("view_media")
        assertThat(result.content).contains("引用已失效")
        assertThat(result.content).doesNotContain("secret.png")
        assertThat(result.content).doesNotContain(images[0])
        assertThat(result.content).doesNotContain(images[2])
    }

    @Test fun `历史图片没有引用或引用数组较短时逐图保持原输入`() {
        for (references in listOf(null, emptyList(), listOf(null))) {
            val result = UserAttachmentModelContent.format(Message("u", MessageRole.USER, "", userImages = images,
                userImageReferences = references), session, isCurrentTurn = false)
            assertThat(result.imageUrls?.map { it.url }).containsExactlyElementsIn(images).inOrder()
        }
        val mixed = UserAttachmentModelContent.format(Message("u", MessageRole.USER, "", userImages = images,
            userImageReferences = listOf(null, reference("second.png"), null)), session, false)
        assertThat(mixed.imageUrls?.map { it.url }).containsExactly(images[0], images[2]).inOrder()
        assertThat(mixed.content).contains("index=\"2\"")
    }

    @Test fun `自动保存文档只在当前轮全文且手动引用始终使用路径`() {
        val auto = document().copy(workspaceReference = reference("source.md"), inlineInCurrentTurn = true)
        val current = FullContextDocumentFormatter.appendToUserContent("阅读", listOf(auto), session, true)
        val history = FullContextDocumentFormatter.appendToUserContent("阅读", listOf(auto), session, false)
        val manual = FullContextDocumentFormatter.appendToUserContent("阅读", listOf(auto.copy(inlineInCurrentTurn = false)), session, true)

        assertThat(current).contains(auto.content)
        assertThat(current).contains("<<<INFINITY_DOCUMENT_BEGIN hash>>>")
        for (text in listOf(history, manual)) {
            assertThat(text).contains("/session/attachments/source.md")
            assertThat(text).contains("read_file")
            assertThat(text).doesNotContain(auto.content)
        }
    }

    @Test fun `无引用文档每轮保持全文兼容且失效自动引用不泄正文或路径`() {
        val legacy = document()
        assertThat(FullContextDocumentFormatter.appendToUserContent("", listOf(legacy), session, false)).contains(legacy.content)
        val expired = legacy.copy(workspaceReference = reference("secret.md").copy(sourceSessionId = "other"), inlineInCurrentTurn = true)
        val result = FullContextDocumentFormatter.appendToUserContent("", listOf(expired), session, true)
        assertThat(result).contains("引用已失效")
        assertThat(result).doesNotContain(expired.content)
        assertThat(result).doesNotContain("secret.md")
        assertThat(result).doesNotContain("read_file")
    }

    @Test fun `相同历史附件输入每次格式确定且图片路径保持单行转义`() {
        val message = Message("u", MessageRole.USER, "问题", userImages = images.take(1),
            userImageReferences = listOf(reference("a\"<&>.png")), userDocuments = listOf(document()))
        val first = UserAttachmentModelContent.format(message, session, false)
        assertThat(UserAttachmentModelContent.format(message, session, false)).isEqualTo(first)
        assertThat(first.content).contains("a&quot;&lt;&amp;&gt;.png")
        assertThat(first.content.lines().single { it.startsWith("[INFINITY_WORKSPACE_IMAGE") }).contains("view_media")
    }

    @Test fun `历史图片切换工作区或缺少来源会话时仅输出稳定失效说明`() {
        val ref = reference("private.png").copy(path = "/attachments/source.png", isPrivate = false, workspaceId = "workspace-a")
        val message = Message("u", MessageRole.USER, "问题", userImages = images.take(1), userImageReferences = listOf(ref))
        val valid = UserAttachmentModelContent.format(message, session.copy(workspaceId = "workspace-a"), false)
        val invalid = UserAttachmentModelContent.format(message, session.copy(workspaceId = "workspace-b"), false)
        val missing = UserAttachmentModelContent.format(message, null, false)

        assertThat(valid.content).contains(ref.path)
        assertThat(invalid).isEqualTo(missing)
        assertThat(invalid.content).contains("引用已失效")
        assertThat(invalid.content).doesNotContain(ref.path)
        assertThat(invalid.content).doesNotContain("view_media")
        assertThat(invalid.imageUrls).isNull()
    }

    private fun reference(name: String) = WorkspaceDocumentReference("/session/attachments/$name", 0, 0,
        sourceSessionId = session.id, isPrivate = true, linuxNamespace = false)
    private fun document() = MessageDocumentAttachment("doc", "source.md", "text/markdown", "附件全文独立保留", 24, "hash", 8)
}
