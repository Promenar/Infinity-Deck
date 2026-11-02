package com.promenar.infinity.data.repository

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.dao.AgentRuleRevisionDao
import com.promenar.infinity.data.local.db.entity.AgentRuleRevisionEntity
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID
import com.promenar.infinity.data.local.db.dao.AgentDao
import com.promenar.infinity.data.mapper.AgentMapper
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField
import com.promenar.infinity.domain.model.AgentFieldUpdate
import com.promenar.infinity.domain.model.AgentPromptRevision
import com.promenar.infinity.domain.repository.IAgentRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class AgentRepository(
    private val agentDao: AgentDao,
    private val revisionDao: AgentRuleRevisionDao? = null,
    private val database: RoomDatabase? = null,
) : IAgentRepository {
    private val fallbackLock = Mutex()

    private suspend fun <T> transaction(block: suspend () -> T): T =
        if (database != null) database.withTransaction { block() } else fallbackLock.withLock { block() }

    override fun observeAll(): Flow<List<Agent>> =
        agentDao.observeAll().map { list -> list.map { AgentMapper.toDomain(it) } }

    override fun observeById(id: String): Flow<Agent?> =
        agentDao.observeAll().map { list -> list.find { it.id == id }?.let { AgentMapper.toDomain(it) } }

    override suspend fun create(agent: Agent) {
        create(agent, null)
    }

    /** 工具新建时显式记录提示词来源；普通创建沿用无历史的行为。 */
    suspend fun create(agent: Agent, promptSource: AgentPromptRevision.Source?) = transaction {
        agentDao.insert(AgentMapper.toEntity(agent))
        if (promptSource != null && agent.systemPrompt.isNotEmpty()) recordRevision(agent.id, agent.systemPrompt, promptSource)
        Unit
    }

    override suspend fun update(agent: Agent) {
        agentDao.update(AgentMapper.toEntity(agent))
    }

    override suspend fun delete(id: String) {
        agentDao.deleteWithRuleRevisions(id)
    }

    override suspend fun getById(id: String): Agent? =
        agentDao.getById(id)?.let { AgentMapper.toDomain(it) }

    /**
     * 只写 [fields] 列出的列，取值来自 [values]；其余列保持数据库现值。读、比较、写与提示词版本记录在同一事务内完成。
     *
     * - [expected] 非空时，先比较 [fields] 中各字段的数据库现值与 [expected]；任一不同返回 [AgentFieldUpdate.Conflict]，不写入。
     * - [fields] 含 [AgentField.SYSTEM_PROMPT] 且新提示词与现值不同时：该 Agent 还没有历史版本则先把现值记为基线（来源 USER），
     *   再按 [promptSource] 记录新内容；内容 SHA-256 与最新一版相同时不重复记录；只保留最近 20 版。
     */
    suspend fun updateFields(
        id: String,
        fields: Set<AgentField>,
        values: Agent,
        expected: Agent? = null,
        promptSource: AgentPromptRevision.Source = AgentPromptRevision.Source.USER,
    ): AgentFieldUpdate = transaction {
        updateFieldsInTransaction(id, fields, values, expected, promptSource)
    }

    private suspend fun updateFieldsInTransaction(
        id: String, fields: Set<AgentField>, values: Agent, expected: Agent?, promptSource: AgentPromptRevision.Source,
    ): AgentFieldUpdate {
        val current = getById(id) ?: return AgentFieldUpdate.NotFound
        val conflicts = if (expected == null) emptySet() else fields.filterTo(linkedSetOf()) { it.read(current) != it.read(expected) }
        if (conflicts.isNotEmpty()) return AgentFieldUpdate.Conflict(current, conflicts)
        val updated = fields.fold(current) { result, field -> field.copy(result, values) }
        agentDao.update(AgentMapper.toEntity(updated))
        if (updated.systemPrompt != current.systemPrompt) {
            if (revisionDao?.latestForAgent(id) == null) recordRevision(id, current.systemPrompt, AgentPromptRevision.Source.USER)
            recordRevision(id, updated.systemPrompt, promptSource)
        }
        return AgentFieldUpdate.Updated(updated)
    }

    /** 该 Agent 的提示词历史，最新在前。 */
    fun observePromptRevisions(agentId: String): Flow<List<AgentPromptRevision>> =
        revisionDao?.observeForAgent(agentId)?.map { list -> list.map { it.toDomain() } } ?: flowOf(emptyList())

    /**
     * 只记录一个历史版本，不改当前提示词（编辑页冲突时保存被放弃的一方）。
     * 内容 SHA-256 与最新一版相同时不记录并返回 false。
     */
    suspend fun recordPromptRevision(agentId: String, content: String, source: AgentPromptRevision.Source): Boolean =
        transaction { getById(agentId) != null && recordRevision(agentId, content, source) }

    /** 把提示词恢复为 [revisionId] 的内容，并以来源 ROLLBACK 记一版；版本不存在或不属于该 Agent 时返回 NotFound。 */
    suspend fun restorePromptRevision(agentId: String, revisionId: String): AgentFieldUpdate = transaction {
        val revision = revisionDao?.getById(revisionId)?.takeIf { it.agentId == agentId }
            ?: return@transaction AgentFieldUpdate.NotFound
        val current = getById(agentId) ?: return@transaction AgentFieldUpdate.NotFound
        updateFieldsInTransaction(agentId, setOf(AgentField.SYSTEM_PROMPT), current.copy(systemPrompt = revision.content),
            current, AgentPromptRevision.Source.ROLLBACK)
    }

    private suspend fun recordRevision(agentId: String, content: String, source: AgentPromptRevision.Source): Boolean {
        val dao = revisionDao ?: return false
        val latest = dao.latestForAgent(agentId)
        val hash = MessageDigest.getInstance("SHA-256").digest(content.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        if (latest?.contentSha256 == hash) return false
        // 同一毫秒连续保存时仍按写入顺序展示，并保证裁剪保留最后写入的内容。
        val timestamp = maxOf(System.currentTimeMillis(), (latest?.createdAt ?: 0L) + 1L)
        dao.record(AgentRuleRevisionEntity(UUID.randomUUID().toString(), agentId, content, hash, source.storageKey, timestamp))
        return true
    }

    private fun AgentRuleRevisionEntity.toDomain() =
        AgentPromptRevision(id, agentId, content, AgentPromptRevision.Source.fromStorage(source), createdAt)
}
