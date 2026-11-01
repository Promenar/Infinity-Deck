package com.promenar.infinity.ui.lab.chat

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.ui.AdaptiveNavigationFrame
import com.promenar.infinity.ui.AppTab
import com.promenar.infinity.ui.NavigationBody
import com.promenar.infinity.ui.chat.LocalChatNavigationIcon
import com.promenar.infinity.ui.hub.AgentDisplayItem
import com.promenar.infinity.ui.hub.AgentHubScreenActions
import com.promenar.infinity.ui.hub.AgentHubScreenContent
import com.promenar.infinity.ui.hub.AgentHubScreenState
import com.promenar.infinity.ui.hub.HubExpandButton
import com.promenar.infinity.ui.hub.HubNavigationRailWidth
import com.promenar.infinity.ui.hub.HubSessionActivity
import com.promenar.infinity.ui.hub.HubSessionStrip
import com.promenar.infinity.ui.hub.HubThreePaneLayout
import com.promenar.infinity.ui.hub.hubPaneGeometry
import com.promenar.infinity.ui.hub.hubStripEntries
import com.promenar.infinity.ui.hub.hubThreePaneAvailable

/** 首页列表里演示会话所属的故事：ADE 开发会话或 502 排障会话。 */
internal enum class LabHubStory { ADE, OPS }

private const val LAB_AGENT_INFINITY = "lab-agent-infinity"
private const val LAB_AGENT_ATLAS = "lab-agent-atlas"
private const val LAB_HUB_ADE = "lab-hub-ade"
private const val LAB_HUB_OPS = "lab-hub-ops"
private const val MINUTE = 60_000L

/** 两个演示助手：Infinity 承载两段演示会话，Atlas 没有会话。 */
private val labHubAgents = listOf(
    AgentDisplayItem(Agent(LAB_AGENT_INFINITY, LAB_AGENT, icon = "sym:auto_awesome", color = "#C0C1FF"), LAB_AGENT, ""),
    AgentDisplayItem(Agent(LAB_AGENT_ATLAS, "Atlas", icon = "sym:edit_note", color = "#C0C1FF"), "Atlas", ""),
)

/**
 * 首页列表在这一帧的样子：当前会话随脚本更新时间与生成状态，另一段故事的会话排在它之前或之后；
 * ADE 派发分支后，分支会话嵌在主会话之下。[frame] 的时间已平移到墙钟。
 */
internal data class LabHubList(
    val sessions: List<Session>,
    val activity: Map<String, HubSessionActivity>,
    val selectedSessionId: String,
)

internal fun labHubList(story: LabHubStory, frame: LabChatFrame, zh: Boolean): LabHubList {
    val ui = frame.screen.uiState
    val current = ui.session?.title.orEmpty()
    val lastAt = ui.messages.maxOfOrNull { it.createdAt } ?: 0L
    val currentId = if (story == LabHubStory.ADE) LAB_HUB_ADE else LAB_HUB_OPS
    val currentActivity = when {
        ui.approvalRequest != null -> HubSessionActivity.AWAITING_APPROVAL
        ui.isGenerating -> HubSessionActivity.GENERATING
        else -> null
    }
    fun session(id: String, title: String, at: Long, parent: String? = null, branch: String? = null) = Session(
        id = id,
        agentId = LAB_AGENT_INFINITY,
        title = title,
        createdAt = at,
        updatedAt = at,
        parentSessionId = parent,
        worktreeBranch = branch,
    )
    val sessions = when (story) {
        // 排障在开发之后发生，开发会话里列表只有它自己与派发出的分支。
        LabHubStory.ADE -> listOf(session(LAB_HUB_ADE, current, lastAt)) +
            frame.branches.map { session(it.sessionId, it.title, it.updatedAt, LAB_HUB_ADE, it.worktreeBranch) }
        LabHubStory.OPS -> {
            val ade = LabAdeText(zh)
            listOf(
                session(LAB_HUB_OPS, current, lastAt),
                session(LAB_HUB_ADE, ade.sessionTitle, lastAt - 12 * MINUTE),
                session(ADE_BRANCH_SESSION, ade.branchTitle, lastAt - 14 * MINUTE, LAB_HUB_ADE, "config-toml"),
            )
        }
    }
    val activity = buildMap {
        currentActivity?.let { put(currentId, it) }
        // 分支派发后在独立推进，列表里一直标为生成中。
        sessions.filter { it.parentSessionId != null }.forEach { put(it.id, HubSessionActivity.GENERATING) }
    }
    return LabHubList(sessions, activity, currentId)
}

/**
 * 内屏首页的三列外框：导航栏｜会话列表｜会话列，与真实首页同一套组件与几何；
 * 会话列左上角的按钮在三列与展开（图标条｜会话）之间切换。窗口不够宽（外屏）时只有会话本身。
 */
@Composable
internal fun LabHubFrame(
    list: LabHubList,
    initialExpanded: Boolean,
    detail: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!hubThreePaneAvailable(maxWidth.value.toInt(), maxHeight.value.toInt())) {
            detail()
            return@BoxWithConstraints
        }
        var expanded by rememberSaveable { mutableStateOf(initialExpanded) }
        val expansion = if (expanded) 1f else 0f
        val railWidth = HubNavigationRailWidth
        val shiftPx = with(LocalDensity.current) { hubPaneGeometry(railWidth, railWidth, expanded = false).shift.toPx() }
        val sessionsByAgent = remember(list) { list.sessions.groupBy { it.agentId } }
        val strip = remember(list) { hubStripEntries(labHubAgents, sessionsByAgent, list.selectedSessionId) }
        AdaptiveNavigationFrame(
            expanded = true,
            selectedTab = AppTab.CHAT,
            onTabSelected = {},
            railShift = { -shiftPx * expansion },
        ) {
            NavigationBody(fullBleed = true) {
                HubThreePaneLayout(
                    railWidth = railWidth,
                    expanded = expanded,
                    expansion = { expansion },
                    stripShown = expanded,
                    drawerShown = false,
                    drawerProgress = { 0f },
                    onDismissDrawer = {},
                    list = {
                        AgentHubScreenContent(
                            state = AgentHubScreenState(
                                displayAgents = labHubAgents,
                                sessionsByAgent = sessionsByAgent,
                                sessionActivity = list.activity,
                                selectedSessionId = list.selectedSessionId,
                            ),
                            actions = AgentHubScreenActions(),
                        )
                    },
                    strip = {
                        HubSessionStrip(
                            entries = strip,
                            activity = list.activity,
                            selectedSessionId = list.selectedSessionId,
                            enabled = true,
                            onOpenList = {},
                            onSelect = {},
                        )
                    },
                ) {
                    val icon: @Composable () -> Unit = { HubExpandButton(expanded = expanded, onClick = { expanded = !expanded }) }
                    CompositionLocalProvider(LocalChatNavigationIcon provides icon, content = detail)
                }
            }
        }
    }
}
