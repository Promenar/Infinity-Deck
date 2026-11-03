package com.promenar.infinity.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing

/** [InfinityChoiceChips] 的一项。 */
@Immutable
class InfinityChoice<T>(
    val value: T,
    val label: String,
    val icon: ImageVector? = null,
    val tag: String? = null,
)

/**
 * 单选或多选标签组，放不下时自动换行。
 * - [multiSelect] 为 false 时是单选组（读屏按单选项朗读），点已选中的项不变；
 * - 选中态用次要容器色填充，图标转为主色；
 * - 每个标签视觉高 36dp，外层补足 48dp 触控高度。
 */
@Composable
fun <T> InfinityChoiceChips(
    choices: List<InfinityChoice<T>>,
    selected: Set<T>,
    onToggle: (T) -> Unit,
    modifier: Modifier = Modifier,
    multiSelect: Boolean = true,
    enabled: Boolean = true,
) {
    val haptics = LocalInfinityHaptics.current
    FlowRow(
        modifier = modifier.padding(horizontal = InfinitySpacing.RowHorizontal - ChipTouchInset),
        horizontalArrangement = Arrangement.spacedBy(ChipGap - ChipTouchInset * 2),
    ) {
        choices.forEach { choice ->
            val isSelected = choice.value in selected
            // 48dp 外盒撑触控区，按压反馈画在 36dp 标签内。
            val press = remember { MutableInteractionSource() }
            val interaction = if (multiSelect) {
                Modifier.toggleable(value = isSelected, interactionSource = press, indication = null, enabled = enabled, role = Role.Checkbox) {
                    haptics.toggle(it)
                    onToggle(choice.value)
                }
            } else {
                Modifier.selectable(selected = isSelected, interactionSource = press, indication = null, enabled = enabled, role = Role.RadioButton) {
                    haptics.select(isSelected)
                    if (!isSelected) onToggle(choice.value)
                }
            }
            InfinityChoiceChip(
                choice = choice,
                selected = isSelected,
                enabled = enabled,
                press = press,
                modifier = (choice.tag?.let { Modifier.testTag(it) } ?: Modifier)
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .then(interaction)
                    .padding(horizontal = ChipTouchInset),
            )
        }
    }
}

@Composable
private fun <T> InfinityChoiceChip(
    choice: InfinityChoice<T>,
    selected: Boolean,
    enabled: Boolean,
    press: MutableInteractionSource,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) scheme.secondaryContainer else scheme.surfaceContainerHigh.copy(alpha = 0f),
        tween(InfinityMotion.DurationShort),
        label = "choice_chip_container",
    )
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Row(
            modifier = Modifier
                .heightIn(min = ChipHeight)
                .pressFeedback(press, ChipShape)
                .background(container)
                .then(
                    if (selected) Modifier
                    else Modifier.border(BorderStroke(1.dp, scheme.outlineVariant), ChipShape),
                )
                .padding(start = if (choice.icon != null) 10.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            choice.icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (selected) scheme.primary else content,
                )
            }
            Text(
                text = choice.label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) content else content.copy(alpha = 0.38f),
                maxLines = 1,
            )
        }
    }
}

private val ChipHeight = 36.dp
private val ChipGap = 8.dp
/** 标签两侧让出的触控余量；FlowRow 间距相应收窄，视觉间距仍是 [ChipGap]。 */
private val ChipTouchInset = 2.dp
private val ChipShape = RoundedCornerShape(10.dp)
