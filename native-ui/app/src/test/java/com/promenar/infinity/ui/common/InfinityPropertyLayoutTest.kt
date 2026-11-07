package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InfinityPropertyLayoutTest {
    @Test
    fun `名称列宽上限为内容宽度的四成`() {
        assertThat(InfinityPropertyLayout.labelCap(1000)).isEqualTo(400)
        assertThat(InfinityPropertyLayout.labelCap(333)).isEqualTo(133)
        assertThat(InfinityPropertyLayout.labelCap(-5)).isEqualTo(0)
    }

    @Test
    fun `列宽取上限内最宽的名称`() {
        assertThat(InfinityPropertyLayout.columnWidth(listOf(60, 120, 90), cap = 400)).isEqualTo(120)
    }

    @Test
    fun `超过上限的名称不参与列宽计算`() {
        assertThat(InfinityPropertyLayout.columnWidth(listOf(60, 80, 300), cap = 140)).isEqualTo(80)
    }

    @Test
    fun `名称全部超限或没有名称时列宽为零`() {
        assertThat(InfinityPropertyLayout.columnWidth(listOf(300, 500), cap = 140)).isEqualTo(0)
        assertThat(InfinityPropertyLayout.columnWidth(emptyList(), cap = 140)).isEqualTo(0)
    }

    @Test
    fun `名称恰好等于上限仍并排，超出一像素改为上下排列`() {
        assertThat(InfinityPropertyLayout.stacked(labelWidth = 140, cap = 140)).isFalse()
        assertThat(InfinityPropertyLayout.stacked(labelWidth = 141, cap = 140)).isTrue()
        assertThat(InfinityPropertyLayout.columnWidth(listOf(140, 141), cap = 140)).isEqualTo(140)
    }

    @Test
    fun `取值单行放得下时行尾动作留在行尾`() {
        // 取值列 400，按钮 120，间距 30：取值可用 250。
        val placement = InfinityPropertyLayout.trailingPlacement(valueColumnWidth = 400, valueIntrinsicWidth = 250, trailingWidth = 120, gap = 30)
        assertThat(placement).isEqualTo(TrailingPlacement(wraps = false, valueWidth = 250))
    }

    @Test
    fun `取值单行放不下时行尾动作移到下方，取值用整列宽度`() {
        // 宽屏右栏：取值“14 个实体 · 22 条关系”约 330，按钮“重新抽取”约 260，取值列 420。
        val placement = InfinityPropertyLayout.trailingPlacement(valueColumnWidth = 420, valueIntrinsicWidth = 330, trailingWidth = 260, gap = 30)
        assertThat(placement).isEqualTo(TrailingPlacement(wraps = true, valueWidth = 420))
    }

    @Test
    fun `没有行尾动作时取值用整列宽度且不换行`() {
        val placement = InfinityPropertyLayout.trailingPlacement(valueColumnWidth = 420, valueIntrinsicWidth = 2000, trailingWidth = 0, gap = 30)
        assertThat(placement).isEqualTo(TrailingPlacement(wraps = false, valueWidth = 420))
    }

    @Test
    fun `按钮比取值列还宽时移到下方，宽度不为负`() {
        val placement = InfinityPropertyLayout.trailingPlacement(valueColumnWidth = 100, valueIntrinsicWidth = 10, trailingWidth = 200, gap = 30)
        assertThat(placement).isEqualTo(TrailingPlacement(wraps = true, valueWidth = 100))
        assertThat(InfinityPropertyLayout.trailingPlacement(valueColumnWidth = -5, valueIntrinsicWidth = 10, trailingWidth = 0, gap = 30).valueWidth)
            .isEqualTo(0)
    }
}
