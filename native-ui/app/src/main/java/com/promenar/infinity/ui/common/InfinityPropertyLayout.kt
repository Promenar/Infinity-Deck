package com.promenar.infinity.ui.common

/**
 * 属性表的列宽规则（纯函数，单测覆盖）：
 * - 名称列宽上限为行内容宽度的 40%；
 * - 名称列宽取所有放得下的名称中最宽的一个，各行两列对齐；
 * - 名称超过上限的行改为名称在上、取值在下，不参与列宽计算。
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
}
