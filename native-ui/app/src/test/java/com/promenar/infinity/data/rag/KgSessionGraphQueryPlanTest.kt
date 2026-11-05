package com.promenar.infinity.data.rag

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteProgram
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.ACTIVE_KG_EVIDENCE
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class KgSessionGraphQueryPlanTest {
    private lateinit var database: InfinityDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), InfinityDatabase::class.java)
            .allowMainThreadQueries().build()
        database.openHelper.writableDatabase.execSQL("INSERT INTO kg_collections(id,kind,title,created_at) VALUES('shared','shared','通用知识',1)")
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `三千条证据的查询使用现有索引且完成会话与轮次读取`() = runBlocking<Unit> {
        database.withTransaction {
            repeat(60) { database.kgNodeDao().insert(KgNodeEntity("n-$it", "n-$it", createdAt = 1, collectionId = "shared")) }
            repeat(5) { session ->
                val sessionId = "s-$session"
                database.sessionDao().insert(SessionEntity(sessionId, "agent", title = "",
                    ragOptions = """{"graphBinding":{"collectionId":"shared"}}""", createdAt = 1, updatedAt = 1))
                repeat(10) { turn ->
                    database.messageDao().insert(MessageEntity("u-$session-$turn", sessionId, "user", "正文", createdAt = turn * 2L))
                    val messageId = "a-$session-$turn"
                    database.messageDao().insert(MessageEntity(messageId, sessionId, "assistant", "正文", createdAt = turn * 2L + 1))
                    repeat(60) { node ->
                        database.kgEvidenceDao().insert(KgEvidenceEntity("p-$session-$turn-$node", nodeId = "n-$node",
                            sourceType = "session", sessionId = sessionId, messageId = messageId, contentHash = "",
                            taskId = "fixture", createdAt = turn.toLong()))
                    }
                }
            }
        }
        val nodePredicate = "(EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id = n.id AND $ACTIVE_KG_EVIDENCE) OR " +
            "EXISTS (SELECT 1 FROM kg_edges endpoint_edge WHERE (endpoint_edge.source_id=n.id OR endpoint_edge.target_id=n.id) " +
            "AND EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = endpoint_edge.id AND $ACTIVE_KG_EVIDENCE))) AND n.collection_id = ?"
        val edgePredicate = "EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id = e.id AND $ACTIVE_KG_EVIDENCE)"
        val queries = linkedMapOf(
            "options" to KgSessionGraphQueries.options("shared"),
            "turns" to KgSessionGraphQueries.turns("s-0", "shared"),
            "turnNodes" to KgSessionGraphQueries.turnNodes("s-0", (0..9).map { "a-0-$it" }, "shared"),
            "documents" to KgSessionGraphQueries.documents("n-0", 20),
            "sessionRefs" to KgSessionGraphQueries.sessionRefs("n-0", 20),
            "subgraphNodes" to SimpleSQLiteQuery("SELECT n.* FROM kg_nodes n WHERE $nodePredicate " +
                "ORDER BY COALESCE(n.updated_at, n.created_at) DESC, n.id LIMIT ?", arrayOf<Any?>("shared", 300)),
            "subgraphEdges" to SimpleSQLiteQuery("SELECT e.* FROM kg_edges e WHERE e.source_id IN (?) AND e.target_id IN (?) " +
                "AND $edgePredicate ORDER BY e.created_at DESC, e.id LIMIT ?", arrayOf<Any?>("n-0", "n-1", 1_200)),
            "neighbours" to SimpleSQLiteQuery("SELECT DISTINCT n.* FROM kg_edges e JOIN kg_nodes n " +
                "ON n.id = CASE WHEN e.source_id = ? THEN e.target_id ELSE e.source_id END " +
                "WHERE (e.source_id = ? OR e.target_id = ?) AND $edgePredicate AND $nodePredicate " +
                "ORDER BY COALESCE(n.updated_at, n.created_at) DESC, n.id LIMIT ? OFFSET ?",
                arrayOf<Any?>("n-0", "n-0", "n-0", "shared", 128, 0)),
        )
        queries.forEach { (name, sql) ->
            val plan = explain(sql)
            println("KG_SESSION_PLAN $name: ${plan.joinToString(" | ")}")
            assertThat(plan.any { it.contains("USING INDEX") || it.contains("USING COVERING INDEX") }).isTrue()
            if (name in listOf("turns", "turnNodes", "documents", "sessionRefs")) {
                assertThat(plan.any { Regex("\\bSCAN(?: TABLE)? (?:kg_evidence AS )?p\\b").containsMatchIn(it) }).isFalse()
            }
        }
        val started = System.nanoTime()
        val store = GraphStore(database)
        assertThat(store.getSessionOptions("shared")).hasSize(5)
        val turns = store.getSessionTurns("s-0")
        assertThat(turns.map { it.turnNumber }).containsExactlyElementsIn(1..10).inOrder()
        assertThat(turns.sumOf { it.nodeIds.size }).isEqualTo(600)
        assertThat(turns.sumOf { it.newNodeIds.size }).isEqualTo(60)
        assertThat(store.getEntitySources("n-0").sessionTurns).hasSize(20)
        assertThat(store.getGraphSubgraph(GraphSubgraphRequest(sessionId = "s-0")).totalNodeCount).isEqualTo(60)
        println("KG_SESSION_READ_MS ${(System.nanoTime() - started) / 1_000_000}")
    }

    private fun explain(query: SupportSQLiteQuery): List<String> {
        val explain = object : SupportSQLiteQuery {
            override val sql = "EXPLAIN QUERY PLAN ${query.sql}"
            override val argCount = query.argCount
            override fun bindTo(statement: SupportSQLiteProgram) = query.bindTo(statement)
        }
        return database.openHelper.readableDatabase.query(explain).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
        }
    }
}
