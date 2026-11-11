package com.promenar.infinity.ui.skills.store

import android.app.Application
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.data.skills.store.SkillStoreSource
import com.promenar.infinity.data.skills.store.SourceStatus
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityPropertyRow
import com.promenar.infinity.ui.common.InfinityPropertySheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySnackbar
import com.promenar.infinity.ui.common.InfinitySnackbarData
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.SnackbarType
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import kotlinx.coroutines.launch

/** 目录来源页：官方目录（内置不可移除）、第三方目录的添加与移除，以及自动检查更新。 */
@Composable
fun SkillStoreSourcesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val viewModel: SkillStoreViewModel = viewModel(factory = SkillStoreViewModel.factory(context.applicationContext as Application))
    val state by viewModel.state.collectAsState()
    val event by viewModel.event.collectAsState()
    val snackbarHost = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    var snackbarType by remember { mutableStateOf(SnackbarType.SUCCESS) }

    LaunchedEffect(event) {
        val current = event ?: return@LaunchedEffect
        val message = when (current) {
            is SkillStoreEvent.SourceAdded -> context.getString(R.string.skill_store_event_source_added)
            is SkillStoreEvent.Failed -> storeFailureText(context, current.failure, current.httpStatus)
            else -> null
        } ?: return@LaunchedEffect
        viewModel.consumeEvent()
        snackbarType = if (current is SkillStoreEvent.Failed) SnackbarType.ERROR else SnackbarType.SUCCESS
        snackbarScope.launch { snackbarHost.showSnackbar(message) }
    }

    Box(Modifier.fillMaxSize()) {
        SkillStoreSourcesContent(
            sources = state.sources,
            autoCheck = state.autoCheck,
            lastCheckedAt = state.lastCheckedAt,
            actions = SkillStoreSourcesActions(
                onBack = onBack,
                onAdd = viewModel::addSource,
                onRemove = viewModel::removeSource,
                onAutoCheckChange = viewModel::setAutoCheck,
            ),
        )
        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
        ) { data -> InfinitySnackbar(InfinitySnackbarData(data.visuals.message, snackbarType)) }
    }
}

class SkillStoreSourcesActions(
    val onBack: () -> Unit,
    val onAdd: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onAutoCheckChange: (Boolean) -> Unit,
)

@Composable
fun SkillStoreSourcesContent(
    sources: List<SkillStoreSource>,
    autoCheck: Boolean,
    lastCheckedAt: Long?,
    actions: SkillStoreSourcesActions,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SkillStoreSource?>(null) }
    InfinitySettingsPageLayout(title = stringResource(R.string.skill_store_sources), onBack = actions.onBack) { contentPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {
            InfinitySettingsSection(title = stringResource(R.string.skill_store_sources_group)) {
                sources.forEach { source ->
                    SourceRow(source = source, onRemove = { removing = source })
                }
                InfinitySettingsItem(
                    icon = Icons.Rounded.Add,
                    title = stringResource(R.string.skill_store_source_add),
                    titleColor = MaterialTheme.colorScheme.primary,
                    onClick = { adding = true },
                    modifier = Modifier.testTag("skill_store_source_add"),
                )
            }
            InfinitySettingsSection(title = stringResource(R.string.skill_store_updates_group)) {
                SettingsToggle(
                    title = stringResource(R.string.skill_store_auto_check),
                    checked = autoCheck,
                    onCheckedChange = actions.onAutoCheckChange,
                    switchModifier = Modifier.testTag("skill_store_auto_check"),
                )
                InfinityPropertySheet(Modifier.padding(vertical = InfinitySpacing.Small)) {
                    InfinityPropertyRow(
                        label = stringResource(R.string.skill_store_last_checked),
                        value = lastCheckedAt?.let {
                            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                        } ?: stringResource(R.string.skill_store_never_checked),
                    )
                }
            }
        }
    }
    if (adding) {
        AddSourceDialog(
            onConfirm = { url ->
                adding = false
                actions.onAdd(url)
            },
            onDismiss = { adding = false },
        )
    }
    removing?.let { source ->
        InfinityConfirmDialog(
            title = stringResource(R.string.skill_store_source_remove_title, sourceName(source)),
            message = stringResource(R.string.skill_store_source_remove_message),
            confirmText = stringResource(R.string.skill_store_source_remove),
            onConfirm = {
                removing = null
                actions.onRemove(source.url)
            },
            onCancel = { removing = null },
            isDestructive = true,
            confirmButtonModifier = Modifier.testTag("skill_store_source_remove_confirm"),
        )
    }
}

/** 一个目录：名称，副标题为主机、技能数与状态；第三方目录写明安装后停用，行尾可移除。 */
@Composable
private fun SourceRow(source: SkillStoreSource, onRemove: () -> Unit) {
    val segments = buildList {
        if (source.title != null || source.official) add(source.host)
        when (val status = source.status) {
            SourceStatus.Loading -> add(stringResource(R.string.skill_store_source_loading))
            is SourceStatus.Failed -> add(storeFailureText(status.failure, status.httpStatus))
            SourceStatus.Idle, SourceStatus.Ready -> add(
                pluralStringResource(R.plurals.skill_store_skill_count, source.skillCount, source.skillCount),
            )
        }
        if (!source.official) add(stringResource(R.string.skill_store_source_installs_disabled))
    }
    // 目录行本身没有可打开的内容，用不可点按的行布局，避免点按反馈误导。
    InfinitySettingsRowLayout(
        title = sourceName(source),
        subtitleSegments = segments,
        modifier = Modifier.testTag("skill_store_source:${source.host}"),
        trailingContent = {
            when {
                source.status == SourceStatus.Loading -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                source.official -> Text(
                    stringResource(R.string.skill_store_source_builtin),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                else -> IconButton(onClick = onRemove, modifier = Modifier.testTag("skill_store_source_remove:${source.host}")) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = stringResource(R.string.skill_store_source_remove))
                }
            }
        },
    )
}

@Composable
private fun AddSourceDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val trimmed = text.trim()
    InfinityConfirmDialog(
        title = stringResource(R.string.skill_store_source_add),
        message = "",
        confirmText = stringResource(R.string.skill_store_source_add_confirm),
        onConfirm = { onConfirm(trimmed) },
        onCancel = onDismiss,
        confirmEnabled = trimmed.isNotEmpty(),
        confirmButtonModifier = Modifier.testTag("skill_store_source_add_confirm"),
        content = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(2048) },
                textStyle = LocalTextStyle.current.forInput(),
                label = { Text(stringResource(R.string.skill_store_source_address)) },
                placeholder = { Text(stringResource(R.string.skill_store_source_address_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().testTag("skill_store_source_input"),
            )
        },
    )
}
