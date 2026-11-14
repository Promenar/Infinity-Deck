package com.promenar.infinity.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingStateStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("infinity_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private fun store(linuxOffered: Boolean = false) = OnboardingStateStore(context, linuxOffered)

    @Test
    fun `连接成功一次即进入选模型而没有单独的验证连接步骤`() {
        val store = store()

        assertThat(OnboardingStep.entries.map { it.name }).doesNotContain("CONNECTION")
        assertThat(store.recordProviderConnected("provider")).isFalse()
        assertThat(store.selectLanguage("zh")).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.PROVIDER)
        assertThat(store.recordProviderConnected(" ")).isFalse()
        assertThat(store.recordProviderConnected("provider")).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.MODEL)
        assertThat(store.state.value.providerId).isEqualTo("provider")
        // 已在选模型时再记录连接不会重复推进。
        assertThat(store.recordProviderConnected("provider")).isFalse()
    }

    @Test
    fun `支持Linux时语言之后进入Linux步骤且安装或跳过都推进到连接`() {
        val store = store(linuxOffered = true)

        assertThat(store.completeLinuxStep()).isFalse()
        assertThat(store.selectLanguage("en")).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.LINUX)
        assertThat(store.recordProviderConnected("provider")).isFalse()
        assertThat(store.completeLinuxStep()).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.PROVIDER)
        assertThat(store.completeLinuxStep()).isFalse()
    }

    @Test
    fun `不支持Linux时语言之后直接连接提供商`() {
        val store = store(linuxOffered = false)

        assertThat(store.selectLanguage("zh")).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.PROVIDER)
        assertThat(store.completeLinuxStep()).isFalse()
    }

    @Test
    fun `无模型时保持阻断且仅有效模型可推进到Agent`() {
        val store = store()
        assertThat(store.selectLanguage("en")).isTrue()
        assertThat(store.recordProviderConnected("provider")).isTrue()
        assertThat(store.selectModel(null)).isFalse()
        assertThat(store.selectModel("  ")).isFalse()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.MODEL)
        assertThat(store.selectModel("provider::model")).isTrue()
        assertThat(store.state.value.modelId).isEqualTo("provider::model")
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.AGENT)
    }

    @Test
    fun `选模型可返回连接提供商且创建Agent可返回选模型`() {
        val store = store()
        assertThat(store.selectLanguage("zh")).isTrue()
        assertThat(store.recordProviderConnected("provider")).isTrue()
        assertThat(store.recordAgentCreated("agent", "session")).isFalse()
        assertThat(store.returnToProvider()).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.PROVIDER)
        // 返回后保留已保存的提供商，重新连接时更新同一个而不是新增。
        assertThat(store.state.value.providerId).isEqualTo("provider")

        assertThat(store.recordProviderConnected("provider")).isTrue()
        assertThat(store.selectModel("provider::model")).isTrue()
        assertThat(store.returnToModel()).isTrue()
        assertThat(store.state.value).isEqualTo(
            OnboardingState(step = OnboardingStep.MODEL, languageCode = "zh", providerId = "provider"),
        )
    }

    @Test
    fun `首聊只有目标会话最终成功态才完成`() {
        val store = store()
        assertThat(store.selectLanguage("en")).isTrue()
        assertThat(store.recordProviderConnected("provider")).isTrue()
        assertThat(store.selectModel("provider::model")).isTrue()
        assertThat(store.recordAgentCreated("agent", "session")).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.FIRST_CHAT)
        assertThat(store.returnToModel()).isFalse()

        assertThat(store.recordFirstChatResult("other", assistantSucceeded = true)).isFalse()
        assertThat(store.recordFirstChatResult("session", assistantSucceeded = false)).isFalse()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.FIRST_CHAT)
        assertThat(store.recordFirstChatResult("session", assistantSucceeded = true)).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.RECORDING_SETUP)
        assertThat(store.completeRecordingSetup()).isTrue()
        assertThat(store.state.value.step).isEqualTo(OnboardingStep.COMPLETED)
        assertThat(context.getSharedPreferences("infinity_prefs", Context.MODE_PRIVATE)
            .getBoolean("has_shown_welcome", false)).isTrue()
    }

    @Test
    fun `旋转或进程重建从最后成功检查点恢复`() {
        val first = store()
        assertThat(first.selectLanguage("zh")).isTrue()
        assertThat(first.recordProviderConnected("provider-id")).isTrue()

        assertThat(store().state.value).isEqualTo(
            OnboardingState(
                step = OnboardingStep.MODEL,
                languageCode = "zh",
                providerId = "provider-id",
            )
        )
    }

    @Test
    fun `旧版本停在验证连接的检查点读入为连接提供商并保留提供商`() {
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE).edit()
            .putString("step", "CONNECTION")
            .putString("language", "zh")
            .putString("provider_id", "default")
            .commit()

        val restored = store()

        assertThat(restored.state.value).isEqualTo(
            OnboardingState(step = OnboardingStep.PROVIDER, languageCode = "zh", providerId = "default"),
        )
        assertThat(restored.recordProviderConnected("default")).isTrue()
        assertThat(restored.state.value.step).isEqualTo(OnboardingStep.MODEL)
    }

    @Test
    fun `停在Linux步骤但当前版本不支持时恢复为连接提供商`() {
        val offered = store(linuxOffered = true)
        assertThat(offered.selectLanguage("en")).isTrue()
        assertThat(offered.state.value.step).isEqualTo(OnboardingStep.LINUX)

        assertThat(store(linuxOffered = false).state.value.step).isEqualTo(OnboardingStep.PROVIDER)
        assertThat(store(linuxOffered = true).state.value.step).isEqualTo(OnboardingStep.LINUX)
    }

    @Test
    fun `历史已完成欢迎页迁移为完成态`() {
        context.getSharedPreferences("infinity_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("has_shown_welcome", true).commit()

        assertThat(store().state.value.step).isEqualTo(OnboardingStep.COMPLETED)
    }

    @Test
    fun `只有侧载渠道且主ABI为arm64时引导提供Linux`() {
        assertThat(onboardingOffersLinux(linuxRuntimeAvailable = true, primaryAbi = "arm64-v8a")).isTrue()
        assertThat(onboardingOffersLinux(linuxRuntimeAvailable = false, primaryAbi = "arm64-v8a")).isFalse()
        assertThat(onboardingOffersLinux(linuxRuntimeAvailable = true, primaryAbi = "x86_64")).isFalse()
        assertThat(onboardingOffersLinux(linuxRuntimeAvailable = true, primaryAbi = null)).isFalse()
    }
}
