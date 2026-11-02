package com.promenar.infinity.data.local.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.AgentRuleRevisionEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.data.repository.AgentRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 自选工作区登记、Agent/会话绑定与用户规则历史的持久化契约。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class WorkspaceBindingDaoTest {
    private lateinit var db: InfinityDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private fun workspace(id: String, kind: String = WorkspaceEntity.KIND_SHARED, location: String = "/storage/emulated/0/Code/repo") =
        WorkspaceEntity(id, kind, "仓库😀", location, createdAt = 1)

    @Test
    fun `Linux主目录使用现有字符串列且登记与原始数据库读取兼容`(): Unit = runBlocking {
        val first = db.workspaceDao().register(workspace("linux", WorkspaceEntity.KIND_LINUX_HOME,
            "/root#nx1:00000000-0000-4000-8000-000000000001|" + "a".repeat(64)))
        assertThat(db.workspaceDao().register(first.copy(id = "duplicate"))).isEqualTo(first)
        db.openHelper.readableDatabase.query("SELECT kind, location FROM workspaces WHERE id = 'linux'").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("linux_home")
            assertThat(it.getString(1)).isEqualTo(first.location)
        }
        assertThat(WorkspaceEntity.isValidLocation(WorkspaceEntity.KIND_LINUX_HOME, "/root/a")).isFalse()
        // 含设备号的旧身份格式不迁移，直接视为无效。
        assertThat(WorkspaceEntity.isValidLocation(WorkspaceEntity.KIND_LINUX_HOME, "/root#" + "a".repeat(64))).isFalse()
    }

    @Test
    fun `同一目录重复登记返回已有记录`(): Unit = runBlocking {
        val dao = db.workspaceDao()
        val first = dao.register(workspace("ws-1"))
        val second = dao.register(workspace("ws-2").copy(displayName = "别名"))

        assertThat(second).isEqualTo(first)
        assertThat(dao.getById("ws-2")).isNull()
        // 同一位置字符串在不同类型下是不同目录。
        assertThat(dao.register(workspace("ws-3", WorkspaceEntity.KIND_PROJECT, "repo")).id).isEqualTo("ws-3")
    }

    @Test
    fun `登记拒绝不合法的位置`(): Unit = runBlocking {
        val dao = db.workspaceDao()
        listOf(
            workspace("a", location = "relative/path"),
            workspace("b", location = "/storage/emulated/0/a/../b"),
            workspace("c", location = "/storage/emulated/0/"),
            workspace("d", kind = WorkspaceEntity.KIND_PROJECT, location = "a/b"),
            workspace("e", kind = "saf_tree", location = "/storage/emulated/0/x"),
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { dao.register(candidate) } }
        }
        assertThat(dao.getById("a")).isNull()
    }

    @Test
    fun `仍被会话或Agent引用的工作区不能删除`(): Unit = runBlocking {
        val dao = db.workspaceDao()
        dao.register(workspace("ws-1"))
        db.agentDao().insert(AgentEntity("agent", "Agent", createdAt = 1, defaultWorkspaceId = "ws-1"))
        db.sessionDao().insert(SessionEntity("session", "agent", workspaceId = "ws-1", createdAt = 1, updatedAt = 1))

        assertThat(dao.deleteIfUnreferenced("ws-1")).isFalse()
        db.sessionDao().deleteById("session")
        assertThat(dao.deleteIfUnreferenced("ws-1")).isFalse()
        db.agentDao().insert(AgentEntity("agent", "Agent", createdAt = 1))
        assertThat(dao.deleteIfUnreferenced("ws-1")).isTrue()
        assertThat(dao.getById("ws-1")).isNull()
    }

    @Test
    fun `规则历史按Agent只保留最近20个版本`(): Unit = runBlocking {
        val dao = db.agentRuleRevisionDao()
        repeat(25) { index ->
            dao.record(AgentRuleRevisionEntity("a-$index", "agent-a", "规则 $index", "0".repeat(64), "agent", createdAt = index.toLong()))
        }
        dao.record(AgentRuleRevisionEntity("b-0", "agent-b", "其它", "1".repeat(64), "user", createdAt = 0))

        val kept = dao.listForAgent("agent-a")
        assertThat(kept).hasSize(AgentRuleRevisionEntity.MAX_REVISIONS_PER_AGENT)
        assertThat(kept.first().id).isEqualTo("a-24")
        assertThat(kept.last().id).isEqualTo("a-5")
        assertThat(dao.listForAgent("agent-b").map { it.id }).containsExactly("b-0")
    }

    @Test
    fun `整行更新Agent写入调用方提供的停用列`(): Unit = runBlocking {
        val agentDao = db.agentDao()
        agentDao.insert(AgentEntity("agent", "Agent", createdAt = 1))
        val staleCopy = agentDao.getById("agent")!!
        assertThat(agentDao.updateUserRules("agent", "新规则")).isEqualTo(1)

        agentDao.update(staleCopy.copy(name = "改名", defaultWorkspaceId = "ws-1"))

        val saved = agentDao.getById("agent")!!
        assertThat(saved.name).isEqualTo("改名")
        assertThat(saved.defaultWorkspaceId).isEqualTo("ws-1")
        assertThat(saved.userRules).isEqualTo("")
    }

    @Test
    fun `仓库更新与删除Agent经领域模型往返不丢失新字段`(): Unit = runBlocking {
        val repository = AgentRepository(db.agentDao())
        db.agentDao().insert(AgentEntity("agent", "Agent", createdAt = 1, defaultWorkspaceId = "ws-1", userRules = "规则"))
        db.agentDao().insert(AgentEntity("other", "Other", createdAt = 2))
        val revisions = db.agentRuleRevisionDao()
        revisions.record(AgentRuleRevisionEntity("r-1", "agent", "旧", "0".repeat(64), "user", 1))
        revisions.record(AgentRuleRevisionEntity("r-2", "other", "旧", "0".repeat(64), "user", 1))

        val domain = repository.getById("agent")!!
        assertThat(domain.defaultWorkspaceId).isEqualTo("ws-1")
        assertThat(domain.userRules).isEqualTo("规则")
        repository.update(domain.copy(name = "改名", userRules = "界面旧副本"))
        val saved = db.agentDao().getById("agent")!!
        assertThat(saved.name).isEqualTo("改名")
        assertThat(saved.defaultWorkspaceId).isEqualTo("ws-1")
        assertThat(saved.userRules).isEqualTo("界面旧副本")

        repository.delete("agent")
        assertThat(db.agentDao().getById("agent")).isNull()
        assertThat(revisions.listForAgent("agent")).isEmpty()
        assertThat(revisions.listForAgent("other")).hasSize(1)
    }

    @Test
    fun `会话工作区绑定经领域模型往返`() {
        val entity = SessionEntity("session", "agent", workspaceId = "ws-1", createdAt = 1, updatedAt = 1)
        val domain = entity.toDomain()
        assertThat(domain.workspaceId).isEqualTo("ws-1")
        assertThat(domain.toEntity().workspaceId).isEqualTo("ws-1")
        assertThat(domain.copy(workspaceId = null).toEntity().workspaceId).isNull()
    }
}
