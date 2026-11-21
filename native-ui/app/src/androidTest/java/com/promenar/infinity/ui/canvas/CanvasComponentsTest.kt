package com.promenar.infinity.ui.canvas

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import com.promenar.infinity.data.canvas.CanvasMetadata
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CanvasComponentsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
    private val canvas = CanvasMetadata(
        id = "canvas-test-dashboard",
        title = "经营看板",
        createdAt = 1_792_000_000_000L,
        updatedAt = 1_792_000_060_000L,
        currentVersion = 3,
        createdByMessageId = "message-test",
        versions = listOf(1, 2, 3),
    )

    @Test
    fun cardShowsInvocationVersionAndLatestPreviewWithoutAWebView() {
        var opened = false
        rule.setContent {
            InfinityTheme {
                CanvasCard(
                    metadata = canvas,
                    version = 1,
                    html = null,
                    onOpen = { opened = true },
                    previewContent = { Text("固定预览内容") },
                )
            }
        }

        rule.onNodeWithText(canvas.title).assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.canvas_version, 1)).assertIsDisplayed()
        rule.onNodeWithText(resources.getString(R.string.canvas_card_newer, 3)).assertIsDisplayed()
        // 卡片可点击会合并子节点语义，预览节点只在未合并树中可寻址。
        rule.onNodeWithTag(UiTags.CANVAS_PREVIEW, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CANVAS_CARD)
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
            .performClick()
        rule.runOnIdle { assertTrue(opened) }
    }

    @Test
    fun openShowsFullscreenAndCloseReturnsToCard() {
        rule.setContent {
            var opened by remember { mutableStateOf(false) }
            InfinityTheme {
                if (opened) {
                    CanvasFullscreenContent(
                        document = null,
                        metadata = canvas,
                        selectedVersion = null,
                        onSelectVersion = {},
                        onCopy = {},
                        onClose = { opened = false },
                        onInsertText = {},
                    )
                } else {
                    CanvasCard(
                        metadata = canvas,
                        version = 3,
                        html = null,
                        onOpen = { opened = true },
                        previewContent = { Text("固定预览内容") },
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.CANVAS_CARD).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_FULLSCREEN).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CANVAS_COPY).assertIsNotEnabled()
        rule.onNodeWithTag(UiTags.CANVAS_CLOSE).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag(UiTags.CANVAS_CARD).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CANVAS_FULLSCREEN).assertDoesNotExist()
    }

    @Test
    fun historySelectorRemainsReachableAtLargeFontAndNarrowWidth() {
        var selected: Int? = null
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.3f)) {
                InfinityTheme {
                    var selectedVersion by remember { mutableStateOf<Int?>(null) }
                    Box(Modifier.width(360.dp)) {
                        CanvasFullscreenContent(
                            document = null,
                            metadata = canvas,
                            selectedVersion = selectedVersion,
                            onSelectVersion = {
                                selectedVersion = it
                                selected = it
                            },
                            onCopy = {},
                            onClose = {},
                            onInsertText = {},
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag(UiTags.CANVAS_VERSION_SELECTOR)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithText(resources.getString(R.string.canvas_history_version, 1))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithText(resources.getString(R.string.canvas_history_version, 1)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(1, selected) }

        rule.onNodeWithTag(UiTags.CANVAS_VERSION_SELECTOR).performClick()
        rule.onNodeWithText(resources.getString(R.string.canvas_follow_latest, 3)).performClick()
        rule.onNodeWithText(resources.getString(R.string.canvas_follow_latest, 3)).assertIsDisplayed()
        rule.runOnIdle { assertEquals(null, selected) }
    }
}
