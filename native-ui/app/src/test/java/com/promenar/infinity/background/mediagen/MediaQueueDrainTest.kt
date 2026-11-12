package com.promenar.infinity.background.mediagen

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaQueueDrainTest {
    @Test fun `旧运行判空时送达的新启动请求必须再排空一次`() = runTest {
        var version = 1L
        var runs = 0
        drainMediaQueue(run = { runs++ }, hasWork = {
            if (runs == 1) version++
            false
        }, version = { version })
        assertEquals(2, runs)
    }
    @Test fun `退出检查发现新增持久任务必须执行`() = runTest {
        var runs = 0
        drainMediaQueue(run = { runs++ }, hasWork = { runs == 1 }, version = { 1L })
        assertEquals(2, runs)
    }
}
