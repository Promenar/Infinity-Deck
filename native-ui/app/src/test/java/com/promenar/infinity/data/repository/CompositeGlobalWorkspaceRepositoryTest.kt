package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import java.io.File
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CompositeGlobalWorkspaceRepositoryTest {

    private val baseRepo: IWorkspaceRepository = mockk(relaxed = true)
    private val sessionDao: SessionDao = mockk(relaxed = true)
    private val fileEntryDao: FileEntryDao = mockk(relaxed = true)

    private val ragRootUuid = "rag-root-uuid"
    private val session1 = SessionEntity(
        id = "session-1",
        agentId = "agent-1",
        title = "旅行规划",
        workspaceRootUuid = "session-1-root-uuid",
        workspacePath = "/data/user/0/app/files/session_workspaces/hash1",
        createdAt = 1000L,
        updatedAt = 2000L,
    )
    private val session2 = SessionEntity(
        id = "session-2",
        agentId = "agent-2",
        title = "代码重构",
        workspaceRootUuid = "session-2-root-uuid",
        workspacePath = "/data/user/0/app/files/session_workspaces/hash2",
        createdAt = 1100L,
        updatedAt = 2100L,
    )
    private val ragSession = SessionEntity(
        id = CompositeWorkspaceConstants.GLOBAL_KNOWLEDGE_SESSION_ID,
        agentId = "system",
        title = "全局知识库",
        workspaceRootUuid = ragRootUuid,
        createdAt = 100L,
        updatedAt = 200L,
    )

    private lateinit var compositeRepo: CompositeGlobalWorkspaceRepository

    @BeforeEach
    fun setUp() {
        compositeRepo = CompositeGlobalWorkspaceRepository(
            baseRepo = baseRepo,
            sessionDao = sessionDao,
            fileEntryDao = fileEntryDao,
        )
    }

    @Test
    fun `知识库根目录包含会话时首项挂载虚拟会话工作区目录`() = runTest {
        val baseDoc = FileEntry(
            uuid = "doc-1",
            workspaceRootUuid = ragRootUuid,
            parentUuid = ragRootUuid,
            name = "知识库文档.md",
            hash = "h1",
            physicalRootPath = "/data/rag",
            materializedPath = "/知识库文档.md",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        every { baseRepo.observeChildren(ragRootUuid, ragRootUuid) } returns flowOf(listOf(baseDoc))
        every { sessionDao.observeAll() } returns flowOf(listOf(ragSession, session1, session2))

        val children = compositeRepo.observeChildren(ragRootUuid, ragRootUuid).first()

        assertThat(children).hasSize(2)
        val firstNode = children.first()
        assertThat(firstNode.uuid).isEqualTo(CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID)
        assertThat(firstNode.name).isEqualTo("会话工作区")
        assertThat(firstNode.isDirectory).isTrue()
        assertThat(children[1].uuid).isEqualTo("doc-1")
    }

    @Test
    fun `展开会话工作区时列出除知识库以外的所有会话`() = runTest {
        every { sessionDao.observeAll() } returns flowOf(listOf(ragSession, session1, session2))

        val children = compositeRepo.observeChildren(
            ragRootUuid,
            CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
        ).first()

        assertThat(children).hasSize(2)
        assertThat(children.map { it.name }).containsExactly("旅行规划", "代码重构")
        assertThat(children.map { it.uuid }).containsExactly(
            CompositeWorkspaceConstants.virtualSessionFolderUuid("session-1"),
            CompositeWorkspaceConstants.virtualSessionFolderUuid("session-2"),
        )
        assertThat(children.all { it.isDirectory }).isTrue()
    }

    @Test
    fun `虚拟根目录与无标题会话使用传入的当前语言显示名`() = runTest {
        val untitled = session1.copy(title = "  ")
        every { baseRepo.observeChildren(ragRootUuid, ragRootUuid) } returns flowOf(emptyList())
        every { sessionDao.observeAll() } returns flowOf(listOf(ragSession, untitled))
        val englishRepo = CompositeGlobalWorkspaceRepository(
            baseRepo = baseRepo,
            sessionDao = sessionDao,
            fileEntryDao = fileEntryDao,
            sessionWorkspacesRootName = "Session Workspaces",
            untitledSessionName = "Untitled session",
        )

        val root = englishRepo.observeChildren(ragRootUuid, ragRootUuid).first().single()
        val sessionFolder = englishRepo.observeChildren(
            ragRootUuid,
            CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
        ).first().single()

        assertThat(root.name).isEqualTo("Session Workspaces")
        assertThat(root.materializedPath).isEqualTo("/Session Workspaces")
        assertThat(sessionFolder.name).isEqualTo("Untitled session session-")
        assertThat(sessionFolder.materializedPath).isEqualTo("/Session Workspaces/Untitled session session-")
    }

    @Test
    fun `展开具体会话目录时代理至该会话真实工作区根`() = runTest {
        coEvery { sessionDao.getById("session-1") } returns session1
        val sessionFile = FileEntry(
            uuid = "session-file-1",
            workspaceRootUuid = "session-1-root-uuid",
            parentUuid = "session-1-root-uuid",
            name = "行程表.xlsx",
            hash = "h_session",
            physicalRootPath = "/data/session1",
            materializedPath = "/行程表.xlsx",
            createdAt = 1000L,
            updatedAt = 1000L,
        )
        every { baseRepo.observeChildren("session-1-root-uuid", "session-1-root-uuid") } returns flowOf(listOf(sessionFile))

        val sessionVirtualUuid = CompositeWorkspaceConstants.virtualSessionFolderUuid("session-1")
        val children = compositeRepo.observeChildren(ragRootUuid, sessionVirtualUuid).first()

        assertThat(children).hasSize(1)
        assertThat(children.first().name).isEqualTo("行程表.xlsx")
        assertThat(children.first().uuid).isEqualTo("session-file-1")
    }

    @Test
    fun `getByUuid精确解析虚拟根与虚拟会话目录`() = runTest {
        coEvery { sessionDao.getById("session-2") } returns session2

        val rootNode = compositeRepo.getByUuid(
            ragRootUuid,
            CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
        )
        assertThat(rootNode).isNotNull()
        assertThat(rootNode!!.name).isEqualTo("会话工作区")
        assertThat(rootNode.isDirectory).isTrue()

        val sessionNode = compositeRepo.getByUuid(
            ragRootUuid,
            CompositeWorkspaceConstants.virtualSessionFolderUuid("session-2"),
        )
        assertThat(sessionNode).isNotNull()
        assertThat(sessionNode!!.name).isEqualTo("代码重构")
        assertThat(sessionNode.lastWriteSessionId).isEqualTo("session-2")
    }

    @Test
    fun `系统虚拟目录受写保护禁止删除`() = runTest {
        assertThrows<SecurityException> {
            compositeRepo.permanentDelete(
                ragRootUuid,
                CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
            )
        }

        assertThrows<SecurityException> {
            compositeRepo.permanentDelete(
                ragRootUuid,
                CompositeWorkspaceConstants.virtualSessionFolderUuid("session-1"),
            )
        }
    }

    @Test
    fun `系统虚拟根禁止重命名但会话目录重命名联动更新会话标题`() = runTest {
        val rootRenameResult = compositeRepo.rename(
            ragRootUuid,
            CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
            "新名称",
        )
        assertThat(rootRenameResult).isInstanceOf(RenameResult.Conflict::class.java)

        val sessionVirtualUuid = CompositeWorkspaceConstants.virtualSessionFolderUuid("session-1")
        val sessionRenameResult = compositeRepo.rename(
            ragRootUuid,
            sessionVirtualUuid,
            "东京旅行规划",
        )
        assertThat(sessionRenameResult).isInstanceOf(RenameResult.Success::class.java)
        val success = sessionRenameResult as RenameResult.Success
        assertThat(success.name).isEqualTo("东京旅行规划")
        coVerify { sessionDao.updateTitle("session-1", "东京旅行规划", any()) }
    }

    @Test
    fun `转存会话文件到知识库能正确解析物理目录与相对路径并触发回调`() = runTest {
        val tempDir = java.nio.file.Files.createTempDirectory("session_ws").toFile()
        try {
            val sessionFile = File(tempDir, "doc.txt")
            sessionFile.writeText("测试会话文件内容")

            val sourceUuid = "file-source-1"
            val sourceEntry = FileEntry(
                uuid = sourceUuid,
                workspaceRootUuid = "session-1-root",
                parentUuid = "session-1-root",
                name = "doc.txt",
                hash = "hash-source-1",
                physicalRootPath = tempDir.absolutePath, // 指向根目录，与生产环境一致
                materializedPath = "/doc.txt",
                sizeBytes = sessionFile.length(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            coEvery { fileEntryDao.findActiveByUuid(sourceUuid) } returns sourceEntry

            val targetCreatedEntry = FileEntry(
                uuid = "rag-new-file-uuid",
                workspaceRootUuid = ragRootUuid,
                parentUuid = ragRootUuid,
                name = "doc.txt",
                hash = "new-hash",
                physicalRootPath = "/data/rag",
                materializedPath = "/doc.txt",
                sizeBytes = sessionFile.length(),
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )

            coEvery {
                baseRepo.createFileInWorkspaceStreaming(
                    workspaceRootUuid = ragRootUuid,
                    uuid = any(),
                    name = "doc.txt",
                    mimeType = any(),
                    parentUuid = ragRootUuid,
                    materializedPath = "/doc.txt",
                    maxBytes = any(),
                    writer = any(),
                )
            } answers {
                @Suppress("UNCHECKED_CAST")
                val writer = args[7] as (java.io.OutputStream) -> Unit
                val baos = java.io.ByteArrayOutputStream()
                writer.invoke(baos)
                assertThat(baos.toString(Charsets.UTF_8.name())).isEqualTo("测试会话文件内容")
                targetCreatedEntry
            }

            var callbackTriggered = false
            val repoWithCallback = CompositeGlobalWorkspaceRepository(
                baseRepo = baseRepo,
                sessionDao = sessionDao,
                fileEntryDao = fileEntryDao,
                onTransferToKnowledgeBaseCommitted = { transferred ->
                    callbackTriggered = true
                    assertThat(transferred.uuid).isEqualTo("rag-new-file-uuid")
                }
            )

            val result = repoWithCallback.transferFileToKnowledgeBase(
                sourceFileUuid = sourceUuid,
                targetKnowledgeBaseRootUuid = ragRootUuid,
            )

            assertThat(result.uuid).isEqualTo("rag-new-file-uuid")
            assertThat(callbackTriggered).isTrue()
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
