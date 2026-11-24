package com.promenar.infinity.ui.chat.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobController
import com.promenar.infinity.domain.schedule.ScheduledTask
import com.promenar.infinity.domain.schedule.ScheduledTaskController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** 聊天页的后台活动入口；由 ChatRoute 按当前会话提供，没有提供时不显示任何内容。 */
internal class AgentActivityAccess(
    val sessionId: String,
    val jobs: BackgroundJobController?,
    val schedules: ScheduledTaskController?,
    val keepAliveAvailable: Boolean,
    val exactAlarmAvailable: Boolean,
    val onOpenLog: (String) -> Unit,
    /** 系统精确闹钟授权页；渠道不支持或已授权时为 null。 */
    val onGrantExact: (() -> Unit)?,
    val liveOutput: @Composable (BackgroundJob) -> Unit = {},
)

internal val LocalAgentActivity = staticCompositionLocalOf<AgentActivityAccess?> { null }

/** 当前会话的后台任务与定时任务快照，供运行坞计数与运行详情的“后台”页共用。 */
internal class AgentActivityState(
    val access: AgentActivityAccess,
    val jobs: List<BackgroundJob>,
    val schedules: List<ScheduledTask>,
    val nowMillis: Long,
    val onStop: (String) -> Unit,
    val onToggleSchedule: (String, Boolean) -> Unit,
    val onDeleteSchedule: (String) -> Unit,
    val onClearFinished: () -> Unit = {},
) {
    val summary: AgentActivitySummary get() = summarizeAgentActivity(jobs, schedules, nowMillis)
    val isEmpty: Boolean get() = jobs.isEmpty() && schedules.isEmpty()
}

/**
 * 订阅后台活动。时长与“下次触发”每 30 秒且在下次触发时刻刷新；[fastTick] 为 true（详情面板打开）且有任务运行时每秒刷新。
 */
@Composable
internal fun rememberAgentActivityState(fastTick: Boolean): AgentActivityState? {
    val access = LocalAgentActivity.current ?: return null
    val jobsFlow = remember(access.sessionId, access.jobs) { access.jobs?.observe(access.sessionId) ?: flowOf(emptyList()) }
    val schedulesFlow = remember(access.sessionId, access.schedules) { access.schedules?.observe(access.sessionId) ?: flowOf(emptyList()) }
    val jobs by jobsFlow.collectAsState(initial = emptyList<BackgroundJob>())
    val schedules by schedulesFlow.collectAsState(initial = emptyList<ScheduledTask>())
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val anyRunning = jobs.any { !it.state.isTerminal }
    val nextFire = schedules.mapNotNull { it.nextFireAtMillis?.takeIf { _ -> it.enabled } }.minOrNull()
    LaunchedEffect(anyRunning, fastTick, nextFire) {
        while (true) {
            now = System.currentTimeMillis()
            val base = if (anyRunning && fastTick) 1_000L else 30_000L
            delay(nextFire?.let { (it - now).takeIf { untilFire -> untilFire > 0 } }?.coerceAtMost(base) ?: base)
        }
    }
    val scope = rememberCoroutineScope()
    return AgentActivityState(
        access = access,
        jobs = jobs,
        schedules = schedules,
        nowMillis = now,
        onStop = { id -> scope.launch { access.jobs?.stop(id) } },
        onToggleSchedule = { id, enabled -> scope.launch { if (enabled) access.schedules?.resume(id) else access.schedules?.pause(id) } },
        onDeleteSchedule = { id -> scope.launch { access.schedules?.delete(id) } },
        onClearFinished = { scope.launch { access.jobs?.clearFinished(access.sessionId) } },
    )
}

/** 运行详情“后台”页：后台任务在上，定时任务在下；只有一类时独占整页。 */
@Composable
internal fun AgentActivityTabContent(state: AgentActivityState, onOpenLog: (String) -> Unit, modifier: Modifier = Modifier) {
    val showJobs = state.jobs.isNotEmpty() || state.schedules.isEmpty()
    val showSchedules = state.schedules.isNotEmpty()
    Column(modifier.fillMaxWidth()) {
        if (showJobs) {
            BackgroundJobsContent(
                jobs = state.jobs,
                nowMillis = state.nowMillis,
                keepAliveAvailable = state.access.keepAliveAvailable,
                onStop = state.onStop,
                onOpenLog = onOpenLog,
                modifier = Modifier.weight(1f),
                onClearFinished = state.onClearFinished.takeIf { state.access.jobs != null },
                liveOutput = state.access.liveOutput,
            )
        }
        if (showSchedules) {
            ScheduledTasksContent(
                tasks = state.schedules,
                nowMillis = state.nowMillis,
                exactAlarmAvailable = state.access.exactAlarmAvailable,
                onToggle = state.onToggleSchedule,
                onDelete = state.onDeleteSchedule,
                onGrantExact = state.access.onGrantExact,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
