package com.promenar.infinity.ui.browser

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.browser.BROWSER_UNTRUSTED_NOTICE
import com.promenar.infinity.data.generation.*
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.ModelCapabilities
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.domain.media.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class BrowserScreenshotMediaContextTest {
    private val png = byteArrayOf(1, 2, 3)
    private val ref = MediaRef("/media/example.png", "image/png", MediaKind.IMAGE, 3, sha256 = "example-hash")
    private var available = true
    private val resolver = object : WorkspaceMediaResolver {
        override suspend fun resolve(sessionId: String, path: String): MediaResolution =
            if (available) MediaResolution.Available(ref, MediaSource.LocalFile(File("unused")))
            else MediaResolution.Unavailable(path, MediaUnavailableReason.NOT_FOUND)
        override suspend fun open(resolution: MediaResolution.Available) = ByteArrayInputStream(png)
    }
    private val viewer = WorkspaceMediaViewer(resolver, MediaViewPreprocessor { source, mode, _, expectedHash ->
        assertThat(expectedHash == null || expectedHash == ref.sha256).isTrue()
        PreparedViewedMedia(ViewedMedia(source.ref, mode), listOf(EncodedViewedMedia(MediaKind.IMAGE, "image/png", png)))
    })
    private val store = object : WorkspaceMediaStore {
        override suspend fun save(sessionId: String, payload: MediaPayload, origin: MediaOrigin, mimeTypeHint: String?, suggestedName: String?, targetPath: String?): MediaRef {
            assertThat(sessionId).isEqualTo("chat")
            assertThat((payload as MediaPayload.Bytes).bytes.toList()).containsExactlyElementsIn(png.toList()).inOrder()
            assertThat(mimeTypeHint).isEqualTo("image/png")
            assertThat(targetPath).isNull()
            return ref
        }
    }

    @Test fun `截图保存到工作区并在当前轮工具消息注入图片`() = runBlocking {
        val target = MediaViewTarget(ModelCapabilities(vision = true))
        val result = WorkspaceBrowserScreenshotPublisher(store, viewer) { target }.publish("chat", png)
        assertThat(result.content).contains(BROWSER_UNTRUSTED_NOTICE)
        assertThat(result.media).containsExactly(ref)
        assertThat(result.viewedMedia).hasSize(1)
        val message = Message("tool", MessageRole.TOOL, result.content, toolCallId = "capture", name = "browser_screenshot", viewedMedia = result.viewedMedia)
        val session = Session("chat", "agent", messages = listOf(message))
        val protocol = listOf(ProtocolMessage("user", "检查页面"), ProtocolMessage("tool", result.content, name = "browser_screenshot", toolCallId = "capture"))
        val loader = ViewedMediaContextLoader(viewer) { _, _ -> target }
        val attached = loader.attach(session, protocol, null)
        assertThat(attached.last().imageUrls).hasSize(1)
        assertThat(attached.last().mediaPaths).containsExactly(ref.path)
        val historical = loader.attach(session, attached + ProtocolMessage("user", "下一轮"), null)
        assertThat(historical[1].imageUrls).isNull()
        available = false
        val deleted = loader.attach(session, protocol, null).last()
        assertThat(deleted.imageUrls).isNull()
        assertThat(deleted.content).contains("文件已删除或不可读")
    }

    @Test fun `模型不支持图片时保存结果明确说明图片未发送`() = runBlocking {
        val result = WorkspaceBrowserScreenshotPublisher(store, viewer) { MediaViewTarget() }.publish("chat", png)
        assertThat(result.content).contains(ref.path)
        assertThat(result.content).contains("不支持")
        assertThat(result.media).containsExactly(ref)
        assertThat(result.viewedMedia).isNull()
    }
}
