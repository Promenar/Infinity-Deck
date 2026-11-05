package com.promenar.infinity.background.indexing

/** 所有通知更新共用最小间隔，调用方在延时结束时发送最新状态。 */
internal class IndexNotificationThrottle(private val intervalMs: Long = 1_000L) {
    private var lastSentAt: Long? = null

    fun delayFor(now: Long): Long = lastSentAt?.let { (it + intervalMs - now).coerceAtLeast(0L) } ?: 0L

    fun markSent(now: Long) {
        lastSentAt = now
    }
}
