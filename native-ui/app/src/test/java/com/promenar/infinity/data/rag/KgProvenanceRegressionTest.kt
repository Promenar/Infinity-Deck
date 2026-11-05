package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.session.RoomSessionDeletionTransaction
import com.promenar.infinity.domain.repository.RenameIndexTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KgProvenanceRegressionTest {
    private lateinit var db: InfinityDatabase
    private lateinit var store: GraphStore

    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        store = GraphStore(db)
        db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('shared','shared','通用知识',1)")
        runBlocking {
            listOf("s1", "s2").forEach { id -> db.sessionDao().insert(SessionEntity(id, "agent", createdAt = 1, updatedAt = 1,
                ragOptions = """{"graphBinding":{"collectionId":"shared"}}""")) }
        }
    }

    @After fun close() = db.close()

    @Test fun `删除首个会话不破坏另一会话共享的实体和关系`() = runBlocking {
        val a = store.upsertNode("Alice", "person", scope = KgScope(sessionId = "s1"), collectionId = "shared")
        val b = store.upsertNode("Bob", "person", scope = KgScope(sessionId = "s1"), collectionId = "shared")
        store.createEdge(a, b, "knows", scope = KgScope(sessionId = "s1"), collectionId = "shared")
        store.upsertNode("Alice", "person", scope = KgScope(sessionId = "s2"), collectionId = "shared")
        store.upsertNode("Bob", "person", scope = KgScope(sessionId = "s2"), collectionId = "shared")
        store.createEdge(a, b, "knows", scope = KgScope(sessionId = "s2"), collectionId = "shared")

        RoomSessionDeletionTransaction(db).deleteWithoutWorkspace("s1")

        assertThat(store.getGraphData(sessionId = "s2").edges).hasSize(1)
        assertThat(store.getGraphData(sessionId = "s2").nodes).hasSize(2)
    }

    @Test fun `Unicode和大小写归一且同名不同类型保持独立`() = runBlocking {
        val a = store.upsertNode(" Ａlice ", "PERSON", collectionId = "shared")
        val b = store.upsertNode("alice", "person", collectionId = "shared")
        val c = store.upsertNode("alice", "org", collectionId = "shared")
        assertThat(a).isEqualTo(b)
        assertThat(c).isNotEqualTo(a)
    }

    @Test fun `并发写入只有一个业务实体和一个关系`() = runBlocking {
        coroutineScope {
            (1..24).map { index -> async(Dispatchers.IO) {
                val scope = KgScope(sessionId = "s$index")
                val a = store.upsertNode(if (index % 2 == 0) " ALICE " else "Alice", "person", scope = scope, collectionId = "shared")
                val b = store.upsertNode("Bob", "person", scope = scope, collectionId = "shared")
                store.createEdge(a, b, if (index % 2 == 0) " KNOWS " else "knows", scope = scope, collectionId = "shared")
            } }.awaitAll()
        }
        assertThat(store.getGraphData().nodes).hasSize(2)
        assertThat(store.getGraphData().edges).hasSize(1)
    }

    @Test fun `数据库把来源证据独立存储`() {
        db.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name='kg_evidence'").use {
            assertThat(it.moveToFirst()).isTrue()
        }
    }

    @Test fun `删除文档只移除该来源证据且空文档筛选不泄漏全图`() = runBlocking {
        val first = KgScope(docId = "doc-a", contentHash = "v1", taskId = "t1")
        val second = KgScope(docId = "doc-b", contentHash = "v2", taskId = "t2")
        val a = store.upsertNode("Alice", "person", scope = first, collectionId = "shared")
        val b = store.upsertNode("Bob", "person", scope = first, collectionId = "shared")
        store.createEdge(a, b, "knows", scope = first, collectionId = "shared")
        store.createEdge(a, b, " KNOWS ", scope = second, collectionId = "shared")
        store.clearGraphForDoc("doc-a")
        assertThat(store.getGraphData(docIds = listOf("doc-b")).edges).hasSize(1)
        assertThat(store.getGraphData(docIds = emptyList()).edges).isEmpty()
        assertThat(store.getGraphData().edges.single().documentIds).containsExactly("doc-b")
        store.clearGraphForDoc("doc-b")
        assertThat(store.getGraphData().nodes).isEmpty()
    }

    @Test fun `种子实体查询用结构化文档范围且支持第二跳`() = runBlocking {
        val a = store.upsertNode("A", collectionId = "shared")
        val b = store.upsertNode("B", collectionId = "shared")
        val c = store.upsertNode("C", collectionId = "shared")
        store.createEdge(a, b, "first", docId = "d1", collectionId = "shared")
        store.createEdge(b, c, "second", docId = "d1", collectionId = "shared")
        store.createEdge(a, c, "other", docId = "d2", collectionId = "shared")
        val dao = db.kgEdgeDao()
        assertThat(dao.getBySeedIds(listOf(a), true, listOf("d1"), 10).map { it.relation }).containsExactly("first")
        assertThat(dao.getBySeedIds(listOf(b), true, listOf("d1"), 10).map { it.relation }).containsExactly("first", "second")
        assertThat(dao.getBySeedIds(listOf(a), true, emptyList(), 10)).isEmpty()
    }

    @Test fun `手动合并重指向碰撞关系时保留全部来源`() = runBlocking {
        val a = store.upsertNode("A", scope = KgScope(sessionId = "s1"), collectionId = "shared")
        val b = store.upsertNode("B", scope = KgScope(sessionId = "s2"), collectionId = "shared")
        val c = store.upsertNode("C", scope = KgScope(sessionId = "s2"), collectionId = "shared")
        store.createEdge(a, c, "knows", scope = KgScope(sessionId = "s1"), collectionId = "shared")
        store.createEdge(b, c, "knows", scope = KgScope(sessionId = "s2"), collectionId = "shared")
        store.mergeNodes(a, "B")
        assertThat(store.getGraphData().edges).hasSize(1)
        assertThat(store.getGraphData(sessionId = "s1").edges.single().sourceId).isEqualTo(b)
        RoomSessionDeletionTransaction(db).deleteWithoutWorkspace("s1")
        assertThat(store.getGraphData(sessionId = "s2").edges).hasSize(1)
    }

    @Test fun `关系置信度与种子排序只使用当前来源的存活证据`() = runBlocking {
        val a = store.upsertNode("A", collectionId = "shared")
        val b = store.upsertNode("B", collectionId = "shared")
        val c = store.upsertNode("C", collectionId = "shared")
        store.createEdge(a, b, "shared", docId = "a", weight = 0.9, collectionId = "shared")
        store.createEdge(a, b, "shared", docId = "b", weight = 0.2, collectionId = "shared")
        store.createEdge(a, c, "other", docId = "b", weight = 0.5, collectionId = "shared")
        assertThat(store.getGraphData(docIds = listOf("b")).edges.single { it.relation == "shared" }.weight).isEqualTo(0.2)
        assertThat(db.kgEdgeDao().getBySeedIds(listOf(a), true, listOf("b"), 1).single().relation).isEqualTo("other")
        store.clearGraphForDoc("a")
        assertThat(store.getGraphData().edges.single { it.relation == "shared" }.weight).isEqualTo(0.2)
    }

    private fun file(id: String) = FileEntry(uuid = id, workspaceRootUuid = "root", parentUuid = "root",
        name = "$id.md", hash = "hash-$id", physicalRootPath = "/tmp/kg-test", materializedPath = "/$id.md",
        createdAt = 1, updatedAt = 20)
}
