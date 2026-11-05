package com.promenar.infinity.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemoryManagerDocumentScopeTest {
    private lateinit var database: InfinityDatabase
    private lateinit var vectorStore: VectorStore
    private lateinit var embedding: EmbeddingClient
    private lateinit var manager: MemoryManager

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        seedSession("session-a")
        seedSession("session-b")

        vectorStore = VectorStore(
            database.vectorDao(),
            database.kgNodeDao(),
            database.kgEdgeDao(),
            database.kgEvidenceDao(),
        )
        vectorStore.addVectorRecords(
            listOf(
                VectorStore.NewVectorRecord(
                    docId = "doc-a",
                    content = "document alpha",
                    embedding = floatArrayOf(1f, 0f),
                    metadata = documentVectorMetadata("doc-a", 0, "alpha.txt"),
                ),
                VectorStore.NewVectorRecord(
                    docId = "doc-b",
                    content = "document beta",
                    embedding = floatArrayOf(1f, 0f),
                    metadata = documentVectorMetadata("doc-b", 0, "beta.txt"),
                ),
                VectorStore.NewVectorRecord(
                    sessionId = "session-a",
                    content = "memory alpha",
                    embedding = floatArrayOf(1f, 0f),
                    metadata = "{\"type\":\"memory\"}",
                ),
                VectorStore.NewVectorRecord(
                    sessionId = "session-b",
                    content = "memory beta",
                    embedding = floatArrayOf(1f, 0f),
                    metadata = "{\"type\":\"memory\"}",
                ),
            ),
        )
        embedding = mockk<EmbeddingClient>()
        every { embedding.isConfigured } returns true
        every { embedding.hasLocalFallback } returns false
        coEvery { embedding.embedQuery(any()) } returns (floatArrayOf(1f, 0f) to null)
        manager = createManager(enableHybridSearch = false)
    }

    private fun createManager(enableHybridSearch: Boolean) = MemoryManager(
            vectorStore = vectorStore,
            keywordSearcher = KeywordSearcher(database.vectorDao()),
            graphStore = GraphStore(database),
            embeddingClient = embedding,
            ragConfig = RagConfiguration(
                enableQueryRewrite = false,
                enableHybridSearch = enableHybridSearch,
                enableRerank = false,
                memoryThreshold = 0.1f,
                docThreshold = 0.1f,
                memoryLimit = 10,
                docLimit = 10,
                rerankTopK = 10,
            ),
        )

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `关闭文档检索时绝不返回文档`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = true,
                enableDocs = false,
                activeDocIds = listOf("doc-a"),
                memoryScope = ConversationScope.ALL_AGENTS,
                documentScope = DocumentScope.ALL_INDEXED,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.mapNotNull { it.documentId }).isEmpty()
        assertThat(result.references.map { it.content })
            .containsExactly("memory alpha", "memory beta")
    }

    @Test
    fun `限定文档范围在会话无工作区且无活动文档时返回零文档`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = emptyList(),
                sessionId = "session-a",
            ),
        )

        assertThat(result.references).isEmpty()
    }

    @Test
    fun `非全局仅有未展开文件夹范围时不得退化为全库检索`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = emptyList(),
                activeFolderIds = listOf("folder-a"),
                sessionId = "session-a",
            ),
        )

        assertThat(result.references).isEmpty()
    }

    @Test
    fun `混合检索在非全局空文档范围时也不得通过关键词路径泄露文档`() = runTest {
        val result = createManager(enableHybridSearch = true).retrieveContext(
            query = "memory",
            sessionId = "session-a",
            options = MemoryManager.RetrieveOptions(
                enableMemory = true,
                enableDocs = true,
                activeDocIds = emptyList(),
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.map { it.content }).containsExactly("memory alpha")
        assertThat(result.references.mapNotNull { it.documentId }).isEmpty()
    }

    @Test
    fun `非全局文档检索只能命中显式活动文档`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = listOf("doc-a"),
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.mapNotNull { it.documentId }).containsExactly("doc-a")
        assertThat(result.references.single().source).isEqualTo("文档: alpha.txt")
    }

    @Test
    fun `全局文档检索才允许命中全部文档`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = false,
                enableDocs = true,
                activeDocIds = emptyList(),
                memoryScope = ConversationScope.ALL_AGENTS,
                documentScope = DocumentScope.ALL_INDEXED,
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.mapNotNull { it.documentId })
            .containsExactly("doc-a", "doc-b")
    }

    @Test
    fun `向量与关键词结果保留真实文件标识并用于对话引用`() = runTest {
        database.fileEntryDao().insert(com.promenar.infinity.data.local.db.entity.FileEntry(
            uuid = "file-a", parentUuid = null, name = "alpha.txt", hash = "", physicalRootPath = "",
            materializedPath = "alpha.txt", createdAt = 1, updatedAt = 1,
        ))
        database.openHelper.writableDatabase.execSQL("UPDATE vectors SET file_uuid = 'file-a' WHERE doc_id = 'doc-a'")
        val semantic = vectorStore.searchDocuments(floatArrayOf(1f, 0f), DocumentReach.All, 10, 0.1f)
        assertThat(semantic.first { it.docId == "doc-a" }.fileUuid).isEqualTo("file-a")
        val keywords = manager.searchDocumentKeywords("alpha", DocumentReach.All, 10)
        assertThat(keywords.first { it.docId == "doc-a" }.fileUuid).isEqualTo("file-a")
        val result = retrieve(MemoryManager.RetrieveOptions(
            // 不在会话工作区也不属于资料合集的文件只能显式选择；按旧文档标识选择后引用记录真实文件标识。
            enableMemory = false, enableDocs = true, activeDocIds = listOf("doc-a"), sessionId = "session-a",
        ))
        assertThat(result.references.first { it.content == "document alpha" }.documentId).isEqualTo("file-a")
    }

    @Test
    fun `非全局记忆检索与文档范围互不串扰且不跨会话`() = runTest {
        val result = retrieve(
            MemoryManager.RetrieveOptions(
                enableMemory = true,
                enableDocs = true,
                activeDocIds = listOf("doc-b"),
                sessionId = "session-a",
            ),
        )

        assertThat(result.references.map { it.content })
            .containsExactly("memory alpha", "document beta")
        assertThat(result.references.map { it.content }).doesNotContain("memory beta")
        assertThat(result.references.mapNotNull { it.documentId }).containsExactly("doc-b")
    }

    private suspend fun retrieve(options: MemoryManager.RetrieveOptions) =
        manager.retrieveContext("scope query", "session-a", options)

    private suspend fun seedSession(id: String) {
        val now = System.currentTimeMillis()
        database.sessionDao().insert(
            SessionEntity(
                id = id,
                agentId = "test-agent",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
