package com.promenar.infinity.data.repository

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
    private val agentDao: AgentDao
) : IAgentRepository {
    override fun observeAll(): Flow<List<Agent>> =
        agentDao.observeAll().map { list -> list.map { AgentMapper.toDomain(it) } }

    override fun observeById(id: String): Flow<Agent?> =
        agentDao.observeAll().map { list -> list.find { it.id == id }?.let { AgentMapper.toDomain(it) } }

    override suspend fun create(agent: Agent) {
        agentDao.insert(AgentMapper.toEntity(agent))
    }

    override suspend fun update(agent: Agent) {
        agentDao.updatePreservingUserRules(AgentMapper.toEntity(agent))
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
    ): AgentFieldUpdate = TODO("波次 A 实现")

    /** 该 Agent 的提示词历史，最新在前。 */
    fun observePromptRevisions(agentId: String): Flow<List<AgentPromptRevision>> = TODO("波次 A 实现")

    /**
     * 只记录一个历史版本，不改当前提示词（编辑页冲突时保存被放弃的一方）。
     * 内容 SHA-256 与最新一版相同时不记录并返回 false。
     */
    suspend fun recordPromptRevision(agentId: String, content: String, source: AgentPromptRevision.Source): Boolean =
        TODO("波次 A 实现")

    /** 把提示词恢复为 [revisionId] 的内容，并以来源 ROLLBACK 记一版；版本不存在或不属于该 Agent 时返回 NotFound。 */
    suspend fun restorePromptRevision(agentId: String, revisionId: String): AgentFieldUpdate = TODO("波次 A 实现")
}
