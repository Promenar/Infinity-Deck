package com.promenar.infinity.data.model

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.repository.CacheUsageAggregate
import com.promenar.infinity.domain.repository.TokenUsageAggregate
import org.junit.Test

class CacheCostEstimateTest {
    @Test fun `读写和长 TTL 写入互斥计价且不重复扣除`() {
        val cost = estimateTokenCost(ModelPricing(2.0, 8.0, 0.2, 2.5, 5.0), TokenUsageAggregate(
            inputTokens = 1_000_000, outputTokens = 100_000,
            cache = CacheUsageAggregate(2, 1, 600_000, 400_000, 300_000, 100_000),
        ))
        assertThat(cost.ordinaryInput).isWithin(1e-9).of(0.6)
        assertThat(cost.cacheRead).isWithin(1e-9).of(0.08)
        assertThat(cost.cacheWrite).isWithin(1e-9).of(1.0)
        assertThat(cost.output).isWithin(1e-9).of(0.8)
        assertThat(cost.total).isWithin(1e-9).of(2.48)
        assertThat(cost.cacheDiscountApplied).isTrue()
        assertThat(cost.cacheDiscountMissing).isFalse()
    }

    @Test fun `缺少的缓存价逐档按普通输入价回退`() {
        val cost = estimateTokenCost(ModelPricing(2.0, 8.0, cacheReadPerMillion = 0.0), TokenUsageAggregate(
            inputTokens = 1_000_000,
            cache = CacheUsageAggregate(cacheReadTokens = 200_000, cacheWriteTokens = 300_000, cacheWriteLongTtlTokens = 100_000),
        ))
        assertThat(cost.total).isWithin(1e-9).of(1.6)
        assertThat(cost.cacheDiscountApplied).isTrue()
        assertThat(cost.cacheDiscountMissing).isTrue()
    }

    @Test fun `未报告和后台输入全部使用普通输入价`() {
        val cost = estimateTokenCost(ModelPricing(2.0, 8.0, 0.2), TokenUsageAggregate(1_000_000, 100_000))
        assertThat(cost.total).isWithin(1e-9).of(2.8)
        assertThat(cost.cacheDiscountApplied).isFalse()
        assertThat(cost.cacheDiscountMissing).isFalse()
    }

    @Test fun `异常聚合不产生负的普通输入或重复长 TTL 费用`() {
        val cost = estimateTokenCost(ModelPricing(2.0, 8.0, 0.2, 2.5, 5.0), TokenUsageAggregate(
            inputTokens = 100, cache = CacheUsageAggregate(cacheReadTokens = 80, cacheWriteTokens = 60, cacheWriteLongTtlTokens = 90),
        ))
        assertThat(cost.ordinaryInput).isEqualTo(0.0)
        assertThat(cost.total).isWithin(1e-9).of((80 * 0.2 + 20 * 5.0) / 1_000_000)
    }
}
