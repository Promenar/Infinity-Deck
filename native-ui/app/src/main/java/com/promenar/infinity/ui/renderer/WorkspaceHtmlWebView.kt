package com.promenar.infinity.ui.renderer

import android.webkit.WebView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.ui.common.LocalLabSandbox
import com.promenar.infinity.ui.media.LocalWorkspaceMedia
import java.io.File

/** 保留通用富内容的安全门禁与测高逻辑，只为会话 HTML 卡片附加主工作区媒体来源。 */
@Composable
internal fun WorkspaceHtmlWebView(
    html: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    minHeight: Int = 40,
    maxHeight: Int = 600,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    val environment = LocalWorkspaceMedia.current
    val sandboxed = LocalLabSandbox.current
    val application = (LocalContext.current.applicationContext as? InfinityApplication)?.takeUnless { sandboxed }
    val source = remember(environment?.sessionId, application) {
        if (environment == null || application == null) null else {
            HtmlWorkspaceMediaSource({ application.htmlMediaRoot(environment.sessionId) })
        }
    }
    // 会话切换时释放旧客户端；池归还流程清除全部绑定，不能复用上个会话的读取能力。
    val preparedHtml = remember(html) { ensureHtmlArtifactDocument(html) }
    key(source) {
        RichContentWebView(preparedHtml, modifier, fontSize, minHeight, maxHeight, onWebViewCreated = { view ->
            (view.webViewClient as? RichContentWebViewClient)?.workspaceMediaSource = source
            onWebViewCreated?.invoke(view)
        })
    }
}

/**
 * 将独立 HTML 片段（如 `<video>`、`<audio>`、`<img>`、`<div>`）包装为完整 HTML5 文档，
 * 避免被通用安全容器当成纯文本转义显示。
 */
internal fun ensureHtmlArtifactDocument(html: String): String {
    val trimmed = html.trim()
    if (
        trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
        trimmed.contains("<html", ignoreCase = true) ||
        trimmed.contains("<body", ignoreCase = true)
    ) {
        return html
    }
    return """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                html, body { margin: 0; padding: 6px; box-sizing: border-box; height: auto !important; overflow: hidden; }
                img, video, svg, canvas { max-width: 100%; height: auto; display: block; border-radius: 8px; }
                video { width: 100%; min-height: 180px; background: #000; }
                audio { width: 100%; display: block; }
            </style>
        </head>
        <body>$html</body>
        </html>
    """.trimIndent()
}

private suspend fun InfinityApplication.htmlMediaRoot(sessionId: String): File? = when (val workspace = sessionWorkspaceResolver.resolve(sessionId)) {
    is SessionWorkspace.Directory -> workspace.root
    SessionWorkspace.Private -> {
        val rootId = sessionRepository.getById(sessionId)?.workspaceRootUuid
        rootId?.let { workspaceRepository.getByUuid(it, it)?.physicalRootPath?.takeIf(String::isNotBlank)?.let(::File) }
    }
    is SessionWorkspace.Unavailable -> null
}
