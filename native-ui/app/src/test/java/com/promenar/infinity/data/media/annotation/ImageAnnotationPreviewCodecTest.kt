package com.promenar.infinity.data.media.annotation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.ImageAnnotation
import com.promenar.infinity.domain.media.ImageAnnotationKind
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import org.junit.Test

class ImageAnnotationPreviewCodecTest {
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=")
    private val annotation = ImageAnnotation(ImageAnnotationKind.MARKUP, 1, 1, "/图层.png", "/原图.png", "笔画")

    @Test fun `元数据往返且图像块逐字节保留`() {
        val encoded = ImageAnnotationPreviewCodec.embed(png, annotation)
        assertThat(ImageAnnotationPreviewCodec.read(encoded)).isEqualTo(annotation)
        assertThat(ImageAnnotationPreviewCodec.strip(encoded).toList()).isEqualTo(png.toList())
        assertThat(String(encoded, 37, 4, Charsets.US_ASCII)).isEqualTo("inAn")
        val length = ByteBuffer.wrap(encoded, 33, 4).int
        val crc = CRC32().apply { update(encoded, 37, length + 4) }.value
        assertThat(ByteBuffer.wrap(encoded, 41 + length, 4).int.toLong() and 0xffffffffL).isEqualTo(crc)
    }

    @Test fun `替换已有块后只保留新标注`() {
        val updated = annotation.copy(kind = ImageAnnotationKind.SKETCH, sourcePath = null)
        val encoded = ImageAnnotationPreviewCodec.embed(ImageAnnotationPreviewCodec.embed(png, annotation), updated)
        assertThat(ImageAnnotationPreviewCodec.read(encoded)).isEqualTo(updated)
        assertThat(ImageAnnotationPreviewCodec.strip(encoded).toList()).isEqualTo(png.toList())
    }

    @Test fun `非PNG截断CRC损坏与无法解析的JSON均返回空`() {
        assertThat(ImageAnnotationPreviewCodec.read("jpeg".toByteArray())).isNull()
        assertThat(ImageAnnotationPreviewCodec.read(png)).isNull()
        val encoded = ImageAnnotationPreviewCodec.embed(png, annotation)
        for (size in 0 until encoded.size) assertThat(ImageAnnotationPreviewCodec.read(encoded.copyOf(size))).isNull()
        val damaged = encoded.copyOf().apply { this[41] = 0 }
        assertThat(ImageAnnotationPreviewCodec.read(damaged)).isNull()
        val length = ByteBuffer.wrap(encoded, 33, 4).int
        val badJson = encoded.copyOf().apply {
            this[41] = '!'.code.toByte()
            val checksum = CRC32()
            checksum.update(this, 37, length + 4)
            val crc = checksum.value
            ByteBuffer.wrap(this, 41 + length, 4).putInt(crc.toInt())
        }
        assertThat(ImageAnnotationPreviewCodec.read(badJson)).isNull()
    }
}
