package com.promenar.infinity.ui.renderer

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.promenar.infinity.ui.theme.InfinityShapes
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.util.Locale

@Immutable
internal data class RichContentPalette(
    val background: String,
    val foreground: String,
    val surface: String,
    val outline: String,
    val primary: String,
    val error: String,
    val codeBackground: String,
    val codeForeground: String,
    val isDark: Boolean,
)

private fun Color.toCssColor(): String = String.format(
    Locale.US,
    "#%06X",
    toArgb() and 0xFFFFFF,
)

@Composable
internal fun rememberRichContentPalette(): RichContentPalette {
    val scheme = MaterialTheme.colorScheme
    val domainColors = MaterialTheme.infinityDomainColors
    return remember(
        scheme.background,
        scheme.onBackground,
        scheme.surfaceContainer,
        scheme.outline,
        scheme.primary,
        scheme.error,
        scheme.surfaceContainerLow,
        domainColors.codeString,
    ) {
        RichContentPalette(
            background = scheme.background.toCssColor(),
            foreground = scheme.onBackground.toCssColor(),
            surface = scheme.surfaceContainer.toCssColor(),
            outline = scheme.outline.toCssColor(),
            primary = scheme.primary.toCssColor(),
            error = scheme.error.toCssColor(),
            codeBackground = scheme.surfaceContainerLow.toCssColor(),
            codeForeground = domainColors.codeString.toCssColor(),
            isDark = scheme.background.luminance() < 0.5f,
        )
    }
}

private fun themeStyle(palette: RichContentPalette, fontSize: Int): String = """
    <style id="infinity-theme">
        :root {
            color-scheme: ${if (palette.isDark) "dark" else "light"};
            --infinity-background: ${palette.background};
            --infinity-foreground: ${palette.foreground};
            --infinity-surface: ${palette.surface};
            --infinity-outline: ${palette.outline};
            --infinity-primary: ${palette.primary};
            --infinity-error: ${palette.error};
            --infinity-code-background: ${palette.codeBackground};
            --infinity-code-foreground: ${palette.codeForeground};
        }
        html, body {
            background-color: var(--infinity-background);
            color: var(--infinity-foreground);
        }
        a { color: var(--infinity-primary); }
        pre, code, kbd, samp {
            background-color: var(--infinity-code-background);
            color: var(--infinity-code-foreground);
        }
        pre, blockquote, table, th, td { border-color: var(--infinity-outline); }
        .error, [data-infinity-error] { color: var(--infinity-error); }
        body { font-size: ${fontSize}px; }
    </style>
""".trimIndent()

private fun applyThemeToHtml(
    html: String,
    fontSize: Int,
    palette: RichContentPalette,
): String {
    val trimmed = html.trim()
    val style = themeStyle(palette, fontSize)
    return if (
        trimmed.startsWith("<!DOCTYPE", ignoreCase = true) ||
        trimmed.contains("<html", ignoreCase = true) ||
        trimmed.contains("<body", ignoreCase = true)
    ) {
        val closingHead = Regex("</head>", RegexOption.IGNORE_CASE)
        if (closingHead.containsMatchIn(html)) {
            html.replaceFirst(closingHead, "$style\n</head>")
        } else {
            "$style\n$html"
        }
    } else {
        val escaped = html.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            $style
            <style>
                html, body {
                    margin: 0;
                    padding: 8px;
                    font-family: monospace;
                    word-wrap: break-word;
                    white-space: pre-wrap;
                    height: auto !important;
                    overflow: hidden;
                    box-sizing: border-box;
                }
            </style>
        </head>
        <body>$escaped</body>
        </html>
        """.trimIndent()
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun RichContentWebView(
    html: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14,
    minHeight: Int = 40,
    maxHeight: Int = 600,
    onWebViewCreated: ((WebView) -> Unit)? = null,
) {
    val context = LocalContext.current
    val palette = rememberRichContentPalette()
    var webViewHeight by remember { mutableIntStateOf(minHeight) }

    // WebViewClient 必须在首次文档加载前绑定，以共享安全文档快照与测高回调。
    // 通过 rememberUpdatedState 保持 minHeight/maxHeight 引用的新鲜度，
    // 避免 WebViewClient 闭包捕获过期参数值。
    val currentMinHeight by rememberUpdatedState(minHeight)
    val currentMaxHeight by rememberUpdatedState(maxHeight)

    val webView = remember {
        RichContentWebViewPool.acquire(context).apply {
            webViewClient = object : RichContentWebViewClient(context) {
                override fun onPageFinished(view: WebView?, url: String?) {
                    // 双重测量 + 延迟重测，覆盖 JS 动态渲染（Mermaid/ECharts 等）
                    fun measure() {
                        view?.evaluateJavascript(
                            "(function() { return Math.max(document.body.scrollHeight, document.documentElement.scrollHeight); })()"
                        ) { heightStr ->
                            val h = heightStr.toIntOrNull() ?: currentMinHeight
                            webViewHeight = h.coerceIn(currentMinHeight, currentMaxHeight)
                        }
                    }
                    measure()
                    view?.postDelayed({ measure() }, 100)
                    view?.postDelayed({ measure() }, 400)
                }
            }
        }.also { onWebViewCreated?.invoke(it) }
    }

    val finalHtml = remember(html, fontSize, palette) {
        applyThemeToHtml(html, fontSize, palette)
    }

    // 避免每次 Recompose 都重新 load，减少无用 WebView 重载
    var lastLoadedHtml by remember { mutableStateOf("") }

    DisposableEffect(webView) {
        onDispose {
            // 归还前重置 WebViewClient，防止测高闭包在池中长期持有
            RichContentWebViewPool.release(webView)
        }
    }

    AndroidView(
        factory = { webView },
        update = { wv ->
            wv.settings.defaultFontSize = fontSize
            if (lastLoadedHtml != finalHtml) {
                lastLoadedHtml = finalHtml
                wv.loadRichContent(finalHtml)
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .height(webViewHeight.dp)
            .clip(InfinityShapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLowest)
    )
}
