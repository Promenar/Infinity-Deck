package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.StatefulChainRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 持久化有状态链式工作流的 DAG 版本树与段间不透明接续状态。 */
interface StatefulChainStore {
    fun observeSession(sessionId: String): Flow<List<StatefulChainRecord>>
    suspend fun listSession(sessionId: String, workflowId: String? = null, shotRef: String? = null): List<StatefulChainRecord>
    suspend fun get(sessionId: String, chainId: String): StatefulChainRecord?
    suspend fun save(record: StatefulChainRecord)
    suspend fun delete(sessionId: String, chainId: String)
}

class FileStatefulChainStore(private val rootDir: File) : StatefulChainStore {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 32)

    override fun observeSession(sessionId: String): Flow<List<StatefulChainRecord>> = flow {
        emit(listSession(sessionId))
        updates.collect { changedSession ->
            if (changedSession == sessionId) emit(listSession(sessionId))
        }
    }.distinctUntilChanged()

    override suspend fun listSession(sessionId: String, workflowId: String?, shotRef: String?): List<StatefulChainRecord> =
        withContext(Dispatchers.IO) {
            if (!isSafeId(sessionId)) return@withContext emptyList()
            mutex.withLock {
                val dir = sessionDir(sessionId)
                if (!dir.isDirectory) return@withLock emptyList()
                dir.listFiles().orEmpty()
                    .filter { it.isFile && it.name.endsWith(".json") }
                    .mapNotNull { file -> runCatching { json.decodeFromString(StatefulChainRecord.serializer(), file.readText()) }.getOrNull() }
                    .filter { rec ->
                        (workflowId == null || rec.workflowId == workflowId) &&
                            (shotRef == null || rec.shotRef == shotRef)
                    }
                    .sortedByDescending { it.updatedAt }
            }
        }

    override suspend fun get(sessionId: String, chainId: String): StatefulChainRecord? =
        withContext(Dispatchers.IO) {
            if (!isSafeId(sessionId) || !isSafeId(chainId)) return@withContext null
            mutex.withLock {
                val file = chainFile(sessionId, chainId)
                if (!file.isFile) return@withContext null
                json.decodeFromString(StatefulChainRecord.serializer(), file.readText())
            }
        }

    override suspend fun save(record: StatefulChainRecord) {
        withContext(Dispatchers.IO) {
            require(isSafeId(record.sessionId) && isSafeId(record.chainId)) { "会话或链 ID 格式无效" }
            mutex.withLock {
                val file = chainFile(record.sessionId, record.chainId)
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json.encodeToString(record), Charsets.UTF_8)
                check(tmp.renameTo(file) || run { file.writeText(tmp.readText()); tmp.delete(); true }) {
                    "有状态链记录保存失败"
                }
            }
            updates.tryEmit(record.sessionId)
        }
    }

    override suspend fun delete(sessionId: String, chainId: String) {
        withContext(Dispatchers.IO) {
            if (!isSafeId(sessionId) || !isSafeId(chainId)) return@withContext
            mutex.withLock { chainFile(sessionId, chainId).delete() }
            updates.tryEmit(sessionId)
        }
    }

    private fun sessionDir(sessionId: String): File = File(rootDir, sessionId).canonicalFile

    private fun chainFile(sessionId: String, chainId: String): File =
        File(sessionDir(sessionId), "$chainId.json").canonicalFile

    private companion object {
        fun isSafeId(id: String) = id.matches(Regex("[A-Za-z0-9_.-]{1,128}")) && !id.startsWith('.') && ".." !in id
    }
}

class InMemoryStatefulChainStore : StatefulChainStore {
    private val mutex = Mutex()
    private val data = linkedMapOf<Pair<String, String>, StatefulChainRecord>()
    private val updates = MutableSharedFlow<String>(extraBufferCapacity = 32)

    override fun observeSession(sessionId: String): Flow<List<StatefulChainRecord>> = flow {
        emit(listSession(sessionId))
        updates.collect { if (it == sessionId) emit(listSession(sessionId)) }
    }.distinctUntilChanged()

    override suspend fun listSession(sessionId: String, workflowId: String?, shotRef: String?): List<StatefulChainRecord> =
        mutex.withLock {
            data.values.filter {
                it.sessionId == sessionId &&
                    (workflowId == null || it.workflowId == workflowId) &&
                    (shotRef == null || it.shotRef == shotRef)
            }.sortedByDescending { it.updatedAt }
        }

    override suspend fun get(sessionId: String, chainId: String): StatefulChainRecord? =
        mutex.withLock { data[sessionId to chainId] }

    override suspend fun save(record: StatefulChainRecord) {
        mutex.withLock { data[record.sessionId to record.chainId] = record }
        updates.tryEmit(record.sessionId)
    }

    override suspend fun delete(sessionId: String, chainId: String) {
        mutex.withLock { data.remove(sessionId to chainId) }
        updates.tryEmit(sessionId)
    }
}
