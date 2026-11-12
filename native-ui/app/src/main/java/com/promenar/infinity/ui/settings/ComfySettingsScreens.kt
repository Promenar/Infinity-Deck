package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.InfinityConfirmDialog
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
                    stringResource(R.string.media_settings_templates_count, state.snapshot.templates.count { it.template.hostId == host.id }),
                    connectionLabel(state.connections[host.id] ?: ComfyConnection.UNKNOWN)).joinToString(" · "),
                subtitleMaxLines = Int.MAX_VALUE, titleMaxLines = 2,
                onClick = { onOpen(host.id) }, showChevron = true,
            )
        }
        MediaAction(stringResource(R.string.media_settings_add_host), onAdd, !state.busy)
        if (state.snapshot.hosts.isNotEmpty()) MediaAction(stringResource(R.string.media_settings_check_connection), onCheck, !state.busy)
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
    onConfigure: () -> Unit = {},
    onRetry: () -> Unit = {},
) {
    var deleting by remember(host.id) { mutableStateOf(false) }
    MediaSettingsPage("ComfyUI · ${host.name}", onBack) {
        MediaOperationState(state, onRetry)
        MediaValue(stringResource(R.string.media_settings_host_address), host.endpoint)
        MediaNotice(connectionLabel(state.connections[host.id] ?: ComfyConnection.UNKNOWN))
        MediaAction(stringResource(R.string.media_settings_check_connection), onCheck, !state.busy)
        InfinitySettingsSection(title = stringResource(R.string.media_settings_templates)) {
            val templates = state.snapshot.templates.filter { it.template.hostId == host.id }
            if (templates.isEmpty()) MediaNotice(stringResource(R.string.media_settings_no_templates))
            templates.forEach { entry ->
                val template = entry.template
                val channel = template.channel()
                val range = referenceInputDescription(channel.capabilities)?.let { referenceDescription(it) }
                val subtitle = (listOf(mediaGroupLabel(template.group)) + channel.capabilities.modes.sortedBy { it.ordinal }.map { generationModeLabel(it) } +
                    listOfNotNull(range)).joinToString(" · ")
                InfinitySettingsItem(icon = mediaGroupIcon(template.group), title = template.name, subtitle = subtitle,
                    subtitleMaxLines = Int.MAX_VALUE, titleMaxLines = 2, onClick = { onOpenTemplate(template.id) },
                    subtitleContent = {
                        Column {
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(preflightLabel(entry.preflight), style = MaterialTheme.typography.labelMedium,
                                color = if (entry.preflight.status == PreflightStatus.PASS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                        }
                    }, showChevron = true)
            }
            MediaAction(stringResource(R.string.media_settings_recheck), onPreflight, templates.isNotEmpty() && !state.busy)
        }
        MediaAction(stringResource(R.string.media_settings_configure), onConfigure, !state.busy)
        MediaAction(stringResource(R.string.media_settings_edit_host), onEdit, !state.busy)
        MediaAction(stringResource(R.string.media_settings_delete), { deleting = true }, !state.busy)
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
    onUnlock: () -> Unit = {},
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
            MediaNotice(preflightLabel(entry.preflight), error = entry.preflight.status != PreflightStatus.PASS)
            val time = state.snapshot.preflightTimes[template.id]
            MediaValue(stringResource(R.string.media_settings_preflight_time), time?.let { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, locale).format(Date(it)) }
                ?: stringResource(R.string.media_settings_time_unknown))
            entry.preflight.missing.forEach { MediaNotice(mediaDiagnosticLabel(it), error = true) }
            entry.preflight.uncertain.forEach { MediaNotice(mediaDiagnosticLabel(it)) }
            MediaAction(stringResource(R.string.media_settings_recheck), onPreflight, !state.busy)
            MediaNotice(stringResource(if (template.locked) R.string.media_settings_locked else R.string.media_settings_unlocked))
            MediaAction(stringResource(if (template.locked) R.string.media_settings_unlock else R.string.media_settings_lock),
                { if (template.locked) onUnlock() else onLock(order) }, !state.busy)
        }
        MediaCapabilitiesSection(template.group, caps)
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
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine = true, enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
            }
            if (submitted && error != null) MediaNotice(formErrorLabel(error), error = true)
            MediaAction(stringResource(R.string.media_settings_save), {
                submitted = true
                if (error == null) onSave(draft, credential)
            }, !state.busy)
        }
    }
}
