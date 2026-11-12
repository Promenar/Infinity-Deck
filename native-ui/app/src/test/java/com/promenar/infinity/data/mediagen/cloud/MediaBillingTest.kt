package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.domain.mediagen.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class MediaBillingTest {
    @Test fun combinesUnitsAndFreeAllowanceWithoutRoundingEachItem() {
        val tariff = MediaTariff("USD", listOf(
            MediaRate(BillingUnit.OUTPUT_SECONDS, "0.08"),
            MediaRate(BillingUnit.INPUT_IMAGES, "0.04", freeUnits = 5),
            MediaRate(BillingUnit.INPUT_VIDEO_SECONDS, "0.08"),
        ))
        assertEquals(BigDecimal("1.00"), tariff.cost(mapOf("output_seconds" to 10L, "input_image_count" to 8L, "input_seconds" to 1L))!!.amount)
        assertNull(tariff.cost(mapOf("output_seconds" to 10L)))
        assertEquals(BigDecimal("0.00"), tariff.cost(mapOf("output_seconds" to 0L, "input_image_count" to 0L, "input_seconds" to 0L))!!.amount)
    }
    @Test fun tokenPriceCanBePerMillion() {
        val tariff = MediaTariff("CNY", listOf(MediaRate(BillingUnit.TOKENS, "5", perUnits = 1_000_000)))
        assertEquals(0, BigDecimal("0.61728").compareTo(tariff.cost(mapOf("total_tokens" to 123456L))!!.amount))
        assertNull(tariff.cost(mapOf("total_tokens" to -1L)))
    }
    @Test fun speechCountsChineseAsTwoAndUnicodeCodePoints() {
        assertEquals(7L, speechBillableCharacters("你好a😀!"))
    }
}
