package com.promenar.infinity.ui.linux.settings

import com.promenar.infinity.ui.theme.InfinityMonospace
import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.common.InfinityPageSearch
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxPackage
import com.promenar.infinity.domain.linux.LinuxPackageAction
import com.promenar.infinity.domain.linux.LinuxPackageOperation
import com.promenar.infinity.domain.linux.LinuxPackageOrigin
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.SettingsSectionHeader
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing

internal data class LinuxPackagesActions(
    val onFilter: (LinuxPackageFilter) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onInstall: (String) -> Unit = {},
    val onRemove: (String) -> Unit = {},
    val onUpgrade: (String) -> Unit = {},
    val onUpgradeAll: () -> Unit = {},
)

@Composable
fun LinuxPackagesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val viewModel: LinuxPackagesViewModel = viewModel(factory = LinuxPackagesViewModel.factory(context.applicationContext as Application))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var searchActive by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    LaunchedEffect(state.failure) {
        val failure = state.failure ?: return@LaunchedEffect
        val label = resources.getString(linuxPackageActionLabel(failure.action))
        val detail = failure.result?.failure?.let { resources.getString(linuxFailureText(it.code)) }
        viewModel.consumeFailure()
        snackbar.showSnackbar(listOfNotNull(resources.getString(R.string.linux_packages_operation_failed, label), detail).joinToString(" "))
    }
    InfinitySettingsPageLayout(
        title = stringResource(R.string.linux_packages_title),
        onBack = onBack,
        horizontalContentPadding = 0.dp,
        search = InfinityPageSearch(
            query = state.query,
            active = searchActive,
            onQueryChange = viewModel::setQuery,
            onActiveChange = { searchActive = it },
            placeholder = stringResource(R.string.linux_packages_search_hint),
            fieldTag = UiTags.LINUX_PACKAGES_SEARCH,
        ),
        actions = {
            IconButton(onClick = viewModel::refreshIndex, enabled = state.operation == null) {
                Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.linux_packages_refresh))
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            LinuxPackagesContent(
                state = state,
                actions = LinuxPackagesActions(
                    onFilter = viewModel::setFilter,
                    onQuery = viewModel::setQuery,
                    onInstall = viewModel::install,
                    onRemove = viewModel::remove,
                    onUpgrade = viewModel::upgrade,
                    onUpgradeAll = viewModel::upgradeAll,
                ),
            )
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/**
 * 进度条、状态行与过滤芯片固定在列表上方，高度不随操作与可更新数变化，刷新与安装时列表不跳动；
 * 操作输出收进“查看输出”面板。
 */
@Composable
internal fun LinuxPackagesContent(state: LinuxPackagesUiState, actions: LinuxPackagesActions, modifier: Modifier = Modifier) {
    var removing by rememberSaveable { mutableStateOf<String?>(null) }
    var showOutput by rememberSaveable { mutableStateOf(false) }
    var lastOperation by remember { mutableStateOf(state.operation) }
    LaunchedEffect(state.operation) { state.operation?.let { lastOperation = it } }
    val searchOnly = searchOnlyResults(state.searchResults, state.installed)
    val installed = state.visibleInstalled
    // 列表各项等高：惰性列表看不到屏幕外的行，按搜索结果与已安装两段的全部数据算出最高档。
    val listed = (if (searchOnly != null || state.searching) searchOnly.orEmpty() else emptyList()) +
        (if (state.installed != null) installed else emptyList())
    val listHasSubtitle = listed.any { it.hasSubtitleLine() }
    val listHasAction = listed.any { it.hasActionButton() }
    Column(modifier.fillMaxSize()) {
        LinuxPackagesProgress(state.operation)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
            verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
        ) {
            Text(
                state.operation?.let { linuxPackageOperationHead(it) }
                    ?: if (state.installed == null) stringResource(R.string.linux_packages_summary_loading)
                    else stringResource(R.string.linux_packages_summary, state.installedCount, state.updateCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            InfinityPillRow(Modifier.fillMaxWidth()) {
                if (lastOperation != null) {
                    InfinityPillButton(
                        text = stringResource(R.string.linux_packages_view_output),
                        onClick = { showOutput = true },
                    )
                }
                InfinityPillButton(
                    text = stringResource(R.string.linux_packages_upgrade_all),
                    onClick = actions.onUpgradeAll,
                    enabled = state.updateCount > 0 && state.operation == null,
                    tone = InfinityPillTone.PRIMARY,
                    modifier = Modifier.testTag(UiTags.LINUX_PACKAGES_UPGRADE_ALL),
                )
            }
        }
        InfinitySegmentedControl(
            segments = LinuxPackageFilter.entries.map { InfinitySegment(it, filterLabel(it, state.updateCount)) },
            selected = state.filter,
            onSelect = actions.onFilter,
            contentPadding = PaddingValues(horizontal = InfinitySpacing.Large),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = InfinitySpacing.Large),
        ) {
            if (searchOnly != null || state.searching) {
                item(key = "search-header") {
                    SettingsSectionHeader(stringResource(R.string.linux_packages_search_results, state.query.trim()), modifier = Modifier.padding(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = InfinitySpacing.Large, bottom = InfinitySpacing.XSmall))
                }
                when {
                    state.searching -> item(key = "searching") {
                        Box(Modifier.fillMaxWidth().padding(InfinitySpacing.Large), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                        }
                    }
                    searchOnly.isNullOrEmpty() && installed.isEmpty() -> item(key = "search-empty") {
                        LinuxPackagesEmpty(stringResource(R.string.linux_packages_search_empty, state.query.trim()))
                    }
                    else -> items(searchOnly.orEmpty(), key = { "s-${it.name}" }) { pkg ->
                        LinuxPackageRow(pkg, state.operation, actions, onRequestRemove = { removing = it }, listHasSubtitle = listHasSubtitle, listHasAction = listHasAction)
                    }
                }
            }
            if (state.installed != null) {
                item(key = "installed-header") {
                    SettingsSectionHeader(stringResource(R.string.linux_packages_installed_section), modifier = Modifier.padding(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = InfinitySpacing.Large, bottom = InfinitySpacing.XSmall))
                }
                if (installed.isEmpty()) {
                    item(key = "installed-empty") { LinuxPackagesEmpty(stringResource(R.string.linux_packages_empty)) }
                }
                items(installed, key = { "i-${it.name}" }) { pkg ->
                    LinuxPackageRow(pkg, state.operation, actions, onRequestRemove = { removing = it }, listHasSubtitle = listHasSubtitle, listHasAction = listHasAction)
                }
            }
        }
    }
    InfinityBottomSheet(
        show = showOutput && lastOperation != null,
        onDismiss = { showOutput = false },
        title = stringResource(R.string.linux_packages_output_title),
        scrollContent = true,
    ) {
        lastOperation?.let { operation ->
            Text(
                linuxPackageOperationHead(operation),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = InfinitySpacing.Small),
            )
            LinuxOutputBox(operation.outputTail.trimEnd().ifEmpty { "—" })
        }
    }
    removing?.let { name ->
        InfinityConfirmDialog(
            title = stringResource(R.string.linux_packages_remove_confirm_title, name),
            message = stringResource(R.string.linux_packages_remove_confirm_body),
            confirmText = stringResource(R.string.linux_packages_remove),
            onConfirm = { removing = null; actions.onRemove(name) },
            onCancel = { removing = null },
            isDestructive = true,
        )
    }
}

/** 顶栏下方 2dp 的进度条；空闲时保留同样高度，避免内容上下移动。 */
@Composable
private fun LinuxPackagesProgress(operation: LinuxPackageOperation?) {
    Box(Modifier.fillMaxWidth().height(2.dp)) {
        if (operation == null) return@Box
        val step = operation.step
        val total = operation.totalSteps
        if (step != null && total != null && total > 0) {
            LinearProgressIndicator(progress = { step.toFloat() / total }, modifier = Modifier.fillMaxSize(), drawStopIndicator = {})
        } else {
            LinearProgressIndicator(Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun LinuxPackagesEmpty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(InfinitySpacing.Large),
    )
}

@Composable
private fun LinuxPackageRow(
    pkg: LinuxPackage,
    operation: LinuxPackageOperation?,
    actions: LinuxPackagesActions,
    onRequestRemove: (String) -> Unit,
    listHasSubtitle: Boolean = false,
    listHasAction: Boolean = false,
) {
    val busyHere = operation != null && (pkg.name in operation.names || (operation.action == LinuxPackageAction.UPGRADE && operation.names.isEmpty() && pkg.availableVersion != null))
    val minHeight = linuxPackageRowMinHeight(
        subtitle = listHasSubtitle || pkg.hasSubtitleLine(),
        action = listHasAction || pkg.hasActionButton(),
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = InfinitySpacing.Large, vertical = 10.dp).testTag(UiTags.linuxPackageRow(pkg.name)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
    ) {
        Column(Modifier.weight(1f)) {
            Text(pkg.name, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = InfinityMonospace), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                pkg.availableVersion?.let { "${pkg.version} → $it" } ?: pkg.version,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // 列表各项等高：第三行限一行省略，长描述不把个别行撑高。
            packageSubtitle(pkg)?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val enabled = operation == null
        // 操作位固定最小宽度，按钮文字切换时行内文字不跟着挤动。
        Box(Modifier.widthIn(min = 96.dp), contentAlignment = Alignment.CenterEnd) { when {
            busyHere -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(packageBusyLabel(operation!!.action)),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            !pkg.installed -> FilledTonalButton(onClick = { actions.onInstall(pkg.name) }, enabled = enabled) {
                Text(stringResource(R.string.linux_packages_install))
            }
            pkg.availableVersion != null -> FilledTonalButton(onClick = { actions.onUpgrade(pkg.name) }, enabled = enabled) {
                Text(stringResource(R.string.linux_packages_update))
            }
            pkg.explicit -> OutlinedButton(onClick = { onRequestRemove(pkg.name) }, enabled = enabled) {
                Text(stringResource(R.string.linux_packages_remove))
            }
        } }
    }
}

/** 是否有第三行，与 [packageSubtitle] 返回非 null 的条件一致。 */
private fun LinuxPackage.hasSubtitleLine(): Boolean =
    origin != null || (installed && !explicit) || !description.isNullOrBlank() || installedSizeBytes != null

/** 行尾是否有安装、更新或移除按钮（进行中时换成同位置的进度提示）。 */
private fun LinuxPackage.hasActionButton(): Boolean = !installed || availableVersion != null || explicit

/**
 * 软件包行最小高度。列表各项等高：上下内边距 10dp×2 + 文字区与行尾按钮中的较高者。
 * 文字区 = 包名 bodyLarge + 版本 bodySmall，有第三行再加 bodySmall 一行，默认 22 + 18 (+ 18)；
 * 按钮取 40dp 按钮高与最小触控尺寸 48dp 的较大者。默认依次为 64（下限）、68、78dp；行高随字号实算。
 */
@Composable
private fun linuxPackageRowMinHeight(subtitle: Boolean, action: Boolean): Dp {
    val typography = MaterialTheme.typography
    val touch = LocalMinimumInteractiveComponentSize.current.takeOrElse { 0.dp }
    return with(LocalDensity.current) {
        var text = typography.bodyLarge.lineHeight.toDp() + typography.bodySmall.lineHeight.toDp()
        if (subtitle) text += typography.bodySmall.lineHeight.toDp()
        val trailing = if (action) maxOf(ButtonDefaults.MinHeight, touch) else 0.dp
        maxOf(64.dp, maxOf(text, trailing) + 20.dp)
    }
}

/** 第三行：来源、描述与大小；没有可说明的内容时为 null。 */
@Composable
private fun packageSubtitle(pkg: LinuxPackage): String? {
    val origin = when (val origin = pkg.origin) {
        is LinuxPackageOrigin.Agent -> origin.sessionTitle?.takeIf(String::isNotBlank)
            ?.let { stringResource(R.string.linux_packages_origin_agent, it) }
            ?: stringResource(R.string.linux_packages_origin_agent_unnamed)
        LinuxPackageOrigin.Preset -> stringResource(R.string.linux_packages_origin_preset)
        LinuxPackageOrigin.User -> stringResource(R.string.linux_packages_origin_user)
        null -> if (pkg.installed && !pkg.explicit) stringResource(R.string.linux_packages_dependency) else null
    }
    val size = pkg.installedSizeBytes?.let { linuxBytes(it) }
    return listOfNotNull(origin, pkg.description?.takeIf(String::isNotBlank), size).joinToString(" · ").ifEmpty { null }  // wrap-check: allow 含可能很长的软件包描述，按一行省略
}

@Composable
private fun filterLabel(filter: LinuxPackageFilter, updates: Int): String = when (filter) {
    LinuxPackageFilter.ALL -> stringResource(R.string.linux_packages_filter_all)
    LinuxPackageFilter.INSTALLED -> stringResource(R.string.linux_packages_filter_installed)
    LinuxPackageFilter.UPDATES -> stringResource(R.string.linux_packages_filter_updates, updates)
    LinuxPackageFilter.AGENT -> stringResource(R.string.linux_packages_filter_agent)
}

/** 软件包操作的一行摘要：动作、包名与 apk 进度。 */
@Composable
internal fun linuxPackageOperationHead(operation: LinuxPackageOperation): String = listOfNotNull(
    stringResource(packageBusyLabel(operation.action)),
    operation.names.joinToString(" ").ifEmpty { null },
    if (operation.step != null && operation.totalSteps != null) stringResource(R.string.linux_packages_progress, operation.step, operation.totalSteps) else null,
).joinToString(" ")

/** 软件包操作的进度摘要与输出尾部；管理页补装附加包时同样使用。 */
@Composable
internal fun linuxPackageOperationText(operation: LinuxPackageOperation): String {
    val head = linuxPackageOperationHead(operation)
    val tail = operation.outputTail.trimEnd().lines().takeLast(4).joinToString("\n")
    return if (tail.isBlank()) head else "$head\n$tail"
}

internal fun linuxPackageActionLabel(action: LinuxPackageAction): Int = when (action) {
    LinuxPackageAction.REFRESH -> R.string.linux_packages_refresh
    LinuxPackageAction.INSTALL -> R.string.linux_packages_install
    LinuxPackageAction.REMOVE -> R.string.linux_packages_remove
    LinuxPackageAction.UPGRADE -> R.string.linux_packages_update
}

private fun packageBusyLabel(action: LinuxPackageAction): Int = when (action) {
    LinuxPackageAction.REFRESH -> R.string.linux_packages_refreshing
    LinuxPackageAction.INSTALL -> R.string.linux_packages_installing
    LinuxPackageAction.REMOVE -> R.string.linux_packages_removing
    LinuxPackageAction.UPGRADE -> R.string.linux_packages_updating
}
