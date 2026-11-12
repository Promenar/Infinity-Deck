package com.promenar.infinity.data.production

import android.content.Context
import com.promenar.infinity.R
import com.promenar.infinity.domain.mediagen.MediaTask

interface ReviewMessageStrings {
    fun text(key: String, vararg args: Any): String
}

class AndroidReviewMessageStrings(private val context: Context) : ReviewMessageStrings {
    override fun text(key: String, vararg args: Any): String = context.getString(when (key) {
        "field_title" -> R.string.production_review_message_field_title
        "field_size" -> R.string.production_review_message_field_size
        "field_durationSeconds" -> R.string.production_review_message_field_durationseconds
        "field_camera" -> R.string.production_review_message_field_camera
        "field_cameraMovement" -> R.string.production_review_message_field_cameramovement
        "field_visual" -> R.string.production_review_message_field_visual
        "field_performance" -> R.string.production_review_message_field_performance
        "field_dialogue" -> R.string.production_review_message_field_dialogue
        "field_sound" -> R.string.production_review_message_field_sound
        "field_transition" -> R.string.production_review_message_field_transition
        "field_continuity" -> R.string.production_review_message_field_continuity
        "field_appearances" -> R.string.production_review_message_field_appearances
        "field_locationAssetId" -> R.string.production_review_message_field_locationassetid
        "field_propAssetIds" -> R.string.production_review_message_field_propassetids
        "intro" -> R.string.production_review_message_intro
        "title" -> R.string.production_review_message_title
        "approve" -> R.string.production_review_message_approve
        "reject" -> R.string.production_review_message_reject
        "suggest" -> R.string.production_review_message_suggest
        "open" -> R.string.production_review_message_open
        "overflow" -> R.string.production_review_message_overflow
        "episode" -> R.string.production_review_message_episode
        "shot" -> R.string.production_review_message_shot
        "take" -> R.string.production_review_message_take
        "asset" -> R.string.production_review_message_asset
        "script" -> R.string.production_review_message_script
        "scene" -> R.string.production_review_message_scene
        "cut" -> R.string.production_review_message_cut
        "change" -> R.string.production_review_message_change
        "failed" -> R.string.production_review_message_failed
        "canceled" -> R.string.production_review_message_canceled
        "rejected" -> R.string.production_review_message_rejected
        "cost" -> R.string.production_review_message_cost
        "project" -> R.string.production_review_message_project
        else -> throw IllegalArgumentException("未知审核消息类型：$key")
    }, *args)
}

object ProductionReviewMessage {
    const val MAX_OPEN_ITEMS = 12
    fun compose(production: Production, tasks: List<MediaTask>, strings: ReviewMessageStrings): String? {
        val entries = production.unsentReviews()
        if (entries.isEmpty()) return null
        val stats = productionCostStats(production, tasks)
        val openItems = productionOpenItems(production, tasks, stats)
        return buildString {
            appendLine(strings.text("intro"))
            appendLine(strings.text("title", production.nextReviewRound))
            listOf(ReviewKind.APPROVE to "approve", ReviewKind.REJECT to "reject", ReviewKind.SUGGEST to "suggest").forEach { (kind, key) ->
                val group = entries.filter { it.kind == kind }
                if (group.isNotEmpty()) {
                    appendLine()
                    appendLine(strings.text(key))
                    group.forEach { entry ->
                        append("- ").append(reviewTargetLabel(production, entry.target, strings))
                        entry.note?.trim()?.takeIf { it.isNotEmpty() }?.let { append(": ").append(it) }
                        appendLine()
                    }
                }
            }
            if (openItems.isNotEmpty()) {
                appendLine()
                appendLine(strings.text("open"))
                openItems.take(MAX_OPEN_ITEMS).forEach { item ->
                    fun label(target: ReviewTarget?) = target?.let { reviewTargetLabel(production, it, strings) }.orEmpty()
                    val text = when (item) {
                        is OpenItem.FailedTask -> strings.text("failed", item.taskId, label(item.target), item.error.orEmpty())
                        is OpenItem.CanceledTask -> strings.text("canceled", item.taskId, label(item.target))
                        is OpenItem.Rejected -> strings.text("rejected", label(item.target))
                        is OpenItem.CostAlert -> strings.text("cost", label(item.target), item.count.total)
                    }
                    appendLine("- $text")
                }
                if (openItems.size > MAX_OPEN_ITEMS) appendLine(strings.text("overflow", openItems.size))
            }
        }.trimEnd()
    }
}

internal fun reviewTargetLabel(p: Production, target: ReviewTarget, strings: ReviewMessageStrings): String {
    val episode = p.reviewEpisode(target.episodeId)
    val episodeName = strings.text("episode", episode?.number ?: target.episodeId.orEmpty(), episode?.title.orEmpty())
    val shot = p.reviewShot(target)
    fun shotName() = strings.text("shot", episodeName, target.shotCode.orEmpty(), shot?.title.orEmpty())
    return when (target.kind) {
        ReviewTargetKind.PROJECT -> strings.text("project", p.title)
        ReviewTargetKind.EPISODE -> episodeName
        ReviewTargetKind.SHOT -> shotName()
        ReviewTargetKind.TAKE -> strings.text("take", shotName(), shot?.takes?.indexOfFirst { it.id == target.takeId }?.takeIf { it >= 0 }?.plus(1) ?: target.takeId.orEmpty())
        ReviewTargetKind.ASSET_VERSION -> strings.text("asset", p.assets.firstOrNull { it.id == target.assetId }?.name ?: target.assetId.orEmpty(), target.version ?: "?")
        ReviewTargetKind.SCRIPT -> strings.text("script", episodeName)
        ReviewTargetKind.SCENE -> strings.text("scene", episodeName, target.sceneCode.orEmpty(), episode?.scenes?.firstOrNull { it.code == target.sceneCode }?.heading.orEmpty())
        ReviewTargetKind.CUT -> {
            val cut = p.cuts.firstOrNull { it.id == target.cutId }
            val ep = p.reviewEpisode(cut?.episodeId)
            strings.text("cut", ep?.title ?: cut?.episodeId.orEmpty(), cut?.number ?: target.cutId.orEmpty())
        }
        ReviewTargetKind.CHANGE_REQUEST -> {
            val request = p.changeRequests.firstOrNull { it.id == target.requestId }
            val label = request?.let { reviewTargetLabel(p, ReviewTarget.shot(it.episodeId, it.shotCode), strings) } ?: target.requestId.orEmpty()
            strings.text("change", label, request?.changes?.keys?.joinToString(", ") { strings.text("field_$it") }.orEmpty())
        }
    }
}
