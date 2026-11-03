package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.tailnet.TailnetController
import com.promenar.infinity.data.tailnet.TailnetPaths
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPingResult
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
import kotlinx.coroutines.test.advanceUntilIdle
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
        /** 依次返回的 Ping 结果；用尽后返回 [pingFallback]。 */
        val pingReplies = ArrayDeque<TailnetPingResult?>()
        var pingFallback: TailnetPingResult? = TailnetPingResult(timedOut = true)
        var pings = 0
        var refreshes = 0
        override suspend fun ping(peer: TailnetPeer): TailnetPingResult? {
            pings++
            return if (pingReplies.isEmpty()) pingFallback else pingReplies.removeFirst()
        }
        override suspend fun refresh(): TailnetState {
            refreshes++
            return state.value
        }
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

    private val workstation = TailnetPeer("workstation", ips = listOf("100.64.0.12"), online = true)
    private fun derp(ms: Double) = TailnetPingResult(path = TailnetPaths.DERP, relayRegion = "hkg", latencyMs = ms)
    private fun direct(ms: Double) = TailnetPingResult(path = TailnetPaths.DIRECT, directLan = true, latencyMs = ms)

    @Test
    fun `先中转后打通：连续两次直连即停，并记下最近结果供列表显示`() = runTest(dispatcher) {
        controller.pingReplies.addAll(listOf(derp(92.0), derp(88.0), direct(3.1), direct(2.4), direct(2.2)))
        viewModel.startPing(workstation)
        assertThat(viewModel.uiState.value.ping?.running).isTrue()
        advanceUntilIdle()
        val run = viewModel.uiState.value.ping!!
        assertThat(run.finish).isEqualTo(TailscalePingFinish.DIRECT)
        assertThat(run.samples.map { it.path })
            .containsExactly(TailnetPaths.DERP, TailnetPaths.DERP, TailnetPaths.DIRECT, TailnetPaths.DIRECT).inOrder()
        assertThat(controller.pings).isEqualTo(4)
        assertThat(viewModel.uiState.value.lastPing["workstation"]?.latencyMs).isEqualTo(2.4)
        // 检测结束后刷新列表里的被动路径。
        assertThat(controller.refreshes).isAtLeast(1)
    }

    @Test
    fun `始终中转发满上限，连续超时提前判为无响应`() = runTest(dispatcher) {
        controller.pingFallback = derp(86.0)
        viewModel.startPing(workstation)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.ping?.finish).isEqualTo(TailscalePingFinish.EXHAUSTED)
        assertThat(controller.pings).isEqualTo(TAILSCALE_PING_MAX_ATTEMPTS)

        controller.pings = 0
        controller.pingFallback = TailnetPingResult(timedOut = true)
        viewModel.startPing(workstation)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.ping?.finish).isEqualTo(TailscalePingFinish.UNREACHABLE)
        assertThat(controller.pings).isEqualTo(TAILSCALE_PING_MAX_FAILURES)
        // 超时不覆盖列表上次有路径的结果。
        assertThat(viewModel.uiState.value.lastPing["workstation"]?.path).isEqualTo(TailnetPaths.DERP)
    }

    @Test
    fun `未运行时结束为不可用，停止保留已有结果，关闭面板清空检测`() = runTest(dispatcher) {
        controller.pingReplies.add(null)
        viewModel.startPing(workstation)
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.ping?.finish).isEqualTo(TailscalePingFinish.UNAVAILABLE)
        assertThat(viewModel.uiState.value.ping?.samples).isEmpty()

        controller.pingFallback = derp(80.0)
        viewModel.startPing(workstation)
        assertThat(viewModel.uiState.value.ping?.samples).hasSize(1)
        viewModel.stopPing()
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.ping?.finish).isEqualTo(TailscalePingFinish.STOPPED)
        assertThat(viewModel.uiState.value.ping?.samples).hasSize(1)
        assertThat(controller.pings).isEqualTo(2)

        viewModel.dismissPing()
        assertThat(viewModel.uiState.value.ping).isNull()
        assertThat(viewModel.uiState.value.lastPing).containsKey("workstation")
    }

    @Test
    fun `结束判定`() {
        assertThat(tailscalePingFinish(listOf(derp(90.0)))).isNull()
        assertThat(tailscalePingFinish(listOf(direct(2.0)))).isNull()
        assertThat(tailscalePingFinish(listOf(derp(90.0), direct(2.0), direct(2.0)))).isEqualTo(TailscalePingFinish.DIRECT)
        assertThat(tailscalePingFinish(listOf(direct(2.0), derp(90.0)))).isNull()
        val timeout = TailnetPingResult(timedOut = true)
        assertThat(tailscalePingFinish(listOf(derp(90.0), timeout, timeout))).isNull()
        assertThat(tailscalePingFinish(listOf(timeout, timeout, timeout))).isEqualTo(TailscalePingFinish.UNREACHABLE)
        assertThat(tailscalePingFinish(List(TAILSCALE_PING_MAX_ATTEMPTS) { derp(90.0) })).isEqualTo(TailscalePingFinish.EXHAUSTED)
    }
}
