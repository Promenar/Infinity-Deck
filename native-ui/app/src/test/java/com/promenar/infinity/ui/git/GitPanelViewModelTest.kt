package com.promenar.infinity.ui.git

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.git.FakeInspectRunner
import com.promenar.infinity.data.git.FakePythonRuntime
import com.promenar.infinity.data.git.GitChangeGroup
import com.promenar.infinity.data.git.GitChangeKind
import com.promenar.infinity.data.git.GitChangesService
import com.promenar.infinity.data.git.GitDiffSides
import com.promenar.infinity.data.git.GitFileChange
import com.promenar.infinity.data.git.GitFileContent
import com.promenar.infinity.data.git.GitInspectError
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitInspector
import com.promenar.infinity.data.git.GitWorkspaceResolution
import com.promenar.infinity.data.git.GitWorkspaceTarget
import com.promenar.infinity.data.git.InspectFixtures
import com.promenar.infinity.data.git.NioGitWorkTreeReader
import com.promenar.infinity.ui.common.diff.FileChangeKind
import com.promenar.infinity.ui.common.diff.FileDiffContent
import com.promenar.infinity.ui.common.diff.FileDiffSummaryReason
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class GitPanelViewModelTest {
    private val work: File = Files.createTempDirectory("git-panel-vm").toFile().canonicalFile
    private val root = File(work, "repo").apply { mkdirs(); File(this, ".git").mkdirs() }
    private val runner = FakeInspectRunner(respond = { operation, args ->
        when (operation) {
            "status" -> InspectFixtures.status
            "log" -> InspectFixtures.log
            "commit" -> InspectFixtures.commit
            "branches" -> InspectFixtures.branches
            "read" -> InspectFixtures.echoRead(args)
            else -> null
        }
    })
    private val service = GitChangesService(
        locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(root, "project")) },
        inspector = GitInspector(FakePythonRuntime(), runner, { File(work, "out") }),
        workTree = NioGitWorkTreeReader(pathFallback = true),
    )

    // 面板读取走真实 IO 调度；断言满足时可能仍有读取在 IO 上返回途中。
    // 撤销 Main 之前先取消并等待这些 ViewModel 的协程结束，否则它们回到 Main 时的异常会记到后续测试头上。
    private val viewModels = mutableListOf<GitPanelViewModel>()
    private fun track(viewModel: GitPanelViewModel) = viewModel.also(viewModels::add)

    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }

    @After fun tearDown() {
        runBlocking { viewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() } }
        Dispatchers.resetMain()
        work.deleteRecursively()
    }

    private fun awaitState(viewModel: GitPanelViewModel, predicate: (GitPanelUiState) -> Boolean): GitPanelUiState =
        runBlocking { withTimeout(10_000) { viewModel.state.first(predicate) } }

    @Test fun `打开面板读取状态，切换到最近提交时才读取提交列表`() {
        val viewModel = track(GitPanelViewModel("s", service))
        viewModel.open()
        val loaded = awaitState(viewModel) { it.status is GitLoad.Ready }
        assertThat((loaded.status as GitLoad.Ready).value.branch).isEqualTo("main")
        assertThat(loaded.log).isNull()
        assertThat(runner.operations()).containsExactly("status")

        viewModel.selectTab(GitPanelTab.COMMITS)
        val commits = awaitState(viewModel) { it.log is GitLoad.Ready }
        assertThat((commits.log as GitLoad.Ready).value.commits).hasSize(2)

        viewModel.refresh()
        awaitState(viewModel) { !it.refreshing && runner.operations().size == 4 }
        // 刷新时状态与提交列表并行读取，顺序不定。
        assertThat(runner.operations().take(2)).containsExactly("status", "log").inOrder()
        assertThat(runner.operations().drop(2)).containsExactly("status", "log")
    }

    @Test fun `重复打开复用状态缓存，手动刷新重新读取`() {
        val cacheScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        try {
            val cache = com.promenar.infinity.data.git.DefaultGitStatusCache(
                locate = { GitWorkspaceResolution.Ready(GitWorkspaceTarget(root, "project")) },
                read = { service.status("s") }, scope = cacheScope, fingerprint = { "same" },
            )
            val viewModel = track(GitPanelViewModel("s", service, statusCache = cache))
            viewModel.open()
            awaitState(viewModel) { it.status is GitLoad.Ready && !it.refreshing }
            viewModel.close()
            viewModel.open()
            awaitState(viewModel) { it.status is GitLoad.Ready && !it.refreshing }
            runBlocking { kotlinx.coroutines.delay(500) }
            assertThat(runner.operations().count { it == "status" }).isEqualTo(1)
            viewModel.refresh()
            awaitState(viewModel) { runner.operations().count { op -> op == "status" } == 2 && !it.refreshing }
            assertThat(runner.operations().count { it == "status" }).isEqualTo(2)
        } finally {
            cacheScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test fun `切到分支页时才读取本地分支，刷新时一并重读`() {
        val viewModel = track(GitPanelViewModel("s", service))
        viewModel.open()
        awaitState(viewModel) { it.status is GitLoad.Ready }
        assertThat(runner.operations()).doesNotContain("branches")

        viewModel.selectTab(GitPanelTab.BRANCHES)
        val loaded = awaitState(viewModel) { it.branches is GitLoad.Ready }
        val branches = (loaded.branches as GitLoad.Ready).value.branches
        assertThat(branches.map { it.name }).containsExactly("main", "topic").inOrder()
        assertThat(branches.first().tracking.ahead).isEqualTo(2)

        viewModel.refresh()
        awaitState(viewModel) { !it.refreshing && runner.operations().count { op -> op == "branches" } == 2 }
        assertThat(runner.operations()).doesNotContain("log")
    }

    @Test fun `未跟踪文件差异在后台构建为新建文件，返回后回到列表`() {
        File(root, "notes.txt").writeText("第一行\n第二行\n")
        val viewModel = track(GitPanelViewModel("s", service))
        viewModel.open()
        awaitState(viewModel) { it.status is GitLoad.Ready }
        viewModel.openChange(GitChangeGroup.UNTRACKED, GitFileChange("notes.txt", GitChangeKind.ADDED))
        val state = awaitState(viewModel) { (it.detail as? GitPanelDetail.Change)?.diff is GitLoad.Ready }
        val model = ((state.detail as GitPanelDetail.Change).diff as GitLoad.Ready).value
        assertThat(model.changeKind).isEqualTo(FileChangeKind.CREATED)
        assertThat(model.headerAdded).isEqualTo(2)
        assertThat(model.fileName).isEqualTo("notes.txt")

        assertThat(viewModel.back()).isTrue()
        assertThat(viewModel.state.value.detail).isNull()
        assertThat(viewModel.back()).isFalse()
    }

    @Test fun `提交详情与提交内文件差异分两级返回`() {
        val viewModel = track(GitPanelViewModel("s", service))
        viewModel.open()
        viewModel.selectTab(GitPanelTab.COMMITS)
        val log = awaitState(viewModel) { it.log is GitLoad.Ready }.log as GitLoad.Ready
        viewModel.openCommit(log.value.commits.first())
        val detail = awaitState(viewModel) { (it.detail as? GitPanelDetail.Commit)?.detail is GitLoad.Ready }
        val files = ((detail.detail as GitPanelDetail.Commit).detail as GitLoad.Ready).value.files.entries
        viewModel.openCommitFile(files[1])
        val fileState = awaitState(viewModel) { (it.detail as? GitPanelDetail.Commit)?.file?.diff is GitLoad.Ready }
        val commit = fileState.detail as GitPanelDetail.Commit
        assertThat(commit.detail).isInstanceOf(GitLoad.Ready::class.java)
        assertThat(commit.file!!.change.oldPath).isEqualTo("src/Old.kt")
        // 其他异步查询可能在文件读取后完成；验证参数时不能依赖全局命令顺序。
        val statusIndex = runner.operations().indexOf("status")
        assertThat(statusIndex).isAtLeast(0)
        runBlocking { runner.run(runner.commands[statusIndex]) }
        val readIndex = runner.operations().indexOfLast { it == "read" }
        assertThat(readIndex).isAtLeast(0)
        assertThat(runner.values(readIndex, "--blob")).containsExactly(
            "${InspectFixtures.SHA_B}^:src/Old.kt",
            "${InspectFixtures.SHA_B}:src/New.kt",
        ).inOrder()

        assertThat(viewModel.back()).isTrue()
        val back = viewModel.state.value.detail as GitPanelDetail.Commit
        assertThat(back.file).isNull()
        assertThat(back.detail).isInstanceOf(GitLoad.Ready::class.java)
        assertThat(viewModel.back()).isTrue()
        assertThat(viewModel.state.value.detail).isNull()
    }

    @Test fun `刷新详情页时提交详情与文件差异都重新加载`() {
        val viewModel = track(GitPanelViewModel("s", service))
        viewModel.open()
        viewModel.selectTab(GitPanelTab.COMMITS)
        val log = awaitState(viewModel) { it.log is GitLoad.Ready }.log as GitLoad.Ready
        viewModel.openCommit(log.value.commits.first())
        awaitState(viewModel) { (it.detail as? GitPanelDetail.Commit)?.detail is GitLoad.Ready }
        viewModel.openCommitFile(GitFileChange("src/App.kt", GitChangeKind.MODIFIED))
        awaitState(viewModel) { (it.detail as? GitPanelDetail.Commit)?.file?.diff is GitLoad.Ready }
        viewModel.refresh()
        val refreshed = awaitState(viewModel) {
            val commit = it.detail as? GitPanelDetail.Commit
            commit?.detail is GitLoad.Ready && commit.file?.diff is GitLoad.Ready && !it.refreshing
        }
        assertThat((refreshed.detail as GitPanelDetail.Commit).file!!.change.path).isEqualTo("src/App.kt")
    }

    @Test fun `读取失败显示对应问题类别`() {
        val failing = GitChangesService(
            locate = { GitWorkspaceResolution.Unavailable("目录不可用") },
            inspector = GitInspector(FakePythonRuntime(), runner, { File(work, "out") }),
        )
        val viewModel = track(GitPanelViewModel("s", failing))
        viewModel.open()
        val state = awaitState(viewModel) { it.status is GitLoad.Failed }
        assertThat((state.status as GitLoad.Failed).problem).isEqualTo(GitPanelProblem(GitProblemKind.WORKSPACE_UNAVAILABLE, "目录不可用"))
    }
}

class GitPanelModelsTest {
    @Test fun `入口摘要：不是仓库与工作区不可用时隐藏`() {
        assertThat(GitEntrySummary.from(GitInspectResult.Failure(GitInspectError.NotRepository))).isEqualTo(GitEntrySummary.Hidden)
        assertThat(GitEntrySummary.from(GitInspectResult.Failure(GitInspectError.WorkspaceUnavailable("x")))).isEqualTo(GitEntrySummary.Hidden)
        assertThat(GitEntrySummary.from(GitInspectResult.Failure(GitInspectError.PythonUnavailable))).isEqualTo(GitEntrySummary.PythonUnavailable)
        assertThat(GitEntrySummary.from(GitInspectResult.Failure(GitInspectError.TimedOut))).isEqualTo(GitEntrySummary.Failed)
    }

    @Test fun `错误映射到问题类别`() {
        assertThat(GitInspectError.TimedOut.toProblem().kind).isEqualTo(GitProblemKind.TIMED_OUT)
        assertThat(GitInspectError.UnsafePath("x").toProblem().kind).isEqualTo(GitProblemKind.UNSAFE_PATH)
        assertThat(GitInspectError.ParseFailed("x").toProblem().kind).isEqualTo(GitProblemKind.FAILED)
        assertThat(GitInspectError.InspectFailed("NOT_FOUND", "x").toProblem()).isEqualTo(GitPanelProblem(GitProblemKind.FAILED, "x"))
    }

    @Test fun `失败原因取末行并截断，路径不安全归入对应类别`() {
        val traceback = "Traceback (most recent call last):\n  File \"midx.py\"\nValueError: Large offset found but no LOFF chunk\n"
        assertThat(GitInspectError.CommandFailed(1, traceback).toProblem())
            .isEqualTo(GitPanelProblem(GitProblemKind.FAILED, "ValueError: Large offset found but no LOFF chunk"))
        assertThat(GitInspectError.InspectFailed("UNSAFE_GIT_PATH", "指针不在可信映射内").toProblem())
            .isEqualTo(GitPanelProblem(GitProblemKind.UNSAFE_PATH, "指针不在可信映射内"))
        val long = GitInspectError.ParseFailed("x".repeat(500)).toProblem().detail!!
        assertThat(long.length).isEqualTo(240)
        assertThat(long).endsWith("…")
        assertThat(GitInspectError.CommandFailed(null, " \n ").toProblem().detail).isNull()
    }

    @Test fun `差异模型：二进制、超限、删除与修改`() {
        val text = GitFileContent(exists = true, size = 2, text = "a\n")
        val binary = GitDiffModels.build(GitDiffSides("img.png", "img.png", text, GitFileContent(exists = true, binary = true, size = 3)))
        assertThat(binary.content).isEqualTo(FileDiffContent.Binary)
        val large = GitDiffModels.build(GitDiffSides("big.log", "big.log", GitFileContent(exists = true, truncated = true, size = 900_000), text))
        assertThat(large.content).isEqualTo(FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
        val deleted = GitDiffModels.build(GitDiffSides("a.txt", "a.txt", text, GitFileContent.Absent))
        assertThat(deleted.changeKind).isEqualTo(FileChangeKind.DELETED)
        val modified = GitDiffModels.build(GitDiffSides("a.txt", "a.txt", text, GitFileContent(exists = true, size = 4, text = "a\nb\n")))
        assertThat(modified.changeKind).isEqualTo(FileChangeKind.MODIFIED)
        assertThat(modified.headerAdded).isEqualTo(1)
    }

    @Test fun `提交时间按提交时区格式化`() {
        val summary = com.promenar.infinity.data.git.GitCommitSummary(
            sha = InspectFixtures.SHA_A, shortSha = "1111111", authorName = "t", authorEmail = "t@e",
            authorTimeSeconds = 1_700_000_000L, authorTimezoneSeconds = 8 * 3600, title = "x", parents = emptyList(),
        )
        assertThat(formatCommitTime(summary)).isEqualTo("2023-11-15 06:13")
        assertThat(formatCommitTime(summary.copy(authorTimezoneSeconds = 0))).isEqualTo("2023-11-14 22:13")
    }
}
