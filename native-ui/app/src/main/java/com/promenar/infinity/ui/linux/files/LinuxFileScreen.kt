package com.promenar.infinity.ui.linux.files

import com.promenar.infinity.ui.common.InfinityPanelTabs
import com.promenar.infinity.ui.common.InfinityPanelTab
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxFileEntry
import com.promenar.infinity.domain.linux.LinuxFileType
import com.promenar.infinity.ui.common.FolderStatusMessage
import com.promenar.infinity.ui.common.InfinityBackButton
import com.promenar.infinity.ui.common.LocalInfinitySettingsPaneRoot
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.UnifiedPromptEditor
import com.promenar.infinity.ui.common.bettboxListGroup
import com.promenar.infinity.ui.renderer.infinitySyntaxTheme
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.InfinityMetaLine

/** 超过此长度的文本只为开头部分上色，其余保持纯文本，避免大文件卡顿。 */
private const val HIGHLIGHT_MAX_CHARS = 64 * 1024

/**
 * Linux 文件详情页。
 *
 * @param path 容器内绝对路径，例如 `/root/projects/run.sh`。
 * @param onBack 返回上一页。
 * @param onOpenTerminal 打开本地终端：`directory` 为文件所在目录，`command` 为“在终端运行”时要执行的命令。
 */
@Composable
fun LinuxFileScreen(
    path: String,
    onBack: () -> Unit,
    onOpenTerminal: (directory: String, command: String?) -> Unit,
) {
    val linux = (LocalContext.current.applicationContext as InfinityApplication).linux
    val viewModel: LinuxFileViewModel = viewModel(key = "linux_file:$path", factory = LinuxFileViewModel.factory(linux.files, path))
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) { viewModel.onShown() }
    LinuxFileContent(
        state = state,
        actions = LinuxFileActions(
            onBack = onBack,
            onSelectTab = viewModel::selectTab,
            onReload = viewModel::reload,
            onHexPage = viewModel::loadHexPage,
            onEdit = viewModel::startEditing,
            onRun = {
                val directory = linuxParentPath(state.path) ?: "/"
                onOpenTerminal(directory, "./" + shellQuote(state.name))
            },
            onChmod = viewModel::requestChmod,
        ),
    )
    val text = state.text
    UnifiedPromptEditor(
        show = state.editing && text != null,
        onDismiss = viewModel::stopEditing,
        onSave = { edited -> viewModel.save(edited) },
        initialText = text?.text.orEmpty(),
        title = state.name,
        placeholder = stringResource(R.string.linux_file_editor_placeholder),
        showPreviewTab = text?.kind == LinuxFileKind.MARKDOWN,
    )
    if (state.conflict != null) {
        InfinityConfirmDialog(
            onDismissRequest = viewModel::dismissConflict,
            title = stringResource(R.string.linux_file_conflict_title),
            message = stringResource(R.string.linux_file_conflict_message),
            confirmText = stringResource(R.string.linux_file_conflict_reload),
            cancelText = stringResource(R.string.linux_file_conflict_keep_editing),
            onConfirm = viewModel::reloadAfterConflict,
            onCancel = viewModel::dismissConflict,
            confirmButtonModifier = Modifier.testTag(UiTags.LINUX_FILE_CONFLICT_RELOAD),
        ) {
            TextButton(
                onClick = viewModel::overwriteAfterConflict,
                modifier = Modifier
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .testTag(UiTags.LINUX_FILE_CONFLICT_OVERWRITE),
            ) { Text(stringResource(R.string.linux_file_conflict_overwrite), color = MaterialTheme.colorScheme.error) }
        }
    }
    val entry = state.entry
    if (state.chmodOpen && entry != null) {
        LinuxChmodDialog(entry = entry, error = state.chmodError, onConfirm = viewModel::confirmChmod, onDismiss = viewModel::dismissChmod)
    }
}

internal data class LinuxFileActions(
    val onBack: () -> Unit = {},
    val onSelectTab: (LinuxFileTab) -> Unit = {},
    val onReload: () -> Unit = {},
    val onHexPage: (Int) -> Unit = {},
    val onEdit: () -> Unit = {},
    val onRun: () -> Unit = {},
    val onChmod: () -> Unit = {},
)

@Composable
internal fun LinuxFileContent(state: LinuxFileUiState, actions: LinuxFileActions, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            LinuxFileTopBar(state, actions)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = InfinitySpacing.Large),
            ) {
                LinuxFilePathLine(state.path)
                InfinityPanelTabs(
                    tabs = listOf(
                        InfinityPanelTab(LinuxFileTab.CONTENT, stringResource(R.string.linux_file_tab_content), testTag = "${UiTags.LINUX_FILE_TABS}:content"),
                        InfinityPanelTab(LinuxFileTab.HEX, stringResource(R.string.linux_file_tab_hex), testTag = "${UiTags.LINUX_FILE_TABS}:hex"),
                        InfinityPanelTab(LinuxFileTab.PROPERTIES, stringResource(R.string.linux_file_tab_properties), testTag = "${UiTags.LINUX_FILE_TABS}:properties"),
                    ),
                    selected = state.tab,
                    onSelect = actions.onSelectTab,
                    contentInset = InfinitySpacing.Large,
                    bleed = InfinitySpacing.Large,
                    modifier = Modifier
                        .padding(bottom = InfinitySpacing.Small)
                        .testTag(UiTags.LINUX_FILE_TABS),
                )
                AnimatedVisibility(visible = state.loading, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.XSmall))
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val error = state.error
                    if (error != null) {
                        FolderStatusMessage(text = stringResource(R.string.linux_file_load_failed, linuxErrorText(error)), error = true) {
                            InfinityRetryButton(onClick = actions.onReload)
                        }
                    } else if (state.entry != null) {
                        AnimatedContent(
                            targetState = state.tab,
                            transitionSpec = { InfinityMotion.sharedAxisX(forward = targetState.ordinal > initialState.ordinal) },
                            label = "linux_file_tab",
                        ) { tab ->
                            when (tab) {
                                LinuxFileTab.CONTENT -> LinuxContentTab(state, actions)
                                LinuxFileTab.HEX -> LinuxHexTab(state, actions)
                                LinuxFileTab.PROPERTIES -> LinuxPropertiesTab(state)
                            }
                        }
                    }
                }
                val entry = state.entry
                if (entry != null && entry.type != LinuxFileType.DIRECTORY) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                        modifier = Modifier.fillMaxWidth().padding(vertical = InfinitySpacing.Medium),
                    ) {
                        if (state.canRun) {
                            FilledTonalButton(
                                onClick = actions.onRun,
                                modifier = Modifier.weight(1f).heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(UiTags.LINUX_FILE_RUN),
                            ) { Text(stringResource(R.string.linux_file_run), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                        Button(
                            onClick = actions.onEdit,
                            enabled = state.canEdit,
                            modifier = Modifier.weight(1f).heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(UiTags.LINUX_FILE_EDIT),
                        ) { Text(stringResource(R.string.linux_file_edit), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/**
 * 与设置子页同一顶栏：返回 · 文件名 · 更多。文件名单行，放不下时省略中间、保留扩展名；
 * 完整路径在顶栏下方的 [LinuxFilePathLine]。右列根层不画返回（本页不会是根层，保持与其它设置页一致的判断）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinuxFileTopBar(state: LinuxFileUiState, actions: LinuxFileActions) {
    val scheme = MaterialTheme.colorScheme
    TopAppBar(
        title = {
            Text(
                text = state.name,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier.fillMaxWidth().semantics { heading() }.testTag(UiTags.LINUX_FILE_TITLE),
            )
        },
        navigationIcon = {
            if (!LocalInfinitySettingsPaneRoot.current) InfinityBackButton(onClick = actions.onBack)
        },
        actions = { LinuxFileMoreMenu(state, actions) },
        // 宿主页面已处理系统栏内边距。
        windowInsets = WindowInsets(0),
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Transparent,
            scrolledContainerColor = Color.Transparent,
            titleContentColor = scheme.onBackground,
            navigationIconContentColor = scheme.onSurfaceVariant,
            actionIconContentColor = scheme.onSurfaceVariant,
        ),
    )
}

/** 文件的完整路径：等宽小字，自动换行，可选中复制。 */
@Composable
private fun LinuxFilePathLine(path: String) {
    SelectionContainer {
        Text(
            text = path,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = InfinitySpacing.Small)
                .testTag(UiTags.LINUX_FILE_PATH),
        )
    }
}

@Composable
private fun LinuxFileMoreMenu(state: LinuxFileUiState, actions: LinuxFileActions) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.testTag(UiTags.LINUX_FILE_MORE)) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.linux_files_more))
        }
        InfinityDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            InfinityMenuItem(text = { Text(stringResource(R.string.linux_file_reload)) }, onClick = { expanded = false; actions.onReload() })
            InfinityMenuItem(
                text = { Text(stringResource(R.string.linux_files_chmod)) },
                enabled = state.entry?.type.let { it != null && it != LinuxFileType.SYMLINK },
                onClick = { expanded = false; actions.onChmod() },
            )
        }
    }
}

// ── 内容 ──

@Composable
private fun LinuxContentTab(state: LinuxFileUiState, actions: LinuxFileActions) {
    val entry = state.entry ?: return
    val text = state.text
    Column(Modifier.fillMaxSize()) {
        LinuxContentSummary(entry, text, state.nowMs)
        if (text?.truncated == true) {
            Text(
                stringResource(R.string.linux_file_truncated, formatLinuxSize(text.shownBytes.toLong()), formatLinuxSize(text.totalSize)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.padding(bottom = InfinitySpacing.Small),
            )
        }
        val textError = state.textError
        when {
            entry.type == LinuxFileType.DIRECTORY -> Unit
            textError != null -> FolderStatusMessage(text = stringResource(R.string.linux_file_load_failed, linuxErrorText(textError)), error = true) {
                InfinityRetryButton(onClick = actions.onReload)
            }
            text == null -> Unit
            text.isBinary -> FolderStatusMessage(text = stringResource(R.string.linux_file_binary), icon = Icons.Rounded.Memory)
            text.text.isEmpty() -> FolderStatusMessage(text = stringResource(R.string.linux_file_empty), icon = Icons.AutoMirrored.Rounded.InsertDriveFile)
            else -> LinuxCodeView(text, Modifier.weight(1f))
        }
    }
}

/** 内容页顶部的摘要：类型、编码、换行、大小与行数；权限、属主属组与修改时间。 */
@Composable
private fun LinuxContentSummary(entry: LinuxFileEntry, text: LinuxTextPreview?, nowMs: Long) {
    val first = buildList {
        addAll(linuxTypeLabels(entry, text))
        text?.encoding?.let(::add)
        text?.lineEnding?.let { add(linuxLineEndingLabel(it)) }
        add(formatLinuxSize(entry.size))
        text?.lineCount?.takeIf { !text.isBinary }?.let { add(pluralStringResource(R.plurals.linux_file_line_count, it, it)) }
    }
    val second = listOf(
        "${formatLinuxPermissions(entry.type, entry.mode)} (${formatLinuxOctalMode(entry.mode)})",
        "${entry.owner}:${entry.group}",
        linuxTimestampText(linuxTimestamp(entry.modifiedMs, nowMs)),
    )
    Column(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small)) {
        InfinityMetaLine(first, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        InfinityMetaLine(second, style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun linuxTypeLabels(entry: LinuxFileEntry, text: LinuxTextPreview?): List<String> = when (entry.type) {
    LinuxFileType.DIRECTORY -> listOf(stringResource(R.string.linux_file_directory))
    LinuxFileType.OTHER -> listOf(stringResource(R.string.linux_file_special))
    else -> {
        val kind = stringResource(linuxFileKindLabel(text?.kind ?: linuxKindByName(entry.name) ?: LinuxFileKind.TEXT))
        if (entry.type == LinuxFileType.SYMLINK) listOf(stringResource(R.string.linux_file_link), kind) else listOf(kind)
    }
}

/** 只读代码视图：行号列 + 自动换行的正文，按语言上色。 */
@Composable
private fun LinuxCodeView(text: LinuxTextPreview, modifier: Modifier) {
    val content = text.text
    val highlighted = rememberLinuxHighlight(content, text.kind)
    val lineStarts = remember(content) {
        buildList {
            add(0)
            content.forEachIndexed { index, c -> if (c == '\n' && index + 1 < content.length) add(index + 1) }
        }
    }
    val digits = lineStarts.size.toString().length
    val codeStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace)
    val numberColor = MaterialTheme.colorScheme.outline
    SelectionContainer(modifier.fillMaxWidth()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .clip12()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .testTag(UiTags.LINUX_FILE_CONTENT),
            contentPadding = PaddingValues(vertical = InfinitySpacing.Medium, horizontal = InfinitySpacing.Small),
        ) {
            items(lineStarts.size) { line ->
                val start = lineStarts[line]
                val end = (lineStarts.getOrNull(line + 1)?.minus(1) ?: content.length).let { stop ->
                    if (stop > start && content[stop - 1] == '\r') stop - 1 else stop
                }
                val lineText = remember(highlighted, start, end) {
                    if (highlighted != null && end <= highlighted.length) highlighted.subSequence(start, end) else AnnotatedString(content.substring(start, end))
                }
                Row {
                    Text(
                        (line + 1).toString().padStart(digits),
                        style = codeStyle,
                        color = numberColor,
                        modifier = Modifier.padding(end = InfinitySpacing.Small),
                    )
                    Text(lineText, style = codeStyle, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun Modifier.clip12() = clip(RoundedCornerShape(12.dp))

/** 预览与截图中同步上色；运行时在 Default 线程计算，完成前先显示纯文本。 */
@Composable
private fun rememberLinuxHighlight(content: String, kind: LinuxFileKind): AnnotatedString? {
    val syntax = kind.syntaxName ?: return null
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val compute = { highlightLinuxCode(content.take(HIGHLIGHT_MAX_CHARS), syntax, isDark) }
    if (LocalInspectionMode.current) return remember(content, syntax, isDark) { compute() }
    val result by produceState<AnnotatedString?>(null, content, syntax, isDark) {
        value = withContext(Dispatchers.Default) { compute() }
    }
    return result
}

private fun highlightLinuxCode(code: String, syntax: String, isDark: Boolean): AnnotatedString {
    val builder = dev.snipme.highlights.Highlights.Builder().theme(infinitySyntaxTheme(isDark)).code(code)
    dev.snipme.highlights.model.SyntaxLanguage.getByName(syntax)?.let(builder::language)
    val highlights = builder.build().getHighlights()
    return buildAnnotatedString {
        append(code)
        highlights.forEach { highlight ->
            val span = when (highlight) {
                is dev.snipme.highlights.model.ColorHighlight -> SpanStyle(color = Color(highlight.rgb).copy(alpha = 1f))
                is dev.snipme.highlights.model.BoldHighlight -> SpanStyle(fontWeight = FontWeight.Bold)
            }
            val start = highlight.location.start.coerceIn(0, code.length)
            val end = highlight.location.end.coerceIn(start, code.length)
            if (end > start) addStyle(span, start, end)
        }
    }
}

// ── 十六进制 ──

@Composable
private fun LinuxHexTab(state: LinuxFileUiState, actions: LinuxFileActions) {
    val hex = state.hex
    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(visible = state.hexLoading, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.XSmall))
        }
        val hexError = state.hexError
        when {
            hexError != null -> FolderStatusMessage(text = stringResource(R.string.linux_file_load_failed, linuxErrorText(hexError)), error = true) {
                InfinityRetryButton(onClick = { actions.onHexPage(hex?.page ?: 0) })
            }
            hex == null -> Box(Modifier.weight(1f))
            hex.bytes.isEmpty() -> FolderStatusMessage(text = stringResource(R.string.linux_file_empty), icon = Icons.AutoMirrored.Rounded.InsertDriveFile)
            else -> LinuxHexRowsView(hex, Modifier.weight(1f))
        }
        if (state.hexPageCount > 1) LinuxHexPager(page = hex?.page ?: 0, pageCount = state.hexPageCount, busy = state.hexLoading, onPage = actions.onHexPage)
    }
}

@Composable
private fun LinuxHexRowsView(hex: LinuxHexPage, modifier: Modifier) {
    val style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip12()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        val horizontalPadding = InfinitySpacing.Medium
        val charWidthPx = remember(style) { measurer.measure("0", style).size.width.coerceAtLeast(1) }
        val availablePx = with(LocalDensity.current) { (maxWidth - horizontalPadding * 2).toPx() }
        val bytesPerRow = linuxHexBytesPerRow((availablePx / charWidthPx).toInt())
        val rows = remember(hex, bytesPerRow) { linuxHexRows(hex.bytes, hex.offset, bytesPerRow) }
        SelectionContainer {
            LazyColumn(
                modifier = Modifier.fillMaxSize().testTag(UiTags.LINUX_FILE_HEX),
                contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = InfinitySpacing.Medium),
            ) {
                items(rows, key = { it.offset }) { row ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.outline)) { append(row.offset) }
                            append("  ")
                            append(row.hex)
                            append("  ")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.tertiary)) { append(row.ascii) }
                        },
                        style = style,
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun LinuxHexPager(page: Int, pageCount: Int, busy: Boolean, onPage: (Int) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small),
    ) {
        IconButton(onClick = { onPage(page - 1) }, enabled = !busy && page > 0) {
            Icon(Icons.Rounded.ChevronLeft, contentDescription = stringResource(R.string.linux_file_hex_previous))
        }
        Text(
            stringResource(R.string.linux_file_hex_page, page + 1, pageCount),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .padding(horizontal = InfinitySpacing.Medium)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
        IconButton(onClick = { onPage(page + 1) }, enabled = !busy && page < pageCount - 1) {
            Icon(Icons.Rounded.ChevronRight, contentDescription = stringResource(R.string.linux_file_hex_next))
        }
    }
}

// ── 属性 ──

@Composable
private fun LinuxPropertiesTab(state: LinuxFileUiState) {
    val entry = state.entry ?: return
    val text = state.text
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag(UiTags.LINUX_FILE_PROPERTIES),
    ) {
        Column(Modifier.fillMaxWidth().bettboxListGroup().padding(vertical = 6.dp)) {
            PropertyRow(stringResource(R.string.linux_file_prop_path), entry.path, mono = true)
            PropertyRow(stringResource(R.string.linux_file_prop_type), linuxTypeLabels(entry, text))
            entry.symlinkTarget?.let { PropertyRow(stringResource(R.string.linux_file_prop_link), it, mono = true) }
            if (text != null && !text.isBinary) {
                text.encoding?.let { PropertyRow(stringResource(R.string.linux_file_prop_encoding), it) }
                text.lineEnding?.let { PropertyRow(stringResource(R.string.linux_file_prop_line_ending), linuxLineEndingLabel(it)) }
            }
            val size = buildList {
                add(formatLinuxSize(entry.size))
                if (entry.size >= 1024) add("${entry.size} B")
                text?.lineCount?.takeIf { !text.isBinary }?.let { add(pluralStringResource(R.plurals.linux_file_line_count, it, it)) }
            }
            PropertyRow(stringResource(R.string.linux_file_prop_size), size)
            PropertyRow(
                stringResource(R.string.linux_file_prop_permissions),
                "${formatLinuxPermissions(entry.type, entry.mode)} (${formatLinuxOctalMode(entry.mode)})",
                mono = true,
            )
            PropertyRow(stringResource(R.string.linux_file_prop_owner), "${entry.owner}:${entry.group}", mono = true)
            PropertyRow(stringResource(R.string.linux_file_prop_modified), linuxTimestampText(linuxTimestamp(entry.modifiedMs, state.nowMs)))
        }
    }
}

@Composable
private fun PropertyRow(label: String, value: String, mono: Boolean = false) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = InfinitySpacing.Large, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = if (mono) MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace) else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 多段取值（如“大小 · 字节数 · 行数”）：整段换行，靠右对齐。 */
@Composable
private fun PropertyRow(label: String, segments: List<String>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = InfinitySpacing.Large, vertical = 9.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Box(Modifier.weight(1f), contentAlignment = Alignment.TopEnd) {
            InfinityMetaLine(segments, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
