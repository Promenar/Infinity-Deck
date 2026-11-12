package com.promenar.infinity.data.repository

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.ProductionEntity
import com.promenar.infinity.data.local.db.entity.SessionProductionEntity
import com.promenar.infinity.data.production.*
import java.util.UUID
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
        mutex.withLock { dao.getForSession(sessionId)?.let { inspect(sessionId, it) } }
    }
    fun recordContextRead(sessionId: String, owner: String, revision: Long, productionId: String) { synchronized(reads) { val k = "$sessionId:$owner:$productionId"; val projectKey = "$sessionId:$productionId"; reads[k] = maxOf(reads[k] ?: 0, revision); sessionReads[projectKey] = maxOf(sessionReads[projectKey] ?: 0, revision) } }

    private fun clearReads(sessionId: String) { synchronized(reads) { reads.keys.removeAll { it.startsWith("$sessionId:") }; sessionReads.keys.removeAll { it.startsWith("$sessionId:") } }; externalObservations.keys.removeAll { it.first == sessionId } }

    suspend fun define(sessionId: String, title: String, kind: ProductionKind, rootPath: String? = null, logline: String? = null, aspectRatio: String? = null): ProductionSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = dao.getForSession(sessionId)
            val t = now()
            val p = if (old == null) {
                val id = newId(); val slug = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { id }
                Production(id, title, kind, rootPath ?: "productions/$slug", logline, aspectRatio, createdAt = t, updatedAt = t)
            } else snapshot(old).production.copy(title = title, kind = kind, rootPath = rootPath ?: old.rootPath, logline = logline, aspectRatio = aspectRatio, updatedAt = t)
            require(old == null || old.revision < Long.MAX_VALUE) { "项目修订号已达到上限。" }
            if (old == null) {
                val existingBytes = files.read(sessionId, "${p.rootPath}/production.json")
                if (existingBytes != null) {
                    val existing = ProductionManifestCodec.decode(existingBytes)
                    require(existing.production.id == p.id) { "目标目录已有其他制作项目清单，请更换 root_path。" }
                }
            }
            val next = ProductionSnapshot(p, (old?.revision ?: 0) + 1, ManifestSync.Pending)
            ProductionManifestCodec.encode(p, next.revision)
            database.withTransaction {
                if (old == null) { dao.insert(entity(next)); dao.bind(SessionProductionEntity(sessionId, p.id, true, t)) }
                else dao.update(entity(next, old).let { if (p.rootPath != old.rootPath) it.copy(manifestSha256 = null, manifestError = null) else it })
            }
            if (old == null) clearReads(sessionId)
            enqueue(p.id)
            snapshot(requireNotNull(dao.get(p.id)), ManifestSync.Pending)
        }
    }

    suspend fun attach(sessionId: String, manifestPath: String): ProductionSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            ProductionManifestCodec.validatePath(manifestPath)
            val bytes = files.read(sessionId, manifestPath) ?: throw IllegalArgumentException("清单文件不存在，请检查路径。")
            val manifest = ProductionManifestCodec.decode(bytes)
            require(manifestPath == "${manifest.production.rootPath}/production.json") { "清单路径与项目根目录不一致，请检查路径。" }
            val existing = dao.get(manifest.production.id)
            database.withTransaction {
                val bound = dao.otherActiveSession(manifest.production.id, sessionId)
                require(bound == null) { "该项目已绑定到另一个会话，请先在原会话解除绑定。" }
                if (existing == null) dao.insert(entity(ProductionSnapshot(manifest.production, manifest.revision, ManifestSync.InSync)).copy(manifestSha256 = ProductionManifestCodec.sha256(bytes)))
                dao.bind(SessionProductionEntity(sessionId, manifest.production.id, true, now()))
            }
            clearReads(sessionId)
            inspect(sessionId, requireNotNull(dao.get(manifest.production.id)), allowMissingWrite = false)
        }
    }
    suspend fun unbind(sessionId: String) = withContext(Dispatchers.IO) { mutex.withLock { dao.unbind(sessionId); clearReads(sessionId) } }

    suspend fun update(sessionId: String, owner: String, operations: List<JsonObject>): ProductionSnapshot = withContext(Dispatchers.IO) {
        mutex.withLock {
            val row = required(sessionId)
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
        mutex.withLock {
            var row = required(sessionId)
            if (resolution == ManifestResolution.ADOPT_FILE) {
                fun changed(): Nothing { externalObservations.remove(sessionId to row.id); throw IllegalArgumentException("清单已变化，请重新查看。") }
                val observed = externalObservations[sessionId to row.id] ?: changed()
                val bytes = try { files.read(sessionId, path(row)) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                if (bytes == null || ProductionManifestCodec.sha256(bytes) != observed.hash || row.revision != observed.revision) changed()
                val imported = try { ProductionManifestCodec.decode(bytes) } catch (_: IllegalArgumentException) { changed() }
                val current = snapshot(row).production
                if (observed.hash == row.manifestSha256 || imported.production == current && imported.revision == row.revision) changed()
                require(imported.production.id == row.id && imported.production.rootPath == row.rootPath) { "清单项目身份或根目录不一致，无法采纳。" }
                val prior = maxOf(row.revision, imported.revision)
                require(prior < Long.MAX_VALUE) { "项目修订号已达到上限。" }
                val result = ProductionSnapshot(imported.production.copy(updatedAt = now()), prior + 1, ManifestSync.Pending)
                ProductionManifestCodec.encode(result.production, result.revision)
                row = entity(result, row).copy(manifestSha256 = observed.hash, manifestError = null)
                database.withTransaction { dao.update(row) }
                clearReads(sessionId)
                externalObservations.remove(sessionId to row.id)
            }
            write(sessionId, row, overwrite = resolution == ManifestResolution.OVERWRITE_FILE)
        }
    }

    /** 每个项目合并短时间内的提交，最长等待 500 毫秒，写入始终串行。 */
    private fun enqueue(id: String) {
        state(id, ManifestSync.Pending)
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
                                    mutex.withLock {
                                        val row = dao.get(id)
                                        val session = dao.activeSession(id)
                                        if (row != null && session != null) write(session, row)
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
    companion object { private val sharedMutex = Mutex() }

    private suspend fun inspect(sessionId: String, row: ProductionEntity, allowMissingWrite: Boolean = true): ProductionSnapshot {
        externalObservations.remove(sessionId to row.id)
        try {
            val bytes = files.read(sessionId, path(row))
            if (bytes == null) return if (allowMissingWrite) write(sessionId, row) else snapshot(row, ManifestSync.ExternalChange(null)).also { state(row.id, it.manifest) }
            val hash = ProductionManifestCodec.sha256(bytes)
            val file = try { ProductionManifestCodec.decode(bytes) } catch (e: IllegalArgumentException) {
                return snapshot(row, ManifestSync.Invalid(message(e))).also { state(row.id, it.manifest) }
            }
            val db = snapshot(row)
            if (hash == row.manifestSha256 && (file.production != db.production || file.revision != row.revision)) return write(sessionId, row)
            val sync = if (file.production == db.production && file.revision == row.revision) ManifestSync.InSync else ManifestSync.ExternalChange(file.revision)
            if (sync is ManifestSync.ExternalChange) externalObservations[sessionId to row.id] = ExternalObservation(hash, row.revision) else externalObservations.remove(sessionId to row.id)
            if (sync == ManifestSync.InSync && (row.manifestSha256 != hash || row.manifestError != null)) database.withTransaction { dao.update(row.copy(manifestSha256 = hash, manifestError = null)) }
            state(row.id, sync)
            return snapshot(row, sync)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val sync = ManifestSync.WriteFailed(message(e)); state(row.id, sync); return snapshot(row, sync)
        }
    }
    private suspend fun write(sessionId: String, row: ProductionEntity, overwrite: Boolean = false): ProductionSnapshot {
        externalObservations.remove(sessionId to row.id)
        state(row.id, ManifestSync.Pending)
        return try {
            val current = files.read(sessionId, path(row))
            val currentHash = current?.let(ProductionManifestCodec::sha256)
            if (!overwrite && current != null && currentHash != row.manifestSha256) {
                val parsed = try { ProductionManifestCodec.decode(current) } catch (e: IllegalArgumentException) {
                    return snapshot(row, ManifestSync.Invalid(message(e))).also { state(row.id, it.manifest) }
                }
                externalObservations[sessionId to row.id] = ExternalObservation(requireNotNull(currentHash), row.revision)
                return snapshot(row, ManifestSync.ExternalChange(parsed.revision)).also { state(row.id, it.manifest) }
            }
            val bytes = ProductionManifestCodec.encode(snapshot(row).production, row.revision)
            files.write(sessionId, path(row), bytes, currentHash)
            database.withTransaction { dao.update(row.copy(manifestSha256 = ProductionManifestCodec.sha256(bytes), manifestError = null)) }
            state(row.id, ManifestSync.InSync)
            snapshot(row, ManifestSync.InSync)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val reason = message(e)
            database.withTransaction { dao.update(row.copy(manifestError = reason)) }
            val failed = ManifestSync.WriteFailed(reason); state(row.id, failed); snapshot(row, failed)
        }
    }
}
