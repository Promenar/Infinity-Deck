package com.promenar.infinity.data.repository

import app.cash.turbine.test
import com.promenar.infinity.data.local.db.dao.AgentDao
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.domain.model.ExecutionMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class AgentRepositoryTest {

    private fun createEntity(id: String, name: String) = AgentEntity(
        id = id, name = name, description = "", systemPrompt = "",
        model = "m", icon = "✨", color = "#000", avatarPath = null,
        isPinned = 0, temperature = null, top_p = null, max_tokens = null,
        ragConfig = null, retrievalConfig = null, useInheritedConfig = true,
        createdAt = 0L
    )

    @Test
    fun `observeAll maps entities to domain`() = runTest {
        val dao: AgentDao = mockk()
        val repo = AgentRepository(dao)
        val entities = listOf(createEntity("a1", "Agent1"), createEntity("a2", "Agent2"))
        every { dao.observeAll() } returns flowOf(entities)

        repo.observeAll().test {
            val agents = awaitItem()
            assertThat(agents).hasSize(2)
            assertThat(agents[0].name).isEqualTo("Agent1")
            assertThat(agents[0].modelId).isEqualTo("m")
            assertThat(agents[1].name).isEqualTo("Agent2")
            awaitComplete()
        }
    }

    @Test
    fun `observeById finds matching agent`() = runTest {
        val dao: AgentDao = mockk()
        val repo = AgentRepository(dao)
        val entities = listOf(createEntity("target", "Target"), createEntity("other", "Other"))
        every { dao.observeAll() } returns flowOf(entities)

        repo.observeById("target").test {
            val agent = awaitItem()
            assertThat(agent).isNotNull()
            assertThat(agent!!.name).isEqualTo("Target")
            awaitComplete()
        }
    }

    @Test
    fun `observeById returns null for missing id`() = runTest {
        val dao: AgentDao = mockk()
        val repo = AgentRepository(dao)
        every { dao.observeAll() } returns flowOf(emptyList())

        repo.observeById("nonexistent").test {
            assertThat(awaitItem()).isNull()
            awaitComplete()
        }
    }

    @Test
    fun `create delegates to dao insert`() = runTest {
        val dao: AgentDao = mockk(relaxed = true)
        val repo = AgentRepository(dao)
        val agent = com.promenar.infinity.domain.model.Agent(
            id = "new", name = "New", executionMode = ExecutionMode.WORKSPACE
        )

        repo.create(agent)

        coVerify { dao.insert(any()) }
    }

    @Test
    fun `update writes the supplied user rules column`() = runTest {
        val dao: AgentDao = mockk(relaxed = true)
        val repo = AgentRepository(dao)
        val agent = com.promenar.infinity.domain.model.Agent(
            id = "update-me", name = "Updated", executionMode = ExecutionMode.WORKSPACE
        )

        repo.update(agent)

        coVerify { dao.update(match { it.id == "update-me" && it.name == "Updated" }) }
    }

    @Test
    fun `delete removes agent together with its rule revisions`() = runTest {
        val dao: AgentDao = mockk(relaxed = true)
        val repo = AgentRepository(dao)

        repo.delete("delete-me")

        coVerify { dao.deleteWithRuleRevisions("delete-me") }
    }

    @Test
    fun `create maps domain agent to entity`() = runTest {
        val dao: AgentDao = mockk(relaxed = true)
        val repo = AgentRepository(dao)
        val agent = com.promenar.infinity.domain.model.Agent(
            id = "mapped",
            name = "Mapped",
            modelId = "gpt-4",
            isPinned = true,
            temperature = 0.5,
            topP = 0.8,
            maxTokens = 2048
        )

        repo.create(agent)

        coVerify {
            dao.insert(match { entity ->
                entity.id == "mapped" &&
                entity.name == "Mapped" &&
                entity.model == "gpt-4" &&
                entity.isPinned == 1 &&
                entity.temperature == 0.5
            })
        }
    }
}
