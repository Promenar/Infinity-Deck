package com.promenar.infinity.ui.annotation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.media.ImageAnnotationKind
import org.junit.Test

class AnnotationBoardTest {
    private val measurer = LabelMeasurer { text, size -> text.length * size }
    private val red = MarkupColors.first()

    private fun pen(vararg xy: Float) = BoardOp.Pen(red, 10f, xy.toList().chunked(2) { BoardPoint(it[0], it[1]) })

    @Test
    fun `撤销与重做按画法分开且新笔画清空重做栈`() {
        val board = AnnotationBoard.blank(ImageAnnotationKind.MARKUP, 1000, 1000)
            .add(pen(10f, 10f, 20f, 20f))
            .add(pen(100f, 100f, 200f, 200f))
        val undone = board.undo()
        assertThat(undone.ops).hasSize(1)
        assertThat(undone.canRedo).isTrue()
        assertThat(undone.redo().ops).isEqualTo(board.ops)
        val branched = undone.add(pen(300f, 300f))
        assertThat(branched.canRedo).isFalse()

        val mask = branched.switchTo(ImageAnnotationKind.MASK)
        assertThat(mask.ops).isEmpty()
        assertThat(mask.canUndo).isFalse()
        assertThat(mask.switchTo(ImageAnnotationKind.MARKUP).ops).hasSize(2)
    }

    @Test
    fun `另一种画法有内容时提示只提交当前画法`() {
        val board = AnnotationBoard.blank(ImageAnnotationKind.MARKUP, 800, 600).add(pen(1f, 1f))
        assertThat(board.hasConflictingLayer).isFalse()
        val mask = board.switchTo(ImageAnnotationKind.MASK)
        assertThat(mask.hasConflictingLayer).isTrue()
        assertThat(mask.add(BoardOp.MaskStroke(40f, listOf(BoardPoint(5f, 5f)), erase = false)).switchTo(ImageAnnotationKind.MARKUP).hasConflictingLayer).isTrue()
    }

    @Test
    fun `草图画板不能切到标注或选区`() {
        val sketch = AnnotationBoard.blank(ImageAnnotationKind.SKETCH, 1024, 1536)
        assertThat(sketch.switchTo(ImageAnnotationKind.MARKUP).kind).isEqualTo(ImageAnnotationKind.SKETCH)
        assertThat(sketch.hasConflictingLayer).isFalse()
    }

    @Test
    fun `橡皮按整笔删除被碰到的内容`() {
        val board = AnnotationBoard.blank(ImageAnnotationKind.MARKUP, 1000, 1000)
            .add(pen(0f, 0f, 100f, 0f))
            .add(BoardOp.Line(red, 8f, BoardPoint(0f, 500f), BoardPoint(500f, 500f), arrow = true))
            .add(BoardOp.Box(red, 8f, BoardPoint(600f, 600f), BoardPoint(900f, 900f)))
            .add(BoardOp.Label(red, 40f, BoardPoint(300f, 300f), "换色", outline = true))

        assertThat(board.eraseAt(BoardPoint(50f, 4f), measurer)).hasSize(3)
        assertThat(board.eraseAt(BoardPoint(250f, 503f), measurer).none { it is BoardOp.Line }).isTrue()
        // 方框只在边线上命中，框内空白不删除。
        assertThat(board.eraseAt(BoardPoint(750f, 750f), measurer)).isSameInstanceAs(board.ops)
        assertThat(board.eraseAt(BoardPoint(900f, 750f), measurer).none { it is BoardOp.Box }).isTrue()
        assertThat(board.eraseAt(BoardPoint(330f, 310f), measurer).none { it is BoardOp.Label }).isTrue()
        assertThat(board.eraseAt(BoardPoint(980f, 20f), measurer)).isSameInstanceAs(board.ops)
    }

    @Test
    fun `一次擦除手势记为一步撤销`() {
        val board = AnnotationBoard.blank(ImageAnnotationKind.MARKUP, 1000, 1000).add(pen(0f, 0f)).add(pen(500f, 500f))
        val before = board.ops
        val erased = board.replace(board.eraseAt(BoardPoint(0f, 0f), measurer), recordFrom = null)
            .let { it.replace(it.eraseAt(BoardPoint(500f, 500f), measurer), recordFrom = null) }
            .replace(emptyList(), recordFrom = before)
        assertThat(erased.ops).isEmpty()
        assertThat(erased.undo().ops).isEqualTo(before)
    }

    @Test
    fun `粗细按短边比例换算`() {
        val wide = AnnotationBoard.blank(ImageAnnotationKind.SKETCH, 1536, 1024)
        assertThat(wide.strokeWidth(1)).isWithin(0.01f).of(1024 * 0.011f)
        assertThat(wide.brushWidth(9)).isWithin(0.01f).of(1024 * 0.11f)
        assertThat(wide.textSize(-1)).isWithin(0.01f).of(1024 * 0.036f)
    }

    @Test
    fun `草图换尺寸需要先确认清空已有笔画`() {
        val sketch = AnnotationBoard.blank(ImageAnnotationKind.SKETCH, 1024, 1536).add(pen(1f, 1f))
        val square = SketchCanvasSizes.first()
        assertThat(sketch.resizeSketch(square, discardContent = false)).isSameInstanceAs(sketch)
        val resized = sketch.resizeSketch(square, discardContent = true)
        assertThat(resized.width to resized.height).isEqualTo(1024 to 1024)
        assertThat(resized.isEmpty).isTrue()
        assertThat(resized.canUndo).isFalse()
        assertThat(SketchCanvasSizes.all { it.width % 16 == 0 && it.height % 16 == 0 }).isTrue()
    }

    @Test
    fun `快照往返恢复全部笔画且尺寸不符时回到空白`() {
        val board = AnnotationBoard.blank(ImageAnnotationKind.MARKUP, 1024, 768)
            .add(pen(1f, 2f, 3f, 4f))
            .add(BoardOp.Label(red, 30f, BoardPoint(10f, 10f), "红色杯子", outline = true))
            .switchTo(ImageAnnotationKind.MASK)
            .add(BoardOp.MaskStroke(60f, listOf(BoardPoint(5f, 5f), BoardPoint(9f, 9f)), erase = false))
        val restored = AnnotationBoard.restore(board.snapshot(), ImageAnnotationKind.MASK, 1024, 768)
        assertThat(restored.layers).isEqualTo(board.layers)
        assertThat(restored.kind).isEqualTo(ImageAnnotationKind.MASK)
        assertThat(restored.canUndo).isFalse()

        assertThat(AnnotationBoard.restore(board.snapshot(), ImageAnnotationKind.MARKUP, 1000, 768).hasAnyContent).isFalse()
        assertThat(AnnotationBoard.restore("不是 JSON", ImageAnnotationKind.MARKUP, 1024, 768).hasAnyContent).isFalse()
    }

    @Test
    fun `过短的拖动视为误触`() {
        val board = AnnotationBoard.blank(ImageAnnotationKind.MARKUP, 1000, 1000)
        assertThat(isNegligibleDrag(BoardPoint(0f, 0f), BoardPoint(5f, 5f), board)).isTrue()
        assertThat(isNegligibleDrag(BoardPoint(0f, 0f), BoardPoint(20f, 0f), board)).isFalse()
    }

    @Test
    fun `各画法工具与颜色`() {
        assertThat(boardTools(ImageAnnotationKind.MARKUP)).contains(BoardTool.ARROW)
        assertThat(boardTools(ImageAnnotationKind.MASK)).containsExactly(BoardTool.BRUSH, BoardTool.UNBRUSH).inOrder()
        assertThat(boardTools(ImageAnnotationKind.SKETCH)).contains(BoardTool.LINE)
        assertThat(SketchColors.first()).isEqualTo(0xFF1C1C1E.toInt())
    }
}
