package com.promenar.infinity.ui.canvas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.canvas.CanvasDocument
import com.promenar.infinity.data.canvas.CanvasMetadata
import com.promenar.infinity.data.canvas.CanvasScreenSource
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlin.math.roundToInt

/** 画板上一屏的外框尺寸；网页按 [CanvasThumbnailVirtualWidth] 排版后缩进外框。 */
private val ScreenFrameWidth = 240.dp
private val ScreenFrameHeight = 480.dp
private val ScreenGapX = 60.dp
private val ScreenGapY = 56.dp
private const val MIN_ZOOM = 0.25f
private const val MAX_ZOOM = 1.5f

/** 画板一行最多三屏，屏少时按屏数收窄。 */
internal fun prototypeBoardColumns(screenCount: Int): Int = screenCount.coerceIn(1, 3)

/** 下一档缩放：按 [steps] 逐档，超出两端时停在端点。 */
internal fun prototypeZoomStep(current: Float, zoomIn: Boolean, steps: List<Float> = listOf(0.25f, 0.4f, 0.6f, 0.8f, 1f, 1.25f, 1.5f)): Float =
    if (zoomIn) steps.firstOrNull { it > current + 0.001f } ?: steps.last()
    else steps.lastOrNull { it < current - 0.001f } ?: steps.first()

/**
 * 原型查看：画板模式把各屏缩略图排在可缩放的画布上，点某屏或“演示”进入演示模式；
 * 演示模式整屏显示一屏，屏内 `infinity-screen:` 链接切屏，返回键回到画板。
 */
@Composable
internal fun PrototypeBoardContent(
    document: CanvasDocument?,
    metadata: CanvasMetadata?,
    selectedVersion: Int?,
    onSelectVersion: (Int?) -> Unit,
    onCopy: () -> Unit,
    onClose: () -> Unit,
    onInsertText: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    val haptics = LocalInfinityHaptics.current
    val currentMetadata = metadata ?: document?.metadata
    val screens = document?.screens.orEmpty()
    val isHistory = selectedVersion != null
    var focused by rememberSaveable(currentMetadata?.id) { mutableStateOf<String?>(null) }
    val focusIndex = screens.indexOfFirst { it.id == focused }.takeIf { it >= 0 }
    // 换版本后原屏可能已不存在，回到画板。
    LaunchedEffect(screens, focused) { if (focused != null && focusIndex == null && screens.isNotEmpty()) focused = null }
    BackHandler(enabled = focusIndex != null) { focused = null }
    Surface(modifier = modifier.fillMaxSize().testTag(UiTags.PROTOTYPE_BOARD), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    // 顶栏底色延伸到状态栏下方，和系统栏连成一体。
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                    .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (focusIndex != null) {
                        IconButton(
                            onClick = { focused = null },
                            modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget).testTag(UiTags.PROTOTYPE_BACK_TO_BOARD),
                        ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.prototype_back_to_board)) }
                    }
                    Column(Modifier.weight(1f).padding(horizontal = InfinitySpacing.Small)) {
                        Text(
                            text = currentMetadata?.title ?: stringResource(R.string.canvas_title),
                            style = InfinityTypography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val subtitle = if (focusIndex != null) {
                            stringResource(R.string.prototype_screen_position, focusIndex + 1, screens.size, screens[focusIndex].title)
                        } else {
                            stringResource(
                                R.string.prototype_board_subtitle,
                                document?.version ?: currentMetadata?.currentVersion ?: 1,
                                pluralStringResource(R.plurals.artifact_screen_count, screens.size, screens.size),
                            )
                        }
                        Text(text = subtitle, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    IconButton(
                        onClick = onCopy,
                        enabled = document != null && error == null,
                        modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget).testTag(UiTags.CANVAS_COPY),
                    ) { Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.canvas_copy_source)) }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget).testTag(UiTags.CANVAS_CLOSE),
                    ) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.canvas_close)) }
                }
                if (focusIndex == null) {
                    CanvasVersionSelector(
                        metadata = currentMetadata,
                        selectedVersion = selectedVersion,
                        onSelectVersion = onSelectVersion,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Small),
                    )
                }
                if (isHistory) {
                    Text(
                        text = stringResource(R.string.canvas_history_notice),
                        modifier = Modifier.padding(InfinitySpacing.Small),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = InfinityTypography.bodySmall,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                when {
                    error != null -> Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = InfinityTypography.bodyMedium,
                        modifier = Modifier.padding(InfinitySpacing.XLarge),
                    )
                    document == null -> CanvasLoading()
                    focusIndex != null -> PrototypeDemo(
                        screens = screens,
                        index = focusIndex,
                        readOnly = isHistory,
                        onNavigate = { id -> haptics.select(false); focused = id },
                        onInsertText = if (isHistory) ({ _: String -> }) else onInsertText,
                    )
                    else -> PrototypeCanvas(
                        screens = screens,
                        onOpenScreen = { id -> haptics.select(false); focused = id },
                    )
                }
            }
        }
    }
}

@Composable
private fun PrototypeCanvas(screens: List<CanvasScreenSource>, onOpenScreen: (String) -> Unit) {
    val columns = prototypeBoardColumns(screens.size)
    val rows = (screens.size + columns - 1) / columns
    val contentWidth = ScreenFrameWidth * columns + ScreenGapX * (columns - 1)
    val dotColor = MaterialTheme.colorScheme.outlineVariant
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            // 缩放与平移后的屏幕不得画到画板外的标题栏上。
            .clipToBounds(),
    ) {
        Canvas(Modifier.matchParentSize()) {
            val step = 18.dp.toPx()
            var y = step / 2
            while (y < size.height) {
                var x = step / 2
                while (x < size.width) { drawCircle(dotColor, radius = 1.dp.toPx(), center = Offset(x, y)); x += step }
                y += step
            }
        }
        val density = LocalDensity.current
        val margin = InfinitySpacing.Large
        val fitZoom = ((maxWidth - margin * 2) / contentWidth).coerceIn(MIN_ZOOM, 1f)
        var zoom by remember(screens.size) { mutableFloatStateOf(fitZoom) }
        var offset by remember(screens.size) { mutableStateOf(Offset.Zero) }
        val marginPx = with(density) { margin.toPx() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(screens.size) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(MIN_ZOOM, MAX_ZOOM)
                        offset += pan
                    }
                },
        ) {
            Column(
                modifier = Modifier
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = zoom
                        scaleY = zoom
                        translationX = marginPx + offset.x
                        translationY = marginPx + offset.y
                    },
                verticalArrangement = Arrangement.spacedBy(ScreenGapY),
            ) {
                repeat(rows) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ScreenGapX)) {
                        screens.drop(row * columns).take(columns).forEachIndexed { column, screen ->
                            val number = row * columns + column + 1
                            PrototypeScreenFrame(number, screen, onClick = { onOpenScreen(screen.id) })
                        }
                    }
                }
                // 底部留出工具条的位置，最后一行不被遮住。
                Spacer(Modifier.height(96.dp))
            }
            PrototypeToolbar(
                zoomPercent = (zoom * 100).roundToInt(),
                onZoomOut = { zoom = prototypeZoomStep(zoom, zoomIn = false) },
                onZoomIn = { zoom = prototypeZoomStep(zoom, zoomIn = true) },
                onFit = { zoom = fitZoom; offset = Offset.Zero },
                onPlay = { screens.firstOrNull()?.let { onOpenScreen(it.id) } },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = InfinitySpacing.Large),
            )
        }
    }
}

@Composable
private fun PrototypeScreenFrame(number: Int, screen: CanvasScreenSource, onClick: () -> Unit) {
    val label = stringResource(R.string.prototype_open_screen, number, screen.title)
    Column(Modifier.width(ScreenFrameWidth)) {
        Text(
            text = "$number  ${screen.title}",
            style = InfinityTypography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = InfinitySpacing.XSmall, bottom = InfinitySpacing.Small),
        )
        Box(
            modifier = Modifier
                .size(ScreenFrameWidth, ScreenFrameHeight)
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = label }
                .testTag(UiTags.prototypeScreen(screen.id)),
        ) {
            ScaledCanvasThumbnail(Modifier.fillMaxSize()) {
                CanvasWebView(html = screen.html, modifier = Modifier.fillMaxSize(), interactive = false)
            }
        }
    }
}

@Composable
private fun PrototypeToolbar(
    zoomPercent: Int,
    onZoomOut: () -> Unit,
    onZoomIn: () -> Unit,
    onFit: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 6.dp,
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onZoomOut, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.prototype_zoom_out))
            }
            Text(
                text = "$zoomPercent%",
                style = InfinityTypography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(48.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(onClick = onZoomIn, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.prototype_zoom_in))
            }
            IconButton(onClick = onFit, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                Icon(Icons.Rounded.FitScreen, contentDescription = stringResource(R.string.prototype_fit))
            }
            Button(
                onClick = onPlay,
                contentPadding = PaddingValues(start = InfinitySpacing.Medium, end = InfinitySpacing.Large),
                modifier = Modifier.testTag(UiTags.PROTOTYPE_PLAY),
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(InfinitySpacing.XSmall))
                Text(stringResource(R.string.prototype_play))
            }
        }
    }
}

@Composable
private fun PrototypeDemo(
    screens: List<CanvasScreenSource>,
    index: Int,
    readOnly: Boolean,
    onNavigate: (String) -> Unit,
    onInsertText: (String) -> Unit,
) {
    val screen = screens[index]
    Column(Modifier.fillMaxSize().testTag(UiTags.PROTOTYPE_DEMO)) {
        CanvasWebView(
            html = screen.html,
            modifier = Modifier.fillMaxWidth().weight(1f),
            interactive = true,
            readOnly = readOnly,
            onInsertText = onInsertText,
            onScreenLink = { id -> if (screens.any { it.id == id }) onNavigate(id) },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).padding(InfinitySpacing.XSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { onNavigate(screens[(index + screens.size - 1) % screens.size].id) },
                enabled = screens.size > 1,
                modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget),
            ) { Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.prototype_previous_screen)) }
            Text(
                text = "${index + 1} / ${screens.size}",
                style = InfinityTypography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            IconButton(
                onClick = { onNavigate(screens[(index + 1) % screens.size].id) },
                enabled = screens.size > 1,
                modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget),
            ) { Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.prototype_next_screen)) }
        }
    }
}
