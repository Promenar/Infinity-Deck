package com.promenar.infinity.data.knowledge

import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.ConversationReach
import com.promenar.infinity.data.rag.DocumentReach
import com.promenar.infinity.data.rag.RetrievalScopeResolver
import com.promenar.infinity.data.rag.documentTitleFromVectorMetadata
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.workspace.WorkspaceDefaultFileDestination
import com.promenar.infinity.data.workspace.workspaceFilePathPresenter
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 只查询当前数据库元数据与有效向量，不创建工作区、不触发索引。 */
class RoomKnowledgeCatalog(
    private val database: InfinityDatabase,
    private val externalAccess: ExternalWorkspaceAccess,
) : KnowledgeCatalogSource {
    private val resolver = RetrievalScopeResolver(database)

    override suspend fun knowledgeBases(session: Session, options: RagOptions): List<KnowledgeBaseSummary> = withContext(Dispatchers.IO) {
        if (!options.enableDocs || options.effectiveDocumentScope == DocumentScope.WORKSPACE) return@withContext emptyList()
        val policy = database.agentDao().getById(session.agentId)?.retrievalConfig?.memoryPolicy
        if (policy?.allowedCollectionIds != null || options.selectedCollectionIds != null) return@withContext emptyList()
        val reach = resolver.resolve(session.id, options).documents
        val documents = indexedDocuments(reach)
        roots().filter { root ->
            reach == DocumentReach.All || (documents.any { it.rootUuid == root.uuid } && database.query(SimpleSQLiteQuery(
                "SELECT uuid FROM workspace_files WHERE workspace_root_uuid=? AND is_directory=0 AND in_recycle_bin=0", arrayOf(root.uuid),
            )).use { cursor ->
                val visible = (reach as DocumentReach.Scoped).documentIds
                var allowed = true
                while (cursor.moveToNext()) if (cursor.getString(0) !in visible) allowed = false
                allowed
            })
        }
            .map { root -> KnowledgeBaseSummary(root.uuid, root.title, documents.count { it.rootUuid == root.uuid }, root.isDefault) }
    }

    override suspend fun snapshot(session: Session, options: RagOptions): KnowledgeCatalogSnapshot = withContext(Dispatchers.IO) {
        val scope = resolver.resolve(session.id, options)
        val documents = if (options.enableDocs) indexedDocuments(scope.documents) else emptyList()
        val rootNames = rootNames()
        val knowledgeBases = roots().associateBy { it.uuid }
        val mounts = externalAccess.visible(session.id)
        val groups = documents.groupBy { it.rootUuid }.map { (uuid, entries) ->
            val knowledgeBase = knowledgeBases[uuid]
            KnowledgeCatalogGroup(
                rootUuid = uuid,
                title = knowledgeBase?.title ?: rootNames[uuid] ?: "其它已索引文档",
                documentCount = entries.size,
                knowledgeBase = knowledgeBase != null,
                mounted = knowledgeBase != null && mounts.any {
                    it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE && it.sourceId == uuid && it.readOnly
                },
            )
        }.sortedWith(compareByDescending<KnowledgeCatalogGroup> { it.knowledgeBase }.thenBy { it.title }.thenBy { it.rootUuid })
        val history = if (options.enableMemory) {
            val stats = resolver.stats(scope.memory)
            KnowledgeCatalogHistory(options.effectiveMemoryScope, stats.sessionCount, indexedHistorySessionCount(scope.memory))
        } else null
        KnowledgeCatalogSnapshot(groups, documents.map { KnowledgeCatalogDocument(it.id, it.title, it.updatedAt) }, history)
    }

    /** 与向量检索使用相同范围和有效性条件；只做 EXISTS，不读取 embedding BLOB。 */
    override fun hasIndexedMaterial(session: Session, options: RagOptions): Boolean {
        val scope = kotlinx.coroutines.runBlocking(Dispatchers.IO) { resolver.resolve(session.id, options) }
        if (options.enableDocs && indexedDocumentsExist(scope.documents)) return true
        if (!options.enableMemory) return false
        return indexedHistorySessionCount(scope.memory) > 0
    }

    override suspend fun documentLocation(sessionId: String, reference: RagReference): KnowledgeDocumentLocation? = withContext(Dispatchers.IO) {
        val documentId = reference.documentId?.takeIf(String::isNotBlank) ?: return@withContext null
        val exactVectorFile = reference.id.takeIf(String::isNotBlank)?.let { id ->
            database.query(SimpleSQLiteQuery(
                "SELECT COALESCE(file_uuid,doc_id) FROM vectors WHERE id=? AND stale=0 AND (doc_id=? OR file_uuid=?)",
                arrayOf(id, documentId, documentId),
            )).use { if (it.moveToFirst()) it.getString(0) else null }
        }
        val directFile = database.fileEntryDao().findActiveByUuid(documentId)
        val legacyFiles = if (exactVectorFile == null && directFile == null && reference.id.isBlank()) database.query(SimpleSQLiteQuery(
            "SELECT DISTINCT file_uuid FROM vectors WHERE doc_id=? AND file_uuid IS NOT NULL AND stale=0 LIMIT 2", arrayOf(documentId),
        )).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } } else emptyList()
        val fileUuid = exactVectorFile ?: directFile?.uuid ?: legacyFiles.singleOrNull() ?: documentId
        val file = database.fileEntryDao().findActiveByUuid(fileUuid)
        val title = file?.name ?: reference.source.removePrefix("文档: ").ifBlank { documentId }
        if (file == null || file.isDirectory) return@withContext KnowledgeDocumentLocation(documentId, title, KnowledgeDocumentLocationKind.SNIPPET_ONLY)
        val relativePath = runCatching { ExternalFolderPaths.normalize(file.materializedPath) }.getOrNull()
        if (relativePath == null || relativePath == "/") return@withContext KnowledgeDocumentLocation(documentId, title, KnowledgeDocumentLocationKind.SNIPPET_ONLY)
        val session = database.sessionDao().getById(sessionId)
        if (session != null && file.workspaceRootUuid == session.workspaceRootUuid) {
            val path = workspaceFilePathPresenter(SessionRunMode.fromStored(session.runMode)).present(
                WorkspaceDefaultFileDestination.SESSION_PRIVATE,
                relativePath,
                privatePrimary = session.workspaceId == null && session.worktreeDirectory == null,
            )
            return@withContext KnowledgeDocumentLocation(documentId, title, KnowledgeDocumentLocationKind.WORKSPACE, file.workspaceRootUuid, path)
        }
        val knowledgeBase = roots().firstOrNull { it.uuid == file.workspaceRootUuid }
            ?: return@withContext KnowledgeDocumentLocation(documentId, title, KnowledgeDocumentLocationKind.SNIPPET_ONLY, file.workspaceRootUuid)
        val mount = externalAccess.visible(sessionId).firstOrNull {
            it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE && it.sourceId == file.workspaceRootUuid && it.readOnly && it.unavailableReason == null
        }
        KnowledgeDocumentLocation(
            documentId, title,
            if (mount == null) KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_UNMOUNTED else KnowledgeDocumentLocationKind.KNOWLEDGE_BASE_MOUNTED,
            file.workspaceRootUuid,
            mount?.let { ExternalMountPaths.virtualPath(it, relativePath) },
            knowledgeBase.title,
        )
    }

    private fun indexedDocuments(reach: DocumentReach): List<IndexedDocument> {
        if (reach is DocumentReach.Scoped && reach.documentIds.size > 400) return reach.documentIds.chunked(400)
            .flatMap { indexedDocuments(DocumentReach.Scoped(reach.workspaceRootUuids, it.toSet())) }.distinctBy { it.id }
        val condition = documentCondition(reach)
        return database.query(SimpleSQLiteQuery(
            "SELECT MIN(v.doc_id), f.workspace_root_uuid, f.name, MAX(COALESCE(f.updated_at, v.updated_at, v.created_at)), v.metadata " +
                "FROM vectors v LEFT JOIN workspace_files f ON f.uuid = COALESCE(v.file_uuid, v.doc_id) " +
                "WHERE $ACTIVE_DOCUMENT AND ${condition.sql} GROUP BY COALESCE(f.uuid, v.doc_id)",
            condition.args.toTypedArray(),
        )).use { cursor -> buildList {
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                add(IndexedDocument(id, cursor.getString(1), cursor.getString(2) ?: documentTitleFromVectorMetadata(cursor.getString(4)) ?: id, cursor.getLong(3)))
            }
        } }
    }

    private fun indexedDocumentsExist(reach: DocumentReach): Boolean {
        if (reach is DocumentReach.Scoped && reach.documentIds.size > 400) return reach.documentIds.chunked(400)
            .any { indexedDocumentsExist(DocumentReach.Scoped(reach.workspaceRootUuids, it.toSet())) }
        val condition = documentCondition(reach)
        return database.query(SimpleSQLiteQuery(
            "SELECT 1 FROM vectors v LEFT JOIN workspace_files f ON f.uuid = COALESCE(v.file_uuid, v.doc_id) WHERE $ACTIVE_DOCUMENT AND ${condition.sql} LIMIT 1",
            condition.args.toTypedArray(),
        )).use { it.moveToFirst() }
    }

    private fun documentCondition(reach: DocumentReach): SqlCondition = when (reach) {
        DocumentReach.All -> SqlCondition("1")
        is DocumentReach.Scoped -> {
            val roots = reach.workspaceRootUuids.toList()
            val docs = reach.documentIds.toList()
            val conditions = buildList {
                if (roots.isNotEmpty()) add("(f.in_recycle_bin = 0 AND f.workspace_root_uuid IN (${placeholders(roots.size)}))")
                if (docs.isNotEmpty()) add("COALESCE(v.file_uuid,v.doc_id) IN (${placeholders(docs.size)})")
            }
            SqlCondition(if (conditions.isEmpty()) "0" else conditions.joinToString(" OR ", "(", ")"), roots + docs)
        }
    }

    private fun indexedHistorySessionCount(reach: ConversationReach): Int {
        val condition = when (reach) {
            is ConversationReach.Session -> SqlCondition("s.id = ?", listOf(reach.sessionId))
            is ConversationReach.Agent -> SqlCondition("s.agent_id = ?", listOf(reach.agentId))
            is ConversationReach.SharedAgents -> if (reach.privateAgentIds.isEmpty()) SqlCondition("1") else
                SqlCondition("(s.agent_id = ? OR s.agent_id NOT IN (${placeholders(reach.privateAgentIds.size)}))", listOf(reach.agentId) + reach.privateAgentIds)
        }
        return database.query(SimpleSQLiteQuery(
            "SELECT COUNT(DISTINCT s.id) FROM vectors v JOIN sessions s ON s.id = v.session_id " +
                "LEFT JOIN workspace_files f ON f.uuid = COALESCE(v.file_uuid, v.doc_id) " +
                "WHERE v.doc_id IS NULL AND v.stale = 0 AND $ACTIVE_FILE AND $HISTORY_TYPE AND $HISTORY_TURN_PRESENT AND ${condition.sql}",
            condition.args.toTypedArray(),
        )).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    private fun roots(): List<Root> = database.query(SimpleSQLiteQuery(
        "SELECT workspace_root_uuid, title, id FROM sessions WHERE agent_id = ? AND workspace_root_uuid IS NOT NULL AND workspace_root_uuid != '' " +
            "ORDER BY CASE WHEN id = ? THEN 0 ELSE 1 END, title, id",
        arrayOf(RetrievalScopeResolver.KNOWLEDGE_BASE_AGENT_ID, DEFAULT_KNOWLEDGE_SESSION),
    )).use { cursor -> buildList {
        val seen = hashSetOf<String>()
        while (cursor.moveToNext()) {
            val uuid = cursor.getString(0)
            if (seen.add(uuid)) add(Root(uuid, cursor.getString(1), cursor.getString(2) == DEFAULT_KNOWLEDGE_SESSION))
        }
    } }

    private fun rootNames(): Map<String, String> = database.query(SimpleSQLiteQuery(
        "SELECT s.workspace_root_uuid, COALESCE(w.display_name, root.name, '会话工作区') FROM sessions s " +
            "LEFT JOIN workspaces w ON w.id = s.workspace_id " +
            "LEFT JOIN workspace_files root ON root.uuid = s.workspace_root_uuid AND root.in_recycle_bin = 0 " +
            "WHERE s.workspace_root_uuid IS NOT NULL ORDER BY s.updated_at DESC, s.id",
    )).use { cursor -> buildMap {
        while (cursor.moveToNext()) {
            val uuid = cursor.getString(0)
            if (uuid !in this) put(uuid, cursor.getString(1))
        }
    } }

    private data class Root(val uuid: String, val title: String, val isDefault: Boolean)
    private data class IndexedDocument(val id: String, val rootUuid: String?, val title: String, val updatedAt: Long)
    private data class SqlCondition(val sql: String, val args: List<String> = emptyList())

    private companion object {
        // 与 RagWorkspaceProvisioner 的默认知识库会话身份一致。
        const val DEFAULT_KNOWLEDGE_SESSION = "__infinity_rag_workspace__"
        const val ACTIVE_FILE = "((v.file_uuid IS NOT NULL AND f.uuid IS NOT NULL AND f.in_recycle_bin = 0) OR " +
            "(v.file_uuid IS NULL AND (f.uuid IS NULL OR f.in_recycle_bin = 0)))"
        const val ACTIVE_DOCUMENT = "v.doc_id IS NOT NULL AND v.stale = 0 AND $ACTIVE_FILE"
        const val HISTORY_TYPE = "(substr(COALESCE(v.metadata, ''), 1, 16) = '{\"type\":\"memory\"' OR " +
            "substr(COALESCE(v.metadata, ''), 1, 17) = '{\"type\":\"summary\"' OR substr(COALESCE(v.metadata, ''), 1, 15) = '{\"type\":\"state\"')"
        const val HISTORY_TURN_PRESENT = "((v.start_message_id IS NULL OR EXISTS (SELECT 1 FROM messages m WHERE m.id = v.start_message_id)) " +
            "AND (v.end_message_id IS NULL OR EXISTS (SELECT 1 FROM messages m WHERE m.id = v.end_message_id)))"
        fun placeholders(size: Int): String = List(size) { "?" }.joinToString()
    }
}
