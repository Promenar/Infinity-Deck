package com.promenar.infinity.ui.pet

import com.promenar.infinity.domain.pet.PetRig
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** 网格每边的格数；顶点数为 (N+1)²。 */
internal const val PET_MESH_CELLS = 16
internal const val PET_MESH_VERTICES = (PET_MESH_CELLS + 1) * (PET_MESH_CELLS + 1)

/** 阻尼弹簧，头发、尾巴和落地回弹共用。 */
internal class PetSpring(private val stiffness: Float, private val damping: Float) {
    var x = 0f
    var v = 0f

    fun step(target: Float, dt: Float): Float {
        // 分两个子步积分，帧间隔偏大时仍然稳定。
        val h = dt / 2f
        repeat(2) {
            val a = -stiffness * (x - target) - damping * v
            v += a * h
            x += v * h
        }
        return x
    }
}

/** 一帧的程序化运动量：呼吸、点头、跳跃、抖动等。长度单位为 dp，角度为弧度。 */
internal class PetMotion {
    var breath = 0f
    var breathAmplitude = 0f
    var head = 0f
    var hopY = 0f
    var shakeX = 0f
    var bob = 0f
    var squash = 0f

    fun reset() {
        breath = 0f
        breathAmplitude = 0f
        head = 0f
        hopY = 0f
        shakeX = 0f
        bob = 0f
        squash = 0f
    }
}

/** 按片段声明的 `motion` 预设计算时刻 [t]（秒）的运动量，见规格第 3.1 节。 */
internal fun petMotion(preset: String, t: Float, out: PetMotion) {
    out.reset()
    when (preset) {
        "breathe" -> {
            out.breath = sin(t * 2f * Math.PI.toFloat() / 3.6f)
            out.breathAmplitude = 0.012f
            out.head = 0.03f * sin(t * 0.9f)
        }
        "nod" -> {
            out.breath = sin(t * 2f * Math.PI.toFloat() / 2.6f)
            out.breathAmplitude = 0.010f
            out.head = 0.045f * sin(t * 2.4f)
            out.bob = 1.2f * abs(sin(t * 2.4f))
        }
        "hop" -> {
            val c = (t * 1.4f) % 1f
            out.hopY = if (c < 0.35f) -5f * sin(c / 0.35f * Math.PI.toFloat()) else 0f
            out.squash = if (c > 0.35f && c < 0.5f) sin((c - 0.35f) / 0.15f * Math.PI.toFloat()) * 0.05f else 0f
            out.head = 0.03f * sin(t * 3.1f)
        }
        "bounce" -> {
            val c = (t * 1.05f) % 1f
            out.hopY = if (c < 0.5f) -14f * sin(c / 0.5f * Math.PI.toFloat()) else 0f
            out.squash = when {
                c > 0.5f && c < 0.68f -> sin((c - 0.5f) / 0.18f * Math.PI.toFloat()) * 0.08f
                c < 0.08f -> sin(c / 0.08f * Math.PI.toFloat()) * 0.05f
                else -> 0f
            }
            out.head = 0.03f * sin(t * 6.6f)
        }
        "shake" -> out.shakeX = 2.5f * sin(t * 38f)
    }
}

/** 头发与尾巴的弹簧状态，由头部角度和身体速度驱动。 */
internal class PetRigState {
    val hairLeft = PetSpring(60f, 7f)
    val hairRight = PetSpring(52f, 6f)
    val tail = PetSpring(38f, 4.5f)
    private var previousBodyY = 0f
    private var bodyVelocityY = 0f
    private var previousBodyX = 0f

    fun step(motion: PetMotion, bodyX: Float, t: Float, dt: Float) {
        if (dt <= 0f) return
        val bodyY = motion.hopY + motion.bob
        val velocityY = (bodyY - previousBodyY) / dt
        val accelerationY = (velocityY - bodyVelocityY) / dt
        bodyVelocityY = velocityY
        previousBodyY = bodyY
        val velocityX = ((bodyX - previousBodyX) / dt).coerceIn(-400f, 400f)
        previousBodyX = bodyX
        val wind = 0.05f * (sin(t * 1.04f) * 0.5f + sin(t * 2.16f + 1.7f) * 0.3f + sin(t * 3.28f + 0.4f) * 0.2f)
        val lift = (accelerationY * 0.00005f).coerceIn(-0.08f, 0.08f)
        hairLeft.step(-motion.head * 0.55f - velocityX * 0.0028f + wind + lift, dt)
        hairRight.step(-motion.head * 0.55f - velocityX * 0.0028f + wind * 0.8f - lift, dt)
        tail.step(0.14f * sin(t * 1.8f) - motion.head * 0.8f + (velocityY * 0.002f).coerceIn(-0.3f, 0.3f), dt)
    }

    fun settle() {
        hairLeft.x = 0f
        hairLeft.v = 0f
        hairRight.x = 0f
        hairRight.v = 0f
        tail.x = 0f
        tail.v = 0f
    }
}

private fun smooth(a: Float, b: Float, x: Float): Float {
    val t = ((x - a) / (b - a)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/**
 * 计算一张片段图的网格顶点位置。
 *
 * 图片以 [anchorU]、[anchorV]（归一化）为接触点落在 ([baseX], [baseY])，整幅显示为 [width]×[height] 像素。
 * [dpToPx] 把运动量里的 dp 换成像素。结果按行优先写入 [out]，每个顶点两个值。
 */
internal fun deformPetMesh(
    out: FloatArray,
    width: Float,
    height: Float,
    anchorU: Float,
    anchorV: Float,
    rig: PetRig?,
    motion: PetMotion,
    rigState: PetRigState?,
    baseX: Float,
    baseY: Float,
    pop: Float,
    rotation: Float,
    flip: Boolean,
    dpToPx: Float,
) {
    val n = PET_MESH_CELLS
    val neckU = rig?.neck?.getOrNull(0)?.toFloat() ?: 0.5f
    val neckV = rig?.neck?.getOrNull(1)?.toFloat() ?: 0.5f
    val hasNeck = rig?.neck != null
    val hairTop = rig?.hairTop?.toFloat()
    val tail = rig?.tail
    val sitting = rig?.sitting == true
    val lying = rig?.lying == true
    val headCos = cos(motion.head)
    val headSin = sin(motion.head)
    val hairLeft = rigState?.hairLeft?.x ?: 0f
    val hairRight = rigState?.hairRight?.x ?: 0f
    val tailAngle = rigState?.tail?.x ?: 0f
    val squashY = 1f - motion.squash
    val squashX = 1f + motion.squash * 0.8f
    val rotationCos = cos(rotation)
    val rotationSin = sin(rotation)
    for (j in 0..n) {
        for (i in 0..n) {
            val u = i / n.toFloat()
            val v = j / n.toFloat()
            var x = (u - anchorU) * width
            var y = (v - anchorV) * height
            val above = anchorV - v
            val side = u - 0.5f
            val distance = abs(side)
            // 呼吸：躯干上提并微微横向舒张，接触点不动。
            y -= motion.breathAmplitude * motion.breath * above * height * (if (lying) smooth(0.3f, 0.7f, u) else 1f)
            x += motion.breathAmplitude * 0.6f * motion.breath * side * width * smooth(0.05f, 0.4f, above)
            // 头发：两侧下垂部分按弹簧摆动，坐姿时压住地面上的发尾。
            if (hairTop != null) {
                var weight = smooth(0.16f, 0.42f, distance) * smooth(hairTop, hairTop + 0.3f, v)
                if (sitting) weight *= 1f - smooth(0.86f, 0.99f, v)
                val swing = if (side < 0f) hairLeft else hairRight
                x += swing * weight * (v - hairTop) * height * 0.9f
                y += abs(swing) * weight * (v - hairTop) * height * 0.08f
            }
            // 尾巴：绕尾根旋转，权重随离尾根的距离增加。
            if (tail != null && tail.root.size == 2 && tail.tip.size == 2) {
                val rootU = tail.root[0].toFloat()
                val rootV = tail.root[1].toFloat()
                val alongU = tail.tip[0].toFloat() - rootU
                val alongV = tail.tip[1].toFloat() - rootV
                val length = max(1e-4f, hypot(alongU, alongV))
                val du = u - rootU
                val dv = v - rootV
                val s = (du * alongU + dv * alongV) / (length * length)
                val across = abs(du * alongV - dv * alongU) / length
                val radius = tail.radius.toFloat()
                val weight = smooth(0f, 1f, s) * s.coerceIn(0f, 1.2f).pow(1.2f) *
                    (1f - smooth(radius * 0.8f, radius * 1.3f, across))
                if (weight > 0f) {
                    val angle = tailAngle * weight
                    val pivotX = (rootU - anchorU) * width
                    val pivotY = (rootV - anchorV) * height
                    val rx = x - pivotX
                    val ry = y - pivotY
                    x = pivotX + rx * cos(angle) - ry * sin(angle)
                    y = pivotY + rx * sin(angle) + ry * cos(angle)
                }
            }
            // 头部旋转，颈部平滑过渡；两侧长发只部分跟随。
            if (hasNeck) {
                var weight = if (lying) (1f - smooth(0.35f, 0.6f, u)) * 0.3f else 1f - smooth(neckV - 0.07f, neckV + 0.06f, v)
                weight *= 1f - 0.55f * smooth(0.18f, 0.45f, distance) * smooth(neckV - 0.1f, neckV + 0.1f, v)
                if (weight > 0f) {
                    val pivotX = (neckU - anchorU) * width
                    val pivotY = (neckV - anchorV) * height
                    val rx = x - pivotX
                    val ry = y - pivotY
                    val c = 1f + (headCos - 1f) * weight
                    val s = headSin * weight
                    x = pivotX + rx * c - ry * s
                    y = pivotY + rx * s + ry * c
                }
            }
            if (!lying) y += motion.bob * dpToPx * smooth(0.1f, 0.5f, above)
            // 挤压拉伸与落地回弹，以接触点为原点。
            x *= squashX * (1f + (1f - pop) * 0.5f)
            y *= squashY * pop
            if (rotation != 0f) {
                val rx = x
                val ry = y
                x = rx * rotationCos - ry * rotationSin
                y = rx * rotationSin + ry * rotationCos
            }
            if (flip) x = -x
            val k = (j * (n + 1) + i) * 2
            out[k] = baseX + x + motion.shakeX * dpToPx
            out[k + 1] = baseY + y + motion.hopY * dpToPx
        }
    }
}

/** 网格的三角形索引，行优先。 */
internal fun petMeshIndices(): ShortArray {
    val n = PET_MESH_CELLS
    val indices = ShortArray(n * n * 6)
    var k = 0
    for (j in 0 until n) {
        for (i in 0 until n) {
            val a = j * (n + 1) + i
            val b = a + 1
            val c = a + n + 1
            val d = c + 1
            indices[k++] = a.toShort()
            indices[k++] = b.toShort()
            indices[k++] = c.toShort()
            indices[k++] = b.toShort()
            indices[k++] = d.toShort()
            indices[k++] = c.toShort()
        }
    }
    return indices
}

/** 把片段在图集中的一格（像素矩形）写成网格的纹理坐标。 */
internal fun petMeshTextureCoordinates(out: FloatArray, left: Float, top: Float, cellWidth: Float, cellHeight: Float) {
    val n = PET_MESH_CELLS
    for (j in 0..n) {
        for (i in 0..n) {
            val k = (j * (n + 1) + i) * 2
            out[k] = left + cellWidth * i / n
            out[k + 1] = top + cellHeight * j / n
        }
    }
}

internal fun petClamp(value: Float, low: Float, high: Float): Float = min(max(value, low), high)
