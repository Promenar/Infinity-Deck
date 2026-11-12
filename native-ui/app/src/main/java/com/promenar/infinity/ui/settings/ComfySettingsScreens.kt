package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.excludeFromAutofill
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import java.text.DateFormat
import java.util.Date

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
                title = "ComfyUI · ${host.name}",
                subtitle = listOf(host.endpoint,
                    stringResource(R.string.media_settings_templates_count, state.snapshot.templates.count { it.template.hostId == host.id })).joinToString(" · "),
                subtitleMaxLines = 2, titleMaxLines = 2,
                onClick = { onOpen(host.id) },
                trailingContent = {
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        ConnectionChip(state.connections[host.id] ?: ComfyConnection.UNKNOWN)
                        Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        }
        InfinitySettingsItem(icon = Icons.Rounded.Add, title = stringResource(R.string.media_settings_add_host), onClick = onAdd, enabled = !state.busy)
        if (state.snapshot.hosts.isNotEmpty()) InfinitySettingsItem(icon = Icons.Rounded.NetworkCheck,
            title = stringResource(R.string.media_settings_check_connection), onClick = onCheck, enabled = !state.busy)
    }
}

@Composable
internal fun ComfyHostScreenContent(
    host: ComfyHost,
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    onEdit: () -> Unit = {},
    onDelete: () -> Unit = {},
    onOpenTemplate: (String) -> Unit = {},
    onPreflight: () -> Unit = {},
    onCheck: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    var deleting by remember(host.id) { mutableStateOf(false) }
    MediaSettingsPage("ComfyUI · ${host.name}", onBack) {
        MediaOperationState(state, onRetry)
        InfinitySettingsSection(title = stringResource(R.string.media_settings_connection)) {
            MediaValue(stringResource(R.string.media_settings_host_address), host.endpoint)
            InfinitySettingsItem(
                icon = Icons.Rounded.NetworkCheck,
                title = stringResource(R.string.media_settings_check_connection),
                onClick = onCheck, enabled = !state.busy,
                trailingContent = { ConnectionChip(state.connections[host.id] ?: ComfyConnection.UNKNOWN) },
            )
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_templates)) {
            val templates = state.snapshot.templates.filter { it.template.hostId == host.id }
            TemplatesManagedInSession()
            if (templates.isEmpty()) MediaNotice(stringResource(R.string.media_settings_no_templates))
            templates.forEach { entry ->
                val template = entry.template
                val channel = template.channel()
                val range = referenceInputDescription(channel.capabilities)?.let { referenceDescription(it) }
                val subtitle = (listOf(mediaGroupLabel(template.group)) + channel.capabilities.modes.sortedBy { it.ordinal }.map { generationModeLabel(it) } +
                    listOfNotNull(range)).joinToString(" · ")
                InfinitySettingsItem(icon = mediaGroupIcon(template.group), title = template.name, subtitle = subtitle,
                    subtitleMaxLines = 2, titleMaxLines = 2, onClick = { onOpenTemplate(template.id) },
                    trailingContent = { PreflightChip(entry.preflight) })
            }
            if (templates.isNotEmpty()) InfinitySettingsItem(icon = Icons.Rounded.Refresh, title = stringResource(R.string.media_settings_recheck), onClick = onPreflight, enabled = !state.busy)
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_host_actions)) {
            InfinitySettingsItem(icon = Icons.Rounded.Edit, title = stringResource(R.string.media_settings_edit_host), onClick = onEdit, enabled = !state.busy, showChevron = true)
            InfinitySettingsItem(icon = Icons.Rounded.Delete, title = stringResource(R.string.media_settings_delete), onClick = { deleting = true }, enabled = !state.busy,
                titleColor = MaterialTheme.colorScheme.error)
        }
    }
    if (deleting) InfinityConfirmDialog(
        title = stringResource(R.string.media_settings_delete_host_title), message = stringResource(R.string.media_settings_delete_host_message),
        confirmText = stringResource(R.string.media_settings_delete), cancelText = stringResource(R.string.media_settings_cancel),
        isDestructive = true, confirmEnabled = !state.busy,
        onConfirm = { deleting = false; onDelete() }, onCancel = { deleting = false },
    )
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
            InfinitySettingsItem(icon = Icons.Rounded.Refresh, title = stringResource(R.string.media_settings_recheck), onClick = onPreflight, enabled = !state.busy, trailingContent = { PreflightChip(entry.preflight) })
            val time = state.snapshot.preflightTimes[template.id] ?: entry.checkedAt
            MediaValue(stringResource(R.string.media_settings_preflight_time), time?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(it)) }
                ?: stringResource(R.string.media_settings_time_unknown))
            entry.preflight.missing.forEach { MediaNotice(mediaDiagnosticLabel(it), error = true) }
            entry.preflight.uncertain.forEach { MediaNotice(mediaDiagnosticLabel(it)) }
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_lock_section)) {
            TemplatesManagedInSession()
            com.promenar.infinity.ui.common.InfinitySettingsRowLayout(
                icon = if (template.locked) Icons.Rounded.Lock else Icons.Rounded.LockOpen,
                title = stringResource(if (template.locked) R.string.media_settings_locked else R.string.media_settings_unlocked),
            )
            // 已锁定的模板由智能体在会话里经审批卡修改；未锁定的模板仍可在这里按当前参考顺序锁定。
            if (!template.locked) InfinitySettingsItem(
                icon = Icons.Rounded.Lock,
                title = stringResource(R.string.media_settings_lock),
                onClick = { onLock(order) }, enabled = !state.busy,
            )
        }
        // 模板未声明规格时，显示锁定工作流里写定的画幅与帧数。
        val fixed = remember(template) { runCatching { com.promenar.infinity.data.mediagen.comfyPlanningSpecs(template) }.getOrNull() }
        val workflowSpecs = listOfNotNull(fixed?.width?.let { w -> fixed.height?.let { h -> "$w×$h" } },
            fixed?.frames?.let { stringResource(R.string.media_settings_frames, it) }).joinToString(" · ").ifEmpty { null }
        MediaCapabilitiesSection(template.group, caps, workflowSpecs)
        if (caps.maxImages > 0) {
            MediaValue(stringResource(R.string.media_settings_reference_input), referenceDescription(ReferenceInputDescription(CharacterReferenceMode.SEPARATE, caps.maxImages)))
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
                            Text("${slot.nodeId}.${if (slot.kind == SlotKind.OUTPUT) "outputs" else "inputs"}.${slot.names().first()}" + if (slot.capacity > 1) "…${slot.names().last()}" else "",
                                style = MaterialTheme.typography.bodyMedium)
                        }
                        if (slot.capacity > 1) Text(stringResource(R.string.media_settings_variable, slot.capacity), style = MaterialTheme.typography.bodySmall)
                        slot.uploadTargets.forEach { target ->
                            Text(stringResource(R.string.media_settings_upload, "${target.nodeId}.inputs.${target.input}"), style = MaterialTheme.typography.bodySmall)
                        }
                        when (slot.kind) {
                            SlotKind.WIDTH, SlotKind.HEIGHT -> Text(stringResource(R.string.media_settings_alignment), style = MaterialTheme.typography.bodySmall)
                            SlotKind.FRAMES -> {
                                Text(stringResource(R.string.media_settings_frame_constraint, template.frameBase, template.frameStep), style = MaterialTheme.typography.bodySmall)
                                template.maxFrames?.let { Text(stringResource(R.string.media_settings_max_frames, it), style = MaterialTheme.typography.bodySmall) }
                            }
                            SlotKind.OUTPUT -> slot.outputKind?.let { Text(mediaGroupLabel(when (it) {
                                InputKind.IMAGE -> MediaGroup.IMAGE; InputKind.VIDEO -> MediaGroup.VIDEO; InputKind.AUDIO -> MediaGroup.AUDIO
                            }), style = MaterialTheme.typography.bodySmall) }
                            else -> Unit
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun slotKindLabel(kind: SlotKind) = stringResource(when (kind) {
    SlotKind.PROMPT -> R.string.media_settings_slot_prompt; SlotKind.REFERENCE_IMAGES -> R.string.media_settings_slot_images
    SlotKind.FIRST_FRAME -> R.string.media_settings_slot_first; SlotKind.LAST_FRAME -> R.string.media_settings_slot_last
    SlotKind.REFERENCE_AUDIO -> R.string.media_settings_slot_audio; SlotKind.WIDTH -> R.string.media_settings_slot_width
    SlotKind.HEIGHT -> R.string.media_settings_slot_height; SlotKind.FRAMES -> R.string.media_settings_slot_frames
    SlotKind.SEED -> R.string.media_settings_slot_seed; SlotKind.OUTPUT -> R.string.media_settings_slot_output
})

@Composable
internal fun ComfyHostFormScreenContent(
    host: ComfyHost?, hasCredential: Boolean, state: MediaSettingsUiState,
    onBack: () -> Unit = {}, onSave: (ComfyHostDraft, ComfyCredentialInput) -> Unit = { _, _ -> }, onRetry: () -> Unit = {},
) {
    var draft by remember(host?.id) { mutableStateOf(ComfyHostDraft(host?.name.orEmpty(), host?.baseUrl.orEmpty(), host?.authKind ?: ComfyAuthKind.NONE)) }
    var token by remember(host?.id) { mutableStateOf("") }
    var username by remember(host?.id) { mutableStateOf("") }
    var password by remember(host?.id) { mutableStateOf("") }
    var submitted by remember(host?.id) { mutableStateOf(false) }
    val credential = ComfyCredentialInput(token, username, password)
    val error = validateComfyHost(draft, credential, hasCredential, host?.authKind)
    MediaSettingsPage(stringResource(if (host == null) R.string.media_settings_add_host else R.string.media_settings_edit_host), onBack) {
        MediaOperationState(state, onRetry)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaFormField(stringResource(R.string.media_settings_host_name), draft.name, { draft = draft.copy(name = it) }, !state.busy)
            MediaFormField(stringResource(R.string.media_settings_host_address), draft.address, { draft = draft.copy(address = it) }, !state.busy, KeyboardType.Uri)
            Text(stringResource(R.string.media_settings_auth), style = MaterialTheme.typography.labelMedium)
            InfinitySegmentedControl(segments = ComfyAuthKind.entries.map { InfinitySegment(it, authKindLabel(it)) }, selected = draft.authKind,
                onSelect = { draft = draft.copy(authKind = it); token = ""; username = ""; password = "" }, enabled = !state.busy)
            if (draft.authKind != ComfyAuthKind.NONE) {
                MediaNotice(stringResource(if (hasCredential && host?.authKind == draft.authKind) R.string.media_settings_credential_saved else R.string.media_settings_credential_missing))
                if (draft.authKind == ComfyAuthKind.BASIC) {
                    MediaFormField(stringResource(R.string.media_settings_username), username, { username = it }, !state.busy)
                }
                OutlinedTextField(value = if (draft.authKind == ComfyAuthKind.BEARER) token else password,
                    onValueChange = { if (draft.authKind == ComfyAuthKind.BEARER) token = it else password = it },
                    label = { Text(stringResource(if (draft.authKind == ComfyAuthKind.BEARER) R.string.media_settings_token else R.string.media_settings_password)) },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = com.promenar.infinity.ui.common.SecretKeyboardOptions,
                    singleLine = true, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).excludeFromAutofill())
            }
            if (submitted && error != null) MediaNotice(formErrorLabel(error), error = true)
            MediaAction(stringResource(R.string.media_settings_save), {
                submitted = true
                if (error == null) onSave(draft, credential)
            }, !state.busy)
        }
    }
}

/** 模板由智能体在会话中管理的说明行：主机页与模板详情共用。 */
@Composable
private fun TemplatesManagedInSession() {
    com.promenar.infinity.ui.common.InfinitySettingsRowLayout(
        icon = Icons.Rounded.Forum,
        title = stringResource(R.string.media_settings_templates_in_session),
        titleMaxLines = 3,
        modifier = Modifier.testTag(MediaSettingsTags.TEMPLATES_IN_SESSION),
    )
}

object MediaSettingsTags {
    const val TEMPLATES_IN_SESSION = "media_settings_templates_in_session"
}
