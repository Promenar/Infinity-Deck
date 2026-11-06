package com.promenar.infinity.ui.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.WorkHistory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.background.schedule.canScheduleExact
import com.promenar.infinity.background.schedule.exactAlarmPermissionIntent
import com.promenar.infinity.distribution.DistributionCapabilities
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.schedule.ScheduledTask
import com.promenar.infinity.ui.chat.components.BackgroundJobRow
import com.promenar.infinity.ui.chat.components.ScheduledTaskRow
import com.promenar.infinity.ui.common.ChannelLimitChip
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** 影响后台任务与定时任务能否按时运行的条件；渠道能力来自构建配置，其余在页面恢复时重新读取。 */
internal data class BackgroundRunConditions(
    val keepAliveAvailable: Boolean,
    val exactAlarmAvailable: Boolean,
    val exactAlarmGranted: Boolean,
    val batteryUnrestricted: Boolean,
    val notificationsAllowed: Boolean,
)

private fun readConditions(context: Context): BackgroundRunConditions = BackgroundRunConditions(
    keepAliveAvailable = DistributionCapabilities.backgroundKeepAliveAvailable,
    exactAlarmAvailable = DistributionCapabilities.exactAlarmAvailable,
    exactAlarmGranted = canScheduleExact(context),
    batteryUnrestricted = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
    notificationsAllowed = NotificationManagerCompat.from(context).areNotificationsEnabled(),
)

internal class BackgroundActivitySettingsActions(
    val onGrantExact: () -> Unit = {},
    val onOpenBattery: () -> Unit = {},
    val onOpenNotifications: () -> Unit = {},
    val onStopJob: (String) -> Unit = {},
    val onToggleSchedule: (String, Boolean) -> Unit = { _, _ -> },
    val onDeleteSchedule: (String) -> Unit = {},
    val onOpenSession: (String) -> Unit = {},
)

@Composable
fun BackgroundActivitySettingsScreen(onBack: () -> Unit, onOpenSession: (String) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as InfinityApplication
    var conditions by remember { mutableStateOf(readConditions(context)) }
    LifecycleResumeEffect(Unit) {
        conditions = readConditions(context)
        onPauseOrDispose { }
    }
    val jobs by remember { app.backgroundJobController.observe(null) }.collectAsState(initial = emptyList())
    val schedules by remember { app.scheduledTaskController.observe(null) }.collectAsState(initial = emptyList())
    val titles by remember {
        app.database.sessionDao().observeAll().map { sessions -> sessions.associate { it.id to it.title } }
    }.collectAsState(initial = emptyMap())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }
    val scope = rememberCoroutineScope()
    fun open(intent: Intent?) {
        intent ?: return
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
    InfinitySettingsPageLayout(title = stringResource(R.string.settings_background_title), onBack = onBack) { padding ->
        BackgroundActivitySettingsContent(
            conditions = conditions,
            jobs = jobs,
            schedules = schedules,
            sessionTitles = titles,
            nowMillis = now,
            actions = BackgroundActivitySettingsActions(
                onGrantExact = { open(exactAlarmPermissionIntent(context)) },
                onOpenBattery = { open(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) },
                onOpenNotifications = {
                    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                },
                onStopJob = { id -> scope.launch { app.backgroundJobController.stop(id) } },
                onToggleSchedule = { id, enabled ->
                    scope.launch { if (enabled) app.scheduledTaskController.resume(id) else app.scheduledTaskController.pause(id) }
                },
                onDeleteSchedule = { id -> scope.launch { app.scheduledTaskController.delete(id) } },
                onOpenSession = onOpenSession,
            ),
            modifier = Modifier.padding(padding),
        )
    }
}

/** 运行条件在上，跨会话的后台任务与定时任务在下；渠道差异只用行尾限制标签表达。 */
@Composable
internal fun BackgroundActivitySettingsContent(
    conditions: BackgroundRunConditions,
    jobs: List<BackgroundJob>,
    schedules: List<ScheduledTask>,
    sessionTitles: Map<String, String>,
    nowMillis: Long,
    actions: BackgroundActivitySettingsActions,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<ScheduledTask?>(null) }
    val untitled = stringResource(R.string.settings_background_untitled_session)
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 16.dp).testTag(BACKGROUND_SETTINGS_TAG),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        InfinitySettingsSection(stringResource(R.string.settings_background_conditions)) {
            // 渠道能力不可更改，只读展示；Play 版用行尾限制标签说明差异。
            InfinitySettingsRowLayout(
                icon = Icons.Rounded.WorkHistory,
                title = stringResource(R.string.settings_background_keep_alive),
                subtitle = stringResource(
                    if (conditions.keepAliveAvailable) R.string.settings_background_keep_alive_on else R.string.settings_background_keep_alive_foreground,
                ),
                subtitleMaxLines = 2,
                trailingContent = if (conditions.keepAliveAvailable) null else {
                    {
                        ChannelLimitChip(
                            label = stringResource(R.string.agent_jobs_limit_label),
                            title = stringResource(R.string.agent_jobs_limit_title),
                            body = null,
                            icon = Icons.Rounded.Visibility,
                        )
                    }
                },
            )
            if (conditions.exactAlarmAvailable) {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Alarm,
                    title = stringResource(R.string.settings_background_exact),
                    subtitle = stringResource(
                        if (conditions.exactAlarmGranted) R.string.settings_background_exact_granted else R.string.settings_background_exact_denied,
                    ),
                    onClick = actions.onGrantExact,
                    showChevron = true,
                    subtitleMaxLines = 2,
                )
            } else {
                InfinitySettingsRowLayout(
                    icon = Icons.Rounded.Alarm,
                    title = stringResource(R.string.settings_background_exact),
                    subtitle = stringResource(R.string.settings_background_exact_system),
                    subtitleMaxLines = 2,
                    trailingContent = {
                        ChannelLimitChip(
                            label = stringResource(R.string.agent_schedules_limit_label),
                            title = stringResource(R.string.agent_schedules_limit_title),
                            body = stringResource(R.string.agent_schedules_limit_body),
                            icon = Icons.Rounded.Schedule,
                        )
                    },
                )
            }
            InfinitySettingsItem(
                icon = Icons.Rounded.BatterySaver,
                title = stringResource(R.string.settings_background_battery),
                subtitle = stringResource(
                    if (conditions.batteryUnrestricted) R.string.settings_background_battery_unrestricted else R.string.settings_background_battery_restricted,
                ),
                onClick = actions.onOpenBattery,
                showChevron = true,
                subtitleMaxLines = 2,
            )
            InfinitySettingsItem(
                icon = Icons.Rounded.Notifications,
                title = stringResource(R.string.settings_background_notifications),
                subtitle = stringResource(
                    if (conditions.notificationsAllowed) R.string.settings_background_notifications_on else R.string.settings_background_notifications_off,
                ),
                onClick = actions.onOpenNotifications,
                showChevron = true,
                subtitleMaxLines = 2,
            )
        }
        InfinitySettingsSection(
            title = stringResource(R.string.agent_jobs_title),
            footer = stringResource(R.string.settings_background_jobs_footer),
        ) {
            if (jobs.isEmpty()) EmptyRow(stringResource(R.string.settings_background_jobs_empty))
            // 列表各项等高：按全部任务算出是否有运行中任务、运行中传输。
            val listHasRunning = jobs.any { !it.state.isTerminal }
            val listHasTransfer = jobs.any { !it.state.isTerminal && it.transfer != null }
            jobs.forEach { job ->
                BackgroundJobRow(
                    job = job,
                    nowMillis = nowMillis,
                    expanded = false,
                    onToggle = { actions.onOpenSession(job.sessionId) },
                    onStop = { actions.onStopJob(job.id) },
                    onOpenLog = null,
                    liveOutput = {},
                    sessionTitle = sessionTitles[job.sessionId]?.takeIf(String::isNotBlank) ?: untitled,
                    listHasRunning = listHasRunning,
                    listHasTransfer = listHasTransfer,
                )
            }
        }
        InfinitySettingsSection(
            title = stringResource(R.string.agent_schedules_title),
            footer = stringResource(R.string.settings_background_schedules_footer),
        ) {
            if (schedules.isEmpty()) EmptyRow(stringResource(R.string.settings_background_schedules_empty))
            // 精确闹钟授权由本页上方的“精确闹钟”设置项提供，任务行保持等高。
            schedules.forEach { task ->
                ScheduledTaskRow(
                    task = task,
                    nowMillis = nowMillis,
                    onToggle = { actions.onToggleSchedule(task.id, it) },
                    onDelete = { deleting = task },
                    sessionTitle = sessionTitles[task.sessionId]?.takeIf(String::isNotBlank) ?: untitled,
                )
            }
        }
    }
    deleting?.let { task ->
        InfinityConfirmDialog(
            onDismissRequest = { deleting = null },
            title = stringResource(R.string.agent_schedules_delete_title),
            message = stringResource(R.string.agent_schedules_delete_body, task.name),
            confirmText = stringResource(R.string.agent_schedules_delete_confirm),
            cancelText = stringResource(android.R.string.cancel),
            onConfirm = { deleting = null; actions.onDeleteSchedule(task.id) },
            onCancel = { deleting = null },
            isDestructive = true,
        )
    }
}

@Composable
private fun EmptyRow(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Medium),
    )
}

internal const val BACKGROUND_SETTINGS_TAG = "background_activity_settings"
