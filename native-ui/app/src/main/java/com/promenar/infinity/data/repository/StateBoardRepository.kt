package com.promenar.infinity.data.repository

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity
import com.promenar.infinity.data.state.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class StateBoardRepository(private val database: InfinityDatabase, private val now: () -> Long = System::currentTimeMillis) {
    private val dao get() = database.stateBoardDao()
    private val json = Json
    private data class GenerationScope(val owner: String, val excluded: Set<String>)
    private val generationScopes = MutableStateFlow<Map<String, GenerationScope>>(emptyMap())
    private val contextRevisions = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 512
    })

    fun beginGeneration(sessionId: String, owner: String, excluded: Set<String>) {
        generationScopes.update { it + (sessionId to GenerationScope(owner, excluded.toSet())) }
    }
    fun endGeneration(sessionId: String, owner: String) {
        generationScopes.update { if (it[sessionId]?.owner == owner) it - sessionId else it }
    }
    fun recordContextRead(sessionId: String, messageId: String, revision: Long) = synchronized(contextRevisions) {
        val key = "$sessionId:$messageId"
        contextRevisions[key] = maxOf(contextRevisions[key] ?: 0, revision)
    }
    private fun excluded(sessionId: String, explicit: Set<String>) = explicit + generationScopes.value[sessionId]?.excluded.orEmpty()

    suspend fun get(sessionId: String, excluded: Set<String> = emptySet()): StateBoardSnapshot? =
        withContext(Dispatchers.IO) { dao.latest(sessionId, excluded(sessionId, excluded).toList())?.toSnapshot() }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(sessionId: String, excluded: Set<String> = emptySet()) = generationScopes
        .map { excluded + it[sessionId]?.excluded.orEmpty() }
        .flatMapLatest { dao.observeLatest(sessionId, it.toList()) }
        .map { it?.toSnapshot() }.flowOn(Dispatchers.IO)

    suspend fun define(sessionId: String, owner: String, title: String, fields: List<StateField>, excluded: Set<String> = emptySet(), seenRevision: Long? = null): StateBoardSnapshot =
        mutate(sessionId, owner, excluded, seenRevision = seenRevision) { old -> StateBoardEngine.define(old, title, fields, now()) }

    suspend fun update(sessionId: String, owner: String, operations: List<StateOperation>, excluded: Set<String> = emptySet(), seenRevision: Long? = null): Pair<StateBoardSnapshot, List<StateChange>> {
        var changes = emptyList<StateChange>()
        val snapshot = mutate(sessionId, owner, excluded, operations.mapTo(mutableSetOf()) { it.key }, seenRevision) { old ->
            val result = StateBoardEngine.update(requireBoard(old), operations, now())
            changes = result.changes
            result.board
        }
        return snapshot to changes
    }

    suspend fun remove(sessionId: String, owner: String, excluded: Set<String> = emptySet(), seenRevision: Long? = null) =
        mutate(sessionId, owner, excluded, seenRevision = seenRevision) { null }

    suspend fun edit(sessionId: String, expectedRevision: Long, operations: List<StateOperation>, excluded: Set<String> = emptySet()): StateBoardSnapshot =
        userMutation(sessionId, expectedRevision, excluded) { old -> StateBoardEngine.update(requireBoard(old), operations, now(), user = true).board }

    suspend fun removeByUser(sessionId: String, expectedRevision: Long, excluded: Set<String> = emptySet()) = userMutation(sessionId, expectedRevision, excluded) { null }

    private suspend fun userMutation(sessionId: String, expectedRevision: Long, excluded: Set<String>, transform: (StateBoard?) -> StateBoard?): StateBoardSnapshot =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val effectiveExcluded = excluded(sessionId, excluded)
                val latest = dao.latest(sessionId, effectiveExcluded.toList())
                require(latest?.id == expectedRevision) { "面板已更新，请关闭编辑后重新打开。" }
                val owner = dao.latestMessageId(sessionId, effectiveExcluded.toList())
                    ?: throw IllegalArgumentException("会话没有消息，无法保存状态。")
                val before = requireNotNull(latest).toSnapshot().board
                val after = transform(before)
                val changedKeys = after?.fields.orEmpty().filter { field -> before?.fields?.firstOrNull { it.key == field.key }?.value != field.value }.map { it.key }
                persist(sessionId, owner, "user", after, changedKeys)
            }
        }

    private suspend fun mutate(sessionId: String, owner: String, excluded: Set<String>, touchedKeys: Set<String>? = null, seenRevision: Long? = null, transform: (StateBoard?) -> StateBoard?): StateBoardSnapshot =
        withContext(Dispatchers.IO) {
            database.withTransaction {
                val message = database.messageDao().getById(owner)
                val effectiveExcluded = excluded(sessionId, excluded)
                require(message?.sessionId == sessionId && message.role == "assistant" && owner !in effectiveExcluded) { "状态更新必须绑定当前会话中有效的助手消息。" }
                val oldVersion = dao.latest(sessionId, effectiveExcluded.toList())
                val oldSnapshot = oldVersion?.toSnapshot()
                val old = oldSnapshot?.board
                val readRevision = seenRevision ?: contextRevisions["$sessionId:$owner"]
                require(oldVersion?.source != "user" || old != null || readRevision != null && oldVersion.id <= readRevision) {
                    "用户在请求后移除了面板；先 state_get 读取当前状态，在下一工具轮确认需求。"
                }
                val conflicts = old?.manualKeys.orEmpty().filter { key ->
                    (touchedKeys == null || key in touchedKeys) &&
                        (readRevision == null || (old?.manualRevisions?.get(key) ?: oldSnapshot?.revision ?: Long.MAX_VALUE) > readRevision)
                }
                require(conflicts.isEmpty()) { "用户在请求后修改了 ${conflicts.joinToString()}；先 state_get 读取当前状态，在下一工具轮依据用户值更新。" }
                persist(sessionId, owner, "assistant", transform(old))
            }
        }

    private suspend fun persist(sessionId: String, owner: String, source: String, board: StateBoard?, manualChangedKeys: List<String> = emptyList()): StateBoardSnapshot {
        val id = dao.insert(StateBoardVersionEntity(sessionId = sessionId, anchorMessageId = owner, source = source, snapshot = board?.let { json.encodeToString(it) }, createdAt = now()))
        val stored = board?.copy(manualRevisions = board.manualRevisions + manualChangedKeys.associateWith { id })
        if (stored != board) dao.updateSnapshot(id, stored?.let { json.encodeToString(it) })
        // 同一消息的中间状态不构成独立回滚点；每个消息始终保留最终快照。
        dao.compact(sessionId, owner, MAX_VERSIONS_PER_MESSAGE)
        return StateBoardSnapshot(id, stored)
    }

    suspend fun captureDeletion(sessionId: String, ids: Set<String>, delete: suspend () -> Unit): List<StateBoardVersionEntity> = withContext(Dispatchers.IO) {
        database.withTransaction {
            val versions = dao.versions(sessionId).filter { it.anchorMessageId in ids }
            delete()
            versions
        }
    }

    suspend fun versionsForMessages(sessionId: String, ids: Set<String>) = withContext(Dispatchers.IO) {
        dao.versions(sessionId).filter { it.anchorMessageId in ids }
    }

    suspend fun restoreVersions(versions: List<StateBoardVersionEntity>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            versions.forEach { version ->
                if (database.messageDao().getById(version.anchorMessageId)?.sessionId == version.sessionId && dao.versions(version.sessionId).none { it.id == version.id }) dao.insert(version)
            }
        }
    }

    private fun StateBoardVersionEntity.toSnapshot() = StateBoardSnapshot(id, snapshot?.let { json.decodeFromString<StateBoard>(it) })
    private fun requireBoard(board: StateBoard?) = requireNotNull(board) { "当前没有状态面板，请先调用 state_define。" }
    companion object { const val MAX_VERSIONS_PER_MESSAGE = 8 }
}
