package com.promenar.infinity.ui.common

import com.promenar.infinity.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 审计打磨：模型 ID → 品牌 Logo 资产键映射。 */
class ModelBrandResolverTest {

    @Test
    fun `maps gateway free models`() {
        assertEquals("deepseek", ModelBrandResolver.brandKey("deepseek-v4-flash"))
        assertEquals("sensenova", ModelBrandResolver.brandKey("sensenova-6.8-flash-lite"))
        assertEquals("sensenova", ModelBrandResolver.brandKey("sensenova-u1.5-lite"))
    }

    @Test
    fun `maps major brands`() {
        assertEquals("anthropic", ModelBrandResolver.brandKey("claude-opus-4-6"))
        assertEquals("google", ModelBrandResolver.brandKey("gemini-3.7-flash"))
        assertEquals("google", ModelBrandResolver.brandKey("gemma-3"))
        assertEquals("openai", ModelBrandResolver.brandKey("gpt-5"))
        assertEquals("openai", ModelBrandResolver.brandKey("codex-mini"))
        assertEquals("openai", ModelBrandResolver.brandKey("openai/o4-mini"))
        assertEquals("zhipu", ModelBrandResolver.brandKey("GLM-5.3"))
        assertEquals("kimi", ModelBrandResolver.brandKey("kimi-k3"))
        assertEquals("volcengine", ModelBrandResolver.brandKey("doubao-seed-2-1-pro"))
        assertEquals("minimax", ModelBrandResolver.brandKey("MiniMax-M3"))
        assertEquals("qwen", ModelBrandResolver.brandKey("qwen3-max"))
        assertEquals("meta", ModelBrandResolver.brandKey("llama-4-scout"))
        assertEquals("mistral", ModelBrandResolver.brandKey("mistral-large"))
        assertEquals("grok", ModelBrandResolver.brandKey("grok-4.6"))
        assertEquals("grok", ModelBrandResolver.brandKey("xai/grok-5"))
        assertEquals("cohere", ModelBrandResolver.brandKey("command-a"))
        assertEquals("nvidia", ModelBrandResolver.brandKey("nemotron-ultra"))
        assertEquals("ai21", ModelBrandResolver.brandKey("jamba-large"))
        assertEquals("opencode", ModelBrandResolver.brandKey("OpenCode Go", "https://opencode.ai/zen/go/v1"))
        assertEquals("openrouter", ModelBrandResolver.brandKey("OpenRouter"))
        assertEquals("siliconflow", ModelBrandResolver.brandKey("SiliconFlow"))
        assertEquals("qwen", ModelBrandResolver.brandKey("百炼 Coding Plan"))
    }

    @Test
    fun `unknown models return null`() {
        assertNull(ModelBrandResolver.brandKey("infinity-proprietary-9000"))
        assertNull(ModelBrandResolver.brandKey(null))
        assertNull(ModelBrandResolver.brandKey(""))
    }

    @Test
    fun `case insensitive`() {
        assertEquals("deepseek", ModelBrandResolver.brandKey("DeepSeek-V4-Flash"))
        assertEquals("qwen", ModelBrandResolver.brandKey("QWEN3-MAX"))
    }

    @Test
    fun `google models distinguish gemini spark and google g logo`() {
        assertEquals(
            R.drawable.ic_provider_gemini,
            localBrandDrawableRes("google", "gemini-3.8-flash"),
        )
        assertEquals(
            R.drawable.ic_provider_gemini,
            localBrandDrawableRes("google", "google/gemini-2.5-pro"),
        )
        assertEquals(
            R.drawable.ic_provider_google,
            localBrandDrawableRes("google", "gemma-4-31b-it"),
        )
        assertEquals(
            R.drawable.ic_provider_google,
            localBrandDrawableRes("google", "Gemma 4 31B IT"),
        )
        assertEquals(
            R.drawable.ic_provider_google,
            localBrandDrawableRes("google", "Vertex AI"),
        )
    }
}
