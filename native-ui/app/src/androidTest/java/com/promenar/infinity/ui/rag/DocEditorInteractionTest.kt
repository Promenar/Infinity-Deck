package com.promenar.infinity.ui.rag

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.SystemClock
import android.view.WindowInsets as AndroidWindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.espresso.Espresso
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import org.junit.After
import org.junit.Rule
import org.junit.Test

class DocEditorInteractionTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @After
    fun closeImeAfterTest() {
        // 返回键收键盘由输入法决定；API 35 模拟器的 Gboard 会同时把返回交给页面并结束 Activity，
        // 清理阶段改走 IMM 收起，不让下一个测试继承键盘状态。
        if (imeVisible()) {
            Espresso.closeSoftKeyboard()
            awaitImeClosed()
        }
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    @Test
    fun loadingDoesNotRenderFakeEditor() {
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = DocEditorUiState(phase = DocEditorPhase.Loading),
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_LOADING).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertDoesNotExist()
    }

    @Test
    fun loadErrorRetries() {
        val retries = AtomicInteger(0)
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = DocEditorUiState(
                        phase = DocEditorPhase.LoadError,
                        failureCode = DocEditorFailureCode.LoadFailed,
                    ),
                ),
                actions = DocEditorScreenActions(onRetryLoad = { retries.incrementAndGet() }),
            )
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RETRY_LOAD)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun dirtyTopBackCancelRetainsContentAndConfirmNavigates() {
        val navigations = AtomicInteger(0)
        rule.setContent { TestRoute(navigations = navigations) }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_DIALOG).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_CANCEL).performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertExists()
        assertThat(navigations.get()).isEqualTo(0)

        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_CONFIRM).performClick()
        assertThat(navigations.get()).isEqualTo(1)
    }

    @Test
    fun dirtySystemBackUsesTheSameDiscardConfirmation() {
        val navigations = AtomicInteger(0)
        rule.setContent { TestRoute(navigations = navigations) }

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_DIALOG).assertExists()
        assertThat(navigations.get()).isEqualTo(0)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_CONFIRM).performClick()
        assertThat(navigations.get()).isEqualTo(1)
    }

    @Test
    fun saveErrorRetries() {
        val retries = AtomicInteger(0)
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState(
                        phase = DocEditorPhase.SaveError,
                        dirty = true,
                    ),
                ),
                actions = DocEditorScreenActions(
                    onRetrySave = { retries.incrementAndGet() },
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_RETRY_SAVE).performClick()
        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun readyWithPendingIndexShowsWarningAndRetriesIndex() {
        val retries = AtomicInteger(0)
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState().copy(
                        indexQueueFailed = true,
                        indexPendingTargets = listOf(
                            com.promenar.infinity.domain.repository.RenameIndexTarget(
                                fileUuid = "doc",
                                targetHash = "hash-2",
                                targetEpoch = 456L,
                            ),
                        ),
                    ),
                ),
                actions = DocEditorScreenActions(
                    onRetryPendingIndex = { retries.incrementAndGet() },
                ),
            )
        }

        rule.onNodeWithText(
            rule.activity.getString(com.promenar.infinity.R.string.rag_index_retry_hint),
        ).assertIsDisplayed()
        rule.onNodeWithContentDescription(pendingIndexRetryDescription())
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun pendingIndexWarningIsSingleAndRetryableAcrossSaveConflictAndLoadError() {
        val retries = AtomicInteger(0)
        var editorState by mutableStateOf(
            readyEditorState(phase = DocEditorPhase.SaveConflict, dirty = true).withPendingIndex(),
        )
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(editorState = editorState),
                actions = DocEditorScreenActions(
                    onRetryPendingIndex = { retries.incrementAndGet() },
                ),
            )
        }
        val hint = rule.activity.getString(com.promenar.infinity.R.string.rag_index_retry_hint)
        val retry = pendingIndexRetryDescription()

        rule.onAllNodesWithText(hint).assertCountEquals(1)
        rule.onAllNodes(hasContentDescription(retry)).assertCountEquals(1)
        rule.onNodeWithContentDescription(retry).performClick()
        assertThat(retries.get()).isEqualTo(1)

        rule.runOnIdle {
            editorState = DocEditorUiState(
                phase = DocEditorPhase.LoadError,
                workspaceRootUuid = "root",
                documentId = "doc",
                failureCode = DocEditorFailureCode.LoadFailed,
            ).withPendingIndex()
        }
        rule.onAllNodesWithText(hint).assertCountEquals(1)
        rule.onAllNodes(hasContentDescription(retry)).assertCountEquals(1)
    }

    @Test
    fun compactDoubleFontConflictKeepsBothConflictActionsAndSinglePendingRetryVisible() {
        val copies = AtomicInteger(0)
        val reloads = AtomicInteger(0)
        val pendingRetries = AtomicInteger(0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 640.dp)) {
                    TestContent(
                        screenState = DocEditorScreenState(
                            editorState = readyEditorState(
                                phase = DocEditorPhase.SaveConflict,
                                dirty = true,
                            ).withPendingIndex(),
                        ),
                        actions = DocEditorScreenActions(
                            onCopyLocalContent = { copies.incrementAndGet() },
                            onRequestReload = { reloads.incrementAndGet() },
                            onRetryPendingIndex = { pendingRetries.incrementAndGet() },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithText(
            rule.activity.getString(com.promenar.infinity.R.string.doc_editor_conflict_description),
        ).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_COPY_LOCAL)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_REQUEST_RELOAD)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        // 紧凑保存终态应把恢复操作放在首位，不能留下隐藏但仍占高度的编辑 chrome。
        rule.onNodeWithTag(UiTags.DOC_EDITOR_IDENTITY).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SELECTOR).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATUS).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MAIN_PANE).assertDoesNotExist()

        val pendingHint = rule.activity.getString(com.promenar.infinity.R.string.rag_index_retry_hint)
        val pendingRetry = pendingIndexRetryDescription()
        rule.onAllNodesWithText(pendingHint).assertCountEquals(1)
        rule.onNodeWithText(pendingHint).assertIsDisplayed()
        rule.onAllNodes(hasContentDescription(pendingRetry)).assertCountEquals(1)
        rule.onNodeWithContentDescription(pendingRetry)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        assertThat(copies.get()).isEqualTo(1)
        assertThat(reloads.get()).isEqualTo(1)
        assertThat(pendingRetries.get()).isEqualTo(1)
    }

    @Test
    fun compactTerminalStatesDoNotReserveEditorChromeSpace() {
        var editorState by mutableStateOf(
            readyEditorState(phase = DocEditorPhase.SaveError, dirty = true),
        )
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 640.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                TestContent(screenState = DocEditorScreenState(editorState = editorState))
            }
        }

        val terminalStates = listOf(
            readyEditorState(phase = DocEditorPhase.SaveError, dirty = true) to
                UiTags.DOC_EDITOR_RETRY_SAVE,
            readyEditorState(phase = DocEditorPhase.SaveConflict, dirty = true) to
                UiTags.DOC_EDITOR_COPY_LOCAL,
            readyEditorState(phase = DocEditorPhase.NotFound, dirty = true) to
                UiTags.DOC_EDITOR_COPY_LOCAL,
        )
        terminalStates.forEach { (state, recoveryActionTag) ->
            rule.runOnIdle { editorState = state }
            rule.onNodeWithTag(recoveryActionTag)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
            rule.onNodeWithTag(UiTags.DOC_EDITOR_IDENTITY).assertDoesNotExist()
            rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SELECTOR).assertDoesNotExist()
            rule.onNodeWithTag(UiTags.DOC_EDITOR_STATUS).assertDoesNotExist()
            rule.onNodeWithTag(UiTags.DOC_EDITOR_MAIN_PANE).assertDoesNotExist()
        }
    }

    @Test
    fun saveCancelledShowsRetainedLocalChangesFeedback() {
        val retries = AtomicInteger(0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    TestContent(
                        screenState = DocEditorScreenState(
                            editorState = readyEditorState(
                                phase = DocEditorPhase.SaveError,
                                dirty = true,
                            ).copy(failureCode = DocEditorFailureCode.SaveCancelled),
                        ),
                        actions = DocEditorScreenActions(
                            onRetrySave = { retries.incrementAndGet() },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithText(
            rule.activity.getString(com.promenar.infinity.R.string.doc_editor_save_cancelled_description),
        ).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RETRY_SAVE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(retries.get()).isEqualTo(1)
    }

    @Test
    fun conflictAtDoubleFontScaleCopiesThenConfirmsReload() {
        val copies = AtomicInteger(0)
        val reloads = AtomicInteger(0)
        rule.setContent {
            var state by remember {
                mutableStateOf(
                    DocEditorScreenState(
                        editorState = readyEditorState(
                            phase = DocEditorPhase.SaveConflict,
                            dirty = true,
                        ),
                    ),
                )
            }
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    TestContent(
                        screenState = state,
                        actions = DocEditorScreenActions(
                            onCopyLocalContent = { copies.incrementAndGet() },
                            onRequestReload = {
                                state = state.copy(confirmation = DocEditorConfirmation.ReloadLatest)
                            },
                            onDismissConfirmation = { state = state.copy(confirmation = null) },
                            onConfirmReload = {
                                state = state.copy(confirmation = null)
                                reloads.incrementAndGet()
                            },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_COPY_LOCAL)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_REQUEST_RELOAD)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RELOAD_DIALOG).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RELOAD_CONFIRM).performClick()
        assertThat(copies.get()).isEqualTo(1)
        assertThat(reloads.get()).isEqualTo(1)
    }

    @Test
    fun titleConflictUsesDedicatedWorkspaceAndRetryActions() {
        val useWorkspace = AtomicInteger(0)
        val retryMine = AtomicInteger(0)
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                    TestContent(
                        screenState = DocEditorScreenState(
                            editorState = readyEditorState(
                                phase = DocEditorPhase.SaveConflict,
                                dirty = true,
                            ).copy(
                                failureCode = DocEditorFailureCode.TitleConflict,
                                titleConflictCurrentName = "remote.md",
                            ),
                        ),
                        actions = DocEditorScreenActions(
                            onUseWorkspaceTitle = { useWorkspace.incrementAndGet() },
                            onRetryMyTitle = { retryMine.incrementAndGet() },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_COPY_LOCAL).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_USE_WORKSPACE_TITLE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_RETRY_MY_TITLE)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(useWorkspace.get()).isEqualTo(1)
        assertThat(retryMine.get()).isEqualTo(1)
    }

    @Test
    fun savingPreventsRepeatSave() {
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState(
                        phase = DocEditorPhase.Saving,
                        dirty = true,
                    ),
                ),
            )
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_SAVING).assertExists()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE).assertDoesNotExist()
    }

    @Test
    fun savingTopBackStaysOnEditorWithoutDiscardAndShowsVisibleFeedback() {
        val navigations = AtomicInteger(0)
        rule.setContent {
            TestRoute(
                navigations = navigations,
                editorState = readyEditorState(
                    phase = DocEditorPhase.Saving,
                    dirty = true,
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).performClick()

        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_DIALOG).assertDoesNotExist()
        rule.onNodeWithText(
            rule.activity.getString(com.promenar.infinity.R.string.doc_editor_saving_back_feedback),
        )
            .assertIsDisplayed()
        assertThat(navigations.get()).isEqualTo(0)
    }

    @Test
    fun savingSystemBackStaysOnEditorWithoutDiscard() {
        val navigations = AtomicInteger(0)
        rule.setContent {
            TestRoute(
                navigations = navigations,
                editorState = readyEditorState(
                    phase = DocEditorPhase.Saving,
                    dirty = true,
                ),
            )
        }

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_DISCARD_DIALOG).assertDoesNotExist()
        rule.onNodeWithText(
            rule.activity.getString(com.promenar.infinity.R.string.doc_editor_saving_back_feedback),
        )
            .assertIsDisplayed()
        assertThat(navigations.get()).isEqualTo(0)
    }

    @Test
    fun rapidSavingBackUsesOnePoliteFeedbackCycleAndPhaseChangeDismissesIt() {
        val navigations = AtomicInteger(0)
        var editorState by mutableStateOf(
            readyEditorState(
                phase = DocEditorPhase.Saving,
                dirty = true,
            ),
        )
        rule.setContent {
            TestRoute(
                navigations = navigations,
                editorState = editorState,
            )
        }
        val feedback = rule.activity.getString(
            com.promenar.infinity.R.string.doc_editor_saving_back_feedback,
        )

        rule.runOnIdle {
            repeat(6) {
                rule.activity.onBackPressedDispatcher.onBackPressed()
            }
        }

        rule.onAllNodesWithText(feedback).assertCountEquals(1)
        rule.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            ),
        ).assertCountEquals(0)
        rule.onAllNodes(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ) and SemanticsMatcher.keyIsDefined(SemanticsActions.Dismiss),
        ).assertCountEquals(1)
        assertThat(navigations.get()).isEqualTo(0)

        listOf(
            readyEditorState(dirty = false),
            readyEditorState(phase = DocEditorPhase.SaveError, dirty = true)
                .copy(failureCode = DocEditorFailureCode.SaveCancelled),
            readyEditorState(phase = DocEditorPhase.SaveError, dirty = true),
            readyEditorState(phase = DocEditorPhase.SaveConflict, dirty = true),
        ).forEach { nextState ->
            rule.runOnIdle { editorState = nextState }
            rule.onAllNodesWithText(feedback).assertCountEquals(0)

            rule.runOnIdle {
                editorState = readyEditorState(
                    phase = DocEditorPhase.Saving,
                    dirty = true,
                )
            }
            rule.runOnIdle {
                repeat(3) {
                    rule.activity.onBackPressedDispatcher.onBackPressed()
                }
            }
            rule.onAllNodesWithText(feedback).assertCountEquals(1)
        }

        rule.runOnIdle { editorState = readyEditorState(dirty = false) }
        rule.onAllNodesWithText(feedback).assertCountEquals(0)
    }

    @Test
    fun documentKeyChangeCancelsPreviousSavingFeedbackCycle() {
        val navigations = AtomicInteger(0)
        var stateKey by mutableStateOf("doc-a")
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                DocEditorRouteContent(
                    editorState = readyEditorState(
                        phase = DocEditorPhase.Saving,
                        dirty = true,
                    ),
                    onNavigateBack = { navigations.incrementAndGet() },
                    actions = DocEditorScreenActions(),
                    stateKey = stateKey,
                )
            }
        }
        val feedback = rule.activity.getString(
            com.promenar.infinity.R.string.doc_editor_saving_back_feedback,
        )

        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).performClick()
        rule.onAllNodesWithText(feedback).assertCountEquals(1)

        rule.runOnIdle { stateKey = "doc-b" }

        rule.onAllNodesWithText(feedback).assertCountEquals(0)
        assertThat(navigations.get()).isEqualTo(0)
    }

    @Test
    fun largeFileHidesEditingActions() {
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState().copy(
                        content = "",
                        persistedContent = "",
                        totalLines = 0,
                        sizeBytes = 1_048_577L,
                        contentAccess = DocEditorContentAccess.MetadataOnly,
                    ),
                ),
            )
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_LARGE_FILE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_WARNING_DISMISS)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertDoesNotExist()
    }

    @Test
    fun performanceProtectedKeepsFullSaveAndCopyActionsWithoutRenderingEditorModes() {
        val copyRequests = AtomicInteger(0)
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState(dirty = true).copy(
                        content = "x".repeat(MAX_EDITABLE_CONTENT_LENGTH + 1),
                        contentAccess = DocEditorContentAccess.PerformanceProtected,
                    ),
                ),
                actions = DocEditorScreenActions(
                    onCopyLocalContent = { copyRequests.incrementAndGet() },
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_PERFORMANCE_PROTECTED).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_COPY_PROTECTED_FULL)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertThat(copyRequests.get()).isEqualTo(1)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE).assertIsEnabled()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_PREVIEW).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertDoesNotExist()
    }

    @Test
    fun phoneAtDoubleFontScaleKeepsPrimaryActionsReachableAndAtLeast48Dp() {
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                TestContent(
                    screenState = DocEditorScreenState(
                        editorState = readyEditorState(dirty = true),
                    ),
                )
            }
        }

        listOf(
            UiTags.DOC_EDITOR_BACK,
            UiTags.DOC_EDITOR_SAVE,
            UiTags.DOC_EDITOR_MODE_EDIT,
            UiTags.DOC_EDITOR_MODE_PREVIEW,
        ).forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE).assertIsEnabled()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT)
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithTag(UiTags.DOC_EDITOR_TITLE_INPUT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATUS).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertDoesNotExist()
    }

    @Test
    fun titleInputNormalizesPastedAndEnteredLineBreaksWithoutDiscardingText() {
        var screenState by mutableStateOf(
            DocEditorScreenState(
                editorState = readyEditorState(dirty = true),
            ),
        )
        rule.setContent {
            TestContent(
                screenState = screenState,
                actions = DocEditorScreenActions(
                    onTitleChange = { newTitle ->
                        screenState = screenState.copy(
                            editorState = screenState.editorState.copy(title = newTitle),
                        )
                    },
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_TITLE_INPUT)
            .performTextReplacement("release\r\nnotes\nfinal\rend")

        rule.onNodeWithTag(UiTags.DOC_EDITOR_TITLE_INPUT)
            .assertTextContains("release notes final end")
    }

    @Test
    fun titleInputImeActionClosesIme() {
        var screenState by mutableStateOf(
            DocEditorScreenState(
                editorState = readyEditorState(dirty = true),
            ),
        )
        rule.setContent {
            TestContent(
                screenState = screenState,
                actions = DocEditorScreenActions(
                    onTitleChange = { newTitle ->
                        screenState = screenState.copy(
                            editorState = screenState.editorState.copy(title = newTitle),
                        )
                    },
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_TITLE_INPUT).performClick()
        val imeHeight = awaitImeOpened()
        assertThat(imeHeight).isGreaterThan(0)
        assertThat(imeVisible()).isTrue()

        rule.onNodeWithTag(UiTags.DOC_EDITOR_TITLE_INPUT).performImeAction()

        assertThat(awaitImeClosed()).isTrue()
    }

    @Test
    fun exact800x360KeepsEditorIdentityModesBodyStatusAndPrimaryActionsReachable() {
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(800.dp, 360.dp)),
            ) {
                TestContent(
                    screenState = DocEditorScreenState(
                        editorState = readyEditorState(dirty = true),
                    ),
                )
            }
        }

        listOf(
            UiTags.DOC_EDITOR_BACK,
            UiTags.DOC_EDITOR_SAVE,
            UiTags.DOC_EDITOR_MODE_EDIT,
            UiTags.DOC_EDITOR_MODE_PREVIEW,
            UiTags.DOC_EDITOR_MODE_SPLIT,
        ).forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
        }
        val identityBounds = rule.onNodeWithTag(UiTags.DOC_EDITOR_IDENTITY)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        val titleBounds = rule.onNodeWithTag(UiTags.DOC_EDITOR_TITLE_INPUT)
            .assertIsDisplayed()
            .fetchSemanticsNode().boundsInRoot
        assertThat(titleBounds.right).isAtMost(identityBounds.right)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATUS).assertIsDisplayed()
    }

    @Test
    fun tablet840x900AtDoubleFontScaleKeepsSplitThreeModesAndBothPanesReachable() {
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(840.dp, 900.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                TestContent(
                    screenState = DocEditorScreenState(
                        editorState = readyEditorState(dirty = true),
                        viewMode = DocEditorViewMode.SPLIT,
                    ),
                )
            }
        }

        listOf(
            UiTags.DOC_EDITOR_MODE_EDIT,
            UiTags.DOC_EDITOR_MODE_PREVIEW,
            UiTags.DOC_EDITOR_MODE_SPLIT,
        ).forEach { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT)
            .assertIsSelected()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_TITLE_INPUT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_PREVIEW).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATUS).assertIsDisplayed()
    }

    @Test
    fun nonTerminalTextSelectionSurvivesEditSplitEditModeTransitions() {
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        rule.waitUntil(8_000) {
            rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val content = "prefix--SELECTED-MIDDLE--suffix"
        val selectedRange = TextRange(start = 8, end = 23)
        var screenState by mutableStateOf(
            DocEditorScreenState(
                editorState = readyEditorState().copy(
                    content = content,
                    persistedContent = content,
                    sizeBytes = content.toByteArray().size.toLong(),
                ),
                viewMode = DocEditorViewMode.EDIT,
            ),
        )
        rule.setContent {
            TestContent(
                screenState = screenState,
                actions = DocEditorScreenActions(
                    onContentChange = { value ->
                        screenState = screenState.copy(
                            editorState = screenState.editorState.copy(content = value),
                        )
                    },
                    onViewModeChange = { mode ->
                        screenState = screenState.copy(viewMode = mode)
                    },
                ),
            )
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).assertIsDisplayed().performClick()
        assertThat(awaitImeOpened()).isGreaterThan(0)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).performTextInputSelection(selectedRange)
        // 用户收起键盘后切换模式；使用真实横屏，避免虚拟窗口超出屏幕的可见性干扰。
        Espresso.closeSoftKeyboard()
        assertThat(awaitImeClosed()).isTrue()
        settleLayout()
        assertTextSelectionRange(selectedRange)

        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertIsSelected()
        assertTextSelectionRange(selectedRange)

        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT).assertIsDisplayed().performClick()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT).assertIsSelected()
        assertTextSelectionRange(selectedRange)
    }

    @Test
    fun physicalLandscapeRealImeKeepsCurrentEditorSurfaceAndSaveActionAboveKeyboard() {
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        rule.waitUntil(8_000) {
            rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        var content by mutableStateOf("line before IME")
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState(dirty = true).copy(content = content),
                ),
                actions = DocEditorScreenActions(onContentChange = { content = it }),
            )
        }

        val currentLine = "current line remains reachable above IME"
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT)
            .assertIsDisplayed()
            .performClick()
        val imeInset = awaitImeOpened()
        assertThat(imeInset).isGreaterThan(0)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).performTextReplacement(currentLine)
        settleLayout()

        val visibleBottomPx = decorHeightPx().toFloat() - imeInset.toFloat()
        val tolerancePx = with(rule.density) { 2.dp.toPx() }
        val input = rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT)
            .assertIsDisplayed()
            .assertTextContains(currentLine)
            .fetchSemanticsNode().boundsInRoot
        val save = rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE)
            .assertIsDisplayed()
            .assertIsEnabled()
            .fetchSemanticsNode().boundsInRoot

        assertThat(input.bottom).isAtMost(visibleBottomPx + tolerancePx)
        assertThat(save.bottom).isAtMost(visibleBottomPx + tolerancePx)

        Espresso.closeSoftKeyboard()
        assertThat(awaitImeClosed()).isTrue()
        settleLayout()

        rule.onNodeWithTag(UiTags.DOC_EDITOR_IDENTITY).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_EDIT).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_PREVIEW).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_STATUS).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT)
            .assertIsDisplayed()
            .assertTextContains(currentLine)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE)
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun asynchronousSaveTerminalStatesDismissRealImeAndExposeRecoveryWithoutLosingDraft() {
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        rule.waitUntil(8_000) {
            rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val localDraft = "local draft survives asynchronous save terminal state"
        var editorState by mutableStateOf(
            readyEditorState(dirty = true).copy(content = localDraft),
        )
        rule.setContent {
            TestContent(screenState = DocEditorScreenState(editorState = editorState))
        }

        val terminalStates = listOf(
            Triple(
                readyEditorState(phase = DocEditorPhase.SaveError, dirty = true).copy(
                    content = localDraft,
                    failureCode = DocEditorFailureCode.ContentSaveFailed,
                ),
                UiTags.DOC_EDITOR_STATE_SAVE_ERROR,
                listOf(UiTags.DOC_EDITOR_RETRY_SAVE),
            ),
            Triple(
                readyEditorState(phase = DocEditorPhase.SaveConflict, dirty = true).copy(
                    content = localDraft,
                    failureCode = DocEditorFailureCode.ContentConflict,
                ),
                UiTags.DOC_EDITOR_STATE_CONFLICT,
                listOf(UiTags.DOC_EDITOR_COPY_LOCAL, UiTags.DOC_EDITOR_REQUEST_RELOAD),
            ),
            Triple(
                readyEditorState(phase = DocEditorPhase.NotFound, dirty = true).copy(
                    content = localDraft,
                ),
                UiTags.DOC_EDITOR_STATE_NOT_FOUND,
                listOf(UiTags.DOC_EDITOR_COPY_LOCAL, UiTags.DOC_EDITOR_REQUEST_RELOAD),
            ),
        )

        terminalStates.forEach { (terminalState, terminalTag, recoveryActionTags) ->
            rule.runOnIdle {
                editorState = readyEditorState(dirty = true).copy(content = localDraft)
            }
            rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).performClick()
            assertThat(awaitImeOpened()).isGreaterThan(0)

            rule.runOnIdle { editorState = terminalState }
            settleLayout()

            rule.onNodeWithTag(terminalTag).assertIsDisplayed()
            recoveryActionTags.forEach { actionTag ->
                rule.onNodeWithTag(actionTag)
                    .assertIsDisplayed()
                    .assertHasClickAction()
                    .assertHeightIsAtLeast(48.dp)
            }
            assertThat(awaitImeClosed()).isTrue()

            rule.runOnIdle {
                editorState = readyEditorState(dirty = true).copy(content = localDraft)
            }
            rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT)
                .assertIsDisplayed()
                .assertTextContains(localDraft)
            rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE)
                .assertIsDisplayed()
                .assertIsEnabled()
        }
    }

    @Test
    fun asynchronousPendingIndexDismissesRealImeAndKeepsRetryReachableWithoutLosingDraft() {
        rule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        rule.waitUntil(8_000) {
            rule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
        val localDraft = "local draft survives pending index feedback"
        var editorState by mutableStateOf(
            readyEditorState(dirty = true).copy(content = localDraft),
        )
        rule.setContent {
            TestContent(screenState = DocEditorScreenState(editorState = editorState))
        }

        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT).performClick()
        assertThat(awaitImeOpened()).isGreaterThan(0)
        rule.runOnIdle { editorState = editorState.withPendingIndex() }
        settleLayout()

        rule.onNodeWithText(
            rule.activity.getString(com.promenar.infinity.R.string.rag_index_retry_hint),
        ).assertIsDisplayed()
        rule.onNodeWithContentDescription(pendingIndexRetryDescription())
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        assertThat(awaitImeClosed()).isTrue()

        rule.runOnIdle {
            editorState = readyEditorState(dirty = true).copy(content = localDraft)
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT)
            .assertIsDisplayed()
            .assertTextContains(localDraft)
        rule.onNodeWithTag(UiTags.DOC_EDITOR_SAVE)
            .assertIsDisplayed()
            .assertIsEnabled()
    }

    @Test
    fun backNavigationControlDrawsVisibleForegroundPixels() {
        rule.setContent {
            TestContent(
                screenState = DocEditorScreenState(
                    editorState = readyEditorState(dirty = true),
                ),
            )
        }

        val back = rule.onNodeWithTag(UiTags.DOC_EDITOR_BACK)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        val image = back.captureToImage()
        val pixels = image.toPixelMap()
        val background = pixels[0, 0]
        var foregroundPixels = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = pixels[x, y]
                val difference = abs(pixel.red - background.red) +
                    abs(pixel.green - background.green) +
                    abs(pixel.blue - background.blue)
                if (pixel.alpha > 0.1f && difference > 0.25f) foregroundPixels++
            }
        }

        assertThat(foregroundPixels).isGreaterThan((image.width * image.height) / 100)
    }

    @Test
    fun splitAppearsOnlyAtOrAbove840FixtureWidth() {
        var width by mutableStateOf(840.dp)
        rule.setContent {
            Box(Modifier.requiredSize(width = width, height = 900.dp)) {
                TestContent(
                    screenState = DocEditorScreenState(editorState = readyEditorState()),
                )
            }
        }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)

        rule.runOnIdle { width = 719.dp }
        rule.onNodeWithTag(UiTags.DOC_EDITOR_MODE_SPLIT).assertDoesNotExist()
    }

    @Test
    fun standaloneLongInlineCodeAtDoubleFontScaleReachesBothEndSentinels() {
        val sentinels = listOf("WIDE_CONTENT_END_SENTINEL_中文🚀_A███", "WIDE_CONTENT_END_SENTINEL_中文🚀_B███")
        val commands = sentinels.map { sentinel ->
            "./gradlew :app:validateDebugScreenshotTest --tests " +
                "com.promenar.infinity.ui.rag.DocEditorInteractionTest.$sentinel"
        }
        val controls = commands.map { command -> command.replace("███", "   ") }
        var markdown by mutableStateOf(commands.joinToString("\n\n") { command -> "`$command`" })
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                TestContent(
                    screenState = DocEditorScreenState(
                        editorState = readyEditorState().copy(
                            content = markdown,
                        ),
                        viewMode = DocEditorViewMode.PREVIEW,
                    ),
                )
            }
        }

        val wideHosts = rule.onAllNodesWithTag(UiTags.MARKDOWN_WIDE_CONTENT)
        wideHosts.assertCountEquals(sentinels.size)
        val sentinelImages = mutableListOf<androidx.compose.ui.graphics.ImageBitmap>()
        sentinels.indices.forEach { index ->
            val wideHost = wideHosts[index]
                .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
            val initialRange = wideHost.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertThat(initialRange.maxValue()).isGreaterThan(0f)

            wideHost.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                assertThat(scrollBy(100_000f, 0f)).isTrue()
            }
            val finalRange = wideHost.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
            assertThat(finalRange.value()).isAtLeast(finalRange.maxValue() - 1f)
            rule.onNodeWithText(sentinels[index], substring = true).assertIsDisplayed()
            sentinelImages += wideHost.captureToImage()
        }

        rule.runOnIdle {
            markdown = controls.joinToString("\n\n") { command -> "`$command`" }
        }
        val controlHosts = rule.onAllNodesWithTag(UiTags.MARKDOWN_WIDE_CONTENT)
        controlHosts.assertCountEquals(controls.size)
        controls.indices.forEach { index ->
            val controlHost = controlHosts[index]
            controlHost.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
                assertThat(scrollBy(100_000f, 0f)).isTrue()
            }
            assertRightEdgePixelDifference(
                sentinelImage = sentinelImages[index],
                controlImage = controlHost.captureToImage(),
            )
        }
    }

    @Test
    fun wideGfmTableAtDoubleFontScaleReachesTerminalCellAndPaintsItsTailGlyphs() {
        val sentinel = "TABLE_END_SENTINEL_中文🚀███"
        val table = """
            | Model | Provider | Context | Vision | Tools | Terminal |
            |---|---|---|---|---|---|
            | DeepSeek V4 Flash | Local Aggregator | 64K | Yes | Yes | $sentinel |
        """.trimIndent()
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                TestContent(
                    screenState = DocEditorScreenState(
                        editorState = readyEditorState().copy(content = table),
                        viewMode = DocEditorViewMode.PREVIEW,
                    ),
                )
            }
        }

        val wideHost = rule.onNodeWithTag(UiTags.MARKDOWN_WIDE_CONTENT)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        val initialRange = wideHost.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertThat(initialRange.maxValue()).isGreaterThan(0f)

        wideHost.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertThat(scrollBy(100_000f, 0f)).isTrue()
        }

        val finalRange = wideHost.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertThat(finalRange.value()).isAtLeast(finalRange.maxValue() - 1f)
        rule.onNodeWithText(sentinel).assertIsDisplayed()
        assertGlyphPixelsVisible(rule.onNodeWithText(sentinel).captureToImage())
    }

    @Test
    fun blockQuoteNestedWideTablePreservesQuoteAndReachesTerminalCell() {
        val sentinel = "QUOTE_TABLE_END_中文🚀███"
        val markdown = """
            > | Model | Provider | Context | Vision | Tools | Terminal |
            > |---|---|---|---|---|---|
            > | DeepSeek | Local | 64K | Yes | Yes | $sentinel |
        """.trimIndent()

        renderPreviewAtDoubleFont(markdown)

        scrollSingleWideHostToEnd()
        val sentinelNode = rule.onNodeWithText(sentinel).assertIsDisplayed()
        assertGlyphPixelsVisible(sentinelNode.captureToImage())
    }

    @Test
    fun listItemNestedWideTablePreservesListAndReachesTerminalCell() {
        val sentinel = "LIST_TABLE_END_中文🚀███"
        val markdown = """
            - Models

              | Model | Provider | Context | Vision | Tools | Terminal |
              |---|---|---|---|---|---|
              | DeepSeek | Local | 64K | Yes | Yes | $sentinel |
        """.trimIndent()

        renderPreviewAtDoubleFont(markdown)

        rule.onNodeWithText("Models").assertIsDisplayed()
        scrollSingleWideHostToEnd()
        val sentinelNode = rule.onNodeWithText(sentinel).assertIsDisplayed()
        assertGlyphPixelsVisible(sentinelNode.captureToImage())
    }

    private fun renderPreviewAtDoubleFont(markdown: String) {
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                TestContent(
                    screenState = DocEditorScreenState(
                        editorState = readyEditorState().copy(content = markdown),
                        viewMode = DocEditorViewMode.PREVIEW,
                    ),
                )
            }
        }
    }

    private fun scrollSingleWideHostToEnd() {
        val wideHost = rule.onNodeWithTag(UiTags.MARKDOWN_WIDE_CONTENT)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        val initialRange = wideHost.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertThat(initialRange.maxValue()).isGreaterThan(0f)
        wideHost.performSemanticsAction(SemanticsActions.ScrollBy) { scrollBy ->
            assertThat(scrollBy(100_000f, 0f)).isTrue()
        }
        val finalRange = wideHost.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertThat(finalRange.value()).isAtLeast(finalRange.maxValue() - 1f)
    }

    private fun assertGlyphPixelsVisible(image: androidx.compose.ui.graphics.ImageBitmap) {
        val pixels = image.toPixelMap()
        val background = pixels[0, 0]
        var foregroundPixels = 0
        for (y in 0 until image.height) {
            for (x in 0 until image.width) {
                val pixel = pixels[x, y]
                val difference = abs(pixel.red - background.red) +
                    abs(pixel.green - background.green) +
                    abs(pixel.blue - background.blue)
                if (pixel.alpha > 0.1f && difference > 0.25f) foregroundPixels++
            }
        }

        assertThat(foregroundPixels).isGreaterThan((image.width * image.height) / 100)
    }

    private fun assertRightEdgePixelDifference(
        sentinelImage: androidx.compose.ui.graphics.ImageBitmap,
        controlImage: androidx.compose.ui.graphics.ImageBitmap,
    ) {
        assertThat(controlImage.width).isEqualTo(sentinelImage.width)
        assertThat(controlImage.height).isEqualTo(sentinelImage.height)
        val sentinelPixels = sentinelImage.toPixelMap()
        val controlPixels = controlImage.toPixelMap()
        val scanStartX = sentinelImage.width * 3 / 4
        var changedPixels = 0
        for (y in 0 until sentinelImage.height) {
            for (x in scanStartX until sentinelImage.width) {
                val sentinel = sentinelPixels[x, y]
                val control = controlPixels[x, y]
                val difference = abs(sentinel.red - control.red) +
                    abs(sentinel.green - control.green) +
                    abs(sentinel.blue - control.blue) +
                    abs(sentinel.alpha - control.alpha)
                if (difference > 0.2f) changedPixels++
            }
        }

        val comparedPixels = (sentinelImage.width - scanStartX) * sentinelImage.height
        assertThat(changedPixels).isGreaterThan(comparedPixels / 500)
    }

    private fun assertTextSelectionRange(expected: TextRange) {
        val actual = rule.onNodeWithTag(UiTags.DOC_EDITOR_INPUT)
            .fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange]
        assertThat(actual).isEqualTo(expected)
    }

    @Composable
    private fun TestRoute(
        navigations: AtomicInteger,
        editorState: DocEditorUiState = readyEditorState(dirty = true),
    ) {
        InfinityTheme(dynamicColor = false) {
            DocEditorRouteContent(
                editorState = editorState,
                onNavigateBack = { navigations.incrementAndGet() },
                actions = DocEditorScreenActions(),
            )
        }
    }

    @Composable
    private fun TestContent(
        screenState: DocEditorScreenState,
        actions: DocEditorScreenActions = DocEditorScreenActions(),
    ) {
        InfinityTheme(dynamicColor = false) {
            DocEditorScreenContent(state = screenState, actions = actions)
        }
    }

    private fun readyEditorState(
        phase: DocEditorPhase = DocEditorPhase.Ready,
        dirty: Boolean = false,
    ) = DocEditorUiState(
        phase = phase,
        workspaceRootUuid = "root",
        documentId = "doc",
        title = "release-notes.md",
        content = "# Release\n\nLocal draft",
        persistedTitle = "release-notes.md",
        persistedContent = if (dirty) "# Release" else "# Release\n\nLocal draft",
        currentHash = "hash-1",
        totalLines = 3,
        lastModified = 123L,
        sizeBytes = 256L,
        contentDirty = dirty,
        hasLoadedDocument = true,
        failureCode = when (phase) {
            DocEditorPhase.SaveError -> DocEditorFailureCode.ContentSaveFailed
            DocEditorPhase.SaveConflict -> DocEditorFailureCode.ContentConflict
            else -> null
        },
    )

    private fun DocEditorUiState.withPendingIndex() = copy(
        indexQueueFailed = true,
        indexPendingTargets = listOf(
            com.promenar.infinity.domain.repository.RenameIndexTarget(
                fileUuid = "doc",
                targetHash = "hash-2",
                targetEpoch = 456L,
            ),
        ),
    )

    private fun pendingIndexRetryDescription(): String =
        "${rule.activity.getString(com.promenar.infinity.R.string.shared_btn_retry)}：" +
            rule.activity.getString(com.promenar.infinity.R.string.rag_index_retry_hint)

    private fun awaitImeOpened(timeoutMs: Long = 8_000): Int {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = imeBottomInset()
        var stableMs = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            val current = imeBottomInset()
            stableMs = if (current > 0 && current == last) stableMs + 100 else 0L
            last = current
            if (current > 0 && stableMs >= 200) return current
        }
        return last
    }

    private fun awaitImeClosed(timeoutMs: Long = 6_000): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var stableMs = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            stableMs = if (!imeVisible() && imeBottomInset() == 0) stableMs + 100 else 0L
            if (stableMs >= 200) return true
        }
        return !imeVisible() && imeBottomInset() == 0
    }

    private fun settleLayout() {
        rule.waitForIdle()
        SystemClock.sleep(150)
        rule.waitForIdle()
    }

    private fun imeVisible(): Boolean = rule.runOnUiThread {
        val insets = rule.activity.window.decorView.rootWindowInsets
        insets != null && insets.isVisible(AndroidWindowInsets.Type.ime())
    }

    private fun imeBottomInset(): Int = rule.runOnUiThread {
        rule.activity.window.decorView.rootWindowInsets
            ?.getInsets(AndroidWindowInsets.Type.ime())?.bottom ?: 0
    }

    private fun decorHeightPx(): Int = rule.runOnUiThread {
        rule.activity.window.decorView.height
    }
}
