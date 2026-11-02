package com.promenar.infinity.ui.linux.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxOptionalPackage
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors

/** 新会话默认模式二选一；只影响之后新建的会话。 */
@Composable
internal fun LinuxDefaultModeSection(selected: SessionRunMode, onSelect: (SessionRunMode) -> Unit, modifier: Modifier = Modifier) {
    InfinitySettingsSection(
        title = stringResource(R.string.linux_run_mode_default_title),
        footer = stringResource(R.string.linux_run_mode_default_note),
        modifier = modifier,
    ) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            SessionRunMode.entries.forEach { mode ->
                LinuxRadioRow(
                    title = stringResource(defaultModeTitle(mode)),
                    subtitle = stringResource(defaultModeSubtitle(mode)),
                    selected = selected == mode,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

/** 管理页的附加软件包：已安装项只读，未安装项可勾选后补装。 */
@Composable
internal fun LinuxOptionalPackagesSection(
    state: LinuxEnvironmentUiState,
    onToggle: (LinuxOptionalPackage, Boolean) -> Unit,
    onInstall: () -> Unit,
) {
    val installed = state.optionalInstalled
    InfinitySettingsSection(title = stringResource(R.string.linux_run_mode_optional_title)) {
        LinuxOptionalPackage.entries.forEach { item ->
            when {
                installed == null -> InfinitySettingsRowLayout(
                    title = stringResource(linuxOptionalPackageTitle(item)),
                    subtitle = stringResource(R.string.linux_run_mode_optional_checking),
                    leadingSpacer = true,
                )
                item in installed -> InfinitySettingsRowLayout(
                    title = stringResource(linuxOptionalPackageTitle(item)),
                    subtitle = stringResource(R.string.linux_run_mode_optional_installed),
                    leadingContent = {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.infinityDomainColors.success)
                    },
                )
                else -> LinuxCheckboxRow(
                    title = stringResource(linuxOptionalPackageTitle(item)),
                    subtitle = linuxOptionalPackageSubtitle(item),
                    checked = item in state.optionalSelection,
                    onCheckedChange = { onToggle(item, it) },
                    enabled = state.packageOperation == null && !state.installingOptional,
                )
            }
        }
        val missing = installed != null && installed.size < LinuxOptionalPackage.entries.size
        if (missing) {
            val busy = state.installingOptional || isOptionalPackageOperation(state.packageOperation)
            FilledTonalButton(
                onClick = onInstall,
                enabled = state.canInstallOptional,
                modifier = Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium).heightIn(min = 48.dp),
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.linux_packages_install))
            }
        }
    }
    state.packageOperation?.takeIf { isOptionalPackageOperation(it) }?.let { LinuxOutputBox(linuxPackageOperationText(it)) }
}

/** 整行可点的复选行，带复选框无障碍语义。 */
@Composable
internal fun LinuxCheckboxRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val haptics = LocalInfinityHaptics.current
    InfinitySettingsRowLayout(
        title = title,
        subtitle = subtitle,
        enabled = enabled,
        subtitleMaxLines = 2,
        leadingContent = { Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        modifier = modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox) {
            haptics.toggle(it)
            onCheckedChange(it)
        },
    )
}

/** 整行可点的单选行，带单选按钮无障碍语义；外层需放在 selectableGroup 中。 */
@Composable
private fun LinuxRadioRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val haptics = LocalInfinityHaptics.current
    InfinitySettingsRowLayout(
        title = title,
        subtitle = subtitle,
        subtitleMaxLines = 2,
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton) {
            haptics.select(selected)
            onClick()
        },
    )
}

internal fun linuxOptionalPackageTitle(item: LinuxOptionalPackage): Int = when (item) {
    LinuxOptionalPackage.GIT -> R.string.linux_run_mode_package_git_title
    LinuxOptionalPackage.RIPGREP -> R.string.linux_run_mode_package_ripgrep_title
    LinuxOptionalPackage.PYTHON3 -> R.string.linux_run_mode_package_python_title
    LinuxOptionalPackage.NODEJS -> R.string.linux_run_mode_package_nodejs_title
    LinuxOptionalPackage.FFMPEG -> R.string.linux_run_mode_package_ffmpeg_title
}

@Composable
internal fun linuxOptionalPackageSubtitle(item: LinuxOptionalPackage): String {
    val bytes = linuxBytes(linuxOptionalPackageBytes(item))
    return when (item) {
        LinuxOptionalPackage.GIT -> stringResource(R.string.linux_run_mode_package_git_subtitle, bytes)
        LinuxOptionalPackage.RIPGREP -> stringResource(R.string.linux_run_mode_package_ripgrep_subtitle, bytes)
        LinuxOptionalPackage.PYTHON3 -> stringResource(R.string.linux_run_mode_package_python_subtitle, bytes)
        LinuxOptionalPackage.NODEJS -> stringResource(R.string.linux_run_mode_package_nodejs_subtitle, bytes)
        LinuxOptionalPackage.FFMPEG -> stringResource(R.string.linux_run_mode_package_ffmpeg_subtitle, bytes)
    }
}

private fun defaultModeTitle(mode: SessionRunMode): Int = when (mode) {
    SessionRunMode.LINUX -> R.string.linux_run_mode_linux_title
    SessionRunMode.BUILTIN -> R.string.linux_run_mode_builtin_title
}

private fun defaultModeSubtitle(mode: SessionRunMode): Int = when (mode) {
    SessionRunMode.LINUX -> R.string.linux_run_mode_linux_subtitle
    SessionRunMode.BUILTIN -> R.string.linux_run_mode_builtin_subtitle
}
