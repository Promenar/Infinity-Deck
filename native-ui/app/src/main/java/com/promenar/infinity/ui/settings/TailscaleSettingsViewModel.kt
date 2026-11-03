package com.promenar.infinity.ui.settings

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.tailnet.TailnetController
import com.promenar.infinity.data.tailnet.TailnetSettings
import com.promenar.infinity.data.tailnet.TailnetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
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

data class TailscaleSettingsUiState(
    val available: Boolean,
    val settings: TailnetSettings,
    val tailnet: TailnetState,
    val busy: Boolean = false,
    val controlUrlInvalid: Boolean = false,
    /** 最近一次保存的 auth key 格式不正确；在编辑面板内提示，面板会遮住页面底部的通知。 */
    val authKeyInvalid: Boolean = false,
    val notice: TailscaleNotice? = null,
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
    )

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
