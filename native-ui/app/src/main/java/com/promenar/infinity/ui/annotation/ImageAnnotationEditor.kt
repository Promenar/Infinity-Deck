package com.promenar.infinity.ui.annotation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.FormatColorReset
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.media.ImageAnnotation
import com.promenar.infinity.domain.media.ImageAnnotationExport
import com.promenar.infinity.domain.media.ImageAnnotationKind
import com.promenar.infinity.domain.media.ImageAnnotationSource
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityFullscreenDialog
import com.promenar.infinity.ui.common.InfinityLoadingIndicator
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.theme.InfinityPureDarkTheme
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.testing.UiTags
import java.nio.ByteBuffer
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 查看器里“标注”按钮的入口，参数为当前会话工作区内的图片路径；只在对话页提供。 */
val LocalImageAnnotationLauncher = androidx.compose.runtime.staticCompositionLocalOf<((String) -> Unit)?> { null }

/** 打开画板的对象。 */
sealed interface AnnotationTarget {
    /** 当前会话工作区里的图片（生成结果或已入库的原图）；[initial] 非空时恢复上次的笔画。 */
    data class WorkspaceImage(val path: String, val initial: ImageAnnotation? = null) : AnnotationTarget

    /** 输入框里尚未入工作区的图片。 */
    data class ComposerImage(val uri: Uri) : AnnotationTarget

    data class Sketch(val initial: ImageAnnotation? = null) : AnnotationTarget
}

@kotlinx.serialization.Serializable
private data class SavedAnnotationTarget(val type: String, val value: String? = null, val initial: ImageAnnotation? = null)

/** 画板对象随界面重建（折叠屏开合、深浅色切换）保存与恢复。 */
val AnnotationTargetSaver = androidx.compose.runtime.saveable.Saver<AnnotationTarget?, String>(
    save = { target ->
        val saved = when (target) {
            null -> null
            is AnnotationTarget.WorkspaceImage -> SavedAnnotationTarget("workspace", target.path, target.initial)
            is AnnotationTarget.ComposerImage -> SavedAnnotationTarget("composer", target.uri.toString())
            is AnnotationTarget.Sketch -> SavedAnnotationTarget("sketch", initial = target.initial)
        }
        saved?.let { BoardJson.encodeToString(SavedAnnotationTarget.serializer(), it) }
    },
    restore = { encoded ->
        runCatching { BoardJson.decodeFromString(SavedAnnotationTarget.serializer(), encoded) }.getOrNull()?.let { saved ->
            when (saved.type) {
                "workspace" -> saved.value?.let { AnnotationTarget.WorkspaceImage(it, saved.initial) }
                "composer" -> saved.value?.let { AnnotationTarget.ComposerImage(Uri.parse(it)) }
                "sketch" -> AnnotationTarget.Sketch(saved.initial)
                else -> null
            }
        }
    },
)

/** 界面重建时保存的画板快照上限，避免超出 Bundle 容量。 */
private const val SavedBoardMaxChars = 200_000

/** 输入框原图解码上限：长边 2048，兼顾标注精度与内存。 */
private const val ComposerSourceMaxEdge = 2048

/** 工作区原图必须按原尺寸打开（图层尺寸要与之一致），超过该像素数时拒绝。 */
private const val WorkspaceSourceMaxPixels = 40_000_000L

private class BoardImageTooLarge : IllegalStateException()

private sealed interface SourceState {
    data object Loading : SourceState
    class Ready(val bitmap: Bitmap?, val board: AnnotationBoard) : SourceState
    class Failed(val messageRes: Int) : SourceState
}

/**
 * 全屏画板：在图片上标注或涂抹选区，或在空白画布上手绘草图。[onExport] 抛出的异常显示在提示条里，画板保持打开；
 * 成功后由调用方关闭画板。
 */
@Composable
fun ImageAnnotationEditor(
    target: AnnotationTarget,
    readWorkspaceImage: suspend (String) -> ByteArray,
    onExport: suspend (ImageAnnotationExport) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var savedBoard by androidx.compose.runtime.saveable.rememberSaveable(target) { mutableStateOf<String?>(null) }
    val source by produceState<SourceState>(SourceState.Loading, target) {
        val restored = AnnotationBoard.parse(savedBoard)
        value = try {
            withContext(Dispatchers.IO) {
                when (target) {
                    is AnnotationTarget.Sketch -> {
                        val initial = target.initial
                        val size = SketchCanvasSizes[1]
                        SourceState.Ready(null, restored?.takeIf { it.kind == ImageAnnotationKind.SKETCH }
                            ?: AnnotationBoard.restore(initial?.editorState, ImageAnnotationKind.SKETCH,
                                initial?.width ?: size.width, initial?.height ?: size.height))
                    }
                    is AnnotationTarget.WorkspaceImage -> {
                        val bytes = readWorkspaceImage(target.path)
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        if (bounds.outWidth.toLong() * bounds.outHeight > WorkspaceSourceMaxPixels) throw BoardImageTooLarge()
                        val bitmap = decodeSoftware(ImageDecoder.createSource(ByteBuffer.wrap(bytes)), maxEdge = null)
                        val kind = target.initial?.kind?.takeIf { it != ImageAnnotationKind.SKETCH } ?: ImageAnnotationKind.MARKUP
                        SourceState.Ready(bitmap, restored.matching(bitmap)
                            ?: AnnotationBoard.restore(target.initial?.editorState, kind, bitmap.width, bitmap.height))
                    }
                    is AnnotationTarget.ComposerImage -> {
                        val bitmap = decodeSoftware(ImageDecoder.createSource(context.contentResolver, target.uri), ComposerSourceMaxEdge)
                        SourceState.Ready(bitmap, restored.matching(bitmap)
                            ?: AnnotationBoard.blank(ImageAnnotationKind.MARKUP, bitmap.width, bitmap.height))
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: BoardImageTooLarge) {
            SourceState.Failed(R.string.annotation_too_large)
        } catch (_: Exception) {
            SourceState.Failed(R.string.annotation_load_failed)
        }
    }

    InfinityPureDarkTheme {
        val barColor = MaterialTheme.colorScheme.surfaceContainer
        var confirmDiscard by remember { mutableStateOf(false) }
        val ready = source as? SourceState.Ready
        val state = remember(ready) { ready?.let { AnnotationEditorState(it.board) } }
        LaunchedEffect(state) {
            val editor = state ?: return@LaunchedEffect
            androidx.compose.runtime.snapshotFlow { editor.board }.collect { board ->
                savedBoard = board.snapshot().takeIf { it.length <= SavedBoardMaxChars }
            }
        }
        val requestClose = { if (state?.board?.hasAnyContent == true) confirmDiscard = true else onDismiss() }
        InfinityFullscreenDialog(onDismissRequest = requestClose, systemBarColor = barColor) {
            when (val current = source) {
                SourceState.Loading -> EditorMessage(stringResource(R.string.annotation_loading), loading = true, onClose = onDismiss)
                is SourceState.Failed -> EditorMessage(stringResource(current.messageRes), loading = false, onClose = onDismiss)
                is SourceState.Ready -> {
                    val image = remember(current.bitmap) { current.bitmap?.asImageBitmap() }
                    val scope = rememberCoroutineScope()
                    AnnotationEditorContent(
                        state = requireNotNull(state),
                        image = image,
                        onClose = requestClose,
                        onDone = {
                            val editor = requireNotNull(state)
                            if (editor.board.isEmpty) {
                                editor.notice = EditorNotice.Empty
                            } else if (!editor.busy) {
                                editor.busy = true
                                scope.launch {
                                    try {
                                        onExport(exportBoard(editor.board, current.bitmap, target))
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (failure: Exception) {
                                        editor.notice = EditorNotice.Failed(failure.message ?: failure.javaClass.simpleName)
                                    } finally {
                                        editor.busy = false
                                    }
                                }
                            }
                        },
                    )
                }
            }
            if (confirmDiscard) {
                InfinityConfirmDialog(
                    title = stringResource(R.string.annotation_discard_title),
                    message = stringResource(R.string.annotation_discard_message),
                    confirmText = stringResource(R.string.annotation_discard_confirm),
                    cancelText = stringResource(R.string.annotation_discard_cancel),
                    isDestructive = true,
                    onConfirm = { confirmDiscard = false; onDismiss() },
                    onCancel = { confirmDiscard = false },
                )
            }
        }
    }
}

private fun AnnotationBoard?.matching(bitmap: Bitmap): AnnotationBoard? =
    this?.takeIf { it.kind != ImageAnnotationKind.SKETCH && it.width == bitmap.width && it.height == bitmap.height }

private fun decodeSoftware(source: ImageDecoder.Source, maxEdge: Int?): Bitmap =
    ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val width = info.size.width
        val height = info.size.height
        if (maxEdge != null && max(width, height) > maxEdge) {
            val scale = maxEdge.toFloat() / max(width, height)
            decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        }
    }

/** 按原始像素重绘图层与预览，在后台线程用独立的画笔完成。 */
private suspend fun exportBoard(board: AnnotationBoard, bitmap: Bitmap?, target: AnnotationTarget): ImageAnnotationExport =
    withContext(Dispatchers.Default) {
        val renderer = AnnotationRenderer()
        val layer = renderer.exportLayer(board.kind, board.ops, board.width, board.height)
        val preview = renderer.exportPreview(board.kind, bitmap, board.ops, board.width, board.height)
        try {
            ImageAnnotationExport(
                kind = board.kind,
                width = board.width,
                height = board.height,
                layerPng = layer.encodePng(),
                previewPng = preview.encodePng(),
                source = when (target) {
                    is AnnotationTarget.WorkspaceImage -> ImageAnnotationSource.Workspace(target.initial?.sourcePath ?: target.path)
                    is AnnotationTarget.ComposerImage -> ImageAnnotationSource.Bytes(requireNotNull(bitmap).encodePng())
                    is AnnotationTarget.Sketch -> ImageAnnotationSource.None
                },
                editorState = board.snapshot(),
            )
        } finally {
            layer.recycle()
            preview.recycle()
        }
    }

internal sealed interface EditorNotice {
    data object Empty : EditorNotice
    data class Resize(val size: SketchCanvasSize) : EditorNotice
    data class Failed(val message: String) : EditorNotice
}

/** 画板界面状态：画板内容、各画法的工具与颜色、正在画的一笔、视图缩放平移。 */
internal class AnnotationEditorState(initial: AnnotationBoard) {
    var board by mutableStateOf(initial)
    var draft by mutableStateOf<BoardOp?>(null)
    var notice by mutableStateOf<EditorNotice?>(null)
    var busy by mutableStateOf(false)
    var textAnchor by mutableStateOf<BoardPoint?>(null)
    val tools = mutableStateMapOf<ImageAnnotationKind, BoardTool>()
    val colors = mutableStateMapOf<ImageAnnotationKind, Int>()
    val sizes = mutableStateMapOf<ImageAnnotationKind, Int>()

    var stageSize by mutableStateOf(IntSize.Zero)
        private set
    private var stagePadding = 0f
    private var stageBottomReserve = 0f
    var zoom by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    private var eraseFrom: List<BoardOp>? = null

    val kind get() = board.kind
    val tool get() = tools[kind] ?: boardTools(kind).first()
    val palette get() = if (kind == ImageAnnotationKind.SKETCH) SketchColors else MarkupColors
    val color get() = colors[kind] ?: palette.first()
    val sizeLevel get() = sizes[kind] ?: 1
    private val baseScale: Float
        get() = if (stageSize == IntSize.Zero) 1f else
            min((stageSize.width - stagePadding * 2) / board.width,
                (stageSize.height - stagePadding * 2 - stageBottomReserve) / board.height).coerceAtLeast(0.01f)
    val scale get() = baseScale * zoom

    /** [bottomReserve] 为底部悬浮的撤销条留出的高度，未缩放时画布不被遮挡。 */
    fun updateStage(size: IntSize, padding: Float, bottomReserve: Float) {
        if (size == stageSize && padding == stagePadding && bottomReserve == stageBottomReserve) return
        stageSize = size
        stagePadding = padding
        stageBottomReserve = bottomReserve
        resetView()
    }

    fun resetView() {
        zoom = 1f
        offset = Offset((stageSize.width - board.width * baseScale) / 2, (stageSize.height - stageBottomReserve - board.height * baseScale) / 2)
    }

    fun transformBy(zoomChange: Float, pan: Offset, centroid: Offset) {
        val next = (zoom * zoomChange).coerceIn(1f, 6f)
        val factor = next / zoom
        offset = centroid - (centroid - offset) * factor + pan
        zoom = next
        if (zoom <= 1.01f) resetView()
    }

    fun toBoard(position: Offset) = BoardPoint((position.x - offset.x) / scale, (position.y - offset.y) / scale)

    private fun inside(p: BoardPoint) = p.x in 0f..board.width.toFloat() && p.y in 0f..board.height.toFloat()
    private fun clamp(p: BoardPoint) = BoardPoint(p.x.coerceIn(0f, board.width.toFloat()), p.y.coerceIn(0f, board.height.toFloat()))

    fun switchKind(target: ImageAnnotationKind) {
        board = board.switchTo(target)
        notice = null
        textAnchor = null
    }

    fun selectTool(next: BoardTool) {
        tools[kind] = next
        if (next != BoardTool.TEXT) textAnchor = null
    }

    fun cycleSize() { sizes[kind] = (sizeLevel + 1) % 3 }

    fun requestSketchSize(size: SketchCanvasSize) {
        if (size.width == board.width && size.height == board.height) return
        if (board.isEmpty) applySketchSize(size) else notice = EditorNotice.Resize(size)
    }

    fun applySketchSize(size: SketchCanvasSize) {
        board = board.resizeSketch(size, discardContent = true)
        notice = null
        resetView()
    }

    fun undo() { board = board.undo() }
    fun redo() { board = board.redo() }
    fun clear() { board = board.clear() }

    fun beginStroke(position: Offset, measurer: LabelMeasurer) {
        val point = toBoard(position)
        if (!inside(point)) return
        notice = (notice as? EditorNotice.Failed)
        when (val current = tool) {
            BoardTool.TEXT -> textAnchor = point
            BoardTool.ERASER -> { eraseFrom = board.ops; eraseAt(point, measurer) }
            BoardTool.BRUSH, BoardTool.UNBRUSH ->
                draft = BoardOp.MaskStroke(board.brushWidth(sizeLevel), listOf(point), erase = current == BoardTool.UNBRUSH)
            BoardTool.PEN -> draft = BoardOp.Pen(color, board.strokeWidth(sizeLevel), listOf(point))
            BoardTool.ARROW, BoardTool.LINE ->
                draft = BoardOp.Line(color, board.strokeWidth(sizeLevel), point, point, arrow = current == BoardTool.ARROW)
            BoardTool.RECT -> draft = BoardOp.Box(color, board.strokeWidth(sizeLevel), point, point)
        }
    }

    fun extendStroke(position: Offset, measurer: LabelMeasurer) {
        val point = clamp(toBoard(position))
        if (eraseFrom != null) { eraseAt(point, measurer); return }
        val minStep = min(board.width, board.height) * 0.002f
        draft = when (val current = draft) {
            is BoardOp.Pen -> current.points.last().let { last ->
                if (hypot(point.x - last.x, point.y - last.y) < minStep) current else current.copy(points = current.points + point)
            }
            is BoardOp.MaskStroke -> current.points.last().let { last ->
                if (hypot(point.x - last.x, point.y - last.y) < minStep) current else current.copy(points = current.points + point)
            }
            is BoardOp.Line -> current.copy(end = point)
            is BoardOp.Box -> current.copy(end = point)
            else -> current
        }
    }

    fun endStroke() {
        eraseFrom?.let { before ->
            if (board.ops !== before) board = board.replace(board.ops, recordFrom = before)
            eraseFrom = null
            return
        }
        val finished = draft ?: return
        draft = null
        val negligible = when (finished) {
            is BoardOp.Line -> isNegligibleDrag(finished.start, finished.end, board)
            is BoardOp.Box -> isNegligibleDrag(finished.start, finished.end, board)
            else -> false
        }
        if (!negligible) board = board.add(finished)
    }

    /** 第二根手指落下时放弃当前这一笔，转为缩放平移。 */
    fun cancelStroke() {
        eraseFrom?.let { board = board.replace(it, recordFrom = null) }
        eraseFrom = null
        draft = null
    }

    fun placeText(text: String) {
        val anchor = textAnchor ?: return
        textAnchor = null
        val content = text.trim()
        if (content.isEmpty()) return
        board = board.add(BoardOp.Label(color, board.textSize(sizeLevel), anchor, content, outline = kind == ImageAnnotationKind.MARKUP))
    }

    private fun eraseAt(point: BoardPoint, measurer: LabelMeasurer) {
        val remaining = board.eraseAt(point, measurer)
        if (remaining !== board.ops) board = board.replace(remaining, recordFrom = null)
    }
}

@Composable
private fun EditorMessage(text: String, loading: Boolean, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).statusBarsPadding()) {
        IconButton(onClick = onClose, modifier = Modifier.padding(4.dp)) {
            Icon(Icons.Rounded.Close, stringResource(R.string.annotation_cd_close), tint = MaterialTheme.colorScheme.onSurface)
        }
        Column(Modifier.align(Alignment.Center).padding(InfinitySpacing.XLarge), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            if (loading) InfinityLoadingIndicator()
            Text(text, style = InfinityTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun AnnotationEditorContent(
    state: AnnotationEditorState,
    image: ImageBitmap?,
    onClose: () -> Unit,
    onDone: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val renderer = remember { AnnotationRenderer() }
    val density = LocalDensity.current
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurface) {
    Column(
        Modifier.fillMaxSize().background(colors.surfaceContainerLowest).imePadding().testTag(UiTags.ANNOTATION_EDITOR),
    ) {
        EditorTopBar(state, onClose, onDone)
        NoticeBar(state)
        Box(
            Modifier.weight(1f).fillMaxWidth().clipToBounds()
                .onSizeChanged { state.updateStage(it, with(density) { 14.dp.toPx() }, with(density) { 58.dp.toPx() }) }
                .pointerInput(state) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        state.beginStroke(down.position, renderer.measurer)
                        var transforming = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            if (pressed.size >= 2) {
                                if (!transforming) { state.cancelStroke(); transforming = true }
                                state.transformBy(event.calculateZoom(), event.calculatePan(), event.calculateCentroid())
                            } else if (!transforming) {
                                state.extendStroke(pressed.first().position, renderer.measurer)
                            }
                            event.changes.forEach { it.consume() }
                        }
                        if (!transforming) state.endStroke()
                    }
                }
                .testTag(UiTags.ANNOTATION_STAGE),
        ) {
            val outline = colors.outlineVariant
            Canvas(Modifier.fillMaxSize()) {
                val board = state.board
                val scale = state.scale
                val origin = state.offset
                val drawnSize = Size(board.width * scale, board.height * scale)
                if (image != null) {
                    drawImage(
                        image,
                        dstOffset = IntOffset(origin.x.roundToInt(), origin.y.roundToInt()),
                        dstSize = IntSize(drawnSize.width.roundToInt(), drawnSize.height.roundToInt()),
                        filterQuality = FilterQuality.Medium,
                    )
                }
                drawRect(outline, origin, drawnSize, style = Stroke(1.dp.toPx()))
                val ops = state.draft?.let { board.ops + it } ?: board.ops
                drawIntoCanvas { canvas ->
                    val native = canvas.nativeCanvas
                    val saved = native.save()
                    native.translate(origin.x, origin.y)
                    native.clipRect(0f, 0f, drawnSize.width, drawnSize.height)
                    if (image == null) renderer.drawPaper(native)
                    if (board.kind == ImageAnnotationKind.MASK) renderer.drawMask(native, ops, scale, MaskTint, 128)
                    else renderer.drawOps(native, ops, scale)
                    native.restoreToCount(saved)
                }
            }
            Text(
                "${state.board.width}×${state.board.height}",
                style = InfinityTypography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.align(Alignment.BottomEnd).padding(horizontal = 14.dp, vertical = 22.dp)
                    .background(colors.surfaceContainerHigh.copy(alpha = 0.85f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            if (state.zoom > 1.01f) {
                val resetDescription = stringResource(R.string.annotation_cd_reset_zoom)
                TextButton(onClick = state::resetView, modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                    .semantics { contentDescription = resetDescription }) {
                    Text("${(state.zoom * 100).roundToInt()}%", style = InfinityTypography.labelMedium)
                }
            }
            Surface(
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
                shape = CircleShape,
                color = colors.surfaceContainerHigh.copy(alpha = 0.92f),
                contentColor = colors.onSurface,
            ) {
                Row {
                    IconButton(onClick = state::undo, enabled = state.board.canUndo) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, stringResource(R.string.annotation_cd_undo))
                    }
                    IconButton(onClick = state::redo, enabled = state.board.canRedo) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, stringResource(R.string.annotation_cd_redo))
                    }
                }
            }
        }
        if (state.textAnchor != null) TextBar(state)
        BottomPanel(state)
    }
    }
}

@Composable
private fun EditorTopBar(state: AnnotationEditorState, onClose: () -> Unit, onDone: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding()
            .height(56.dp).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, stringResource(R.string.annotation_cd_close)) }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (state.kind == ImageAnnotationKind.SKETCH) {
                val selected = SketchCanvasSizes.firstOrNull { it.width == state.board.width && it.height == state.board.height }
                InfinitySegmentedControl(
                    segments = SketchCanvasSizes.map { InfinitySegment(it, it.label) },
                    selected = selected,
                    onSelect = state::requestSketchSize,
                )
            } else {
                InfinitySegmentedControl(
                    segments = listOf(
                        InfinitySegment(ImageAnnotationKind.MARKUP, stringResource(R.string.annotation_kind_markup)),
                        InfinitySegment(ImageAnnotationKind.MASK, stringResource(R.string.annotation_kind_mask)),
                    ),
                    selected = state.kind,
                    onSelect = state::switchKind,
                )
            }
        }
        InfinityPillButton(
            text = stringResource(R.string.annotation_done),
            onClick = onDone,
            tone = InfinityPillTone.PRIMARY,
            enabled = !state.busy,
            compact = true,
            modifier = Modifier.padding(end = 4.dp).testTag(UiTags.ANNOTATION_DONE),
        )
    }
}

@Composable
private fun NoticeBar(state: AnnotationEditorState) {
    val notice = state.notice
    val conflictName = stringResource(if (state.kind == ImageAnnotationKind.MASK) R.string.annotation_kind_mask else R.string.annotation_kind_markup)
    val text = when (notice) {
        EditorNotice.Empty -> stringResource(R.string.annotation_notice_empty)
        is EditorNotice.Resize -> stringResource(R.string.annotation_notice_resize)
        is EditorNotice.Failed -> stringResource(R.string.annotation_notice_failed, notice.message)
        null -> if (state.board.hasConflictingLayer) stringResource(R.string.annotation_notice_conflict, conflictName) else return
    }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = InfinitySpacing.Medium, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Text(text, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
        if (notice is EditorNotice.Resize) {
            TextButton(onClick = { state.applySketchSize(notice.size) }) {
                Text(stringResource(R.string.annotation_notice_resize_action), maxLines = 1)
            }
        }
    }
}

@Composable
private fun TextBar(state: AnnotationEditorState) {
    var text by remember(state.textAnchor) { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(state.textAnchor) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = InfinitySpacing.Medium, vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.annotation_text_placeholder)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { state.placeText(text) }),
            textStyle = InfinityTypography.bodyLarge.forInput(),
            modifier = Modifier.weight(1f).focusRequester(focus).testTag(UiTags.ANNOTATION_TEXT_INPUT),
        )
        InfinityPillButton(text = stringResource(R.string.annotation_text_place), onClick = { state.placeText(text) }, tone = InfinityPillTone.PRIMARY)
    }
}

@Composable
private fun BottomPanel(state: AnnotationEditorState) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().background(colors.surfaceContainer).navigationBarsPadding()
            .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            boardTools(state.kind).forEach { tool ->
                val selected = state.tool == tool
                Column(
                    Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (selected) colors.secondaryContainer else Color.Transparent)
                        .selectable(selected = selected, role = Role.RadioButton) { state.selectTool(tool) }
                        .testTag(UiTags.annotationTool(tool.name)),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    val tint = if (selected) colors.onSecondaryContainer else colors.onSurfaceVariant
                    Icon(toolIcon(tool), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(stringResource(toolLabel(tool)), style = InfinityTypography.labelSmall, color = tint, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.kind == ImageAnnotationKind.MASK) {
                InfinityPillButton(
                    text = stringResource(R.string.annotation_tool_clear),
                    onClick = state::clear,
                    enabled = !state.board.isEmpty,
                    compact = true,
                )
            } else {
                state.palette.forEach { argb ->
                    val selected = state.color == argb
                    val name = stringResource(colorName(argb))
                    Box(
                        Modifier.size(48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { state.colors[state.kind] = argb }
                            .semantics { contentDescription = name },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier.size(30.dp).clip(CircleShape).background(Color(argb))
                                .border(if (selected) 2.5.dp else 1.dp, if (selected) colors.onSurface else colors.outline, CircleShape),
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            val sizeLabel = stringResource(
                when (state.sizeLevel) { 0 -> R.string.annotation_size_small; 2 -> R.string.annotation_size_large; else -> R.string.annotation_size_medium },
            )
            val sizeDescription = stringResource(R.string.annotation_cd_size, sizeLabel)
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button) { state.cycleSize() }
                    .semantics { contentDescription = sizeDescription },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(36.dp).border(1.dp, colors.outline, CircleShape), contentAlignment = Alignment.Center) {
                    Text(sizeLabel, style = InfinityTypography.labelMedium, color = colors.onSurface, maxLines = 1)
                }
            }
        }
        Text(
            stringResource(
                when (state.kind) {
                    ImageAnnotationKind.MARKUP -> R.string.annotation_hint_markup
                    ImageAnnotationKind.MASK -> R.string.annotation_hint_mask
                    ImageAnnotationKind.SKETCH -> R.string.annotation_hint_sketch
                },
            ),
            style = InfinityTypography.bodySmall,
            color = colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Small),
        )
    }
}

private fun toolLabel(tool: BoardTool) = when (tool) {
    BoardTool.PEN -> R.string.annotation_tool_pen
    BoardTool.ARROW -> R.string.annotation_tool_arrow
    BoardTool.LINE -> R.string.annotation_tool_line
    BoardTool.RECT -> R.string.annotation_tool_rect
    BoardTool.TEXT -> R.string.annotation_tool_text
    BoardTool.ERASER -> R.string.annotation_tool_eraser
    BoardTool.BRUSH -> R.string.annotation_tool_brush
    BoardTool.UNBRUSH -> R.string.annotation_tool_unbrush
}

private fun toolIcon(tool: BoardTool): ImageVector = when (tool) {
    BoardTool.PEN -> Icons.Rounded.Draw
    BoardTool.ARROW -> Icons.Rounded.NorthEast
    BoardTool.LINE -> Icons.Rounded.HorizontalRule
    BoardTool.RECT -> Icons.Rounded.CropSquare
    BoardTool.TEXT -> Icons.Rounded.TextFields
    BoardTool.ERASER -> EraserIcon
    BoardTool.BRUSH -> Icons.Rounded.Brush
    BoardTool.UNBRUSH -> Icons.Rounded.FormatColorReset
}

private fun colorName(argb: Int) = when (argb) {
    0xFFFF3B30.toInt() -> R.string.annotation_color_red
    0xFFFFCC00.toInt() -> R.string.annotation_color_yellow
    0xFF34C759.toInt() -> R.string.annotation_color_green
    0xFF0A84FF.toInt() -> R.string.annotation_color_blue
    0xFFFFFFFF.toInt() -> R.string.annotation_color_white
    0xFF1C1C1E.toInt() -> R.string.annotation_color_black
    0xFF8E8E93.toInt() -> R.string.annotation_color_gray
    else -> R.string.annotation_color_orange
}

/** Material 图标库没有橡皮，按 24dp 网格画一个实心轮廓（奇偶填充挖出内部），由 Icon 着色。 */
private val EraserIcon: ImageVector by lazy {
    materialIcon(name = "Rounded.Eraser") {
        materialPath(pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd) {
            moveTo(11.4f, 4.2f); lineTo(19.8f, 12.6f); lineTo(13.2f, 19.2f); lineTo(7.4f, 19.2f); lineTo(2.2f, 14f); close()
            moveTo(11.4f, 6.75f); lineTo(17.25f, 12.6f); lineTo(12.45f, 17.4f); lineTo(8.15f, 17.4f); lineTo(4.75f, 14f); close()
            moveTo(7.3f, 10.85f); lineTo(8.55f, 9.6f); lineTo(14.4f, 15.45f); lineTo(13.15f, 16.7f); close()
            moveTo(7.4f, 19.2f); lineTo(21f, 19.2f); lineTo(21f, 21f); lineTo(7.4f, 21f); close()
        }
    }
}
