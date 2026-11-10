package com.promenar.infinity.data.pet

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 会话宠物的用户偏好。宠物默认关闭，只在用户于“设置 → 宠物”中手动启用后才显示。 */
class PetPreferenceStore(context: Context) {
    companion object {
        const val ENABLED_KEY = "pet_enabled"
        private const val PREFERENCES_NAME = "infinity_settings"
    }

    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _enabled = MutableStateFlow(preferences.getBoolean(ENABLED_KEY, false))

    /** 宠物总开关。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply()
    }
}
