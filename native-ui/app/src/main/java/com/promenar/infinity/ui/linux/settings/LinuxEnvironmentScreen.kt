package com.promenar.infinity.ui.linux.settings

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxFailure
import com.promenar.infinity.domain.linux.LinuxInstallProgress
import com.promenar.infinity.domain.linux.LinuxInstallStep
import com.promenar.infinity.domain.linux.LinuxInstallStepState
import com.promenar.infinity.domain.linux.LinuxMaintenance
import com.promenar.infinity.domain.linux.LinuxOptionalPackage
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxStepDetail
import com.promenar.infinity.domain.linux.LinuxStepStatus
import com.promenar.infinity.domain.linux.LinuxUnavailableReason
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinityDisclosureRow
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.common.InfinityMetaLine

/** 环境页的全部用户操作；预览与截图测试传入空实现。 */
internal data class LinuxEnvironmentActions(
    val onSetInstallPackage: (LinuxOptionalPackage, Boolean) -> Unit = { _, _ -> },
    val onSetInstallDefaultMode: (SessionRunMode) -> Unit = {},
    val onInstall: () -> Unit = {},
    val onCancelInstall: () -> Unit = {},
    val onLeave: () -> Unit = {},
    val onSetDefaultMode: (SessionRunMode) -> Unit = {},
    val onSetOptionalPackage: (LinuxOptionalPackage, Boolean) -> Unit = { _, _ -> },
    val onInstallOptionalPackages: () -> Unit = {},
    val onClearCache: () -> Unit = {},
    val onRunDiagnostics: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onUninstall: () -> Unit = {},
    val onOpenTerminal: () -> Unit = {},
    val onOpenFiles: () -> Unit = {},
    val onOpenPackages: () -> Unit = {},
    val onOpenMirrors: () -> Unit = {},
    val onOpenTermux: () -> Unit = {},
    val onOpenSsh: () -> Unit = {},
)

@Composable
fun LinuxEnvironmentScreen(
    onBack: () -> Unit,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenPackages: () -> Unit,
    onOpenMirrors: () -> Unit,
    onOpenTermux: () -> Unit,
    onOpenSsh: () -> Unit,
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: LinuxEnvironmentViewModel =
        viewModel(factory = LinuxEnvironmentViewModel.factory(context.applicationContext as android.app.Application))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.ensureMirrorLatency()
        viewModel.refreshOptionalPackages()
    }
    LaunchedEffect(state.notice) {
        val message = when (val notice = state.notice) {
            null -> return@LaunchedEffect
            is LinuxEnvironmentNotice.CacheCleared -> resources.getString(
                R.string.linux_storage_cleared,
                android.text.format.Formatter.formatShortFileSize(context, notice.bytes),
            )
            is LinuxEnvironmentNotice.Failed -> listOfNotNull(
                resources.getString(R.string.linux_operation_failed),
                notice.detail?.takeIf(String::isNotBlank),
            ).joinToString(": ")
            is LinuxEnvironmentNotice.PackageFailed -> listOfNotNull(
                resources.getString(R.string.linux_packages_operation_failed, resources.getString(linuxPackageActionLabel(notice.failure.action))),
                notice.failure.result?.failure?.let { resources.getString(linuxFailureText(it.code)) },
            ).joinToString(" ")
        }
        viewModel.consumeNotice()
        snackbar.showSnackbar(message)
    }
    InfinitySettingsPageLayout(title = stringResource(R.string.linux_title), onBack = onBack) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LinuxEnvironmentContent(
                state = state,
                actions = LinuxEnvironmentActions(
                    onSetInstallPackage = viewModel::setInstallPackage,
                    onSetInstallDefaultMode = viewModel::setInstallDefaultMode,
                    onInstall = viewModel::install,
                    onCancelInstall = viewModel::cancelInstall,
                    onLeave = onBack,
                    onSetDefaultMode = viewModel::setDefaultMode,
                    onSetOptionalPackage = viewModel::setOptionalSelected,
                    onInstallOptionalPackages = viewModel::installOptionalPackages,
                    onClearCache = viewModel::clearDownloadCache,
                    onRunDiagnostics = viewModel::runDiagnostics,
                    onReset = viewModel::reset,
                    onUninstall = viewModel::uninstall,
                    onOpenTerminal = onOpenTerminal,
                    onOpenFiles = onOpenFiles,
                    onOpenPackages = onOpenPackages,
                    onOpenMirrors = onOpenMirrors,
                    onOpenTermux = onOpenTermux,
                    onOpenSsh = onOpenSsh,
                ),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
internal fun LinuxEnvironmentContent(
    state: LinuxEnvironmentUiState,
    actions: LinuxEnvironmentActions,
    modifier: Modifier = Modifier,
) {
    when (val runtime = state.runtime) {
        is LinuxRuntimeState.Unavailable -> LinuxUnavailableContent(runtime.reason, actions, modifier)
        is LinuxRuntimeState.NotInstalled -> LinuxInstallGuideContent(state, runtime.freeBytes, actions, modifier)
        is LinuxRuntimeState.Installing -> LinuxInstallingContent(runtime.progress, failure = null, running = true, actions, modifier)
        is LinuxRuntimeState.InstallInterrupted ->
            LinuxInstallingContent(runtime.progress, runtime.failure, running = false, actions, modifier)
        is LinuxRuntimeState.Ready -> LinuxManageContent(state, runtime, actions, modifier)
        is LinuxRuntimeState.Maintaining -> LinuxMaintainingContent(runtime.operation, modifier)
        is LinuxRuntimeState.Broken -> LinuxBrokenContent(runtime.failure, state.busy, actions, modifier)
    }
}

/** 页面顶部的状态行（标题由顶栏显示）。 */
@Composable
internal fun LinuxStatusLine(text: String, dot: Color? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.XSmall),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 可滚动正文加固定在底部的操作区。 */
@Composable
private fun LinuxPageWithFooter(
    modifier: Modifier,
    footer: @Composable ColumnScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
            content = content,
        )
        Column(
            Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small, bottom = InfinitySpacing.Large),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = footer,
        )
    }
}

@Composable
private fun LinuxUnavailableContent(reason: LinuxUnavailableReason, actions: LinuxEnvironmentActions, modifier: Modifier) {
    val (title, body) = when (reason) {
        LinuxUnavailableReason.DISTRIBUTION -> R.string.linux_unavailable_distribution_title to R.string.linux_unavailable_distribution_body
        LinuxUnavailableReason.UNSUPPORTED_ABI -> R.string.linux_unavailable_abi_title to R.string.linux_unavailable_abi_body
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        LinuxHeroCard(title = stringResource(title), body = stringResource(body))
        InfinitySettingsSection(title = stringResource(R.string.settings_local_remote)) {
            InfinitySettingsItem(title = stringResource(R.string.linux_open_termux), onClick = actions.onOpenTermux, showChevron = true)
            InfinitySettingsItem(title = stringResource(R.string.linux_open_ssh), onClick = actions.onOpenSsh, showChevron = true)
        }
    }
}

@Composable
private fun LinuxHeroCard(title: String, body: String) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LinuxInstallGuideContent(
    state: LinuxEnvironmentUiState,
    freeBytes: Long,
    actions: LinuxEnvironmentActions,
    modifier: Modifier,
) {
    val required = linuxRequiredBytes(state.installPackages)
    val enoughSpace = freeBytes >= required
    LinuxPageWithFooter(
        modifier = modifier,
        footer = {
            Button(
                onClick = actions.onInstall,
                enabled = enoughSpace,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.LINUX_INSTALL_BUTTON),
            ) { Text(stringResource(R.string.linux_install_action), style = MaterialTheme.typography.titleSmall) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.linux_install_alternatives),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            InfinityPillRow(Modifier.fillMaxWidth(), reverseWhenStacked = false) {
                InfinityPillButton(text = stringResource(R.string.linux_open_termux), onClick = actions.onOpenTermux)
                InfinityPillButton(text = stringResource(R.string.linux_open_ssh), onClick = actions.onOpenSsh)
            }
        },
    ) {
        LinuxStatusLine(stringResource(R.string.linux_status_not_installed))
        LinuxHeroCard(stringResource(R.string.linux_install_hero_title), stringResource(R.string.linux_install_hero_body))
        InfinitySettingsSection(
            title = stringResource(R.string.linux_install_contents),
        ) {
            InfinitySettingsRowLayout(
                icon = Icons.Rounded.Inventory2,
                title = stringResource(R.string.linux_install_base_title, LINUX_BUNDLED_ALPINE_VERSION),
                subtitle = stringResource(R.string.linux_install_base_subtitle),
                subtitleMaxLines = 2,
            )
            InfinitySettingsRowLayout(
                icon = Icons.Rounded.Terminal,
                title = stringResource(R.string.linux_run_mode_install_base),
            )
            LinuxOptionalPackage.entries.forEach { item ->
                LinuxCheckboxRow(
                    title = stringResource(linuxOptionalPackageTitle(item)),
                    subtitle = linuxOptionalPackageSubtitle(item),
                    checked = item in state.installPackages,
                    onCheckedChange = { actions.onSetInstallPackage(item, it) },
                )
            }
        }
        InfinitySettingsGroup {
            InfinitySettingsRowLayout(
                icon = Icons.Rounded.SdStorage,
                title = stringResource(R.string.linux_install_space_title),
                subtitle = if (enoughSpace) stringResource(R.string.linux_install_space_ok, linuxBytes(freeBytes))
                else stringResource(R.string.linux_install_space_low, linuxBytes(freeBytes), linuxBytes(required)),
                subtitleMaxLines = 2,
                trailingContent = {
                    Icon(
                        if (enoughSpace) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = if (enoughSpace) MaterialTheme.infinityDomainColors.success else MaterialTheme.colorScheme.error,
                    )
                },
            )
        }
        LinuxCallout(stringResource(R.string.linux_install_warning))
        // 新会话默认模式不在安装流程中选择：首次安装沿用 installDefaultMode，之后在管理页“会话”分组修改（H5）。
    }
}

/** 警示说明块：命令的权限边界等需要用户知情的内容。 */
@Composable
internal fun LinuxCallout(text: String, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.infinityDomainColors.warningContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = InfinitySpacing.Large, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
        ) {
            Icon(
                Icons.Rounded.WarningAmber,
                contentDescription = null,
                tint = MaterialTheme.infinityDomainColors.onWarningContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.infinityDomainColors.onWarningContainer)
        }
    }
}

@Composable
private fun LinuxInstallingContent(
    progress: LinuxInstallProgress,
    failure: LinuxFailure?,
    running: Boolean,
    actions: LinuxEnvironmentActions,
    modifier: Modifier,
) {
    val current = progress.current
    val currentIndex = progress.steps.indexOfFirst { it.status == LinuxStepStatus.RUNNING }
    LinuxPageWithFooter(
        modifier = modifier,
        footer = {
            if (running) {
                Row(horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium)) {
                    FilledTonalButton(onClick = actions.onLeave, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.linux_install_background))
                    }
                    FilledTonalButton(
                        onClick = actions.onCancelInstall,
                        colors = ButtonDefaults.filledTonalButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag(UiTags.LINUX_INSTALL_CANCEL),
                    ) { Text(stringResource(R.string.linux_install_cancel)) }
                }
                Text(
                    stringResource(R.string.linux_install_cancel_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Button(
                    onClick = actions.onInstall,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(UiTags.LINUX_INSTALL_BUTTON),
                ) { Text(stringResource(R.string.linux_install_resume), style = MaterialTheme.typography.titleSmall) }
            }
        },
    ) {
        LinuxStatusLine(
            when {
                running && currentIndex >= 0 -> stringResource(R.string.linux_status_installing, currentIndex + 1, progress.steps.size)
                failure != null -> stringResource(R.string.linux_status_failed)
                else -> stringResource(R.string.linux_status_interrupted)
            },
        )
        if (running && current != null) {
            Column(Modifier.padding(horizontal = InfinitySpacing.XSmall), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(linuxStepTitle(current.step)),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                    )
                    progress.fraction?.let {
                        Text(
                            "${(it * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = InfinityMonospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                val label = stringResource(R.string.linux_progress_label)
                val barModifier = Modifier.fillMaxWidth().semantics { contentDescription = label }
                progress.fraction?.let { LinearProgressIndicator(progress = { it }, modifier = barModifier) }
                    ?: LinearProgressIndicator(modifier = barModifier)
            }
        }
        if (failure != null) LinuxFailureCard(failure)
        else if (!running) LinuxCallout(stringResource(R.string.linux_install_paused_note))
        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                progress.steps.forEach { LinuxInstallStepRow(it) }
            }
        }
        if (progress.outputTail.isNotBlank()) {
            Column(verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                Text(
                    stringResource(R.string.linux_live_output),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = InfinitySpacing.Large),
                )
                LinuxOutputBox(progress.outputTail.trimEnd().lines().takeLast(LIVE_OUTPUT_LINES).joinToString("\n"))
            }
        }
    }
}

@Composable
internal fun LinuxOutputBox(text: String, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainer, modifier = modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.Medium, vertical = 10.dp),
            )
        }
    }
}

@Composable
internal fun LinuxFailureCard(failure: LinuxFailure) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium)) {
            Text(
                stringResource(linuxFailureText(failure.code)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            val detail = failure.detail?.takeIf(String::isNotBlank)
            if (detail != null) {
                InfinityDisclosureRow(
                    text = stringResource(R.string.linux_failure_details),
                    expanded = expanded,
                    onClick = { expanded = !expanded },
                )
                if (expanded) {
                    SelectionContainer {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun LinuxInstallStepRow(step: LinuxInstallStepState) {
    val pending = step.status == LinuxStepStatus.PENDING || step.status == LinuxStepStatus.SKIPPED
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = InfinitySpacing.Large, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (step.status) {
                LinuxStepStatus.DONE -> Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.infinityDomainColors.success)
                LinuxStepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                LinuxStepStatus.FAILED -> Icon(Icons.Rounded.Close, null, tint = MaterialTheme.colorScheme.error)
                LinuxStepStatus.SKIPPED -> Icon(Icons.Rounded.Remove, null, tint = MaterialTheme.colorScheme.outline)
                LinuxStepStatus.PENDING -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(linuxStepTitle(step.step)),
                style = MaterialTheme.typography.bodyLarge,
                color = if (pending) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            InfinityMetaLine(
                linuxStepSubtitle(step),
                style = MaterialTheme.typography.bodySmall,
                color = if (step.status == LinuxStepStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        step.durationMs?.takeIf { step.status == LinuxStepStatus.DONE }?.let {
            Text(
                linuxDuration(it),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 步骤副标题的分段文字：网络步骤把 DNS、VPN 与代理各列一段。 */
@Composable
private fun linuxStepSubtitle(step: LinuxInstallStepState): List<String> {
    if (step.status == LinuxStepStatus.FAILED) return listOf(stringResource(R.string.linux_step_failed))
    if (step.status == LinuxStepStatus.SKIPPED && step.step == LinuxInstallStep.COMMON_TOOLS) {
        return listOf(stringResource(R.string.linux_step_tools_skipped))
    }
    return when (val detail = step.detail) {
        is LinuxStepDetail.Unpacked -> listOf(stringResource(R.string.linux_step_unpack_done, linuxBytes(detail.bytes)))
        is LinuxStepDetail.Network -> listOfNotNull(
            stringResource(R.string.linux_step_network_done, detail.dnsServers.joinToString(", ")),
            stringResource(R.string.linux_step_network_vpn).takeIf { detail.viaVpn },
            stringResource(R.string.linux_step_network_proxy).takeIf { detail.proxyConfigured },
        )
        is LinuxStepDetail.Downloading -> {
            val mirror = stringResource(linuxMirrorName(detail.mirror))
            val total = detail.totalBytes
            listOf(
                if (total != null) stringResource(R.string.linux_step_tools_progress, mirror, linuxBytes(detail.downloadedBytes), linuxBytes(total))
                else stringResource(R.string.linux_step_tools_progress_unknown, mirror, linuxBytes(detail.downloadedBytes)),
            )
        }
        is LinuxStepDetail.Diagnostics -> listOf(stringResource(R.string.linux_step_diagnostics_done, linuxDuration(detail.startupMs)))
        null -> listOf(stringResource(linuxStepPendingText(step.step)))
    }
}

@Composable
private fun LinuxMaintainingContent(operation: LinuxMaintenance, modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(vertical = InfinitySpacing.XXLarge),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        CircularProgressIndicator()
        Text(
            stringResource(
                when (operation) {
                    LinuxMaintenance.RESETTING -> R.string.linux_status_resetting
                    LinuxMaintenance.UNINSTALLING -> R.string.linux_status_uninstalling
                },
            ),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun LinuxBrokenContent(
    failure: LinuxFailure,
    busy: LinuxBusyAction?,
    actions: LinuxEnvironmentActions,
    modifier: Modifier,
) {
    var confirm by remember { mutableStateOf<LinuxBusyAction?>(null) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        LinuxStatusLine(stringResource(R.string.linux_status_broken), dot = MaterialTheme.colorScheme.error)
        LinuxFailureCard(failure)
        LinuxDangerRows(
            uninstallSubtitle = stringResource(R.string.linux_uninstall_subtitle_plain),
            busy = busy,
            onRequest = { confirm = it },
        )
    }
    LinuxDangerConfirm(confirm, onDismiss = { confirm = null }, actions = actions)
}

/** 重置与卸载两行；只发出确认请求，由 [LinuxDangerConfirm] 执行。 */
@Composable
internal fun LinuxDangerRows(uninstallSubtitle: String, busy: LinuxBusyAction?, onRequest: (LinuxBusyAction) -> Unit) {
    InfinitySettingsItem(
        title = stringResource(R.string.linux_reset_title),
        subtitle = stringResource(R.string.linux_reset_subtitle),
        subtitleMaxLines = 3,
        titleColor = MaterialTheme.colorScheme.error,
        enabled = busy == null,
        onClick = { onRequest(LinuxBusyAction.RESET) },
        modifier = Modifier.testTag(UiTags.LINUX_RESET),
    )
    InfinitySettingsItem(
        title = stringResource(R.string.linux_uninstall_title),
        subtitle = uninstallSubtitle,
        subtitleMaxLines = 2,
        titleColor = MaterialTheme.colorScheme.error,
        enabled = busy == null,
        onClick = { onRequest(LinuxBusyAction.UNINSTALL) },
        modifier = Modifier.testTag(UiTags.LINUX_UNINSTALL),
    )
}

@Composable
internal fun LinuxDangerConfirm(request: LinuxBusyAction?, onDismiss: () -> Unit, actions: LinuxEnvironmentActions) {
    when (request) {
        LinuxBusyAction.RESET -> InfinityConfirmDialog(
            title = stringResource(R.string.linux_reset_confirm_title),
            message = stringResource(R.string.linux_reset_confirm_body),
            confirmText = stringResource(R.string.linux_reset_confirm_action),
            onConfirm = { onDismiss(); actions.onReset() },
            onCancel = onDismiss,
            isDestructive = true,
        )
        LinuxBusyAction.UNINSTALL -> InfinityConfirmDialog(
            title = stringResource(R.string.linux_uninstall_confirm_title),
            message = stringResource(R.string.linux_uninstall_confirm_body),
            confirmText = stringResource(R.string.linux_uninstall_confirm_action),
            onConfirm = { onDismiss(); actions.onUninstall() },
            onCancel = onDismiss,
            isDestructive = true,
        )
        else -> Unit
    }
}

/** 与 `native-ui/app/linux/linux-runtime.properties` 的 `alpine.version` 一致；只用于安装前的说明文字。 */
internal const val LINUX_BUNDLED_ALPINE_VERSION = "3.24.3"
private const val LIVE_OUTPUT_LINES = 6
