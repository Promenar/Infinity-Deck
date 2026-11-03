package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.PromptUsageBaseline
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.ProtocolUsage
import com.promenar.infinity.data.remote.protocol.PromptRequest

/** 每个实际请求创建一个实例；无用量的新请求会清除上一请求基线，快照始终覆盖而不累加。 */
class RequestInputUsageRecorder(
    private val session: Session,
    private val prompt: PromptRequest,
    private val stableModelId: String?,
    private val persist: suspend (PromptUsageBaseline?) -> Unit,
) {
    private var lastInput: Int? = null
    suspend fun start() = persist(null)

    suspend fun observe(usage: ProtocolUsage) {
        if (!usage.inputReported || usage.input < 0 || usage.input == lastInput) return
        val baseline = PromptUsageCalibration.capture(session, prompt, usage.input, stableModelId)
        persist(baseline)
        lastInput = usage.input
    }
}
