package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.ui.chat.components.RunDetailContent
import com.promenar.infinity.ui.chat.components.RunDetailTab
import com.promenar.infinity.ui.chat.components.RunDockRow
import com.promenar.infinity.ui.chat.components.runDockState
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

/** 运行坞：一句话读出当前步骤与进度，点按整行打开运行详情；待批准数交给盾牌，锁定触摸在顶栏。 */
class RunDockRowTest {
    @get:Rule val rule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val plan = requireNotNull(
        taskPanelUiState(
            listOf(
                TaskStep(id = "a", title = "列出工作区", status = "done", sortOrder = 0),
                TaskStep(id = "b", title = "查找 Markdown 文件", status = "doing", sortOrder = 1),
            ),
            isGenerating = true,
        ),
    )

    @Test
    fun readsCurrentStepAndOpensMatchingTab() {
        val opened = mutableListOf<RunDetailTab?>()
        rule.setContent {
            InfinityTheme {
                RunDockRow(
                    state = requireNotNull(runDockState(plan, true, 0, 1, 0, "进度", 3 * 60_000L)),
                    onOpen = { opened += it },
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_MAIN)
            .assertContentDescriptionContains("查找 Markdown 文件", substring = true)
            .assertContentDescriptionContains("1/2", substring = true)
            .performClick()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_JOBS, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_SCOPE, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_STATE).performClick()
        rule.runOnIdle { assertThat(opened).containsExactly(null, RunDetailTab.STATE).inOrder() }
    }

    @Test
    fun pendingApprovalsLeaveDockChipsInPlace() {
        rule.setContent {
            InfinityTheme {
                RunDockRow(
                    state = requireNotNull(runDockState(plan, true, 2, 1, 0, "进度", null)),
                    onOpen = {},
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_JOBS, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_STATE).assertExists()
        rule.onNodeWithTag(UiTags.CHAT_TOUCH_LOCK_BUTTON).assertDoesNotExist()
    }

    @Test
    fun pendingShieldJumpsOnTapAndOpensMenuOnLongPress() {
        var jumps = 0
        rule.setContent {
            InfinityTheme {
                ExecutionModeButton(
                    com.promenar.infinity.ui.common.ExecutionMode.WORKSPACE,
                    onSelect = {},
                    pendingApprovals = 2,
                    onJumpToApproval = { jumps++ },
                )
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_EXECUTION_MODE)
            .assertContentDescriptionContains(context.getString(R.string.chat_execution_mode_pending_cd, 2))
            .performClick()
        rule.runOnIdle { assertThat(jumps).isEqualTo(1) }
        rule.onNodeWithTag("${UiTags.CHAT_EXECUTION_MODE}_workspace").assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CHAT_EXECUTION_MODE).performTouchInput { longClick() }
        rule.onNodeWithTag("${UiTags.CHAT_EXECUTION_MODE}_workspace").assertExists()
        rule.runOnIdle { assertThat(jumps).isEqualTo(1) }
    }

    @Test
    fun planFooterKeepsActionsWithoutRunStats() {
        rule.setContent {
            InfinityTheme {
                Column {
                    RunDetailContent(
                        initialTab = RunDetailTab.PLAN,
                        plan = plan,
                        isGenerating = true,
                        turnStartedAt = null,
                        activity = null,
                        stateBoard = null,
                        onDismiss = {},
                        onContinue = {},
                        onComplete = {},
                        onHidePlan = {},
                        onClearPlan = {},
                        onEditState = { _, _ -> null },
                        onRemoveState = { null },
                        modifier = androidx.compose.ui.Modifier.weight(1f),
                        nowMillis = 0L,
                    )
                }
            }
        }
        rule.onNodeWithTag(UiTags.CHAT_TASK_CONTINUE).assertExists()
    }
}
