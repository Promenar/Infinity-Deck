package com.promenar.infinity.ui.rag

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.WindowSize
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsToggleable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.then
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.data.rag.RetrievalDepth
import com.promenar.infinity.data.rag.retrievalDepth
import com.promenar.infinity.data.rag.withRetrievalDepth
import com.promenar.infinity.ui.settings.SearchConfigState
import com.promenar.infinity.ui.settings.SearchSecretOperation
import com.promenar.infinity.ui.settings.SearchSecretErrorCode
import com.promenar.infinity.ui.settings.TavilySecretEditor
import com.promenar.infinity.ui.settings.SearchSecretActions
import com.promenar.infinity.ui.settings.KeenableSecretEditor
import com.promenar.infinity.ui.settings.SearchConfigBottomSheet
import com.promenar.infinity.ui.settings.EngineOption
import com.promenar.infinity.ui.settings.DepthChip
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.compose.ui.test.SemanticsMatcher
import org.junit.Rule
import org.junit.Test

class RagSettingsAccessibilityTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun knowledgeBaseDepthSegmentsAndToggleNodesUseProductionContentAt2x() {
        var state by mutableStateOf(
            KnowledgeBaseSettingsState(
                config = RagConfiguration(enableHybridSearch = true, enableQueryRewrite = true)
                    .withRetrievalDepth(RetrievalDepth.BALANCED),
            ),
        )
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                InfinityTheme(dynamicColor = false) {
                    KnowledgeBaseSettingsContent(
                        state = state,
                        actions = KnowledgeBaseSettingsActions(
                            onDepthSelected = { depth ->
                                state = state.copy(config = state.config.withRetrievalDepth(depth))
                            },
                            onConfigChanged = { transform ->
                                state = state.copy(config = state.config.transform())
                            },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag("kb_depth_control").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("kb_depth_balanced", useUnmergedTree = true).assertIsSelected()
        rule.onNodeWithTag("kb_depth_custom", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("kb_depth_lean", useUnmergedTree = true).performClick()
        rule.runOnIdle { assertThat(state.config.retrievalDepth()).isEqualTo(RetrievalDepth.LEAN) }
        rule.onNodeWithTag("kb_depth_lean", useUnmergedTree = true).assertIsSelected()

        // 原始值偏离档位：出现选中但不可点按的“自定义”段。
        rule.runOnIdle { state = state.copy(config = state.config.copy(rerankFinalK = 7)) }
        rule.onNodeWithTag("kb_depth_custom", useUnmergedTree = true)
            .assertIsSelected()
            .assertIsNotEnabled()

        rule.onNodeWithTag("kb_hybrid_toggle")
            .performScrollTo()
            .assertIsToggleable()
            .assertIsOn()
            .performClick()
        rule.runOnIdle { assertThat(state.config.enableHybridSearch).isFalse() }
        rule.onNodeWithTag("kb_hybrid_switch", useUnmergedTree = true).assert(!hasClickAction())
        rule.onNodeWithTag("kb_rewrite_toggle")
            .performScrollTo()
            .assertIsToggleable()
            .assertIsOn()
            .performClick()
        rule.runOnIdle { assertThat(state.config.enableQueryRewrite).isFalse() }
        rule.onNodeWithTag("kb_rewrite_switch", useUnmergedTree = true).assert(!hasClickAction())
    }

    @Test
    fun knowledgeDebugChunkPickerNoticeAndClearConfirmationUseProductionContentAt2x() {
        val clearedWithGraph = AtomicReference<Boolean>()
        val reindexed = AtomicBoolean(false)
        val initial = RagConfiguration()
        var state by mutableStateOf(
            KnowledgeDebugSettingsState(
                config = initial,
                indexedChunkSettings = initial.chunkSettings(),
                vectorCount = 42,
                initiallyExpanded = true,
            ),
        )
        rule.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                    DeviceConfigurationOverride.FontScale(2f),
            ) {
                InfinityTheme(dynamicColor = false) {
                    KnowledgeDebugSettingsContent(
                        state = state,
                        actions = KnowledgeDebugSettingsActions(
                            onReindexAll = { reindexed.set(true) },
                            onClearVectors = clearedWithGraph::set,
                            onConfigChanged = { transform ->
                                state = state.copy(config = state.config.transform())
                            },
                        ),
                    )
                }
            }
        }

        rule.onNodeWithTag("kb_debug_chunk_notice").assertDoesNotExist()
        rule.onNodeWithTag("kb_debug_chunk_size").performScrollTo().performClick()
        rule.onNodeWithTag("numeric_custom").performScrollTo().performClick()
        rule.onNodeWithTag("numeric_custom_input").performScrollTo().performTextReplacement("500")
        rule.onNodeWithTag("numeric_done").performScrollTo().performClick()
        rule.runOnIdle { assertThat(state.config.docChunkSize).isEqualTo(500) }
        // 分块参数与建索引时不同：顶部出现重建提示。
        rule.onNodeWithTag("kb_debug_chunk_notice").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("kb_debug_chunk_notice_reindex").performScrollTo().performClick()
        rule.runOnIdle { assertThat(reindexed.get()).isTrue() }

        rule.onNodeWithTag("rag_global_clear_action")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.onNodeWithTag("rag_global_clear_graph_row", useUnmergedTree = true)
            .assertIsSelectable()
            .assertIsSelected()
        rule.onNodeWithTag("rag_global_clear_graph_radio", useUnmergedTree = true)
            .assert(!hasClickAction())
        rule.onNodeWithTag("rag_global_clear_vectors_row", useUnmergedTree = true)
            .assertIsSelectable()
            .assertHasClickAction()
            .performClick()
            .assertIsSelected()
        rule.onNodeWithTag("rag_global_clear_vectors_radio", useUnmergedTree = true)
            .assert(!hasClickAction())
        rule.onNodeWithTag("rag_global_clear_graph_row", useUnmergedTree = true)
            .assertHasClickAction()
            .performClick()
            .assertIsSelected()
        rule.onNodeWithTag("rag_global_clear_confirm", useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()
        rule.runOnIdle { assertThat(clearedWithGraph.get()).isTrue() }
    }

    @Test
    fun tavilySecretEditorSavesRevealsAndClearsWithoutPersistingPlaintextInState() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var state by mutableStateOf(
            SearchConfigState(
                searchEngine = "tavily",
                hasTavilyApiKey = false,
                secretOperation = SearchSecretOperation.Idle,
            ),
        )
        val savedSecret = AtomicReference<String>()
        val cleared = AtomicBoolean(false)
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                TavilySecretEditor(
                    state = state,
                    actions = SearchSecretActions(
                        onSave = { secret ->
                            savedSecret.set(secret.concatToString())
                            state = state.copy(
                                hasTavilyApiKey = true,
                                secretOperation = SearchSecretOperation.Saved,
                            )
                        },
                        onReveal = { "runtime-only-key".toCharArray() },
                        onClear = {
                            cleared.set(true)
                            state = state.copy(
                                hasTavilyApiKey = false,
                                secretOperation = SearchSecretOperation.Idle,
                            )
                        },
                    ),
                )
            }
        }

        rule.onNodeWithTag("search_tavily_secret_field").performTextInput("test-secret")
        rule.onNodeWithTag("search_tavily_secret_save")
            .assertIsEnabled()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.runOnIdle {
            assertThat(savedSecret.get()).isEqualTo("test-secret")
            assertThat(state).isEqualTo(
                SearchConfigState(
                    searchEngine = "tavily",
                    hasTavilyApiKey = true,
                    secretOperation = SearchSecretOperation.Saved,
                ),
            )
        }

        rule.onNodeWithContentDescription(resources.getString(R.string.secret_field_show))
            .assertHasClickAction()
            .performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("search_tavily_secret_field").assertTextContains("runtime-only-key")
        rule.onNodeWithContentDescription(resources.getString(R.string.secret_field_clear))
            .assertHasClickAction()
            .performClick()
        rule.runOnIdle {
            assertThat(cleared.get()).isTrue()
            assertThat(state.hasTavilyApiKey).isFalse()
        }
    }

    @Test
    fun tavilySaveFailureRetainsEditAndRetryReplaysOnlySave() {
        var state by mutableStateOf(
            SearchConfigState(
                searchEngine = "tavily",
                secretOperation = SearchSecretOperation.Idle,
            ),
        )
        val retried = AtomicReference<String>()
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                TavilySecretEditor(
                    state = state,
                    actions = SearchSecretActions(
                        onSave = {
                            state = state.copy(
                                secretOperation = SearchSecretOperation.Error(
                                    SearchSecretErrorCode.SAVE_FAILED,
                                ),
                            )
                        },
                        onRetry = { code, secret ->
                            assertThat(code).isEqualTo(SearchSecretErrorCode.SAVE_FAILED)
                            retried.set(secret?.concatToString())
                        },
                    ),
                )
            }
        }

        rule.onNodeWithTag("search_tavily_secret_field").performTextInput("retry-secret")
        rule.onNodeWithTag("search_tavily_secret_save").performClick()
        rule.onNodeWithTag("search_tavily_secret_field").assertTextContains("retry-secret")
        rule.onNodeWithTag("search_tavily_secret_retry")
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        rule.runOnIdle { assertThat(retried.get()).isEqualTo("retry-secret") }
    }

    @Test
    fun searchEngineAndDepthOptionsExposeSelectableRadioSemantics() {
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                androidx.compose.foundation.layout.Column {
                    EngineOption("tavily", "Tavily", true) {}
                    EngineOption("keenable", "Keenable", true) {}
                    DepthChip("advanced", "Advanced", true) {}
                }
            }
        }

        rule.onNodeWithTag("search_engine_tavily")
            .assertIsSelectable()
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("search_depth_advanced")
            .assertIsSelectable()
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
        rule.onNodeWithTag("search_engine_keenable")
            .assertIsSelectable()
            .assertIsSelected()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun keenableSearchSheetSavesSelectionAndScrollsToOptionalKeyAt2x() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("infinity_search", 0)
        val previous = prefs.getString("search_engine", null)
        prefs.edit().putString("search_engine", "duckduckgo").commit()
        try {
            rule.setContent {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.WindowSize(DpSize(360.dp, 800.dp)) then
                        DeviceConfigurationOverride.FontScale(2f),
                ) {
                    InfinityTheme(dynamicColor = false) {
                        SearchConfigBottomSheet(onDismiss = {})
                    }
                }
            }
            rule.onNodeWithTag("search_engine_keenable").performScrollTo().performClick()
            rule.onNodeWithTag("search_engine_keenable").assertIsSelected()
            rule.waitUntil { prefs.getString("search_engine", null) == "keenable" }
            rule.onNodeWithText(context.getString(R.string.search_keenable_keyless_hint))
                .performScrollTo().assertIsDisplayed()
            rule.onNodeWithTag("search_keenable_secret_save")
                .performScrollTo().assertIsDisplayed().assertIsNotEnabled()
        } finally {
            prefs.edit().apply {
                if (previous == null) remove("search_engine") else putString("search_engine", previous)
            }.commit()
        }
    }

    @Test
    fun keenableSecretEditorSavesRevealsAndClearsItsOwnState() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        var state by mutableStateOf(
            SearchConfigState(
                searchEngine = "keenable",
                hasTavilyApiKey = true,
                keenableSecretOperation = SearchSecretOperation.Idle,
            ),
        )
        val saved = AtomicReference<String>()
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                KeenableSecretEditor(
                    state = state,
                    actions = SearchSecretActions(
                        onSave = { key ->
                            saved.set(key.concatToString())
                            key.fill('\u0000')
                            state = state.copy(
                                hasKeenableApiKey = true,
                                keenableSecretOperation = SearchSecretOperation.Saved,
                            )
                        },
                        onReveal = { "fake-keenable-key".toCharArray() },
                        onClear = { state = state.copy(hasKeenableApiKey = false) },
                    ),
                )
            }
        }
        rule.onNodeWithTag("search_keenable_secret_field").performTextInput("fake-keenable-key")
        rule.onNodeWithTag("search_keenable_secret_save").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertThat(saved.get()).isEqualTo("fake-keenable-key")
            assertThat(state.hasKeenableApiKey).isTrue()
            assertThat(state.toString()).doesNotContain("fake-keenable-key")
        }
        rule.onNodeWithContentDescription(resources.getString(R.string.secret_field_show)).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("search_keenable_secret_field").assertTextContains("fake-keenable-key")
        rule.onNodeWithContentDescription(resources.getString(R.string.secret_field_clear)).performClick()
        rule.runOnIdle {
            assertThat(state.hasKeenableApiKey).isFalse()
            assertThat(state.hasTavilyApiKey).isTrue()
        }
    }

    @Test
    fun knowledgeGraphPageDispatchesGraphAndDebugNavigationFromProductionContent() {
        val navigatedToGraph = AtomicBoolean(false)
        val navigatedToDebug = AtomicBoolean(false)
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                KnowledgeGraphSettingsContent(
                    state = KnowledgeGraphSettingsState(config = RagConfiguration(), entityCount = 3),
                    actions = KnowledgeGraphSettingsActions(
                        onNavigateToGraph = { navigatedToGraph.set(true) },
                        onNavigateToDebug = { navigatedToDebug.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag("rag_advanced_graph_row")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        rule.runOnIdle { assertThat(navigatedToGraph.get()).isTrue() }
        rule.onNodeWithTag("rag_advanced_model_row").performScrollTo().assertHasClickAction()
        rule.onNodeWithTag("kg_settings_debug_row")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        rule.runOnIdle { assertThat(navigatedToDebug.get()).isTrue() }
    }

    @Test
    fun agentKnowledgeChoicesUseRadioSheetsAndPrivateToggle() {
        var mode by mutableStateOf(com.promenar.infinity.data.agent.AgentRetrievalMode.AUTO)
        var access by mutableStateOf(com.promenar.infinity.data.agent.KnowledgeFileAccess.ON_REQUEST)
        var policy by mutableStateOf(com.promenar.infinity.data.agent.AgentMemoryPolicy())
        rule.setContent {
            InfinityTheme(
                preferences = InfinityThemePreferences(
                    mode = InfinityThemeMode.LIGHT,
                    colorSource = InfinityColorSource.INFINITY,
                ),
            ) {
                com.promenar.infinity.ui.hub.AgentKnowledgeMemorySection(
                    retrievalMode = mode,
                    fileAccess = access,
                    policy = policy,
                    onRetrievalModeChange = { mode = it },
                    onFileAccessChange = { access = it },
                    onPolicyChange = { transform -> policy = transform(policy) },
                )
            }
        }

        rule.onNodeWithTag("agent_edit_retrieval_mode").assertHasClickAction().assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("agent_edit_retrieval_mode_auto").assertIsSelected()
        rule.onNodeWithTag("agent_edit_retrieval_mode_tool").performClick()
        rule.runOnIdle { assertThat(mode).isEqualTo(com.promenar.infinity.data.agent.AgentRetrievalMode.TOOL) }
        // 检索方式与文件访问互不联动。
        rule.runOnIdle { assertThat(access).isEqualTo(com.promenar.infinity.data.agent.KnowledgeFileAccess.ON_REQUEST) }

        rule.onNodeWithTag("agent_edit_file_access").performClick()
        rule.onNodeWithTag("agent_edit_file_access_always").performClick()
        rule.runOnIdle { assertThat(access).isEqualTo(com.promenar.infinity.data.agent.KnowledgeFileAccess.ALWAYS) }

        // 私密智能体已取消：会话默认互不可见，跨会话读取改由全局记忆控制。
        rule.onNodeWithTag("agent_memory_private").assertDoesNotExist()
    }

    @Test
    fun agentPermissionSheetsUseRadioChoicesAndCollectionChecklist() {
        var policy by mutableStateOf(com.promenar.infinity.data.agent.AgentMemoryPolicy())
        val collections = listOf(
            com.promenar.infinity.data.rag.KgCollectionSummary("work:a", "work", "长夜行", "存储空间/长夜行", 20, 412, 300),
            com.promenar.infinity.data.rag.KgCollectionSummary("shared", "shared", "通用知识", null, 3, 5, 2),
        )
        rule.setContent {
            InfinityTheme(
                preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY),
            ) {
                com.promenar.infinity.ui.hub.AgentScopeSection(
                    policy = policy,
                    onPolicyChange = { transform -> policy = transform(policy) },
                    collections = collections,
                )
            }
        }

        rule.onNodeWithTag("agent_perm_global_memory").assertHeightIsAtLeast(48.dp).performClick()
        rule.onNodeWithTag("agent_perm_global_0")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            .performClick()
        rule.runOnIdle { assertThat(policy.globalMemory).isEqualTo(com.promenar.infinity.data.agent.GlobalMemoryPolicy.FORBID) }
        rule.onNodeWithTag("agent_perm_global_2").performClick()
        rule.onNodeWithTag("agent_perm_reach_0").performClick()
        rule.runOnIdle {
            assertThat(policy.globalMemory).isEqualTo(com.promenar.infinity.data.agent.GlobalMemoryPolicy.ALLOW_DEFAULT_ON)
            assertThat(policy.memoryCap).isEqualTo(com.promenar.infinity.data.model.ConversationScope.AGENT)
        }
        androidx.test.espresso.Espresso.pressBack()

        rule.onNodeWithTag("agent_perm_collections").performClick()
        rule.onNodeWithTag("agent_perm_collections_all").assertIsToggleable().performClick()
        rule.runOnIdle { assertThat(policy.allowedCollectionIds).containsExactly("work:a", "shared") }
        rule.onNodeWithTag("agent_perm_collection_shared")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .performClick()
        rule.runOnIdle { assertThat(policy.allowedCollectionIds).containsExactly("work:a") }
        rule.onNodeWithTag("agent_perm_default_1").performScrollTo().performClick()
        rule.runOnIdle { assertThat(policy.defaultCollectionIds).isEmpty() }
    }

}
