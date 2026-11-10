package com.promenar.infinity.ui.pet

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints

/*
 * 宠物每推进一帧，直接把绘制与跟随宠物的布局标记为失效，在推进的同一帧重绘。
 * 若改用快照状态驱动，帧回调里的写入要等应用通知送达后才让节点失效，重绘会推迟到下一帧，
 * 推进所在的那一帧也照常渲染，实际帧数是目标帧率的两倍。
 */

/** 用 [draw] 画出宠物；每次 [PetRuntime.publishFrame] 后重绘。 */
internal fun Modifier.petDraw(runtime: PetRuntime, draw: DrawScope.() -> Unit): Modifier =
    this then PetDrawElement(runtime, draw)

/** 按宠物当前位置测量与摆放；每次 [PetRuntime.publishFrame] 后重新测量。 */
internal fun Modifier.petFollow(
    runtime: PetRuntime,
    measure: MeasureScope.(Measurable, Constraints) -> MeasureResult,
): Modifier = this then PetFollowElement(runtime, measure)

private class PetDrawElement(
    private val runtime: PetRuntime,
    private val draw: DrawScope.() -> Unit,
) : ModifierNodeElement<PetDrawNode>() {
    override fun create() = PetDrawNode(runtime, draw)

    override fun update(node: PetDrawNode) {
        node.onDraw = draw
        node.bind(runtime)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "petDraw"
    }

    override fun equals(other: Any?) = other is PetDrawElement && other.runtime === runtime && other.draw === draw

    override fun hashCode() = 31 * System.identityHashCode(runtime) + System.identityHashCode(draw)
}

private class PetDrawNode(
    private var runtime: PetRuntime,
    var onDraw: DrawScope.() -> Unit,
) : Modifier.Node(), DrawModifierNode {
    private val listener: () -> Unit = { invalidateDraw() }

    fun bind(next: PetRuntime) {
        if (next === runtime) {
            invalidateDraw()
            return
        }
        if (isAttached) runtime.removeFrameListener(listener)
        runtime = next
        if (isAttached) runtime.addFrameListener(listener)
        invalidateDraw()
    }

    override fun onAttach() = runtime.addFrameListener(listener)

    override fun onDetach() = runtime.removeFrameListener(listener)

    override fun ContentDrawScope.draw() {
        onDraw()
        drawContent()
    }
}

private class PetFollowElement(
    private val runtime: PetRuntime,
    private val measure: MeasureScope.(Measurable, Constraints) -> MeasureResult,
) : ModifierNodeElement<PetFollowNode>() {
    override fun create() = PetFollowNode(runtime, measure)

    override fun update(node: PetFollowNode) {
        node.onMeasure = measure
        node.bind(runtime)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "petFollow"
    }

    override fun equals(other: Any?) = other is PetFollowElement && other.runtime === runtime && other.measure === measure

    override fun hashCode() = 31 * System.identityHashCode(runtime) + System.identityHashCode(measure)
}

private class PetFollowNode(
    private var runtime: PetRuntime,
    var onMeasure: MeasureScope.(Measurable, Constraints) -> MeasureResult,
) : Modifier.Node(), LayoutModifierNode {
    private val listener: () -> Unit = { invalidateMeasurement() }

    fun bind(next: PetRuntime) {
        if (next === runtime) {
            invalidateMeasurement()
            return
        }
        if (isAttached) runtime.removeFrameListener(listener)
        runtime = next
        if (isAttached) runtime.addFrameListener(listener)
        invalidateMeasurement()
    }

    override fun onAttach() = runtime.addFrameListener(listener)

    override fun onDetach() = runtime.removeFrameListener(listener)

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult =
        onMeasure(measurable, constraints)
}
