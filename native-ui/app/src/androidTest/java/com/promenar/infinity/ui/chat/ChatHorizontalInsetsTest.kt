package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

class ChatHorizontalInsetsTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun landscapeBackAndOptionsStayInsideCutoutAndNavigationInsets() {
        rule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp))) {
                InfinityTheme {
                    Box(Modifier.fillMaxSize().testTag("SAFE_TOP_HOST")) {
                        ChatTopBar(
                            title = "横屏会话", onBack = {}, onSettings = {},
                            onClearHistory = {}, onRename = {}, onDeleteSession = {},
                            windowInsets = WindowInsets(left = 28.dp, right = 48.dp),
                        )
                    }
                }
            }
        }
        val host = rule.onNodeWithTag("SAFE_TOP_HOST").fetchSemanticsNode()
        val density = host.layoutInfo.density.density
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val back = rule.onNodeWithContentDescription(resources.getString(R.string.common_cd_back)).fetchSemanticsNode().boundsInRoot
        val more = rule.onNodeWithTag(UiTags.CHAT_OPTIONS).fetchSemanticsNode().boundsInRoot
        assertThat(back.left).isAtLeast(host.boundsInRoot.left + 27.5f * density)
        assertThat(more.right).isAtMost(host.boundsInRoot.right - 47.5f * density)
    }
}
