package com.promenar.infinity.ui.common

import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.chat.ChatPanelHubSignals
import com.promenar.infinity.ui.chat.ChatInspectorCard
import com.promenar.infinity.ui.chat.ChatInspectorStrip
import com.promenar.infinity.ui.chat.ChatInspectorTags
import com.promenar.infinity.ui.chat.chatPanelHubEntries
import com.promenar.infinity.ui.chat.ChatPage
import com.promenar.infinity.ui.theme.InfinityTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertIsSelected
import org.junit.Rule
import org.junit.Test

/** 共用控件与会话检视器的触感覆盖：只断言系统收到的触感常量，不依赖设备马达。 */
class HapticCoverageTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private class RecordingView(context: Context) : View(context) {
        val played = mutableListOf<Int>()
        override fun performHapticFeedback(feedbackConstant: Int): Boolean {
            played += feedbackConstant
            return true
        }
    }

    private fun show(content: @Composable () -> Unit): RecordingView {
        val view = RecordingView(rule.activity)
        rule.setContent {
            InfinityTheme {
                CompositionLocalProvider(LocalInfinityHaptics provides InfinityHaptics(view, interaction = true, status = true)) {
                    content()
                }
            }
        }
        return view
    }

    @Test
    fun menuItemTicksByDefault_usesRequestedEvent_andStaysSilentWhenDisabled() {
        val view = show {
            Column {
                InfinityMenuItem(text = { Text("Rename") }, onClick = {}, modifier = Modifier.testTag("plain"))
                InfinityMenuItem(text = { Text("Copy") }, onClick = {}, haptic = InfinityHapticEvent.CONFIRM, modifier = Modifier.testTag("copy"))
                InfinityMenuItem(text = { Text("Time") }, onClick = {}, enabled = false, modifier = Modifier.testTag("disabled"))
            }
        }
        rule.onNodeWithTag("plain").performClick()
        rule.onNodeWithTag("copy").performClick()
        rule.onNodeWithTag("disabled").performClick()
        rule.runOnIdle {
            assertThat(view.played).containsExactly(HapticFeedbackConstants.SEGMENT_TICK, HapticFeedbackConstants.CONFIRM).inOrder()
        }
    }

    @Test
    fun radioSettingsRowTicksOnlyWhenSelectionChanges_buttonRowStaysSilent() {
        val view = show {
            Column {
                InfinitySettingsItem(title = "A", onClick = {}, role = Role.RadioButton, selected = false, modifier = Modifier.testTag("unselected"))
                InfinitySettingsItem(title = "B", onClick = {}, role = Role.RadioButton, selected = true, modifier = Modifier.testTag("selected"))
                InfinitySettingsItem(title = "C", onClick = {}, modifier = Modifier.testTag("button"))
            }
        }
        rule.onNodeWithTag("unselected").performClick()
        rule.onNodeWithTag("selected").performClick()
        rule.onNodeWithTag("button").performClick()
        rule.runOnIdle { assertThat(view.played).containsExactly(HapticFeedbackConstants.SEGMENT_TICK) }
    }

    @Test
    fun inspectorTabsTickOnSwitch_andCollapseExpandUseToggleFeedback() {
        val entries = chatPanelHubEntries(ChatPanelHubSignals(), summary = { null }, onOpen = {})
        val view = show {
            Column {
                // 卡片内部铺满父级，固定高度才能让边缘条留在屏幕内可被点按。
                ChatInspectorCard(root = ChatPage.Run, entries = entries, widened = false, onToggleWiden = {}, onCollapse = {}, modifier = Modifier.height(240.dp)) {}
                ChatInspectorStrip(entries = entries, onExpand = {})
            }
        }
        rule.onNodeWithTag(ChatInspectorTags.tab(ChatPage.Run)).performClick()
        rule.onNodeWithTag(ChatInspectorTags.tab(ChatPage.Explorer)).performClick()
        rule.onNodeWithTag(ChatInspectorTags.COLLAPSE).performClick()
        rule.onNodeWithTag(ChatInspectorTags.EXPAND).performClick()
        rule.onNodeWithTag(ChatInspectorTags.stripItem(ChatPage.Explorer)).performClick()
        rule.runOnIdle {
            assertThat(view.played).containsExactly(
                HapticFeedbackConstants.SEGMENT_TICK,
                HapticFeedbackConstants.TOGGLE_OFF,
                HapticFeedbackConstants.TOGGLE_ON,
                HapticFeedbackConstants.TOGGLE_ON,
            ).inOrder()
        }
    }

    @Test
    fun segmentedControlTicksOnlyWhenSwitching_andMovesSelection() {
        var picked = "a"
        val view = show {
            var current by remember { mutableStateOf("a") }
            InfinitySegmentedControl(
                segments = listOf(InfinitySegment("a", "A", tag = "seg_a", count = 2), InfinitySegment("b", "B", tag = "seg_b")),
                selected = current,
                onSelect = { current = it; picked = it },
            )
        }
        rule.onNodeWithTag("seg_a").assertIsSelected().assertHeightIsAtLeast(40.dp)
        rule.onNodeWithTag("seg_a").performClick()
        rule.onNodeWithTag("seg_b").assertIsNotSelected().performClick()
        rule.onNodeWithTag("seg_b").assertIsSelected()
        rule.onNodeWithTag("seg_a").assertIsNotSelected()
        rule.runOnIdle {
            assertThat(picked).isEqualTo("b")
            // 点已选中的段不振动，切换时给一次分段刻度反馈。
            assertThat(view.played).containsExactly(HapticFeedbackConstants.SEGMENT_TICK)
        }
    }

    @Test
    fun chipTogglesWithTick_actionChipStaysSilent_andCallerCanOwnFeedback() {
        var toggled = 0
        val view = show {
            var on by remember { mutableStateOf(false) }
            Column {
                InfinityChip(label = "Filter", onClick = { on = !on; toggled++ }, selected = on, modifier = Modifier.testTag("toggle"))
                InfinityChip(label = "Save", onClick = {}, modifier = Modifier.testTag("action"))
                InfinityChip(label = "Own", onClick = {}, selected = false, haptic = false, modifier = Modifier.testTag("own"))
            }
        }
        rule.onNodeWithTag("toggle").assertIsNotSelected().assertTouchHeightIsEqualTo(48.dp).performClick()
        rule.onNodeWithTag("toggle").assertIsSelected()
        rule.onNodeWithTag("action").performClick()
        rule.onNodeWithTag("own").performClick()
        rule.runOnIdle {
            assertThat(toggled).isEqualTo(1)
            assertThat(view.played).containsExactly(HapticFeedbackConstants.SEGMENT_TICK)
        }
    }
}
