package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.model.GraphBinding
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.ui.chat.manager.KgContextResult
import com.promenar.infinity.ui.chat.manager.KgProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KgTimelineStoreTest {
    private lateinit var db: InfinityDatabase
    private lateinit var graph: GraphStore
    private lateinit var collections: KgCollectionRepository

    @Before fun setup() = runBlocking<Unit> {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        graph = GraphStore(db)
        collections = KgCollectionRepository(db)
        db.fileEntryDao().insert(FileEntry(uuid = "p", workspaceRootUuid = "root", parentUuid = null, name = "作品", hash = "",
            physicalRootPath = "/fixture", materializedPath = "/p", isDirectory = true, createdAt = 1, updatedAt = 1))
        (1..20).forEach { chapter ->
            db.fileEntryDao().insert(FileEntry(uuid = "doc$chapter", workspaceRootUuid = "root", parentUuid = "p", name = "第${chapter}章", hash = "h",
                physicalRootPath = "/fixture", materializedPath = "/doc$chapter", createdAt = 1, updatedAt = 1))
        }
        collections.resolveDocumentForWrite("doc3")
    }
    @After fun close() = db.close()

    private suspend fun fact(chapter: Int, relation: String, flashback: Boolean = false, sessionId: String? = null, kind: String = "state") {
        val nodes = listOf(KgNodeEntity("a", "甲", "person", createdAt = 1), KgNodeEntity("b", "乙", "person", createdAt = 1))
        val edge = KgEdgeEntity("candidate", "a", "b", relation, createdAt = 1)
        val mention = KgCandidateMention(0, 0, 4, kind, if (kind == "state") "relationship" else null, "甲乙关系", false, flashback)
        graph.commitCandidate(DocumentIndexCandidate(emptyList(), nodes, listOf(edge), edgeMentions = mapOf(edge.id to mention)),
            KgSource(if (sessionId == null) "document" else "session", docId = "doc$chapter", sessionId = sessionId,
                contentHash = "h", taskId = "task$chapter", workspaceDocument = true))
    }

    @Test fun `范围历史重排和删除均按篇序重算`() = runBlocking<Unit> {
        fact(3, "同盟"); fact(15, "敌对")
        val timeline = KgTimelineStore(db)
        val firstTen = (1..10).map { "doc$it" }.toSet()
        assertThat(timeline.snapshot("work:p", firstTen).states.single().relation).isEqualTo("同盟")
        val all = (1..20).map { "doc$it" }.toSet()
        val state = timeline.snapshot("work:p", all).states.single()
        assertThat(state.relation).isEqualTo("敌对")
        assertThat(state.history.single().relation).isEqualTo("同盟")
        assertThat(state.history.single().startOrdinal).isEqualTo(3)
        assertThat(state.history.single().endOrdinal).isEqualTo(14)
        val alpha = db.kgNodeDao().getByCollection("work:p").single { it.name == "甲" }
        assertThat(timeline.entityHistory("work:p", alpha.id, all).map { it.relation }).containsAtLeast("敌对", "同盟")
        val retrieval = GraphRetrievalStore(db)
        val firstPaths = retrieval.retrieve("甲", KgRetrievalScope("s", documentIds = firstTen.toList(), collectionId = "work:p")).paths
        assertThat(firstPaths.map { it.edges.single().relation }).containsExactly("同盟")
        val allPaths = retrieval.retrieve("甲", KgRetrievalScope("s", documentIds = all.toList(), collectionId = "work:p")).paths
        assertThat(allPaths.map { it.edges.single().relation }).containsExactly("敌对")
        assertThat(allPaths.single().reasoning).contains("第 3–14 篇")
        val explicit = retrieval.retrieve("甲第10章", KgRetrievalScope("s", documentIds = all.toList(), collectionId = "work:p")).paths
        assertThat(explicit.map { it.edges.single().relation }).containsExactly("同盟")
        val missingChapter = retrieval.retrieve("甲第99章", KgRetrievalScope("s", documentIds = all.toList(), collectionId = "work:p")).paths
        assertThat(missingChapter).isEmpty()
        graph.upsertNode("后续出场者", "person", scope = KgScope(docId = "doc15", contentHash = "h", workspaceDocument = true),
            sourceType = "document", collectionId = "work:p")
        val graphAtTen = graph.getGraphSubgraph(GraphSubgraphRequest(collectionId = "work:p", asOf = KgAsOf.Ordinal(10), edgeLimit = 1))
        assertThat(graphAtTen.data.edges.map { it.relation }).containsExactly("同盟")
        assertThat(graphAtTen.data.nodes.map { it.name }).doesNotContain("后续出场者")
        collections.moveArticle("work:p", "doc15", 2)
        assertThat(timeline.snapshot("work:p", all).states.single().relation).isEqualTo("同盟")
        graph.clearGraphForDoc("doc3")
        assertThat(timeline.snapshot("work:p", all).states.single().relation).isEqualTo("敌对")
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_edge_validity WHERE collection_id='work:p'").use { c ->
            check(c.moveToFirst()); assertThat(c.getInt(0)).isEqualTo(1)
        }
    }

    @Test fun `倒叙只作为历史以及旧边位置未知`() = runBlocking<Unit> {
        fact(3, "同盟"); fact(15, "敌对"); fact(19, "同盟", flashback = true)
        val ids = db.kgNodeDao().getByCollection("work:p").associateBy { it.name }
        val legacy = graph.createEdge(ids.getValue("甲").id, ids.getValue("乙").id, "旧关系", scope = KgScope(docId = "doc1"), collectionId = "work:p")
        val result = KgTimelineStore(db).snapshot("work:p", (1..20).map { "doc$it" }.toSet())
        assertThat(result.states.single().relation).isEqualTo("敌对")
        assertThat(result.states.single().flashbacks.map { it.relation }).containsExactly("同盟")
        assertThat(result.states.single().flashbacks.single().quote).isEqualTo("甲乙关系")
        assertThat(result.unknownEdgeIds).contains(legacy)
        val paths = GraphRetrievalStore(db).retrieve("甲", KgRetrievalScope("s", documentIds = (1..20).map { "doc$it" }, collectionId = "work:p")).paths
        assertThat(paths.single { it.edges.single().relation == "旧关系" }.reasoning).contains("位置未知")
    }

    @Test fun `同一事件的多次出现按位置倒序交给上下文`() = runBlocking<Unit> {
        fact(9, "救出", kind = "event"); fact(12, "救出", kind = "event")
        val paths = GraphRetrievalStore(db).retrieve("甲", KgRetrievalScope("s", documentIds = (1..20).map { "doc$it" }, collectionId = "work:p")).paths
        assertThat(paths.map { it.narrativeEventOrdinal }).containsExactly(12, 9).inOrder()
        val context = KgContextBudget.fit(paths, 4096).context
        assertThat(context).contains("第 12 篇")
        assertThat(context).contains("第 9 篇")
        assertThat(Regex("事件：").findAll(context).count()).isEqualTo(2)
    }

    @Test fun `撤回会话证据后重算有效区间`() = runBlocking<Unit> {
        fact(3, "同盟"); fact(15, "敌对", sessionId = "s")
        assertThat(collections.revokeSessionEvidence("s", "work:p")).isGreaterThan(0)
        val all = (1..20).map { "doc$it" }.toSet()
        assertThat(KgTimelineStore(db).snapshot("work:p", all).states.single().relation).isEqualTo("同盟")
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM kg_edge_validity WHERE collection_id='work:p'").use { c ->
            check(c.moveToFirst()); assertThat(c.getInt(0)).isEqualTo(1)
        }
    }

    @Test fun `来源不一致的出现记录不能改变时间线`() = runBlocking<Unit> {
        fact(15, "敌对")
        db.openHelper.writableDatabase.execSQL("UPDATE kg_edge_mentions SET doc_id='doc2'")
        val snapshot = KgTimelineStore(db).snapshot("work:p", (1..20).map { "doc$it" }.toSet())
        assertThat(snapshot.states).isEmpty()
        assertThat(snapshot.unknownEdgeIds).hasSize(1)
    }

    @Test fun `存在时间线时即时抽取不会补入时间点之外的事实`() = runBlocking<Unit> {
        db.sessionDao().insert(SessionEntity("s", "agent", createdAt = 1, updatedAt = 1))
        collections.setBinding("s", GraphBinding("work:p"))
        fact(3, "同盟"); fact(15, "敌对")
        var calls = 0
        val jit = object : KgProvider {
            override suspend fun extractContext(query: String, sessionId: String, topKResults: List<RagReference>): KgContextResult {
                calls++
                return KgContextResult("")
            }
        }
        val provider = PersistentGraphKgProvider(graph, jit) { RagConfiguration(kgJitMinSeeds = 32) }
        provider.retrieveContext("甲第10章", "s", listOf(RagReference("future", "第十五章片段", documentId = "doc15")),
            RagOptions(activeDocIds = (1..20).map { "doc$it" }, enableKnowledgeGraph = true, graphBinding = GraphBinding("work:p")))
        assertThat(calls).isEqualTo(0)
    }
}
