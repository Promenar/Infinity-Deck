package com.promenar.infinity.ui.lab.fakes

import androidx.compose.runtime.Composable
import com.promenar.infinity.data.exec.ToolLiveOutputStore
import com.promenar.infinity.domain.jobs.BackgroundJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** 调用日志只有内存状态，不执行命令、请求或持久化。 */
internal data class LabCall(val name: String, val arguments: List<Any?>)

internal abstract class LabCallRecorder {
    private val recorded = MutableStateFlow<List<LabCall>>(emptyList())
    val calls: StateFlow<List<LabCall>> = recorded.asStateFlow()

    protected fun record(name: String, vararg arguments: Any?) {
        recorded.update { it + LabCall(name, arguments.toList()) }
    }
}

/** 一份实验脚本持有一套依赖；不从应用容器获取任何对象。 */
internal class LabFakes(
    val sessionId: String = "lab-session",
    onOpenSession: (String) -> Unit = {},
    onOpenLog: (String) -> Unit = {},
    onOpenSubagent: (String, Int) -> Unit = { _, _ -> },
    onGrantExact: () -> Unit = {},
    modelName: (String) -> String = { it },
    liveOutput: @Composable (BackgroundJob) -> Unit = {},
) {
    val branches = LabBranchControls()
    val branchAccess = LabBranchAccess(sessionId, branches, onOpenSession)
    val jobs = LabBackgroundJobController()
    val schedules = LabScheduledTaskController()
    val agentActivity = LabAgentActivityAccess(sessionId, jobs, schedules, onOpenLog, onGrantExact, liveOutput)
    val subagents = LabSubagentControls()
    val subagentRuns = LabSubagentRunsAccess(onOpenSubagent, modelName)
    val memory = LabMemoryChangeHost()
    val toolOutput = LabToolLiveOutput()
    val liveOutput: ToolLiveOutputStore get() = toolOutput.store
}
