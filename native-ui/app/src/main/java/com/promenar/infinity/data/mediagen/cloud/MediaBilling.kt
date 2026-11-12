package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.domain.mediagen.Money
import java.math.BigDecimal
import java.math.MathContext
import kotlinx.serialization.Serializable

@Serializable
enum class BillingUnit(val usageKey: String) {
    TOKENS("total_tokens"), OUTPUT_SECONDS("output_seconds"), INPUT_IMAGES("input_image_count"),
    INPUT_VIDEO_SECONDS("input_seconds"), INPUT_AUDIO_SECONDS("input_audio_seconds"), CHARACTERS("usage_characters"), TASK("tasks"),
}

@Serializable
data class MediaRate(val unit: BillingUnit, val amount: String, val perUnits: Long = 1, val freeUnits: Long = 0) {
    init { require(amount.toBigDecimal() >= BigDecimal.ZERO && perUnits > 0 && freeUnits >= 0) }
}

/** 不提供默认单价；缺少任一收费项的用量时，费用保持未知。 */
@Serializable
data class MediaTariff(val currency: String, val rates: List<MediaRate>) {
    init { require(currency.isNotBlank() && rates.isNotEmpty() && rates.map { it.unit }.distinct().size == rates.size) }
    fun cost(usage: Map<String, Long>): Money? {
        var total = BigDecimal.ZERO
        for (rate in rates) {
            val units = usage[rate.unit.usageKey]?.takeIf { it >= 0 } ?: return null
            val billable = (units - rate.freeUnits).coerceAtLeast(0)
            total += rate.amount.toBigDecimal().multiply(BigDecimal.valueOf(billable))
                .divide(BigDecimal.valueOf(rate.perUnits), MathContext.DECIMAL128)
        }
        return Money(total, currency)
    }
}

fun speechBillableCharacters(text: String): Long = text.codePoints().toArray().sumOf {
    if (Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN) 2L else 1L
}
