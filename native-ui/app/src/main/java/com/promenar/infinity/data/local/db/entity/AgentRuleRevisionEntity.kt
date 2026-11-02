package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Agent 提示词的历史版本。不对 agents 建外键：Agent 以 REPLACE 写入时外键级联会误删历史，
 * 删除 Agent 时由 [com.promenar.infinity.data.local.db.dao.AgentDao.deleteWithRuleRevisions] 一并清理。
 * 与文件版本缓存一样不进入可移植备份。
 */
@Entity(tableName = "agent_rule_revisions", indices = [Index(value = ["agent_id", "created_at"])])
data class AgentRuleRevisionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    val content: String,
    @ColumnInfo(name = "content_sha256") val contentSha256: String,
    val source: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_AGENT = "agent"
        const val SOURCE_ROLLBACK = "rollback"
        const val MAX_REVISIONS_PER_AGENT = 20
    }
}
