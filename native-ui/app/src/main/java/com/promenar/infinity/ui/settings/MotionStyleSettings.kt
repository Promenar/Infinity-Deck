package com.promenar.infinity.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.chat.ComposerIslandCorner
import com.promenar.infinity.ui.chat.ComposerToolbarInset
import com.promenar.infinity.ui.chat.ComposerIslandLights
import com.promenar.infinity.ui.chat.ComposerIslandSurface
import com.promenar.infinity.ui.chat.LocalComposerBackdrop
import com.promenar.infinity.ui.chat.composerBackdropSource
import com.promenar.infinity.ui.chat.rememberComposerBackdrop
import com.promenar.infinity.ui.chat.ComposerDockStrip
import com.promenar.infinity.ui.chat.LocalComposerGlow
import com.promenar.infinity.ui.chat.components.RunDockScope
import com.promenar.infinity.ui.chat.components.RunDockScopeMode
import com.promenar.infinity.ui.chat.LedEmptyChat
import com.promenar.infinity.ui.chat.ParticleWordmark
import com.promenar.infinity.ui.chat.composerIslandGlow
import com.promenar.infinity.ui.chat.composerIslandMaterial
import com.promenar.infinity.ui.chat.rememberComposerGlowState
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.motion.LocalGenerationMotion
import com.promenar.infinity.ui.common.motion.rememberDemoGenerationMotion
import com.promenar.infinity.ui.common.GenerationDots
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPopoverSpec
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinityValueRow
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import com.promenar.infinity.ui.theme.ComposerGlowStyle
import com.promenar.infinity.ui.theme.ComposerSurfaceStyle
import com.promenar.infinity.ui.theme.EmptyChatStyle
import com.promenar.infinity.ui.theme.GenerationIndicatorStyle
import com.promenar.infinity.ui.theme.LocalInfinityMotionStyles
import com.promenar.infinity.ui.theme.InfinityMotionStyles

/** 一个可选样式的名称，以及可选的一句交互提示。 */
internal data class MotionStyleLabel(@StringRes val name: Int, @StringRes val description: Int? = null)

internal fun ComposerGlowStyle.label(): MotionStyleLabel = when (this) {
    ComposerGlowStyle.AURORA -> MotionStyleLabel(R.string.motion_composer_aurora)
    ComposerGlowStyle.BREATHE -> MotionStyleLabel(R.string.motion_composer_breathe)
    ComposerGlowStyle.MESH -> MotionStyleLabel(R.string.motion_composer_mesh)
    ComposerGlowStyle.BOTTOM_STRIP -> MotionStyleLabel(R.string.motion_composer_bottom_strip)
    ComposerGlowStyle.MATERIAL_ONLY -> MotionStyleLabel(R.string.motion_composer_material_only, R.string.motion_composer_material_only_desc)
    ComposerGlowStyle.SWEEP_GRID -> MotionStyleLabel(R.string.motion_composer_sweep_grid)
}

internal fun GenerationIndicatorStyle.label(): MotionStyleLabel = when (this) {
    GenerationIndicatorStyle.WAVE -> MotionStyleLabel(R.string.motion_indicator_wave)
    GenerationIndicatorStyle.PORTS -> MotionStyleLabel(R.string.motion_indicator_ports)
    GenerationIndicatorStyle.MATRIX -> MotionStyleLabel(R.string.motion_indicator_matrix)
    GenerationIndicatorStyle.STACKED -> MotionStyleLabel(R.string.motion_indicator_stacked)
    GenerationIndicatorStyle.BAYS -> MotionStyleLabel(R.string.motion_indicator_bays)
    GenerationIndicatorStyle.INFINITY_LINE -> MotionStyleLabel(R.string.motion_indicator_infinity_line)
    GenerationIndicatorStyle.INFINITY_LED -> MotionStyleLabel(R.string.motion_indicator_infinity_led)
}

internal fun EmptyChatStyle.label(): MotionStyleLabel = when (this) {
    EmptyChatStyle.FLOW -> MotionStyleLabel(R.string.motion_empty_flow)
    EmptyChatStyle.LED_WORDMARK -> MotionStyleLabel(R.string.motion_empty_led_wordmark, R.string.motion_empty_led_wordmark_desc)
    EmptyChatStyle.MOBIUS -> MotionStyleLabel(R.string.motion_empty_mobius, R.string.motion_empty_mobius_desc)
    EmptyChatStyle.MOBIUS_LED -> MotionStyleLabel(R.string.motion_empty_mobius_led, R.string.motion_empty_mobius_led_desc)
    EmptyChatStyle.MORPH -> MotionStyleLabel(R.string.motion_empty_morph, R.string.motion_empty_morph_desc)
    EmptyChatStyle.LED_GLOBE -> MotionStyleLabel(R.string.motion_empty_led_globe, R.string.motion_empty_led_globe_desc)
    EmptyChatStyle.LED_MORPH -> MotionStyleLabel(R.string.motion_empty_led_morph, R.string.motion_empty_led_morph_desc)
}

private val DefaultMotion = InfinityMotionStyles()

/** 设置页列表把默认样式排在第一个，其余保持枚举里的相对顺序；偏好按名称存储，不受排列影响。 */
internal fun <T> List<T>.defaultFirst(default: T): List<T> = listOf(default) + filter { it != default }

/**
 * 外观页“生成动效”分组：三行分别打开带实时预览的样式选择面板；浮岛光效下方是浮岛材质（毛玻璃 / 环境光）与它的预览，
 * 再下面是运行坞点阵屏开关，打开时另有“显示数字”。
 */
@Composable
internal fun MotionStyleRows(
    motion: InfinityMotionStyles,
    onComposerSelect: (ComposerGlowStyle) -> Unit,
    onIndicatorSelect: (GenerationIndicatorStyle) -> Unit,
    onEmptyChatSelect: (EmptyChatStyle) -> Unit,
    onRunDockScopeChange: (Boolean) -> Unit = {},
    onScopeDigitsChange: (Boolean) -> Unit = {},
    onSurfaceSelect: (ComposerSurfaceStyle) -> Unit = {},
) {
    MotionStylePicker(
        title = stringResource(R.string.motion_composer_title),
        options = ComposerGlowStyle.entries.defaultFirst(DefaultMotion.composer),
        selected = motion.composer,
        label = { it.label() },
        tag = "theme_motion_composer",
        onSelect = onComposerSelect,
        phaseDemo = true,
    ) { ComposerPreview(it) }
    ComposerSurfaceOptions(motion, onSurfaceSelect)
    RunDockScopeOptions(motion, onRunDockScopeChange, onScopeDigitsChange)
    MotionStylePicker(
        title = stringResource(R.string.motion_indicator_title),
        options = GenerationIndicatorStyle.entries.defaultFirst(DefaultMotion.indicator),
        selected = motion.indicator,
        label = { it.label() },
        tag = "theme_motion_indicator",
        onSelect = onIndicatorSelect,
        phaseDemo = true,
    ) { IndicatorPreview(it) }
    MotionStylePicker(
        title = stringResource(R.string.motion_empty_chat_title),
        options = EmptyChatStyle.entries.defaultFirst(DefaultMotion.emptyChat),
        selected = motion.emptyChat,
        label = { it.label() },
        tag = "theme_motion_empty_chat",
        onSelect = onEmptyChatSelect,
    ) { EmptyChatPreview(it) }
}

@Composable
private fun <T : Enum<T>> MotionStylePicker(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> MotionStyleLabel,
    tag: String,
    onSelect: (T) -> Unit,
    /** 预览按演示脚本轮播各生成阶段，面板顶部显示当前阶段。 */
    phaseDemo: Boolean = false,
    preview: @Composable (T) -> Unit,
) {
    var show by remember { mutableStateOf(false) }
    val anchor = rememberInfinityPopoverAnchor()
    val haptics = LocalInfinityHaptics.current
    InfinityValueRow(
        title = title,
        value = stringResource(label(selected).name),
        modifier = Modifier.infinityPopoverAnchor(anchor).testTag(tag),
        onClick = { if (!anchor.consumeRecentDismiss()) show = true },
    )
    InfinityBottomSheet(show = show, title = title, onDismiss = { show = false }, scrollContent = true, popover = InfinityPopoverSpec(anchor)) {
        val demo = if (phaseDemo) rememberDemoGenerationMotion() else LocalGenerationMotion.current
        CompositionLocalProvider(LocalGenerationMotion provides demo) {
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (phaseDemo) {
                // 演示脚本的思考段属于流式家族，阶段名单独标出“思考”。
                val phaseName = if (demo.demoThinking) R.string.motion_phase_thinking else (demo.displayed ?: GenerationMotionPhase.STREAM).labelRes()
                Text(
                    stringResource(R.string.motion_preview_phase, stringResource(phaseName)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp).testTag("${tag}_phase"),
                )
            }
            options.forEach { option ->
                val isSelected = option == selected
                val text = label(option)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    border = BorderStroke(
                        if (isSelected) 2.dp else 1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .selectable(selected = isSelected, role = Role.RadioButton) {
                            haptics.select(isSelected)
                            onSelect(option)
                            show = false
                        }
                        .testTag("${tag}_${option.name.lowercase()}"),
                ) {
                    Column {
                        preview(option)
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(text.name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                text.description?.let {
                                    Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            RadioButton(selected = isSelected, onClick = null)
                        }
                    }
                }
            }
        }
        }
    }
}

/** 浮岛材质：分段控件加一块生成中的预览，示例正文垫在浮岛后面，毛玻璃时能看到它被模糊。 */
@Composable
private fun ComposerSurfaceOptions(motion: InfinityMotionStyles, onSelect: (ComposerSurfaceStyle) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.motion_surface_title),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        InfinitySegmentedControl(
            segments = listOf(
                InfinitySegment(ComposerSurfaceStyle.FROSTED, stringResource(R.string.motion_surface_frosted), tag = "theme_motion_surface_frosted"),
                InfinitySegment(ComposerSurfaceStyle.AMBIENT, stringResource(R.string.motion_surface_ambient), tag = "theme_motion_surface_ambient"),
            ),
            selected = motion.surface,
            onSelect = onSelect,
            fillWidth = true,
            compact = true,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("theme_motion_surface"),
        )
    }
    // 预览跑演示生成脚本：环境光的内部漫光与点阵屏只有生成中才看得出来。
    CompositionLocalProvider(LocalGenerationMotion provides rememberDemoGenerationMotion()) {
        Box(Modifier.padding(horizontal = 8.dp).padding(bottom = 8.dp).testTag("theme_motion_surface_preview")) {
            ComposerPreview(motion.composer)
        }
    }
}

/** 运行坞点阵屏开关；打开时另有“显示数字”。 */
@Composable
private fun RunDockScopeOptions(
    motion: InfinityMotionStyles,
    onScopeChange: (Boolean) -> Unit,
    onDigitsChange: (Boolean) -> Unit,
) {
    SettingsToggle(
        title = stringResource(R.string.motion_run_dock_scope),
        description = stringResource(R.string.motion_run_dock_scope_desc),
        checked = motion.runDockScope,
        onCheckedChange = onScopeChange,
        modifier = Modifier.testTag("theme_motion_run_dock_scope"),
    )
    if (motion.runDockScope) {
        SettingsToggle(
            title = stringResource(R.string.motion_scope_digits),
            description = stringResource(R.string.motion_scope_digits_desc),
            checked = motion.scopeDigits,
            onCheckedChange = onDigitsChange,
            modifier = Modifier.testTag("theme_motion_scope_digits"),
        )
    }
}

/** 生成阶段在设置预览中的名称。 */
internal fun GenerationMotionPhase.labelRes(): Int = when (this) {
    GenerationMotionPhase.WAIT -> R.string.motion_phase_wait
    GenerationMotionPhase.STREAM -> R.string.motion_phase_stream
    GenerationMotionPhase.TOOL -> R.string.motion_phase_tool
    GenerationMotionPhase.APPROVAL -> R.string.motion_phase_approval
    GenerationMotionPhase.RETRY -> R.string.motion_phase_retry
}

/**
 * 浮岛预览：一个迷你浮岛（默认处于生成中），右下角画出停止键的位置；运行坞点阵屏打开时浮岛顶部多一行迷你运行坞。
 * 浮岛压在一段示例正文的下半部上，按当前材质画：毛玻璃时正文从浮岛后面模糊透出，环境光时被实色挡住。
 */
@Composable
private fun ComposerPreview(style: ComposerGlowStyle, generating: Boolean = true) {
    val styles = LocalInfinityMotionStyles.current.copy(composer = style)
    CompositionLocalProvider(LocalInfinityMotionStyles provides styles) {
        val glow = rememberComposerGlowState(generating = generating, error = false)
        val material = composerIslandMaterial()
        val backdrop = rememberComposerBackdrop(styles.surface)
        backdrop?.background = MaterialTheme.colorScheme.surfaceContainerLow
        Box(Modifier.fillMaxWidth().clipToBounds()) {
            Text(
                stringResource(R.string.motion_preview_backdrop),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.matchParentSize().composerBackdropSource(backdrop).padding(horizontal = 20.dp, vertical = 10.dp),
            )
            CompositionLocalProvider(LocalComposerBackdrop provides backdrop, LocalComposerGlow provides glow) {
            Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 40.dp, bottom = 18.dp)) {
            Surface(
                modifier = Modifier.fillMaxWidth().composerIslandGlow(glow, material.topHighlight),
                shape = RoundedCornerShape(ComposerIslandCorner),
                color = material.container,
                border = material.border,
            ) {
                Box(Modifier.onGloballyPositioned { glow.islandOriginInRoot = it.positionInRoot() }) {
                ComposerIslandSurface(glow, Modifier.matchParentSize())
                Column {
                if (styles.runDockScope) {
                    // 迷你运行坞：右侧是点阵屏，环境光柔光从这里漫开。
                    Row(
                        Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            stringResource(R.string.chat_progress_running),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        RunDockScope(RunDockScopeMode.LIVE, Modifier.width(120.dp))
                    }
                    ComposerDockStrip(glow)
                }
                Box(Modifier.fillMaxWidth().height(88.dp)) {
                    ComposerIslandLights(glow, style, Modifier.matchParentSize())
                    Text(
                        stringResource(R.string.motion_preview_placeholder),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 14.dp),
                    )
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(end = ComposerToolbarInset, bottom = ComposerToolbarInset).size(40.dp)
                            .clip(CircleShape).background(MaterialTheme.colorScheme.onSurface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(12.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.surface))
                    }
                }
                }
                }
            }
            }
            }
        }
    }
}

/** 指示预览：左边放大两倍看清细节，右边是实际大小。 */
@Composable
private fun IndicatorPreview(style: GenerationIndicatorStyle) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            GenerationDots(dotSize = 12.dp, gap = 10.dp, lift = 8.dp, style = style)
        }
        Box(Modifier.size(width = 72.dp, height = 40.dp), contentAlignment = Alignment.Center) {
            GenerationDots(style = style)
        }
    }
}

/** 空会话预览：缩小的画布与较少的粒子。 */
@Composable
private fun EmptyChatPreview(style: EmptyChatStyle) {
    Box(
        Modifier.fillMaxWidth().height(156.dp).padding(8.dp).clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        if (style == EmptyChatStyle.FLOW) {
            ParticleWordmark(dispersed = false, modifier = Modifier.fillMaxSize(), centerFraction = 0.5f, maxTextSizeDp = 36f, logoLimit = 380, ambientCount = 60)
        } else {
            LedEmptyChat(style = style, dispersed = false, modifier = Modifier.fillMaxSize(), centerFraction = 0.5f, particleScale = 0.35f)
        }
    }
}
