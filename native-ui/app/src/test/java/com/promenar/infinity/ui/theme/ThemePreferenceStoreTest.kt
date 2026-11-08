package com.promenar.infinity.ui.theme

import android.content.Context
import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ThemePreferenceStoreTest {

    private fun createStore(): ThemePreferenceStore {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString("theme_mode", any()) } returns null
            every { getString("theme_color_source", any()) } returns null
            every { edit() } returns editor
        }
        return ThemePreferenceStore(contextFor(prefs))
    }

    private fun contextFor(prefs: SharedPreferences): Context = mockk(relaxed = true) {
        every { getSharedPreferences("infinity_settings", Context.MODE_PRIVATE) } returns prefs
    }

    @Test
    fun `缺失偏好默认 dark 与 Infinity Deck 颜色源`() = runTest {
        val store = createStore()
        val state = store.state.value

        assertThat(state).isEqualTo(
            InfinityThemePreferences(
                mode = InfinityThemeMode.DARK,
                colorSource = InfinityColorSource.INFINITY,
            ),
        )
    }

    @Test
    fun `mode 和 colorSource 持久化恢复`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { edit() } returns editor
            every { getString("theme_mode", any()) } returns "SYSTEM"
            every { getString("theme_color_source", any()) } returns "DYNAMIC"
        }

        val store = ThemePreferenceStore(contextFor(prefs))
        store.setMode(InfinityThemeMode.SYSTEM)
        store.setColorSource(InfinityColorSource.DYNAMIC)

        assertThat(store.state.value.mode).isEqualTo(InfinityThemeMode.SYSTEM)
        assertThat(store.state.value.colorSource).isEqualTo(InfinityColorSource.DYNAMIC)

        verify { editor.putString("theme_mode", "system") }
        verify { editor.putString("theme_color_source", "dynamic") }
        verify(exactly = 2) { editor.apply() }
    }

    @Test
    fun `Bettbox 外观偏好立即更新并写入持久化存储`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.putLong(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putFloat(any(), any()) } returns editor
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { contains(any()) } returns false
            every { getString("theme_mode", any()) } returns null
            every { getString("theme_color_source", any()) } returns null
            every { edit() } returns editor
        }
        val store = ThemePreferenceStore(contextFor(prefs))

        store.setPrimaryColor(0xFF6366F1L)
        store.setPureBlack(true)
        store.setTextScale(enabled = true, scale = 1.2f)

        assertThat(store.state.value.primaryColor).isEqualTo(0xFF6366F1L)
        assertThat(store.state.value.pureBlack).isTrue()
        assertThat(store.state.value.textScaleEnabled).isTrue()
        assertThat(store.state.value.textScale).isWithin(0.001f).of(1.2f)
        verify { editor.putLong("theme_primary_color", 0xFF6366F1L) }
        verify { editor.putBoolean("theme_pure_black", true) }
        verify { editor.putBoolean("theme_text_scale_enabled", true) }
        verify { editor.putFloat("theme_text_scale", 1.2f) }
    }

    @Test
    fun `未知持久值回退到默认策略`() = runTest {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString("theme_mode", any()) } returns "unknown"
            every { getString("theme_color_source", any()) } returns "mystery"
        }

        val store = ThemePreferenceStore(contextFor(prefs))

        assertThat(store.state.value.mode).isEqualTo(InfinityThemeMode.DARK)
        assertThat(store.state.value.colorSource).isEqualTo(InfinityColorSource.INFINITY)
    }

    @Test
    fun `外部 restore 变更无需重启直接更新状态`() {
        val changeListenerSlot = slot<SharedPreferences.OnSharedPreferenceChangeListener>()
        val prefs = mockk<SharedPreferences>(relaxed = true)

        every { prefs.registerOnSharedPreferenceChangeListener(capture(changeListenerSlot)) } returns Unit
        every { prefs.getString("theme_mode", any()) } returns "DARK"
        every { prefs.getString("theme_color_source", any()) } returns "INFINITY"

        val store = ThemePreferenceStore(contextFor(prefs))
        assertThat(store.state.value.mode).isEqualTo(InfinityThemeMode.DARK)
        assertThat(store.state.value.colorSource).isEqualTo(InfinityColorSource.INFINITY)

        every { prefs.getString("theme_mode", any()) } returns "LIGHT"
        every { prefs.getString("theme_color_source", any()) } returns "DYNAMIC"
        changeListenerSlot.captured.onSharedPreferenceChanged(prefs, "theme_mode")

        assertThat(store.state.value.mode).isEqualTo(InfinityThemeMode.LIGHT)
        assertThat(store.state.value.colorSource).isEqualTo(InfinityColorSource.DYNAMIC)
    }

    @Test
    fun `完成后折叠过程缺省开启并持久化关闭`() {
        val booleans = mutableMapOf<String, Boolean>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers {
            booleans[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { contains(any()) } answers { firstArg<String>() in booleans }
            every { getBoolean(any(), any()) } answers { booleans[firstArg()] ?: secondArg() }
            every { edit() } returns editor
        }

        val store = ThemePreferenceStore(contextFor(prefs))
        assertThat(store.state.value.collapseFinishedTurns).isTrue()

        store.setCollapseFinishedTurns(false)
        assertThat(store.state.value.collapseFinishedTurns).isFalse()
        verify { editor.putBoolean("theme_collapse_finished_turns", false) }
        // 进程重建后从同一偏好文件读回关闭状态。
        assertThat(ThemePreferenceStore(contextFor(prefs)).state.value.collapseFinishedTurns).isFalse()
    }

    @Test
    fun `触感反馈与状态提醒缺省开启并分别持久化`() {
        val booleans = mutableMapOf<String, Boolean>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } answers {
            booleans[firstArg()] = secondArg()
            editor
        }
        every { editor.apply() } returns Unit
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { contains(any()) } answers { firstArg<String>() in booleans }
            every { getBoolean(any(), any()) } answers { booleans[firstArg()] ?: secondArg() }
            every { edit() } returns editor
        }

        val store = ThemePreferenceStore(contextFor(prefs))
        assertThat(store.state.value.hapticsEnabled).isTrue()
        assertThat(store.state.value.statusHapticsEnabled).isTrue()

        store.setStatusHapticsEnabled(false)
        assertThat(store.state.value.hapticsEnabled).isTrue()
        assertThat(store.state.value.statusHapticsEnabled).isFalse()
        store.setHapticsEnabled(false)
        // 沿用旧设置项 haptic_enabled，备份恢复的值直接生效。
        verify { editor.putBoolean("haptic_enabled", false) }
        verify { editor.putBoolean("haptic_status_enabled", false) }
        val reloaded = ThemePreferenceStore(contextFor(prefs)).state.value
        assertThat(reloaded.hapticsEnabled).isFalse()
        assertThat(reloaded.statusHapticsEnabled).isFalse()
    }

    @Test
    fun `动效样式与配色风格读取已存值，未知值回落默认`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { edit() } returns editor
            every { getString("motion_composer_glow", any()) } returns "bottom_strip"
            every { getString("motion_generation_indicator", any()) } returns "infinity_led"
            every { getString("motion_empty_chat", any()) } returns "removed_style"
            every { getString("theme_scheme_style", any()) } returns "fidelity"
            every { getString("theme_recent_custom_colors", any()) } returns "FFFF5A1F,zz,FF10B981"
        }

        val state = ThemePreferenceStore(contextFor(prefs)).state.value

        assertThat(state.motion).isEqualTo(
            InfinityMotionStyles(
                composer = ComposerGlowStyle.BOTTOM_STRIP,
                indicator = GenerationIndicatorStyle.INFINITY_LED,
                emptyChat = EmptyChatStyle.LED_MORPH,
            ),
        )
        assertThat(state.schemeStyle).isEqualTo(InfinitySchemeStyle.FIDELITY)
        assertThat(state.recentCustomColors).containsExactly(0xFFFF5A1F.toInt(), 0xFF10B981.toInt()).inOrder()
    }

    @Test
    fun `切换动效样式写入小写名并立即更新状态`() = runTest {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>(relaxed = true) { every { edit() } returns editor }
        val store = ThemePreferenceStore(contextFor(prefs))

        store.setComposerGlowStyle(ComposerGlowStyle.SWEEP_GRID)
        store.setGenerationIndicatorStyle(GenerationIndicatorStyle.PORTS)
        store.setEmptyChatStyle(EmptyChatStyle.MOBIUS_LED)
        store.setSchemeStyle(InfinitySchemeStyle.VIBRANT)

        assertThat(store.state.value.motion.composer).isEqualTo(ComposerGlowStyle.SWEEP_GRID)
        assertThat(store.state.value.motion.indicator).isEqualTo(GenerationIndicatorStyle.PORTS)
        assertThat(store.state.value.motion.emptyChat).isEqualTo(EmptyChatStyle.MOBIUS_LED)
        assertThat(store.state.value.schemeStyle).isEqualTo(InfinitySchemeStyle.VIBRANT)
        verify { editor.putString("motion_composer_glow", "sweep_grid") }
        verify { editor.putString("motion_generation_indicator", "ports") }
        verify { editor.putString("motion_empty_chat", "mobius_led") }
        verify { editor.putString("theme_scheme_style", "vibrant") }
    }

    @Test
    fun `从未选择过动效时走新默认值：仅材质光加运行坞点阵屏、运算矩阵、灯珠变形与环境光`() {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString(any(), any()) } returns null
            every { contains(any()) } returns false
        }

        val motion = ThemePreferenceStore(contextFor(prefs)).state.value.motion

        assertThat(motion.composer).isEqualTo(ComposerGlowStyle.MATERIAL_ONLY)
        assertThat(motion.runDockScope).isTrue()
        assertThat(motion.scopeDigits).isTrue()
        assertThat(motion.indicator).isEqualTo(GenerationIndicatorStyle.MATRIX)
        assertThat(motion.emptyChat).isEqualTo(EmptyChatStyle.LED_MORPH)
        assertThat(motion.surface).isEqualTo(ComposerSurfaceStyle.AMBIENT)
        assertThat(motion).isEqualTo(InfinityMotionStyles())
    }

    @Test
    fun `手动选过的旧默认值原样保留，不被新默认覆盖`() {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString("motion_composer_glow", any()) } returns "aurora"
            every { getString("motion_generation_indicator", any()) } returns "wave"
            every { getString("motion_empty_chat", any()) } returns "flow"
            every { getString("motion_composer_surface", any()) } returns "frosted"
        }

        val motion = ThemePreferenceStore(contextFor(prefs)).state.value.motion

        assertThat(motion.composer).isEqualTo(ComposerGlowStyle.AURORA)
        assertThat(motion.indicator).isEqualTo(GenerationIndicatorStyle.WAVE)
        assertThat(motion.emptyChat).isEqualTo(EmptyChatStyle.FLOW)
        assertThat(motion.surface).isEqualTo(ComposerSurfaceStyle.FROSTED)
    }

    @Test
    fun `选过旧点阵波形屏流光的用户迁成仅材质光，点阵屏留在运行坞`() {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString("motion_composer_glow", any()) } returns "scope_screen"
            every { getString("motion_scope_placement", any()) } returns "top_edge"
            every { contains(any()) } returns false
            every { contains("motion_composer_glow") } returns true
            every { contains("motion_scope_placement") } returns true
        }

        val motion = ThemePreferenceStore(contextFor(prefs)).state.value.motion

        assertThat(motion.composer).isEqualTo(ComposerGlowStyle.MATERIAL_ONLY)
        assertThat(motion.runDockScope).isTrue()
        assertThat(motion.scopeDigits).isTrue()
    }

    @Test
    fun `运行坞点阵屏开关写入后读回`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { edit() } returns editor
            every { contains("motion_run_dock_scope") } returns true
            every { getBoolean("motion_run_dock_scope", any()) } returns false
        }
        val store = ThemePreferenceStore(contextFor(prefs))
        assertThat(store.state.value.motion.runDockScope).isFalse()
        store.setRunDockScope(true)
        assertThat(store.state.value.motion.runDockScope).isTrue()
        verify { editor.putBoolean("motion_run_dock_scope", true) }
    }

    @Test
    fun `浮岛材质回退与写入`() {
        assertThat(ComposerSurfaceStyle.fromPreferenceValue(null)).isEqualTo(ComposerSurfaceStyle.AMBIENT)
        assertThat(ComposerSurfaceStyle.fromPreferenceValue("glassy")).isEqualTo(ComposerSurfaceStyle.AMBIENT)
        assertThat(ComposerSurfaceStyle.fromPreferenceValue("FROSTED")).isEqualTo(ComposerSurfaceStyle.FROSTED)
        assertThat(ComposerGlowStyle.fromPreferenceValue("removed")).isEqualTo(ComposerGlowStyle.MATERIAL_ONLY)
        assertThat(GenerationIndicatorStyle.fromPreferenceValue(null)).isEqualTo(GenerationIndicatorStyle.MATRIX)
        assertThat(EmptyChatStyle.fromPreferenceValue("led_morph")).isEqualTo(EmptyChatStyle.LED_MORPH)

        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>(relaxed = true) { every { edit() } returns editor }
        val store = ThemePreferenceStore(contextFor(prefs))
        store.setComposerSurfaceStyle(ComposerSurfaceStyle.FROSTED)
        assertThat(store.state.value.motion.surface).isEqualTo(ComposerSurfaceStyle.FROSTED)
        verify { editor.putString("motion_composer_surface", "frosted") }
    }

    @Test
    fun `最近自定义色去重置顶并限制数量`() {
        val recent = (1..8).map { it }
        assertThat(pushRecentColor(recent, 5, 8)).containsExactly(5, 1, 2, 3, 4, 6, 7, 8).inOrder()
        assertThat(pushRecentColor(recent, 9, 8)).containsExactly(9, 1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(decodeRecentColors(encodeRecentColors(listOf(0xFF000000.toInt(), 0xFFABCDEF.toInt()))))
            .containsExactly(0xFF000000.toInt(), 0xFFABCDEF.toInt()).inOrder()
    }

    @Test
    fun `从未设置过外观时走新默认：冰蓝主色与纯黑背景`() {
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString(any(), any()) } returns null
            every { contains(any()) } returns false
        }

        val state = ThemePreferenceStore(contextFor(prefs)).state.value

        assertThat(state.primaryColor).isEqualTo(InfinityIceBlueSeed.value.toLong())
        assertThat(state.primaryColor).isEqualTo(InfinityDefaultPrimaryColor)
        assertThat(state.pureBlack).isTrue()
        assertThat(state.mode).isEqualTo(InfinityThemeMode.DARK)
        assertThat(InfinityPresetColors.first()).isEqualTo(InfinityIceBlueSeed)
    }

    @Test
    fun `已有的主色与纯黑设置原样保留，不被新默认覆盖`() {
        val custom = InfinityPresetColors[3].value.toLong()
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { getString(any(), any()) } returns null
            every { contains(any()) } returns false
            every { contains("theme_primary_color") } returns true
            every { getLong("theme_primary_color", any()) } returns custom
            every { contains("theme_pure_black") } returns true
            every { getBoolean("theme_pure_black", any()) } returns false
        }

        val state = ThemePreferenceStore(contextFor(prefs)).state.value

        assertThat(state.primaryColor).isEqualTo(custom)
        assertThat(state.pureBlack).isFalse()
    }

    @Test
    fun `选过内置淡紫的写入显式标记，读回仍是内置配色而不是冰蓝`() {
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putLong(any(), any()) } returns editor
        val prefs = mockk<SharedPreferences>(relaxed = true) {
            every { edit() } returns editor
            every { getString(any(), any()) } returns null
            every { contains(any()) } returns false
            every { contains("theme_primary_color") } returns true
            every { getLong("theme_primary_color", any()) } returns ThemePreferenceStore.BUILT_IN_PRIMARY_COLOR
        }

        val store = ThemePreferenceStore(contextFor(prefs))
        assertThat(store.state.value.primaryColor).isNull()

        store.setPrimaryColor(InfinityDefaultPrimaryColor)
        store.setPrimaryColor(null)

        assertThat(store.state.value.primaryColor).isNull()
        verify { editor.putLong("theme_primary_color", ThemePreferenceStore.BUILT_IN_PRIMARY_COLOR) }
        verify(exactly = 0) { editor.remove("theme_primary_color") }
    }
}
