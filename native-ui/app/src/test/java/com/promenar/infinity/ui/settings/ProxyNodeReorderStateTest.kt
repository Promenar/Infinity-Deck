package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.proxy.ProxyNodeItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyNodeReorderStateTest {

    private fun node(id: String, enabled: Boolean = true) = ProxyNodeItem(
        id = id,
        name = "Node-$id",
        type = "vless",
        server = "127.0.0.1",
        port = 443,
        enabled = enabled,
    )

    private fun preparedState(nodes: List<ProxyNodeItem>, rowHeight: Float = 100f, divider: Float = 2f): ProxyNodeReorderState {
        return ProxyNodeReorderState().apply {
            updateDividerHeight(divider)
            nodes.forEach { updateRowHeight(it.id, rowHeight) }
        }
    }

    @Test
    fun startDrag_rejectsSingleNodeOrMissingNode() {
        val state = ProxyNodeReorderState()
        assertFalse(state.startDrag("a", listOf(node("a"))))
        assertFalse(state.startDrag("missing", listOf(node("a"), node("b"))))
        assertFalse(state.isDragging)
    }

    @Test
    fun dragDownAcrossMultipleSlots_commitsSingleNetOffsetAndHoldsPendingOrder() {
        val nodes = listOf(node("a"), node("b"), node("c"), node("d"))
        val state = preparedState(nodes, rowHeight = 100f, divider = 2f) // step = 102f, threshold = 51f

        assertTrue(state.startDrag("a", nodes))
        // 拖动不足一半行高不换位
        assertFalse(state.onDragDelta(40f))
        assertEquals(listOf("a", "b", "c", "d"), state.displayNodes(nodes).map { it.id })

        // 越过 b 中线（累计 60 > 51）：与 b 交换，残留偏移 60 - 102 = -42
        assertTrue(state.onDragDelta(20f))
        assertEquals(listOf("b", "a", "c", "d"), state.displayNodes(nodes).map { it.id })
        assertEquals(-42f, state.dragOffsetY, 0.01f)

        // 再向下拖 200px：-42 + 200 = +158 -> 越过 c（余 56）-> 越过 d（余 -46）
        assertTrue(state.onDragDelta(200f))
        assertEquals(listOf("b", "c", "d", "a"), state.displayNodes(nodes).map { it.id })
        assertEquals(-46f, state.dragOffsetY, 0.01f)

        val commit = state.finishDrag()
        assertEquals("a" to 3, commit)
        assertFalse(state.isDragging)
        // 落盘前乐观顺序仍保持新顺序，避免 1 帧闪烁回旧顺序
        assertEquals(listOf("b", "c", "d", "a"), state.displayNodes(nodes).map { it.id })

        // 存储层回报持久化后的新顺序后，自动清空乐观锁
        val persistedAfter = listOf(node("b"), node("c"), node("d"), node("a"))
        state.reconcilePersisted(persistedAfter)
        assertNull(state.pendingOrderIds)
        assertEquals(listOf("b", "c", "d", "a"), state.displayNodes(persistedAfter).map { it.id })
    }

    @Test
    fun dragUpAcrossDisabledAndEnabledNodes_clampsAtTopBumperAndReversesImmediately() {
        val nodes = listOf(node("a"), node("b", enabled = false), node("c"))
        val state = preparedState(nodes, rowHeight = 100f, divider = 0f)

        assertTrue(state.startDrag("c", nodes))
        // 一次拖到顶并试图越界 80px
        assertTrue(state.onDragDelta(-280f))
        assertEquals(listOf("c", "a", "b"), state.displayNodes(nodes).map { it.id })
        // 顶缘应钳制为 0f，不累积负死区也不超出卡片顶部
        assertEquals(0f, state.dragOffsetY, 0.01f)
        assertFalse(state.canDragUp("c"))
        assertTrue(state.canDragDown("c"))

        // 立刻向下回拖 55px 应马上触发向下换位，无粘滞死区
        assertTrue(state.onDragDelta(55f))
        assertEquals(listOf("a", "c", "b"), state.displayNodes(nodes).map { it.id })

        assertEquals("c" to -1, state.finishDrag())
    }

    @Test
    fun dragAndReturnToOrigin_finishReturnsNullWithoutCommit() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val state = preparedState(nodes, rowHeight = 100f, divider = 0f)

        assertTrue(state.startDrag("b", nodes))
        assertTrue(state.onDragDelta(-60f))
        assertEquals(listOf("b", "a", "c"), state.displayNodes(nodes).map { it.id })
        assertTrue(state.onDragDelta(60f))
        assertEquals(listOf("a", "b", "c"), state.displayNodes(nodes).map { it.id })

        assertNull(state.finishDrag())
        assertNull(state.pendingOrderIds)
    }

    @Test
    fun cancelDragAndExternalRemoval_rollbackSafely() {
        val nodes = listOf(node("a"), node("b"), node("c"))
        val state = preparedState(nodes, rowHeight = 100f, divider = 0f)

        assertTrue(state.startDrag("c", nodes))
        assertTrue(state.onDragDelta(-60f))
        assertEquals(listOf("a", "c", "b"), state.displayNodes(nodes).map { it.id })

        state.cancelDrag()
        assertFalse(state.isDragging)
        assertEquals(listOf("a", "b", "c"), state.displayNodes(nodes).map { it.id })

        // 拖拽过程中节点被外部删除应自动安全取消
        assertTrue(state.startDrag("c", nodes))
        state.reconcilePersisted(listOf(node("a"), node("b")))
        assertFalse(state.isDragging)
        assertEquals(listOf("a", "b"), state.displayNodes(listOf(node("a"), node("b"))).map { it.id })
    }
}
