package com.promenar.infinity.ui.rag

import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.rag.KgCollectionArticle
import com.promenar.infinity.data.rag.KgTimelineHistory
import com.promenar.infinity.data.rag.KgTimelineStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 实体状态变化的一行：关系两端用实体名显示，区间为篇目序号。 */
data class KgEntityHistoryLine(
    val edgeId: String,
    val source: String,
    val relation: String,
    val target: String,
    val startOrdinal: Int,
    val endOrdinal: Int?,
    val flashback: Boolean,
)

/** 图谱页时间轴读取：合集篇目刻度与实体按阅读顺序的状态变化。 */
interface KgGraphTimelineSource {
    suspend fun ticks(collectionId: String): List<KgCollectionArticle>
    suspend fun history(collectionId: String, nodeId: String, ticks: List<KgCollectionArticle>, asOfOrdinal: Int?): List<KgEntityHistoryLine>
}

/** 图谱页按合集全部篇目计算时间线；会话或文档范围的可见性只作用于对话检索。 */
class DatabaseKgGraphTimelineSource(private val database: InfinityDatabase) : KgGraphTimelineSource {
    private val store = KgTimelineStore(database)

    override suspend fun ticks(collectionId: String): List<KgCollectionArticle> = store.ticks(collectionId)

    override suspend fun history(
        collectionId: String,
        nodeId: String,
        ticks: List<KgCollectionArticle>,
        asOfOrdinal: Int?,
    ): List<KgEntityHistoryLine> {
        val asOfDoc = asOfOrdinal?.let { ordinal -> ticks.firstOrNull { it.ordinal == ordinal }?.docId }
        val rows = store.entityHistory(collectionId, nodeId, ticks.mapTo(mutableSetOf()) { it.docId }, asOfDoc)
        val names = edgeEnds(rows.map { it.edgeId }.distinct())
        return kgHistoryLines(rows, names)
    }

    private suspend fun edgeEnds(edgeIds: List<String>): Map<String, Pair<String, String>> = withContext(Dispatchers.IO) {
        edgeIds.chunked(400).flatMap { batch ->
            database.query(
                SimpleSQLiteQuery(
                    "SELECT e.id,COALESCE(s.name,e.source_id),COALESCE(t.name,e.target_id) FROM kg_edges e " +
                        "LEFT JOIN kg_nodes s ON s.id=e.source_id LEFT JOIN kg_nodes t ON t.id=e.target_id " +
                        "WHERE e.id IN (${batch.joinToString { "?" }})",
                    batch.toTypedArray(),
                ),
            ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) to (c.getString(1) to c.getString(2))) } }
        }.toMap()
    }
}

/** 时间线记录转为显示行：缺少两端名字的关系不显示，同一关系同一区间只列一次。 */
internal fun kgHistoryLines(rows: List<KgTimelineHistory>, ends: Map<String, Pair<String, String>>): List<KgEntityHistoryLine> =
    rows.mapNotNull { row ->
        val (source, target) = ends[row.edgeId] ?: return@mapNotNull null
        KgEntityHistoryLine(row.edgeId, source, row.relation, target, row.startOrdinal, row.endOrdinal, row.flashback)
    }.distinctBy { listOf(it.edgeId, it.startOrdinal, it.endOrdinal, it.flashback) }
