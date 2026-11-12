package com.promenar.infinity.ui.production

import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*

/** 接线由宿主负责，sourceRef 按规划器实际契约提供，避免跨项目混入任务。 */
data class ProductionPanelServices(
    val planner: ShotGenerationPlanner? = null,
    val queue: MediaGenerationQueue? = null,
    val registry: MediaChannelRegistry? = null,
    val projectSourcePrefix: ((Production) -> String)? = null,
    val shotSourceRef: ((Production, ShotLocation) -> String)? = null,
    val channelLabel: (String) -> String = { it },
    val onOpenMediaModels: (() -> Unit)? = null,
    /** 主导航打开任意页面；给出时制作面板在自身容器内打开媒体设置子页，[onOpenMediaModels] 由面板填入。 */
    val openRoute: ((String) -> Unit)? = null,
    val onAddShot: ((String, String) -> Unit)? = null,
    val requestContentChange: (suspend (Long, ShotLocation, Shot) -> String?)? = null,
    val pendingRegistrations: Set<String> = emptySet(),
    val cutExport: com.promenar.infinity.data.cut.CutExportController? = null,
    /** 把本轮审核经应用级派发器发到项目绑定的会话。 */
    val sendReview: (suspend () -> com.promenar.infinity.data.production.ReviewDispatch)? = null,
    /** 打开面板时核对正在排队的审核轮次。 */
    val reconcileReviews: (suspend () -> Unit)? = null,
    /** 打开面板时扫描项目目录里的未登记文件。 */
    val auditFiles: (suspend () -> Unit)? = null,
)

enum class ShotDetailTab { STORYBOARD, CANDIDATES }
enum class OutputFilter { ALL, REVIEW, REDO, LOCKED, TODO }
data class ProductionTabSignals(val storyboardDot: Boolean, val reviews: Int)

fun projectTasks(tasks: List<MediaTask>, prefix: String?): List<MediaTask> {
    val projectId = prefix?.let { ProductionSourceRef.projectIdFromPrefix(it) } ?: return emptyList()
    return tasks.filter { ProductionSourceRef.parse(it.request.sourceRef)?.productionId == projectId }
}

fun Production.tabSignals(): ProductionTabSignals = ProductionTabSignals(
    storyboardDot = episodes().any { episode -> episode.scenes.any { !it.storyboardLocked } },
    reviews = shotLocations().count { it.shot.status == ShotStatus.REVIEW },
)

fun Shot.defaultDetailTab() = if (takes.isEmpty()) ShotDetailTab.STORYBOARD else ShotDetailTab.CANDIDATES

/** 未锁定场在全部筛选中只保留一个入口；试验项目允许展示每个镜头。 */
sealed interface OutputItem {
    data class UnlockedScene(val episode: Episode, val scene: Scene) : OutputItem
    data class ShotRow(val location: ShotLocation) : OutputItem
}
fun Production.outputItems(filter: OutputFilter): List<OutputItem> = buildList {
    episodes().forEach { episode -> episode.scenes.forEach { scene ->
        val unlocked = !experimental && !scene.storyboardLocked
        // 未锁定场先占一行提示；已有候选的镜头仍列出，审片入口才能找到它们。
        if (unlocked && filter == OutputFilter.ALL) add(OutputItem.UnlockedScene(episode, scene))
        scene.shots.filter { shot -> !unlocked || shot.takes.isNotEmpty() }.filter { shot ->
            when (filter) {
                OutputFilter.ALL -> true
                OutputFilter.REVIEW -> shot.status == ShotStatus.REVIEW
                OutputFilter.REDO -> shot.status == ShotStatus.REDO
                OutputFilter.LOCKED -> shot.status == ShotStatus.LOCKED
                OutputFilter.TODO -> shot.status == ShotStatus.TODO && shot.takes.isEmpty()
            }
        }.forEach { add(OutputItem.ShotRow(ShotLocation(episode, scene, it))) }
    } }
}

/** 总览“待你处理”的条目：待审的镜头候选、场、资产、剪辑版本与改动请求。 */
sealed interface OverviewItem {
    data class Review(val count: Int) : OverviewItem
    data class Redo(val count: Int) : OverviewItem
    data class SceneLock(val episode: Episode, val scene: Scene) : OverviewItem
    data class ReferenceReview(val asset: Asset) : OverviewItem
    data class ChangeReview(val request: ShotChangeRequest) : OverviewItem
    data class CutReview(val cut: CutVersion) : OverviewItem
}
fun Production.overviewItems(): List<OverviewItem> = buildList {
    val signals = tabSignals()
    if (signals.reviews > 0) add(OverviewItem.Review(signals.reviews))
    val redo = redoShots().size
    if (redo > 0) add(OverviewItem.Redo(redo))
    if (!experimental) episodes().forEach { episode -> episode.scenes.filter { it.awaitsReview(this@overviewItems, episode) }.forEach { add(OverviewItem.SceneLock(episode, it)) } }
    assets.filter { it.status == AssetStatus.REVIEW }.forEach { add(OverviewItem.ReferenceReview(it)) }
    changeRequests.filter { it.awaitsReview(this@overviewItems) }.forEach { add(OverviewItem.ChangeReview(it)) }
    // 每集只列最新一个还没有结论的剪辑版本。
    cuts.groupBy { it.episodeId }.values.mapNotNull { versions -> versions.maxByOrNull { it.number } }
        .filter { verdictOf(ReviewTarget.cut(it.id)) == null }
        .forEach { add(OverviewItem.CutReview(it)) }
}

/** 场待审：未定稿，或这一轮刚给出、尚未发送的结论（发送前仍可在总览里改）。 */
internal fun Scene.awaitsReview(production: Production, episode: Episode): Boolean {
    val verdict = production.verdictOf(ReviewTarget.scene(episode.id, code))
    return if (verdict != null) verdict.round == null else !storyboardLocked
}

/** 改动请求待审：没有结论且未应用，或结论尚未发送。 */
internal fun ShotChangeRequest.awaitsReview(production: Production): Boolean {
    val verdict = production.verdictOf(ReviewTarget.change(id))
    return if (verdict != null) verdict.round == null else !approved
}

fun ShotMode.generationMode(): GenerationMode = when (this) {
    ShotMode.TEXT -> GenerationMode.TEXT
    ShotMode.FIRST_FRAME -> GenerationMode.FIRST_FRAME
    ShotMode.FIRST_LAST -> GenerationMode.FIRST_LAST_FRAME
    ShotMode.REFERENCE -> GenerationMode.REFERENCE
}

/** 注册表的显式顺序优先，尚未登记顺序的通道按观察列表顺序补在末尾。 */
fun channelChoices(channels: List<MediaChannel>, order: List<String>, mode: ShotMode): List<MediaChannel> {
    val supported = channels.filter { it.group == MediaGroup.VIDEO && mode.generationMode() in it.capabilities.modes }.distinctBy { it.id }
    return order.distinct().mapNotNull { id -> supported.firstOrNull { it.id == id } } + supported.filter { it.id !in order }
}


/** 参考位列表由当前造型定义；切换造型默认选中实际存在的默认位。 */
fun ShotAppearance.switchLook(look: CharacterLook) = copy(lookId = look.id, referenceSlotIds = ProductionRules.defaultSlotIds(look))
fun ShotAppearance.toggleSlot(look: CharacterLook, slotId: String): ShotAppearance {
    require(look.id == lookId && look.slots.any { it.id == slotId })
    return copy(referenceSlotIds = if (slotId in referenceSlotIds) referenceSlotIds - slotId else (referenceSlotIds + slotId).distinct())
}
fun ShotAppearance.validSlotIds(look: CharacterLook) = referenceSlotIds.filter { id -> look.slots.any { it.id == id } }.distinct()
fun Asset.referenceLocations(production: Production): List<ShotLocation> = production.shotLocations().filter { location ->
    val shot = location.shot
    shot.appearances.any { it.characterAssetId == id } || shot.inputs.any { it.assetId == id } || shot.locationAssetId == id || id in shot.propAssetIds
}.toList()

/** 规划摘要只拼接当前有效数据；本地化词汇由界面传入。 */
fun planSummary(channel: String, mode: String, packing: String?, edited: Boolean, editedLabel: String): String =
    listOfNotNull(channel.takeIf { it.isNotBlank() }, mode, packing?.takeIf { it.isNotBlank() }, editedLabel.takeIf { edited }).joinToString(" · ")

/** 进行中的任务可以取消；这是面板上唯一立即生效的动作。 */
fun MediaTask.canCancel(): Boolean = isActiveTask()
fun MediaTask.awaitingRegistration(pendingIds: Set<String>) = state == MediaTaskState.COMPLETED && id in pendingIds
fun List<MediaTask>.overviewQueueTasks(pendingIds: Set<String>) = filter { it.isActiveTask() || it.awaitingRegistration(pendingIds) }
fun List<MediaTask>.recentQueueTasks(pendingIds: Set<String>) = filter {
    !it.isActiveTask() && !it.awaitingRegistration(pendingIds)
}.takeLast(20).reversed()
fun MediaTask.isActiveTask() = state.name in setOf("QUEUED", "SUBMITTING", "RUNNING", "DOWNLOADING")

/** 剪辑版本由成片层注入；预演与当前选定候选可直接由项目快照浏览。 */
data class ProductionCutVersion(val id: String, val title: String, val path: String?, val clips: List<ProductionCutClip>, val specs: String? = null)
data class ProductionCutClip(val episodeId: String, val shotCode: String, val title: String, val seconds: Double, val path: String?)
fun Production.cutClips(preview: Boolean): List<ProductionCutClip> = shotLocations().map { location ->
    val shot = location.shot
    ProductionCutClip(location.episode.id, shot.code, shot.title, shot.durationSeconds, if (preview) shot.previewPath else shot.selectedTake()?.path)
}.toList()

fun Production.stageProgressCounts(stage: ProductionStage): Pair<Int, Int>? {
    val episodes = episodes()
    return when (stage) {
        ProductionStage.SCRIPT -> episodes.count { it.scriptLocked } to episodes.size
        ProductionStage.VISUAL_DEV -> assets.count { it.status == AssetStatus.LOCKED } to assets.size
        ProductionStage.STORYBOARD -> episodes.flatMap { it.scenes }.let { scenes -> scenes.count { it.storyboardLocked } to scenes.size }
        ProductionStage.ANIMATIC -> shotLocations().count { it.shot.previewPath != null } to shotStats().total
        ProductionStage.GENERATION -> shotStats().locked to shotStats().total
        ProductionStage.SOUND -> assets.filter { it.kind == AssetKind.SOUND }.let { sounds -> sounds.count { it.status == AssetStatus.LOCKED } to sounds.size }
        ProductionStage.EDIT -> null
    }
}

fun resolvedShotChannel(shot: Shot, channels: List<MediaChannel>, order: List<String>): MediaChannel? {
    val choices = channelChoices(channels, order, shot.mode)
    return if (shot.channelId == null) choices.firstOrNull() else choices.firstOrNull { it.id == shot.channelId }
}

fun Production.importedReferencePaths(shot: Shot): List<String> = (
    shot.appearances.flatMap { appearance -> findAsset(appearance.characterAssetId)?.looks?.firstOrNull { it.id == appearance.lookId }?.slots?.filter { it.id in appearance.referenceSlotIds }?.mapNotNull { it.path }.orEmpty() } +
        shot.inputs.map { it.path } + listOfNotNull(shot.locationAssetId?.let { findAsset(it)?.currentPath() }) + shot.propAssetIds.mapNotNull { findAsset(it)?.currentPath() }
    ).distinct()

fun modeSelectionAction(location: ShotLocation, mode: ShotMode, channels: List<MediaChannel>, order: List<String>): ProductionUserAction.SetShotPlan {
    val previous = location.shot.channelId
    val retained = previous?.takeIf { id -> channelChoices(channels, order, mode).any { it.id == id } }
    return ProductionUserAction.SetShotPlan(location.episode.id, location.shot.code, retained, mode)
}
