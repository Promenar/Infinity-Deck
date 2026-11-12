package com.promenar.infinity.data.production

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ReviewKind { @SerialName("approve") APPROVE, @SerialName("reject") REJECT, @SerialName("suggest") SUGGEST }

@Serializable
enum class ReviewTargetKind {
    @SerialName("take") TAKE, @SerialName("asset_version") ASSET_VERSION, @SerialName("script") SCRIPT,
    @SerialName("scene") SCENE, @SerialName("cut") CUT, @SerialName("change") CHANGE_REQUEST,
    @SerialName("project") PROJECT, @SerialName("episode") EPISODE, @SerialName("shot") SHOT,
}

@Serializable
data class ReviewTarget(
    val kind: ReviewTargetKind,
    val episodeId: String? = null,
    val sceneCode: String? = null,
    val shotCode: String? = null,
    val takeId: String? = null,
    val assetId: String? = null,
    val version: Int? = null,
    val cutId: String? = null,
    val requestId: String? = null,
) {
    /** PROJECT、EPISODE、SHOT 只接受建议。 */
    val acceptsVerdict: Boolean get() = kind !in setOf(ReviewTargetKind.PROJECT, ReviewTargetKind.EPISODE, ReviewTargetKind.SHOT)
    companion object {
        fun take(episodeId: String, shotCode: String, takeId: String) = ReviewTarget(ReviewTargetKind.TAKE, episodeId = episodeId, shotCode = shotCode, takeId = takeId)
        fun assetVersion(assetId: String, version: Int) = ReviewTarget(ReviewTargetKind.ASSET_VERSION, assetId = assetId, version = version)
        fun script(episodeId: String) = ReviewTarget(ReviewTargetKind.SCRIPT, episodeId = episodeId)
        fun scene(episodeId: String, sceneCode: String) = ReviewTarget(ReviewTargetKind.SCENE, episodeId = episodeId, sceneCode = sceneCode)
        fun cut(cutId: String) = ReviewTarget(ReviewTargetKind.CUT, cutId = cutId)
        fun change(requestId: String) = ReviewTarget(ReviewTargetKind.CHANGE_REQUEST, requestId = requestId)
        fun project() = ReviewTarget(ReviewTargetKind.PROJECT)
        fun episode(episodeId: String) = ReviewTarget(ReviewTargetKind.EPISODE, episodeId = episodeId)
        fun shot(episodeId: String, shotCode: String) = ReviewTarget(ReviewTargetKind.SHOT, episodeId = episodeId, shotCode = shotCode)
    }
}

/** 一条意见；[round] 记录已发送轮次，[queuedRound] 记录待送达轮次。 */
@Serializable
data class ReviewEntry(val target: ReviewTarget, val kind: ReviewKind, val note: String? = null, val at: Long, val round: Int? = null, val sentAt: Long? = null, val sentRevision: Long? = null, val queuedRound: Int? = null)

/** 已发送的一轮，只读；revision 用于和实体修订号比较。 */
@Serializable
data class ReviewRound(val number: Int, val sentAt: Long, val entries: List<ReviewEntry>, val revision: Long = 0)

/** 同一镜头或资产共用一个基线，使候选互斥与撤回能同时恢复。 */
@Serializable
data class ReviewDraft(
    val group: ReviewTarget,
    val previousVerdicts: List<ReviewEntry>,
    val pendingVerdicts: List<ReviewEntry> = emptyList(),
    val selectedTakeId: String? = null,
    val shotStatus: ShotStatus? = null,
    val currentVersion: Int? = null,
    val assetStatus: AssetStatus? = null,
    val locked: Boolean? = null,
    val changeApproved: Boolean = false,
    val changeRejected: Boolean = false,
    val displacements: List<ReviewDisplacement> = emptyList(),
    /** 已排队轮次的回退基线，与下一轮未发送草稿分别保存。 */
    val queued: Boolean = false,
)

@Serializable
data class ReviewDisplacement(val target: ReviewTarget, val displacedTargets: List<ReviewTarget> = emptyList(), val approvedVersion: Int? = null)

fun Production.verdictOf(target: ReviewTarget): ReviewEntry? = currentReviewVerdicts().lastOrNull { it.target == target }
fun Production.unsentReviews(): List<ReviewEntry> = reviews.filter { it.round == null && it.queuedRound == null }.sortedBy { it.at }
fun Production.queuedReviews(): List<ReviewEntry> = reviews.filter { it.round == null && it.queuedRound != null }.sortedBy { it.at }
internal val Production.queuedReviewDrafts: List<ReviewDraft> get() = reviewDrafts.filter { it.queued }
internal val Production.pendingReviewDrafts: List<ReviewDraft> get() = reviewDrafts.filterNot { it.queued }
val Production.queuedReviewRound: Int? get() = queuedReviews().firstOrNull()?.queuedRound

/** 排队原文独立保留，当前效果按未发送草稿的替换关系计算。 */
internal fun Production.currentReviewVerdicts(): List<ReviewEntry> {
    val drafts = reviewDrafts.filterNot { it.queued }.associateBy { it.group }
    return reviews.filter { it.kind != ReviewKind.SUGGEST }.groupBy { it.target.reviewGroup() }.flatMap { (group, entries) ->
        drafts[group]?.let(::reviewDraftVerdicts) ?: entries
    }
}

internal fun reviewDraftVerdicts(draft: ReviewDraft): List<ReviewEntry> {
    val suppressed = draft.displacements.flatMap { it.displacedTargets }.toSet()
    return draft.pendingVerdicts.fold(draft.previousVerdicts.filterNot { it.target in suppressed }) { entries, value ->
        entries.filterNot { it.target == value.target || value.kind == ReviewKind.APPROVE && it.kind == ReviewKind.APPROVE } + value
    }
}

fun Production.unsentSuggestions(target: ReviewTarget): List<ReviewEntry> = unsentReviews().filter { it.target == target && it.kind == ReviewKind.SUGGEST }

/** 候选结论决定镜头状态；空镜头保留原状态。 */
fun deriveShotReviewStatus(shot: Shot, verdicts: List<ReviewEntry>): ShotStatus = when {
    shot.takes.isEmpty() -> shot.status
    shot.takes.any { take -> verdicts.any { it.target.kind == ReviewTargetKind.TAKE && it.target.shotCode == shot.code && it.target.takeId == take.id && it.kind == ReviewKind.APPROVE } } -> ShotStatus.LOCKED
    shot.takes.all { take -> verdicts.any { it.target.kind == ReviewTargetKind.TAKE && it.target.shotCode == shot.code && it.target.takeId == take.id && it.kind == ReviewKind.REJECT } } -> ShotStatus.REDO
    else -> ShotStatus.REVIEW
}

internal fun Production.reviewEpisode(id: String?): Episode? = seasons.flatMap { it.episodes }.firstOrNull { it.id == id }
internal fun Production.reviewShot(target: ReviewTarget): Shot? = reviewEpisode(target.episodeId)?.scenes?.flatMap { it.shots }?.firstOrNull { it.code == target.shotCode }

internal fun ReviewTarget.reviewGroup(): ReviewTarget = when (kind) {
    ReviewTargetKind.TAKE -> ReviewTarget.shot(requireNotNull(episodeId), requireNotNull(shotCode))
    ReviewTargetKind.ASSET_VERSION -> ReviewTarget(ReviewTargetKind.ASSET_VERSION, assetId = assetId)
    else -> this
}

internal fun ReviewTarget.requireReviewShape() {
    val target = this
    val canonical = when (target.kind) {
        ReviewTargetKind.PROJECT -> ReviewTarget.project()
        ReviewTargetKind.EPISODE -> ReviewTarget.episode(requireNotNull(target.episodeId) { "缺少集标识。" })
        ReviewTargetKind.SCRIPT -> ReviewTarget.script(requireNotNull(target.episodeId) { "缺少集标识。" })
        ReviewTargetKind.SCENE -> ReviewTarget.scene(requireNotNull(target.episodeId) { "缺少集标识。" }, requireNotNull(target.sceneCode) { "缺少场标识。" })
        ReviewTargetKind.SHOT -> ReviewTarget.shot(requireNotNull(target.episodeId) { "缺少集标识。" }, requireNotNull(target.shotCode) { "缺少镜头标识。" })
        ReviewTargetKind.TAKE -> ReviewTarget.take(requireNotNull(target.episodeId) { "缺少集标识。" }, requireNotNull(target.shotCode) { "缺少镜头标识。" }, requireNotNull(target.takeId) { "缺少候选标识。" })
        ReviewTargetKind.ASSET_VERSION -> ReviewTarget.assetVersion(requireNotNull(target.assetId) { "缺少资产标识。" }, requireNotNull(target.version) { "缺少版本号。" })
        ReviewTargetKind.CUT -> ReviewTarget.cut(requireNotNull(target.cutId) { "缺少剪辑标识。" })
        ReviewTargetKind.CHANGE_REQUEST -> ReviewTarget.change(requireNotNull(target.requestId) { "缺少改动请求标识。" })
    }
    require(target == canonical) { "审核对象包含不匹配的标识。" }
    require(listOfNotNull(target.episodeId, target.sceneCode, target.shotCode, target.takeId, target.assetId, target.cutId, target.requestId).all { it.isNotBlank() } &&
        (target.version == null || target.version > 0)) { "审核对象标识无效。" }
}

internal fun Production.requireReviewTarget(target: ReviewTarget) {
    target.requireReviewShape()
    val exists = when (target.kind) {
        ReviewTargetKind.PROJECT -> true
        ReviewTargetKind.EPISODE, ReviewTargetKind.SCRIPT -> reviewEpisode(target.episodeId) != null
        ReviewTargetKind.SCENE -> reviewEpisode(target.episodeId)?.scenes?.any { it.code == target.sceneCode } == true
        ReviewTargetKind.SHOT -> reviewShot(target) != null
        ReviewTargetKind.TAKE -> reviewShot(target)?.takes?.any { it.id == target.takeId } == true
        ReviewTargetKind.ASSET_VERSION -> assets.any { it.id == target.assetId && it.versions.any { version -> version.number == target.version } }
        ReviewTargetKind.CUT -> cuts.any { it.id == target.cutId }
        ReviewTargetKind.CHANGE_REQUEST -> changeRequests.any { it.id == target.requestId && reviewShot(ReviewTarget.shot(it.episodeId, it.shotCode)) != null }
    }
    require(exists) { "审核对象不存在，请刷新制作面板。" }
}

/** 内容修订和审核效果修订分别记录，撤回意见不会被当作新产出。 */
internal fun stampProductionContent(before: Production, after: Production, revision: Long): Production = after.copy(
    seasons = after.seasons.map { season -> season.copy(episodes = season.episodes.map { episode ->
        val oldEpisode = before.reviewEpisode(episode.id)
        val changedScript = oldEpisode == null || oldEpisode.scriptPath != episode.scriptPath || oldEpisode.title != episode.title || oldEpisode.number != episode.number
        episode.copy(
            changedAt = if (changedScript) revision else episode.changedAt,
            contentChangedAt = if (changedScript) revision else episode.contentChangedAt,
            scenes = episode.scenes.map { scene ->
                val oldScene = oldEpisode?.scenes?.firstOrNull { it.code == scene.code }
                val changed = oldScene == null || oldScene.heading != scene.heading || oldScene.number != scene.number ||
                    oldScene.shots.map { it.code } != scene.shots.map { it.code } || scene.shots.any { shot ->
                        oldScene.shots.firstOrNull { it.code == shot.code }?.let { ProductionRules.changes(it, shot).isNotEmpty() } ?: true
                    }
                if (changed) scene.copy(changedAt = revision, contentChangedAt = revision) else scene
            },
        )
    }) },
    cuts = after.cuts.map { cut ->
        val old = before.cuts.firstOrNull { it.id == cut.id }
        if (old == null || old.clips != cut.clips || old.burnSubtitles != cut.burnSubtitles || old.export != cut.export)
            cut.copy(changedAt = revision, contentChangedAt = revision) else cut
    },
)

/** 删除制作对象时保留锁定的排队原文及历史；未发送意见同步移除。 */
internal fun Production.pruneDeletedReviewTargets(): Production {
    fun exists(target: ReviewTarget) = runCatching { requireReviewTarget(target) }.isSuccess
    return copy(reviews = reviews.filter { it.queuedRound != null || exists(it.target) },
        reviewDrafts = reviewDrafts.filter { draft -> draft.queued || draft.pendingVerdicts.all { exists(it.target) } })
}
