package com.promenar.infinity.ui.avatar

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 90° 倍数旋转与水平镜像组成的方向：先按 [mirrored] 水平翻转，再顺时针旋转 [quarterTurns] 个 90°。
 * 坐标系与屏幕一致，x 向右、y 向下。
 */
internal data class AvatarOrientation(val quarterTurns: Int = 0, val mirrored: Boolean = false) {

    init {
        require(quarterTurns in 0..3) { "quarterTurns 必须在 0..3" }
    }

    /** 旋转奇数次时宽高互换。 */
    val swapsAxes: Boolean get() = quarterTurns % 2 == 1

    /** 组合方向：先应用 [other]，再应用本方向。 */
    operator fun times(other: AvatarOrientation): AvatarOrientation =
        if (mirrored) {
            // 镜像与旋转交换次序时旋转方向取反：F·R^k = R^-k·F。
            of(quarterTurns - other.quarterTurns, !other.mirrored)
        } else {
            of(quarterTurns + other.quarterTurns, other.mirrored)
        }

    /** 在当前方向之后再顺时针旋转 90°。 */
    fun rotatedClockwise(): AvatarOrientation = of(quarterTurns + 1, mirrored)

    /** 在当前方向之后再按屏幕水平方向镜像。 */
    fun mirroredHorizontally(): AvatarOrientation = MIRROR * this

    /** 把以图片中心为原点的向量映射到方向变换之后的屏幕向量，返回 [a, b, c, d]，即 x' = a·x + b·y，y' = c·x + d·y。 */
    fun matrix(): IntArray {
        var a = if (mirrored) -1 else 1
        var b = 0
        var c = 0
        var d = 1
        repeat(quarterTurns) {
            // 顺时针 90°：(x, y) → (-y, x)。
            val na = -c
            val nb = -d
            c = a
            d = b
            a = na
            b = nb
        }
        return intArrayOf(a, b, c, d)
    }

    companion object {
        val IDENTITY = AvatarOrientation()
        private val MIRROR = AvatarOrientation(0, true)

        fun of(quarterTurns: Int, mirrored: Boolean): AvatarOrientation =
            AvatarOrientation(Math.floorMod(quarterTurns, 4), mirrored)

        /** EXIF Orientation 1..8 对应的“原始像素 → 正向显示”变换；未知值按 1 处理。 */
        fun fromExif(value: Int): AvatarOrientation = when (value) {
            2 -> AvatarOrientation(0, true)
            3 -> AvatarOrientation(2, false)
            4 -> AvatarOrientation(2, true)
            5 -> AvatarOrientation(3, true)
            6 -> AvatarOrientation(1, false)
            7 -> AvatarOrientation(1, true)
            8 -> AvatarOrientation(3, false)
            else -> IDENTITY
        }
    }
}

/** 二维仿射变换：x' = a·x + b·y + tx，y' = c·x + d·y + ty。字段次序与 android.graphics.Matrix 的 setValues 前两行一致。 */
internal data class AvatarAffine(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val tx: Float,
    val ty: Float,
) {
    fun mapX(x: Float, y: Float): Float = a * x + b * y + tx
    fun mapY(x: Float, y: Float): Float = c * x + d * y + ty

    /** 先应用本变换，再应用 [next]。 */
    fun then(next: AvatarAffine): AvatarAffine = AvatarAffine(
        a = next.a * a + next.b * c,
        b = next.a * b + next.b * d,
        c = next.c * a + next.d * c,
        d = next.c * b + next.d * d,
        tx = next.a * tx + next.b * ty + next.tx,
        ty = next.c * tx + next.d * ty + next.ty,
    )

    fun inverted(): AvatarAffine {
        val det = a * d - b * c
        require(det != 0f) { "变换不可逆" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return AvatarAffine(ia, ib, ic, id, -(ia * tx + ib * ty), -(ic * tx + id * ty))
    }

    /** android.graphics.Matrix.setValues 所需的 3×3 行优先数组。 */
    fun toMatrixValues(): FloatArray = floatArrayOf(a, b, tx, c, d, ty, 0f, 0f, 1f)

    companion object {
        fun scaleTranslate(scaleX: Float, scaleY: Float, translateX: Float, translateY: Float) =
            AvatarAffine(scaleX, 0f, 0f, scaleY, translateX, translateY)
    }
}

/** 源图上的整数像素矩形，右、下边界不含。 */
internal data class AvatarPixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * 取景状态，与取景区像素尺寸无关：以裁剪方形边长为 1 个单位，方形中心为原点。
 *
 * @property zoom 相对“刚好铺满”的倍数，范围 1..[AvatarCropGeometry.MAX_ZOOM]。
 * @property offsetX 图片中心相对裁剪方形中心的水平偏移，单位为裁剪方形边长。
 * @property orientation 用户在 EXIF 正向基础上叠加的旋转与镜像。
 */
internal data class AvatarCropState(
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val orientation: AvatarOrientation = AvatarOrientation.IDENTITY,
)

/**
 * 头像取景的纯几何：铺满约束、旋转镜像下的坐标变换、源图裁剪区域与输出变换。
 * 尺寸均为源图原始像素（未应用 EXIF），[exif] 描述原始像素到正向显示的变换。
 */
internal class AvatarCropGeometry(
    val rawWidth: Int,
    val rawHeight: Int,
    val exif: AvatarOrientation = AvatarOrientation.IDENTITY,
) {
    init {
        require(rawWidth > 0 && rawHeight > 0) { "图片尺寸无效：${rawWidth}x$rawHeight" }
    }

    /** 缩放为 1 时每个源像素对应的裁剪单位：短边刚好等于裁剪方形边长。 */
    private val baseScale: Float = 1f / min(rawWidth, rawHeight)

    fun initialState(): AvatarCropState = AvatarCropState()

    /** 用户方向与 EXIF 方向叠加后的总方向。 */
    fun combinedOrientation(state: AvatarCropState): AvatarOrientation = state.orientation * exif

    /** 按当前方向显示时的宽高（源像素）。 */
    fun displayedSize(state: AvatarCropState): Pair<Int, Int> =
        if (combinedOrientation(state).swapsAxes) rawHeight to rawWidth else rawWidth to rawHeight

    /** 把缩放限制在 1..MAX_ZOOM，把偏移限制在图片仍覆盖整个裁剪方形的范围内。 */
    fun clamp(state: AvatarCropState): AvatarCropState {
        val zoom = state.zoom.takeIf { it.isFinite() }?.coerceIn(1f, MAX_ZOOM) ?: 1f
        val (width, height) = displayedSize(state)
        val scale = baseScale * zoom
        val maxX = max(0f, (width * scale - 1f) / 2f)
        val maxY = max(0f, (height * scale - 1f) / 2f)
        return state.copy(
            zoom = zoom,
            offsetX = state.offsetX.finiteOrZero().coerceIn(-maxX, maxX).withoutNegativeZero(),
            offsetY = state.offsetY.finiteOrZero().coerceIn(-maxY, maxY).withoutNegativeZero(),
        )
    }

    /** 平移，[dx]、[dy] 以裁剪方形边长为单位。 */
    fun pan(state: AvatarCropState, dx: Float, dy: Float): AvatarCropState =
        clamp(state.copy(offsetX = state.offsetX + dx, offsetY = state.offsetY + dy))

    /** 以 ([focusX], [focusY]) 为不动点缩放，焦点坐标同样以裁剪方形中心为原点、边长为单位。 */
    fun zoomBy(state: AvatarCropState, factor: Float, focusX: Float = 0f, focusY: Float = 0f): AvatarCropState {
        if (!factor.isFinite() || factor <= 0f) return clamp(state)
        val newZoom = (state.zoom * factor).coerceIn(1f, MAX_ZOOM)
        val ratio = newZoom / state.zoom
        return clamp(
            state.copy(
                zoom = newZoom,
                offsetX = focusX + (state.offsetX - focusX) * ratio,
                offsetY = focusY + (state.offsetY - focusY) * ratio,
            ),
        )
    }

    fun withZoom(state: AvatarCropState, zoom: Float): AvatarCropState = zoomBy(state, zoom / state.zoom)

    /** 双击：刚好铺满时以点击处为中心放大到 [DOUBLE_TAP_ZOOM]，否则回到刚好铺满并居中。 */
    fun toggleZoom(state: AvatarCropState, focusX: Float = 0f, focusY: Float = 0f): AvatarCropState =
        if (state.zoom < 1f + ZOOM_EPSILON) {
            zoomBy(state, DOUBLE_TAP_ZOOM / state.zoom, focusX, focusY)
        } else {
            clamp(state.copy(zoom = 1f, offsetX = 0f, offsetY = 0f))
        }

    /** 整体顺时针旋转 90°，当前取景中心随图片一起转动。 */
    fun rotateClockwise(state: AvatarCropState): AvatarCropState = clamp(
        state.copy(
            offsetX = -state.offsetY,
            offsetY = state.offsetX,
            orientation = state.orientation.rotatedClockwise(),
        ),
    )

    /** 按屏幕水平方向镜像，当前取景内容随图片一起翻转。 */
    fun mirrorHorizontally(state: AvatarCropState): AvatarCropState = clamp(
        state.copy(
            offsetX = -state.offsetX,
            orientation = state.orientation.mirroredHorizontally(),
        ),
    )

    /**
     * 源图原始像素到目标画布像素的变换。目标画布上裁剪方形边长为 [cropSide]、中心位于 ([centerX], [centerY])。
     * 取景区、实时预览和输出图都用这一个变换绘制。
     */
    fun transform(state: AvatarCropState, cropSide: Float, centerX: Float, centerY: Float): AvatarAffine {
        val (ma, mb, mc, md) = combinedOrientation(state).matrix()
        val s = baseScale * state.zoom * cropSide
        val a = ma * s
        val b = mb * s
        val c = mc * s
        val d = md * s
        val halfW = rawWidth / 2f
        val halfH = rawHeight / 2f
        return AvatarAffine(
            a = a,
            b = b,
            c = c,
            d = d,
            tx = centerX + state.offsetX * cropSide - (a * halfW + b * halfH),
            ty = centerY + state.offsetY * cropSide - (c * halfW + d * halfH),
        )
    }

    /** 裁剪方形对应的源图区域（原始像素，浮点），已限制在图片范围内。 */
    fun sourceCropRegion(state: AvatarCropState): FloatArray {
        val inverse = transform(state, cropSide = 1f, centerX = 0f, centerY = 0f).inverted()
        val corners = listOf(-0.5f to -0.5f, 0.5f to -0.5f, -0.5f to 0.5f, 0.5f to 0.5f)
        val xs = corners.map { (x, y) -> inverse.mapX(x, y) }
        val ys = corners.map { (x, y) -> inverse.mapY(x, y) }
        return floatArrayOf(
            xs.min().coerceIn(0f, rawWidth.toFloat()),
            ys.min().coerceIn(0f, rawHeight.toFloat()),
            xs.max().coerceIn(0f, rawWidth.toFloat()),
            ys.max().coerceIn(0f, rawHeight.toFloat()),
        )
    }

    /** 外扩到整数像素的裁剪区域，供区域解码使用。 */
    fun sourceCropBounds(state: AvatarCropState): AvatarPixelRect {
        val (left, top, right, bottom) = sourceCropRegion(state)
        val l = floor(left + PIXEL_EPSILON).toInt().coerceIn(0, rawWidth - 1)
        val t = floor(top + PIXEL_EPSILON).toInt().coerceIn(0, rawHeight - 1)
        val r = ceil(right - PIXEL_EPSILON).toInt().coerceIn(l + 1, rawWidth)
        val b = ceil(bottom - PIXEL_EPSILON).toInt().coerceIn(t + 1, rawHeight)
        return AvatarPixelRect(l, t, r, b)
    }

    /**
     * 解码出的局部位图像素到输出图像素的变换。[region] 是解码时请求的源图区域，
     * [decodedWidth]、[decodedHeight] 是实际得到的位图尺寸（含 inSampleSize 缩小）。
     */
    fun regionTransform(
        state: AvatarCropState,
        region: AvatarPixelRect,
        decodedWidth: Int,
        decodedHeight: Int,
        outputSize: Int,
    ): AvatarAffine = AvatarAffine.scaleTranslate(
        scaleX = region.width.toFloat() / decodedWidth,
        scaleY = region.height.toFloat() / decodedHeight,
        translateX = region.left.toFloat(),
        translateY = region.top.toFloat(),
    ).then(transform(state, outputSize.toFloat(), outputSize / 2f, outputSize / 2f))

    companion object {
        /** 输出头像边长，JPEG 方图。 */
        const val OUTPUT_SIZE = 512
        const val OUTPUT_JPEG_QUALITY = 95
        const val MAX_ZOOM = 5f
        const val DOUBLE_TAP_ZOOM = 2f
        const val ZOOM_STEP = 1.25f
        private const val ZOOM_EPSILON = 0.01f
        private const val PIXEL_EPSILON = 0.001f

        /** 缩放条位置 0..1 与缩放倍数按对数对应，低倍段更细。 */
        fun zoomToSlider(zoom: Float): Float = (ln(zoom.coerceIn(1f, MAX_ZOOM)) / ln(MAX_ZOOM)).coerceIn(0f, 1f)

        fun sliderToZoom(position: Float): Float = MAX_ZOOM.pow(position.coerceIn(0f, 1f))

        fun zoomPercent(zoom: Float): Int = (zoom * 100f).roundToInt()
    }
}

/** 解码时的 inSampleSize 选择：只取 2 的幂，与 BitmapFactory 的取整一致。 */
internal object AvatarSampling {
    /** 取景显示用：长边不超过 [maxLongSide] 的最小采样倍数。 */
    fun forDisplay(width: Int, height: Int, maxLongSide: Int = DISPLAY_MAX_LONG_SIDE): Int {
        var sample = 1
        while (max(width, height) / sample > maxLongSide) sample *= 2
        return sample
    }

    /** 输出用：缩小后短边仍不小于 [targetSide] 的最大采样倍数。 */
    fun forRegion(width: Int, height: Int, targetSide: Int = AvatarCropGeometry.OUTPUT_SIZE): Int {
        var sample = 1
        while (min(width, height) / (sample * 2) >= targetSide) sample *= 2
        return sample
    }

    const val DISPLAY_MAX_LONG_SIDE = 2048
}

private fun Float.finiteOrZero(): Float = if (isFinite()) this else 0f

/** 夹紧到 0 时可能得到 -0f，统一成 0f，避免状态比较与保存时出现两种零。 */
private fun Float.withoutNegativeZero(): Float = this + 0f
