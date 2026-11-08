package com.promenar.infinity.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.generation.DefaultChatGenerationContentStrategy
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.repository.MessageRepository
import com.promenar.infinity.data.repository.TestWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.ContextBuilder
import com.promenar.infinity.ui.chat.manager.ContextBuilderParams
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RagImportRetrievalPipelineTest {
    private lateinit var database: InfinityDatabase
    private lateinit var workspaceParent: File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        workspaceParent = Files.createTempDirectory(
            java.nio.file.Path.of(System.getProperty("user.dir")),
            ".infinity-rag-pipeline",
        ).toFile()
    }

    @After
    fun tearDown() {
        database.close()
        workspaceParent.deleteRecursively()
    }

    @Test
    fun `工作区文件经真实索引检索进入提示词并随消息引用往返`() = runTest {
        verifyImportRetrieval(graphFails = false)
    }

    @Test
    fun `图谱抽取失败后已提交向量仍可检索进入提示词`() = runTest {
        verifyImportRetrieval(graphFails = true)
    }

    private suspend fun verifyImportRetrieval(graphFails: Boolean) {
        val sessionId = "rag-pipeline-session"
        val fileId = "rag-pipeline-file"
        val fileName = "infinity-release-fact.txt"
        val uniqueFact = "INFINITY_RAG_PIPELINE_SENTINEL_7319"
        val query = "Infinity Deck release sentinel"
        seedSession(sessionId)
        val workspace = WorkspaceRepository(
            database.fileEntryDao(),
            database.workspaceSeqDao(),
            workspaceParent,
            TestWorkspaceFileOps(),
        )
        val root = workspace.ensureSessionRoot(sessionId)
        val created = workspace.createFileInWorkspaceStreaming(
            workspaceRootUuid = root.uuid,
            uuid = fileId,
            name = fileName,
            mimeType = "text/plain",
            parentUuid = root.uuid,
            materializedPath = "/$fileName",
            maxBytes = 1024,
            writer = { it.write(uniqueFact.toByteArray()) },
        )
        val physical = File(created.physicalRootPath, created.materializedPath.trimStart('/'))
        assertThat(physical.readText()).isEqualTo(uniqueFact)
        assertThat(created.hash).isEqualTo(Sha256Utils.hash(uniqueFact))
        assertThat(workspace.getByUuid(root.uuid, fileId)).isEqualTo(created)

        val embedding = mockk<EmbeddingClient>()
        coEvery { embedding.embedDocuments(any()) } answers {
            EmbeddingResult(firstArg<List<String>>().map { floatArrayOf(1f, 0f) })
        }
        coEvery { embedding.embedQuery(query) } returns (floatArrayOf(1f, 0f) to null)
        every { embedding.isConfigured } returns true
        every { embedding.hasLocalFallback } returns false
        every { embedding.frozenForIndex() } returns embedding
        every { embedding.vectorSpaceKey() } returns "test-space"
        every { embedding.localModelGeneration() } returns null
        val config = RagConfiguration(
            enableMemory = false,
            enableDocs = true,
            enableHybridSearch = false,
            enableRerank = false,
            docThreshold = 0.1f,
            docChunkSize = 256,
            chunkOverlap = 16,
            enableKnowledgeGraph = graphFails,
        )
        val indexService = RoomDocumentIndexService(
            database = database,
            candidateBuilder = WorkspaceDocumentIndexCandidateBuilder(
                database.fileEntryDao(),
                embedding,
                config,
                checkpointRoot = kotlin.io.path.createTempDirectory("embedding-checkpoints").toFile(),
                graphCandidateBuilder = if (graphFails) KnowledgeGraphCandidateBuilder { _, _, _ ->
                    ExtractionResult.failure(KgExtractionStatus.PARSE_FAILURE, "fixture")
                } else null,
            ),
        )
        database.vectorizationTaskDao().insert(VectorizationTaskEntity(
            id = "pipeline-index-task",
            type = VectorizationQueue.TYPE_DOCUMENT_REFERENCE,
            status = "processing",
            docId = fileId,
            workspaceRootUuid = root.uuid,
            sourceMimeType = "text/plain",
            targetContentHash = created.hash,
            targetEpoch = created.updatedAt,
            createdAt = created.updatedAt,
            updatedAt = created.updatedAt,
        ))

        val indexed = indexService.rebuild(FileIndexEvent.Changed(
            root.uuid,
            fileId,
            created.hash,
            created.updatedAt,
            activeTaskId = "pipeline-index-task",
        ))

        assertThat(indexed).isEqualTo(DocumentIndexResult.Rebuilt(fileId,
            kgFailure = KgExtractionStatus.PARSE_FAILURE.takeIf { graphFails }))
        if (graphFails) {
            val task = database.vectorizationTaskDao().getById("pipeline-index-task")!!
            assertThat(task.status).isEqualTo("partial")
            assertThat(task.error).startsWith(VectorizationQueue.KG_FAILED)
        }
        assertThat(database.fileEntryDao().getByUuid(root.uuid, fileId)!!.vectorizedAt).isNotNull()
        assertThat(database.vectorDao().getByDocId(fileId)).isNotEmpty()

        val memoryManager = MemoryManager(
            vectorStore = VectorStore(
                database.vectorDao(),
                database.kgNodeDao(),
                database.kgEdgeDao(),
                database.kgEvidenceDao(),
            ),
            keywordSearcher = KeywordSearcher(database.vectorDao()),
            graphStore = GraphStore(database),
            embeddingClient = embedding,
            ragConfig = config,
        )
        val ragOptions = RagOptions(
            enableMemory = false,
            enableDocs = true,
            documentScope = com.promenar.infinity.data.model.DocumentScope.ALL_INDEXED,
            enableRerank = false,
            enableHybridSearch = false,
            docThreshold = 0.1f,
        )
        val context = ContextBuilder(ragProvider = MemoryManagerRagAdapter(memoryManager)).buildContext(
            ContextBuilderParams(
                sessionId = sessionId,
                content = query,
                assistantMsgId = "assistant-rag",
                session = Session(
                    id = sessionId,
                    agentId = "test-agent",
                    ragOptions = ragOptions,
                ),
                ragOptions = ragOptions,
            ),
        )

        assertThat(context.ragReferences).hasSize(1)
        val reference = context.ragReferences.single()
        assertThat(reference.documentId).isEqualTo(fileId)
        assertThat(reference.source).isEqualTo("文档: $fileName")
        assertThat(reference.content).contains(uniqueFact)
        val messages = MessageRepository(database.messageDao())
        messages.insert(
            Message("user-rag", MessageRole.USER, query, turnContext = context.turnContext),
            sessionId,
        )
        messages.insert(
            Message(
                id = "assistant-rag",
                role = MessageRole.ASSISTANT,
                content = "fixture answer",
                ragReferences = context.ragReferences,
            ),
            sessionId,
        )

        val restoredUser = messages.getById("user-rag")!!
        val restoredAnswer = messages.getById("assistant-rag")!!
        assertThat(restoredAnswer.ragReferences).containsExactly(reference)
        val application = ApplicationProvider.getApplicationContext<Context>()
        val protocol = DefaultChatGenerationContentStrategy(
            application.getSharedPreferences("rag-pipeline", Context.MODE_PRIVATE), null,
        ).buildProtocolMessages(
            Session(sessionId, "test-agent", messages = listOf(restoredUser, restoredAnswer)),
            context.finalSystemPrompt,
            restoredUser.id,
        )
        assertThat(protocol.first { it.role == "system" }.content).doesNotContain(uniqueFact)
        val sentUser = protocol.single { it.role == "user" }
        assertThat(sentUser.content).isEqualTo(query)
        assertThat(sentUser.turnContext).isEqualTo(com.promenar.infinity.ui.chat.manager.turnContextForModel(context.turnContext))
        val sentContext = requireNotNull(sentUser.turnContext)
        assertThat(sentContext).contains(uniqueFact)
        assertThat(sentContext.windowed(uniqueFact.length).count { it == uniqueFact }).isEqualTo(1)
    }

    private suspend fun seedSession(sessionId: String) {
        val now = System.currentTimeMillis()
        database.sessionDao().insert(
            SessionEntity(
                id = sessionId,
                agentId = "test-agent",
                createdAt = now,
                updatedAt = now,
            ),
        )
    }
}
