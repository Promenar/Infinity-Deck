package com.promenar.infinity.ui.settings

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.production.ProductionStatusChip
import com.promenar.infinity.ui.production.ProductionTone
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forInput
import androidx.compose.ui.graphics.Color
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether
import com.promenar.infinity.ui.theme.rememberLastNonNull
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private enum class ComfyHostDetailTab { WORKFLOWS, SUBMITTERS, HOST_CONFIG }

@Composable
internal fun ComfyHubScreenContent(
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    onOpenHost: (String) -> Unit = {},
    onAddHost: () -> Unit = {},
    onCheckAll: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val hosts = state.snapshot.hosts

    InfinitySettingsPageLayout(
        title = stringResource(R.string.media_settings_comfy_title),
        onBack = onBack,
        actions = {
            if (hosts.isNotEmpty()) {
                IconButton(onClick = onCheckAll, enabled = !state.busy, modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.media_settings_check_all_hosts))
                }
            }
            IconButton(
                onClick = onAddHost,
                enabled = !state.busy,
                modifier = Modifier.size(InfinitySpacing.MinimumTouchTarget).testTag("comfy_hub_add_host"),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.media_settings_add_host))
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .animateContentSize(InfinityMotion.contentSizeSpec())
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
        ) {
            MediaOperationState(state, onRetry)

            val pyReady = state.snapshot.pythonAvailable
            val containerColor = if (pyReady) domain.infoContainer.copy(alpha = 0.55f) else domain.warningContainer.copy(alpha = 0.65f)
            val contentColor = if (pyReady) domain.onInfoContainer else domain.onWarningContainer
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.ScreenHorizontal),
                shape = RoundedCornerShape(18.dp),
                color = containerColor,
                border = BorderStroke(0.8.dp, contentColor.copy(alpha = 0.22f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(contentColor.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Terminal, null, tint = contentColor, modifier = Modifier.size(20.dp))
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        InfinityScrollingLine(Modifier.fillMaxWidth()) {
                            Text(
                                text = stringResource(if (pyReady) R.string.media_settings_python_ready else R.string.media_settings_python_unavailable),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                color = contentColor,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                        if (pyReady) {
                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                InfinityMetaLine(
                                    segments = listOf(
                                        stringResource(R.string.media_settings_python_meta_no_linux),
                                        stringResource(R.string.media_settings_python_meta_submit_only),
                                        stringResource(R.string.media_settings_python_meta_proxy_auth),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = contentColor.copy(alpha = 0.88f),
                                )
                            }
                        }
                    }
                }
            }

            InfinitySettingsSection(
                title = stringResource(R.string.media_settings_hosts),
                footer = stringResource(R.string.media_settings_hosts_footer),
            ) {
                if (hosts.isEmpty()) {
                    MediaNotice(stringResource(R.string.media_settings_hosts_empty))
                } else {
                    hosts.forEachIndexed { index, host ->
                        val wfCount = state.snapshot.workflows.count { it.workflow.hostId == host.id }
                        val subCount = state.snapshot.submitters.count { it.hostId == host.id }
                        val tailnet = isTailnetAddress(host.endpoint)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button, onClick = { onOpenHost(host.id) })
                                .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp)
                                .testTag("comfy_hub_host:${host.id}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Rounded.Dns, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    InfinityScrollingLine(Modifier.weight(1f, fill = false)) {
                                        Text(
                                            text = host.name,
                                            style = MaterialTheme.typography.bodyLarge,
                                            color = scheme.onSurface,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    if (tailnet) ProductionStatusChip("Tailnet", ProductionTone.INFO)
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    Text(
                                        text = host.endpoint,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                    InfinityMetaLine(
                                        segments = listOf(
                                            stringResource(R.string.media_settings_host_workflows_count, wfCount),
                                            stringResource(R.string.media_settings_host_submitters_count, subCount),
                                            authKindLabel(host.authKind),
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                    )
                                }
                            }
                            ConnectionChip(state.connections[host.id] ?: ComfyConnection.UNKNOWN)
                        }
                        if (index < hosts.lastIndex) InfinityGroupDivider()
                    }
                }
            }

            InfinitySettingsSection(title = stringResource(R.string.media_settings_actions_section)) {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Add,
                    title = stringResource(R.string.media_settings_add_host),
                    onClick = onAddHost,
                    enabled = !state.busy,
                )
                if (hosts.isNotEmpty()) {
                    InfinityGroupDivider()
                    InfinitySettingsItem(
                        icon = Icons.Rounded.NetworkCheck,
                        title = stringResource(R.string.media_settings_check_all_hosts),
                        onClick = onCheckAll,
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ComfyHostsSection(
    state: MediaSettingsUiState,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onCheck: () -> Unit,
) {
    InfinitySettingsSection(title = stringResource(R.string.media_settings_hosts)) {
        state.snapshot.hosts.forEach { host ->
            InfinitySettingsItem(
                icon = Icons.Rounded.Dns,
                title = host.name,
                titleSegments = listOf("ComfyUI", host.name),
                subtitleSegments = listOf(
                    host.endpoint,
                    stringResource(R.string.media_settings_templates_count, state.snapshot.workflows.count { it.workflow.hostId == host.id }),
                ),
                subtitleMaxLines = 2,
                titleMaxLines = 2,
                onClick = { onOpen(host.id) },
                trailingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ConnectionChip(state.connections[host.id] ?: ComfyConnection.UNKNOWN)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
        InfinitySettingsItem(icon = Icons.Rounded.Add, title = stringResource(R.string.media_settings_add_host), onClick = onAdd, enabled = !state.busy)
        if (state.snapshot.hosts.isNotEmpty()) {
            InfinitySettingsItem(
                icon = Icons.Rounded.NetworkCheck,
                title = stringResource(R.string.media_settings_check_connection),
                onClick = onCheck,
                enabled = !state.busy,
            )
        }
    }
}

private data class SubmitterEditorDraft(
    val isNew: Boolean,
    val scriptId: String = "",
    val fileName: String = "",
    val displayName: String = "",
    val description: String = "",
    val protocol: SubmitterInvocationProtocol = SubmitterInvocationProtocol.CLI_ARGS,
    val sourceKind: SubmitterSourceKind = SubmitterSourceKind.DESKTOP_IMPORT,
    val sourceText: String = "",
    val supportFiles: Map<String, ByteArray> = emptyMap(),
)

@Composable
internal fun ComfyHostScreenContent(
    host: ComfyHost,
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onOpenTemplate: (String) -> Unit = {},
    onOpenWorkflowEditor: (String) -> Unit = {},
    onNewWorkflow: () -> Unit = {},
    onPreflightWorkflow: (String) -> Unit = {},
    onDeleteWorkflow: (String) -> Unit = {},
    onSaveSubmitter: (
        scriptId: String,
        fileName: String,
        displayName: String,
        description: String,
        sourceKind: SubmitterSourceKind,
        protocol: SubmitterInvocationProtocol,
        sourceText: String,
        supportFiles: Map<String, ByteArray>,
        runDryRun: Boolean,
        onDone: () -> Unit,
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _ -> },
    onDryRunSubmitter: (String) -> Unit = {},
    onDryRunAllSubmitters: () -> Unit = {},
    onDeleteSubmitter: (String) -> Unit = {},
    onReadSubmitterSource: suspend (String) -> String? = { null },
    onPreflight: () -> Unit = {},
    onCheck: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme

    var selectedTab by remember(host.id) { mutableStateOf(ComfyHostDetailTab.WORKFLOWS) }
    var categoryFilter by remember(host.id) { mutableStateOf<ComfyMediaCategory?>(null) }
    var deletingHost by remember(host.id) { mutableStateOf(false) }
    var deletingWorkflow by remember { mutableStateOf<ComfyWorkflow?>(null) }
    var deletingSubmitter by remember { mutableStateOf<ComfySubmitterScript?>(null) }
    var submitterEditor by remember { mutableStateOf<SubmitterEditorDraft?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val hostWorkflows = remember(state.snapshot.workflows, host.id) {
        state.snapshot.workflows.filter { it.workflow.hostId == host.id }
    }
    val hostSubmitters = remember(state.snapshot.submitters, host.id) {
        state.snapshot.submitters.filter { it.hostId == host.id }
    }
    val filteredWorkflows = remember(hostWorkflows, categoryFilter) {
        hostWorkflows.filter { categoryFilter == null || it.workflow.mediaCategory == categoryFilter }
    }

    val pyFilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val (pickedName, bytes) = readUriNameAndBytes(context, uri)
                val safeFile = pickedName.takeIf { it.endsWith(".py") } ?: "${pickedName.substringBeforeLast('.')}.py"
                val stem = safeFile.removeSuffix(".py").replace(Regex("[^A-Za-z0-9_-]"), "_").ifBlank { "submitter" }
                submitterEditor = SubmitterEditorDraft(
                    isNew = true,
                    scriptId = stem,
                    fileName = safeFile,
                    displayName = safeFile,
                    sourceKind = SubmitterSourceKind.DESKTOP_IMPORT,
                    protocol = SubmitterInvocationProtocol.CLI_ARGS,
                    sourceText = bytes.toString(Charsets.UTF_8),
                )
                importError = null
            }.onFailure { e ->
                importError = e.message ?: "读取脚本文件失败"
            }
        }
    }

    InfinitySettingsPageLayout(
        title = host.name,
        onBack = onBack,
        horizontalContentPadding = 0.dp,
        topContentPadding = 0.dp,
        actions = {
            Box(Modifier.padding(end = 12.dp)) {
                ConnectionChip(state.connections[host.id] ?: ComfyConnection.UNKNOWN)
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .animateContentSize(InfinityMotion.contentSizeSpec())
                .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding()),
        ) {
            MediaOperationState(state, onRetry)
            AnimatedVisibility(visible = importError != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                importError?.let { MediaNotice(it, error = true) }
            }
            state.errorDetail?.let { detail ->
                MediaNotice(detail, error = true)
            }

            InfinityPanelTabs(
                tabs = listOf(
                    InfinityPanelTab(ComfyHostDetailTab.WORKFLOWS, stringResource(R.string.media_settings_tab_workflows), count = hostWorkflows.size.toString()),
                    InfinityPanelTab(ComfyHostDetailTab.SUBMITTERS, stringResource(R.string.media_settings_tab_submitters), count = hostSubmitters.size.toString()),
                    InfinityPanelTab(ComfyHostDetailTab.HOST_CONFIG, stringResource(R.string.media_settings_tab_host_config)),
                ),
                selected = selectedTab,
                onSelect = { selectedTab = it },
            )

            when (selectedTab) {
                ComfyHostDetailTab.WORKFLOWS -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        InfinityPillRow(Modifier.fillMaxWidth(), stackedSpacing = 8.dp) {
                            InfinityPillButton(
                                text = stringResource(R.string.media_settings_add_workflow),
                                onClick = onNewWorkflow,
                                enabled = !state.busy,
                                tone = InfinityPillTone.PRIMARY,
                                icon = Icons.Rounded.Add,
                                modifier = Modifier.weight(1f).testTag("comfy_host_add_workflow"),
                            )
                            InfinityPillButton(
                                text = stringResource(R.string.media_settings_recheck),
                                onClick = onPreflight,
                                enabled = !state.busy && hostWorkflows.isNotEmpty(),
                                tone = InfinityPillTone.NEUTRAL,
                                icon = Icons.Rounded.Refresh,
                                modifier = Modifier.weight(1f),
                            )
                        }

                        InfinityTagScrollRow(spacing = 8.dp) {
                            InfinityChip(
                                label = stringResource(R.string.media_settings_cat_all),
                                selected = categoryFilter == null,
                                onClick = { categoryFilter = null },
                            )
                            ComfyMediaCategory.entries.forEach { cat ->
                                InfinityChip(
                                    label = comfyMediaCategoryLabel(cat),
                                    selected = categoryFilter == cat,
                                    onClick = { categoryFilter = cat },
                                )
                            }
                        }

                        if (filteredWorkflows.isEmpty()) {
                            MediaNotice(stringResource(R.string.media_settings_no_templates))
                        } else {
                            BettboxListGroup {
                                filteredWorkflows.forEachIndexed { index, wfState ->
                                    val wf = wfState.workflow
                                    val chained = wf.executionMode == WorkflowExecutionMode.STATEFUL_CHAIN
                                    val submitterName = wf.submitterScriptId?.let { id ->
                                        hostSubmitters.firstOrNull { it.scriptId == id }?.fileName ?: id
                                    } ?: stringResource(R.string.media_settings_builtin_json_executor)
                                    val legacyTemplateOnly = state.snapshot.workflows.any { it.workflow.workflowId == wf.workflowId } &&
                                        state.snapshot.templates.any { it.template.id == wf.workflowId } &&
                                        wf.submitterScriptId == null
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(role = Role.Button) {
                                                if (legacyTemplateOnly) onOpenTemplate(wf.workflowId)
                                                else onOpenWorkflowEditor(wf.workflowId)
                                            }
                                            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp)
                                            .testTag("comfy_workflow_row:${wf.workflowId}"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (chained) scheme.primaryContainer else scheme.surfaceContainerHigh),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Icon(
                                                imageVector = if (chained) Icons.Rounded.Link else comfyMediaCategoryIcon(wf.mediaCategory),
                                                contentDescription = null,
                                                tint = if (chained) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
                                                modifier = Modifier.size(19.dp),
                                            )
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                                Text(
                                                    text = wf.name,
                                                    style = MaterialTheme.typography.bodyLarge,
                                                    color = scheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                            InfinityTagScrollRow(spacing = 6.dp) {
                                                ProductionStatusChip(comfyMediaCategoryLabel(wf.mediaCategory), ProductionTone.NEUTRAL)
                                                ProductionStatusChip(
                                                    stringResource(if (chained) R.string.media_settings_exec_chain else R.string.media_settings_exec_single),
                                                    if (chained) ProductionTone.INFO else ProductionTone.NEUTRAL,
                                                )
                                                Text(
                                                    text = submitterName,
                                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                                                    color = scheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                                InfinityMetaLine(
                                                    segments = listOf(
                                                        stringResource(R.string.media_settings_workflow_content_count, wf.contentFields().size),
                                                        stringResource(R.string.media_settings_workflow_fixed_count, wf.fixedFields().size),
                                                    ),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = scheme.onSurfaceVariant,
                                                )
                                            }
                                        }
                                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            PreflightChip(wfState.preflight)
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                IconButton(onClick = { onPreflightWorkflow(wf.workflowId) }, modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.Rounded.Refresh, stringResource(R.string.media_settings_recheck), Modifier.size(16.dp))
                                                }
                                                IconButton(onClick = { deletingWorkflow = wf }, modifier = Modifier.size(32.dp)) {
                                                    Icon(Icons.Rounded.DeleteOutline, stringResource(R.string.media_settings_delete), Modifier.size(16.dp), tint = scheme.error)
                                                }
                                            }
                                        }
                                    }
                                    if (index < filteredWorkflows.lastIndex) InfinityGroupDivider()
                                }
                            }
                        }
                    }
                }

                ComfyHostDetailTab.SUBMITTERS -> {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = scheme.surfaceContainerLow,
                            border = BorderStroke(0.5.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Icon(Icons.Rounded.Security, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                                Text(
                                    text = stringResource(R.string.media_settings_submitter_notice).keepCjkTailTogether(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }

                        InfinityPillRow(Modifier.fillMaxWidth(), stackedSpacing = 8.dp) {
                            InfinityPillButton(
                                text = stringResource(R.string.media_settings_import_submitter),
                                onClick = { pyFilePicker.launch(arrayOf("*/*")) },
                                enabled = !state.busy,
                                tone = InfinityPillTone.PRIMARY,
                                icon = Icons.Rounded.FileUpload,
                                modifier = Modifier.weight(1f).testTag("comfy_submitter_import_btn"),
                            )
                            InfinityPillButton(
                                text = stringResource(R.string.media_settings_paste_submitter),
                                onClick = {
                                    submitterEditor = SubmitterEditorDraft(
                                        isNew = true,
                                        protocol = SubmitterInvocationProtocol.CLI_ARGS,
                                        sourceKind = SubmitterSourceKind.DESKTOP_IMPORT,
                                    )
                                },
                                enabled = !state.busy,
                                tone = InfinityPillTone.NEUTRAL,
                                icon = Icons.Rounded.Add,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (hostSubmitters.isNotEmpty()) {
                            InfinityPillButton(
                                text = stringResource(R.string.media_settings_dry_run_all),
                                onClick = onDryRunAllSubmitters,
                                enabled = !state.busy && state.snapshot.pythonAvailable,
                                tone = InfinityPillTone.NEUTRAL,
                                icon = Icons.Rounded.PlayArrow,
                                compact = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        if (hostSubmitters.isEmpty()) {
                            MediaNotice(stringResource(R.string.media_settings_no_submitters))
                        } else {
                            BettboxListGroup {
                                hostSubmitters.forEachIndexed { index, sub ->
                                    val usedBy = hostWorkflows.count { it.workflow.submitterScriptId == sub.scriptId }
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            Icon(Icons.Rounded.Code, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                                            InfinityScrollingLine(Modifier.weight(1f)) {
                                                Text(
                                                    text = sub.fileName,
                                                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace),
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = scheme.onSurface,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                            SubmitterDryRunChip(sub.lastDryRunStatus)
                                        }
                                        if (sub.description.isNotBlank() || sub.lastDryRunMessage.isNotBlank()) {
                                            InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                                Text(
                                                    text = sub.lastDryRunMessage.ifBlank { sub.description },
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (sub.lastDryRunStatus == SubmitterDryRunStatus.FAILED) scheme.error else scheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                        InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                            InfinityMetaLine(
                                                segments = buildList {
                                                    add(sub.protocol.name)
                                                    add("SHA ${sub.sha256.take(8)}")
                                                    add(stringResource(R.string.media_settings_used_by_workflows, usedBy))
                                                    if (sub.supportFiles.isNotEmpty()) {
                                                        add(stringResource(R.string.media_settings_support_files_count, sub.supportFiles.size))
                                                    }
                                                    sub.lastDryRunLatencyMs?.let { add("${it}ms") }
                                                },
                                                style = MaterialTheme.typography.labelSmall,
                                                color = scheme.onSurfaceVariant,
                                            )
                                        }
                                        InfinityTagScrollRow(spacing = 8.dp, modifier = Modifier.padding(top = 2.dp)) {
                                            InfinityPillButton(
                                                text = stringResource(R.string.media_settings_dry_run),
                                                onClick = { onDryRunSubmitter(sub.scriptId) },
                                                enabled = !state.busy && state.snapshot.pythonAvailable,
                                                tone = InfinityPillTone.NEUTRAL,
                                                compact = true,
                                            )
                                            InfinityPillButton(
                                                text = stringResource(R.string.media_settings_replace_script),
                                                onClick = {
                                                    scope.launch {
                                                        val loadedSource = onReadSubmitterSource(sub.scriptId).orEmpty()
                                                        submitterEditor = SubmitterEditorDraft(
                                                            isNew = false,
                                                            scriptId = sub.scriptId,
                                                            fileName = sub.fileName,
                                                            displayName = sub.displayName,
                                                            description = sub.description,
                                                            protocol = sub.protocol,
                                                            sourceKind = sub.sourceKind,
                                                            sourceText = loadedSource,
                                                        )
                                                    }
                                                },
                                                enabled = !state.busy,
                                                tone = InfinityPillTone.NEUTRAL,
                                                compact = true,
                                            )
                                            InfinityPillButton(
                                                text = stringResource(R.string.media_settings_delete),
                                                onClick = { deletingSubmitter = sub },
                                                enabled = !state.busy && usedBy == 0,
                                                tone = InfinityPillTone.DANGER,
                                                compact = true,
                                            )
                                        }
                                    }
                                    if (index < hostSubmitters.lastIndex) InfinityGroupDivider()
                                }
                            }
                        }
                    }
                }

                ComfyHostDetailTab.HOST_CONFIG -> {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InfinitySettingsSection(title = stringResource(R.string.media_settings_connection)) {
                            MediaValue(stringResource(R.string.media_settings_host_address), host.endpoint)
                            MediaValue(
                                stringResource(R.string.media_settings_auth),
                                authKindLabel(host.authKind),
                            )
                            InfinitySettingsItem(
                                icon = Icons.Rounded.NetworkCheck,
                                title = stringResource(R.string.media_settings_check_connection),
                                onClick = onCheck,
                                enabled = !state.busy,
                                trailingContent = { ConnectionChip(state.connections[host.id] ?: ComfyConnection.UNKNOWN) },
                            )
                        }
                        InfinitySettingsSection(title = stringResource(R.string.media_settings_host_actions)) {
                            InfinitySettingsItem(
                                icon = Icons.Rounded.Edit,
                                title = stringResource(R.string.media_settings_edit_host),
                                onClick = onEdit,
                                enabled = !state.busy,
                                showChevron = true,
                            )
                            InfinityGroupDivider()
                            InfinitySettingsItem(
                                icon = Icons.Rounded.Delete,
                                title = stringResource(R.string.media_settings_delete),
                                onClick = { deletingHost = true },
                                enabled = !state.busy,
                                titleColor = scheme.error,
                            )
                        }
                    }
                }
            }
        }
    }

    submitterEditor?.let { draft ->
        ComfySubmitterEditorDialog(
            draft = draft,
            busy = state.busy,
            onDismiss = { submitterEditor = null },
            onSave = { updated ->
                onSaveSubmitter(
                    updated.scriptId.trim(),
                    updated.fileName.trim(),
                    updated.displayName.trim().ifBlank { updated.fileName.trim() },
                    updated.description.trim(),
                    updated.sourceKind,
                    updated.protocol,
                    updated.sourceText,
                    updated.supportFiles,
                    true,
                ) {
                    submitterEditor = null
                }
            },
        )
    }

    if (deletingHost) {
        InfinityConfirmDialog(
            title = stringResource(R.string.media_settings_delete_host_title),
            message = stringResource(R.string.media_settings_delete_host_message),
            confirmText = stringResource(R.string.media_settings_delete),
            cancelText = stringResource(R.string.media_settings_cancel),
            isDestructive = true,
            confirmEnabled = !state.busy,
            onConfirm = { deletingHost = false; onDelete() },
            onCancel = { deletingHost = false },
        )
    }
    deletingWorkflow?.let { target ->
        InfinityConfirmDialog(
            title = stringResource(R.string.media_settings_delete_workflow_title),
            message = stringResource(R.string.media_settings_delete_workflow_msg, target.name),
            confirmText = stringResource(R.string.media_settings_delete),
            cancelText = stringResource(R.string.media_settings_cancel),
            isDestructive = true,
            confirmEnabled = !state.busy,
            onConfirm = { deletingWorkflow = null; onDeleteWorkflow(target.workflowId) },
            onCancel = { deletingWorkflow = null },
        )
    }
    deletingSubmitter?.let { target ->
        InfinityConfirmDialog(
            title = stringResource(R.string.media_settings_delete_submitter_title),
            message = stringResource(R.string.media_settings_delete_submitter_msg, target.fileName),
            confirmText = stringResource(R.string.media_settings_delete),
            cancelText = stringResource(R.string.media_settings_cancel),
            isDestructive = true,
            confirmEnabled = !state.busy,
            onConfirm = { deletingSubmitter = null; onDeleteSubmitter(target.scriptId) },
            onCancel = { deletingSubmitter = null },
        )
    }
}

@Composable
private fun SubmitterDryRunChip(status: SubmitterDryRunStatus) {
    val (resId, tone) = when (status) {
        SubmitterDryRunStatus.PASSED -> R.string.media_settings_dry_run_passed to ProductionTone.SUCCESS
        SubmitterDryRunStatus.FAILED -> R.string.media_settings_dry_run_failed to ProductionTone.ERROR
        SubmitterDryRunStatus.UNTESTED -> R.string.media_settings_dry_run_untested to ProductionTone.NEUTRAL
    }
    ProductionStatusChip(stringResource(resId), tone)
}

@Composable
private fun ComfySubmitterEditorDialog(
    draft: SubmitterEditorDraft,
    busy: Boolean,
    onDismiss: () -> Unit,
    onSave: (SubmitterEditorDraft) -> Unit,
) {
    val context = LocalContext.current
    var scriptId by remember(draft) { mutableStateOf(draft.scriptId) }
    var fileName by remember(draft) { mutableStateOf(draft.fileName) }
    var description by remember(draft) { mutableStateOf(draft.description) }
    var protocol by remember(draft) { mutableStateOf(draft.protocol) }
    var sourceText by remember(draft) { mutableStateOf(draft.sourceText) }
    var supportFiles by remember(draft) { mutableStateOf(draft.supportFiles) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    val supportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                val (name, bytes) = readUriNameAndBytes(context, uri)
                supportFiles = supportFiles + (name to bytes)
            }.onFailure { e ->
                errorMsg = e.message ?: "读取配套文件失败"
            }
        }
    }

    val dark = MaterialTheme.infinityDomainColors.isDark
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(top = 24.dp),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = infinitySheetContainerColor(),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = if (dark) BorderStroke(1.dp, InfinityPureWhite.copy(alpha = 0.12f)) else null,
        ) {
            Column(
                Modifier
                    .obsidianTopGlint(
                        color = if (dark) InfinityPureWhite.copy(alpha = 0.14f) else Color.Transparent,
                        cornerRadius = 20.dp,
                    )
                    .fillMaxSize(),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.media_settings_cancel)) }
                    InfinityScrollingLine(Modifier.weight(1f)) {
                        Text(
                            stringResource(if (draft.isNew) R.string.media_settings_paste_submitter else R.string.media_settings_replace_script),
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    InfinityPillButton(
                        text = stringResource(R.string.media_settings_save),
                        onClick = {
                            runCatching {
                                require(scriptId.trim().matches(Regex("[A-Za-z0-9_-]{1,128}"))) { "script_id 仅允许字母、数字、下划线和连字符" }
                                require(fileName.trim().endsWith(".py")) { "入口文件名须以 .py 结尾" }
                                require(sourceText.isNotBlank()) { "Python 源码不能为空" }
                                draft.copy(
                                    scriptId = scriptId.trim(),
                                    fileName = fileName.trim(),
                                    displayName = fileName.trim(),
                                    description = description.trim(),
                                    protocol = protocol,
                                    sourceText = sourceText,
                                    supportFiles = supportFiles,
                                )
                            }.onSuccess(onSave).onFailure { errorMsg = it.message ?: "参数无效" }
                        },
                        enabled = !busy,
                        tone = InfinityPillTone.PRIMARY,
                        compact = true,
                    )
                }
                HorizontalDivider()
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AnimatedVisibility(visible = errorMsg != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                        errorMsg?.let { MediaNotice(it, error = true) }
                    }
                    OutlinedTextField(
                        value = scriptId,
                        onValueChange = { if (draft.isNew) { scriptId = it; errorMsg = null } },
                        enabled = draft.isNew && !busy,
                        label = { Text(stringResource(R.string.media_settings_script_id)) },
                        textStyle = LocalTextStyle.current.forInput().copy(fontFamily = InfinityMonospace),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = fileName,
                        onValueChange = { fileName = it; errorMsg = null },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.media_settings_file_name)) },
                        textStyle = LocalTextStyle.current.forInput().copy(fontFamily = InfinityMonospace),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.media_settings_wf_desc)) },
                        textStyle = LocalTextStyle.current.forInput(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.media_settings_protocol), style = MaterialTheme.typography.labelMedium)
                    InfinitySegmentedControl(
                        segments = listOf(
                            InfinitySegment(SubmitterInvocationProtocol.CLI_ARGS, "CLI_ARGS"),
                            InfinitySegment(SubmitterInvocationProtocol.PAYLOAD_JSON, "PAYLOAD_JSON"),
                        ),
                        selected = protocol,
                        onSelect = { protocol = it },
                        fillWidth = true,
                    )
                    OutlinedTextField(
                        value = sourceText,
                        onValueChange = { sourceText = it; errorMsg = null },
                        enabled = !busy,
                        label = { Text(stringResource(R.string.media_settings_source_code)) },
                        textStyle = LocalTextStyle.current.forInput().copy(fontFamily = InfinityMonospace),
                        minLines = 8,
                        maxLines = 18,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.media_settings_support_files),
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.weight(1f),
                        )
                        InfinityPillButton(
                            text = stringResource(R.string.media_settings_add_support_file),
                            onClick = { supportPicker.launch(arrayOf("*/*")) },
                            enabled = !busy,
                            tone = InfinityPillTone.NEUTRAL,
                            icon = Icons.Rounded.Add,
                            compact = true,
                        )
                    }
                    if (supportFiles.isNotEmpty()) {
                        InfinityTagScrollRow(spacing = 6.dp) {
                            supportFiles.keys.forEach { rel ->
                                InfinityChip(
                                    label = rel,
                                    selected = true,
                                    onClick = { supportFiles = supportFiles - rel },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun readUriNameAndBytes(context: Context, uri: Uri): Pair<String, ByteArray> {
    var name = "script.py"
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) {
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0) c.getString(idx)?.takeIf { it.isNotBlank() }?.let { name = it }
        }
    }
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        ?: throw IllegalArgumentException("无法读取所选文件内容")
    require(bytes.size <= 2 * 1024 * 1024) { "文件大小超过 2 MiB 上限" }
    return name to bytes
}

private fun isTailnetAddress(endpoint: String): Boolean {
    val host = endpoint.substringAfter("://").substringBefore('/').substringBefore(':')
    return host.startsWith("100.") || host.endsWith(".ts.net")
}

@Composable
internal fun ComfyTemplateScreenContent(
    entry: ComfyTemplateState,
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    onLock: (List<String>) -> Unit = {},
    onPreflight: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    val template = entry.template
    val caps = template.channel().capabilities
    var order by remember(template.id, template.locked, caps.referenceOrder) { mutableStateOf(caps.referenceOrder) }
    val locale = LocalConfiguration.current.locales[0]
    MediaSettingsPage(template.name, onBack) {
        MediaOperationState(state, onRetry)
        InfinitySettingsSection(title = stringResource(R.string.media_settings_preflight)) {
            InfinitySettingsItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.media_settings_recheck),
                onClick = onPreflight,
                enabled = !state.busy,
                trailingContent = { PreflightChip(entry.preflight) },
            )
            val time = state.snapshot.preflightTimes[template.id] ?: entry.checkedAt
            MediaValue(
                stringResource(R.string.media_settings_preflight_time),
                time?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(it)) }
                    ?: stringResource(R.string.media_settings_time_unknown),
            )
            entry.preflight.missing.forEach { MediaNotice(mediaDiagnosticLabel(it), error = true) }
            entry.preflight.uncertain.forEach { MediaNotice(mediaDiagnosticLabel(it)) }
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_lock_section)) {
            TemplatesManagedInSession()
            InfinitySettingsRowLayout(
                icon = if (template.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                title = stringResource(if (template.locked) R.string.media_settings_locked else R.string.media_settings_unlocked),
            )
            if (!template.locked) {
                InfinitySettingsItem(
                    icon = Icons.Rounded.Lock,
                    title = stringResource(R.string.media_settings_lock),
                    onClick = { onLock(order) },
                    enabled = !state.busy,
                )
            }
        }
        val fixed = remember(template) { runCatching { com.promenar.infinity.data.mediagen.comfyPlanningSpecs(template) }.getOrNull() }
        val workflowSpecs = listOfNotNull(
            fixed?.width?.let { w -> fixed.height?.let { h -> "$w×$h" } },
            fixed?.frames?.let { stringResource(R.string.media_settings_frames, it) },
        )
        InfinitySettingsSection(title = stringResource(R.string.media_settings_role)) {
            MediaValue(
                stringResource(R.string.media_settings_role),
                stringResource(if (template.role == TemplateRole.DERIVE) R.string.media_settings_role_derive else R.string.media_settings_role_generate),
            )
            template.operation?.let { MediaValue(stringResource(R.string.media_settings_operation), it.label) }
        }
        MediaCapabilitiesSection(template.group, caps, workflowSpecs)
        if (template.params.isNotEmpty()) {
            InfinitySettingsSection(title = stringResource(R.string.media_settings_params)) {
                template.params.forEach { TemplateParamRow(it) }
            }
        }
        if (caps.maxImages > 0) {
            MediaValue(
                stringResource(R.string.media_settings_reference_input),
                referenceDescription(ReferenceInputDescription(caps.characterReferenceMode, caps.maxImages)),
            )
            InfinitySettingsSection(title = stringResource(R.string.media_settings_references)) {
                ReferenceOrderList(order, !template.locked && !state.busy) { order = moveMediaItemUp(order, it) }
            }
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_slots)) {
            template.slots.forEach { slot ->
                Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(slotKindLabel(slot.kind), Modifier.width(80.dp), style = MaterialTheme.typography.labelMedium)
                    Column(Modifier.weight(1f)) {
                        SelectionContainer {
                            Text("${slot.nodeId}.${slot.input}", style = MaterialTheme.typography.bodyMedium)
                        }
                        slot.uploadTargets.forEach { target ->
                            Text(
                                stringResource(R.string.media_settings_upload, "${target.nodeId}.${target.input}"),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun slotKindLabel(kind: SlotKind) = stringResource(
    when (kind) {
        SlotKind.PROMPT -> R.string.media_settings_slot_prompt
        SlotKind.REFERENCE_IMAGES -> R.string.media_settings_slot_images
        SlotKind.FIRST_FRAME -> R.string.media_settings_slot_first
        SlotKind.LAST_FRAME -> R.string.media_settings_slot_last
        SlotKind.REFERENCE_AUDIO -> R.string.media_settings_slot_audio
        SlotKind.WIDTH -> R.string.media_settings_slot_width
        SlotKind.HEIGHT -> R.string.media_settings_slot_height
        SlotKind.FRAMES -> R.string.media_settings_slot_frames
        SlotKind.SEED -> R.string.media_settings_slot_seed
        SlotKind.OUTPUT -> R.string.media_settings_slot_output
        SlotKind.INPUT_VIDEO -> R.string.media_settings_slot_input_video
        SlotKind.PARAM -> R.string.media_settings_slot_param
        SlotKind.DURATION -> R.string.media_settings_slot_duration
    },
)

@Composable
private fun TemplateParamRow(param: TemplateParam) {
    MediaValue(param.label, templateParamSegments(param))
}

@Composable
private fun templateParamSegments(param: TemplateParam): List<String> {
    val on = stringResource(R.string.media_settings_on)
    val off = stringResource(R.string.media_settings_off)
    val default = when (param.type) {
        TemplateParamType.BOOLEAN -> if (param.default.content.toBooleanStrictOrNull() == true) on else off
        else -> param.default.content
    }
    return listOfNotNull(
        stringResource(
            when (param.type) {
                TemplateParamType.ENUM -> R.string.media_settings_param_enum
                TemplateParamType.NUMBER -> if (param.integer) R.string.media_settings_param_integer else R.string.media_settings_param_number
                TemplateParamType.BOOLEAN -> R.string.media_settings_param_boolean
            },
        ),
        stringResource(R.string.media_settings_param_default, default),
        param.options.takeIf { param.type == TemplateParamType.ENUM && it.isNotEmpty() }?.joinToString("、"),
        param.min?.let { min -> param.max?.let { max -> stringResource(R.string.media_settings_param_range, paramNumber(min), paramNumber(max)) } }
            ?: param.min?.let { stringResource(R.string.media_settings_param_min, paramNumber(it)) }
            ?: param.max?.let { stringResource(R.string.media_settings_param_max, paramNumber(it)) },
    )
}

private fun paramNumber(value: Double): String =
    if (value % 1.0 == 0.0 && kotlin.math.abs(value) < 1e15) value.toLong().toString() else value.toString()

@Composable
internal fun ComfyHostFormScreenContent(
    host: ComfyHost?,
    hasCredential: Boolean,
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    onSave: (ComfyHostDraft, ComfyCredentialInput) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
) {
    var draft by remember(host?.id) {
        mutableStateOf(ComfyHostDraft(host?.name.orEmpty(), host?.baseUrl.orEmpty(), host?.authKind ?: ComfyAuthKind.NONE))
    }
    var token by remember(host?.id) { mutableStateOf("") }
    var username by remember(host?.id) { mutableStateOf("") }
    var password by remember(host?.id) { mutableStateOf("") }
    var submitted by remember(host?.id) { mutableStateOf(false) }
    val credential = ComfyCredentialInput(token, username, password)
    val error = validateComfyHost(draft, credential, hasCredential, host?.authKind)
    MediaSettingsPage(stringResource(if (host == null) R.string.media_settings_add_host else R.string.media_settings_edit_host), onBack) {
        MediaOperationState(state, onRetry)
        Column(Modifier.animateContentSize(InfinityMotion.contentSizeSpec()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaFormField(stringResource(R.string.media_settings_host_name), draft.name, { draft = draft.copy(name = it) }, !state.busy)
            MediaFormField(stringResource(R.string.media_settings_host_address), draft.address, { draft = draft.copy(address = it) }, !state.busy, KeyboardType.Uri)
            Text(stringResource(R.string.media_settings_auth), style = MaterialTheme.typography.labelMedium)
            InfinitySegmentedControl(
                segments = ComfyAuthKind.entries.map { InfinitySegment(it, authKindLabel(it)) },
                selected = draft.authKind,
                onSelect = { draft = draft.copy(authKind = it); token = ""; username = ""; password = "" },
                enabled = !state.busy,
            )
            AnimatedVisibility(
                visible = draft.authKind != ComfyAuthKind.NONE,
                enter = InfinityMotion.expandIn,
                exit = InfinityMotion.shrinkOut,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MediaNotice(stringResource(if (hasCredential && host?.authKind == draft.authKind) R.string.media_settings_credential_saved else R.string.media_settings_credential_missing))
                    AnimatedVisibility(
                        visible = draft.authKind == ComfyAuthKind.BASIC,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        MediaFormField(stringResource(R.string.media_settings_username), username, { username = it }, !state.busy)
                    }
                    OutlinedTextField(
                        value = if (draft.authKind == ComfyAuthKind.BEARER) token else password,
                        onValueChange = { if (draft.authKind == ComfyAuthKind.BEARER) token = it else password = it },
                        label = { Text(stringResource(if (draft.authKind == ComfyAuthKind.BEARER) R.string.media_settings_token else R.string.media_settings_password)) },
                        textStyle = LocalTextStyle.current.forInput(),
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = SecretKeyboardOptions,
                        singleLine = true,
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).excludeFromAutofill(),
                    )
                }
            }
            val lastFormError = rememberLastNonNull(error?.takeIf { submitted })
            AnimatedVisibility(
                visible = submitted && error != null,
                enter = InfinityMotion.expandIn,
                exit = InfinityMotion.shrinkOut,
            ) {
                lastFormError?.let { activeError ->
                    MediaNotice(formErrorLabel(activeError), error = true)
                }
            }
            MediaAction(
                stringResource(R.string.media_settings_save),
                {
                    submitted = true
                    if (error == null) onSave(draft, credential)
                },
                !state.busy,
                primary = true,
            )
        }
    }
}

@Composable
private fun TemplatesManagedInSession() {
    InfinitySettingsRowLayout(
        icon = Icons.Rounded.Forum,
        title = stringResource(R.string.media_settings_templates_in_session),
        titleMaxLines = 3,
        modifier = Modifier.testTag(MediaSettingsTags.TEMPLATES_IN_SESSION),
    )
}

object MediaSettingsTags {
    const val TEMPLATES_IN_SESSION = "media_settings_templates_in_session"
}
