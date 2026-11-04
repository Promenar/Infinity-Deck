package com.promenar.infinity.data.media.annotation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.*
import java.io.File
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageAnnotationStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aX1sAAAAASUVORK5CYII=")
    private class Media(private val bytes: ByteArray) : WorkspaceMediaStore, WorkspaceMediaResolver {
        var writable = true
        val saved = mutableListOf<ByteArray>()
        override suspend fun canSave(sessionId: String) = writable
        override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef {
            saved += (payload as MediaPayload.Bytes).bytes
            return MediaRef("/media/annot-${saved.size}.png", "image/png", MediaKind.IMAGE, bytes.size.toLong(), width = 1, height = 1)
        }
        override suspend fun resolve(sessionId: String, path: String) = MediaResolution.Available(
            MediaRef(path, "image/png", MediaKind.IMAGE, bytes.size.toLong(), width = 1, height = 1), MediaSource.LocalFile(File("unused")))
        override suspend fun open(resolution: MediaResolution.Available) = bytes.inputStream()
    }
    private fun export(source: ImageAnnotationSource = ImageAnnotationSource.Bytes(png), kind: ImageAnnotationKind = ImageAnnotationKind.MARKUP) =
        ImageAnnotationExport(kind, 1, 1, png, png, source, "笔画")

    @Test fun `原图与图层入库后预览携带工作区路径且按会话隔离`() = runTest {
        val media = Media(png)
        val store = ImageAnnotationStore(media, media, temporary.root)
        val context = ImageAnnotationContext("session", "workspace", false)
        val file = store.save("session", export(), context)
        val annotation = ImageAnnotationPreviewCodec.read(file.readBytes())
        assertThat(annotation?.sourcePath).isEqualTo("/media/annot-1.png")
        assertThat(annotation?.layerPath).isEqualTo("/media/annot-2.png")
        assertThat(annotation?.editorState).isEqualTo("笔画")
        assertThat(file.inputStream().use { ImageAnnotationPreviewCodec.readPreview(it) }?.context).isEqualTo(context)
        assertThat(media.saved).hasSize(2)
        assertThat(store.save("other", export()).parentFile).isNotEqualTo(file.parentFile)
        assertThat(store.readSource("session", "/source.png").toList()).isEqualTo(png.toList())
    }

    @Test fun `已有工作区原图与草图只写图层且不可用时拒绝保存`() = runTest {
        val media = Media(png)
        val store = ImageAnnotationStore(media, media, temporary.root)
        val existing = store.save("s", export(ImageAnnotationSource.Workspace("/original.png")))
        assertThat(ImageAnnotationPreviewCodec.read(existing.readBytes())?.sourcePath).isEqualTo("/original.png")
        val sketch = store.save("s", export(ImageAnnotationSource.None, ImageAnnotationKind.SKETCH))
        assertThat(ImageAnnotationPreviewCodec.read(sketch.readBytes())?.sourcePath).isNull()
        assertThat(media.saved).hasSize(2)
        media.writable = false
        assertThat(runCatching { store.save("s", export()) }.exceptionOrNull()?.message).contains("工作区")
        assertThat(media.saved).hasSize(2)
    }

    @Test fun `过期清理保护仍引用文件且不触碰其它会话`() = runTest {
        val media = Media(png)
        val store = ImageAnnotationStore(media, media, temporary.root)
        val previous = ImageAnnotationStore(media, media, temporary.root, "previous-process")
        val protected = previous.save("s", export())
        val expired = previous.save("s", export())
        val other = previous.save("other", export())
        val fresh = store.save("s", export())
        listOf(protected, expired, other).forEach { assertThat(it.setLastModified(1)).isTrue() }
        store.cleanup("s", setOf(protected.absolutePath))
        assertThat(protected.exists()).isTrue()
        assertThat(expired.exists()).isFalse()
        assertThat(other.exists()).isTrue()
        assertThat(fresh.exists()).isTrue()
    }

    @Test fun `无原图标注与尺寸错误在写入前拒绝`() = runTest {
        val media = Media(png)
        val store = ImageAnnotationStore(media, media, temporary.root)
        assertThat(runCatching { store.save("s", export(ImageAnnotationSource.None)) }.exceptionOrNull()?.message).contains("原图")
        val wrong = ImageAnnotationExport(ImageAnnotationKind.SKETCH, 2, 1, png, png, ImageAnnotationSource.None, null)
        assertThat(runCatching { store.save("s", wrong) }.exceptionOrNull()?.message).contains("尺寸")
        assertThat(media.saved).isEmpty()
    }
}
