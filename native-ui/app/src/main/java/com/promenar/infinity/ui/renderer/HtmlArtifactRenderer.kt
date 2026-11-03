package com.promenar.infinity.ui.renderer

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private val HTML_ARTIFACT_LANGUAGES = setOf("html", "htm", "svg", "xml")

internal fun isHtmlArtifact(language: String?): Boolean =
    language != null && language.lowercase() in HTML_ARTIFACT_LANGUAGES

/**
 * 智能判定 XML/HTML 内容是否为真正有意义的可渲染网页或图形。
 * 防止将 <tool_call> 等普通 XML 代码块误判为 HTML 并渲染大白底。
 */
internal fun isLikelyRenderableHtml(code: String): Boolean {
    val trimmed = code.trim()
    // SVG 图形必然是可渲染的
    if (trimmed.contains("<svg", ignoreCase = true)) return true

    // 典型的 HTML 标记
    val renderableTags = setOf(
        "html", "body", "div", "iframe", "canvas", "table", "form",
        "h1", "h2", "h3", "style", "section", "article", "p", "a"
    )
    val hasTag = renderableTags.any { tag ->
        trimmed.contains("<$tag", ignoreCase = true) || trimmed.contains("</$tag", ignoreCase = true)
    }

    // 排除工具调用标签
    val isToolCall = trimmed.contains("tool_call", ignoreCase = true) || trimmed.contains("function_call", ignoreCase = true)

    return hasTag && !isToolCall
}

@Composable
fun HtmlArtifactCard(
    htmlCode: String,
    language: String?,
    fontSize: Int = 13,
    onWebViewCreated: (WebView) -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (!isHtmlArtifact(language)) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        )
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            WorkspaceHtmlWebView(
                html = htmlCode,
                fontSize = fontSize,
                modifier = Modifier.fillMaxWidth(),
                minHeight = 60,
                onWebViewCreated = onWebViewCreated
            )
        }
    }
}


@Composable
fun HtmlArtifactsPopup(
    htmlCode: String,
    fontSize: Int = 13,
    onDismiss: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val context = LocalContext.current
    val exportBackground = MaterialTheme.colorScheme.background.toArgb()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "HTML Preview",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        webView?.let { exportHtmlArtifactPng(it, context, exportBackground) }
                    }) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = "Export PNG",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    WorkspaceHtmlWebView(
                        html = htmlCode,
                        fontSize = fontSize,
                        modifier = Modifier.fillMaxWidth(),
                        minHeight = 200,
                        maxHeight = 10000,
                        onWebViewCreated = { wv -> webView = wv }
                    )
                }
            }
        }
    }
}

internal fun exportHtmlArtifactPng(webView: WebView, context: Context) {
    exportHtmlArtifactPng(webView, context, android.graphics.Color.TRANSPARENT)
}

private fun exportHtmlArtifactPng(
    webView: WebView,
    context: Context,
    backgroundColor: Int,
) {
    if (webView.width <= 0 || webView.height <= 0) return
    val bitmap = Bitmap.createBitmap(
        webView.width, webView.height, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    canvas.drawColor(backgroundColor)
    webView.draw(canvas)

    val filename = "html_artifact_${System.currentTimeMillis()}.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
        ?: return

    resolver.openOutputStream(uri)?.use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        contentValues.clear()
        contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)
    }

    bitmap.recycle()
}
