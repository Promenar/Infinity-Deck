package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.cloud.BillingUnit
import com.promenar.infinity.data.mediagen.comfy.ComfyAuthKind
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout

@Composable
internal fun MediaSettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    InfinitySettingsPageLayout(title = title, onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(padding), content = content)
    }
}

@Composable
internal fun MediaValue(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun MediaAction(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun MediaNotice(text: String, error: Boolean = false) {
    Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun MediaOperationState(state: MediaSettingsUiState, onRetry: () -> Unit) {
    if (!state.loaded && !state.failed) MediaNotice(stringResource(R.string.media_settings_loading))
    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    if (state.failed) {
        MediaNotice(stringResource(R.string.media_settings_failed), error = true)
        MediaAction(stringResource(R.string.media_settings_retry), onRetry, !state.busy)
    }
}

@Composable
internal fun ReferenceOrderList(order: List<String>, enabled: Boolean, onMoveUp: (String) -> Unit) {
    order.forEachIndexed { index, name ->
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}", Modifier.widthIn(min = 28.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(referenceSlotLabel(name), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { onMoveUp(name) }, enabled = enabled && index > 0, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.media_settings_move_up, referenceSlotLabel(name)))
            }
        }
    }
}

@Composable
internal fun referenceSlotLabel(name: String): String = when (name) {
    "front_full_body" -> stringResource(R.string.media_settings_front_full_body)
    "side_full_body" -> stringResource(R.string.media_settings_side_full_body)
    "back_full_body" -> stringResource(R.string.media_settings_back_full_body)
    "face_close_up" -> stringResource(R.string.media_settings_face_close_up)
    else -> name
}

@Composable
internal fun mediaGroupLabel(group: MediaGroup): String = stringResource(when (group) {
    MediaGroup.IMAGE -> R.string.media_settings_image; MediaGroup.VIDEO -> R.string.media_settings_video; MediaGroup.AUDIO -> R.string.media_settings_audio
})
internal fun mediaGroupIcon(group: MediaGroup): ImageVector = when (group) {
    MediaGroup.IMAGE -> Icons.Rounded.Image; MediaGroup.VIDEO -> Icons.Rounded.Movie; MediaGroup.AUDIO -> Icons.Rounded.MusicNote
}
@Composable
internal fun generationModeLabel(mode: GenerationMode): String = stringResource(when (mode) {
    GenerationMode.TEXT -> R.string.media_settings_mode_text; GenerationMode.REFERENCE -> R.string.media_settings_mode_reference
    GenerationMode.FIRST_FRAME -> R.string.media_settings_mode_first; GenerationMode.FIRST_LAST_FRAME -> R.string.media_settings_mode_first_last
    GenerationMode.SPEECH -> R.string.media_settings_mode_speech; GenerationMode.MUSIC -> R.string.media_settings_mode_music
})
@Composable
internal fun referenceModeLabel(mode: CharacterReferenceMode) = stringResource(if (mode == CharacterReferenceMode.COMPOSITE) R.string.media_settings_composite else R.string.media_settings_separate)
@Composable
internal fun referenceDescription(value: ReferenceInputDescription) = stringResource(R.string.media_settings_reference_limit, referenceModeLabel(value.mode), value.limit)
@Composable
internal fun channelDescription(row: MediaChannelRow): String = (listOf(row.source).filter { it.isNotBlank() } +
    row.channel.capabilities.modes.sortedBy { it.ordinal }.map { generationModeLabel(it) } +
    listOfNotNull(row.references?.let { referenceDescription(it) })).joinToString(" · ")
@Composable
internal fun preflightLabel(result: PreflightResult): String {
    val label = templateStatusLabel(result)
    return when (label.status) {
        PreflightStatus.PASS -> stringResource(R.string.media_settings_pass)
        PreflightStatus.MISSING -> stringResource(R.string.media_settings_missing_items, label.missingCount)
        PreflightStatus.REVIEW -> stringResource(R.string.media_settings_review)
    }
}
@Composable
internal fun connectionLabel(value: ComfyConnection) = stringResource(when (value) {
    ComfyConnection.UNKNOWN -> R.string.media_settings_unchecked; ComfyConnection.CHECKING -> R.string.media_settings_checking
    ComfyConnection.ONLINE -> R.string.media_settings_online; ComfyConnection.OFFLINE -> R.string.media_settings_offline
})
@Composable
internal fun authKindLabel(kind: ComfyAuthKind) = stringResource(when (kind) {
    ComfyAuthKind.NONE -> R.string.media_settings_auth_none; ComfyAuthKind.BEARER -> R.string.media_settings_auth_bearer; ComfyAuthKind.BASIC -> R.string.media_settings_auth_basic
})
@Composable
internal fun billingUnitLabel(unit: BillingUnit): String = stringResource(when (unit) {
    BillingUnit.TOKENS -> R.string.media_settings_unit_tokens; BillingUnit.OUTPUT_SECONDS -> R.string.media_settings_unit_output_seconds
    BillingUnit.INPUT_IMAGES -> R.string.media_settings_unit_input_images; BillingUnit.INPUT_VIDEO_SECONDS -> R.string.media_settings_unit_input_video
    BillingUnit.INPUT_AUDIO_SECONDS -> R.string.media_settings_unit_input_audio; BillingUnit.CHARACTERS -> R.string.media_settings_unit_characters; BillingUnit.TASK -> R.string.media_settings_unit_task
})
@Composable
internal fun formErrorLabel(error: MediaFormError) = stringResource(when (error) {
    MediaFormError.NAME -> R.string.media_settings_error_name; MediaFormError.ADDRESS -> R.string.media_settings_error_address
    MediaFormError.CREDENTIAL -> R.string.media_settings_error_credential; MediaFormError.CURRENCY -> R.string.media_settings_error_currency
    MediaFormError.AMOUNT -> R.string.media_settings_error_amount; MediaFormError.PER_UNITS -> R.string.media_settings_error_per_units
    MediaFormError.FREE_UNITS -> R.string.media_settings_error_free_units; MediaFormError.DUPLICATE_UNIT -> R.string.media_settings_error_duplicate_unit
})

@Composable
internal fun providerMediaCountLabel(count: ProviderMediaCount): String = listOf(
    "${stringResource(R.string.media_settings_chat)} ${count.chat}",
    "${stringResource(R.string.media_settings_video)} ${count.video}",
    "${stringResource(R.string.media_settings_speech)} ${count.speech}",
    "${stringResource(R.string.media_settings_image)} ${count.image}",
).joinToString(" · ")
