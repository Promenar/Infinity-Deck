package com.promenar.infinity.data.model

import com.promenar.infinity.domain.repository.TokenUsageAggregate

/** 金额单位与价格表一致；长 TTL 写入并入 [cacheWrite]。 */
data class TokenCostEstimate(
    val ordinaryInput: Double,
    val cacheRead: Double,
    val cacheWrite: Double,
    val output: Double,
    val cacheDiscountApplied: Boolean,
    val cacheDiscountMissing: Boolean,
) {
    val total: Double get() = ordinaryInput + cacheRead + cacheWrite + output
}

/** 未报告或缺少单价的部分按普通输入价计算；缓存写入价是完整单价，不是附加费。 */
fun estimateTokenCost(pricing: ModelPricing, usage: TokenUsageAggregate): TokenCostEstimate {
    val input = usage.inputTokens.coerceAtLeast(0)
    val read = usage.cache.cacheReadTokens.coerceIn(0, input)
    val write = usage.cache.cacheWriteTokens.coerceIn(0, input - read)
    val longWrite = usage.cache.cacheWriteLongTtlTokens.coerceIn(0, write)
    val shortWrite = write - longWrite
    fun amount(tokens: Long, price: Double) = tokens.toDouble() * price / 1_000_000
    val parts = listOf(read to pricing.cacheReadPerMillion, shortWrite to pricing.cacheWritePerMillion, longWrite to pricing.cacheWriteLongTtlPerMillion)
    return TokenCostEstimate(
        ordinaryInput = amount(input - read - write, pricing.inputPerMillion),
        cacheRead = amount(read, pricing.cacheReadPerMillion ?: pricing.inputPerMillion),
        cacheWrite = amount(shortWrite, pricing.cacheWritePerMillion ?: pricing.inputPerMillion) +
            amount(longWrite, pricing.cacheWriteLongTtlPerMillion ?: pricing.inputPerMillion),
        output = amount(usage.outputTokens.coerceAtLeast(0), pricing.outputPerMillion),
        cacheDiscountApplied = parts.any { (tokens, price) -> tokens > 0 && price != null },
        cacheDiscountMissing = parts.any { (tokens, price) -> tokens > 0 && price == null },
    )
}
