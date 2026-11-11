package com.promenar.infinity.ui.git

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.using
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.hierarchicalTransition
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Commit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.git.GitBranch
import com.promenar.infinity.data.git.GitBranchList
import com.promenar.infinity.data.git.GitChangeGroup
import com.promenar.infinity.data.git.GitChangeKind
import com.promenar.infinity.data.git.GitChangeList
import com.promenar.infinity.data.git.GitCommitDetail
import com.promenar.infinity.data.git.GitCommitLog
import com.promenar.infinity.data.git.GitCommitSummary
import com.promenar.infinity.data.git.GitFileChange
import com.promenar.infinity.data.git.GitStatus
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.LocalInfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityHeaderTitle
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityPanePresentation
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.InfinityPanelTab
import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.diff.FileDiffModel
import com.promenar.infinity.ui.common.diff.FileDiffView
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import com.promenar.infinity.ui.common.InfinityMetaLine

object GitPanelTags {
    const val ROOT = "git_panel_root"
    const val ENTRY = "git_panel_entry"
    const val REFRESH = "git_panel_refresh"
    const val STATE = "git_panel_state"
    const val CHANGES_LIST = "git_panel_changes_list"
    const val COMMITS_LIST = "git_panel_commits_list"
    const val COMMIT_FILES = "git_panel_commit_files"
    const val DIFF = "git_panel_diff"
    const val BRANCHES_LIST = "git_panel_branches_list"
    const val TRACKING = "git_panel_tracking"
    fun branch(name: String) = "git_panel_branch_$name"
    fun change(group: GitChangeGroup, path: String) = "git_panel_change_${group.name.lowercase()}_$path"
    fun commit(sha: String) = "git_panel_commit_$sha"
}

/**
 * Git 变更的二级页面：由 [com.promenar.infinity.ui.common.InfinityAdaptivePaneHost] 承载，窄窗口整页、宽窗口在会话检视器中。
 * 打开时刷新一次，之后由用户手动刷新；页内详情先逐层返回，回到列表后再由 [onExit] 离开（为空时根层不显示返回）。
 */
@Composable
fun GitChangesPage(
    sessionId: String,
    onExit: (() -> Unit)?,
) {
    val application = LocalContext.current.applicationContext as InfinityApplication
    val viewModel: GitPanelViewModel = viewModel(
        key = "git-panel-$sessionId",
        factory = GitPanelViewModel.factory(sessionId, application.gitChangesService, application.branchControls::listWorktrees, application.gitStatusCache),
    )
    // 页面在前台时才观察状态缓存；切到后台或离开即停止轮询。
    androidx.lifecycle.compose.LifecycleStartEffect(viewModel) {
        viewModel.resumeStatus()
        onStopOrDispose { viewModel.close() }
    }
    LaunchedEffect(viewModel) { viewModel.open() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.detail != null) { viewModel.back() }
    GitChangesPanel(
        state = state,
        actions = GitPanelActions(
            onRefresh = viewModel::refresh,
            onSelectTab = viewModel::selectTab,
            onOpenChange = viewModel::openChange,
            onOpenCommit = viewModel::openCommit,
            onOpenCommitFile = viewModel::openCommitFile,
            onBack = { viewModel.back() },
            onExit = onExit,
        ),
        modifier = gitPageModifier(),
    )
}

/** Git 页的边距外壳：检视器是会话内的浮动卡片，不贴系统栏；整页时避开系统栏。视觉实验室的 Git 版本复用同一外壳。 */
@Composable
internal fun gitPageModifier(): Modifier = Modifier
    .fillMaxSize()
    .then(
        if (LocalInfinityPanePresentation.current == InfinityPanePresentation.Inspector) Modifier.padding(top = InfinitySpacing.XSmall)
        else Modifier.windowInsetsPadding(WindowInsets.safeDrawing),
    )
    .padding(horizontal = InfinitySpacing.ScreenHorizontal)

/** 无状态的面板内容；父容器必须给出有界高度（差异视图以 `Dp.Infinity` 视口填满剩余高度）。 */
@Composable
fun GitChangesPanel(
    state: GitPanelUiState,
    actions: GitPanelActions,
    modifier: Modifier = Modifier,
    nowMillis: Long? = null,
) {
    val readyStatus = (state.status as? GitLoad.Ready)?.value
    Column(modifier = modifier.testTag(GitPanelTags.ROOT)) {
        GitPanelHeader(state, actions)
        AnimatedVisibility(visible = state.detail == null && readyStatus != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            readyStatus?.let { BranchSyncRows(it, nowMillis) }
        }
        Spacer(modifier = Modifier.height(InfinitySpacing.Small))
        // 列表、文件差异、提交详情、提交内文件差异之间按层级做共享 X 轴切换；同一页内的数据刷新不触发切换。
        AnimatedContent(
            targetState = state,
            contentKey = { it.detail.pageKey },
            // 不裁剪：列表页的二级页签向两侧伸到面板边缘。
            transitionSpec = { hierarchicalTransition { it.detail.depth } using SizeTransform(clip = false) },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            label = "git_panel_page",
        ) { shown ->
            when (val detail = shown.detail) {
                null -> Column(Modifier.fillMaxSize()) {
                    InfinityPanelTabs(
                        tabs = listOf(
                            InfinityPanelTab(
                                value = GitPanelTab.CHANGES,
                                label = stringResource(R.string.git_panel_tab_changes),
                                count = (shown.status as? GitLoad.Ready)?.value?.takeIf { !it.isClean }?.let { countBadge(it) },
                            ),
                            InfinityPanelTab(value = GitPanelTab.COMMITS, label = stringResource(R.string.git_panel_tab_commits)),
                            InfinityPanelTab(
                                value = GitPanelTab.BRANCHES,
                                label = stringResource(R.string.git_panel_tab_branches),
                                count = (shown.branches as? GitLoad.Ready)?.value?.branches?.size?.takeIf { it > 0 }?.toString(),
                            ),
                        ),
                        selected = shown.tab,
                        onSelect = actions.onSelectTab,
                        bleed = InfinitySpacing.ScreenHorizontal,
                    )
                    Spacer(modifier = Modifier.height(InfinitySpacing.Small))
                    AnimatedContent(
                        targetState = shown,
                        contentKey = { it.tab },
                        transitionSpec = { InfinityMotion.sharedAxisX(forward = targetState.tab.ordinal > initialState.tab.ordinal) },
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        label = "git_panel_tab",
                    ) { tabState ->
                        when (tabState.tab) {
                            GitPanelTab.CHANGES -> ChangesContent(tabState.status, actions)
                            GitPanelTab.COMMITS -> CommitsContent(tabState.log ?: GitLoad.Loading, actions)
                            GitPanelTab.BRANCHES -> BranchesContent(tabState.branches ?: GitLoad.Loading, actions, tabState.worktrees, tabState.currentWorktreePath)
                        }
                    }
                }
                is GitPanelDetail.Change -> DiffContent(detail.diff, actions.onRefresh)
                is GitPanelDetail.Commit -> {
                    val file = detail.file
                    if (file != null) DiffContent(file.diff, actions.onRefresh) else CommitDetailContent(detail, actions)
                }
            }
        }
    }
}

/** 面板页的身份：同一页内数据变化（加载完成、刷新）不算换页。 */
private val GitPanelDetail?.pageKey: String
    get() = when (this) {
        null -> "list"
        is GitPanelDetail.Change -> "change:${group.name}:${change.path}"
        is GitPanelDetail.Commit -> "commit:${summary.sha}" + (file?.let { ":${it.change.path}" } ?: "")
    }

private val GitPanelDetail?.depth: Int
    get() = when (this) {
        null -> 0
        is GitPanelDetail.Change -> 1
        is GitPanelDetail.Commit -> if (file == null) 1 else 2
    }

/** 加载、失败、就绪三态之间交叉淡入；就绪后数据刷新原地更新。 */
@Composable
private fun <T> GitLoadContent(
    load: GitLoad<T>,
    label: String,
    content: @Composable (GitLoad<T>) -> Unit,
) {
    AnimatedContent(
        targetState = load,
        contentKey = {
            when (it) {
                GitLoad.Loading -> 0
                is GitLoad.Failed -> 1
                is GitLoad.Ready -> 2
            }
        },
        transitionSpec = { InfinityMotion.fadeThrough() },
        modifier = Modifier.fillMaxSize(),
        label = label,
    ) { shown -> content(shown) }
}

private fun countBadge(status: GitStatus): String =
    if (status.truncated) "${status.changeCount}+" else status.changeCount.toString()

@Composable
private fun GitPanelHeader(state: GitPanelUiState, actions: GitPanelActions) {
    val detail = state.detail
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget),
        // 页头可能多行：返回与刷新按钮对齐首行，不随整块居中。
        verticalAlignment = Alignment.Top,
    ) {
        AnimatedVisibility(
            visible = detail != null || actions.onExit != null,
            enter = expandHorizontally(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)) + fadeIn(),
            exit = shrinkHorizontally(tween(InfinityMotion.DurationShort)) + fadeOut(tween(InfinityMotion.DurationShort)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfinityBackButton(onClick = { if (detail != null) actions.onBack() else actions.onExit?.invoke() })
                Spacer(modifier = Modifier.width(InfinitySpacing.XSmall))
            }
        }
        // 标题随页面纵向替换：进入更深一层自下而上，返回自上而下。
        AnimatedContent(
            targetState = state,
            contentKey = { it.detail.pageKey },
            transitionSpec = { InfinityMotion.sharedAxisY(forward = targetState.detail.depth > initialState.detail.depth) },
            modifier = Modifier.weight(1f),
            label = "git_panel_title",
        ) { shown ->
            val detail = shown.detail
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = InfinitySpacing.MinimumTouchTarget),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.RowTwoLineGap, Alignment.CenterVertically),
            ) {
                when (detail) {
                    null -> RepositoryTitle((shown.status as? GitLoad.Ready)?.value?.repositoryName)
                    is GitPanelDetail.Change -> HeaderTitles(
                        title = detail.change.path,
                        subtitle = stringResource(
                            when (detail.group) {
                                GitChangeGroup.STAGED -> R.string.git_panel_diff_staged
                                GitChangeGroup.UNSTAGED -> R.string.git_panel_diff_unstaged
                                GitChangeGroup.UNTRACKED -> R.string.git_panel_diff_untracked
                            },
                        ),
                        identifier = true,
                    )
                    is GitPanelDetail.Commit -> {
                        val file = detail.file
                        if (file != null) {
                            HeaderTitles(
                                title = file.change.path,
                                subtitle = stringResource(R.string.git_panel_diff_commit, detail.summary.shortSha),
                                identifier = true,
                            )
                        } else {
                            HeaderTitles(title = detail.summary.title, subtitle = detail.summary.shortSha, identifier = false)
                        }
                    }
                }
            }
        }
        RefreshButton(refreshing = state.refreshing, onClick = actions.onRefresh)
    }
}

/** 刷新按钮；后台刷新期间图标换成转圈，仍可再次点按。 */
@Composable
private fun RefreshButton(refreshing: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (refreshing) R.string.git_panel_status_refreshing else R.string.git_panel_refresh)
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(InfinitySpacing.MinimumTouchTarget)
            .semantics { contentDescription = label }
            .testTag(GitPanelTags.REFRESH),
    ) {
        Crossfade(targetState = refreshing, animationSpec = tween(InfinityMotion.DurationShort), label = "git_panel_refresh") { busy ->
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HeaderTitles(title: String, subtitle: String, identifier: Boolean) {
    // 路径类标题停在末尾，露出文件名；超出宽度时单行横滑。
    InfinityHeaderTitle(
        text = title,
        style = InfinityTypography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        alignEnd = identifier,
    )
    Text(
        text = subtitle,
        style = InfinityTypography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 列表页标题：仓库名；旧检查端不输出仓库名时用“Git”。 */
@Composable
private fun RepositoryTitle(repositoryName: String?) {
    val repository = repositoryName?.takeIf { it.isNotBlank() }
    Row(
        modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        if (repository != null) {
            Icon(
                imageVector = Icons.Rounded.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        InfinityHeaderTitle(
            text = repository ?: stringResource(R.string.git_panel_title),
            style = InfinityTypography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 页头分为宽窄两种排布的分界：放得下分支行与同步行并排时合为一行。 */
private val BranchSyncWideWidth = 520.dp

/**
 * 页头的第二、三层：分支与上游一行，同步状态与抓取时间一行；宽窗口两行并排。
 * 分离 HEAD 与无上游时没有同步行；上游在本地不存在时同步行换成错误提示。
 * 抓取时间每分钟重算一次；截图测试传入固定时刻。
 */
@Composable
private fun BranchSyncRows(status: GitStatus, nowMillis: Long?) {
    val ticking by produceState(System.currentTimeMillis(), status.lastFetchSeconds, nowMillis) {
        if (nowMillis != null || status.lastFetchSeconds == null) return@produceState
        while (true) {
            delay(GIT_FETCH_AGE_TICK_MILLIS)
            value = System.currentTimeMillis()
        }
    }
    val now = nowMillis ?: ticking
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.XSmall)) {
        if (maxWidth >= BranchSyncWideWidth) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            ) {
                BranchRefLine(status, Modifier.weight(1f))
                SyncLine(status, now)
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.RowTwoLineGap)) {
                BranchRefLine(status, Modifier.fillMaxWidth())
                SyncLine(status, now)
            }
        }
    }
}

/** 分支行：分支名等宽显示，后接上游（与本地同名时只写远端名）；分离 HEAD 显示提交号；超出宽度时单行横滑。 */
@Composable
private fun BranchRefLine(status: GitStatus, modifier: Modifier) {
    val tracking = status.tracking
    val upstream = tracking.upstream
    val missing = tracking.upstreamMissing == true && upstream != null
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val label = branchLabel(status.branch, status.detached, status.head?.take(7), status.unborn)
    Row(
        modifier = modifier.heightIn(min = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = if (status.detached) Icons.Rounded.Commit else Icons.Rounded.AccountTree,
            contentDescription = null,
            tint = muted,
            modifier = Modifier.size(16.dp),
        )
        Row(
            modifier = Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = InfinityTypography.labelLarge.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
            when {
                status.detached || missing -> Unit
                upstream == null -> Text(
                    text = " · " + stringResource(R.string.git_panel_no_upstream),
                    style = InfinityTypography.bodySmall,
                    color = muted,
                    maxLines = 1,
                    softWrap = false,
                )
                else -> {
                    Text(
                        text = "  →  ",
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Text(
                        text = gitUpstreamLabel(status.branch, upstream),
                        style = InfinityTypography.labelLarge.copy(fontFamily = InfinityMonospace, fontWeight = FontWeight.Normal),
                        color = muted,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * 同步行：落后、领先计数（都为 0 时显示“已同步”，无法确定时显示中性提示），后接抓取时间。
 */
@Composable
private fun SyncLine(status: GitStatus, now: Long) {
    val tracking = status.tracking
    val upstream = tracking.upstream
    if (status.detached || upstream == null) return
    if (tracking.upstreamMissing == true) {
        Text(
            text = stringResource(R.string.git_panel_upstream_missing, upstream),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag(GitPanelTags.TRACKING),
        )
        return
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val fetchAge = gitFetchAge(status.lastFetchSeconds, now)
    val ahead = gitTrackingCount(tracking.ahead, tracking.truncated)
    val behind = gitTrackingCount(tracking.behind, tracking.truncated)
    val separator: @Composable () -> Unit = {
        Text(" · ", style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, softWrap = false)
    }
    Row(
        modifier = Modifier.heightIn(min = 20.dp).horizontalScroll(rememberScrollState()).testTag(GitPanelTags.TRACKING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val synced = ahead == null && behind == null && !tracking.countsUnknown
        // 从未抓取时“已同步”没有依据，只显示抓取时间；本地领先或落后的计数照常显示。
        if (!(synced && fetchAge == GitFetchAge.Never)) {
            when {
                tracking.countsUnknown -> SyncCount(Icons.AutoMirrored.Rounded.HelpOutline, stringResource(R.string.git_panel_tracking_unknown), muted)
                synced -> SyncCount(Icons.Rounded.Check, stringResource(R.string.git_panel_synced), muted)
                else -> {
                    behind?.let { SyncCount(Icons.Rounded.ArrowDownward, stringResource(R.string.git_panel_behind, it), MaterialTheme.infinityDomainColors.warning) }
                    if (behind != null && ahead != null) separator()
                    ahead?.let { SyncCount(Icons.Rounded.ArrowUpward, stringResource(R.string.git_panel_ahead, it), MaterialTheme.colorScheme.primary) }
                }
            }
            separator()
        }
        Text(fetchAgeLabel(fetchAge), style = InfinityTypography.bodySmall, color = muted, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun SyncCount(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
        Text(
            text = text,
            style = InfinityTypography.bodySmall.copy(fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
            color = color,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
internal fun fetchAgeLabel(age: GitFetchAge): String = when (age) {
    GitFetchAge.Never -> stringResource(R.string.git_panel_fetch_never)
    GitFetchAge.JustNow -> stringResource(R.string.git_panel_fetch_just_now)
    is GitFetchAge.Minutes -> pluralStringResource(R.plurals.git_panel_fetch_minutes, age.value, age.value)
    is GitFetchAge.Hours -> pluralStringResource(R.plurals.git_panel_fetch_hours, age.value, age.value)
    is GitFetchAge.Days -> pluralStringResource(R.plurals.git_panel_fetch_days, age.value, age.value)
}

@Composable
internal fun branchLabel(branch: String?, detached: Boolean, headShortSha: String?, unborn: Boolean): String {
    val name = when {
        detached -> stringResource(R.string.git_panel_branch_detached, headShortSha.orEmpty())
        branch != null -> branch
        else -> stringResource(R.string.git_panel_branch_unknown)
    }
    return if (unborn) stringResource(R.string.git_panel_branch_unborn, name) else name
}

@Composable
internal fun changeCountLabel(count: Int, truncated: Boolean): String = when {
    truncated -> pluralStringResource(R.plurals.git_panel_change_count_truncated, count, count)
    count == 0 -> stringResource(R.string.git_panel_no_changes)
    else -> pluralStringResource(R.plurals.git_panel_change_count, count, count)
}

// —— 工作区变更 ——

@Composable
private fun ChangesContent(load: GitLoad<GitStatus>, actions: GitPanelActions) = GitLoadContent(load, "git_changes_state") { status ->
    // 面板只读：提交、推送等写操作由用户直接告诉 Agent。
    val readonlyHint = stringResource(R.string.git_panel_readonly_hint)
    when (status) {
        GitLoad.Loading -> GitPanelStateMessage(loading = true)
        is GitLoad.Failed -> GitPanelStateMessage(problem = status.problem, onRetry = actions.onRefresh)
        is GitLoad.Ready -> {
            val value = status.value
            if (value.isClean) {
                GitPanelStateMessage(
                    message = stringResource(if (value.unborn) R.string.git_panel_empty_repository else R.string.git_panel_clean),
                    hint = readonlyHint,
                )
            } else {
                val hint = listOfNotNull(
                    if (value.unborn) stringResource(R.string.git_panel_empty_repository) else null,
                    readonlyHint,
                )
                // 列表各项等高：惰性列表看不到屏幕外的行，按三组全部变更算出是否有带路径第二行的项。
                val twoLine = GitChangeGroup.entries.any { group -> value.group(group).entries.any { it.hasSecondaryLine() } }
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(GitPanelTags.CHANGES_LIST)) {
                    if (hint.isNotEmpty()) {
                        item(key = "hint", contentType = "hint") { PanelHint(text = hint.joinToString("\n")) }
                    }
                    GitChangeGroup.entries.forEach { group ->
                        changeGroup(group, value.group(group), actions, twoLine)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.changeGroup(group: GitChangeGroup, list: GitChangeList, actions: GitPanelActions, twoLine: Boolean) {
    if (list.entries.isEmpty()) return
    item(key = "header-${group.name}", contentType = "header") {
        GroupHeader(
            text = stringResource(
                R.string.git_panel_group_header,
                stringResource(
                    when (group) {
                        GitChangeGroup.STAGED -> R.string.git_panel_group_staged
                        GitChangeGroup.UNSTAGED -> R.string.git_panel_group_unstaged
                        GitChangeGroup.UNTRACKED -> R.string.git_panel_group_untracked
                    },
                ),
                list.size,
            ),
        )
    }
    items(list.entries, key = { "${group.name}:${it.path}" }, contentType = { "change" }) { change ->
        ChangeRow(
            change = change,
            untracked = group == GitChangeGroup.UNTRACKED,
            onClick = { actions.onOpenChange(group, change) },
            modifier = Modifier.testTag(GitPanelTags.change(group, change.path)),
            twoLine = twoLine,
        )
    }
    if (list.truncated) {
        item(key = "truncated-${group.name}", contentType = "footer") {
            FooterNote(pluralStringResource(R.plurals.git_panel_group_truncated, list.size, list.size))
        }
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text = text,
        style = InfinityTypography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = InfinitySpacing.Large, bottom = InfinitySpacing.XSmall)
            .semantics { heading() },
    )
}

@Composable
private fun PanelHint(text: String) {
    Text(
        text = text,
        style = InfinityTypography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.XSmall),
    )
}

@Composable
private fun FooterNote(text: String) {
    Text(
        text = text,
        style = InfinityTypography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Small),
    )
}

@Composable
private fun ChangeRow(
    change: GitFileChange,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    untracked: Boolean = false,
    twoLine: Boolean = change.hasSecondaryLine(),
) {
    val kindLabel = if (untracked) stringResource(R.string.git_panel_group_untracked) else changeKindLabel(change.kind)
    val linesA11y = if (change.addedLines != null && change.removedLines != null) {
        stringResource(R.string.git_panel_lines_a11y, change.addedLines, change.removedLines)
    } else null
    val name = change.path.substringAfterLast('/')
    val directory = change.path.substringBeforeLast('/', missingDelimiterValue = "")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = changeRowMinHeight(twoLine || change.hasSecondaryLine()))
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClickLabel = change.path, onClick = onClick)
            .semantics { contentDescription = listOfNotNull(kindLabel, change.path, linesA11y).joinToString(" ") }
            .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        ChangeKindBadge(change.kind, untracked)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.RowTwoLineGap)) {
            // 列表各项等高：文件名与路径各限一行，中间省略保留扩展名与末级目录；完整路径见无障碍描述。
            InfinityIdentifierText(
                text = name,
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            val secondary = when {
                change.kind == GitChangeKind.RENAMED && change.oldPath != null ->
                    stringResource(R.string.git_panel_renamed_from, change.oldPath)
                directory.isNotEmpty() -> directory
                else -> null
            }
            if (secondary != null) {
                InfinityIdentifierText(
                    text = secondary,
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
        if (change.showsLineCounts()) {
            LineCounts(change)
        } else {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** 变更行是否带第二行（重命名来源或所在目录），与 [ChangeRow] 内 secondary 的判定一致。 */
private fun GitFileChange.hasSecondaryLine(): Boolean =
    (kind == GitChangeKind.RENAMED && oldPath != null) || path.substringBeforeLast('/', missingDelimiterValue = "").isNotEmpty()

/**
 * 变更行最小高度。列表各项等高：同列表有双行项时，单行项也取双行高并垂直居中。
 * 双行高 = 上下内边距 8dp×2 + 文件名 bodyMedium 一行 + 行距 2dp + 路径 bodySmall 一行，
 * 默认字号下为 16 + 20 + 2 + 18 = 56dp；行高按当前字号缩放实算，大字号下仍与双行项一致。
 */
@Composable
private fun changeRowMinHeight(twoLine: Boolean): Dp = with(LocalDensity.current) {
    val text = if (twoLine) {
        InfinityTypography.bodyMedium.lineHeight.toDp() + InfinitySpacing.RowTwoLineGap + InfinityTypography.bodySmall.lineHeight.toDp()
    } else {
        InfinityTypography.bodyMedium.lineHeight.toDp()
    }
    maxOf(InfinitySpacing.MinimumTouchTarget, text + InfinitySpacing.Small * 2)
}

/** 行数统计：只显示非零的一侧；二进制文件写明，其它无法统计的情况不显示。 */
@Composable
private fun LineCounts(change: GitFileChange) {
    val style = InfinityTypography.labelMedium.copy(fontFeatureSettings = "tnum")
    if (change.linesSkipped == "binary") {
        Text(stringResource(R.string.git_panel_lines_binary), style = style, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        return
    }
    val added = change.addedLines ?: 0
    val removed = change.removedLines ?: 0
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (added > 0) Text("+$added", style = style, color = MaterialTheme.infinityDomainColors.success, maxLines = 1)
        if (removed > 0) Text("\u2212$removed", style = style, color = MaterialTheme.colorScheme.error, maxLines = 1)
    }
}

@Composable
private fun changeKindLabel(kind: GitChangeKind): String = stringResource(
    when (kind) {
        GitChangeKind.ADDED -> R.string.git_panel_change_added
        GitChangeKind.MODIFIED -> R.string.git_panel_change_modified
        GitChangeKind.DELETED -> R.string.git_panel_change_deleted
        GitChangeKind.RENAMED -> R.string.git_panel_change_renamed
    },
)

@Composable
private fun ChangeKindBadge(kind: GitChangeKind, untracked: Boolean = false) {
    val domain = MaterialTheme.infinityDomainColors
    val (letter, color) = if (untracked) "?" to MaterialTheme.colorScheme.onSurfaceVariant else when (kind) {
        GitChangeKind.ADDED -> "A" to domain.success
        GitChangeKind.MODIFIED -> "M" to domain.warning
        GitChangeKind.DELETED -> "D" to MaterialTheme.colorScheme.error
        GitChangeKind.RENAMED -> "R" to domain.info
    }
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = letter, style = InfinityTypography.labelMedium, color = color, fontWeight = FontWeight.Bold)
    }
}

// —— 本地分支 ——

@Composable
private fun BranchesContent(
    load: GitLoad<GitBranchList>,
    actions: GitPanelActions,
    worktrees: List<com.promenar.infinity.domain.branch.WorktreeEntry> = emptyList(),
    currentWorktreePath: String? = null,
) = GitLoadContent(load, "git_branches_state") { branches ->
    when (branches) {
        GitLoad.Loading -> GitPanelStateMessage(loading = true)
        is GitLoad.Failed -> GitPanelStateMessage(problem = branches.problem, onRetry = actions.onRefresh)
        is GitLoad.Ready -> {
            val value = branches.value
            if (value.branches.isEmpty()) {
                GitPanelStateMessage(message = stringResource(R.string.git_panel_no_branches))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(GitPanelTags.BRANCHES_LIST)) {
                    items(value.branches, key = { it.name }, contentType = { "branch" }) { branch ->
                        BranchRow(branch)
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (value.truncated) {
                        item(key = "truncated", contentType = "footer") {
                            FooterNote(pluralStringResource(R.plurals.git_panel_branches_truncated, value.branches.size, value.branches.size))
                        }
                    }
                    worktreeSection(entries = worktrees, currentPath = currentWorktreePath, onSelect = null)
                }
            }
        }
    }
}

/** 分支行只读展示：名称（当前分支带标记）、提交与标题、相对上游的领先落后。 */
@Composable
private fun BranchRow(branch: GitBranch) {
    val ahead = gitTrackingCount(branch.tracking.ahead, branch.tracking.truncated)
    val behind = gitTrackingCount(branch.tracking.behind, branch.tracking.truncated)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .semantics(mergeDescendants = true) {}
            .testTag(GitPanelTags.branch(branch.name))
            .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            if (branch.current) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = stringResource(R.string.git_panel_branch_current),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            InfinityIdentifierText(
                text = branch.name,
                style = InfinityTypography.bodyMedium.copy(
                    fontFamily = InfinityMonospace,
                    fontWeight = if (branch.current) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(branch.shortSha, branch.title?.takeIf(String::isNotBlank)).joinToString(" · ")  // wrap-check: allow 单行省略
            if (meta.isNotEmpty()) {
                Text(
                    text = meta,
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (ahead != null || behind != null) {
            Column(horizontalAlignment = Alignment.End) {
                val style = InfinityTypography.labelMedium.copy(fontFeatureSettings = "tnum")
                ahead?.let { Text("\u2191$it", style = style, color = MaterialTheme.colorScheme.primary, maxLines = 1) }
                behind?.let { Text("\u2193$it", style = style, color = MaterialTheme.infinityDomainColors.warning, maxLines = 1) }
            }
        }
    }
}

// —— 最近提交 ——

@Composable
private fun CommitsContent(load: GitLoad<GitCommitLog>, actions: GitPanelActions) = GitLoadContent(load, "git_commits_state") { log ->
    when (log) {
        GitLoad.Loading -> GitPanelStateMessage(loading = true)
        is GitLoad.Failed -> GitPanelStateMessage(problem = log.problem, onRetry = actions.onRefresh)
        is GitLoad.Ready -> {
            val value = log.value
            if (value.commits.isEmpty()) {
                GitPanelStateMessage(message = stringResource(R.string.git_panel_no_commits))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize().testTag(GitPanelTags.COMMITS_LIST)) {
                    items(value.commits, key = { it.sha }, contentType = { "commit" }) { commit ->
                        CommitRow(commit, onClick = { actions.onOpenCommit(commit) })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    if (value.truncated) {
                        item(key = "truncated", contentType = "footer") {
                            FooterNote(pluralStringResource(R.plurals.git_panel_commits_truncated, value.commits.size, value.commits.size))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitRow(commit: GitCommitSummary, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = InfinitySpacing.RowTwoLineHeight)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(GitPanelTags.commit(commit.sha))
            .padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            Text(
                text = commit.title,
                style = InfinityTypography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            CommitMeta(commit)
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun CommitMeta(commit: GitCommitSummary) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
        Text(
            text = commit.shortSha,
            style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
        Text(
            text = commit.authorName.ifBlank { commit.authorEmail } + " · " + formatCommitTime(commit),  // wrap-check: allow 单行省略
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
    }
}

private val CommitTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 按提交自身记录的时区显示，与 git log 一致，且不随设备时区变化。 */
internal fun formatCommitTime(commit: GitCommitSummary): String {
    val offset = ZoneOffset.ofTotalSeconds(commit.authorTimezoneSeconds.coerceIn(-MAX_OFFSET_SECONDS, MAX_OFFSET_SECONDS))
    return Instant.ofEpochSecond(commit.authorTimeSeconds).atOffset(offset).format(CommitTimeFormat)
}

private const val MAX_OFFSET_SECONDS = 18 * 3600

@Composable
private fun CommitDetailContent(detail: GitPanelDetail.Commit, actions: GitPanelActions) {
    LazyColumn(modifier = Modifier.fillMaxSize().testTag(GitPanelTags.COMMIT_FILES)) {
        item(key = "summary", contentType = "summary") {
            CommitSummaryCard(detail.summary, (detail.detail as? GitLoad.Ready)?.value)
        }
        when (val files = detail.detail) {
            GitLoad.Loading -> item(key = "loading", contentType = "state") {
                GitPanelStateMessage(loading = true, scrollable = false, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp))
            }
            is GitLoad.Failed -> item(key = "failed", contentType = "state") {
                GitPanelStateMessage(
                    problem = files.problem,
                    onRetry = actions.onRefresh,
                    scrollable = false,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                )
            }
            is GitLoad.Ready -> {
                val list = files.value.files
                // 列表各项等高：按全部提交文件算出是否有带第二行的项。
                val twoLine = list.entries.any { it.hasSecondaryLine() }
                item(key = "files-header", contentType = "header") {
                    GroupHeader(
                        if (list.entries.isEmpty()) {
                            stringResource(R.string.git_panel_commit_no_files)
                        } else {
                            pluralStringResource(R.plurals.git_panel_commit_files, list.size, list.size)
                        },
                    )
                }
                items(list.entries, key = { "file:${it.path}" }, contentType = { "change" }) { change ->
                    ChangeRow(change = change, onClick = { actions.onOpenCommitFile(change) }, twoLine = twoLine)
                }
                if (list.truncated) {
                    item(key = "files-truncated", contentType = "footer") {
                        FooterNote(pluralStringResource(R.plurals.git_panel_commit_files_truncated, list.size, list.size))
                    }
                }
            }
        }
    }
}

@Composable
private fun CommitSummaryCard(summary: GitCommitSummary, detail: GitCommitDetail?) {
    BettboxListGroup(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
        ) {
            CommitMeta(summary)
            val body = detail?.message?.trim()?.substringAfter('\n', missingDelimiterValue = "")?.trim().orEmpty()
            if (body.isNotEmpty()) {
                Text(
                    text = body,
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            InfinityIdentifierText(
                text = summary.sha,
                style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// —— 差异与状态 ——

@Composable
private fun DiffContent(load: GitLoad<FileDiffModel>, onRetry: () -> Unit) = GitLoadContent(load, "git_diff_state") { diff ->
    when (diff) {
        GitLoad.Loading -> GitPanelStateMessage(loading = true)
        is GitLoad.Failed -> GitPanelStateMessage(problem = diff.problem, onRetry = onRetry)
        // 父容器高度有界（面板剩余高度），视口可用 Dp.Infinity 填满。
        is GitLoad.Ready -> FileDiffView(
            model = diff.value,
            modifier = Modifier.fillMaxWidth().testTag(GitPanelTags.DIFF),
            maxViewportHeight = Dp.Infinity,
        )
    }
}

@Composable
internal fun problemMessage(problem: GitPanelProblem): String = stringResource(
    when (problem.kind) {
        GitProblemKind.PYTHON_UNAVAILABLE -> R.string.git_panel_error_python
        GitProblemKind.NOT_REPOSITORY -> R.string.git_panel_error_not_repository
        GitProblemKind.WORKSPACE_UNAVAILABLE -> R.string.git_panel_error_workspace
        GitProblemKind.TIMED_OUT -> R.string.git_panel_error_timeout
        GitProblemKind.UNSAFE_PATH -> R.string.git_panel_error_unsafe_path
        GitProblemKind.FAILED -> R.string.git_panel_error_failed
    },
)

@Composable
private fun GitPanelStateMessage(
    modifier: Modifier = Modifier,
    loading: Boolean = false,
    problem: GitPanelProblem? = null,
    message: String? = null,
    hint: String? = null,
    onRetry: (() -> Unit)? = null,
    scrollable: Boolean = true,
) {
    val text = when {
        loading -> stringResource(R.string.shared_loading)
        problem != null -> problemMessage(problem)
        else -> message.orEmpty()
    }
    val isError = problem != null && problem.kind != GitProblemKind.NOT_REPOSITORY
    // 有界容器内居中，内容超出高度（大字体、长说明）时可在自身范围内滚动；
    // 放在 Lazy 列表条目里时由调用方关闭滚动，避免同向嵌套滚动。
    Box(
        modifier = modifier
            .then(if (scrollable) Modifier.fillMaxSize() else Modifier)
            .testTag(GitPanelTags.STATE)
            .semantics { liveRegion = if (isError) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(InfinitySpacing.XLarge),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (loading) {
                LinearProgressIndicator(modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth())
                Spacer(modifier = Modifier.height(InfinitySpacing.Medium))
            }
            Text(
                text = text,
                style = InfinityTypography.bodyMedium,
                color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            problem?.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                Spacer(modifier = Modifier.height(InfinitySpacing.XSmall))
                Text(
                    text = detail,
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            hint?.let {
                Spacer(modifier = Modifier.height(InfinitySpacing.Small))
                Text(
                    text = it,
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (onRetry != null && problem != null && problem.kind != GitProblemKind.PYTHON_UNAVAILABLE) {
                Spacer(modifier = Modifier.height(InfinitySpacing.Medium))
                InfinityRetryButton(onClick = onRetry)
            }
        }
    }
}

// —— 资源管理器入口 ——

/** 分支 · 改动数 · ↑领先 ↓落后；会话顶栏与资源管理器入口共用。 */
@Composable
internal fun gitSummaryLine(summary: GitEntrySummary.Repository, compactChanges: Boolean = false): String =
    branchLabel(summary.branch, summary.detached, summary.headShortSha, summary.unborn) + gitSummarySuffix(summary, compactChanges)

/** 分支名之后的“ · 改动数 · ↑领先 · ↓落后”；顶栏单独排版，让长分支名先被截断。 */
@Composable
internal fun gitSummarySuffix(summary: GitEntrySummary.Repository, compactChanges: Boolean = false): String =
    gitSummaryCounts(summary, compactChanges).joinToString(separator = " · ", prefix = " · ")  // wrap-check: allow 顶栏单行逐级收缩

/** 改动数、↑领先与↓落后，各为一段。 */
@Composable
internal fun gitSummaryCounts(summary: GitEntrySummary.Repository, compactChanges: Boolean = false): List<String> {
    val changes = if (compactChanges && !summary.truncated && summary.changeCount > 0) {
        pluralStringResource(R.plurals.git_top_bar_changes, summary.changeCount, summary.changeCount)
    } else changeCountLabel(summary.changeCount, summary.truncated)
    return listOfNotNull(
        changes,
        gitTrackingCount(summary.ahead, null)?.let { "\u2191$it" },
        gitTrackingCount(summary.behind, null)?.let { "\u2193$it" },
    )
}

/** 会话工作区状态区中的 Git 入口：显示分支与改动数，点击打开 Git 变更面板。[GitEntrySummary.Hidden] 时不显示。 */
@Composable
fun GitWorkspaceEntry(
    summary: GitEntrySummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val subtitle = when (summary) {
        GitEntrySummary.Hidden -> return
        is GitEntrySummary.Repository ->
            listOf(branchLabel(summary.branch, summary.detached, summary.headShortSha, summary.unborn)) + gitSummaryCounts(summary)
        GitEntrySummary.Loading -> listOf(stringResource(R.string.git_panel_entry_loading))
        GitEntrySummary.PythonUnavailable -> listOf(stringResource(R.string.git_panel_entry_python_unavailable))
        GitEntrySummary.Failed -> listOf(stringResource(R.string.git_panel_entry_failed))
    }
    val accent: Color = when (summary) {
        is GitEntrySummary.Repository -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val label = stringResource(R.string.git_panel_entry_cd)
    BettboxListGroup(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = InfinitySpacing.RowTwoLineHeight)
                .clickable(role = Role.Button, onClickLabel = label, onClick = onClick)
                .testTag(GitPanelTags.ENTRY)
                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.RowTwoLineVertical),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountTree,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(InfinitySpacing.RowIconSize),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.RowTwoLineGap)) {
                Text(
                    text = stringResource(R.string.git_panel_title),
                    style = InfinityTypography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                InfinityMetaLine(
                    segments = subtitle,
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
