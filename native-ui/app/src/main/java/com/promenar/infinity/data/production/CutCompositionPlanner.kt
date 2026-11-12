package com.promenar.infinity.data.production

import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** 宿主按工作区虚拟路径提供媒体探测结果。 */
data class CutMediaMetadata(
    val durationSeconds: Double? = null,
    val width: Int? = null,
    val height: Int? = null,
    val hasAudio: Boolean? = null,
)

data class CutCompositionPlan(
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
    val videoPath: String,
    val subtitlePath: String,
    val burnSubtitles: Boolean,
    val segments: List<CutVideoSegment> = emptyList(),
    val audio: List<CutAudioSegment> = emptyList(),
    val subtitles: List<CutSubtitleCue> = emptyList(),
    val srt: String = "",
)

/** path 为空时按 title 渲染标题卡；所有段的起点均属于完整剪辑时间线。 */
data class CutVideoSegment(
    val episodeId: String,
    val shotCode: String,
    val path: String?,
    val title: String,
    val startSeconds: Double,
    val durationSeconds: Double,
)

enum class CutAudioKind { ORIGINAL, DIALOGUE, MUSIC }

data class CutAudioSegment(
    val path: String,
    val startSeconds: Double,
    val durationSeconds: Double,
    val sourceStartSeconds: Double = 0.0,
    val volumeDb: Double,
    val fadeOutSeconds: Double = 0.0,
    val kind: CutAudioKind,
)

/** text 已完成 SRT 文本转义，可直接写入旁挂字幕。 */
data class CutSubtitleCue(val startMillis: Long, val endMillis: Long, val text: String)

/** 只计算时间线，不读文件、不探测媒体、不创建导出任务。 */
object CutCompositionPlanner {
    fun plan(production: Production, cut: CutVersion, metadata: Map<String, CutMediaMetadata> = emptyMap()): CutCompositionPlan {
        ProductionManifestCodec.validate(production)
        // 也允许对尚未写入项目的候选快照规划，但候选须通过完整引用与编号校验。
        ProductionCuts.validate(production.copy(cuts = production.cuts.filterNot { it.id == cut.id } + cut))
        val episode = production.seasons.flatMap { it.episodes }.first { it.id == cut.episodeId }
        val shots = episode.scenes.flatMap { it.shots }.associateBy { it.code }
        val segments = mutableListOf<CutVideoSegment>()
        val audio = mutableListOf<CutAudioSegment>()
        val subtitles = mutableListOf<CutSubtitleCue>()
        var timeline = 0.0
        cut.clips.forEach { clip ->
            val shot = shots.getValue(clip.shotCode)
            val segment = CutVideoSegment(clip.episodeId, clip.shotCode, clip.path, shot.title, timeline, clip.seconds)
            segments += segment
            val dubbed = dialogueAudio(production, shot, segment, metadata)
            clip.path?.let { path ->
                if (metadata[path]?.hasAudio != false) {
                    val duration = min(clip.seconds, measuredDuration(metadata[path]) ?: clip.seconds)
                    audio += CutAudioSegment(path, timeline, duration, volumeDb = if (dubbed.isEmpty()) 0.0 else -10.0, kind = CutAudioKind.ORIGINAL)
                }
            }
            audio += dubbed
            subtitles += subtitleCues(shot.dialogue, timeline, timeline + clip.seconds)
            timeline += clip.seconds
        }
        music(production, timeline, metadata)?.let(audio::add)
        val height = cut.clips.mapNotNull { clip -> clip.path?.let { metadata[it]?.height } }
            .filter { it > 1 }.maxOrNull()?.coerceAtMost(1080)?.let { it - it % 2 } ?: 1080
        val width = outputWidth(production.aspectRatio, height)
        return CutCompositionPlan(width, height, timeline, ProductionCuts.videoPath(production, cut),
            ProductionCuts.subtitlePath(production, cut), cut.burnSubtitles, segments, audio, subtitles, toSrt(subtitles))
    }

    private fun dialogueAudio(production: Production, shot: Shot, segment: CutVideoSegment,
        metadata: Map<String, CutMediaMetadata>): List<CutAudioSegment> = buildList {
        var offset = 0.0
        shot.dialogue.forEachIndexed { index, line ->
            if (line.audioSource != DialogueAudioSource.DUBBED || offset >= segment.durationSeconds) return@forEachIndexed
            val latest = shot.audio.filter { it.dialogueIndex == index }.sortedBy { it.createdAt }.lastOrNull()
                ?: return@forEachIndexed
            ProductionCuts.validateProjectPath(production, latest.path)
            val measured = measuredDuration(metadata[latest.path])
                ?: throw IllegalArgumentException("配音时长尚未读取，请检查配音文件后导出。")
            val duration = min(measured, segment.durationSeconds - offset)
            add(CutAudioSegment(latest.path, segment.startSeconds + offset, duration, volumeDb = 0.0, kind = CutAudioKind.DIALOGUE))
            offset += duration
        }
    }

    private fun music(production: Production, duration: Double, metadata: Map<String, CutMediaMetadata>): CutAudioSegment? {
        val asset = production.assets.firstOrNull { it.kind == AssetKind.SOUND && it.status == AssetStatus.LOCKED } ?: return null
        val version = asset.versions.firstOrNull { it.number == asset.currentVersion } ?: return null
        ProductionCuts.validateProjectPath(production, version.path)
        val usedDuration = min(duration, measuredDuration(metadata[version.path]) ?: duration)
        return CutAudioSegment(version.path, 0.0, usedDuration, volumeDb = -14.0,
            fadeOutSeconds = min(1.0, usedDuration), kind = CutAudioKind.MUSIC)
    }

    private fun measuredDuration(metadata: CutMediaMetadata?): Double? =
        metadata?.durationSeconds?.takeIf { it.isFinite() && it > 0.0 && it <= ProductionCuts.MAX_TIMELINE_SECONDS }

    private fun outputWidth(aspectRatio: String?, height: Int): Int {
        val parts = aspectRatio?.split(':')
        val pair = parts?.takeIf { it.size == 2 }?.map { it.trim().toDoubleOrNull() }
        val ratio = if (pair != null && pair.all { it != null && it.isFinite() && it > 0.0 }) pair[0]!! / pair[1]!! else 16.0 / 9.0
        val candidate = height * ratio
        val safe = if (candidate.isFinite() && candidate >= 2.0 && candidate <= Int.MAX_VALUE - 2.0) candidate else height * 16.0 / 9.0
        return ((safe / 2.0).roundToInt() * 2).coerceAtLeast(2)
    }

    private fun subtitleCues(dialogue: List<ShotDialogue>, start: Double, end: Double): List<CutSubtitleCue> {
        val text = dialogue.map { escapeSubtitle(it.text) }.filter { it.isNotBlank() }
        if (text.isEmpty()) return emptyList()
        val startMillis = (start * 1000.0).roundToLong()
        val endMillis = (end * 1000.0).roundToLong()
        val span = endMillis - startMillis
        require(span >= text.size) { "镜头时长过短，无法为每句台词安排有效字幕时间。" }
        fun boundary(index: Int): Long = startMillis + (span / text.size) * index + (span % text.size) * index / text.size
        return text.mapIndexed { index, value -> CutSubtitleCue(boundary(index), boundary(index + 1), value) }
    }

    private fun escapeSubtitle(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')
        .filter { it == '\n' || it == '\t' || !it.isISOControl() }
        .lineSequence().map { it.replace('\t', ' ').trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun toSrt(cues: List<CutSubtitleCue>): String {
        var lastEnd = 0L
        cues.forEach { cue ->
            require(cue.startMillis >= lastEnd && cue.endMillis > cue.startMillis && cue.text.isNotBlank() &&
                !cue.text.contains("\r") && !cue.text.contains("\n\n")) { "字幕时间或文本结构无效。" }
            lastEnd = cue.endMillis
        }
        return cues.mapIndexed { index, cue -> "${index + 1}\n${timestamp(cue.startMillis)} --> ${timestamp(cue.endMillis)}\n${cue.text}\n\n" }.joinToString("")
    }

    private fun timestamp(millis: Long): String = String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
        millis / 3_600_000, millis / 60_000 % 60, millis / 1000 % 60, millis % 1000)
}
