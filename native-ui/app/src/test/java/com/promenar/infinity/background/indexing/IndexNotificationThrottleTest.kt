package com.promenar.infinity.background.indexing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IndexNotificationThrottleTest {
    @Test
    fun `首次通知立即发送随后所有更新至少间隔一秒`() {
        val throttle = IndexNotificationThrottle()
        assertThat(throttle.delayFor(10_000)).isEqualTo(0)
        throttle.markSent(10_000)
        assertThat(throttle.delayFor(10_100)).isEqualTo(900)
        assertThat(throttle.delayFor(11_000)).isEqualTo(0)
        throttle.markSent(11_000)
        assertThat(throttle.delayFor(11_001)).isEqualTo(999)
    }
}
