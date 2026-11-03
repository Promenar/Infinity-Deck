package com.promenar.infinity.data.exec

import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select

internal sealed interface SteeringStep<out T> {
    data class Completed<T>(val value: T) : SteeringStep<T>
    data object Interrupted : SteeringStep<Nothing>
}

/** 插队只取消步骤的子协程；生成任务保留所有权并完成落盘、配对和注入。 */
internal suspend fun <T> interruptOnSteering(signal: SteeringContext?, block: suspend () -> T): SteeringStep<T> {
    if (signal == null) return SteeringStep.Completed(block())
    currentCoroutineContext().ensureActive()
    if (signal.pending()) return SteeringStep.Interrupted
    return coroutineScope {
        val operation = async(start = CoroutineStart.UNDISPATCHED) { block() }
        val insertion = async(start = CoroutineStart.UNDISPATCHED) { signal.await() }
        try {
            select {
                operation.onAwait { SteeringStep.Completed(it) }
                insertion.onAwait { SteeringStep.Interrupted }
            }
        } finally {
            withContext(NonCancellable) { insertion.cancelAndJoin(); operation.cancelAndJoin() }
        }
    }
}
