package com.promenar.infinity.ui.theme

import androidx.compose.ui.text.style.LineBreak
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextLineBreakPolicyTest {
    @Test
    fun `identifier breaks only after separators followed by letters or slash`() {
        assertThat("Release-readiness-checklist.md".breakableIdentifier())
            .isEqualTo("Release-​readiness-​checklist.​md")
        assertThat("BAAI/bge-reranker-v2-m3".breakableIdentifier())
            .isEqualTo("BAAI/​bge-​reranker-​v2-​m3")
        assertThat("Gemini 3.1 Flash".breakableIdentifier()).isEqualTo("Gemini 3.1 Flash")
        assertThat("api.promenar.com/v1".breakableIdentifier())
            .isEqualTo("api.​promenar.​com/​v1")
        assertThat("trailing/".breakableIdentifier()).isEqualTo("trailing/")
    }

    @Test
    fun `cjk tail joins the last two glyphs and ignores trailing punctuation`() {
        assertThat("技能包本身不执行代码。".keepCjkTailTogether()).isEqualTo("技能包本身不执行代\u2060码。")
        assertThat("保留最近轮次".keepCjkTailTogether()).isEqualTo("保留最近轮\u2060次")
        assertThat("参考资料」）".keepCjkTailTogether()).isEqualTo("参考资\u2060料」）")
        assertThat("加载 SKILL.md".keepCjkTailTogether()).isEqualTo("加载 SKILL.md")
        assertThat("使用 Token".keepCjkTailTogether()).isEqualTo("使用 Token")
        assertThat("文 字".keepCjkTailTogether()).isEqualTo("文 字")
        assertThat("字".keepCjkTailTogether()).isEqualTo("字")
        assertThat("".keepCjkTailTogether()).isEqualTo("")
        assertThat("Skill packs do not execute code.".keepCjkTailTogether())
            .isEqualTo("Skill packs do not execute code.")
    }

    @Test
    fun `every typography role declares an explicit line break policy`() {
        val roles = with(InfinityTypography) {
            listOf(
                displayLarge, displayMedium, displaySmall, headlineLarge, headlineMedium, headlineSmall,
                titleLarge, titleMedium, titleSmall, bodyLarge, bodyMedium, bodySmall,
                labelLarge, labelMedium, labelSmall,
            )
        }
        roles.forEach { assertThat(it.lineBreak).isEqualTo(InfinityUiLineBreak) }
        assertThat(InfinityUiLineBreak.strategy).isEqualTo(LineBreak.Strategy.HighQuality)
        assertThat(InfinityUiLineBreak.strictness).isEqualTo(LineBreak.Strictness.Strict)
        assertThat(InfinityUiLineBreak.wordBreak).isEqualTo(LineBreak.WordBreak.Default)
    }

    @Test
    fun `reading and input helpers switch only the line break policy`() {
        val base = InfinityTypography.bodyMedium
        assertThat(base.forReading()).isEqualTo(base.copy(lineBreak = LineBreak.Paragraph))
        assertThat(base.forInput()).isEqualTo(base.copy(lineBreak = LineBreak.Simple))
    }
}
