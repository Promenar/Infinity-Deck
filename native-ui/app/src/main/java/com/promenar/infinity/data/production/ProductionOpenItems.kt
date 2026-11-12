package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.MediaTask
import com.promenar.infinity.domain.mediagen.MediaTaskState

sealed interface OpenItem {
    data class Run(val run: ProductionRun, val unreported: Boolean) : OpenItem
    data class Chain(val episodeId: String, val shotCode: String, val chain: ShotChain) : OpenItem
    data class UnregisteredFile(val path: String) : OpenItem
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

fun productionOpenItems(production: Production, tasks: List<MediaTask>, stats: ProductionCostStats, now: Long = System.currentTimeMillis()): List<OpenItem> = buildList {
    production.runs.filter { it.state != RunState.COMPLETED }.forEach { add(OpenItem.Run(it, it.isUnreported(now))) }
    production.seasons.flatMap { it.episodes }.forEach { ep -> ep.scenes.flatMap { it.shots }.forEach { shot ->
        shot.chains.filter { it.isActive() || it.state == ChainState.FAILED }.forEach { add(OpenItem.Chain(ep.id, shot.code, it)) }
    } }
    production.unregisteredFiles.forEach { add(OpenItem.UnregisteredFile(it)) }
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

/** 审核发送与每轮上下文共享的简短描述。 */
fun productionProgressItemText(item: OpenItem): String? = when (item) {
    is OpenItem.Run -> "登记 ${item.run.id} · ${item.run.label} · " + when {
        item.unreported -> "超过 30 分钟未回报"
        item.run.state == RunState.CANCELED -> "用户已取消（外部脚本需自行停止）"
        item.run.state == RunState.FAILED -> "失败：${item.run.failure.orEmpty()}"
        else -> "进行中 ${item.run.completedSteps}/${item.run.steps ?: "?"} · ${item.run.message.orEmpty()}"
    }
    is OpenItem.Chain -> "衔接链 ${item.chain.id} · ${item.episodeId}/${item.shotCode} · ${item.chain.state} · 第 ${item.chain.current + 1}/${item.chain.segments.size} 段" + (item.chain.failure?.let { " · $it" } ?: "")
    is OpenItem.UnregisteredFile -> "未登记媒体：${item.path}"
    else -> null
}

/** maxChars 包括读取提示；超出时同时给出总数和省略数。 */
fun productionOpenItemsSummary(production: Production, tasks: List<MediaTask> = emptyList(), now: Long = System.currentTimeMillis(), maxChars: Int = 2400): String {
    require(maxChars >= 180) { "未结事项摘要长度至少 180 字符。" }
    val items = productionOpenItems(production, tasks, productionCostStats(production, tasks), now)
    if (items.isEmpty()) return ""
    val lines = mutableListOf<String>()
    var used = 0
    for (item in items.take(12)) {
        val text = productionProgressItemText(item) ?: when (item) {
            is OpenItem.FailedTask -> "失败任务 ${item.taskId}：${item.error.orEmpty()}"
            is OpenItem.CanceledTask -> "升级后取消的任务 ${item.taskId}"
            is OpenItem.Rejected -> "尚未处理的否决：${item.target}"
            is OpenItem.CostAlert -> "生成次数提醒：${item.target} · ${item.count.total} 次"
            else -> ""
        }
        val line = "- " + text.take(300)
        if (used + line.length + 1 > maxChars - 160) break
        lines += line; used += line.length + 1
    }
    return (listOf("制作未结事项（共 ${items.size} 条）：") + lines +
        if (lines.size < items.size) listOf("省略 ${items.size - lines.size} 条；使用 production_get 的 changes 范围，按 limit、offset 分页读取。") else emptyList()).joinToString("\n").take(maxChars)
}
