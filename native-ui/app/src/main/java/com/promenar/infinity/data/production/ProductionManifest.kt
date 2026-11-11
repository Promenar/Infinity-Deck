package com.promenar.infinity.data.production

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ProductionManifest(val schema: String, val revision: Long, val production: Production)

/** 清单的结构和领域约束共用同一校验入口。 */
object ProductionManifestCodec {
    const val SCHEMA = "infinity.production/1"
    const val MAX_BYTES = 4 * 1024 * 1024
    private const val MAX_MANIFEST_BYTES = 16 * 1024 * 1024
    private val json = Json { prettyPrint = true; encodeDefaults = true }
    private val compact = Json { encodeDefaults = true }

    fun encode(production: Production, revision: Long): ByteArray {
        require(revision >= 0) { "清单修订号不能为负数。" }
        validate(production, revision)
        return json.encodeToString(ProductionManifest(SCHEMA, revision, production)).toByteArray(Charsets.UTF_8).also {
            require(it.size <= MAX_MANIFEST_BYTES) { "清单文件过大，请减少项目内容。" }
        }
    }

    fun decode(bytes: ByteArray): ProductionManifest {
        require(bytes.size <= MAX_MANIFEST_BYTES) { "清单文件过大，请减少项目内容。" }
        val manifest = try {
            json.decodeFromString<ProductionManifest>(Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString())
        } catch (e: Exception) {
            throw IllegalArgumentException("清单结构损坏，请检查 JSON 和字段。", e)
        }
        require(manifest.schema == SCHEMA) { "清单 schema 不受支持，请使用 $SCHEMA。" }
        require(manifest.revision >= 0) { "清单修订号不能为负数。" }
        validate(manifest.production, manifest.revision)
        return manifest
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && !path.startsWith('\\') &&
            !Regex("^[A-Za-z]:").containsMatchIn(path) && !path.contains('\\') &&
            path.split('/').none { it == ".." || it == "." || it.isEmpty() } && !path.contains('\u0000')) {
            "路径 $path 必须是工作区内规范化的相对路径，请移除绝对路径、空段和点路径。"
        }
    }

    fun validate(p: Production, revision: Long) {
        require(revision >= 0) { "清单修订号不能为负数。" }
        validate(p)
        val changed = p.stages.map { it.changedAt } + p.assets.map { it.changedAt } + p.seasons.flatMap { season -> season.episodes.flatMap { ep -> listOf(ep.changedAt) + ep.scenes.flatMap { scene -> listOf(scene.changedAt) + scene.shots.map { it.changedAt } } } }
        require(changed.all { it <= revision }) { "实体修订号不能大于清单修订号，请检查清单。" }
    }

    fun validate(p: Production) {
        require(compact.encodeToString(p).toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "项目快照超过 4 MiB，请减少项目内容。" }
        fun title(value: String, field: String) = require(value.isNotBlank() && value.length <= 120) { "$field 需为 1～120 字。" }
        fun note(value: String?, field: String) = require(value == null || value.length <= 1000) { "$field 最多 1000 字。" }
        fun unique(values: List<String>, field: String) = require(values.distinct().size == values.size) { "$field 标识重复，请使用唯一标识。" }
        fun path(value: String?) { if (value != null) validatePath(value) }
        require(p.id.isNotBlank()) { "项目 id 不能为空。" }
        title(p.title, "项目标题")
        validatePath(p.rootPath)
        note(p.logline, "故事梗概")
        require(p.aspectRatio == null || p.aspectRatio.length <= 120) { "画幅最多 120 字。" }
        require(p.bible.size <= 50) { "bible 最多 50 项。" }
        p.bible.forEach { title(it.title, "文档标题"); validatePath(it.path) }
        require(p.stages.size == ProductionStage.entries.size && p.stages.map { it.stage }.toSet() == ProductionStage.entries.toSet()) { "制作阶段必须各出现一次。" }
        p.stages.forEach { note(it.note, "阶段说明"); require(it.changedAt >= 0) { "阶段修订号不能为负数。" } }
        require(p.seasons.size <= 20) { "最多 20 季。" }
        unique(p.seasons.map { it.id }, "季")
        val episodeIds = mutableListOf<String>()
        p.seasons.forEach { season ->
            require(Regex("S[0-9]+").matches(season.id) && season.number > 0) { "季标识需为 S 加数字，序号须大于零。" }
            season.title?.let { title(it, "季标题") }
            require(season.episodes.size <= 100) { "每季最多 100 集。" }
            season.episodes.forEach { episode ->
                episodeIds += episode.id
                require(Regex("S[0-9]+E[0-9][0-9]+").matches(episode.id) && episode.id.startsWith(season.id + "E") && episode.number > 0) { "集标识须属于对应季并使用 S数字E两位以上数字，序号须大于零。" }
                title(episode.title, "集标题"); path(episode.scriptPath)
                require(episode.changedAt >= 0) { "集修订号不能为负数。" }
                require(episode.scenes.size <= 200) { "每集最多 200 场。" }
                unique(episode.scenes.map { it.code }, "场")
                unique(episode.scenes.flatMap { it.shots }.map { it.code }, "镜头")
                episode.scenes.forEach { scene ->
                    require(Regex("SC[0-9]+").matches(scene.code) && scene.number > 0) { "场标识需为 SC 加数字，序号须大于零。" }
                    title(scene.heading, "场标题")
                    require(scene.changedAt >= 0) { "场修订号不能为负数。" }
                    require(scene.shots.size <= 100) { "每场最多 100 个镜头。" }
                    scene.shots.forEach { shot ->
                        require(Regex("SC[0-9]+-[0-9]+").matches(shot.code) && shot.code.startsWith(scene.code + "-")) { "镜头标识须为所属场标识加短横线和数字。" }
                        title(shot.title, "镜头标题")
                        require(shot.durationSeconds.isFinite() && shot.durationSeconds > 0) { "镜头时长必须为有限正数。" }
                        require(shot.prompt.length <= 7000) { "镜头提示词最多 7000 字。" }
                        note(shot.userNote, "用户批注"); note(shot.agentNote, "智能体说明")
                        require(shot.changedAt >= 0) { "镜头修订号不能为负数。" }
                        require(shot.inputs.size <= 12) { "每个镜头最多 12 个输入。" }
                        shot.inputs.forEach { input ->
                            validatePath(input.path); input.label?.let { title(it, "输入名称") }
                            require(input.atSeconds == null || input.role == InputRole.ANCHOR && input.atSeconds.isFinite() && input.atSeconds >= 0) { "锚点时间须为非负有限数值，且仅用于 anchor 输入。" }
                        }
                        path(shot.previewPath)
                        require(shot.takes.size <= 30) { "每个镜头最多 30 个候选。" }
                        unique(shot.takes.map { it.id }, "候选")
                        shot.takes.forEach { take ->
                            require(take.id.isNotBlank()) { "候选 id 不能为空。" }
                            validatePath(take.path); title(take.source.service, "候选服务"); note(take.note, "候选说明")
                        }
                        require(shot.selectedTakeId == null || shot.takes.any { it.id == shot.selectedTakeId }) { "选定候选不存在，请重新选择。" }
                    }
                }
            }
        }
        unique(episodeIds, "集")
        require(p.assets.size <= 500) { "最多 500 个资产。" }
        unique(p.assets.map { it.id }, "资产")
        p.assets.forEach { asset ->
            require(asset.id.length <= 48 && Regex("[a-z][a-z0-9-]*").matches(asset.id)) { "资产 id 需以小写字母开头，最多 48 个小写字母、数字或短横线。" }
            title(asset.name, "资产名称"); note(asset.description, "资产说明"); note(asset.userNote, "资产批注")
            require(asset.changedAt >= 0) { "资产修订号不能为负数。" }
            require(asset.versions.size <= 30) { "每个资产最多 30 个版本。" }
            require(asset.versions.map { it.number }.distinct().size == asset.versions.size && asset.versions.all { it.number > 0 }) { "资产版本号须为唯一正整数。" }
            asset.versions.forEach { validatePath(it.path); note(it.note, "资产版本说明") }
            require(asset.currentVersion == null || asset.versions.any { it.number == asset.currentVersion }) { "资产当前版本不存在。" }
        }
    }
}
