package com.promenar.infinity.ui.startup

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.startup.StartupIssue
import com.promenar.infinity.startup.StartupIssueAction
import com.promenar.infinity.startup.StartupIssueResolution
import com.promenar.infinity.startup.StartupScopeKind
import com.promenar.infinity.startup.StartupStep
import com.promenar.infinity.startup.diagnostics.AppDiagnostics
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.launch

object StartupIssuesTags {
    const val LINUX_BANNER = "startup_linux_banner"
    const val BANNER = "startup_issues_banner"
    const val SESSION_BANNER = "startup_issues_session_banner"
    const val SHEET = "startup_issues_sheet"
    const val DIAGNOSE = "startup_issues_diagnose"
    const val EXPORT = "startup_issues_export"
    const val RECORDS = "startup_issues_records"
    fun issue(id: String) = "startup_issue_$id"
    fun action(id: String, action: StartupIssueAction) = "startup_issue_${id}_${action.name.lowercase()}"
}

/** 面板里一条问题的显示内容；范围名称只在本机界面使用。 */
data class StartupIssueRow(val issue: StartupIssue, val scopeName: String)

/** 当前会话或其工作区受启动问题影响时只读。 */
fun List<StartupIssue>.affectsSession(sessionId: String?, workspaceRootUuid: String?): Boolean = any { issue ->
    when (issue.scope.kind) {
        StartupScopeKind.SESSION -> sessionId != null && issue.scope.id == sessionId
        StartupScopeKind.WORKSPACE_ROOT -> workspaceRootUuid != null && issue.scope.id == workspaceRootUuid
        else -> false
    }
}

/** 问题标题按启动步骤给出，摘要是脱敏后的技术原因。 */
internal fun StartupStep.titleRes(): Int = when (this) {
    StartupStep.DUAL_DATABASE, StartupStep.BACKUP_RESTORE, StartupStep.WRITER_INITIALIZATION -> R.string.startup_issue_title_data
    StartupStep.WORKSPACE_INVENTORY -> R.string.startup_issue_title_inventory
    StartupStep.LEGACY_ADOPTION -> R.string.startup_issue_title_adoption
    StartupStep.WORKSPACE_JOURNAL -> R.string.startup_issue_title_file_operation
    StartupStep.SESSION_JOURNAL -> R.string.startup_issue_title_session_delete
    StartupStep.TOMBSTONES -> R.string.startup_issue_title_recycle
    StartupStep.TOOL_LEDGER -> R.string.startup_issue_title_tool_ledger
    StartupStep.VECTOR_QUEUE -> R.string.startup_issue_title_vectors
    StartupStep.BACKGROUND_JOBS -> R.string.startup_issue_title_background
    StartupStep.HEADLESS_INTERACTIONS -> R.string.startup_issue_title_confirmations
    StartupStep.SCHEDULED_TASKS -> R.string.startup_issue_title_schedules
    StartupStep.MODEL_CATALOG -> R.string.startup_issue_title_catalog
    StartupStep.MEMORY_CLEANUP -> R.string.startup_issue_title_memory
}

/**
 * 技术原因里去掉开头的异常类名（如 `WorkspaceMutationRecoveryException: `），只留可读的说明；
 * 完整原文保留在诊断记录里。没有可读部分时原样返回。
 */
internal fun startupIssueReason(summary: String): String {
    val match = STARTUP_REASON_PREFIX.find(summary) ?: return summary.trim()
    return summary.substring(match.range.last + 1).trim().ifEmpty { summary.trim() }
}

private val STARTUP_REASON_PREFIX = Regex("""^\s*[A-Za-z_$][\w.$]*(Exception|Error)\s*:\s*""")

/** 会话列表顶部：有启动问题时照常进入应用，只提示受影响的范围。 */
@Composable
fun StartupIssuesBanner(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    StartupNotice(
        title = pluralStringResource(R.plurals.startup_issues_banner_title, count, count),
        detail = stringResource(R.string.startup_issues_banner_detail),
        actionLabel = stringResource(R.string.startup_issues_view),
        icon = Icons.Rounded.WarningAmber,
        onClick = onOpen,
        modifier = modifier.testTag(StartupIssuesTags.BANNER),
    )
}

/** 会话列表顶部：Linux 环境不可用时提示受影响的 Linux 会话数，不阻止进入应用。 */
@Composable
fun LinuxUnavailableBanner(count: Int, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    StartupNotice(
        title = stringResource(R.string.run_mode_startup_banner_title),
        detail = pluralStringResource(R.plurals.run_mode_startup_banner_detail, count, count),
        actionLabel = stringResource(R.string.run_mode_startup_action),
        icon = Icons.Rounded.WarningAmber,
        onClick = onOpen,
        modifier = modifier.testTag(StartupIssuesTags.LINUX_BANNER),
    )
}

/** 会话顶栏下方：该会话或其工作区暂停写入。 */
@Composable
fun StartupWritePausedBanner(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    StartupNotice(
        title = stringResource(R.string.startup_issues_session_paused_title),
        detail = stringResource(R.string.startup_issues_session_paused_detail),
        actionLabel = stringResource(R.string.startup_issues_handle),
        icon = Icons.Rounded.Lock,
        onClick = onOpen,
        modifier = modifier.testTag(StartupIssuesTags.SESSION_BANNER),
    )
}

/** 内嵌于列表留白中的提醒卡片：浅色警示底、圆形图标徽标、标题与说明两层，整卡可点按进入处理。 */
@Composable
internal fun StartupNotice(
    title: String,
    detail: String?,
    actionLabel: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
    titleMaxLines: Int = 1,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.XSmall)
            .clip(shape)
            .background(colors.tertiary.copy(alpha = 0.10f).compositeOver(colors.surfaceContainer))
            .border(1.dp, colors.tertiary.copy(alpha = 0.22f), shape)
            .clickable(role = Role.Button, onClickLabel = actionLabel, onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(start = InfinitySpacing.Medium, end = InfinitySpacing.Small, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        StartupIconBadge(icon, size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = InfinityTypography.labelLarge, color = colors.onSurface, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (detail != null) {
                Text(detail, style = InfinityTypography.bodySmall, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun StartupIconBadge(icon: ImageVector, size: Dp) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(size).background(colors.tertiary.copy(alpha = 0.16f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = colors.tertiary, modifier = Modifier.size(size * 0.56f))
    }
}

private fun StartupScopeKind.icon(): ImageVector = when (this) {
    StartupScopeKind.WORKSPACE_ROOT -> Icons.Rounded.FolderOpen
    StartupScopeKind.SESSION -> Icons.Rounded.ChatBubbleOutline
    StartupScopeKind.SUBSYSTEM -> Icons.Rounded.Memory
    StartupScopeKind.GLOBAL -> Icons.Rounded.Apps
}

/**
 * 启动问题面板：逐条列出问题与可用处置，底部是“让 Agent 诊断”、诊断记录与导出。
 * [busyIssueId] 非空时该条处置进行中，其余按钮照常可用；[messageIssueId] 指向的问题在条目内显示处置结果，
 * 否则 [message] 显示在列表下方。
 */
@Composable
fun StartupIssuesSheetContent(
    rows: List<StartupIssueRow>,
    busyIssueId: String?,
    message: String?,
    canDiagnose: Boolean,
    onAction: (StartupIssue, StartupIssueAction) -> Unit,
    onDiagnose: () -> Unit,
    onViewRecords: () -> Unit,
    onExport: () -> Unit,
    messageIssueId: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            // 水平留白由面板外壳给出。
            .padding(bottom = InfinitySpacing.Small)
            .testTag(StartupIssuesTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            StartupIconBadge(Icons.Rounded.WarningAmber, size = 40.dp)
            Text(
                stringResource(R.string.startup_issues_title),
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
        }
        Text(stringResource(R.string.startup_issues_intro), style = InfinityTypography.bodyMedium, color = colors.onSurfaceVariant)
        if (rows.isEmpty()) {
            Text(stringResource(R.string.startup_issues_none), style = InfinityTypography.bodyMedium, color = colors.onSurfaceVariant)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(colors.surfaceContainerHigh),
            ) {
                rows.forEachIndexed { index, row ->
                    if (index > 0) HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(start = InfinitySpacing.Large))
                    StartupIssueItem(
                        row = row,
                        busy = busyIssueId == row.issue.id,
                        message = message?.takeIf { messageIssueId == row.issue.id },
                        onAction = onAction,
                    )
                }
            }
        }
        if (message != null && rows.none { it.issue.id == messageIssueId }) {
            Text(message, style = InfinityTypography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (rows.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
                Button(
                    onClick = onDiagnose,
                    enabled = canDiagnose,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(StartupIssuesTags.DIAGNOSE),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.startup_issues_diagnose), modifier = Modifier.padding(start = InfinitySpacing.Small))
                }
                if (!canDiagnose) {
                    Text(stringResource(R.string.startup_issues_diagnose_unavailable), style = InfinityTypography.bodySmall, color = colors.onSurfaceVariant)
                }
            }
        }
        InfinityPillRow(modifier = Modifier.fillMaxWidth(), reverseWhenStacked = false) {
            InfinityPillButton(
                text = stringResource(R.string.startup_issues_records),
                onClick = onViewRecords,
                icon = Icons.AutoMirrored.Rounded.Article,
                modifier = Modifier.testTag(StartupIssuesTags.RECORDS),
            )
            InfinityPillButton(
                text = stringResource(R.string.startup_issues_export),
                onClick = onExport,
                icon = Icons.Rounded.Download,
                modifier = Modifier.testTag(StartupIssuesTags.EXPORT),
            )
        }
    }
}

@Composable
private fun StartupIssueItem(
    row: StartupIssueRow,
    busy: Boolean,
    message: String?,
    onAction: (StartupIssue, StartupIssueAction) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val issue = row.issue
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = InfinitySpacing.Large, end = InfinitySpacing.Small, top = InfinitySpacing.Medium, bottom = InfinitySpacing.XSmall)
            .testTag(StartupIssuesTags.issue(issue.id)),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
    ) {
        Text(
            stringResource(issue.step.titleRes()),
            style = MaterialTheme.typography.titleSmall,
            color = colors.onSurface,
            modifier = Modifier.padding(end = InfinitySpacing.Small),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(issue.scope.kind.icon(), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(row.scopeName, style = InfinityTypography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            startupIssueReason(issue.summary),
            style = InfinityTypography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(end = InfinitySpacing.Small),
        )
        if (message != null) {
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 2.dp, end = InfinitySpacing.Small),
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(14.dp).padding(top = 1.dp))
                Text(message, style = InfinityTypography.bodySmall, color = colors.error)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(end = InfinitySpacing.Medium).size(20.dp), strokeWidth = 2.dp)
            } else {
                if (StartupIssueAction.ABANDON in issue.actions) {
                    InfinityPillButton(
                        text = stringResource(R.string.startup_issues_abandon_short),
                        onClick = { onAction(issue, StartupIssueAction.ABANDON) },
                        tone = InfinityPillTone.DANGER,
                        compact = true,
                        modifier = Modifier.testTag(StartupIssuesTags.action(issue.id, StartupIssueAction.ABANDON)),
                    )
                }
                if (StartupIssueAction.RETRY in issue.actions) {
                    InfinityPillButton(
                        text = stringResource(R.string.startup_issues_retry),
                        onClick = { onAction(issue, StartupIssueAction.RETRY) },
                        tone = InfinityPillTone.PRIMARY,
                        icon = Icons.Rounded.Refresh,
                        compact = true,
                        modifier = Modifier.testTag(StartupIssuesTags.action(issue.id, StartupIssueAction.RETRY)),
                    )
                }
            }
        }
    }
}

/**
 * 有状态的启动问题面板：处置、放弃确认、诊断会话、诊断记录与导出都在这里完成。
 * [diagnosisAgentId] 给出诊断会话使用的助手；问题属于某个会话时优先用该会话的助手。
 */
@Composable
fun StartupIssuesSheet(
    onDismiss: () -> Unit,
    diagnosisAgentId: (List<StartupIssue>) -> String?,
    onOpenSession: (String) -> Unit,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val issues by app.startupIssues.collectAsState()
    val scope = rememberCoroutineScope()
    var busyIssueId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIssueId by remember { mutableStateOf<String?>(null) }
    var confirmAbandon by remember { mutableStateOf<StartupIssue?>(null) }
    var records by remember { mutableStateOf<String?>(null) }
    val resolvedText = stringResource(R.string.startup_issues_resolved)
    val failedText = stringResource(R.string.startup_issues_failed)
    val exportedText = stringResource(R.string.startup_issues_exported)
    val exportFailedText = stringResource(R.string.startup_issues_export_failed)
    val noRecordsText = stringResource(R.string.startup_issues_no_records)
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            messageIssueId = null
            message = runCatching {
                checkNotNull(app.contentResolver.openOutputStream(uri)).use { app.exportStartupDiagnostics(it) }
            }.fold({ exportedText }, { exportFailedText })
        }
    }
    fun resolve(issue: StartupIssue, action: StartupIssueAction, confirmed: Boolean) {
        busyIssueId = issue.id
        scope.launch {
            val result = runCatching { app.resolveStartupIssue(issue.id, action, confirmed) }
                .getOrElse { StartupIssueResolution.Failed(failedText) }
            busyIssueId = null
            messageIssueId = issue.id
            when (result) {
                StartupIssueResolution.ConfirmationRequired -> confirmAbandon = issue
                StartupIssueResolution.Resolved, StartupIssueResolution.NotFound -> message = resolvedText
                StartupIssueResolution.UnsupportedAction -> message = failedText
                // 条目里的原因已随处置结果更新，这里只给结论，不重复原因。
                is StartupIssueResolution.Failed -> message = failedText
            }
        }
    }
    InfinityBottomSheet(show = true, onDismiss = onDismiss, height = InfinitySheetHeight.Content) {
        val agentId = diagnosisAgentId(issues)
        StartupIssuesSheetContent(
            rows = issues.map { StartupIssueRow(it, app.startupIssueManager.scopeDisplayName(it.scope)) },
            busyIssueId = busyIssueId,
            message = message,
            messageIssueId = messageIssueId,
            canDiagnose = agentId != null,
            onAction = { issue, action ->
                if (action == StartupIssueAction.ABANDON) confirmAbandon = issue else resolve(issue, action, confirmed = false)
            },
            onDiagnose = {
                val id = agentId ?: return@StartupIssuesSheetContent
                scope.launch {
                    runCatching { app.createDiagnosticSession(issues.map { it.id }, id) }
                        .onSuccess { session -> onDismiss(); onOpenSession(session.id) }
                        .onFailure { messageIssueId = null; message = failedText }
                }
            },
            onViewRecords = { scope.launch { records = AppDiagnostics.recent().ifBlank { noRecordsText } } },
            onExport = { exportLauncher.launch("infinity-diagnostics.txt") },
        )
    }
    confirmAbandon?.let { issue ->
        InfinityConfirmDialog(
            onDismissRequest = { confirmAbandon = null },
            title = stringResource(R.string.startup_issues_abandon_title),
            message = stringResource(R.string.startup_issues_abandon_message),
            confirmText = stringResource(R.string.startup_issues_abandon),
            onConfirm = { confirmAbandon = null; resolve(issue, StartupIssueAction.ABANDON, confirmed = true) },
            onCancel = { confirmAbandon = null },
            isDestructive = true,
        )
    }
    records?.let { text -> DiagnosticRecordsDialog(text, onDismiss = { records = null }) }
}

/** 脱敏诊断记录全文，可选择复制。 */
@Composable
fun DiagnosticRecordsDialog(text: String, onDismiss: () -> Unit) = com.promenar.infinity.ui.common.MonospaceTextDialog(
    title = stringResource(R.string.startup_issues_records),
    message = stringResource(R.string.startup_issues_records_note),
    text = text,
    onDismiss = onDismiss,
)
