package com.promenar.infinity.data.generation

import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode

/** 实例归属一次生成，所有工具回合共用；结构上仅有首次发送和一次补救发送。 */
class ContextLimitRecovery {
    private var attempted = false

    suspend fun attempt(failure: GenerationFailure, historySummaryAttempted: Boolean, recover: suspend () -> Boolean): Boolean {
        if (attempted || historySummaryAttempted || failure.code != GenerationFailureCode.CONTEXT_LIMIT ||
            failure.formatArgs[GenerationFailure.KEY_CONTEXT_LIMIT_REASON] != "UPSTREAM") return false
        attempted = true
        return recover()
    }

    companion object {
        internal fun meetsRecoveryBudget(result: ContextCompactionResult, budget: Int): Boolean =
            result.blockedFloor == null && result.summarizedTurns > 0 && !result.summaryFailed && result.afterTokens <= budget
    }
}
