package com.promenar.infinity.data.rag

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgEvidenceEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KgSessionGraphTest {
    private lateinit var database: InfinityDatabase
    private lateinit var store: GraphStore

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        store = GraphStore(database)
        database.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('shared','shared','通用知识',1)")
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `会话子图展示绑定图谱内所有存活来源并聚合关系`() = runBlocking<Unit> {
        seedSession("s1")
        seedSession("s2")
        listOf("shared", "local", "other", "document", "jit").forEach { node(it) }
        evidence("shared", session = "s1", message = "s1-a")
        evidence("local", session = "s1", message = "s1-a")
        evidence("other", session = "s2", message = "s2-a")
        evidence("document", type = "document", doc = "doc")
        evidence("jit", type = "jit", session = "s1")
        edge("shared-local", "shared", "local")
        edgeEvidence("shared-local", "session", "s1", "s1-a", 0.2)
        edgeEvidence("shared-local", "session", "s1", "s1-a", 0.4)
        edgeEvidence("shared-local", "session", "s2", "s2-a", 0.9)
        edgeEvidence("shared-local", "jit", "s1", null, 0.95)
        edgeEvidence("shared-local", "document", null, null, 1.0, "doc")
        edge("jit-edge", "local", "shared")
        edgeEvidence("jit-edge", "jit", "s1", null, 1.0)

        val graph = store.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s1"))

        assertThat(graph.totalNodeCount).isEqualTo(5)
        assertThat(graph.data.nodes.map { it.id }).containsExactly("shared", "local", "other", "document", "jit")
        assertThat(graph.data.edges.map { it.id }).containsExactly("shared-local", "jit-edge")
        val combined = graph.data.edges.single { it.id == "shared-local" }
        assertThat(combined.weight).isWithin(0.0001).of(1.0)
        assertThat(combined.sourceType).isEqualTo("full")
        assertThat(combined.docId).isEqualTo("doc")
        assertThat(combined.documentIds).containsExactly("doc")
    }

    @Test
    fun `会话搜索包含前缀和包含匹配且展开过滤保留实体与邻居`() = runBlocking<Unit> {
        seedSession("s1")
        seedSession("s2")
        listOf("root", "local", "other", "jit").forEach { node(it) }
        listOf("root", "local").forEach { evidence(it, session = "s1", message = "s1-a") }
        evidence("other", session = "s2", message = "s2-a")
        evidence("jit", type = "jit", session = "s1")
        listOf("local", "other", "jit").forEach {
            edge("root-$it", "root", it)
            edgeEvidence("root-$it", if (it == "jit") "jit" else "session", if (it == "other") "s2" else "s1",
                if (it == "jit") null else if (it == "other") "s2-a" else "s1-a", 0.5)
        }

        assertThat(store.searchGraphEntities(GraphEntitySearchRequest("loc", sessionId = "s1")).map { it.id }).containsExactly("local")
        assertThat(store.searchGraphEntities(GraphEntitySearchRequest("oca", sessionId = "s1")).map { it.id }).containsExactly("local")
        assertThat(store.searchGraphEntities(GraphEntitySearchRequest("other", sessionId = "s1")).map { it.id }).containsExactly("other")
        val graph = store.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s1", retainedNodeIds = listOf("root", "other", "jit"), expandNodeId = "root"))
        assertThat(graph.data.nodes.map { it.id }).containsExactly("root", "local", "other", "jit")
        assertThat(graph.data.edges.map { it.id }).containsExactly("root-local", "root-other", "root-jit")
        assertThat(store.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s1", expandNodeId = "other")).data.nodes.map { it.id })
            .containsExactly("other", "root")
    }

    @Test
    fun `文档和会话范围互斥包括空搜索`() = runBlocking<Unit> {
        expectIllegalArgument { store.getGraphSubgraph(GraphSubgraphRequest(docId = "d", sessionId = "s")) }
        expectIllegalArgument { store.searchGraphEntities(GraphEntitySearchRequest("x", docId = "d", sessionId = "s")) }
        expectIllegalArgument { store.searchGraphEntities(GraphEntitySearchRequest("", docId = "d", sessionId = "s")) }
    }

    @Test
    fun `轮号只计用户消息且新增实体按首次存活轮次计算`() = runBlocking<Unit> {
        seedSession("s", assistantTime = 4)
        message("s", "tool", "tool", 2)
        message("s", "intermediate", "assistant", 3)
        message("s", "u2", "user", 5)
        message("s", "a2", "assistant", 6)
        message("s", "u3", "user", 7)
        message("s", "a3", "assistant", 8)
        listOf("first", "shared", "second", "third").forEach { node(it) }
        evidence("first", session = "s", message = "s-a")
        evidence("shared", session = "s", message = "s-a")
        evidence("shared", session = "s", message = "a2")
        evidence("second", session = "s", message = "a2")
        evidence("second", session = "s", message = "a2")
        evidence("third", session = "s", message = "a3")

        val turns = store.getSessionTurns("s")
        assertThat(turns.map { it.turnNumber }).containsExactly(1, 2, 3).inOrder()
        assertThat(turns.map { it.messageId }).containsExactly("s-a", "a2", "a3").inOrder()
        assertThat(turns.map { it.createdAt }).containsExactly(4L, 6L, 8L).inOrder()
        assertThat(turns[0].nodeIds).containsExactly("first", "shared")
        assertThat(turns[0].newNodeIds).containsExactly("first", "shared")
        assertThat(turns[1].nodeIds).containsExactly("shared", "second")
        assertThat(turns[1].newNodeIds).containsExactly("second")
        assertThat(turns[2].newNodeIds).containsExactly("third")
        assertThat(store.getEntitySources("shared").sessionTurns.map { it.turnNumber }).containsExactly(2, 1).inOrder()
    }

    @Test
    fun `仅有关系证据的历史轮次仍返回两个端点`() = runBlocking<Unit> {
        seedSession("s")
        node("source"); node("target")
        edge("edge-only", "source", "target")
        edgeEvidence("edge-only", "session", "s", "s-a", 0.8)

        val turn = store.getSessionTurns("s").single()

        assertThat(turn.messageId).isEqualTo("s-a")
        assertThat(turn.nodeIds).containsExactly("source", "target")
        assertThat(turn.newNodeIds).containsExactly("source", "target")
        assertThat(store.getSessionOptions("shared").single().entityCount).isEqualTo(2)
        assertThat(store.getEntitySources("source").sessionTurns.map { it.messageId }).containsExactly("s-a")
    }

    @Test
    fun `删除助手消息后四类读取失效且首次出现转移到后轮撤销后恢复`() = runBlocking<Unit> {
        seedSession("s1")
        seedSession("s2")
        message("s1", "u2", "user", 3)
        message("s1", "a2", "assistant", 4)
        node("shared")
        node("removed")
        evidence("shared", session = "s1", message = "s1-a", writtenAt = 100)
        evidence("removed", session = "s2", message = "s2-a", writtenAt = 300)
        evidence("shared", session = "s1", message = "a2", writtenAt = 200)
        database.messageDao().deleteById("s1-a")
        database.messageDao().deleteById("s2-a")

        assertThat(store.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s2")).data.nodes.map { it.id }).containsExactly("shared")
        assertThat(store.getSessionTurns("s2")).isEmpty()
        assertThat(store.getEntitySources("removed").sessionTurns).isEmpty()
        assertThat(store.getSessionOptions()).containsExactly(KgSessionOption("s1", "标题 s1", 1, 1, 200))
        assertThat(store.getSessionTurns("s1").single().newNodeIds).containsExactly("shared")
        assertThat(store.getEntitySources("shared").sessionTurns.map { it.messageId }).containsExactly("a2")
        message("s1", "s1-a", "assistant", 2)
        assertThat(store.getSessionTurns("s1").map { it.newNodeIds }).containsExactly(listOf("shared"), emptyList<String>()).inOrder()
    }

    @Test
    fun `会话选项只统计会话存活证据且按证据写入时间排序`() = runBlocking<Unit> {
        seedSession("old", title = "")
        seedSession("recent")
        seedSession("jit-only")
        seedSession("empty")
        node("one")
        node("two")
        message("old", "old-u2", "user", 3)
        message("old", "old-a2", "assistant", 4)
        evidence("one", session = "old", message = "old-a", writtenAt = 10)
        evidence("one", session = "old", message = "old-a2", writtenAt = 20)
        evidence("two", session = "old", message = "old-a2", writtenAt = 20)
        evidence("two", session = "recent", message = "recent-a", writtenAt = 30)
        evidence("two", type = "jit", session = "old", writtenAt = 1_000)
        evidence("two", type = "jit", session = "jit-only", writtenAt = 2_000)

        assertThat(store.getSessionOptions()).containsExactly(
            KgSessionOption("recent", "标题 recent", 1, 1, 30), KgSessionOption("old", "", 2, 2, 20),
        ).inOrder()
    }

    @Test
    fun `空图未知会话和未知实体返回空结果`() = runBlocking<Unit> {
        assertThat(store.getSessionOptions()).isEmpty()
        assertThat(store.getSessionTurns("missing")).isEmpty()
        assertThat(store.getEntitySources("missing")).isEqualTo(KgEntitySources(emptyList(), emptyList()))
    }

    @Test
    fun `实体来源同时列出文档及会话轮次并分别限额去重`() = runBlocking<Unit> {
        node("entity")
        node("other")
        listOf("s1", "s2", "s3").forEachIndexed { index, session ->
            seedSession(session, assistantTime = 10L + index)
            evidence("entity", session = session, message = "$session-a")
            evidence("entity", session = session, message = "$session-a")
        }
        listOf("a", "b", "c").forEach {
            document(it)
            evidence("entity", type = "document", doc = it)
            evidence("entity", type = "document", doc = it)
        }
        document("deleted", recycled = true)
        evidence("entity", type = "document", doc = "deleted", workspace = true)
        document("stale")
        evidence("entity", type = "document", doc = "stale", workspace = true, hash = "old-hash")
        evidence("entity", type = "document", doc = "no-title")

        val sources = store.getEntitySources("entity", 2)
        assertThat(sources.documents).containsExactly(KgDocumentOption("a", "a.md"), KgDocumentOption("b", "b.md")).inOrder()
        assertThat(sources.documents).isEqualTo(store.getDocumentOptions().take(2))
        assertThat(sources.sessionTurns).containsExactly(
            KgEntitySessionRef("s3", "标题 s3", "s3-a", 1), KgEntitySessionRef("s2", "标题 s2", "s2-a", 1),
        ).inOrder()
        assertThat(store.getEntitySources("entity", 0)).isEqualTo(KgEntitySources(emptyList(), emptyList()))
        assertThat(store.getEntitySources("entity", -1)).isEqualTo(KgEntitySources(emptyList(), emptyList()))
        assertThat(store.getEntitySources("other").sessionTurns).isEmpty()
    }

    @Test
    fun `同一时间的助手轮次排序稳定且用户消息时间相等也计入轮号`() = runBlocking<Unit> {
        seedSession("s", assistantTime = 1)
        message("s", "z", "assistant", 1)
        node("n")
        evidence("n", session = "s", message = "z")
        evidence("n", session = "s", message = "s-a")
        val turns = store.getSessionTurns("s")
        assertThat(turns.map { it.messageId }).containsExactly("s-a", "z").inOrder()
        assertThat(turns.map { it.turnNumber }).containsExactly(1, 1)
        assertThat(turns.map { it.newNodeIds }).containsExactly(listOf("n"), emptyList<String>()).inOrder()
    }

    @Test
    fun `删除高权重轮次后关系只使用剩余轮次证据`() = runBlocking<Unit> {
        seedSession("s")
        message("s", "a2", "assistant", 3)
        listOf("a", "b").forEach { node(it); evidence(it, session = "s", message = "s-a") }
        edge("e", "a", "b")
        edgeEvidence("e", "session", "s", "s-a", 0.3)
        edgeEvidence("e", "session", "s", "a2", 0.9)
        database.messageDao().deleteById("a2")

        assertThat(store.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s")).data.edges.single().weight).isWithin(0.0001).of(0.3)
        database.messageDao().deleteById("s-a")
        val graph = store.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s"))
        assertThat(graph.data.edges).isEmpty()
        assertThat(graph.data.nodes).isEmpty()
    }

    @Test
    fun `大量轮次遵循查询上限且来源大限额也有硬上限`() = runBlocking<Unit> {
        seedSession("s")
        node("n")
        database.withTransaction {
            repeat(MAX_KG_SESSION_TURNS + 1) { index ->
                message("s", "a-$index", "assistant", 3L + index)
                evidence("n", session = "s", message = "a-$index")
            }
        }

        val turns = store.getSessionTurns("s")
        assertThat(turns).hasSize(MAX_KG_SESSION_TURNS)
        assertThat(turns.first().messageId).isEqualTo("a-0")
        assertThat(turns.last().messageId).isEqualTo("a-${MAX_KG_SESSION_TURNS - 1}")
        assertThat(turns.first().newNodeIds).containsExactly("n")
        assertThat(turns.drop(1).flatMap { it.newNodeIds }).isEmpty()
        assertThat(store.getEntitySources("n", Int.MAX_VALUE).sessionTurns).hasSize(MAX_KG_ENTITY_SOURCES)
    }

    @Test
    fun `实体出现记录超限时只返回完整轮次前缀`() = runBlocking<Unit> {
        seedSession("s")
        message("s", "a2", "assistant", 3)
        node("first")
        evidence("first", session = "s", message = "s-a")
        database.withTransaction {
            repeat(MAX_KG_SESSION_NODE_OCCURRENCES) { index ->
                node("n-$index")
                evidence("n-$index", session = "s", message = "a2")
            }
        }

        val turns = store.getSessionTurns("s")
        assertThat(turns.map { it.messageId }).containsExactly("s-a")
        assertThat(turns.single().nodeIds).containsExactly("first")
    }

    private suspend fun seedSession(id: String, title: String = "标题 $id", assistantTime: Long = 2) {
        database.sessionDao().insert(SessionEntity(id, "agent", title = title,
            ragOptions = """{"graphBinding":{"collectionId":"shared"}}""", createdAt = 1, updatedAt = 99_999))
        message(id, "$id-u", "user", 1)
        message(id, "$id-a", "assistant", assistantTime)
    }

    private suspend fun message(session: String, id: String, role: String, time: Long) {
        database.messageDao().insert(MessageEntity(id, session, role, "正文", createdAt = time))
    }

    private suspend fun node(id: String) {
        database.kgNodeDao().insert(KgNodeEntity(id, id, createdAt = 1, collectionId = "shared"))
    }

    private suspend fun edge(id: String, source: String, target: String) {
        database.kgEdgeDao().insert(KgEdgeEntity(id, source, target, "关联", weight = 1.0, createdAt = 1, collectionId = "shared"))
    }

    private suspend fun evidence(node: String, type: String = "session", session: String? = null, message: String? = null,
        doc: String? = null, writtenAt: Long = 1, workspace: Boolean = false, hash: String = "") {
        database.kgEvidenceDao().insert(KgEvidenceEntity(UUID.randomUUID().toString(), nodeId = node, sourceType = type,
            docId = doc, sessionId = session, contentHash = hash, taskId = "fixture", createdAt = writtenAt,
            workspaceDocument = workspace, messageId = message))
    }

    private suspend fun edgeEvidence(edge: String, type: String, session: String?, message: String?, weight: Double, doc: String? = null) {
        database.kgEvidenceDao().insert(KgEvidenceEntity(UUID.randomUUID().toString(), edgeId = edge, sourceType = type,
            docId = doc, sessionId = session, contentHash = "", taskId = "fixture", createdAt = 1, weight = weight, messageId = message))
    }

    private suspend fun document(id: String, recycled: Boolean = false) {
        database.fileEntryDao().insert(FileEntry(uuid = id, parentUuid = null, name = "$id.md", hash = "hash",
            physicalRootPath = "workspace", materializedPath = "/$id.md", inRecycleBin = recycled, createdAt = 1, updatedAt = 1))
    }

    private suspend fun expectIllegalArgument(block: suspend () -> Unit) {
        try {
            block()
            throw AssertionError("应拒绝同时指定文档与会话范围")
        } catch (_: IllegalArgumentException) {
            // 互斥范围必须在查询前拒绝。
        }
    }
}
