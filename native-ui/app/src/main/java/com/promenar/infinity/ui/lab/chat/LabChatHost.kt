package com.promenar.infinity.ui.lab.chat

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.promenar.infinity.data.subagent.SubagentBatch
import com.promenar.infinity.domain.branch.BranchSummary
import com.promenar.infinity.domain.branch.PendingBranchPlan
import com.promenar.infinity.domain.branch.WorktreeEntry
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.ui.chat.ChatBrowserAccess
import com.promenar.infinity.ui.chat.ChatPopoverAnchors
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatScreenContent
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.LocalBranchAccess
import com.promenar.infinity.ui.chat.LocalChatBrowser
import com.promenar.infinity.ui.chat.LocalChatPopoverAnchors
import com.promenar.infinity.ui.chat.LocalMemoryChangeHost
import com.promenar.infinity.ui.chat.LocalSubagentControls
import com.promenar.infinity.ui.chat.LocalSubagentRuns
import com.promenar.infinity.ui.chat.LocalToolLiveOutput
import com.promenar.infinity.ui.chat.components.LocalAgentActivity
import com.promenar.infinity.ui.common.InfinityAdaptivePaneHost
import com.promenar.infinity.ui.common.LocalLabSandbox
import com.promenar.infinity.ui.lab.fakes.LabFakes

/**
 * 会话页某一时刻的完整脚本快照：页面状态加上经注入依赖读取的数据。
 * 每帧都是完整快照，宿主按快照整体覆盖假依赖，因此跳转、倒退与重播都得到同一画面。
 */
internal data class LabChatFrame(
    val screen: ChatScreenState,
    /** 键为 `agent_task` 调用的执行步骤 id。 */
    val subagentBatches: Map<String, SubagentBatch> = emptyMap(),
    val jobs: List<BackgroundJob> = emptyList(),
    val pendingPlan: PendingBranchPlan? = null,
    val branches: List<BranchSummary> = emptyList(),
    val worktrees: List<WorktreeEntry> = emptyList(),
    /** 实时输出面板：键为调用的输出 key，值为已出现的行。 */
    val liveOutput: Map<String, List<String>> = emptyMap(),
)

/**
 * 脚本快照里的时间都相对脚本零点；交给真实组件前统一平移到墙钟，
 * 组件按墙钟显示的运行时长、等待倒计时与“几分钟前”因此和脚本进度一致。
 */
internal fun LabChatFrame.atWallClock(anchorMs: Long): LabChatFrame {
    fun Long.wall() = this + anchorMs
    val ui = screen.uiState
    return copy(
        screen = screen.copy(
            uiState = ui.copy(messages = ui.messages.map { message ->
                message.copy(
                    createdAt = message.createdAt.wall(),
                    executionSteps = message.executionSteps?.map { it.copy(timestamp = it.timestamp.wall()) },
                )
            }),
            generationStartedAt = screen.generationStartedAt?.wall(),
        ),
        subagentBatches = subagentBatches.mapValues { (_, batch) ->
            batch.copy(
                runs = batch.runs.map { run ->
                    run.copy(
                        startedAt = run.startedAt.wall(),
                        endedAt = run.endedAt?.wall(),
                        events = run.events.map { it.copy(at = it.at.wall()) },
                    )
                },
                wait = batch.wait?.let { it.copy(startedAt = it.startedAt.wall(), deadline = it.deadline.wall()) },
            )
        },
        jobs = jobs.map { it.copy(startedAtMillis = it.startedAtMillis.wall(), endedAtMillis = it.endedAtMillis?.wall()) },
        branches = branches.map { it.copy(updatedAt = it.updatedAt.wall()) },
    )
}

/**
 * 用脚本快照渲染真实的 [ChatScreenContent]：8 个注入依赖全部换成实验室的内存实现，
 * 并打开 [LocalLabSandbox]，组件内部直接读取真实数据的位置一律返回空。
 * [frame] 的时间已平移到墙钟（见 [atWallClock]）。
 */
@Composable
internal fun LabChatHost(
    frame: LabChatFrame,
    actions: ChatScreenActions,
    inspectorPageContent: @Composable BoxScope.() -> Unit = {},
    paneVisible: Boolean = false,
    onPaneBack: () -> Unit = {},
) {
    val sessionId = frame.screen.uiState.session?.id ?: LAB_SESSION_ID
    val fakes = remember(sessionId) { LabFakes(sessionId = sessionId) }
    val anchors = remember { ChatPopoverAnchors() }
    val shownOutputKeys = remember(fakes) { mutableSetOf<String>() }
    SideEffect {
        val batches = fakes.subagentRuns.batches.value
        (batches.keys - frame.subagentBatches.keys).forEach { fakes.subagentRuns.setBatch(it, null) }
        frame.subagentBatches.forEach { (stepId, batch) -> if (batches[stepId] != batch) fakes.subagentRuns.setBatch(stepId, batch) }
        fakes.jobs.setJobs(frame.jobs)
        fakes.branches.setPendingPlan(sessionId, frame.pendingPlan)
        fakes.branches.setBranches(sessionId, frame.branches)
        fakes.branches.setWorktrees(sessionId, frame.worktrees)
        (shownOutputKeys - frame.liveOutput.keys).forEach(fakes.toolOutput::clear)
        frame.liveOutput.forEach { (key, lines) ->
            val current = fakes.liveOutput.snapshot(key)?.text.orEmpty()
            val next = lines.joinToString("") { "$it\n" }
            if (current != next) fakes.toolOutput.setLines(key, lines)
        }
        shownOutputKeys.clear()
        shownOutputKeys += frame.liveOutput.keys
    }
    val branchAccess by fakes.branchAccess.access.collectAsState()
    val activity by fakes.agentActivity.access.collectAsState()
    CompositionLocalProvider(
        LocalLabSandbox provides true,
        LocalChatPopoverAnchors provides anchors,
        LocalToolLiveOutput provides fakes.liveOutput,
        LocalMemoryChangeHost provides fakes.memory,
        LocalSubagentRuns provides fakes.subagentRuns.access,
        LocalSubagentControls provides fakes.subagents,
        LocalBranchAccess provides branchAccess,
        LocalAgentActivity provides activity,
        LocalChatBrowser provides ChatBrowserAccess(state = null, open = {}),
    ) {
        // 与真实宿主 ChatRoute 相同：同一份二级页面在窄窗口由整页宿主覆盖会话，窗口够宽够高时交给会话页的浮动检视器。
        InfinityAdaptivePaneHost(paneVisible = paneVisible, onBack = onPaneBack, pane = inspectorPageContent) {
            ChatScreenContent(state = frame.screen, actions = actions, inspectorPageContent = inspectorPageContent)
        }
    }
}

internal const val LAB_SESSION_ID = "lab-session"
