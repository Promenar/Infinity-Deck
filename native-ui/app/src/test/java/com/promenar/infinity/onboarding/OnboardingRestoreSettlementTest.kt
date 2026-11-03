package com.promenar.infinity.onboarding

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.navigation.initialOnboardingDestination
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OnboardingRestoreSettlementTest {
    private lateinit var context: Context
    private val persistedLanguages = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(OnboardingStateStore.PREFERENCES_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences("infinity_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
        persistedLanguages.clear()
    }

    private fun store(linuxOffered: Boolean = false) = OnboardingStateStore(context, linuxOffered)

    /** 启动期结算总是用新的 store 实例读取持久化状态，与进程重启后一致。 */
    private fun settleAfterRestart(committed: Boolean, linuxOffered: Boolean = false): Boolean =
        settleOnboardingRestore(store(linuxOffered), committed) { persistedLanguages += it; true }

    @Test
    fun `向导发起的恢复提交成功后引导记为完成并保留语言`() {
        val wizard = store()
        assertThat(wizard.selectLanguage("zh")).isTrue()
        assertThat(wizard.recordRestoreStaged("tx-1")).isTrue()
        assertThat(wizard.stagedRestoreTxId).isEqualTo("tx-1")

        assertThat(settleAfterRestart(committed = true)).isTrue()

        val restarted = store()
        assertThat(restarted.state.value.step).isEqualTo(OnboardingStep.COMPLETED)
        assertThat(restarted.state.value.languageCode).isEqualTo("zh")
        assertThat(restarted.stagedRestoreTxId).isNull()
        assertThat(initialOnboardingDestination(restarted.state.value.step)).isEqualTo(NavDestinations.MAIN_TAB_SCAFFOLD)
        // 备份带回的语言被向导里选的语言覆盖。
        assertThat(persistedLanguages).containsExactly("zh")
        assertThat(
            context.getSharedPreferences("infinity_prefs", Context.MODE_PRIVATE).getBoolean("has_shown_welcome", false),
        ).isTrue()
    }

    @Test
    fun `提交成功时清除向导里填过的提供商检查点`() {
        val wizard = store()
        assertThat(wizard.selectLanguage("en")).isTrue()
        assertThat(wizard.recordProviderConnected("provider")).isTrue()
        assertThat(wizard.returnToProvider()).isTrue()
        assertThat(wizard.recordRestoreStaged("tx-2")).isTrue()

        assertThat(settleAfterRestart(committed = true)).isTrue()

        val state = store().state.value
        assertThat(state.step).isEqualTo(OnboardingStep.COMPLETED)
        assertThat(state.languageCode).isEqualTo("en")
        assertThat(state.providerId).isNull()
        assertThat(state.modelId).isNull()
    }

    @Test
    fun `提交失败或回滚时引导保持原步骤且撤销标记`() {
        val wizard = store()
        assertThat(wizard.selectLanguage("zh")).isTrue()
        assertThat(wizard.recordProviderConnected("provider")).isTrue()
        assertThat(wizard.returnToProvider()).isTrue()
        assertThat(wizard.recordRestoreStaged("tx-3")).isTrue()

        assertThat(settleAfterRestart(committed = false)).isFalse()

        val restarted = store()
        assertThat(restarted.state.value.step).isEqualTo(OnboardingStep.PROVIDER)
        assertThat(restarted.state.value.providerId).isEqualTo("provider")
        assertThat(restarted.state.value.languageCode).isEqualTo("zh")
        assertThat(restarted.stagedRestoreTxId).isNull()
        assertThat(initialOnboardingDestination(restarted.state.value.step)).isEqualTo(NavDestinations.WELCOME)
        assertThat(persistedLanguages).isEmpty()
        // 标记已撤销，之后的启动即使提交了别处发起的恢复也不会跳过向导。
        assertThat(settleAfterRestart(committed = true)).isFalse()
        assertThat(store().state.value.step).isEqualTo(OnboardingStep.PROVIDER)
    }

    @Test
    fun `没有向导标记时提交恢复不改动引导`() {
        val wizard = store()
        assertThat(wizard.selectLanguage("zh")).isTrue()

        assertThat(settleAfterRestart(committed = true)).isFalse()

        assertThat(store().state.value.step).isEqualTo(OnboardingStep.PROVIDER)
        assertThat(persistedLanguages).isEmpty()
    }

    @Test
    fun `Linux步骤发起的恢复同样完成引导且不经过Linux安装`() {
        val wizard = store(linuxOffered = true)
        assertThat(wizard.selectLanguage("en")).isTrue()
        assertThat(wizard.state.value.step).isEqualTo(OnboardingStep.LINUX)
        assertThat(wizard.recordRestoreStaged("tx-4")).isTrue()

        // 应用启动时以默认参数构造 store；停在 Linux 的检查点也必须能结算。
        assertThat(settleAfterRestart(committed = true, linuxOffered = false)).isTrue()

        assertThat(store(linuxOffered = true).state.value.step).isEqualTo(OnboardingStep.COMPLETED)
    }

    @Test
    fun `引导已完成或事务号为空时拒绝记录标记且丢弃暂存会撤销标记`() {
        val wizard = store()
        assertThat(wizard.recordRestoreStaged(" ")).isFalse()
        assertThat(wizard.recordRestoreStaged("tx-5")).isTrue()
        assertThat(wizard.clearRestoreStaged()).isTrue()
        assertThat(wizard.stagedRestoreTxId).isNull()
        assertThat(wizard.clearRestoreStaged()).isTrue()

        assertThat(wizard.recordRestoreStaged("tx-6")).isTrue()
        assertThat(settleAfterRestart(committed = true)).isTrue()
        val completed = store()
        assertThat(completed.recordRestoreStaged("tx-7")).isFalse()
        assertThat(completed.stagedRestoreTxId).isNull()
    }

    @Test
    fun `结算对重复启动幂等`() {
        val wizard = store()
        assertThat(wizard.selectLanguage("zh")).isTrue()
        assertThat(wizard.recordRestoreStaged("tx-8")).isTrue()

        assertThat(settleAfterRestart(committed = true)).isTrue()
        assertThat(settleAfterRestart(committed = true)).isFalse()
        assertThat(settleAfterRestart(committed = false)).isFalse()

        assertThat(store().state.value.step).isEqualTo(OnboardingStep.COMPLETED)
        assertThat(persistedLanguages).containsExactly("zh")
    }
}
