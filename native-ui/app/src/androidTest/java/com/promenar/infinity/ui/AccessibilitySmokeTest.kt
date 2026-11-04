package com.promenar.infinity.ui

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertContentDescriptionContains
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import com.promenar.infinity.data.model.PostProcessStatus
import com.promenar.infinity.data.model.PostProcessTask
import com.promenar.infinity.data.model.PostProcessType
import com.promenar.infinity.data.model.TaskStep
import com.promenar.infinity.ui.chat.ChatApprovalLiveRegion
import com.promenar.infinity.ui.chat.ChatInputBar
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatScreenContent
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.GenerationStatus
import com.promenar.infinity.ui.chat.PostProcessChip
import com.promenar.infinity.ui.chat.taskPanelUiState
import com.promenar.infinity.ui.chat.components.RunDetailContent
import com.promenar.infinity.ui.chat.components.RunDetailTab
import com.promenar.infinity.ui.chat.components.RunDockRow
import com.promenar.infinity.ui.chat.components.runDockState
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.UnifiedPromptEditor
import com.promenar.infinity.ui.rag.DocEditorFailureCode
import com.promenar.infinity.ui.rag.DocEditorPhase
import com.promenar.infinity.ui.rag.DocEditorScreenActions
import com.promenar.infinity.ui.rag.DocEditorScreenContent
import com.promenar.infinity.ui.rag.DocEditorScreenState
import com.promenar.infinity.ui.rag.DocEditorUiState
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AccessibilitySmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun chatInputCapsulesUseApproved36DpAndPrimaryControlsUse48DpTargets() {
        composeRule.setContent {
            InfinityTheme {
                ChatScreenContent(
                    state = ChatScreenState(),
                    actions = ChatScreenActions(),
                )
            }
        }

        // 工具行可见高 40dp，模型胶囊采用 40dp 触控例外；其它动作校验实际触控边界。
        composeRule.onNodeWithTag(UiTags.CHAT_MODEL_SELECTOR)
            .assertHasClickAction()
            .assertHeightIsAtLeast(40.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_TOKEN_INDICATOR)
            .assertHasClickAction()
            .assertHeightIsAtLeast(40.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_INPUT)
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT)
            .assertHasClickAction()
            .assertHeightIsAtLeast(40.dp)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    InstrumentationRegistry.getInstrumentation().targetContext
                        .getString(R.string.common_state_collapsed),
                )
            )
        listOf(UiTags.CHAT_ADD_ATTACHMENT, UiTags.CHAT_TOKEN_INDICATOR).forEach { tag ->
            val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
            val bounds = node.touchBoundsInRoot
            val density = node.layoutInfo.density.density
            assertTrue("$tag 触控宽度", bounds.width / density >= 47.5f)
            assertTrue("$tag 触控高度", bounds.height / density >= 47.5f)
        }
        composeRule.onNodeWithTag(UiTags.CHAT_COMPOSER)
            .assertIsDisplayed()

        composeRule.onNodeWithTag(UiTags.CHAT_ADD_ATTACHMENT).performClick()
        composeRule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_IMAGE)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_ATTACH_MENU_DOCUMENT)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun criticalChatActionsMeetTouchTargetAndExposeLiveStatus() {
        var status by mutableStateOf(GenerationStatus.THINKING)
        composeRule.setContent {
            InfinityTheme {
                Column {
                    ChatInputBar(
                        text = "hello",
                        onTextChange = {},
                        onSend = {},
                        status = status,
                        isGenerating = true,
                    )
                    ChatApprovalLiveRegion { Text("approval") }
                }
            }
        }

        val action = composeRule.onNodeWithTag("chat_generation_action")
            .assertHasClickAction()
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                )
            )
        val bounds = action.fetchSemanticsNode().touchBoundsInRoot
        val minimumPx = 48f * InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        assertTrue(bounds.width >= minimumPx)
        assertTrue(bounds.height >= minimumPx)

        composeRule.onNodeWithTag("chat_approval_live_region").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            )
        )

        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        composeRule.runOnIdle { status = GenerationStatus.ERROR }
        composeRule.onNodeWithTag("chat_generation_action")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(resources.getString(R.string.chat_status_error)),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Assertive,
                )
            )

        composeRule.runOnIdle { status = GenerationStatus.COMPLETED }
        composeRule.onNodeWithTag("chat_generation_action").assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(resources.getString(R.string.chat_status_completed)),
            )
        )
    }

    @Test
    fun chatInputBarExposesStablePlaceholderNameAfterTextEntered() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val placeholder = resources.getString(R.string.chat_input_placeholder_default)
        composeRule.setContent {
            InfinityTheme {
                ChatInputBar(
                    text = "hello",
                    placeholder = placeholder,
                    onTextChange = {},
                    onSend = {},
                    status = GenerationStatus.IDLE,
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.CHAT_INPUT).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(placeholder),
            )
        )
    }

    @Test
    fun promptEditorInputExposesStableTagAndPlaceholderName() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val placeholder = resources.getString(R.string.prompt_editor_default_placeholder)
        composeRule.setContent {
            InfinityTheme {
                UnifiedPromptEditor(
                    show = true,
                    onDismiss = {},
                    onSave = { Result.success(Unit) },
                    initialText = "draft prompt",
                    placeholder = placeholder,
                    title = "Session Prompt",
                )
            }
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(UiTags.PROMPT_EDITOR_INPUT).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ContentDescription,
                listOf(placeholder),
            )
        )
    }

    @Test
    fun docEditorSavingStateAnnouncesSavingLabelOnPoliteLiveRegion() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val savingLabel = resources.getString(R.string.doc_editor_saving)
        composeRule.setContent {
            InfinityTheme {
                DocEditorScreenContent(
                    state = DocEditorScreenState(
                        editorState = DocEditorUiState(
                            phase = DocEditorPhase.Saving,
                            workspaceRootUuid = "root",
                            documentId = "doc",
                            title = "release-notes.md",
                            content = "# Release",
                            hasLoadedDocument = true,
                        ),
                    ),
                    actions = DocEditorScreenActions(),
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_SAVING)
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ContentDescription,
                    listOf(savingLabel),
                )
            )
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.LiveRegion,
                    LiveRegionMode.Polite,
                )
            )
    }

    @Test
    fun docEditorSaveErrorNoticeUsesAssertiveLiveRegion() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val saveErrorTitle = resources.getString(R.string.doc_editor_save_failed_title)
        composeRule.setContent {
            InfinityTheme {
                DocEditorScreenContent(
                    state = DocEditorScreenState(
                        editorState = DocEditorUiState(
                            phase = DocEditorPhase.SaveError,
                            workspaceRootUuid = "root",
                            documentId = "doc",
                            title = "release-notes.md",
                            content = "# Release",
                            persistedContent = "# Release draft",
                            hasLoadedDocument = true,
                            contentDirty = true,
                            failureCode = DocEditorFailureCode.ContentSaveFailed,
                        ),
                    ),
                    actions = DocEditorScreenActions(),
                )
            }
        }

        composeRule.onNodeWithTag(UiTags.DOC_EDITOR_STATE_SAVE_ERROR).assertExists()
        composeRule.onNodeWithText(saveErrorTitle).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.LiveRegion,
                LiveRegionMode.Assertive,
            )
        )
    }

    @Test
    fun pageRemainsScrollableAtTwoTimesFontScale() {
        composeRule.setContent {
            val density = LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale = 2f),
            ) {
                InfinityTheme {
                    InfinityPageLayout(title = "A deliberately long accessible page title") {
                        Text("Body content remains available in landscape and large text layouts")
                    }
                }
            }
        }

        composeRule.onNodeWithTag("infinity_page_body").assertIsDisplayed()
    }

    @Test
    fun defaultResourcesContainNoChineseAndChineseLocaleResolvesTranslatedKeys() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        fun resourcesFor(tag: String) = base.createConfigurationContext(
            Configuration(base.resources.configuration).apply {
                setLocale(Locale.forLanguageTag(tag))
            }
        ).resources

        val english = resourcesFor("en")
        val chinesePattern = Regex("[\\u4E00-\\u9FFF]")
        // 引导页语言选择在用户选语言之前出现，标签有意中英并列；例外仍须带完整英文，不能退化成漏翻。
        val intentionallyBilingual = mapOf(R.string.onboarding_choose_language to "Choose language")
        intentionallyBilingual.forEach { (id, englishPart) ->
            assertTrue("Bilingual label lost its English part", english.getString(id).contains(englishPart))
        }
        R.string::class.java.fields.forEach { field ->
            val id = field.getInt(null)
            if (id in intentionallyBilingual) return@forEach
            val value = english.getText(id).toString()
            assertTrue("Default English resource contains Chinese: ${field.name}", !chinesePattern.containsMatchIn(value))
        }

        val chinese = resourcesFor("zh-CN")
        assertTrue(chinese.getString(R.string.nav_tab_chat).contains("对话"))
        assertTrue(chinese.getString(R.string.chat_status_thinking).contains("思考"))
    }

    @Test
    fun postProcessChipAndRunDockAccessibilityCoverage() {
        val resources = InstrumentationRegistry.getInstrumentation().targetContext.resources
        val postProcessLabel = resources.getString(R.string.chat_postprocess_summary)

        val fakeTask = PostProcessTask(
            id = "task-1",
            type = PostProcessType.AUTO_SUMMARY,
            status = PostProcessStatus.RUNNING,
        )

        val rootNode = TaskStep(
            id = "step-1",
            title = "分析文档结构",
            status = "doing",
            children = listOf(
                TaskStep(id = "step-1-1", title = "提取大纲", status = "done"),
                TaskStep(id = "step-1-2", title = "生成脑图", status = "pending"),
            ),
        )
        val plan = taskPanelUiState(listOf(rootNode), isGenerating = false)!!
        composeRule.setContent {
            InfinityTheme {
                Column {
                    PostProcessChip(
                        task = fakeTask,
                        onRemove = {},
                    )
                    RunDockRow(
                        state = runDockState(plan, false, 0, 0, 0, null, null)!!,
                        onOpen = {},
                    )
                    RunDetailContent(
                        initialTab = RunDetailTab.PLAN,
                        plan = plan,
                        isGenerating = false,
                        turnStartedAt = null,
                        activity = null,
                        stateBoard = null,
                        onDismiss = {},
                        onContinue = {},
                        onComplete = {},
                        onHidePlan = {},
                        onClearPlan = {},
                        onEditState = { _, _ -> null },
                        onRemoveState = { null },
                        modifier = Modifier.weight(1f),
                        nowMillis = 0L,
                    )
                }
            }
        }

        composeRule.waitForIdle()

        composeRule.onNodeWithText(postProcessLabel).assertIsDisplayed()

        // 当前步骤同时出现在运行坞与运行详情的步骤列表中，这里校验列表内的步骤。
        composeRule.onNode(
            hasText("分析文档结构", substring = true) and hasAnyAncestor(hasTestTag(UiTags.CHAT_TASK_STEP_LIST)),
        ).assertIsDisplayed()
        composeRule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_MAIN).assertContentDescriptionContains("1/2", substring = true)
        composeRule.onNode(
            hasText("生成脑图", substring = true) and hasAnyAncestor(hasTestTag(UiTags.CHAT_TASK_STEP_LIST)),
        ).assertIsDisplayed()

        composeRule.onNodeWithTag(UiTags.CHAT_RUN_DOCK_MAIN)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_RUN_SHEET_MENU)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_TASK_CONTINUE)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
        composeRule.onNodeWithTag(UiTags.CHAT_TASK_COMPLETE)
            .assertHasClickAction()
            .assertHeightIsAtLeast(48.dp)
    }
}
