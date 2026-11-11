package com.promenar.infinity.ui.canvas

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.data.canvas.FileCanvasStore
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.ui.chat.CanvasInvocationCards
import com.promenar.infinity.ui.chat.ChatPage
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatScreenContent
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.PanelHubTags
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.R
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test

/** 使用稳定的 Compose 占位验证真实宿主接线，WebView 隔离由独立 JVM 策略测试覆盖。 */
class CanvasChatEntryTest {
    @get:Rule val rule = createComposeRule()
    private val root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "canvas-test-${UUID.randomUUID()}")
    private val store = FileCanvasStore(root)

    @After fun cleanup() { root.deleteRecursively() }

    @Test fun panelHub_artifactsEntryUnusedUntilAgentCreatesOne_thenShowsCountAndOpensList() {
        var opened = 0
        rule.setContent {
            InfinityTheme {
                CanvasSessionHost("session", store, onInsertText = {}, onOpenList = { opened++ }) {
                    ChatScreenContent(ChatScreenState(), ChatScreenActions())
                }
            }
        }
        // 产物条目在功能面板入口里常驻；Agent 还没有创建产物时灰调不可点。
        rule.onNodeWithTag(PanelHubTags.BUTTON).performClick()
        rule.onNodeWithTag(PanelHubTags.item(ChatPage.Canvas)).assertIsDisplayed().assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack()
        runBlocking { store.create("session", "图表", "<p>1</p>", "message") }
        rule.waitForIdle()
        rule.onNodeWithTag(PanelHubTags.BUTTON).performClick()
        rule.waitUntil(5_000) {
            rule.onAllNodesWithTag(PanelHubTags.item(ChatPage.Canvas)).fetchSemanticsNodes()
                .any { androidx.compose.ui.semantics.SemanticsProperties.Disabled !in it.config }
        }
        rule.onNodeWithTag(PanelHubTags.item(ChatPage.Canvas)).assertIsEnabled().performClick()
        rule.runOnIdle { assertThat(opened).isEqualTo(1) }
    }

    @Test fun artifactsPage_listsSessionArtifacts() {
        val canvas = runBlocking { store.create("session", "图表", "<p>1</p>", "message") }
        rule.setContent {
            InfinityTheme {
                CanvasSessionHost("session", store, onInsertText = {}, onOpenList = {}) {
                    CanvasListPage(onBack = {})
                }
            }
        }
        rule.onNodeWithTag(UiTags.CANVAS_PAGE).assertIsDisplayed()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag(UiTags.artifactItem(canvas.metadata.id)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun successfulToolCard_opensLatestAndRefreshesWhileVisible() {
        val canvas = runBlocking { store.create("session", "图表", "<p>1</p>", "message") }
        val steps = listOf(
            ExecutionStep(id = "call", type = "tool_call", toolName = "canvas_create", toolCallId = "call"),
            ExecutionStep(id = "result", type = "tool_result", toolCallId = "call", content = """{"canvasId":"${canvas.metadata.id}","title":"图表","version":1}"""),
        )
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InfinityTheme {
                    CanvasSessionHost("session", store, onInsertText = {}, onOpenList = {}) {
                        Box(Modifier.fillMaxSize()) { CanvasInvocationCards(steps) }
                    }
                }
            }
        }
        rule.onNodeWithTag(UiTags.CANVAS_TURN_ARTIFACTS).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CANVAS_CARD).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CANVAS_CARD).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_FULLSCREEN).assertIsDisplayed()
        runBlocking { store.update("session", canvas.metadata.id, html = "<p>2</p>") }
        val latestLabel = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.canvas_follow_latest, 2)
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithText(latestLabel).fetchSemanticsNode() }.isSuccess
        }
        rule.onNodeWithTag(UiTags.CANVAS_CLOSE).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_CARD).assertIsDisplayed()
    }

    @Test fun inlineCard_anchorsUnderStepAndOpensItsVersion() {
        val canvas = runBlocking { store.create("session", "图表", "<p>1</p>", "message") }
        val steps = listOf(
            ExecutionStep(id = "call", type = "tool_call", toolName = "canvas_create", toolCallId = "call"),
            ExecutionStep(id = "result", type = "tool_result", toolCallId = "call", content = """{"canvasId":"${canvas.metadata.id}","title":"图表","version":1}"""),
        )
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InfinityTheme {
                    CanvasSessionHost("session", store, onInsertText = {}, onOpenList = {}) {
                        Box(Modifier.fillMaxSize()) { CanvasInvocationCards(steps, inline = true) }
                    }
                }
            }
        }
        // 生成中只显示紧凑卡，不出现本轮产物区。
        rule.onNodeWithTag(UiTags.CANVAS_TURN_ARTIFACTS).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.CANVAS_CARD_INLINE).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.CANVAS_FULLSCREEN).assertIsDisplayed()
    }

    @Test fun selectedHistory_remainsReadableAfterItsStoredVersionIsPruned() {
        val limited = FileCanvasStore(File(root, "limited"), maxVersions = 2)
        val canvas = runBlocking {
            limited.create("session", "历史图表", "<p>1</p>", "message").also {
                limited.update("session", it.metadata.id, html = "<p>2</p>")
            }
        }
        val observedRevision = AtomicInteger()
        val steps = listOf(
            ExecutionStep(id = "call", type = "tool_call", toolName = "canvas_create", toolCallId = "call"),
            ExecutionStep(id = "result", type = "tool_result", toolCallId = "call", content = """{"canvasId":"${canvas.metadata.id}","title":"历史图表","version":1}"""),
        )
        rule.setContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                InfinityTheme {
                    CanvasSessionHost("session", limited, onInsertText = {}, onOpenList = {}) {
                        val revision = LocalCanvasSession.current?.canvases?.singleOrNull()?.currentVersion ?: 0
                        SideEffect { observedRevision.set(revision) }
                        Box(Modifier.fillMaxSize()) { CanvasInvocationCards(steps) }
                    }
                }
            }
        }
        rule.waitUntil(5_000) { observedRevision.get() == 2 }
        // 旧消息的卡片直接打开它当时的版本。
        rule.onNodeWithTag(UiTags.CANVAS_CARD).performClick()
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val historyLabel = resources.getString(R.string.canvas_history_version, 1)
        rule.onNodeWithText(historyLabel).assertIsDisplayed()
        rule.waitUntil(5_000) {
            runCatching { rule.onNodeWithTag(UiTags.CANVAS_COPY).assertIsEnabled() }.isSuccess
        }
        runBlocking { limited.update("session", canvas.metadata.id, html = "<p>3</p>") }
        rule.waitUntil(5_000) { observedRevision.get() == 3 }
        rule.onNodeWithText(historyLabel).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CANVAS_COPY).assertIsEnabled()
        rule.onNodeWithText(resources.getString(R.string.canvas_load_error)).assertDoesNotExist()
    }
}
