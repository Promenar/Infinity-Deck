package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.common.InfinityDisclosureRow

/** 紧凑图标按钮的可见与布局尺寸。 */
internal val ChatCompactIconSize = 40.dp

/** 可见尺寸与布局尺寸一致；clickable 按平台的最小触控尺寸向外扩展命中范围。 */
@Composable
internal fun ChatCompactIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = Color.Transparent,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier.size(ChatCompactIconSize).clip(CircleShape).background(containerColor)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
internal fun ChatCompactApprovalButton(
    label: String,
    onClick: () -> Unit,
    outlined: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fill = when {
        outlined -> Color.Transparent
        enabled -> colors.primary
        else -> colors.onSurface.copy(alpha = 0.12f)
    }
    val textColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        outlined -> colors.primary
        else -> colors.onPrimary
    }
    Box(
        modifier = modifier.height(40.dp).clip(ButtonDefaults.shape).background(fill)
            .then(if (outlined) Modifier.border(BorderStroke(1.dp, colors.outline), ButtonDefaults.shape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = textColor)
    }
}

/** 会话气泡里的展开/收起：统一用折叠行，[expanded] 决定箭头方向。 */
@Composable
internal fun ChatExpandLink(text: String, expanded: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    InfinityDisclosureRow(text = text, expanded = expanded, onClick = onClick, modifier = modifier)
}
