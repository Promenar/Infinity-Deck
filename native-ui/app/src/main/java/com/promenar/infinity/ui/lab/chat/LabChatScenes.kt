package com.promenar.infinity.ui.lab.chat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.promenar.infinity.data.git.GitCommitLog
import com.promenar.infinity.data.git.GitCommitSummary
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.git.GitTracking
import com.promenar.infinity.ui.chat.ChatPage
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.common.infinityInspectorAvailable
import com.promenar.infinity.ui.git.GitChangesPanel
import com.promenar.infinity.ui.git.GitLoad
import com.promenar.infinity.ui.git.GitPanelActions
import com.promenar.infinity.ui.git.GitPanelTab
import com.promenar.infinity.ui.git.GitPanelUiState
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabStateScene
import com.promenar.infinity.ui.lab.stage.LabWorkflowScene
import com.promenar.infinity.ui.theme.InfinitySpacing
import java.time.Instant
import java.time.ZoneId

/**
 * 对话目录（会话页与检查器）下的版本场景。工作流按时间轴回放；状态版本是同一脚本在某一时刻的定格，
 * 定格时刻决定运行计时与等待倒计时的读数。
 */
internal val labChatScenes: Map<String, () -> LabScene> = mapOf(
    "chat.workflow.ade-json" to {
        LabWorkflowScene(
            timeline = { resources -> adeTimeline(LabAdeText(isChinese(resources))) },
            content = { state: AdeState -> LabChatScene(adeFrame(state), hub = LabHubStory.ADE) },
        )
    },
    "chat.workflow.ops-502" to {
        LabWorkflowScene(
            timeline = { resources -> opsTimeline(LabOpsText(isChinese(resources))) },
            content = { state: OpsState -> LabChatScene(opsFrame(state), hub = LabHubStory.OPS) },
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
    // 根因已查明，修复命令等待批准（README「定位根因」）。
    "chat.state.ops-approval" to {
        LabStateScene { OpsStill(OPS_APPROVE3, OpsTimes.APPROVE3 + 3_000) }
    },
    // 修复验证后的总结，外屏用来拍「合上继续看报告」。
    "chat.state.ops-report" to {
        LabStateScene { OpsStill(OPS_DONE, OpsTimes.DONE + 60_000) }
    },
    "inspector.state.git-worktrees" to {
        LabStateScene { AdeStill(ADE_DONE, AdeTimes.DONE + 60_000, initialPages = listOf(ChatPage.Git)) }
    },
    "inspector.state.git-commits" to {
        LabStateScene { AdeStill(ADE_DONE, AdeTimes.DONE + 60_000, initialPages = listOf(ChatPage.Git), gitTab = GitPanelTab.COMMITS) }
    },
)

@Composable
private fun rememberAdeText(): LabAdeText {
    val zh = isChinese(LocalContext.current.resources)
    return remember(zh) { LabAdeText(zh) }
}

/** 502 排障脚本在 [stage] 的定格画面，内屏带首页列表。 */
@Composable
private fun LabSceneScope.OpsStill(stage: Int, nowScriptMs: Long) {
    val zh = isChinese(LocalContext.current.resources)
    val frame = remember(zh, stage) { opsFrame(OpsState(LabOpsText(zh), stage = stage)) }
    LabChatScene(frame, nowScriptMs = nowScriptMs, hub = LabHubStory.OPS)
}

/**
 * ADE 脚本在 [stage] 的定格画面。[initialPages] 非空时打开二级页：宽窗口在检视器里，页签可以切换；
 * 窄窗口（外屏）与真实应用一样换成整页，返回回到会话。Git 页显示提交、分支与 worktree，其余页由会话页自己的内容承载。
 */
@Composable
private fun LabSceneScope.AdeStill(
    stage: Int,
    nowScriptMs: Long,
    explorerSteps: List<Int> = listOf(5, 7),
    initialPages: List<ChatPage> = emptyList(),
    gitTab: GitPanelTab = GitPanelTab.BRANCHES,
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (!infinityInspectorAvailable(maxWidth, maxHeight) && pages.lastOrNull() == ChatPage.Git) {
            BackHandler { pages = pages.dropLast(1) }
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                AdeGitPanel(
                    text = text,
                    initialTab = gitTab,
                    onExit = { pages = pages.dropLast(1) },
                    modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
                )
            }
            return@BoxWithConstraints
        }
        LabChatScene(
            frame = frame.copy(screen = frame.screen.copy(inspectorPages = pages)),
            nowScriptMs = nowScriptMs,
            actions = actions,
            hub = LabHubStory.ADE,
            // 检视器要会话列够宽，内屏以展开态（图标条｜会话）开始。
            expanded = initialPages.isNotEmpty(),
        ) {
            if (pages.lastOrNull() == ChatPage.Git) {
                AdeGitPanel(text, initialTab = gitTab, onExit = null, modifier = Modifier.padding(top = InfinitySpacing.XSmall))
            }
        }
    }
}

/** 提交之后的仓库：main 干净、尚未设置上游；提交页是 starlog 的六次提交，分支页列出两个分支与各自在用的会话。 */
@Composable
private fun AdeGitPanel(text: LabAdeText, initialTab: GitPanelTab, onExit: (() -> Unit)?, modifier: Modifier) {
    var tab by remember { mutableStateOf(initialTab) }
    val status = GitStatus(
        branch = "main",
        detached = false,
        head = "4831233e5a0c9d1f7b2e6a8c3d4f5e6a7b8c9d0e",
        unborn = false,
        staged = GitChangeList.Empty,
        unstaged = GitChangeList.Empty,
        untracked = GitChangeList.Empty,
        tracking = GitTracking(),
        repositoryName = "starlog",
    )
    val branches = GitBranchList(
        listOf(
            GitBranch("main", current = true, shortSha = "4831233", title = "Add --json output to the starlog CLI"),
            GitBranch("config-toml", shortSha = "c627b5b", title = "Bump version to 0.3.1"),
        ),
    )
    val log = remember { adeCommitLog(System.currentTimeMillis()) }
    GitChangesPanel(
        state = GitPanelUiState(
            tab = tab,
            status = GitLoad.Ready(status),
            branches = GitLoad.Ready(branches),
            log = GitLoad.Ready(log),
            worktrees = adeWorktrees(text),
            currentWorktreePath = "/root/starlog",
        ),
        // 三个页签都可切换；提交详情不在脚本里，点提交不打开。
        actions = GitPanelActions(onSelectTab = { tab = it }, onExit = onExit),
        // 与 GitChangesPage 的边距一致。
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal),
    )
}

/** starlog 的提交历史：最新一次是刚才的 --json 提交，此前五次按天回溯，作者是演示账号。 */
internal fun adeCommitLog(nowMillis: Long): GitCommitLog {
    val zone = ZoneId.systemDefault()
    val today = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val offset = today.offset.totalSeconds
    fun daysAgo(days: Long, hour: Int, minute: Int) =
        today.toLocalDate().minusDays(days).atTime(hour, minute).atZone(zone).toEpochSecond()
    val head = nowMillis / 1000 - 60
    val commits = listOf(
        Triple("4831233e5a0c9d1f7b2e6a8c3d4f5e6a7b8c9d0e", "Add --json output to the starlog CLI", head),
        Triple("c627b5b19e2d4c7a8f0b3e5d6a9c1b2e4f7a8d90", "Bump version to 0.3.1", daysAgo(1, 23, 30)),
        Triple("0f477dc2a8b1e9d3c5f6a7b8e0d1c2f3a4b5c6d7", "Add full-season regression test", daysAgo(2, 21, 48)),
        Triple("0a67cd4e1f2a3b5c6d7e8f9a0b1c2d3e4f5a6b7c", "Summarize by target, brightest first", daysAgo(3, 20, 15)),
        Triple("79f7cd0b3c4d5e6f7a8b9c0d1e2f3a4b5c6d7e8f", "Add parser tests", daysAgo(4, 22, 3)),
        Triple("cf6c76a5d4e3f2a1b0c9d8e7f6a5b4c3d2e1f0a9", "Initial starlog CLI", daysAgo(5, 21, 12)),
    )
    return GitCommitLog(
        commits.mapIndexed { index, (sha, title, at) ->
            GitCommitSummary(
                sha = sha,
                shortSha = sha.take(7),
                authorName = "Starlog Demo",
                authorEmail = "demo@starlog.example",
                authorTimeSeconds = at,
                authorTimezoneSeconds = offset,
                title = title,
                parents = listOfNotNull(commits.getOrNull(index + 1)?.first),
            )
        },
    )
}
