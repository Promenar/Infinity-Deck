package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.theme.InfinityTheme
import kotlin.math.abs
import org.junit.Rule
import org.junit.Test

class ChatLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test fun toolbarKeepsFortyDpActionsOnOneLine() = checkToolbar(1f)

    @Test fun enlargedTextKeepsToolbarOnOneLineAndTruncatesModel() = checkToolbar(1.3f)

    @Test fun markdownCodeHeaderKeepsThirtySixDpHeight() {
        rule.setContent {
            InfinityTheme {
                Box(Modifier.width(371.dp)) {
                    MarkdownText("```kotlin\nval answer = 42\n```", onContentChange = {})
                }
            }
        }
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag("CHAT_MARKDOWN_CODE_HEADER", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        val header = rule.onNodeWithTag("CHAT_MARKDOWN_CODE_HEADER", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertThat(abs(header.height / rule.density.density - 36f)).isAtMost(0.5f)
        rule.onNodeWithTag("CHAT_MARKDOWN_CODE_BODY", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun checkToolbar(fontScale: Float) {
        var modelOpened = false
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                InfinityTheme {
                    // 与会话页一致：输入区距屏幕左右 12dp，加号的命中区可越过容器左缘。
                    Box(Modifier.padding(horizontal = 12.dp).width(387.dp)) {
                        ChatInputBar(
                            text = "请比较这两份报告。",
                            onTextChange = {}, onSend = {},
                            modelName = "这是一个应当单行截断的超长模型名称",
                            onModelClick = { modelOpened = true },
                        )
                    }
                }
            }
        }
        val toolbar = rule.onNodeWithTag("chat_composer_toolbar", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val add = rule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).assertHasClickAction().fetchSemanticsNode()
        val send = rule.onNodeWithTag(UiTags.CHAT_GENERATION_ACTION).assertHasClickAction().fetchSemanticsNode()
        val model = rule.onNodeWithTag(UiTags.CHAT_MODEL_SELECTOR).fetchSemanticsNode().boundsInRoot
        val token = rule.onNodeWithTag(UiTags.CHAT_TOKEN_INDICATOR).fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        for (bounds in listOf(toolbar, add.boundsInRoot, send.boundsInRoot, model, token)) {
            assertThat(abs(bounds.height / density - 40f)).isAtMost(0.5f)
            assertThat(abs(bounds.center.y - toolbar.center.y) / density).isAtMost(0.5f)
        }
        assertWithMessage("发送按钮可见宽度 ${send.boundsInRoot.width / density}dp")
            .that(abs(send.boundsInRoot.width / density - 40f)).isAtMost(0.5f)
        assertWithMessage("加号按钮可见宽度 ${add.boundsInRoot.width / density}dp")
            .that(abs(add.boundsInRoot.width / density - 40f)).isAtMost(0.5f)
        for (node in listOf(add, send)) {
            assertWithMessage("触控宽度 ${node.touchBoundsInRoot.width / density}dp")
                .that(node.touchBoundsInRoot.width / density).isAtLeast(48f - 0.5f)
            assertWithMessage("触控高度 ${node.touchBoundsInRoot.height / density}dp")
                .that(node.touchBoundsInRoot.height / density).isAtLeast(48f - 0.5f)
        }
        assertThat(model.right).isAtMost(token.left + 0.5f * density)
        assertThat(token.right).isAtMost(send.boundsInRoot.left)
        rule.onNodeWithTag(UiTags.CHAT_MODEL_SELECTOR).performClick()
        rule.runOnIdle { assertThat(modelOpened).isTrue() }
        rule.onNodeWithTag(UiTags.CHAT_TOKEN_INDICATOR).performClick()
        rule.onNodeWithTag("context_usage_compact_now").assertIsDisplayed()
    }

    @Test fun topBarShowsOnlyOneSingleLineTitleAndKeepsRenameAction() {
        var renamed = false
        val title = "会话标题很长也必须在同一行截断，不得显示智能体副标题"
        rule.setContent {
            InfinityTheme {
                Box(Modifier.width(411.dp)) {
                    ChatTopBar(title, {}, {}, {}, onClearHistory = {}, onRename = { renamed = true }, onDeleteSession = {})
                }
            }
        }
        val bar = rule.onNodeWithTag("chat_top_bar").fetchSemanticsNode().boundsInRoot
        val titleBounds = rule.onNodeWithTag("chat_top_title").fetchSemanticsNode().boundsInRoot
        val density = rule.density.density
        assertThat(abs(bar.height / density - 56f)).isAtMost(0.5f)
        assertThat(titleBounds.height / density).isAtMost(48f + 0.5f)
        rule.onAllNodesWithText(title).assertCountEquals(1)
        rule.onNodeWithTag("chat_top_title").performClick()
        rule.runOnIdle { assertThat(renamed).isTrue() }
    }

    @Test fun wideTopBarKeepsOnlyInspectorPinAndMenuWhileNarrowMovesArtifactsBrowserAndFilesIntoMenu() {
        val resources = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.resources
        val files = resources.getString(com.promenar.infinity.R.string.chat_cd_workspace)
        val canvas = resources.getString(com.promenar.infinity.R.string.canvas_title)
        var wide by mutableStateOf(true)
        rule.setContent {
            InfinityTheme {
                Box(Modifier.width(if (wide) 814.dp else 411.dp)) {
                    ChatTopBar(
                        "会话", {}, {}, {}, onClearHistory = {}, onRename = {}, onDeleteSession = {},
                        artifacts = chatTopBarArtifacts(inspectorMode = wide, count = 1, onOpen = {}),
                        inspector = if (wide) ChatTopBarInspector(pinned = true, canPin = true, onTogglePin = {}) else null,
                        browser = chatTopBarBrowser(inspectorMode = wide, mode = com.promenar.infinity.data.browser.BrowserMode.IDLE, open = false, onOpen = {}),
                    )
                }
            }
        }
        rule.onNodeWithTag(ChatInspectorTags.PIN).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_OPTIONS).assertIsDisplayed()
        rule.onAllNodesWithContentDescription(files).assertCountEquals(0)
        rule.onAllNodesWithContentDescription(canvas).assertCountEquals(0)

        wide = false
        // 窄窗口顶栏只留选项菜单；产物、浏览器与文件是菜单首组，运行模式只在状态行切换，菜单不重复。
        rule.onAllNodesWithContentDescription(files).assertCountEquals(0)
        rule.onAllNodesWithContentDescription(canvas).assertCountEquals(0)
        rule.onAllNodesWithTag(com.promenar.infinity.ui.browser.BrowserTags.TOP_BAR_ENTRY).assertCountEquals(0)
        rule.onAllNodesWithTag(ChatInspectorTags.PIN).assertCountEquals(0)
        rule.onNodeWithTag(UiTags.CHAT_OPTIONS).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_ENTRY).assertIsDisplayed()
        rule.onNodeWithTag(com.promenar.infinity.ui.browser.BrowserTags.TOP_BAR_ENTRY).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_MENU_WORKSPACE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_SESSION_SETTINGS).assertIsDisplayed()
        rule.onAllNodesWithText(resources.getString(com.promenar.infinity.R.string.run_mode_title), substring = true).assertCountEquals(0)
    }

    @Test fun inspectorTabsAndActionsShareBottomBarWithCollapseAtRight() {
        var collapsed = false
        rule.setContent {
            InfinityTheme {
                ChatInspectorCard(
                    root = ChatPage.Run,
                    badges = ChatInspectorBadges(
                        running = true, hasRunContent = true, gitAvailable = true, gitChanges = 3, canvases = 2,
                        browser = com.promenar.infinity.data.browser.BrowserMode.AGENT, browserEntry = true,
                    ),
                    widened = false,
                    onSelect = {},
                    onToggleWiden = {},
                    onCollapse = { collapsed = true },
                    modifier = Modifier.width(360.dp).height(600.dp),
                    onLockTouch = {},
                ) {}
            }
        }
        val card = rule.onNodeWithTag(com.promenar.infinity.ui.common.InfinityPaneTags.INSPECTOR).getUnclippedBoundsInRoot()
        val collapse = rule.onNodeWithTag(ChatInspectorTags.COLLAPSE).getUnclippedBoundsInRoot()
        val widen = rule.onNodeWithTag(ChatInspectorTags.WIDEN).getUnclippedBoundsInRoot()
        val tab = rule.onNodeWithTag(ChatInspectorTags.tab(ChatPage.Run)).getUnclippedBoundsInRoot()
        assertThat((card.bottom - collapse.bottom).value).isAtMost(8f)
        assertThat((card.right - collapse.right).value).isAtMost(8f)
        assertThat(widen.right.value).isAtMost(collapse.left.value)
        assertThat(abs((widen.top - collapse.top).value)).isAtMost(1f)
        assertThat((card.bottom - tab.bottom).value).isAtMost(8f)
        assertThat(tab.right.value).isAtMost(widen.left.value)
        rule.onNodeWithTag(ChatInspectorTags.COLLAPSE).performClick()
        assertThat(collapsed).isTrue()
    }
}
