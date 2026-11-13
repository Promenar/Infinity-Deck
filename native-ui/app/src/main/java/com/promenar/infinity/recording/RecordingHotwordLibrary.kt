package com.promenar.infinity.recording

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private enum class HotwordPane { Edit, Import }

@Composable
internal fun RecordingHotwordLibrary(controller: RecordingController, onBack: () -> Unit) {
    val entries by controller.hotwords.collectAsStateWithLifecycle()
    val asr by controller.asrModel.collectAsStateWithLifecycle()
    val state by controller.state.collectAsStateWithLifecycle()
    val unknown = controller::unknownHotwordCharacters.takeIf { asr == RecordingAsrModelState.Installed }
    val failure = state.failure?.takeIf { it == R.string.recording_hotword_storage }
    RecordingHotwordLibraryContent(
        entries = entries,
        ready = state.ready,
        unknown = unknown,
        failure = failure,
        onDismissFailure = { failure?.let(controller::dismissFailure) },
        onSave = controller::saveHotword,
        onDelete = controller::deleteHotword,
        onToggle = controller::setHotwordEnabled,
        onImport = controller::importHotwords,
        onBack = onBack,
    )
}

@Composable
internal fun RecordingHotwordLibraryContent(
    entries: List<RecordingHotword>,
    ready: Boolean,
    unknown: ((String) -> List<Char>?)?,
    onSave: suspend (RecordingHotword) -> Int?,
    onDelete: (String) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onImport: suspend (String) -> RecordingHotwordImport?,
    onBack: () -> Unit,
    failure: Int? = null,
    onDismissFailure: () -> Unit = {},
) {
    var query by rememberSaveable { mutableStateOf("") }
    var pane by rememberSaveable { mutableStateOf<HotwordPane?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var writing by rememberSaveable { mutableStateOf("") }
    var spoken by rememberSaveable { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var importResult by remember { mutableStateOf<RecordingHotwordImport?>(null) }
    var error by remember { mutableStateOf<Int?>(null) }
    var pending by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val selected = entries.firstOrNull { it.id == selectedId }
    val visible = remember(entries, query) {
        val search = query.trim()
        entries.filter { it.text.contains(search, ignoreCase = true) || it.spoken.contains(search, ignoreCase = true) }
    }
    val computedPreview by produceState<Pair<String, RecordingHotwordImport>?>(null, pane, importText, entries) {
        val text = importText
        value = if (pane == HotwordPane.Import) withContext(Dispatchers.Default) {
            text to RecordingHotwordPolicy.previewImport(text, entries)
        } else null
    }
    val preview = computedPreview?.takeIf { it.first == importText }?.second
    fun closePane() {
        focusManager.clearFocus()
        pane = null
        selectedId = null
        error = null
    }
    fun openEditor(entry: RecordingHotword?) {
        focusManager.clearFocus()
        selectedId = entry?.id
        writing = entry?.text.orEmpty()
        spoken = entry?.spoken.orEmpty()
        error = null
        importResult = null
        pane = HotwordPane.Edit
    }

    InfinitySettingsPageLayout(title = stringResource(R.string.recording_hotword_library), onBack = onBack) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val wide = maxWidth >= 680.dp
            BackHandler(enabled = pane != null && wide) { if (!pending) closePane() }
            val editor: @Composable () -> Unit = {
                when (pane) {
                    HotwordPane.Edit -> {
                        Text(
                            stringResource(if (selectedId == null) R.string.recording_hotword_add else R.string.recording_hotword_edit),
                            style = InfinityTypography.titleLarge,
                        )
                        SettingsInput(writing, { writing = it; error = null }, label = stringResource(R.string.recording_hotword_written))
                        SettingsInput(spoken, { spoken = it; error = null }, label = stringResource(R.string.recording_hotword_spoken))
                        HotwordVocabularyNotice(unknown?.invoke(writing + " " + spoken).orEmpty())
                    }
                    HotwordPane.Import -> {
                        Text(stringResource(R.string.recording_hotword_import), style = InfinityTypography.titleLarge)
                        SettingsInput(
                            importText, { importText = it; error = null }, label = stringResource(R.string.recording_hotword_import_format),
                            singleLine = false, maxLines = 8,
                        )
                        preview?.let { Text(hotwordImportLabel(it), style = InfinityTypography.bodySmall) }
                    }
                    null -> Unit
                }
                error?.let {
                    Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
                InfinityPillRow(Modifier.fillMaxWidth()) {
                    if (pane == HotwordPane.Edit && selected != null) {
                        InfinityPillButton(stringResource(R.string.recording_ui_delete_confirm), { confirmDelete = true }, enabled = !pending)
                    }
                    InfinityPillButton(stringResource(R.string.recording_cancel), ::closePane, enabled = !pending)
                    val importing = pane == HotwordPane.Import
                    InfinityPillButton(
                        text = stringResource(if (importing) R.string.recording_hotword_import else R.string.recording_save),
                        enabled = !pending && if (importing) (preview?.added ?: 0) > 0 else writing.isNotBlank(),
                        tone = InfinityPillTone.PRIMARY,
                        onClick = {
                            if (pending) return@InfinityPillButton
                            pending = true
                            val text = importText
                            val entry = RecordingHotword(selectedId ?: UUID.randomUUID().toString(), writing, spoken, selected?.enabled ?: true)
                            scope.launch {
                                try {
                                    if (importing) {
                                        val result = onImport(text)
                                        if (result == null) error = R.string.recording_hotword_storage
                                        else {
                                            importResult = result
                                            closePane()
                                        }
                                    } else {
                                        error = onSave(entry)
                                        if (error == null) closePane()
                                    }
                                } finally { pending = false }
                            }
                        },
                    )
                }
            }

            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.XLarge)) {
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                    SettingsInput(query, { query = it }, label = stringResource(R.string.recording_hotword_search))
                    Text(
                        stringResource(R.string.recording_hotword_count, entries.count { it.enabled }, entries.size),
                        style = InfinityTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    importResult?.let { Text(hotwordImportLabel(it), style = InfinityTypography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (failure != null) {
                        Text(stringResource(failure), color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                        InfinityPillButton(stringResource(R.string.recording_close), onDismissFailure, compact = true)
                    }
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        if (ready && visible.isEmpty()) item {
                            Text(
                                stringResource(if (query.isBlank()) R.string.recording_hotword_empty else R.string.recording_hotword_no_match),
                                style = InfinityTypography.bodyMedium,
                                modifier = Modifier.padding(vertical = InfinitySpacing.Large),
                            )
                        }
                        itemsIndexed(visible, key = { _, entry -> entry.id }) { index, entry ->
                            ProvideGroupedPress {
                                InfinitySettingsItem(
                                    title = entry.text,
                                    subtitleSegments = listOfNotNull(
                                        entry.spoken.takeIf(String::isNotBlank)?.let { stringResource(R.string.recording_hotword_read_as, it) },
                                        unknown?.invoke(entry.text + " " + entry.spoken)?.takeIf { it.isNotEmpty() }?.let {
                                            stringResource(R.string.recording_hotword_out_of_vocab, it.joinToString("、"))
                                        },
                                    ),
                                    onClick = { openEditor(entry) },
                                    enabled = !pending,
                                    selected = pane == HotwordPane.Edit && selectedId == entry.id,
                                    modifier = Modifier.bettboxListGroupItem(index, visible.size),
                                    subtitleMaxLines = 3,
                                    trailingContent = {
                                        val label = stringResource(R.string.recording_hotword_toggle, entry.text)
                                        InfinitySwitch(entry.enabled, { onToggle(entry.id, it) }, Modifier.semantics { contentDescription = label }, enabled = !pending)
                                    },
                                )
                            }
                        }
                    }
                    InfinityPillRow(Modifier.fillMaxWidth()) {
                        InfinityPillButton(
                            stringResource(R.string.recording_hotword_import),
                            { importText = ""; importResult = null; error = null; pane = HotwordPane.Import },
                            enabled = ready && !pending,
                        )
                        InfinityPillButton(
                            stringResource(R.string.recording_hotword_add), { openEditor(null) }, tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.Add, enabled = ready && !pending && entries.size < RecordingHotwordPolicy.MAX_LIBRARY,
                        )
                    }
                }
                if (wide) {
                    VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
                    ) {
                        if (pane == null) {
                            Text(stringResource(R.string.recording_hotword_select), style = InfinityTypography.bodyMedium,
                                modifier = Modifier.align(Alignment.CenterHorizontally).padding(InfinitySpacing.XLarge))
                        } else editor()
                    }
                }
            }
            if (!wide && pane != null) {
                InfinityBottomSheet(show = true, onDismiss = ::closePane, height = InfinitySheetHeight.Full,
                    dismissEnabled = !pending, dragGesturesEnabled = false) {
                    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) { editor() }
                }
            }
            if (confirmDelete && selected != null) {
                InfinityConfirmDialog(
                    title = stringResource(R.string.recording_hotword_delete),
                    message = stringResource(R.string.recording_hotword_delete_detail, selected.text),
                    confirmText = stringResource(R.string.recording_ui_delete_confirm),
                    cancelText = stringResource(R.string.recording_cancel),
                    onConfirm = { confirmDelete = false; onDelete(selected.id); closePane() },
                    onCancel = { confirmDelete = false },
                    isDestructive = true,
                )
            }
        }
    }
}

@Composable
private fun hotwordImportLabel(result: RecordingHotwordImport): String =
    stringResource(R.string.recording_hotword_import_count, result.added, result.existing, result.invalid)

@Composable
private fun HotwordVocabularyNotice(unknown: List<Char>) {
    if (unknown.isEmpty()) return
    Text(
        stringResource(R.string.recording_hotword_out_of_vocab_detail, unknown.joinToString("、")),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = InfinityTypography.bodySmall,
    )
}
