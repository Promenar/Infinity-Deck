package com.promenar.infinity.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

private val FooterCardShape = RoundedCornerShape(14.dp)
private val EmbeddedNoteCardShape = RoundedCornerShape(12.dp)
private val FooterCardMaxWidth = 560.dp

enum class InfinityNoteTone {
    DEFAULT,
    PRIMARY,
    WARNING,
}

data class InfinityEmbeddedNoteRow(
    val icon: ImageVector,
    val text: String,
    val tone: InfinityNoteTone = InfinityNoteTone.DEFAULT,
)

/**
 * 分组与引导页说明文本的统一轻量微衬底容器，取代直接裸露在页面背景上的长说明。
 */
@Composable
fun InfinitySectionFooterCard(
    text: String,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Info,
    maxLines: Int = Int.MAX_VALUE,
    selectable: Boolean = true,
) {
    if (text.isBlank()) return
    InfinitySectionFooterShell(modifier = modifier, icon = icon) { color ->
        val content: @Composable () -> Unit = {
            Text(
                text = text.keepCjkTailTogether(),
                style = MaterialTheme.typography.bodySmall.forReading(),
                color = color,
                maxLines = maxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = textModifier,
            )
        }
        if (selectable) SelectionContainer(content = content) else content()
    }
}

/**
 * 含可点击链接的分组说明文本容器，保持长按选择关闭，避免抢走链接点击。
 */
@Composable
fun InfinitySectionFooterCard(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    textModifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Info,
    maxLines: Int = Int.MAX_VALUE,
) {
    if (text.isEmpty()) return
    InfinitySectionFooterShell(modifier = modifier, icon = icon) { color ->
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = color,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            modifier = textModifier,
        )
    }
}

@Composable
private fun InfinitySectionFooterShell(
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.Info,
    content: @Composable (Color) -> Unit,
) {
    val contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = FooterCardMaxWidth),
        shape = FooterCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.68f),
        border = BorderStroke(
            width = 0.5.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(15.dp),
                tint = contentColor.copy(alpha = 0.85f),
            )
            Column(modifier = Modifier.weight(1f)) {
                content(contentColor)
            }
        }
    }
}

/**
 * 设置分组内部的结构化键值状态摘要容器，每条状态作为独立短行呈现，避免长段散文折行产生大块留白。
 */
@Composable
fun InfinityEmbeddedNoteCard(
    rows: List<InfinityEmbeddedNoteRow>,
    modifier: Modifier = Modifier,
) {
    val visible = rows.filter { it.text.isNotBlank() }
    if (visible.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    Surface(
        shape = EmbeddedNoteCardShape,
        color = scheme.surfaceContainerHigh.copy(alpha = 0.72f),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            visible.forEach { row ->
                val tint = when (row.tone) {
                    InfinityNoteTone.DEFAULT -> scheme.onSurfaceVariant
                    InfinityNoteTone.PRIMARY -> scheme.onSurface
                    InfinityNoteTone.WARNING -> domain.warning
                }
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = row.icon,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .size(15.dp),
                        tint = if (row.tone == InfinityNoteTone.PRIMARY) scheme.primary else tint,
                    )
                    Text(
                        text = row.text.keepCjkTailTogether(),
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = tint,
                    )
                }
            }
        }
    }
}
