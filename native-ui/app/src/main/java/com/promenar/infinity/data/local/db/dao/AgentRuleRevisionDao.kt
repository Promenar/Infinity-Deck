package com.promenar.infinity.data.local.db.dao

import kotlinx.coroutines.flow.Flow
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.promenar.infinity.data.local.db.entity.AgentRuleRevisionEntity

@Dao
interface AgentRuleRevisionDao {
    @Query("SELECT * FROM agent_rule_revisions WHERE agent_id = :agentId ORDER BY created_at DESC, id DESC")
    fun observeForAgent(agentId: String): Flow<List<AgentRuleRevisionEntity>>

    @Query("SELECT * FROM agent_rule_revisions WHERE agent_id = :agentId ORDER BY created_at DESC, id DESC LIMIT 1")
    suspend fun latestForAgent(agentId: String): AgentRuleRevisionEntity?

    @Query("SELECT * FROM agent_rule_revisions WHERE agent_id = :agentId ORDER BY created_at DESC, id DESC")
    suspend fun listForAgent(agentId: String): List<AgentRuleRevisionEntity>

    @Query("SELECT * FROM agent_rule_revisions WHERE id = :id")
    suspend fun getById(id: String): AgentRuleRevisionEntity?

    @Insert
    suspend fun insert(revision: AgentRuleRevisionEntity)

    @Query(
        """DELETE FROM agent_rule_revisions WHERE agent_id = :agentId AND id NOT IN (
            SELECT id FROM agent_rule_revisions WHERE agent_id = :agentId
            ORDER BY created_at DESC, id DESC LIMIT :keep
        )""",
    )
    suspend fun trim(agentId: String, keep: Int): Int

    /** 写入一个历史版本并只保留该 Agent 最近的 [AgentRuleRevisionEntity.MAX_REVISIONS_PER_AGENT] 个。 */
    @Transaction
    suspend fun record(revision: AgentRuleRevisionEntity) {
        insert(revision)
        trim(revision.agentId, AgentRuleRevisionEntity.MAX_REVISIONS_PER_AGENT)
    }
}
