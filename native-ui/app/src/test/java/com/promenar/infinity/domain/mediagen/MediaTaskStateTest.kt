package com.promenar.infinity.domain.mediagen

import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class MediaTaskStateTest {
    private val request = MediaGenerationRequest("c", GenerationMode.TEXT, "p", emptyList(), "s")
    private val estimate = Money(BigDecimal("1.20"), "CNY")

    @Test fun approvalAndRemoteIdAreRetainedInTaskSnapshot() {
        val pending = MediaTask("t", request, estimate)
        val queued = pending.approve()
        val running = queued.started("remote")
        assertEquals(MediaTaskState.RUNNING, running.state)
        assertEquals("remote", running.remoteId)
        assertEquals("remote", running.copy().remoteId)
    }

    @Test fun retryRetainsRemoteIdWhenDownloadFails() {
        val running = MediaTask("t", request, estimate).approve().started("remote")
        val retry = running.copy(state = MediaTaskState.DOWNLOAD_FAILED, failure = MediaTaskFailure.DOWNLOAD).retry()
        assertEquals("remote", retry.remoteId)
        assertEquals(MediaTaskState.DOWNLOADING, retry.state)
        assertThrows(IllegalStateException::class.java) { running.approve() }
    }

    @Test fun completionRequiresSavedOutputAndDoesNotInventActualCost() {
        val running = MediaTask("t", request, estimate).approve().started("remote")
        assertThrows(IllegalArgumentException::class.java) { running.completed(emptyList(), null) }
        val complete = running.completed(listOf("/media/a.mp4"), null)
        assertEquals(MediaTaskState.COMPLETED, complete.state)
        assertNull(complete.actualCost)
    }
}
