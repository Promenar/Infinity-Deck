package com.promenar.infinity.data.production

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class MediaSpec(val width: Int? = null, val height: Int? = null, val fps: Double? = null, val durationSeconds: Double? = null)

/** 派生候选的直接来源；允许从派生候选再派生。 */
@Serializable
data class TakeDerivation(val sourceTakeId: String, val operation: String, val channelId: String? = null, val params: Map<String, JsonPrimitive> = emptyMap())

/** 时间为拼接成片中的起止秒数。 */
@Serializable
data class TakeSegment(val index: Int, val path: String, val startSeconds: Double, val endSeconds: Double)

@Serializable
enum class ChainState { @SerialName("planned") PLANNED, @SerialName("running") RUNNING, @SerialName("handoff") HANDOFF, @SerialName("concatenating") CONCATENATING, @SerialName("completed") COMPLETED, @SerialName("failed") FAILED, @SerialName("canceled") CANCELED }
@Serializable
enum class ChainSegmentState { @SerialName("pending") PENDING, @SerialName("running") RUNNING, @SerialName("completed") COMPLETED, @SerialName("failed") FAILED, @SerialName("canceled") CANCELED }

@Serializable
data class ChainSegment(
    val index: Int, val seconds: Double, val prompt: String? = null,
    val state: ChainSegmentState = ChainSegmentState.PENDING, val taskId: String? = null,
    val path: String? = null, val firstFramePath: String? = null, val failure: String? = null,
    val taskOutputPath: String? = null,
)
@Serializable
data class ShotChain(
    val id: String, val state: ChainState, val segments: List<ChainSegment>, val current: Int = 0,
    val startTakeId: String? = null, val channelId: String? = null, val templateFingerprint: String? = null,
    val failure: String? = null, val takeId: String? = null, val createdAt: Long, val updatedAt: Long,
    /** 冻结首段规划与重试代次，恢复时按代次寻找已入队任务。 */
    val planSnapshot: TakePlanSnapshot? = null, val attempt: Int = 0, val outputPath: String? = null,
)
@Serializable
enum class RunState { @SerialName("running") RUNNING, @SerialName("completed") COMPLETED, @SerialName("failed") FAILED, @SerialName("canceled") CANCELED }
@Serializable
data class RunTarget(val episodeId: String? = null, val shotCode: String? = null, val assetId: String? = null)
@Serializable
data class ProductionRun(
    val id: String, val target: RunTarget, val label: String, val steps: Int? = null, val completedSteps: Int = 0,
    val message: String? = null, val state: RunState = RunState.RUNNING, val failure: String? = null,
    val startedAt: Long, val reportedAt: Long, val resultTakeId: String? = null, val resultAssetVersion: Int? = null,
    /** 保留外部产物引用，供文件审计与资产版本来源恢复使用。 */
    val resultPath: String? = null, val segments: List<TakeSegment> = emptyList(),
)

const val RUN_UNREPORTED_MILLIS = 30 * 60_000L
fun ShotChain.isActive(): Boolean = state in setOf(ChainState.PLANNED, ChainState.RUNNING, ChainState.HANDOFF, ChainState.CONCATENATING)
fun ProductionRun.isActive(): Boolean = state == RunState.RUNNING
fun ProductionRun.isUnreported(now: Long): Boolean = isActive() && now >= reportedAt && now - reportedAt >= RUN_UNREPORTED_MILLIS

internal fun Production.requiredShot(episodeId: String, shotCode: String): Shot = seasons.flatMap { it.episodes }
    .firstOrNull { it.id == episodeId }?.scenes?.flatMap { it.shots }?.firstOrNull { it.code == shotCode }
    ?: throw IllegalArgumentException("集或镜头不存在：$episodeId/$shotCode")

internal fun Production.validateRunTarget(target: RunTarget) {
    if (target.assetId != null) {
        require(target.episodeId == null && target.shotCode == null && assets.any { it.id == target.assetId }) { "登记项需要存在的资产，且不能同时指定镜头。" }
    } else {
        requiredShot(requireNotNull(target.episodeId) { "登记项缺少集。" }, requireNotNull(target.shotCode) { "登记项缺少镜头。" })
    }
}

internal fun Asset.addProductionVersion(version: AssetVersion): Asset {
    require(versions.size < 30) { "资产版本已达 30 个。" }
    require(version.number == (versions.maxOfOrNull { it.number } ?: 0) + 1) { "资产版本号已变化，请重试。" }
    return copy(versions = versions + version, currentVersion = if (status == AssetStatus.LOCKED) currentVersion else version.number,
        status = if (status == AssetStatus.LOCKED) status else AssetStatus.REVIEW)
}
internal fun Shot.addProductionTake(take: Take): Shot {
    if (takes.any { it.id == take.id }) return this
    require(takes.size < 30) { "候选已达 30 个。" }
    return copy(takes = takes + take, status = if (status in setOf(ShotStatus.LOCKED, ShotStatus.REDO)) status else ShotStatus.REVIEW)
}

internal fun applyProductionProgress(p: Production, action: ProductionUserAction, now: Long): Production = when (action) {
    is ProductionUserAction.CancelChain -> {
        val shot = p.requiredShot(action.episodeId, action.shotCode)
        val chain = shot.chains.firstOrNull { it.id == action.chainId } ?: throw IllegalArgumentException("衔接链不存在。")
        require(chain.isActive()) { "衔接链当前为 ${chain.state}，无法取消。" }
        p.mapShot(action.episodeId, action.shotCode) { it.copy(chains = it.chains.map { c -> if (c.id != chain.id) c else c.copy(
            state = ChainState.CANCELED, updatedAt = now, segments = c.segments.map { s -> if (s.state == ChainSegmentState.COMPLETED) s else s.copy(state = ChainSegmentState.CANCELED) }) }) }
    }
    is ProductionUserAction.CancelRun -> {
        val run = p.runs.firstOrNull { it.id == action.runId } ?: throw IllegalArgumentException("登记项不存在。")
        require(run.isActive()) { "登记项当前为 ${run.state}，无法取消。" }
        p.copy(runs = p.runs.map { if (it.id == run.id) it.copy(state = RunState.CANCELED, message = "用户已取消", reportedAt = now) else it })
    }
    is ProductionUserAction.SaveChain -> {
        val shot = p.requiredShot(action.episodeId, action.shotCode)
        require(shot.chains.none { it.id != action.chain.id && it.isActive() } || !action.chain.isActive()) { "该镜头已有进行中的衔接链。" }
        p.mapShot(action.episodeId, action.shotCode) { old ->
            val updated = action.take?.let(old::addProductionTake) ?: old
            updated.copy(chains = updated.chains.filterNot { it.id == action.chain.id } + action.chain)
        }
    }
    is ProductionUserAction.SaveRun -> {
        p.validateRunTarget(action.run.target)
        p.runs.firstOrNull { it.id == action.run.id }?.let { old -> require(old.isActive()) { "登记项当前为 ${old.state}，不能再回报。" }; require(old.target == action.run.target) { "登记目标不能改变。" } }
        var result = p
        action.take?.let { take -> result = result.mapShot(requireNotNull(action.run.target.episodeId), requireNotNull(action.run.target.shotCode)) { it.addProductionTake(take) } }
        action.assetVersion?.let { version -> result = result.copy(assets = result.assets.map { if (it.id == action.run.target.assetId) it.addProductionVersion(version) else it }) }
        result.copy(runs = result.runs.filterNot { it.id == action.run.id } + action.run)
    }
    is ProductionUserAction.SetUnregisteredFiles -> p.copy(unregisteredFiles = action.paths.distinct().sorted())
    else -> error("不支持的制作进度动作")
}
