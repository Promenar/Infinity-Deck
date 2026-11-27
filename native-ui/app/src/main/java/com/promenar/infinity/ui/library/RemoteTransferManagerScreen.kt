package com.promenar.infinity.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.domain.storage.RemoteTransferController
import com.promenar.infinity.domain.storage.RemoteTransferDirection
import com.promenar.infinity.domain.storage.RemoteTransferState
import com.promenar.infinity.domain.storage.RemoteTransferSummary
import com.promenar.infinity.domain.storage.RemoteTransferTask
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import com.promenar.infinity.ui.files.formatFileSize
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.breakableIdentifier
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.launch

/** 传输管理页面的状态筛选维度。 */
internal enum class RemoteTransferFilter {
    ALL,
    ACTIVE,
    PAUSED,
    COMPLETED,
    FAILED,
}

internal fun RemoteTransferState.isActiveTransfer(): Boolean = this in setOf(
    RemoteTransferState.QUEUED,
    RemoteTransferState.PREFLIGHT,
    RemoteTransferState.TRANSFERRING,
    RemoteTransferState.VERIFYING,
    RemoteTransferState.COMMITTING,
    RemoteTransferState.PAUSING,
    RemoteTransferState.CANCELLING,
    RemoteTransferState.RECONCILING,
)

internal fun RemoteTransferState.isFinishedTransfer(): Boolean = this in setOf(
    RemoteTransferState.SUCCEEDED,
    RemoteTransferState.FAILED,
    RemoteTransferState.CANCELLED,
)

internal fun filterRemoteTransfers(
    tasks: List<RemoteTransferTask>,
    filter: RemoteTransferFilter,
): List<RemoteTransferTask> = when (filter) {
    RemoteTransferFilter.ALL -> tasks
    RemoteTransferFilter.ACTIVE -> tasks.filter { it.state.isActiveTransfer() }
    RemoteTransferFilter.PAUSED -> tasks.filter { it.state == RemoteTransferState.PAUSED }
    RemoteTransferFilter.COMPLETED -> tasks.filter { it.state == RemoteTransferState.SUCCEEDED }
    RemoteTransferFilter.FAILED -> tasks.filter { it.state == RemoteTransferState.FAILED || it.state == RemoteTransferState.CANCELLED }
}

internal data class RemoteTransferFilterCounts(
    val all: Int,
    val active: Int,
    val paused: Int,
    val completed: Int,
    val failed: Int,
)

internal fun computeRemoteTransferFilterCounts(tasks: List<RemoteTransferTask>): RemoteTransferFilterCounts =
    RemoteTransferFilterCounts(
        all = tasks.size,
        active = tasks.count { it.state.isActiveTransfer() },
        paused = tasks.count { it.state == RemoteTransferState.PAUSED },
        completed = tasks.count { it.state == RemoteTransferState.SUCCEEDED },
        failed = tasks.count { it.state == RemoteTransferState.FAILED || it.state == RemoteTransferState.CANCELLED },
    )

internal fun transferProgressFraction(bytesTransferred: Long, bytesTotal: Long): Float? {
    if (bytesTotal <= 0L) return null
    return (bytesTransferred.toDouble() / bytesTotal.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal fun formatTransferSpeed(bytesPerSecond: Long): String? =
    if (bytesPerSecond <= 0L) null else "${formatFileSize(bytesPerSecond)}/s"

internal fun shortSha256(sha: String): String =
    if (sha.length <= 16) sha else "${sha.take(8)}…${sha.takeLast(4)}"

/** 资料顶栏与远程浏览器顶栏共用的传输管理圆形图标按钮（含活跃数量角标）。 */
@Composable
internal fun RemoteTransferHeaderButton(
    activeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val label = stringResource(R.string.sftp_transfers_title)
    Box(
        modifier = modifier
            .size(InfinitySpacing.MinimumTouchTarget)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag("library_header_transfers"),
        contentAlignment = Alignment.Center,
    ) {
        BadgedBox(
            badge = {
                if (activeCount > 0) {
                    Badge(containerColor = scheme.primary, contentColor = scheme.onPrimary) {
                        Text(activeCount.toString())
                    }
                }
            },
        ) {
            Surface(
                shape = CircleShape,
                color = scheme.surfaceContainerHigh,
                modifier = Modifier.size(34.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Rounded.SwapVert,
                        contentDescription = null,
                        tint = scheme.onSurface,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/** 资料库内嵌的专用传输管理页面（折叠屏双栏右列 & 外屏单栏整页共用）。 */
@Composable
internal fun LibraryTransfersPage(
    leading: LibraryLeading,
    crumbs: LibraryCrumbs?,
    controller: RemoteTransferController? = null,
) {
    val app = LocalContext.current.applicationContext as? InfinityApplication
    val transferController = controller ?: app?.remoteTransferManager ?: return
    val tasks by transferController.tasks.collectAsStateWithLifecycle()
    val summary by transferController.summary.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val noticeController = LocalInfinityNoticeController.current
    val shaCopiedText = stringResource(R.string.sftp_transfers_sha_copied)

    LibraryPageFrame(
        title = stringResource(R.string.sftp_transfers_title),
        leading = leading,
        crumbs = crumbs,
        modifier = Modifier.testTag(LibraryTags.page("transfers")),
        meta = {
            val segments = transferHeaderMetaSegments(summary)
            if (segments.isNotEmpty()) {
                InfinityMetaLine(
                    segments = segments,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        },
        actions = {
            when {
                summary.activeCount > 0 -> InfinityPillButton(
                    text = stringResource(R.string.sftp_transfers_pause_all),
                    icon = Icons.Rounded.Pause,
                    onClick = { scope.launch { transferController.pauseAll() } },
                    tone = InfinityPillTone.NEUTRAL,
                    compact = true,
                    modifier = Modifier.padding(end = InfinitySpacing.Small).testTag("transfers_pause_all"),
                )
                summary.pausedCount > 0 -> InfinityPillButton(
                    text = stringResource(R.string.sftp_transfers_resume_all),
                    icon = Icons.Rounded.PlayArrow,
                    onClick = { scope.launch { transferController.resumeAll() } },
                    tone = InfinityPillTone.PRIMARY,
                    compact = true,
                    modifier = Modifier.padding(end = InfinitySpacing.Small).testTag("transfers_resume_all"),
                )
            }
        },
    ) { padding ->
        RemoteTransferManagerList(
            tasks = tasks,
            contentPadding = padding,
            onPause = { id -> scope.launch { transferController.pause(id) } },
            onResume = { id -> scope.launch { transferController.resume(id) } },
            onCancel = { id -> scope.launch { transferController.cancel(id) } },
            onRetry = { id -> scope.launch { transferController.retry(id) } },
            onRemove = { id -> scope.launch { transferController.remove(id) } },
            onCopySha = { sha ->
                clipboard.setText(AnnotatedString(sha))
                noticeController?.show(InfinityNoticeSeverity.SUCCESS, shaCopiedText)
            },
            onClearFinished = { scope.launch { transferController.clearFinished() } },
        )
    }
}

@Composable
private fun transferHeaderMetaSegments(summary: RemoteTransferSummary): List<String> = buildList {
    if (summary.activeCount > 0) add(stringResource(R.string.library_transfers_active_summary, summary.activeCount))
    if (summary.pausedCount > 0) add(stringResource(R.string.library_transfers_paused_summary, summary.pausedCount))
    formatTransferSpeed(summary.aggregateBytesPerSecond)?.let { add(it) }
    if (isEmpty()) add(stringResource(R.string.library_transfers_idle_summary))
}

@Composable
internal fun RemoteTransferManagerList(
    tasks: List<RemoteTransferTask>,
    contentPadding: PaddingValues,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRetry: (String) -> Unit,
    onRemove: (String) -> Unit,
    onCopySha: (String) -> Unit,
    onClearFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selectedFilter by rememberSaveable { mutableStateOf(RemoteTransferFilter.ALL) }
    val counts = remember(tasks) { computeRemoteTransferFilterCounts(tasks) }
    val visible = remember(tasks, selectedFilter) { filterRemoteTransfers(tasks, selectedFilter) }
    val hasFinished = counts.completed > 0 || counts.failed > 0

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("remote_transfer_list"),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        item(key = "filters") {
            com.promenar.infinity.ui.common.InfinityTagScrollRow(spacing = 8.dp) {
                TransferFilterChipPill(
                    text = stringResource(R.string.sftp_transfers_filter_all, counts.all),
                    selected = selectedFilter == RemoteTransferFilter.ALL,
                    onClick = { selectedFilter = RemoteTransferFilter.ALL },
                )
                TransferFilterChipPill(
                    text = stringResource(R.string.sftp_transfers_filter_active, counts.active),
                    selected = selectedFilter == RemoteTransferFilter.ACTIVE,
                    onClick = { selectedFilter = RemoteTransferFilter.ACTIVE },
                )
                TransferFilterChipPill(
                    text = stringResource(R.string.sftp_transfers_filter_paused, counts.paused),
                    selected = selectedFilter == RemoteTransferFilter.PAUSED,
                    onClick = { selectedFilter = RemoteTransferFilter.PAUSED },
                )
                TransferFilterChipPill(
                    text = stringResource(R.string.sftp_transfers_filter_completed, counts.completed),
                    selected = selectedFilter == RemoteTransferFilter.COMPLETED,
                    onClick = { selectedFilter = RemoteTransferFilter.COMPLETED },
                )
                TransferFilterChipPill(
                    text = stringResource(R.string.sftp_transfers_filter_failed, counts.failed),
                    selected = selectedFilter == RemoteTransferFilter.FAILED,
                    onClick = { selectedFilter = RemoteTransferFilter.FAILED },
                )
            }
        }
        if (visible.isEmpty()) {
            item(key = "empty") {
                LibraryEmptyText(
                    stringResource(
                        if (tasks.isEmpty()) R.string.sftp_transfers_empty else R.string.sftp_transfers_empty_filter,
                    ),
                )
            }
        }
        items(visible, key = { it.id }) { task ->
            RemoteTransferTaskCard(
                task = task,
                onPause = { onPause(task.id) },
                onResume = { onResume(task.id) },
                onCancel = { onCancel(task.id) },
                onRetry = { onRetry(task.id) },
                onRemove = { onRemove(task.id) },
                onCopySha = { task.receipt?.sha256?.let(onCopySha) },
                modifier = Modifier.animateItem(),
            )
        }
        if (hasFinished) {
            item(key = "clear-finished") {
                Box(Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Small)) {
                    InfinityPillButton(
                        text = stringResource(R.string.sftp_transfers_action_clear_finished),
                        icon = Icons.Rounded.CleaningServices,
                        onClick = onClearFinished,
                        tone = InfinityPillTone.NEUTRAL,
                        modifier = Modifier.fillMaxWidth().testTag("transfers_clear_finished"),
                    )
                }
            }
        }
    }
}

@Composable
private fun TransferFilterChipPill(text: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = if (selected) scheme.primary else scheme.surfaceContainerHigh,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RemoteTransferTaskCard(
    task: RemoteTransferTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onCopySha: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors

    val (statusLabel, statusTone) = transferStatusPresentation(task)
    val directionIcon = when {
        task.state == RemoteTransferState.SUCCEEDED -> Icons.Rounded.CheckCircle
        task.state == RemoteTransferState.FAILED -> Icons.Rounded.ErrorOutline
        task.state == RemoteTransferState.VERIFYING -> Icons.Rounded.VerifiedUser
        task.direction == RemoteTransferDirection.UPLOAD -> Icons.Rounded.CloudUpload
        else -> Icons.Rounded.CloudDownload
    }
    val (iconBg, iconTint) = when (statusTone) {
        ProductionTone.SUCCESS -> domain.successContainer to domain.onSuccessContainer
        ProductionTone.WARNING -> domain.warningContainer to domain.onWarningContainer
        ProductionTone.ERROR -> scheme.errorContainer to scheme.onErrorContainer
        ProductionTone.INFO -> domain.infoContainer to domain.onInfoContainer
        ProductionTone.NEUTRAL -> scheme.surfaceContainerHigh to scheme.onSurfaceVariant
    }
    val route = remember(task.sourceLabel, task.targetLabel, task.hostAlias, task.remoteFullPath) {
        val left = task.sourceLabel.ifBlank { task.mountVirtualPath }
        val right = task.targetLabel.ifBlank { "${task.hostAlias}:${task.remoteFullPath}" }
        "$left → $right"
    }
    val progress = transferProgressFraction(task.bytesTransferred, task.bytesTotal)
    val showProgressBar = task.state != RemoteTransferState.SUCCEEDED && task.state != RemoteTransferState.CANCELLED
    val progressColor = when (statusTone) {
        ProductionTone.ERROR -> scheme.error
        ProductionTone.WARNING -> domain.warning
        else -> scheme.primary
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth().testTag("transfer_card_${task.id}"),
    ) {
        Column(
            modifier = Modifier.padding(InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(directionIcon, contentDescription = null, tint = iconTint, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = task.fileName,
                            style = MaterialTheme.typography.titleSmall,
                            color = scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        ProductionStatusChip(statusLabel, statusTone)
                    }
                    Text(
                        text = route.breakableIdentifier(),
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (showProgressBar) {
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = progressColor,
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                    )
                } else if (task.state.isActiveTransfer()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
                        color = progressColor,
                        strokeCap = StrokeCap.Round,
                    )
                }
            }

            InfinityMetaLine(
                segments = transferDetailSegments(task),
                style = MaterialTheme.typography.labelSmall,
                color = if (task.state == RemoteTransferState.FAILED) scheme.error else scheme.onSurfaceVariant,
                maxLines = 3,
            )

            TransferTaskActions(
                task = task,
                onPause = onPause,
                onResume = onResume,
                onCancel = onCancel,
                onRetry = onRetry,
                onRemove = onRemove,
                onCopySha = onCopySha,
            )
        }
    }
}

@Composable
private fun transferStatusPresentation(task: RemoteTransferTask): Pair<String, ProductionTone> = when (task.state) {
    RemoteTransferState.QUEUED -> stringResource(R.string.sftp_transfers_state_queued) to ProductionTone.NEUTRAL
    RemoteTransferState.PREFLIGHT -> stringResource(R.string.sftp_transfers_state_preflight) to ProductionTone.INFO
    RemoteTransferState.TRANSFERRING -> when (task.direction) {
        RemoteTransferDirection.UPLOAD -> stringResource(R.string.sftp_transfers_state_uploading) to ProductionTone.INFO
        RemoteTransferDirection.DOWNLOAD -> stringResource(R.string.sftp_transfers_state_downloading) to ProductionTone.INFO
        RemoteTransferDirection.PREVIEW_CACHE -> stringResource(R.string.sftp_transfers_state_caching) to ProductionTone.INFO
    }
    RemoteTransferState.VERIFYING -> stringResource(R.string.sftp_transfers_state_verifying) to ProductionTone.INFO
    RemoteTransferState.COMMITTING -> stringResource(R.string.sftp_transfers_state_committing) to ProductionTone.INFO
    RemoteTransferState.PAUSING -> stringResource(R.string.sftp_transfers_state_pausing) to ProductionTone.WARNING
    RemoteTransferState.PAUSED -> stringResource(R.string.sftp_transfers_state_paused) to ProductionTone.WARNING
    RemoteTransferState.CANCELLING -> stringResource(R.string.sftp_transfers_state_cancelling) to ProductionTone.WARNING
    RemoteTransferState.CANCELLED -> stringResource(R.string.sftp_transfers_state_cancelled) to ProductionTone.NEUTRAL
    RemoteTransferState.SUCCEEDED -> when (task.direction) {
        RemoteTransferDirection.UPLOAD -> stringResource(R.string.sftp_transfers_state_succeeded_archive) to ProductionTone.SUCCESS
        RemoteTransferDirection.DOWNLOAD -> stringResource(R.string.sftp_transfers_state_succeeded_download) to ProductionTone.SUCCESS
        RemoteTransferDirection.PREVIEW_CACHE -> stringResource(R.string.sftp_transfers_state_succeeded_cache) to ProductionTone.SUCCESS
    }
    RemoteTransferState.FAILED -> stringResource(R.string.sftp_transfers_state_failed) to ProductionTone.ERROR
    RemoteTransferState.RECONCILING -> stringResource(R.string.sftp_transfers_state_reconciling) to ProductionTone.INFO
}

@Composable
private fun transferDetailSegments(task: RemoteTransferTask): List<String> {
    val pct = transferProgressFraction(task.bytesTransferred, task.bytesTotal)?.let { "${(it * 100).toInt()}%" }
    val sizePart = if (task.bytesTotal > 0L) {
        "${formatFileSize(task.bytesTransferred)} / ${formatFileSize(task.bytesTotal)}" + (pct?.let { " ($it)" } ?: "")
    } else null
    val speedPart = formatTransferSpeed(task.bytesPerSecond)
    val etaPart = task.etaSeconds?.takeIf { it > 0L }?.let { sec ->
        if (sec < 60L) stringResource(R.string.sftp_transfers_eta_seconds, sec.toInt())
        else stringResource(R.string.sftp_transfers_eta_minutes, (sec / 60L).toInt(), (sec % 60L).toInt())
    }
    return when (task.state) {
        RemoteTransferState.VERIFYING -> listOfNotNull(
            sizePart,
            stringResource(R.string.sftp_transfers_verifying_desc),
        )
        RemoteTransferState.PAUSED -> listOfNotNull(
            stringResource(R.string.sftp_transfers_paused_resumable),
            sizePart,
        )
        RemoteTransferState.SUCCEEDED -> {
            val receipt = task.receipt
            listOfNotNull(
                formatFileSize(receipt?.sizeBytes ?: task.bytesTotal),
                receipt?.verificationMethod,
                receipt?.sha256?.let(::shortSha256),
            )
        }
        RemoteTransferState.FAILED -> listOfNotNull(
            task.errorMessage ?: task.errorCode,
            sizePart,
        )
        else -> listOfNotNull(sizePart, speedPart, etaPart)
            .ifEmpty { listOf(stringResource(R.string.sftp_transfers_state_queued)) }
    }
}

@Composable
private fun TransferTaskActions(
    task: RemoteTransferTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onCopySha: () -> Unit,
) {
    when (task.state) {
        RemoteTransferState.QUEUED,
        RemoteTransferState.PREFLIGHT,
        RemoteTransferState.TRANSFERRING,
        RemoteTransferState.RECONCILING,
        -> InfinityPillRow(reverseWhenStacked = false) {
            InfinityPillButton(
                text = stringResource(R.string.sftp_transfers_action_pause),
                icon = Icons.Rounded.Pause,
                onClick = onPause,
                tone = InfinityPillTone.NEUTRAL,
                compact = true,
            )
            InfinityPillButton(
                text = stringResource(R.string.sftp_transfers_action_cancel),
                icon = Icons.Rounded.Close,
                onClick = onCancel,
                tone = InfinityPillTone.DANGER,
                compact = true,
            )
        }

        RemoteTransferState.VERIFYING,
        RemoteTransferState.COMMITTING,
        -> InfinityPillRow(reverseWhenStacked = false) {
            InfinityPillButton(
                text = stringResource(R.string.sftp_transfers_action_cancel),
                icon = Icons.Rounded.Close,
                onClick = onCancel,
                tone = InfinityPillTone.DANGER,
                compact = true,
            )
        }

        RemoteTransferState.PAUSED -> InfinityPillRow(reverseWhenStacked = true) {
            InfinityPillButton(
                text = stringResource(R.string.sftp_transfers_action_cancel),
                icon = Icons.Rounded.Close,
                onClick = onCancel,
                tone = InfinityPillTone.DANGER,
                compact = true,
            )
            InfinityPillButton(
                text = stringResource(R.string.sftp_transfers_action_resume),
                icon = Icons.Rounded.PlayArrow,
                onClick = onResume,
                tone = InfinityPillTone.PRIMARY,
                compact = true,
            )
        }

        RemoteTransferState.SUCCEEDED -> InfinityPillRow(reverseWhenStacked = false) {
            if (task.receipt != null) {
                InfinityPillButton(
                    text = stringResource(R.string.sftp_transfers_action_copy_sha),
                    icon = Icons.Rounded.ContentCopy,
                    onClick = onCopySha,
                    tone = InfinityPillTone.NEUTRAL,
                    compact = true,
                )
            }
            InfinityPillButton(
                text = stringResource(R.string.sftp_transfers_action_remove),
                icon = Icons.Rounded.DeleteOutline,
                onClick = onRemove,
                tone = InfinityPillTone.NEUTRAL,
                compact = true,
            )
        }

        RemoteTransferState.FAILED -> InfinityPillRow(reverseWhenStacked = true) {
            InfinityPillButton(
                text = stringResource(R.string.sftp_transfers_action_abandon),
                icon = Icons.Rounded.DeleteOutline,
                onClick = onRemove,
                tone = InfinityPillTone.DANGER,
                compact = true,
            )
            if (task.canResume) {
                InfinityPillButton(
                    text = stringResource(R.string.sftp_transfers_action_retry),
                    icon = Icons.Rounded.Refresh,
                    onClick = onRetry,
                    tone = InfinityPillTone.PRIMARY,
                    compact = true,
                )
            }
        }

        RemoteTransferState.CANCELLED -> InfinityPillRow(reverseWhenStacked = false) {
            InfinityPillButton(
                text = stringResource(R.string.sftp_transfers_action_remove),
                icon = Icons.Rounded.DeleteOutline,
                onClick = onRemove,
                tone = InfinityPillTone.NEUTRAL,
                compact = true,
            )
        }

        RemoteTransferState.PAUSING,
        RemoteTransferState.CANCELLING,
        -> Unit
    }
}
