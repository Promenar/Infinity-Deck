package com.promenar.infinity.data.memory

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.SessionOptions
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.data.generation.DefaultSessionToolResolver
import com.promenar.infinity.data.generation.BuiltinToolPreferences
import com.promenar.infinity.ui.chat.manager.registry.DefaultSkillRegistry
import com.promenar.infinity.ui.chat.manager.skills.longTermMemoryTools
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], application = Application::class)
class LongTermMemoryAccessTest {
    @Test fun `旧私密智能体也能使用开启的主动记忆工具`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("memory-access-private-migration-test", 0)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).build()
        try {
            val session = Session("session-private", "agent-private")
            db.agentDao().insert(AgentEntity(id = "agent-private", name = "测试智能体", createdAt = 1,
                retrievalConfig = AgentRetrievalConfig(memoryPolicy = AgentMemoryPolicy(shareWithOtherAgents = false))))
            db.sessionDao().insert(session.toEntity())
            val access = RoomLongTermMemoryAccess(db, prefs)
            val registry = DefaultSkillRegistry().apply {
                longTermMemoryTools(RoomLongTermMemoryRepository(db), access).forEach(::register)
            }
            val resolver = DefaultSessionToolResolver(prefs, registry, longTermMemoryAllowed = { runBlocking { access.isEnabled(it) } })

            assertThat(access.isEnabled(session)).isTrue()
            assertThat(resolver.resolve(session).map { it.runtimeToolId })
                .containsExactly("memory_save", "memory_forget", "memory_read")
        } finally { db.close() }
    }

    @Test fun `工具关闭和全局关闭都立即撤销广告与执行权限`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("memory-access-test", 0)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).build()
        try {
            val access = RoomLongTermMemoryAccess(db, prefs)
            val repo = RoomLongTermMemoryRepository(db)
            val registry = DefaultSkillRegistry().apply { longTermMemoryTools(repo, access).forEach(::register) }
            val resolver = DefaultSessionToolResolver(prefs, registry, longTermMemoryAllowed = { runBlocking { access.isEnabled(it) } })
            val session = Session("s", "a")
            db.agentDao().insert(AgentEntity(id = "a", name = "共享助手", createdAt = 1))
            db.sessionDao().insert(session.toEntity())
            assertThat(LongTermMemoryPreferences.isEnabled(prefs)).isTrue()
            assertThat(access.isEnabled("s")).isTrue()
            assertThat(resolver.resolve(session).map { it.runtimeToolId }).containsExactly("memory_save", "memory_forget", "memory_read")
            val privateAgent = db.agentDao().getById("a")!!.copy(retrievalConfig = AgentRetrievalConfig(memoryPolicy = AgentMemoryPolicy(shareWithOtherAgents = false)))
            db.agentDao().update(privateAgent)
            assertThat(access.isEnabled(session)).isTrue()
            assertThat(access.isEnabled("s")).isTrue()
            assertThat(resolver.resolve(session).map { it.runtimeToolId }).containsExactly("memory_save", "memory_forget", "memory_read")
            db.agentDao().update(privateAgent.copy(retrievalConfig = null))
            assertThat(resolver.resolve(session.copy(options = SessionOptions(toolsEnabled = false)))).isEmpty()
            assertThat(access.isEnabled(session.copy(options = SessionOptions(toolsEnabled = false)))).isFalse()
            LongTermMemoryPreferences.setEnabled(prefs, false)
            assertThat(access.isEnabled("s")).isFalse()
            assertThat(resolver.resolve(session)).isEmpty()
            LongTermMemoryPreferences.setEnabled(prefs, true)
            prefs.edit().putStringSet(BuiltinToolPreferences.KNOWN_KEY, LongTermMemoryPreferences.TOOL_IDS).putStringSet(BuiltinToolPreferences.ENABLED_KEY, setOf("memory_read")).commit()
            assertThat(resolver.resolve(session).map { it.runtimeToolId }).containsExactly("memory_read")
            assertThat(access.isEnabled(session.copy(agentId = ""))).isTrue()
            assertThat(access.isEnabled("missing-session")).isFalse()
        } finally { db.close() }
    }
    @Test fun `内存中的会话开关优先于持久化旧快照`() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("memory-access-live-test", 0)
        prefs.edit().clear().commit()
        val db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java).build()
        try {
            val session = Session("s", "")
            db.sessionDao().insert(session.toEntity())
            val access = RoomLongTermMemoryAccess(db, prefs) { session.copy(options = SessionOptions(toolsEnabled = false)) }
            assertThat(access.isEnabled("s")).isFalse()
            assertThat(access.isEnabled(session)).isFalse()
        } finally { db.close() }
    }
}
