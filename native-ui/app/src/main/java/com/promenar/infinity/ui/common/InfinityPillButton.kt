package com.promenar.infinity.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 胶囊按钮配色：主操作为主色实心；次要操作为中性色块；危险的次要操作为中性色块配错误色文字；
 * 危险的主操作（如重置、清空）为错误色实心。
 */
enum class InfinityPillTone { PRIMARY, NEUTRAL, DANGER, DESTRUCTIVE }

private val PillHeight = 44.dp
private val CompactPillHeight = 32.dp
private val PillRadius = 22.dp
private val PillShape = RoundedCornerShape(PillRadius)

/**
 * 卡片、面板与表单里的操作按钮，与审批卡胶囊同一视觉：可见高度 44dp、圆角 22dp，外围补足 48dp 触控区。
 * 文字始终单行，不折成两排；同一操作区的多个操作放进 [InfinityPillRow]，放不下时整行改为竖排而不是压缩文字。
 * [compact] 用于列表行、状态条和标题行行尾：可见高度 32dp，触控区同样补足 48dp。
 */
@Composable
fun InfinityPillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: InfinityPillTone = InfinityPillTone.NEUTRAL,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    compact: Boolean = false,
) {
    InfinityPillSurface(
        onClick = onClick,
        tone = tone,
        enabled = enabled,
        shape = PillShape,
        modifier = modifier.minimumInteractiveComponentSize(),
        horizontalPadding = when {
            compact -> 12.dp
            icon != null -> 14.dp
            else -> 16.dp
        },
        minHeight = if (compact) CompactPillHeight else PillHeight,
    ) { contentColor -> PillLabel(text, icon, tone, contentColor, compact) }
}

/**
 * 分体胶囊：主体执行最常用的操作，右侧相连的 44dp 箭头段打开其余同类操作的菜单，不另占行。
 * 组件填满所给宽度；[menuContent] 收到关闭菜单的回调，菜单项点击后先关闭再执行。
 */
@Composable
fun InfinitySplitPillButton(
    text: String,
    onClick: () -> Unit,
    moreContentDescription: String,
    modifier: Modifier = Modifier,
    tone: InfinityPillTone = InfinityPillTone.NEUTRAL,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    mainModifier: Modifier = Modifier,
    moreModifier: Modifier = Modifier,
    menuMinWidth: Dp = 240.dp,
    initialMenuExpanded: Boolean = false,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(initialMenuExpanded) }
    Row(
        modifier = modifier.minimumInteractiveComponentSize().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        InfinityPillSurface(
            onClick = onClick,
            tone = tone,
            enabled = enabled,
            shape = RoundedCornerShape(topStart = PillRadius, bottomStart = PillRadius, topEnd = 6.dp, bottomEnd = 6.dp),
            modifier = mainModifier.weight(1f).fillMaxHeight(),
            horizontalPadding = 12.dp,
        ) { contentColor -> PillLabel(text, icon, tone, contentColor) }
        Box {
            InfinityPillSurface(
                onClick = { expanded = true },
                tone = tone,
                enabled = enabled,
                shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp, topEnd = PillRadius, bottomEnd = PillRadius),
                modifier = moreModifier.width(PillHeight).fillMaxHeight().semantics { contentDescription = moreContentDescription },
                horizontalPadding = 0.dp,
            ) { contentColor ->
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
            }
            InfinityDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, minWidth = menuMinWidth) {
                menuContent { expanded = false }
            }
        }
    }
}

/** 与胶囊按钮同排的圆形“更多”按钮：低频或危险的操作收进菜单，不占操作区的位置。 */
@Composable
fun InfinityPillMenuButton(
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector = Icons.Rounded.MoreHoriz,
    menuMinWidth: Dp = 200.dp,
    initialMenuExpanded: Boolean = false,
    menuContent: @Composable ColumnScope.(dismiss: () -> Unit) -> Unit,
) {
    var expanded by remember { mutableStateOf(initialMenuExpanded) }
    Box(modifier.minimumInteractiveComponentSize()) {
        InfinityPillSurface(
            onClick = { expanded = true },
            tone = InfinityPillTone.NEUTRAL,
            enabled = enabled,
            shape = PillShape,
            modifier = Modifier.size(PillHeight).semantics { this.contentDescription = contentDescription },
            horizontalPadding = 0.dp,
        ) { contentColor ->
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(22.dp))
        }
        InfinityDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, minWidth = menuMinWidth) {
            menuContent { expanded = false }
        }
    }
}

/**
 * 胶囊操作行：每个按钮都能以单行文字放进等分宽度时并排等宽排列；任一按钮放不下就整组竖排、各占满宽，
 * 保证按钮文字永远不折行。并排时主操作放在最后（最右），[reverseWhenStacked] 为真时竖排倒序，让主操作在最上。
 * 没有主次之分的同级操作（如两个替代方案）传 false，竖排保持原顺序。
 * 父布局剩余高度不足时，报告的高度截到约束以内、按钮仍从顶部排起，超出部分由父布局裁切，不会上移盖住前面的内容。
 */
@Composable
fun InfinityPillRow(
    modifier: Modifier = Modifier,
    spacing: Dp = 10.dp,
    stackedSpacing: Dp = 4.dp,
    reverseWhenStacked: Boolean = true,
    content: @Composable () -> Unit,
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        if (measurables.isEmpty()) return@Layout layout(constraints.minWidth, constraints.minHeight) {}
        val gap = spacing.roundToPx()
        val singleLineWidths = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val width = if (constraints.hasBoundedWidth) {
            constraints.maxWidth
        } else {
            singleLineWidths.max() * measurables.size + gap * (measurables.size - 1)
        }
        val slot = (width - gap * (measurables.size - 1)) / measurables.size
        if (singleLineWidths.all { it <= slot }) {
            val height = measurables.maxOf { it.maxIntrinsicHeight(slot) }
            val placeables = measurables.map { it.measure(Constraints.fixed(slot, height)) }
            layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                placeables.forEachIndexed { index, placeable -> placeable.placeRelative(index * (slot + gap), 0) }
            }
        } else {
            val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width)) }
                .let { if (reverseWhenStacked) it.asReversed() else it }
            val stackedGap = stackedSpacing.roundToPx()
            val height = placeables.sumOf { it.height } + stackedGap * (placeables.size - 1)
            layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
                var y = 0
                placeables.forEach { placeable ->
                    placeable.placeRelative(0, y)
                    y += placeable.height + stackedGap
                }
            }
        }
    }
}

@Composable
private fun PillLabel(text: String, icon: ImageVector?, tone: InfinityPillTone, contentColor: Color, compact: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        icon?.let { Icon(it, contentDescription = null, tint = contentColor, modifier = Modifier.size(if (compact) 16.dp else 18.dp)) }
        Text(
            text,
            style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
            fontWeight = if (tone == InfinityPillTone.PRIMARY || tone == InfinityPillTone.DESTRUCTIVE) FontWeight.Medium else FontWeight.Normal,
            color = contentColor,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InfinityPillSurface(
    onClick: () -> Unit,
    tone: InfinityPillTone,
    enabled: Boolean,
    shape: Shape,
    modifier: Modifier,
    horizontalPadding: Dp,
    minHeight: Dp = PillHeight,
    content: @Composable (contentColor: Color) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fill = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        tone == InfinityPillTone.PRIMARY -> colors.primary
        tone == InfinityPillTone.DESTRUCTIVE -> colors.error
        else -> colors.surfaceContainerHighest
    }
    val contentColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        tone == InfinityPillTone.PRIMARY -> colors.onPrimary
        tone == InfinityPillTone.DESTRUCTIVE -> colors.onError
        tone == InfinityPillTone.DANGER -> colors.error
        else -> colors.onSurface
    }
    // 按压反馈取 LocalContentColor：主色、危险色胶囊上用其前景色，而不是外层的 onSurface。
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier = modifier
                .heightIn(min = minHeight)
                .clip(shape)
                .background(fill)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = horizontalPadding, vertical = if (minHeight < PillHeight) 4.dp else 6.dp),
            contentAlignment = Alignment.Center,
        ) { content(contentColor) }
    }
}
