package com.promenar.infinity.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.ThemePreferenceStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

data class ThemeUiState(
    val preferences: InfinityThemePreferences = InfinityThemePreferences(),
    val dynamicColorAvailable: Boolean = false
)

class ThemeViewModel(
    private val store: ThemePreferenceStore,
    val dynamicColorAvailable: Boolean = Build.VERSION.SDK_INT >= 31
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        ThemeUiState(
            preferences = store.state.value,
            dynamicColorAvailable = dynamicColorAvailable
        )
    )
    val uiState: StateFlow<ThemeUiState> = _uiState.asStateFlow()

    init {
        store.state.onEach { prefs ->
            _uiState.value = _uiState.value.copy(preferences = prefs)
        }.launchIn(viewModelScope)
    }

    fun setThemeMode(mode: InfinityThemeMode) {
        store.setMode(mode)
    }

    fun setColorSource(source: InfinityColorSource) {
        if (source == InfinityColorSource.DYNAMIC && !dynamicColorAvailable) {
            return
        }
        store.setColorSource(source)
    }

    fun setPrimaryColor(color: Long?) {
        store.setPrimaryColor(color)
        store.setColorSource(InfinityColorSource.INFINITY)
    }

    /** 应用自定义取色：作为种子色写入，并记进最近使用。 */
    fun applyCustomColor(argb: Int) {
        store.rememberCustomColor(argb)
        setPrimaryColor(androidx.compose.ui.graphics.Color(argb).value.toLong())
    }

    fun setSchemeStyle(style: com.promenar.infinity.ui.theme.InfinitySchemeStyle) {
        store.setSchemeStyle(style)
    }

    fun setComposerGlowStyle(style: com.promenar.infinity.ui.theme.ComposerGlowStyle) {
        store.setComposerGlowStyle(style)
    }

    fun setGenerationIndicatorStyle(style: com.promenar.infinity.ui.theme.GenerationIndicatorStyle) {
        store.setGenerationIndicatorStyle(style)
    }

    fun setEmptyChatStyle(style: com.promenar.infinity.ui.theme.EmptyChatStyle) {
        store.setEmptyChatStyle(style)
    }

    fun setRunDockScope(enabled: Boolean) {
        store.setRunDockScope(enabled)
    }

    fun setScopeDigits(enabled: Boolean) {
        store.setScopeDigits(enabled)
    }

    fun setComposerSurfaceStyle(style: com.promenar.infinity.ui.theme.ComposerSurfaceStyle) {
        store.setComposerSurfaceStyle(style)
    }

    fun setPureBlack(enabled: Boolean) {
        store.setPureBlack(enabled)
    }

    fun setTextScaleEnabled(enabled: Boolean) {
        store.setTextScale(enabled)
    }

    fun setTextScale(scale: Float) {
        store.setTextScale(store.state.value.textScaleEnabled, scale)
    }

    fun setCollapseFinishedTurns(enabled: Boolean) {
        store.setCollapseFinishedTurns(enabled)
    }

    fun setHapticsEnabled(enabled: Boolean) {
        store.setHapticsEnabled(enabled)
    }

    fun setStatusHapticsEnabled(enabled: Boolean) {
        store.setStatusHapticsEnabled(enabled)
    }

    fun setTouchLockUnlock(unlock: com.promenar.infinity.ui.theme.TouchLockUnlock) {
        store.setTouchLockUnlock(unlock)
    }

    companion object {
        fun provideFactory(store: ThemePreferenceStore): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return ThemeViewModel(store) as T
                }
            }
    }
}
