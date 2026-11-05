package com.promenar.infinity.data.rag

import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.FileEntry
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KgBatchDocument(
    val docId: String,
    val title: String,
    val workspaceRootUuid: String,
    val contentHash: String,
    val targetEpoch: Long,
    val alreadyExtracted: Boolean,
    val estimate: KgExtractionEstimate,
)
data class KgBatchEstimate(
    val documents: List<KgBatchDocument>,
    val documentCount: Int,
    val skippedCount: Int,
    val windows: Long,
    val calls: Long,
    val inputTokens: Long,
    val outputTokenLimit: Long,
    val budgetedCalls: Long,
    val onlyUnextracted: Boolean,
)

object KgBatchPlanner {
    fun aggregate(rows: List<KgBatchDocument>, onlyUnextracted: Boolean = true): KgBatchEstimate {
        val unique = rows.distinctBy { it.docId }
        val selected = unique.filter { !onlyUnextracted || !it.alreadyExtracted }
        return KgBatchEstimate(selected, selected.size, unique.size - selected.size,
            selected.sumOf { it.estimate.windows.toLong() }, selected.sumOf { it.estimate.calls.toLong() },
            selected.sumOf { it.estimate.inputTokens }, selected.sumOf { it.estimate.outputTokenLimit },
            selected.sumOf { it.estimate.budgetedCalls.toLong() }, onlyUnextracted)
    }
}

/** 确认前冻结每篇内容版本；确认后通过现有文档索引队列提交。 */
class KgBatchExtraction(
    private val database: InfinityDatabase,
    private val extractor: GraphExtractor,
    private val queue: VectorizationQueue,
    private val config: RagConfiguration,
) {
    suspend fun estimateDocuments(docIds: Collection<String>, onlyUnextracted: Boolean = true): KgBatchEstimate = withContext(Dispatchers.IO) {
        val zero = KgExtractionEstimate(0, 0, 0, 0, false, 0)
        val rows = docIds.distinct().map { id ->
            val file = requireNotNull(database.fileEntryDao().findActiveByUuid(id)) { "文档不存在" }
            require(!file.isDirectory && file.mimeType in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES) { "文档格式不支持抽取" }
            val extracted = file.kgExtractedAt?.let { it >= file.updatedAt } == true
            val estimate = if (onlyUnextracted && extracted) zero else {
                val path = checkedPath(file)
                require(hash(path) == file.hash) { "估算前文档内容已变化" }
                val text = DocumentReferenceExtractor(config.docChunkSize, config.chunkOverlap).extract(file).graphText
                require(hash(path) == file.hash) { "估算期间文档内容已变化" }
                extractor.estimate(text)
            }
            KgBatchDocument(file.uuid, file.name, file.workspaceRootUuid, file.hash, file.updatedAt, extracted, estimate)
        }
        KgBatchPlanner.aggregate(rows, onlyUnextracted)
    }

    suspend fun estimateCollection(collectionId: String, onlyUnextracted: Boolean = true): KgBatchEstimate {
        val ids = withContext(Dispatchers.IO) {
            database.query(SimpleSQLiteQuery("SELECT uuid FROM workspace_files WHERE in_recycle_bin=0 AND is_directory=0 ORDER BY uuid")).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            }
        }
        val collections = KgCollectionRepository(database)
        val selected = mutableListOf<String>()
        for (id in ids) {
            val file = database.fileEntryDao().findActiveByUuid(id) ?: continue
            if (file.mimeType in DocumentReferenceExtractor.SUPPORTED_MIME_TYPES && collections.collectionForDocument(id) == collectionId) selected += id
        }
        return estimateDocuments(selected, onlyUnextracted)
    }

    suspend fun enqueue(estimate: KgBatchEstimate): List<String> = withContext(Dispatchers.IO) {
        val files = estimate.documents.map { item ->
            val file = requireNotNull(database.fileEntryDao().findActiveByUuid(item.docId)) { "确认后文档已删除" }
            require(file.workspaceRootUuid == item.workspaceRootUuid && file.hash == item.contentHash && file.updatedAt == item.targetEpoch &&
                !file.isDirectory && hash(checkedPath(file)) == item.contentHash) { "确认后文档已变化，请重新估算" }
            file
        }
        val submitted = mutableListOf<String>()
        files.forEach { file ->
            if (estimate.onlyUnextracted && file.kgExtractedAt?.let { it >= file.updatedAt } == true) return@forEach
            submitted += queue.enqueueDocumentReference(file.workspaceRootUuid, file.uuid, file.name,
                requireNotNull(file.mimeType), file.hash, file.updatedAt, KgExtractionBudget.CONFIRMED_STRATEGY, skipVectorization = true)
        }
        submitted
    }

    private fun checkedPath(entry: FileEntry): File {
        val root = File(entry.physicalRootPath).canonicalFile
        val file = File(root, entry.materializedPath.trimStart('/')).canonicalFile
        require(file.path.startsWith(root.path + File.separator) && file.isFile) { "文档不在工作区内" }
        return file
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
