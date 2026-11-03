package com.promenar.infinity.data.network

import android.content.SharedPreferences

/** 应用启动时移除已停用的网络偏好。 */
class AgentNetworkSettings(preferences: SharedPreferences) {
    init {
        preferences.edit().remove("script_lan_hosts").apply()
    }

    companion object {
        const val PREFERENCES_NAME = "infinity_settings"
    }
}
