package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.chat.queue.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SteeringInterruptionTest {
    @Test fun `普通排队不取消当前步骤而插队取消步骤子协程`() = runTest {
        val queue = MutableStateFlow(ComposerQueueState())
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val task = async {
            interruptOnSteering(SteeringContext(queue)) {
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
        }
        entered.await()
        queue.value = ComposerQueueState(items = listOf(QueuedMessage("normal", "排队", createdAt = 0)))
        yield()
        assertThat(task.isCompleted).isFalse()
        queue.value = ComposerQueueState(items = listOf(QueuedMessage("urgent", "插队", createdAt = 0, steering = true)))
        assertThat(task.await()).isEqualTo(SteeringStep.Interrupted)
        assertThat(cancelled.isCompleted).isTrue()
        assertThat(currentCoroutineContext().isActive).isTrue()
    }
    @Test fun `取消父任务保持取消并不把插队回执为成功`() = runTest {
        val queue = MutableStateFlow(ComposerQueueState())
        val entered = CompletableDeferred<Unit>()
        val task = launch { interruptOnSteering(SteeringContext(queue)) { entered.complete(Unit); awaitCancellation() } }
        entered.await()
        task.cancelAndJoin()
        assertThat(task.isCancelled).isTrue()
    }
}
