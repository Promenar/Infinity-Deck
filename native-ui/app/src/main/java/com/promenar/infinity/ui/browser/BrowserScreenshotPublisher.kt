package com.promenar.infinity.ui.browser

import com.promenar.infinity.data.browser.BROWSER_UNTRUSTED_NOTICE
import com.promenar.infinity.data.generation.MediaViewRequest
import com.promenar.infinity.data.generation.MediaViewTarget
import com.promenar.infinity.data.generation.WorkspaceMediaViewer
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.media.MediaOrigin
import com.promenar.infinity.domain.media.MediaPayload
import com.promenar.infinity.domain.media.WorkspaceMediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

fun interface BrowserScreenshotPublisher {
    suspend fun publish(sessionId: String, png: ByteArray): ToolResult
}

/** 使用现有媒体存储与 view_media 链路，保存路径和当前轮图片身份一并返回。 */
class WorkspaceBrowserScreenshotPublisher(
    private val store: WorkspaceMediaStore,
    private val viewer: WorkspaceMediaViewer,
    private val target: suspend (String) -> MediaViewTarget,
) : BrowserScreenshotPublisher {
    override suspend fun publish(sessionId: String, png: ByteArray): ToolResult = withContext(Dispatchers.IO) {
        val ref = store.save(sessionId, MediaPayload.Bytes(png), MediaOrigin.FRAME, "image/png", "browser.png")
        val viewed = viewer.view(sessionId, MediaViewRequest(listOf(ref.path)), target(sessionId))
        viewed.copy(id = "browser_screenshot", status = "success", media = listOf(ref), content = "$BROWSER_UNTRUSTED_NOTICE\nPNG: ${ref.path}\n${viewed.content}")
    }
}
