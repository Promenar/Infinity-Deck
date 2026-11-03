package com.promenar.infinity.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityDisclosureRow
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/** 工具卡左上角状态点与状态文字的色调。 */
internal enum class ToolStepTone { RUNNING, SUCCESS, FAILED, NEUTRAL }

private val ToolCardShape = RoundedCornerShape(12.dp)
private val ToolChipShape = RoundedCornerShape(6.dp)
private val ToolLinkShape = RoundedCornerShape(8.dp)

/** 工具与命令卡片共用的底色与描边；会话内其它嵌入卡片也用它对齐。 */
@Composable
internal fun Modifier.toolCardSurface(): Modifier = this
    .background(MaterialTheme.colorScheme.surfaceContainerLow, ToolCardShape)
    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), ToolCardShape)

@Composable
internal fun toolStepToneColor(tone: ToolStepTone): Color = when (tone) {
    ToolStepTone.RUNNING -> MaterialTheme.colorScheme.primary
    ToolStepTone.SUCCESS -> MaterialTheme.infinityDomainColors.success
    ToolStepTone.FAILED -> MaterialTheme.colorScheme.error
    ToolStepTone.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal val ToolStepMetaStyle @Composable get() = InfinityTypography.labelSmall.copy(fontSize = 11.sp, fontFeatureSettings = "tnum")

/**
 * 工具调用卡片的原子组件，所有工具与命令的调用详情都由它承载：
 * 标题行（图标、标题、右侧状态与耗时）→ 面向用户的摘要标签 → [content]（参数与正文）→ 底栏（完整结果入口、补充信息与情境操作）。
 * 各工具只决定摘要与正文的取值；容器、状态位置与折叠规则在此统一。给模型看的原文不直接铺开，经 [onOpenFull] 查看。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ToolStepCard(
    tone: ToolStepTone,
    status: String?,
    title: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    summary: List<String> = emptyList(),
    statusTag: String? = null,
    onOpenFull: (() -> Unit)? = null,
    openFullLabel: String? = null,
    footerMeta: String? = null,
    footerMetaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    footerActions: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    val meta = ToolStepMetaStyle
    val toneColor = toolStepToneColor(tone)
    Column(
        modifier = modifier.fillMaxWidth().toolCardSurface()
            .padding(horizontal = 10.dp, vertical = InfinitySpacing.Small),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
            title()
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(toneColor, CircleShape))
                    Text(
                        status, style = meta, color = if (tone == ToolStepTone.FAILED) toneColor else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 5.dp).then(if (statusTag != null) Modifier.testTag(statusTag) else Modifier),
                    )
                }
            }
        }
        if (summary.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                summary.forEach { chip ->
                    Text(
                        chip, style = meta, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, ToolChipShape)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
            }
        }
        content()
        if (onOpenFull != null || footerMeta != null || footerActions != null) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                onOpenFull?.let { open ->
                    Row(
                        modifier = Modifier.clip(ToolLinkShape).clickable(role = Role.Button, onClick = open)
                            .padding(vertical = 4.dp).padding(end = 6.dp).testTag(UiTags.CHAT_TOOL_FULL_OUTPUT),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Text(
                            openFullLabel ?: stringResource(R.string.tool_step_full_result), style = meta,
                            color = MaterialTheme.colorScheme.primary, maxLines = 1, modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                footerMeta?.let {
                    Text(it, style = meta, color = footerMetaColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = InfinitySpacing.Small))
                }
                footerActions?.let { actions ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = InfinitySpacing.Small)) { actions() }
                }
            }
        }
    }
}

/** 标题行文字：友好名或短标题占主位，工具名退为等宽小字。 */
@Composable
internal fun RowScope.ToolStepTitle(title: String, toolName: String?) {
    Text(title, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    Text(
        toolName.orEmpty(),
        style = InfinityTypography.labelSmall.copy(fontSize = 11.sp, fontFamily = InfinityMonospace),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
}

/** 字段化参数：默认合成两行预览，展开为字段表。 */
@Composable
internal fun ToolArgumentsPreview(rows: List<ToolFieldRow>) {
    var expanded by rememberSaveable(rows) { mutableStateOf(false) }
    if (expanded) {
        ToolFieldList(rows, arguments = true)
    } else {
        val preview = rows.filterNot { it.isHeader }.joinToString("　") { "${it.key}：${it.value.replace('\n', ' ')}" }
        Text(
            preview,
            style = InfinityTypography.bodySmall.copy(lineHeight = 18.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().testTag(UiTags.CHAT_TOOL_ARGUMENTS_PREVIEW),
        )
    }
    InfinityDisclosureRow(
        text = stringResource(if (expanded) R.string.tool_step_arguments_collapse else R.string.tool_step_arguments_expand),
        expanded = expanded,
        onClick = { expanded = !expanded },
    )
}

/** 完整结果面板：原样显示给模型的结果文本，可选中复制。 */
@Composable
internal fun ToolFullResultSheet(text: String?, onDismiss: () -> Unit) {
    InfinityBottomSheet(
        show = text != null,
        onDismiss = onDismiss,
        title = stringResource(R.string.tool_step_full_result),
        scrollContent = true,
    ) {
        SelectionContainer {
            Text(
                text.orEmpty(),
                style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp, lineHeight = 18.sp),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)
                    .testTag(UiTags.CHAT_TOOL_FULL_RESULT_TEXT),
            )
        }
    }
}
