package com.promenar.infinity.ui.chat

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.share.core.ShareImportItem
import com.promenar.infinity.share.core.ShareImportStatus
import com.promenar.infinity.share.core.ShareRejectReason
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Rule
import org.junit.Test

class ResourceExplorerInteractionTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun workspaceLoadingDoesNotExposeFalseEmptyState() {
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                ResourceExplorerSheetContent(
                    state = state(
                        isLoading = true,
                    ),
                    actions = ResourceExplorerSheetActions(),
                    filesContent = { Box(Modifier.testTag(TAG_FALSE_EMPTY_STATE)) },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_SESSION_STATUS)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    resources.getString(R.string.shared_loading),
                ),
            )
        rule.onNodeWithTag(TAG_FALSE_EMPTY_STATE).assertDoesNotExist()
    }

    @Test
    fun workspaceLoadErrorDoesNotExposeFalseEmptyStateAndRetriesOnce() {
        val retryCount = AtomicInteger()

        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                ResourceExplorerSheetContent(
                    state = state(
                        hasLoadError = true,
                    ),
                    actions = ResourceExplorerSheetActions(
                        onRetryLoad = { retryCount.incrementAndGet() },
                    ),
                    filesContent = { Box(Modifier.testTag(TAG_FALSE_EMPTY_STATE)) },
                )
            }
        }

        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_SESSION_STATUS)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    resources.getString(R.string.resource_explorer_error_workspace_load_failed),
                ),
            )
        rule.onNodeWithTag(TAG_FALSE_EMPTY_STATE).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_RETRY_LOAD)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(retryCount.get()).isEqualTo(1)
    }

    @Test
    fun clearResultsButtonCallsTheRealClearAction() {
        val clearCount = AtomicInteger()

        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                ResourceExplorerSheetContent(
                    state = state(importItems = listOf(importItem())),
                    actions = ResourceExplorerSheetActions(
                        onClearImportResults = { clearCount.incrementAndGet() },
                    ),
                    filesContent = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_IMPORT_RESULTS).assertIsDisplayed()
        rule.onNodeWithContentDescription(resources.getString(R.string.resource_explorer_clear_results))
            .assertIsDisplayed()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_CLEAR_RESULTS)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(clearCount.get()).isEqualTo(1)
    }

    @Test
    fun longRejectedImportKeepsFullStatusAndActionsReachableAtDoubleFont() {
        val rejected = rejectedImportItem()
        val retryCount = AtomicInteger()
        val clearCount = AtomicInteger()

        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                InfinityTheme(dynamicColor = false) {
                    ResourceExplorerSheetContent(
                        state = state(importItems = listOf(rejected)),
                        actions = ResourceExplorerSheetActions(
                            onRetryImport = { retryCount.incrementAndGet() },
                            onClearImportResults = { clearCount.incrementAndGet() },
                        ),
                        filesContent = {},
                    )
                }
            }
        }

        rule.onNodeWithTag(importStatusTag(rejected.uri))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertTextEquals(
                resources.getString(
                    R.string.resource_explorer_status_rejected,
                    resources.getString(R.string.share_import_reason_write),
                ),
            )
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_IMPORT_RESULTS)
            .performScrollToNode(hasTestTag(importRetryTag(rejected.uri)))
        rule.onNodeWithTag(importRetryTag(rejected.uri))
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assert(
                SemanticsMatcher("retry action names the imported document") { node ->
                    runCatching { node.config[SemanticsActions.OnClick].label }.getOrNull() ==
                        resources.getString(R.string.resource_explorer_retry_document, rejected.displayName)
                },
            )
            .performClick()
        rule.onNodeWithTag(UiTags.RESOURCE_EXPLORER_CLEAR_RESULTS)
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(retryCount.get()).isEqualTo(1)
        assertThat(clearCount.get()).isEqualTo(1)
    }

    @Test
    fun trueSheetAtDoubleFontCanScrollToLastFilesItem() {
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                InfinityTheme(dynamicColor = false) {
                    InfinityBottomSheet(
                        show = true,
                        onDismiss = {},
                        title = resources.getString(R.string.resource_explorer_title),
                        modifier = Modifier.fillMaxHeight(),
                    ) {
                        ResourceExplorerSheetContent(
                            state = state(importItems = listOf(rejectedImportItem())),
                            actions = ResourceExplorerSheetActions(),
                            filesContent = {
                                LazyColumn(Modifier.testTag(TAG_SHEET_FILES_LIST)) {
                                    items((0 until 30).toList()) { index ->
                                        Text(
                                            text = "sheet-file-$index",
                                            modifier = Modifier.testTag("sheet-file-$index"),
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag(TAG_SHEET_FILES_LIST).performScrollToIndex(29)
        rule.onNodeWithTag("sheet-file-29").assertIsDisplayed()
    }

    @Test
    fun phoneWithDoubleFontKeepsPrimaryControlsVisibleAndReachable() {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                InfinityTheme(dynamicColor = false) {
                    Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                        ResourceExplorerSheetContent(
                            state = state(),
                            actions = ResourceExplorerSheetActions(),
                            filesContent = {},
                        )
                    }
                }
            }
        }

        listOf(
            UiTags.RESOURCE_EXPLORER_SEARCH_OPEN,
            UiTags.RESOURCE_EXPLORER_IMPORT,
        ).forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
    }

    /** 键盘弹出会压缩面板可用高度；布局形态不得随之切换，否则搜索框被重建、焦点与键盘立刻丢失。 */
    @Test
    fun searchKeepsFocusWhenKeyboardShrinksAvailableHeight() {
        var height by mutableStateOf(800.dp)
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                Box(Modifier.requiredSize(width = 360.dp, height = height)) {
                    ResourceExplorerSheetContent(
                        state = state(searchActive = true),
                        actions = ResourceExplorerSheetActions(),
                        filesContent = {},
                    )
                }
            }
        }
        val search = SemanticsMatcher.keyIsDefined(SemanticsProperties.Focused) and hasAncestorTag(UiTags.RESOURCE_EXPLORER_SEARCH)
        rule.onNode(search).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.onNode(search).assertIsFocused()

        height = 300.dp
        rule.waitForIdle()

        rule.onNode(search).assertIsFocused()
    }

    private fun hasAncestorTag(tag: String) = androidx.compose.ui.test.hasAnyAncestor(hasTestTag(tag))

    private fun state(
        importItems: List<ShareImportItem> = emptyList(),
        isLoading: Boolean = false,
        hasLoadError: Boolean = false,
        searchActive: Boolean = false,
    ) = ResourceExplorerSheetState(
        searchQuery = "",
        searchActive = searchActive,
        workspaceReady = true,
        importItems = importItems,
        isImporting = false,
        isLoading = isLoading,
        hasLoadError = hasLoadError,
    )

    private fun importItem() = ShareImportItem(
        uri = Uri.parse("content://fixture/very-long-import-item"),
        displayName = "A-very-long-import-file-name-that-must-not-overflow-the-sheet.md",
        mimeType = "text/markdown",
        sizeBytes = 1024L,
        status = ShareImportStatus.Created,
    )

    private fun rejectedImportItem() = ShareImportItem(
        uri = Uri.parse("content://fixture/long-rejected-import"),
        displayName = "A-very-long-import-file-name-that-must-remain-readable-at-double-font.pdf",
        mimeType = "application/pdf",
        sizeBytes = 4096L,
        status = ShareImportStatus.Rejected,
        reason = ShareRejectReason.WriteFailed,
    )

    private fun importStatusTag(uri: Uri) = "resource_explorer_import_status:$uri"

    private fun importRetryTag(uri: Uri) = "resource_explorer_import_retry:$uri"

    private companion object {
        const val TAG_FALSE_EMPTY_STATE = "recycle_bin_empty_state"
        const val TAG_SHEET_FILES_LIST = "resource_explorer_sheet_files_list"
        const val FIXED_NOW = 1_720_000_000_000L
    }
}
