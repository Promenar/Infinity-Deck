package com.promenar.infinity.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.ui.settings.PROVIDER_PRESETS
import com.promenar.infinity.ui.settings.label
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ProtocolTypeLabelTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    @Test
    fun `英文界面下所有协议名称不含中文`() {
        val withCjk = ProtocolType.entries
            .map { it.persistedId to it.label(resources) }
            .filter { (_, label) -> label.any(::isCjk) }
        assertThat(withCjk).isEmpty()
    }

    @Test
    fun `英文界面下所有预设名称不含中文且稳定键不变`() {
        val withCjk = PROVIDER_PRESETS
            .map { it.name to it.label(resources) }
            .filter { (_, label) -> label.any(::isCjk) }
        assertThat(withCjk).isEmpty()
        assertThat(PROVIDER_PRESETS.map { it.name }).containsExactly(
            "OpenAI", "DeepSeek", "Anthropic", "Gemini", "Vertex AI", "Kimi",
            "通义千问", "智谱", "豆包", "百川", "Mistral", "Local", "Custom",
        ).inOrder()
    }

    @Test
    @Config(qualifiers = "zh-rCN")
    fun `中文界面保留中文协议名与预设名`() {
        assertThat(ProtocolType.Qwen_DashScope.label(resources)).isEqualTo("通义千问 (Qwen)")
        assertThat(ProtocolType.Generic_OpenAI_Compat.label(resources)).isEqualTo("OpenAI 兼容（通用）")
        assertThat(PROVIDER_PRESETS.first { it.name == "Custom" }.label(resources)).isEqualTo("自定义")
    }

    @Test
    fun `每个协议有独立的界面名称资源`() {
        val labels = ProtocolType.entries.map { it.labelRes }
        assertThat(labels).containsNoDuplicates()
    }

    @Test
    fun `历史显示名作为存储匹配键逐字冻结`() {
        assertThat(ProtocolType.entries.associate { it.persistedId to it.legacyDisplayName }).containsExactlyEntriesIn(
            mapOf(
                "OpenAI_ChatCompletions" to "OpenAI Chat Completions",
                "OpenAI_Responses" to "OpenAI Responses",
                "Anthropic_Messages" to "Anthropic Messages",
                "Google_VertexAI" to "Google Vertex AI",
                "Google_Gemini" to "Google Gemini API",
                "Cohere_Chat" to "Cohere Chat",
                "Mistral_Chat" to "Mistral Chat",
                "DeepSeek" to "DeepSeek",
                "Moonshot_Kimi" to "Moonshot (Kimi)",
                "Qwen_DashScope" to "通义千问 (Qwen)",
                "Zhipu_GLM" to "智谱 (GLM)",
                "Doubao_ByteDance" to "豆包 (Doubao)",
                "Yi_ZeroOne" to "零一万物 (Yi)",
                "Baichuan" to "百川 (Baichuan)",
                "Generic_OpenAI_Compat" to "OpenAI 兼容 (通用)",
                "Local" to "本地推理",
            ),
        )
    }

    private fun isCjk(c: Char) = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
}
