package com.promenar.infinity.ui.common

/**
 * 属性表的列宽规则（纯函数，单测覆盖）：
 * - 名称列宽上限为行内容宽度的 40%；
 * - 名称列宽取所有放得下的名称中最宽的一个，各行两列对齐；
 * - 名称超过上限的行改为名称在上、取值在下，不参与列宽计算；
 * - 行尾动作挤得取值单行放不下时，动作移到取值下方。
 */
internal object InfinityPropertyLayout {
    const val LABEL_MAX_FRACTION = 0.4f

    /** 名称列宽上限（像素）。 */
    fun labelCap(contentWidth: Int): Int = (contentWidth.coerceAtLeast(0) * LABEL_MAX_FRACTION).toInt()

    /** 名称在上限内放不下时，这一行改为上下排列。 */
    fun stacked(labelWidth: Int, cap: Int): Boolean = labelWidth > cap

    /** 统一的名称列宽：上限内最宽的名称；全部超限时为 0。 */
    fun columnWidth(labelWidths: Collection<Int>, cap: Int): Int =
        labelWidths.filter { it <= cap }.maxOrNull() ?: 0

    /**
     * 行尾动作的位置与取值宽度：先在取值列内预留动作宽度与间距；
     * 取值的单行固有宽度放不下时，动作移到取值下方，取值改用整列宽度。
     *
     * @param valueColumnWidth 取值列宽度（已扣除行尾箭头）。
     * @param valueIntrinsicWidth 取值单行时的最大固有宽度。
     * @param trailingWidth 行尾动作宽度；没有动作时为 0。
     */
    fun trailingPlacement(valueColumnWidth: Int, valueIntrinsicWidth: Int, trailingWidth: Int, gap: Int): TrailingPlacement {
        val column = valueColumnWidth.coerceAtLeast(0)
        if (trailingWidth <= 0) return TrailingPlacement(wraps = false, valueWidth = column)
        val inline = (column - trailingWidth - gap).coerceAtLeast(0)
        return if (valueIntrinsicWidth > inline) TrailingPlacement(wraps = true, valueWidth = column)
        else TrailingPlacement(wraps = false, valueWidth = inline)
    }
}

/** [InfinityPropertyLayout.trailingPlacement] 的结果：[wraps] 为真时行尾动作另起一行放在取值下方。 */
internal data class TrailingPlacement(val wraps: Boolean, val valueWidth: Int)
