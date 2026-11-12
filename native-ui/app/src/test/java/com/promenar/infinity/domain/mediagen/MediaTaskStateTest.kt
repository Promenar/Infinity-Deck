package com.promenar.infinity.domain.mediagen

import org.junit.Assert.*
import org.junit.Test

class MediaTaskStateTest {
    private val request = MediaGenerationRequest("c", GenerationMode.TEXT, "树林", emptyList(), "s")

    @Test fun `新任务默认排队且未知估价为空`() {
        val task = MediaTask("t", request)
        assertEquals(MediaTaskState.QUEUED, task.state)
        assertNull(task.estimate)
        val running = task.submitting().started("remote")
        assertEquals(MediaTaskState.RUNNING, running.state)
        assertEquals("remote", running.copy().remoteId)
    }

    @Test fun `活动阶段可取消并保留远端编号和已知费用`() {
        val running = MediaTask("t", request).started("remote").copy(actualCost = Money(java.math.BigDecimal.ONE, "CNY"))
        for (state in listOf(MediaTaskState.QUEUED, MediaTaskState.SUBMITTING, MediaTaskState.RUNNING, MediaTaskState.DOWNLOADING)) {
            val task = running.copy(state = state, createdAt = 42)
            val canceled = task.canceled()
            assertEquals(MediaTaskState.CANCELED, canceled.state)
            assertEquals(task.remoteId, canceled.remoteId)
            assertEquals(task.actualCost, canceled.actualCost)
            assertEquals(42L, canceled.createdAt)
        }
    }

    @Test fun `终态和兼容待批准记录不能通过状态方法再次调度`() {
        for (state in listOf(MediaTaskState.CANCELED, MediaTaskState.COMPLETED, MediaTaskState.AWAITING_APPROVAL, MediaTaskState.FAILED)) {
            val task = MediaTask("t", request, state = state)
            assertThrows(IllegalStateException::class.java) { task.submitting() }
            assertThrows(IllegalStateException::class.java) { task.started("remote") }
            assertThrows(IllegalStateException::class.java) { task.canceled() }
        }
    }

    @Test fun `完成必须有产物且不虚构费用`() {
        val running = MediaTask("t", request).started("remote")
        assertThrows(IllegalArgumentException::class.java) { running.completed(emptyList(), null) }
        val complete = running.completed(listOf("takes/a.mp4"), null)
        assertEquals(MediaTaskState.COMPLETED, complete.state)
        assertNull(complete.actualCost)
    }
}
