package com.promenar.infinity.data.cut

import com.promenar.infinity.data.production.*
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface CutExportState {
    data object Idle : CutExportState
    data class Running(val progress: Float? = null) : CutExportState
    data class Succeeded(val record: CutExportRecord) : CutExportState
    data class Failed(val reason: String, val recoveryReference: String? = null) : CutExportState
}

interface CutExportController {
    suspend fun createVersion(sessionId: String, episodeId: String): CutVersion
    suspend fun deleteVersion(sessionId: String, versionId: String)
    suspend fun setBurnSubtitles(sessionId: String, versionId: String, burn: Boolean)
    /** 仅接受任务；实际工作由前台服务执行。 */
    suspend fun export(sessionId: String, versionId: String, burnSubtitles: Boolean? = null)
    suspend fun cancel(versionId: String)
    fun observe(): StateFlow<Map<String, CutExportState>>
}

interface CutProjectStore {
    suspend fun load(sessionId: String): ProductionSnapshot?
    suspend fun apply(sessionId: String, productionId: String, revision: Long, action: ProductionUserAction): ProductionSnapshot
}

fun interface CutMediaProbe {
    suspend fun read(sessionId: String, path: String): CutMediaMetadata
}

/** 临时成片的所有权交给调用方，发布或取消后必须关闭。 */
class CutExportArtifact(val video: File, private val directory: File) : Closeable {
    override fun close() { directory.deleteRecursively() }
}

fun interface CutExporter {
    suspend fun export(sessionId: String, plan: CutCompositionPlan, progress: (Float?) -> Unit): CutExportArtifact
}

enum class CutFailureReason { MEDIA_UNAVAILABLE, UNSUPPORTED_SIZE, SOURCE_CHANGED, INVALID_OUTPUT, ENCODING_FAILED }
class CutExportFailure(val reason: CutFailureReason, cause: Throwable? = null) : Exception(reason.name, cause)

/** 原因由 Android 装配层提供，避免将底层异常和媒体路径暴露给界面。 */
data class CutExportMessages(
    val busy: String,
    val missingProject: String,
    val missingVersion: String,
    val changed: String,
    val failed: String,
    val interrupted: String,
    val recoveryRequired: String = failed,
    val mediaUnavailable: String = failed,
    val unsupportedSize: String = failed,
    val sourceChanged: String = changed,
    val invalidOutput: String = failed,
    val encodingFailed: String = failed,
    val storageFull: String = failed,
)

class DefaultCutExportController(
    private val store: CutProjectStore,
    private val probe: CutMediaProbe,
    private val exporter: CutExporter,
    private val output: CutOutputStore,
    private val startService: (Long, String) -> Unit,
    private val messages: CutExportMessages,
    private val captureWorkspace: suspend (String) -> CutWorkspaceBinding = { CutWorkspaceBinding() },
    private val onRecoveryRequired: (String?) -> Unit = {},
) : CutExportController {
    private data class Request(val sessionId: String, val production: Production, val version: CutVersion, val workspace: CutWorkspaceBinding, val lease: Long)
    private val mutex = Mutex()
    private val states = MutableStateFlow<Map<String, CutExportState>>(emptyMap())
    private var request: Request? = null
    private var worker: Job? = null
    private var nextLease = 0L
    override fun observe(): StateFlow<Map<String, CutExportState>> = states.asStateFlow()
    private fun state(id: String, state: CutExportState) { states.update { it + (id to state) } }
    private suspend fun snapshot(sessionId: String) = requireNotNull(store.load(sessionId)) { messages.missingProject }
    private fun Production.version(id: String) = requireNotNull(cuts.firstOrNull { it.id == id }) { messages.missingVersion }

    override suspend fun createVersion(sessionId: String, episodeId: String): CutVersion = withContext(Dispatchers.IO) {
        val binding = captureWorkspace(sessionId)
        withContext(binding.context + CutWorkspaceOperation(binding)) {
            binding.verify()
            val before = snapshot(sessionId)
            val episode = requireNotNull(before.production.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }) { messages.missingVersion }
            val durations = buildMap {
                episode.scenes.flatMap { it.shots }.mapNotNull { shot -> shot.takes.firstOrNull { it.id == shot.selectedTakeId }?.path }.distinct().forEach { path ->
                    try { probe.read(sessionId, path).durationSeconds?.takeIf { it.isFinite() && it > 0 }?.let { put(path, it) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* 实测失败时由纯动作回落计划时长。 */ }
                }
            }
            binding.verify()
            val after = store.apply(sessionId, before.production.id, before.revision, ProductionUserAction.CreateCut(episodeId, durations))
            after.production.cuts.single { cut -> before.production.cuts.none { it.id == cut.id } }
        }
    }

    override suspend fun deleteVersion(sessionId: String, versionId: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(request?.version?.id != versionId) { messages.busy }
            val before = snapshot(sessionId)
            before.production.version(versionId)
            store.apply(sessionId, before.production.id, before.revision, ProductionUserAction.DeleteCut(versionId))
            states.update { it - versionId }
            // 删除版本不删除已交付文件，避免影响用户分享的产物。
        }
    }

    override suspend fun setBurnSubtitles(sessionId: String, versionId: String, burn: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(request?.version?.id != versionId) { messages.busy }
            val before = snapshot(sessionId)
            val version = before.production.version(versionId)
            if (version.burnSubtitles != burn) {
                store.apply(sessionId, before.production.id, before.revision, ProductionUserAction.SetCutSubtitles(versionId, burn))
                states.update { it - versionId }
            }
        }
    }

    override suspend fun export(sessionId: String, versionId: String, burnSubtitles: Boolean?): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(request == null) { messages.busy }
            val binding = captureWorkspace(sessionId)
            binding.verify()
            var before = snapshot(sessionId)
            val initial = before.production.version(versionId)
            if (burnSubtitles != null && initial.burnSubtitles != burnSubtitles) {
                before = store.apply(sessionId, before.production.id, before.revision, ProductionUserAction.SetCutSubtitles(versionId, burnSubtitles))
            }
            val version = before.production.version(versionId)
            require(version.clips.isNotEmpty()) { messages.missingVersion }
            val lease = Math.incrementExact(nextLease).also { nextLease = it }
            request = Request(sessionId, before.production, version, binding, lease)
            state(versionId, CutExportState.Running())
            try { startService(lease, versionId) }
            catch (failure: Exception) {
                request = null
                state(versionId, CutExportState.Failed(messages.interrupted))
                throw IllegalStateException(messages.interrupted, failure)
            }
        }
    }

    override suspend fun cancel(versionId: String) {
        mutex.withLock {
            if (request?.version?.id != versionId) return@withLock
            if (worker == null) {
                request = null
                state(versionId, CutExportState.Idle)
            } else worker?.cancel()
        }
    }

    /** 服务启动失败、系统超时和服务销毁都终止同一份任务。 */
    suspend fun serviceStopped(expectedLease: Long? = null) {
        mutex.withLock {
            request?.let { pending ->
                if (expectedLease != null && pending.lease != expectedLease) return@withLock
                state(pending.version.id, CutExportState.Failed(messages.interrupted))
                worker?.cancel()
                if (worker == null) request = null
            }
        }
    }

    suspend fun hasPending(): Boolean = mutex.withLock { request != null }

    suspend fun hasPending(expectedLease: Long): Boolean = mutex.withLock { request?.lease == expectedLease }

    suspend fun runPending(expectedLease: Long? = null) = withContext(Dispatchers.IO) {
        val current = mutex.withLock {
            if (worker != null) return@withContext
            if (expectedLease != null && request?.lease != expectedLease) return@withContext
            request?.also { worker = currentCoroutineContext()[Job] }
        } ?: return@withContext
        val id = current.version.id
        var committedRecord: CutExportRecord? = null
        try {
            withContext(current.workspace.context + CutWorkspaceOperation(current.workspace)) {
                current.workspace.verify()
                val paths = current.version.clips.mapNotNull { it.path } +
                    current.production.seasons.flatMap { it.episodes }.filter { it.id == current.version.episodeId }
                        .flatMap { it.scenes }.flatMap { it.shots }.filter { shot -> current.version.clips.any { it.shotCode == shot.code } }
                        .flatMap { shot -> shot.dialogue.mapIndexedNotNull { index, line ->
                            if (line.audioSource == DialogueAudioSource.DUBBED) shot.audio.filter { it.dialogueIndex == index }.sortedBy { it.createdAt }.lastOrNull()?.path else null
                        } } +
                    current.production.assets.filter { it.kind == AssetKind.SOUND && it.status == AssetStatus.LOCKED }.take(1)
                        .mapNotNull { asset -> asset.versions.firstOrNull { it.number == asset.currentVersion }?.path }
                val metadata = paths.distinct().associateWith { path ->
                    // 导出读取失败应显式失败；创建版本的测量回退不等于缺失素材可导出。
                    try { probe.read(current.sessionId, path) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { throw CutExportFailure(CutFailureReason.MEDIA_UNAVAILABLE, failure) }
                }
                current.workspace.verify()
                val plan = CutCompositionPlanner.plan(current.production, current.version, metadata)
                if (!plan.segments.all { segment -> segment.path == null ||
                    (metadata[segment.path]?.durationSeconds ?: segment.durationSeconds) + 0.05 >= segment.durationSeconds }) throw CutExportFailure(CutFailureReason.SOURCE_CHANGED)
                val artifact = exporter.export(current.sessionId, plan) { value ->
                    state(id, CutExportState.Running(value?.takeIf { it.isFinite() }?.coerceIn(0f, 1f)))
                }
                val record = try {
                    current.workspace.verify()
                    output.publish(current.sessionId, plan, artifact.video) { record ->
                        register(current, record)
                        committedRecord = record
                    }
                } finally { artifact.close() }
                state(id, CutExportState.Succeeded(record))
            }
        } catch (cancelled: CancellationException) {
            val committed = committedRecord
            if (committed != null) state(id, CutExportState.Succeeded(committed))
            else if (states.value[id] !is CutExportState.Failed) state(id, CutExportState.Idle)
            throw cancelled
        } catch (failure: Exception) {
            if ((failure as? com.promenar.infinity.domain.storage.ExternalFolderException)?.code == "RECOVERY_REQUIRED") {
                val reference = Regex("cut-output-[A-Za-z0-9-]+").find(failure.message.orEmpty())?.value
                state(id, CutExportState.Failed(messages.recoveryRequired, reference))
                onRecoveryRequired(reference)
            } else {
                val reason = when ((failure as? CutExportFailure)?.reason) {
                    CutFailureReason.MEDIA_UNAVAILABLE -> messages.mediaUnavailable
                    CutFailureReason.UNSUPPORTED_SIZE -> messages.unsupportedSize
                    CutFailureReason.SOURCE_CHANGED -> messages.sourceChanged
                    CutFailureReason.INVALID_OUTPUT -> messages.invalidOutput
                    CutFailureReason.ENCODING_FAILED -> messages.encodingFailed
                    null -> if ((failure as? com.promenar.infinity.domain.storage.ExternalFolderException)?.code == "NO_SPACE") messages.storageFull else messages.failed
                }
                state(id, CutExportState.Failed(reason))
            }
        } finally {
            withContext(NonCancellable) { mutex.withLock { if (request === current) { request = null; worker = null } } }
        }
    }

    private suspend fun register(current: Request, record: CutExportRecord): Unit = withContext(NonCancellable) {
        // 仅重试无关修订；绑定、版本、字幕或项目输入变化均拒绝登记。
        repeat(4) {
            current.workspace.verify()
            val latest = snapshot(current.sessionId)
            require(latest.production == current.production ||
                latest.production.copy(cuts = current.production.cuts, updatedAt = current.production.updatedAt) == current.production) { messages.changed }
            require(latest.production.id == current.production.id && latest.production.version(current.version.id) == current.version) { messages.changed }
            try {
                store.apply(current.sessionId, current.production.id, latest.revision, ProductionUserAction.RegisterCutExport(current.version.id, record))
                return@withContext
            } catch (conflict: ProductionRevisionConflictException) { if (it == 3) throw conflict }
        }
    }
}
