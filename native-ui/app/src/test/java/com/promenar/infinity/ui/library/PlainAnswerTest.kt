package com.promenar.infinity.ui.library

import org.junit.Assert.assertEquals
import org.junit.Test

class PlainAnswerTest {
    @Test
    fun `去掉粗体、行内代码与标题标记`() {
        val text = "## 小结\n1. **Codex CLI**：位于 `/usr/local/bin/codex` [1]\n保留 __init__ 与 a*b"
        assertEquals("小结\n1. Codex CLI：位于 /usr/local/bin/codex [1]\n保留 __init__ 与 a*b", plainAnswer(text))
    }

    @Test
    fun `复制文本同时去掉编号`() {
        assertEquals("位于 /bin。", stripCitations("位于 `/bin` [1][2]。"))
    }
}
