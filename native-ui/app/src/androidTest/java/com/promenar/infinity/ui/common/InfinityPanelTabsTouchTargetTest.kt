package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

/** 面板二级页签：任何窗口与字号下，每个页签都保留 48dp 触控高度；放不下时横滑可达，不截断。 */
class InfinityPanelTabsTouchTargetTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun phoneWithDoubleFontKeepsEveryTabReachable() {
        assertTabsReachable(windowSize = DpSize(360.dp, 800.dp), fontScale = 2f)
    }

    @Test
    fun landscapeKeepsEveryTabAtMinimumTouchHeight() {
        assertTabsReachable(windowSize = DpSize(800.dp, 360.dp), fontScale = 1f)
    }

    private fun assertTabsReachable(windowSize: DpSize, fontScale: Float) {
        var selected = 0
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(windowSize) then
                    DeviceConfigurationOverride.FontScale(fontScale),
            ) {
                InfinityTheme(dynamicColor = false) {
                    Box(Modifier.requiredSize(windowSize.width, windowSize.height)) {
                        var current by remember { mutableIntStateOf(0) }
                        InfinityPanelTabs(
                            tabs = TAB_TAGS.mapIndexed { index, tag ->
                                InfinityPanelTab(index, "知识检索页签 $index", count = "1$index", testTag = tag)
                            },
                            selected = current,
                            onSelect = { current = it; selected = it },
                        )
                    }
                }
            }
        }

        TAB_TAGS.forEach { tag ->
            rule.onNodeWithTag(tag)
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithTag(TAB_TAGS.last()).performScrollTo().performClick()
        rule.onNodeWithTag(TAB_TAGS.last()).assertIsSelected()
        assertThat(selected).isEqualTo(TAB_TAGS.lastIndex)
    }

    private companion object {
        val TAB_TAGS = listOf("panel_tab_0", "panel_tab_1", "panel_tab_2", "panel_tab_3")
    }
}
