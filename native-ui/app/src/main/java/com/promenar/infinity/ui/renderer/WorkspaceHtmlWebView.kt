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
    key(source) {
        RichContentWebView(html, modifier, fontSize, minHeight, maxHeight, onWebViewCreated = { view ->
            (view.webViewClient as? RichContentWebViewClient)?.workspaceMediaSource = source
            onWebViewCreated?.invoke(view)
        })
    }
}

private suspend fun InfinityApplication.htmlMediaRoot(sessionId: String): File? = when (val workspace = sessionWorkspaceResolver.resolve(sessionId)) {
    is SessionWorkspace.Directory -> workspace.root
    SessionWorkspace.Private -> {
        val rootId = sessionRepository.getById(sessionId)?.workspaceRootUuid
        rootId?.let { workspaceRepository.getByUuid(it, it)?.physicalRootPath?.takeIf(String::isNotBlank)?.let(::File) }
    }
    is SessionWorkspace.Unavailable -> null
}
