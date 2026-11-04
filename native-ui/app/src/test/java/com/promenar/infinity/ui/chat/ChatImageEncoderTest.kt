package com.promenar.infinity.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChatImageEncoderTest {

    @Test fun `循环队列遇到损坏元数据仍剥离私有块`() = kotlinx.coroutines.test.runTest {
        val annotation = com.promenar.infinity.domain.media.ImageAnnotation(
            com.promenar.infinity.domain.media.ImageAnnotationKind.SKETCH, 320, 200, "/sketch.png", editorState = "本地笔画")
        val png = encodedImage(320, 200, Bitmap.CompressFormat.PNG)
        val encoded = com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.embed(png, annotation)
        encoded[41] = '!'.code.toByte()
        val file = java.io.File.createTempFile("annotation-bad-queue", ".image")
        try {
            for (repairCrc in listOf(false, true)) {
                if (repairCrc) {
                    val length = java.nio.ByteBuffer.wrap(encoded, 33, 4).int
                    val crc = java.util.zip.CRC32().apply { update(encoded, 37, length + 4) }.value
                    java.nio.ByteBuffer.wrap(encoded, 41 + length, 4).putInt(crc.toInt())
                }
                file.writeBytes(encoded)
                val loaded = com.promenar.infinity.data.generation.QueuedUserImage.read(file.path)
                assertThat(loaded.annotation).isNull()
                val bytes = Base64.decode(loaded.dataUrl.substringAfter("base64,"), Base64.NO_WRAP)
                assertThat(bytes.toList()).isEqualTo(png.toList())
            }
        } finally { file.delete() }
    }

    @Test fun `循环队列拒绝跨工作区画板副本`() = kotlinx.coroutines.test.runTest {
        val source = com.promenar.infinity.data.model.Session("s", "a", workspaceId = "original")
        val annotation = com.promenar.infinity.domain.media.ImageAnnotation(
            com.promenar.infinity.domain.media.ImageAnnotationKind.SKETCH, 320, 200, "/sketch.png")
        val file = java.io.File.createTempFile("annotation-context-queue", ".image")
        try {
            file.writeBytes(com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.embed(
                encodedImage(320, 200, Bitmap.CompressFormat.PNG), annotation,
                com.promenar.infinity.data.media.annotation.ImageAnnotationContext.from(source)))
            val error = runCatching { com.promenar.infinity.data.generation.QueuedUserImage.read(file.path, source.copy(workspaceId = "other")) }.exceptionOrNull()
            assertThat(error?.message).contains("工作区已变化")
        } finally { file.delete() }
    }

    @Test fun `循环内队列画板预览保留元数据并仅内联JPEG`() = kotlinx.coroutines.test.runTest {
        val annotation = com.promenar.infinity.domain.media.ImageAnnotation(
            com.promenar.infinity.domain.media.ImageAnnotationKind.SKETCH, 320, 200, "/sketch.png")
        val session = com.promenar.infinity.data.model.Session("s", "a")
        val file = java.io.File.createTempFile("annotation-queue", ".image")
        try {
            file.writeBytes(com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.embed(
                encodedImage(320, 200, Bitmap.CompressFormat.PNG), annotation,
                com.promenar.infinity.data.media.annotation.ImageAnnotationContext.from(session)))
            val loaded = com.promenar.infinity.data.generation.QueuedUserImage.read(file.path, session)
            assertThat(loaded.annotation).isEqualTo(annotation)
            assertThat(loaded.dataUrl).startsWith("data:image/jpeg;base64,")
            assertThat(String(Base64.decode(loaded.dataUrl.substringAfter("base64,"), Base64.NO_WRAP), Charsets.ISO_8859_1)).doesNotContain("inAn")
        } finally { file.delete() }
    }

    @Test fun `画板预览编码给模型后不携带PNG私有块`() {
        val annotation = com.promenar.infinity.domain.media.ImageAnnotation(
            com.promenar.infinity.domain.media.ImageAnnotationKind.MARKUP, 320, 200, "/layer.png", "/source.png", "私有笔画")
        val preview = com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.embed(
            encodedImage(320, 200, Bitmap.CompressFormat.PNG), annotation)
        val dataUrl = requireNotNull(ChatImageEncoder.encodeToDataUrl { ByteArrayInputStream(preview) })
        val message = com.promenar.infinity.data.model.Message("u", com.promenar.infinity.data.model.MessageRole.USER, "改图",
            userImages = listOf(dataUrl), userImageAnnotations = listOf(annotation))
        val image = requireNotNull(com.promenar.infinity.data.model.UserAttachmentModelContent.format(message, null, true).imageUrls).single()
        assertThat(image.mimeType).isEqualTo("image/jpeg")
        val bytes = Base64.decode(image.base64, Base64.NO_WRAP)
        assertThat(com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec.read(bytes)).isNull()
        assertThat(String(bytes, Charsets.ISO_8859_1)).doesNotContain("inAn")
    }

    @Test
    fun `PNG 截图可编码为 JPEG data URL`() {
        val png = encodedImage(320, 200, Bitmap.CompressFormat.PNG)

        val dataUrl = ChatImageEncoder.encodeToDataUrl { ByteArrayInputStream(png) }

        assertThat(dataUrl).startsWith("data:image/jpeg;base64,")
        val decoded = decodeDataUrl(requireNotNull(dataUrl))
        assertThat(decoded.width).isEqualTo(320)
        assertThat(decoded.height).isEqualTo(200)
    }

    @Test
    fun `超大图片按长边降采样`() {
        val jpeg = encodedImage(4000, 1000, Bitmap.CompressFormat.JPEG)

        val dataUrl = ChatImageEncoder.encodeToDataUrl { ByteArrayInputStream(jpeg) }

        val decoded = decodeDataUrl(requireNotNull(dataUrl))
        assertThat(decoded.width).isEqualTo(2000)
        assertThat(decoded.height).isEqualTo(500)
    }

    @Test
    fun `非图片内容与无法打开的流返回 null`() {
        assertThat(ChatImageEncoder.encodeToDataUrl { ByteArrayInputStream("not an image".toByteArray()) }).isNull()
        assertThat(ChatImageEncoder.encodeToDataUrl { null }).isNull()
    }

    @Test
    fun `采样率保证解码长边不低于上限的一半`() {
        assertThat(ChatImageEncoder.sampleSizeFor(1000, 800)).isEqualTo(1)
        assertThat(ChatImageEncoder.sampleSizeFor(3136, 100)).isEqualTo(2)
        assertThat(ChatImageEncoder.sampleSizeFor(4000, 3000)).isEqualTo(2)
        assertThat(ChatImageEncoder.sampleSizeFor(12544, 100)).isEqualTo(8)
    }

    private fun encodedImage(width: Int, height: Int, format: Bitmap.CompressFormat): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(40, 120, 200))
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(format, 90, output)
            output.toByteArray()
        }
    }

    private fun decodeDataUrl(dataUrl: String): Bitmap {
        val bytes = Base64.decode(dataUrl.substringAfter("base64,"), Base64.NO_WRAP)
        return requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }
}
