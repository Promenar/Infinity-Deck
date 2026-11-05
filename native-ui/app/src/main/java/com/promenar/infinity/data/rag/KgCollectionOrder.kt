package com.promenar.infinity.data.rag

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import java.math.BigInteger

data class KgCollectionArticle(
    val docId: String,
    val title: String,
    val orderKey: String?,
    val orderSource: String?,
    val ordinal: Int,
)

/** 篇序只在显式写入路径调整，读取不修复数据库。 */
internal class KgCollectionOrder(private val database: InfinityDatabase) {
    private val step = BigInteger("1000000000000")

    suspend fun list(collectionId: String): List<KgCollectionArticle> = database.withTransaction { rows(collectionId) }

    suspend fun refresh(collectionId: String) = database.withTransaction {
        discovered(collectionId).forEach { entry ->
            database.openHelper.writableDatabase.execSQL(
                "INSERT OR IGNORE INTO kg_collection_members(doc_id,collection_id,order_key,order_source) VALUES(?,?,NULL,NULL)",
                arrayOf(entry.docId, collectionId),
            )
        }
        val current = persistedRows(collectionId)
        if (current.isEmpty()) return@withTransaction
        if (current.none { it.orderSource == "manual" }) {
            val sorted = current.sortedWith(compareBy<KgCollectionArticle, String>(KgNaturalOrder) { it.title }.thenBy { it.docId })
            writeRanks(sorted)
        } else {
            val ordered = current.filter { it.orderKey != null }.toMutableList()
            val missing = current.filter { it.orderKey == null }
                .sortedWith(compareBy<KgCollectionArticle, String>(KgNaturalOrder) { it.title }.thenBy { it.docId })
            missing.forEach { entry ->
                val index = ordered.indexOfFirst { KgNaturalOrder.compare(entry.title, it.title) < 0 }.let { if (it < 0) ordered.size else it }
                ordered.add(index, entry)
                val left = ordered.getOrNull(index - 1)?.orderKey
                val right = ordered.getOrNull(index + 1)?.orderKey
                val rank = orderKeyBetween(left, right)
                database.openHelper.writableDatabase.execSQL("UPDATE kg_collection_members SET order_key=?,order_source='natural' WHERE doc_id=?",
                    arrayOf(rank, entry.docId))
                ordered[index] = entry.copy(orderKey = rank, orderSource = "natural")
            }
        }
    }

    suspend fun move(collectionId: String, docId: String, targetIndex: Int) = database.withTransaction {
        refresh(collectionId)
        val current = persistedRows(collectionId).toMutableList()
        val from = current.indexOfFirst { it.docId == docId }
        require(from >= 0 && targetIndex in current.indices) { "篇目或目标位置不存在" }
        current.add(targetIndex, current.removeAt(from))
        current.forEachIndexed { index, item -> writeRank(item.docId, step * BigInteger.valueOf(index + 1L), "manual") }
    }

    private suspend fun rows(collectionId: String): List<KgCollectionArticle> {
        val current = persistedRows(collectionId)
        val pending = current.filter { it.orderKey == null } + discovered(collectionId)
        val ordered = if (current.none { it.orderSource == "manual" }) {
            val ids = current.mapTo(hashSetOf()) { it.docId }
            (current + pending.filter { it.docId !in ids })
                .sortedWith(compareBy<KgCollectionArticle, String>(KgNaturalOrder) { it.title }.thenBy { it.docId })
                .mapIndexed { index, row -> row.copy(orderKey = format(step * BigInteger.valueOf(index + 1L)), orderSource = "natural") }
        } else {
            val result = current.filter { it.orderKey != null }.toMutableList()
            pending.sortedWith(compareBy<KgCollectionArticle, String>(KgNaturalOrder) { it.title }.thenBy { it.docId }).forEach { entry ->
                val index = result.indexOfFirst { KgNaturalOrder.compare(entry.title, it.title) < 0 }.let { if (it < 0) result.size else it }
                val key = orderKeyBetween(result.getOrNull(index - 1)?.orderKey, result.getOrNull(index)?.orderKey)
                result.add(index, entry.copy(orderKey = key, orderSource = "natural"))
            }
            result
        }
        return ordered.mapIndexed { index, row -> row.copy(ordinal = index + 1) }
    }

    /** 新文件按归属临时纳入读取；只有明确写入路径才补建成员。 */
    private suspend fun discovered(collectionId: String): List<KgCollectionArticle> {
        val seeds: String
        val args: Array<Any>
        if (collectionId == "shared") {
            seeds = "SELECT f.uuid FROM workspace_files f JOIN kg_folder_settings s ON s.folder_id=f.uuid " +
                "WHERE f.is_directory=1 AND f.in_recycle_bin=0 AND s.mode='SHARED'"
            args = emptyArray()
        } else if (collectionId.startsWith("work:")) {
            seeds = "SELECT uuid FROM workspace_files WHERE uuid=? AND is_directory=1 AND in_recycle_bin=0"
            val root = collectionId.removePrefix("work:")
            args = arrayOf(root, root)
        } else return emptyList()
        val rootDocument = if (collectionId == "shared") "0" else "w.uuid=?"
        val sql = "WITH RECURSIVE folders(uuid) AS ($seeds UNION SELECT child.uuid FROM workspace_files child " +
            "JOIN folders parent ON child.parent_uuid=parent.uuid JOIN kg_folder_settings s ON s.folder_id=child.uuid " +
            "WHERE child.is_directory=1 AND child.in_recycle_bin=0 AND s.mode='MERGE_PARENT') " +
            "SELECT w.uuid,w.name FROM workspace_files w WHERE w.is_directory=0 AND w.in_recycle_bin=0 " +
            "AND (w.parent_uuid IN (SELECT uuid FROM folders) OR $rootDocument) " +
            "AND NOT EXISTS (SELECT 1 FROM kg_collection_members m WHERE m.doc_id=w.uuid) ORDER BY w.uuid"
        val candidates = database.query(SimpleSQLiteQuery(sql, args)).use { c -> buildList {
            while (c.moveToNext()) add(KgCollectionArticle(c.getString(0), c.getString(1), null, null, 0))
        } }
        val repository = KgCollectionRepository(database)
        return candidates.filter { repository.collectionForDocument(it.docId) == collectionId }
    }

    private fun persistedRows(collectionId: String): List<KgCollectionArticle> = database.query(SimpleSQLiteQuery(
        "SELECT m.doc_id,COALESCE(w.name,m.doc_id),m.order_key,m.order_source FROM kg_collection_members m " +
            "LEFT JOIN workspace_files w ON w.uuid=m.doc_id WHERE m.collection_id=? ORDER BY m.order_key IS NULL,m.order_key,m.doc_id",
        arrayOf(collectionId),
    )).use { cursor -> buildList {
        while (cursor.moveToNext()) add(KgCollectionArticle(cursor.getString(0), cursor.getString(1),
            if (cursor.isNull(2)) null else cursor.getString(2), if (cursor.isNull(3)) null else cursor.getString(3), size + 1))
    } }

    private fun writeRanks(rows: List<KgCollectionArticle>) {
        rows.forEachIndexed { index, row -> writeRank(row.docId, step * BigInteger.valueOf(index + 1L), row.orderSource ?: "natural") }
    }

    private fun writeRank(docId: String, rank: BigInteger, source: String) {
        database.openHelper.writableDatabase.execSQL("UPDATE kg_collection_members SET order_key=?,order_source=? WHERE doc_id=?",
            arrayOf(format(rank), source, docId))
    }

    private fun format(rank: BigInteger): String = rank.toString().padStart(30, '0')
}

/** 数字字符串按字典序插入，延长尾数也不会改动两侧已有的手动值。 */
internal fun orderKeyBetween(left: String?, right: String?): String {
    require(left == null || left.isNotEmpty() && left.all { it in '0'..'9' })
    require(right == null || right.isNotEmpty() && right.all { it in '0'..'9' })
    require(left == null || right == null || left < right)
    val result = StringBuilder()
    var upper = right
    var index = 0
    while (index < 4096) {
        val lowerDigit = left?.getOrNull(index)?.digitToInt() ?: 0
        val upperDigit = upper?.getOrNull(index)?.digitToInt() ?: if (upper == null) 10 else 0
        require(lowerDigit <= upperDigit) { "篇目顺序值无法插入，请手动调整排序" }
        when {
            upperDigit - lowerDigit > 1 -> {
                result.append(('0'.code + (lowerDigit + upperDigit) / 2).toChar())
                return result.toString()
            }
            upperDigit == lowerDigit + 1 -> { result.append(('0'.code + lowerDigit).toChar()); upper = null }
            else -> result.append(('0'.code + lowerDigit).toChar())
        }
        index++
    }
    error("篇目顺序值过长，请手动调整排序")
}
