package com.promenar.infinity.ui.chat.manager

import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.data.memory.LongTermMemoryAccess
import com.promenar.infinity.data.memory.LongTermMemoryKind
import com.promenar.infinity.data.memory.LongTermMemoryRepository
import com.promenar.infinity.data.memory.LongTermMemorySource
import com.promenar.infinity.data.memory.LongTermMemoryWrite
import com.promenar.infinity.data.model.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

internal fun memoryFixture(
    id: String = "memory-id",
    kind: LongTermMemoryKind = LongTermMemoryKind.USER,
    title: String = "偏好",
    content: String = "使用简体中文",
    updatedAt: Long = 10,
) = LongTermMemory(id, kind, title, content, "agent", "session", "message", 1, updatedAt)

internal class RecordingMemoryRepository(initial: List<LongTermMemory> = emptyList()) : LongTermMemoryRepository {
    private val memories = initial.associateByTo(linkedMapOf()) { it.id }
    var failure: Throwable? = null
    var reads = 0
    var writes = 0
    var savedSource: LongTermMemorySource? = null
    var savedId: String? = null
    var savedKind: LongTermMemoryKind? = null
    var savedTitle: String? = null
    var savedContent: String? = null
    var writeResult = LongTermMemoryWrite(memoryFixture(), null)

    override fun observeAll(): Flow<List<LongTermMemory>> = flowOf(memories.values.toList())
    override suspend fun getAll(): List<LongTermMemory> {
        reads++
        failure?.let { throw it }
        return memories.values.toList()
    }
    override suspend fun get(id: String): LongTermMemory? {
        reads++
        failure?.let { throw it }
        return memories[id]
    }
    override suspend fun save(
        kind: LongTermMemoryKind,
        title: String,
        content: String,
        id: String?,
        source: LongTermMemorySource?,
    ): LongTermMemoryWrite {
        writes++
        failure?.let { throw it }
        savedKind = kind
        savedTitle = title
        savedContent = content
        savedId = id
        savedSource = source
        return writeResult
    }
    override suspend fun delete(id: String): LongTermMemory? {
        writes++
        failure?.let { throw it }
        return memories.remove(id)
    }
    override suspend fun restore(memory: LongTermMemory) { memories[memory.id] = memory }
}

internal class RecordingMemoryAccess(var enabled: Boolean = true) : LongTermMemoryAccess {
    var ownerSessionId: String? = null
    var failure: Throwable? = null
    var checkedSession: Session? = null
    val checkedIds = mutableListOf<String>()
    override suspend fun isEnabled(session: Session): Boolean {
        checkedSession = session
        failure?.let { throw it }
        return enabled
    }
    override suspend fun isEnabled(sessionId: String): Boolean {
        checkedIds += sessionId
        failure?.let { throw it }
        return enabled
    }
    override suspend fun ownerForSession(sessionId: String): String? = ownerSessionId
}
