package com.promenar.infinity.ui.production

import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*
import java.math.BigDecimal

/** 接线由宿主负责，sourceRef 按规划器实际契约提供，避免跨项目混入任务。 */
data class ProductionPanelServices(
    val planner: ShotGenerationPlanner? = null,
    val queue: MediaGenerationQueue? = null,
    val registry: MediaChannelRegistry? = null,
    val projectSourcePrefix: ((Production) -> String)? = null,
    val shotSourceRef: ((Production, ShotLocation) -> String)? = null,
    val channelLabel: (String) -> String = { it },
    val onOpenMediaModels: (() -> Unit)? = null,
    val onAddShot: ((String, String) -> Unit)? = null,
    val requestContentChange: (suspend (Long, ShotLocation, Shot) -> String?)? = null,
    val pendingRegistrations: Set<String> = emptySet(),
    val cutVersions: List<ProductionCutVersion> = emptyList(),
    val onCreateCut: (() -> Unit)? = null,
    val onExportCut: ((String) -> Unit)? = null,
)

enum class ShotDetailTab { STORYBOARD, CANDIDATES }
enum class OutputFilter { ALL, REVIEW, REDO, LOCKED, TODO }
enum class QueueAction { APPROVE, REJECT, RETRY }
data class ProductionTabSignals(val approvals: Int, val storyboardDot: Boolean, val reviews: Int) {
    val entryBadge: Int get() = approvals + reviews
}

fun projectTasks(tasks: List<MediaTask>, prefix: String?): List<MediaTask> {
    val projectId = prefix?.let { ProductionSourceRef.projectIdFromPrefix(it) } ?: return emptyList()
    return tasks.filter { ProductionSourceRef.parse(it.request.sourceRef)?.productionId == projectId }
}

fun Production.tabSignals(tasks: List<MediaTask>): ProductionTabSignals = ProductionTabSignals(
    approvals = tasks.count { it.state == MediaTaskState.AWAITING_APPROVAL },
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

sealed interface OverviewItem {
    data class Review(val count: Int) : OverviewItem
    data class Approval(val task: MediaTask) : OverviewItem
    data class Redo(val count: Int) : OverviewItem
    data class SceneLock(val episode: Episode, val scene: Scene) : OverviewItem
    data class ReferenceReview(val asset: Asset) : OverviewItem
    data class ChangeReview(val request: ShotChangeRequest) : OverviewItem
}
fun Production.overviewItems(tasks: List<MediaTask>): List<OverviewItem> = buildList {
    val signals = tabSignals(tasks)
    if (signals.reviews > 0) add(OverviewItem.Review(signals.reviews))
    tasks.filter { it.state == MediaTaskState.AWAITING_APPROVAL }.forEach { add(OverviewItem.Approval(it)) }
    val redo = redoShots().size
    if (redo > 0) add(OverviewItem.Redo(redo))
    if (!experimental) episodes().forEach { episode -> episode.scenes.filterNot { it.storyboardLocked }.forEach { add(OverviewItem.SceneLock(episode, it)) } }
    assets.filter { it.status == AssetStatus.REVIEW }.forEach { add(OverviewItem.ReferenceReview(it)) }
    changeRequests.filterNot { it.approved }.forEach { add(OverviewItem.ChangeReview(it)) }
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

fun sceneLockAction(location: ShotLocation): ProductionUserAction.LockScene =
    ProductionUserAction.LockScene(location.episode.id, location.scene.code, !location.scene.storyboardLocked)
fun Scene.requiresLockConfirmation() = !storyboardLocked && shots.isNotEmpty()
fun changeApprovalAction(request: ShotChangeRequest, approve: Boolean) = ProductionUserAction.ResolveChange(request.id, approve)

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

fun generationEnabled(sceneLocked: Boolean, experimental: Boolean, preview: ShotPlanPreview?, busy: Boolean): Boolean =
    !busy && (sceneLocked || experimental) && preview != null && preview.channel != null && preview.blockedReason == null

fun MediaTask.allowedQueueActions(): Set<QueueAction> = when (state.name) {
    "AWAITING_APPROVAL" -> setOf(QueueAction.REJECT, QueueAction.APPROVE)
    "FAILED", "DOWNLOAD_FAILED" -> setOf(QueueAction.RETRY)
    else -> emptySet()
}
fun MediaTask.awaitingRegistration(pendingIds: Set<String>) = state == MediaTaskState.COMPLETED && id in pendingIds
fun List<MediaTask>.overviewQueueTasks(pendingIds: Set<String>) = filter { it.isActiveTask() || it.awaitingRegistration(pendingIds) }
fun List<MediaTask>.recentQueueTasks(pendingIds: Set<String>) = filter {
    !it.isActiveTask() && !it.awaitingRegistration(pendingIds) && it.state != MediaTaskState.AWAITING_APPROVAL
}.takeLast(20).reversed()
fun MediaTask.isActiveTask() = state.name in setOf("QUEUED", "SUBMITTING", "RUNNING", "DOWNLOADING")
fun List<MediaTask>.spentByCurrency(): Map<String, BigDecimal> = filter { it.state == MediaTaskState.COMPLETED }
    .mapNotNull { it.actualCost }.groupBy { it.currency }.mapValues { (_, costs) -> costs.fold(BigDecimal.ZERO) { sum, cost -> sum + cost.amount } }

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
