package com.promenar.infinity.ui.git

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.git.GitBranch
import com.promenar.infinity.data.git.GitBranchList
import com.promenar.infinity.data.git.GitChangeKind
import com.promenar.infinity.data.git.GitChangeList
import com.promenar.infinity.data.git.GitFileChange
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.git.GitTracking
import com.promenar.infinity.ui.chat.ChatTopBar
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTheme
import org.junit.Rule
import org.junit.Test

class GitChangesPanelTest {
    @get:Rule val rule = createComposeRule()

    private fun status(changes: Int, tracking: GitTracking) = GitStatus(
        branch = "main", detached = false, head = "2222222222", unborn = false,
        staged = GitChangeList(emptyList()),
        unstaged = GitChangeList(List(changes) { GitFileChange("src/F$it.kt", GitChangeKind.MODIFIED, addedLines = 3, removedLines = 1) }),
        untracked = GitChangeList(emptyList()),
        tracking = tracking,
    )

    @Test fun changes_readOnlyHintWithoutWriteActions() {
        val state = GitPanelUiState(status = GitLoad.Ready(status(2, GitTracking("origin/main", 2, 1, false, false))))
        rule.setContent { InfinityTheme { GitChangesPanel(state, GitPanelActions(), Modifier.fillMaxSize()) } }
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        rule.onNodeWithTag(GitPanelTags.TRACKING).assertIsDisplayed()
        // 面板只显示状态，提交、推送等写操作由用户直接告诉 Agent。
        rule.onNodeWithText(context.getString(com.promenar.infinity.R.string.git_panel_readonly_hint), substring = true).assertIsDisplayed()
    }

    @Test fun branchesTab_listsLocalBranches() {
        val state = GitPanelUiState(
            tab = GitPanelTab.BRANCHES,
            status = GitLoad.Ready(status(0, GitTracking())),
            branches = GitLoad.Ready(GitBranchList(listOf(GitBranch("main", current = true, shortSha = "2222222", title = "提交"), GitBranch("topic")))),
        )
        rule.setContent { InfinityTheme { GitChangesPanel(state, GitPanelActions(), Modifier.fillMaxSize()) } }
        rule.onNodeWithTag(GitPanelTags.BRANCHES_LIST).assertIsDisplayed()
        rule.onNodeWithTag(GitPanelTags.branch("main")).assertIsDisplayed()
        rule.onNodeWithTag(GitPanelTags.branch("topic")).assertIsDisplayed()
    }

    @Test fun topBarGitLine_opensGitPanel() {
        var opened = false
        rule.setContent {
            InfinityTheme {
                ChatTopBar(
                    title = "会话", onBack = {}, onSettings = {},
                    onClearHistory = {}, onRename = {}, onDeleteSession = {},
                    git = GitEntrySummary.Repository("main", false, "2222222", false, 3, false, ahead = 2),
                    onOpenGit = { opened = true },
                )
            }
        }
        rule.onNodeWithText("main", substring = true).assertIsDisplayed()
        rule.onNodeWithTag(UiTags.CHAT_GIT_LINE).performClick()
        assertThat(opened).isTrue()
    }
}
