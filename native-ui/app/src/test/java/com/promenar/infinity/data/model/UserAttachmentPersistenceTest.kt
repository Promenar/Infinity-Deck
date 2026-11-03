package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import org.junit.Test

class UserAttachmentPersistenceTest {
    @Test fun `仅有图片引用也可在现有files列往返并保留空位与旧载荷`() {
        val reference = WorkspaceDocumentReference("/session/attachments/image.png", 0, 2048,
            sourceSessionId = "s", isPrivate = true, linuxNamespace = false, sha256 = "synthetic-hash")
        val message = Message("u", MessageRole.USER, "图片", userImages = listOf("data:image/png;base64,AA==", "legacy-image"),
            userImageReferences = listOf(reference, null), legacyFilesPayload = "legacy-files")
        val restored = message.toEntity("s").toDomain()
        assertThat(restored.userImages).isEqualTo(message.userImages)
        assertThat(restored.userImageReferences).containsExactly(reference, null).inOrder()
        assertThat(restored.legacyFilesPayload).isEqualTo("legacy-files")
    }

    @Test fun `旧消息与旧文档JSON缺少新增字段时维持内联行为`() {
        val message = json.decodeFromString<Message>("""{"id":"u","role":"USER","content":"旧消息","userImages":["legacy"]}""")
        val document = json.decodeFromString<MessageDocumentAttachment>("""{"id":"d","name":"a.txt","mimeType":"text/plain","content":"正文","sizeBytes":6,"sha256":"hash","estimatedTokens":2}""")
        assertThat(message.userImageReferences).isNull()
        assertThat(document.workspaceReference).isNull()
        assertThat(document.inlineInCurrentTurn).isFalse()
        assertThat(WorkspaceDocumentReference("/path", 1, 2).sha256).isNull()
    }

    @Test fun `自动落盘文档的当前轮内联标志与引用一起持久化`() {
        val document = MessageDocumentAttachment("d", "a.txt", "text/plain", "全文", 6, "hash", 2,
            WorkspaceDocumentReference("/session/attachments/a.txt", 1, 2), inlineInCurrentTurn = true)
        val message = Message("u", MessageRole.USER, "问题", userDocuments = listOf(document))
        assertThat(message.toEntity("s").toDomain().userDocuments).containsExactly(document)
    }
}
