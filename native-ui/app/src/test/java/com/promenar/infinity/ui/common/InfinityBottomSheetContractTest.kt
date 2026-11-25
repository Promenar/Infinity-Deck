package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class InfinityBottomSheetContractTest {

    @Test
    fun `未指定高度策略时按内容高度显示`() {
        // 大内容面板须显式声明 Large；短选择列表默认按内容高度，避免出现大片空白。
        assertThat(INFINITY_BOTTOM_SHEET_DEFAULT_HEIGHT).isEqualTo(InfinitySheetHeight.Content)
    }

    @Test
    fun `取消全屏模式且大上滑面板统一收口为93占比高度`() {
        assertThat(InfinitySheetHeight.entries).containsExactly(
            InfinitySheetHeight.Large,
            InfinitySheetHeight.Content,
        )
        assertThat(INFINITY_LARGE_SHEET_HEIGHT_FRACTION).isWithin(0.0001f).of(0.93f)
        assertThat(INFINITY_CENTERED_SHEET_HEIGHT_FRACTION).isWithin(0.0001f).of(0.88f)
    }
}
