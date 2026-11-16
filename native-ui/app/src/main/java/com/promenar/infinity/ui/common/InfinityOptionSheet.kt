package com.promenar.infinity.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * 下拉选项与短单选面板共用的 30dp 圆角图标底板。
 * 支持品牌着色或主题默认语义着色，保持纯矢量呈现。
 */
@Composable
internal fun InfinityOptionIconBadge(
    icon: ImageVector? = null,
    @DrawableRes drawableRes: Int? = null,
    selected: Boolean = false,
    accentColor: Color? = null,
    destructive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val containerColor = when {
        destructive -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)
        accentColor != null -> accentColor.copy(alpha = if (selected) 0.22f else 0.14f)
        selected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f)
        else -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.72f)
    }
    val tintColor = when {
        destructive -> MaterialTheme.colorScheme.error
        accentColor != null -> accentColor
        selected -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = modifier
            .size(30.dp)
            .background(containerColor, RoundedCornerShape(9.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when {
            drawableRes != null -> Icon(
                painter = painterResource(drawableRes),
                contentDescription = null,
                tint = tintColor,
                modifier = Modifier.size(18.dp),
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tintColor,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** 选项面板内的分组小标题，统一使用主色 labelMedium。 */
@Composable
internal fun InfinityOptionSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = 10.dp, bottom = 4.dp),
    )
}

/**
 * 选项面板标准行：圆角图标底板、多行标题/副标题以及选中态主色对勾。
 */
@Composable
internal fun InfinityOptionItem(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleSegments: List<String>? = null,
    subtitleIsIdentifier: Boolean = false,
    subtitleMaxLines: Int = 2,
    titleMaxLines: Int = 2,
    icon: ImageVector? = null,
    @DrawableRes drawableRes: Int? = null,
    accentColor: Color? = null,
    destructive: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
) {
    val hasLeading = icon != null || drawableRes != null
    InfinitySettingsItem(
        horizontalPadding = 0.dp,
        title = title,
        subtitle = subtitle,
        subtitleSegments = subtitleSegments,
        subtitleIsIdentifier = subtitleIsIdentifier,
        subtitleMaxLines = subtitleMaxLines,
        titleMaxLines = titleMaxLines,
        titleColor = if (destructive) MaterialTheme.colorScheme.error else null,
        leadingContent = if (hasLeading) {
            {
                InfinityOptionIconBadge(
                    icon = icon,
                    drawableRes = drawableRes,
                    selected = selected,
                    accentColor = accentColor,
                    destructive = destructive,
                )
            }
        } else {
            null
        },
        onClick = onClick,
        enabled = enabled,
        role = role,
        selected = selected,
        modifier = modifier,
        trailingContent = {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        },
    )
}
