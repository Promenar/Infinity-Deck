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
}
