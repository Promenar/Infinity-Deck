package com.promenar.infinity.ui.settings

import androidx.compose.foundation.interaction.MutableInteractionSource
import com.promenar.infinity.ui.common.pressFeedback
import com.promenar.infinity.ui.theme.InfinityLightColorScheme
import com.promenar.infinity.ui.theme.InfinityDarkColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BrightnessAuto
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityNumericSetting
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.UnavailableReasons
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityPresetColors
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.ComposerGlowStyle
import com.promenar.infinity.ui.theme.EmptyChatStyle
import com.promenar.infinity.ui.theme.GenerationIndicatorStyle
import com.promenar.infinity.ui.theme.InfinitySchemeStyle
import kotlin.math.roundToInt

@Composable
fun ThemeScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current.applicationContext as InfinityApplication
    val viewModel: ThemeViewModel = viewModel(
        factory = ThemeViewModel.provideFactory(context.themePreferenceStore),
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    ThemeScreenContent(
        state = state,
        onModeSelect = viewModel::setThemeMode,
        onColorSourceSelect = viewModel::setColorSource,
        onNavigateBack = onNavigateBack,
        onPrimaryColorSelect = viewModel::setPrimaryColor,
        onPureBlackChange = viewModel::setPureBlack,
        onTextScaleEnabledChange = viewModel::setTextScaleEnabled,
        onTextScaleChange = viewModel::setTextScale,
        onCollapseFinishedTurnsChange = viewModel::setCollapseFinishedTurns,
        onHapticsChange = viewModel::setHapticsEnabled,
        onStatusHapticsChange = viewModel::setStatusHapticsEnabled,
        onTouchLockUnlockChange = viewModel::setTouchLockUnlock,
        onCustomColorApply = viewModel::applyCustomColor,
        onSchemeStyleSelect = viewModel::setSchemeStyle,
        onComposerGlowSelect = viewModel::setComposerGlowStyle,
        onIndicatorSelect = viewModel::setGenerationIndicatorStyle,
        onEmptyChatSelect = viewModel::setEmptyChatStyle,
        onRunDockScopeChange = viewModel::setRunDockScope,
        onScopeDigitsChange = viewModel::setScopeDigits,
        onComposerSurfaceSelect = viewModel::setComposerSurfaceStyle,
    )
}

@Composable
fun ThemeScreenContent(
    state: ThemeUiState,
    onModeSelect: (InfinityThemeMode) -> Unit,
    onColorSourceSelect: (InfinityColorSource) -> Unit,
    onNavigateBack: () -> Unit,
    onPrimaryColorSelect: (Long?) -> Unit = {},
    onPureBlackChange: (Boolean) -> Unit = {},
    onTextScaleEnabledChange: (Boolean) -> Unit = {},
    onTextScaleChange: (Float) -> Unit = {},
    onCollapseFinishedTurnsChange: (Boolean) -> Unit = {},
    onHapticsChange: (Boolean) -> Unit = {},
    onStatusHapticsChange: (Boolean) -> Unit = {},
    onTouchLockUnlockChange: (com.promenar.infinity.ui.theme.TouchLockUnlock) -> Unit = {},
    onCustomColorApply: (Int) -> Unit = {},
    onSchemeStyleSelect: (InfinitySchemeStyle) -> Unit = {},
    onComposerGlowSelect: (ComposerGlowStyle) -> Unit = {},
    onIndicatorSelect: (GenerationIndicatorStyle) -> Unit = {},
    onEmptyChatSelect: (EmptyChatStyle) -> Unit = {},
    onRunDockScopeChange: (Boolean) -> Unit = {},
    onScopeDigitsChange: (Boolean) -> Unit = {},
    onComposerSurfaceSelect: (com.promenar.infinity.ui.theme.ComposerSurfaceStyle) -> Unit = {},
) {
    val preferences = state.preferences
    val dynamicColorChecked = state.dynamicColorAvailable &&
        preferences.colorSource == InfinityColorSource.DYNAMIC

    InfinitySettingsPageLayout(
        title = stringResource(R.string.theme_title),
        onBack = onNavigateBack,
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxWidth(), contentPadding = contentPadding) {
            item("mode") {
                InfinitySettingsSection(title = stringResource(R.string.settings_batch1_display_style)) {
                    ThemeModeSelector(
                        selectedMode = preferences.mode,
                        onModeSelect = onModeSelect,
                    )
                }
            }

            item("color") {
                InfinitySettingsSection(
                    title = stringResource(R.string.theme_accent_color),
                ) {
                    ColorPaletteRow(
                        selectedColor = preferences.primaryColor,
                        recentCustomColors = preferences.recentCustomColors,
                        onColorSelect = onPrimaryColorSelect,
                        onCustomColorApply = onCustomColorApply,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                    SchemeStyleSelector(
                        selected = preferences.schemeStyle,
                        enabled = !dynamicColorChecked,
                        onSelect = onSchemeStyleSelect,
                    )
                    SettingsToggle(
                        title = stringResource(R.string.theme_dynamic_color),
                        description = stringResource(
                            if (state.dynamicColorAvailable) R.string.settings_batch1_dynamic_color_summary
                            else R.string.settings_batch1_dynamic_color_unavailable_summary,
                        ),
                        checked = dynamicColorChecked,
                        enabled = state.dynamicColorAvailable,
                        onCheckedChange = { enabled ->
                            onColorSourceSelect(
                                if (enabled) InfinityColorSource.DYNAMIC else InfinityColorSource.INFINITY,
                            )
                        },
                        modifier = Modifier.testTag("theme_dynamic_color_switch"),
                    )
                }
            }

            item("motion") {
                InfinitySettingsSection(
                    title = stringResource(R.string.motion_section_title),
                    footer = stringResource(R.string.motion_section_footer),
                ) {
                    MotionStyleRows(
                        motion = preferences.motion,
                        onComposerSelect = onComposerGlowSelect,
                        onIndicatorSelect = onIndicatorSelect,
                        onEmptyChatSelect = onEmptyChatSelect,
                        onRunDockScopeChange = onRunDockScopeChange,
                        onScopeDigitsChange = onScopeDigitsChange,
                        onSurfaceSelect = onComposerSurfaceSelect,
                    )
                }
            }

            item("pure-black") {
                val pureBlackReason = UnavailableReasons.pureBlack(preferences.mode)
                InfinitySettingsSection(
                    title = stringResource(R.string.theme_dark_mode),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.theme_pure_black),
                        description = pureBlackReason?.let { stringResource(it) },
                        checked = preferences.pureBlack,
                        onCheckedChange = onPureBlackChange,
                        enabled = pureBlackReason == null,
                    )
                }
            }

            item("text-scale") {
                InfinitySettingsSection(
                    title = stringResource(R.string.theme_text),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.theme_text_scale),
                        checked = preferences.textScaleEnabled,
                        onCheckedChange = onTextScaleEnabledChange,
                    )
                    val textScaleReason = UnavailableReasons.textScaleValue(preferences.textScaleEnabled)
                    InfinityNumericSetting(
                        title = stringResource(R.string.settings_batch1_text_scale_value),
                        value = (preferences.textScale * 100).roundToInt(),
                        spec = InterfaceTextScaleSpec,
                        subtitle = textScaleReason?.let { stringResource(it) },
                        enabled = textScaleReason == null,
                        description = stringResource(R.string.theme_text_scale_desc),
                        onValueChange = { onTextScaleChange((it / 100.0).toFloat()) },
                    )
                }
            }
            item("chat") {
                InfinitySettingsSection(
                    title = stringResource(R.string.turn_collapse_settings_section),
                ) {
                    SettingsToggle(
                        title = stringResource(R.string.turn_collapse_setting_title),
                        description = stringResource(R.string.turn_collapse_setting_summary),
                        checked = preferences.collapseFinishedTurns,
                        onCheckedChange = onCollapseFinishedTurnsChange,
                        modifier = Modifier.testTag("theme_collapse_finished_turns_switch"),
                    )
                }
            }
            item("touch-lock") {
                InfinitySettingsSection(
                    title = stringResource(R.string.touch_lock_setting_title),
                    footer = stringResource(R.string.touch_lock_setting_footer),
                ) {
                    TouchLockUnlockSelector(preferences.touchLockUnlock, onTouchLockUnlockChange)
                }
            }
            item("haptics") {
                InfinitySettingsSection(
                    title = stringResource(R.string.haptics_settings_section),
                    footer = stringResource(R.string.haptics_settings_footer),
                ) {
                    val haptics = LocalInfinityHaptics.current
                    SettingsToggle(
                        title = stringResource(R.string.haptics_setting_title),
                        description = stringResource(R.string.haptics_setting_summary),
                        checked = preferences.hapticsEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) haptics.confirmEnabled()
                            onHapticsChange(enabled)
                        },
                        modifier = Modifier.testTag("theme_haptics_switch"),
                    )
                    SettingsToggle(
                        title = stringResource(R.string.haptics_status_setting_title),
                        description = stringResource(R.string.haptics_status_setting_summary),
                        checked = preferences.hapticsEnabled && preferences.statusHapticsEnabled,
                        enabled = preferences.hapticsEnabled,
                        onCheckedChange = onStatusHapticsChange,
                        modifier = Modifier.testTag("theme_status_haptics_switch"),
                    )
                }
            }
            item("bottom-space") { Spacer(modifier = Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ThemeModeSelector(
    selectedMode: InfinityThemeMode,
    onModeSelect: (InfinityThemeMode) -> Unit,
) {
    var showModes by remember { mutableStateOf(false) }
    val modesAnchor = rememberInfinityPopoverAnchor()
    val modes = listOf(
        Triple(InfinityThemeMode.SYSTEM, Icons.Rounded.BrightnessAuto, R.string.settings_theme_system),
        Triple(InfinityThemeMode.LIGHT, Icons.Rounded.LightMode, R.string.settings_theme_light),
        Triple(InfinityThemeMode.DARK, Icons.Rounded.DarkMode, R.string.settings_theme_dark),
    )
    InfinityValueRow(
        title = stringResource(R.string.theme_mode),
        value = stringResource(modes.first { it.first == selectedMode }.third),
        modifier = Modifier.infinityPopoverAnchor(modesAnchor).testTag("theme_mode_picker"),
        onClick = { if (!modesAnchor.consumeRecentDismiss()) showModes = true },
    )
    InfinityBottomSheet(show = showModes, title = stringResource(R.string.theme_mode),
        onDismiss = { showModes = false }, scrollContent = true, popover = InfinityPopoverSpec(modesAnchor)) {
        modes.forEach { (mode, _, titleRes) ->
            val selected = selectedMode == mode
            InfinitySettingsItem(
                title = stringResource(titleRes),
                horizontalPadding = 0.dp,
                onClick = { onModeSelect(mode); showModes = false },
                modifier = Modifier.testTag("theme_mode_${mode.name.lowercase()}"),
                role = Role.RadioButton,
                selected = selected,
                trailingContent = { androidx.compose.material3.RadioButton(selected = selected, onClick = null) },
            )
        }
    }
}

/** 触摸锁的解锁方式：长按或滑动，以单选列表直接展示两种说明。 */
@Composable
private fun TouchLockUnlockSelector(
    selected: com.promenar.infinity.ui.theme.TouchLockUnlock,
    onSelect: (com.promenar.infinity.ui.theme.TouchLockUnlock) -> Unit,
) {
    val options = listOf(
        Triple(com.promenar.infinity.ui.theme.TouchLockUnlock.HOLD, R.string.touch_lock_unlock_hold, R.string.touch_lock_unlock_hold_desc),
        Triple(com.promenar.infinity.ui.theme.TouchLockUnlock.SLIDE, R.string.touch_lock_unlock_slide, R.string.touch_lock_unlock_slide_desc),
    )
    options.forEach { (option, titleRes, descriptionRes) ->
        val isSelected = selected == option
        InfinitySettingsItem(
            title = stringResource(titleRes),
            subtitle = stringResource(descriptionRes),
            onClick = { onSelect(option) },
            modifier = Modifier.testTag("theme_touch_lock_${option.name.lowercase()}"),
            role = Role.RadioButton,
            selected = isSelected,
            trailingContent = { androidx.compose.material3.RadioButton(selected = isSelected, onClick = null) },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun ColorPaletteRow(
    selectedColor: Long?,
    recentCustomColors: List<Int>,
    onColorSelect: (Long?) -> Unit,
    onCustomColorApply: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showPicker by remember { mutableStateOf(false) }
    val presetValues = remember { InfinityPresetColors.map { it.value.toLong() } }
    val customSelected = selectedColor != null && selectedColor !in presetValues
    // 内置配色色块始终显示内置淡紫的主色，不随当前已选的强调色变化。
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val defaultColor = if (dark) InfinityDarkColorScheme.primary else InfinityLightColorScheme.primary
    // 内置配色、预设与自定义共 13 个色块，每行 5 个；末行用空位补齐，色块上下对齐。
    val columns = 5
    val swatches = InfinityPresetColors.size + 2
    androidx.compose.foundation.layout.FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceAround,
        maxItemsInEachRow = columns,
    ) {
        ColorChoice(
            color = defaultColor,
            selected = selectedColor == null,
            onClick = { onColorSelect(null) },
        )
        InfinityPresetColors.forEach { color ->
            ColorChoice(
                color = color,
                selected = selectedColor == color.value.toLong(),
                onClick = { onColorSelect(color.value.toLong()) },
            )
        }
        CustomColorChoice(
            color = selectedColor?.takeIf { customSelected }?.let { Color(it.toULong()) },
            onClick = { showPicker = true },
        )
        repeat((columns - swatches % columns) % columns) { Box(Modifier.size(48.dp)) }
    }
    val initial = (selectedColor?.let { Color(it.toULong()) } ?: defaultColor).toArgb()
    ThemeColorPickerSheet(
        show = showPicker,
        initialArgb = initial,
        recentColors = recentCustomColors,
        onApply = { argb ->
            onCustomColorApply(argb)
            showPicker = false
        },
        onDismiss = { showPicker = false },
    )
}

/** 自定义色块：未选自定义色时画一圈色相环，选中后显示该颜色并打勾。 */
@Composable
private fun CustomColorChoice(color: Color?, onClick: () -> Unit) {
    val label = stringResource(R.string.theme_custom_color)
    val haptics = LocalInfinityHaptics.current
    val rainbow = remember { (0..6).map { Color.hsv(it * 60f % 360f, 0.85f, 1f) } }
    // 48dp 外盒撑触控区，按压反馈画在 34dp 圆内。
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.size(48.dp).semantics { contentDescription = label }
            .selectable(selected = color != null, interactionSource = interaction, indication = null, role = Role.Button, onClick = { haptics.select(false); onClick() })
            .testTag("theme_custom_color"),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(34.dp).pressFeedback(interaction, CircleShape)
                .background(if (color != null) androidx.compose.ui.graphics.SolidColor(color) else androidx.compose.ui.graphics.Brush.sweepGradient(rainbow))
                .then(if (color != null) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (color != null) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
            } else {
                Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

/** 配色风格：决定种子色如何展开成整套配色；跟随系统取色时不可用。 */
@Composable
private fun SchemeStyleSelector(selected: InfinitySchemeStyle, enabled: Boolean, onSelect: (InfinitySchemeStyle) -> Unit) {
    var show by remember { mutableStateOf(false) }
    val anchor = rememberInfinityPopoverAnchor()
    val options = listOf(
        Triple(InfinitySchemeStyle.TONAL_SPOT, R.string.theme_scheme_tonal_spot, R.string.theme_scheme_tonal_spot_desc),
        Triple(InfinitySchemeStyle.VIBRANT, R.string.theme_scheme_vibrant, R.string.theme_scheme_vibrant_desc),
        Triple(InfinitySchemeStyle.FIDELITY, R.string.theme_scheme_fidelity, R.string.theme_scheme_fidelity_desc),
        Triple(InfinitySchemeStyle.EXPRESSIVE, R.string.theme_scheme_expressive, R.string.theme_scheme_expressive_desc),
    )
    InfinityValueRow(
        title = stringResource(R.string.theme_scheme_style),
        value = stringResource(options.first { it.first == selected }.second),
        subtitle = if (enabled) null else stringResource(R.string.theme_scheme_style_dynamic_note),
        enabled = enabled,
        modifier = Modifier.infinityPopoverAnchor(anchor).testTag("theme_scheme_style_picker"),
        onClick = { if (!anchor.consumeRecentDismiss()) show = true },
    )
    InfinityBottomSheet(show = show, title = stringResource(R.string.theme_scheme_style),
        onDismiss = { show = false }, scrollContent = true, popover = InfinityPopoverSpec(anchor)) {
        options.forEach { (style, titleRes, descriptionRes) ->
            val isSelected = selected == style
            InfinitySettingsItem(
                title = stringResource(titleRes),
                subtitle = stringResource(descriptionRes),
                horizontalPadding = 0.dp,
                onClick = { onSelect(style); show = false },
                modifier = Modifier.testTag("theme_scheme_${style.name.lowercase()}"),
                role = Role.RadioButton,
                selected = isSelected,
                trailingContent = { androidx.compose.material3.RadioButton(selected = isSelected, onClick = null) },
            )
        }
    }
}

@Composable
private fun ColorChoice(color: Color, selected: Boolean, onClick: () -> Unit) {
    val colorLabel = stringResource(R.string.settings_batch1_color_description, "%06X".format(color.toArgb() and 0xFFFFFF))
    val haptics = LocalInfinityHaptics.current
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier.size(48.dp).semantics { contentDescription = colorLabel }
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = { haptics.select(selected); onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier.size(34.dp).pressFeedback(interaction, CircleShape).background(color)
                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(18.dp))
            }
        }
    }
}
