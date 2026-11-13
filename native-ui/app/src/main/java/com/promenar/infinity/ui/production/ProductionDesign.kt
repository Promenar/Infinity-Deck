package com.promenar.infinity.ui.production

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.BettboxListGroup
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityPaneHeaderTitle
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/* 制作面板的版式构件：与全站设置页同一套分组色面、行高与分组标题，原型的列表行、状态标签与页头都由这里给出。 */

/** 分组卡片内自由内容的内边距，与列表行的水平留白对齐。 */
internal val ProductionCardInset = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = InfinitySpacing.Small)

/** 分组：主色小标题（可带右侧说明），下接一张圆角色面。 */
@Composable
internal fun ProductionSection(
    title: String?,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    titleAction: (@Composable RowScope.() -> Unit)? = null,
    titleSegments: List<String>? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) ProductionSectionHeader(title, trailing = trailing, action = titleAction, titleSegments = titleSegments)
        BettboxListGroup {
            Column(Modifier.padding(vertical = InfinitySpacing.XSmall), content = content)
        }
    }
}

/** 分组标题：与 [com.promenar.infinity.ui.common.InfinitySettingsSection] 的标题同字号同色。 */
@Composable
internal fun ProductionSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    subtitle: String? = null,
    action: (@Composable RowScope.() -> Unit)? = null,
    titleSegments: List<String>? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 28.dp).padding(start = InfinitySpacing.RowHorizontal, end = if (action != null) 4.dp else InfinitySpacing.RowHorizontal, bottom = InfinitySpacing.SectionHeaderBottom),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Column(Modifier.weight(1f)) {
            val titleStyle = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium)
            val heading = Modifier.semantics { heading() }
            val titleColor = MaterialTheme.colorScheme.primary
            if (titleSegments != null) {
                InfinityMetaLine(titleSegments, style = titleStyle, color = titleColor, modifier = heading)
            } else {
                Text(title, style = titleStyle, color = titleColor, modifier = heading)
            }
            subtitle?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
        action?.invoke(this)
    }
}

/**
 * 分组内的一行：可带前置图标、副标题、右侧数值、状态标签与右箭头。
 * 右侧数值右对齐、弱化显示；可点行默认带右箭头。
 */
@Composable
internal fun ProductionRow(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    subtitle: String? = null,
    subtitleSegments: List<String>? = null,
    value: String? = null,
    chip: ProductionChipSpec? = null,
    chevron: Boolean? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    titleColor: Color? = null,
    subtitleMaxLines: Int = 1,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    role: Role = Role.Button,
    titleSegments: List<String>? = null,
    valueSegments: List<String>? = null,
    onClick: (() -> Unit)? = null,
) {
    val showChevron = chevron ?: (onClick != null)
    val displayValue = valueSegments?.takeIf { it.isNotEmpty() }?.joinToString(" · ") ?: value // wrap-check: allow 右侧数值固定单行省略
    val hasTrailing = displayValue != null || chip != null || trailing != null || showChevron || selected
    InfinitySettingsRowLayout(
        title = title,
        titleSegments = titleSegments,
        subtitle = subtitle,
        subtitleSegments = subtitleSegments,
        icon = icon,
        leadingContent = leading,
        enabled = enabled,
        titleColor = titleColor,
        subtitleMaxLines = subtitleMaxLines,
        trailingMaxWidthFraction = 0.62f,
        modifier = modifier.then(if (onClick != null) Modifier.clickable(enabled = enabled, role = role, onClick = onClick) else Modifier),
        trailingContent = if (!hasTrailing) null else ({
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                displayValue?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                chip?.let { ProductionStatusChip(it.text, it.tone) }
                trailing?.invoke()
                if (selected) Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                if (showChevron) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(20.dp), MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }),
    )
}

internal data class ProductionChipSpec(val text: String, val tone: ProductionTone)

/** 分组内的说明行：无交互的弱化文字，与列表行同样留白。 */
@Composable
internal fun ProductionMutedRow(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().heightIn(min = InfinitySpacing.RowSingleLineHeight).padding(horizontal = InfinitySpacing.RowHorizontal), contentAlignment = Alignment.CenterStart) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 细进度条：与原型一致的 5dp 圆头条。 */
@Composable
internal fun ProductionProgressBar(progress: Float?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    if (progress == null) {
        LinearProgressIndicator(modifier.fillMaxWidth().height(5.dp), strokeCap = StrokeCap.Round, gapSize = 0.dp, trackColor = track)
    } else {
        LinearProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = modifier.fillMaxWidth().height(5.dp),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
            trackColor = track,
        )
    }
}

/**
 * 面板与次级页的页头：返回、标题与一行信息、右侧“发送”与操作。
 * [onBack] 为空时（宽屏检视器根层）不画返回；“发送”由 [LocalProductionSend] 提供，面板以外不画。
 */
@Composable
internal fun ProductionTopBar(
    title: String,
    meta: List<String>,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    metaAccent: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // 有返回键为窄屏整屏面板；没有时在检视器页签里，与文件页页头同一规格：最小 64dp、四周 4dp，标题块左右再留 8dp。
    val inPane = onBack == null
    Row(
        modifier.fillMaxWidth().then(
            if (inPane) Modifier.heightIn(min = ProductionPaneHeaderMinHeight).padding(InfinitySpacing.XSmall)
            else Modifier.heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp),
        ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            InfinityBackButton(onClick = onBack)
            Spacer(Modifier.width(4.dp))
        } else {
            Spacer(Modifier.width(InfinitySpacing.Small))
        }
        InfinityPaneHeaderTitle(
            title = title,
            modifier = Modifier.weight(1f).then(if (inPane) Modifier.padding(horizontal = InfinitySpacing.Small) else Modifier),
            meta = if (meta.isEmpty() && metaAccent == null) null else ({
                Text(meta.joinToString(" · "), maxLines = 1, softWrap = false) // wrap-check: allow 共享页头信息行固定单行横滑
                metaAccent?.let {
                    Text(if (meta.isEmpty()) it else " · $it", color = MaterialTheme.infinityDomainColors.onWarningContainer, maxLines = 1, softWrap = false) // wrap-check: allow 同一横滑行的独立警告色
                }
            }),
        )
        LocalProductionSend.current?.let { send -> ProductionSendButton(send, Modifier.padding(start = InfinitySpacing.XSmall, end = if (inPane) InfinitySpacing.Small else 4.dp)) }
        actions()
    }
}

/** 检视器页签页头的最小高度，与文件页页头一致。 */
private val ProductionPaneHeaderMinHeight = 64.dp

/** 选项面板：每项一行，可带说明，当前项打勾；与原型的底部弹层同一形式。 */
@Composable
internal fun <T> ProductionChoiceSheet(
    title: String,
    options: List<ProductionChoice<T>>,
    selected: T?,
    onDismiss: () -> Unit,
    subtitle: String? = null,
    emptyText: String? = null,
    onSelect: (T) -> Unit,
) {
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = title, scrollContent = true) {
        Column(Modifier.fillMaxWidth()) {
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 4.dp))
            }
            if (options.isEmpty() && emptyText != null) ProductionMutedRow(emptyText)
            options.forEach { option ->
                ProductionRow(
                    title = option.label,
                    titleSegments = option.labelSegments,
                    subtitle = option.detail,
                    icon = option.icon,
                    chip = option.chip,
                    chevron = false,
                    selected = option.value == selected,
                    role = Role.RadioButton,
                    onClick = { onSelect(option.value); onDismiss() },
                )
            }
            Spacer(Modifier.height(InfinitySpacing.Small))
        }
    }
}

internal data class ProductionChoice<T>(
    val value: T,
    val label: String,
    val detail: String? = null,
    val icon: ImageVector? = null,
    val chip: ProductionChipSpec? = null,
    val labelSegments: List<String>? = null,
)
