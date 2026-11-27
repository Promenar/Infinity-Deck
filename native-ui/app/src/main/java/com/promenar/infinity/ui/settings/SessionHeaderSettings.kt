package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.SessionHeaderMode
import com.promenar.infinity.data.model.SessionHeaderPolicy
import com.promenar.infinity.ui.common.InfinityFieldRow
import com.promenar.infinity.ui.common.InfinityGroupDivider
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.theme.InfinitySpacing

/** 提供商表单“高级”分组：会话请求头开关（带一行说明）与请求头名称。 */
@Composable
internal fun ProviderSessionHeaderFields(enabled: Boolean, name: String, onEnabled: (Boolean) -> Unit, onName: (String) -> Unit) {
    InfinitySettingsSection(
        title = stringResource(R.string.provider_form_group_advanced),
        footer = stringResource(R.string.provider_form_session_header_footer),
        typographicFooter = true,
    ) {
        SettingsToggle(
            title = stringResource(R.string.session_header_title),
            description = stringResource(R.string.provider_form_session_header_description),
            checked = enabled,
            onCheckedChange = onEnabled,
        )
        if (enabled || name.isNotEmpty() && !SessionHeaderPolicy.isValidName(name)) {
            InfinityGroupDivider()
            InfinityFieldRow(
                label = stringResource(R.string.session_header_name),
                value = name,
                onValueChange = onName,
                monospace = true,
                error = if (!SessionHeaderPolicy.isValidName(name)) stringResource(R.string.session_header_name_error) else null,
            )
        }
    }
}

/** 模型编辑页“会话请求头”分组：跟随提供商 / 开启 / 关闭三档页内分段选择。 */
@Composable
internal fun ModelSessionHeaderSetting(mode: SessionHeaderMode, onMode: (SessionHeaderMode) -> Unit, modifier: Modifier = Modifier) {
    InfinitySettingsSection(
        title = stringResource(R.string.session_header_title),
        footer = stringResource(R.string.session_header_model_help),
        typographicFooter = true,
        modifier = modifier,
    ) {
        InfinitySegmentedControl(
            segments = SessionHeaderMode.entries.map { option ->
                InfinitySegment(option, stringResource(option.labelResource()), tag = "session_header_mode:${option.name}")
            },
            selected = mode,
            onSelect = onMode,
            fillWidth = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = InfinitySpacing.Medium, vertical = 8.dp)
                .testTag("model_session_header_mode"),
        )
    }
}

private fun SessionHeaderMode.labelResource(): Int = when (this) {
    SessionHeaderMode.INHERIT -> R.string.session_header_inherit
    SessionHeaderMode.ON -> R.string.session_header_on
    SessionHeaderMode.OFF -> R.string.session_header_off
}
