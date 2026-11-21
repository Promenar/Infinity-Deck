package com.promenar.infinity.data.production

import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/**
 * 离线 3D 白模分镜预演（Previs）声明式场景 DSL。
 * - 右手 Y-Up 坐标系，单位米，`y = 0` 为地面；
 * - 道具与演员 `pos` 均为底面中心世界坐标；
 * - 镜头 `code` 对齐项目分镜 `shot.code` 以驱动时间线联动与单帧/成片导出。
 */
@Serializable
data class PrevisScene(
    val version: Int = 1,
    val unit: String = "m",
    val skyColor: String? = null,
    val props: List<PrevisProp> = emptyList(),
    val actors: List<PrevisActor> = emptyList(),
    val shots: List<PrevisShot> = emptyList(),
)

@Serializable
data class PrevisProp(
    val id: String,
    val label: String? = null,
    val shape: String = "box",
    val pos: List<Double>,
    val size: List<Double>,
    val rotY: Double = 0.0,
    val rot: List<Double>? = null,
    val color: String? = null,
)

@Serializable
data class PrevisActor(
    val id: String,
    val name: String? = null,
    val height: Double = 1.7,
    val color: String? = null,
    val accent: String? = null,
)

@Serializable
data class PrevisCamera(
    val from: List<Double>,
    val to: List<Double>? = null,
    val lookAtFrom: List<Double>,
    val lookAtTo: List<Double>? = null,
    val fov: Double? = null,
    val focalLengthMm: Double? = null,
    val roll: Double = 0.0,
    val easing: String = "easeInOut",
)

@Serializable
data class PrevisActorKeyframe(
    val id: String,
    val from: List<Double>,
    val to: List<Double>? = null,
    val facing: Double? = null,
    val lookAt: List<Double>? = null,
    val lookAtActor: String? = null,
    val pose: String = "stand",
)

@Serializable
data class PrevisAxisSpec(
    val actorA: String,
    val actorB: String,
    val side: Int = 1,
)

@Serializable
data class PrevisShot(
    val code: String,
    val duration: Double,
    val label: String? = null,
    val camera: PrevisCamera,
    val actors: List<PrevisActorKeyframe> = emptyList(),
    val axis: PrevisAxisSpec? = null,
    val depthRange: List<Double>? = null,
)

fun PrevisScene.totalDurationSeconds(): Double = shots.sumOf { it.duration }

fun PrevisScene.toShotMap(): Map<String, PrevisShot> = shots.associateBy { it.code }

fun defaultPrevisPath(rootPath: String, episodeId: String): String =
    "${rootPath.trimEnd('/')}/previs/$episodeId.previs.json"

fun defaultPrevisStillPath(rootPath: String, episodeId: String, shotCode: String, isDepth: Boolean): String {
    val suffix = if (isDepth) "depth" else "beauty"
    return "${rootPath.trimEnd('/')}/previs/frames/${episodeId}_${shotCode}-$suffix.png"
}

fun defaultPrevisVideoPath(rootPath: String, episodeId: String): String =
    "${rootPath.trimEnd('/')}/previs/$episodeId-animatic.mp4"

/**
 * 将项目画幅字符串（如 `16:9`、`9:16`、`1:1`、`2.39:1`）映射为 16 像素对齐的导出分辨率，
 * 默认 `1280×720`，宽高均限制在 `[320, 1280]` 内以适配移动硬件 H.264 编码能力。
 */
fun previsExportDimensions(aspectRatio: String?): Pair<Int, Int> {
    val ratio = parseAspectRatio(aspectRatio) ?: (16.0 / 9.0)
    fun align16(value: Double): Int = ((value / 16.0).roundToInt() * 16).coerceIn(320, 1280)
    return if (ratio >= 1.0) {
        val w = 1280
        val h = align16(w / ratio)
        w to h
    } else {
        val h = 1280
        val w = align16(h * ratio)
        w to h
    }
}

private fun parseAspectRatio(raw: String?): Double? {
    val cleaned = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val parts = cleaned.split(':')
    if (parts.size == 2) {
        val a = parts[0].trim().toDoubleOrNull()
        val b = parts[1].trim().toDoubleOrNull()
        if (a != null && b != null && a > 0.0 && b > 0.0 && a.isFinite() && b.isFinite()) {
            return (a / b).coerceIn(0.4, 3.0)
        }
    }
    return cleaned.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }?.coerceIn(0.4, 3.0)
}
