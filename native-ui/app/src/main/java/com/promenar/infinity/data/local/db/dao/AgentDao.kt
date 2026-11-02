package com.promenar.infinity.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.infinity.data.local.db.entity.AgentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(agent: AgentEntity)

    @Update
    suspend fun update(agent: AgentEntity)

    @Delete
    suspend fun delete(agent: AgentEntity)

    @Query("DELETE FROM agents WHERE id = :agentId")
    suspend fun deleteById(agentId: String)

    @Query("SELECT * FROM agents WHERE id = :agentId")
    suspend fun getById(agentId: String): AgentEntity?

    @Query("SELECT * FROM agents ORDER BY created_at ASC")
    fun observeAll(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents ORDER BY created_at ASC")
    suspend fun getAll(): List<AgentEntity>

    @Query("UPDATE agents SET icon = :newIcon, color = :newColor WHERE id = :id AND icon = :oldIcon AND color = :oldColor")
    suspend fun upgradePresetAvatarIfUnchanged(id: String, oldIcon: String, oldColor: String, newIcon: String, newColor: String): Int

    @Query("SELECT user_rules FROM agents WHERE id = :agentId")
    suspend fun getUserRules(agentId: String): String?

    @Query("UPDATE agents SET user_rules = :userRules WHERE id = :agentId")
    suspend fun updateUserRules(agentId: String, userRules: String): Int

    @Query("DELETE FROM agent_rule_revisions WHERE agent_id = :agentId")
    suspend fun deleteRuleRevisions(agentId: String): Int

    @Transaction
    suspend fun deleteWithRuleRevisions(agentId: String) {
        deleteRuleRevisions(agentId)
        deleteById(agentId)
    }
}
