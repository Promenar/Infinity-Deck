package com.promenar.infinity.ui.avatar

import com.google.common.truth.Truth.assertThat
import kotlin.math.sqrt
import org.junit.Assert.assertThrows
import org.junit.Test

class AvatarCropGeometryTest {

    private fun region(geometry: AvatarCropGeometry, state: AvatarCropState): List<Float> =
        geometry.sourceCropRegion(state).toList()

    private fun assertRegion(actual: List<Float>, vararg expected: Float) {
        assertThat(actual).hasSize(4)
        actual.zip(expected.toList()).forEach { (a, e) -> assertThat(a).isWithin(0.01f).of(e) }
    }

    private fun assertMaps(transform: AvatarAffine, x: Float, y: Float, expectedX: Float, expectedY: Float) {
        assertThat(transform.mapX(x, y)).isWithin(0.01f).of(expectedX)
        assertThat(transform.mapY(x, y)).isWithin(0.01f).of(expectedY)
    }

    // ─── 初始状态 ───

    @Test
    fun `横图初始状态以短边铺满并居中取景`() {
        val geometry = AvatarCropGeometry(1200, 800)
        val state = geometry.initialState()

        assertThat(state).isEqualTo(AvatarCropState())
        assertRegion(region(geometry, state), 200f, 0f, 1000f, 800f)
        assertThat(geometry.sourceCropBounds(state)).isEqualTo(AvatarPixelRect(200, 0, 1000, 800))
    }

    @Test
    fun `竖图初始状态以短边铺满并居中取景`() {
        val geometry = AvatarCropGeometry(800, 1200)

        assertRegion(region(geometry, geometry.initialState()), 0f, 200f, 800f, 1000f)
    }

    @Test
    fun `方图初始状态取整张图且不能平移`() {
        val geometry = AvatarCropGeometry(1000, 1000)
        val panned = geometry.pan(geometry.initialState(), 0.4f, -0.4f)

        assertRegion(region(geometry, geometry.initialState()), 0f, 0f, 1000f, 1000f)
        assertThat(panned.offsetX).isEqualTo(0f)
        assertThat(panned.offsetY).isEqualTo(0f)
    }

    @Test
    fun `尺寸无效的图片直接拒绝`() {
        assertThrows(IllegalArgumentException::class.java) { AvatarCropGeometry(0, 100) }
        assertThrows(IllegalArgumentException::class.java) { AvatarCropGeometry(100, -1) }
    }

    // ─── 缩放与平移夹紧 ───

    @Test
    fun `缩放限制在刚好铺满到五倍之间`() {
        val geometry = AvatarCropGeometry(1200, 800)
        val start = geometry.initialState()

        assertThat(geometry.zoomBy(start, 10f).zoom).isEqualTo(AvatarCropGeometry.MAX_ZOOM)
        assertThat(geometry.zoomBy(start, 0.2f).zoom).isEqualTo(1f)
        assertThat(geometry.withZoom(start, 0.5f).zoom).isEqualTo(1f)
        assertThat(geometry.clamp(start.copy(zoom = Float.NaN)).zoom).isEqualTo(1f)
        assertThat(geometry.zoomBy(start, Float.NaN)).isEqualTo(start)
    }

    @Test
    fun `平移到边缘后被吸附，不露出空白角`() {
        val geometry = AvatarCropGeometry(1200, 800)
        val pannedRight = geometry.pan(geometry.initialState(), 1f, 1f)

        // 横图显示宽度是 1.5 个裁剪边长，最多向一侧偏移 0.25。
        assertThat(pannedRight.offsetX).isWithin(1e-5f).of(0.25f)
        assertThat(pannedRight.offsetY).isEqualTo(0f)
        assertRegion(region(geometry, pannedRight), 0f, 0f, 800f, 800f)

        val pannedLeft = geometry.pan(geometry.initialState(), -1f, 0f)
        assertRegion(region(geometry, pannedLeft), 400f, 0f, 1200f, 800f)
    }

    @Test
    fun `放大后可平移范围随之扩大，缩小时偏移重新收回`() {
        val geometry = AvatarCropGeometry(1000, 1000)
        val zoomed = geometry.pan(geometry.withZoom(geometry.initialState(), 2f), 5f, -5f)

        assertThat(zoomed.offsetX).isWithin(1e-5f).of(0.5f)
        assertThat(zoomed.offsetY).isWithin(1e-5f).of(-0.5f)
        assertRegion(region(geometry, zoomed), 0f, 500f, 500f, 1000f)

        val zoomedOut = geometry.withZoom(zoomed, 1f)
        assertThat(zoomedOut.offsetX).isWithin(1e-5f).of(0f)
        assertThat(zoomedOut.offsetY).isWithin(1e-5f).of(0f)
    }

    @Test
    fun `五倍时裁剪区域是短边的五分之一`() {
        val geometry = AvatarCropGeometry(1000, 1000)
        val state = geometry.withZoom(geometry.initialState(), 5f)

        assertRegion(region(geometry, state), 400f, 400f, 600f, 600f)
    }

    @Test
    fun `以焦点缩放时焦点下的源像素保持不动`() {
        val geometry = AvatarCropGeometry(1000, 1000)
        val start = geometry.withZoom(geometry.initialState(), 2f)
        val focusX = 0.2f
        val focusY = 0.1f
        val before = geometry.transform(start, 1f, 0f, 0f).inverted()
        val after = geometry.transform(geometry.zoomBy(start, 1.5f, focusX, focusY), 1f, 0f, 0f).inverted()

        assertThat(after.mapX(focusX, focusY)).isWithin(0.01f).of(before.mapX(focusX, focusY))
        assertThat(after.mapY(focusX, focusY)).isWithin(0.01f).of(before.mapY(focusX, focusY))
    }

    @Test
    fun `双击在刚好铺满和放大之间切换并保留方向`() {
        val geometry = AvatarCropGeometry(1200, 800)
        val rotated = geometry.rotateClockwise(geometry.initialState())

        val zoomedIn = geometry.toggleZoom(rotated, 0.1f, 0.1f)
        assertThat(zoomedIn.zoom).isEqualTo(AvatarCropGeometry.DOUBLE_TAP_ZOOM)
        assertThat(zoomedIn.orientation).isEqualTo(rotated.orientation)

        val back = geometry.toggleZoom(zoomedIn)
        assertThat(back.zoom).isEqualTo(1f)
        assertThat(back.offsetX).isEqualTo(0f)
        assertThat(back.offsetY).isEqualTo(0f)
        assertThat(back.orientation).isEqualTo(rotated.orientation)
    }

    // ─── 旋转 ───

    @Test
    fun `旋转 90 度后横图按竖图约束平移`() {
        val geometry = AvatarCropGeometry(1200, 800)
        val rotated = geometry.rotateClockwise(geometry.initialState())
        val panned = geometry.pan(rotated, 1f, 1f)

        assertThat(geometry.displayedSize(rotated)).isEqualTo(800 to 1200)
        assertThat(panned.offsetX).isEqualTo(0f)
        assertThat(panned.offsetY).isWithin(1e-5f).of(0.25f)
        // 顺时针旋转后原图左侧转到上方，图片下移时取景落在原图左侧。
        assertRegion(region(geometry, panned), 0f, 0f, 800f, 800f)
    }

    @Test
    fun `旋转时取景内容随图片一起转动`() {
        val geometry = AvatarCropGeometry(1200, 800)
        var state = geometry.pan(geometry.initialState(), 1f, 0f)
        val expected = region(geometry, state)

        repeat(4) {
            state = geometry.rotateClockwise(state)
            assertRegion(region(geometry, state), *expected.toFloatArray())
        }
        assertThat(state.orientation).isEqualTo(AvatarOrientation.IDENTITY)
        assertThat(state.offsetX).isWithin(1e-5f).of(0.25f)
    }

    @Test
    fun `旋转 90 度后原图左上角出现在输出右上角`() {
        val geometry = AvatarCropGeometry(1000, 1000)
        val rotated = geometry.rotateClockwise(geometry.initialState())
        val output = geometry.transform(rotated, 512f, 256f, 256f)

        assertMaps(output, 0f, 0f, 512f, 0f)
        assertMaps(output, 1000f, 1000f, 0f, 512f)
    }

    // ─── 镜像 ───

    @Test
    fun `镜像后裁剪区域不变而左右翻转`() {
        val geometry = AvatarCropGeometry(1200, 800)
        val panned = geometry.pan(geometry.initialState(), 1f, 0f)
        val mirrored = geometry.mirrorHorizontally(panned)

        assertThat(mirrored.offsetX).isWithin(1e-5f).of(-0.25f)
        assertRegion(region(geometry, mirrored), 0f, 0f, 800f, 800f)
        assertMaps(geometry.transform(panned, 512f, 256f, 256f), 0f, 400f, 0f, 256f)
        assertMaps(geometry.transform(mirrored, 512f, 256f, 256f), 0f, 400f, 512f, 256f)
    }

    @Test
    fun `旋转后的镜像仍按屏幕水平方向翻转`() {
        val geometry = AvatarCropGeometry(1000, 1000)
        val state = geometry.mirrorHorizontally(geometry.rotateClockwise(geometry.initialState()))
        val output = geometry.transform(state, 512f, 256f, 256f)

        // 旋转后左上角在右上，再水平镜像回到左上；若在源图方向上镜像会落到右下。
        assertMaps(output, 0f, 0f, 0f, 0f)
        assertMaps(output, 1000f, 0f, 0f, 512f)
    }

    @Test
    fun `镜像两次回到原状态`() {
        val geometry = AvatarCropGeometry(1200, 800)
        val start = geometry.pan(geometry.initialState(), 0.1f, 0f)

        assertThat(geometry.mirrorHorizontally(geometry.mirrorHorizontally(start))).isEqualTo(start)
    }

    // ─── EXIF 方向 ───

    @Test
    fun `EXIF 八种方向对应正确的像素矩阵`() {
        val expected = mapOf(
            1 to listOf(1, 0, 0, 1),
            2 to listOf(-1, 0, 0, 1),
            3 to listOf(-1, 0, 0, -1),
            4 to listOf(1, 0, 0, -1),
            5 to listOf(0, 1, 1, 0),
            6 to listOf(0, -1, 1, 0),
            7 to listOf(0, -1, -1, 0),
            8 to listOf(0, 1, -1, 0),
        )
        expected.forEach { (exif, matrix) ->
            assertThat(AvatarOrientation.fromExif(exif).matrix().toList()).isEqualTo(matrix)
        }
        assertThat(AvatarOrientation.fromExif(0)).isEqualTo(AvatarOrientation.IDENTITY)
    }

    @Test
    fun `方向组合与矩阵乘法一致`() {
        val all = (0..3).flatMap { turns -> listOf(false, true).map { AvatarOrientation(turns, it) } }
        all.forEach { first ->
            all.forEach { second ->
                val (a1, b1, c1, d1) = first.matrix()
                val (a2, b2, c2, d2) = second.matrix()
                val product = listOf(
                    a1 * a2 + b1 * c2,
                    a1 * b2 + b1 * d2,
                    c1 * a2 + d1 * c2,
                    c1 * b2 + d1 * d2,
                )
                assertThat((first * second).matrix().toList()).isEqualTo(product)
            }
        }
    }

    @Test
    fun `EXIF 旋转 90 度的横向原图按竖图铺满`() {
        val geometry = AvatarCropGeometry(1200, 800, AvatarOrientation.fromExif(6))
        val start = geometry.initialState()
        val panned = geometry.pan(start, 1f, 1f)

        assertThat(geometry.displayedSize(start)).isEqualTo(800 to 1200)
        assertRegion(region(geometry, start), 200f, 0f, 1000f, 800f)
        assertThat(panned.offsetX).isEqualTo(0f)
        assertThat(panned.offsetY).isWithin(1e-5f).of(0.25f)
        // 原始像素左上角在正向显示时位于右上方。
        val output = geometry.transform(geometry.withZoom(start, 1f), 512f, 256f, 256f)
        assertThat(output.mapX(0f, 0f)).isWithin(0.01f).of(512f)
    }

    // ─── 输出 ───

    @Test
    fun `局部解码位图按采样倍数映射到输出图`() {
        val geometry = AvatarCropGeometry(1200, 800)
        val state = geometry.initialState()
        val bounds = geometry.sourceCropBounds(state)
        val transform = geometry.regionTransform(state, bounds, decodedWidth = 400, decodedHeight = 400, outputSize = 512)

        assertMaps(transform, 0f, 0f, 0f, 0f)
        assertMaps(transform, 400f, 400f, 512f, 512f)
    }

    @Test
    fun `裁剪区域外扩到整数像素并留在图片内`() {
        val geometry = AvatarCropGeometry(1001, 777)
        val state = geometry.pan(geometry.withZoom(geometry.initialState(), 3.3f), 0.37f, -0.21f)
        val bounds = geometry.sourceCropBounds(state)
        val exact = geometry.sourceCropRegion(state)

        assertThat(bounds.left.toFloat()).isAtMost(exact[0])
        assertThat(bounds.top.toFloat()).isAtMost(exact[1])
        assertThat(bounds.right.toFloat()).isAtLeast(exact[2])
        assertThat(bounds.bottom.toFloat()).isAtLeast(exact[3])
        assertThat(bounds.left).isAtLeast(0)
        assertThat(bounds.top).isAtLeast(0)
        assertThat(bounds.right).isAtMost(1001)
        assertThat(bounds.bottom).isAtMost(777)
    }

    @Test
    fun `仿射变换的逆与组合`() {
        val transform = AvatarAffine(0f, -2f, 2f, 0f, 10f, 20f)
        val roundTrip = transform.then(transform.inverted())

        assertMaps(roundTrip, 3f, 7f, 3f, 7f)
        assertThat(transform.toMatrixValues().toList())
            .isEqualTo(listOf(0f, -2f, 10f, 2f, 0f, 20f, 0f, 0f, 1f))
    }

    // ─── 缩放条与采样 ───

    @Test
    fun `缩放条按对数刻度与倍数互换`() {
        assertThat(AvatarCropGeometry.zoomToSlider(1f)).isEqualTo(0f)
        assertThat(AvatarCropGeometry.zoomToSlider(5f)).isWithin(1e-5f).of(1f)
        assertThat(AvatarCropGeometry.sliderToZoom(0.5f)).isWithin(1e-4f).of(sqrt(5f))
        assertThat(AvatarCropGeometry.zoomToSlider(AvatarCropGeometry.sliderToZoom(0.3f))).isWithin(1e-5f).of(0.3f)
        assertThat(AvatarCropGeometry.zoomPercent(sqrt(5f))).isEqualTo(224)
    }

    @Test
    fun `显示采样把长边压到上限以内`() {
        assertThat(AvatarSampling.forDisplay(2048, 1000)).isEqualTo(1)
        assertThat(AvatarSampling.forDisplay(4000, 3000)).isEqualTo(2)
        assertThat(AvatarSampling.forDisplay(3000, 8000)).isEqualTo(4)
    }

    @Test
    fun `输出采样保证短边不低于输出尺寸`() {
        assertThat(AvatarSampling.forRegion(800, 800)).isEqualTo(1)
        assertThat(AvatarSampling.forRegion(1024, 1024)).isEqualTo(2)
        assertThat(AvatarSampling.forRegion(4000, 3000)).isEqualTo(4)
        assertThat(AvatarSampling.forRegion(100, 100)).isEqualTo(1)
    }
}
