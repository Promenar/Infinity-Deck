package com.promenar.infinity.ui.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.promenar.infinity.R
import com.promenar.infinity.domain.jobs.BackgroundJobController
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.ui.chat.copyToClipboard
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityShapes
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG_JOB_LOG_DIALOG = "job_log_dialog"
private const val TAG_JOB_LOG_TEXT = "job_log_text"
private const val TAG_JOB_LOG_RETRY = "job_log_retry"
private const val TAG_JOB_LOG_CANCEL = "job_log_cancel"
private const val TAG_JOB_LOG_COPY = "job_log_copy"
private const val TAG_JOB_LOG_CLOSE = "job_log_close"

/**
 * 后台任务日志：按任务引用读取应用私有日志末尾，不扫描工作区。
 * 任务运行中且对话框可见（宿主处于前台）时每 [JOB_LOG_REFRESH_INTERVAL_MS] 刷新一次，关闭即停止；
 * 读取失败给出重试，首次读取过慢时给出取消，不会一直停在“正在读取”。
 */
@Composable
fun BackgroundJobLogDialog(
    controller: BackgroundJobController,
    sessionId: String,
    reference: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val jobs by remember(controller, sessionId) { controller.observe(sessionId) }.collectAsState(initial = null)
    val job = jobs?.firstOrNull { it.logWorkspacePath == reference }
    val running = job?.state == BackgroundJobState.RUNNING
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    var readState by remember(sessionId, reference) { mutableStateOf(JobLogReadState()) }
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000L)
        }
    }
    // 取消或失败会改变键并终止当前读取；重试清掉标记后循环重新开始。
    LaunchedEffect(sessionId, reference, running, visible, readState.cancelled, readState.failed) {
        if (!visible || readState.cancelled || readState.failed) return@LaunchedEffect
        while (true) {
            readState = readState.started(System.currentTimeMillis())
            readState = try {
                val log = withContext(Dispatchers.IO) { controller.readLog(sessionId, reference) }
                readState.succeeded(log, System.currentTimeMillis())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                readState.failedRead()
            }
            val next = jobLogRefreshDelayMillis(running, readState) ?: break
            delay(next)
        }
    }

    val phase = resolveJobLogPhase(readState, nowMillis)
    val name = jobLogDisplayName(job)
    val logText = readState.log?.text.orEmpty()
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, InfinityShapes.extraLarge)
                .testTag(TAG_JOB_LOG_DIALOG),
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = InfinityShapes.extraLarge,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.XLarge),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                ) {
                    Text(
                        text = name?.let { stringResource(R.string.job_log_title_named, it) }
                            ?: stringResource(R.string.agent_jobs_log_title),
                        style = InfinityTypography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    resolveJobLogStatus(job)?.let { JobLogStatusPill(it) }
                }
                JobLogMetaLine(readState, nowMillis)
                JobLogBody(
                    phase = phase,
                    text = logText,
                    refreshFailed = phase == JobLogPhase.Content && readState.failed,
                    onRetry = { readState = readState.retried() },
                    onCancel = { readState = readState.cancelledRead() },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small, Alignment.End),
                ) {
                    OutlinedButton(
                        onClick = { copyToClipboard(context, logText) },
                        enabled = logText.isNotEmpty(),
                        modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(TAG_JOB_LOG_COPY),
                    ) { Text(stringResource(R.string.shared_btn_copy), style = InfinityTypography.labelMedium) }
                    Button(
                        onClick = onDismiss,
                        shape = InfinityShapes.medium,
                        modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(TAG_JOB_LOG_CLOSE),
                    ) { Text(stringResource(R.string.common_dismiss), style = InfinityTypography.labelMedium) }
                }
            }
        }
    }
}

@Composable
private fun JobLogStatusPill(status: JobLogStatus) {
    val scheme = MaterialTheme.colorScheme
    val success = MaterialTheme.infinityDomainColors
    val (label, container, content) = when (status) {
        JobLogStatus.Live -> Triple(stringResource(R.string.job_log_status_live), success.successContainer, success.onSuccessContainer)
        is JobLogStatus.Exited -> Triple(
            status.exitCode?.let { stringResource(R.string.job_log_status_exited_code, it) }
                ?: stringResource(R.string.job_log_status_finished),
            if (status.isFailure) scheme.errorContainer else scheme.surfaceContainerHighest,
            if (status.isFailure) scheme.onErrorContainer else scheme.onSurfaceVariant,
        )
        JobLogStatus.Stopped -> Triple(stringResource(R.string.job_log_status_stopped), scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        JobLogStatus.TimedOut -> Triple(stringResource(R.string.job_log_status_timed_out), scheme.errorContainer, scheme.onErrorContainer)
        JobLogStatus.Interrupted -> Triple(stringResource(R.string.job_log_status_interrupted), scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
        JobLogStatus.Failed -> Triple(stringResource(R.string.job_log_status_failed), scheme.errorContainer, scheme.onErrorContainer)
    }
    Text(
        text = label,
        style = InfinityTypography.labelSmall,
        color = content,
        maxLines = 1,
        modifier = Modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun JobLogMetaLine(state: JobLogReadState, nowMillis: Long) {
    val log = state.log ?: return
    val scope = if (log.truncated) {
        stringResource(R.string.job_log_tail_limit, JOB_LOG_TAIL_CHARS)
    } else {
        stringResource(R.string.job_log_full_output)
    }
    val updated = when (val seconds = jobLogSecondsSinceUpdate(state, nowMillis)) {
        null -> null
        0L -> stringResource(R.string.job_log_updated_just_now)
        else -> pluralStringResource(R.plurals.job_log_updated_seconds_ago, seconds.toIntClamped(), seconds)
    }
    InfinityMetaLine(
        segments = listOfNotNull(scope, updated),
        style = InfinityTypography.bodySmall.copy(fontFeatureSettings = "tnum"),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private fun Long.toIntClamped(): Int = coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

@Composable
private fun JobLogBody(
    phase: JobLogPhase,
    text: String,
    refreshFailed: Boolean,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    val shape = RoundedCornerShape(InfinitySpacing.Small)
    val areaModifier = Modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surfaceContainerLowest, shape)
    when (phase) {
        JobLogPhase.Content -> Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall)) {
            JobLogText(text = text.ifEmpty { stringResource(R.string.agent_jobs_log_empty) }, modifier = areaModifier)
            if (refreshFailed) {
                JobLogStatusRow(
                    message = stringResource(R.string.job_log_refresh_failed),
                    error = true,
                    actionLabel = stringResource(R.string.shared_btn_retry),
                    actionTag = TAG_JOB_LOG_RETRY,
                    onAction = onRetry,
                )
            }
        }
        JobLogPhase.Loading -> Box(areaModifier.heightIn(min = 96.dp).padding(InfinitySpacing.Medium), contentAlignment = Alignment.CenterStart) {
            JobLogStatusRow(message = stringResource(R.string.agent_jobs_log_loading), progress = true)
        }
        JobLogPhase.SlowLoading -> Box(areaModifier.heightIn(min = 96.dp).padding(InfinitySpacing.Medium), contentAlignment = Alignment.CenterStart) {
            JobLogStatusRow(
                message = stringResource(R.string.job_log_reading_slow),
                progress = true,
                actionLabel = stringResource(R.string.common_btn_cancel),
                actionTag = TAG_JOB_LOG_CANCEL,
                onAction = onCancel,
            )
        }
        JobLogPhase.Failed, JobLogPhase.Cancelled -> Box(areaModifier.heightIn(min = 96.dp).padding(InfinitySpacing.Medium), contentAlignment = Alignment.CenterStart) {
            JobLogStatusRow(
                message = stringResource(if (phase == JobLogPhase.Failed) R.string.agent_jobs_log_failed else R.string.job_log_read_cancelled),
                error = phase == JobLogPhase.Failed,
                actionLabel = stringResource(R.string.shared_btn_retry),
                actionTag = TAG_JOB_LOG_RETRY,
                onAction = onRetry,
            )
        }
    }
}

/** 等宽日志区：对话框外层不滚动，这里是唯一的纵向滚动容器；停在末尾时新输出到达自动贴底。 */
@Composable
private fun JobLogText(text: String, modifier: Modifier) {
    val scrollState = rememberScrollState()
    val slackPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    var followTail by remember { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.value }.collect { value ->
            if (scrollState.isScrollInProgress) followTail = shouldFollowLogTail(value, scrollState.maxValue, slackPx)
        }
    }
    LaunchedEffect(scrollState) {
        snapshotFlow { scrollState.maxValue }.collect { max -> if (followTail) scrollState.scrollTo(max) }
    }
    SelectionContainer {
        Text(
            text = text,
            style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
                .heightIn(min = 96.dp, max = 360.dp)
                .verticalScroll(scrollState)
                .padding(InfinitySpacing.Medium)
                .testTag(TAG_JOB_LOG_TEXT),
        )
    }
}

@Composable
private fun JobLogStatusRow(
    message: String,
    error: Boolean = false,
    progress: Boolean = false,
    actionLabel: String? = null,
    actionTag: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { liveRegion = if (error) LiveRegionMode.Assertive else LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        if (progress) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            text = message,
            style = InfinityTypography.bodySmall,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                modifier = Modifier
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .then(if (actionTag != null) Modifier.testTag(actionTag) else Modifier),
            ) { Text(actionLabel) }
        }
    }
}
