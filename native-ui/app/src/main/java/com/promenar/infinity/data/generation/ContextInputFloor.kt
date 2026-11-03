package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode

/** 当前轮不可折叠输入，按完整请求的同一校准总数分配占比；安全取整向上。 */
data class ContextInputFloor(val tokens: Int, val availableTokens: Long?) {
    val exceeded: Boolean get() = availableTokens != null && tokens > availableTokens

    fun failure(hasFullContextDocuments: Boolean) = GenerationFailure(
        GenerationFailureCode.CONTEXT_LIMIT, mapOf(
            GenerationFailure.KEY_CONTEXT_LIMIT_REASON to "CURRENT_TURN",
            GenerationFailure.KEY_REQUIRED_TOKENS to tokens.toString(),
            GenerationFailure.KEY_AVAILABLE_TOKENS to (availableTokens ?: 0).toString(),
            GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS to hasFullContextDocuments.toString(),
        ),
    )

    companion object {
        fun evaluate(
            session: Session, prompt: PromptRequest, window: PromptWindow,
            stableModelId: String? = null, foldableIndexes: Set<Int> = emptySet(),
        ): ContextInputFloor {
            val current = prompt.messages.indexOfLast { it.role == "user" && !it.isRuntimeInstruction }
            val messages = prompt.messages.mapIndexedNotNull { index, message ->
                when {
                    message.role == "system" -> message
                    current >= 0 && index < current -> null
                    index in foldableIndexes -> message.copy(content = "")
                    else -> message
                }
            }
            return ContextInputFloor(PromptUsageCalibration.estimatePortion(session, prompt,
                prompt.copy(messages = messages), stableModelId), window.inputBudgetTokens)
        }
    }
}
