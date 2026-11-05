package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KgBatchExtractionTest {
    @Test fun `批量估算为各篇估算之和并默认跳过已完成`() {
        fun estimate(calls: Int, windows: Int) = KgExtractionEstimate(calls, calls.toLong() * 100, calls.toLong() * 200, calls, false, windows)
        val rows = listOf(
            KgBatchDocument("one", "一", "root", "h1", 1, false, estimate(4, 2)),
            KgBatchDocument("two", "二", "root", "h2", 2, true, estimate(6, 3)),
            KgBatchDocument("three", "三", "root", "h3", 3, false, estimate(2, 1)),
        )
        val only = KgBatchPlanner.aggregate(rows)
        assertThat(only.documentCount).isEqualTo(2)
        assertThat(only.skippedCount).isEqualTo(1)
        assertThat(only.calls).isEqualTo(6)
        assertThat(only.windows).isEqualTo(3)
        assertThat(only.outputTokenLimit).isEqualTo(1200)
        val all = KgBatchPlanner.aggregate(rows, onlyUnextracted = false)
        assertThat(all.documentCount).isEqualTo(3)
        assertThat(all.calls).isEqualTo(12)
        assertThat(all.windows).isEqualTo(6)
        assertThat(all.outputTokenLimit).isEqualTo(2400)
    }
}
