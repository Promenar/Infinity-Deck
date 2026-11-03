package com.promenar.infinity.ui.renderer

import android.content.Context
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.net.URLConnection

/** 测高等回调可由渲染器扩展；请求和导航门禁不能被覆盖。 */
internal open class RichContentWebViewClient(context: Context) : WebViewClient() {
    @Volatile var workspaceMediaSource: HtmlWorkspaceMediaSource? = null
    private val assets = context.applicationContext.assets
    private val documents = RichContentDocumentSession()

    fun loadDocument(view: WebView, html: String) {
        val previous = documents.currentUrl
        val url = documents.register(html)
        if (previous != url) view.loadUrl(url)
    }

    final override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = true

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    final override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean = true

    final override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest): WebResourceResponse {
        val mediaUrl = request.url.toString()
        if (HtmlWorkspaceMediaSource.isOrigin(mediaUrl)) {
            if (request.isForMainFrame) return denied()
            val source = workspaceMediaSource ?: return denied()
            val response = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.IO) {
                source.respond(mediaUrl, request.method, request.requestHeaders.entries.firstOrNull { it.key.equals("Range", true) }?.value)
            }
            return WebResourceResponse(response.mime, null, response.status, response.reason, response.headers, response.body)
        }
        if (request.method != "GET") return denied()
        val url = request.url.toString()
        documents.contentFor(url, request.isForMainFrame)?.let { html ->
            return WebResourceResponse(
                "text/html", "UTF-8", 200, "OK", securityHeaders + ("Cache-Control" to "no-store"),
                ByteArrayInputStream(html.toByteArray(Charsets.UTF_8)),
            )
        }
        if (request.isForMainFrame) return denied()
        return when (RichContentRequestPolicy.classify(url)) {
            RichContentRequestPolicy.Kind.LOCAL_ASSET -> localAsset(url)
            RichContentRequestPolicy.Kind.INLINE_DATA -> richContentInlineResource(url, false)?.let {
                WebResourceResponse(it.mimeType, null, 200, "OK", securityHeaders, ByteArrayInputStream(it.bytes))
            } ?: denied()
            RichContentRequestPolicy.Kind.PUBLIC_HTTPS -> publicResource(url, request.requestHeaders)
            RichContentRequestPolicy.Kind.BLOCKED -> denied()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    final override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse =
        if (url != null && RichContentRequestPolicy.classify(url) == RichContentRequestPolicy.Kind.LOCAL_ASSET) localAsset(url) else denied()

    private fun localAsset(url: String): WebResourceResponse {
        val path = RichContentRequestPolicy.assetPath(url) ?: return denied()
        return try {
            val type = when (path.substringAfterLast('.')) {
                "js" -> "application/javascript"
                "css" -> "text/css"
                "woff" -> "font/woff"
                "woff2" -> "font/woff2"
                "ttf" -> "font/ttf"
                "html" -> "text/html"
                else -> URLConnection.guessContentTypeFromName(path) ?: "application/octet-stream"
            }
            WebResourceResponse(type, "UTF-8", 200, "OK", securityHeaders, assets.open(path))
        } catch (_: IOException) {
            denied()
        }
    }

    private fun publicResource(url: String, requestHeaders: Map<String, String>): WebResourceResponse {
        val response = fetcher.fetch(url, requestHeaders) ?: return denied()
        val body = response.body
        if (body == null || body.contentLength() > MAX_RESOURCE_BYTES || response.code !in 200..299) {
            response.close()
            return denied()
        }
        val mediaType = body.contentType()
        val stream = object : FilterInputStream(body.byteStream()) {
            private var bytesRead = 0L
            private fun count(size: Int): Int {
                if (size > 0) bytesRead += size
                if (bytesRead > MAX_RESOURCE_BYTES) {
                    close()
                    throw IOException("富内容资源超出大小限制")
                }
                return size
            }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = count(super.read(buffer, offset, length))
            override fun close() { response.close() }
        }
        val headers = response.headers.toMultimap().filterKeys {
            it.lowercase() !in setOf("set-cookie", "set-cookie2", "content-security-policy", "connection", "transfer-encoding")
        }.mapValues { (_, values) -> values.joinToString(", ") } + securityHeaders
        return WebResourceResponse(
            mediaType?.let { "${it.type}/${it.subtype}" } ?: "application/octet-stream",
            mediaType?.charset()?.name(), response.code, response.message.ifBlank { "OK" }, headers, stream,
        )
    }

    private fun denied() = WebResourceResponse(
        "text/plain", "UTF-8", 403, "Blocked", securityHeaders, ByteArrayInputStream(ByteArray(0)),
    )

    companion object {
        private const val MAX_RESOURCE_BYTES = 16 * 1024 * 1024L
        private val fetcher = RichContentHttpsFetcher()
        private val securityHeaders = mapOf(
            "Content-Security-Policy" to RICH_CONTENT_CSP,
            "X-Content-Type-Options" to "nosniff",
            "Referrer-Policy" to "no-referrer",
        )
    }
}
