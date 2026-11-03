package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.TailnetController
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetSettings
import com.promenar.infinity.data.tailnet.TailnetSettingsPolicy
import com.promenar.infinity.data.tailnet.TailnetSettingsProblem
import com.promenar.infinity.data.tailnet.TailnetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TailscaleSettingsViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private class FakeController : TailnetController {
        override var available = true
        override val state = MutableStateFlow(TailnetState(TailnetPhase.DISABLED))
        override val settings = MutableStateFlow(TailnetSettings())
        var loginUrl: String? = "https://login.example.test/a/xyz"
        var logoutResult = true
        var savedKey: String? = null
        var retried = 0
        var failNext = false

        override fun start() = Unit
        override fun retry() {
            retried++
        }
        override suspend fun refresh(): TailnetState = state.value
        override suspend fun login(): String? = loginUrl
        override suspend fun logout(): Boolean = logoutResult
        override suspend fun setEnabled(enabled: Boolean) {
            if (failNext) throw IllegalStateException("写入失败")
            settings.value = settings.value.copy(enabled = enabled)
            state.value = TailnetState(if (enabled) TailnetPhase.STARTING else TailnetPhase.DISABLED)
        }
        override suspend fun setEphemeral(ephemeral: Boolean) {
            settings.value = settings.value.copy(ephemeral = ephemeral)
        }
        override suspend fun setControlUrl(raw: String): Boolean {
            val value = TailnetSettingsPolicy.normalizeControlUrl(raw) ?: return false
            settings.value = settings.value.copy(controlUrl = value)
            return true
        }

        override suspend fun setAuthKey(raw: CharArray): Boolean {
            val value = String(raw)
            raw.fill('\u0000')
            if (!TailnetSettingsPolicy.validAuthKey(value)) return false
            savedKey = value
            settings.value = settings.value.copy(hasAuthKey = true, authKeyRevision = settings.value.authKeyRevision + 1)
            return true
        }
        override suspend fun clearAuthKey() {
            savedKey = null
            settings.value = settings.value.copy(hasAuthKey = false)
        }
        override suspend fun revealAuthKey(): CharArray? = savedKey?.toCharArray()
    }

    private val controller = FakeController()
    private val viewModel by lazy { TailscaleSettingsViewModel(controller) }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)
    @After fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `开关与状态跟随控制器`() = runTest(dispatcher) {
        assertThat(viewModel.uiState.value.settings.enabled).isFalse()
        viewModel.setEnabled(true)
        assertThat(viewModel.uiState.value.settings.enabled).isTrue()
        assertThat(viewModel.uiState.value.tailnet.phase).isEqualTo(TailnetPhase.STARTING)
        assertThat(viewModel.uiState.value.busy).isFalse()
        controller.failNext = true
        viewModel.setEnabled(false)
        assertThat(viewModel.uiState.value.notice).isEqualTo(TailscaleNotice.OPERATION_FAILED)
        viewModel.consumeNotice()
        assertThat(viewModel.uiState.value.notice).isNull()
    }

    @Test
    fun `登录发出打开浏览器事件，链接不进入界面状态`() = runTest(dispatcher) {
        viewModel.login()
        val event = viewModel.effects.first() as TailscaleSettingsEvent.OpenLogin
        assertThat(event.url).isEqualTo("https://login.example.test/a/xyz")
        assertThat(event.toString()).doesNotContain("xyz")
        assertThat(viewModel.uiState.value.toString()).doesNotContain("xyz")
        controller.loginUrl = null
        viewModel.login()
        assertThat(viewModel.uiState.value.notice).isEqualTo(TailscaleNotice.LOGIN_UNAVAILABLE)
    }

    @Test
    fun `控制服务器校验失败时标记错误`() = runTest(dispatcher) {
        viewModel.saveControlUrl("https://user:pass@hs.example.org")
        assertThat(viewModel.uiState.value.controlUrlInvalid).isTrue()
        viewModel.clearControlUrlError()
        viewModel.saveControlUrl("https://hs.example.org")
        assertThat(viewModel.uiState.value.controlUrlInvalid).isFalse()
        assertThat(viewModel.uiState.value.settings.controlUrl).isEqualTo("https://hs.example.org")
        assertThat(viewModel.uiState.value.notice).isEqualTo(TailscaleNotice.CONTROL_URL_SAVED)


    }

    @Test
    fun `auth key 保存后清零输入，界面只知道是否已保存`() = runTest(dispatcher) {
        val input = "tskey-auth-SECRETVALUE".toCharArray()
        viewModel.saveAuthKey(input)
        assertThat(input.all { it == '\u0000' }).isTrue()
        assertThat(viewModel.uiState.value.settings.hasAuthKey).isTrue()
        assertThat(viewModel.uiState.value.notice).isEqualTo(TailscaleNotice.AUTH_KEY_SAVED)
        assertThat(viewModel.uiState.value.toString()).doesNotContain("SECRETVALUE")
        assertThat(String(viewModel.revealAuthKey()!!)).isEqualTo("tskey-auth-SECRETVALUE")
        viewModel.consumeNotice()
        viewModel.saveAuthKey("bad key".toCharArray())
        assertThat(viewModel.uiState.value.authKeyInvalid).isTrue()
        assertThat(viewModel.uiState.value.notice).isNull()
        assertThat(viewModel.uiState.value.settings.hasAuthKey).isTrue()
        viewModel.clearAuthKeyError()
        assertThat(viewModel.uiState.value.authKeyInvalid).isFalse()
        viewModel.clearAuthKey()
        assertThat(viewModel.uiState.value.settings.hasAuthKey).isFalse()
    }

    @Test
    fun `退出登录与重试`() = runTest(dispatcher) {
        viewModel.logout()
        assertThat(viewModel.uiState.value.notice).isEqualTo(TailscaleNotice.LOGGED_OUT)
        controller.logoutResult = false
        viewModel.logout()
        assertThat(viewModel.uiState.value.notice).isEqualTo(TailscaleNotice.LOGOUT_FAILED)
        viewModel.retry()
        assertThat(controller.retried).isEqualTo(1)
    }
}
