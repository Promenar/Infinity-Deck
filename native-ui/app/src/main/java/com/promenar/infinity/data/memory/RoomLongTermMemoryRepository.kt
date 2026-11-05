package com.promenar.infinity.data.memory

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.startup.StartupWriteBarrier
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class RoomLongTermMemoryRepository(
    private val database: InfinityDatabase,
    private val now: () -> Long = System::currentTimeMillis,
) : LongTermMemoryRepository {
    private val dao get() = database.longTermMemoryDao()

    override fun observeAll(): Flow<List<LongTermMemory>> = dao.observeAll()
        .map { rows -> rows.map { it.toDomain() } }.flowOn(Dispatchers.IO)
    override suspend fun getAll(): List<LongTermMemory> = withContext(Dispatchers.IO) { dao.getAll().map { it.toDomain() } }
    override suspend fun get(id: String): LongTermMemory? = withContext(Dispatchers.IO) { dao.get(id)?.toDomain() }

    override suspend fun deleteForOwner(id: String, ownerSessionId: String?): LongTermMemory? = withContext(Dispatchers.IO) {
        StartupWriteBarrier.requireWritable()
        database.withTransaction {
            dao.get(id)?.takeIf { it.ownerSessionId == ownerSessionId }?.toDomain()?.also { dao.delete(id) }
        }
    }

    override suspend fun save(kind: LongTermMemoryKind, title: String, content: String, id: String?, source: LongTermMemorySource?): LongTermMemoryWrite = withContext(Dispatchers.IO) {
        val (cleanTitle, cleanContent) = LongTermMemoryValidation.validate(title, content)
        StartupWriteBarrier.requireWritable()
        database.withTransaction {
            val rows = dao.getAll()
            // 管理入口按记录 ID 编辑；工具入口始终提供 source 并限定归属。
            val owner = if (source == null && id != null) rows.firstOrNull { it.id == id }?.ownerSessionId else source?.ownerSessionId
            val visible = rows.filter { it.ownerSessionId == owner }
            val previous = if (id != null) {
                visible.firstOrNull { it.id == id } ?: throw LongTermMemoryException(LongTermMemoryFailure.NOT_FOUND)
            } else visible.firstOrNull { it.title.trim().equals(cleanTitle, ignoreCase = true) }
            if (previous == null) requireCapacity(rows.size)
            val memory = LongTermMemory(
                id = previous?.id ?: UUID.randomUUID().toString(), kind = kind, title = cleanTitle, content = cleanContent,
                sourceAgentId = if (source == null) previous?.sourceAgentId else source.agentId,
                sourceSessionId = if (source == null) previous?.sourceSessionId else source.sessionId,
                sourceMessageId = if (source == null) previous?.sourceMessageId else source.messageId,
                createdAt = previous?.createdAt ?: now(), updatedAt = now(),
                ownerSessionId = owner,
            )
            dao.upsert(memory.toEntity())
            LongTermMemoryWrite(memory, previous?.toDomain())
        }
    }

    override suspend fun delete(id: String): LongTermMemory? = withContext(Dispatchers.IO) {
        StartupWriteBarrier.requireWritable()
        database.withTransaction { dao.get(id)?.toDomain()?.also { dao.delete(id) } }
    }

    override suspend fun restore(memory: LongTermMemory) = withContext(Dispatchers.IO) {
        LongTermMemoryValidation.validateId(memory.id)
        val (title, content) = LongTermMemoryValidation.validate(memory.title, memory.content)
        if (title != memory.title || content != memory.content) throw LongTermMemoryException(LongTermMemoryFailure.INVALID_RESTORE)
        StartupWriteBarrier.requireWritable()
        database.withTransaction {
            if (dao.get(memory.id) == null) requireCapacity(dao.getAll().size)
            dao.upsert(memory.toEntity())
        }
    }

    private fun requireCapacity(count: Int) {
        if (count >= LongTermMemoryValidation.MAX_MEMORIES) throw LongTermMemoryException(LongTermMemoryFailure.LIMIT_REACHED)
    }
}
