package com.promenar.infinity.domain.pet

import java.util.Locale

/** 基座支持的契约版本，见规格第 1 节。 */
object PetContract {
    const val MAJOR = 0
    const val MINOR = 3
}

/** 片段及显示字段的契约取值，见规格第 3 节。 */
object PetValues {
    const val POSE = "pose"
    const val FRAMES = "frames"
    const val VIDEO = "video"
    val clipTypes = setOf(POSE, FRAMES, VIDEO)
    val staticClipTypes = setOf(POSE, FRAMES)
    val motions = setOf("breathe", "nod", "hop", "shake", "bounce", "none")
    val alphas = setOf("side_by_side", "chroma_green")
    val facings = setOf("left", "right")
    val haptics = setOf("tick", "thud", "buzz")
    val perchKinds = setOf("cloud", "bubble")
    val zoneNames = setOf("head", "belly", "tail")
}

/** 导入限制，见规格第 3、7.1、10 节。 */
data class PetLimits(
    val maxPackageBytes: Long = 30L * 1024 * 1024,
    val maxFiles: Int = 200,
    val maxImageEdge: Int = 4096,
    val maxVideoEdge: Int = 1024,
    val maxVideoDurationMillis: Long = 15_000,
    val minHeightDp: Int = 80,
    val maxHeightDp: Int = 160,
    val maxPersonaText: Int = 200,
    val maxPersonaItems: Int = 10,
    val maxPersonaItemLength: Int = 40,
    val maxFallbackItems: Int = 8,
    val maxFallbackLength: Int = 40,
)

/** 已取得的文件元数据，见规格第 10 节。 */
data class PetFile(
    val path: String,
    val bytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val codec: String? = null,
)

/** 校验失败的原因，见规格第 10 节。 */
enum class PetIssueCode {
    UNSAFE_PATH,
    DUPLICATE_PATH,
    PACKAGE_SIZE,
    FILE_COUNT,
    UNSUPPORTED_EXTENSION,
    INVALID_FILE_SIZE,
    MISSING_FILE,
    MISSING_MEDIA_METADATA,
    IMAGE_SIZE,
    VIDEO_SIZE,
    VIDEO_DURATION,
    VIDEO_CODEC,
    INVALID_CONTRACT,
    UNSUPPORTED_CONTRACT,
    INVALID_FIELD,
    MISSING_IDLE,
    MISSING_CLIP,
    INVALID_VIDEO_FALLBACK,
    PERSONA_LENGTH,
    PERSONA_COUNT,
}

/** 一条可供导入界面展示的校验结果，见规格第 10 节。 */
data class PetIssue(
    val code: PetIssueCode,
    val file: String? = null,
    val field: String? = null,
    val actual: String? = null,
    val limit: String? = null,
)

/** 汇总包内所有可检查的问题，见规格第 10 节。 */
class PetPackageValidator(private val limits: PetLimits = PetLimits()) {
    fun validate(manifest: PetManifest, persona: PetPersona?, files: List<PetFile>): List<PetIssue> {
        val check = Check(manifest, persona, files, limits)
        return check.validate()
    }

    private class Check(
        val manifest: PetManifest,
        val persona: PetPersona?,
        val files: List<PetFile>,
        val limits: PetLimits,
    ) {
        val out = mutableListOf<PetIssue>()
        val paths = mutableSetOf<String>()

        fun validate(): List<PetIssue> {
            files()
            contract()
            fields()
            clips()
            mappings()
            expressions()
            extensions()
            persona()
            return out
        }

        fun issue(code: PetIssueCode, file: String? = null, field: String? = null, actual: Any? = null, limit: Any? = null) {
            out.add(PetIssue(code, file, field, actual?.toString(), limit?.toString()))
        }

        fun invalid(field: String, value: Any?) = issue(PetIssueCode.INVALID_FIELD, field = field, actual = value)

        fun requireFile(path: String) {
            if (path !in paths) issue(PetIssueCode.MISSING_FILE, file = path)
        }

        fun files() {
            val total = files.fold(0L) { acc, file ->
                if (file.bytes < 0 || Long.MAX_VALUE - acc < file.bytes) Long.MAX_VALUE else acc + file.bytes
            }
            if (total > limits.maxPackageBytes) issue(PetIssueCode.PACKAGE_SIZE, actual = total, limit = limits.maxPackageBytes)
            if (files.size > limits.maxFiles) issue(PetIssueCode.FILE_COUNT, actual = files.size, limit = limits.maxFiles)
            files.forEach(::file)
            requireFile("manifest.json")
            requireFile("thumbnail.webp")
            if (persona != null) requireFile("persona.json")
        }

        fun file(file: PetFile) {
            val parts = file.path.split('/')
            if (file.path.isEmpty() || file.path.startsWith('/') || '\\' in file.path ||
                parts.any { it == ".." || it == "." || it.isEmpty() } ||
                Regex("^[A-Za-z]:").containsMatchIn(file.path)
            ) issue(PetIssueCode.UNSAFE_PATH, file.path)
            if (!paths.add(file.path)) issue(PetIssueCode.DUPLICATE_PATH, file.path)
            if (file.bytes < 0) issue(PetIssueCode.INVALID_FILE_SIZE, file.path, actual = file.bytes)
            val ext = file.path.substringAfterLast('.', "").lowercase(Locale.ROOT)
            if (ext !in setOf("json", "webp", "png", "mp4", "txt")) {
                issue(PetIssueCode.UNSUPPORTED_EXTENSION, file.path, actual = ext)
            }
            if (ext in setOf("webp", "png", "mp4")) dimensions(file, ext)
            if (ext == "mp4") video(file)
        }

        fun dimensions(file: PetFile, ext: String) {
            val width = file.width
            val height = file.height
            if (width == null || height == null || width <= 0 || height <= 0) {
                issue(PetIssueCode.MISSING_MEDIA_METADATA, file.path, "dimensions")
                return
            }
            val limit = if (ext == "mp4") limits.maxVideoEdge else limits.maxImageEdge
            val code = if (ext == "mp4") PetIssueCode.VIDEO_SIZE else PetIssueCode.IMAGE_SIZE
            if (maxOf(width, height) > limit) issue(code, file.path, actual = maxOf(width, height), limit = limit)
        }

        fun video(file: PetFile) {
            val duration = file.durationMillis
            if (duration == null || duration <= 0) {
                issue(PetIssueCode.MISSING_MEDIA_METADATA, file.path, "durationMillis")
            } else if (duration > limits.maxVideoDurationMillis) {
                issue(PetIssueCode.VIDEO_DURATION, file.path, actual = duration, limit = limits.maxVideoDurationMillis)
            }
            val codecs = setOf("h.264", "h264", "avc", "video/avc", "hevc", "h.265", "h265", "video/hevc")
            if (file.codec == null) issue(PetIssueCode.MISSING_MEDIA_METADATA, file.path, "codec")
            else if (file.codec.lowercase(Locale.ROOT) !in codecs) issue(PetIssueCode.VIDEO_CODEC, file.path, actual = file.codec)
        }

        fun contract() {
            val version = Regex("^(\\d+)\\.(\\d+)$").matchEntire(manifest.contract)
            val major = version?.groupValues?.get(1)?.toLongOrNull()
            val minor = version?.groupValues?.get(2)?.toLongOrNull()
            if (major == null || minor == null) {
                issue(PetIssueCode.INVALID_CONTRACT, field = "contract", actual = manifest.contract)
            } else if (major > PetContract.MAJOR) {
                issue(
                    PetIssueCode.UNSUPPORTED_CONTRACT, field = "contract", actual = manifest.contract,
                    limit = "${PetContract.MAJOR}.${PetContract.MINOR}",
                )
            }
        }

        fun fields() {
            if (manifest.display.heightDp !in limits.minHeightDp..limits.maxHeightDp) {
                issue(PetIssueCode.INVALID_FIELD, field = "display.height_dp", actual = manifest.display.heightDp,
                    limit = "${limits.minHeightDp}..${limits.maxHeightDp}")
            }
            if (manifest.display.facing !in PetValues.facings) invalid("display.facing", manifest.display.facing)
            listOf("id" to manifest.id, "name" to manifest.name, "version" to manifest.version,
                "license.name" to manifest.license.name).forEach { (field, value) ->
                if (value.isBlank()) invalid(field, value)
            }
        }

        fun point(field: String, value: List<Double>) {
            if (value.size != 2 || value.any { !it.isFinite() || it !in 0.0..1.0 }) invalid(field, value)
        }

        fun clips() {
            manifest.clips.forEach { (name, clip) ->
                val field = "clips.$name"
                requireFile(clip.file)
                point("$field.anchor", clip.anchor)
                if (!clip.scale.isFinite() || clip.scale <= 0) invalid("$field.scale", clip.scale)
                if (clip.motion != null && clip.motion !in PetValues.motions) invalid("$field.motion", clip.motion)
                clip.rig?.let { rig(field, it) }
                when (clip.type) {
                    PetValues.POSE -> Unit
                    PetValues.FRAMES -> frames(field, clip)
                    PetValues.VIDEO -> videoClip(field, clip)
                    else -> invalid("$field.type", clip.type)
                }
            }
        }

        fun rig(field: String, rig: PetRig) {
            rig.neck?.let { point("$field.rig.neck", it) }
            rig.hairTop?.let {
                if (!it.isFinite() || it !in 0.0..1.0) invalid("$field.rig.hair_top", it)
            }
            rig.tail?.let {
                point("$field.rig.tail.root", it.root)
                point("$field.rig.tail.tip", it.tip)
                if (!it.radius.isFinite() || it.radius <= 0) invalid("$field.rig.tail.radius", it.radius)
            }
        }

        fun frames(field: String, clip: PetClip) {
            val cols = clip.cols
            val rows = clip.rows
            val count = clip.count
            val fps = clip.fps
            if (cols == null || rows == null || count == null || fps == null || clip.loop == null ||
                cols <= 0 || rows <= 0 || count <= 0 || count.toLong() > cols.toLong() * rows ||
                !fps.isFinite() || fps <= 0
            ) invalid(field, PetValues.FRAMES)
        }

        fun videoClip(field: String, clip: PetClip) {
            if (clip.alpha !in PetValues.alphas || clip.loop == null) invalid(field, PetValues.VIDEO)
            if (clip.fallback == null || manifest.clips[clip.fallback]?.type !in PetValues.staticClipTypes) {
                issue(PetIssueCode.INVALID_VIDEO_FALLBACK, field = "$field.fallback", actual = clip.fallback)
            }
        }

        fun clip(name: String?, field: String) {
            if (name != null && name !in manifest.clips) issue(PetIssueCode.MISSING_CLIP, field = field, actual = name)
        }

        fun mappings() {
            if (PetSignals.IDLE !in manifest.map) issue(PetIssueCode.MISSING_IDLE, field = "map.idle")
            manifest.map.forEach { (signal, reaction) -> mapping(signal, reaction) }
        }

        fun mapping(signal: String, reaction: PetReaction) {
            val field = "map.$signal"
            val parts = signal.split('@')
            if (parts.size > 2 || parts.first().isBlank() || (parts.size == 2 && parts[1] !in
                setOf("perch", "message", "stage", "long", "verylong", "mood.high", "mood.low") + PetDaypart.all)) invalid(field, signal)
            if (reaction.haptic != null && reaction.haptic !in PetValues.haptics) invalid("$field.haptic", reaction.haptic)
            clip(reaction.clip, "$field.clip")
            clip(reaction.enter, "$field.enter")
            reaction.sometimes.forEach {
                clip(it.clip, "$field.sometimes.clip")
                if (!it.everyS.isFinite() || it.everyS <= 0 || it.forMs <= 0) invalid("$field.sometimes", it)
            }
            reaction.behaviors.forEach {
                clip(it.clip, "$field.behaviors.clip")
                val min = it.minS
                val max = it.maxS
                if (!it.weight.isFinite() || it.weight <= 0 || (min != null && (!min.isFinite() || min < 0)) ||
                    (max != null && (!max.isFinite() || max < 0)) || (min != null && max != null && min > max)
                ) invalid("$field.behaviors", it)
            }
            reaction.sequence.forEach {
                clip(it.clip, "$field.sequence.clip")
                if (it.launch != null && it.launch < 0) invalid("$field.sequence.launch", it.launch)
            }
            if (reaction.enterMs != null && reaction.enterMs < 0) invalid("$field.enter_ms", reaction.enterMs)
            if (reaction.expression != null && reaction.expression !in manifest.expressions.set) {
                invalid("$field.expression", reaction.expression)
            }
        }

        fun expressions() {
            val expressions = manifest.expressions
            expressions.baseClip?.let { clip(it, "expressions.base_clip") }
            expressions.face?.let {
                point("expressions.face.center", it.center)
                point("expressions.face.radius", it.radius)
            }
            expressions.set.values.forEach(::requireFile)
            if (expressions.set.isNotEmpty() && (expressions.baseClip == null || expressions.face == null)) {
                invalid("expressions", "base_clip/face")
            }
        }

        fun extensions() {
            manifest.zones.forEach { (name, zone) ->
                if (name !in PetValues.zoneNames) invalid("zones.$name", name)
                point("zones.$name.center", zone.center)
                point("zones.$name.radius", zone.radius)
            }
            manifest.props.perch.forEach { (name, prop) ->
                val field = "props.perch.$name"
                if (name !in PetValues.perchKinds) invalid(field, name)
                prop.file?.let {
                    requireFile(it)
                    if (it.substringAfterLast('.', "").lowercase(Locale.ROOT) !in setOf("png", "webp")) invalid("$field.file", it)
                }
                prop.seat?.let { point("$field.seat", it) }
                prop.widthDp?.let { if (it !in 60..200) invalid("$field.width_dp", it) }
                clip(prop.appear, "$field.appear")
                clip(prop.vanish, "$field.vanish")
            }
        }

        fun persona() {
            val p = persona ?: return
            length("persona.personality", p.personality, limits.maxPersonaText)
            length("persona.style", p.style, limits.maxPersonaText)
            items("persona.catchphrases", p.catchphrases, limits.maxPersonaItems, limits.maxPersonaItemLength)
            items("persona.avoid", p.avoid, limits.maxPersonaItems, limits.maxPersonaItemLength)
            items("persona.examples", p.examples, limits.maxPersonaItems, limits.maxPersonaItemLength)
            p.fallback.forEach { (key, values) ->
                items("persona.fallback.$key", values, limits.maxFallbackItems, limits.maxFallbackLength)
            }
        }

        fun length(field: String, value: String, max: Int) {
            val count = value.codePointCount(0, value.length)
            if (count > max) issue(PetIssueCode.PERSONA_LENGTH, field = field, actual = count, limit = max)
        }

        fun items(field: String, values: List<String>, count: Int, max: Int) {
            if (values.size > count) issue(PetIssueCode.PERSONA_COUNT, field = field, actual = values.size, limit = count)
            values.forEachIndexed { index, value -> length("$field[$index]", value, max) }
        }
    }
}
