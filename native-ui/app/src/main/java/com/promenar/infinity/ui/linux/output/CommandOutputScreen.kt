package com.promenar.infinity.ui.linux.output

import com.promenar.infinity.ui.theme.InfinityMonospace
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityPageLayout
import com.promenar.infinity.ui.common.InfinityRetryButton
import com.promenar.infinity.ui.common.InfinitySearchBar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.InfinitySnackbarHost
import com.promenar.infinity.ui.common.InfinityTabSwitcher
import com.promenar.infinity.ui.common.SnackbarType
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.text.NumberFormat

/**
 * 命令完整输出查看器。
 *
 * @param source 会话、助手消息与工具调用 id，定位一次命令类工具调用。
 * @param onRerunInTerminal 用户点“在终端重跑”时回调完整命令与会话 id，由导航层打开本地终端并自动输入。
 */
@Composable
fun CommandOutputScreen(
    source: CommandOutputSource,
    onBack: () -> Unit,
    onRerunInTerminal: (command: String, sessionId: String?) -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as InfinityApplication
    val viewModel: CommandOutputViewModel = viewModel(
        key = "command-output-${source.sessionId}-${source.messageId}-${source.toolCallId}",
        factory = CommandOutputViewModel.factory(app, source),
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    var snackbar by remember { mutableStateOf<InfinitySnackbarData?>(null) }
    val copiedText = stringResource(R.string.linux_output_copied)
    val failedText = stringResource(R.string.linux_output_save_failed)
    val unsupportedText = stringResource(R.string.linux_output_save_unsupported)
    val savedTemplate = stringResource(R.string.linux_output_saved)
    val shareTitle = stringResource(R.string.linux_output_share_title)

    suspend fun show(message: String, type: SnackbarType) {
        snackbar = InfinitySnackbarData(message, type)
        snackbarHost.currentSnackbarData?.dismiss()
        snackbarHost.showSnackbar(message)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is CommandOutputEvent.Saved -> show(String.format(savedTemplate, event.path), SnackbarType.SUCCESS)
                is CommandOutputEvent.SaveUnsupported -> {
                    shareText(context, event.text, shareTitle)
                    show(unsupportedText, SnackbarType.INFO)
                }
                CommandOutputEvent.SaveFailed -> show(failedText, SnackbarType.ERROR)
            }
        }
    }
    var copyRequest by remember { mutableIntStateOf(0) }
    LaunchedEffect(copyRequest) {
        if (copyRequest > 0) show(copiedText, SnackbarType.SUCCESS)
    }

    Box(Modifier.fillMaxSize()) {
        CommandOutputContent(
            state = state,
            onBack = onBack,
            onSegmentSelected = viewModel::selectSegment,
            onQueryChange = viewModel::setQuery,
            onPreviousMatch = viewModel::previousMatch,
            onNextMatch = viewModel::nextMatch,
            onCopy = {
                copyText(context, viewModel.currentText())
                copyRequest++
            },
            onShare = { shareText(context, viewModel.currentText(), shareTitle) },
            onSave = viewModel::saveToWorkspace,
            onRerun = { command -> onRerunInTerminal(command, source.sessionId) },
            onRetry = viewModel::reload,
        )
        InfinitySnackbarHost(
            hostState = snackbarHost,
            snackbarData = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp),
        )
    }
}

/** 无状态界面，供截图预览直接传入状态。 */
@Composable
fun CommandOutputContent(
    state: CommandOutputUiState,
    onBack: () -> Unit,
    onSegmentSelected: (OutputSegment) -> Unit,
    onQueryChange: (String) -> Unit,
    onPreviousMatch: () -> Unit,
    onNextMatch: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onRerun: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val loaded = state.phase == CommandOutputPhase.LOADED && state.output != null
    InfinityPageLayout(
        title = stringResource(R.string.linux_output_title),
        onBack = onBack,
        scrollable = false,
        modifier = Modifier.testTag(UiTags.LINUX_OUTPUT_SCREEN),
        actions = {
            if (loaded) {
                IconButton(onClick = onShare, modifier = Modifier.testTag(UiTags.LINUX_OUTPUT_SHARE)) {
                    Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.linux_output_share))
                }
            }
        },
    ) {
        val output = state.output
        if (state.phase != CommandOutputPhase.LOADED || output == null) {
            OutputPlaceholder(state.phase, onRetry)
            return@InfinityPageLayout
        }
        Column(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Text(
                text = output.commandLine ?: stringResource(R.string.linux_output_command_unknown),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = InfinityMonospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            OutputTags(output)
            if (state.segmentsAvailable) {
                val segments = OutputSegment.entries
                InfinityTabSwitcher(
                    itemCount = segments.size,
                    selectedIndex = segments.indexOf(state.segment),
                    onTabSelected = { onSegmentSelected(segments[it]) },
                    tabModifier = { Modifier.testTag(UiTags.linuxOutputSegment(segments[it].name.lowercase())) },
                ) { index, _, color ->
                    Text(stringResource(segmentLabel(segments[index])), color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
            SearchRow(state.search, onQueryChange, onPreviousMatch, onNextMatch)
            TruncationNotice(state, output)
            OutputText(state, Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                OutputAction(R.string.linux_output_copy, Icons.Rounded.ContentCopy, UiTags.LINUX_OUTPUT_COPY, enabled = true, onClick = onCopy)
                OutputAction(R.string.linux_output_save, Icons.Rounded.Save, UiTags.LINUX_OUTPUT_SAVE, enabled = !state.saving, onClick = onSave)
                val rerun = output.rerunCommand
                OutputAction(R.string.linux_output_rerun, Icons.Rounded.Terminal, UiTags.LINUX_OUTPUT_RERUN, enabled = rerun != null) {
                    rerun?.let(onRerun)
                }
            }
        }
    }
}

@Composable
private fun OutputPlaceholder(phase: CommandOutputPhase, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = InfinitySpacing.XXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        if (phase == CommandOutputPhase.LOADING) CircularProgressIndicator(modifier = Modifier.size(InfinitySpacing.XLarge))
        Text(
            text = stringResource(
                when (phase) {
                    CommandOutputPhase.LOADING, CommandOutputPhase.LOADED -> R.string.linux_output_loading
                    CommandOutputPhase.PENDING -> R.string.linux_output_pending
                    CommandOutputPhase.NOT_FOUND -> R.string.linux_output_not_found
                    CommandOutputPhase.FAILED -> R.string.linux_output_failed
                },
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (phase == CommandOutputPhase.FAILED || phase == CommandOutputPhase.PENDING) {
            InfinityRetryButton(onClick = onRetry, text = stringResource(R.string.linux_output_retry))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OutputTags(output: CommandOutput) {
    val colors = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall + 2.dp),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.XSmall + 2.dp),
    ) {
        when {
            output.timedOut -> OutputTag(stringResource(R.string.linux_output_tag_timeout), colors.errorContainer, colors.onErrorContainer)
            output.errorCode == STOPPED -> OutputTag(stringResource(R.string.linux_output_tag_stopped), domain.warningContainer, domain.onWarningContainer)
            output.exitCode != null -> {
                val failed = output.exitCode != 0
                OutputTag(
                    stringResource(R.string.linux_output_tag_exit, output.exitCode),
                    if (failed) colors.errorContainer else domain.successContainer,
                    if (failed) colors.onErrorContainer else domain.onSuccessContainer,
                )
            }
            output.errorCode != null -> OutputTag(output.errorCode, colors.errorContainer, colors.onErrorContainer)
        }
        output.durationMs?.let { duration ->
            val text = if (duration < 1_000) stringResource(R.string.linux_output_duration_ms, duration)
            else stringResource(R.string.linux_output_duration_seconds, formatSeconds(duration))
            OutputTag(text, colors.surfaceContainerHighest, colors.onSurfaceVariant)
        }
        runtimeLabel(output.runtime)?.let { label ->
            OutputTag(stringResource(label), colors.secondaryContainer, colors.onSecondaryContainer)
        }
        val sizes = if (output.combinedOnly) {
            stringResource(R.string.linux_output_size_log, formatBytes(output.stdout.retainedBytes))
        } else {
            stringResource(R.string.linux_output_sizes, formatBytes(output.stdout.retainedBytes), formatBytes(output.stderr.retainedBytes))
        }
        OutputTag(sizes, colors.surfaceContainerHighest, colors.onSurfaceVariant)
    }
}

@Composable
private fun OutputTag(text: String, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = InfinityMonospace,
            modifier = Modifier.padding(horizontal = InfinitySpacing.Small, vertical = 3.dp),
        )
    }
}

@Composable
private fun SearchRow(search: OutputSearch, onQueryChange: (String) -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        InfinitySearchBar(
            value = search.query,
            onValueChange = onQueryChange,
            placeholder = stringResource(R.string.linux_output_search_placeholder),
            onSearch = onNext,
            modifier = Modifier.weight(1f),
            fieldModifier = Modifier.testTag(UiTags.LINUX_OUTPUT_SEARCH),
        )
        if (search.query.isNotEmpty()) {
            Text(
                text = stringResource(R.string.linux_output_search_count, if (search.current >= 0) search.current + 1 else 0, search.matches.size),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = InfinityMonospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = InfinitySpacing.Small).testTag(UiTags.LINUX_OUTPUT_SEARCH_COUNT),
            )
            IconButton(onClick = onPrevious, enabled = search.matches.isNotEmpty(), modifier = Modifier.testTag(UiTags.LINUX_OUTPUT_SEARCH_PREVIOUS)) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.linux_output_search_previous))
            }
            IconButton(onClick = onNext, enabled = search.matches.isNotEmpty(), modifier = Modifier.testTag(UiTags.LINUX_OUTPUT_SEARCH_NEXT)) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.linux_output_search_next))
            }
        }
    }
}

@Composable
private fun TruncationNotice(state: CommandOutputUiState, output: CommandOutput) {
    val streams = state.truncatedStreams
    if (streams.isEmpty()) return
    val numbers = remember { NumberFormat.getIntegerInstance() }
    val lines = streams.map { (segment, stream) ->
        val name = if (output.combinedOnly) stringResource(R.string.linux_output_size_log_name) else stringResource(segmentLabel(segment))
        val kept = numbers.format(stream.raw.length)
        val original = stream.originalLength?.let(numbers::format)
        when (stream.truncation) {
            CommandTruncation.FAILURE_SUMMARY ->
                stringResource(R.string.linux_output_truncated_summary, name, kept, original ?: "?")
            CommandTruncation.LOG_TAIL -> output.logPath?.let { stringResource(R.string.linux_output_truncated_log_path, kept, it) }
                ?: stringResource(R.string.linux_output_truncated_log, kept)
            else -> stringResource(R.string.linux_output_truncated_tail, name, kept, original ?: "?")
        }
    }
    Surface(
        color = MaterialTheme.infinityDomainColors.warningContainer,
        contentColor = MaterialTheme.infinityDomainColors.onWarningContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().testTag(UiTags.LINUX_OUTPUT_TRUNCATED),
    ) {
        Row(
            modifier = Modifier.padding(InfinitySpacing.Medium),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Icon(Icons.Rounded.WarningAmber, contentDescription = null, modifier = Modifier.size(20.dp))
            Text(lines.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun OutputText(state: CommandOutputUiState, modifier: Modifier) {
    val palette = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) AnsiPalette.Dark else AnsiPalette.Light
    val scheme = MaterialTheme.colorScheme
    val colors = OutputLineColors(
        foreground = scheme.onSurface,
        stderr = scheme.error,
        separator = scheme.onSurfaceVariant,
        match = scheme.tertiaryContainer,
        onMatch = scheme.onTertiaryContainer,
        currentMatch = scheme.primary,
        onCurrentMatch = scheme.onPrimary,
    )
    val document = state.document
    val listState = rememberLazyListState()
    val current = state.search.currentMatch
    LaunchedEffect(current, document) {
        if (current != null) listState.animateScrollToItem(document.lineOf(current.first).coerceAtLeast(0))
    }
    val numbers = remember { NumberFormat.getIntegerInstance() }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = scheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
    ) {
        if (document.text.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(InfinitySpacing.Medium), contentAlignment = Alignment.Center) {
                Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Rounded.Notes, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.linux_output_empty), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant)
                }
            }
            return@Surface
        }
        val style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace, color = scheme.onSurface)
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(InfinitySpacing.Medium),
            modifier = Modifier.fillMaxSize().testTag(UiTags.LINUX_OUTPUT_TEXT),
        ) {
            itemsIndexed(document.lines, key = { index, _ -> index }) { _, line ->
                val text = remember(document, line, state.search.matches, current, palette, colors) {
                    outputLine(document, line, state.search.matches, current, palette, colors)
                }
                Text(text = text, style = style, modifier = Modifier.fillMaxWidth())
            }
            item(key = "footer") {
                val footer = if (state.truncatedStreams.isEmpty()) {
                    stringResource(R.string.linux_output_footer_complete, numbers.format(utf8Length(document.text)))
                } else stringResource(R.string.linux_output_footer_partial)
                Text(
                    text = footer,
                    style = style,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = InfinitySpacing.Small),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.OutputAction(
    label: Int,
    icon: ImageVector,
    tag: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = InfinitySpacing.Small, vertical = InfinitySpacing.Small),
        modifier = Modifier.weight(1f).heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(tag),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(2.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

private fun segmentLabel(segment: OutputSegment): Int = when (segment) {
    OutputSegment.MERGED -> R.string.linux_output_segment_merged
    OutputSegment.STDOUT -> R.string.linux_output_segment_stdout
    OutputSegment.STDERR -> R.string.linux_output_segment_stderr
}

private fun runtimeLabel(runtime: CommandRuntime): Int? = when (runtime) {
    CommandRuntime.LINUX -> R.string.linux_output_runtime_linux
    CommandRuntime.SYSTEM -> R.string.linux_output_runtime_system
    CommandRuntime.TERMUX -> R.string.linux_output_runtime_termux
    CommandRuntime.SSH -> R.string.linux_output_runtime_ssh
    CommandRuntime.PYTHON -> R.string.linux_output_runtime_python
    CommandRuntime.UNKNOWN -> null
}

private const val STOPPED = "EXEC_STOPPED"

private fun copyText(context: Context, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText("command output", text))
}

private fun shareText(context: Context, text: String, title: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    runCatching { context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
