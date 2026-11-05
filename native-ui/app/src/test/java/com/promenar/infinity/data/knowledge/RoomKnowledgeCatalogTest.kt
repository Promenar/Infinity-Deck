package com.promenar.infinity.data.knowledge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import com.promenar.infinity.data.agent.AgentRetrievalConfig
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.RetrievalScopeResolver
import com.promenar.infinity.data.rag.conversationRows
import com.promenar.infinity.data.rag.documentRows
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.workspace.LinuxSessionPathKind
import com.promenar.infinity.data.workspace.LinuxSessionPaths
import com.promenar.infinity.data.workspace.SessionPrivateWorkspacePaths
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import io.mockk.coEvery
import io.mockk.mockk
import java.nio.ByteBuffer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class RoomKnowledgeCatalogTest {
    private lateinit var db: InfinityDatabase
    private lateinit var catalog: RoomKnowledgeCatalog
    private val mounts = mutableListOf<ExternalMount>()
    private val session = Session("a1", "A", workspaceRootUuid = "root-a1")

    @Before
    fun setup() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        val access = mockk<ExternalWorkspaceAccess>()
        coEvery { access.visible(any()) } answers { mounts.toList() }
        catalog = RoomKnowledgeCatalog(db, access)
        db.agentDao().insert(AgentEntity("A", "A", createdAt = 1))
        db.agentDao().insert(AgentEntity("P", "P", createdAt = 1,
            retrievalConfig = AgentRetrievalConfig(memoryPolicy = AgentMemoryPolicy(shareWithOtherAgents = false))))
        for ((id, agent) in listOf("a1" to "A", "a2" to "A", "b1" to "B", "p1" to "P")) {
            db.sessionDao().insert(SessionEntity(id, agent, title = "禁止列出的会话标题-$id", workspaceRootUuid = "root-$id", createdAt = 1, updatedAt = 1))
            db.vectorDao().insert(vector("memory-$id", sessionId = id))
        }
        db.sessionDao().insert(SessionEntity("__infinity_rag_workspace__", "__system__", title = "默认知识库", workspaceRootUuid = "root-kb", createdAt = 1, updatedAt = 1))
        for ((id, root) in listOf("f-a1" to "root-a1", "f-kb" to "root-kb", "f-b1" to "root-b1")) {
            db.fileEntryDao().insert(file(id, root))
            db.vectorDao().insert(vector("v-$id", docId = id))
        }
        db.vectorDao().insert(vector("v-legacy", docId = "legacy-doc"))
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `目录文档与解析器三档范围一致且知识库统计只算有效索引`() = runBlocking<Unit> {
        val resolver = RetrievalScopeResolver(db)
        for (scope in DocumentScope.entries) {
            val options = RagOptions(enableMemory = false, documentScope = scope)
            val snapshot = catalog.snapshot(session, options)
            val expected = db.vectorDao().documentRows(resolver.resolve("a1", options).documents).map { it.docId }.toSet()
            assertThat(snapshot.documents.map { it.documentId }).containsExactlyElementsIn(expected)
            assertThat(catalog.hasIndexedMaterial(session, options)).isEqualTo(snapshot.hasIndexedMaterial)
            assertThat(renderKnowledgeCatalog(snapshot)).doesNotContain("禁止列出的会话标题")
        }
        assertThat(catalog.knowledgeBases(session, RagOptions(documentScope = DocumentScope.WORKSPACE))).isEmpty()
        val root = catalog.knowledgeBases(session, RagOptions()).single()
        assertThat(root.rootUuid).isEqualTo("root-kb")
        assertThat(root.documentCount).isEqualTo(1)
        assertThat(root.isDefault).isTrue()
        db.fileEntryDao().insert(file("never-indexed", "root-kb").copy(vectorizedAt = 50))
        db.fileEntryDao().insert(file("stale", "root-kb"))
        db.vectorDao().insert(vector("v-stale", docId = "stale").copy(stale = true))
        db.fileEntryDao().insert(file("recycled", "root-kb").copy(inRecycleBin = true))
        db.vectorDao().insert(vector("v-recycled", docId = "recycled"))
        assertThat(catalog.knowledgeBases(session, RagOptions()).single().documentCount).isEqualTo(1)
    }

    @Test
    fun `未索引文件和失效向量不会提供目录或广告`() = runBlocking<Unit> {
        db.vectorDao().deleteAll()
        val options = RagOptions(enableMemory = false, documentScope = DocumentScope.ALL_INDEXED)
        assertThat(catalog.hasIndexedMaterial(session, options)).isFalse()
        assertThat(renderKnowledgeCatalog(catalog.snapshot(session, options))).isEmpty()
        db.vectorDao().insert(vector("stale", docId = "f-a1").copy(stale = true))
        assertThat(catalog.hasIndexedMaterial(session, options)).isFalse()
        db.vectorDao().insert(vector("missing", docId = "missing").copy(fileUuid = "missing-file"))
        assertThat(catalog.hasIndexedMaterial(session, options)).isFalse()
    }

    @Test
    fun `同步广告保留显式文档与文件夹展开的范围语义`() = runBlocking<Unit> {
        db.vectorDao().deleteByDocId("f-a1")
        db.fileEntryDao().insert(file("folder", "root-b1").copy(isDirectory = true, materializedPath = "/folder"))
        db.fileEntryDao().insert(file("nested", "root-b1").copy(parentUuid = "folder", materializedPath = "/folder/nested.md"))
        db.vectorDao().insert(vector("v-nested", docId = "nested"))
        val options = RagOptions(enableMemory = false, documentScope = DocumentScope.WORKSPACE, selectedCollectionIds = emptyList())
        assertThat(catalog.hasIndexedMaterial(session, options)).isFalse()
        for (selected in listOf(options.copy(activeDocIds = listOf("legacy-doc")), options.copy(activeFolderIds = listOf("folder")))) {
            assertThat(catalog.hasIndexedMaterial(session, selected)).isTrue()
            assertThat(catalog.snapshot(session, selected).hasIndexedMaterial).isTrue()
        }
    }

    @Test
    fun `历史统计与解析器一致且不泄露会话标题`() = runBlocking<Unit> {
        val resolver = RetrievalScopeResolver(db)
        for (scope in ConversationScope.entries) {
            val options = RagOptions(enableDocs = false, globalMemoryEnabled = scope != ConversationScope.SESSION, globalMemoryScope = scope)
            val snapshot = catalog.snapshot(session, options)
            val reach = resolver.resolve("a1", options).memory
            val expectedSessions = db.vectorDao().conversationRows(reach, "memory").map { it.sessionId }.toSet()
            assertThat(snapshot.history?.indexedSessionCount).isEqualTo(expectedSessions.size)
            assertThat(snapshot.history?.sessionCount).isEqualTo(resolver.stats(reach).sessionCount)
            assertThat(catalog.hasIndexedMaterial(session, options)).isEqualTo(snapshot.hasIndexedMaterial)
            assertThat(renderKnowledgeCatalog(snapshot)).doesNotContain("禁止列出的会话标题")
        }
        db.vectorDao().deleteBySessionId("a1")
        db.vectorDao().deleteBySessionId("a2")
        db.vectorDao().deleteBySessionId("b1")
        val options = RagOptions(enableDocs = false, globalMemoryEnabled = true, globalMemoryScope = ConversationScope.ALL_AGENTS)
        assertThat(catalog.hasIndexedMaterial(session, options)).isTrue()
        assertThat(catalog.snapshot(session, options).hasIndexedMaterial).isTrue()
        val privateSession = Session("p1", "P")
        assertThat(catalog.hasIndexedMaterial(privateSession, options)).isTrue()
    }

    @Test
    fun `已删除消息所属历史向量不算可检索材料`() = runBlocking<Unit> {
        db.vectorDao().deleteAll()
        db.vectorDao().insert(vector("orphan", sessionId = "a1").copy(startMessageId = "deleted-message"))
        val options = RagOptions(enableDocs = false)
        assertThat(catalog.hasIndexedMaterial(session, options)).isFalse()
        assertThat(renderKnowledgeCatalog(catalog.snapshot(session, options))).isEmpty()
    }

    @Test
    fun `读取路径区分本工作区已挂载知识库未挂载知识库和其它工作区`() = runBlocking<Unit> {
        val current = catalog.documentLocation("a1", RagReference(documentId = "f-a1"))!!
        assertThat(current.kind).isEqualTo(KnowledgeDocumentLocationKind.WORKSPACE)
        assertThat(current.path).isEqualTo("/f-a1.md")
        val unmounted = catalog.documentLocation("a1", RagReference(documentId = "f-kb"))!!
        assertThat(unmounted.kind).isEqualTo(KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED)
        assertThat(unmounted.path).isNull()
        assertThat(unmounted.knowledgeBaseTitle).isEqualTo("默认知识库")
        mounts += ExternalMount("kb-mount", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "root-kb", "a1", 1, readOnly = true)
        val mounted = catalog.documentLocation("a1", RagReference(documentId = "f-kb"))!!
        assertThat(mounted.kind).isEqualTo(KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED)
        assertThat(mounted.path).isEqualTo("/mnt/knowledge/f-kb.md")
        val other = catalog.documentLocation("a1", RagReference(documentId = "f-b1"))!!
        assertThat(other.kind).isEqualTo(KnowledgeDocumentLocationKind.SNIPPET_ONLY)
        assertThat(other.path).isNull()
        assertThat(catalog.documentLocation("a1", RagReference(kind = "memory"))).isNull()
    }

    @Test
    fun `documentId经向量fileUuid映射到当前文件路径`() = runBlocking<Unit> {
        db.vectorDao().insert(vector("mapped", docId = "old-doc-id").copy(fileUuid = "f-kb"))
        mounts += ExternalMount("kb-mount", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "root-kb", "a1", 1, readOnly = true)
        assertThat(catalog.documentLocation("a1", RagReference(documentId = "old-doc-id"))?.path).isEqualTo("/mnt/knowledge/f-kb.md")
    }

    @Test fun `引用定位不把规范文件ID误当成其它向量的旧文档ID`() = runBlocking<Unit> {
        db.vectorDao().insert(vector("collision", docId = "f-a1").copy(fileUuid = "f-b1"))
        val location = catalog.documentLocation("a1", RagReference(id = "v-f-a1", documentId = "f-a1"))!!
        assertThat(location.kind).isEqualTo(KnowledgeDocumentLocationKind.WORKSPACE)
        assertThat(location.rootUuid).isEqualTo("root-a1")
        assertThat(location.path).isEqualTo("/f-a1.md")
    }

    @Test
    fun `私有文档路径跟随内置自选目录和Linux会话命名空间且不改变其它来源`() = runBlocking<Unit> {
        val stored = requireNotNull(db.sessionDao().getById("a1"))
        db.workspaceDao().insert(WorkspaceEntity("selected", WorkspaceEntity.KIND_PROJECT, "项目目录", "project", 1))
        mounts += ExternalMount("kb-mount", "knowledge", ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, "root-kb", "a1", 1, readOnly = true)
        for ((mode, workspaceId, expectedPath) in listOf(
            Triple(SessionRunMode.BUILTIN, null, "/f-a1.md"),
            Triple(SessionRunMode.BUILTIN, "selected", "/session/f-a1.md"),
            Triple(SessionRunMode.LINUX, null, "/home/session/f-a1.md"),
            Triple(SessionRunMode.LINUX, "selected", "/home/session/f-a1.md"),
        )) {
            db.sessionDao().insert(stored.copy(runMode = mode.name, workspaceId = workspaceId))
            val current = requireNotNull(catalog.documentLocation("a1", RagReference(documentId = "f-a1")))
            assertThat(current.kind).isEqualTo(KnowledgeDocumentLocationKind.WORKSPACE)
            assertThat(current.path).isEqualTo(expectedPath)
            when {
                mode == SessionRunMode.LINUX -> {
                    val route = LinuxSessionPaths.route(requireNotNull(current.path), workspaceId == null)
                    assertThat(route.kind).isEqualTo(LinuxSessionPathKind.PRIVATE)
                    assertThat(route.path).isEqualTo("/f-a1.md")
                }
                workspaceId != null -> assertThat(SessionPrivateWorkspacePaths.relative(requireNotNull(current.path))).isEqualTo("/f-a1.md")
            }
            val knowledgeBase = requireNotNull(catalog.documentLocation("a1", RagReference(documentId = "f-kb")))
            assertThat(knowledgeBase.kind).isEqualTo(KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED)
            assertThat(knowledgeBase.path).isEqualTo("/mnt/knowledge/f-kb.md")
            val other = requireNotNull(catalog.documentLocation("a1", RagReference(documentId = "f-b1")))
            assertThat(other.kind).isEqualTo(KnowledgeDocumentLocationKind.SNIPPET_ONLY)
            assertThat(other.path).isNull()
        }
    }

    private fun file(id: String, root: String) = FileEntry(
        uuid = id, workspaceRootUuid = root, parentUuid = root, name = "$id.md", hash = "h",
        physicalRootPath = "/tmp/knowledge-catalog-fixture", materializedPath = "/$id.md", createdAt = 1, updatedAt = 1,
    )

    private fun vector(id: String, sessionId: String? = null, docId: String? = null) = VectorEntity(
        id = id, sessionId = sessionId, docId = docId, content = "needle",
        embedding = ByteBuffer.allocate(8).putFloat(1f).putFloat(0f).array(),
        metadata = if (docId == null) "{\"type\":\"memory\"}" else "{\"type\":\"document\",\"docTitle\":\"$docId\"}",
        createdAt = 1, updatedAt = 1,
    )
}
