package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.stubUpdateWith
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class MessageRepositoryKgPathsTest {
    @Test
    fun `partial update encodes kgPaths and reload decodes them`() = runTest {
        val path = KgPath(
            queryKeywords = listOf("Infinity Deck"),
            nodes = listOf(KgNode("n1", "Infinity Deck", "project")),
            edges = emptyList(),
        )
        val original = MessageEntity(
            id = "m1",
            sessionId = "s1",
            role = "assistant",
            content = "answer",
            createdAt = 1L,
        )
        val dao = mockk<MessageDao>(relaxed = true)
        coEvery { dao.getById("m1") } returns original
        coEvery { dao.update(any()) } returns 1
        dao.stubUpdateWith()
        val repository = MessageRepository(dao)

        repository.updatePartial("m1", mapOf("kgPaths" to listOf(path)))

        coVerify {
            dao.update(match { updated ->
                updated.kgPaths?.contains("Infinity Deck") == true
            })
        }
    }
}
