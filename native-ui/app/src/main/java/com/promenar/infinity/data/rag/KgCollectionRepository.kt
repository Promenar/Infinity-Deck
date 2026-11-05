package com.promenar.infinity.data.rag

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.ACTIVE_KG_EVIDENCE
import com.promenar.infinity.data.model.GraphBinding
import com.promenar.infinity.data.model.GraphBindingMode
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class KgFolderSetting { AUTO, WORK, SHARED, MERGE_PARENT }

data class KgCollectionSummary(
    val id: String,
    val kind: String,
    val title: String,
    val rootRef: String?,
    val documentCount: Int,
    val nodeCount: Int,
    val edgeCount: Int,
)

data class KgDocumentCollectionInfo(val collectionId: String, val title: String, val ordinal: Int?, val documentCount: Int, val orderSource: String?)

data class ResolvedGraphBinding(val collectionId: String, val mode: GraphBindingMode) {
    val writable: Boolean get() = mode == GraphBindingMode.READ_WRITE
}

/** 图谱归属、绑定和无模型重建的统一入口。 */
class KgCollectionRepository(private val database: InfinityDatabase) {
    suspend fun collectionForDocument(docId: String): String = documentDescriptor(docId).id
    suspend fun documentInfo(docId: String): KgDocumentCollectionInfo = database.withTransaction {
        val descriptor = documentDescriptor(docId)
        val id = memberCollection(docId) ?: descriptor.id
        val title = database.query(SimpleSQLiteQuery("SELECT title FROM kg_collections WHERE id=?", arrayOf(id))).use {
            if (it.moveToFirst()) it.getString(0) else descriptor.title
        }
        val rows = KgCollectionOrder(database).list(id)
        val article = rows.firstOrNull { it.docId == docId }
        KgDocumentCollectionInfo(id, title, article?.ordinal, rows.size, article?.orderSource)
    }
    suspend fun listArticles(collectionId: String): List<KgCollectionArticle> = KgCollectionOrder(database).list(collectionId)

    suspend fun moveArticle(collectionId: String, docId: String, targetIndex: Int) = database.withTransaction {
        KgCollectionOrder(database).move(collectionId, docId, targetIndex)
        KgTimelineStore(database).recompute(collectionId)
    }

    suspend fun refreshNaturalOrder(collectionId: String) = database.withTransaction {
        KgCollectionOrder(database).refresh(collectionId)
        KgTimelineStore(database).recompute(collectionId)
    }

    suspend fun collectionIdsForDocuments(docIds: Collection<String>): List<String> = database.withTransaction {
        docIds.distinct().chunked(400).flatMap { chunk ->
            val sql = "SELECT DISTINCT collection_id FROM kg_collection_members WHERE doc_id IN (${chunk.joinToString { "?" }})"
            database.query(SimpleSQLiteQuery(sql, chunk.toTypedArray())).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        }.distinct().sorted()
    }

    suspend fun listBindableCollections(sessionId: String): List<KgCollectionSummary> = database.withTransaction {
        ensureShared()
        val own = sessionCollectionId(sessionId)
        val sql = """SELECT c.id,c.kind,c.title,c.root_ref,
            (SELECT COUNT(*) FROM kg_collection_members m WHERE m.collection_id=c.id),
            (SELECT COUNT(*) FROM kg_nodes n WHERE n.collection_id=c.id AND
                (EXISTS (SELECT 1 FROM kg_evidence p WHERE p.node_id=n.id AND $ACTIVE_KG_EVIDENCE) OR
                 EXISTS (SELECT 1 FROM kg_edges e JOIN kg_evidence p ON p.edge_id=e.id
                     WHERE (e.source_id=n.id OR e.target_id=n.id) AND $ACTIVE_KG_EVIDENCE))),
            (SELECT COUNT(*) FROM kg_edges e WHERE e.collection_id=c.id AND EXISTS (SELECT 1 FROM kg_evidence p WHERE p.edge_id=e.id AND $ACTIVE_KG_EVIDENCE))
            FROM kg_collections c WHERE c.kind IN ('work','shared') OR c.id=? ORDER BY c.kind,c.title,c.id""".trimIndent()
        val rows = database.query(SimpleSQLiteQuery(sql, arrayOf(own))).use { c ->
            buildList { while (c.moveToNext()) add(KgCollectionSummary(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3), c.getInt(4), c.getInt(5), c.getInt(6))) }
        }
        if (rows.any { it.id == own }) rows else {
            val title = database.sessionDao().getById(sessionId)?.title ?: sessionId
            rows + KgCollectionSummary(own, "session", title, null, 0, 0, 0)
        }
    }

    suspend fun readBinding(sessionId: String, options: RagOptions? = null): ResolvedGraphBinding {
        val stored = if (options != null) options.graphBinding else database.sessionDao().getById(sessionId)?.ragOptions?.let { raw ->
            runCatching {
                val binding = json.parseToJsonElement(raw).jsonObject["graphBinding"]
                if (binding == null || binding == kotlinx.serialization.json.JsonNull) null
                else json.decodeFromJsonElement(GraphBinding.serializer(), binding)
            }.getOrElse { GraphBinding(mode = GraphBindingMode.READ_ONLY) }
        }
        val own = sessionCollectionId(sessionId)
        val requested = stored?.collectionId?.takeIf { it.isNotBlank() }
        val resolved = if (requested == null || requested == own) own else {
            val kind = collectionKind(requested)
            if (kind == "work" || kind == "shared") requested else own
        }
        return ResolvedGraphBinding(resolved, stored?.mode ?: GraphBindingMode.READ_WRITE)
    }

    /** 用于非当前会话或测试；当前会话应通过 ChatViewModel.updateRagOptions 更新整份选项，以同步内存缓存。 */
    suspend fun setBinding(sessionId: String, binding: GraphBinding) = database.withTransaction {
        val session = requireNotNull(database.sessionDao().getById(sessionId)) { "会话不存在" }
        val id = binding.collectionId?.takeIf { it.isNotBlank() }
        if (id == "shared") ensureShared()
        if (id != null && id != sessionCollectionId(sessionId)) {
            require(collectionKind(id) in setOf("work", "shared")) { "只能绑定作品图谱、通用知识图谱或本会话图谱" }
        }
        val existing = session.ragOptions?.let { json.parseToJsonElement(it).jsonObject } ?: JsonObject(emptyMap())
        val updated = JsonObject(existing + ("graphBinding" to json.encodeToJsonElement(GraphBinding.serializer(), binding)))
        database.sessionDao().updateOptions(sessionId, session.options, updated.toString(), System.currentTimeMillis())
    }

    suspend fun readFolderSetting(folderId: String): KgFolderSetting {
        val value = database.kgNodeDao().folderSetting(folderId)
        return value?.let { runCatching { KgFolderSetting.valueOf(it) }.getOrNull() } ?: KgFolderSetting.AUTO
    }

    suspend fun setFolderSetting(folderId: String, setting: KgFolderSetting) = database.withTransaction {
        val folder = database.fileEntryDao().findActiveByUuid(folderId)
        require(folder?.isDirectory == true) { "文件夹不存在" }
        val sql = database.openHelper.writableDatabase
        if (setting == KgFolderSetting.AUTO) sql.execSQL("DELETE FROM kg_folder_settings WHERE folder_id=?", arrayOf(folderId))
        else sql.execSQL("INSERT OR REPLACE INTO kg_folder_settings(folder_id,mode) VALUES(?,?)", arrayOf(folderId, setting.name))
        reconcileDocuments()
    }

    /** 只撤回本会话在指定图谱中写入的会话与即时证据。 */
    suspend fun revokeSessionEvidence(sessionId: String, collectionId: String): Int = database.withTransaction {
        val evidence = database.kgEvidenceDao()
        val rows = evidence.evidenceForSessionInCollection(sessionId, collectionId)
        rows.filter { it.sourceType == "jit" }.map { it.taskId }.distinct().forEach {
            database.openHelper.writableDatabase.execSQL("DELETE FROM kg_jit_cache WHERE cache_key=?", arrayOf(it))
        }
        rows.map { it.id }.chunked(400).forEach { evidence.deleteByIds(it) }
        evidence.collectEdges(); evidence.collectNodes(); collectEmptyCollections()
        KgTimelineStore(database).recompute(collectionId)
        rows.size
    }

    /** 校正物化成员与文件树；每批最多读取 400 个文档 ID。 */
    suspend fun reconcileDocuments() = database.withTransaction {
        var after = ""
        while (true) {
            val docs = database.query(SimpleSQLiteQuery("SELECT doc_id FROM kg_collection_members WHERE doc_id>? ORDER BY doc_id LIMIT 400", arrayOf(after))).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }
            }
            if (docs.isEmpty()) break
            docs.forEach { reconcileDocument(it) }
            after = docs.last()
            if (docs.size < 400) break
        }
        database.openHelper.writableDatabase.execSQL("DELETE FROM kg_folder_settings WHERE NOT EXISTS (SELECT 1 FROM workspace_files f WHERE f.uuid=kg_folder_settings.folder_id AND f.is_directory=1)")
        collectEmptyCollections()
    }

    internal suspend fun resolveDocumentForWrite(docId: String): String = database.withTransaction {
        val descriptor = documentDescriptor(docId)
        val current = memberCollection(docId)
        if (current != null && current != descriptor.id) relocateDocument(docId, descriptor)
        ensureCollection(descriptor)
        database.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO kg_collection_members(doc_id,collection_id,order_key,order_source) VALUES(?,?,NULL,NULL)", arrayOf(docId, descriptor.id))
        database.openHelper.writableDatabase.execSQL("UPDATE kg_collection_members SET collection_id=? WHERE doc_id=?", arrayOf(descriptor.id, docId))
        KgCollectionOrder(database).refresh(descriptor.id)
        descriptor.id
    }

    internal suspend fun ensureSessionCollectionIfNeeded(sessionId: String, collectionId: String) = database.withTransaction {
        if (collectionId != sessionCollectionId(sessionId)) return@withTransaction
        val title = database.sessionDao().getById(sessionId)?.title ?: sessionId
        ensureCollection(Descriptor(collectionId, "session", title, null))
    }

    internal suspend fun collectionExists(id: String): Boolean = collectionKind(id) != null

    private suspend fun collectionKind(id: String): String? = database.kgNodeDao().collectionKind(id)

    private fun ensureShared() = ensureCollection(Descriptor("shared", "shared", "通用知识", null))

    private suspend fun reconcileDocument(docId: String) {
        val file = database.fileEntryDao().findActiveByUuid(docId)
        if (file == null || file.isDirectory) {
            val knownWorkspace = database.query(SimpleSQLiteQuery("SELECT 1 FROM workspace_files WHERE uuid=? UNION SELECT 1 FROM kg_evidence WHERE doc_id=? AND workspace_document=1 LIMIT 1", arrayOf(docId, docId))).use { it.moveToFirst() }
            if (knownWorkspace) {
                GraphStore(database).clearGraphForDoc(docId)
                database.openHelper.writableDatabase.execSQL("DELETE FROM kg_collection_members WHERE doc_id=?", arrayOf(docId))
                return
            }
        }
        val descriptor = documentDescriptor(docId)
        if (memberCollection(docId) != descriptor.id) relocateDocument(docId, descriptor)
    }

    private suspend fun relocateDocument(docId: String, target: Descriptor) {
        val previousCollection = memberCollection(docId)
        ensureCollection(target)
        val evidence = database.kgEvidenceDao()
        val rows = evidence.relocatableDocumentEvidence(docId, target.id)
        val store = GraphStore(database)
        rows.forEach { store.relocateEvidence(it, target.id) }
        rows.map { it.id }.chunked(400).forEach { evidence.deleteByIds(it) }
        database.openHelper.writableDatabase.execSQL("UPDATE kg_collection_members SET collection_id=?,order_key=NULL,order_source=NULL WHERE doc_id=?", arrayOf(target.id, docId))
        evidence.collectEdges(); evidence.collectNodes()
        previousCollection?.let { KgCollectionOrder(database).refresh(it); KgTimelineStore(database).recompute(it) }
        KgCollectionOrder(database).refresh(target.id)
        KgTimelineStore(database).recompute(target.id)
    }

    private fun memberCollection(docId: String): String? = database.query(SimpleSQLiteQuery("SELECT collection_id FROM kg_collection_members WHERE doc_id=?", arrayOf(docId))).use { if (it.moveToFirst()) it.getString(0) else null }

    private suspend fun documentDescriptor(docId: String): Descriptor {
        val file = database.fileEntryDao().findActiveByUuid(docId)?.takeUnless { it.isDirectory }
            ?: return Descriptor("work:$docId", "work", docId, docId)
        var folder = file.parentUuid?.let { database.fileEntryDao().findActiveByUuid(it) }
            ?.takeIf { it.isDirectory && it.uuid != file.workspaceRootUuid }
            ?: return Descriptor("work:$docId", "work", file.name, docId)
        val visited = mutableSetOf<String>()
        while (readFolderSetting(folder.uuid) == KgFolderSetting.MERGE_PARENT && visited.add(folder.uuid)) {
            val parent = folder.parentUuid?.let { database.fileEntryDao().findActiveByUuid(it) }
                ?.takeIf { it.isDirectory && it.workspaceRootUuid == file.workspaceRootUuid && it.uuid != file.workspaceRootUuid }
                ?: break
            folder = parent
        }
        return if (readFolderSetting(folder.uuid) == KgFolderSetting.SHARED) Descriptor("shared", "shared", "通用知识", null)
            else Descriptor("work:${folder.uuid}", "work", folder.name, folder.uuid)
    }

    private fun ensureCollection(descriptor: Descriptor) {
        database.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO kg_collections(id,kind,title,root_ref,created_at) VALUES(?,?,?,?,?)", arrayOf<Any?>(descriptor.id, descriptor.kind, descriptor.title, descriptor.rootRef, System.currentTimeMillis()))
    }

    private fun collectEmptyCollections() {
        database.openHelper.writableDatabase.execSQL("DELETE FROM kg_collections WHERE id!='shared' AND NOT EXISTS (SELECT 1 FROM kg_nodes n WHERE n.collection_id=kg_collections.id) AND NOT EXISTS (SELECT 1 FROM kg_edges e WHERE e.collection_id=kg_collections.id) AND NOT EXISTS (SELECT 1 FROM kg_collection_members m WHERE m.collection_id=kg_collections.id)")
    }

    private data class Descriptor(val id: String, val kind: String, val title: String, val rootRef: String?)

    companion object {
        fun sessionCollectionId(sessionId: String) = "session:$sessionId"
    }
}
