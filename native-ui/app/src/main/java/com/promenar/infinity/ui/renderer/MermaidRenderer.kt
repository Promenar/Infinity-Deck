package com.promenar.infinity.ui.renderer

import android.content.ContentValues
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.WebView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.infinity.ui.theme.infinityDomainColors

@Composable
fun MermaidBlock(
    code: String,
    modifier: Modifier = Modifier,
    fontSize: Int = 14
) {
    val context = LocalContext.current
    val palette = rememberRichContentPalette()
    val exportBackground = MaterialTheme.colorScheme.background.toArgb()
    val html = remember(code, fontSize, palette) {
        buildMermaidHtml(code, fontSize, palette)
    }
    var showFullScreen by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    Column(modifier = modifier.semantics {
        contentDescription = "Diagram: ${code.take(60)}"
    }) {
        RichContentWebView(
            html = html,
            fontSize = fontSize,
            minHeight = 100,
            maxHeight = 600,
            onWebViewCreated = { webViewRef = it }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(
                onClick = { showFullScreen = true },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.ZoomIn,
                    contentDescription = "Fullscreen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(
                onClick = {
                    webViewRef?.let { wv ->
                        exportWebViewToPng(context, wv, exportBackground)
                    }
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = "Export PNG",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showFullScreen) {
        MermaidFullScreenDialog(
            html = html,
            fontSize = fontSize,
            onDismiss = { showFullScreen = false }
        )
    }
}

@Composable
private fun MermaidFullScreenDialog(
    html: String,
    fontSize: Int,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val exportBackground = MaterialTheme.colorScheme.background.toArgb()
    val webView = remember { RichContentWebViewPool.acquire(context) }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    DisposableEffect(webView) {
        onDispose { RichContentWebViewPool.release(webView) }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.95f))
        ) {
            AndroidView(
                factory = { webView },
                update = { wv ->
                    wv.settings.defaultFontSize = fontSize
                    wv.loadRichContent(html)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 56.dp, start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(0.5f, 5f)
                            offset = Offset(offset.x + pan.x, offset.y + pan.y)
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
            )

            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                IconButton(
                    onClick = { exportWebViewToPng(context, webView, exportBackground) }
                ) {
                    Icon(
                        Icons.Default.Download,
                        contentDescription = "Export PNG",
                        tint = MaterialTheme.infinityDomainColors.overlayContent
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.infinityDomainColors.overlayContent
                    )
                }
            }
        }
    }
}

private fun exportWebViewToPng(
    context: android.content.Context,
    webView: WebView,
    backgroundColor: Int,
) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context, "Storage permission required", Toast.LENGTH_SHORT).show()
            return
        }
    }

    if (webView.width <= 0 || webView.height <= 0) {
        Toast.makeText(context, "Diagram not ready", Toast.LENGTH_SHORT).show()
        return
    }

    val bitmap = Bitmap.createBitmap(
        webView.width, webView.height, Bitmap.Config.ARGB_8888
    )
    val canvas = Canvas(bitmap)
    canvas.drawColor(backgroundColor)
    webView.draw(canvas)
    saveBitmapToMediaStore(context, bitmap)
}

private fun saveBitmapToMediaStore(context: android.content.Context, bitmap: Bitmap) {
    val filename = "infinity_mermaid_${System.currentTimeMillis()}.png"
    val contentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, filename)
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Infinity Deck")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)

    if (uri != null) {
        resolver.openOutputStream(uri)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)
        }
        Toast.makeText(context, "Saved to Pictures/Infinity Deck", Toast.LENGTH_SHORT).show()
    } else {
        Toast.makeText(context, "Failed to save", Toast.LENGTH_SHORT).show()
    }
    bitmap.recycle()
}

private fun buildMermaidHtml(
    code: String,
    fontSize: Int,
    palette: RichContentPalette,
): String {
    val encoded = android.util.Base64.encodeToString(
        code.toByteArray(), android.util.Base64.NO_WRAP
    )
    return """
    <!DOCTYPE html>
    <html>
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <script src="mermaid/mermaid.min.js"></script>
        <style>
            body { margin: 0; padding: 12px; background: ${palette.background}; color: ${palette.foreground}; font-size: ${fontSize}px; }
            .mermaid { display: flex; justify-content: center; }
            .mermaid svg { max-width: 100%; height: auto; }
            #mermaid-diagram { transition: transform 0.2s; transform-origin: top left; }
            .node rect, .node circle, .node polygon { fill: ${palette.surface} !important; stroke: ${palette.outline} !important; }
            .nodeLabel, .edgeLabel { color: ${palette.foreground} !important; fill: ${palette.foreground} !important; }
            .edgePath .path { stroke: ${palette.outline} !important; }
            .cluster rect { fill: ${palette.codeBackground} !important; stroke: ${palette.outline} !important; }
        </style>
    </head>
    <body>
        <div class="mermaid" id="mermaid-diagram"></div>
        <script>
            var bytes = Uint8Array.from(atob("$encoded"), function(c) { return c.charCodeAt(0); });
            var diagramCode = new TextDecoder('utf-8').decode(bytes);
            document.getElementById('mermaid-diagram').textContent = diagramCode;
            mermaid.initialize({
                startOnLoad: true,
                theme: 'base',
                themeVariables: {
                    darkMode: ${palette.isDark},
                    background: '${palette.background}',
                    primaryColor: '${palette.primary}',
                    primaryTextColor: '${palette.foreground}',
                    lineColor: '${palette.outline}',
                    secondaryColor: '${palette.surface}',
                    tertiaryColor: '${palette.codeBackground}'
                }
            });
            mermaid.run();
        </script>
    </body>
    </html>
    """.trimIndent()
}
