package com.promenar.infinity.ui.production

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.promenar.infinity.R
import com.promenar.infinity.data.production.PrevisCodec
import com.promenar.infinity.data.production.PrevisScene
import com.promenar.infinity.data.production.PrevisVideoEncoder
import com.promenar.infinity.data.production.PrevisVideoResult
import com.promenar.infinity.data.production.decodePrevisDataUrl
import com.promenar.infinity.data.production.totalDurationSeconds
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal enum class PrevisViewMode(val wireValue: String) {
    CAMERA("camera"),
    ORBIT("orbit"),
}

internal enum class PrevisShaderMode(val wireValue: String) {
    CLAY("clay"),
    DEPTH("depth"),
}

/**
 * 协调离线 Three.js WebView 的加载、双向状态同步以及高清 PNG/H.264 MP4 抓帧导出。
 */
internal class PrevisViewportController {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val exportMutex = Mutex()

    @Volatile
    private var activeWebView: WeakReference<WebView>? = null

    @Volatile
    private var pageReady: Boolean = false

    @Volatile
    private var loadedSignature: String? = null

    var onError: ((String) -> Unit)? = null
    var onPlaybackTick: ((shotIndex: Int, localTime: Double, globalTime: Double, playing: Boolean) -> Unit)? = null
    var onPlaybackComplete: (() -> Unit)? = null

    fun attach(webView: WebView) {
        if (activeWebView?.get() !== webView) {
            activeWebView = WeakReference(webView)
            pageReady = false
            loadedSignature = null
        }
    }

    fun detach(webView: WebView) {
        if (activeWebView?.get() === webView) {
            activeWebView = null
            pageReady = false
            loadedSignature = null
        }
    }

    fun markReady() {
        pageReady = true
    }

    fun resetOrbit() {
        val view = activeWebView?.get() ?: return
        if (!pageReady) return
        mainHandler.post {
            if (activeWebView?.get() === view) {
                view.evaluateJavascript("window.__previs && window.__previs.resetOrbit();", null)
            }
        }
    }

    suspend fun syncSceneAndState(
        scene: PrevisScene,
        shotIndex: Int,
        localTime: Double,
        playing: Boolean,
        viewMode: PrevisViewMode,
        shaderMode: PrevisShaderMode,
        showAxis: Boolean,
    ) {
        waitUntilReady()
        val serialized = PrevisCodec.compactEncode(scene)
        if (loadedSignature != serialized) {
            val resultJson = evalJs(
                "window.__previs && window.__previs.loadScene(${JSONObject.quote(serialized)});",
            )
            loadedSignature = serialized
            val err = runCatching {
                val parsed = resultJson?.trim()?.removeSurrounding("\"")?.replace("\\\"", "\"") ?: ""
                if (parsed.startsWith("{")) {
                    val obj = JSONObject(parsed)
                    if (!obj.optBoolean("ok", true)) obj.optString("error") else null
                } else null
            }.getOrNull()
            if (!err.isNullOrBlank()) {
                throw IllegalStateException(err)
            }
        }
        val stateObj = JSONObject().apply {
            put("shotIndex", shotIndex)
            put("localTime", localTime)
            put("playing", playing)
            put("viewMode", viewMode.wireValue)
            put("shaderMode", shaderMode.wireValue)
            put("showAxis", showAxis)
        }
        evalJs("window.__previs && window.__previs.setState(${stateObj});")
    }

    suspend fun captureStillPng(
        shotIndex: Int,
        localTime: Double,
        shaderMode: PrevisShaderMode,
        width: Int,
        height: Int,
    ): ByteArray = exportMutex.withLock {
        waitUntilReady()
        val js = "window.__previs && window.__previs.captureStill($shotIndex, $localTime, ${JSONObject.quote(shaderMode.wireValue)}, $width, $height);"
        val result = withTimeout(15_000L) { evalJs(js) }
        decodePrevisDataUrl(result) ?: error("3D 视口返回空图像数据。")
    }

    suspend fun exportEpisodeMp4(
        scene: PrevisScene,
        width: Int,
        height: Int,
        fps: Int = 24,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): PrevisVideoResult = exportMutex.withLock {
        waitUntilReady()
        val serialized = PrevisCodec.compactEncode(scene)
        if (loadedSignature != serialized) {
            evalJs("window.__previs && window.__previs.loadScene(${JSONObject.quote(serialized)});")
            loadedSignature = serialized
        }
        val started = evalJs("window.__previs && window.__previs.beginExport($width, $height, 'clay');")
        check(started?.contains("true") == true) { "无法进入 3D 离线导出模式。" }
        val decodeOptions = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        try {
            val encoder = PrevisVideoEncoder(width = width, height = height, fps = fps)
            encoder.encode(
                outputFile = outputFile,
                totalDurationSeconds = scene.totalDurationSeconds(),
                captureFrame = { globalTimeSec ->
                    val raw = withTimeout(15_000L) {
                        evalJs("window.__previs && window.__previs.renderExportFrame($globalTimeSec);")
                    }
                    val bytes = decodePrevisDataUrl(raw)
                        ?: error("导出第 ${String.format(Locale.US, "%.2f", globalTimeSec)}s 帧时返回空数据。")
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions)
                        ?: error("无法解码导出的 3D 预演帧。")
                },
                onProgress = onProgress,
            )
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) {
                runCatching { evalJs("window.__previs && window.__previs.endExport();") }
            }
        }
    }

    private suspend fun waitUntilReady() {
        var waitedMs = 0L
        while (!pageReady || activeWebView?.get() == null) {
            if (waitedMs >= 8_000L) {
                error("3D 预演渲染器尚未就绪，请稍候重试。")
            }
            delay(40L)
            waitedMs += 40L
        }
    }

    private suspend fun evalJs(script: String): String? = withContext(Dispatchers.Main.immediate) {
        val view = activeWebView?.get() ?: error("3D 预演视口已分离。")
        suspendCancellableCoroutine { cont ->
            view.evaluateJavascript(script) { value ->
                if (cont.isActive) cont.resume(value)
            }
        }
    }
}

private class PrevisJsBridge(
    private val controller: PrevisViewportController,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onReady() {
        mainHandler.post { controller.markReady() }
    }

    @JavascriptInterface
    fun onError(message: String?) {
        val text = message?.takeIf { it.isNotBlank() } ?: "WebGL error"
        mainHandler.post { controller.onError?.invoke(text) }
    }

    @JavascriptInterface
    fun onPlaybackTick(shotIndex: Int, localTime: Double, globalTime: Double, playing: Boolean) {
        mainHandler.post {
            controller.onPlaybackTick?.invoke(shotIndex, localTime, globalTime, playing)
        }
    }

    @JavascriptInterface
    fun onPlaybackComplete() {
        mainHandler.post { controller.onPlaybackComplete?.invoke() }
    }
}

@Composable
internal fun PrevisStageViewport(
    scene: PrevisScene,
    controller: PrevisViewportController,
    shotIndex: Int,
    localTime: Double,
    seekGeneration: Int,
    playing: Boolean,
    viewMode: PrevisViewMode,
    shaderMode: PrevisShaderMode,
    showThirdsGrid: Boolean,
    showAxisGuide: Boolean,
    onTick: (shotIndex: Int, localTime: Double, globalTime: Double, playing: Boolean) -> Unit,
    onComplete: () -> Unit,
    onEngineError: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOnTick by rememberUpdatedState(onTick)
    val currentOnComplete by rememberUpdatedState(onComplete)
    val currentOnError by rememberUpdatedState(onEngineError)
    val currentViewMode by rememberUpdatedState(viewMode)

    DisposableEffect(controller) {
        controller.onPlaybackTick = { idx, local, global, isPlaying ->
            currentOnTick(idx, local, global, isPlaying)
        }
        controller.onPlaybackComplete = { currentOnComplete() }
        controller.onError = { currentOnError(it) }
        onDispose {
            controller.onPlaybackTick = null
            controller.onPlaybackComplete = null
            controller.onError = null
        }
    }

    val stageBg = MaterialTheme.colorScheme.scrim
    Box(modifier = modifier.background(stageBg)) {
        if (LocalInspectionMode.current) {
            InspectionPrevisPlaceholder(
                shaderMode = shaderMode,
                viewMode = viewMode,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            val webViewRef = remember { mutableStateOf<WebView?>(null) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        configureOfflinePrevisWebView(this)
                        webViewClient = PrevisWebViewClient(ctx)
                        addJavascriptInterface(PrevisJsBridge(controller), "PrevisBridge")
                        attachOrbitTouchGuard(this) { currentViewMode }
                        controller.attach(this)
                        loadUrl(PrevisWebViewClient.PLAYER_URL)
                        webViewRef.value = this
                    }
                },
                update = { view ->
                    controller.attach(view)
                },
            )
            DisposableEffect(Unit) {
                onDispose {
                    webViewRef.value?.let { view ->
                        controller.detach(view)
                        view.removeJavascriptInterface("PrevisBridge")
                        view.stopLoading()
                        view.destroy()
                    }
                }
            }
            // 仅在加载场景或用户显式操作（切镜、显式定位、启停、视角/通道切换）时推送状态，避免播放中 tick 引起反馈重同步。
            LaunchedEffect(scene, playing, viewMode, shaderMode, showAxisGuide, shotIndex, seekGeneration) {
                try {
                    controller.syncSceneAndState(
                        scene = scene,
                        shotIndex = shotIndex,
                        localTime = localTime,
                        playing = playing,
                        viewMode = viewMode,
                        shaderMode = shaderMode,
                        showAxis = showAxisGuide,
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    currentOnError(e.message ?: "3D 预演同步失败")
                }
            }
        }

        if (showThirdsGrid) {
            ThirdsOverlayCanvas(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ThirdsOverlayCanvas(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.infinityDomainColors.codeString
    val lineColor = accent.copy(alpha = 0.28f)
    val crossColor = accent.copy(alpha = 0.48f)
    Canvas(modifier = modifier) {
        val stroke = 1.dp.toPx()
        for (i in 1..2) {
            val x = size.width * i / 3f
            val y = size.height * i / 3f
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = stroke)
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = stroke)
        }
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = 10.dp.toPx()
        drawLine(crossColor, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = stroke)
        drawLine(crossColor, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = stroke)
    }
}

@Composable
private fun InspectionPrevisPlaceholder(
    shaderMode: PrevisShaderMode,
    viewMode: PrevisViewMode,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val bgTop = scheme.scrim
        val bgBot = if (shaderMode == PrevisShaderMode.DEPTH) scheme.scrim else scheme.surfaceContainerLowest
        drawRect(Brush.verticalGradient(listOf(bgTop, bgBot)))
        val horizonY = h * 0.45f
        val grid = domain.overlayContent.copy(alpha = if (shaderMode == PrevisShaderMode.DEPTH) 0.05f else 0.14f)
        for (i in -4..4) {
            drawLine(grid, Offset(w * 0.5f + i * 8.dp.toPx(), horizonY), Offset(w * 0.5f + i * w * 0.16f, h), strokeWidth = 1.dp.toPx())
        }
        val blockColor = if (shaderMode == PrevisShaderMode.DEPTH) domain.overlayContent.copy(alpha = 0.52f) else scheme.onSurfaceVariant.copy(alpha = 0.65f)
        drawRoundRect(
            color = blockColor,
            topLeft = Offset(w * 0.50f, h * 0.24f),
            size = Size(w * 0.32f, h * 0.48f),
            cornerRadius = CornerRadius(4.dp.toPx()),
        )
        val actorColor = if (shaderMode == PrevisShaderMode.DEPTH) domain.overlayContent.copy(alpha = 0.92f) else domain.overlayContent.copy(alpha = 0.85f)
        drawCircle(actorColor, radius = 9.dp.toPx(), center = Offset(w * 0.36f, h * 0.48f))
        drawRoundRect(
            color = actorColor.copy(alpha = 0.9f),
            topLeft = Offset(w * 0.36f - 10.dp.toPx(), h * 0.53f),
            size = Size(20.dp.toPx(), h * 0.25f),
            cornerRadius = CornerRadius(6.dp.toPx()),
        )
        if (viewMode == PrevisViewMode.ORBIT) {
            drawCircle(domain.signalOn, radius = 4.dp.toPx(), center = Offset(w * 0.36f, h * 0.80f))
        }
    }
}

/**
 * 剪辑页一体化监视器外壳：
 * 上方 16:9 画面（3D 白模视口 / 既有视频与占位播放卡），下方紧贴 48dp 常驻播控条，
 * 确保首末镜、播放/停止切换及无预演回退状态下按钮不位移、高度零跳变。
 */
@Composable
internal fun UnifiedCutMonitorCard(
    shotIndex: Int,
    totalShots: Int,
    playing: Boolean,
    localTimeSeconds: Double,
    seekGeneration: Int,
    currentTimeSeconds: Double,
    totalDurationSeconds: Double,
    canPrev: Boolean,
    canNext: Boolean,
    playEnabled: Boolean,
    onPrev: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    previsScene: PrevisScene? = null,
    previsController: PrevisViewportController? = null,
    viewMode: PrevisViewMode = PrevisViewMode.CAMERA,
    shaderMode: PrevisShaderMode = PrevisShaderMode.CLAY,
    showThirdsGrid: Boolean = true,
    cameraTelemetry: String? = null,
    onToggleViewMode: (() -> Unit)? = null,
    onToggleShaderMode: (() -> Unit)? = null,
    onToggleGrid: (() -> Unit)? = null,
    onOpenFullscreen: (() -> Unit)? = null,
    onPrevisTick: (Int, Double, Double, Boolean) -> Unit = { _, _, _, _ -> },
    onPrevisComplete: () -> Unit = {},
    onPrevisError: (String) -> Unit = {},
    fallbackContent: @Composable () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth()) {
            if (previsScene != null && previsController != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .background(scheme.scrim),
                ) {
                    PrevisStageViewport(
                        scene = previsScene,
                        controller = previsController,
                        shotIndex = shotIndex,
                        localTime = localTimeSeconds,
                        seekGeneration = seekGeneration,
                        playing = playing,
                        viewMode = viewMode,
                        shaderMode = shaderMode,
                        showThirdsGrid = showThirdsGrid,
                        showAxisGuide = true,
                        onTick = onPrevisTick,
                        onComplete = onPrevisComplete,
                        onEngineError = onPrevisError,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                            .padding(horizontal = 10.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrevisOverlayPill(
                                text = stringResource(
                                    R.string.p3_previs_shot_label_prefix,
                                    (shotIndex + 1).coerceAtLeast(1),
                                    totalShots.coerceAtLeast(1),
                                ),
                            )
                            PrevisOverlayPill(
                                text = stringResource(
                                    if (shaderMode == PrevisShaderMode.DEPTH) R.string.p3_previs_depth else R.string.p3_previs_clay,
                                ),
                                highlighted = true,
                                onClick = onToggleShaderMode,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PrevisOverlayPill(
                                text = stringResource(
                                    if (viewMode == PrevisViewMode.ORBIT) R.string.p3_previs_orbit_view else R.string.p3_previs_camera_view,
                                ),
                                highlighted = viewMode == PrevisViewMode.ORBIT,
                                onClick = onToggleViewMode,
                            )
                            PrevisOverlayIconButton(
                                icon = Icons.Rounded.GridView,
                                active = showThirdsGrid,
                                contentDescription = stringResource(R.string.p3_previs_grid),
                                onClick = onToggleGrid ?: {},
                            )
                            if (onOpenFullscreen != null) {
                                PrevisOverlayIconButton(
                                    icon = Icons.Rounded.Fullscreen,
                                    active = false,
                                    contentDescription = stringResource(R.string.p3_previs_fullscreen),
                                    onClick = onOpenFullscreen,
                                )
                            }
                        }
                    }
                    if (!cameraTelemetry.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            PrevisOverlayPill(text = cameraTelemetry)
                        }
                    }
                }
            } else {
                fallbackContent()
            }

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.45f))

            PermanentCutTransportBar(
                playing = playing,
                canPrev = canPrev,
                canNext = canNext,
                playEnabled = playEnabled,
                currentTimeSeconds = currentTimeSeconds,
                totalDurationSeconds = totalDurationSeconds,
                onPrev = onPrev,
                onTogglePlay = onTogglePlay,
                onNext = onNext,
            )
        }
    }
}

@Composable
internal fun PermanentCutTransportBar(
    playing: Boolean,
    canPrev: Boolean,
    canNext: Boolean,
    playEnabled: Boolean,
    currentTimeSeconds: Double,
    totalDurationSeconds: Double,
    onPrev: () -> Unit,
    onTogglePlay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IconButton(
            onClick = onPrev,
            enabled = canPrev,
            modifier = Modifier
                .size(40.dp)
                .testTag(ProductionCutTags.PREV_CLIP),
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipPrevious,
                contentDescription = stringResource(R.string.p3_previs_prev_shot),
                tint = if (canPrev) scheme.onSurface else scheme.onSurface.copy(alpha = 0.34f),
            )
        }
        Surface(
            onClick = onTogglePlay,
            enabled = playEnabled,
            shape = RoundedCornerShape(50),
            color = if (playing) scheme.primaryContainer else scheme.surfaceContainerHighest,
            modifier = Modifier
                .heightIn(min = 36.dp)
                .testTag("p2_cut_play"),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = when {
                        !playEnabled -> scheme.onSurface.copy(alpha = 0.38f)
                        playing -> scheme.onPrimaryContainer
                        else -> scheme.onSurface
                    },
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(if (playing) R.string.p3_previs_stop_short else R.string.p3_previs_play_short),
                    style = InfinityTypography.labelLarge,
                    color = when {
                        !playEnabled -> scheme.onSurface.copy(alpha = 0.38f)
                        playing -> scheme.onPrimaryContainer
                        else -> scheme.onSurface
                    },
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        IconButton(
            onClick = onNext,
            enabled = canNext,
            modifier = Modifier
                .size(40.dp)
                .testTag(ProductionCutTags.NEXT_CLIP),
        ) {
            Icon(
                imageVector = Icons.Rounded.SkipNext,
                contentDescription = stringResource(R.string.p2_next_clip),
                tint = if (canNext) scheme.onSurface else scheme.onSurface.copy(alpha = 0.34f),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            text = formatTimecodeRatio(currentTimeSeconds, totalDurationSeconds),
            style = MaterialTheme.typography.labelMedium.copy(
                fontFamily = InfinityMonospace,
                fontFeatureSettings = "tnum",
            ),
            color = scheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(end = 6.dp),
        )
    }
}

@Composable
internal fun PrevisOverlayPill(
    text: String,
    highlighted: Boolean = false,
    darkSurface: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val overlayText = domain.overlayContent
    Surface(
        shape = RoundedCornerShape(50),
        color = when {
            highlighted -> scheme.primaryContainer.copy(alpha = 0.92f)
            darkSurface -> scheme.scrim.copy(alpha = 0.62f)
            else -> scheme.surfaceContainerHighest.copy(alpha = 0.88f)
        },
        border = BorderStroke(
            0.5.dp,
            if (highlighted) scheme.primary.copy(alpha = 0.55f) else overlayText.copy(alpha = 0.16f),
        ),
        modifier = if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = if (highlighted) scheme.onPrimaryContainer else overlayText,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
internal fun PrevisOverlayIconButton(
    icon: ImageVector,
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val overlayText = MaterialTheme.infinityDomainColors.overlayContent
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = if (active) scheme.primaryContainer.copy(alpha = 0.92f) else scheme.scrim.copy(alpha = 0.62f),
        border = BorderStroke(0.5.dp, overlayText.copy(alpha = 0.16f)),
        modifier = Modifier
            .size(32.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (active) scheme.onPrimaryContainer else overlayText,
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

@SuppressLint("ClickableViewAccessibility")
private fun attachOrbitTouchGuard(view: WebView, modeProvider: () -> PrevisViewMode) {
    view.setOnTouchListener { v, event ->
        if (modeProvider() == PrevisViewMode.ORBIT) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE -> v.parent?.requestDisallowInterceptTouchEvent(true)
                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> v.parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        false
    }
}

internal fun formatTimecodeRatio(currentSeconds: Double, totalSeconds: Double): String {
    val cur = currentSeconds.coerceAtLeast(0.0)
    val tot = totalSeconds.coerceAtLeast(0.0)
    return String.format(Locale.US, "%04.1fs / %04.1fs", cur, tot)
}
