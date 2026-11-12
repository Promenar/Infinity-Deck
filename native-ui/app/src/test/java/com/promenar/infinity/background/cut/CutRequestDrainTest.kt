package com.promenar.infinity.background.cut

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CutRequestDrainTest {
    @Test fun `旧请求完成期间新启动世代被接手`() = runTest {
        var version = 1L
        var runs = 0
        drainCutRequests({ runs++; if (runs == 1) version++ }, { false }, { version })
        assertEquals(2, runs)
    }

    @Test fun `新请求尚未交付服务Intent时也按槽位接手`() = runTest {
        var runs = 0
        drainCutRequests({ runs++ }, { runs < 2 }, { 1L })
        assertEquals(2, runs)
    }

    @Test fun `单个任务取消后新请求可以接手`() = runTest {
        var runs = 0
        drainCutRequests({ runs++; if (runs == 1) throw CancellationException("用户取消") }, { runs < 2 }, { 1L })
        assertEquals(2, runs)
    }
}
