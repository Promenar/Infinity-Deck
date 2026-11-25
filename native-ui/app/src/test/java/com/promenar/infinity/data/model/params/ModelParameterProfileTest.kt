package com.promenar.infinity.data.model.params

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.params.ReasoningEffort.HIGH
import com.promenar.infinity.data.model.params.ReasoningEffort.LOW
import com.promenar.infinity.data.model.params.ReasoningEffort.MAX
import com.promenar.infinity.data.model.params.ReasoningEffort.MEDIUM
import com.promenar.infinity.data.model.params.ReasoningEffort.MINIMAL
import com.promenar.infinity.data.model.params.ReasoningEffort.NONE
import com.promenar.infinity.data.model.params.ReasoningEffort.XHIGH
import com.promenar.infinity.data.remote.protocol.ProtocolType
import org.junit.Test

class ModelParameterProfileTest {

    private fun profile(protocol: ProtocolType, model: String) = resolveModelParameterProfile(protocol, model)
    private fun reasoning(protocol: ProtocolType, model: String) = requireNotNull(profile(protocol, model).reasoning) { model }

    @Test
    fun `MiniMax 国内站应用官方参数约束`() {
        val protocol = ProtocolType.Generic_OpenAI_Compat
        assertThat(resolveModelParameterProfile(protocol, "MiniMax-M3", "https://api.minimax.cn/v1").officialEndpoint).isTrue()
        assertThat(resolveModelParameterProfile(protocol, "MiniMax-M3", "https://api.minimax.cn.example.test").officialEndpoint).isFalse()
    }

    @Test
    fun `各厂商代表模型只给出文档列出的档位与关闭能力`() {
        reasoning(ProtocolType.OpenAI_Responses, "gpt-6-astra").run {
            assertThat(levels).containsExactly(LOW, MEDIUM, HIGH, XHIGH, MAX).inOrder()
            assertThat(canDisable).isFalse()
        }
        assertThat(reasoning(ProtocolType.OpenAI_ChatCompletions, "gpt-5.4").defaultEffort).isEqualTo(NONE)
        reasoning(ProtocolType.Anthropic_Messages, "claude-opus-5-5").run {
            assertThat(levels).containsExactly(LOW, MEDIUM, HIGH, XHIGH, MAX).inOrder()
            assertThat(canDisable).isFalse()
            assertThat(defaultEffort).isEqualTo(MEDIUM)
        }
        assertThat(reasoning(ProtocolType.Anthropic_Messages, "claude-opus-5-20260115").canDisable).isTrue()
        assertThat(reasoning(ProtocolType.Anthropic_Messages, "claude-sonnet-4-6").levels).containsExactly(LOW, MEDIUM, HIGH, MAX).inOrder()
        assertThat(reasoning(ProtocolType.Anthropic_Messages, "claude-haiku-4-5").isToggleOnly).isTrue()
        assertThat(reasoning(ProtocolType.Google_Gemini, "gemini-4-argon").levels).doesNotContain(MINIMAL)
        assertThat(reasoning(ProtocolType.Google_Gemini, "gemini-3.6-flash").levels).contains(MINIMAL)
        assertThat(reasoning(ProtocolType.Google_Gemini, "gemini-2.5-flash").dialect).isEqualTo(ReasoningDialect.GEMINI_BUDGET)
        assertThat(reasoning(ProtocolType.DeepSeek, "deepseek-v4-pro").levels).containsExactly(LOW, HIGH, MAX).inOrder()
        assertThat(reasoning(ProtocolType.Zhipu_GLM, "glm-5.3").canDisable).isFalse()
        assertThat(reasoning(ProtocolType.Qwen_DashScope, "qwen3.8-max").levels).containsExactly(LOW, MEDIUM, XHIGH).inOrder()
        assertThat(reasoning(ProtocolType.Qwen_DashScope, "qwen3.6-plus").isToggleOnly).isTrue()
        assertThat(reasoning(ProtocolType.Moonshot_Kimi, "kimi-k3").levels).containsExactly(LOW, HIGH, MAX).inOrder()
        assertThat(reasoning(ProtocolType.Moonshot_Kimi, "kimi-k2.6").isToggleOnly).isTrue()
        assertThat(reasoning(ProtocolType.Generic_OpenAI_Compat, "grok-4.5").levels).doesNotContain(XHIGH)
        assertThat(reasoning(ProtocolType.Anthropic_Messages, "MiniMax-M3.1-Flash").defaultEffort).isEqualTo(MAX)
        assertThat(reasoning(ProtocolType.Generic_OpenAI_Compat, "sensenova-6.8-flash-lite").canDisable).isTrue()
    }

    @Test
    fun `协议无法表达推理字段或模型无法识别时隐藏控件`() {
        assertThat(profile(ProtocolType.Generic_OpenAI_Compat, "anthropic/claude-opus-5-5").reasoning).isNull()
        assertThat(profile(ProtocolType.Generic_OpenAI_Compat, "gemini-4-argon").reasoning).isNull()
        assertThat(profile(ProtocolType.Mistral_Chat, "mistral-large-latest").reasoning).isNull()
        assertThat(profile(ProtocolType.OpenAI_ChatCompletions, "gpt-4o").reasoning).isNull()
        assertThat(profile(ProtocolType.OpenAI_ChatCompletions, "gpt-5.5-chat-latest").reasoning).isNull()
        // 推理控件隐藏时，模型自带的采样限制仍然生效。
        assertThat(profile(ProtocolType.Generic_OpenAI_Compat, "anthropic/claude-opus-5-5").supports(SamplingParam.TEMPERATURE, null)).isFalse()
    }

    @Test
    fun `供应商前缀与稳定模型 id 前缀不影响识别`() {
        assertThat(profile(ProtocolType.DeepSeek, "deepseek::deepseek/deepseek-flash").reasoning).isNotNull()
        assertThat(profile(ProtocolType.Anthropic_Messages, "Claude-Opus-4-8").reasoning?.defaultEffort).isEqualTo(NONE)
    }

    @Test
    fun `保存的档位就近换成模型支持的档位，距离相同取较高一档`() {
        val kimi = reasoning(ProtocolType.Moonshot_Kimi, "kimi-k3")
        assertThat(kimi.effective(MEDIUM)).isEqualTo(HIGH)
        assertThat(kimi.effective(XHIGH)).isEqualTo(MAX)
        assertThat(kimi.effective(MINIMAL)).isEqualTo(LOW)
        assertThat(kimi.effective(null)).isNull()
        // 不能关闭思考的模型存了“关闭”时按模型默认处理。
        assertThat(kimi.effective(NONE)).isNull()
        val deepseek = reasoning(ProtocolType.DeepSeek, "deepseek-v4-pro")
        assertThat(deepseek.effective(NONE)).isEqualTo(NONE)
        // 只能开关的模型以 HIGH 表示开启。
        assertThat(reasoning(ProtocolType.Moonshot_Kimi, "kimi-k2.6").effective(LOW)).isEqualTo(HIGH)
    }

    @Test
    fun `采样参数按是否在思考剔除`() {
        val gpt = profile(ProtocolType.OpenAI_ChatCompletions, "gpt-5.5")
        assertThat(gpt.supports(SamplingParam.TEMPERATURE, null)).isFalse()
        assertThat(gpt.supports(SamplingParam.TEMPERATURE, NONE)).isTrue()
        assertThat(gpt.supports(SamplingParam.TOP_K, NONE)).isFalse()

        val deepseek = profile(ProtocolType.DeepSeek, "deepseek-v4-pro")
        assertThat(deepseek.supports(SamplingParam.TEMPERATURE, HIGH)).isFalse()
        assertThat(deepseek.supports(SamplingParam.TEMPERATURE, NONE)).isTrue()
        assertThat(deepseek.supports(SamplingParam.FREQUENCY_PENALTY, NONE)).isFalse()

        val kimi = profile(ProtocolType.Moonshot_Kimi, "kimi-k3")
        assertThat(kimi.supports(SamplingParam.TEMPERATURE, null)).isFalse()
        assertThat(kimi.supports(SamplingParam.MAX_TOKENS, null)).isTrue()

        // 默认不思考的旧版 Claude 在不选档位时仍可调温度，开启思考后不可。
        val sonnet46 = profile(ProtocolType.Anthropic_Messages, "claude-sonnet-4-6")
        assertThat(sonnet46.supports(SamplingParam.TEMPERATURE, null)).isTrue()
        assertThat(sonnet46.supports(SamplingParam.TEMPERATURE, HIGH)).isFalse()

        val unknown = profile(ProtocolType.Generic_OpenAI_Compat, "some-local-model")
        assertThat(SamplingParam.entries.all { unknown.supports(it, null) }).isTrue()
        assertThat(profile(ProtocolType.Anthropic_Messages, "unknown").supports(SamplingParam.PRESENCE_PENALTY, null)).isFalse()
    }

    @Test
    fun `参数可用性按接口是否为厂商官方地址区分剔除与提醒`() {
        val official = resolveModelParameterProfile(ProtocolType.DeepSeek, "deepseek-v4-pro", "https://api.deepseek.com")
        assertThat(official.officialEndpoint).isTrue()
        assertThat(official.availability(SamplingParam.TOP_K, HIGH)).isEqualTo(ParamAvailability.NOT_APPLICABLE)
        assertThat(official.availability(SamplingParam.TEMPERATURE, HIGH)).isEqualTo(ParamAvailability.NOT_SENT_WHILE_THINKING)
        assertThat(official.availability(SamplingParam.TEMPERATURE, NONE)).isEqualTo(ParamAvailability.SUPPORTED)

        val relay = resolveModelParameterProfile(ProtocolType.Generic_OpenAI_Compat, "deepseek/deepseek-v4-pro", "https://openrouter.ai/api/v1")
        assertThat(relay.officialEndpoint).isFalse()
        assertThat(relay.availability(SamplingParam.TOP_K, HIGH)).isEqualTo(ParamAvailability.MAY_BE_IGNORED)
        assertThat(relay.availability(SamplingParam.TEMPERATURE, HIGH)).isEqualTo(ParamAvailability.MAY_BE_IGNORED)
        assertThat(relay.availability(SamplingParam.TEMPERATURE, NONE)).isEqualTo(ParamAvailability.SUPPORTED)
        assertThat(relay.unsupportedParams(HIGH)).isEmpty()

        // 协议请求体没有的字段不论接口都不发送。
        val claudeRelay = resolveModelParameterProfile(ProtocolType.Anthropic_Messages, "claude-opus-5-5", "https://gateway.example/anthropic")
        assertThat(claudeRelay.availability(SamplingParam.FREQUENCY_PENALTY, null)).isEqualTo(ParamAvailability.NOT_IN_PROTOCOL)
        assertThat(claudeRelay.availability(SamplingParam.TEMPERATURE, null)).isEqualTo(ParamAvailability.MAY_BE_IGNORED)
    }

    @Test
    fun `空地址取协议默认地址，自定义兼容接口与本地推理按第三方处理`() {
        assertThat(resolveModelParameterProfile(ProtocolType.DeepSeek, "deepseek-v4-pro", "").officialEndpoint).isTrue()
        assertThat(resolveModelParameterProfile(ProtocolType.Generic_OpenAI_Compat, "deepseek-v4-pro", "").officialEndpoint).isFalse()
        assertThat(resolveModelParameterProfile(ProtocolType.Local, "qwen3.8-flash", "").officialEndpoint).isFalse()
        // 调用方不知道地址时沿用文档判断；Gemini 协议只有 Google 自己的接口。
        assertThat(resolveModelParameterProfile(ProtocolType.DeepSeek, "deepseek-v4-pro").officialEndpoint).isTrue()
        assertThat(resolveModelParameterProfile(ProtocolType.Google_Gemini, "gemini-4-pro", "https://proxy.example").officialEndpoint).isTrue()
    }

    @Test
    fun `厂商域名只匹配本身与子域名`() {
        val domains = setOf("deepseek.com")
        assertThat(isVendorEndpoint("https://api.deepseek.com/v1", domains)).isTrue()
        assertThat(isVendorEndpoint("api.deepseek.com", domains)).isTrue()
        assertThat(isVendorEndpoint("https://API.DeepSeek.com.", domains)).isTrue()
        assertThat(isVendorEndpoint("https://notdeepseek.com", domains)).isFalse()
        assertThat(isVendorEndpoint("https://api.deepseek.com.evil.io", domains)).isFalse()
        assertThat(isVendorEndpoint("http://127.0.0.1:8080", domains)).isFalse()
        assertThat(isVendorEndpoint("not a url", domains)).isFalse()
    }
}
