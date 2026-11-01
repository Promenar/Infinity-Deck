package com.promenar.infinity.ui.lab.chat

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.promenar.infinity.data.git.GitBranch
import com.promenar.infinity.data.git.GitBranchList
import com.promenar.infinity.data.git.GitChangeList
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.git.GitTracking
import com.promenar.infinity.ui.chat.ChatPage
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.git.GitChangesPanel
import com.promenar.infinity.ui.git.GitLoad
import com.promenar.infinity.ui.git.GitPanelActions
import com.promenar.infinity.ui.git.GitPanelTab
import com.promenar.infinity.ui.git.GitPanelUiState
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.lab.stage.LabWorkflowScene

/**
 * 对话目录（会话页与检查器）下的版本场景。工作流按时间轴回放；状态版本是同一脚本在某一时刻的定格，
 * 定格时刻决定运行计时与等待倒计时的读数。
 */
internal val labChatScenes: Map<String, () -> LabScene> = mapOf(
    "chat.workflow.ade-json" to {
        LabWorkflowScene(
            timeline = { resources -> adeTimeline(LabAdeText(isChinese(resources))) },
            content = { state: AdeState -> LabChatScene(adeFrame(state)) },
        )
    },
    "chat.workflow.ops-502" to {
        LabWorkflowScene(
            timeline = { resources -> opsTimeline(LabOpsText(isChinese(resources))) },
            content = { state: OpsState -> LabChatScene(opsFrame(state)) },
        )
    },
    // 两个探索者跑到 1 分 31 秒，主会话还能等 3 分 29 秒。
    "chat.state.subagents" to {
        LabStateScene { AdeStill(ADE_TASK, AdeTimes.TASK + 91_000, explorerSteps = listOf(4, 6)) }
    },
    "chat.state.background-job" to {
        LabStateScene { AdeStill(ADE_PLAN_UPDATE, AdeTimes.PLAN_UPDATE + 2_000) }
    },
    "chat.state.branch-plan" to {
        LabStateScene { AdeStill(ADE_BRANCH, AdeTimes.BRANCH + 1_000) }
    },
    "inspector.state.plan" to {
        LabStateScene { AdeStill(ADE_PLAN_UPDATE, AdeTimes.PLAN_UPDATE + 2_000, initialPages = listOf(ChatPage.Run)) }
    },
    "inspector.state.git-worktrees" to {
        LabStateScene { AdeStill(ADE_DONE, AdeTimes.DONE + 60_000, initialPages = listOf(ChatPage.Git)) }
    },
)

@Composable
private fun rememberAdeText(): LabAdeText {
    val zh = isChinese(LocalContext.current.resources)
    return remember(zh) { LabAdeText(zh) }
}

/**
 * ADE 脚本在 [stage] 的定格画面。[initialPages] 非空时宽窗口打开检视器：页签可以切换，
 * Git 页显示分支与 worktree，其余页由会话页自己的内容承载。
 */
@Composable
private fun LabSceneScope.AdeStill(
    stage: Int,
    nowScriptMs: Long,
    explorerSteps: List<Int> = listOf(5, 7),
    initialPages: List<ChatPage> = emptyList(),
) {
    val text = rememberAdeText()
    var pages by remember { mutableStateOf(initialPages) }
    val frame = remember(text, stage, explorerSteps) {
        adeFrame(AdeState(text, stage = stage, explorerSteps = explorerSteps, quickLines = 0))
    }
    val actions = remember {
        ChatScreenActions(
            onSetInspectorPages = { pages = it },
            onInspectorBack = { pages = pages.dropLast(1) },
            onOpenGit = { pages = listOf(ChatPage.Git) },
        )
    }
    LabChatScene(
        frame = frame.copy(screen = frame.screen.copy(inspectorPages = pages)),
        nowScriptMs = nowScriptMs,
        actions = actions,
    ) {
        if (pages.lastOrNull() == ChatPage.Git) AdeGitPanel(text)
    }
}

/** 提交之后的仓库：main 干净、尚未设置上游，分支页列出两个分支与各自在用的会话。 */
@Composable
private fun AdeGitPanel(text: LabAdeText) {
    var tab by remember { mutableStateOf(GitPanelTab.BRANCHES) }
    val status = GitStatus(
        branch = "main",
        detached = false,
        head = "4831233e5a0c9d1f7b2e6a8c3d4f5e6a7b8c9d0e",
        unborn = false,
        staged = GitChangeList.Empty,
        unstaged = GitChangeList.Empty,
        untracked = GitChangeList.Empty,
        tracking = GitTracking(upstreamMissing = true),
        repositoryName = "starlog",
    )
    val branches = GitBranchList(
        listOf(
            GitBranch("main", current = true, shortSha = "4831233", title = "Add --json output to the starlog CLI"),
            GitBranch("config-toml", shortSha = "c627b5b", title = "Bump version to 0.3.1"),
        ),
    )
    GitChangesPanel(
        state = GitPanelUiState(
            tab = tab,
            status = GitLoad.Ready(status),
            branches = GitLoad.Ready(branches),
            worktrees = adeWorktrees(text),
            currentWorktreePath = "/root/starlog",
        ),
        // 提交列表不在脚本里，分支与变更两页可以切换。
        actions = GitPanelActions(onSelectTab = { if (it != GitPanelTab.COMMITS) tab = it }),
        modifier = Modifier.fillMaxSize(),
    )
}
