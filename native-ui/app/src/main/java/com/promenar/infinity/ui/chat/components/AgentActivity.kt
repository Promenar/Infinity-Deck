package com.promenar.infinity.ui.chat.components

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.rounded.AlarmOff
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import android.text.format.Formatter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.domain.jobs.SftpTransfer
import com.promenar.infinity.domain.jobs.SftpTransferDirection
import com.promenar.infinity.domain.jobs.SftpTransferFailure
import com.promenar.infinity.domain.schedule.ScheduleRunResult
import com.promenar.infinity.domain.schedule.ScheduleTrigger
import com.promenar.infinity.domain.schedule.ScheduledTask
import com.promenar.infinity.ui.common.ChannelLimitChip
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.animation.AnimatedVisibility
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.common.InfinityMetaLine

// ─────────────────────────────── 后台任务面板 ───────────────────────────────

/**
 * 本会话的后台任务。Play 版在标题旁用一枚标签说明“仅前台运行”，不在列表里重复解释；
 * [liveOutput] 由宿主按任务的实时输出键渲染终端区。
 */
@Composable
internal fun BackgroundJobsContent(
    jobs: List<BackgroundJob>,
    nowMillis: Long,
    keepAliveAvailable: Boolean,
    onStop: (String) -> Unit,
    onOpenLog: (String) -> Unit,
    modifier: Modifier = Modifier,
    liveOutput: @Composable (BackgroundJob) -> Unit = {},
) {
    val groups = remember(jobs) { backgroundJobGroups(jobs) }
    var expandedId by rememberSaveable { mutableStateOf(groups.running.firstOrNull()?.id) }
    val toggle = { id: String -> expandedId = if (expandedId == id) null else id }
    LazyColumn(
        modifier = modifier.fillMaxWidth().testTag(UiTags.BACKGROUND_JOBS_SHEET),
        contentPadding = PaddingValues(start = InfinitySpacing.Medium, end = InfinitySpacing.Medium, bottom = InfinitySpacing.Large),
    ) {
        if (!keepAliveAvailable) {
            item(key = "limit") {
                Row(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small), horizontalArrangement = Arrangement.End) {
                    ChannelLimitChip(
                        label = stringResource(R.string.agent_jobs_limit_label),
                        title = stringResource(R.string.agent_jobs_limit_title),
                        body = null,
                        icon = Icons.Rounded.Visibility,
                    )
                }
            }
        }
        if (jobs.isEmpty()) {
            item(key = "empty") { EmptyHint(Icons.Rounded.Terminal, stringResource(R.string.agent_jobs_empty)) }
        }
        if (groups.running.isNotEmpty()) {
            item(key = "running_label") { JobGroupLabel(stringResource(R.string.agent_jobs_group_running, groups.running.size)) }
            items(groups.running, key = { it.id }) { job ->
                RunningJobCard(
                    job = job,
                    nowMillis = nowMillis,
                    expanded = expandedId == job.id,
                    onToggle = { toggle(job.id) },
                    onStop = { onStop(job.id) },
                    onOpenLog = job.logWorkspacePath?.let { path -> { onOpenLog(path) } },
                    liveOutput = { liveOutput(job) },
                    modifier = Modifier.padding(bottom = InfinitySpacing.Small),
                )
            }
        }
        if (groups.finished.isNotEmpty()) {
            item(key = "finished_label") { JobGroupLabel(stringResource(R.string.agent_jobs_group_finished, groups.finished.size)) }
            item(key = "finished") {
                Surface(shape = JobGroupShape, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Column {
                        groups.finished.forEachIndexed { index, job ->
                            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            FinishedJobRow(
                                job = job,
                                nowMillis = nowMillis,
                                expanded = expandedId == job.id,
                                onToggle = { toggle(job.id) },
                                onOpenLog = job.logWorkspacePath?.let { path -> { onOpenLog(path) } },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobGroupLabel(text: String) {
    Text(
        text,
        style = InfinityTypography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = InfinitySpacing.XSmall, top = InfinitySpacing.Medium, bottom = InfinitySpacing.Small),
    )
}

/** 运行中的任务：命令、执行环境与已运行时长，右侧停止；展开时下面是终端风格的实时输出与查看日志。 */
@Composable
private fun RunningJobCard(
    job: BackgroundJob,
    nowMillis: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onOpenLog: (() -> Unit)?,
    liveOutput: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = JobGroupShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .clickable(role = Role.Button, onClick = onToggle)
                .padding(start = InfinitySpacing.Medium, end = InfinitySpacing.Small, top = InfinitySpacing.Small, bottom = InfinitySpacing.Medium)
                .testTag(UiTags.backgroundJob(job.id)),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(InfinitySpacing.Medium))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    JobLabel(job, showCommand = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        SmallTag(jobRunnerLabel(job), MaterialTheme.colorScheme.surfaceContainerHighest)
                        Text(
                            jobStateText(job, nowMillis),
                            style = InfinityTypography.labelSmall.copy(fontFeatureSettings = "tnum"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    job.transfer?.let { SftpTransferProgress(it, Modifier.padding(top = 2.dp)) }
                }
                OutlinedIconButton(
                    onClick = onStop,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.testTag(UiTags.backgroundJobStop(job.id)),
                ) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.agent_jobs_stop), modifier = Modifier.size(18.dp))
                }
            }
            AnimatedVisibility(visible = expanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                Column(Modifier.padding(top = InfinitySpacing.Small, end = InfinitySpacing.XSmall)) {
                    liveOutput()
                    if (onOpenLog != null) {
                        InfinityPillButton(
                            text = stringResource(R.string.agent_jobs_open_log),
                            onClick = onOpenLog,
                            icon = Icons.Rounded.FolderOpen,
                            modifier = Modifier.align(Alignment.End),
                        )
                    }
                }
            }
        }
    }
}

/** 已结束的任务：状态图标、命令与用时；非零退出码换成红色“退出 N”标签，展开后给出完整说明与查看日志。 */
@Composable
private fun FinishedJobRow(
    job: BackgroundJob,
    nowMillis: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpenLog: (() -> Unit)?,
) {
    val failedExit = job.state == BackgroundJobState.EXITED && job.exitCode != null && job.exitCode != 0
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = InfinitySpacing.Medium, end = InfinitySpacing.Medium, top = 10.dp, bottom = 10.dp)
            .testTag(UiTags.backgroundJob(job.id)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JobStateIcon(job, Modifier.size(18.dp))
            Spacer(Modifier.width(InfinitySpacing.Medium))
            JobLabel(job, Modifier.weight(1f))
            Spacer(Modifier.width(InfinitySpacing.Small))
            val transferFailure = job.transfer?.failure
            if (transferFailure != null) {
                SmallTag(
                    sftpFailureLabel(transferFailure),
                    MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else if (failedExit) {
                SmallTag(
                    stringResource(R.string.agent_jobs_exit_badge, job.exitCode ?: -1),
                    MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
            } else {
                Text(
                    durationText(compactDuration((job.endedAtMillis ?: nowMillis) - job.startedAtMillis)),
                    style = InfinityTypography.labelSmall.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        AnimatedVisibility(visible = expanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            Column(Modifier.padding(start = 18.dp + InfinitySpacing.Medium, top = InfinitySpacing.XSmall)) {
                InfinityMetaLine(jobMetaSegments(job, nowMillis), style = InfinityTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onOpenLog != null) {
                    InfinityPillButton(
                        text = stringResource(R.string.agent_jobs_open_log),
                        onClick = onOpenLog,
                        icon = Icons.Rounded.FolderOpen,
                    )
                }
            }
        }
    }
}

private val JobGroupShape = RoundedCornerShape(14.dp)

/**
 * 后台任务行标题区的最小高度（不含行上下 8dp 内边距）。列表各项等高：
 * 已结束 = 任务名 bodyMedium 一行 + 说明 labelSmall 一行（默认 20 + 16 = 36dp）；
 * 有运行中任务时不低于停止按钮的 48dp 触控高度；
 * 有运行中传输时再加 4dp 间距 + 4dp 进度条 + 4dp 间距 + 进度说明 labelSmall 一行（默认 64dp）。行高随字号实算。
 */
@Composable
private fun backgroundJobHeaderMinHeight(running: Boolean, transfer: Boolean): Dp = with(LocalDensity.current) {
    var height = InfinityTypography.bodyMedium.lineHeight.toDp() + InfinityTypography.labelSmall.lineHeight.toDp()
    if (transfer) height += InfinitySpacing.XSmall * 3 + 4.dp + InfinityTypography.labelSmall.lineHeight.toDp()
    if (running) height = maxOf(height, InfinitySpacing.MinimumTouchTarget)
    height
}

/**
 * [sessionTitle] 仅在跨会话列表中给出，追加在说明行末尾。
 * [listHasRunning]、[listHasTransfer] 由调用方按同列表全部任务给出：列表各项等高，
 * 有运行中任务（带停止按钮）或运行中传输（多一行进度）时，其余行的标题区也按同一高度排版。
 */
@Composable
internal fun BackgroundJobRow(
    job: BackgroundJob,
    nowMillis: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onOpenLog: (() -> Unit)?,
    liveOutput: @Composable () -> Unit,
    sessionTitle: String? = null,
    listHasRunning: Boolean = false,
    listHasTransfer: Boolean = false,
) {
    val running = !job.state.isTerminal
    val headerMinHeight = backgroundJobHeaderMinHeight(
        running = running || listHasRunning,
        transfer = (running && job.transfer != null) || listHasTransfer,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(start = InfinitySpacing.Large, end = InfinitySpacing.Small, top = InfinitySpacing.Small, bottom = InfinitySpacing.Small)
            .testTag(UiTags.backgroundJob(job.id)),
    ) {
        Row(Modifier.heightIn(min = headerMinHeight), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (running) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                else JobStateIcon(job, Modifier.size(18.dp))
            }
            Spacer(Modifier.width(InfinitySpacing.Medium))
            Column(Modifier.weight(1f)) {
                // 列表各项等高：任务名与进度说明各限一行。
                JobLabel(job, maxLines = 1)
                Text(
                    text = (jobMetaSegments(job, nowMillis) + listOfNotNull(sessionTitle)).joinToString(" · "),  // wrap-check: allow 单行显示
                    style = InfinityTypography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                if (running) job.transfer?.let { SftpTransferProgress(it, Modifier.padding(top = InfinitySpacing.XSmall), metaMaxLines = 1) }
            }
            if (running) {
                FilledTonalIconButton(onClick = onStop, modifier = Modifier.testTag(UiTags.backgroundJobStop(job.id))) {
                    Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.agent_jobs_stop), modifier = Modifier.size(18.dp))
                }
            }
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 24.dp + InfinitySpacing.Medium, top = InfinitySpacing.Small, end = InfinitySpacing.Small),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
            ) {
                if (running) liveOutput()
                if (onOpenLog != null) {
                    InfinityPillButton(
                        text = stringResource(R.string.agent_jobs_open_log),
                        onClick = onOpenLog,
                        icon = Icons.Rounded.FolderOpen,
                    )
                }
            }
        }
    }
}

@Composable
private fun JobStateIcon(job: BackgroundJob, modifier: Modifier) {
    val ok = job.state == BackgroundJobState.EXITED && job.exitCode == 0
    val (icon, tint) = when {
        ok -> Icons.Rounded.CheckCircle to MaterialTheme.colorScheme.primary
        job.state == BackgroundJobState.STOPPED || job.state == BackgroundJobState.INTERRUPTED ->
            Icons.Rounded.PauseCircle to MaterialTheme.colorScheme.onSurfaceVariant
        job.state == BackgroundJobState.TIMED_OUT -> Icons.Rounded.HourglassBottom to MaterialTheme.colorScheme.error
        job.state == BackgroundJobState.RUNNING -> Icons.Rounded.Terminal to MaterialTheme.colorScheme.onSurfaceVariant
        else -> Icons.Rounded.ErrorOutline to MaterialTheme.colorScheme.error
    }
    Icon(icon, contentDescription = null, tint = tint, modifier = modifier)
}

@Composable
private fun jobMetaSegments(job: BackgroundJob, nowMillis: Long): List<String> =
    listOfNotNull(jobRunnerName(job), job.remoteHost, jobStateText(job, nowMillis)) + transferMetaSegments(job)

/** 已结束的传输：方向、本地范围、大小、中断原因或校验值前 12 位；运行中的进度由 [SftpTransferProgress] 显示。 */
@Composable
private fun transferMetaSegments(job: BackgroundJob): List<String> {
    val transfer = job.transfer ?: return emptyList()
    if (!job.state.isTerminal) return emptyList()
    val context = LocalContext.current
    return listOfNotNull(
        sftpDirectionLabel(transfer.direction),
        sftpScopeLabel(transfer.localScope),
        (transfer.totalBytes ?: transfer.transferredBytes).let { Formatter.formatShortFileSize(context, it) },
        transfer.failure?.let { sftpFailureLabel(it) },
        transfer.sha256?.takeIf { transfer.failure == null }?.let { stringResource(R.string.sftp_transfer_verified, it.take(12)) },
    )
}

/** 运行中的传输：确定进度条（总大小未知时为不定进度）加一行已传 / 总量、速度与剩余时间。 */
@Composable
private fun SftpTransferProgress(transfer: SftpTransfer, modifier: Modifier = Modifier, metaMaxLines: Int = Int.MAX_VALUE) {
    val context = LocalContext.current
    val fraction = transfer.progressFraction()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
        if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        else LinearProgressIndicator(Modifier.fillMaxWidth())
        val done = Formatter.formatShortFileSize(context, transfer.transferredBytes)
        InfinityMetaLine(
            listOfNotNull(
                sftpDirectionLabel(transfer.direction),
                transfer.totalBytes?.let { stringResource(R.string.sftp_transfer_bytes_of, done, Formatter.formatShortFileSize(context, it)) } ?: done,
                transfer.bytesPerSecond?.takeIf { it > 0 }?.let { stringResource(R.string.sftp_transfer_speed, Formatter.formatShortFileSize(context, it)) },
                transfer.remainingMillis()?.let { stringResource(R.string.sftp_transfer_remaining, durationText(compactDuration(it))) },
            ),
            style = InfinityTypography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = metaMaxLines,
        )
    }
}

@Composable
private fun sftpDirectionLabel(direction: SftpTransferDirection): String = stringResource(
    when (direction) {
        SftpTransferDirection.UPLOAD -> R.string.sftp_transfer_upload
        SftpTransferDirection.DOWNLOAD -> R.string.sftp_transfer_download
    },
)

@Composable
private fun sftpScopeLabel(scope: String): String? = when (scope) {
    "workspace" -> stringResource(R.string.sftp_transfer_scope_workspace)
    "linux" -> stringResource(R.string.sftp_transfer_scope_linux)
    else -> null
}

@Composable
private fun sftpFailureLabel(failure: SftpTransferFailure): String = stringResource(
    when (failure) {
        SftpTransferFailure.NETWORK -> R.string.sftp_transfer_failure_network
        SftpTransferFailure.TIME_LIMIT -> R.string.sftp_transfer_failure_time_limit
        SftpTransferFailure.STOPPED -> R.string.sftp_transfer_failure_stopped
        SftpTransferFailure.CHECKSUM_MISMATCH -> R.string.sftp_transfer_failure_checksum
        SftpTransferFailure.TARGET_CONFLICT -> R.string.sftp_transfer_failure_conflict
        SftpTransferFailure.NO_SPACE -> R.string.sftp_transfer_failure_no_space
        SftpTransferFailure.OTHER -> R.string.sftp_transfer_failure_other
    },
)

/** 运行环境标签；SSH 任务后接主机 ID，与本地任务在同一列表里区分。 */
@Composable
private fun jobRunnerLabel(job: BackgroundJob): String =
    listOfNotNull(jobRunnerName(job), job.remoteHost).joinToString(" · ")  // wrap-check: allow 单行小标签

@Composable
private fun jobRunnerName(job: BackgroundJob): String = stringResource(
    when (job.runner) {
        BackgroundJobRunner.SHELL -> R.string.agent_jobs_runner_shell
        BackgroundJobRunner.LINUX -> R.string.linux_runner_name
        BackgroundJobRunner.PYTHON -> R.string.agent_jobs_runner_python
        BackgroundJobRunner.TERMUX -> R.string.agent_jobs_runner_termux
        BackgroundJobRunner.SSH -> R.string.ssh_remote_runner
    },
)

@Composable
private fun jobStateText(job: BackgroundJob, nowMillis: Long): String {
    val duration = durationText(compactDuration((job.endedAtMillis ?: nowMillis) - job.startedAtMillis))
    return when (job.state) {
        BackgroundJobState.RUNNING -> stringResource(R.string.agent_jobs_state_running, duration)
        BackgroundJobState.EXITED -> stringResource(R.string.agent_jobs_state_exited, job.exitCode ?: -1, duration)
        BackgroundJobState.STOPPED -> stringResource(R.string.agent_jobs_state_stopped, duration)
        BackgroundJobState.TIMED_OUT -> stringResource(R.string.agent_jobs_state_timed_out, duration)
        BackgroundJobState.INTERRUPTED -> stringResource(R.string.agent_jobs_state_interrupted)
        BackgroundJobState.FAILED -> stringResource(R.string.agent_jobs_state_failed)
    }
}

@Composable
private fun durationText(duration: CompactDuration): String = when {
    duration.hours > 0 -> stringResource(R.string.agent_duration_hm, duration.hours, duration.minutes)
    duration.minutes > 0 -> stringResource(R.string.agent_duration_ms, duration.minutes, duration.seconds)
    else -> stringResource(R.string.agent_duration_s, duration.seconds)
}

// ─────────────────────────────── 定时任务面板 ───────────────────────────────

/**
 * 本会话的定时任务：名称、触发规则、下次触发与最近结果，开关暂停，删除前确认。
 * [exactAlarmAvailable] 为 false（Play）时标题旁显示“系统择时”标签；侧载版缺少精确闹钟授权且有任务
 * 请求精确触发时，标题旁显示一枚可点的授权标签（授权属于整个应用，不放进单个条目）。
 */
@Composable
internal fun ScheduledTasksContent(
    tasks: List<ScheduledTask>,
    nowMillis: Long,
    exactAlarmAvailable: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onGrantExact: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    val grantExact = onGrantExact?.takeIf { exactAlarmAvailable && tasks.any { it.exactRequested && !it.exactEffective } }
    LazyColumn(modifier = modifier.fillMaxWidth().testTag(UiTags.SCHEDULED_TASKS_SHEET)) {
        item(key = "header") {
            SheetHeader(title = stringResource(R.string.agent_schedules_title)) {
                if (grantExact != null) GrantExactChip(grantExact)
                if (!exactAlarmAvailable) {
                    ChannelLimitChip(
                        label = stringResource(R.string.agent_schedules_limit_label),
                        title = stringResource(R.string.agent_schedules_limit_title),
                        body = stringResource(R.string.agent_schedules_limit_body),
                        icon = Icons.Rounded.Schedule,
                    )
                }
            }
        }
        if (tasks.isEmpty()) {
            item(key = "empty") { EmptyHint(Icons.Rounded.Schedule, stringResource(R.string.agent_schedules_empty)) }
        }
        items(tasks, key = { it.id }) { task ->
            ScheduledTaskRow(
                task = task,
                nowMillis = nowMillis,
                onToggle = { onToggle(task.id, it) },
                onDelete = { deleting = task },
            )
        }
    }
    deleting?.let { task ->
        InfinityConfirmDialog(
            onDismissRequest = { deleting = null },
            title = stringResource(R.string.agent_schedules_delete_title),
            message = stringResource(R.string.agent_schedules_delete_body, task.name),
            confirmText = stringResource(R.string.agent_schedules_delete_confirm),
            cancelText = stringResource(android.R.string.cancel),
            onConfirm = { deleting = null; onDelete(task.id) },
            onCancel = { deleting = null },
            isDestructive = true,
        )
    }
}

/** 定时任务行：三行文字固定行高，列表各项等高；精确闹钟授权属于整个应用，由列表标题或设置项提供。 */
@Composable
internal fun ScheduledTaskRow(
    task: ScheduledTask,
    nowMillis: Long,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    sessionTitle: String? = null,
) {
    val stateText = stringResource(if (task.enabled) R.string.agent_schedules_enabled else R.string.agent_schedules_paused)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = scheduledTaskRowMinHeight())
            .padding(start = InfinitySpacing.Large, end = InfinitySpacing.Small, top = InfinitySpacing.Small, bottom = InfinitySpacing.Small)
            .testTag(UiTags.scheduledTask(task.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(task.name, style = InfinityTypography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = listOfNotNull(triggerText(task.trigger), sessionTitle).joinToString(" · "),  // wrap-check: allow 单行省略
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = scheduleStatus(task, nowMillis),
                    style = InfinityTypography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (task.exactEffective) SmallTag(stringResource(R.string.agent_schedules_exact), MaterialTheme.colorScheme.secondaryContainer)
            }
        }
        Switch(
            checked = task.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier
                .semantics { stateDescription = stateText }
                .testTag(UiTags.scheduledTaskToggle(task.id)),
        )
        IconButton(onClick = onDelete, modifier = Modifier.testTag(UiTags.scheduledTaskDelete(task.id))) {
            Icon(
                Icons.Outlined.DeleteOutline,
                contentDescription = stringResource(R.string.agent_schedules_delete_title),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 定时任务行最小高度。列表各项等高：上下内边距 8dp×2 + 名称 titleSmall + 2dp + 触发 labelMedium + 2dp
 * + 状态行（取“精确”标签高：labelSmall + 上下 2dp），默认 16 + 20 + 2 + 16 + 2 + 20 = 76dp。行高随字号实算。
 */
@Composable
private fun scheduledTaskRowMinHeight(): Dp = with(LocalDensity.current) {
    InfinitySpacing.Small * 2 + InfinityTypography.titleSmall.lineHeight.toDp() + 2.dp +
        InfinityTypography.labelMedium.lineHeight.toDp() + 2.dp + InfinityTypography.labelSmall.lineHeight.toDp() + 4.dp
}

/** 精确闹钟授权标签：放在定时任务列表标题旁，点按打开系统授权页；可点区域不小于最小触控尺寸。 */
@Composable
private fun GrantExactChip(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AlarmOff, contentDescription = null, modifier = Modifier.size(12.dp))
            Text(stringResource(R.string.agent_schedules_grant_exact), style = InfinityTypography.labelSmall, maxLines = 1,
                modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
internal fun triggerText(trigger: ScheduleTrigger): String = when (trigger) {
    is ScheduleTrigger.Once -> {
        val at = runCatching { LocalDateTime.parse(trigger.localDateTime) }.getOrNull()
        stringResource(
            R.string.agent_schedules_trigger_once,
            at?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)) ?: trigger.localDateTime,
        )
    }
    is ScheduleTrigger.Interval -> when (val unit = intervalUnit(trigger.everyMinutes)) {
        is IntervalUnit.Minutes -> pluralStringResource(R.plurals.agent_schedules_every_minutes, unit.value, unit.value)
        is IntervalUnit.Hours -> pluralStringResource(R.plurals.agent_schedules_every_hours, unit.value, unit.value)
        is IntervalUnit.Days -> pluralStringResource(R.plurals.agent_schedules_every_days, unit.value, unit.value)
    }
    is ScheduleTrigger.Cron -> stringResource(R.string.agent_schedules_trigger_cron, trigger.expression)
}

@Composable
private fun scheduleStatus(task: ScheduledTask, nowMillis: Long): String {
    val next = task.nextFireAtMillis?.takeIf { task.enabled }?.let {
        if (it <= nowMillis) stringResource(R.string.agent_schedules_due)
        else stringResource(R.string.agent_schedules_next, fireTimeLabel(it, nowMillis))
    }
    val last = task.lastResult?.let {
        stringResource(
            when (it) {
                ScheduleRunResult.COMPLETED -> R.string.agent_schedules_last_completed
                ScheduleRunResult.NEEDS_USER -> R.string.agent_schedules_last_needs_user
                ScheduleRunResult.SKIPPED_BUSY -> R.string.agent_schedules_last_skipped_busy
                ScheduleRunResult.SKIPPED_OTHER -> R.string.agent_schedules_last_skipped
                ScheduleRunResult.FAILED -> R.string.agent_schedules_last_failed
            },
        )
    }
    val runs = task.maxRuns?.let { stringResource(R.string.agent_schedules_runs_of, task.runCount, it) }
    return listOfNotNull(next ?: if (!task.enabled) stringResource(R.string.agent_schedules_paused) else null, last, runs).joinToString(" · ")  // wrap-check: allow 调用处为单行省略
}

@Composable
internal fun fireTimeLabel(fireAtMillis: Long, nowMillis: Long): String {
    val zoned = Instant.ofEpochMilli(fireAtMillis).atZone(ZoneId.systemDefault())
    val time = zoned.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    return when (fireDay(fireAtMillis, nowMillis)) {
        FireDay.TODAY -> time
        FireDay.TOMORROW -> stringResource(R.string.agent_schedules_tomorrow, time)
        FireDay.LATER -> zoned.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
    }
}

// ─────────────────────────────── 共用片段 ───────────────────────────────

@Composable
private fun SheetHeader(title: String, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Text(title, style = InfinityTypography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f, fill = false))
        trailing()
    }
}

@Composable
private fun EmptyHint(icon: ImageVector, text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.XLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        Text(text, style = InfinityTypography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SmallTag(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    contentColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Surface(shape = CircleShape, color = color, contentColor = contentColor) {
        Text(text, style = InfinityTypography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

/**
 * 任务名：有短标题时显示标题，[showCommand] 为 true 时在下方用小号等宽字补一行命令原文；
 * 没有标题时从左往右显示命令原文，最多两行，不按规则猜“主命令”。
 */
@Composable
private fun JobLabel(job: BackgroundJob, modifier: Modifier = Modifier, showCommand: Boolean = false, maxLines: Int = 2) {
    val title = job.title?.takeIf(String::isNotBlank)
    Column(modifier) {
        Text(
            text = title ?: job.summary,
            style = if (title != null) InfinityTypography.bodyMedium else InfinityTypography.bodyMedium.copy(fontFamily = InfinityMonospace),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
        )
        if (title != null && showCommand) {
            Text(
                text = job.summary,
                style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
