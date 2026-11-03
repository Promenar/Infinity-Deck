package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.tailnet.TailnetController
import com.promenar.infinity.data.tailnet.TailnetPaths
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPingResult
import com.promenar.infinity.data.tailnet.TailnetSettings
import com.promenar.infinity.data.tailnet.TailnetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 设置页提示；界面按枚举显示文案（ViewModel 不读取资源）。 */
enum class TailscaleNotice {
    AUTH_KEY_SAVED,
    AUTH_KEY_CLEARED,
    CONTROL_URL_SAVED,
    LOGIN_UNAVAILABLE,
    LOGGED_OUT,
    LOGOUT_FAILED,
    OPERATION_FAILED,
}

/** 一次路径检测为何结束。 */
enum class TailscalePingFinish {
    /** 连续两次直连，路径已稳定。 */
    DIRECT,
    /** 发满 [TAILSCALE_PING_MAX_ATTEMPTS] 次仍未直连。 */
    EXHAUSTED,
    /** 连续 [TAILSCALE_PING_MAX_FAILURES] 次超时或失败。 */
    UNREACHABLE,
    /** Tailscale 未运行或设备已无地址，无法检测。 */
    UNAVAILABLE,
    /** 用户中途停止。 */
    STOPPED,
}

/** 设备面板里对 [peerName] 的一轮检测：逐次结果按发送顺序排列。 */
data class TailscalePingRun(
    val peerName: String,
    val samples: List<TailnetPingResult> = emptyList(),
    val finish: TailscalePingFinish? = null,
) {
    val running: Boolean get() = finish == null
}

internal const val TAILSCALE_PING_MAX_ATTEMPTS = 10
internal const val TAILSCALE_PING_MAX_FAILURES = 3
internal const val TAILSCALE_PING_INTERVAL_MS = 400L

/** 与桌面端 tailscale ping 相同：打通直连即停，否则最多发 [TAILSCALE_PING_MAX_ATTEMPTS] 次；仍在进行时返回 null。 */
internal fun tailscalePingFinish(samples: List<TailnetPingResult>): TailscalePingFinish? {
    if (samples.size >= 2 && samples.takeLast(2).all { it.path == TailnetPaths.DIRECT }) return TailscalePingFinish.DIRECT
    if (samples.size >= TAILSCALE_PING_MAX_FAILURES && samples.takeLast(TAILSCALE_PING_MAX_FAILURES).all { it.path == null }) {
        return TailscalePingFinish.UNREACHABLE
    }
    return if (samples.size >= TAILSCALE_PING_MAX_ATTEMPTS) TailscalePingFinish.EXHAUSTED else null
}

data class TailscaleSettingsUiState(
    val available: Boolean,
    val settings: TailnetSettings,
    val tailnet: TailnetState,
    val busy: Boolean = false,
    val controlUrlInvalid: Boolean = false,
    /** 最近一次保存的 auth key 格式不正确；在编辑面板内提示，面板会遮住页面底部的通知。 */
    val authKeyInvalid: Boolean = false,
    val notice: TailscaleNotice? = null,
    /** 设备面板正在显示的检测；面板关闭后清空。 */
    val ping: TailscalePingRun? = null,
    /** 本次打开设置页后各设备最近一次有路径的检测结果，列表行据此显示延迟。 */
    val lastPing: Map<String, TailnetPingResult> = emptyMap(),
)

sealed interface TailscaleSettingsEvent {
    /** 在系统浏览器打开登录链接；链接不写入状态与日志。 */
    class OpenLogin(val url: String) : TailscaleSettingsEvent {
        override fun toString(): String = "OpenLogin"
    }
}

class TailscaleSettingsViewModel(private val controller: TailnetController) : ViewModel() {
    private data class Local(
        val busy: Boolean = false,
        val controlUrlInvalid: Boolean = false,
        val authKeyInvalid: Boolean = false,
        val notice: TailscaleNotice? = null,
        val ping: TailscalePingRun? = null,
        val lastPing: Map<String, TailnetPingResult> = emptyMap(),
    )

    private var pingJob: Job? = null
    private val local = MutableStateFlow(Local())
    private val events = Channel<TailscaleSettingsEvent>(Channel.BUFFERED)
    val effects: Flow<TailscaleSettingsEvent> = events.receiveAsFlow()

    val uiState: StateFlow<TailscaleSettingsUiState> = combine(controller.settings, controller.state, local) { settings, tailnet, local ->
        TailscaleSettingsUiState(
            available = controller.available,
            settings = settings,
            tailnet = tailnet,
            busy = local.busy,
            controlUrlInvalid = local.controlUrlInvalid,
            authKeyInvalid = local.authKeyInvalid,
            notice = local.notice,
            ping = local.ping,
            lastPing = local.lastPing,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        TailscaleSettingsUiState(controller.available, controller.settings.value, controller.state.value),
    )

    fun consumeNotice() = local.update { it.copy(notice = null) }

    fun setEnabled(enabled: Boolean) = perform { controller.setEnabled(enabled) }

    fun setEphemeral(ephemeral: Boolean) = perform { controller.setEphemeral(ephemeral) }

    fun saveControlUrl(raw: String) = perform {
        val saved = controller.setControlUrl(raw)
        local.update { it.copy(controlUrlInvalid = !saved, notice = if (saved) TailscaleNotice.CONTROL_URL_SAVED else null) }
    }

    fun clearControlUrlError() = local.update { it.copy(controlUrlInvalid = false) }

    /** [secret] 由本方法清零。 */
    fun saveAuthKey(secret: CharArray) = perform {
        val saved = controller.setAuthKey(secret)
        local.update { it.copy(authKeyInvalid = !saved, notice = if (saved) TailscaleNotice.AUTH_KEY_SAVED else null) }
    }

    fun clearAuthKeyError() = local.update { it.copy(authKeyInvalid = false) }

    fun clearAuthKey() = perform {
        controller.clearAuthKey()
        notice(TailscaleNotice.AUTH_KEY_CLEARED)
    }

    suspend fun revealAuthKey(): CharArray? = controller.revealAuthKey()

    fun login() = perform {
        val url = controller.login()
        if (url != null) events.send(TailscaleSettingsEvent.OpenLogin(url)) else notice(TailscaleNotice.LOGIN_UNAVAILABLE)
    }

    fun logout() = perform {
        notice(if (controller.logout()) TailscaleNotice.LOGGED_OUT else TailscaleNotice.LOGOUT_FAILED)
    }

    fun retry() = controller.retry()

    fun refresh() {
        viewModelScope.launch {
            try {
                controller.refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 状态读取失败时保留当前状态。
            }
        }
    }

    /** 打开设备面板时调用：取消上一轮，从头检测 [peer] 的连接路径。 */
    fun startPing(peer: TailnetPeer) {
        pingJob?.cancel()
        local.update { it.copy(ping = TailscalePingRun(peer.name)) }
        pingJob = viewModelScope.launch {
            var samples = emptyList<TailnetPingResult>()
            while (true) {
                val result = try {
                    controller.ping(peer)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
                if (result == null) {
                    finishPing(peer.name, samples, TailscalePingFinish.UNAVAILABLE)
                    return@launch
                }
                samples = samples + result
                val finish = tailscalePingFinish(samples)
                local.update { state ->
                    val run = state.ping?.takeIf { it.peerName == peer.name } ?: return@update state
                    state.copy(
                        ping = run.copy(samples = samples, finish = finish),
                        lastPing = if (result.path != null) state.lastPing + (peer.name to result) else state.lastPing,
                    )
                }
                if (finish != null) break
                delay(TAILSCALE_PING_INTERVAL_MS)
            }
            // 检测会让 Tailscale 建立或升级路径，结束后刷新列表里的被动路径。
            refresh()
        }
    }

    /** 面板内“停止”：保留已有结果。 */
    fun stopPing() {
        val run = local.value.ping ?: return
        if (!run.running) return
        pingJob?.cancel()
        finishPing(run.peerName, run.samples, TailscalePingFinish.STOPPED)
    }

    /** 关闭设备面板：停止检测并清空面板状态，最近结果仍留给列表行。 */
    fun dismissPing() {
        pingJob?.cancel()
        pingJob = null
        local.update { it.copy(ping = null) }
    }

    private fun finishPing(peerName: String, samples: List<TailnetPingResult>, finish: TailscalePingFinish) = local.update { state ->
        val run = state.ping?.takeIf { it.peerName == peerName } ?: return@update state
        state.copy(ping = run.copy(samples = samples, finish = finish))
    }

    private fun notice(value: TailscaleNotice) = local.update { it.copy(notice = value) }

    private fun perform(action: suspend () -> Unit) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, notice = null) }
        viewModelScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice(TailscaleNotice.OPERATION_FAILED)
            } finally {
                local.update { it.copy(busy = false) }
            }
        }
    }

    companion object {
        fun factory(application: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TailscaleSettingsViewModel((application as InfinityApplication).tailnetManager) as T
        }
    }
}
