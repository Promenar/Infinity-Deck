package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.cloud.BillingUnit
import com.promenar.infinity.data.mediagen.cloud.MediaTariff
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.forInput

@Composable
internal fun MediaModelDetailScreenContent(
    model: ModelInfo,
    state: MediaSettingsUiState,
    onBack: () -> Unit = {},
    onMode: (CharacterReferenceMode) -> Unit = {},
    onReferenceUp: (String) -> Unit = {},
    onSaveTariff: (MediaTariffDraft, () -> Unit) -> Unit = { _, _ -> },
    onRetry: () -> Unit = {},
) {
    val settings = model.effectiveMediaSettings() ?: return
    val caps = settings.capabilities
    var editing by remember(model.id) { mutableStateOf(false) }
    var draft by remember(model.id, settings.tariff) { mutableStateOf(MediaTariffDraft.from(settings.tariff)) }
    MediaSettingsPage(model.name, onBack) {
        MediaOperationState(state, onRetry)
        MediaValue(stringResource(R.string.media_settings_providers), model.providerName)
        MediaCapabilitiesSection(settings.group, caps)
        if (caps.maxImages > 0) {
            InfinitySettingsSection(title = stringResource(R.string.media_settings_reference_input)) {
                InfinitySegmentedControl(
                    segments = CharacterReferenceMode.entries.map { InfinitySegment(it, referenceModeLabel(it)) },
                    selected = caps.characterReferenceMode,
                    onSelect = onMode,
                    enabled = !state.busy,
                    fillWidth = true,
                    contentPadding = PaddingValues(horizontal = 12.dp),
                )
            }
            if (caps.characterReferenceMode == CharacterReferenceMode.SEPARATE) {
                InfinitySettingsSection(title = stringResource(R.string.media_settings_references)) {
                    ReferenceOrderList(caps.referenceOrder, !state.busy, onReferenceUp)
                }
            }
        }
        InfinitySettingsSection(title = stringResource(R.string.media_settings_billing)) {
            if (editing) {
                MediaTariffEditor(draft, !state.busy, { draft = it })
                draft.validationError()?.let { MediaNotice(formErrorLabel(it), error = true) }
                MediaAction(stringResource(R.string.media_settings_save),
                    onClick = { onSaveTariff(draft) { editing = false } }, enabled = !state.busy && draft.validationError() == null)
                MediaAction(stringResource(R.string.media_settings_cancel), { editing = false }, !state.busy)
            } else {
                MediaTariffValues(settings.tariff)
                MediaAction(stringResource(R.string.media_settings_edit), { draft = MediaTariffDraft.from(settings.tariff); editing = true }, !state.busy)
            }
        }
    }
}

@Composable
internal fun MediaCapabilitiesSection(group: MediaGroup, caps: MediaCapabilities, workflowSpecs: List<String> = emptyList()) {
    InfinitySettingsSection(title = stringResource(R.string.media_settings_capabilities)) {
        MediaValue(stringResource(R.string.media_settings_type), mediaGroupLabel(group))
        MediaValue(stringResource(R.string.media_settings_modes), caps.modes.sortedBy { it.ordinal }.map { generationModeLabel(it) })
        MediaValue(stringResource(R.string.media_settings_limits), stringResource(R.string.media_settings_limits_value, caps.maxImages, caps.maxVideos, caps.maxAudio))
        MediaValue(stringResource(R.string.media_settings_native_audio), stringResource(if (caps.nativeAudio) R.string.media_settings_supported else R.string.media_settings_unsupported))
        val specs = caps.resolutions + if (caps.minDurationSeconds != null || caps.maxDurationSeconds != null) {
            listOf(stringResource(R.string.media_settings_duration, caps.minDurationSeconds?.toString() ?: "—", caps.maxDurationSeconds?.toString() ?: "—"))
        } else emptyList()
        MediaValue(stringResource(R.string.media_settings_specs), specs.ifEmpty { workflowSpecs.ifEmpty { listOf("—") } })
        MediaValue(stringResource(R.string.media_settings_prompt_format), stringResource(when (caps.promptFormat) {
            PromptFormat.H3 -> R.string.media_settings_prompt_h3
            PromptFormat.SEEDANCE -> R.string.media_settings_prompt_seedance
            PromptFormat.GENERIC -> R.string.media_settings_prompt_generic
        }))
    }
}

@Composable
private fun MediaTariffValues(tariff: MediaTariff?) {
    if (tariff == null) {
        MediaNotice(stringResource(R.string.media_settings_tariff_unknown))
    } else {
        MediaValue(stringResource(R.string.media_settings_currency), tariff.currency)
        tariff.rates.forEach { rate ->
            MediaValue(billingUnitLabel(rate.unit), stringResource(R.string.media_settings_rate_value, rate.amount, rate.perUnits.toString(), rate.freeUnits.toString()))
        }
    }
}

@Composable
private fun MediaTariffEditor(draft: MediaTariffDraft, enabled: Boolean, onChange: (MediaTariffDraft) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaFormField(stringResource(R.string.media_settings_currency), draft.currency, { onChange(draft.copy(currency = it)) }, enabled)
        draft.rates.forEachIndexed { index, rate ->
            fun change(value: MediaRateDraft) = onChange(draft.copy(rates = draft.rates.toMutableList().also { it[index] = value }))
            Text(stringResource(R.string.media_settings_billing_unit), style = MaterialTheme.typography.labelMedium)
            InfinitySegmentedControl(
                segments = BillingUnit.entries.map { InfinitySegment(it, billingUnitLabel(it)) },
                selected = rate.unit, onSelect = { change(rate.copy(unit = it)) }, enabled = enabled,
            )
            MediaFormField(stringResource(R.string.media_settings_amount), rate.amount, { change(rate.copy(amount = it)) }, enabled, KeyboardType.Decimal)
            MediaFormField(stringResource(R.string.media_settings_per_units), rate.perUnits, { change(rate.copy(perUnits = it)) }, enabled, KeyboardType.Number)
            MediaFormField(stringResource(R.string.media_settings_free_units), rate.freeUnits, { change(rate.copy(freeUnits = it)) }, enabled, KeyboardType.Number)
            MediaAction(stringResource(R.string.media_settings_remove_rate), { onChange(draft.copy(rates = draft.rates.filterIndexed { i, _ -> i != index })) }, enabled)
        }
        val nextUnit = BillingUnit.entries.firstOrNull { unit -> draft.rates.none { it.unit == unit } }
        if (nextUnit != null) MediaAction(stringResource(R.string.media_settings_add_rate),
            { onChange(draft.copy(rates = draft.rates + MediaRateDraft(unit = nextUnit))) }, enabled)
    }
}

@Composable
internal fun MediaFormField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, keyboardType: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, enabled = enabled,
        textStyle = LocalTextStyle.current.forInput(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType), modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp))
}
