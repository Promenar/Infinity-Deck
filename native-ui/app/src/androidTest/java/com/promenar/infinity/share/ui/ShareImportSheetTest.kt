package com.promenar.infinity.share.ui

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import com.promenar.infinity.share.core.ShareIndexStatus
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareImportOptions
import com.promenar.infinity.share.core.ShareLinuxAvailability
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.share.core.ShareTargetKind
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ShareImportSheetTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun targetMustBeExplicitAndPerItemFailureIsVisible() {
        var imported = false
        rule.setContent {
            var selected by remember { mutableStateOf<ShareTargetKind?>(null) }
            InfinityTheme {
                ShareImportSheet(
                    state = ShareImportUiState(
                        visible = true,
                        selectedKind = selected,
                        options = ShareImportOptions(null, "root", ShareLinuxAvailability.Unsupported),
                        items = listOf(
                            ShareImportItem(
                                uri = Uri.parse("content://fixture/unsafe.pdf"),
                                displayName = "unsafe.pdf",
                                mimeType = "application/pdf",
                                sizeBytes = 12,
                                status = ShareImportStatus.Rejected,
                                reason = ShareRejectReason.WriteFailed,
                            ),
                            ShareImportItem(
                                uri = Uri.parse("content://fixture/docx.docx"),
                                displayName = "report.docx",
                                mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                sizeBytes = 34567,
                                status = ShareImportStatus.Pending,
                            )
                        ),
                    ),
                    onSelectTarget = { selected = it },
                    onImport = { imported = true },
                    onRetry = {},
                    onClose = {},
                )
            }
        }

        rule.onNodeWithText("unsafe.pdf").assertIsDisplayed()
        rule.onNodeWithText("The file could not be saved").assertIsDisplayed()
        rule.onNodeWithText("DOCX", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            substring = true,
        ).assertDoesNotExist()
        rule.onNodeWithText("Retry failed").assertIsNotEnabled()
        rule.onNodeWithText("Knowledge Base").performClick()
        rule.onNodeWithText("Retry failed").assertIsEnabled()
        rule.runOnIdle { check(!imported) }
    }

    @Test
    fun presentationFailureRemainsVisibleAndOffersEnabledRetryWithoutTarget() {
        var retried = false
        rule.setContent {
            InfinityTheme {
                ShareImportSheet(
                    state = ShareImportUiState(
                        visible = true,
                        error = ShareImportErrorCode.PRESENT_FAILED,
                    ),
                    onSelectTarget = {},
                    onImport = {},
                    onRetry = { retried = true },
                    onClose = {},
                )
            }
        }

        rule.onNodeWithText("Unable to open the shared items. Try again.").assertIsDisplayed()
        rule.onNodeWithText("Retry failed")
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        rule.runOnIdle { assertTrue(retried) }
    }

    @Test
    fun actionButtonsRemainReachableAtTwoTimesFontScaleInNarrowWidth() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var imported = false
        var retried = false

        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                Box(Modifier.width(360.dp)) {
                    InfinityTheme {
                        ShareImportSheet(
                            state = ShareImportUiState(
                                visible = true,
                                selectedKind = ShareTargetKind.KnowledgeBase,
                                options = ShareImportOptions(null, "root", ShareLinuxAvailability.Unsupported),
                                items = listOf(
                                    ShareImportItem(
                                        uri = Uri.parse("content://fixture/fail.pdf"),
                                        displayName = "fail.pdf",
                                        mimeType = "application/pdf",
                                        sizeBytes = 12,
                                        status = ShareImportStatus.Created,
                                        indexStatus = ShareIndexStatus.Failed,
                                    ),
                                    ShareImportItem(
                                        uri = Uri.parse("content://fixture/pending.docx"),
                                        displayName = "pending.docx",
                                        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                        sizeBytes = 34567,
                                        status = ShareImportStatus.Pending,
                                    ),
                                ),
                            ),
                            onSelectTarget = {},
                            onImport = { imported = true },
                            onRetry = { retried = true },
                            onClose = {},
                        )
                    }
                }
            }
        }

        val laterLabel = resources.getString(R.string.share_import_later)
        val cancelLabel = resources.getString(R.string.share_import_cancel)
        val retryLabel = resources.getString(R.string.share_import_retry)
        val importLabel = resources.getString(R.string.share_import_action)
        val rootBounds = rule.onNodeWithTag(UiTags.SHARE_IMPORT_SHEET).fetchSemanticsNode().boundsInRoot
        val minButtonPx = with(rule.density) { 48.dp.toPx() }

        listOf(
            rule.onNodeWithText(retryLabel),
            rule.onNodeWithText(importLabel),
            rule.onNodeWithTag(UiTags.SHARE_IMPORT_MORE),
        ).forEach { node ->
            node.assertIsDisplayed().assertHasClickAction().assertHeightIsAtLeast(48.dp)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue(
                "action $bounds should stay within sheet left/right bounds",
                bounds.left >= rootBounds.left && bounds.right <= rootBounds.right,
            )
            assertTrue("action $bounds should keep min height", bounds.height >= minButtonPx)
        }

        // “稍后处理 / 取消导入”收在“⋯”菜单里，打开后仍可点。
        rule.onNodeWithTag(UiTags.SHARE_IMPORT_MORE).performClick()
        listOf(laterLabel, cancelLabel).forEach { label ->
            rule.onNodeWithText(label).assertIsDisplayed().assertHasClickAction()
        }

        rule.runOnIdle {
            assertTrue(!imported)
            assertTrue(!retried)
        }
    }
}
