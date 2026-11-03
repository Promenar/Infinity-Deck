package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import com.promenar.infinity.R
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.UnsupportedProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.InfinitySearchTopBar
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.AgentAvatar
import com.promenar.infinity.ui.settings.ProviderListScreenActions
import com.promenar.infinity.ui.settings.ProviderListScreenContent
import com.promenar.infinity.ui.settings.ProviderListScreenState
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.testing.UiTags
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class UserSettingsAccessibilityTest {
    private class TestMotionDurationScale : MotionDurationScale {
        var value = 1f
        override val scaleFactor: Float
            get() = value
    }

    private val motionDurationScale = TestMotionDurationScale()

    @get:Rule
    val rule = createComposeRule(effectContext = motionDurationScale)

    @Before
    fun resetMotionDurationScale() {
        motionDurationScale.value = 1f
    }

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    @Test
    fun settingsTabsShouldNotExistOnNewHomeScreen() {
        rule.setContent {
            InfinityTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(),
                    actions = UserSettingsHomeScreenActions()
                )
            }
        }
        rule.onNodeWithTag(UiTags.SETTINGS_TAB_APP).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.SETTINGS_TAB_PROVIDER).assertDoesNotExist()
    }

    @Test
    fun userAndAgentAvatarActionsExposeButtonRoleAnd48DpTargets() {
        rule.setContent {
            InfinityTheme {
                androidx.compose.foundation.layout.Column {
                    // 设置首页是 fillMaxSize 的 Scaffold；限制在剩余空间内，
                    // 否则同列的助手头像只剩 0 高度，测到的是测试布局而非产品触控目标。
                    Box(modifier = androidx.compose.ui.Modifier.weight(1f)) {
                        UserSettingsHomeScreenContent(
                            // 设了头像才显示名片；没有头像时整块是品牌字标。
                            state = UserSettingsHomeScreenState(userName = "Infinity Workbench", userAvatar = "/preview/user-avatar.img"),
                            actions = UserSettingsHomeScreenActions(),
                        )
                    }
                    AgentAvatar(
                        icon = "sym:smart_toy",
                        color = "#64748B",
                        seed = "agent",
                        name = "Agent",
                        size = 32.dp,
                        onClick = {},
                        contentDescription = "Edit agent avatar",
                    )
                }
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_PROFILE_CARD)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(UiTags.AGENT_AVATAR_ACTION)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun githubFooterExposesButtonRoleLabelAnd48DpTarget() {
        val opened = AtomicBoolean(false)
        rule.setContent {
            InfinityTheme {
                GitHubProjectFooter(onOpen = { opened.set(true) })
            }
        }

        rule.onNodeWithContentDescription(resources.getString(R.string.ux2_settings_github_open))
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        com.google.common.truth.Truth.assertThat(opened.get()).isTrue()
    }

    @Test
    fun sharedSettingsItemExposesButtonRole48DpTargetAndRealClick() {
        val clicked = AtomicBoolean(false)
        rule.setContent {
            InfinityTheme {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Settings,
                    title = "Shared settings item",
                    onClick = { clicked.set(true) },
                )
            }
        }

        rule.onNodeWithText("Shared settings item")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        com.google.common.truth.Truth.assertThat(clicked.get()).isTrue()
    }

    @Test
    fun sharedSettingsItemLongTitleReflowsInsteadOfClipping() {
        val longTitle = "Very long settings title that must wrap naturally on a narrow phone layout"
        rule.setContent {
            InfinityTheme {
                Box(modifier = androidx.compose.ui.Modifier.width(220.dp)) {
                    InfinitySettingsItem(
                        icon = Icons.Rounded.Settings,
                        title = longTitle,
                        onClick = {},
                    )
                }
            }
        }

        rule.onNodeWithText(longTitle)
            .assertHeightIsAtLeast(72.dp)
    }

    @Test
    fun sharedSearchClearActionIs48DpAndDispatchesValueChange() {
        var query by mutableStateOf("DeepSeek")
        rule.setContent {
            InfinityTheme {
                InfinitySearchBar(
                    value = query,
                    onValueChange = { query = it },
                )
            }
        }

        rule.onNodeWithContentDescription(resources.getString(R.string.common_cd_clear))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()

        rule.onNodeWithContentDescription(resources.getString(R.string.common_search_placeholder))
            .assert(hasSetTextAction())

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(query).isEmpty()
    }

    @Test
    fun sharedSearchTopBarSupportsFocusImeSystemBackAndReducedMotionAt2xScale() {
        val longTitle = "A deliberately long management page title"
        val actionDescription = "Additional settings action"
        var query by mutableStateOf("")
        var searchActive by mutableStateOf(false)
        val backPressed = AtomicBoolean(false)
        val actionClicked = AtomicBoolean(false)
        motionDurationScale.value = 0f
        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                InfinityTheme {
                    InfinitySearchTopBar(
                        title = longTitle,
                        search = com.promenar.infinity.ui.common.InfinityPageSearch(
                            query = query,
                            active = searchActive,
                            onQueryChange = { query = it },
                            onActiveChange = { searchActive = it },
                        ),
                        onBack = { backPressed.set(true) },
                        actions = {
                            IconButton(
                                onClick = { actionClicked.set(true) },
                                modifier = androidx.compose.ui.Modifier.size(48.dp),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Settings,
                                    contentDescription = actionDescription,
                                )
                            }
                        },
                    )
                }
            }
        }

        val clearDescription = resources.getString(R.string.common_cd_clear)
        val placeholderDescription = resources.getString(R.string.common_search_placeholder)

        rule.onNodeWithText(longTitle)
            .assertIsDisplayed()
        rule.onNodeWithContentDescription(actionDescription)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        com.google.common.truth.Truth.assertThat(actionClicked.get()).isTrue()

        rule.mainClock.autoAdvance = false
        rule.onNodeWithContentDescription(placeholderDescription)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithText(longTitle).assertDoesNotExist()
        rule.onNodeWithContentDescription(actionDescription).assertDoesNotExist()
        rule.mainClock.autoAdvance = true

        val searchField = rule.onNodeWithContentDescription(placeholderDescription)
        searchField
            .assert(hasSetTextAction())
            .assertIsFocused()
            .assertHeightIsAtLeast(48.dp)
            .performTextInput("DeepSeek")
        searchField.performImeAction()
        searchField.assertIsNotFocused()

        rule.onNodeWithContentDescription(clearDescription)
            .assertHasClickAction()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(query).isEmpty()

        Espresso.pressBack()
        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(searchActive).isFalse()
        com.google.common.truth.Truth.assertThat(backPressed.get()).isFalse()

        // 退出搜索后，系统返回交给页面导航；顶栏返回按钮调用 onBack。
        rule.onNodeWithContentDescription(resources.getString(R.string.common_cd_back)).performClick()
        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(backPressed.get()).isTrue()
    }

    @Test
    fun settingsSectionGroupSpansPastTheFixedIconColumnAt2xScale() {
        val rowTitle = "Aligned settings row"
        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                InfinityTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                        InfinitySettingsSection(title = "Accessible section") {
                            InfinitySettingsItem(
                                icon = Icons.Rounded.Settings,
                                title = rowTitle,
                                onClick = {},
                            )
                        }
                    }
                }
            }
        }

        rule.onNodeWithText("Accessible section").assertIsDisplayed()
        rule.onNodeWithText(rowTitle)
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)

        // 分组以统一圆角色面划定边界（不再使用分隔线）：可点击行铺满分组面，
        // 起点必须越过固定图标列，标题则在 2x 字号下仍对齐图标列之后。
        val rowBounds = rule.onNodeWithText(rowTitle)
            .fetchSemanticsNode()
            .boundsInRoot
        val rowTextLeft = rule.onNodeWithText(rowTitle, useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
            .left
        val iconColumnPx = with(rule.density) {
            (InfinitySpacing.RowHorizontal + InfinitySpacing.RowIconSize + InfinitySpacing.RowIconGap).toPx()
        }
        val sectionWidthPx = with(rule.density) { 360.dp.toPx() }
        com.google.common.truth.Truth.assertThat(rowBounds.left)
            .isLessThan(rowTextLeft)
        com.google.common.truth.Truth.assertThat(rowTextLeft - rowBounds.left)
            .isAtLeast(iconColumnPx - 1f)
        com.google.common.truth.Truth.assertThat(rowBounds.width)
            .isAtLeast(sectionWidthPx - 1f)
    }

    @Test
    fun defaultModelsItemClickTriggersSecondaryNavigation() {
        var navigatedRoute: String? = null
        rule.setContent {
            InfinityTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(),
                    actions = UserSettingsHomeScreenActions(
                        onNavigateToSecondary = { navigatedRoute = it }
                    )
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.settings_default_models))
            .performClick()

        com.google.common.truth.Truth.assertThat(navigatedRoute).isEqualTo("default_models")
    }

    @Test
    fun appLanguageEntryDispatchesRealDialogAction() {
        val opened = AtomicBoolean(false)

        rule.setContent {
            InfinityTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(),
                    actions = UserSettingsHomeScreenActions(
                        onShowLanguageDialog = { opened.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.settings_language))
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(opened.get()).isTrue()
    }

    @Test
    fun languageOptionExposesRadioButtonSelectionAnd48DpTarget() {
        val selected = AtomicBoolean(false)

        rule.setContent {
            InfinityTheme {
                LanguageOption(
                    label = "中文",
                    isSelected = true,
                    onSelect = { selected.set(true) },
                )
            }
        }

        rule.onNodeWithText("中文")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .assertIsSelected()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(selected.get()).isTrue()
    }

    @Test
    fun appSettingsSingleLineRowsRemainReachableAt2x() {
        val aboutTitle = resources.getString(R.string.settings_about_infinity)
        val aboutOpened = AtomicBoolean(false)

        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 2f,
                ),
            ) {
                InfinityTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                        UserSettingsHomeScreenContent(
                            state = UserSettingsHomeScreenState(),
                            actions = UserSettingsHomeScreenActions(
                                onAboutClick = { aboutOpened.set(true) },
                            ),
                        )
                    }
                }
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_APP_LIST)
            .performScrollToNode(
                hasText(aboutTitle),
            )

        rule.onNodeWithText(aboutTitle)
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
            .assertHasClickAction()
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(aboutOpened.get()).isTrue()
    }

    @Test
    fun providerAddButtonRealClickTriggersNavigationNotJustNodePresence() {
        val navigated = AtomicBoolean(false)

        rule.setContent {
            InfinityTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(),
                    actions = ProviderListScreenActions(onAddProvider = { navigated.set(true) }),
                )
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_ADD_PROVIDER)
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(navigated.get()).isTrue()
    }

    @Test
    fun localInferenceEntryIsAbsentWhenUnavailableInReleaseSurface() {
        rule.setContent {
            InfinityTheme {
                UserSettingsHomeScreenContent(
                    state = UserSettingsHomeScreenState(
                        localInferenceAvailable = false,
                    ),
                    actions = UserSettingsHomeScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.SETTINGS_LOCAL_INFERENCE_ENTRY).assertDoesNotExist()
    }

    @Test
    fun providerRowsExposeDistinctManageAndOverflowTargetsForEachProvider() {
        val apiConfiguredSummary = resources.getString(
            R.string.settings_provider_status_summary,
            resources.getString(R.string.settings_provider_state_enabled),
            resources.getString(R.string.settings_provider_api_key_configured),
        )
        val apiMissingSummary = resources.getString(
            R.string.settings_provider_status_summary,
            resources.getString(R.string.settings_provider_state_disabled),
            resources.getString(R.string.settings_provider_api_key_not_configured),
        )
        val providers = listOf(
            ProviderListItem(
                id = "provider-alpha",
                name = "Alpha Provider",
                baseUrl = "https://api.alpha.example.com/v1",
                protocolType = ProtocolType.Generic_OpenAI_Compat,
                hasApiKey = true,
                enabled = true,
            ),
            ProviderListItem(
                id = "provider-beta",
                name = "Beta Provider",
                baseUrl = "https://api.beta.example.com/v1",
                protocolType = ProtocolType.Anthropic_Messages,
                hasApiKey = false,
                enabled = false,
            ),
        )

        rule.setContent {
            InfinityTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(providers = providers),
                    actions = ProviderListScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.settingsProviderCard("provider-alpha"))
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    apiConfiguredSummary,
                ),
            )
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(UiTags.settingsProviderCard("provider-beta"))
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    apiMissingSummary,
                ),
            )
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-alpha"),
            useUnmergedTree = true,
        )
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-beta"),
            useUnmergedTree = true,
        )
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .assertWidthIsAtLeast(48.dp)
        // 可见副标题按“协议 · [停用状态] · 凭据状态”展示，已启用不重复显示；
        // 完整的“启用状态 · 凭据状态”只作为 TalkBack 的 StateDescription。
        assertProviderSubtitleShows(
            "provider-alpha",
            resources.getString(R.string.settings_provider_api_key_configured),
        )
        assertProviderSubtitleShows(
            "provider-beta",
            resources.getString(R.string.settings_provider_state_disabled),
            resources.getString(R.string.settings_provider_api_key_not_configured),
        )
    }

    /** 断言某个提供商行的可见副标题（未合并树中的文本节点）包含全部给定片段。 */
    private fun assertProviderSubtitleShows(providerId: String, vararg fragments: String) {
        val inCard = hasAnyAncestor(hasTestTag(UiTags.settingsProviderCard(providerId)))
        fragments.forEach { fragment ->
            rule.onNode(inCard and hasText(fragment, substring = true), useUnmergedTree = true)
                .assertIsDisplayed()
        }
    }

    /** 断言某个提供商行的可见文本不含给定片段（防止凭据状态误标）。 */
    private fun assertProviderSubtitleOmits(providerId: String, vararg fragments: String) {
        val inCard = hasAnyAncestor(hasTestTag(UiTags.settingsProviderCard(providerId)))
        fragments.forEach { fragment ->
            rule.onAllNodes(inCard and hasText(fragment, substring = true), useUnmergedTree = true)
                .assertCountEquals(0)
        }
    }

    @Test
    fun unsupportedProviderRowOpensRepairEditNeverModelsAndOverflowOffersDeleteOnly() {
        val providerId = "provider-unsupported"
        val deleteRequested = AtomicReference<String>()
        val editRequested = AtomicReference<String>()
        val modelsRequested = AtomicReference<String>()
        val unsupportedDescription = resources.getString(R.string.settings_provider_protocol_unsupported)

        rule.setContent {
            InfinityTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(
                        unsupportedProviders = listOf(
                            UnsupportedProviderListItem(
                                id = providerId,
                                name = "Future Provider",
                                rawProtocolId = "Future_Unknown_Protocol",
                                enabled = true,
                                hasApiKey = true,
                                hasVertexCredentials = true,
                            ),
                        ),
                    ),
                    actions = ProviderListScreenActions(
                        onRequestDeleteProvider = deleteRequested::set,
                        onEditProvider = editRequested::set,
                        onProviderModels = modelsRequested::set,
                    ),
                )
            }
        }

        // 未知协议行可见、标明不受支持，点击只进入安全修复（编辑）流程，
        // 不得进入模型列表或连通性探测；联网禁用由修复表单负责。
        rule.onNodeWithTag(UiTags.settingsProviderCard(providerId))
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    unsupportedDescription,
                ),
            )
            .assertHeightIsAtLeast(48.dp)
        assertProviderSubtitleShows(providerId, unsupportedDescription, "Future_Unknown_Protocol")

        rule.onNodeWithTag(UiTags.settingsProviderCard(providerId)).performClick()
        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(editRequested.get()).isEqualTo(providerId)
        com.google.common.truth.Truth.assertThat(modelsRequested.get()).isNull()

        rule.onNodeWithTag(
            UiTags.settingsProviderActions(providerId),
            useUnmergedTree = true,
        ).performClick()
        rule.onNodeWithTag("${UiTags.settingsProviderActions(providerId)}:edit")
            .assertDoesNotExist()
        rule.onNodeWithTag("${UiTags.settingsProviderActions(providerId)}:delete")
            .assertHasClickAction()
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(deleteRequested.get()).isEqualTo(providerId)
    }

    @Test
    fun providerRowsShowVertexCredentialStateAt2xWithoutLosingTalkBackOrOverflow() {
        val enabled = resources.getString(R.string.settings_provider_state_enabled)
        val disabled = resources.getString(R.string.settings_provider_state_disabled)
        val vertexConfigured =
            resources.getString(R.string.settings_provider_vertex_credentials_configured)
        val vertexMissing =
            resources.getString(R.string.settings_provider_vertex_credentials_not_configured)
        val configuredSummary = resources.getString(
            R.string.settings_provider_status_summary,
            enabled,
            vertexConfigured,
        )
        val missingSummary = resources.getString(
            R.string.settings_provider_status_summary,
            disabled,
            vertexMissing,
        )

        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                InfinityTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                        ProviderListScreenContent(
                            state = ProviderListScreenState(
                                providers = listOf(
                                    ProviderListItem(
                                        id = "provider-vertex-configured",
                                        name = "Vertex Configured",
                                        baseUrl = "https://vertex.example.com/v1",
                                        protocolType = ProtocolType.Google_VertexAI,
                                        hasVertexCredentials = true,
                                        enabled = true,
                                    ),
                                    ProviderListItem(
                                        id = "provider-vertex-missing",
                                        name = "Vertex Missing",
                                        baseUrl = "https://vertex-missing.example.com/v1",
                                        protocolType = ProtocolType.Google_VertexAI,
                                        hasVertexCredentials = false,
                                        enabled = false,
                                    ),
                                ),
                            ),
                            actions = ProviderListScreenActions(),
                        )
                    }
                }
            }
        }

        // 可见副标题只在停用时显示启用状态；完整摘要由 StateDescription 提供给 TalkBack。
        listOf(
            Triple("provider-vertex-configured", configuredSummary, listOf(vertexConfigured)),
            Triple("provider-vertex-missing", missingSummary, listOf(disabled, vertexMissing)),
        ).forEach { (providerId, summary, visibleFragments) ->
            rule.onNodeWithTag(UiTags.SETTINGS_PROVIDER_LIST)
                .performScrollToNode(hasTestTag(UiTags.settingsProviderCard(providerId)))
            assertProviderSubtitleShows(providerId, *visibleFragments.toTypedArray())
            rule.onNodeWithTag(UiTags.settingsProviderCard(providerId))
                .assertHasClickAction()
                .assert(
                    SemanticsMatcher.expectValue(
                        SemanticsProperties.StateDescription,
                        summary,
                    ),
                )
            rule.onNodeWithTag(
                UiTags.settingsProviderActions(providerId),
                useUnmergedTree = true,
            )
                .assertHasClickAction()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
        }
    }

    @Test
    fun localProviderReportsNoCredentialsRequiredWithoutApiOrVertexMislabeling() {
        val expectedSummary = resources.getString(
            R.string.settings_provider_status_summary,
            resources.getString(R.string.settings_provider_state_enabled),
            resources.getString(R.string.settings_provider_credentials_not_required),
        )

        rule.setContent {
            InfinityTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(
                        providers = listOf(
                            ProviderListItem(
                                id = "provider-local",
                                name = "Local Inference",
                                protocolType = ProtocolType.Local,
                                hasApiKey = true,
                                hasVertexCredentials = true,
                                enabled = true,
                            ),
                        ),
                    ),
                    actions = ProviderListScreenActions(),
                )
            }
        }

        // 本地推理即使历史数据残留 API Key / Vertex 凭据，也只显示“无需凭证”。
        assertProviderSubtitleShows(
            "provider-local",
            resources.getString(R.string.settings_provider_credentials_not_required),
        )
        assertProviderSubtitleOmits(
            "provider-local",
            resources.getString(R.string.settings_provider_api_key_configured),
            resources.getString(R.string.settings_provider_api_key_not_configured),
            resources.getString(R.string.settings_provider_vertex_credentials_configured),
            resources.getString(R.string.settings_provider_vertex_credentials_not_configured),
        )
        rule.onNodeWithTag(UiTags.settingsProviderCard("provider-local"))
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    expectedSummary,
                ),
            )
        rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-local"),
            useUnmergedTree = true,
        ).assertHasClickAction()
    }

    @Test
    fun providerManageAndOverflowEditDispatchDifferentRoutes() {
        val providerId = "provider-actions"
        val lastRoute = AtomicReference<String>()

        rule.setContent {
            InfinityTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(
                        providers = listOf(
                            ProviderListItem(
                                id = providerId,
                                name = "Actions Provider",
                                baseUrl = "https://api.example.com/v1",
                            ),
                        ),
                    ),
                    actions = ProviderListScreenActions(
                        onProviderModels = { id -> lastRoute.set("provider_models/$id") },
                        onEditProvider = { id -> lastRoute.set("provider_form?providerId=$id") },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.settingsProviderCard(providerId))
            .performClick()
        com.google.common.truth.Truth.assertThat(lastRoute.get())
            .isEqualTo("provider_models/$providerId")

        lastRoute.set(null)
        rule.onNodeWithTag(
            UiTags.settingsProviderActions(providerId),
            useUnmergedTree = true,
        ).performClick()
        com.google.common.truth.Truth.assertThat(lastRoute.get()).isNull()
        rule.onNodeWithTag("${UiTags.settingsProviderActions(providerId)}:edit")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(lastRoute.get())
            .isEqualTo("provider_form?providerId=$providerId")
    }

    @Test
    fun providerOverflowDeleteDispatchesConfirmationRequest() {
        val providerId = "provider-delete"
        val deletedProvider = AtomicReference<String>()

        rule.setContent {
            InfinityTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(
                        providers = listOf(
                            ProviderListItem(
                                id = providerId,
                                name = "Delete Provider",
                                baseUrl = "https://api.example.com/v1",
                            ),
                        ),
                    ),
                    actions = ProviderListScreenActions(
                        onRequestDeleteProvider = deletedProvider::set,
                    ),
                )
            }
        }

        rule.onNodeWithTag(
            UiTags.settingsProviderActions(providerId),
            useUnmergedTree = true,
        ).performClick()
        rule.onNodeWithTag("${UiTags.settingsProviderActions(providerId)}:delete")
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assertHeightIsAtLeast(48.dp)
            .performClick()

        rule.waitForIdle()
        com.google.common.truth.Truth.assertThat(deletedProvider.get()).isEqualTo(providerId)
    }

    @Test
    fun providerLongNameReflowsAt360DpAnd2xFontScale() {
        val longName =
            "OpenAI Compatible Internal Aggregator for International Production Workspaces"

        rule.setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(currentDensity.density, fontScale = 2f),
            ) {
                InfinityTheme {
                    Box(modifier = androidx.compose.ui.Modifier.width(360.dp)) {
                        ProviderListScreenContent(
                            state = ProviderListScreenState(
                                providers = listOf(
                                    ProviderListItem(
                                        id = "provider-long-name",
                                        name = longName,
                                        baseUrl = "https://internal.example.com/v1",
                                    ),
                                ),
                            ),
                            actions = ProviderListScreenActions(),
                        )
                    }
                }
            }
        }

        // 行内依次是固定图标列、可伸缩标题列和 48dp 溢出按钮；长名称应占满标题列并换行，
        // 而不是被截断或挤压溢出按钮。宽度按行内几何关系断言，不绑定某一版分组内边距。
        val titleBounds = rule.onNodeWithText(longName, useUnmergedTree = true)
            .assertHeightIsAtLeast(96.dp)
            .fetchSemanticsNode()
            .boundsInRoot
        val cardBounds = rule.onNodeWithTag(UiTags.settingsProviderCard("provider-long-name"))
            .fetchSemanticsNode()
            .boundsInRoot
        val actionBounds = rule.onNodeWithTag(
            UiTags.settingsProviderActions("provider-long-name"),
            useUnmergedTree = true,
        )
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .fetchSemanticsNode()
            .boundsInRoot
        val maxTitleToActionGapPx = with(rule.density) { 16.dp.toPx() }
        com.google.common.truth.Truth.assertThat(titleBounds.width)
            .isAtLeast(cardBounds.width * 0.5f)
        com.google.common.truth.Truth.assertThat(titleBounds.right)
            .isAtMost(actionBounds.left)
        com.google.common.truth.Truth.assertThat(actionBounds.left - titleBounds.right)
            .isAtMost(maxTitleToActionGapPx)
    }

    @Test
    fun providerEmptyStateKeepsOneAddAction() {
        rule.setContent {
            InfinityTheme {
                ProviderListScreenContent(
                    state = ProviderListScreenState(),
                    actions = ProviderListScreenActions(),
                )
            }
        }

        rule.onNodeWithText(resources.getString(R.string.settings_provider_empty))
            .assertIsDisplayed()
        rule.onAllNodesWithTag(UiTags.SETTINGS_ADD_PROVIDER)
            .assertCountEquals(1)
    }
}
