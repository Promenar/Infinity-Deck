package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import com.promenar.infinity.ui.common.LocalInfinityInspectorLayout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.state.StateBoard
import com.promenar.infinity.data.state.StateBoardSnapshot
import com.promenar.infinity.ui.chat.components.RunDetailContent
import com.promenar.infinity.ui.chat.components.RunDetailTab
import com.promenar.infinity.ui.chat.components.RunDockRow
import com.promenar.infinity.ui.chat.components.RunDockTone
import com.promenar.infinity.ui.chat.components.runDockState
import com.promenar.infinity.data.state.StateField
import com.promenar.infinity.ui.git.GitEntrySummary
import kotlinx.serialization.json.JsonPrimitive
import com.promenar.infinity.ui.theme.*

// 生成中：输入框浮岛顶部是一行运行坞，浮岛描边亮起流光；输入框仍可编辑，占位文字提示可以先写下一条。
// 有计划时运行坞显示当前步骤与进度线，轮次、调用与 Token 统计只在运行详情里出现。
@PreviewTest
@Preview(name = "生成浮岛 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatGenerationProgressDarkPreview() = GenerationProgressPage(english = false)

@PreviewTest
@Preview(name = "生成浮岛 深英360 大字号", widthDp = 360, heightDp = 892, locale = "en", fontScale = 1.3f)
@Composable
fun chatGenerationProgressEnglishLargeFontPreview() = GenerationProgressPage(english = true)

@PreviewTest
@Preview(name = "生成浮岛 浅中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatGenerationProgressLightPreview() = GenerationProgressPage(english = false, mode = InfinityThemeMode.LIGHT)

@PreviewTest
@Preview(name = "生成浮岛 计划 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatGenerationProgressPlanDarkPreview() = GenerationProgressPage(english = false, withPlan = true)

@PreviewTest
@Preview(name = "生成浮岛 计划 浅英360 大字号", widthDp = 360, heightDp = 892, locale = "en", fontScale = 1.3f)
@Composable
fun chatGenerationProgressPlanLightPreview() = GenerationProgressPage(english = true, mode = InfinityThemeMode.LIGHT, withPlan = true)

// 首字到达前：回复位置显示静止的三点（预览中不运行波浪动画），浅深色下主色到第三色的过渡都须清晰可辨。
@PreviewTest
@Preview(name = "等待首字 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun chatGenerationWaitingDarkPreview() = GenerationProgressPage(english = false, waiting = true)

@PreviewTest
@Preview(name = "等待首字 浅英360", widthDp = 360, heightDp = 892, locale = "en")
@Composable
fun chatGenerationWaitingLightPreview() = GenerationProgressPage(english = true, mode = InfinityThemeMode.LIGHT, waiting = true)

private fun progressPlan(english: Boolean): TaskPanelUiState = requireNotNull(
    taskPanelUiState(
        listOf(
            TaskStep(id = "probe-root", title = if (english) "List the workspace root" else "列出工作区根目录", status = "done", sortOrder = 0),
            TaskStep(id = "probe-md", title = if (english) "Find Markdown files" else "查找 Markdown 文件", status = "doing", sortOrder = 1),
            TaskStep(id = "summary", title = if (english) "Summarize both results" else "总结两个结果", status = "todo", sortOrder = 2),
        ),
        isGenerating = true,
    ),
)

@Composable
private fun GenerationProgressPage(
    english: Boolean,
    mode: InfinityThemeMode = InfinityThemeMode.DARK,
    withPlan: Boolean = false,
    waiting: Boolean = false,
) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatScreenContent(
                state = ChatScreenState(
                    uiState = ChatUiState(
                        session = Session("progress", "assistant", title = if (english) "Media test" else "媒体测试", modelId = "deepseek::v4-flash"),
                        agentName = "Infinity Workbench",
                        messages = progressMessages(english, waiting),
                        isGenerating = true,
                        status = GenerationStatus.RECEIVING,
                    ),
                    modelDisplayNames = mapOf("deepseek::v4-flash" to "DeepSeek V4 Flash"),
                    tokenState = ChatViewModel.TokenIndicatorState(used = 21800, max = 128000),
                    taskPanel = if (withPlan) progressPlan(english) else null,
                ),
                actions = ChatScreenActions(),
            )
        }
    }
}

private fun progressMessages(english: Boolean, waiting: Boolean = false): List<Message> {
    val answer = if (english) {
        "Both probes are back. The workspace root only holds the media and skills folders. The media test folder contains four files: two MP4 clips, one chart image and one tone recording, and the two MP4 clips are identical copies. There are no Markdown files anywhere in the workspace."
    } else {
        "两个探子都回来了：工作区根目录下只有 media 和 skills 两个目录，media/test 里有 4 个媒体文件（两个 mp4、一张图表和一段音频），其中两个 mp4 是同一份副本，而且整个工作区一个 .md 文件都没有。"
    }
    val question = if (english) {
        "Task 1: list the workspace root. Task 2: find any Markdown files in the workspace. Then summarize both results in one sentence."
    } else {
        "任务一：列出工作区根目录。任务二：查找工作区内所有 Markdown 文件。最后用一句话总结两个结果。"
    }
    val rounds = (1..3).flatMap { round ->
        listOf(
            Message("user-$round", MessageRole.USER, question, createdAt = 1700000000000L + round * 60_000),
            Message("assistant-$round", MessageRole.ASSISTANT, answer, modelId = "deepseek::v4-flash", createdAt = 1700000030000L + round * 60_000),
        )
    }
    if (!waiting) return rounds
    return rounds.take(2) + listOf(
        Message("user-next", MessageRole.USER, question, createdAt = 1700000300000L),
        Message("assistant-next", MessageRole.ASSISTANT, "", modelId = "deepseek::v4-flash", createdAt = 1700000330000L),
    )
}

private const val DOCK_NOW = 1_792_400_000_000L

// 运行坞的典型状态：带计划运行中（步骤、进度与整分钟用时）、待批准、无计划运行中、暂停在某一步、
// 计划完成且有状态面板；文字、计数与图标须在同一条视觉中线上。
@Composable
private fun RunDockStates(mode: InfinityThemeMode, english: Boolean) {
    val plan = progressPlan(english)
    val stopped = requireNotNull(
        taskPanelUiState(
            listOf(
                TaskStep(id = "probe-root", title = if (english) "List the workspace root" else "列出工作区根目录", status = "done", sortOrder = 0),
                TaskStep(id = "probe-md", title = if (english) "Find Markdown files" else "查找 Markdown 文件", status = "doing", sortOrder = 1),
            ),
            isGenerating = false,
        ),
    )
    val finished = requireNotNull(
        taskPanelUiState(
            listOf(TaskStep(id = "probe-root", title = if (english) "List the workspace root" else "列出工作区根目录", status = "done", sortOrder = 0)),
            isGenerating = false,
        ),
    )
    val states = listOf(
        runDockState(plan, true, 0, 1, 0, null, 12 * 60_000L + 4_000L),
        runDockState(plan, true, 2, 0, 0, null, 3 * 60_000L),
        runDockState(null, true, 0, 0, 2, null, 30_000L),
        runDockState(stopped, false, 0, 0, 0, null, null),
        runDockState(finished, false, 0, 0, 0, if (english) "Release checks" else "发布检查", null),
    ).map(::requireNotNull)
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(vertical = 8.dp)) {
                states.forEach { RunDockRow(state = it, onOpen = {}) }
            }
        }
    }
}

@PreviewTest
@Preview(name = "运行坞 深中411", widthDp = 411, locale = "zh-rCN")
@Preview(name = "运行坞 深中360 大字", widthDp = 360, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun runDockStatesDarkPreview() = RunDockStates(InfinityThemeMode.DARK, english = false)

@PreviewTest
@Preview(name = "运行坞 浅英360 大字", widthDp = 360, locale = "en", fontScale = 1.3f)
@Composable
fun runDockStatesLightPreview() = RunDockStates(InfinityThemeMode.LIGHT, english = true)

private fun groupedPlan(english: Boolean): TaskPanelUiState {
    fun leaf(id: String, zh: String, en: String, status: String, order: Int) =
        TaskStep(id = id, title = if (english) en else zh, status = status, sortOrder = order)
    return requireNotNull(
        taskPanelUiState(
            listOf(
                TaskStep(
                    id = "env", title = if (english) "Probe the environment" else "探测运行环境", sortOrder = 0,
                    children = listOf(
                        leaf("env-1", "列出工作区", "List the workspace", "done", 0),
                        leaf("env-2", "检查 Python 与 Termux", "Check Python and Termux", "done", 1),
                    ),
                ),
                TaskStep(
                    id = "files", title = if (english) "File tools" else "文件工具读写", sortOrder = 1,
                    children = listOf(
                        leaf("files-1", "写入并读回测试文件", "Write and read back a file", "done", 0),
                        leaf("files-2", "同目录改名", "Rename in place", "doing", 1),
                        leaf("files-3", "补丁与版本校验", "Patch with version check", "todo", 2),
                    ),
                ),
                leaf("summary", "汇总能力报告", "Summarize the capability report", "todo", 2),
            ),
            isGenerating = true,
            goal = if (english) "Probe agent capabilities" else "探测 Agent 能力",
        ),
    )
}

// 运行详情计划页：已完成的分组收为一行，当前步骤高亮；底部是“标记完成 / 继续处理”。
@Composable
private fun RunDetailPage(mode: InfinityThemeMode, english: Boolean) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            RunDetailContent(
                initialTab = RunDetailTab.PLAN,
                plan = groupedPlan(english),
                isGenerating = true,
                turnStartedAt = DOCK_NOW - (12 * 60_000L + 4_000L),
                activity = null,
                stateBoard = StateBoardSnapshot(1, StateBoard(if (english) "Progress" else "进度", emptyList())),
                onDismiss = {},
                onContinue = {},
                onComplete = {},
                onHidePlan = {},
                onClearPlan = {},
                onEditState = { _, _ -> null },
                onRemoveState = { null },
                modifier = Modifier.fillMaxSize(),
                nowMillis = DOCK_NOW,
            )
        }
    }
}

@PreviewTest
@Preview(name = "运行详情 计划 深中411", widthDp = 411, heightDp = 760, locale = "zh-rCN")
@Composable
fun runDetailPlanDarkPreview() = RunDetailPage(InfinityThemeMode.DARK, english = false)

@PreviewTest
@Preview(name = "运行详情 计划 浅英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun runDetailPlanLightPreview() = RunDetailPage(InfinityThemeMode.LIGHT, english = true)

// 空会话：粒子字标停在笔画上（预览中不运行动画），下方是提示语，输入框浮岛为静止质感。
@Composable
private fun EmptyChatPage(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatScreenContent(
                state = ChatScreenState(
                    uiState = ChatUiState(
                        session = Session("empty", "assistant", title = "", modelId = "deepseek::v4-flash"),
                        agentName = "Infinity Workbench",
                    ),
                    modelDisplayNames = mapOf("deepseek::v4-flash" to "DeepSeek V4 Flash"),
                ),
                actions = ChatScreenActions(),
            )
        }
    }
}

@PreviewTest
@Preview(name = "空会话字标 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun emptyChatWordmarkDarkPreview() = EmptyChatPage(InfinityThemeMode.DARK)

@PreviewTest
@Preview(name = "空会话字标 浅英360", widthDp = 360, heightDp = 780, locale = "en")
@Composable
fun emptyChatWordmarkLightPreview() = EmptyChatPage(InfinityThemeMode.LIGHT)

private fun sideBoard(english: Boolean) = StateBoardSnapshot(
    3,
    StateBoard(
        if (english) "Capability probe" else "能力探测",
        listOf(
            StateField("passed", if (english) "Passed" else "已通过", "number", JsonPrimitive(7)),
            StateField("failed", if (english) "Failed" else "失败", "number", JsonPrimitive(1)),
            StateField("tool", if (english) "Current tool" else "当前工具", "text", JsonPrimitive("rename_file")),
            StateField("hidden", if (english) "Hidden" else "不显示", "text", JsonPrimitive("—")),
        ),
    ),
)

private val sideGit = GitEntrySummary.Repository(
    branch = "feature/run-side-panel", detached = false, headShortSha = "54d8183", unborn = false,
    changeCount = 3, truncated = false, ahead = 2, behind = 1,
)

// 宽窗口生成中：运行信息在右侧浮动检视器的“运行”页签，浮岛不再显示运行坞。
// 内屏（约 814dp）让位后会话不足 480dp，检视器浮在会话右缘之上、底边停在输入区上方；平板横屏会话整体让位。
@Composable
private fun WideRunPage(mode: InfinityThemeMode, english: Boolean, pages: List<ChatPage>) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            CompositionLocalProvider(LocalInfinityInspectorLayout provides true) {
                ChatScreenContent(
                    state = ChatScreenState(
                        uiState = ChatUiState(
                            session = Session("wide", "assistant", title = if (english) "Capability probe" else "能力探测", modelId = "deepseek::v4-flash"),
                            agentName = "Infinity Workbench",
                            messages = progressMessages(english),
                            isGenerating = true,
                            status = GenerationStatus.RECEIVING,
                        ),
                        modelDisplayNames = mapOf("deepseek::v4-flash" to "DeepSeek V4 Flash"),
                        tokenState = ChatViewModel.TokenIndicatorState(used = 21800, max = 128000),
                        taskPanel = groupedPlan(english),
                        stateBoard = sideBoard(english),
                        gitSummary = sideGit,
                        inspectorPages = pages,
                    ),
                    actions = ChatScreenActions(),
                )
            }
        }
    }
}

// 截图只渲染首帧，输入区高度要等布局后才量到，所以这里卡片底边还压在输入区上；
// 真机下一帧起底边停在输入区上方 8dp。
@PreviewTest
@Preview(name = "内屏检视器让位 深中814", widthDp = 814, heightDp = 898, locale = "zh-rCN")
@Composable
fun chatInspectorInnerShiftDarkPreview() = WideRunPage(InfinityThemeMode.DARK, english = false, pages = listOf(ChatPage.Run))

@PreviewTest
@Preview(name = "平板检视器让位 浅英1280", widthDp = 1280, heightDp = 800, locale = "en")
@Composable
fun chatInspectorShiftLightPreview() = WideRunPage(InfinityThemeMode.LIGHT, english = true, pages = listOf(ChatPage.Run))

@PreviewTest
@Preview(name = "内屏检视器边缘条 浅中814", widthDp = 814, heightDp = 898, locale = "zh-rCN")
@Composable
fun chatInspectorStripLightPreview() = WideRunPage(InfinityThemeMode.LIGHT, english = false, pages = emptyList())

// 检视器卡片单独：待批准时运行页签顶部给出跳转入口，页签角标显示待批准数与 Git 改动数；大字号下页签与按钮不重叠。
@Composable
private fun InspectorAlone(mode: InfinityThemeMode, english: Boolean) {
    val plan = groupedPlan(english)
    val badges = ChatInspectorBadges(running = true, pendingApprovals = 2, hasRunContent = true, gitAvailable = true, gitChanges = 3, canvases = 2, browserEntry = true)
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
            Row(Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChatInspectorCard(
                    root = ChatPage.Run,
                    badges = badges,
                    widened = false,
                    onSelect = {},
                    onToggleWiden = {},
                    onCollapse = {},
                    modifier = Modifier.width(360.dp).fillMaxHeight(),
                ) {
                    ChatInspectorRunTab(pendingApprovals = 2, onJumpToApproval = {}, hasRunContent = true) {
                        RunDetailContent(
                            initialTab = RunDetailTab.PLAN,
                            plan = plan,
                            isGenerating = true,
                            turnStartedAt = 0L,
                            activity = null,
                            stateBoard = sideBoard(english),
                            onDismiss = {},
                            onContinue = {},
                            onComplete = {},
                            onHidePlan = {},
                            onClearPlan = {},
                            onEditState = { _, _ -> null },
                            onRemoveState = { null },
                            modifier = Modifier.fillMaxSize(),
                            nowMillis = 72_000L,
                        )
                    }
                }
                ChatInspectorStrip(badges = badges, onOpen = {}, onExpand = {})
            }
        }
    }
}

@PreviewTest
@Preview(name = "检视器卡片 深中", widthDp = 440, heightDp = 860, locale = "zh-rCN")
@Composable
fun chatInspectorCardDarkPreview() = InspectorAlone(InfinityThemeMode.DARK, english = false)

@PreviewTest
@Preview(name = "检视器卡片 浅英 大字", widthDp = 440, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun chatInspectorCardLightLargeFontPreview() = InspectorAlone(InfinityThemeMode.LIGHT, english = true)
