package com.promenar.infinity.ui.annotation

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.media.ImageAnnotationKind
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityPureDarkTheme

/** 截图只消费画板状态：用渐变位图代替照片，不读文件、不导出。 */
@Composable
private fun EditorPreview(board: AnnotationBoard, withImage: Boolean, setup: AnnotationEditorState.() -> Unit = {}) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        InfinityPureDarkTheme {
            val image = remember(withImage, board.width, board.height) {
                if (!withImage) null else Bitmap.createBitmap(board.width, board.height, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val paint = Paint().apply {
                        shader = LinearGradient(0f, 0f, board.width.toFloat(), board.height.toFloat(),
                            0xFF5B7DB1.toInt(), 0xFFE8C9A0.toInt(), Shader.TileMode.CLAMP)
                    }
                    Canvas(bitmap).drawRect(0f, 0f, board.width.toFloat(), board.height.toFloat(), paint)
                }?.asImageBitmap()
            }
            val state = remember(board) { AnnotationEditorState(board).apply(setup) }
            AnnotationEditorContent(state = state, image = image, onClose = {}, onDone = {})
        }
    }
}

private val red = MarkupColors.first()

private val MarkupBoard = AnnotationBoard.blank(ImageAnnotationKind.MARKUP, 1200, 900)
    .add(BoardOp.Box(red, 12f, BoardPoint(380f, 260f), BoardPoint(820f, 640f)))
    .add(BoardOp.Line(red, 12f, BoardPoint(200f, 760f), BoardPoint(360f, 620f), arrow = true))
    .add(BoardOp.Label(red, 52f, BoardPoint(600f, 200f), "换成红色杯子", outline = true))

private val MaskBoard = AnnotationBoard.blank(ImageAnnotationKind.MARKUP, 1200, 900)
    .add(BoardOp.Box(red, 12f, BoardPoint(100f, 100f), BoardPoint(300f, 300f)))
    .switchTo(ImageAnnotationKind.MASK)
    .add(BoardOp.MaskStroke(90f, listOf(BoardPoint(450f, 400f), BoardPoint(600f, 470f), BoardPoint(760f, 420f)), erase = false))

private val SketchBoard = AnnotationBoard.blank(ImageAnnotationKind.SKETCH, 1024, 1536)
    .add(BoardOp.Line(SketchColors.first(), 11f, BoardPoint(0f, 1000f), BoardPoint(1024f, 1000f), arrow = false))
    .add(BoardOp.Box(SketchColors.first(), 11f, BoardPoint(360f, 520f), BoardPoint(660f, 1000f)))
    .add(BoardOp.Label(SketchColors.first(), 51f, BoardPoint(510f, 460f), "小屋", outline = false))

@PreviewTest
@Preview(name = "画板 标注 中文", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun AnnotationEditorMarkupZhPreview() = EditorPreview(MarkupBoard, withImage = true)

@PreviewTest
@Preview(name = "画板 标注 英文 窄屏大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun AnnotationEditorMarkupEnLargePreview() = EditorPreview(MarkupBoard, withImage = true)

@PreviewTest
@Preview(name = "画板 选区 冲突提示 中文", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun AnnotationEditorMaskZhPreview() = EditorPreview(MaskBoard, withImage = true)

@PreviewTest
@Preview(name = "画板 草图 文字输入 中文", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun AnnotationEditorSketchTextZhPreview() = EditorPreview(SketchBoard, withImage = false) {
    selectTool(BoardTool.TEXT)
    textAnchor = BoardPoint(500f, 300f)
}

@PreviewTest
@Preview(name = "画板 草图 英文 窄屏大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun AnnotationEditorSketchEnLargePreview() = EditorPreview(SketchBoard, withImage = false)
