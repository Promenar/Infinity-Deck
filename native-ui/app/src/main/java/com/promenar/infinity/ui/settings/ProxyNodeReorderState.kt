package com.promenar.infinity.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.promenar.infinity.data.proxy.ProxyNodeItem

/**
 * 顺序降级模式下的节点拖拽排序状态机。
 *
 * 拖拽全程在内存中实时换位并维护跟随手指的纵向偏移量，仅在松手结束时返回一次净偏移量
 * `(nodeId, offset)` 交给存储层原子落盘，避免中途反复重载代理内核；落盘等待期保留短暂的
 * 乐观顺序快照以消除松手瞬间的 1 帧回弹闪烁。
 */
@Stable
internal class ProxyNodeReorderState {
    var draggingId: String? by mutableStateOf(null)
        private set

    var dragOffsetY: Float by mutableFloatStateOf(0f)
        private set

    var pendingOrderIds: List<String>? by mutableStateOf(null)
        private set

    private var originIndex: Int = -1
    private var currentOrderIds: List<String> by mutableStateOf(emptyList())
    private val rowHeightsPx = mutableMapOf<String, Float>()
    private var dividerHeightPx: Float = 1f

    val isDragging: Boolean
        get() = draggingId != null

    fun updateRowHeight(nodeId: String, heightPx: Float) {
        if (heightPx > 0f) {
            rowHeightsPx[nodeId] = heightPx
        }
    }

    fun updateDividerHeight(heightPx: Float) {
        if (heightPx >= 0f) {
            dividerHeightPx = heightPx
        }
    }

    /** 开始拖拽指定节点；少于 2 个节点或目标不存在时返回 false。 */
    fun startDrag(nodeId: String, persistedNodes: List<ProxyNodeItem>): Boolean {
        val baseIds = resolveBaseIds(persistedNodes)
        if (baseIds.size < 2) return false
        val index = baseIds.indexOf(nodeId)
        if (index < 0) return false
        pendingOrderIds = null
        draggingId = nodeId
        originIndex = index
        currentOrderIds = baseIds
        dragOffsetY = 0f
        return true
    }

    /**
     * 累加纵向拖拽增量并按相邻行中线判定换位。
     * @return 本次增量是否触发了至少一次换位（用于触感反馈）。
     */
    fun onDragDelta(deltaY: Float): Boolean {
        val id = draggingId ?: return false
        if (deltaY == 0f || currentOrderIds.size < 2) return false

        val working = currentOrderIds.toMutableList()
        var currentIndex = working.indexOf(id)
        if (currentIndex < 0) {
            cancelDrag()
            return false
        }

        var offset = dragOffsetY + deltaY
        var swapped = false

        while (currentIndex < working.lastIndex) {
            val nextStep = stepHeightPx(working[currentIndex + 1], id)
            if (offset > nextStep * SWAP_THRESHOLD_RATIO) {
                working.swap(currentIndex, currentIndex + 1)
                currentIndex++
                offset -= nextStep
                swapped = true
            } else {
                break
            }
        }

        while (currentIndex > 0) {
            val prevStep = stepHeightPx(working[currentIndex - 1], id)
            if (offset < -prevStep * SWAP_THRESHOLD_RATIO) {
                working.swap(currentIndex, currentIndex - 1)
                currentIndex--
                offset += prevStep
                swapped = true
            } else {
                break
            }
        }

        // 在首末边界钳制越界位移，既保证触底手感利落，又避免拖拽卡被分组卡片圆角裁切。
        if (currentIndex == 0 && offset < 0f) {
            offset = 0f
        } else if (currentIndex == working.lastIndex && offset > 0f) {
            offset = 0f
        }

        if (swapped) {
            currentOrderIds = working.toList()
        }
        dragOffsetY = offset
        return swapped
    }

    /**
     * 结束拖拽并返回需要提交的 `(nodeId, netOffset)`；若未产生净位移则返回 null。
     */
    fun finishDrag(): Pair<String, Int>? {
        val id = draggingId ?: return null
        val order = currentOrderIds
        val finalIndex = order.indexOf(id)
        val delta = if (originIndex >= 0 && finalIndex >= 0) finalIndex - originIndex else 0

        draggingId = null
        dragOffsetY = 0f
        originIndex = -1
        currentOrderIds = emptyList()

        if (delta != 0) {
            pendingOrderIds = order
            return id to delta
        }
        return null
    }

    /** 取消当前拖拽并回滚到落盘顺序。 */
    fun cancelDrag() {
        draggingId = null
        dragOffsetY = 0f
        originIndex = -1
        currentOrderIds = emptyList()
    }

    fun clearPendingOrder() {
        pendingOrderIds = null
    }

    /** 持久化列表更新后收敛乐观状态；若拖拽中节点被外部移除则自动中止。 */
    fun reconcilePersisted(persistedNodes: List<ProxyNodeItem>) {
        val persistedIds = persistedNodes.map { it.id }
        val activeId = draggingId
        if (activeId != null) {
            if (activeId !in persistedIds) {
                cancelDrag()
            }
            return
        }
        val pending = pendingOrderIds ?: return
        if (persistedIds == pending || persistedIds.size != pending.size || persistedIds.toSet() != pending.toSet()) {
            pendingOrderIds = null
        }
    }

    /** 计算当前界面应渲染的节点顺序（拖拽态优先，其次短暂乐观落盘态，最后真实落盘顺序）。 */
    fun displayNodes(persistedNodes: List<ProxyNodeItem>): List<ProxyNodeItem> {
        val overrideIds = when {
            draggingId != null && currentOrderIds.isNotEmpty() -> currentOrderIds
            pendingOrderIds != null -> pendingOrderIds
            else -> null
        } ?: return persistedNodes

        val byId = persistedNodes.associateBy { it.id }
        val ordered = ArrayList<ProxyNodeItem>(persistedNodes.size)
        val seen = HashSet<String>(persistedNodes.size)
        overrideIds.forEach { id ->
            val node = byId[id]
            if (node != null && seen.add(id)) {
                ordered.add(node)
            }
        }
        persistedNodes.forEach { node ->
            if (seen.add(node.id)) {
                ordered.add(node)
            }
        }
        return ordered
    }

    fun canDragDown(nodeId: String): Boolean {
        val index = currentOrderIds.indexOf(nodeId)
        return index in 0 until currentOrderIds.lastIndex
    }

    fun canDragUp(nodeId: String): Boolean {
        val index = currentOrderIds.indexOf(nodeId)
        return index > 0
    }

    private fun resolveBaseIds(persistedNodes: List<ProxyNodeItem>): List<String> =
        displayNodes(persistedNodes).map { it.id }

    private fun stepHeightPx(targetId: String, draggedId: String): Float {
        val h = rowHeightsPx[targetId] ?: rowHeightsPx[draggedId] ?: DEFAULT_ROW_HEIGHT_PX
        return (h + dividerHeightPx).coerceAtLeast(MIN_STEP_HEIGHT_PX)
    }

    private fun MutableList<String>.swap(a: Int, b: Int) {
        val tmp = this[a]
        this[a] = this[b]
        this[b] = tmp
    }

    companion object {
        private const val SWAP_THRESHOLD_RATIO = 0.5f
        private const val DEFAULT_ROW_HEIGHT_PX = 160f
        private const val MIN_STEP_HEIGHT_PX = 24f
    }
}
