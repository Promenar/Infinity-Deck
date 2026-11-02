package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forReading

private const val MESSAGE_COLLAPSED_LINES = 4
private const val DETAILS_COLLAPSED_LINES = 6

/**
 * 工具失败详情：与参数块同形、以错误色低透明度着色的底板，深浅主题都不压过正文。标题行表达失败并列出错误码类标签，
 * 说明用正文次要色，命令输出等细节用等宽小字；超出折叠行数时统一展开。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolErrorBlock(content: String) {
    val error = remember(content) { toolErrorPresentation(content) }
    var expanded by rememberSaveable(content) { mutableStateOf(false) }
    var messageOverflows by remember(content) { mutableStateOf(false) }
    var detailsOverflow by remember(content) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(colors.error.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
            .padding(10.dp)
            .testTag("CHAT_TOOL_ERROR"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = colors.error, modifier = Modifier.size(16.dp))
            Text(stringResource(R.string.chat_tool_error_title), style = InfinityTypography.labelMedium, color = colors.error)
            error.tags.forEach { tag ->
                Text(
                    tag,
                    style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace, fontSize = 11.sp),
                    color = colors.error,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(colors.error.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
        if (error.message.isNotBlank()) {
            Text(
                error.message,
                style = InfinityTypography.bodySmall.forReading(),
                color = colors.onSurface,
                maxLines = if (expanded) Int.MAX_VALUE else MESSAGE_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) messageOverflows = it.hasVisualOverflow },
                modifier = Modifier.testTag("CHAT_TOOL_ERROR_MESSAGE"),
            )
        }
        error.details?.let { details ->
            Text(
                details,
                style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp, lineHeight = 18.sp),
                color = colors.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else DETAILS_COLLAPSED_LINES,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) detailsOverflow = it.hasVisualOverflow },
                modifier = Modifier.fillMaxWidth()
                    .background(colors.surfaceContainerLow, RoundedCornerShape(6.dp))
                    .padding(8.dp)
                    .testTag("CHAT_TOOL_ERROR_DETAILS"),
            )
        }
        if (messageOverflows || detailsOverflow || expanded) {
            ChatExpandLink(
                text = stringResource(if (expanded) R.string.chat_step_collapse_all else R.string.chat_step_expand_all),
                expanded = expanded,
                onClick = { expanded = !expanded },
                modifier = Modifier.testTag("CHAT_TOOL_EXPAND_ALL"),
            )
        }
    }
}
