package com.promenar.infinity.data.repository

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.ProductionEntity
import com.promenar.infinity.data.local.db.entity.SessionProductionEntity
import com.promenar.infinity.data.production.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** 项目快照独立于消息生命周期；数据库事务与文件写入在 IO 调度器执行。 */
class ProductionRepository(
    private val database: InfinityDatabase,
    private val files: ProductionWorkspaceFiles,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : ProductionPanelSource {
    private val dao get() = database.productionDao()
    private val mutex = sharedMutex
    private val states = MutableStateFlow<Map<String, ManifestSync>>(emptyMap())
    private val exceptionHandler = CoroutineExceptionHandler { _, error -> InfinityLogger.logError("制作清单后台同步异常", error) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + exceptionHandler)
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private data class ExternalObservation(val hash: String, val revision: Long)
    private val externalObservations = mutableMapOf<Pair<String, String>, ExternalObservation>()
    private val queues = mutableMapOf<String, Channel<Unit>>()
    private val sessionReads = java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())
    fun lastContextRevision(sessionId: String, productionId: String): Long = sessionReads["$sessionId:$productionId"] ?: 0
    private val reads = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 512
    })
    private fun state(id: String, value: ManifestSync) { states.value = states.value + (id to value) }
    private fun entity(snapshot: ProductionSnapshot, prior: ProductionEntity? = null): ProductionEntity = ProductionEntity(
        snapshot.production.id, snapshot.production.title, snapshot.production.rootPath, snapshot.revision,
        json.encodeToString(snapshot.production), prior?.manifestSha256, prior?.manifestError,
        snapshot.production.createdAt, snapshot.production.updatedAt,
    )
    private fun snapshot(row: ProductionEntity, sync: ManifestSync = states.value[row.id] ?: row.manifestError?.let { ManifestSync.WriteFailed(it) } ?: ManifestSync.Pending) =
        ProductionSnapshot(json.decodeFromString<Production>(row.snapshot), row.revision, sync)
    private fun path(row: ProductionEntity) = "${row.rootPath}/production.json"
    private fun message(e: Exception) = (e.message ?: "工作区文件访问失败").take(500)
    private suspend fun required(sessionId: String) = dao.getForSession(sessionId) ?: throw IllegalArgumentException("当前会话没有制作项目，请先定义或附加项目。")

    override fun observe(sessionId: String): Flow<ProductionSnapshot?> = flow {
        get(sessionId)
        emitAll(combine(dao.observeForSession(sessionId), states) { row, statuses ->
            row?.let { snapshot(it, statuses[it.id] ?: it.manifestError?.let { reason -> ManifestSync.WriteFailed(reason) } ?: ManifestSync.Pending) }
        })
    }.flowOn(Dispatchers.IO)
    override fun observeEverBound(sessionId: String): Flow<Boolean> = dao.observeEverBound(sessionId).flowOn(Dispatchers.IO)

    suspend fun get(sessionId: String): ProductionSnapshot? = withContext(Dispatchers.IO) {
        val row = mutex.withLock { dao.getForSession(sessionId) } ?: return@withContext null
        val fileMutex = projectFileMutex(row.id)
        // 面板与审核回执只读取当前数据库快照，不等待正在进行的工作区写入。
        if (!fileMutex.tryLock()) return@withContext pendingSnapshot(sessionId)
        try {
            val work = mutex.withLock { dao.getForSession(sessionId)?.takeIf { it.id == row.id }?.let { work(sessionId, it) } }
                ?: return@withContext mutex.withLock { dao.getForSession(sessionId)?.let { snapshot(it) } }
            val pathMutex = manifestPathMutex(sessionId, path(work.row))
            if (!pathMutex.tryLock()) return@withContext pendingSnapshot(sessionId)
            try {
                val result = inspectFile(work)
                mutex.withLock { record(work, result) }
                mutex.withLock { dao.getForSession(sessionId)?.let { snapshot(it) } }
            } finally { pathMutex.unlock() }
        } finally { fileMutex.unlock() }
    }
    private suspend fun pendingSnapshot(sessionId: String): ProductionSnapshot? = mutex.withLock {
        dao.getForSession(sessionId)?.let { row ->
            enqueue(row.id)
            snapshot(row, ManifestSync.Pending)
        }
    }
    fun recordContextRead(sessionId: String, owner: String, revision: Long, productionId: String) { synchronized(reads) { val k = "$sessionId:$owner:$productionId"; val projectKey = "$sessionId:$productionId"; reads[k] = maxOf(reads[k] ?: 0, revision); sessionReads[projectKey] = maxOf(sessionReads[projectKey] ?: 0, revision) } }

    private fun clearReads(sessionId: String) { synchronized(reads) { reads.keys.removeAll { it.startsWith("$sessionId:") }; sessionReads.keys.removeAll { it.startsWith("$sessionId:") } }; externalObservations.keys.removeAll { it.first == sessionId } }

    suspend fun define(sessionId: String, title: String, kind: ProductionKind, rootPath: String? = null, logline: String? = null, aspectRatio: String? = null): ProductionSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = dao.getForSession(sessionId)
            if (old != null) {
                val p = snapshot(old).production.copy(title = title, kind = kind, rootPath = rootPath ?: old.rootPath, logline = logline, aspectRatio = aspectRatio, updatedAt = now())
                require(old.revision < Long.MAX_VALUE) { "项目修订号已达到上限。" }
                val next = ProductionSnapshot(p, old.revision + 1, ManifestSync.Pending)
                ProductionManifestCodec.encode(p, next.revision)
                database.withTransaction {
                    dao.update(entity(next, old).let { if (p.rootPath != old.rootPath) it.copy(manifestSha256 = null, manifestError = null) else it })
                }
                enqueue(p.id)
                return@withContext next
            }
        }
        val t = now()
        val id = newId()
        val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { id }
        val p = Production(id, title, kind, rootPath ?: "productions/$slug", logline, aspectRatio, createdAt = t, updatedAt = t)
        val next = ProductionSnapshot(p, 1, ManifestSync.Pending)
        ProductionManifestCodec.encode(p, next.revision)
        projectFileMutex(id).withLock {
            manifestPathMutex(sessionId, "${p.rootPath}/production.json").withLock {
                val existingBytes = files.read(sessionId, "${p.rootPath}/production.json")
                if (existingBytes != null) {
                    val existing = ProductionManifestCodec.decode(existingBytes)
                    require(existing.production.id == id) { "目标目录已有其他制作项目清单，请更换 root_path。" }
                }
                mutex.withLock {
                    database.withTransaction {
                        require(dao.getForSession(sessionId) == null) { "会话绑定已改变，请重试。" }
                        dao.insert(entity(next))
                        dao.bind(SessionProductionEntity(sessionId, id, true, t))
                    }
                    clearReads(sessionId)
                    enqueue(id)
                    next
                }
            }
        }
    }

    suspend fun attach(sessionId: String, manifestPath: String): ProductionSnapshot = withContext(Dispatchers.IO) {
        ProductionManifestCodec.validatePath(manifestPath)
        // 未知项目先按文件路径发现身份，再按项目锁顺序重新读取与核对。
        val discovered = manifestPathMutex(sessionId, manifestPath).withLock {
            ProductionManifestCodec.decode(files.read(sessionId, manifestPath) ?: throw IllegalArgumentException("清单文件不存在，请检查路径。"))
        }
        projectFileMutex(discovered.production.id).withLock {
            manifestPathMutex(sessionId, manifestPath).withLock {
                val bytes = files.read(sessionId, manifestPath) ?: throw IllegalArgumentException("清单文件不存在，请检查路径。")
                val manifest = ProductionManifestCodec.decode(bytes)
                require(manifest.production.id == discovered.production.id) { "清单已变化，请重新查看。" }
                require(manifestPath == "${manifest.production.rootPath}/production.json") { "清单路径与项目根目录不一致，请检查路径。" }
                val work = mutex.withLock {
                    database.withTransaction {
                        val bound = dao.otherActiveSession(manifest.production.id, sessionId)
                        require(bound == null) { "该项目已绑定到另一个会话，请先在原会话解除绑定。" }
                        if (dao.get(manifest.production.id) == null) dao.insert(entity(ProductionSnapshot(manifest.production, manifest.revision, ManifestSync.InSync)).copy(manifestSha256 = ProductionManifestCodec.sha256(bytes)))
                        dao.bind(SessionProductionEntity(sessionId, manifest.production.id, true, now()))
                    }
                    clearReads(sessionId)
                    work(sessionId, requireNotNull(dao.get(manifest.production.id)))
                }
                val result = inspectBytes(work, bytes)
                mutex.withLock { record(work, result) }
            }
        }
    }
    suspend fun unbind(sessionId: String) = withContext(Dispatchers.IO) { mutex.withLock { dao.unbind(sessionId); clearReads(sessionId) } }

    suspend fun update(sessionId: String, owner: String, operations: List<JsonObject>): ProductionSnapshot = withContext(Dispatchers.IO) {
        val row = mutex.withLock { required(sessionId) }
        val validMedia = mutableSetOf<String>()
        val mediaPaths = mutableSetOf<String>()
        fun collectPaths(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    if (key == "path" && value is JsonPrimitive && value.isString) mediaPaths += value.content
                    else collectPaths(value)
                }
                is JsonArray -> element.forEach(::collectPaths)
                else -> Unit
            }
        }
        operations.forEach { op ->
            when ((op["op"] as? JsonPrimitive)?.content) {
                "add_take", "add_asset_version" -> (op["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { mediaPaths += it }
                "upsert_shot" -> op["inputs"]?.let(::collectPaths)
                "upsert_asset" -> { op["looks"]?.let(::collectPaths); op["reference_voice"]?.let(::collectPaths) }
            }
        }
        mediaPaths.forEach { path -> if (files.isImageOrVideo(sessionId, path)) validMedia += path }
        mutex.withLock {
            val seen = reads["$sessionId:$owner:${row.id}"]
            database.withTransaction {
                val current = required(sessionId)
                require(current.id == row.id) { "会话绑定已改变，请重试。" }
                val result = ProductionEngine.update(snapshot(current), operations, seen, now()) { it in validMedia }
                dao.update(entity(result, current))
            }
            enqueue(row.id)
            snapshot(requireNotNull(dao.get(row.id)), ManifestSync.Pending)
        }
    }
    override suspend fun apply(sessionId: String, expectedRevision: Long, action: ProductionUserAction): ProductionSnapshot =
        applyChecked(sessionId, null, expectedRevision, action)

    /** 媒体导出把项目绑定与修订号放在同一事务核对，避免登记到同修订号的新绑定。 */
    suspend fun applyForProduction(sessionId: String, productionId: String, expectedRevision: Long, action: ProductionUserAction): ProductionSnapshot =
        applyChecked(sessionId, productionId, expectedRevision, action)

    private suspend fun applyChecked(sessionId: String, productionId: String?, expectedRevision: Long, action: ProductionUserAction): ProductionSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = database.withTransaction {
                val row = required(sessionId)
                require(productionId == null || row.id == productionId) { "制作项目绑定已变化。" }
                val result = ProductionUserEngine.apply(snapshot(row), expectedRevision, action, now())
                dao.update(entity(result, row))
                row.id
            }
            enqueue(id)
            snapshot(requireNotNull(dao.get(id)), ManifestSync.Pending)
        }
    }
    override suspend fun resolveManifest(sessionId: String, resolution: ManifestResolution): ProductionSnapshot = withContext(Dispatchers.IO) {
        val id = mutex.withLock { required(sessionId).id }
        projectFileMutex(id).withLock {
            val initial = mutex.withLock {
                val row = required(sessionId)
                require(row.id == id) { "会话绑定已改变，请重试。" }
                if (resolution == ManifestResolution.OVERWRITE_FILE) {
                    externalObservations.remove(sessionId to id)
                    state(id, ManifestSync.Pending)
                }
                work(sessionId, row)
            }
            manifestPathMutex(sessionId, path(initial.row)).withLock {
                val work = if (resolution == ManifestResolution.ADOPT_FILE) {
                    val observed = mutex.withLock { externalObservations[sessionId to id] }
                    val bytes = try { files.read(sessionId, path(initial.row)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                    val imported = bytes?.let { try { ProductionManifestCodec.decode(it) } catch (_: IllegalArgumentException) { null } }
                    mutex.withLock {
                        database.withTransaction {
                            val row = required(sessionId)
                            fun changed(): Nothing { externalObservations.remove(sessionId to id); throw IllegalArgumentException("清单已变化，请重新查看。") }
                            if (observed == null || bytes == null || imported == null || row.id != id || row.rootPath != initial.row.rootPath || row.revision != observed.revision || externalObservations[sessionId to id] != observed || ProductionManifestCodec.sha256(bytes) != observed.hash) changed()
                            val current = snapshot(row).production
                            if (observed.hash == row.manifestSha256 || imported.production == current && imported.revision == row.revision) changed()
                            require(imported.production.id == row.id && imported.production.rootPath == row.rootPath) { "清单项目身份或根目录不一致，无法采纳。" }
                            val prior = maxOf(row.revision, imported.revision)
                            require(prior < Long.MAX_VALUE) { "项目修订号已达到上限。" }
                            val result = ProductionSnapshot(imported.production.copy(updatedAt = now()), prior + 1, ManifestSync.Pending)
                            val next = entity(result, row).copy(manifestSha256 = observed.hash, manifestError = null)
                            val work = work(sessionId, next)
                            dao.update(next)
                            clearReads(sessionId)
                            state(id, ManifestSync.Pending)
                            work
                        }
                    }
                } else initial
                val result = writeFile(work, overwrite = resolution == ManifestResolution.OVERWRITE_FILE)
                mutex.withLock { record(work, result) }
            }
        }
    }

    /** 每个项目合并短时间内的提交，最长等待 500 毫秒，写入始终串行。 */
    private fun enqueue(id: String, markPending: Boolean = true) {
        if (markPending) state(id, ManifestSync.Pending)
        synchronized(queues) {
            val queue = queues.getOrPut(id) {
                Channel<Unit>(Channel.CONFLATED).also { channel ->
                    scope.launch {
                        try {
                            for (ignored in channel) {
                                try {
                                    val deadline = System.nanoTime() + 500_000_000L
                                    var again: Boolean
                                    do {
                                        delay(100)
                                        again = channel.tryReceive().isSuccess
                                    } while (again && System.nanoTime() < deadline)
                                    projectFileMutex(id).withLock {
                                        val work = mutex.withLock {
                                            val row = dao.get(id)
                                            val session = dao.activeSession(id)
                                            if (row != null && session != null) {
                                                state(id, ManifestSync.Pending)
                                                work(session, row)
                                            } else null
                                        }
                                        if (work != null) manifestPathMutex(work.sessionId, path(work.row)).withLock {
                                            val result = writeFile(work)
                                            mutex.withLock { record(work, result) }
                                        }
                                    }
                                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                                    state(id, ManifestSync.WriteFailed(message(e)))
                                    InfinityLogger.logError("制作清单同步失败", e)
                                }
                            }
                        } catch (e: CancellationException) { throw e } catch (e: Exception) {
                            state(id, ManifestSync.WriteFailed(message(e)))
                            InfinityLogger.logError("制作清单同步任务异常", e)
                        } finally {
                            synchronized(queues) { if (queues[id] === channel) queues.remove(id) }
                            channel.close()
                        }
                    }
                }
            }
            queue.trySend(Unit)
        }
    }
    fun close() { scope.cancel() }
    companion object {
        private val sharedMutex = Mutex()
        private val projectFileMutexes = ConcurrentHashMap<String, Mutex>()
        private val manifestPathMutexes = ConcurrentHashMap<Pair<String, String>, Mutex>()
        private fun projectFileMutex(id: String) = projectFileMutexes.computeIfAbsent(id) { Mutex() }
        private fun manifestPathMutex(sessionId: String, path: String) = manifestPathMutexes.computeIfAbsent(sessionId to path) { Mutex() }
    }

    private data class ManifestWork(val sessionId: String, val row: ProductionEntity, val bytes: ByteArray)
    private data class ManifestResult(
        val sync: ManifestSync,
        val hash: String? = null,
        val externalHash: String? = null,
        val retry: Boolean = false,
    )
    // 调用方持项目文件锁后短暂持 sharedMutex；编码与数据库读取属于同一快照。
    private fun work(sessionId: String, row: ProductionEntity) = ManifestWork(sessionId, row, ProductionManifestCodec.encode(snapshot(row).production, row.revision))

    private suspend fun inspectFile(work: ManifestWork): ManifestResult = try {
        inspectBytes(work, files.read(work.sessionId, path(work.row)))
    } catch (e: CancellationException) { throw e } catch (e: Exception) {
        ManifestResult(ManifestSync.WriteFailed(message(e)))
    }

    private fun inspectBytes(work: ManifestWork, bytes: ByteArray?): ManifestResult {
        val row = work.row
        fun needsWrite() = ManifestResult(row.manifestError?.let { ManifestSync.WriteFailed(it) } ?: ManifestSync.Pending, retry = true)
        if (bytes == null) return needsWrite()
        val hash = ProductionManifestCodec.sha256(bytes)
        val file = try { ProductionManifestCodec.decode(bytes) } catch (e: IllegalArgumentException) {
            return ManifestResult(ManifestSync.Invalid(message(e)))
        }
        val db = snapshot(row)
        if (hash == row.manifestSha256 && (file.production != db.production || file.revision != row.revision)) return needsWrite()
        return if (file.production == db.production && file.revision == row.revision) ManifestResult(ManifestSync.InSync, hash = hash)
        else ManifestResult(ManifestSync.ExternalChange(file.revision), externalHash = hash)
    }

    /** 仅持项目与路径文件锁；工作区等待和条件写入均不占用 sharedMutex。 */
    private suspend fun writeFile(work: ManifestWork, overwrite: Boolean = false): ManifestResult {
        var expectedHash: String? = null
        var writing = false
        return try {
            val current = files.read(work.sessionId, path(work.row))
            expectedHash = current?.let(ProductionManifestCodec::sha256)
            if (!overwrite && current != null && expectedHash != work.row.manifestSha256) {
                val parsed = try { ProductionManifestCodec.decode(current) } catch (e: IllegalArgumentException) {
                    return ManifestResult(ManifestSync.Invalid(message(e)))
                }
                return ManifestResult(ManifestSync.ExternalChange(parsed.revision), externalHash = expectedHash)
            }
            if (!overwrite && current != null && current.contentEquals(work.bytes)) {
                return ManifestResult(ManifestSync.InSync, hash = expectedHash)
            }
            writing = true
            files.write(work.sessionId, path(work.row), work.bytes, expectedHash)
            ManifestResult(ManifestSync.InSync, hash = ProductionManifestCodec.sha256(work.bytes))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            // 条件写入失败后，仅当文件确实变化才排队重新判定，普通 IO 错误留待用户重试。
            val changed = if (writing) try {
                files.read(work.sessionId, path(work.row))?.let(ProductionManifestCodec::sha256) != expectedHash
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false } else false
            ManifestResult(ManifestSync.WriteFailed(message(e)), retry = changed)
        }
    }

    /** 持 sharedMutex，以事务中的当前行合并清单字段，保留并发提交的业务快照。 */
    private suspend fun record(work: ManifestWork, result: ManifestResult): ProductionSnapshot = database.withTransaction {
        val current = dao.get(work.row.id) ?: return@withTransaction snapshot(work.row)
        val bound = dao.getForSession(work.sessionId)
        if (bound?.id != current.id || current.rootPath != work.row.rootPath) {
            if (dao.activeSession(current.id) != null) enqueue(current.id)
            return@withTransaction snapshot(current)
        }
        externalObservations.remove(work.sessionId to current.id)
        val updated = when (val sync = result.sync) {
            ManifestSync.InSync -> current.copy(manifestSha256 = result.hash, manifestError = null)
            is ManifestSync.WriteFailed -> current.copy(manifestError = sync.reason)
            else -> current
        }
        if (updated != current) dao.update(updated)
        val stale = current.revision != work.row.revision
        val sync = if (stale) ManifestSync.Pending else result.sync
        if (sync is ManifestSync.ExternalChange && result.externalHash != null) {
            externalObservations[work.sessionId to current.id] = ExternalObservation(result.externalHash, current.revision)
        }
        state(current.id, sync)
        if (stale || result.retry) enqueue(current.id, markPending = sync !is ManifestSync.WriteFailed)
        snapshot(updated, sync)
    }
}
