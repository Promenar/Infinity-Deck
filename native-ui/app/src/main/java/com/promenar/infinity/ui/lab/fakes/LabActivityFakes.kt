package com.promenar.infinity.ui.lab.fakes

import androidx.compose.runtime.Composable
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobController
import com.promenar.infinity.domain.jobs.BackgroundJobLog
import com.promenar.infinity.domain.schedule.ScheduledTask
import com.promenar.infinity.domain.schedule.ScheduledTaskController
import com.promenar.infinity.ui.chat.components.AgentActivityAccess
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal data class LabLogKey(val sessionId: String, val reference: String)

internal class LabBackgroundJobController(
    private val onStop: (suspend (String) -> Boolean)? = null,
    private val onReadLog: (suspend (String, String) -> BackgroundJobLog)? = null,
) : LabCallRecorder(), BackgroundJobController {
    val jobs = MutableStateFlow<List<BackgroundJob>>(emptyList())
    val logs = MutableStateFlow<Map<LabLogKey, BackgroundJobLog>>(emptyMap())

    fun setJobs(value: List<BackgroundJob>) { jobs.value = value.toList() }
    fun setLog(sessionId: String, reference: String, value: BackgroundJobLog) {
        logs.update { it + (LabLogKey(sessionId, reference) to value) }
    }

    override fun observe(sessionId: String?): Flow<List<BackgroundJob>> = jobs.map { current ->
        current.filter { sessionId == null || it.sessionId == sessionId }.sortedByDescending { it.startedAtMillis }
    }.distinctUntilChanged()

    override suspend fun readLog(sessionId: String, reference: String): BackgroundJobLog {
        record("readLog", sessionId, reference)
        return onReadLog?.invoke(sessionId, reference) ?: logs.value[LabLogKey(sessionId, reference)]
            ?: BackgroundJobLog("实验室未提供该日志", truncated = false)
    }

    override suspend fun stop(jobId: String): Boolean {
        record("stop", jobId)
        return onStop?.invoke(jobId) ?: jobs.value.any { it.id == jobId && !it.state.isTerminal }
    }
}

internal class LabScheduledTaskController(
    private val onPause: suspend (String) -> Unit = {},
    private val onResume: suspend (String) -> Unit = {},
    private val onDelete: suspend (String) -> Unit = {},
) : LabCallRecorder(), ScheduledTaskController {
    val tasks = MutableStateFlow<List<ScheduledTask>>(emptyList())

    fun setTasks(value: List<ScheduledTask>) { tasks.value = value.toList() }

    override fun observe(sessionId: String?): Flow<List<ScheduledTask>> = tasks.map { current ->
        current.filter { sessionId == null || it.sessionId == sessionId }
    }.distinctUntilChanged()

    override suspend fun pause(id: String) { record("pause", id); onPause(id) }
    override suspend fun resume(id: String) { record("resume", id); onResume(id) }
    override suspend fun delete(id: String) { record("delete", id); onDelete(id) }
}

internal class LabAgentActivityAccess(
    private val sessionId: String,
    private val jobs: BackgroundJobController,
    private val schedules: ScheduledTaskController,
    private val onOpenLog: (String) -> Unit = {},
    private val onGrantExact: () -> Unit = {},
    private val liveOutput: @Composable (BackgroundJob) -> Unit = {},
) : LabCallRecorder() {
    val access = MutableStateFlow(create(keepAliveAvailable = true, exactAlarmAvailable = false, canGrantExact = true))

    fun setAvailability(keepAliveAvailable: Boolean, exactAlarmAvailable: Boolean, canGrantExact: Boolean = !exactAlarmAvailable) {
        access.value = create(keepAliveAvailable, exactAlarmAvailable, canGrantExact)
    }

    private fun create(keepAliveAvailable: Boolean, exactAlarmAvailable: Boolean, canGrantExact: Boolean) = AgentActivityAccess(
        sessionId = sessionId, jobs = jobs, schedules = schedules,
        keepAliveAvailable = keepAliveAvailable, exactAlarmAvailable = exactAlarmAvailable,
        onOpenLog = { record("openLog", it); onOpenLog(it) },
        onGrantExact = if (canGrantExact) ({ record("grantExact"); onGrantExact() }) else null,
        liveOutput = liveOutput,
    )
}
