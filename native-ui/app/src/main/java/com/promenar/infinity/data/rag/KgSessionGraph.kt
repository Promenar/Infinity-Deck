package com.promenar.infinity.data.rag

import android.database.Cursor
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.ACTIVE_KG_EVIDENCE
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KgSessionOption(
    val sessionId: String,
    val title: String,
    /** 有存活会话证据的轮数。 */
    val turnCount: Int,
    /** 该会话存活会话证据涉及的不同实体数。 */
    val entityCount: Int,
    /** 最近一条存活会话证据的写入时间（毫秒）。 */
    val updatedAt: Long,
)

data class KgSessionTurn(
    /** 该轮最后一条助手消息。 */
    val messageId: String,
    /** 会话内创建时间不晚于该助手消息的用户消息条数，从 1 开始。 */
    val turnNumber: Int,
    val createdAt: Long,
    /** 该轮存活证据涉及的实体 id。 */
    val nodeIds: List<String>,
    /** 在本会话中首次出现于这一轮的实体 id。 */
    val newNodeIds: List<String>,
)

data class KgEntitySessionRef(val sessionId: String, val sessionTitle: String, val messageId: String, val turnNumber: Int)
data class KgEntitySources(val documents: List<KgDocumentOption>, val sessionTurns: List<KgEntitySessionRef>)

internal const val MAX_KG_SESSION_OPTIONS = 1_000
internal const val MAX_KG_SESSION_TURNS = 400
internal const val MAX_KG_SESSION_NODE_OCCURRENCES = 20_000
internal const val MAX_KG_ENTITY_SOURCES = 100

/** 查询返回有界结果；每次读取在同一事务内使用一致的存活证据快照。 */
internal class KgSessionGraphStore(private val database: InfinityDatabase) {
    suspend fun getSessionOptions(collectionId: String? = null): List<KgSessionOption> = withContext(Dispatchers.IO) {
        database.withTransaction {
            query(KgSessionGraphQueries.options(collectionId)) {
                KgSessionOption(string("sessionId"), string("title"), int("turnCount"), int("entityCount"), long("updatedAt"))
            }
        }
    }

    suspend fun getSessionTurns(sessionId: String): List<KgSessionTurn> = withContext(Dispatchers.IO) {
        database.withTransaction {
            val collectionId = KgCollectionRepository(database).readBinding(sessionId).collectionId
            val turns = query(KgSessionGraphQueries.turns(sessionId, collectionId)) {
                KgSessionTurn(string("messageId"), int("turnNumber"), long("createdAt"), emptyList(), emptyList())
            }
            if (turns.isEmpty()) return@withTransaction emptyList()
            val nodes = query(KgSessionGraphQueries.turnNodes(sessionId, turns.map { it.messageId }, collectionId)) {
                TurnNode(string("messageId"), string("nodeId"), int("isNew") != 0)
            }
            // 多读一行检测上限；整轮舍弃越界部分，保证返回轮次的实体集合完整。
            val completeTurns = if (nodes.size > MAX_KG_SESSION_NODE_OCCURRENCES) {
                turns.takeWhile { it.messageId != nodes.last().messageId }
            } else turns
            val byMessage = nodes.groupBy { it.messageId }
            completeTurns.map { turn ->
                val found = byMessage[turn.messageId].orEmpty()
                turn.copy(nodeIds = found.map { it.nodeId }, newNodeIds = found.filter { it.isNew }.map { it.nodeId })
            }
        }
    }

    suspend fun getEntitySources(nodeId: String, limit: Int): KgEntitySources = withContext(Dispatchers.IO) {
        val bounded = limit.coerceIn(0, MAX_KG_ENTITY_SOURCES)
        if (bounded == 0) return@withContext KgEntitySources(emptyList(), emptyList())
        database.withTransaction {
            val documents = query(KgSessionGraphQueries.documents(nodeId, bounded)) {
                KgDocumentOption(string("docId"), string("title"))
            }
            val sessions = query(KgSessionGraphQueries.sessionRefs(nodeId, bounded)) {
                KgEntitySessionRef(string("sessionId"), string("title"), string("messageId"), int("turnNumber"))
            }
            KgEntitySources(documents, sessions)
        }
    }

    private fun <T> query(sql: SimpleSQLiteQuery, read: Cursor.() -> T): List<T> =
        database.openHelper.readableDatabase.query(sql).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.read()) }
        }

    private fun Cursor.string(column: String): String = getString(getColumnIndexOrThrow(column))
    private fun Cursor.int(column: String): Int = long(column).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private fun Cursor.long(column: String): Long = getLong(getColumnIndexOrThrow(column))
    private data class TurnNode(val messageId: String, val nodeId: String, val isNew: Boolean)
}

/** 与执行路径共享 SQL，查询计划检查使用相同的绑定与过滤条件。 */
internal object KgSessionGraphQueries {
    private const val TURN_NUMBER = "MAX(1, (SELECT COUNT(*) FROM messages u WHERE u.session_id = m.session_id AND u.role = 'user' AND u.created_at <= m.created_at))"

    fun options(collectionId: String? = null) = SimpleSQLiteQuery(
        "WITH visible AS (SELECT p.session_id AS sessionId,p.message_id AS messageId,p.node_id AS nodeId,p.edge_id AS edgeId,p.created_at AS createdAt " +
            "FROM kg_evidence p JOIN sessions s ON s.id=p.session_id WHERE p.source_type='session' AND $ACTIVE_KG_EVIDENCE " +
            (if (collectionId == null) "" else "AND (p.node_id IN (SELECT id FROM kg_nodes WHERE collection_id=?) OR p.edge_id IN (SELECT id FROM kg_edges WHERE collection_id=?)) ") +
            "), entities AS (SELECT sessionId,nodeId FROM visible WHERE nodeId IS NOT NULL " +
            "UNION SELECT v.sessionId,e.source_id FROM visible v JOIN kg_edges e ON e.id=v.edgeId " +
            "UNION SELECT v.sessionId,e.target_id FROM visible v JOIN kg_edges e ON e.id=v.edgeId), " +
            "totals AS (SELECT sessionId,COUNT(DISTINCT messageId) AS turnCount,MAX(createdAt) AS updatedAt FROM visible GROUP BY sessionId) " +
            "SELECT s.id AS sessionId,COALESCE(s.title,'') AS title,t.turnCount AS turnCount," +
            "(SELECT COUNT(*) FROM entities x WHERE x.sessionId=t.sessionId) AS entityCount,t.updatedAt AS updatedAt " +
            "FROM totals t JOIN sessions s ON s.id=t.sessionId ORDER BY updatedAt DESC,s.id LIMIT ?",
        ((if (collectionId == null) emptyList() else listOf(collectionId, collectionId)) + MAX_KG_SESSION_OPTIONS).toTypedArray(),
    )

    fun turns(sessionId: String, collectionId: String? = null) = SimpleSQLiteQuery(
        "SELECT m.id AS messageId, m.created_at AS createdAt, $TURN_NUMBER AS turnNumber FROM messages m " +
            "WHERE m.session_id = ? AND m.role = 'assistant' AND EXISTS (SELECT 1 FROM kg_evidence p " +
            "WHERE p.message_id = m.id AND p.session_id = m.session_id AND p.source_type = 'session' AND $ACTIVE_KG_EVIDENCE" +
            (if (collectionId == null) "" else " AND (p.node_id IN (SELECT id FROM kg_nodes WHERE collection_id = ?) OR p.edge_id IN (SELECT id FROM kg_edges WHERE collection_id = ?))") + ") " +
            "ORDER BY m.created_at, m.id LIMIT ?",
        (listOf(sessionId) + (if (collectionId == null) emptyList() else listOf(collectionId, collectionId)) + MAX_KG_SESSION_TURNS).toTypedArray(),
    )

    fun turnNodes(sessionId: String, messageIds: List<String>, collectionId: String? = null) = SimpleSQLiteQuery(
        "WITH relevant AS (SELECT p.message_id AS messageId, p.node_id AS nodeId, p.edge_id AS edgeId, m.created_at AS createdAt " +
            "FROM kg_evidence p JOIN messages m ON m.id = p.message_id AND m.session_id = p.session_id AND m.role = 'assistant' " +
            "WHERE p.source_type = 'session' AND p.session_id = ? AND $ACTIVE_KG_EVIDENCE" +
            (if (collectionId == null) "" else " AND (p.node_id IN (SELECT id FROM kg_nodes WHERE collection_id = ?) OR p.edge_id IN (SELECT id FROM kg_edges WHERE collection_id = ?))") + "), " +
            "turn_nodes AS (SELECT messageId,nodeId,createdAt FROM relevant WHERE nodeId IS NOT NULL " +
            "UNION SELECT r.messageId,e.source_id,r.createdAt FROM relevant r JOIN kg_edges e ON e.id=r.edgeId " +
            "UNION SELECT r.messageId,e.target_id,r.createdAt FROM relevant r JOIN kg_edges e ON e.id=r.edgeId), " +
            "ordered_nodes AS (SELECT *, ROW_NUMBER() OVER (PARTITION BY nodeId ORDER BY createdAt, messageId) AS occurrenceNumber " +
            "FROM turn_nodes) SELECT messageId, nodeId, occurrenceNumber = 1 AS isNew FROM ordered_nodes " +
            "WHERE messageId IN (${List(messageIds.size) { "?" }.joinToString(",")}) ORDER BY createdAt, messageId, nodeId LIMIT ?",
        (listOf(sessionId) + (if (collectionId == null) emptyList() else listOf(collectionId, collectionId)) + messageIds + (MAX_KG_SESSION_NODE_OCCURRENCES + 1)).toTypedArray(),
    )

    fun documents(nodeId: String, limit: Int) = SimpleSQLiteQuery(
        "SELECT DISTINCT p.doc_id AS docId, w.name AS title FROM kg_evidence p JOIN workspace_files w ON w.uuid = p.doc_id " +
            "WHERE (p.node_id = ? OR p.edge_id IN (SELECT id FROM kg_edges WHERE source_id=? OR target_id=?)) AND w.in_recycle_bin = 0 AND $ACTIVE_KG_EVIDENCE ORDER BY title, docId LIMIT ?",
        arrayOf<Any?>(nodeId, nodeId, nodeId, limit),
    )

    fun sessionRefs(nodeId: String, limit: Int) = SimpleSQLiteQuery(
        "SELECT DISTINCT s.id AS sessionId, COALESCE(s.title, '') AS title, m.id AS messageId, m.created_at AS createdAt, " +
            "$TURN_NUMBER AS turnNumber FROM kg_evidence p JOIN sessions s ON s.id = p.session_id " +
            "JOIN messages m ON m.id = p.message_id AND m.session_id = p.session_id AND m.role = 'assistant' " +
            "WHERE (p.node_id = ? OR p.edge_id IN (SELECT id FROM kg_edges WHERE source_id=? OR target_id=?)) AND p.source_type = 'session' AND $ACTIVE_KG_EVIDENCE " +
            "ORDER BY m.created_at DESC, s.id, m.id DESC LIMIT ?",
        arrayOf<Any?>(nodeId, nodeId, nodeId, limit),
    )
}
