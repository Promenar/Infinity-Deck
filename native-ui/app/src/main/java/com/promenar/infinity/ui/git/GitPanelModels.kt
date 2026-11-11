package com.promenar.infinity.ui.git

import androidx.compose.runtime.Immutable
import com.promenar.infinity.data.git.GitBranchList
import com.promenar.infinity.data.git.GitChangeGroup
import com.promenar.infinity.data.git.GitCommitDetail
import com.promenar.infinity.data.git.GitCommitLog
import com.promenar.infinity.data.git.GitCommitSummary
import com.promenar.infinity.data.git.GitDiffSides
import com.promenar.infinity.data.git.GitFileChange
import com.promenar.infinity.data.git.GitInspectError
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.data.git.GitTracking
import com.promenar.infinity.ui.common.diff.FileChangeKind
import com.promenar.infinity.ui.common.diff.FileDiffContent
import com.promenar.infinity.ui.common.diff.FileDiffModel
import com.promenar.infinity.ui.common.diff.FileDiffSummaryReason

/** 面板显示的问题类别；界面按类别显示本地化文案，不直接显示底层错误信息。 */
enum class GitProblemKind { PYTHON_UNAVAILABLE, NOT_REPOSITORY, WORKSPACE_UNAVAILABLE, TIMED_OUT, UNSAFE_PATH, FAILED }

/** [detail] 是底层给出的原因（截为一行），显示在本地化文案下方，便于排查。 */
@Immutable
data class GitPanelProblem(val kind: GitProblemKind, val detail: String? = null)

fun GitInspectError.toProblem(): GitPanelProblem = when (this) {
    GitInspectError.PythonUnavailable -> GitPanelProblem(GitProblemKind.PYTHON_UNAVAILABLE)
    GitInspectError.NotRepository -> GitPanelProblem(GitProblemKind.NOT_REPOSITORY)
    is GitInspectError.WorkspaceUnavailable -> GitPanelProblem(GitProblemKind.WORKSPACE_UNAVAILABLE, reason)
    GitInspectError.TimedOut -> GitPanelProblem(GitProblemKind.TIMED_OUT)
    is GitInspectError.UnsafePath -> GitPanelProblem(GitProblemKind.UNSAFE_PATH, problemDetail(path))
    is GitInspectError.InspectFailed -> GitPanelProblem(
        if (code == UNSAFE_GIT_PATH_CODE) GitProblemKind.UNSAFE_PATH else GitProblemKind.FAILED,
        problemDetail(message),
    )
    is GitInspectError.ParseFailed -> GitPanelProblem(GitProblemKind.FAILED, problemDetail(detail))
    is GitInspectError.CommandFailed -> GitPanelProblem(GitProblemKind.FAILED, problemDetail(detail))
    is GitInspectError.WorkTreeReadFailed -> GitPanelProblem(GitProblemKind.FAILED, problemDetail(detail))
}

private const val UNSAFE_GIT_PATH_CODE = "UNSAFE_GIT_PATH"
private const val PROBLEM_DETAIL_LIMIT = 240

/** 取最后一个非空行（Python 异常与 stderr 的结论通常在末行），截断到界面可读的长度。 */
internal fun problemDetail(raw: String): String? =
    raw.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }?.let {
        if (it.length > PROBLEM_DETAIL_LIMIT) it.take(PROBLEM_DETAIL_LIMIT - 1) + "…" else it
    }

@Immutable
sealed interface GitLoad<out T> {
    data object Loading : GitLoad<Nothing>

    data class Ready<T>(val value: T) : GitLoad<T>

    data class Failed(val problem: GitPanelProblem) : GitLoad<Nothing>
}

fun <T> GitInspectResult<T>.toLoad(): GitLoad<T> = when (this) {
    is GitInspectResult.Success -> GitLoad.Ready(value)
    is GitInspectResult.Failure -> GitLoad.Failed(error.toProblem())
}

/** 资源管理器中 Git 入口的显示状态；不是仓库或工作区不可用时不显示入口。 */
@Immutable
sealed interface GitEntrySummary {
    data object Hidden : GitEntrySummary

    /** 工作区含 `.git`，状态还在读取；入口先占位，读到后原地替换，不从无到有跳出。 */
    data object Loading : GitEntrySummary

    data class Repository(
        val branch: String?,
        val detached: Boolean,
        val headShortSha: String?,
        val unborn: Boolean,
        val changeCount: Int,
        val truncated: Boolean,
        /** 领先上游的提交数；无上游或无法确定时为空。 */
        val ahead: Int? = null,
        val behind: Int? = null,
    ) : GitEntrySummary

    /** 工作区含 `.git`，但当前 ABI 没有打包 Python。 */
    data object PythonUnavailable : GitEntrySummary

    /** 工作区含 `.git`，但读取状态失败（超时、输出无效等）。 */
    data object Failed : GitEntrySummary

    companion object {
        fun from(result: GitInspectResult<GitStatus>): GitEntrySummary = when (result) {
            is GitInspectResult.Success -> result.value.let { status ->
                Repository(
                    branch = status.branch,
                    detached = status.detached,
                    headShortSha = status.head?.take(7),
                    unborn = status.unborn,
                    changeCount = status.changeCount,
                    truncated = status.truncated,
                    ahead = status.tracking.ahead,
                    behind = status.tracking.behind,
                )
            }
            is GitInspectResult.Failure -> when (result.error) {
                GitInspectError.NotRepository, is GitInspectError.WorkspaceUnavailable -> Hidden
                GitInspectError.PythonUnavailable -> PythonUnavailable
                else -> Failed
            }
        }
    }
}

enum class GitPanelTab { CHANGES, COMMITS, BRANCHES }

@Immutable
data class GitPanelFileDiff(val change: GitFileChange, val diff: GitLoad<FileDiffModel>)

@Immutable
sealed interface GitPanelDetail {
    /** 工作区变更中的单个文件差异。 */
    data class Change(val group: GitChangeGroup, val change: GitFileChange, val diff: GitLoad<FileDiffModel>) : GitPanelDetail

    /** 提交详情；[file] 非空时显示该提交中单个文件相对父提交的差异。 */
    data class Commit(
        val summary: GitCommitSummary,
        val detail: GitLoad<GitCommitDetail>,
        val file: GitPanelFileDiff? = null,
    ) : GitPanelDetail
}

@Immutable
data class GitPanelUiState(
    val tab: GitPanelTab = GitPanelTab.CHANGES,
    val status: GitLoad<GitStatus> = GitLoad.Loading,
    /** 为空表示尚未请求提交列表（切到“最近提交”时才加载）。 */
    val log: GitLoad<GitCommitLog>? = null,
    /** 为空表示尚未请求本地分支（切到“分支”时才加载）。 */
    val branches: GitLoad<GitBranchList>? = null,
    /** 仓库的全部 worktree，随分支页加载；读取失败或只有主目录时不显示。 */
    val worktrees: List<com.promenar.infinity.domain.branch.WorktreeEntry> = emptyList(),
    /** 当前会话所在的 worktree：在用它的会话是本会话，否则为主目录。 */
    val currentWorktreePath: String? = null,
    val detail: GitPanelDetail? = null,
    /** 已有内容时的后台刷新。 */
    val refreshing: Boolean = false,
    /** 当前状态的读取时间（墙钟毫秒）；来自应用缓存时可能早于打开页面。 */
    val statusReadAtMillis: Long? = null,
)

data class GitPanelActions(
    val onRefresh: () -> Unit = {},
    val onSelectTab: (GitPanelTab) -> Unit = {},
    val onOpenChange: (GitChangeGroup, GitFileChange) -> Unit = { _, _ -> },
    val onOpenCommit: (GitCommitSummary) -> Unit = {},
    val onOpenCommitFile: (GitFileChange) -> Unit = {},
    val onBack: () -> Unit = {},
    /** 作为二级页面时离开 Git 的入口；非空时根层也显示返回按钮。检视器根层由页签与收起按钮离开，为空。 */
    val onExit: (() -> Unit)? = null,
)

/** 抓取时间按分钟取整，页头每隔这么久重算一次。 */
const val GIT_FETCH_AGE_TICK_MILLIS = 60_000L

/**
 * 页头里的上游名：上游分支与本地分支同名时只留远端名（`origin/main` 跟 `main` 显示为 `origin`），
 * 否则原样显示；没有斜杠的上游也原样显示。
 */
fun gitUpstreamLabel(branch: String?, upstream: String): String {
    val slash = upstream.indexOf('/')
    if (slash <= 0 || branch.isNullOrEmpty()) return upstream
    return if (upstream.substring(slash + 1) == branch) upstream.substring(0, slash) else upstream
}

/** 上次抓取距今：按分钟、小时、天取整，时钟回拨视为刚刚。 */
sealed interface GitFetchAge {
    data object Never : GitFetchAge
    data object JustNow : GitFetchAge
    data class Minutes(val value: Int) : GitFetchAge
    data class Hours(val value: Int) : GitFetchAge
    data class Days(val value: Int) : GitFetchAge
}

fun gitFetchAge(lastFetchSeconds: Long?, nowMillis: Long): GitFetchAge {
    lastFetchSeconds ?: return GitFetchAge.Never
    val minutes = ((nowMillis / 1000 - lastFetchSeconds) / 60).coerceAtLeast(0)
    return when {
        minutes < 1 -> GitFetchAge.JustNow
        minutes < 60 -> GitFetchAge.Minutes(minutes.toInt())
        minutes < 24 * 60 -> GitFetchAge.Hours((minutes / 60).toInt())
        else -> GitFetchAge.Days((minutes / (24 * 60)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}

/** 超过 [max] 个字符时保留首尾、中间换成省略号，分支名的前缀与末段都有辨识度。 */
fun shortenMiddle(text: String, max: Int): String {
    if (text.length <= max || max < 3) return text
    val keep = max - 1
    val head = (keep + 1) / 2
    return text.take(head) + "\u2026" + text.takeLast(keep - head)
}

/** 检查端单项计数的上限；截断时达到上限的计数显示为“1000+”。 */
const val GIT_TRACKING_COUNT_CAP = 1000

/** 领先或落后的显示数；为空或 0 时不显示。 */
fun gitTrackingCount(count: Int?, truncated: Boolean?): String? = when {
    count == null || count <= 0 -> null
    truncated == true && count >= GIT_TRACKING_COUNT_CAP -> "$count+"
    else -> count.toString()
}

/** 计数因预算耗尽或历史缺失无法确定：截断且两项都为空。 */
val GitTracking.countsUnknown: Boolean
    get() = upstream != null && upstreamMissing != true && truncated == true && ahead == null && behind == null


/** 由两侧内容构建差异模型；计算量与文件大小相关，调用方应在 `Dispatchers.Default` 上执行。 */
object GitDiffModels {
    fun build(sides: GitDiffSides): FileDiffModel {
        val kind = when {
            !sides.old.exists && sides.new.exists -> FileChangeKind.CREATED
            sides.old.exists && !sides.new.exists -> FileChangeKind.DELETED
            else -> FileChangeKind.MODIFIED
        }
        val fileName = sides.path
        if (sides.old.binary || sides.new.binary) {
            return FileDiffModel.fromTexts("", "", fileName, isBinary = true, changeKind = kind)
        }
        if (sides.old.truncated || sides.new.truncated) {
            return FileDiffModel(fileName, kind, stats = null, content = FileDiffContent.Summary(FileDiffSummaryReason.LARGE_FILE))
        }
        return FileDiffModel.fromTexts(sides.old.text.orEmpty(), sides.new.text.orEmpty(), fileName, changeKind = kind)
    }
}

/** 变更行右侧是否显示行数：二进制写明，有增删时显示计数；空文件或没有统计时显示进入详情的箭头。 */
internal fun GitFileChange.showsLineCounts(): Boolean =
    linesSkipped == "binary" || (addedLines ?: 0) > 0 || (removedLines ?: 0) > 0
