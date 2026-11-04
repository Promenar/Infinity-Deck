package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.*
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ImageGenerationInputReaderTest {
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10) + ByteArray(24)
    private class Resolver : WorkspaceMediaResolver {
        var resolution: MediaResolution = MediaResolution.Unavailable("/outside", MediaUnavailableReason.INVALID_PATH)
        var bytes = byteArrayOf()
        var opens = 0
        var stream: (() -> InputStream)? = null
        override suspend fun resolve(sessionId: String, path: String) = resolution
        override suspend fun open(resolution: MediaResolution.Available): InputStream {
            opens++
            return stream?.invoke() ?: ByteArrayInputStream(bytes)
        }
        fun available(size: Long, kind: MediaKind = MediaKind.IMAGE, mime: String = "image/png", width: Int = 3) {
            resolution = MediaResolution.Available(MediaRef("/media/input.png", mime, kind, size, width = width, height = 2), MediaSource.LocalFile(File("unused")))
        }
    }

    @Test fun `标注必须为PNG且尺寸与第一张输入相同`() = runTest {
        val resolver = object : WorkspaceMediaResolver {
            var jpeg = true
            var width = 4
            override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Available(
                MediaRef(path, "image/png", MediaKind.IMAGE, 32, width = if (path == "/a.png") 3 else width, height = 2), MediaSource.LocalFile(File("unused")))
            override suspend fun open(resolution: MediaResolution.Available) = ByteArrayInputStream(
                if (resolution.ref.path == "/mark.png" && jpeg) byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()) else png)
        }
        assertThat(runCatching { ImageGenerationInputReader(resolver).read("s", listOf("/a.png"), null, "/mark.png") }.exceptionOrNull()?.message).contains("PNG")
        resolver.jpeg = false
        assertThat(runCatching { ImageGenerationInputReader(resolver).read("s", listOf("/a.png"), null, "/mark.png") }.exceptionOrNull()?.message).contains("尺寸")
        resolver.width = 3
        val loaded = ImageGenerationInputReader(resolver).read("s", listOf("/a.png"), null, "/mark.png")
        assertThat(loaded.markup?.fileName).isEqualTo("markup.png")
        assertThat(loaded.markupPath).isEqualTo("/mark.png")
    }

    @Test fun `越界来源在打开字节前被拒绝`() = runTest {
        val resolver = Resolver()
        val error = runCatching { ImageGenerationInputReader(resolver).read("session", listOf("../../private.png"), null) }.exceptionOrNull()
        assertThat(error?.message).contains("工作区")
        assertThat(resolver.opens).isEqualTo(0)
    }

    @Test fun `内容决定上传MIME且返回解析后的工作区路径`() = runTest {
        val resolver = Resolver().apply { available(4, mime = "image/png"); bytes = byteArrayOf(255.toByte(), 216.toByte(), 255.toByte(), 0) }
        val loaded = ImageGenerationInputReader(resolver).read("session", listOf("relative.png"), null)
        assertThat(loaded.paths).containsExactly("/media/input.png")
        assertThat(loaded.images.single().mimeType).isEqualTo("image/jpeg")
        assertThat(loaded.images.single().fileName).endsWith(".jpg")
        assertThat(loaded.images.single().bytes.toList()).containsExactly(255.toByte(), 216.toByte(), 255.toByte(), 0.toByte()).inOrder()
    }

    @Test fun `非图片伪装图片及不支持格式均拒绝`() = runTest {
        val resolver = Resolver()
        for ((kind, bytes) in listOf(MediaKind.FILE to png, MediaKind.IMAGE to "text".toByteArray(), MediaKind.IMAGE to "GIF89a".toByteArray())) {
            resolver.available(bytes.size.toLong(), kind)
            resolver.bytes = bytes
            assertThat(runCatching { ImageGenerationInputReader(resolver).read("s", listOf("/input.png"), null) }.exceptionOrNull()?.message).contains("PNG/WebP/JPEG")
        }
    }

    @Test fun `图片与蒙版达到大小上限即拒绝并检查实际读取量`() = runTest {
        val resolver = Resolver().apply { available(50L * 1024 * 1024); bytes = png }
        assertThat(runCatching { ImageGenerationInputReader(resolver).read("s", listOf("/a.png"), null) }.exceptionOrNull()?.message).contains("50 MiB")
        assertThat(resolver.opens).isEqualTo(0)
        resolver.available(png.size.toLong())
        resolver.stream = { object : InputStream() {
            private var offset = 0
            override fun read(): Int = if (offset < 4 * 1024 * 1024) { offset++; 0 } else -1
            override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                val count = minOf(len, 4 * 1024 * 1024 - offset)
                if (count <= 0) return -1
                for (i in 0 until count) buffer[off + i] = if (offset + i < png.size) png[offset + i] else 0
                offset += count
                return count
            }
        } }
        assertThat(runCatching { ImageGenerationInputReader(resolver).read("s", listOf("/a.png"), "/mask.png") }.exceptionOrNull()?.message).contains("4 MiB")
    }

    @Test fun `蒙版必须为PNG且尺寸与第一张输入相同`() = runTest {
        val resolver = object : WorkspaceMediaResolver {
            var jpegMask = true
            override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Available(
                MediaRef(path, "image/png", MediaKind.IMAGE, 32, width = if (path == "/a.png") 3 else 4, height = 2), MediaSource.LocalFile(File("unused")))
            override suspend fun open(resolution: MediaResolution.Available) = ByteArrayInputStream(
                if (resolution.ref.path == "/mask.png" && jpegMask) byteArrayOf(255.toByte(), 216.toByte(), 255.toByte()) else png)
        }
        assertThat(runCatching { ImageGenerationInputReader(resolver).read("s", listOf("/a.png"), "/mask.png") }.exceptionOrNull()?.message).contains("PNG")
        resolver.jpegMask = false
        assertThat(runCatching { ImageGenerationInputReader(resolver).read("s", listOf("/a.png"), "/mask.png") }.exceptionOrNull()?.message).contains("尺寸")
    }

    @Test fun `读取取消保持原始异常`() = runTest {
        val cancellation = CancellationException("cancelled")
        val resolver = Resolver().apply { available(png.size.toLong()); stream = { throw cancellation } }
        // 协程调试模式跨 withContext 会复制异常，只断言取消原样向上传递而非被改写为参数错误。
        val failure = runCatching { ImageGenerationInputReader(resolver).read("s", listOf("/a.png"), null) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(CancellationException::class.java)
        assertThat(failure).hasMessageThat().isEqualTo(cancellation.message)
    }

    @Test fun `输出MIME支持三种格式且按响应请求魔数顺序选择`() {
        val jpeg = byteArrayOf(255.toByte(), 216.toByte(), 255.toByte())
        val webp = "RIFF0000WEBP".toByteArray()
        assertThat(imageGenerationOutputMime("webp", "png", jpeg)).isEqualTo("image/webp")
        assertThat(imageGenerationOutputMime(null, "jpeg", png)).isEqualTo("image/jpeg")
        assertThat(imageGenerationOutputMime(null, null, png)).isEqualTo("image/png")
        assertThat(imageGenerationOutputMime(null, null, jpeg)).isEqualTo("image/jpeg")
        assertThat(imageGenerationOutputMime(null, null, webp)).isEqualTo("image/webp")
        assertThat(imageGenerationOutputMime(null, null, "text".toByteArray())).isNull()
    }
}
