package com.promenar.infinity.data.rag

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.RagReferenceKind
import com.promenar.infinity.data.model.RagRetrievalTrace
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

/** 检索轨迹与逐条名次：命中、重排生效、重排失败、未命中与嵌入不可用。 */
@RunWith(RobolectricTestRunner::class)
class MemoryManagerRetrievalTraceTest {
    private lateinit var database: InfinityDatabase
    private lateinit var vectorStore: VectorStore
    private lateinit var embedding: EmbeddingClient

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        database.sessionDao().insert(SessionEntity(id = "s1", agentId = "agent", createdAt = 1L, updatedAt = 1L))
        vectorStore = VectorStore(database.vectorDao(), database.kgNodeDao(), database.kgEdgeDao(), database.kgEvidenceDao())
        vectorStore.addVectorRecords(
            listOf(
                doc("doc-1", "alpha", floatArrayOf(1f, 0f)),
                doc("doc-2", "beta", floatArrayOf(0.8f, 0.6f)),
                doc("doc-3", "gamma", floatArrayOf(0.6f, 0.8f)),
            ),
        )
        embedding = mockk<EmbeddingClient>()
        every { embedding.isConfigured } returns true
        every { embedding.hasLocalFallback } returns false
        every { embedding.diagnosticMessage() } returns "not configured"
        coEvery { embedding.embedQuery(any()) } returns (floatArrayOf(1f, 0f) to null)
        Unit
    }

    @After
    fun tearDown() = database.close()

    private fun doc(id: String, content: String, vector: FloatArray) = VectorStore.NewVectorRecord(
        docId = id,
        content = content,
        embedding = vector,
        metadata = documentVectorMetadata(id, 0, "$id.txt"),
    )

    private fun manager(rerankClient: RerankClient? = null, threshold: Float = 0.1f) = MemoryManager(
        vectorStore = vectorStore,
        keywordSearcher = KeywordSearcher(database.vectorDao()),
        graphStore = GraphStore(database),
        embeddingClient = embedding,
        rerankClient = rerankClient,
        ragConfig = RagConfiguration(
            enableQueryRewrite = false,
            enableHybridSearch = false,
            enableRerank = rerankClient != null,
            memoryThreshold = threshold,
            docThreshold = threshold,
            memoryLimit = 10,
            docLimit = 10,
            rerankTopK = 10,
            rerankFinalK = 10,
        ),
    )

    private suspend fun MemoryManager.retrieve(enableRerank: Boolean = false) = retrieveContext(
        query = "q",
        sessionId = "s1",
        options = MemoryManager.RetrieveOptions(
            enableMemory = false,
            enableDocs = true,
            documentScope = com.promenar.infinity.data.model.DocumentScope.ALL_INDEXED,
            sessionId = "s1",
            enableRerank = enableRerank,
        ),
    )

    @Test
    fun `未重排时按召回分排序并记录召回名次`() = runTest {
        val result = manager().retrieve()

        assertThat(result.references.map { it.documentId }).containsExactly("doc-1", "doc-2", "doc-3").inOrder()
        assertThat(result.references.map { it.recallRank }).containsExactly(1, 2, 3).inOrder()
        assertThat(result.references.all { it.rerankScore == null && it.rankChange == null }).isTrue()
        assertThat(result.references.all { it.kind == RagReferenceKind.DOCUMENT }).isTrue()
        val trace = requireNotNull(result.trace)
        assertThat(trace.outcome).isEqualTo(RagRetrievalTrace.OUTCOME_HIT)
        assertThat(trace.recallCount).isEqualTo(3)
        assertThat(trace.selectedCount).isEqualTo(3)
        assertThat(trace.documentCount).isEqualTo(3)
        assertThat(trace.scoring).isEqualTo(RagRetrievalTrace.SCORING_VECTOR)
        assertThat(trace.rerank).isEqualTo(RagRetrievalTrace.RERANK_OFF)
        assertThat(trace.effectiveDocumentIds).containsAtLeast("doc-1", "doc-2", "doc-3")
        assertThat(trace.graphCollectionId).isEqualTo("session:s1")
        assertThat(trace.graphTitle).isEqualTo(database.sessionDao().getById("s1")!!.title)
        assertThat(trace.graphBindingMode).isEqualTo(com.promenar.infinity.data.model.GraphBindingMode.READ_WRITE)
    }

    @Test
    fun `重排生效时记录重排分、原召回分与名次变化`() = runTest {
        val rerank = mockk<RerankClient>()
        coEvery { rerank.rerankWithOutcome(any(), any(), any()) } answers {
            val candidates = secondArg<List<SearchResult>>()
            val reordered = candidates.reversed().mapIndexed { index, result ->
                result.copy(similarity = 0.9f - index * 0.1f, originalSimilarity = result.similarity)
            }
            RerankOutcome(reordered, RerankMethod.API, "bge-reranker")
        }

        val result = manager(rerank).retrieve(enableRerank = true)

        assertThat(result.references.map { it.documentId }).containsExactly("doc-3", "doc-2", "doc-1").inOrder()
        val top = result.references.first()
        assertThat(top.recallRank).isEqualTo(3)
        assertThat(top.rankChange).isEqualTo(2)
        assertThat(top.rerankScore).isWithin(1e-4f).of(0.9f)
        assertThat(top.score).isWithin(1e-3f).of(0.6f)
        assertThat(result.references.last().rankChange).isEqualTo(-2)
        val trace = requireNotNull(result.trace)
        assertThat(trace.rerank).isEqualTo(RagRetrievalTrace.RERANK_API)
        assertThat(trace.rerankModel).isEqualTo("bge-reranker")
    }

    @Test
    fun `重排失败时保持召回顺序并如实记录`() = runTest {
        val rerank = mockk<RerankClient>()
        coEvery { rerank.rerankWithOutcome(any(), any(), any()) } throws IllegalStateException("down")

        val result = manager(rerank).retrieve(enableRerank = true)

        assertThat(result.references.map { it.documentId }).containsExactly("doc-1", "doc-2", "doc-3").inOrder()
        assertThat(result.references.all { it.rerankScore == null }).isTrue()
        assertThat(result.trace!!.rerank).isEqualTo(RagRetrievalTrace.RERANK_FAILED)
    }

    @Test
    fun `没有候选达到阈值时记录未命中`() = runTest {
        coEvery { embedding.embedQuery(any()) } returns (floatArrayOf(-1f, 0f) to null)

        val result = manager(threshold = 0.5f).retrieve()

        assertThat(result.references).isEmpty()
        assertThat(result.trace!!.outcome).isEqualTo(RagRetrievalTrace.OUTCOME_EMPTY)
    }

    @Test
    fun `嵌入模型不可用时记录未检索`() = runTest {
        every { embedding.isConfigured } returns false

        val result = manager().retrieve()

        assertThat(result.references).isEmpty()
        assertThat(result.trace!!.outcome).isEqualTo(RagRetrievalTrace.OUTCOME_UNAVAILABLE)
    }

    @Test
    fun `嵌入已配置但查询向量请求全部失败时记录检索失败而非未配置`() = runTest {
        coEvery { embedding.embedQuery(any()) } throws IllegalStateException("HTTP 503")

        val result = manager().retrieve()

        assertThat(result.references).isEmpty()
        assertThat(result.trace!!.outcome).isEqualTo(RagRetrievalTrace.OUTCOME_FAILED)
    }
}
