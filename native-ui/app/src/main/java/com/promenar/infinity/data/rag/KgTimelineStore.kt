package com.promenar.infinity.data.rag

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.ACTIVE_KG_EVIDENCE

data class KgTimelineHistory(
    val edgeId: String,
    val relation: String,
    val startOrdinal: Int,
    val endOrdinal: Int?,
    val docId: String,
    val flashback: Boolean = false,
    val windowIndex: Int = 0,
    val charStart: Int = 0,
    val charEnd: Int = charStart,
    val quote: String? = null,
    val positionCoarse: Boolean = false,
)
data class KgTimelineCurrent(
    val edgeId: String,
    val relation: String,
    val startOrdinal: Int,
    val history: List<KgTimelineHistory>,
    val flashbacks: List<KgTimelineHistory>,
    val docId: String,
    val windowIndex: Int,
    val charStart: Int,
    val charEnd: Int,
    val quote: String?,
    val positionCoarse: Boolean,
)
data class KgTimelineSnapshot(
    val asOfDocId: String?,
    val asOfOrdinal: Int?,
    val states: List<KgTimelineCurrent>,
    val events: List<KgTimelineHistory>,
    val flashbacks: List<KgTimelineHistory>,
    val unknownEdgeIds: Set<String>,
    val knownEdgeIds: Set<String>,
)

/** 写入时物化全合集区间，读取时依据可见文档重新计算切片。 */
class KgTimelineStore(private val database: InfinityDatabase) {
    suspend fun ticks(collectionId: String): List<KgCollectionArticle> = KgCollectionOrder(database).list(collectionId)

    suspend fun entityHistory(collectionId: String, nodeId: String, visibleDocIds: Set<String>, asOfDocId: String? = null): List<KgTimelineHistory> = database.withTransaction {
        val canonical = KgEntityResolutionStore(database).canonicalIds(collectionId)
        require(nodeId in canonical) { "实体不属于该合集" }
        val root = canonical.getValue(nodeId)
        val members = canonical.filterValues { it == root }.keys.toList()
        val edgeIds = members.chunked(400).flatMap { batch ->
            val ids = batch.joinToString { "?" }
            database.query(SimpleSQLiteQuery("SELECT id FROM kg_edges WHERE collection_id=? AND (source_id IN ($ids) OR target_id IN ($ids))",
                (listOf(collectionId) + batch + batch).toTypedArray())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        }.toSet()
        val snapshot = snapshotInternal(collectionId, visibleDocIds, asOfDocId, Int.MAX_VALUE)
        val current = snapshot.states.filter { it.edgeId in edgeIds }.flatMap { state ->
            listOf(KgTimelineHistory(state.edgeId, state.relation, state.startOrdinal, null,
                state.docId, windowIndex = state.windowIndex, charStart = state.charStart, charEnd = state.charEnd,
                quote = state.quote, positionCoarse = state.positionCoarse)) + state.history + state.flashbacks
        }
        (current + snapshot.events.filter { it.edgeId in edgeIds } + snapshot.flashbacks.filter { it.edgeId in edgeIds })
            .distinct().sortedByDescending { it.startOrdinal }
    }

    suspend fun recompute(collectionId: String) = database.withTransaction {
        val articles = KgCollectionOrder(database).list(collectionId)
        val mentions = loadMentions(collectionId, articles)
        val timeline = KgNarrativeTimeline.materialize(mentions)
        val byOrdinal = articles.associateBy { it.ordinal }
        val sqlite = database.openHelper.writableDatabase
        sqlite.execSQL("DELETE FROM kg_edge_validity WHERE collection_id=?", arrayOf(collectionId))
        timeline.intervals.forEach { interval ->
            val from = requireNotNull(byOrdinal[interval.from.order])
            val to = interval.toExclusive?.let { byOrdinal[it.order] }
            val id = kgSha256(listOf(collectionId, interval.edgeId, interval.slot.family, interval.slot.sourceId, interval.slot.targetId.orEmpty(), from.docId,
                interval.from.windowIndex.toString(), interval.from.charStart.toString()).joinToString("\u0000"))
            sqlite.execSQL("INSERT INTO kg_edge_validity(id,edge_id,collection_id,from_doc_id,from_window,from_char,to_doc_id,to_window,to_char) VALUES(?,?,?,?,?,?,?,?,?)",
                arrayOf<Any?>(id, interval.edgeId, collectionId, from.docId, interval.from.windowIndex, interval.from.charStart,
                    to?.docId, interval.toExclusive?.windowIndex, interval.toExclusive?.charStart))
        }
    }

    suspend fun snapshot(collectionId: String, visibleDocIds: Set<String>, asOfDocId: String? = null): KgTimelineSnapshot =
        snapshotInternal(collectionId, visibleDocIds, asOfDocId, 3)

    private suspend fun snapshotInternal(collectionId: String, visibleDocIds: Set<String>, asOfDocId: String?, historyLimit: Int): KgTimelineSnapshot = database.withTransaction {
        val articles = KgCollectionOrder(database).list(collectionId)
        val visible = articles.filter { it.docId in visibleDocIds }
        val point = if (asOfDocId == null) visible.lastOrNull() else {
            requireNotNull(visible.firstOrNull { it.docId == asOfDocId }) { "时间点不在当前可见文档范围" }
        }
        val allMentions = loadMentions(collectionId, articles)
        val known = allMentions.mapTo(mutableSetOf()) { it.edgeId }
        val eligible = if (point == null) emptyList() else allMentions.filter { it.docId in visibleDocIds && it.order <= point.ordinal }
        val timeline = KgNarrativeTimeline.materialize(eligible)
        val edgeNames = database.query(SimpleSQLiteQuery("SELECT e.id,e.relation FROM kg_edges e WHERE e.collection_id=?", arrayOf(collectionId))).use { c ->
            buildMap { while (c.moveToNext()) put(c.getString(0), c.getString(1)) }
        }
        fun mentionHistory(row: KgNarrativeMention) = KgTimelineHistory(row.edgeId, edgeNames[row.edgeId].orEmpty(), row.order, row.order,
            row.docId, row.flashback, row.windowIndex, row.charStart, row.charEnd, row.quote, row.positionCoarse)
        fun intervalMention(interval: KgTimelineInterval) = eligible.first { it.edgeId == interval.edgeId && it.position == interval.from && !it.flashback }
        fun intervalHistory(interval: KgTimelineInterval): KgTimelineHistory {
            val row = intervalMention(interval)
            return mentionHistory(row).copy(endOrdinal = interval.toExclusive?.order?.let { maxOf(interval.from.order, it - 1) })
        }
        val states = point?.let { KgNarrativeTimeline.at(timeline, it.ordinal, historyLimit) }.orEmpty().map { state ->
            val row = intervalMention(state.interval)
            KgTimelineCurrent(state.edgeId, edgeNames[state.edgeId].orEmpty(), state.interval.from.order,
                state.history.map(::intervalHistory), state.flashbacks.map(::mentionHistory), row.docId, row.windowIndex,
                row.charStart, row.charEnd, row.quote, row.positionCoarse)
        }
        val events = timeline.events.sortedByDescending { it.position }.map(::mentionHistory)
        val flashbacks = timeline.flashbacks.sortedByDescending { it.position }.map(::mentionHistory)
        val edgeIds = database.query(SimpleSQLiteQuery(
            "SELECT e.id FROM kg_edges e WHERE e.collection_id=? AND EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id=e.id AND $ACTIVE_KG_EVIDENCE)",
            arrayOf(collectionId),
        )).use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }
        KgTimelineSnapshot(point?.docId, point?.ordinal, states, events, flashbacks, edgeIds - known, known)
    }

    private suspend fun loadMentions(collectionId: String, articles: List<KgCollectionArticle>): List<KgNarrativeMention> {
        val order = articles.associate { it.docId to it.ordinal }
        val canonical = KgEntityResolutionStore(database).canonicalIds(collectionId)
        return database.query(SimpleSQLiteQuery(
            "SELECT m.edge_id,e.source_id,e.target_id,m.kind,m.family,m.doc_id,m.window_index,m.char_start,m.flashback,m.char_end,m.quote,m.position_coarse " +
                "FROM kg_edge_mentions m JOIN kg_edges e ON e.id=m.edge_id " +
                "JOIN kg_evidence p ON p.id=m.evidence_id AND p.edge_id=m.edge_id AND p.doc_id=m.doc_id " +
                "AND p.task_id=m.task_id AND p.content_hash=m.content_hash " +
                "WHERE e.collection_id=? AND $ACTIVE_KG_EVIDENCE",
            arrayOf(collectionId),
        )).use { c -> buildList {
            while (c.moveToNext()) {
                val docId = c.getString(5)
                val ordinal = order[docId] ?: continue
                val source = c.getString(1)
                val target = c.getString(2)
                add(KgNarrativeMention(c.getString(0), collectionId, canonical[source] ?: source, canonical[target] ?: target, c.getString(3),
                    if (c.isNull(4)) null else c.getString(4), docId, ordinal, c.getInt(6), c.getInt(7), c.getInt(8) != 0,
                    c.getInt(9), if (c.isNull(10)) null else c.getString(10), c.getInt(11) != 0))
            }
        } }
    }
}
