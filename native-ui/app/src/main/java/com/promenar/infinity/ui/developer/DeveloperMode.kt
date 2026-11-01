package com.promenar.infinity.ui.developer

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 版本号连点的一次结果：只有“还差几次”“已开启”“本来就开着”需要提示。 */
sealed interface DeveloperUnlockResult {
    data object Silent : DeveloperUnlockResult
    data class Remaining(val taps: Int) : DeveloperUnlockResult
    data object Unlocked : DeveloperUnlockResult
    data object AlreadyEnabled : DeveloperUnlockResult
}

/**
 * 关于面板版本号的连点计数：相邻两次点击间隔不超过 [windowMs] 才累计，满 [requiredTaps] 次开启开发者模式；
 * 还差 [hintFrom] 次及以内时逐次提示。已开启时只在一轮连点的第一下提示一次，避免连续弹出。
 */
class DeveloperUnlockCounter(
    private val requiredTaps: Int = 5,
    private val windowMs: Long = 1_500L,
    private val hintFrom: Int = 2,
) {
    private var count = 0
    private var lastTapAt: Long? = null

    fun tap(nowMs: Long, alreadyEnabled: Boolean): DeveloperUnlockResult {
        val last = lastTapAt
        count = if (last != null && nowMs - last in 0..windowMs) count + 1 else 1
        lastTapAt = nowMs
        if (alreadyEnabled) return if (count == 1) DeveloperUnlockResult.AlreadyEnabled else DeveloperUnlockResult.Silent
        val remaining = requiredTaps - count
        return when {
            remaining <= 0 -> {
                count = 0
                lastTapAt = null
                DeveloperUnlockResult.Unlocked
            }
            remaining <= hintFrom -> DeveloperUnlockResult.Remaining(remaining)
            else -> DeveloperUnlockResult.Silent
        }
    }
}

/** 开发者模式开关，存于设置偏好；进程内共享一份，设置首页与开发者页同步刷新。 */
class DeveloperModeStore internal constructor(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(prefs.getBoolean(KEY_ENABLED, false))
    val enabled: StateFlow<Boolean> = state.asStateFlow()

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
        state.value = enabled
    }

    companion object {
        const val PREFS_NAME = "infinity_settings"
        const val KEY_ENABLED = "developer_mode_enabled"

        @Volatile
        private var instance: DeveloperModeStore? = null

        fun get(context: Context): DeveloperModeStore = instance ?: synchronized(this) {
            instance ?: DeveloperModeStore(
                context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
            ).also { instance = it }
        }
    }
}
