package com.promenar.infinity.ui.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.onSelectionIndicator
import com.promenar.infinity.ui.theme.selectionIndicator

/**
 * 单项页签描述模型
 */
data class InfinityTabItem(
    val title: String,
    val icon: ImageVector? = null,
    val badge: String? = null,
    val testTag: String? = null,
)

/** 滑动底座与胶囊容器四边之间的视觉内缩。 */
private val InfinityTabSwitcherInset = 4.dp

/** 容器总高度 56dp：滑动底座上下内缩后仍为 48dp，页签触控区则占满整个容器高度。 */
private val InfinityTabSwitcherHeight = InfinitySpacing.MinimumTouchTarget + InfinityTabSwitcherInset * 2

/**
 * 全站统一全圆胶囊 Tab 切换组件 (移植自 Homebar 与知识库设计语言)
 *
 * 特性：
 * 1. 外层全胶囊容器 (CircleShape) + surfaceContainer 底色 + outlineVariant 0.5f 微描边；
 * 2. 同心全圆胶囊滑动底座（主色淡染 [selectionIndicator]）+ 物理弹簧动画 (Spring, dampingRatio=0.85f)；
 * 3. 切换触发原生 SEGMENT_TICK 触觉反馈震动；
 * 4. 选中项加粗并使用主色，未选中态为 onSurfaceVariant；
 * 5. 容器高度由组件统一为 56dp，调用方不可压低；上下 4dp 内缩只作用于滑动底座，
 *    页签触控区占满容器高度，避免内边距按像素取整后触控高度低于 48dp。
 */
@Composable
fun InfinityTabSwitcher(
    itemCount: Int,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.selectionIndicator(containerColor),
    itemRole: Role = Role.Tab,
    tabModifier: (index: Int) -> Modifier = { Modifier },
    tabContent: @Composable (index: Int, isSelected: Boolean, contentColor: Color) -> Unit,
) {
    if (itemCount <= 0) return

    val haptics = LocalInfinityHaptics.current
    // 越界表示没有选中项（如表单里当前值不在选项中），此时不画底座。
    val hasSelection = selectedIndex in 0 until itemCount
    val safeSelectedIndex = selectedIndex.coerceIn(0, itemCount - 1)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(InfinityTabSwitcherHeight)
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = CircleShape,
            )
            .padding(horizontal = InfinityTabSwitcherInset),
    ) {
        val tabWidth = maxWidth / itemCount
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * safeSelectedIndex,
            animationSpec = spring(
                dampingRatio = 0.85f,
                stiffness = Spring.StiffnessMediumLow,
            ),
            label = "infinityTabIndicatorOffset",
        )

        // 胶囊滑动高亮底座
        if (hasSelection) Box(
            modifier = Modifier
                .offset(x = indicatorOffset)
                .width(tabWidth)
                .fillMaxHeight()
                .padding(vertical = InfinityTabSwitcherInset)
                .clip(CircleShape)
                .background(indicatorColor),
        )

        // 选项文本与点击行
        Row(
            modifier = Modifier
                .fillMaxSize()
                .selectableGroup(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (index in 0 until itemCount) {
                val selected = hasSelection && safeSelectedIndex == index
                val contentColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.onSelectionIndicator
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    animationSpec = tween(durationMillis = 200),
                    label = "infinityTabContentColor_$index",
                )
                // 触控区占满容器高度，按压反馈画在与滑动底座同形的内缩胶囊里。
                val interaction = remember { MutableInteractionSource() }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .selectable(
                            selected = selected,
                            interactionSource = interaction,
                            indication = null,
                            role = itemRole,
                            onClick = {
                                if (!selected) {
                                    haptics.perform(InfinityHapticEvent.SELECTION)
                                    onTabSelected(index)
                                }
                            },
                        )
                        .then(tabModifier(index)),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.matchParentSize().padding(vertical = InfinityTabSwitcherInset).pressFeedback(interaction, CircleShape))
                    tabContent(index, selected, contentColor)
                }
            }
        }
    }
}

/**
 * 针对标准 InfinityTabItem 列表的开箱即用胶囊 Tab 切换器
 */
@Composable
fun InfinityCapsuleTabRow(
    tabs: List<InfinityTabItem>,
    selectedIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    indicatorColor: Color = MaterialTheme.colorScheme.selectionIndicator(containerColor),
    itemRole: Role = Role.Tab,
) {
    InfinityTabSwitcher(
        itemCount = tabs.size,
        selectedIndex = selectedIndex,
        onTabSelected = onTabSelected,
        modifier = modifier,
        containerColor = containerColor,
        indicatorColor = indicatorColor,
        itemRole = itemRole,
        tabModifier = { index ->
            tabs.getOrNull(index)?.testTag?.let { Modifier.testTag(it) } ?: Modifier
        },
    ) { index, selected, contentColor ->
        val item = tabs.getOrNull(index) ?: return@InfinityTabSwitcher
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        ) {
            if (item.icon != null) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            Text(
                text = item.title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.badge.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = item.badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = contentColor.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
    }
}
