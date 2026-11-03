package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.SessionRunMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test

class WorkspaceDocumentReferenceTest {
    @Test fun `旧附件JSON未含工作区引用时默认空引用`() {
        val legacy = """{"id":"doc","name":"report.md","mimeType":"text/markdown","content":"正文","sizeBytes":6,"sha256":"hash","estimatedTokens":6}"""

        val restored = Json.decodeFromString<MessageDocumentAttachment>(legacy)

        assertThat(restored.workspaceReference).isNull()
        assertThat(restored.content).isEqualTo("正文")
    }

    @Test fun `工作区引用和消息实体往返保留元数据`() {
        val attachment = document().copy(
            content = "", workspaceReference = privateReference(),
        )
        val serialized = Json.encodeToString(attachment)
        val message = Message("message", MessageRole.USER, "阅读附件", userDocuments = listOf(attachment), createdAt = 1)

        assertThat(Json.decodeFromString<MessageDocumentAttachment>(serialized)).isEqualTo(attachment)
        assertThat(message.toEntity("session").toDomain().userDocuments).containsExactly(attachment)
    }

    @Test fun `引用文档只附文件名路径行数估算大小和分页读取提示`() {
        val attachment = document().copy(
            content = "这段正文不应进入模型上下文",
            workspaceReference = privateReference(),
        )

        val formatted = FullContextDocumentFormatter.appendToUserContent("分析", listOf(attachment), session())

        assertThat(formatted).contains("name=\"report.md\"")
        assertThat(formatted).contains("/session/attachments/report.md")
        assertThat(formatted).contains("20")
        assertThat(formatted).contains("1024")
        assertThat(formatted).contains("正文未放入上下文")
        assertThat(formatted).contains("read_file")
        assertThat(formatted).contains("分页")
        assertThat(formatted).contains("行范围")
        assertThat(formatted).doesNotContain(attachment.content)
        assertThat(formatted).doesNotContain("<<<INFINITY_DOCUMENT_BEGIN")
    }

    @Test fun `引用路径转义且混合普通文档保留原文`() {
        val reference = document().copy(workspaceReference = privateReference().copy(path = "/session/docs/a\"<&>.md"))
        val inline = document().copy(id = "inline", name = "inline.txt", content = "普通附件正文")

        val formatted = FullContextDocumentFormatter.appendToUserContent("", listOf(reference, inline), session())

        assertThat(formatted).contains("/session/docs/a&quot;&lt;&amp;&gt;.md")
        assertThat(formatted).contains(inline.content)
        assertThat(formatted).doesNotContain(reference.content)
    }

    @Test fun `保存于A工作区的引用切到B后省略旧路径和读取指令`() {
        val attachment = document().copy(workspaceReference = directoryReference())

        val source = FullContextDocumentFormatter.appendToUserContent("", listOf(attachment), session().copy(workspaceId = "workspace-a"))
        val switched = FullContextDocumentFormatter.appendToUserContent("", listOf(attachment), session().copy(workspaceId = "workspace-b"))

        assertThat(source).contains("/attachments/report.md")
        assertThat(source).contains("read_file")
        assertInvalidReference(switched, attachment)
    }

    @Test fun `运行模式变化后旧命名空间引用失效`() {
        val attachment = document().copy(workspaceReference = directoryReference())

        val formatted = FullContextDocumentFormatter.appendToUserContent(
            "", listOf(attachment), session().copy(workspaceId = "workspace-a", runMode = SessionRunMode.LINUX),
        )

        assertInvalidReference(formatted, attachment)
    }

    @Test fun `私有引用切换主工作区后仍显式读取本会话私有路径`() {
        val attachment = document().copy(workspaceReference = privateReference())

        val formatted = FullContextDocumentFormatter.appendToUserContent(
            "", listOf(attachment), session().copy(workspaceId = "workspace-b"),
        )

        assertThat(formatted).contains("/session/attachments/report.md")
        assertThat(formatted).contains("read_file")
        assertThat(formatted).doesNotContain(attachment.content)
    }

    @Test fun `Linux私有引用切换主工作区后仍读取home-session路径`() {
        val attachment = document().copy(workspaceReference = privateReference().copy(
            path = "/home/session/attachments/report.md", linuxNamespace = true,
        ))

        val formatted = FullContextDocumentFormatter.appendToUserContent(
            "", listOf(attachment), session().copy(workspaceId = "workspace-b", runMode = SessionRunMode.LINUX),
        )

        assertThat(formatted).contains("/home/session/attachments/report.md")
        assertThat(formatted).contains("read_file")
    }

    @Test fun `缺少会话上下文和跨会话引用均只输出失效说明`() {
        val attachment = document().copy(workspaceReference = privateReference())

        assertInvalidReference(FullContextDocumentFormatter.appendToUserContent("", listOf(attachment)), attachment)
        assertInvalidReference(
            FullContextDocumentFormatter.appendToUserContent("", listOf(attachment), session().copy(id = "another-session")), attachment,
        )
    }

    @Test fun `旧引用JSON默认缺少来源绑定并按失效处理`() {
        val legacy = """{"path":"/attachments/report.md","lineCount":20,"estimatedTokens":1024}"""
        val reference = Json.decodeFromString<WorkspaceDocumentReference>(legacy)
        val attachment = document().copy(workspaceReference = reference)

        assertThat(reference.sourceSessionId).isNull()
        assertThat(reference.isPrivate).isNull()
        assertThat(reference.linuxNamespace).isNull()
        assertInvalidReference(FullContextDocumentFormatter.appendToUserContent("", listOf(attachment), session()), attachment)
    }

    @Test fun `worktree引用核验当前分支目录而不沿用父工作区ID`() {
        val reference = directoryReference().copy(
            workspaceId = "branch-worktree:session", worktreeDirectory = "/workspace/branches/one", linuxNamespace = true,
            path = "/workspace/attachments/report.md",
        )
        val attachment = document().copy(workspaceReference = reference)
        val branch = session().copy(
            workspaceId = "parent-workspace", worktreeDirectory = "/workspace/branches/one", runMode = SessionRunMode.LINUX,
        )

        val valid = FullContextDocumentFormatter.appendToUserContent("", listOf(attachment), branch)
        val moved = FullContextDocumentFormatter.appendToUserContent(
            "", listOf(attachment), branch.copy(worktreeDirectory = "/workspace/branches/two"),
        )

        assertThat(valid).contains("read_file")
        assertInvalidReference(moved, attachment)
    }

    @Test fun `来源标为私有但路径仍使用主根时拒绝读取`() {
        val attachment = document().copy(workspaceReference = privateReference().copy(path = "/attachments/report.md"))

        assertInvalidReference(FullContextDocumentFormatter.appendToUserContent("", listOf(attachment), session()), attachment)
    }

    private fun assertInvalidReference(formatted: String, attachment: MessageDocumentAttachment) {
        assertThat(formatted).contains("name=\"report.md\"")
        assertThat(formatted).contains("失效")
        assertThat(formatted).doesNotContain(requireNotNull(attachment.workspaceReference).path)
        assertThat(formatted).doesNotContain("read_file")
        assertThat(formatted).doesNotContain(attachment.content)
    }

    private fun session() = Session("session", "agent", runMode = SessionRunMode.BUILTIN)

    private fun privateReference() = WorkspaceDocumentReference(
        "/session/attachments/report.md", 20, 1024, sourceSessionId = "session", isPrivate = true, linuxNamespace = false,
    )

    private fun directoryReference() = WorkspaceDocumentReference(
        "/attachments/report.md", 20, 1024, sourceSessionId = "session", workspaceId = "workspace-a", isPrivate = false, linuxNamespace = false,
    )

    private fun document() = MessageDocumentAttachment(
        id = "doc", name = "report.md", mimeType = "text/markdown", content = "引用附件残留正文",
        sizeBytes = 24, sha256 = "hash", estimatedTokens = 4,
    )
}
