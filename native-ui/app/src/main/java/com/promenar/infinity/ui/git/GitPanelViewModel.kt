package com.promenar.infinity.ui.git

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.data.git.GitChangeGroup
import com.promenar.infinity.data.git.GitChangesService
import com.promenar.infinity.data.git.GitCommitSummary
import com.promenar.infinity.data.git.GitDiffSides
import com.promenar.infinity.data.git.GitFileChange
import com.promenar.infinity.data.git.GitInspectResult
import com.promenar.infinity.data.git.GitStatusCache
import com.promenar.infinity.ui.common.diff.FileDiffModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Git 变更面板的状态：工作区变更三组、最近提交、本地分支、提交详情与单文件差异。只读；
 * 差异模型在 [diffDispatcher]（默认 `Dispatchers.Default`）上构建后再交给视图。
 * 每次导航递增详情或文件令牌，过期任务的结果不会覆盖当前页面。
 */
class GitPanelViewModel(
    private val sessionId: String,
    private val service: GitChangesService,
    private val diffDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val listWorktrees: (suspend (String) -> Result<List<com.promenar.infinity.domain.branch.WorktreeEntry>>)? = null,
    private val statusCache: GitStatusCache? = null,
) : ViewModel() {
    private val _state = MutableStateFlow(GitPanelUiState())
    val state: StateFlow<GitPanelUiState> = _state.asStateFlow()

    private var statusJob: Job? = null
    private var logJob: Job? = null
    private var branchesJob: Job? = null
    private var detailJob: Job? = null
    private var fileJob: Job? = null
    private var detailToken = 0L
    private var fileToken = 0L

    /** 打开面板：回到列表并按缓存策略读取状态。 */
    fun open() {
        detailToken++
        fileToken++
        detailJob?.cancel()
        fileJob?.cancel()
        _state.update { it.copy(detail = null) }
        if (statusCache == null || statusJob?.isActive != true) loadStatus()
        val current = _state.value
        if (current.tab == GitPanelTab.COMMITS || current.log != null) loadLog()
        if (current.tab == GitPanelTab.BRANCHES || current.branches != null) loadBranches()
    }

    /** 回到前台：缓存订阅已停止时重新订阅，先显示缓存再按需刷新。 */
    fun resumeStatus() {
        if (statusCache != null && statusJob?.isActive != true) loadStatus()
    }

    fun close() {
        statusJob?.cancel()
        statusJob = null
    }

    /** 手动刷新：重新读取状态；已请求过的提交列表与当前详情页一并重新读取。 */
    fun refresh() {
        statusCache?.invalidate(sessionId)
        if (statusCache == null || statusJob?.isActive != true) loadStatus()
        val current = _state.value
        if (current.tab == GitPanelTab.COMMITS || current.log != null) loadLog()
        if (current.tab == GitPanelTab.BRANCHES || current.branches != null) loadBranches()
        when (val detail = current.detail) {
            null -> Unit
            is GitPanelDetail.Change -> openChange(detail.group, detail.change)
            is GitPanelDetail.Commit -> {
                val file = detail.file?.change
                openCommit(detail.summary)
                file?.let(::openCommitFile)
            }
        }
    }

    fun selectTab(tab: GitPanelTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == GitPanelTab.COMMITS && _state.value.log == null) loadLog()
        if (tab == GitPanelTab.BRANCHES && _state.value.branches == null) loadBranches()
    }

    fun openChange(group: GitChangeGroup, change: GitFileChange) {
        val token = ++detailToken
        fileToken++
        fileJob?.cancel()
        detailJob?.cancel()
        _state.update { it.copy(detail = GitPanelDetail.Change(group, change, GitLoad.Loading)) }
        detailJob = viewModelScope.launch {
            val diff = buildDiff(service.changeDiff(sessionId, group, change))
            if (token != detailToken) return@launch
            _state.update { state ->
                val detail = state.detail as? GitPanelDetail.Change ?: return@update state
                state.copy(detail = detail.copy(diff = diff))
            }
        }
    }

    fun openCommit(summary: GitCommitSummary) {
        val token = ++detailToken
        fileToken++
        fileJob?.cancel()
        detailJob?.cancel()
        _state.update { it.copy(detail = GitPanelDetail.Commit(summary, GitLoad.Loading)) }
        detailJob = viewModelScope.launch {
            val detail = service.commit(sessionId, summary.sha).toLoad()
            if (token != detailToken) return@launch
            _state.update { state ->
                val current = state.detail as? GitPanelDetail.Commit ?: return@update state
                state.copy(detail = current.copy(detail = detail))
            }
        }
    }

    fun openCommitFile(change: GitFileChange) {
        val commit = _state.value.detail as? GitPanelDetail.Commit ?: return
        val sha = commit.summary.sha
        val owner = detailToken
        val token = ++fileToken
        fileJob?.cancel()
        _state.update { state ->
            val current = state.detail as? GitPanelDetail.Commit ?: return@update state
            state.copy(detail = current.copy(file = GitPanelFileDiff(change, GitLoad.Loading)))
        }
        fileJob = viewModelScope.launch {
            val diff = buildDiff(service.commitFileDiff(sessionId, sha, change))
            if (token != fileToken || owner != detailToken) return@launch
            _state.update { state ->
                val current = state.detail as? GitPanelDetail.Commit ?: return@update state
                val file = current.file?.takeIf { it.change == change } ?: return@update state
                state.copy(detail = current.copy(file = file.copy(diff = diff)))
            }
        }
    }

    /** 返回上一级；已在列表时返回 false。 */
    fun back(): Boolean {
        val detail = _state.value.detail ?: return false
        fileToken++
        fileJob?.cancel()
        if (detail is GitPanelDetail.Commit && detail.file != null) {
            _state.update { it.copy(detail = detail.copy(file = null)) }
        } else {
            detailToken++
            detailJob?.cancel()
            _state.update { it.copy(detail = null) }
        }
        return true
    }

    private fun loadStatus() {
        statusJob?.cancel()
        _state.update { state ->
            if (state.status is GitLoad.Ready) state.copy(refreshing = true) else state.copy(status = GitLoad.Loading)
        }
        statusJob = viewModelScope.launch {
            val cache = statusCache
            if (cache == null) {
                val status = service.status(sessionId).toLoad()
                _state.update { it.copy(status = status, refreshing = false) }
            } else {
                cache.observe(sessionId).collect { snapshot ->
                    _state.update { state ->
                        state.copy(
                            status = snapshot.result?.toLoad() ?: GitLoad.Loading,
                            refreshing = snapshot.refreshing,
                            statusReadAtMillis = snapshot.readAtMillis,
                        )
                    }
                }
            }
        }
    }

    private fun loadLog() {
        logJob?.cancel()
        _state.update { state -> if (state.log is GitLoad.Ready) state else state.copy(log = GitLoad.Loading) }
        logJob = viewModelScope.launch {
            val log = service.log(sessionId).toLoad()
            _state.update { it.copy(log = log) }
        }
    }

    private fun loadBranches() {
        branchesJob?.cancel()
        _state.update { state -> if (state.branches is GitLoad.Ready) state else state.copy(branches = GitLoad.Loading) }
        branchesJob = viewModelScope.launch {
            val branches = service.branches(sessionId).toLoad()
            _state.update { it.copy(branches = branches) }
            val worktrees = listWorktrees?.invoke(sessionId)?.getOrNull() ?: return@launch
            val current = (worktrees.firstOrNull { it.sessionId == sessionId } ?: worktrees.firstOrNull { it.isMain })?.path
            _state.update { it.copy(worktrees = worktrees, currentWorktreePath = current) }
        }
    }

    private suspend fun buildDiff(result: GitInspectResult<GitDiffSides>): GitLoad<FileDiffModel> = when (result) {
        is GitInspectResult.Failure -> GitLoad.Failed(result.error.toProblem())
        is GitInspectResult.Success -> GitLoad.Ready(withContext(diffDispatcher) { GitDiffModels.build(result.value) })
    }

    companion object {
        fun factory(
            sessionId: String,
            service: GitChangesService,
            listWorktrees: (suspend (String) -> Result<List<com.promenar.infinity.domain.branch.WorktreeEntry>>)? = null,
            statusCache: GitStatusCache? = null,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    GitPanelViewModel(sessionId, service, listWorktrees = listWorktrees, statusCache = statusCache) as T
            }
    }
}
