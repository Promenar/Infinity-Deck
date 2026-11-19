package com.promenar.infinity.ui.hub

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.ui.AdaptiveNavigationSurface
import com.promenar.infinity.ui.AppTab
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatScreenContent
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.ChatUiState
import com.promenar.infinity.ui.chat.LocalChatNavigationIcon
import com.promenar.infinity.ui.common.InfinityAdaptivePaneHost
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private const val INNER_WIDTH_DP = 814
private const val INNER_HEIGHT_DP = 900
private const val NOW = 1_790_000_000_000L

private val coder = AgentDisplayItem(Agent(id = "coder", name = "Coder", icon = "💻", color = "#5B8DEF"), "代码助手", "写代码与审查")
private val architect = AgentDisplayItem(Agent(id = "architect", name = "Architect", color = "#4CAF7D"), "架构师", "接口与契约")
private val writer = AgentDisplayItem(Agent(id = "writer", name = "Writer", icon = "📝", color = "#E0A030"), "写作助手", "周报与文档")

private fun session(id: String, agentId: String, title: String, minutesAgo: Long, pinned: Boolean = false, parentId: String? = null) =
    Session(id = id, agentId = agentId, title = title, isPinned = pinned, parentSessionId = parentId, createdAt = 0, updatedAt = NOW - minutesAgo * 60_000)

private val sessions = mapOf(
    "coder" to listOf(
        session("s-layout", "coder", "折叠屏内屏首页三列布局、右列独立导航与展开动画的实现方案评审", 2, pinned = true),
        session("s-layout-branch", "coder", "40dp 电路侧栏与纯黑熏黑玻璃复核分支", 1, parentId = "s-layout"),
        session("s-ci", "coder", "修复截图测试在夜间构建中偶发失败", 30),
    ),
    "architect" to listOf(
        session("s-ask", "architect", "提问工具接口评审", 5),
        session("s-queue", "architect", "多会话并发排队策略", 8),
    ),
    "writer" to listOf(session("s-weekly", "writer", "周报整理", 12)),
)

private val activity = mapOf(
    "s-layout" to HubSessionActivity.GENERATING,
    "s-layout-branch" to HubSessionActivity.GENERATING,
    "s-ask" to HubSessionActivity.AWAITING_ANSWER,
    "s-weekly" to HubSessionActivity.AWAITING_APPROVAL,
    "s-queue" to HubSessionActivity.QUEUED,
)

private fun message(id: String, role: MessageRole, content: String) =
    Message(id = id, role = role, content = content, createdAt = NOW)

@Composable
private fun HubThreePanePreview(expanded: Boolean, dark: Boolean, drawer: Boolean = false) {
    val selected = "s-layout"
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        val railWidth = HubNavigationRailWidth
        val shiftPx = with(LocalDensity.current) { hubPaneGeometry(railWidth, railWidth, expanded = false).shift.toPx() }
        val progress = if (expanded) 1f else 0f
        AdaptiveNavigationSurface(
            expanded = true,
            selectedTab = AppTab.CHAT,
            onTabSelected = {},
            railShift = { -shiftPx * progress },
        ) {
            HubThreePaneLayout(
                railWidth = railWidth,
                expanded = expanded,
                expansion = { progress },
                stripShown = expanded,
                drawerShown = drawer,
                drawerProgress = { if (drawer) 1f else 0f },
                onDismissDrawer = {},
                list = {
                    AgentHubScreenContent(
                        state = AgentHubScreenState(
                            displayAgents = listOf(coder, architect, writer),
                            sessionsByAgent = sessions,
                            sessionActivity = activity,
                            selectedSessionId = selected,
                        ),
                        actions = AgentHubScreenActions(),
                    )
                },
                strip = {
                    HubSessionStrip(
                        entries = hubStripEntries(listOf(coder, architect, writer), sessions, selected),
                        activity = activity,
                        selectedSessionId = selected,
                        enabled = true,
                        onOpenList = {},
                        onSelect = {},
                    )
                },
            ) {
                CompositionLocalProvider(LocalChatNavigationIcon provides { HubExpandButton(expanded = expanded, onClick = {}) }) {
                    // 宽窄由会话列自身宽度决定：三列 438dp 为窄会话，展开 742dp 为带检视器的宽会话。
                    InfinityAdaptivePaneHost(paneVisible = false, onBack = {}, pane = {}) {
                        ChatScreenContent(
                            state = ChatScreenState(
                                uiState = ChatUiState(
                                    session = sessions.getValue("coder").first(),
                                    agentName = "代码助手",
                                    messages = listOf(
                                        message("u1", MessageRole.USER, "把首页改成内屏三列：导航、会话列表、会话。"),
                                        message("a1", MessageRole.ASSISTANT, "好的。三列宽度为 80、296、438dp；点会话左上角的展开按钮，整排左移，会话扩到 742dp 并显示检视器。"),
                                    ),
                                ),
                            ),
                            actions = ChatScreenActions(),
                        )
                    }
                }
            }
        }
    }
}

@PreviewTest
@Preview(name = "首页三列 深中814", widthDp = INNER_WIDTH_DP, heightDp = INNER_HEIGHT_DP, locale = "zh-rCN")
@Composable
fun hubThreePaneDarkPreview() = HubThreePanePreview(expanded = false, dark = true)

@PreviewTest
@Preview(name = "首页三列 浅中814 字号130", widthDp = INNER_WIDTH_DP, heightDp = INNER_HEIGHT_DP, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun hubThreePaneLargeFontPreview() = HubThreePanePreview(expanded = false, dark = false)

@PreviewTest
@Preview(name = "首页三列 展开 深中814", widthDp = INNER_WIDTH_DP, heightDp = INNER_HEIGHT_DP, locale = "zh-rCN")
@Composable
fun hubThreePaneExpandedPreview() = HubThreePanePreview(expanded = true, dark = true)

@PreviewTest
@Preview(name = "首页三列 展开窄列表 浅中814", widthDp = INNER_WIDTH_DP, heightDp = INNER_HEIGHT_DP, locale = "zh-rCN")
@Composable
fun hubThreePaneDrawerPreview() = HubThreePanePreview(expanded = true, dark = false, drawer = true)
