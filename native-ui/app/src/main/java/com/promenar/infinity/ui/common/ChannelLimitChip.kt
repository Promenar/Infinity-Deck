package com.promenar.infinity.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RichTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.launch

/**
 * 发行渠道之间的能力差异：只在受限的渠道显示一枚小标签，轻点弹出一句说明，不在页面里堆放解释文字。
 * [label] 是两到六个字的结论（如“仅前台运行”），[title] 与可选的 [body] 只在气泡里出现。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelLimitChip(
    label: String,
    title: String,
    body: String?,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    val state = rememberTooltipState(isPersistent = true)
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = {
            RichTooltip(title = { Text(title) }) { body?.let { Text(it) } }
        },
        state = state,
        modifier = modifier,
    ) {
        Surface(
            onClick = { scope.launch { state.show() } },
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier
                .heightIn(min = 32.dp)
                .semantics(mergeDescendants = true) {
                    role = Role.Button
                    contentDescription = "$label。$title"
                }
                .testTag(UiTags.CHANNEL_LIMIT_CHIP),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = InfinitySpacing.Small + 2.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp))
                Text(label, style = InfinityTypography.labelSmall, maxLines = 1)
                Icon(Icons.Outlined.Info, contentDescription = null, modifier = Modifier.size(12.dp))
            }
        }
    }
}
