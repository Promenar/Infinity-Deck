package com.promenar.infinity.onboarding

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.MainActivity
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.ui.testing.UiTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain

/**
 * 真实 Activity/Compose 设备端首次引导回归。
 *
 * 进程级恢复由主控分两次 instrumentation 调用执行：首次运行推进并保留偏好，随后
 * `adb shell am force-stop` 再以保留数据方式运行验证入口；本类自身不把 JVM 重建冒充进程死亡。
 */
class OnboardingAndroidEndToEndTest {
    val rule = createAndroidComposeRule<MainActivity>()

    /**
     * 每个测试的 Activity 启动前先清空引导检查点：否则上一个测试完成引导后，新 Activity 直接进入主界面，
     * 之后的 recreate 会恢复主界面的返回栈而不再回到欢迎页。
     */
    @get:Rule
    val chain: RuleChain = RuleChain.outerRule(object : ExternalResource() {
        override fun before() {
            if (InstrumentationRegistry.getArguments().getString("onboardingPhase") == null) clearPersistentPreferences()
        }
    }).around(rule)
    private var originalProviderSummary: com.promenar.infinity.data.model.ProviderSummary? = null
    private var originalMainSlot: com.promenar.infinity.data.local.inference.SlotState? = null
    private var originalChatState: com.promenar.infinity.ui.chat.ChatState? = null

    @Before
    fun resetBeforeEachDeviceFlow() {
        if (phaseArgument() == null) resetPersistentState()
        val app = waitForStartupReady()
        originalProviderSummary = ProviderManager.getInstance().getProviderSummary("default")
        originalMainSlot = if (app.localInferenceRuntimeGate.isAvailable) {
            app.localInferenceEngine.mainSlot.value
        } else {
            null
        }
        originalChatState = app.chatStore.current
    }

    @After
    fun testHooksDoNotMutateProviderOrLocalSlot() {
        rule.activity.intent.removeExtra(MainActivity.EXTRA_ONBOARDING_EMPTY_MODELS_FOR_TESTING)
        rule.activity.intent.removeExtra(MainActivity.EXTRA_ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING)
        val app = rule.activity.application as InfinityApplication
        originalChatState?.let { snapshot -> app.chatStore.update { snapshot } }
        if (app.startupState.value == BackupStartupState.Ready) {
            assertEquals(originalProviderSummary, ProviderManager.getInstance().getProviderSummary("default"))
            if (app.localInferenceRuntimeGate.isAvailable) {
                assertEquals(
                    originalMainSlot,
                    app.localInferenceEngine.mainSlot.value,
                )
            }
        }
    }

    private fun resetPersistentState() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("infinity_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
            .edit().putString("language", "zh").commit()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        rule.activityRule.scenario.recreate()
        waitForStep(OnboardingStep.LANGUAGE)
    }

    @Test
    fun freshInstall_localeAndEveryCheckpointSurviveRecreate_thenOnlyMatchingSuccessCompletes() {
        assumeTrue("常规E2E不在force-stop分阶段模式运行", phaseArgument() == null)
        rule.onNodeWithTag("onboarding_step_language").assertIsDisplayed()
        rule.activity.intent.putExtra(MainActivity.EXTRA_ONBOARDING_EMPTY_MODELS_FOR_TESTING, true)
        rule.onNodeWithText("English").performClick()
        if (rule.activity.onboardingStateStoreForTesting().linuxOffered) {
            // sideload arm64 先出现 Linux 步骤；跳过后进入连接页，且跳过状态需经得起重建。
            waitForStep(OnboardingStep.LINUX)
            recreateAndAssert(OnboardingStep.LINUX, "onboarding_step_linux")
            waitForTag("onboarding_linux_skip")
        rule.onNodeWithTag("onboarding_linux_skip").performClick()
        }
        waitForStep(OnboardingStep.PROVIDER)
        rule.activityRule.scenario.recreate()
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithText("Connect an AI provider").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Connect an AI provider").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_step_provider").assertIsDisplayed()
        // 合并后的连接页只有一个连接按钮，不再出现第二个“测试连接”步骤。
        rule.onAllNodesWithTag("onboarding_provider_connect").assertCountEquals(1)
        rule.onAllNodesWithText(rule.activity.getString(R.string.provider_form_btn_test)).assertCountEquals(0)

        rule.runOnIdle {
            check(rule.activity.onboardingStateStoreForTesting().recordProviderConnected("provider-test"))
        }
        waitForStep(OnboardingStep.MODEL)
        recreateAndAssert(OnboardingStep.MODEL, "onboarding_step_model")

        // 测试入口强制提供真实空列表；UI 必须阻断并提供可恢复动作。
        rule.onAllNodesWithTag("onboarding_model_option").assertCountEquals(0)
        rule.onNodeWithTag("onboarding_model_empty").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_model_refresh").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_model_back_provider").assertIsDisplayed()
        assertStep(OnboardingStep.MODEL)
        recreateAndAssert(OnboardingStep.MODEL, "onboarding_step_model")
        rule.runOnIdle {
            check(
                rule.activity.onboardingStateStoreForTesting()
                    .selectModel("provider-test::model-test")
            )
        }
        waitForStep(OnboardingStep.AGENT)
        recreateAndAssert(OnboardingStep.AGENT, "onboarding_step_agent")

        rule.runOnIdle {
            check(
                rule.activity.onboardingStateStoreForTesting()
                    .recordAgentCreated("agent-test", "session-test")
            )
        }
        waitForStep(OnboardingStep.FIRST_CHAT)
        recreateAndAssert(OnboardingStep.FIRST_CHAT, "onboarding_step_first_chat")

        val app = rule.activity.application as InfinityApplication
        runBlocking {
            val sessionDao = app.database.sessionDao()
            val now = System.currentTimeMillis()
            val existing = sessionDao.getById("session-test")
            val fixture = existing?.copy(
                agentId = "agent-test",
                modelId = "provider-test::model-test",
                updatedAt = now,
            ) ?: SessionEntity(
                id = "session-test",
                agentId = "agent-test",
                title = "Onboarding E2E",
                modelId = "provider-test::model-test",
                createdAt = now,
                updatedAt = now,
            )
            if (existing == null) sessionDao.insert(fixture) else sessionDao.update(fixture)
        }
        rule.runOnIdle {
            app.chatStore.update { state ->
                state.copy(
                    sessions = listOf(
                        Session(
                            id = "session-test",
                            agentId = "agent-test",
                            messages = listOf(
                                Message(id = "assistant-partial", role = MessageRole.ASSISTANT, content = "partial")
                            ),
                        ),
                        Session(
                            id = "other-session",
                            agentId = "agent-test",
                            messages = listOf(
                                Message(
                                    id = "other-success",
                                    role = MessageRole.ASSISTANT,
                                    content = "success in wrong session",
                                    status = "success",
                                )
                            ),
                        ),
                    )
                )
            }
        }
        rule.waitForIdle()
        assertStep(OnboardingStep.FIRST_CHAT)
        rule.onNodeWithText(rule.activity.getString(R.string.onboarding_first_chat_action)).performClick()
        rule.onNodeWithTag(UiTags.CHAT_ROOT).assertIsDisplayed()

        rule.runOnIdle {
            app.chatStore.updateSession("session-test") { session ->
                session.copy(
                    messages = session.messages.map {
                        if (it.id == "assistant-partial") it.copy(status = "error", isError = true) else it
                    }
                )
            }
        }
        rule.waitForIdle()
        assertStep(OnboardingStep.FIRST_CHAT)

        rule.runOnIdle {
            app.chatStore.updateSession("session-test") { session ->
                session.copy(
                    messages = session.messages.map {
                        if (it.id == "assistant-partial") {
                            it.copy(status = "cancelled", isError = false)
                        } else it
                    }
                )
            }
        }
        rule.waitForIdle()
        assertStep(OnboardingStep.FIRST_CHAT)

        rule.runOnIdle {
            app.chatStore.updateSession("session-test") { session ->
                session.copy(
                    messages = session.messages.map {
                        if (it.id == "assistant-partial") {
                            it.copy(status = "success", content = "successful reply", isError = false)
                        } else it
                    }
                )
            }
        }
        waitForStep(OnboardingStep.RECORDING_SETUP)
        rule.onNodeWithTag("recording-setup-skip").performClick()
        waitForStep(OnboardingStep.COMPLETED)
        rule.onNodeWithTag(UiTags.CHAT_ROOT).assertIsDisplayed()

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag(UiTags.HUB_ROOT).assertIsDisplayed()
        rule.onNodeWithTag("main_navigation_tab_library").performClick()
        rule.onNodeWithTag(com.promenar.infinity.ui.library.LibraryTags.SCREEN).assertIsDisplayed()

        rule.onNodeWithTag("main_navigation_tab_settings").performClick()
        rule.onNodeWithTag(UiTags.SETTINGS_ROOT).assertIsDisplayed()
        // 设置页“知识”分组：存储空间设置与知识图谱，各以只在目标页出现的分组/条目确认已进入。
        val knowledgeBaseLabel = rule.activity.getString(R.string.settings_ia_kb_settings_title)
        rule.onNodeWithTag(UiTags.SETTINGS_APP_LIST)
            .performScrollToNode(hasText(knowledgeBaseLabel))
        rule.onNodeWithText(knowledgeBaseLabel)
            .performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_ia_new_session_section)).assertIsDisplayed()

        rule.runOnIdle { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag(UiTags.SETTINGS_ROOT).assertIsDisplayed()
        val knowledgeGraphLabel = rule.activity.getString(R.string.settings_ia_kg_title)
        rule.onNodeWithTag(UiTags.SETTINGS_APP_LIST)
            .performScrollToNode(hasText(knowledgeGraphLabel))
        rule.onNodeWithText(knowledgeGraphLabel)
            .performClick()
        rule.onNodeWithText(rule.activity.getString(R.string.settings_ia_kg_extraction_model)).assertIsDisplayed()
    }

    @Test
    fun localProviderRespectsBuildCapabilityAndStaysOnProvider() {
        assumeTrue("常规E2E不在force-stop分阶段模式运行", phaseArgument() == null)
        val app = rule.activity.application as InfinityApplication
        rule.activity.intent.putExtra(MainActivity.EXTRA_ONBOARDING_LOCAL_PROBE_FAILURE_FOR_TESTING, true)
        rule.activityRule.scenario.recreate()
        waitForStep(OnboardingStep.LANGUAGE)
        rule.runOnIdle {
            val store = rule.activity.onboardingStateStoreForTesting()
            check(store.selectLanguage("zh"))
            if (store.linuxOffered) check(store.completeLinuxStep())
        }
        waitForStep(OnboardingStep.PROVIDER)
        waitForTag("onboarding_service_more")
        rule.onNodeWithTag("onboarding_service_more").performScrollTo().performClick()
        waitForTag("onboarding_service_list")
        rule.onNodeWithTag("onboarding_service_list").assertIsDisplayed()
        val localTag = "onboarding_service_sheet_Local"
        if (app.localInferenceRuntimeGate.isAvailable) {
            rule.onNodeWithTag("onboarding_service_list").performScrollToNode(hasTestTag(localTag))
            rule.onNodeWithTag(localTag).performClick()
            rule.onNodeWithTag("onboarding_provider_connect").performClick()
            val unavailable = rule.activity.getString(R.string.onboarding_local_model_unavailable)
            rule.waitUntil(timeoutMillis = 10_000) {
                rule.onAllNodesWithText(unavailable).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(unavailable).performScrollTo().assertIsDisplayed()
        } else {
            // 不带本地推理的构建不提供本地选项，也就不会走到缺少模型的分支。
            rule.onAllNodesWithTag(localTag).assertCountEquals(0)
        }
        assertStep(OnboardingStep.PROVIDER)
    }

    @Test
    fun forceStopPhaseCheckpoint() {
        val phase = phaseArgument()
        assumeTrue("仅由主控通过onboardingPhase显式运行", phase != null)
        when (phase) {
            "seed_first_chat" -> {
                resetPersistentState()
                val store = rule.activity.onboardingStateStoreForTesting()
                rule.runOnIdle {
                    check(store.selectLanguage("zh"))
                    if (store.linuxOffered) check(store.completeLinuxStep())
                    check(store.recordProviderConnected("provider-force-stop"))
                    check(store.selectModel("provider-force-stop::model"))
                    check(store.recordAgentCreated("agent-force-stop", "session-force-stop"))
                }
                waitForStep(OnboardingStep.FIRST_CHAT)
            }
            "verify_first_chat" -> {
                waitForStep(OnboardingStep.FIRST_CHAT)
                rule.runOnIdle {
                    assertEquals(
                        "session-force-stop",
                        rule.activity.onboardingStateForTesting().sessionId,
                    )
                }
                rule.onNodeWithTag("onboarding_step_first_chat").assertIsDisplayed()
            }
            else -> error("未知onboardingPhase=$phase")
        }
    }

    private fun recreateAndAssert(step: OnboardingStep, tag: String) {
        rule.activityRule.scenario.recreate()
        waitForStep(step)
        rule.onNodeWithTag(tag).assertIsDisplayed()
    }

    private fun waitForStep(step: OnboardingStep) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.activity.onboardingStateForTesting().step == step
        }
    }

    /** 检查点先于转场动画落定，操作控件前等它真正出现在界面上。 */
    private fun waitForTag(tag: String) {
        rule.waitUntil(timeoutMillis = 10_000) {
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun assertStep(step: OnboardingStep) {
        rule.runOnIdle { assertEquals(step, rule.activity.onboardingStateForTesting().step) }
    }

    private fun waitForStartupReady(): InfinityApplication {
        val app = rule.activity.application as InfinityApplication
        rule.waitUntil(timeoutMillis = 20_000) {
            val state = app.startupState.value
            state == BackupStartupState.Ready || state == BackupStartupState.Blocked
        }
        check(app.startupState.value == BackupStartupState.Ready) {
            "InfinityApplication 启动恢复未就绪 (state=${app.startupState.value})，ProviderManager 暂不可读"
        }
        return app
    }

    private fun phaseArgument(): String? = InstrumentationRegistry.getArguments()
        .getString("onboardingPhase")

    companion object {
        private fun clearPersistentPreferences() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
                .edit().clear().commit()
            context.getSharedPreferences("infinity_prefs", Context.MODE_PRIVATE)
                .edit().clear().commit()
            context.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)
                .edit().putString("language", "zh").commit()
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh-CN"))
        }
    }
}
