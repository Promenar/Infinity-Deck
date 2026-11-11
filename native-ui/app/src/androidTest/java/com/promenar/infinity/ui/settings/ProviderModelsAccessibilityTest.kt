package com.promenar.infinity.ui.settings

import android.os.SystemClock
import android.view.WindowInsets as AndroidWindowInsets
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Rule
import org.junit.Test

class ProviderModelsAccessibilityTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @After
    fun closeImeAfterTest() {
        // 返回键收键盘由输入法决定；API 35 模拟器的 Gboard 会同时把返回交给页面并结束 Activity，
        // 清理阶段改走 IMM 收起，不让下一个测试继承键盘状态。
        if (imeVisible()) {
            Espresso.closeSoftKeyboard()
            awaitImeClosed()
        }
    }

    @Test
    fun syncNoticeIsAssertiveAndDismissTargetIsAtLeast48Dp() {
        rule.setContent {
            InfinityTheme {
                ModelSyncNoticeBanner(
                    notice = ModelSyncNotice.syncFailed("must never be spoken"),
                    dismissEnabled = true,
                    onDismiss = {},
                )
            }
        }

        val message = resources.getString(R.string.provider_models_sync_failed)
        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, message),
        ).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            ),
        )
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_NOTICE_DISMISS)
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun disabledSyncActionKeepsAccessibleTouchTarget() {
        val refreshCount = AtomicInteger()
        rule.setContent {
            InfinityTheme {
                ProviderModelsSyncBar(
                    enabledCount = 0,
                    totalCount = 0,
                    isFetching = true,
                    lastSyncAtMillis = null,
                    onRefresh = { refreshCount.incrementAndGet() },
                )
            }
        }

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_SYNC)
            .assertIsNotEnabled()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .assertHeightIsEqualTo(48.dp)
            .performClick()
        assertThat(refreshCount.get()).isEqualTo(0)
    }

    @Test
    fun modelSummaryRowShowsCompactInfoAndNoExpandedInlineControlsAt2xFontScale() {
        val modelId = "provider-alpha::model-a"
        val remoteModelId = "vendor/model-a-with-a-very-long-remote-identifier"
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                InfinityTheme {
                    Box(Modifier.width(360.dp)) {
                        ProviderModelsScreenContent(
                            state = ProviderModelsScreenState(
                                providerName = "Provider",
                                providerId = "provider-alpha",
                                isFetching = false,
                                syncNotice = null,
                                models = listOf(
                                    ModelInfo(
                                        name = "Model A",
                                        id = modelId,
                                        remoteModelId = remoteModelId,
                                        description = "desc",
                                        enabled = true,
                                        type = "chat",
                                        contextLength = 65_536,
                                        capabilities = listOf("chat", "vision"),
                                        providerId = "provider-alpha",
                                    ),
                                ),
                                modelTestStates = mapOf(modelId to ModelTestState.Idle),
                            ),
                            actions = idleActions(),
                            onNavigateBack = {},
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag(
            UiTags.providerModelsRemoteId(modelId),
            useUnmergedTree = true,
        ).assertIsDisplayed()
        rule.onNodeWithText("Model A").assertIsDisplayed()
        rule.onNodeWithTag(UiTags.providerModelsToggleAction(modelId))
            .assertIsDisplayed()
            .assert(hasClickAction())
            .assertHeightIsAtLeast(48.dp)

        rule.onNodeWithTag(UiTags.providerModelsTypeAction(modelId, "chat")).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.providerModelsCapabilityAction(modelId, "vision")).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.providerModelsContextField(modelId)).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.providerModelsTestAction(modelId)).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.providerModelsDeleteAction(modelId)).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.providerModelsNameField(modelId)).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.providerModelsDetails(modelId)).assertDoesNotExist()

        onModelCard(modelId).performClick()
        rule.onNodeWithTag(editorSheetTag(modelId)).assertExists()
    }

    @Test
    fun primaryActionsAndOverflowStayReachableAt360DpWithTwoXFontScale() {
        val clicks = AtomicInteger()
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 2f)) {
                InfinityTheme {
                    // 同步按钮在搜索栏下的状态条里，添加与溢出菜单在顶栏。
                    Column(Modifier.width(360.dp)) {
                        Row {
                            ProviderModelsTopActions(
                                onAdd = { clicks.incrementAndGet() },
                                onEditProvider = {},
                                onDisableAll = { clicks.incrementAndGet() },
                                onDeleteAll = { clicks.incrementAndGet() },
                            )
                        }
                        ProviderModelsSyncBar(
                            enabledCount = 1,
                            totalCount = 2,
                            isFetching = false,
                            lastSyncAtMillis = null,
                            onRefresh = { clicks.incrementAndGet() },
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL).assertDoesNotExist()
        val visibleBounds = listOf(
            UiTags.PROVIDER_MODELS_ACTION_SYNC,
            UiTags.PROVIDER_MODELS_ACTION_ADD,
            UiTags.PROVIDER_MODELS_ACTION_OVERFLOW,
        ).map { tag ->
            rule.onNodeWithTag(tag)
                .assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp)
                .performClick()
                .fetchSemanticsNode().boundsInRoot
        }
        assertThat(visibleBounds.all { it.left >= 0f && it.right <= 360f }).isTrue()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL)
            .performClick()
        assertThat(clicks.get()).isEqualTo(3)
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL)
            .assertIsDisplayed()
            .performClick()
        assertThat(clicks.get()).isEqualTo(4)
    }

    @Test
    fun failedCustomModelSubmissionKeepsFormOpenAndPreservesInput() {
        var submittedId: String? = null
        var submittedName: String? = null
        var added = 0
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                InfinityTheme {
                    Box(Modifier.requiredSize(width = 360.dp, height = 800.dp)) {
                        AddCustomModelForm(
                            onSubmit = { id, name ->
                                submittedId = id
                                submittedName = name
                                false
                            },
                            onAdded = { added++ },
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_ID_FIELD)
            .performClick()
            .assertIsFocused()
        rule.onNodeWithText(resources.getString(R.string.provider_models_field_model_id)).assertExists()
        rule.onNodeWithText(resources.getString(R.string.provider_models_field_display_name)).assertExists()
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_ID_FIELD, "custom-model")
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_NAME_FIELD, "Custom Name")
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_FORM_LIST).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_SUBMIT_BUTTON)
            .performScrollTo()
            .performClick()

        assertThat(submittedId).isEqualTo("custom-model")
        assertThat(submittedName).isEqualTo("Custom Name")
        assertThat(added).isEqualTo(0)
        rule.onNodeWithText("custom-model").assertExists()
        rule.onNodeWithText("Custom Name").assertExists()
        val failureMessage = resources.getString(R.string.generation_failure_invalid_request)
        rule.onNodeWithText(failureMessage)
            .assertExists()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_ID_FIELD, "-fixed")
        rule.onNodeWithText(failureMessage).assertDoesNotExist()
    }

    @Test
    fun providerModelsContentSupportsFullInteractionFlowByUniqueTags() {
        val refreshCount = AtomicInteger()
        val addCount = AtomicInteger()
        val disableAllCount = AtomicInteger()
        val deleteCount = AtomicInteger()
        val submittedId = AtomicReference<String?>(null)

        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider-Alpha",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Model A",
                                id = "provider-alpha::model-a",
                                description = "Description A",
                                enabled = true,
                                type = "chat",
                                contextLength = 4096,
                                capabilities = listOf("chat", "vision"),
                                providerId = "provider-alpha",
                                maxOutputTokens = 2048,
                                knowledgeCutoff = "20240101",
                            ),
                        ),
                        modelTestStates = mapOf("provider-alpha::model-a" to ModelTestState.Idle),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = { refreshCount.incrementAndGet() },
                        onAdd = { id, name ->
                            submittedId.set("$id|$name")
                            addCount.incrementAndGet()
                            true
                        },
                        onDisableAll = { disableAllCount.incrementAndGet() },
                        onDeleteAll = { deleteCount.incrementAndGet() },
                        onUpdate = {},
                        onToggle = {},
                        onTest = {},
                        onCancelTest = {},
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_SYNC).performClick()
        assertThat(refreshCount.get()).isEqualTo(1)

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_ADD).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_SHEET).assertExists()
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_ID_FIELD, "provider-alpha::added-model")
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_ADD_NAME_FIELD, "Added Model")
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_SUBMIT_BUTTON).performClick()
        assertThat(addCount.get()).isEqualTo(1)
        assertThat(submittedId.get()).isEqualTo("provider-alpha::added-model|Added Model")
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ADD_SHEET).assertDoesNotExist()

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DISABLE_ALL).performClick()
        assertThat(disableAllCount.get()).isEqualTo(1)

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_DIALOG).assertExists()
        assertThat(deleteCount.get()).isEqualTo(0)
        rule.onNodeWithText(resources.getString(R.string.common_btn_cancel)).performClick()
        assertThat(deleteCount.get()).isEqualTo(0)
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_OVERFLOW).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_ACTION_DELETE_ALL).performClick()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_DELETE_ALL_CONFIRM_BUTTON).performClick()
        assertThat(deleteCount.get()).isEqualTo(1)

        rule.onNodeWithTag(UiTags.providerModelsModelCard("provider-alpha::model-a")).performScrollTo()
        onModelCard("provider-alpha::model-a").performClick()
        rule.onNodeWithTag(editorSheetTag("provider-alpha::model-a")).assertExists()
    }

    @Test
    fun loadingIsShownOnceInTheListWithoutASeparateNotice() {
        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "p",
                        isFetching = true,
                        syncNotice = ModelSyncNotice.loading(),
                        models = emptyList(),
                        modelTestStates = emptyMap(),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {},
                        onToggle = {},
                        onTest = {},
                        onCancelTest = {},
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_NOTICE).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_STATE_LOADING)
            .assertExists()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun providerModelsErrorAndEmptyStatesExposeDistinctLiveRegions() {
        val state = mutableStateOf(
            ProviderModelsScreenState(
                providerName = "Provider",
                providerId = "p",
                isFetching = false,
                syncNotice = ModelSyncNotice.syncFailed("technical detail"),
                models = emptyList(),
                modelTestStates = emptyMap(),
            ),
        )
        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = state.value,
                    actions = idleActions(),
                    onNavigateBack = {},
                )
            }
        }

        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_STATE_ERROR)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                ),
            )
        // 列表已显示同步失败，不再另起同样内容的提示条。
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_NOTICE).assertDoesNotExist()

        rule.runOnIdle { state.value = state.value.copy(syncNotice = null) }
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_STATE_ERROR).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_STATE_EMPTY)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
    }

    @Test
    fun searchMatchesDisplayNameAndRemoteModelIdAndExposesSearchEmptyState() {
        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "p",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Alpha Display",
                                id = "p::opaque-a",
                                remoteModelId = "vendor/alpha-real-id",
                                description = "desc",
                                enabled = true,
                                type = "chat",
                                providerId = "p",
                            ),
                            ModelInfo(
                                name = "Beta Display",
                                id = "p::opaque-b",
                                remoteModelId = "vendor/beta-real-id",
                                description = "desc",
                                enabled = true,
                                type = "chat",
                                providerId = "p",
                            ),
                        ),
                        modelTestStates = emptyMap(),
                    ),
                    actions = idleActions(),
                    onNavigateBack = {},
                )
            }
        }

        inputInTaggedSearch(UiTags.PROVIDER_MODELS_SEARCH_FIELD, "vendor/alpha-real-id")
        onModelCard("p::opaque-a").assertExists()
        onModelCard("p::opaque-b").assertDoesNotExist()

        val searchField = rule.onNode(
            matcher = hasSetTextAction() and hasAnyAncestor(hasTestTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD)),
            useUnmergedTree = true,
        )
        searchField.performTextClearance()
        searchField.performTextInput("Beta Display")
        onModelCard("p::opaque-a").assertDoesNotExist()
        onModelCard("p::opaque-b").assertExists()

        searchField.performTextClearance()
        searchField.performTextInput("no-such-model")
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_STATE_SEARCH_EMPTY)
            .assertIsDisplayed()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                ),
            )
        rule.onNodeWithText(
            resources.getString(R.string.provider_models_search) +
                " · " + resources.getString(R.string.common_model_picker_empty),
        ).assertExists()
    }

    @Test
    fun providerModelsSummaryRefreshKeepsDraftedRowSummaryByModelId() {
        val modelState = mutableStateOf(
            ModelInfo(
                name = "Initial Model",
                id = "provider-alpha::stable-id",
                description = "desc",
                enabled = true,
                type = "chat",
                providerId = "provider-alpha",
                contextLength = 8_192,
                capabilities = listOf("chat"),
            ),
        )

        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(modelState.value),
                        modelTestStates = mapOf(modelState.value.id to ModelTestState.Idle),
                    ),
                    actions = idleActions(),
                    onNavigateBack = {},
                )
            }
        }

        rule.onNodeWithText("Initial Model").assertExists()
        rule.onNodeWithTag(
            UiTags.providerModelsRemoteId("provider-alpha::stable-id"),
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
        onModelCard("provider-alpha::stable-id").performClick()
        rule.onNodeWithTag(editorSheetTag("provider-alpha::stable-id")).assertExists()

        rule.runOnIdle {
            modelState.value = modelState.value.copy(
                name = "Refreshed Model",
                type = "reasoning",
                contextLength = 262_144,
                capabilities = listOf("reasoning", "vision"),
            )
        }

        rule.onNode(
            matcher = hasSetTextAction() and
                (hasTestTag(UiTags.providerModelsNameField("provider-alpha::stable-id")) or
                    hasAnyAncestor(
                        hasTestTag(UiTags.providerModelsNameField("provider-alpha::stable-id")),
                    )),
            useUnmergedTree = true,
        ).assertTextContains("Refreshed Model")
        rule.onNodeWithTag(
            UiTags.providerModelsEditorCloseAction("provider-alpha::stable-id"),
        ).performClick()
        rule.onNodeWithText("Refreshed Model").assertExists()
        rule.onNodeWithTag(
            UiTags.providerModelsRemoteId("provider-alpha::stable-id"),
            useUnmergedTree = true,
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun providerModelsSheetPreservesTypeCapabilityContextAndTestCallbacks() {
        val modelId = "provider-alpha::editable"
        val latestType = AtomicReference<String?>(null)
        val latestCapability = AtomicReference<List<String>?>(null)
        val latestContext = AtomicReference<Int?>(null)
        val testCount = AtomicInteger()

        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Editable",
                                id = modelId,
                                description = "desc",
                                enabled = true,
                                type = "chat",
                                contextLength = 8_192,
                                capabilities = listOf("chat", "vision"),
                                providerId = "provider-alpha",
                                maxOutputTokens = 4_096,
                                knowledgeCutoff = "202501",
                            ),
                        ),
                        modelTestStates = mapOf(modelId to ModelTestState.Idle),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {
                            latestType.set(it.type)
                            latestCapability.set(it.capabilities)
                            latestContext.set(it.contextLength)
                        },
                        onToggle = {},
                        onTest = { testCount.incrementAndGet() },
                        onCancelTest = {},
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }

        onModelCard(modelId).performClick()
        rule.onNodeWithTag(editorSheetTag(modelId)).assertExists()
        // 类型是页内分段滑块；能力点按摘要行后在多选面板里勾选，返回键关闭面板；
        // 上下文长度点按规格格子后在底部面板里输入。
        scrollEditorTo(modelId, UiTags.providerModelsTypeAction(modelId, "reasoning"))
        rule.onNodeWithTag(UiTags.providerModelsTypeAction(modelId, "reasoning")).performClick()
        scrollEditorTo(modelId, UiTags.providerModelsCapabilityPicker(modelId))
        rule.onNodeWithTag(UiTags.providerModelsCapabilityPicker(modelId)).performClick()
        rule.onNodeWithTag(UiTags.providerModelsCapabilityAction(modelId, "internet")).performClick()
        Espresso.pressBack()
        rule.waitForIdle()
        scrollEditorTo(modelId, UiTags.providerModelsSpecCell(modelId, ModelSpecField.Context.name))
        rule.onNodeWithTag(UiTags.providerModelsSpecCell(modelId, ModelSpecField.Context.name)).performClick()
        val contextField = rule.onNode(
            matcher = hasSetTextAction() and
                (hasTestTag(UiTags.providerModelsContextField(modelId)) or
                    hasAnyAncestor(hasTestTag(UiTags.providerModelsContextField(modelId)))),
            useUnmergedTree = true,
        )
        contextField.performTextClearance()
        contextField.performTextInput("32768")
        rule.onNodeWithText(resources.getString(R.string.model_spec_done)).performClick()
        rule.onNodeWithTag(UiTags.providerModelsEditorList(modelId))
            .performScrollToNode(hasTestTag(editorTestActionTag(modelId)))
        rule.onNodeWithTag(editorTestActionTag(modelId)).performClick()

        rule.waitForIdle()
        assertThat(latestType.get()).isEqualTo("reasoning")
        assertThat(latestCapability.get()).contains("internet")
        assertThat(latestContext.get()).isEqualTo(32_768)
        assertThat(testCount.get()).isEqualTo(1)
    }

    @Test
    fun systemBackClosesInPageEditorAndReturnsToList() {
        val modelId = "provider-alpha::back-close"
        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Back model",
                                id = modelId,
                                description = "desc",
                                enabled = true,
                                providerId = "provider-alpha",
                            ),
                        ),
                        modelTestStates = emptyMap(),
                    ),
                    actions = idleActions(),
                    onNavigateBack = {},
                )
            }
        }

        onModelCard(modelId).performClick()
        rule.onNodeWithTag(editorSheetTag(modelId)).assertExists()
        Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithTag(editorSheetTag(modelId)).assertDoesNotExist()
        onModelCard(modelId).assertIsDisplayed()
    }

    @Test
    fun editorSheetExposesUserOverrideSourceAndTestErrorSemantics() {
        val modelId = "provider-alpha::error-source"
        val errorMessage = resources.getString(R.string.generation_failure_auth)
        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Edited model",
                                id = modelId,
                                description = "desc",
                                enabled = true,
                                type = "chat",
                                contextLength = 8_192,
                                capabilities = listOf("chat"),
                                providerId = "provider-alpha",
                                userEditedFields = setOf("name", "contextLength"),
                            ),
                        ),
                        modelTestStates = mapOf(
                            modelId to ModelTestState.Error(GenerationFailureCode.AUTH),
                        ),
                    ),
                    actions = idleActions(),
                    onNavigateBack = {},
                )
            }
        }

        onModelCard(modelId).performClick()
        rule.onNode(
            hasTestTag(editorSourceTag(modelId)) and hasText(
                resources.getString(R.string.provider_models_field_display_name),
                substring = true,
            ),
        ).assertExists()
        rule.onNodeWithTag(UiTags.providerModelsEditorList(modelId))
            .performScrollToNode(hasText(errorMessage))
        rule.onNode(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, errorMessage),
        ).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Polite,
            ),
        )
    }

    @Test
    fun singleModelDeleteRequiresCancelOrExplicitConfirmationInEditorSheet() {
        val modelId = "provider-alpha::delete-me"
        val deleteCount = AtomicInteger()

        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Delete Me",
                                id = modelId,
                                description = "desc",
                                enabled = true,
                                providerId = "provider-alpha",
                            ),
                        ),
                        modelTestStates = emptyMap(),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {},
                        onToggle = {},
                        onTest = {},
                        onCancelTest = {},
                        onDelete = { deleteCount.incrementAndGet() },
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }

        onModelCard(modelId).performClick()
        rule.onNodeWithTag(editorSheetTag(modelId)).assertExists()
        scrollEditorTo(modelId, editorDeleteTag(modelId))
        rule.onNodeWithTag(editorDeleteTag(modelId)).performClick()
        rule.onNodeWithTag(editorDeleteConfirmDialogTag(modelId)).assertExists()
        assertThat(deleteCount.get()).isEqualTo(0)
        rule.onNodeWithText(resources.getString(R.string.common_btn_cancel)).performClick()
        assertThat(deleteCount.get()).isEqualTo(0)
        rule.onNodeWithTag(editorDeleteConfirmDialogTag(modelId)).assertDoesNotExist()

        rule.onNodeWithTag(editorDeleteTag(modelId)).performClick()
        rule.onNodeWithTag(editorDeleteConfirmButtonTag(modelId)).performClick()
        assertThat(deleteCount.get()).isEqualTo(1)
        rule.onNodeWithTag(editorDeleteConfirmDialogTag(modelId)).assertDoesNotExist()
    }

    @Test
    fun providerModelsContentCanRenderModelActionTagsByModelId() {
        val modelId = "provider-alpha::long-id-with-dash-123"
        val cancelTestCount = AtomicInteger(0)
        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "p",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Model Long Name",
                                id = modelId,
                                description = "desc",
                                enabled = false,
                                type = "chat",
                                providerId = "p",
                            ),
                        ),
                        modelTestStates = mapOf(modelId to ModelTestState.Testing),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {},
                        onToggle = {},
                        onTest = {},
                        onCancelTest = { cancelTestCount.incrementAndGet() },
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }
        rule.onNodeWithTag(UiTags.providerModelsModelCard(modelId))
            .assertExists()
            .performScrollTo()
        rule.onNodeWithTag(UiTags.providerModelsTestAction(modelId)).assertDoesNotExist()
        onModelCard(modelId).performClick()
        rule.onNodeWithTag(editorSheetTag(modelId)).assertExists()
        scrollEditorTo(modelId, editorTestActionTag(modelId))
        rule.onNodeWithTag(editorTestActionTag(modelId)).assertExists()
        rule.onNodeWithTag(editorTestActionTag(modelId)).performClick()
        assertThat(cancelTestCount.get()).isEqualTo(1)
    }

    @Test
    fun providerModelsActionListTagRemainsDiscoverableWhenEmptyAndSearchState() {
        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "p",
                        isFetching = false,
                        syncNotice = null,
                        models = emptyList(),
                        modelTestStates = emptyMap(),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {},
                        onToggle = {},
                        onTest = {},
                        onCancelTest = {},
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_LIST).assertExists()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_STATE_EMPTY).assertExists()
        rule.onNodeWithTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD).assertExists()
    }

    @Test
    fun openingModelEditorClearsSearchFocus() {
        val modelId = "provider-alpha::model-focus"
        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Focus Model",
                                id = modelId,
                                remoteModelId = "model-focus",
                                description = "focus fixture",
                                enabled = true,
                                type = "chat",
                                providerId = "provider-alpha",
                            ),
                        ),
                        modelTestStates = emptyMap(),
                    ),
                    actions = idleActions(),
                    onNavigateBack = {},
                )
            }
        }

        val searchInput = rule.onNode(
            matcher = hasSetTextAction() and
                (hasTestTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD) or
                    hasAnyAncestor(hasTestTag(UiTags.PROVIDER_MODELS_SEARCH_FIELD))),
            useUnmergedTree = true,
        )
        searchInput.performClick().assertIsFocused()
        assertThat(awaitImeOpened()).isGreaterThan(0)
        inputInTaggedSearch(UiTags.PROVIDER_MODELS_SEARCH_FIELD, "Focus")
        onModelCard(modelId).performClick()

        rule.onNodeWithTag(editorSheetTag(modelId)).assertExists()
        searchInput.assertIsNotFocused()
        assertThat(awaitImeClosed()).isTrue()
    }

    @Test
    fun modelToggleActionDoesNotOpenEditorSheet() {
        val modelId = "provider-alpha::model-toggle"
        var toggleCount = 0

        rule.setContent {
            InfinityTheme {
                ProviderModelsScreenContent(
                    state = ProviderModelsScreenState(
                        providerName = "Provider",
                        providerId = "provider-alpha",
                        isFetching = false,
                        syncNotice = null,
                        models = listOf(
                            ModelInfo(
                                name = "Toggle Model",
                                id = modelId,
                                description = "desc",
                                enabled = false,
                                type = "chat",
                                providerId = "provider-alpha",
                            ),
                        ),
                        modelTestStates = emptyMap(),
                    ),
                    actions = ProviderModelsScreenActions(
                        onRefresh = {},
                        onAdd = { _, _ -> true },
                        onDisableAll = {},
                        onDeleteAll = {},
                        onUpdate = {},
                        onToggle = { toggleCount++ },
                        onTest = {},
                        onCancelTest = {},
                        onDelete = {},
                        onClearNotice = {},
                    ),
                    onNavigateBack = {},
                )
            }
        }

        onModelCard(modelId).assert(hasClickAction())
        rule.onNodeWithTag(UiTags.providerModelsToggleAction(modelId)).performClick()
        assertThat(toggleCount).isEqualTo(1)
        rule.onNodeWithTag(editorSheetTag(modelId)).assertDoesNotExist()
    }

    private fun inputInTaggedSearch(tag: String, value: String) {
        rule.onNode(
            matcher = hasSetTextAction() and
                (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))),
            useUnmergedTree = true,
        ).performTextInput(value)
    }

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

    private fun imeVisible(): Boolean = rule.runOnUiThread {
        val insets = rule.activity.window.decorView.rootWindowInsets
        insets != null && insets.isVisible(AndroidWindowInsets.Type.ime())
    }

    private fun imeBottomInset(): Int = rule.runOnUiThread {
        rule.activity.window.decorView.rootWindowInsets
            ?.getInsets(AndroidWindowInsets.Type.ime())?.bottom ?: 0
    }

    private fun onModelCard(modelId: String) =
        rule.onNodeWithTag(UiTags.providerModelsModelCard(modelId))

    /** 编辑器是 LazyColumn，视口外的字段与操作未组合，交互前先滚到目标。 */
    private fun scrollEditorTo(modelId: String, tag: String) {
        rule.onNodeWithTag(UiTags.providerModelsEditorList(modelId)).performScrollToNode(hasTestTag(tag))
    }

    private fun editorSheetTag(modelId: String) = "provider_models_editor_sheet:$modelId"

    private fun editorTestActionTag(modelId: String) = "provider_models_editor_test:$modelId"

    private fun editorCancelActionTag(modelId: String) = "provider_models_editor_cancel:$modelId"

    private fun editorSourceTag(modelId: String) = "provider_models_editor_source:$modelId"

    private fun editorDeleteTag(modelId: String) = "provider_models_editor_delete:$modelId"

    private fun editorDeleteConfirmDialogTag(modelId: String) =
        "provider_models_editor_delete_confirm_dialog:$modelId"

    private fun editorDeleteConfirmButtonTag(modelId: String) =
        "provider_models_editor_delete_confirm:$modelId"

    private fun idleActions() = ProviderModelsScreenActions(
        onRefresh = {},
        onAdd = { _, _ -> true },
        onDisableAll = {},
        onDeleteAll = {},
        onUpdate = {},
        onToggle = {},
        onTest = {},
        onCancelTest = {},
        onDelete = {},
        onClearNotice = {},
    )
}
