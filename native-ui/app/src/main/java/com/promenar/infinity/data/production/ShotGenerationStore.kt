package com.promenar.infinity.data.production

import com.promenar.infinity.data.repository.ProductionRepository
import com.promenar.infinity.domain.mediagen.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** 引用段逐段 UTF-8 百分号编码；对白后缀只用于镜头附属音频。 */
data class ProductionSourceRef(val productionId: String, val episodeId: String, val shotCode: String, val dialogueIndex: Int? = null) {
    fun encode(): String = "${projectPrefix(productionId)}episode:${encodePart(episodeId)}/shot:${encodePart(shotCode)}" +
        (dialogueIndex?.let { "/dialogue:$it" } ?: "")
    companion object {
        fun projectPrefix(productionId: String): String {
            require(productionId.isNotBlank()) { "项目 id 不能为空。" }
            return "production:${encodePart(productionId)}/"
        }
        fun projectIdFromPrefix(prefix: String): String? = runCatching {
            if (!prefix.startsWith("production:") || !prefix.endsWith("/")) return null
            val id = URLDecoder.decode(prefix.removePrefix("production:").removeSuffix("/"), "UTF-8")
            id.takeIf { it.isNotBlank() && projectPrefix(it) == prefix }
        }.getOrNull()
        private fun encodePart(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
        fun parse(value: String): ProductionSourceRef? = runCatching {
            val match = Regex("^production:([^/]+)/episode:([^/]+)/shot:([^/]+)(?:/dialogue:([0-9]+))?$").matchEntire(value) ?: return null
            fun part(i: Int) = URLDecoder.decode(match.groupValues[i], "UTF-8").also { require(it.isNotBlank()) }
            ProductionSourceRef(part(1), part(2), part(3), match.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()).also { require(it.encode() == value) }
        }.getOrNull()
    }
}

interface ShotGenerationStore {
    suspend fun load(sessionId: String): ProductionSnapshot?
    suspend fun production(id: String): Production?
    suspend fun recompile(sessionId: String, revision: Long, episodeId: String, shotCode: String, prompt: String)
    /** false 表示项目未绑定或镜头已移除；保留任务以便后续恢复。 */
    suspend fun register(task: MediaTask): Boolean
}

/** 所有快照修改复用仓库的互斥与事务，不另建写入事实源。 */
class RepositoryShotGenerationStore(
    private val repository: ProductionRepository,
    private val byId: suspend (String) -> Production?,
    private val activeSession: suspend (String) -> String?,
) : ShotGenerationStore {
    override suspend fun load(sessionId: String) = repository.get(sessionId)
    override suspend fun production(id: String) = byId(id)
    override suspend fun recompile(sessionId: String, revision: Long, episodeId: String, shotCode: String, prompt: String) {
        repository.apply(sessionId, revision, ProductionUserAction.RecompilePrompt(episodeId, shotCode, prompt))
    }
    override suspend fun register(task: MediaTask): Boolean {
        val ref = ProductionSourceRef.parse(task.request.sourceRef) ?: return false
        val session = activeSession(ref.productionId) ?: return false
        if (session != task.request.sessionId) return false
        val snapshot = repository.get(session)?.takeIf { it.production.id == ref.productionId } ?: return false
        val changed = registerShotTask(snapshot.production, task, System.currentTimeMillis()) ?: return false
        if (changed == snapshot.production) return true
        repository.apply(session, snapshot.revision, ProductionUserAction.RegisterGeneratedTask(task))
        return true
    }
}

internal fun Production.mapShot(episodeId: String, shotCode: String, transform: (Shot) -> Shot): Production = copy(seasons = seasons.map { season ->
    season.copy(episodes = season.episodes.map { episode -> if (episode.id != episodeId) episode else episode.copy(scenes = episode.scenes.map { scene ->
        scene.copy(shots = scene.shots.map { if (it.code == shotCode) transform(it) else it })
    }) })
})

/** 只使用任务冻结的请求；用户在提交后编辑的规划不影响候选谱系。 */
internal fun registerShotTask(production: Production, task: MediaTask, now: Long): Production? {
    if (task.state != MediaTaskState.COMPLETED || task.outputPaths.isEmpty()) return null
    val ref = ProductionSourceRef.parse(task.request.sourceRef)?.takeIf { it.productionId == production.id } ?: return null
    if (production.seasons.flatMap { it.episodes }.filter { it.id == ref.episodeId }.flatMap { it.scenes }.flatMap { it.shots }.none { it.code == ref.shotCode }) return null
    if (ProductionRules.generationRejection(production, ref.episodeId, ref.shotCode) != null) return null
    if (task.outputPaths.any { !it.startsWith("${task.request.outputDir}/") }) return null
    task.outputPaths.forEach(ProductionManifestCodec::validatePath)
    val targetShot = production.seasons.flatMap { it.episodes }.first { it.id == ref.episodeId }.scenes.flatMap { it.shots }.first { it.code == ref.shotCode }
    if (ref.dialogueIndex == null && targetShot.takes.size + task.outputPaths.distinct().count { path -> targetShot.takes.none { it.taskId == task.id && it.path == path } } > 30) return null
    val request = task.request
    val snapshot = TakePlanSnapshot(request.channelId, request.mode.toShotMode(), request.inputs.map { input ->
        ShotInput(when (input.role) {
            com.promenar.infinity.domain.mediagen.InputRole.FIRST_FRAME -> InputRole.FIRST_FRAME
            com.promenar.infinity.domain.mediagen.InputRole.LAST_FRAME -> InputRole.LAST_FRAME
            else -> InputRole.REFERENCE
        }, input.location)
    }, request.prompt, mediaInputs = request.inputs, specs = request.specs, generationMode = request.mode)
    return production.mapShot(ref.episodeId, ref.shotCode) { shot ->
        if (ref.dialogueIndex != null) {
            val additions = task.outputPaths.distinct().filter { path -> shot.audio.none { it.taskId == task.id && it.path == path } }.map { path ->
                ShotAudio(UUID.nameUUIDFromBytes("${task.id}:$path".toByteArray()).toString(), path, ref.dialogueIndex, task.id, snapshot, now)
            }
            shot.copy(audio = shot.audio + additions)
        } else {
            val additions = task.outputPaths.distinct().filter { path -> shot.takes.none { it.taskId == task.id && it.path == path } }.map { path ->
                Take(UUID.nameUUIDFromBytes("${task.id}:$path".toByteArray()).toString(), path,
                    TakeSource(request.channelId.take(120), mode = snapshot.mode, usageRef = task.id), now, taskId = task.id, planSnapshot = snapshot)
            }
            shot.copy(takes = shot.takes + additions, status = if (additions.isNotEmpty() && shot.status !in setOf(ShotStatus.LOCKED, ShotStatus.REDO)) ShotStatus.REVIEW else shot.status)
        }
    }
}

/** 首轮即扫持久任务；登记失败按任务隔离重试，取消不吞掉。 */
class ShotTaskCompletionRegistrar(private val queue: MediaGenerationQueue, private val store: ShotGenerationStore) {
    private val registered = mutableSetOf<String>()
    private val _pending = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    val pending: kotlinx.coroutines.flow.StateFlow<Set<String>> = _pending
    suspend fun reconcile(): Set<String> = buildSet {
        queue.observeTasks().first().filter { it.id !in registered && it.state == MediaTaskState.COMPLETED && ProductionSourceRef.parse(it.request.sourceRef) != null }.forEach { task ->
            try { if (!store.register(task)) add(task.id) else registered += task.id }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { add(task.id) }
        }
    }.also { _pending.value = it }
    fun start(scope: CoroutineScope): Job = scope.launch {
        // 周期检查同时覆盖清单冲突解除、项目重新绑定与队列未再发射的情况。
        while (isActive) {
            try { reconcile() } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { }
            delay(2_000)
        }
    }
}

internal fun ShotMode.toGenerationMode(): GenerationMode = when (this) {
    ShotMode.TEXT -> GenerationMode.TEXT
    ShotMode.FIRST_FRAME -> GenerationMode.FIRST_FRAME
    ShotMode.FIRST_LAST -> GenerationMode.FIRST_LAST_FRAME
    ShotMode.REFERENCE -> GenerationMode.REFERENCE
}
private fun GenerationMode.toShotMode(): ShotMode = when (this) {
    GenerationMode.FIRST_FRAME -> ShotMode.FIRST_FRAME
    GenerationMode.FIRST_LAST_FRAME -> ShotMode.FIRST_LAST
    GenerationMode.REFERENCE -> ShotMode.REFERENCE
    else -> ShotMode.TEXT
}
