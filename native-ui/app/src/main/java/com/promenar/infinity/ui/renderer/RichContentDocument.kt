package com.promenar.infinity.ui.renderer

import android.webkit.WebView
import org.jsoup.Jsoup

internal const val RICH_CONTENT_CSP = "default-src 'none'; " +
    "script-src https: 'unsafe-inline' 'unsafe-eval'; style-src https: 'unsafe-inline'; " +
    "img-src https: data:; font-src https: data:; connect-src https:; " +
    "media-src https://workspace-media.infinity.invalid; " +
    "frame-src 'none'; object-src 'none'; worker-src 'none'; base-uri 'none'; form-action 'none'"

internal fun secureRichContentDocument(html: String): String {
    val document = Jsoup.parse(html)
    document.outputSettings().prettyPrint(false)
    document.head().prependElement("meta").attr("http-equiv", "Content-Security-Policy").attr("content", RICH_CONTENT_CSP)
    document.head().prependElement("meta").attr("name", "referrer").attr("content", "no-referrer")
    return document.outerHtml()
}

internal fun WebView.loadRichContent(html: String) {
    val client = checkNotNull(webViewClient as? RichContentWebViewClient) { "富内容必须使用安全客户端" }
    client.loadDocument(this, html)
}
