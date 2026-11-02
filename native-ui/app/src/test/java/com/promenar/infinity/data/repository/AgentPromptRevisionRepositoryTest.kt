package com.promenar.infinity.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.domain.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class AgentPromptRevisionRepositoryTest {
    private lateinit var db: InfinityDatabase
    private lateinit var repo: AgentRepository
    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = AgentRepository(db.agentDao(), db.agentRuleRevisionDao(), db)
    }
    @After fun tearDown() = db.close()

    @Test fun `字段保存保留其它列且冲突不写入任何字段`(): Unit = runBlocking {
        val original = Agent("a", "原名", systemPrompt = "原提示", isPinned = true, userRules = "停用列")
        repo.create(original)
        val stale = original.copy(name = "他人名字")
        val conflict = repo.updateFields("a", setOf(AgentField.NAME, AgentField.SYSTEM_PROMPT),
            original.copy(name = "新名", systemPrompt = "新提示"), stale)
        assertThat(conflict).isInstanceOf(AgentFieldUpdate.Conflict::class.java)
        assertThat(repo.getById("a")).isEqualTo(original)
        assertThat(repo.observePromptRevisions("a").first()).isEmpty()
        repo.updateFields("a", setOf(AgentField.NAME), original.copy(name = "新名", isPinned = false, userRules = "丢弃"))
        assertThat(repo.getById("a")).isEqualTo(original.copy(name = "新名"))
    }

    @Test fun `首次修改记录空基线并按最新SHA去重`(): Unit = runBlocking {
        val original = Agent("a", "名字")
        repo.create(original)
        repo.updateFields("a", setOf(AgentField.SYSTEM_PROMPT), original.copy(systemPrompt = "新提示"),
            promptSource = AgentPromptRevision.Source.AGENT)
        val versions = repo.observePromptRevisions("a").first()
        assertThat(versions.map { it.content }).containsExactly("新提示", "").inOrder()
        assertThat(versions.map { it.source }).containsExactly(AgentPromptRevision.Source.AGENT, AgentPromptRevision.Source.USER).inOrder()
        assertThat(repo.recordPromptRevision("a", "新提示", AgentPromptRevision.Source.USER)).isFalse()
        assertThat(repo.recordPromptRevision("a", "另一方", AgentPromptRevision.Source.USER)).isTrue()
        repo.updateFields("a", setOf(AgentField.SYSTEM_PROMPT), original.copy(systemPrompt = "另一方"))
        assertThat(repo.observePromptRevisions("a").first()).hasSize(3)
    }

    @Test fun `超过二十版裁剪且恢复记录rollback并拒绝跨Agent版本`(): Unit = runBlocking {
        repo.create(Agent("a", "名字"))
        repo.create(Agent("b", "其它"))
        repeat(25) { index -> repo.recordPromptRevision("a", "提示$index", AgentPromptRevision.Source.USER) }
        val versions = repo.observePromptRevisions("a").first()
        assertThat(versions).hasSize(20)
        assertThat(versions.first().content).isEqualTo("提示24")
        assertThat(versions.last().content).isEqualTo("提示5")
        assertThat(repo.restorePromptRevision("b", versions.last().id)).isEqualTo(AgentFieldUpdate.NotFound)
        assertThat(repo.restorePromptRevision("a", "不存在")).isEqualTo(AgentFieldUpdate.NotFound)
        assertThat(repo.restorePromptRevision("a", versions.last().id)).isInstanceOf(AgentFieldUpdate.Updated::class.java)
        assertThat(repo.getById("a")!!.systemPrompt).isEqualTo("提示5")
        assertThat(repo.observePromptRevisions("a").first().first().source).isEqualTo(AgentPromptRevision.Source.ROLLBACK)
        assertThat(repo.observePromptRevisions("a").first()).hasSize(20)
    }

    @Test fun `工具新建可记录AGENT来源而普通新建保持无历史`(): Unit = runBlocking {
        repo.create(Agent("a", "普通", systemPrompt = "提示"))
        repo.create(Agent("b", "工具", systemPrompt = "提示"), AgentPromptRevision.Source.AGENT)
        assertThat(repo.observePromptRevisions("a").first()).isEmpty()
        assertThat(repo.observePromptRevisions("b").first().single().source).isEqualTo(AgentPromptRevision.Source.AGENT)
    }

    @Test fun `版本记录失败时事务回滚提示词与基线`(): Unit = runBlocking {
        val original = Agent("a", "名字", systemPrompt = "原提示")
        repo.create(original)
        val dao = db.agentRuleRevisionDao()
        val failing = object : com.promenar.infinity.data.local.db.dao.AgentRuleRevisionDao by dao {
            override suspend fun record(revision: com.promenar.infinity.data.local.db.entity.AgentRuleRevisionEntity) {
                if (revision.source == "agent") throw IllegalStateException("受控写入失败")
                dao.record(revision)
            }
        }
        val guarded = AgentRepository(db.agentDao(), failing, db)
        val result = runCatching { guarded.updateFields("a", setOf(AgentField.SYSTEM_PROMPT), original.copy(systemPrompt = "新提示"),
            promptSource = AgentPromptRevision.Source.AGENT) }
        assertThat(result.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(repo.getById("a")!!.systemPrompt).isEqualTo("原提示")
        assertThat(repo.observePromptRevisions("a").first()).isEmpty()
    }

    @Test fun `整行更新按传入值保存停用列`(): Unit = runBlocking {
        val original = Agent("a", "名字", userRules = "旧值")
        repo.create(original)
        repo.update(original.copy(userRules = "传入值"))
        assertThat(repo.getById("a")!!.userRules).isEqualTo("传入值")
    }
}
