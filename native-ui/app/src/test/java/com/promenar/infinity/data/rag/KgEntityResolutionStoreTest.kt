package com.promenar.infinity.data.rag

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class KgEntityResolutionStoreTest {
    private lateinit var db: InfinityDatabase
    private lateinit var graph: GraphStore
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        db.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('a','work','甲',1),('b','work','乙',1)")
        graph = GraphStore(db)
    }
    @After fun close() = db.close()

    @Test fun `仅手动执行模型判定且撤销可恢复原始节点`() = runBlocking<Unit> {
        val one = graph.upsertNode("青禾", "person", collectionId = "a")
        val two = graph.upsertNode("青禾先生", "person", collectionId = "a")
        val foreign = graph.upsertNode("青禾", "person", collectionId = "b")
        val city = graph.upsertNode("山城", "location", collectionId = "a")
        graph.createEdge(two, city, "居住", collectionId = "a")
        var calls = 0
        val store = KgEntityResolutionStore(db, KgResolutionJudge { _, pairs, _ ->
            calls++
            pairs.map { KgResolutionDecision(it.first.id, it.second.id, "same") }
        })
        assertThat(store.candidates("a")).hasSize(1)
        assertThat(store.estimate("a", 100).calls).isEqualTo(1)
        assertThat(calls).isEqualTo(0)
        store.runManual("a", "fixture-model", 100)
        assertThat(calls).isEqualTo(1)
        val merged = store.canonicalIds("a")
        assertThat(merged[one]).isEqualTo(merged[two])
        assertThat(merged).doesNotContainKey(foreign)
        val path = GraphRetrievalStore(db).retrieve("青禾", KgRetrievalScope("s", collectionId = "a")).paths.single()
        assertThat(path.edges.single().sourceId).isEqualTo(merged[one])
        val subgraph = graph.getGraphSubgraph(GraphSubgraphRequest(collectionId = "a"))
        assertThat(subgraph.data.nodes).hasSize(2)
        assertThat(subgraph.data.edges.single().sourceId).isEqualTo(merged[one])
        assertThat(graph.getEdgesForNode(one).single().sourceId).isEqualTo(merged[one])
        assertThat(graph.getAllNodes().count { it.name in setOf("青禾", "青禾先生") }).isEqualTo(2)
        val link = store.listLinks("a").single()
        store.revoke("a", link.id)
        val after = store.canonicalIds("a")
        assertThat(after[one]).isNotEqualTo(after[two])
        assertThat(store.candidates("a")).isEmpty()
        assertThat(runCatching { store.setUserDecision("a", one, foreign, true) }.isFailure).isTrue()
    }

    @Test fun `模型执行期间的用户否决不计为成功合并`() = runBlocking<Unit> {
        val first = graph.upsertNode("青禾", "person", collectionId = "a")
        val second = graph.upsertNode("青禾先生", "person", collectionId = "a")
        lateinit var store: KgEntityResolutionStore
        store = KgEntityResolutionStore(db, KgResolutionJudge { _, pairs, _ ->
            store.setUserDecision("a", first, second, false)
            pairs.map { KgResolutionDecision(it.first.id, it.second.id, "same") }
        })
        val result = store.runManual("a", "fixture-model", 100)
        assertThat(result.calls).isEqualTo(1)
        assertThat(result.decided).isEqualTo(0)
        assertThat(result.merged).isEqualTo(0)
        assertThat(store.canonicalIds("a")[first]).isNotEqualTo(store.canonicalIds("a")[second])
    }

    @Test fun `已合并列表排除被用户否决阻止的传递链接`() = runBlocking<Unit> {
        val first = graph.upsertNode("甲", "person", collectionId = "a")
        val second = graph.upsertNode("乙", "person", collectionId = "a")
        val middle = graph.upsertNode("丙", "person", collectionId = "a")
        val store = KgEntityResolutionStore(db)
        store.setUserDecision("a", first, middle, true)
        store.setUserDecision("a", second, middle, true)
        store.setUserDecision("a", first, second, false)
        val roots = store.canonicalIds("a")
        val links = store.listLinks("a")
        assertThat(links).hasSize(1)
        assertThat(roots[links.single().fromNode]).isEqualTo(roots[links.single().toNode])
    }
}
