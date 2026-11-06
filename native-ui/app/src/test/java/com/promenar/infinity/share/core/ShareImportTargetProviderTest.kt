package com.promenar.infinity.share.core

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ShareImportTargetProviderTest {
    @Test
    fun `当前聊天和全局知识库使用两个显式工作区目标`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { sessionDao.getById(CURRENT) } returns session(CURRENT, "当前聊天")
        coEvery { sessionDao.getById(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID) } returns
            session(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID, "知识库")
        coEvery { workspace.ensureSessionRoot(CURRENT) } returns root("current-root")
        coEvery { workspace.ensureSessionRoot(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID) } returns
            root("knowledge-root")

        val targets = ShareImportTargetProvider(sessionDao, workspace) { root("knowledge-root") }.load(
            currentSessionId = CURRENT,
            currentSessionTitle = "当前聊天",
            knowledgeBaseLabel = "知识库",
        )

        assertThat(targets.map { it.workspaceRootUuid }).containsExactly("current-root", "knowledge-root").inOrder()
        assertThat(targets.map { it.kind }).containsExactly(
            ShareTargetKind.CurrentSession,
            ShareTargetKind.KnowledgeBase,
        ).inOrder()
    }

    @Test
    fun `知识库系统会话缺失时创建后再认领工作区`() = runTest {
        val sessionDao = mockk<SessionDao>()
        val workspace = mockk<IWorkspaceRepository>()
        var provisionCalls = 0

        val targets = ShareImportTargetProvider(sessionDao, workspace) {
            provisionCalls += 1
            root("knowledge-root")
        }.load(null, null, "知识库")

        assertThat(targets).hasSize(1)
        assertThat(targets.single().kind).isEqualTo(ShareTargetKind.KnowledgeBase)
        assertThat(provisionCalls).isEqualTo(1)
    }

    @Test
    fun `会话列表排除当前与存储空间按更新时间倒序且不默认选择`() = runTest {
        val dao = mockk<SessionDao>()
        val workspace = mockk<IWorkspaceRepository>()
        coEvery { dao.getAll() } returns listOf(session("old", "旧").copy(updatedAt = 1),
            session("current", "当前").copy(updatedAt = 9), session("new", "新").copy(updatedAt = 8),
            session(ShareImportTargetProvider.GLOBAL_KNOWLEDGE_SESSION_ID, "存储空间"),
            session("missing", "不可用").copy(worktreeDirectory = "/missing/worktree"))
        val provider = ShareImportTargetProvider(dao, workspace) { root("knowledge") }
        assertThat(provider.sessions("current").map { it.sessionId }).containsExactly("new", "old").inOrder()
        coVerify(exactly = 0) { workspace.ensureSessionRoot(any()) }
        coEvery { dao.getById("deleted") } returns null
        assertThat(provider.sessionDestination("deleted", false)).isNull()
    }

    @Test
    fun `存储空间只列直接可见子文件夹并按名称排序`() = runTest {
        val dao = mockk<SessionDao>()
        val workspace = mockk<IWorkspaceRepository>()
        val root = root("knowledge")
        val children = listOf(
            root.copy(uuid = "b", parentUuid = root.uuid, name = "B", materializedPath = "/B"),
            root.copy(uuid = "a", parentUuid = root.uuid, name = "A", materializedPath = "/A"),
            root.copy(uuid = "file", parentUuid = root.uuid, name = "file", isDirectory = false, materializedPath = "/file"),
            root.copy(uuid = "recycled", parentUuid = root.uuid, name = "trash", inRecycleBin = true, materializedPath = "/trash"),
            root.copy(uuid = "internal", parentUuid = root.uuid, name = ".infinity", materializedPath = "/.infinity"),
            root.copy(uuid = "nested", parentUuid = "a", name = "nested", materializedPath = "/A/nested"),
        )
        io.mockk.every { workspace.observeChildren(root.uuid, root.uuid) } returns kotlinx.coroutines.flow.flowOf(children)
        val provider = ShareImportTargetProvider(dao, workspace) { root }
        assertThat(provider.knowledgeFolders(root.uuid).map { it.name }).containsExactly("A", "B").inOrder()
        coEvery { dao.getById(CURRENT) } returns session(CURRENT, "当前")
        coEvery { workspace.ensureSessionRoot(CURRENT) } returns root("session-root")
        assertThat(provider.sessionDestination(CURRENT, false)?.workspaceRootUuid).isEqualTo("session-root")
    }

    @Test
    fun `选项映射Linux可用性且当前会话必须真实存在`() = runTest {
        val dao = mockk<SessionDao>()
        val workspace = mockk<IWorkspaceRepository>()
        val runtime = mockk<com.promenar.infinity.domain.linux.LinuxRuntime>()
        val state = kotlinx.coroutines.flow.MutableStateFlow<com.promenar.infinity.domain.linux.LinuxRuntimeState>(
            com.promenar.infinity.domain.linux.LinuxRuntimeState.NotInstalled(0))
        io.mockk.every { runtime.state } returns state
        coEvery { dao.getById(CURRENT) } returns session(CURRENT, "数据库标题")
        val provider = ShareImportTargetProvider(dao, workspace, { root("knowledge") }, runtime)
        val first = provider.options(CURRENT, "当前标题")
        assertThat(first.currentSession?.title).isEqualTo("当前标题")
        assertThat(first.linux).isEqualTo(ShareLinuxAvailability.NeedsInstall)
        state.value = com.promenar.infinity.domain.linux.LinuxRuntimeState.Unavailable(com.promenar.infinity.domain.linux.LinuxUnavailableReason.DISTRIBUTION)
        assertThat(provider.options(null, null).linux).isEqualTo(ShareLinuxAvailability.Unsupported)
        state.value = com.promenar.infinity.domain.linux.LinuxRuntimeState.Ready(mockk(relaxed = true))
        assertThat(provider.options(null, null).linux).isEqualTo(ShareLinuxAvailability.Ready)
        assertThat(provider.options(null, null).currentSession).isNull()
    }

    private fun session(id: String, title: String) = SessionEntity(
        id = id,
        agentId = "agent",
        title = title,
        createdAt = 1,
        updatedAt = 1,
    )

    private fun root(uuid: String) = FileEntry(
        uuid = uuid,
        workspaceRootUuid = uuid,
        parentUuid = null,
        name = "root",
        hash = "hash",
        isDirectory = true,
        physicalRootPath = "/workspace/$uuid",
        materializedPath = "/",
        createdAt = 1,
        updatedAt = 1,
    )

    private companion object {
        const val CURRENT = "current-session"
    }
}
