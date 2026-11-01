package com.promenar.infinity.ui.lab.fakes

import com.promenar.infinity.data.subagent.SubagentBatch
import com.promenar.infinity.data.subagent.SubagentControls
import com.promenar.infinity.ui.chat.SubagentRunsAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

internal class LabSubagentControls(
    private val onReleaseWait: (String, String) -> Unit = { _, _ -> },
    private val onStop: suspend (String, String) -> Unit = { _, _ -> },
) : LabCallRecorder(), SubagentControls {
    override fun releaseWait(sessionId: String, stepId: String) {
        record("releaseWait", sessionId, stepId)
        onReleaseWait(sessionId, stepId)
    }

    override suspend fun stop(sessionId: String, runId: String) {
        record("stop", sessionId, runId)
        onStop(sessionId, runId)
    }
}

internal class LabSubagentRunsAccess(
    private val onOpen: (String, Int) -> Unit = { _, _ -> },
    modelName: (String) -> String = { it },
) : LabCallRecorder() {
    val batches = MutableStateFlow<Map<String, SubagentBatch>>(emptyMap())
    val access = SubagentRunsAccess(
        observe = { stepId -> batches.map { it[stepId] }.distinctUntilChanged() },
        snapshot = { batches.value[it] },
        onOpen = { stepId, index -> record("openSubagent", stepId, index); onOpen(stepId, index) },
        modelName = modelName,
    )

    fun setBatch(stepId: String, value: SubagentBatch?) {
        require(value == null || value.stepId == stepId) { "子代理分组的步骤与索引键不一致" }
        batches.update { if (value == null) it - stepId else it + (stepId to value) }
    }
}
