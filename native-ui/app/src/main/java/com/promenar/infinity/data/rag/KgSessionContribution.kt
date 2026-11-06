package com.promenar.infinity.data.rag

import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 会话设置“撤回本会话添加”一行所需的数据：当前绑定、可绑定图谱与本会话在绑定图谱中的贡献。 */
data class SessionGraphBindingData(
    val binding: ResolvedGraphBinding,
    val collections: List<KgCollectionSummary>,
    /** 本会话写入绑定图谱的实体数与关系数；绑定本会话图谱时为 null。 */
    val contribution: Pair<Int, Int>?,
)

/** 读取会话的图谱绑定状态。只读，在事务外调用。 */
suspend fun loadSessionGraphBinding(
    database: InfinityDatabase,
    sessionId: String,
    options: com.promenar.infinity.data.model.RagOptions,
): SessionGraphBindingData = withContext(Dispatchers.IO) {
    val repository = KgCollectionRepository(database)
    val binding = repository.readBinding(sessionId, options)
    val collections = repository.listBindableCollections(sessionId)
    val contribution = if (binding.collectionId == KgCollectionRepository.sessionCollectionId(sessionId)) null
        else sessionContribution(database, sessionId, binding.collectionId)
    SessionGraphBindingData(binding, collections, contribution)
}

/** 本会话证据支撑的实体数与关系数（含即时抽取证据），与撤回范围一致。 */
internal fun sessionContribution(database: InfinityDatabase, sessionId: String, collectionId: String): Pair<Int, Int> =
    database.query(
        SimpleSQLiteQuery(
            """SELECT
                (SELECT COUNT(DISTINCT p.node_id) FROM kg_evidence p JOIN kg_nodes n ON n.id = p.node_id
                    WHERE p.session_id = ? AND n.collection_id = ?),
                (SELECT COUNT(DISTINCT p.edge_id) FROM kg_evidence p JOIN kg_edges e ON e.id = p.edge_id
                    WHERE p.session_id = ? AND e.collection_id = ?)""",
            arrayOf(sessionId, collectionId, sessionId, collectionId),
        ),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) to cursor.getInt(1) else 0 to 0 }

/** 图谱合集（作品与通用知识），供权限设置与图谱页选择。只读。 */
suspend fun loadLibraryCollections(database: InfinityDatabase): List<KgCollectionSummary> = withContext(Dispatchers.IO) {
    KgCollectionRepository(database).listBindableCollections("").filter { it.kind != "session" }
}
