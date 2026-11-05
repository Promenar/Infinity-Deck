package com.promenar.infinity.data.memory

import java.util.Locale
import kotlinx.coroutines.flow.Flow

enum class LongTermMemoryKind {
    USER, FEEDBACK, PROJECT, REFERENCE;

    val storageValue: String get() = name.lowercase(Locale.ROOT)

    companion object {
        fun fromStorage(value: String): LongTermMemoryKind = entries.firstOrNull { it.storageValue == value }
            ?: throw LongTermMemoryException(LongTermMemoryFailure.INVALID_KIND)
    }
}

data class LongTermMemory(
    val id: String,
    val kind: LongTermMemoryKind,
    val title: String,
    val content: String,
    val sourceAgentId: String?,
    val sourceSessionId: String?,
    val sourceMessageId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val ownerSessionId: String? = null,
)

data class LongTermMemoryWrite(val memory: LongTermMemory, val previous: LongTermMemory?)
data class LongTermMemorySource(val agentId: String?, val sessionId: String?, val messageId: String?, val ownerSessionId: String? = null)

interface LongTermMemoryRepository {
    fun observeAll(): Flow<List<LongTermMemory>>
    suspend fun getAll(): List<LongTermMemory>
    suspend fun get(id: String): LongTermMemory?
    suspend fun getAllForOwner(ownerSessionId: String?): List<LongTermMemory> = getAll().filter { it.ownerSessionId == ownerSessionId }
    suspend fun getForOwner(id: String, ownerSessionId: String?): LongTermMemory? = get(id)?.takeIf { it.ownerSessionId == ownerSessionId }
    suspend fun deleteForOwner(id: String, ownerSessionId: String?): LongTermMemory? =
        getForOwner(id, ownerSessionId)?.let { delete(id) }
    suspend fun save(
        kind: LongTermMemoryKind,
        title: String,
        content: String,
        id: String? = null,
        source: LongTermMemorySource? = null,
    ): LongTermMemoryWrite
    suspend fun delete(id: String): LongTermMemory?
    suspend fun restore(memory: LongTermMemory)
}
