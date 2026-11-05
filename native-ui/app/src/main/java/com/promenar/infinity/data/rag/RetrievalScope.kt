package com.promenar.infinity.data.rag

import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.dao.VectorDao
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.model.ConversationScope
import com.promenar.infinity.data.model.DocumentScope
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.ActiveMemoryMode
import com.promenar.infinity.data.agent.AgentMemoryPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 对话记忆可读取的会话集合，按会话所属助手判定。 */
sealed interface ConversationReach {
    data class Session(val sessionId: String) : ConversationReach

    data class Agent(val agentId: String) : ConversationReach

    /** 本助手，以及未设为私密的其他助手。 */
    data class SharedAgents(val agentId: String, val privateAgentIds: Set<String>) : ConversationReach

    fun allows(sessionId: String?, sessionAgentId: String?): Boolean = when (this) {
        is Session -> sessionId == this.sessionId
        is Agent -> sessionAgentId == agentId
        is SharedAgents -> sessionAgentId != null && (sessionAgentId == agentId || sessionAgentId !in privateAgentIds)
    }
}

/** 文档检索可读取的范围。 */
sealed interface DocumentReach {
    data object All : DocumentReach

    /** 指定工作区根下的文件，以及显式选择（含已展开文件夹）的文档。 */
    data class Scoped(val workspaceRootUuids: Set<String>, val documentIds: Set<String>) : DocumentReach
}

/** 一个对话范围覆盖的会话数与助手数，用于在界面上说明检索可达的范围。 */
data class ConversationReachStats(val sessionCount: Int, val agentCount: Int)

data class ResolvedRetrievalScope(
    val sessionId: String,
    val memory: ConversationReach,
    val graph: ConversationReach,
    val documents: DocumentReach,
    val graphBinding: ResolvedGraphBinding,
    val activeMemoryMode: ActiveMemoryMode = ActiveMemoryMode.SHARED,
    val selectedCollectionIds: Set<String> = emptySet(),
    val graphTitle: String? = null,
    val graphRestriction: String? = null,
    val restrictedCollectionIds: Set<String> = emptySet(),
    val memoryRestriction: String? = null,
)

/**
 * 把会话检索选项解析为可执行的范围。选项应已按助手上限收紧（见 AgentMemoryPolicy.clamp）。
 * 向量和关键词检索使用对话与文档范围；图谱检索使用独立解析的绑定。
 */
class RetrievalScopeResolver(private val database: InfinityDatabase) {
    suspend fun resolve(sessionId: String, options: RagOptions): ResolvedRetrievalScope = withContext(Dispatchers.IO) {
        val session = database.sessionDao().getById(sessionId)
        if (session == null) return@withContext ResolvedRetrievalScope(
            sessionId, ConversationReach.Session(sessionId), ConversationReach.Session(sessionId),
            DocumentReach.Scoped(emptySet(), emptySet()),
            ResolvedGraphBinding(KgCollectionRepository.sessionCollectionId(sessionId), com.promenar.infinity.data.model.GraphBindingMode.READ_ONLY),
            graphRestriction = "会话不存在",
        )
        val agentId = session?.agentId
        val policy = agentId?.let { database.agentDao().getById(it)?.retrievalConfig?.memoryPolicy } ?: AgentMemoryPolicy()
        val effective = policy.clamp(options)

        suspend fun reach(scope: ConversationScope): ConversationReach = when {
            agentId == null || scope == ConversationScope.SESSION -> ConversationReach.Session(sessionId)
            scope == ConversationScope.AGENT -> ConversationReach.Agent(agentId)
            else -> ConversationReach.SharedAgents(agentId, emptySet())
        }

        val binding = KgCollectionRepository(database).readBinding(sessionId, options)
        val collectionRows = database.query(SimpleSQLiteQuery("SELECT id,title FROM kg_collections WHERE kind IN ('work','shared')")).use { cursor ->
            buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1)) }
        }
        val allowed = policy.allowedCollectionIds?.toSet()
        val selected = (options.selectedCollectionIds?.toSet() ?: collectionRows.keys).filterTo(linkedSetOf()) { allowed == null || it in allowed }
        val boundIsDocument = binding.collectionId in collectionRows
        val graphRestriction = if (boundIsDocument && allowed != null && binding.collectionId !in allowed) "受智能体资料限制" else null
        if (boundIsDocument && graphRestriction == null) selected += binding.collectionId
        val requested = explicitDocuments(options)
        val requestedFiles = requested.toMutableSet()
        requested.chunked(400).forEach { batch ->
            database.query(SimpleSQLiteQuery("SELECT DISTINCT file_uuid FROM vectors WHERE file_uuid IS NOT NULL AND stale=0 AND doc_id IN (${batch.joinToString { "?" }})", batch.toTypedArray())).use { cursor ->
                while (cursor.moveToNext()) requestedFiles += cursor.getString(0)
            }
        }
        val roots = knowledgeBaseRoots().toSet()
        val docIds = linkedSetOf<String>()
        val sql = "SELECT f.uuid,f.workspace_root_uuid,m.collection_id FROM workspace_files f LEFT JOIN kg_collection_members m ON m.doc_id=f.uuid WHERE f.in_recycle_bin=0 AND f.is_directory=0"
        val files = database.query(SimpleSQLiteQuery(sql)).use { cursor -> buildList {
            while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), if (cursor.isNull(2)) null else cursor.getString(2)))
        } }
        val collections = KgCollectionRepository(database)
        for ((id, root, member) in files) {
            val inferred = member ?: collections.documentCollectionIdForRead(id)
            val knownCollection = member != null || inferred in collectionRows || root in roots
            val collection = inferred.takeIf { knownCollection }
            if (options.selectedCollectionIds == null && collection != null && (allowed == null || collection in allowed)) selected += collection
            val collectionAllowed = if (collection == null) allowed == null || root == session.workspaceRootUuid
                else allowed == null || collection in allowed
            val requestedBySession = root == session?.workspaceRootUuid || id in requestedFiles
            val selectedByCollection = collection != null && collection in selected
            if (collectionAllowed && (requestedBySession || selectedByCollection)) docIds += id
        }
        database.query(SimpleSQLiteQuery("SELECT DISTINCT v.doc_id,m.collection_id FROM vectors v LEFT JOIN kg_collection_members m ON m.doc_id=v.doc_id WHERE v.doc_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM workspace_files f WHERE f.uuid=COALESCE(v.file_uuid,v.doc_id))")).use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val collection = if (cursor.isNull(1)) null else cursor.getString(1)
                if ((collection == null && allowed == null && (id in requested || options.selectedCollectionIds == null)) ||
                    (collection != null && collection in selected && (allowed == null || collection in allowed))) docIds += id
            }
        }
        val documents = DocumentReach.Scoped(emptySet(), docIds)
        ResolvedRetrievalScope(
            sessionId = sessionId,
            memory = reach(effective.effectiveMemoryScope),
            graph = ConversationReach.Session(sessionId),
            documents = documents,
            graphBinding = binding,
            activeMemoryMode = options.activeMemoryMode ?: policy.activeMemoryDefault,
            selectedCollectionIds = selected,
            graphTitle = collectionRows[binding.collectionId] ?: session?.title,
            graphRestriction = graphRestriction,
            restrictedCollectionIds = options.selectedCollectionIds.orEmpty().filterTo(linkedSetOf()) { allowed != null && it !in allowed },
            memoryRestriction = if (options.globalMemoryEnabled && effective.effectiveMemoryScope != options.globalMemoryScope)
                "受智能体全局记忆上限限制" else null,
        )
    }

    /** 统计 [reach] 覆盖的会话与助手，条件与 VectorDao 的会话范围一致。 */
    suspend fun stats(reach: ConversationReach): ConversationReachStats = withContext(Dispatchers.IO) {
        val (where, args) = when (reach) {
            is ConversationReach.Session -> "id = ?" to listOf(reach.sessionId)
            is ConversationReach.Agent -> "agent_id = ?" to listOf(reach.agentId)
            is ConversationReach.SharedAgents -> if (reach.privateAgentIds.isEmpty()) "1" to emptyList() else
                "(agent_id = ? OR agent_id NOT IN (${reach.privateAgentIds.joinToString { "?" }}))" to
                    listOf(reach.agentId) + reach.privateAgentIds
        }
        database.query(SimpleSQLiteQuery("SELECT COUNT(*), COUNT(DISTINCT agent_id) FROM sessions WHERE $where", args.toTypedArray()))
            .use { cursor ->
                if (cursor.moveToFirst()) ConversationReachStats(cursor.getInt(0), cursor.getInt(1)) else ConversationReachStats(0, 0)
            }
    }

    private fun knowledgeBaseRoots(): List<String> = database.query(
        SimpleSQLiteQuery(
            "SELECT DISTINCT workspace_root_uuid FROM sessions WHERE agent_id = ? AND workspace_root_uuid IS NOT NULL AND workspace_root_uuid != ''",
            arrayOf(KNOWLEDGE_BASE_AGENT_ID),
        ),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    /** 显式文档与文件夹下的全部文件；文件夹在这里展开，下游不再区分。 */
    private fun explicitDocuments(options: RagOptions): Set<String> {
        val documents = options.activeDocIds.filterTo(linkedSetOf(), String::isNotBlank)
        val folders = options.activeFolderIds.filter(String::isNotBlank).distinct()
        folders.chunked(400).forEach { batch ->
            val sql = "WITH RECURSIVE tree(id, root) AS (" +
                "SELECT uuid, workspace_root_uuid FROM workspace_files WHERE uuid IN (${batch.joinToString { "?" }}) AND in_recycle_bin = 0 " +
                "UNION SELECT w.uuid, w.workspace_root_uuid FROM workspace_files w JOIN tree t ON w.parent_uuid = t.id AND w.workspace_root_uuid = t.root WHERE w.in_recycle_bin = 0) " +
                "SELECT w.uuid FROM workspace_files w JOIN tree t ON w.uuid = t.id WHERE w.is_directory = 0"
            database.query(SimpleSQLiteQuery(sql, batch.toTypedArray())).use { cursor ->
                while (cursor.moveToNext()) documents += cursor.getString(0)
            }
        }
        return documents
    }

    companion object {
        const val KNOWLEDGE_BASE_AGENT_ID = "__system__"

        /** 显式文档以绑定参数传入 SQL，限制数量以免超过 SQLite 参数上限。 */
        const val MAX_SCOPED_DOCUMENTS = 500
    }
}

/** 按范围读取对话记忆或摘要向量（doc_id 为空的行）。 */
internal suspend fun VectorDao.conversationRows(reach: ConversationReach, type: String): List<VectorEntity> = when (reach) {
    is ConversationReach.Session -> getConversationBySession(type, reach.sessionId)
    is ConversationReach.Agent -> getConversationByAgent(type, reach.agentId)
    is ConversationReach.SharedAgents -> getConversationBySharedAgents(type, reach.agentId, reach.privateAgentIds.toList())
}

internal suspend fun VectorDao.conversationFts(reach: ConversationReach, query: String): List<VectorEntity> = when (reach) {
    is ConversationReach.Session -> searchFtsConversationBySession(query, reach.sessionId)
    is ConversationReach.Agent -> searchFtsConversationByAgent(query, reach.agentId)
    is ConversationReach.SharedAgents -> searchFtsConversationBySharedAgents(query, reach.agentId, reach.privateAgentIds.toList())
}

internal suspend fun VectorDao.documentRows(reach: DocumentReach): List<VectorEntity> = when (reach) {
    DocumentReach.All -> getAllDocuments()
    is DocumentReach.Scoped -> if (reach.isEmpty) emptyList()
        else reach.documentIds.chunked(400).ifEmpty { listOf(emptyList()) }.flatMap { getDocumentsInScope(reach.workspaceRootUuids.toList(), it) }.distinctBy { it.id }
}

internal suspend fun VectorDao.documentFts(reach: DocumentReach, query: String): List<VectorEntity> = when (reach) {
    DocumentReach.All -> searchFtsDocuments(query)
    is DocumentReach.Scoped -> if (reach.isEmpty) emptyList()
        else reach.documentIds.chunked(400).ifEmpty { listOf(emptyList()) }.flatMap { searchFtsDocumentsInScope(query, reach.workspaceRootUuids.toList(), it) }.distinctBy { it.id }
}

private val DocumentReach.Scoped.isEmpty: Boolean
    get() = workspaceRootUuids.isEmpty() && documentIds.isEmpty()
