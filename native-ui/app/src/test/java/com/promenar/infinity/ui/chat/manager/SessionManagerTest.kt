package com.promenar.infinity.ui.chat.manager

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.repository.ISessionRepository
import com.promenar.infinity.ui.chat.ChatStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {
    @Test fun `删除会话撤销全部类别授权且删除失败保留授权`() = runTest {
        val grants = com.promenar.infinity.data.approval.TimedApprovalGrantStore(backgroundScope) { testScheduler.currentTime }
        val manager = SessionManager(ChatStore(), stubSessionRepo, timedGrants = grants)
        grants.grant("deleted", com.promenar.infinity.domain.tool.ApprovalGrantCategory.SKILLS)
        grants.grant("other", com.promenar.infinity.domain.tool.ApprovalGrantCategory.COMMAND)
        manager.deleteSession("deleted")
        assertThat(grants.observe("deleted").value).isEmpty()
        assertThat(grants.observe("other").value).isNotEmpty()
        val failing = object : ISessionRepository by stubSessionRepo {
            override suspend fun delete(id: String) { error("删除失败") }
        }
        runCatching { SessionManager(ChatStore(), failing, timedGrants = grants).deleteSession("other") }
        assertThat(grants.observe("other").value).isNotEmpty()
    }

    @Test
    fun `创建会话的内存状态使用仓库确定的运行模式`() = kotlinx.coroutines.test.runTest {
        val localStore = ChatStore()
        var saved: Session? = null
        val repository = object : ISessionRepository by stubSessionRepo {
            override suspend fun create(session: Session) {
                saved = session.copy(runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX)
            }
            override suspend fun getById(id: String): Session? = saved?.takeIf { it.id == id }
        }
        SessionManager(localStore, repository).addSession(Session("fixed", "agent"))
        assertThat(localStore.getSession("fixed")!!.runMode)
            .isEqualTo(com.promenar.infinity.domain.model.SessionRunMode.LINUX)
    }

    private lateinit var store: ChatStore
    private lateinit var sessionManager: SessionManager
    private val testScope = TestScope()

    private val stubSessionRepo = object : ISessionRepository {
        override suspend fun create(session: Session) {}
        override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {}
        override suspend fun delete(id: String) {}
        override suspend fun getById(id: String): Session? = null
        override suspend fun getAll(): List<Session> = emptyList()
    }

    @Before
    fun setUp() {
        store = ChatStore()
        sessionManager = SessionManager(store, stubSessionRepo)
    }

    @Test
    fun addSession() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1", title = "Test")
        sessionManager.addSession(session)
        advanceUntilIdle()

        val result = sessionManager.getSession("s1")
        assertThat(result).isNotNull()
        assertThat(result!!.title).isEqualTo("Test")
        assertThat(result.agentId).isEqualTo("a1")
        assertThat(result.loopStatus).isEqualTo(LoopStatus.COMPLETED)
        assertThat(result.executionMode).isEqualTo("workspace")
    }

    @Test
    fun `addSession normalizes unknown execution mode to semi before persistence`() = testScope.runTest {
        var persisted: Session? = null
        val recordingRepository = object : ISessionRepository by stubSessionRepo {
            override suspend fun create(session: Session) {
                persisted = session
            }
        }
        val manager = SessionManager(store, recordingRepository)

        manager.addSession(Session(id = "unknown-mode", agentId = "a1", executionMode = "future"))

        assertThat(persisted!!.executionMode).isEqualTo("workspace")
        assertThat(manager.getSession("unknown-mode")!!.executionMode).isEqualTo("workspace")
    }

    @Test
    fun `updateExecutionMode persists each valid mode and normalizes invalid input`() = testScope.runTest {
        val updates = mutableListOf<Map<String, Any?>>()
        val recordingRepository = object : ISessionRepository by stubSessionRepo {
            override suspend fun updatePartial(id: String, values: Map<String, Any?>) {
                updates += values
            }
        }
        val manager = SessionManager(store, recordingRepository)
        manager.addSession(Session(id = "mode-session", agentId = "a1"))

        manager.updateSessionExecutionMode("mode-session", "auto")
        manager.updateSessionExecutionMode("mode-session", "manual")
        manager.updateSessionExecutionMode("mode-session", "semi")
        manager.updateSessionExecutionMode("mode-session", "invalid")

        assertThat(updates.map { it["executionMode"] })
            .containsExactly("full_access", "workspace", "workspace", "workspace")
            .inOrder()
        assertThat(manager.getSession("mode-session")!!.executionMode).isEqualTo("workspace")
    }

    @Test
    fun `数据库快照已先发布同一会话时addSession必须按id幂等upsert`() = testScope.runTest {
        val fromDatabase = Session(id = "s1", agentId = "a1", title = "Database")
        store.update { it.copy(sessions = listOf(fromDatabase)) }

        sessionManager.addSession(fromDatabase.copy(title = "Owner"))

        assertThat(store.get().sessions.map { it.id }).containsExactly("s1")
        assertThat(store.get().sessions.single().title).isEqualTo("Owner")
    }

    @Test
    fun `addSession 持久化失败时不得创建内存幽灵会话`() = testScope.runTest {
        val failure = IllegalStateException("database write failed")
        val failingRepository = object : ISessionRepository by stubSessionRepo {
            override suspend fun create(session: Session) {
                throw failure
            }
        }
        val manager = SessionManager(store, failingRepository)

        val observed = runCatching {
            manager.addSession(Session(id = "failed", agentId = "a1"))
        }.exceptionOrNull()

        assertThat(observed).isSameInstanceAs(failure)
        assertThat(manager.getSession("failed")).isNull()
    }

    @Test
    fun `workspace root 更新必须同步完整写回 ChatStore`() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1"))

        sessionManager.updateSession(
            "s1",
            mapOf(
                "workspaceRootUuid" to "root-1",
                "workspacePath" to "/private/root-1",
            ),
        )

        val updated = sessionManager.getSession("s1")!!
        assertThat(updated.workspaceRootUuid).isEqualTo("root-1")
        assertThat(updated.workspacePath).isEqualTo("/private/root-1")
    }

    @Test
    fun `updateSession 持久化失败时不得污染内存会话`() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1", title = "Before"))
        val failure = IllegalStateException("database update failed")
        val failingRepository = object : ISessionRepository by stubSessionRepo {
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
                throw failure
            }
        }
        val manager = SessionManager(store, failingRepository)

        val observed = runCatching {
            manager.updateSessionTitle("s1", "After")
        }.exceptionOrNull()

        assertThat(observed).isSameInstanceAs(failure)
        assertThat(manager.getSession("s1")!!.title).isEqualTo("Before")
    }

    @Test
    fun `updateSession 取消时必须传播且不得污染内存会话`() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1", title = "Before"))
        val cancellation = CancellationException("cancelled")
        val cancellingRepository = object : ISessionRepository by stubSessionRepo {
            override suspend fun updatePartial(id: String, updates: Map<String, Any?>) {
                throw cancellation
            }
        }
        val manager = SessionManager(store, cancellingRepository)

        val observed = runCatching {
            manager.updateSessionTitle("s1", "After")
        }.exceptionOrNull()

        assertThat(observed).isSameInstanceAs(cancellation)
        assertThat(manager.getSession("s1")!!.title).isEqualTo("Before")
    }

    @Test
    fun `deleteSession 持久化失败时不得移除内存会话`() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1"))
        val failure = IllegalStateException("database delete failed")
        val failingRepository = object : ISessionRepository by stubSessionRepo {
            override suspend fun delete(id: String) {
                throw failure
            }
        }
        val manager = SessionManager(store, failingRepository)

        val observed = runCatching { manager.deleteSession("s1") }.exceptionOrNull()

        assertThat(observed).isSameInstanceAs(failure)
        assertThat(manager.getSession("s1")).isNotNull()
    }

    @Test
    fun `deleteSession 协调删除失败时保留内存会话并暴露可重试错误`() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1"))
        val failure = IllegalStateException("workspace conflict")
        val manager = SessionManager(store, stubSessionRepo) { _, _ ->
            com.promenar.infinity.data.session.SessionDeletionResult.Failed(
                com.promenar.infinity.data.session.SessionDeletionError(
                    com.promenar.infinity.data.session.SessionDeletionErrorCode.WORKSPACE,
                    failure,
                ),
            )
        }

        val observed = runCatching { manager.deleteSession("s1") }.exceptionOrNull()

        assertThat(observed).hasCauseThat().isSameInstanceAs(failure)
        assertThat(manager.getSession("s1")).isNotNull()
    }

    @Test
    fun deleteSession() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1")
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.deleteSession("s1")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")).isNull()
    }

    @Test
    fun `deleteSession 透传保留参数并返回显示名`() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1"))
        val kept = com.promenar.infinity.data.session.SessionDeletionResult.DeletedKeepingFiles("workspace", "会话文件")
        val manager = SessionManager(store, stubSessionRepo) { id, keepFiles ->
            assertThat(id).isEqualTo("s1")
            assertThat(keepFiles).isTrue()
            kept
        }
        assertThat(manager.deleteSession("s1", keepFiles = true)).isEqualTo(kept)
        assertThat(manager.getSession("s1")).isNull()
    }

    @Test
    fun updateSessionTitle() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1")
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.updateSessionTitle("s1", "New Title")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.title).isEqualTo("New Title")
    }

    @Test
    fun updateSessionDraft() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1")
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.updateSessionDraft("s1", "hello")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.draft).isEqualTo("hello")
    }

    @Test
    fun toggleSessionPin() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1", isPinned = false)
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.toggleSessionPin("s1")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.isPinned).isTrue()

        sessionManager.toggleSessionPin("s1")
        advanceUntilIdle()

        assertThat(sessionManager.getSession("s1")!!.isPinned).isFalse()
    }

    @Test
    fun updateSessionModel() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1", options = SessionOptions(toolsEnabled = false))
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.updateSessionModel("s1", "gpt-4o")
        advanceUntilIdle()

        val result = sessionManager.getSession("s1")!!
        assertThat(result.modelId).isEqualTo("gpt-4o")
        assertThat(result.options.toolsEnabled).isFalse()
    }

    @Test
    fun getSessionsByAgent() = testScope.runTest {
        sessionManager.addSession(Session(id = "s1", agentId = "a1"))
        sessionManager.addSession(Session(id = "s2", agentId = "a2"))
        sessionManager.addSession(Session(id = "s3", agentId = "a1", isPinned = true))
        advanceUntilIdle()

        val sessions = sessionManager.getSessionsByAgent("a1")
        assertThat(sessions).hasSize(2)
        assertThat(sessions[0].isPinned).isTrue()
    }

    @Test
    fun toggleMcpServer() = testScope.runTest {
        val session = Session(id = "s1", agentId = "a1", activeMcpServerIds = listOf("m1"))
        sessionManager.addSession(session)
        advanceUntilIdle()

        sessionManager.toggleMcpServer("s1", "m2")
        advanceUntilIdle()
        assertThat(sessionManager.getSession("s1")!!.activeMcpServerIds).containsExactly("m1", "m2")

        sessionManager.toggleMcpServer("s1", "m1")
        advanceUntilIdle()
        assertThat(sessionManager.getSession("s1")!!.activeMcpServerIds).containsExactly("m2")
    }

}
