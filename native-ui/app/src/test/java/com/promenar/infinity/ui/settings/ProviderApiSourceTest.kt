package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetState
import org.junit.Test

class ProviderApiSourceTest {
    private val running = TailnetState(TailnetPhase.RUNNING)

    @Test
    fun `未手动切换时 API 源跟随地址自动识别`() {
        val tailnet = providerApiSourceState("https://gpu-box.tail3f9c.ts.net/v1", manual = null, running)
        assertThat(tailnet.selected).isEqualTo(ProviderApiSource.TAILNET)
        assertThat(tailnet.hint).isEqualTo(ProviderApiSourceHint.RECOGNIZED_TAILNET)
        val public = providerApiSourceState("https://api.deepseek.com/v1", manual = null, running)
        assertThat(public.selected).isEqualTo(ProviderApiSource.PUBLIC)
        assertThat(public.hint).isNull()
    }

    @Test
    fun `手动选择与地址不一致时保留选择并提示一键改回`() {
        val publicChosen = providerApiSourceState("https://gpu-box.tail3f9c.ts.net/v1", ProviderApiSource.PUBLIC, running)
        assertThat(publicChosen.selected).isEqualTo(ProviderApiSource.PUBLIC)
        assertThat(publicChosen.hint).isEqualTo(ProviderApiSourceHint.ADDRESS_IS_TAILNET)

        val tailnetChosen = providerApiSourceState("https://api.deepseek.com/v1", ProviderApiSource.TAILNET, running)
        assertThat(tailnetChosen.selected).isEqualTo(ProviderApiSource.TAILNET)
        assertThat(tailnetChosen.hint).isEqualTo(ProviderApiSourceHint.ADDRESS_IS_PUBLIC)

        // 还没填地址时选 Tailscale 不算不一致，可以从设备列表填入。
        assertThat(providerApiSourceState("", ProviderApiSource.TAILNET, running).hint).isNull()
    }

    @Test fun `tailnet的HTTP地址保留选路提示`() {
        assertThat(providerApiSourceState("http://100.101.7.42:8000/v1", null, running).hint)
            .isEqualTo(ProviderApiSourceHint.RECOGNIZED_TAILNET)
        assertThat(providerApiSourceState("http://100.101.7.42:8000/v1", ProviderApiSource.PUBLIC, running).hint)
            .isEqualTo(ProviderApiSourceHint.ADDRESS_IS_TAILNET)
        assertThat(providerApiSourceState("http://api.example.com/v1", null, running).hint).isNull()
    }

    @Test
    fun `内置 Tailscale 状态行跟随相位`() {
        val cases = mapOf(
            TailnetPhase.RUNNING to BuiltInTailscaleStatus.CONNECTED,
            TailnetPhase.STARTING to BuiltInTailscaleStatus.CONNECTING,
            TailnetPhase.STOPPED to BuiltInTailscaleStatus.CONNECTING,
            TailnetPhase.NEEDS_LOGIN to BuiltInTailscaleStatus.NEEDS_LOGIN,
            TailnetPhase.FAILED to BuiltInTailscaleStatus.FAILED,
            TailnetPhase.DISABLED to BuiltInTailscaleStatus.DISABLED,
            TailnetPhase.UNAVAILABLE to BuiltInTailscaleStatus.UNAVAILABLE,
        )
        cases.forEach { (phase, expected) ->
            assertThat(providerApiSourceState("https://x.ts.net", null, TailnetState(phase)).builtIn).isEqualTo(expected)
        }
    }
}
