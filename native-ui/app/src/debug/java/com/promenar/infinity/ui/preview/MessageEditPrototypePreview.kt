package com.promenar.infinity.ui.preview

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.ForkRight
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityMenuDivider
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.infinityMenuColors
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.forReading

/**
 * 已发送用户消息「编辑与重生成」高保真隔离原型：
 * 1. 长按菜单新增「编辑」入口；
 * 2. 最后一轮就地编辑重生成（可移出原图片/文档/引用）；
 * 3. 历史中间轮次提供「新建分支并生成」与「就地重新生成」双路径；
 * 4. 折叠屏宽屏英文布局。
 */
@Composable
private fun MessageEditPrototypeFrame(
    mode: InfinityThemeMode,
    content: @Composable () -> Unit,
) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = mode,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}

private data class ProtoRemovableDoc(val id: String, val name: String, val meta: String)
private data class ProtoRemovableQuote(val id: String, val excerpt: String, val note: String)

@Composable
private fun ProtoInlineUserMessageEditorCard(
    zh: Boolean,
    initialText: String,
    subsequentTurns: Int,
    hasImage: Boolean = false,
    docs: List<ProtoRemovableDoc> = emptyList(),
    quotes: List<ProtoRemovableQuote> = emptyList(),
) {
    var text by remember(initialText) { mutableStateOf(initialText) }
    var imageKept by remember(hasImage) { mutableStateOf(hasImage) }
    var docItems by remember(docs) { mutableStateOf(docs) }
    var quoteItems by remember(quotes) { mutableStateOf(quotes) }
    val scheme = MaterialTheme.colorScheme
    val hasAnyPayload = text.isNotBlank() || imageKept || docItems.isNotEmpty() || quoteItems.isNotEmpty()

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.42f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = if (zh) "编辑消息" else "Edit message",
                    style = InfinityTypography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
            }

            if (imageKept || docItems.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (imageKept) {
                        ProtoRemovableImageChip(
                            label = if (zh) "架构草图.png" else "architecture.png",
                            removeCd = if (zh) "移除图片" else "Remove image",
                            onRemove = { imageKept = false },
                        )
                    }
                    docItems.forEach { doc ->
                        ProtoRemovableDocChip(
                            doc = doc,
                            removeCd = if (zh) "移除文档 ${doc.name}" else "Remove ${doc.name}",
                            onRemove = { docItems = docItems.filterNot { it.id == doc.id } },
                        )
                    }
                }
            }

            quoteItems.forEach { quote ->
                ProtoRemovableQuoteCard(
                    quote = quote,
                    removeCd = if (zh) "移除引用" else "Remove quote",
                    onRemove = { quoteItems = quoteItems.filterNot { it.id == quote.id } },
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = scheme.surfaceContainerLowest,
                border = BorderStroke(0.5.dp, scheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = InfinityTypography.bodyLarge.copy(
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = scheme.onSurface,
                    ).forInput(),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp, max = 180.dp)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }

            if (subsequentTurns > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceContainer)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (zh) {
                            "此后还有 $subsequentTurns 轮对话，就地重生成将替换这些后续内容；也可新建分支探索，磁盘工作区文件不受影响。"
                        } else {
                            "$subsequentTurns subsequent turns will be replaced if regenerated here. Or branch into a new chat; workspace files on disk remain intact."
                        },
                        style = InfinityTypography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            InfinityPillRow(
                modifier = Modifier.fillMaxWidth(),
                spacing = 8.dp,
                stackedSpacing = 8.dp,
                reverseWhenStacked = true,
            ) {
                InfinityPillButton(
                    text = if (zh) "取消" else "Cancel",
                    onClick = {},
                    tone = InfinityPillTone.NEUTRAL,
                )
                if (subsequentTurns > 0) {
                    InfinityPillButton(
                        text = if (zh) "新建分支并生成" else "Branch & generate",
                        onClick = {},
                        tone = InfinityPillTone.NEUTRAL,
                        icon = Icons.Rounded.ForkRight,
                        enabled = hasAnyPayload,
                    )
                }
                InfinityPillButton(
                    text = when {
                        subsequentTurns > 0 && zh -> "就地重新生成"
                        subsequentTurns > 0 -> "Regenerate here"
                        zh -> "重新生成"
                        else -> "Regenerate"
                    },
                    onClick = {},
                    tone = InfinityPillTone.PRIMARY,
                    icon = Icons.Rounded.Refresh,
                    enabled = hasAnyPayload,
                )
            }
        }
    }
}

@Composable
private fun ProtoRemovableImageChip(
    label: String,
    removeCd: String,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .size(width = 92.dp, height = 64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(scheme.surfaceContainerHighest)
            .border(0.5.dp, scheme.outlineVariant, RoundedCornerShape(12.dp)),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Image,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = label,
                style = InfinityTypography.labelSmall,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(
            onClick = onRemove,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(28.dp)
                .padding(3.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHighest.copy(alpha = 0.9f)),
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = removeCd,
                tint = scheme.onSurface,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ProtoRemovableDocChip(
    doc: ProtoRemovableDoc,
    removeCd: String,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(0.5.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Rounded.Description,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.widthIn(max = 150.dp)) {
                Text(
                    text = doc.name,
                    style = InfinityTypography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = doc.meta,
                    style = InfinityTypography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = removeCd,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun ProtoRemovableQuoteCard(
    quote: ProtoRemovableQuote,
    removeCd: String,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(0.5.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.FormatQuote,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(16.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = quote.excerpt,
                    style = InfinityTypography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = quote.note,
                    style = InfinityTypography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(28.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = removeCd,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Preview(name = "1. 用户消息长按菜单（含编辑入口）深色中文", widthDp = 390, heightDp = 520, locale = "zh-rCN")
@Composable
fun ProtoUserMessageContextMenuDarkPreview() {
    MessageEditPrototypeFrame(InfinityThemeMode.DARK) {
        val scheme = MaterialTheme.colorScheme
        val menuColors = infinityMenuColors()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = scheme.surfaceContainerHigh,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .widthIn(max = maxWidth * 0.82f),
                ) {
                    Text(
                        text = "帮我核对一下 BranchSessionUseCase 里的工具调用配对逻辑，顺便把重发逻辑和桌面端 ADE 对齐一下。",
                        style = InfinityTypography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.5.sp).forReading(),
                        color = scheme.onSurface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = menuColors.container,
                    border = BorderStroke(0.5.dp, menuColors.edge),
                    shadowElevation = 6.dp,
                ) {
                    Column(
                        modifier = Modifier
                            .width(IntrinsicSize.Max)
                            .widthIn(min = 210.dp, max = 280.dp)
                            .padding(6.dp),
                    ) {
                        InfinityMenuItem(
                            text = { Text("14:32", style = InfinityTypography.labelSmall) },
                            onClick = {},
                            enabled = false,
                        )
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(Icons.Rounded.ContentCopy, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            },
                            text = { Text(stringResource(R.string.chat_action_copy), style = InfinityTypography.labelLarge) },
                            onClick = {},
                        )
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(Icons.Rounded.TextFields, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            },
                            text = { Text(stringResource(R.string.chat_action_select_text), style = InfinityTypography.labelLarge) },
                            onClick = {},
                        )
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(Icons.Rounded.Edit, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            },
                            text = {
                                Text(
                                    text = "编辑",
                                    style = InfinityTypography.labelLarge,
                                )
                            },
                            onClick = {},
                        )
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(Icons.Rounded.Refresh, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            },
                            text = { Text(stringResource(R.string.chat_action_resend), style = InfinityTypography.labelLarge) },
                            onClick = {},
                        )
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(Icons.Rounded.ForkRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            },
                            text = { Text(stringResource(R.string.chat_action_branch), style = InfinityTypography.labelLarge) },
                            onClick = {},
                        )
                        InfinityMenuDivider()
                        InfinityMenuItem(
                            leadingIcon = {
                                Icon(Icons.Rounded.DeleteOutline, null, tint = scheme.error, modifier = Modifier.size(20.dp))
                            },
                            text = { Text(stringResource(R.string.chat_action_delete), style = InfinityTypography.labelLarge, color = scheme.error) },
                            destructive = true,
                            onClick = {},
                        )
                    }
                }
            }
        }
    }
}

@Preview(name = "2. 最后一轮用户消息内联编辑 浅色中文", widthDp = 390, heightDp = 620, locale = "zh-rCN")
@Composable
fun ProtoUserMessageEditLastTurnLightPreview() {
    MessageEditPrototypeFrame(InfinityThemeMode.LIGHT) {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "已定位到最后一轮：保存后将直接替换为新回复",
                style = InfinityTypography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            ProtoInlineUserMessageEditorCard(
                zh = true,
                initialText = "请优先保留工作区外部文件的当前磁盘改动，只回退 StateBoard 和历史消息，再重新分析 BranchSessionPlanner 的稳定性判定。",
                subsequentTurns = 0,
                hasImage = true,
                docs = listOf(
                    ProtoRemovableDoc("doc-1", "BranchSessionUseCase.kt", "workspace/native-ui"),
                ),
                quotes = listOf(
                    ProtoRemovableQuote(
                        "q-1",
                        "declaredToolCalls.keys == toolResults.mapNotNullTo(mutableSetOf()) { it.toolCallId }",
                        "需要兼容正文附带尾随工具结果的分组",
                    ),
                ),
            )
        }
    }
}

@Preview(name = "3. 历史中间轮次内联编辑（双路径）深色中文", widthDp = 411, heightDp = 700, locale = "zh-rCN")
@Composable
fun ProtoUserMessageEditHistoricalTurnDarkPreview() {
    MessageEditPrototypeFrame(InfinityThemeMode.DARK) {
        val scheme = MaterialTheme.colorScheme
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProtoInlineUserMessageEditorCard(
                zh = true,
                initialText = "这里先不要合并分支工作流，改成直接基于 Room 事务批量删除 replacedIds 并清理过期摘要。",
                subsequentTurns = 3,
                docs = listOf(
                    ProtoRemovableDoc("doc-2", "DefaultChatGenerationRuntime.kt", "14.2 KB"),
                ),
            )

            HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .alpha(0.45f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "后续已有回复（若选“就地重新生成”将被替换）",
                    style = InfinityTypography.labelSmall,
                    color = scheme.onSurfaceVariant,
                )
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = scheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "好的，我先保留当前分支，并在 MessageManager 中按轮次清理关联摘要与记忆……",
                        style = InfinityTypography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Preview(name = "4. 历史轮次宽屏内屏英文", widthDp = 840, heightDp = 560, locale = "en")
@Composable
fun ProtoUserMessageEditHistoricalWideEnPreview() {
    MessageEditPrototypeFrame(InfinityThemeMode.DARK) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 36.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ProtoInlineUserMessageEditorCard(
                zh = false,
                initialText = "Keep local disk edits untouched when rewinding this turn, preserve Canvas & Artifact snapshots, and regenerate the summary comparison.",
                subsequentTurns = 2,
                hasImage = true,
                docs = listOf(
                    ProtoRemovableDoc("doc-3", "architecture-notes.md", "workspace/docs"),
                ),
                quotes = listOf(
                    ProtoRemovableQuote(
                        "q-2",
                        "Restore conversation only vs restore code and conversation",
                        "Match Claude Code separate rewind semantics",
                    ),
                ),
            )
        }
    }
}
