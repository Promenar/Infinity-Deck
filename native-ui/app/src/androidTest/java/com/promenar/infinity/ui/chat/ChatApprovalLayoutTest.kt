package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.R
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

class ChatApprovalLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private var primary = Color.Unspecified
    private var cardBackground = Color.Unspecified
    private var neutralFill = Color.Unspecified
    private var warningFill = Color.Unspecified

    @Test
    fun approvalActionsAreEqualWidthPillsWith48DpTouchHeight() {
        content {
            ApprovalCard(
                toolName = "write_file",
                description = "Check the target before approval.",
                calls = listOf(call("write_file", "/notes/a.md")),
            )
        }

        listOf(UiTags.CHAT_APPROVAL_DECLINE, UiTags.CHAT_APPROVAL_APPROVE).forEach { tag ->
            val action = rule.onNodeWithTag(tag)
            action.assertHeightIsEqualTo(44.dp)
            // WindowSize 覆盖会按窗口缩放组合密度，必须用节点自身的布局密度换算 dp。
            val node = action.fetchSemanticsNode()
            val touchHeight = node.touchBoundsInRoot.height / node.layoutInfo.density.density
            assertWithMessage("$tag 触控高度 ${touchHeight}dp").that(touchHeight).isAtLeast(47.5f)
        }
        val reject = rule.onNodeWithTag(UiTags.CHAT_APPROVAL_DECLINE).fetchSemanticsNode().boundsInRoot
        val approve = rule.onNodeWithTag(UiTags.CHAT_APPROVAL_APPROVE).fetchSemanticsNode().boundsInRoot
        assertThat(abs(reject.center.y - approve.center.y)).isAtMost(with(rule.density) { 0.5.dp.toPx() })
        assertThat(abs(reject.width - approve.width)).isAtMost(with(rule.density) { 1.dp.toPx() })
    }

    @Test
    fun approvalIsTheOnlySolidActionAndWarningsHaveNoRedFill() {
        content {
            ApprovalCard(
                toolName = "delete_file",
                description = "Deletion can remove data.",
                calls = listOf(
                    call("delete_file", "/notes/a.md", highRisk = true),
                    call("write_file", "/notes/b.md"),
                ),
                onApproveAll = {},
            )
        }

        // 有“全部批准”时批准是分体按钮：箭头段与批准主体同为主色，属于同一个实心操作。
        val actions = listOf(
            rule.onNodeWithTag(UiTags.CHAT_APPROVAL_APPROVE),
            rule.onNodeWithTag(UiTags.CHAT_APPROVAL_DECLINE),
        )
        assertThat(actions.count { near(actionFill(it), primary) }).isEqualTo(1)
        assertThat(near(actionFill(actions[1]), neutralFill)).isTrue()
        assertThat(near(actionFill(rule.onNodeWithTag(UiTags.CHAT_APPROVAL_MORE)), primary)).isTrue()
        rule.onAllNodesWithTag(UiTags.CHAT_APPROVAL_APPROVE_ALL).assertCountEquals(0)

        val pixels = rule.onNodeWithTag(UiTags.CHAT_APPROVAL_CARD).captureToImage().toPixelMap()
        var longestWarningRun = 0
        for (y in 0 until pixels.height) {
            var run = 0
            for (x in 0 until pixels.width) {
                run = if (near(pixels[x, y], warningFill)) run + 1 else 0
                longestWarningRun = maxOf(longestWarningRun, run)
            }
        }
        assertThat(longestWarningRun.toFloat()).isLessThan(with(rule.density) { 24.dp.toPx() })
    }

    @Test
    fun parameterBlockUsesSixReadableLinesAndCanExpandAllContent() {
        val arguments = (1..12).joinToString("\n") { "parameter-$it: value-$it" }
        content {
            ApprovalCard(
                toolName = "write_file",
                description = "",
                calls = listOf(call("write_file", "/notes/a.md").copy(argumentsSummary = arguments)),
            )
        }

        // 收起态只排版前几行；展开后由独立的限高滚动节点承载完整原文。
        val collapsed = layout(rule.onNodeWithText(ApprovalTextBlockLayout.collapsedText(arguments), useUnmergedTree = true))
        assertThat(collapsed.lineCount).isEqualTo(6)
        assertThat(collapsed.layoutInput.style.fontSize).isEqualTo(12.sp)
        assertThat(collapsed.layoutInput.style.lineHeight).isEqualTo(18.sp)
        assertThat(collapsed.layoutInput.style.fontFamily).isEqualTo(FontFamily.Monospace)
        rule.onNodeWithText(resources().getString(R.string.chat_inline_expand_all))
            .assertIsDisplayed().performClick()
        val expanded = layout(rule.onNodeWithText(arguments, useUnmergedTree = true))
        assertThat(expanded.lineCount).isEqualTo(12)
        assertThat(expanded.layoutInput.text.text).isEqualTo(arguments)
        rule.onNodeWithText(resources().getString(R.string.chat_inline_collapse)).assertIsDisplayed()
    }

    @Test
    fun structuredRisksRemainVisibleBeforeAndAfterExecutionWithTechnicalReason() {
        var executed by mutableStateOf(false)
        content {
            ApprovalCard(
                toolName = "delete_file", description = "Execution mode: semi", isExecuted = executed,
                calls = listOf(
                    call("delete_file", "/notes/a.md", highRisk = true).copy(
                        preview = ApprovalPreview.Preview(ApprovalPreview.Action.DELETE, "/notes/a.md", ApprovalPreview.IRREVERSIBLE_WARNING),
                    ),
                    call("exec_js", "/scripts/a.js").copy(
                        preview = ApprovalPreview.Preview(ApprovalPreview.Action.SCRIPT, "", ApprovalPreview.NETWORK_ALLOWED + "\nconsole.log(1)", scriptAllowsNetwork = true),
                    ),
                ),
            )
        }
        fun assertRisks() {
            rule.onNodeWithText(resources().getString(R.string.agent_panels_irreversible_warning)).assertIsDisplayed()
            rule.onAllNodesWithText(resources().getString(R.string.agent_panels_network_allowed)).assertCountEquals(1)
            rule.onNodeWithText("console.log(1)").assertIsDisplayed()
            rule.onNodeWithText("Execution mode: semi").assertDoesNotExist()
        }
        assertRisks()
        rule.runOnIdle { executed = true }
        assertRisks()
    }

    @Test
    fun executedApprovalKeepsOrdinaryReasonSeparateFromRiskLabel() {
        content {
            ApprovalCard("write_file", "请核对内容后批准。", calls = listOf(call("write_file", "/notes/a.md")), isExecuted = true)
        }
        rule.onNodeWithText("请核对内容后批准。").assertIsDisplayed()
        rule.onNodeWithText("File write").assertIsDisplayed()
        assertThat(layout(rule.onNodeWithText("请核对内容后批准。")).layoutInput.style.color)
            .isEqualTo(layout(rule.onNodeWithText("write_file")).layoutInput.style.color)
        assertThat(layout(rule.onNodeWithText("请核对内容后批准。")).layoutInput.style.color)
            .isNotEqualTo(layout(rule.onNodeWithText("File write")).layoutInput.style.color)
    }

    private fun content(block: @Composable () -> Unit) = rule.setContent {
        DeviceConfigurationOverride(DeviceConfigurationOverride.WindowSize(DpSize(411.dp, 1000.dp))) {
            InfinityTheme(dynamicColor = false) {
                primary = MaterialTheme.colorScheme.primary
                cardBackground = MaterialTheme.colorScheme.surfaceContainerLow
                neutralFill = MaterialTheme.colorScheme.surfaceContainerHighest
                warningFill = MaterialTheme.colorScheme.errorContainer
                Column(Modifier.width(411.dp).padding(20.dp)) { block() }
            }
        }
    }

    private fun actionFill(action: SemanticsNodeInteraction): Color {
        val pixels = action.captureToImage().toPixelMap()
        val x = with(rule.density) { 8.dp.roundToPx() }.coerceAtMost(pixels.width - 1)
        return pixels[x, pixels.height / 2]
    }

    private fun layout(text: SemanticsNodeInteraction): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        text.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        return layouts.single()
    }

    private fun near(actual: Color, expected: Color): Boolean =
        abs(actual.red - expected.red) < 0.015f &&
            abs(actual.green - expected.green) < 0.015f &&
            abs(actual.blue - expected.blue) < 0.015f

    private fun resources() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun commandAndToolApprovalsCarryDistinctKindNotes() {
        var toolName by mutableStateOf("shell_exec")
        content {
            ApprovalCard(
                toolName = toolName,
                description = "",
                calls = listOf(call(toolName, "/notes/a.md")),
            )
        }
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_KIND_COMMAND).assertIsDisplayed()
        rule.onNodeWithText(resources().getString(R.string.chat_approval_kind_builtin_command)).assertIsDisplayed()

        toolName = "ssh_exec"
        rule.onNodeWithText(resources().getString(R.string.chat_approval_kind_ssh_command)).assertIsDisplayed()

        toolName = "schedule_create"
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_KIND_TOOL).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_APPROVAL_KIND_COMMAND).assertDoesNotExist()
    }

    private fun call(name: String, path: String, highRisk: Boolean = false) = ApprovalDisplayCall(
        name = name,
        argumentsSummary = "{\"path\":\"$path\"}",
        riskLabel = if (highRisk) "Deletion" else "File write",
        executionTarget = "[\"path\",\"$path\"]",
        highRisk = highRisk,
    )
}
