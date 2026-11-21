package com.promenar.infinity.data.production

import com.google.common.truth.Truth.assertThat
import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.assertThrows
import org.junit.Test

class PrevisVideoEncoderTest {
    @Test
    fun `帧数与恒定帧率时间戳计算准确无漂移`() {
        assertThat(previsFrameCount(0.0, 24)).isEqualTo(1)
        assertThat(previsFrameCount(-1.0, 24)).isEqualTo(1)
        assertThat(previsFrameCount(4.5, 24)).isEqualTo(108)
        assertThat(previsFrameCount(20.5, 24)).isEqualTo(492)

        assertThat(previsFrameTimestampUs(0, 24)).isEqualTo(0L)
        assertThat(previsFrameTimestampUs(24, 24)).isEqualTo(1_000_000L)
        assertThat(previsFrameTimestampUs(480, 24)).isEqualTo(20_000_000L)
    }

    @Test
    fun `ARGB 转 YUV420 在带行对齐与半交错平面下色彩及偏移精确`() {
        val w = 4
        val h = 2
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val red = 0xFFFF0000.toInt()
        // 左侧 2x2 纯黑，右侧 2x2 上红下白
        val argb = intArrayOf(
            black, black, red, red,
            black, black, white, white,
        )

        // 模拟硬件编码器常见的带行距 I420 + 交错 NV12 pixelStride=2
        val yRowStride = 8
        val uRowStride = 6
        val vRowStride = 6
        val yBuf = ByteBuffer.allocate(yRowStride * h)
        val uvBacking = ByteArray(uRowStride * (h / 2) + 2) { 0x7F }
        val uBuf = ByteBuffer.wrap(uvBacking, 0, uvBacking.size).slice()
        val vBuf = ByteBuffer.wrap(uvBacking, 1, uvBacking.size - 1).slice()

        argbToYuv420Planes(
            argb = argb,
            width = w,
            height = h,
            yBuffer = yBuf,
            yRowStride = yRowStride,
            yPixelStride = 1,
            uBuffer = uBuf,
            uRowStride = uRowStride,
            uPixelStride = 2,
            vBuffer = vBuf,
            vRowStride = vRowStride,
            vPixelStride = 2,
        )

        // 左侧黑块 Y=16, U=128, V=128
        assertThat(yBuf.get(0).toInt() and 0xFF).isEqualTo(16)
        assertThat(yBuf.get(yRowStride + 1).toInt() and 0xFF).isEqualTo(16)
        // 右上纯红 Y=82, 右下纯白 Y=235
        assertThat(yBuf.get(2).toInt() and 0xFF).isEqualTo(82)
        assertThat(yBuf.get(yRowStride + 2).toInt() and 0xFF).isEqualTo(235)
        // 行末尾 padding 保持 0，未越界写入
        assertThat(yBuf.get(4).toInt()).isEqualTo(0)

        // NV12 交错平面的左侧 2x2 黑块 U=128, V=128
        assertThat(uvBacking[0].toInt() and 0xFF).isEqualTo(128)
        assertThat(uvBacking[1].toInt() and 0xFF).isEqualTo(128)
        // 右侧红白均值 R=255, G=128, B=128 -> 应偏向红色色度 Cr > 140, Cb < 128
        val rightCb = uvBacking[2].toInt() and 0xFF
        val rightCr = uvBacking[3].toInt() and 0xFF
        assertThat(rightCr).isGreaterThan(160)
        assertThat(rightCb).isLessThan(128)
    }

    @Test
    fun `odd dimensions throw clear validation error`() {
        val buf = ByteBuffer.allocate(32)
        assertThrows(IllegalArgumentException::class.java) {
            argbToYuv420Planes(IntArray(9), 3, 3, buf, 3, 1, buf, 2, 1, buf, 2, 1)
        }
    }

    @Test
    fun `decodePrevisDataUrl 解析 WebView JSON 返回值并忽略空串`() {
        val raw = byteArrayOf(1, 2, 3, 4, 42)
        val b64 = Base64.getEncoder().encodeToString(raw)
        val jsQuoted = "\"data:image\\/png;base64,$b64\""

        assertThat(decodePrevisDataUrl(jsQuoted)).isEqualTo(raw)
        assertThat(decodePrevisDataUrl("null")).isNull()
        assertThat(decodePrevisDataUrl("\"\"")).isNull()
        assertThat(decodePrevisDataUrl(null)).isNull()
    }
}
