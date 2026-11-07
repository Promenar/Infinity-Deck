package com.promenar.infinity.ui.hub

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.test.platform.app.InstrumentationRegistry
import com.promenar.infinity.R
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.testing.UiTags
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class AgentHubScreenContentTest {
    @get:Rule
    val rule = createComposeRule()

    private val resources
        get() = InstrumentationRegistry.getInstrumentation().targetContext.resources

    private val coder = AgentDisplayItem(
        agent = previewAgent("agent-coder", "Coding Expert", "Full-stack development"),
        title = "Coding Expert",
        subtitle = "Full-stack development",
    )

    @Test
    fun plusMenuAddAgentItemDispatchesWithoutOpeningSession() {
        val add = AtomicBoolean(false)
        val open = AtomicBoolean(false)
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(displayAgents = listOf(coder)),
                    actions = AgentHubScreenActions(
                        onRequestAdd = { add.set(true) },
                        onOpenChat = { open.set(true) },
                    ),
                )
            }
        }
        rule.onNodeWithTag(UiTags.HUB_ADD_AGENT).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.HUB_NEW_CHAT).performClick()
        rule.onNodeWithTag(UiTags.HUB_ADD_AGENT).assertHasClickAction().performClick()
        assertThat(add.get()).isTrue()
        assertThat(open.get()).isFalse()
    }

    @Test
    fun agentRowExpandsInPlaceAfterScrollingAndOpensItsSession() {
        val agents = (0 until 24).map { index ->
            AgentDisplayItem(
                agent = previewAgent("agent-$index", "Agent $index", "Description $index"),
                title = "Agent $index",
                subtitle = "Description $index",
            )
        }
        val opened = AtomicReference<String>()
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    AgentHubScreenState(
                        displayAgents = agents,
                        sessionsByAgent = mapOf("agent-18" to listOf(
                            Session(id = "session-18", agentId = "agent-18", title = "Agent 18 chat"),
                        )),
                    ),
                    AgentHubScreenActions(onOpenChat = { opened.set(it) }),
                )
            }
        }
        rule.onNodeWithTag(UiTags.HUB_AGENT_LIST).performScrollToNode(hasTestTag(UiTags.hubAgentCard("agent-18")))
        rule.onNodeWithTag(UiTags.hubAgentSession("session-18")).assertDoesNotExist()
        rule.onNodeWithTag(UiTags.hubAgentCard("agent-18")).assertIsDisplayed().performClick()
        assertThat(opened.get()).isNull()
        rule.onNodeWithTag(UiTags.hubAgentCard("agent-18")).assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                resources.getString(R.string.common_state_expanded),
            ),
        )
        rule.onNodeWithTag(UiTags.HUB_AGENT_LIST).performScrollToNode(hasTestTag(UiTags.hubAgentSession("session-18")))
        rule.onNodeWithTag(UiTags.hubAgentSession("session-18")).performClick()
        assertThat(opened.get()).isEqualTo("session-18")
    }

    @Test
    fun expandedAgentHasNoInlineNewSessionRow() {
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(displayAgents = listOf(coder)),
                    actions = AgentHubScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentCard("agent-coder")).performClick()
        rule.onNodeWithText(resources.getString(R.string.hub_sessions_empty)).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.hubAgentNewSession("agent-coder")).assertDoesNotExist()
    }

    private fun previewAgent(
        id: String,
        name: String,
        description: String,
        isPinned: Boolean = false,
        avatarPath: String? = null,
    ) = Agent(
        id = id,
        name = name,
        description = description,
        icon = "A",
        color = "#5B8DEF",
        isPinned = isPinned,
        avatarPath = avatarPath,
    )

    @Test
    fun customAvatarPathSelectsTheCustomImageBranchInHubRow() {
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(
                            AgentDisplayItem(
                                agent = previewAgent(
                                    id = "agent-custom",
                                    name = "Custom Avatar",
                                    description = "Uses the persisted image",
                                    avatarPath = "/data/user/0/test/files/avatars/custom.img",
                                ),
                                title = "Custom Avatar",
                                subtitle = "Uses the persisted image",
                            ),
                        ),
                    ),
                    actions = AgentHubScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(
            "hub_agent_custom_avatar:agent-custom",
            useUnmergedTree = true,
        ).assertExists()
    }

    @Test
    fun agentLongPressOpensLocalizedMenuWithoutNavigating() {
        val opened = AtomicBoolean(false)
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(displayAgents = listOf(coder)),
                    actions = AgentHubScreenActions(
                        onOpenChat = { opened.set(true) },
                        onCreateSession = { opened.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentCard("agent-coder")).performTouchInput { longClick() }

        rule.onNodeWithTag(UiTags.HUB_AGENT_MENU_PIN).assertExists()
        rule.onNodeWithText(resources.getString(R.string.shared_btn_edit)).assertExists()
        rule.onNodeWithText(resources.getString(R.string.shared_btn_delete)).assertExists()
        rule.onNodeWithTag(UiTags.hubAgentNewSession("agent-coder")).assertDoesNotExist()
        assertThat(opened.get()).isFalse()
    }

    @Test
    fun recentSessionClickDispatchesExactSessionId() {
        val opened = AtomicReference<String>()
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(coder),
                        sessionsByAgent = mapOf("agent-coder" to listOf(
                            Session(id = "session-exact", agentId = "agent-coder", title = "Existing conversation"),
                        )),
                    ),
                    actions = AgentHubScreenActions(onOpenChat = { opened.set(it) }),
                )
            }
        }
        rule.onNodeWithText(resources.getString(R.string.hub_section_recent)).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.hubSession("session-exact")).assertHasClickAction().performClick()
        assertThat(opened.get()).isEqualTo("session-exact")
    }

    @Test
    fun newChatMenuCreatesSessionForChosenAgent() {
        val created = AtomicReference<String>()
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(displayAgents = listOf(coder)),
                    actions = AgentHubScreenActions(onCreateSession = { created.set(it) }),
                )
            }
        }

        rule.onNodeWithTag(UiTags.HUB_NEW_CHAT).assertHasClickAction().performClick()
        rule.onNodeWithTag(UiTags.hubNewChatAgent("agent-coder")).performClick()
        assertThat(created.get()).isEqualTo("agent-coder")
    }

    @Test
    fun pinnedRowExposesLocalizedStateDescription() {
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(
                        displayAgents = listOf(
                            AgentDisplayItem(
                                agent = previewAgent(
                                    id = "agent-pinned",
                                    name = "Pinned Agent",
                                    description = "Pinned for quick access",
                                    isPinned = true,
                                ),
                                title = "Pinned Agent",
                                subtitle = "Pinned for quick access",
                            ),
                        ),
                    ),
                    actions = AgentHubScreenActions(),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentCard("agent-pinned"))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    resources.getString(R.string.sessions_tag_pinned) + ", " +
                        resources.getString(R.string.common_state_collapsed),
                ),
            )
    }

    @Test
    fun shortSwipeDoesNotTriggerDeleteOrNavigationOnHighDensityDevice() {
        val deleteRequested = AtomicBoolean(false)
        val opened = AtomicBoolean(false)
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(displayAgents = listOf(coder)),
                    actions = AgentHubScreenActions(
                        onRequestDelete = { deleteRequested.set(true) },
                        onOpenChat = { opened.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.hubAgentCard("agent-coder"))
            .performTouchInput {
                swipe(
                    start = center,
                    end = center - Offset(120f, 0f),
                    durationMillis = 200,
                )
            }
        rule.waitForIdle()

        assertThat(deleteRequested.get()).isFalse()
        assertThat(opened.get()).isFalse()
    }

    @Test
    fun emptyStateAddButtonIsClickable() {
        val clicked = AtomicBoolean(false)
        rule.setContent {
            InfinityTheme {
                AgentHubScreenContent(
                    state = AgentHubScreenState(displayAgents = emptyList()),
                    actions = AgentHubScreenActions(
                        onRequestAdd = { clicked.set(true) },
                    ),
                )
            }
        }

        rule.onNodeWithTag(UiTags.HUB_EMPTY_STATE).assertExists()
        rule.onNodeWithTag(UiTags.HUB_EMPTY_ADD_AGENT)
            .assertHasClickAction()
            .performClick()

        assertThat(clicked.get()).isTrue()
    }
}
