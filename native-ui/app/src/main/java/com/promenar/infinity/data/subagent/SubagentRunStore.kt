package com.promenar.infinity.data.subagent

import com.promenar.infinity.data.model.TokenUsage
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** 父轮附加用量的兼容端口；后台子代理使用持久累计记录独立统计。 */
interface SubagentUsageLedger {
    fun add(sessionId: String, usage: TokenUsage)
    fun drain(sessionId: String): TokenUsage?
}

/**
 * 子代理运行记录与恢复检查点：内存供界面实时读取，持久状态写入应用私有的不备份目录；
 * 父会话删除时整目录删除。进程重启后仍为运行中的记录显示为已中断。
 */
class SubagentRunStore(
    private val root: File,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SubagentUsageLedger {
    private val live = MutableStateFlow<Map<String, SubagentBatch>>(emptyMap())
    private val mutex = Mutex()
    private val usage = ConcurrentHashMap<String, TokenUsage>()
    /** 已删除的父会话：仍在收尾的子代理不能再写回记录或用量。会话 id 不复用，只需保存在内存。 */
    private val deleted = ConcurrentHashMap.newKeySet<String>()

    /** 运行中读内存；结束或重启后读磁盘，失去执行者的运行显示为已中断。 */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun observe(sessionId: String, stepId: String): Flow<SubagentBatch?> {
        val key = key(sessionId, stepId)
        return live.map { it[key] }.distinctUntilChanged()
            .mapLatest { current -> current ?: load(sessionId, stepId) }
            .distinctUntilChanged()
    }

    fun snapshot(sessionId: String, stepId: String): SubagentBatch? = live.value[key(sessionId, stepId)]

    /** 运行中更新：内存立即可见，磁盘按调用方节奏写入。 */
    suspend fun update(batch: SubagentBatch, persist: Boolean) {
        if (batch.sessionId in deleted) return
        if (persist) write(batch)
        publish(batch)
    }

    /** Launcher 的状态锁内只发布内存，磁盘写者不得回写旧快照。 */
    internal fun publish(batch: SubagentBatch) {
        if (batch.sessionId !in deleted) live.update { it + (key(batch.sessionId, batch.stepId) to batch) }
    }

    internal suspend fun persist(batch: SubagentBatch) = write(batch)

    internal fun markDeleted(sessionId: String) { deleted += sessionId }

    suspend fun finish(batch: SubagentBatch) {
        if (batch.sessionId in deleted) return
        write(batch)
        live.update { it - key(batch.sessionId, batch.stepId) }
    }

    suspend fun load(sessionId: String, stepId: String): SubagentBatch? = withContext(dispatcher) {
        try {
            mutex.withLock {
                val entry = entry(sessionId, stepId, create = false) ?: return@withLock null
                if (!Files.isRegularFile(entry.toPath(), NOFOLLOW_LINKS)) return@withLock null
                val batch = json.decodeFromString(SubagentBatch.serializer(), entry.readText(Charsets.UTF_8))
                if (batch.sessionId != sessionId || batch.stepId != stepId) return@withLock null
                if (live.value[key(sessionId, stepId)] == null && batch.runs.any { it.status.isActive }) batch.interrupted() else batch
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 应用启动与独立用量统计扫描；只接受由本存储写入的哈希目录。 */
    suspend fun all(): List<SubagentBatch> = withContext(dispatcher) {
        mutex.withLock {
            val base = checkedRoot(false) ?: return@withLock emptyList()
            val disk = base.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}")) && Files.isDirectory(it.toPath(), NOFOLLOW_LINKS) }
                .flatMap { directory ->
                    checkChild(directory, base)
                    directory.listFiles().orEmpty().filter { it.name.matches(Regex("[a-f0-9]{64}\\.json")) }.mapNotNull { file ->
                        try {
                            checkChild(file, directory)
                            val batch = json.decodeFromString(SubagentBatch.serializer(), file.readText())
                            batch.takeIf { sha256(it.sessionId) == directory.name && "${sha256(it.stepId)}.json" == file.name && it.sessionId !in deleted }
                        } catch (_: Exception) { null }
                    }
                }
            (disk.associateBy { key(it.sessionId, it.stepId) } + live.value).values.toList()
        }
    }

    suspend fun usageWatermarks(): Map<String, SubagentUsageWatermark> = withContext(dispatcher) {
        mutex.withLock {
            val base = checkedRoot(false) ?: return@withLock emptyMap()
            val file = File(base, "usage-watermarks.json").also { checkChild(it, base) }
            if (!file.isFile) emptyMap() else json.decodeFromString<kotlin.collections.Map<String, SubagentUsageWatermark>>(file.readText())
        }
    }

    suspend fun resetUsageWatermarks() {
        val watermark = all().flatMap { it.runs }.filter { it.id.isNotEmpty() }.flatMap { run ->
            listOf(run.id to SubagentUsageWatermark(run.inputTokens, run.outputTokens, run.totalTokens, run.legacyCache(), run.cacheRequests.mapTo(mutableSetOf()) { it.id })) +
                (run.dailyUsage.keys + run.dailyCacheUsage.keys + run.cacheRequests.map { it.day }).map { day ->
                    val usage = run.dailyUsage[day] ?: TokenUsage()
                    "${run.id}/$day" to SubagentUsageWatermark(usage.input, usage.output, usage.total,
                        run.legacyCache(day), run.cacheRequests.filter { it.day == day }.mapTo(mutableSetOf()) { it.id })
                }
        }.toMap()
        restoreUsageWatermarks(watermark)
    }

    suspend fun restoreUsageWatermarks(watermark: Map<String, SubagentUsageWatermark>) = withContext(dispatcher) { mutex.withLock {
        val base = checkNotNull(checkedRoot(true))
        val file = File(base, "usage-watermarks.json").also { checkChild(it, base) }
        atomicText(file, json.encodeToString(kotlinx.serialization.builtins.MapSerializer(kotlinx.serialization.serializer<String>(), SubagentUsageWatermark.serializer()), watermark))
    } }

    suspend fun execution(sessionId: String, stepId: String, id: String): SubagentExecutionState? = withContext(dispatcher) {
        mutex.withLock { readExecution(sessionId, stepId, id) }
    }

    /** 收件箱与模型历史共用原子检查点，历史提交时才移除已消费指令。 */
    suspend fun saveExecution(sessionId: String, stepId: String, id: String, state: SubagentExecutionState) = withContext(dispatcher) {
        mutex.withLock {
            if (sessionId in deleted) return@withLock
            val old = readExecution(sessionId, stepId, id)
            val pending = (old?.pendingInstructions.orEmpty() + state.pendingInstructions).distinctBy { it.id }
                .filter { it.id !in state.consumedInstructionIds }
            val file = executionFile(sessionId, stepId, id, true) ?: return@withLock
            atomicText(file, json.encodeToString(SubagentExecutionState.serializer(), state.copy(pendingInstructions = pending)))
        }
    }

    suspend fun enqueue(sessionId: String, stepId: String, id: String, instruction: SubagentInstruction) = withContext(dispatcher) {
        mutex.withLock {
            check(sessionId !in deleted) { "父会话已删除" }
            val state = readExecution(sessionId, stepId, id) ?: SubagentExecutionState()
            val file = checkNotNull(executionFile(sessionId, stepId, id, true))
            atomicText(file, json.encodeToString(SubagentExecutionState.serializer(), state.copy(pendingInstructions = state.pendingInstructions + instruction)))
        }
    }

    private fun readExecution(sessionId: String, stepId: String, id: String): SubagentExecutionState? {
        val file = executionFile(sessionId, stepId, id, false) ?: return null
        if (!Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return null
        return json.decodeFromString(SubagentExecutionState.serializer(), file.readText())
    }

    private fun executionFile(sessionId: String, stepId: String, id: String, create: Boolean): File? {
        require(id.matches(Regex("sa_[a-z0-9]{6}"))) { "子代理地址无效" }
        val batch = entry(sessionId, stepId, create) ?: return null
        return File(batch.parentFile, "${sha256(stepId)}-$id.context").also { checkChild(it, batch.parentFile) }
    }

    private fun atomicText(entry: File, text: String) {
        val temporary = File(entry.parentFile, ".${entry.name}-${UUID.randomUUID()}.tmp")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(text.toByteArray(Charsets.UTF_8)); stream.fd.sync() }
            try { Files.move(temporary.toPath(), entry.toPath(), ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary.toPath(), entry.toPath(), REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary.toPath()) }
    }

    suspend fun deleteSession(sessionId: String): Unit = withContext(dispatcher) {
        mutex.withLock {
            deleted += sessionId
            live.update { batches -> batches.filterValues { it.sessionId != sessionId } }
            usage.remove(sessionId)
            val storeRoot = checkedRoot(create = false) ?: return@withLock
            val session = File(storeRoot, sha256(sessionId))
            checkChild(session, storeRoot)
            deleteTree(session)
        }
    }

    override fun add(sessionId: String, usage: TokenUsage) {
        if (sessionId in deleted) return
        if (usage.total <= 0 && usage.input <= 0 && usage.output <= 0) return
        this.usage.merge(sessionId, usage) { old, new ->
            TokenUsage(
                input = saturated(old.input, new.input),
                output = saturated(old.output, new.output),
                total = saturated(old.total, new.total),
                estimated = old.estimated || new.estimated,
            )
        }
    }

    override fun drain(sessionId: String): TokenUsage? = usage.remove(sessionId)

    private suspend fun write(batch: SubagentBatch) = withContext(dispatcher) {
        mutex.withLock {
            if (batch.sessionId in deleted) return@withLock
            val file = entry(batch.sessionId, batch.stepId, create = true) ?: error("子代理目录不可用")
            atomicText(file, json.encodeToString(SubagentBatch.serializer(), batch))
        }
    }

    private fun entry(sessionId: String, stepId: String, create: Boolean): File? {
        val storeRoot = checkedRoot(create) ?: return null
        val session = File(storeRoot, sha256(sessionId))
        checkChild(session, storeRoot)
        if (!Files.exists(session.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            if (!session.mkdir() && !session.isDirectory) throw IOException("子代理记录目录无法创建。")
        }
        if (!Files.isDirectory(session.toPath(), NOFOLLOW_LINKS)) throw IOException("子代理记录目录不可用。")
        return File(session, "${sha256(stepId)}.json").also { checkChild(it, session) }
    }

    private fun checkedRoot(create: Boolean): File? {
        if (Files.isSymbolicLink(root.toPath())) throw IOException("子代理记录根目录不可用。")
        if (!Files.exists(root.toPath(), NOFOLLOW_LINKS)) {
            if (!create) return null
            if (!root.mkdirs() && !root.isDirectory) throw IOException("子代理记录目录无法创建。")
        }
        if (!root.isDirectory) throw IOException("子代理记录目录不可用。")
        return root.canonicalFile
    }

    private fun checkChild(child: File, parent: File) {
        if (Files.isSymbolicLink(child.toPath()) || child.canonicalFile.parentFile != parent.canonicalFile) {
            throw IOException("子代理记录路径不安全。")
        }
    }

    private fun deleteTree(entry: File) {
        if (Files.isDirectory(entry.toPath(), NOFOLLOW_LINKS)) {
            val children = entry.listFiles() ?: throw IOException("子代理记录无法清理。")
            children.forEach { child ->
                if (Files.isSymbolicLink(child.toPath())) Files.deleteIfExists(child.toPath())
                else {
                    checkChild(child, entry)
                    deleteTree(child)
                }
            }
        }
        Files.deleteIfExists(entry.toPath())
    }

    private fun key(sessionId: String, stepId: String) = "$sessionId\u0000$stepId"

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun saturated(a: Int, b: Int) = (a.toLong() + b).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()

    private companion object {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

/** 进程重启后没有执行者的运行记录。 */
internal fun SubagentBatch.interrupted(): SubagentBatch = copy(
    runs = runs.map { run ->
        if (run.status.isActive) run.copy(status = SubagentStatus.INTERRUPTED, currentTool = null, liveOutputKey = null) else run
    },
)
