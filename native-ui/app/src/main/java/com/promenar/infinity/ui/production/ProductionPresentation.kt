package com.promenar.infinity.ui.production

import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotStatus
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.data.production.Take

/* 制作面板的纯展示逻辑：统计、待处理清单与定位。界面只读这些结果，不直接遍历快照。 */

/** 面板内的六个流程与管理页签。 */
enum class ProductionTab { OVERVIEW, SCRIPT, ASSETS, STORYBOARD, OUTPUT, CUT }

/** 镜头统计。[pending] 是等待用户处理的数量（待审）。 */
data class ShotStats(
    val total: Int,
    val todo: Int,
    val generating: Int,
    val review: Int,
    val locked: Int,
    val redo: Int,
) {
    val pending: Int get() = review

    /** 已定比例，0 到 1；没有镜头时为 0。 */
    val progress: Float get() = if (total == 0) 0f else locked.toFloat() / total

    companion object {
        val Empty = ShotStats(0, 0, 0, 0, 0, 0)

        fun of(shots: Sequence<Shot>): ShotStats {
            var total = 0; var todo = 0; var generating = 0; var review = 0; var locked = 0; var redo = 0
            shots.forEach { shot ->
                total++
                when (shot.status) {
                    ShotStatus.TODO -> todo++
                    ShotStatus.GENERATING -> generating++
                    ShotStatus.REVIEW -> review++
                    ShotStatus.LOCKED -> locked++
                    ShotStatus.REDO -> redo++
                }
            }
            return ShotStats(total, todo, generating, review, locked, redo)
        }
    }
}

/** 镜头在项目里的位置。 */
data class ShotLocation(val episode: Episode, val scene: Scene, val shot: Shot)

/** 待用户处理的一项。 */
sealed interface PendingItem {
    data class ShotReview(val location: ShotLocation) : PendingItem
    data class AssetReview(val asset: Asset) : PendingItem
}

fun Production.episodes(): List<Episode> = seasons.flatMap { it.episodes }

fun Episode.shots(): Sequence<Shot> = scenes.asSequence().flatMap { it.shots.asSequence() }

fun Production.shotLocations(): Sequence<ShotLocation> = episodes().asSequence().flatMap { episode ->
    episode.scenes.asSequence().flatMap { scene -> scene.shots.asSequence().map { ShotLocation(episode, scene, it) } }
}

fun Production.shotStats(): ShotStats = ShotStats.of(shotLocations().map { it.shot })

fun Episode.shotStats(): ShotStats = ShotStats.of(shots())

fun Scene.shotStats(): ShotStats = ShotStats.of(shots.asSequence())

/** 等待用户处理的事项：先镜头后资产，各自保持项目顺序。 */
fun Production.pendingItems(): List<PendingItem> =
    shotLocations().filter { it.shot.status == ShotStatus.REVIEW }.map { PendingItem.ShotReview(it) }.toList() +
        assets.filter { it.status == AssetStatus.REVIEW }.map { PendingItem.AssetReview(it) }

/** 功能面板入口的待处理角标数。 */
fun Production.pendingCount(): Int =
    shotLocations().count { it.shot.status == ShotStatus.REVIEW }

/** 是否有正在生成的镜头；入口据此显示运行点。 */
fun Production.isGenerating(): Boolean = shotLocations().any { it.shot.status == ShotStatus.GENERATING }

/** 智能体打回或用户要求重做、仍在等智能体处理的镜头。 */
fun Production.redoShots(): List<ShotLocation> = shotLocations().filter { it.shot.status == ShotStatus.REDO }.toList()

/** 流程当前所在阶段：第一个既未锁定也未跳过的阶段；全部完成时为 null。 */
fun Production.currentStage(): ProductionStage? =
    stages.firstOrNull { it.status != StageStatus.LOCKED && it.status != StageStatus.SKIPPED }?.stage

/** 界面展示的阶段：关闭动态预演时不显示该阶段。 */
fun Production.visibleStages() = stages.filter { previewEnabled || it.stage != ProductionStage.ANIMATIC }

fun Production.findShot(episodeId: String, shotCode: String): ShotLocation? =
    shotLocations().firstOrNull { it.episode.id == episodeId && it.shot.code == shotCode }

fun Production.findAsset(assetId: String): Asset? = assets.firstOrNull { it.id == assetId }

/** 引用该资产的镜头数。 */
fun Production.shotsUsingAsset(assetId: String): Int =
    findAsset(assetId)?.referenceLocations(this)?.size ?: 0

fun Shot.selectedTake(): Take? = selectedTakeId?.let { id -> takes.firstOrNull { it.id == id } }

/** 镜头默认展示的候选：已选定的，否则最新一条。 */
fun Shot.displayTake(): Take? = selectedTake() ?: takes.maxByOrNull { it.createdAt }

/** 镜头列表与卡片用的缩略图路径：展示候选，否则预演素材，否则首个输入。 */
fun Shot.thumbnailPath(): String? = displayTake()?.path ?: previewPath ?: inputs.firstOrNull()?.path

fun Asset.currentPath(): String? =
    (versions.firstOrNull { it.number == currentVersion } ?: versions.maxByOrNull { it.number })?.path

/** 分镜页默认选中的集：有待审镜头的第一集，否则第一集。 */
fun Production.defaultEpisodeId(): String? {
    val episodes = episodes()
    return (episodes.firstOrNull { it.shotStats().pending > 0 } ?: episodes.firstOrNull())?.id
}

/** 时长显示：整数秒不带小数，其余保留一位。 */
fun formatShotSeconds(seconds: Double): String {
    val rounded = Math.round(seconds * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}
