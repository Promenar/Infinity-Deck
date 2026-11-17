package com.promenar.infinity.data.export

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.promenar.infinity.domain.export.SessionExportFile
import com.promenar.infinity.domain.export.SessionExportFormat
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** 只使用公开 API。分段滚动软件绘制，不改变全进程 WebView 的整页绘制策略。 */
internal class ExportWebViewRenderer(private val context: Context) {
    suspend fun render(html: String, directory: File, base: String, format: SessionExportFormat): List<SessionExportFile> = withContext(Dispatchers.Main) {
        val view = ExportRenderView(context)
        try {
            configure(view)
            // 先用固定物理宽度布局，再按网页报告的 CSS 宽度换算绘制坐标。
            view.setInitialScale(100)
            val width = if (format == SessionExportFormat.PDF) 760 else EXPORT_WIDTH
            layout(view, width, 1200)
            val metrics = loadMetrics(view, html)
            val cssWidth = metrics.getValue("width").jsonPrimitive.double
            require(cssWidth > 0)
            val scale = width / cssWidth
            val height = ceil(metrics.getValue("height").jsonPrimitive.double * scale).toLong()
            require(height in 1..Int.MAX_VALUE.toLong()) { "导出页面高度超限" }
            val total = height.toInt()
            if (format == SessionExportFormat.PNG) {
                val boundaries = metrics.getValue("boundaries").jsonArray.map { floor(it.jsonPrimitive.double * scale).toInt() }
                val slices = splitExportHeight(total, boundaries)
                val names = exportImageNames(base, slices.size)
                slices.mapIndexed { index, slice ->
                    ensureActive()
                    val bitmap = Bitmap.createBitmap(EXPORT_WIDTH, slice.height, Bitmap.Config.ARGB_8888)
                    try {
                        bitmap.eraseColor(Color.WHITE)
                        drawSlice(view, Canvas(bitmap), slice, width)
                        val file = File(directory, names[index])
                        withContext(Dispatchers.IO) {
                            file.outputStream().buffered().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "长图压缩失败" } }
                            ensureActive()
                        }
                        SessionExportFile(file, file.name, format.mimeType)
                    } finally { bitmap.recycle() }
                }
            } else {
                // A4 的 595×842 点；每边 42 点（约 15 mm）。
                val pageWidth = 595
                val pageHeight = 842
                val margin = 42
                val ratio = (pageWidth - margin * 2).toFloat() / width
                val pixelsPerPage = floor((pageHeight - margin * 2) / ratio.toDouble()).toInt()
                val blocks = metrics.getValue("blocks").jsonArray.map { item ->
                    ExportSlice(floor(item.jsonObject.getValue("top").jsonPrimitive.double * scale).toInt(),
                        ceil(item.jsonObject.getValue("bottom").jsonPrimitive.double * scale).toInt())
                }
                val slices = splitExportPdfHeight(total, blocks, pixelsPerPage)
                val pdf = PdfDocument()
                try {
                    slices.forEachIndexed { index, slice ->
                        ensureActive()
                        val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, pageHeight, index + 1).create())
                        try {
                            page.canvas.drawColor(Color.WHITE)
                            page.canvas.save()
                            page.canvas.translate(margin.toFloat(), margin.toFloat())
                            page.canvas.scale(ratio, ratio)
                            page.canvas.clipRect(0, 0, width, slice.height)
                            drawSlice(view, page.canvas, slice, width)
                            page.canvas.restore()
                        } finally { pdf.finishPage(page) }
                    }
                    val file = File(directory, "$base.pdf")
                    withContext(Dispatchers.IO) {
                        file.outputStream().buffered().use { pdf.writeTo(it) }
                        ensureActive()
                    }
                    listOf(SessionExportFile(file, file.name, format.mimeType))
                } finally { pdf.close() }
            }
        } finally {
            dispose(view)
        }
    }

    suspend fun renderUnbounded(
        html: String,
        directory: File,
        base: String,
        format: SessionExportFormat,
        baseWidthPx: Int = 1440,
    ): List<SessionExportFile> = withContext(Dispatchers.Main) {
        require(format == SessionExportFormat.PNG || format == SessionExportFormat.PDF)
        require(baseWidthPx > 0)
        val view = ExportRenderView(context)
        try {
            configure(view)
            val scale = baseWidthPx / 720.0
            view.settings.useWideViewPort = true
            view.setInitialScale((scale * 100).toInt().coerceAtLeast(1))
            layout(view, baseWidthPx, EXPORT_BAND_HEIGHT + 2)
            val zoom = scale / context.resources.displayMetrics.density
            var metrics = loadMetrics(view, lockViewportScale(html, zoom))
            val width = pixelDimension(metrics.contentWidth() * scale).coerceAtLeast(baseWidthPx)
            val nativeWidth = pixelDimension(width / scale * metrics.nativeScale(scale)).coerceAtLeast(baseWidthPx)
            // 视口扩大后浏览器可能把小数视口进位，输出宽度以原始内容测量值为准。
            if (nativeWidth != view.width) {
                layout(view, nativeWidth, view.height)
                delay(60)
                metrics = requireNotNull(readMetrics(view))
            }
            var drawScale = scale / metrics.nativeScale(scale)
            val viewportHeight = pixelDimension(EXPORT_BAND_HEIGHT / drawScale) + 2
            if (viewportHeight != view.height) {
                layout(view, view.width, viewportHeight)
                delay(60)
                metrics = requireNotNull(readMetrics(view))
                drawScale = scale / metrics.nativeScale(scale)
            }
            val height = pixelDimension(metrics.dimension("height") * scale)
            val file = File(directory, "$base.${format.extension}")
            if (format == SessionExportFormat.PNG) {
                writeUnboundedPng(view, file, width, height, drawScale)
            } else {
                val blocks = metrics.getValue("blocks").jsonArray.map { item ->
                    val block = item.jsonObject
                    ExportSlice(floor(block.dimension("top") * scale).toInt(), ceil(block.dimension("bottom") * scale).toInt())
                }
                writeUnboundedPdf(view, file, width, height, drawScale, baseWidthPx, blocks)
            }
            listOf(SessionExportFile(file, file.name, format.mimeType))
        } finally {
            dispose(view)
        }
    }

    private suspend fun writeUnboundedPng(view: ExportRenderView, file: File, width: Int, height: Int, drawScale: Double) =
        withContext(Dispatchers.IO) {
            // 约束每条带的内存用量，整图只受 PNG 与 WebView 可表示的尺寸约束。
            val rows = minOf(height, EXPORT_BAND_HEIGHT, (2_097_152 / width).coerceAtLeast(1))
            val bitmap = Bitmap.createBitmap(width, rows, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(bitmap)
                file.outputStream().buffered().use { output ->
                    PngStreamEncoder.encodeBands(width, height, output, rows) { top, _, bandRows, pixels ->
                        ensureActive()
                        withContext(Dispatchers.Main) {
                            bitmap.eraseColor(Color.WHITE)
                            drawUnboundedSlice(view, canvas, ExportSlice(top, top + bandRows), width, drawScale)
                        }
                        bitmap.getPixels(pixels, 0, width, 0, 0, width, bandRows)
                    }
                }
            } finally {
                bitmap.recycle()
            }
        }

    private suspend fun writeUnboundedPdf(
        view: ExportRenderView,
        file: File,
        width: Int,
        height: Int,
        drawScale: Double,
        baseWidth: Int,
        blocks: List<ExportSlice>,
    ) {
        val margin = 42
        val pageHeight = 842
        val ratio = (595 - margin * 2).toDouble() / baseWidth
        val pageWidth = pixelDimension(width * ratio) + margin * 2
        val standardRows = floor((pageHeight - margin * 2) / ratio).toInt().coerceAtLeast(1)
        val pages = splitUnboundedExportPdfHeight(height, blocks, standardRows)
        val pdf = PdfDocument()
        try {
            pages.forEachIndexed { index, slice ->
                currentCoroutineContext().ensureActive()
                val expandedHeight = (pixelDimension(slice.height * ratio) + margin * 2).coerceAtLeast(pageHeight)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageWidth, expandedHeight, index + 1).create())
                try {
                    val canvas = page.canvas
                    canvas.drawColor(Color.WHITE)
                    canvas.translate(margin.toFloat(), margin.toFloat())
                    canvas.scale(ratio.toFloat(), ratio.toFloat())
                    var top = slice.top
                    while (top < slice.bottom) {
                        val bottom = top + minOf(EXPORT_BAND_HEIGHT, slice.bottom - top)
                        canvas.save()
                        canvas.translate(0f, (top - slice.top).toFloat())
                        drawUnboundedSlice(view, canvas, ExportSlice(top, bottom), width, drawScale)
                        canvas.restore()
                        top = bottom
                    }
                } finally {
                    pdf.finishPage(page)
                }
            }
            withContext(Dispatchers.IO) { file.outputStream().buffered().use { pdf.writeTo(it) } }
        } finally {
            pdf.close()
        }
    }

    private suspend fun drawUnboundedSlice(view: ExportRenderView, canvas: Canvas, slice: ExportSlice, width: Int, drawScale: Double) {
        // 末带通常从视口底端取像素，先把原生滚动锚点限制在 WebView 的有效范围内。
        val scrollTop = floor(slice.top / drawScale).toInt().coerceAtMost(view.maxScrollY())
        view.scrollTo(0, scrollTop)
        evaluate(view, "document.documentElement.offsetHeight")
        delay(32)
        currentCoroutineContext().ensureActive()
        canvas.save()
        try {
            canvas.clipRect(0, 0, width, slice.height)
            // View.draw 使用文档绝对坐标，先对齐目标条带，再补偿 WebView 的实际缩放。
            canvas.translate(0f, -slice.top.toFloat())
            canvas.scale(drawScale.toFloat(), drawScale.toFloat())
            view.draw(canvas)
        } finally {
            canvas.restore()
        }
    }

    private fun JsonObject.contentWidth() = maxOf(dimension("width"), dimension("scrollWidth"))

    private fun JsonObject.dimension(name: String) = getValue(name).jsonPrimitive.double

    private fun JsonObject.nativeScale(target: Double): Double {
        val measured = dimension("pixelScale")
        require(measured.isFinite() && measured > 0) { "无法读取导出视口缩放" }
        return if (abs(measured - target) < 0.000001) target else measured
    }

    private fun lockViewportScale(html: String, zoom: Double): String {
        val viewport = "<meta name=\"viewport\" content=\"width=device-width,initial-scale=$zoom,minimum-scale=$zoom,maximum-scale=$zoom,user-scalable=no\">"
        return if (EXPORT_VIEWPORT.containsMatchIn(html)) EXPORT_VIEWPORT.replaceFirst(html, viewport)
        else EXPORT_HEAD.find(html)?.let { html.replaceRange(it.range.last + 1, it.range.last + 1, viewport) } ?: "$viewport$html"
    }

    private fun pixelDimension(value: Double): Int {
        require(value.isFinite() && value > 0 && value <= Int.MAX_VALUE) { "导出尺寸超出支持范围" }
        return ceil(value).toInt()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configure(view: ExportRenderView) {
        view.setBackgroundColor(Color.WHITE)
        view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        view.settings.apply {
            javaScriptEnabled = true
            blockNetworkLoads = true
            blockNetworkImage = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            domStorageEnabled = false
            textZoom = 100
            builtInZoomControls = false
        }
        view.isVerticalScrollBarEnabled = false
        view.isHorizontalScrollBarEnabled = false
    }

    private suspend fun loadMetrics(view: ExportRenderView, html: String): JsonObject {
        var loaded = false
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(webView: WebView, url: String?) { loaded = true }
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
        }
        return withTimeout(30_000) {
            view.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            var result: JsonObject? = null
            while (result == null) {
                ensureActive()
                if (loaded) {
                    result = readMetrics(view)
                }
                if (result == null) delay(60)
            }
            result
        }
    }

    private suspend fun readMetrics(view: ExportRenderView): JsonObject? {
        val raw = evaluate(view, "(window.__sessionExportReady && window.__sessionExportMetrics) ? JSON.stringify(window.__sessionExportMetrics()) : null")
        if (raw == "null") return null
        return Json.parseToJsonElement(Json.parseToJsonElement(raw).jsonPrimitive.content).jsonObject
    }

    private suspend fun dispose(view: ExportRenderView) = withContext(NonCancellable + Dispatchers.Main.immediate) {
        view.disposed = true
        view.stopLoading()
        view.webViewClient = WebViewClient()
        view.removeAllViews()
        view.destroy()
    }

    private suspend fun drawSlice(view: ExportRenderView, canvas: Canvas, slice: ExportSlice, width: Int) {
        currentCoroutineContext().ensureActive()
        layout(view, width, slice.height)
        view.scrollTo(0, slice.top)
        // 布局与滚动均经渲染进程确认后再绘制，避免重复上一段。
        evaluate(view, "document.documentElement.offsetHeight")
        delay(32)
        currentCoroutineContext().ensureActive()
        canvas.save()
        canvas.clipRect(0, 0, width, slice.height)
        // 手工 View.draw 没有父容器的 -scrollY 变换；先补偿滚动，再对齐目标切片。
        canvas.translate(0f, -slice.top.toFloat())
        view.draw(canvas)
        canvas.restore()
    }

    private fun layout(view: WebView, width: Int, height: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private suspend fun evaluate(view: ExportRenderView, script: String): String = withTimeout(5000) { suspendCancellableCoroutine { continuation ->
        view.evaluateJavascript(script) { value -> if (continuation.isActive) continuation.resume(value) }
        continuation.invokeOnCancellation { Handler(Looper.getMainLooper()).post { if (!view.disposed) view.stopLoading() } }
    } }
}

private class ExportRenderView(context: Context) : WebView(context) {
    var disposed = false

    fun maxScrollY(): Int = (computeVerticalScrollRange() - computeVerticalScrollExtent()).coerceAtLeast(0)
}

internal const val EXPORT_WIDTH = 1080
private const val EXPORT_BAND_HEIGHT = 2048
private val EXPORT_HEAD = Regex("<head\\b[^>]*>", RegexOption.IGNORE_CASE)
private val EXPORT_VIEWPORT = Regex("""<meta\s+name=["']viewport["'][^>]*>""", RegexOption.IGNORE_CASE)

internal fun splitUnboundedExportPdfHeight(height: Int, blocks: List<ExportSlice>, pageHeight: Int): List<ExportSlice> {
    require(height > 0 && pageHeight > 0)
    val visible = blocks.map { ExportSlice(it.top.coerceIn(0, height), it.bottom.coerceIn(0, height)) }.filter { it.height > 0 }
    val expanded = mutableListOf<ExportSlice>()
    visible.filter { it.height > pageHeight }.sortedBy { it.top }.forEach { slice ->
        val previous = expanded.lastOrNull()
        if (previous != null && slice.top < previous.bottom) {
            expanded[expanded.lastIndex] = previous.copy(bottom = maxOf(previous.bottom, slice.bottom))
        } else {
            expanded.add(slice)
        }
    }
    return buildList {
        var top = 0
        fun appendStandardPages(bottom: Int) {
            if (bottom <= top) return
            val contained = visible.filter { it.top >= top && it.bottom <= bottom }
                .map { ExportSlice(it.top - top, it.bottom - top) }
            addAll(splitExportPdfHeight(bottom - top, contained, pageHeight).map { ExportSlice(it.top + top, it.bottom + top) })
        }
        expanded.forEach { block ->
            appendStandardPages(block.top)
            add(block)
            top = block.bottom
        }
        appendStandardPages(height)
    }
}
