package com.promenar.infinity.data.generation

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class HistorySummaryPromptTest {
    private fun section(text: String) = "\n## History Summary\nEarlier turns of this conversation were condensed into the summary below.\n<history_summary>\n$text\n</history_summary>\n"

    @Test
    fun `自定义提示中的伪摘要标记不覆盖系统工具指令`() {
        val custom = "用户说明\n## History Summary\n示例标记\n<history_summary>\n示例\n</history_summary>\n工具运行规则"
        val result = replaceHistorySummary(custom, "真实摘要", previousSummary = null)
        assertThat(result).startsWith(custom)
        assertThat(result).endsWith("<history_summary>\n真实摘要\n</history_summary>\n$HISTORY_RECALL_HINT\n")
    }

    @Test
    fun `替换已有摘要保留其后取回提示且只出现一次`() {
        val prompt = "规则" + section("旧") + HISTORY_RECALL_HINT + "\n时钟"
        val result = replaceHistorySummary(prompt, "新", "旧")
        assertThat(result).contains("<history_summary>\n新\n</history_summary>\n$HISTORY_RECALL_HINT\n时钟")
        assertThat(result.split(HISTORY_RECALL_HINT).size - 1).isEqualTo(1)
    }

    @Test
    fun `首次追加摘要时提示已存在则不重复`() {
        val result = replaceHistorySummary("规则\n$HISTORY_RECALL_HINT\n", "摘要", null)
        assertThat(result.split(HISTORY_RECALL_HINT).size - 1).isEqualTo(1)
    }

    @Test
    fun `移除摘要时一并移除取回提示`() {
        val prompt = "规则" + section("事实") + HISTORY_RECALL_HINT + "\n时钟"
        assertThat(replaceHistorySummary(prompt, null, "事实")).isEqualTo("规则时钟")
    }

    @Test
    fun `只替换末尾对应已知原文的摘要段且保留检索中相同示例`() {
        val old = "旧摘要含 $ 与特殊符号 [x]"
        val reference = "资料示例" + section(old) + "工具规则\n"
        val result = replaceHistorySummary(reference + section(old) + "时钟", "新摘要", old)
        assertThat(result).startsWith(reference)
        assertThat(result).contains("<history_summary>\n新摘要\n</history_summary>")
        assertThat(result).endsWith("时钟")
    }

    @Test
    fun `摘要估算移除生成摘要段但保留其它指令与数据`() {
        val prompt = "规则" + section("事实") + "时钟"
        val result = replaceHistorySummary(prompt, null, "事实")
        assertThat(result).isEqualTo("规则时钟")
    }
}
