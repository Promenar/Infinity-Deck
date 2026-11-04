package com.promenar.infinity.ui.theme

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ThemePreferenceStore(context: Context) {
    companion object {
        const val MODE_KEY = "theme_mode"
        const val COLOR_SOURCE_KEY = "theme_color_source"
        const val PRIMARY_COLOR_KEY = "theme_primary_color"
        const val PURE_BLACK_KEY = "theme_pure_black"
        const val TEXT_SCALE_ENABLED_KEY = "theme_text_scale_enabled"
        const val TEXT_SCALE_KEY = "theme_text_scale"
        const val COLLAPSE_FINISHED_TURNS_KEY = "theme_collapse_finished_turns"
        const val HAPTICS_KEY = "haptic_enabled"
        const val STATUS_HAPTICS_KEY = "haptic_status_enabled"
        const val TOUCH_LOCK_UNLOCK_KEY = "chat_touch_lock_unlock"
        const val SCHEME_STYLE_KEY = "theme_scheme_style"
        const val COMPOSER_GLOW_STYLE_KEY = "motion_composer_glow"
        const val INDICATOR_STYLE_KEY = "motion_generation_indicator"
        const val EMPTY_CHAT_STYLE_KEY = "motion_empty_chat"
        const val RUN_DOCK_SCOPE_KEY = "motion_run_dock_scope"
        const val SCOPE_DIGITS_KEY = "motion_scope_digits"
        const val COMPOSER_SURFACE_KEY = "motion_composer_surface"
        const val RECENT_CUSTOM_COLORS_KEY = "theme_recent_custom_colors"
        /** 主色键里表示“内置淡紫配色”的值：显式写入，和从未选择过（缺键，走冰蓝默认）区分开。 */
        const val BUILT_IN_PRIMARY_COLOR = 0L
        /** 最近使用的自定义色最多保留几个。 */
        const val RECENT_CUSTOM_COLOR_LIMIT = 8
        private const val PREFERENCES_NAME = "infinity_settings"
        private val THEME_KEYS = setOf(
            MODE_KEY,
            COLOR_SOURCE_KEY,
            PRIMARY_COLOR_KEY,
            PURE_BLACK_KEY,
            TEXT_SCALE_ENABLED_KEY,
            TEXT_SCALE_KEY,
            COLLAPSE_FINISHED_TURNS_KEY,
            HAPTICS_KEY,
            STATUS_HAPTICS_KEY,
            TOUCH_LOCK_UNLOCK_KEY,
            SCHEME_STYLE_KEY,
            COMPOSER_GLOW_STYLE_KEY,
            INDICATOR_STYLE_KEY,
            EMPTY_CHAT_STYLE_KEY,
            RUN_DOCK_SCOPE_KEY,
            SCOPE_DIGITS_KEY,
            COMPOSER_SURFACE_KEY,
            RECENT_CUSTOM_COLORS_KEY,
        )
    }

    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(readPreferences())
    val state: StateFlow<InfinityThemePreferences> = _state.asStateFlow()

    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in THEME_KEYS) {
            _state.value = readPreferences()
        }
    }

    init {
        preferences.registerOnSharedPreferenceChangeListener(changeListener)
    }

    fun setMode(mode: InfinityThemeMode) {
        _state.value = _state.value.copy(mode = mode)
        preferences.edit().putString(MODE_KEY, mode.toPreferenceValue()).apply()
    }

    fun setColorSource(source: InfinityColorSource) {
        _state.value = _state.value.copy(colorSource = source)
        preferences.edit().putString(COLOR_SOURCE_KEY, source.toPreferenceValue()).apply()
    }

    fun setPrimaryColor(color: Long?) {
        _state.value = _state.value.copy(primaryColor = color)
        preferences.edit().apply {
            putLong(PRIMARY_COLOR_KEY, color ?: BUILT_IN_PRIMARY_COLOR)
        }.apply()
    }

    fun setPureBlack(enabled: Boolean) {
        _state.value = _state.value.copy(pureBlack = enabled)
        preferences.edit().putBoolean(PURE_BLACK_KEY, enabled).apply()
    }

    fun setTextScale(enabled: Boolean, scale: Float = _state.value.textScale) {
        val boundedScale = scale.coerceIn(0.8f, 1.4f)
        _state.value = _state.value.copy(
            textScaleEnabled = enabled,
            textScale = boundedScale,
        )
        preferences.edit()
            .putBoolean(TEXT_SCALE_ENABLED_KEY, enabled)
            .putFloat(TEXT_SCALE_KEY, boundedScale)
            .apply()
    }

    fun setCollapseFinishedTurns(enabled: Boolean) {
        _state.value = _state.value.copy(collapseFinishedTurns = enabled)
        preferences.edit().putBoolean(COLLAPSE_FINISHED_TURNS_KEY, enabled).apply()
    }

    fun setHapticsEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(hapticsEnabled = enabled)
        preferences.edit().putBoolean(HAPTICS_KEY, enabled).apply()
    }

    fun setStatusHapticsEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(statusHapticsEnabled = enabled)
        preferences.edit().putBoolean(STATUS_HAPTICS_KEY, enabled).apply()
    }

    fun setTouchLockUnlock(unlock: TouchLockUnlock) {
        _state.value = _state.value.copy(touchLockUnlock = unlock)
        preferences.edit().putString(TOUCH_LOCK_UNLOCK_KEY, unlock.name.lowercase()).apply()
    }

    fun setSchemeStyle(style: InfinitySchemeStyle) {
        _state.value = _state.value.copy(schemeStyle = style)
        preferences.edit().putString(SCHEME_STYLE_KEY, style.name.lowercase()).apply()
    }

    fun setComposerGlowStyle(style: ComposerGlowStyle) {
        _state.value = _state.value.copy(motion = _state.value.motion.copy(composer = style))
        preferences.edit().putString(COMPOSER_GLOW_STYLE_KEY, style.name.lowercase()).apply()
    }

    fun setGenerationIndicatorStyle(style: GenerationIndicatorStyle) {
        _state.value = _state.value.copy(motion = _state.value.motion.copy(indicator = style))
        preferences.edit().putString(INDICATOR_STYLE_KEY, style.name.lowercase()).apply()
    }

    fun setEmptyChatStyle(style: EmptyChatStyle) {
        _state.value = _state.value.copy(motion = _state.value.motion.copy(emptyChat = style))
        preferences.edit().putString(EMPTY_CHAT_STYLE_KEY, style.name.lowercase()).apply()
    }

    fun setRunDockScope(enabled: Boolean) {
        _state.value = _state.value.copy(motion = _state.value.motion.copy(runDockScope = enabled))
        preferences.edit().putBoolean(RUN_DOCK_SCOPE_KEY, enabled).apply()
    }

    fun setScopeDigits(enabled: Boolean) {
        _state.value = _state.value.copy(motion = _state.value.motion.copy(scopeDigits = enabled))
        preferences.edit().putBoolean(SCOPE_DIGITS_KEY, enabled).apply()
    }

    fun setComposerSurfaceStyle(style: ComposerSurfaceStyle) {
        _state.value = _state.value.copy(motion = _state.value.motion.copy(surface = style))
        preferences.edit().putString(COMPOSER_SURFACE_KEY, style.name.lowercase()).apply()
    }

    /** 把自定义色放到最近使用的最前面，去重后截断到 [RECENT_CUSTOM_COLOR_LIMIT] 个。 */
    fun rememberCustomColor(argb: Int) {
        val recent = pushRecentColor(_state.value.recentCustomColors, argb, RECENT_CUSTOM_COLOR_LIMIT)
        _state.value = _state.value.copy(recentCustomColors = recent)
        preferences.edit().putString(RECENT_CUSTOM_COLORS_KEY, encodeRecentColors(recent)).apply()
    }

    private fun readPreferences(): InfinityThemePreferences = InfinityThemePreferences(
        mode = InfinityThemeMode.fromPreferenceValue(preferences.getString(MODE_KEY, null)),
        colorSource = InfinityColorSource.fromPreferenceValue(
            preferences.getString(COLOR_SOURCE_KEY, null),
        ),
        // 缺键才回退冰蓝；选过内置淡紫的写的是 BUILT_IN_PRIMARY_COLOR。
        primaryColor = if (preferences.contains(PRIMARY_COLOR_KEY)) {
            preferences.getLong(PRIMARY_COLOR_KEY, BUILT_IN_PRIMARY_COLOR).takeIf { it != BUILT_IN_PRIMARY_COLOR }
        } else {
            InfinityDefaultPrimaryColor
        },
        // 纯黑背景缺省开启；只有用户显式关闭后才写入 false。
        pureBlack = !preferences.contains(PURE_BLACK_KEY) || preferences.getBoolean(PURE_BLACK_KEY, true),
        textScaleEnabled = preferences.getBoolean(TEXT_SCALE_ENABLED_KEY, false),
        textScale = if (preferences.contains(TEXT_SCALE_KEY)) {
            preferences.getFloat(TEXT_SCALE_KEY, 1f).coerceIn(0.8f, 1.4f)
        } else {
            1f
        },
        // 缺省开启；只有用户显式关闭后才写入 false。
        collapseFinishedTurns = if (preferences.contains(COLLAPSE_FINISHED_TURNS_KEY)) {
            preferences.getBoolean(COLLAPSE_FINISHED_TURNS_KEY, true)
        } else {
            true
        },
        // 触感与状态提醒同样缺省开启。
        hapticsEnabled = !preferences.contains(HAPTICS_KEY) || preferences.getBoolean(HAPTICS_KEY, true),
        statusHapticsEnabled = !preferences.contains(STATUS_HAPTICS_KEY) || preferences.getBoolean(STATUS_HAPTICS_KEY, true),
        touchLockUnlock = TouchLockUnlock.fromPreferenceValue(preferences.getString(TOUCH_LOCK_UNLOCK_KEY, null)),
        schemeStyle = InfinitySchemeStyle.fromPreferenceValue(preferences.getString(SCHEME_STYLE_KEY, null)),
        motion = InfinityMotionStyles(
            composer = ComposerGlowStyle.fromPreferenceValue(preferences.getString(COMPOSER_GLOW_STYLE_KEY, null)),
            indicator = GenerationIndicatorStyle.fromPreferenceValue(preferences.getString(INDICATOR_STYLE_KEY, null)),
            emptyChat = EmptyChatStyle.fromPreferenceValue(preferences.getString(EMPTY_CHAT_STYLE_KEY, null)),
            surface = ComposerSurfaceStyle.fromPreferenceValue(preferences.getString(COMPOSER_SURFACE_KEY, null)),
            // 缺省显示；选过旧“点阵波形屏”流光的用户迁成仅材质光，点阵屏照样在运行坞里。
            runDockScope = !preferences.contains(RUN_DOCK_SCOPE_KEY) || preferences.getBoolean(RUN_DOCK_SCOPE_KEY, true),
            // 缺省显示数字。
            scopeDigits = !preferences.contains(SCOPE_DIGITS_KEY) || preferences.getBoolean(SCOPE_DIGITS_KEY, true),
        ),
        recentCustomColors = decodeRecentColors(preferences.getString(RECENT_CUSTOM_COLORS_KEY, null)),
    )
}

internal fun InfinityThemeMode.toPreferenceValue(): String = name.lowercase()

internal fun InfinityColorSource.toPreferenceValue(): String = name.lowercase()

internal fun pushRecentColor(recent: List<Int>, argb: Int, limit: Int): List<Int> =
    (listOf(argb) + recent.filter { it != argb }).take(limit)

/** 最近使用的颜色以逗号分隔的 8 位十六进制 ARGB 存储；无法解析的项跳过。 */
internal fun encodeRecentColors(colors: List<Int>): String = colors.joinToString(",") { "%08X".format(it) }

internal fun decodeRecentColors(value: String?): List<Int> =
    value.orEmpty().split(',').mapNotNull { it.trim().takeIf(String::isNotEmpty)?.toLongOrNull(16)?.toInt() }
