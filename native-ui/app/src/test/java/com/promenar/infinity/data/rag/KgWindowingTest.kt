package com.promenar.infinity.data.rag

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class KgWindowingTest {
    @Test fun `短文完整保留为单个窗口`() {
        assertThat(KgWindowing.split("甲。\n\n乙。", 20)).containsExactly("甲。\n\n乙。")
    }

    @Test fun `段落装箱且拼接还原原文`() {
        val text = "甲甲\n\n乙乙\n\n丙丙"
        val windows = KgWindowing.split(text, 8)
        assertThat(windows).containsExactly("甲甲\n\n乙乙\n\n", "丙丙").inOrder()
        assertThat(windows.joinToString("")).isEqualTo(text)
    }

    @Test fun `超长段落优先按句切且硬切保留代理对`() {
        val text = "甲乙。丙丁。戊己🙂庚辛"
        val windows = KgWindowing.split(text, 5)
        assertThat(windows.joinToString("")).isEqualTo(text)
        assertThat(windows.first()).isEqualTo("甲乙。")
        assertThat(windows).doesNotContain("\uD83D")
        windows.zipWithNext().forEach { (left, right) ->
            assertThat(left.last().isHighSurrogate() && right.first().isLowSurrogate()).isFalse()
        }
    }

    @Test fun `上下文限制收窄窗口但保持最低值`() {
        val limits = KgModelLimits(maxOutputTokens = 1000, contextTokens = 5000)
        assertThat(KgWindowing.effectiveWindowChars(16000, limits, 1000, "x".repeat(1000))).isEqualTo(3000)
        assertThat(KgWindowing.effectiveWindowChars(16000, limits.copy(contextTokens = 1500), 1000, "x".repeat(1000))).isEqualTo(2000)
    }

    @Test fun `估算包含追加轮和模型单次输出限制`() {
        val estimate = KgExtractionBudget(largeDocumentConfirmCalls = 3).estimate(
            "x".repeat(4001), windowChars = 2000, gleaningPasses = 1,
            modelLimits = KgModelLimits(maxOutputTokens = 1024, contextTokens = null),
        )
        assertThat(estimate.windows).isEqualTo(3)
        assertThat(estimate.calls).isEqualTo(6)
        assertThat(estimate.outputTokenLimit).isEqualTo(6144)
        assertThat(estimate.requiresConfirmation).isTrue()
    }
}
