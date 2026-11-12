package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.domain.mediagen.MediaTaskState

sealed interface OpenItem {
    data class FailedTask(val taskId: String, val target: ReviewTarget?, val error: String?) : OpenItem
    data class CanceledTask(val taskId: String, val target: ReviewTarget?) : OpenItem
    data class Rejected(val target: ReviewTarget) : OpenItem
    data class CostAlert(val target: ReviewTarget, val count: GenerationCount) : OpenItem
}

/** 发送后的产出时间和实体修订分别与对应口径比较。 */
fun isSentRejectionUnresolved(production: Production, entry: ReviewEntry): Boolean {
    if (entry.kind != ReviewKind.REJECT || entry.round == null) return false
    val round = production.reviewRounds.firstOrNull { it.number == entry.round }
    val sentAt = entry.sentAt ?: round?.sentAt ?: entry.at
    val sentRevision = entry.sentRevision ?: round?.revision ?: Long.MAX_VALUE
    val target = entry.target
    return when (target.kind) {
        ReviewTargetKind.TAKE -> production.reviewShot(target)?.let { shot ->
            shot.takes.any { it.id == target.takeId } && shot.takes.none { it.id != target.takeId && it.createdAt > sentAt }
        } ?: false
        ReviewTargetKind.ASSET_VERSION -> production.assets.firstOrNull { it.id == target.assetId }?.let { asset ->
            asset.versions.any { it.number == target.version } && asset.versions.none { it.number > requireNotNull(target.version) }
        } ?: false
        ReviewTargetKind.SCRIPT -> production.reviewEpisode(target.episodeId)?.let { (it.contentChangedAt ?: it.changedAt) <= sentRevision } ?: false
        ReviewTargetKind.SCENE -> production.reviewEpisode(target.episodeId)?.scenes?.firstOrNull { it.code == target.sceneCode }?.let { (it.contentChangedAt ?: it.changedAt) <= sentRevision } ?: false
        ReviewTargetKind.CUT -> production.cuts.firstOrNull { it.id == target.cutId }?.let { (it.contentChangedAt ?: it.changedAt) <= sentRevision } ?: false
        ReviewTargetKind.CHANGE_REQUEST -> production.changeRequests.any { it.id == target.requestId && it.rejected }
        else -> false
    }
}

fun productionOpenItems(production: Production, tasks: List<MediaTask>, stats: ProductionCostStats): List<OpenItem> = buildList {
    val projectTasks = tasks.distinctBy { it.id }.filter { production.ownsTask(it) }
    projectTasks.forEach { task ->
        val location = production.taskLocation(task)
        if (task.state == MediaTaskState.FAILED) add(OpenItem.FailedTask(task.id, location?.target, task.error))
        if (task.state == MediaTaskState.CANCELED && task.error == "升级后取消") {
            // 旧任务没有时间时，保留提醒，避免把任意列表顺序当作后续产出证据。
            val replaced = task.createdAt > 0 && projectTasks.any { later ->
                later.id != task.id && later.createdAt > task.createdAt && later.createdAt > 0 &&
                    later.request.sourceRef == task.request.sourceRef && later.state !in setOf(MediaTaskState.CANCELED, MediaTaskState.AWAITING_APPROVAL)
            }
            if (!replaced) add(OpenItem.CanceledTask(task.id, location?.target))
        }
    }
    production.reviews.filter { isSentRejectionUnresolved(production, it) }.forEach { add(OpenItem.Rejected(it.target)) }
    stats.alertShots.forEach { (key, count) -> add(OpenItem.CostAlert(ReviewTarget.shot(key.episodeId, key.shotCode), count)) }
    stats.alertAssets.forEach { (id, count) ->
        val asset = production.assets.firstOrNull { it.id == id }
        val version = asset?.currentVersion ?: asset?.versions?.maxOfOrNull { it.number }
        // 成本目标允许尚无成功版本的资产，用于失败任务已达到阈值的情形。
        add(OpenItem.CostAlert(ReviewTarget(ReviewTargetKind.ASSET_VERSION, assetId = id, version = version), count))
    }
}
