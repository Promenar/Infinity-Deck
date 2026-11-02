package com.promenar.infinity.ui.common

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.protocol.ProtocolType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en")
class ProviderDisplayNameTest {
    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources

    private fun display(name: String, protocol: ProtocolType?) = providerDisplayName(name, protocol, resources)

    @Test
    fun `旧版本写入的默认名称按当前语言显示为协议名`() {
        assertThat(display("OpenAI 兼容 (通用)", ProtocolType.Generic_OpenAI_Compat)).isEqualTo("OpenAI Compatible (Generic)")
        assertThat(display("通义千问 (Qwen)", ProtocolType.Qwen_DashScope)).isEqualTo("Qwen (DashScope)")
        assertThat(display("本地模型", ProtocolType.Local)).isEqualTo("Local inference")
        assertThat(display("本地推理", ProtocolType.Local)).isEqualTo("Local inference")
    }

    @Test
    fun `模型快照里的默认名不依赖协议也能识别`() {
        assertThat(display("智谱 (GLM)", protocol = null)).isEqualTo("Zhipu (GLM)")
        assertThat(display("本地模型", protocol = null)).isEqualTo("Local inference")
        assertThat(display("Cloud", protocol = null)).isEqualTo("Provider")
        assertThat(display("", protocol = null)).isEqualTo("Provider")
    }

    @Test
    fun `空名称回退为协议名`() {
        assertThat(display("  ", ProtocolType.Doubao_ByteDance)).isEqualTo("Doubao (ByteDance)")
    }

    @Test
    fun `用户自己起的名称原样显示`() {
        assertThat(display("我的网关", ProtocolType.Generic_OpenAI_Compat)).isEqualTo("我的网关")
        assertThat(display("通义千问", ProtocolType.Qwen_DashScope)).isEqualTo("通义千问")
        assertThat(display("Cloud", ProtocolType.Generic_OpenAI_Compat)).isEqualTo("Cloud")
    }

    @Test
    fun `表单只把默认名视为未命名`() {
        assertThat(isDefaultProviderName("OpenAI 兼容 (通用)")).isTrue()
        assertThat(isDefaultProviderName("本地模型")).isTrue()
        assertThat(isDefaultProviderName("")).isTrue()
        assertThat(isDefaultProviderName("DeepSeek Prod")).isFalse()
    }
}
