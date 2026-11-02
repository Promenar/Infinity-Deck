package com.promenar.infinity.ui.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.forReading
import kotlin.math.roundToInt

@Composable
fun InfinityValuePickerSheet(
    show: Boolean,
    title: String,
    value: Double,
    spec: NumericSettingSpec,
    onValueChange: (Double) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
) {
    InfinityBottomSheet(show = show, onDismiss = onDismiss) {
        InfinityValuePickerContent(title, value, spec, onValueChange, onDismiss, description = description)
    }
}

@Composable
fun InfinityValuePickerContent(
    title: String,
    value: Double,
    spec: NumericSettingSpec,
    onValueChange: (Double) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    initialCustomExpanded: Boolean = false,
    initialCustomInput: String? = null,
) {
    val haptics = LocalInfinityHaptics.current
    var customExpanded by rememberSaveable(title) { mutableStateOf(initialCustomExpanded) }
    val editableValue = value.takeIf { it in spec.min..spec.max }
        ?: spec.defaultValue.takeIf { it in spec.min..spec.max } ?: spec.min
    var customInput by rememberSaveable(title) { mutableStateOf(initialCustomInput ?: spec.format(editableValue).removeSuffix(spec.unit).trim()) }
    var attempted by rememberSaveable(title) { mutableStateOf(initialCustomInput != null) }
    var draft by rememberSaveable(value) { mutableStateOf(value) }
    val invalid = customExpanded && attempted && spec.parseCustom(customInput).isFailure
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (description != null) {
            Text(description, style = MaterialTheme.typography.bodySmall.forReading(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        if (spec.kind == NumericSettingKind.Discrete) {
            Column(Modifier.selectableGroup()) {
                spec.effectivePresets.forEach { preset ->
                    val label = numericValueLabel(spec, preset.value)
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = spec.selectedPreset(value)?.value == preset.value, role = Role.RadioButton,
                            onClick = {
                                haptics.select(spec.selectedPreset(value)?.value == preset.value)
                                onValueChange(preset.value); onDismiss()
                            })
                        .padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (spec.isDefault(preset.value)) stringResource(R.string.numeric_default_value, label) else label,
                            style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"),
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (spec.selectedPreset(value)?.value == preset.value) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            InfinitySettingsItem(title = stringResource(R.string.numeric_custom), horizontalPadding = 0.dp,
                onClick = { customExpanded = true }, modifier = Modifier.testTag("numeric_custom"))
            if (customExpanded) {
                OutlinedTextField(value = customInput, onValueChange = { customInput = it; attempted = true },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum").forInput(),
                    label = { Text(title) }, suffix = if (spec.unit.isBlank()) null else ({ Text(spec.unit) }),
                    isError = invalid, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = if (spec.decimalPlaces == 0) KeyboardType.Number else KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("numeric_custom_input"))
                Text(stringResource(R.string.numeric_range, spec.format(spec.min), spec.format(spec.max)),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (invalid) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).testTag(if (invalid) "numeric_custom_error" else "numeric_custom_range"))
                Button(onClick = {
                    attempted = true
                    spec.parseCustom(customInput).onSuccess { onValueChange(it); onDismiss() }
                }, modifier = Modifier.align(Alignment.End).testTag("numeric_done")) { Text(stringResource(R.string.numeric_done)) }
            }
        } else {
            Text(spec.format(draft), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 16.dp))
            val intervals = if (spec.step > 0) ((spec.max - spec.min) / spec.step).roundToInt() else 0
            InfinitySlider(value = draft.toFloat(), onValueChange = { draft = spec.clamp(it.toDouble()) },
                valueRange = spec.min.toFloat()..spec.max.toFloat(), steps = (intervals - 1).coerceAtLeast(0),
                onValueChangeFinished = { onValueChange(draft) }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(spec.format(spec.min), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(spec.format(spec.max), style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            InfinitySegmentedControl(
                segments = spec.effectivePresets.map { preset ->
                    val label = numericValueLabel(spec, preset.value)
                    InfinitySegment(preset.value, if (spec.isDefault(preset.value)) stringResource(R.string.numeric_default_value, label) else label)
                },
                selected = spec.selectedPreset(draft)?.value,
                onSelect = { draft = it; onValueChange(draft) },
                modifier = Modifier.padding(top = 8.dp),
            )
            InfinityPillButton(
                text = stringResource(R.string.numeric_restore_default),
                onClick = { draft = spec.defaultValue; onValueChange(draft) },
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            )
        }
    }
}
