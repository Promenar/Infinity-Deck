package com.promenar.infinity.ui.chat.quote

import android.os.SystemClock
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.chat.queue.ComposerQuoteDraft
import com.promenar.infinity.data.chat.queue.ComposerQuotes
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.ui.common.InfinityDisclosureRow
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.forReading

/** 会话页提供的引用入口：选字后加入引用卡，点击气泡里的引文定位来源消息。 */
class MessageQuoteActions(
    val onQuote: (sourceMessageId: String, selectedText: String) -> Unit,
    val onLocateSource: (sourceMessageId: String) -> Unit,
)

/** 只在会话页提供；为 null 时选字工具条不出现“引用”，引文也不可点击。 */
val LocalMessageQuoteActions = staticCompositionLocalOf<MessageQuoteActions?> { null }

private object QuoteMenuItemKey

/** 复制动作在菜单收集时取到，点击“引用”时借它拿到选中文字。 */
private class CopyActionHolder {
    var onClick: ((TextContextMenuSession) -> Unit)? = null
}

/**
 * 截获下一次复制的剪贴板：选区复制走 [LocalClipboard]，“引用”先登记截获再触发复制，
 * 选中文字交给引用卡而不写入系统剪贴板。登记两秒内未发生复制即作废，之后的复制照常写入。
 */
internal class QuoteCapturingClipboard(private val delegate: Clipboard) : Clipboard {
    private var pending: ((String) -> Unit)? = null
    private var pendingAt = 0L

    fun captureNext(now: Long = SystemClock.uptimeMillis(), onText: (String) -> Unit) {
        pending = onText
        pendingAt = now
    }

    /** 返回 true 表示这次复制已被引用截获。 */
    internal fun consume(text: CharSequence?, now: Long = SystemClock.uptimeMillis()): Boolean {
        val handler = pending?.takeIf { now - pendingAt <= CaptureWindowMillis } ?: run { pending = null; return false }
        pending = null
        text?.toString()?.let(handler)
        return true
    }

    override suspend fun getClipEntry(): ClipEntry? = delegate.getClipEntry()

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        val text = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text
        if (!consume(text)) delegate.setClipEntry(clipEntry)
    }

    override val nativeClipboard get() = delegate.nativeClipboard

    private companion object {
        const val CaptureWindowMillis = 2_000L
    }
}

/**
 * 可引用的正文选区：系统选字工具条在复制、全选之外多一项“引用”。
 * 只用于助手正文与用户正文；没有引用入口或来源消息时退回普通 [SelectionContainer]。
 */
@Composable
internal fun QuotableSelectionContainer(
    sourceMessageId: String?,
    modifier: Modifier = Modifier,
    onQuoted: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val actions = LocalMessageQuoteActions.current
    if (actions == null || sourceMessageId == null) {
        SelectionContainer(modifier, content)
        return
    }
    val label = stringResource(R.string.ux2_quote_action)
    val systemClipboard = LocalClipboard.current
    val clipboard = remember(systemClipboard) { QuoteCapturingClipboard(systemClipboard) }
    val copyAction = remember { CopyActionHolder() }
    CompositionLocalProvider(LocalClipboard provides clipboard) {
        SelectionContainer(
            modifier = modifier
                // 菜单项在收集时生成，过滤器随后才看到“复制”；点击时复制动作已就位。
                .appendTextContextMenuComponents {
                    item(key = QuoteMenuItemKey, label = label) {
                        val session = this
                        val copy = copyAction.onClick
                        if (copy == null) {
                            session.close()
                        } else {
                            clipboard.captureNext { text ->
                                ComposerQuotes.draft(sourceMessageId, text)?.let {
                                    actions.onQuote(sourceMessageId, it.text)
                                    onQuoted()
                                }
                            }
                            copy(session)
                        }
                    }
                }
                .filterTextContextMenuComponents { component ->
                    if (component.key == TextContextMenuKeys.CopyKey && component is TextContextMenuItem) {
                        copyAction.onClick = component.onClick
                    }
                    true
                },
            content = content,
        )
    }
}

/**
 * 输入区上方的引用卡：引文最多两行，下面是批注框，每张可单独删除。
 * 超过两张时折叠成一行摘要，展开后末尾有“收起引用”；引用区最高约屏高三分之一，超出时区内滚动。
 */
@Composable
internal fun ComposerQuoteArea(
    quotes: List<ComposerQuoteDraft>,
    onAnnotationChange: (id: String, annotation: String) -> Unit,
    onRemove: (id: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (quotes.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    val collapsible = ComposerQuotes.collapsible(quotes)
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp / 3
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))
            .testTag(UiTags.CHAT_QUOTE_AREA),
    ) {
        if (collapsible && !expanded) {
            QuoteCollapsedRow(total = quotes.size, annotated = ComposerQuotes.annotatedCount(quotes), onExpand = { expanded = true })
        } else {
            Column(Modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
                quotes.forEach { quote ->
                    key(quote.id) {
                        ComposerQuoteCard(
                            quote = quote,
                            onAnnotationChange = { onAnnotationChange(quote.id, it) },
                            onRemove = { onRemove(quote.id) },
                        )
                    }
                }
                if (collapsible) {
                    InfinityDisclosureRow(
                        text = stringResource(R.string.ux2_quote_collapse),
                        expanded = true,
                        onClick = { expanded = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .testTag(UiTags.CHAT_QUOTE_COLLAPSE),
                    )
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            thickness = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )
    }
}

/** 折叠后的引用摘要：摘要文字后跟向下箭头，与折叠行同一视觉；“展开”作为点按说明供读屏使用。 */
@Composable
private fun QuoteCollapsedRow(total: Int, annotated: Int, onExpand: () -> Unit) {
    val expandLabel = stringResource(R.string.ux2_quote_expand)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = expandLabel, onClick = onExpand)
            .padding(start = 16.dp, end = 16.dp)
            .testTag(UiTags.CHAT_QUOTE_COLLAPSED),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = stringResource(R.string.ux2_quote_collapsed_summary, total, annotated),
            style = MaterialTheme.typography.labelLarge,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ComposerQuoteCard(
    quote: ComposerQuoteDraft,
    onAnnotationChange: (String) -> Unit,
    onRemove: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val annotationDescription = stringResource(R.string.ux2_quote_annotation_cd)
    val placeholder = stringResource(R.string.ux2_quote_annotation_placeholder)
    val annotationStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface).forInput()
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 8.dp).testTag(UiTags.chatQuoteCard(quote.id)),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).quoteAccentBar(accent).padding(start = 10.dp)) {
            Text(
                text = quote.text,
                style = MaterialTheme.typography.bodySmall.forReading(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            BasicTextField(
                value = quote.annotation,
                onValueChange = onAnnotationChange,
                textStyle = annotationStyle.forInput(),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                maxLines = 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = annotationDescription }
                    .testTag(UiTags.chatQuoteAnnotation(quote.id)),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (quote.annotation.isEmpty()) {
                            Text(placeholder, style = annotationStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        inner()
                    }
                },
            )
        }
        IconButton(onClick = onRemove, modifier = Modifier.size(48.dp).testTag(UiTags.chatQuoteRemove(quote.id))) {
            Icon(Icons.Rounded.Close, stringResource(R.string.ux2_quote_remove_cd), modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 用户气泡上方的引用：引文可点击定位来源消息，批注写在引文下面。 */
@Composable
internal fun UserMessageQuotes(quotes: List<MessageQuote>, maxWidth: Dp, fontSize: Int, modifier: Modifier = Modifier) {
    if (quotes.isEmpty()) return
    val actions = LocalMessageQuoteActions.current
    val accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val jumpDescription = stringResource(R.string.ux2_quote_bubble_jump_cd)
    Column(
        modifier = modifier.widthIn(max = maxWidth).testTag(UiTags.CHAT_USER_QUOTES),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        quotes.forEach { quote ->
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .then(
                                if (actions != null) {
                                    Modifier.clickable(role = Role.Button, onClickLabel = jumpDescription) {
                                        actions.onLocateSource(quote.sourceMessageId)
                                    }
                                } else Modifier,
                            ),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            text = quote.text,
                            style = MaterialTheme.typography.bodySmall.forReading(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.quoteAccentBar(accent).padding(start = 10.dp, top = 6.dp, bottom = 6.dp),
                        )
                    }
                    Text(
                        text = quote.annotation,
                        style = MaterialTheme.typography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5).sp).forReading(),
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
        }
    }
}

/** 引文左侧的细竖条，与 Markdown 引用块的视觉一致。 */
private fun Modifier.quoteAccentBar(color: androidx.compose.ui.graphics.Color): Modifier = drawWithContent {
    drawRect(color = color, size = Size(3.dp.toPx(), size.height))
    drawContent()
}
