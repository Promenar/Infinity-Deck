package com.promenar.infinity.ui.avatar

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Rotate90DegreesCw
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.ZoomOut
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySlider
import kotlin.math.abs

/** 裁剪圆直径占方形取景区边长的比例。 */
private const val CROP_FRACTION = 0.84f
private val ViewportMaxSide = 400.dp
private val ToolGap = 10.dp

/** 编辑器界面需要的取景数据；预览与截图测试用示例位图构造，无需真实解码。 */
internal class AvatarEditorImage(
    val bitmap: ImageBitmap,
    val geometry: AvatarCropGeometry,
)

internal class AvatarEditorActions(
    val onClose: () -> Unit,
    val onDone: () -> Unit,
    val onChange: ((AvatarCropGeometry, AvatarCropState) -> AvatarCropState) -> Unit,
    val onReplace: () -> Unit,
)

@Composable
internal fun AvatarEditorContent(
    image: AvatarEditorImage?,
    state: AvatarCropState,
    saving: Boolean,
    actions: AvatarEditorActions,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initiallyDragging: Boolean = false,
) {
    val ready = image != null && !saving
    // 用 Surface 提供内容色；只铺背景色时图标与标题沿用默认黑色，深色主题下看不见。
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                EditorTopBar(saving = saving, doneEnabled = ready, onClose = actions.onClose, onDone = actions.onDone)
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                        val side = if (maxWidth < ViewportMaxSide) maxWidth else ViewportMaxSide
                        CropViewport(
                            image = image,
                            state = state,
                            enabled = ready,
                            onChange = actions.onChange,
                            initiallyDragging = initiallyDragging,
                            modifier = Modifier.size(side).align(Alignment.Center),
                        )
                    }
                    Text(
                        text = stringResource(R.string.avatar_editor_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    Spacer(Modifier.size(16.dp))
                    PreviewRow(image = image, state = state)
                    Spacer(Modifier.size(12.dp))
                    ZoomRow(image = image, state = state, enabled = ready, onChange = actions.onChange)
                }
                ToolRow(
                    enabled = ready,
                    onChange = actions.onChange,
                    onReplace = actions.onReplace,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                )
            }
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(bottom = 96.dp),
            )
        }
    }
}

@Composable
private fun EditorTopBar(saving: Boolean, doneEnabled: Boolean, onClose: () -> Unit, onDone: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = 4.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.avatar_editor_close))
        }
        Text(
            text = stringResource(R.string.avatar_editor_title),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 4.dp, end = 12.dp),
        )
        val savingDescription = stringResource(R.string.avatar_editor_saving)
        Button(
            onClick = onDone,
            enabled = doneEnabled,
            modifier = if (saving) Modifier.semantics { stateDescription = savingDescription } else Modifier,
        ) {
            if (saving) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(stringResource(R.string.avatar_editor_done), maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun CropViewport(
    image: AvatarEditorImage?,
    state: AvatarCropState,
    enabled: Boolean,
    onChange: ((AvatarCropGeometry, AvatarCropState) -> AvatarCropState) -> Unit,
    initiallyDragging: Boolean,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(initiallyDragging) }
    val gridAlpha by animateFloatAsState(if (dragging) 1f else 0f, label = "avatarGridAlpha")
    val currentOnChange by rememberUpdatedState(onChange)
    val description = stringResource(R.string.avatar_editor_viewport)
    val backdrop = MaterialTheme.colorScheme.surfaceContainerHighest
    val scrim = MaterialTheme.colorScheme.scrim.copy(alpha = 0.58f)
    val gestures = if (enabled) {
        Modifier
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    dragging = true
                    try {
                        var pastSlop = false
                        var pendingZoom = 1f
                        var pendingPan = Offset.Zero
                        do {
                            val event = awaitPointerEvent()
                            val zoomChange = event.calculateZoom()
                            val panChange = event.calculatePan()
                            if (!pastSlop) {
                                pendingZoom *= zoomChange
                                pendingPan += panChange
                                val zoomMotion = abs(1f - pendingZoom) * event.calculateCentroidSize(useCurrent = false)
                                val touchSlop = viewConfiguration.touchSlop
                                pastSlop = zoomMotion > touchSlop || pendingPan.getDistance() > touchSlop
                            }
                            if (pastSlop) {
                                val cropSide = size.width * CROP_FRACTION
                                val centroid = event.calculateCentroid(useCurrent = false)
                                if (cropSide > 0f && centroid != Offset.Unspecified) {
                                    val focusX = (centroid.x - size.width / 2f) / cropSide
                                    val focusY = (centroid.y - size.height / 2f) / cropSide
                                    currentOnChange { geometry, current ->
                                        geometry.pan(
                                            geometry.zoomBy(current, zoomChange, focusX, focusY),
                                            panChange.x / cropSide,
                                            panChange.y / cropSide,
                                        )
                                    }
                                }
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    } finally {
                        dragging = false
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = { tap ->
                        val cropSide = size.width * CROP_FRACTION
                        if (cropSide > 0f) {
                            currentOnChange { geometry, current ->
                                geometry.toggleZoom(
                                    current,
                                    (tap.x - size.width / 2f) / cropSide,
                                    (tap.y - size.height / 2f) / cropSide,
                                )
                            }
                        }
                    },
                )
            }
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .semantics { contentDescription = description }
            .then(gestures),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(backdrop)
            val cropSide = size.width * CROP_FRACTION
            if (image != null) drawCroppedImage(image, state, cropSide, center)
            val circle = Rect(center, cropSide / 2f)
            val outside = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(Rect(Offset.Zero, size))
                addOval(circle)
            }
            drawPath(outside, scrim)
            drawCircle(Color.White.copy(alpha = 0.9f), radius = cropSide / 2f, style = Stroke(1.5.dp.toPx()))
            if (gridAlpha > 0f) {
                clipPath(Path().apply { addOval(circle) }) {
                    val stroke = 0.75.dp.toPx()
                    val color = Color.White.copy(alpha = 0.45f * gridAlpha)
                    for (fraction in listOf(1f / 3f, 2f / 3f)) {
                        val x = circle.left + circle.width * fraction
                        val y = circle.top + circle.height * fraction
                        drawLine(color, Offset(x, circle.top), Offset(x, circle.bottom), stroke)
                        drawLine(color, Offset(circle.left, y), Offset(circle.right, y), stroke)
                    }
                }
            }
        }
        if (image == null) CircularProgressIndicator()
    }
}

@Composable
private fun PreviewRow(image: AvatarEditorImage?, state: AvatarCropState) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(22.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        AvatarPreview(image, state, 72.dp, stringResource(R.string.avatar_editor_preview_profile))
        AvatarPreview(image, state, 48.dp, stringResource(R.string.avatar_editor_preview_list))
        AvatarPreview(image, state, 28.dp, stringResource(R.string.avatar_editor_preview_chat))
    }
}

@Composable
private fun AvatarPreview(image: AvatarEditorImage?, state: AvatarCropState, size: Dp, label: String) {
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(size).clip(CircleShape)) {
            drawRect(placeholder)
            if (image != null) drawCroppedImage(image, state, this.size.width, center)
        }
        Spacer(Modifier.size(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
private fun ZoomRow(
    image: AvatarEditorImage?,
    state: AvatarCropState,
    enabled: Boolean,
    onChange: ((AvatarCropGeometry, AvatarCropState) -> AvatarCropState) -> Unit,
) {
    val percent = stringResource(R.string.avatar_editor_zoom_percent, AvatarCropGeometry.zoomPercent(state.zoom))
    val sliderDescription = stringResource(R.string.avatar_editor_zoom)
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(
            onClick = { onChange { geometry, current -> geometry.zoomBy(current, 1f / AvatarCropGeometry.ZOOM_STEP) } },
            enabled = enabled && state.zoom > 1f,
        ) {
            Icon(Icons.Rounded.ZoomOut, contentDescription = stringResource(R.string.avatar_editor_zoom_out))
        }
        InfinitySlider(
            value = AvatarCropGeometry.zoomToSlider(state.zoom),
            onValueChange = { position ->
                onChange { geometry, current -> geometry.withZoom(current, AvatarCropGeometry.sliderToZoom(position)) }
            },
            enabled = enabled && image != null,
            modifier = Modifier.weight(1f).semantics {
                contentDescription = sliderDescription
                stateDescription = percent
            },
        )
        IconButton(
            onClick = { onChange { geometry, current -> geometry.zoomBy(current, AvatarCropGeometry.ZOOM_STEP) } },
            enabled = enabled && state.zoom < AvatarCropGeometry.MAX_ZOOM,
        ) {
            Icon(Icons.Rounded.ZoomIn, contentDescription = stringResource(R.string.avatar_editor_zoom_in))
        }
        Text(
            text = percent,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.widthIn(min = 44.dp).clearAndSetSemantics {},
        )
    }
}

private class EditorTool(
    val icon: ImageVector,
    val label: String,
    val description: String,
    val onClick: () -> Unit,
)

/** 四个工具按钮：一行放得下时横排，大字号下放不下则改为两行网格，文字始终单行完整显示。 */
@Composable
private fun ToolRow(
    enabled: Boolean,
    onChange: ((AvatarCropGeometry, AvatarCropState) -> AvatarCropState) -> Unit,
    onReplace: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tools = listOf(
        EditorTool(
            Icons.Rounded.Rotate90DegreesCw,
            stringResource(R.string.avatar_editor_rotate),
            stringResource(R.string.avatar_editor_rotate_description),
        ) { onChange { geometry, current -> geometry.rotateClockwise(current) } },
        EditorTool(
            Icons.Rounded.Flip,
            stringResource(R.string.avatar_editor_mirror),
            stringResource(R.string.avatar_editor_mirror_description),
        ) { onChange { geometry, current -> geometry.mirrorHorizontally(current) } },
        EditorTool(
            Icons.Rounded.RestartAlt,
            stringResource(R.string.avatar_editor_reset),
            stringResource(R.string.avatar_editor_reset_description),
        ) { onChange { geometry, _ -> geometry.initialState() } },
        EditorTool(
            Icons.Rounded.AddPhotoAlternate,
            stringResource(R.string.avatar_editor_replace),
            stringResource(R.string.avatar_editor_replace_description),
            onReplace,
        ),
    )
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widestLabel = with(density) {
            tools.maxOf { measurer.measure(it.label, labelStyle, maxLines = 1, softWrap = false).size.width }.toDp()
        }
        val singleRowCell = (maxWidth - ToolGap * 3) / 4
        if (widestLabel + 16.dp <= singleRowCell) {
            Row(horizontalArrangement = Arrangement.spacedBy(ToolGap)) {
                tools.forEach { ToolButton(it, enabled, stacked = true, modifier = Modifier.weight(1f)) }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(ToolGap)) {
                tools.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(ToolGap)) {
                        pair.forEach { ToolButton(it, enabled, stacked = false, modifier = Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolButton(tool: EditorTool, enabled: Boolean, stacked: Boolean, modifier: Modifier = Modifier) {
    val content = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    val buttonModifier = modifier
        .heightIn(min = if (stacked) 64.dp else 52.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(enabled = enabled, onClick = tool.onClick)
        .semantics(mergeDescendants = true) {
            role = Role.Button
            contentDescription = tool.description
        }
        .padding(horizontal = 8.dp, vertical = 10.dp)
    val label: @Composable () -> Unit = {
        Text(
            text = tool.label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            softWrap = false,
        )
    }
    if (stacked) {
        Column(buttonModifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(tool.icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(4.dp))
            label()
        }
    } else {
        Row(buttonModifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(tool.icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            label()
        }
    }
}

/** 按取景状态把显示位图画到画布上：裁剪方形边长为 [cropSide]，中心在 [cropCenter]。 */
private fun DrawScope.drawCroppedImage(image: AvatarEditorImage, state: AvatarCropState, cropSide: Float, cropCenter: Offset) {
    val geometry = image.geometry
    val bitmap = image.bitmap
    val transform = AvatarAffine.scaleTranslate(
        scaleX = geometry.rawWidth.toFloat() / bitmap.width,
        scaleY = geometry.rawHeight.toFloat() / bitmap.height,
        translateX = 0f,
        translateY = 0f,
    ).then(geometry.transform(state, cropSide, cropCenter.x, cropCenter.y))
    val matrix = android.graphics.Matrix().apply { setValues(transform.toMatrixValues()) }
    drawIntoCanvas { canvas ->
        canvas.nativeCanvas.drawBitmap(bitmap.asAndroidBitmap(), matrix, BitmapPaint)
    }
}

private val BitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
