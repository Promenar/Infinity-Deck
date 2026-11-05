package com.promenar.infinity.data.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "long_term_memories", indices = [Index("updated_at")])
data class LongTermMemoryEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val content: String,
    @ColumnInfo(name = "source_agent_id") val sourceAgentId: String?,
    @ColumnInfo(name = "source_session_id") val sourceSessionId: String?,
    @ColumnInfo(name = "source_message_id") val sourceMessageId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "owner_session_id") val ownerSessionId: String? = null,
) {
    fun toDomain() = LongTermMemory(id, LongTermMemoryKind.fromStorage(kind), title, content,
        sourceAgentId, sourceSessionId, sourceMessageId, createdAt, updatedAt, ownerSessionId)
}

internal fun LongTermMemory.toEntity() = LongTermMemoryEntity(id, kind.storageValue, title, content,
    sourceAgentId, sourceSessionId, sourceMessageId, createdAt, updatedAt, ownerSessionId)
