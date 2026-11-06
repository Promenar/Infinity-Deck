package com.promenar.infinity.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R

@Composable
fun InfinityValueRow(
    title: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leadingSpacer: Boolean = false,
    enabled: Boolean = true,
) {
    InfinitySettingsRowLayout(
        title = title, subtitle = subtitle, icon = icon, leadingSpacer = leadingSpacer, enabled = enabled,
        modifier = modifier.semantics { stateDescription = value }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        trailingMaxWidthFraction = 0.5f,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 取值放不下时横滑查看、右侧渐隐，不用省略号。
            InfinityScrollingLine(Modifier.weight(1f, fill = false)) {
                Text(value, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ChevronRight, null, Modifier.size(18.dp), MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 仅持有弹层开合状态，数值持久化仍由调用方完成。 */
@Composable
fun InfinityNumericSetting(
    title: String,
    value: Number,
    spec: NumericSettingSpec,
    onValueChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    leadingSpacer: Boolean = false,
    enabled: Boolean = true,
    description: String? = null,
) {
    var show by rememberSaveable(title) { mutableStateOf(false) }
    InfinityValueRow(title = title, value = numericValueLabel(spec, value.toDouble()), onClick = { show = true },
        modifier = modifier, subtitle = subtitle, icon = icon, leadingSpacer = leadingSpacer, enabled = enabled)
    InfinityValuePickerSheet(show = show, title = title, value = value.toDouble(), spec = spec,
        onValueChange = onValueChange, onDismiss = { show = false }, description = description)
}

@Composable
internal fun numericValueLabel(spec: NumericSettingSpec, value: Double): String {
    val labelRes = spec.selectedPreset(value)?.labelRes
    return when {
        labelRes != null -> stringResource(labelRes)
        spec.isUnlimited(value) -> stringResource(R.string.numeric_unlimited)
        else -> spec.format(value)
    }
}
