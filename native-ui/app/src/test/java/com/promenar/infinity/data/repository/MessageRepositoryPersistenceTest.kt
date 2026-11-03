package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.stubUpdateWith
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.WorkspaceDocumentReference
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.model.toEntity
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessageRepositoryPersistenceTest {
    @Test fun `files局部更新实际写回图片与文档引用且可重新加载`() = runTest {
        val dao = mockk<MessageDao>()
        val original = Message("u", MessageRole.USER, "问题", userImages = listOf("data:image/png;base64,AA=="), turnContext = "上下文")
        var entity = original.toEntity("s")
        coEvery { dao.getById("u") } answers { entity }
        coEvery { dao.update(any()) } answers { entity = firstArg(); 1 }
        dao.stubUpdateWith()
        val repository = MessageRepository(dao)
        val reference = WorkspaceDocumentReference("/session/attachments/image.png", 1, 12,
            sourceSessionId = "s", isPrivate = true, linuxNamespace = false, sha256 = "hash")
        val document = MessageDocumentAttachment("d", "a.txt", "text/plain", "全文", 6, "hash", 2,
            workspaceReference = reference.copy(path = "/session/attachments/a.txt"), inlineInCurrentTurn = true)
        val files = original.copy(userImageReferences = listOf(reference), userDocuments = listOf(document)).toEntity("s").files

        repository.updatePartial("u", mapOf("files" to files))

        val reloaded = repository.getById("u")!!
        assertThat(entity.files).isEqualTo(files)
        assertThat(reloaded.userImageReferences).containsExactly(reference)
        assertThat(reloaded.userDocuments).containsExactly(document)
        assertThat(reloaded.userImages).isEqualTo(original.userImages)
        assertThat(reloaded.turnContext).isEqualTo(original.turnContext)
        repository.updatePartial("u", mapOf("files" to null))
        assertThat(entity.toDomain().userImageReferences).isNull()
        assertThat(entity.files).isNull()
    }

    @Test
    fun `updatePartial目标行缺失时抛明确持久化异常`() = runTest {
        val dao = mockk<MessageDao>()
        coEvery { dao.getById("missing") } returns null
        dao.stubUpdateWith()
        val repository = MessageRepository(dao)

        val failure = runCatching {
            repository.updatePartial("missing", mapOf("status" to "success"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MessagePersistenceException::class.java)
        assertThat(failure?.message).contains("missing")
    }

    @Test
    fun `updatePartial affectedRows为零时抛明确持久化异常`() = runTest {
        val dao = mockk<MessageDao>()
        val entity = Message("m1", MessageRole.ASSISTANT, "partial").toEntity("s1")
        coEvery { dao.getById("m1") } returns entity
        coEvery { dao.update(any()) } returns 0
        dao.stubUpdateWith()
        val repository = MessageRepository(dao)

        val failure = runCatching {
            repository.updatePartial("m1", mapOf("status" to "success"))
        }.exceptionOrNull()

        assertThat(failure).isInstanceOf(MessagePersistenceException::class.java)
        assertThat(failure?.message).contains("affectedRows=0")
    }
}
