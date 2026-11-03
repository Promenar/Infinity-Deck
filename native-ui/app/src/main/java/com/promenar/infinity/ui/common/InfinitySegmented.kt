package com.promenar.infinity.ui.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 分段滑块中的一段；[count] 以弱化数字跟在名称后，[leading] 是名称前的小图标或状态点。
 * [clickable] 为 false 的段只用于展示状态（例如选中的“自定义”档位）：照常显示选中滑块，但不可点按。
 */
class InfinitySegment<T>(
    val value: T,
    val label: String,
    val tag: String? = null,
    val count: Int? = null,
    val leading: (@Composable () -> Unit)? = null,
    val clickable: Boolean = true,
)

/**
 * Infinity Workbench 统一的单选分段滑块：浅色轨道里一块随选中项滑动的亮色滑块。
 * - 触控高度 48dp（[compact] 为 40dp），轨道与滑块在其中垂直内缩，视觉更轻；
 * - 段数多时整条轨道横向滚动，[fillWidth] 时各段等分整行宽度；
 * - 每段是可选中的单选项（读屏读出选中状态），点按时按“是否已选中”给振动反馈。
 */
@Composable
fun <T> InfinitySegmentedControl(
    segments: List<InfinitySegment<T>>,
    selected: T?,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fillWidth: Boolean = false,
    compact: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    scrollState: ScrollState = rememberScrollState(),
) {
    val haptics = LocalInfinityHaptics.current
    val density = LocalDensity.current
    val height = if (compact) 40.dp else 48.dp
    val trackInset = if (compact) 2.dp else InfinitySegmentTrackInset
    val rowPadding = trackInset + SegmentThumbGap
    // 各段在行内的位置与宽度（像素），用来摆放滑块。
    val bounds = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val selectedIndex = segments.indexOfFirst { it.value == selected }
    val thumbX = remember { Animatable(0f) }
    val thumbWidth = remember { Animatable(0f) }
    val target = bounds[selectedIndex]
    LaunchedEffect(target) {
        val (x, width) = target ?: return@LaunchedEffect
        if (thumbWidth.value == 0f) {
            thumbX.snapTo(x); thumbWidth.snapTo(width)
            return@LaunchedEffect
        }
        val motion = spring<Float>(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
        coroutineScope {
            launch { thumbX.animateTo(x, motion) }
            launch { thumbWidth.animateTo(width, motion) }
        }
    }
    val outer = if (fillWidth) modifier.fillMaxWidth().padding(contentPadding)
        else modifier.horizontalScroll(scrollState).padding(contentPadding)
    Box(outer.height(height).alpha(if (enabled) 1f else 0.38f)) {
        Box(
            Modifier
                .matchParentSize()
                .padding(vertical = trackInset)
                .clip(SegmentTrackShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        val thumbPlaced = thumbWidth.value > 0f
        if (selectedIndex >= 0 && thumbPlaced) {
            SegmentThumb(
                Modifier
                    .offset { IntOffset((rowPadding.toPx() + thumbX.value).roundToInt(), 0) }
                    .width(with(density) { thumbWidth.value.toDp() })
                    .fillMaxHeight()
                    .padding(vertical = trackInset + SegmentThumbGap),
            )
        }
        Row(
            modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .fillMaxHeight()
                .padding(horizontal = rowPadding)
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            segments.forEachIndexed { index, segment ->
                val isSelected = index == selectedIndex
                // 点击区占满控件高度，按压反馈画在与滑块同形的内缩区域，不溢出轨道。
                val interaction = remember { MutableInteractionSource() }
                Box(
                    modifier = (if (fillWidth) Modifier.weight(1f) else Modifier)
                        .fillMaxHeight()
                        .onGloballyPositioned { coordinates ->
                            bounds[index] = coordinates.positionInParent().x to coordinates.size.width.toFloat()
                        }
                        .selectable(
                            selected = isSelected,
                            interactionSource = interaction,
                            indication = null,
                            enabled = enabled && segment.clickable,
                            role = Role.RadioButton,
                            onClick = { haptics.select(isSelected); onSelect(segment.value) },
                        )
                        .then(segment.tag?.let { Modifier.testTag(it) } ?: Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    // 滑块要等首帧量出各段位置才能摆放，之前先在选中段原地画一块，避免首帧闪空。
                    if (isSelected && !thumbPlaced) {
                        SegmentThumb(Modifier.matchParentSize().padding(vertical = trackInset + SegmentThumbGap))
                    }
                    Box(Modifier.matchParentSize().padding(vertical = trackInset + SegmentThumbGap).pressFeedback(interaction, SegmentThumbShape))
                    SegmentLabel(segment.label, segment.count, segment.leading, isSelected, Modifier.padding(horizontal = if (compact) 10.dp else 12.dp))
                }
            }
        }
    }
}

@Composable
private fun SegmentThumb(modifier: Modifier) {
    Box(
        modifier
            .clip(SegmentThumbShape)
            .background(MaterialTheme.colorScheme.surfaceBright)
            .border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant), SegmentThumbShape),
    )
}

@Composable
private fun SegmentLabel(label: String, count: Int?, leading: (@Composable () -> Unit)?, selected: Boolean, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        leading?.invoke()
        Text(
            text = label,
            style = InfinityTypography.labelLarge.copy(fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal),
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        count?.let {
            Text(text = it.toString(), style = InfinityTypography.labelMedium, color = MaterialTheme.colorScheme.outline, maxLines = 1)
        }
    }
}

/**
 * 与分段滑块同一视觉语言的独立标签，用于多选筛选、开关和一次性动作：
 * 未选中是轨道色底，选中换成滑块的亮色底、细描边并加深文字；选中且没有自带图标时显示勾。
 * [selected] 为 null 表示动作标签（无选中态）；[haptic] 为 false 时由调用方自行给振动反馈。
 */
@Composable
fun InfinityChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    haptic: Boolean = true,
) {
    val haptics = LocalInfinityHaptics.current
    val on = selected == true
    val shape = SegmentTrackShape
    val clickModifier = if (selected == null) {
        Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    } else {
        Modifier.selectable(selected = on, enabled = enabled, role = Role.Checkbox, onClick = { if (haptic) haptics.select(false); onClick() })
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = ChipHeight)
            .clip(shape)
            .background(if (on) MaterialTheme.colorScheme.surfaceBright else MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (on) Modifier.border(BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant), shape) else Modifier)
            .then(clickModifier)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            when {
                leading != null -> leading()
                on -> Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            }
            Text(
                text = label,
                style = InfinityTypography.labelLarge.copy(fontWeight = if (on) FontWeight.Medium else FontWeight.Normal),
                color = if (on || selected == null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            trailing?.invoke()
        }
    }
}

private val SegmentTrackShape = RoundedCornerShape(12.dp)

/** 标准分段控件的轨道在控件高度内上下各内缩这么多，可见轨道比控件本身矮。 */
val InfinitySegmentTrackInset = 4.dp
private val SegmentThumbShape = RoundedCornerShape(9.dp)
private val SegmentThumbGap = 3.dp
private val ChipHeight: Dp = 36.dp
