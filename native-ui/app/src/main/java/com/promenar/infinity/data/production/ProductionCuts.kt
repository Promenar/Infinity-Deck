package com.promenar.infinity.data.production

import kotlinx.serialization.Serializable
import java.util.UUID

/** 剪辑冻结选定候选和时长；字幕、音频内容由导出时的项目内容提供。 */
@Serializable
data class CutVersion(
    val id: String,
    val episodeId: String,
    val number: Int,
    val createdAt: Long,
    val clips: List<CutClip>,
    val burnSubtitles: Boolean = false,
    val export: CutExportRecord? = null,
    val changedAt: Long = 0,
    val changedBy: ChangeSource = ChangeSource.USER,
    val contentChangedAt: Long? = null,
)

@Serializable
data class CutClip(
    val episodeId: String,
    val shotCode: String,
    val takeId: String? = null,
    val path: String? = null,
    val seconds: Double,
)

@Serializable
data class CutExportRecord(
    val videoPath: String,
    val subtitlePath: String?,
    val durationSeconds: Double,
    val sizeBytes: Long,
    val exportedAt: Long,
)

/** 纯用户操作的剪辑约束，同时用于清单和备份恢复校验。 */
object ProductionCuts {
    const val MAX_VERSIONS_PER_EPISODE = 20
    // 时间线须能安全换算为媒体 API 使用的 Long 微秒。
    const val MAX_TIMELINE_SECONDS = 9_223_372_036_854.0

    fun videoPath(production: Production, cut: CutVersion): String =
        "${production.rootPath}/cuts/${cut.episodeId}-v${cut.number}.mp4"

    fun subtitlePath(production: Production, cut: CutVersion): String =
        "${production.rootPath}/cuts/${cut.episodeId}-v${cut.number}.srt"

    fun validateProjectPath(production: Production, path: String) {
        ProductionManifestCodec.validatePath(path)
        require(path.startsWith(production.rootPath + "/")) { "剪辑素材和产物必须位于项目目录内。" }
    }

    fun create(production: Production, episodeId: String, mediaDurations: Map<String, Double>, now: Long): Production {
        val episode = production.seasons.flatMap { it.episodes }.firstOrNull { it.id == episodeId }
            ?: throw IllegalArgumentException("集 $episodeId 不存在，请先读取项目。")
        require(now >= 0) { "剪辑创建时间不能为负数。" }
        val existing = production.cuts.filter { it.episodeId == episodeId }
        require(existing.size < MAX_VERSIONS_PER_EPISODE) { "每集最多保留 20 个剪辑版本，请先删除不需要的版本。" }
        val high = maxOf(production.cutVersionHighWatermarks[episodeId] ?: 0, existing.maxOfOrNull { it.number } ?: 0)
        require(high < Int.MAX_VALUE) { "该集剪辑编号已达到上限。" }
        val number = high + 1
        val clips = episode.scenes.flatMap { it.shots }.map { shot ->
            val take = shot.selectedTakeId?.let { id ->
                shot.takes.firstOrNull { it.id == id } ?: throw IllegalArgumentException("选定候选不存在，请重新选择。")
            }
            val measured = take?.path?.let(mediaDurations::get)?.takeIf { it.isFinite() && it > 0.0 }
            CutClip(episodeId, shot.code, take?.id, take?.path, measured ?: shot.durationSeconds)
        }
        val id = UUID.nameUUIDFromBytes("${production.id}:$episodeId:$number".toByteArray(Charsets.UTF_8)).toString()
        val cut = CutVersion(id, episodeId, number, now, clips)
        return production.copy(cuts = production.cuts + cut,
            cutVersionHighWatermarks = production.cutVersionHighWatermarks + (episodeId to number)).also(::validate)
    }

    fun delete(production: Production, cutId: String): Production {
        val cut = required(production, cutId)
        val high = maxOf(production.cutVersionHighWatermarks[cut.episodeId] ?: 0,
            production.cuts.filter { it.episodeId == cut.episodeId }.maxOf { it.number })
        return production.copy(cuts = production.cuts.filterNot { it.id == cutId },
            cutVersionHighWatermarks = production.cutVersionHighWatermarks + (cut.episodeId to high))
    }

    fun setSubtitles(production: Production, cutId: String, burnSubtitles: Boolean): Production {
        val cut = required(production, cutId)
        return replace(production, if (cut.burnSubtitles == burnSubtitles) cut else cut.copy(burnSubtitles = burnSubtitles, export = null))
    }

    fun registerExport(production: Production, cutId: String, record: CutExportRecord): Production {
        val cut = required(production, cutId)
        return replace(production, cut.copy(export = record)).also(::validate)
    }

    private fun required(production: Production, cutId: String): CutVersion =
        production.cuts.firstOrNull { it.id == cutId } ?: throw IllegalArgumentException("剪辑版本不存在，请刷新面板。")

    private fun replace(production: Production, cut: CutVersion): Production =
        production.copy(cuts = production.cuts.map { if (it.id == cut.id) cut else it })

    fun validate(production: Production) {
        val episodes = production.seasons.flatMap { it.episodes }.associateBy { it.id }
        require(production.cuts.map { it.id }.distinct().size == production.cuts.size) { "剪辑版本标识重复。" }
        production.cutVersionHighWatermarks.forEach { (episodeId, number) ->
            require(Regex("S[0-9]+E[0-9][0-9]+").matches(episodeId) && number > 0) { "剪辑编号记录无效。" }
        }
        production.cuts.groupBy { it.episodeId }.forEach { (episodeId, cuts) ->
            require(cuts.size <= MAX_VERSIONS_PER_EPISODE) { "每集最多保留 20 个剪辑版本。" }
            require(cuts.map { it.number }.distinct().size == cuts.size) { "同集剪辑版本编号重复。" }
            production.cutVersionHighWatermarks[episodeId]?.let { high ->
                require(cuts.all { it.number <= high }) { "剪辑编号超过已记录的最大编号。" }
            }
        }
        production.cuts.forEach { cut ->
            require(cut.id.isNotBlank() && cut.id.length <= 120 && cut.number > 0 && cut.createdAt >= 0) { "剪辑标识、编号或创建时间无效。" }
            val episode = episodes[cut.episodeId] ?: throw IllegalArgumentException("剪辑引用的集不存在。")
            val shots = episode.scenes.flatMap { it.shots }.associateBy { it.code }
            require(cut.clips.isNotEmpty() && cut.clips.size <= shots.size) { "剪辑须包含该集的有效镜头。" }
            require(cut.clips.map { it.shotCode }.distinct().size == cut.clips.size) { "剪辑镜头不能重复。" }
            var total = 0.0
            cut.clips.forEach { clip ->
                require(clip.episodeId == cut.episodeId) { "剪辑镜头必须属于同一集。" }
                val shot = shots[clip.shotCode] ?: throw IllegalArgumentException("剪辑引用的镜头不存在。")
                require(clip.seconds.isFinite() && clip.seconds > 0.0) { "剪辑时长必须为有限正数。" }
                total += clip.seconds
                require(total.isFinite() && total <= MAX_TIMELINE_SECONDS) { "剪辑时间线超过支持的时长。" }
                require((clip.takeId == null) == (clip.path == null)) { "剪辑候选和素材路径必须同时存在。" }
                clip.path?.let { path ->
                    validateProjectPath(production, path)
                    require(shot.takes.any { it.id == clip.takeId && it.path == path }) { "剪辑引用的候选或素材路径不存在。" }
                }
            }
            cut.export?.let { record ->
                validateProjectPath(production, record.videoPath)
                record.subtitlePath?.let { validateProjectPath(production, it) }
                require(record.subtitlePath != record.videoPath) { "视频和字幕路径不能相同。" }
                require(record.durationSeconds.isFinite() && record.durationSeconds > 0.0 && record.durationSeconds <= MAX_TIMELINE_SECONDS &&
                    record.sizeBytes > 0 && record.exportedAt >= cut.createdAt) { "剪辑导出记录的时长、大小或导出时间无效。" }
            }
        }
    }
}
