package com.promenar.infinity.ui.library

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.domain.library.AskErrorCode
import com.promenar.infinity.domain.library.AskHit
import com.promenar.infinity.domain.library.AskHitKind
import com.promenar.infinity.domain.library.AskMatch
import com.promenar.infinity.domain.library.AskMode
import com.promenar.infinity.domain.library.AskScope
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.chat.copyToClipboard
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading
import java.util.Locale

/*
 * 问资料结果页：输入中列出本机关键词结果；按搜索键后显示“资料里的答案”（或降级说明）与混排的检索结果。
 * 答案里的编号可点，滚到并选中对应结果；点文档结果打开文档页并定位命中段落。
 */

/** 问资料页需要的回调；预览与截图测试直接传入假状态。 */
internal class LibraryAskActions(
    val onSubmit: () -> Unit,
    val onRegenerate: () -> Unit,
    val onFilter: (AskHitKind?) -> Unit,
    val onScope: (AskScope) -> Unit,
    val onSelect: (Int?) -> Unit,
    val onOpenHit: (AskHit) -> Unit,
    val onOpenSettings: () -> Unit,
    val onCopy: (String) -> Unit,
)

@Composable
internal fun LibraryAskContent(host: LibraryPageHost, padding: PaddingValues) {
    val vm = host.askViewModel
    val state by vm.state.collectAsStateWithLifecycle()
    val mode by vm.mode.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val actions = remember(vm, host) {
        LibraryAskActions(
            onSubmit = vm::submit,
            onRegenerate = vm::regenerate,
            onFilter = vm::setFilter,
            onScope = vm::setScope,
            onSelect = vm::selectHit,
            onOpenHit = { hit -> openAskHit(hit, host) },
            onOpenSettings = { host.onNavigateToSecondary(NavDestinations.DEFAULT_MODELS) },
            // 系统自带复制提示，这里不再弹提示。
            onCopy = { text -> copyToClipboard(context, text) },
        )
    }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        if (!host.navigator.twoPane) {
            LibraryAskField(
                query = state.query,
                onQueryChange = host.ask.onQueryChange,
                onSearch = actions.onSubmit,
                modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal, vertical = InfinitySpacing.Small),
                focusOnEnter = host.ask.focusOnEnter,
            )
        }
        LibraryAskResults(state, mode, actions, bottomPadding = padding.calculateBottomPadding(), modifier = Modifier.weight(1f))
    }
}

private fun openAskHit(hit: AskHit, host: LibraryPageHost) {
    when (hit.kind) {
        AskHitKind.DOCUMENT -> hit.documentId?.let { host.navigator.push(LibraryPageKeys.document(it)) }
        AskHitKind.MEMORY -> host.navigator.push(LibraryPageKeys.MEMORY)
        AskHitKind.ENTITY -> host.navigator.push(LibraryPageKeys.GRAPH)
    }
}

@Composable
internal fun LibraryAskResults(
    state: AskUiState,
    mode: AskMode?,
    actions: LibraryAskActions,
    bottomPadding: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    if (state.query.isBlank() && state.phase == AskPhase.TYPING) {
        Box(modifier.fillMaxSize()) {
            Text(
                stringResource(R.string.library_ask_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal + InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
            )
        }
        return
    }
    val listed = state.listedHits
    val shown = listed.withIndex().filter { state.filter == null || it.value.kind == state.filter }
    val listState = rememberLazyListState()
    // 头部条目数：提示或答案卡 1 条，筛选行 1 条。
    val headerCount = 2
    LaunchedEffect(state.selectedHit) {
        val selected = state.selectedHit ?: return@LaunchedEffect
        val position = shown.indexOfFirst { it.index == selected }
        if (position >= 0) listState.animateScrollToItem(headerCount + position, scrollOffset = -48)
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().testTag(LibraryTags.page("ask_results")),
        state = listState,
        contentPadding = PaddingValues(top = InfinitySpacing.XSmall, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        item(key = "head") {
            Box(Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal)) {
                if (state.phase == AskPhase.TYPING) {
                    AskTypingHint(mode)
                } else {
                    AskAnswerCard(state, actions)
                }
            }
        }
        item(key = "filters") { AskFilterRow(state, listed, actions) }
        if (shown.isEmpty() && state.phase != AskPhase.RETRIEVING && (state.phase != AskPhase.TYPING || state.query.isNotBlank())) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.library_ask_no_results),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal + InfinitySpacing.Large, vertical = InfinitySpacing.Small),
                )
            }
        }
        shown.forEach { (index, hit) ->
            item(key = "hit:${hit.key}") {
                AskHitCard(
                    number = index + 1,
                    hit = hit,
                    selected = state.selectedHit == index,
                    onOpen = { actions.onOpenHit(hit) },
                    modifier = Modifier.animateItem().padding(horizontal = InfinitySpacing.ScreenHorizontal),
                )
            }
        }
    }
}

@Composable
private fun AskTypingHint(mode: AskMode?) {
    val text = when (mode) {
        AskMode.NO_QUICK_MODEL -> R.string.library_ask_hint_no_quick
        AskMode.KEYWORD_ONLY -> R.string.library_ask_hint_keyword
        else -> R.string.library_ask_hint_full
    }
    Text(
        stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = InfinitySpacing.XSmall, vertical = InfinitySpacing.XSmall),
    )
}

/** 答案卡：检索中、作答中、完成、失败与两种降级说明共用一张卡。 */
@Composable
private fun AskAnswerCard(state: AskUiState, actions: LibraryAskActions) {
    val scheme = MaterialTheme.colorScheme
    val degraded = state.mode == AskMode.NO_QUICK_MODEL || state.mode == AskMode.KEYWORD_ONLY
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (degraded) scheme.surfaceContainerLow else scheme.secondaryContainer.copy(alpha = 0.55f),
        border = if (degraded) BorderStroke(1.dp, scheme.outlineVariant) else null,
        modifier = Modifier.fillMaxWidth().testTag(LibraryTags.page("ask_answer")),
    ) {
        Column(
            Modifier.animateContentSize(InfinityMotion.contentSizeSpec()).padding(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = InfinitySpacing.Medium, bottom = InfinitySpacing.XSmall),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            when {
                state.mode == AskMode.KEYWORD_ONLY -> DegradedBody(
                    title = stringResource(R.string.library_ask_keyword_title),
                    body = stringResource(R.string.library_ask_keyword_body),
                    action = stringResource(R.string.library_ask_set_embedding),
                    onAction = actions.onOpenSettings,
                )
                state.mode == AskMode.NO_QUICK_MODEL -> DegradedBody(
                    title = stringResource(R.string.library_ask_answer_title),
                    body = stringResource(R.string.library_ask_no_quick_body),
                    action = stringResource(R.string.library_ask_set_quick),
                    onAction = actions.onOpenSettings,
                )
                else -> AnswerBody(state, actions)
            }
        }
    }
}

@Composable
private fun DegradedBody(title: String, body: String, action: String, onAction: () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(body, style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onAction) { Text(action, maxLines = 1) }
    }
}

@Composable
private fun AnswerBody(state: AskUiState, actions: LibraryAskActions) {
    val scheme = MaterialTheme.colorScheme
    // 标题与所用模型分两行：大字号下模型名不被截断。
    Column {
        Text(stringResource(R.string.library_ask_answer_title), style = MaterialTheme.typography.labelLarge, color = scheme.onSecondaryContainer)
        val model = state.modelLabel?.let { stringResource(R.string.library_ask_answer_model, it) } ?: stringResource(R.string.library_ask_answer_model_plain)
        Text(model, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    when (state.phase) {
        AskPhase.RETRIEVING -> {
            Text(stringResource(R.string.library_ask_retrieving), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Medium))
        }
        AskPhase.ANSWERING -> if (state.answer.isEmpty()) {
            Text(stringResource(R.string.library_ask_answering), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Medium))
        } else {
            AnswerText(state.answer + " ▍", state.hits.size, actions.onSelect)
            Spacer(Modifier.size(InfinitySpacing.Small))
        }
        AskPhase.FAILED -> {
            if (state.answer.isNotEmpty()) AnswerText(state.answer, state.hits.size, actions.onSelect)
            Text(askErrorText(state.error), style = MaterialTheme.typography.bodyMedium, color = scheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = actions.onRegenerate) { Text(stringResource(R.string.library_ask_retry), maxLines = 1) }
            }
        }
        AskPhase.DONE, AskPhase.TYPING -> {
            if (state.answer.isNotBlank()) AnswerText(state.answer, state.hits.size, actions.onSelect)
            if (state.noAnswer || state.hits.isEmpty()) {
                Text(stringResource(R.string.library_ask_no_answer_tip), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
            AnswerFooter(state, actions)
        }
    }
}

@Composable
private fun AnswerFooter(state: AskUiState, actions: LibraryAskActions) {
    val scheme = MaterialTheme.colorScheme
    val cited = state.citedHitIndexes.size
    if (cited > 0) {
        Text(pluralStringResource(R.plurals.library_ask_answer_basis, cited, cited), style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        if (state.answer.isNotBlank()) {
            AnswerAction(Icons.Rounded.ContentCopy, stringResource(R.string.library_ask_copy)) { actions.onCopy(stripCitations(state.answer)) }
        }
        AnswerAction(Icons.Rounded.Refresh, stringResource(R.string.library_ask_regenerate), actions.onRegenerate)
    }
}

@Composable
private fun AnswerAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = InfinitySpacing.Small)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(InfinitySpacing.XSmall))
        Text(label, maxLines = 1)
    }
}

/** 答案正文：`[n]` 换成可点的编号；越界编号不显示。 */
@Composable
private fun AnswerText(text: String, hitCount: Int, onSelect: (Int?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val chip = SpanStyle(background = scheme.primary.copy(alpha = 0.16f), color = scheme.primary, fontWeight = FontWeight.SemiBold)
    val annotated = remember(text, hitCount, chip) { citationAnnotated(plainAnswer(text), hitCount, chip, onSelect) }
    Text(annotated, style = MaterialTheme.typography.bodyLarge.forReading(), color = scheme.onSurface)
}

private val CitationPattern = Regex("""\[(\d{1,2})]""")

internal fun citationAnnotated(text: String, hitCount: Int, chip: SpanStyle, onSelect: (Int?) -> Unit): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    CitationPattern.findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        val number = match.groupValues[1].toInt()
        if (number in 1..hitCount) {
            withLink(LinkAnnotation.Clickable("cite:$number", TextLinkStyles(style = chip)) { onSelect(number - 1) }) {
                withStyle(chip) { append(" $number ") }
            }
        }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

/** 复制与朗读用的纯文本：去掉 `[n]` 编号与 Markdown 标记。 */
internal fun stripCitations(text: String): String = plainAnswer(text).replace(Regex("""[ \t]*\[\d{1,2}]"""), "").replace(Regex(" {2,}"), " ").trim()

private val BoldMarker = Regex("""\*\*(.+?)\*\*""")
private val CodeMarker = Regex("""`([^`\n]+)`""")
private val HeadingMarker = Regex("""(?m)^#{1,6}\s+""")

/** 答案按纯文本显示：模型仍写了 Markdown 时去掉粗体、行内代码与标题标记，保留文字。 */
internal fun plainAnswer(text: String): String = text
    .replace(BoldMarker) { it.groupValues[1] }
    .replace(CodeMarker) { it.groupValues[1] }
    .replace(HeadingMarker, "")

@Composable
private fun askErrorText(code: AskErrorCode?): String = stringResource(
    when (code) {
        AskErrorCode.EMBEDDING_FAILED -> R.string.library_ask_failed_embedding
        AskErrorCode.MODEL_FAILED -> R.string.library_ask_failed_model
        AskErrorCode.NETWORK -> R.string.library_ask_failed_network
        else -> R.string.library_ask_failed_unknown
    },
)

@Composable
private fun AskFilterRow(state: AskUiState, listed: List<AskHit>, actions: LibraryAskActions) {
    val counts = listed.groupingBy { it.kind }.eachCount()
    val segments = buildList<InfinitySegment<AskHitKind?>> {
        add(InfinitySegment(null, stringResource(R.string.library_ask_filter_all, listed.size)))
        counts[AskHitKind.DOCUMENT]?.let { add(InfinitySegment(AskHitKind.DOCUMENT, stringResource(R.string.library_ask_filter_documents, it))) }
        counts[AskHitKind.MEMORY]?.let { add(InfinitySegment(AskHitKind.MEMORY, stringResource(R.string.library_ask_filter_memories, it))) }
        counts[AskHitKind.ENTITY]?.let { add(InfinitySegment(AskHitKind.ENTITY, stringResource(R.string.library_ask_filter_graph, it))) }
    }
    Column {
        if (listed.isNotEmpty() && segments.size > 2) {
            InfinitySegmentedControl(
                segments = segments,
                selected = state.filter,
                onSelect = actions.onFilter,
                compact = true,
                contentPadding = PaddingValues(horizontal = InfinitySpacing.ScreenHorizontal),
            )
        }
        AskScopeButton(state.scope, actions.onScope)
    }
}

@Composable
private fun AskScopeButton(scope: AskScope, onScope: (AskScope) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = scopeLabel(scope)
    Box(Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal - InfinitySpacing.Small)) {
        TextButton(onClick = { open = true }) {
            Text(stringResource(R.string.library_ask_scope, label), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
        }
        InfinityDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            listOf(AskScope.AllIndexed, AskScope.Library).forEach { option ->
                InfinityMenuItem(
                    text = { Text(scopeLabel(option)) },
                    onClick = {
                        open = false
                        onScope(option)
                    },
                    selected = option == scope,
                )
            }
        }
    }
}

@Composable
private fun scopeLabel(scope: AskScope): String = when (scope) {
    AskScope.AllIndexed -> stringResource(R.string.library_ask_scope_all)
    AskScope.Library -> stringResource(R.string.library_ask_scope_library)
    is AskScope.Workspace -> scope.title
}

@Composable
internal fun AskHitCard(number: Int, hit: AskHit, selected: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val kindLabel = stringResource(
        when (hit.kind) {
            AskHitKind.DOCUMENT -> R.string.library_ask_kind_document
            AskHitKind.MEMORY -> R.string.library_ask_kind_memory
            AskHitKind.ENTITY -> R.string.library_ask_kind_entity
        },
    )
    Surface(
        shape = MaterialTheme.shapes.large,
        color = if (selected) scheme.secondaryContainer.copy(alpha = 0.55f) else scheme.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .clickable(role = Role.Button, onClick = onOpen)
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                Surface(shape = CircleShape, color = scheme.primary.copy(alpha = 0.14f), modifier = Modifier.size(22.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(number.toString(), style = MaterialTheme.typography.labelSmall, color = scheme.primary, fontWeight = FontWeight.SemiBold)
                    }
                }
                Icon(kindIcon(hit.kind), contentDescription = kindLabel, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                if (hit.kind == AskHitKind.DOCUMENT) {
                    InfinityIdentifierText(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                } else {
                    Text(hit.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                MatchTag(hit)
                Text(hit.location, style = MaterialTheme.typography.labelSmall, color = scheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            val highlight = SpanStyle(background = scheme.tertiaryContainer, color = scheme.onTertiaryContainer)
            val snippet = remember(hit.snippet, hit.highlightTerms, highlight) { highlightTerms(hit.snippet, hit.highlightTerms, highlight) }
            Text(snippet, style = MaterialTheme.typography.bodyMedium.forReading(), color = scheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun kindIcon(kind: AskHitKind): ImageVector = when (kind) {
    AskHitKind.DOCUMENT -> Icons.Rounded.Description
    AskHitKind.MEMORY -> Icons.Rounded.Psychology
    AskHitKind.ENTITY -> Icons.Rounded.Hub
}

@Composable
private fun MatchTag(hit: AskHit) {
    val scheme = MaterialTheme.colorScheme
    val score = hit.similarity?.let { String.format(Locale.ROOT, "%.2f", it) }
    val (label, container, content) = when (hit.match) {
        AskMatch.BOTH -> Triple(stringResource(R.string.library_ask_match_both), scheme.primary.copy(alpha = 0.14f), scheme.primary)
        AskMatch.SEMANTIC -> Triple(
            score?.let { stringResource(R.string.library_ask_match_semantic, it) } ?: stringResource(R.string.library_ask_match_semantic, "").trim(),
            scheme.tertiary.copy(alpha = 0.14f),
            scheme.tertiary,
        )
        AskMatch.KEYWORD -> Triple(stringResource(R.string.library_ask_match_keyword), scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
    Surface(shape = MaterialTheme.shapes.small, color = container) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = content, maxLines = 1, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** 摘录高亮：命中词逐一加底色，不区分大小写。 */
internal fun highlightTerms(text: String, terms: List<String>, style: SpanStyle): AnnotatedString = buildAnnotatedString {
    append(text)
    terms.filter { it.isNotBlank() }.forEach { term ->
        var from = 0
        while (true) {
            val at = text.indexOf(term, from, ignoreCase = true)
            if (at < 0) break
            addStyle(style, at, at + term.length)
            from = at + term.length
        }
    }
}
