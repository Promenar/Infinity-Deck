package com.promenar.infinity.ui.production

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.promenar.infinity.ui.renderer.RichContentRequestPolicy
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.URLConnection

/**
 * 仅用于 3D 白模预演视口的纯离线 WebView 客户端：
 * - 只允许 GET 请求 `https://appassets.androidplatform.net/assets/previs/...` 下随包分发的离线资源；
 * - 显式拦截全部公网 HTTPS、明文 HTTP、file://、content:// 与主文档跳出导航；
 * - 配合严格 CSP，确保渲染器在断网沙箱环境下运行且不产生任何外网或本地文件泄露。
 */
internal class PrevisWebViewClient(context: Context) : WebViewClient() {
    private val assets = context.applicationContext.assets

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        if (request.method != "GET") return denied()
        val url = request.url?.toString() ?: return denied()
        val path = RichContentRequestPolicy.assetPath(url) ?: return denied()
        if (!path.startsWith(PREVIS_ASSET_PREFIX)) return denied()
        if (request.isForMainFrame && path != PREVIS_PLAYER_ASSET) return denied()
        return localPrevisAsset(path)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse {
        val path = url?.let(RichContentRequestPolicy::assetPath) ?: return denied()
        if (!path.startsWith(PREVIS_ASSET_PREFIX)) return denied()
        return localPrevisAsset(path)
    }

    private fun localPrevisAsset(path: String): WebResourceResponse = try {
        val type = when (path.substringAfterLast('.')) {
            "js", "mjs" -> "application/javascript"
            "html" -> "text/html"
            "css" -> "text/css"
            else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
        }
        WebResourceResponse(type, "UTF-8", 200, "OK", securityHeaders, assets.open(path))
    } catch (_: IOException) {
        denied()
    }

    private fun denied() = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        securityHeaders,
        ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        const val PLAYER_URL = "${RichContentRequestPolicy.BASE_URL}previs/previs_player.html"
        private const val PREVIS_ASSET_PREFIX = "previs/"
        private const val PREVIS_PLAYER_ASSET = "previs/previs_player.html"
        private const val PREVIS_CSP =
            "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
                "img-src 'self' data: blob:; connect-src 'none'; font-src 'none'; " +
                "frame-src 'none'; object-src 'none'; base-uri 'none'"

        private val securityHeaders = mapOf(
            "Content-Security-Policy" to PREVIS_CSP,
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
            "Cross-Origin-Resource-Policy" to "same-origin",
        )
    }
}

/** 统一配置纯离线 WebGL 预演 WebView 参数；禁止本地文件与外网加载。 */
@SuppressLint("SetJavaScriptEnabled")
internal fun configureOfflinePrevisWebView(view: WebView) {
    view.setBackgroundColor(android.graphics.Color.rgb(16, 21, 31))
    view.settings.apply {
        javaScriptEnabled = true
        blockNetworkLoads = true
        allowFileAccess = false
        allowContentAccess = false
        @Suppress("DEPRECATION")
        allowFileAccessFromFileURLs = false
        @Suppress("DEPRECATION")
        allowUniversalAccessFromFileURLs = false
        domStorageEnabled = false
        @Suppress("DEPRECATION")
        databaseEnabled = false
        setSupportZoom(false)
        builtInZoomControls = false
        displayZoomControls = false
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        safeBrowsingEnabled = false
    }
}
