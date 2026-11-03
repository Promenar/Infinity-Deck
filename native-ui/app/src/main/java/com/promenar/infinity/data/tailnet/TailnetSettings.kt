package com.promenar.infinity.data.tailnet

import android.content.SharedPreferences
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

/** 内置 Tailscale 设置快照；auth key 只记录是否存在与修订号，值留在 [SecretStore]。 */
data class TailnetSettings(
    val enabled: Boolean = false,
    /** 空表示 Tailscale 官方控制服务器。 */
    val controlUrl: String = "",
    val ephemeral: Boolean = false,
    val hasAuthKey: Boolean = false,
    /** auth key 每次写入或删除递增（只在内存中），用于判断是否需要重启节点。 */
    val authKeyRevision: Int = 0,
)

/** 设置校验结果码；界面按码显示文案。 */
enum class TailnetSettingsProblem { CONTROL_URL_INVALID, AUTH_KEY_INVALID }

object TailnetSettingsPolicy {
    const val MAX_AUTH_KEY_LENGTH = 512

    /** 空串合法（官方控制服务器）；否则必须是不含凭据、查询与片段的 http(s) URL。 */
    fun normalizeControlUrl(raw: String): String? {
        val value = raw.trim().removeSuffix("/")
        if (value.isEmpty()) return ""
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        if (uri.host.isNullOrEmpty() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (value.any { it.isWhitespace() || it.isISOControl() }) return null
        return value
    }

    fun validAuthKey(raw: String): Boolean {
        val value = raw.trim()
        return value.isNotEmpty() && value.length <= MAX_AUTH_KEY_LENGTH && value.none { it.isWhitespace() || it.isISOControl() }
    }
}

/**
 * 设置存储：普通设置写入独立的 `infinity_tailnet` 偏好文件（不在 App 备份的命名空间内，系统备份已关闭）；
 * auth key 写入 [SecretStore]（不属于可备份秘密），不进入快照、日志与模型上下文。
 * 调用方在 Dispatchers.IO 上调用写入方法。
 */
class TailnetSettingsStore(
    private val preferences: SharedPreferences,
    private val secrets: SecretStore,
) {
    init { preferences.edit().remove("tailnet_allowed_peers").apply() }

    private val mutable = MutableStateFlow(read(revision = 0))
    val settings: StateFlow<TailnetSettings> = mutable.asStateFlow()

    val current: TailnetSettings get() = mutable.value

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
        refresh()
    }

    @Synchronized
    fun setEphemeral(ephemeral: Boolean) {
        preferences.edit().putBoolean(KEY_EPHEMERAL, ephemeral).apply()
        refresh()
    }

    /** 返回 false 表示 URL 不合法，未保存。 */
    @Synchronized
    fun setControlUrl(raw: String): Boolean {
        val value = TailnetSettingsPolicy.normalizeControlUrl(raw) ?: return false
        preferences.edit().putString(KEY_CONTROL_URL, value).apply()
        refresh()
        return true
    }

    /** 返回 false 表示格式不合法，未保存。 */
    @Synchronized
    fun setAuthKey(raw: CharArray): Boolean {
        val value = String(raw).trim()
        if (!TailnetSettingsPolicy.validAuthKey(value)) return false
        val bytes = value.toByteArray(Charsets.UTF_8)
        try {
            secrets.put(AUTH_KEY_ID, bytes)
        } finally {
            bytes.fill(0)
        }
        refresh(bumpRevision = true)
        return true
    }

    @Synchronized
    fun clearAuthKey() {
        secrets.remove(AUTH_KEY_ID)
        refresh(bumpRevision = true)
    }

    /** 只在拼装 start 请求与设置页查看时读取。 */
    fun readAuthKey(): CharArray? {
        val bytes = secrets.get(AUTH_KEY_ID) ?: return null
        return try {
            String(bytes, Charsets.UTF_8).toCharArray()
        } finally {
            bytes.fill(0)
        }
    }

    private fun refresh(bumpRevision: Boolean = false) {
        val revision = mutable.value.authKeyRevision + if (bumpRevision) 1 else 0
        mutable.value = read(revision)
    }

    private fun read(revision: Int): TailnetSettings = TailnetSettings(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        controlUrl = preferences.getString(KEY_CONTROL_URL, null).orEmpty(),
        ephemeral = preferences.getBoolean(KEY_EPHEMERAL, false),
        hasAuthKey = secrets.contains(AUTH_KEY_ID),
        authKeyRevision = revision,
    )

    companion object {
        /** 独立偏好文件：不属于备份命名空间（见 BackupPreferencePolicy）。 */
        const val PREFERENCES_NAME = "infinity_tailnet"
        const val KEY_ENABLED = "tailnet_enabled"
        const val KEY_CONTROL_URL = "tailnet_control_url"
        const val KEY_EPHEMERAL = "tailnet_ephemeral"
        val AUTH_KEY_ID = SecretId("tailnet_auth_key_v1")
    }
}
