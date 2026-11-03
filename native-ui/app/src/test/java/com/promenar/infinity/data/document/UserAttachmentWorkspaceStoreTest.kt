package com.promenar.infinity.data.document

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import com.promenar.infinity.data.model.isBoundTo
import com.promenar.infinity.domain.model.SessionRunMode
import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Test

class UserAttachmentWorkspaceStoreTest {
    private val session = Session("session", "agent", runMode = SessionRunMode.BUILTIN)
    private val saver = MemorySaver(session)
    private val diagnostics = mutableListOf<UserAttachmentWorkspaceDiagnostic>()
    private fun store() = DefaultUserAttachmentWorkspaceStore(saver, UserAttachmentWorkspaceDiagnosticSink { diagnostics += it })
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0xff.toByte(), 0, 1)

    @Test fun `图片按真实MIME保存原二进制且保留输入顺序`() = runBlocking {
        val image = dataUrl(png, "image/jpeg")

        val result = store().save(session, listOf(image, "invalid"), emptyList())

        val reference = result.imageReferences!![0]!!
        assertThat(reference.path).endsWith(".png")
        assertThat(reference.path).contains("/attachments/2026-11-03/image-")
        assertThat(reference.sha256).isEqualTo(hash(png))
        assertThat(saver.files.getValue(saver.key(reference.path)).toList()).containsExactlyElementsIn(png.toList()).inOrder()
        assertThat(result.imageReferences!![1]).isNull()
        assertThat(diagnostics.single().kind).isEqualTo(UserAttachmentWorkspaceKind.Image)
        assertThat(diagnostics.single().index).isEqualTo(1)
    }

    @Test fun `文档写UTF8实际字节且自动落盘保留本轮正文`() = runBlocking {
        val document = document("甲\r\n乙\n").copy(sha256 = "hash-of-original-encoding")

        val result = store().save(session, emptyList(), listOf(document))

        val stored = result.documents.single()
        val reference = stored.workspaceReference!!
        assertThat(stored.content).isEqualTo(document.content)
        assertThat(stored.inlineInCurrentTurn).isTrue()
        assertThat(reference.sha256).isEqualTo(hash(document.content.toByteArray(Charsets.UTF_8)))
        assertThat(reference.lineCount).isEqualTo(3)
        assertThat(saver.files.getValue(saver.key(reference.path)).toString(Charsets.UTF_8)).isEqualTo(document.content)
        assertThat(result.imageReferences).isNull()
    }

    @Test fun `同请求同字节文档即使文件名不同也只保存一次`() = runBlocking {
        val first = document("相同正文")
        val second = first.copy(id = "second", name = "another.md")

        val result = store().save(session, emptyList(), listOf(first, second))

        assertThat(saver.files).hasSize(1)
        assertThat(result.documents.map { it.workspaceReference?.path }.distinct()).hasSize(1)
        assertThat(result.documents.map { it.id }).containsExactly("doc", "second").inOrder()
    }

    @Test fun `同请求重复图片和同服务后续请求复用已校验路径`() = runBlocking {
        val store = store()
        val image = dataUrl(png)

        val first = store.save(session, listOf(image, image), emptyList())
        val next = store.save(session, listOf(image), emptyList())

        assertThat(saver.files).hasSize(1)
        assertThat(first.imageReferences!![0]).isEqualTo(first.imageReferences!![1])
        assertThat(next.imageReferences!!.single()).isEqualTo(first.imageReferences!![0])
    }

    @Test fun `同服务并发请求按会话去重`() = runBlocking {
        val store = store()
        val results = coroutineScope {
            (1..8).map { async { store.save(session, listOf(dataUrl(png)), listOf(document("正文"))) } }.awaitAll()
        }

        assertThat(saver.files).hasSize(2)
        assertThat(results.map { it.imageReferences!!.single()!!.path }.distinct()).hasSize(1)
        assertThat(results.map { it.documents.single().workspaceReference!!.path }.distinct()).hasSize(1)
    }

    @Test fun `重建服务从消息引用中复用归档消息的图片和文档`() = runBlocking {
        val first = store().save(session, listOf(dataUrl(png)), listOf(document("正文")))
        val history = Message(
            "old", MessageRole.USER, "历史", isArchived = true,
            userImages = listOf(dataUrl(png)), userImageReferences = first.imageReferences,
            userDocuments = first.documents,
        )
        val restoredSession = session.copy(messages = listOf(history))

        val restored = store().save(restoredSession, listOf(dataUrl(png)), listOf(document("正文")))

        assertThat(saver.files).hasSize(2)
        assertThat(restored.imageReferences).isEqualTo(first.imageReferences)
        assertThat(restored.documents.single().workspaceReference).isEqualTo(first.documents.single().workspaceReference)
    }

    @Test fun `归档手动引用未存hash时按真实字节复用并补齐hash`() = runBlocking {
        val bytes = "相同正文".toByteArray(Charsets.UTF_8)
        val legacy = reference("/session/attachments/manual.md", "unused").copy(sha256 = null)
        saver.files[saver.key(legacy.path)] = bytes
        val historical = document("").copy(sha256 = "original-encoding-hash", workspaceReference = legacy)
        val restored = session.copy(messages = listOf(Message(
            "archived", MessageRole.USER, "历史", isArchived = true, userDocuments = listOf(historical),
        )))

        val result = store().save(restored, emptyList(), listOf(document("相同正文")))

        assertThat(saver.files).hasSize(1)
        assertThat(result.documents.single().workspaceReference!!.path).isEqualTo(legacy.path)
        assertThat(result.documents.single().workspaceReference!!.sha256).isEqualTo(hash(bytes))
        assertThat(result.documents.single().content).isEqualTo("相同正文")
        assertThat(result.documents.single().inlineInCurrentTurn).isTrue()
    }

    @Test fun `归档无hash引用的实际字节不同时忽略文档旧hash并新建`() = runBlocking {
        val expected = "first".toByteArray()
        val legacy = reference("/session/attachments/manual.md", "unused").copy(sha256 = null)
        saver.files[saver.key(legacy.path)] = "other".toByteArray()
        val historical = document("").copy(sha256 = hash(expected), workspaceReference = legacy)
        val restored = session.copy(messages = listOf(Message(
            "archived", MessageRole.USER, "历史", isArchived = true, userDocuments = listOf(historical),
        )))

        val result = store().save(restored, emptyList(), listOf(document("first")))

        assertThat(saver.files).hasSize(2)
        assertThat(result.documents.single().workspaceReference!!.path).isNotEqualTo(legacy.path)
        assertThat(result.documents.single().workspaceReference!!.sha256).isEqualTo(hash(expected))
        assertThat(saver.files.getValue(saver.key(legacy.path)).toString(Charsets.UTF_8)).isEqualTo("other")
    }

    @Test fun `已删除或篡改的缓存文件不复用`() = runBlocking {
        val store = store()
        val first = store.save(session, listOf(dataUrl(png)), emptyList()).imageReferences!!.single()!!
        saver.files[saver.key(first.path)] = "changed".toByteArray()
        val changed = store.save(session, listOf(dataUrl(png)), emptyList()).imageReferences!!.single()!!
        saver.files.remove(saver.key(changed.path))

        val deleted = store.save(session, listOf(dataUrl(png)), emptyList()).imageReferences!!.single()!!

        assertThat(changed.path).isNotEqualTo(first.path)
        assertThat(saver.files[saver.key(first.path)]!!.toString(Charsets.UTF_8)).isEqualTo("changed")
        assertThat(saver.files.getValue(saver.key(deleted.path)).toList()).containsExactlyElementsIn(png.toList()).inOrder()
    }

    @Test fun `切换工作区后旧来源即使相同路径也不复用`() = runBlocking {
        val store = store()
        val first = store.save(session, listOf(dataUrl(png)), emptyList()).imageReferences!!.single()!!
        val next = session.copy(workspaceId = "project-b")
        saver.current = next
        saver.root = "root-b"

        val second = store.save(next, listOf(dataUrl(png)), emptyList()).imageReferences!!.single()!!

        assertThat(saver.files).hasSize(2)
        assertThat(second.workspaceId).isEqualTo("project-b")
        assertThat(second.isPrivate).isFalse()
        assertThat(second.path).doesNotContain("/session/")
        assertThat(first.isPrivate).isTrue()
    }

    @Test fun `同逻辑根被重建且新根无文件时重新保存`() = runBlocking {
        val store = store()
        store.save(session, listOf(dataUrl(png)), emptyList())
        saver.root = "rebuilt-root"

        val result = store.save(session, listOf(dataUrl(png)), emptyList())

        assertThat(saver.files).hasSize(2)
        assertThat(result.imageReferences!!.single()).isNotNull()
        assertThat(saver.files.keys.any { it.startsWith("rebuilt-root:") }).isTrue()
    }

    @Test fun `保存服务当前来源与发送快照不同则不把异根引用写入结果`() = runBlocking<Unit> {
        saver.current = session.copy(workspaceId = "changed-project")

        val result = store().save(session, listOf(dataUrl(png)), listOf(document("正文")))

        assertThat(result.imageReferences!!.single()).isNull()
        assertThat(result.documents.single().workspaceReference).isNull()
        assertThat(result.documents.single().content).isEqualTo("正文")
        assertThat(diagnostics.map { it.storageReason }).containsExactly(
            DocumentWorkspaceSaveFailure.WorkspaceUnavailable, DocumentWorkspaceSaveFailure.WorkspaceUnavailable,
        )
    }

    @Test fun `同名不同字节追加序号并保留已有文档`() = runBlocking<Unit> {
        val result = store().save(session, emptyList(), listOf(document("第一份"), document("第二份")))

        assertThat(result.documents[0].workspaceReference!!.path).endsWith("/note.md")
        assertThat(result.documents[1].workspaceReference!!.path).endsWith("/note-2.md")
        assertThat(saver.files.values.map { it.toString(Charsets.UTF_8) }).containsExactly("第一份", "第二份")
    }

    @Test fun `Unavailable写失败与解码失败逐项保留原内容并诊断`() = runBlocking {
        saver.failure = DocumentWorkspaceSaveFailure.WorkspaceUnavailable
        val document = document("保留正文")

        val result = store().save(session, listOf("data:image/png;base64,%%%", dataUrl(png)), listOf(document))

        assertThat(result.imageReferences).containsExactly(null, null)
        assertThat(result.documents.single()).isEqualTo(document)
        assertThat(saver.files).isEmpty()
        assertThat(diagnostics).hasSize(3)
        assertThat(diagnostics.map { it.reason }).contains(UserAttachmentWorkspaceFailure.DecodeFailed)
        assertThat(diagnostics.map { it.storageReason }).contains(DocumentWorkspaceSaveFailure.WorkspaceUnavailable)
    }

    @Test fun `单项异常不阻断其它附件且诊断不带异常正文`() = runBlocking {
        saver.throwForName = "bad.md"
        val first = document("第一份").copy(name = "bad.md")
        val second = document("第二份").copy(name = "good.md")

        val result = store().save(session, emptyList(), listOf(first, second))

        assertThat(result.documents[0]).isEqualTo(first)
        assertThat(result.documents[1].workspaceReference).isNotNull()
        assertThat(diagnostics.single().toString()).doesNotContain("internal failure details")
    }

    @Test fun `目录越界即使存在可复用缓存也拒绝新落点`() = runBlocking {
        val store = store()
        store.save(session, listOf(dataUrl(png)), listOf(document("正文")))

        val result = store.save(session, listOf(dataUrl(png)), listOf(document("正文")), "../outside")

        assertThat(result.imageReferences!!.single()).isNull()
        assertThat(result.documents.single().workspaceReference).isNull()
        assertThat(result.documents.single().content).isEqualTo("正文")
        assertThat(saver.files).hasSize(2)
    }

    @Test fun `手动引用维持原引用并且不恢复内联正文`() = runBlocking {
        val manual = document("").copy(workspaceReference = reference("/session/attachments/manual.md", hash("manual".toByteArray())))

        val result = store().save(session, emptyList(), listOf(manual))

        assertThat(result.documents.single()).isEqualTo(manual)
        assertThat(result.documents.single().inlineInCurrentTurn).isFalse()
        assertThat(saver.files).isEmpty()
    }

    @Test fun `百分号编码SVG按实际格式落盘且加号保持原字节`() = runBlocking {
        val svg = "<svg xmlns='http://www.w3.org/2000/svg'><text>a+b</text></svg>"

        val result = store().save(session, listOf("data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%3E%3Ctext%3Ea+b%3C/text%3E%3C/svg%3E"), emptyList())

        val stored = result.imageReferences!!.single()!!
        assertThat(stored.path).endsWith(".svg")
        assertThat(saver.files.getValue(saver.key(stored.path)).toString(Charsets.UTF_8)).isEqualTo(svg)
    }

    @Test fun `取消继续向上传递`() = runBlocking {
        saver.cancel = true

        val failure = runCatching { store().save(session, listOf(dataUrl(png)), emptyList()) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(CancellationException::class.java)
    }

    @Test fun `只实现旧save的fake仍可使用且未实现字节存储时安全回退`() = runBlocking {
        val legacy = object : DocumentWorkspaceSaver {
            override suspend fun save(sessionId: String, document: MessageDocumentAttachment, relativeDirectory: String?) =
                DocumentWorkspaceSaveResult.Failure(DocumentWorkspaceSaveFailure.SaveFailed)
        }
        val store = DefaultUserAttachmentWorkspaceStore(legacy, UserAttachmentWorkspaceDiagnosticSink { diagnostics += it })

        val result = store.save(session, listOf(dataUrl(png)), listOf(document("正文")))

        assertThat(result.imageReferences!!.single()).isNull()
        assertThat(result.documents.single().content).isEqualTo("正文")
        assertThat(result.documents.single().workspaceReference).isNull()
    }

    private fun document(content: String) = MessageDocumentAttachment(
        "doc", "note.md", "text/markdown", content, content.toByteArray().size.toLong(), "source-hash", 123,
    )

    private fun dataUrl(bytes: ByteArray, mime: String = "image/png") = "data:$mime;base64,${Base64.getEncoder().encodeToString(bytes)}"
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun reference(path: String, hash: String) = WorkspaceDocumentReference(
        path, 0, 0, sourceSessionId = session.id, isPrivate = true, linuxNamespace = false, sha256 = hash,
    )

    private class MemorySaver(var current: Session) : DocumentWorkspaceSaver {
        var root = "root-a"
        val files = linkedMapOf<String, ByteArray>()
        var failure: DocumentWorkspaceSaveFailure? = null
        var throwForName: String? = null
        var cancel = false
        fun key(path: String) = "$root:$path"

        override suspend fun save(sessionId: String, document: MessageDocumentAttachment, relativeDirectory: String?) =
            error("自动附件必须使用字节入口")

        override suspend fun saveBytes(
            sessionId: String, name: String, mimeType: String, bytes: ByteArray,
            lineCount: Int, estimatedTokens: Int, relativeDirectory: String?,
        ): DocumentWorkspaceBytesSaveResult {
            if (cancel) throw CancellationException("cancelled")
            if (name == throwForName) error("internal failure details")
            failure?.let { return DocumentWorkspaceBytesSaveResult.Failure(it) }
            val directory = relativeDirectory ?: "attachments/2026-11-03"
            val private = current.workspaceId == null
            val prefix = when {
                current.runMode == SessionRunMode.LINUX -> if (private) "/home/session" else "/workspace"
                private -> "/session"
                else -> ""
            }
            var sequence = 1
            var candidate = "$prefix/$directory/$name"
            while (key(candidate) in files) {
                val extension = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
                candidate = "$prefix/$directory/${name.take(extension)}-${++sequence}${name.substring(extension)}"
            }
            files[key(candidate)] = bytes.copyOf()
            return DocumentWorkspaceBytesSaveResult.Success(WorkspaceDocumentReference(
                candidate, lineCount, estimatedTokens, sourceSessionId = sessionId, workspaceId = current.workspaceId,
                isPrivate = private, linuxNamespace = current.runMode == SessionRunMode.LINUX,
                sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
            ))
        }

        override suspend fun isReusable(
            session: Session, reference: WorkspaceDocumentReference, expectedSha256: String, expectedSizeBytes: Long?,
        ): Boolean {
            if (!reference.isBoundTo(session) || reference.workspaceId != current.workspaceId) return false
            val bytes = files[key(reference.path)] ?: return false
            if (expectedSizeBytes != null && bytes.size.toLong() != expectedSizeBytes) return false
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            return (reference.sha256 == null || reference.sha256 == expectedSha256) && hash == expectedSha256
        }
    }
}
