package com.promenar.infinity.data.production

import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive

class ProductionRunService(
    private val store: ProductionWorkStore,
    private val probe: suspend (String, String) -> MediaSpec,
    private val exists: suspend (String, String) -> Boolean,
    private val audit: suspend (String) -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private suspend fun scanAfterReport(sessionId: String) {
        try { audit(sessionId) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled } catch (_: Exception) { /* 由回合结束与面板打开的扫描补查。 */ }
    }
    private suspend fun load(sessionId: String) = requireNotNull(store.load(sessionId)) { "当前会话没有制作项目。" }
    private fun active(p: Production, id: String) = requireNotNull(p.runs.firstOrNull { it.id == id }) { "登记项不存在。" }
        .also { require(it.isActive()) { "登记项当前为 ${it.state}，不能再回报。" } }

    suspend fun start(sessionId: String, target: RunTarget, label: String, steps: Int? = null): ProductionRun = withContext(Dispatchers.IO) {
        val snapshot = load(sessionId)
        snapshot.production.validateRunTarget(target)
        require(label.isNotBlank() && label.length <= 120 && (steps == null || steps > 0)) { "label 需要 1～120 字，steps 需要正整数。" }
        val time = now()
        val run = ProductionRun(newId(), target, label, steps, startedAt = time, reportedAt = time)
        store.apply(sessionId, snapshot, ProductionUserAction.SaveRun(run))
        run
    }
    suspend fun progress(sessionId: String, runId: String, completedSteps: Int, message: String): ProductionRun = withContext(Dispatchers.IO) {
        val snapshot = load(sessionId)
        val old = active(snapshot.production, runId)
        require(completedSteps >= old.completedSteps && (old.steps == null || completedSteps <= old.steps)) { "完成步数不能倒退或超过总步数。" }
        require(message.isNotBlank() && message.length <= 1000) { "进度说明需要 1～1000 字。" }
        old.copy(completedSteps = completedSteps, message = message, reportedAt = maxOf(now(), old.reportedAt)).also {
            store.apply(sessionId, snapshot, ProductionUserAction.SaveRun(it))
        }
    }
    suspend fun fail(sessionId: String, runId: String, reason: String): ProductionRun = withContext(Dispatchers.IO) {
        val snapshot = load(sessionId)
        val old = active(snapshot.production, runId)
        require(reason.isNotBlank() && reason.length <= 1000) { "失败原因需要 1～1000 字。" }
        val run = old.copy(state = RunState.FAILED, failure = reason, reportedAt = maxOf(now(), old.reportedAt))
        store.apply(sessionId, snapshot, ProductionUserAction.SaveRun(run))
        scanAfterReport(sessionId)
        run
    }
    suspend fun finish(sessionId: String, runId: String, path: String, sourceTakeId: String? = null, operation: String? = null,
        params: Map<String, JsonPrimitive> = emptyMap(), segmentPaths: List<String> = emptyList()): ProductionRun = withContext(Dispatchers.IO) {
        val snapshot = load(sessionId)
        val p = snapshot.production
        val old = active(p, runId)
        require((sourceTakeId == null) == (operation == null)) { "来源候选与处理名称须同时提供。" }
        require(params.isEmpty() || sourceTakeId != null) { "处理参数需要来源候选与处理名称。" }
        require(operation == null || operation.isNotBlank() && operation.length <= 120) { "处理名称需要 1～120 字。" }
        for (file in listOf(path) + segmentPaths) {
            ProductionCuts.validateProjectPath(p, file)
            require(exists(sessionId, file)) { "产物不可读或媒体类型无效：$file" }
        }
        val spec = probe(sessionId, path)
        val derivation = sourceTakeId?.let { source ->
            require(p.seasons.flatMap { it.episodes }.flatMap { it.scenes }.flatMap { it.shots }.any { shot -> shot.takes.any { it.id == source } }) { "来源候选不存在。" }
            TakeDerivation(source, requireNotNull(operation), params = params)
        }
        var cursor = 0.0
        val segments = segmentPaths.mapIndexed { index, segment ->
            val seconds = requireNotNull(probe(sessionId, segment).durationSeconds) { "无法读取分段实际时长。" }
            require(seconds.isFinite() && seconds > 0) { "分段时长无效。" }
            TakeSegment(index, segment, cursor, cursor + seconds).also { cursor += seconds }
        }
        if (segments.isNotEmpty()) require(spec.durationSeconds?.let { kotlin.math.abs(it - cursor) <= 0.2 } == true) { "分段时长与产物时长不一致。" }
        val time = maxOf(now(), old.reportedAt)
        var take: Take? = null
        var version: AssetVersion? = null
        if (old.target.assetId != null) {
            val asset = p.assets.first { it.id == old.target.assetId }
            require(asset.kind != AssetKind.SOUND || mediaKindFromPath(path) == com.promenar.infinity.domain.mediagen.InputKind.AUDIO) { "声音资产产物必须是音频。" }
            version = AssetVersion((asset.versions.maxOfOrNull { it.number } ?: 0) + 1, path, time, durationSeconds = spec.durationSeconds, derivedFrom = derivation)
        } else {
            val shot = p.requiredShot(requireNotNull(old.target.episodeId), requireNotNull(old.target.shotCode))
            require(derivation == null || shot.takes.any { it.id == derivation.sourceTakeId }) { "来源候选须属于登记镜头。" }
            require(mediaKindFromPath(path) == com.promenar.infinity.domain.mediagen.InputKind.VIDEO) { "镜头产物必须是视频。" }
            take = Take(newId(), path, TakeSource("外部脚本"), time, derivedFrom = derivation, segments = segments, spec = spec, runId = old.id)
        }
        val run = old.copy(state = RunState.COMPLETED, completedSteps = old.steps ?: old.completedSteps, reportedAt = time,
            resultTakeId = take?.id, resultAssetVersion = version?.number, resultPath = path, segments = segments)
        store.apply(sessionId, snapshot, ProductionUserAction.SaveRun(run, take, version))
        scanAfterReport(sessionId)
        run
    }
}
