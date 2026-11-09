package com.promenar.infinity.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.WorkHistory
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.data.termux.TermuxAvailability
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.linux.settings.LinuxEntryRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置首页“执行环境与连接”五行的状态；各项尚未读取时为 null，副标题显示“读取中”。 */
internal data class EnvironmentConnectionState(
    val linuxState: LinuxRuntimeState? = null,
    val linuxAgentEnabled: Boolean = true,
    val termuxAvailability: TermuxAvailability? = null,
    val sshHostCount: Int? = null,
    val tailscaleState: TailnetState? = null,
)

/** 收集各行状态：Termux 在每次回到前台时于 IO 线程重新检测。 */
@Composable
internal fun rememberEnvironmentConnectionState(): EnvironmentConnectionState {
    val application = LocalContext.current.applicationContext as InfinityApplication
    val linuxState by application.linux.runtime.state.collectAsState()
    val linuxAgentEnabled by application.linux.runtime.agentEnabled.collectAsState()
    val sshHosts by remember(application) {
        application.sshHostStore.observeHosts().flowOn(Dispatchers.IO)
    }.collectAsState(initial = null)
    val tailscaleState by application.tailnetManager.state.collectAsState()
    var termuxAvailability by remember { mutableStateOf<TermuxAvailability?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, application) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                scope.launch { termuxAvailability = withContext(Dispatchers.IO) { application.termuxRuntime.availability() } }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return EnvironmentConnectionState(
        linuxState = linuxState,
        linuxAgentEnabled = linuxAgentEnabled,
        termuxAvailability = termuxAvailability,
        sshHostCount = sshHosts?.size,
        tailscaleState = tailscaleState,
    )
}

/** 执行环境与连接：Linux 环境、Termux、SSH 主机、Tailscale、后台与定时（H4）。 */
@Composable
internal fun ColumnScope.EnvironmentConnectionRows(
    state: EnvironmentConnectionState,
    onOpen: (String) -> Unit,
    selectedRoute: String? = null,
) {
    val loading = stringResource(R.string.settings_status_loading)
    LinuxEntryRow(state.linuxState, state.linuxAgentEnabled) { onOpen(NavDestinations.LINUX_ENVIRONMENT) }
    EnvironmentRow(
        icon = Icons.Rounded.PhoneAndroid,
        title = stringResource(R.string.settings_termux_entry),
        subtitle = stringResource(
            when (termuxEntryStatus(state.termuxAvailability)) {
                TermuxEntryStatus.Loading -> R.string.settings_status_loading
                TermuxEntryStatus.NotInstalled -> R.string.settings_termux_not_installed
                TermuxEntryStatus.UnsupportedVersion -> R.string.settings_termux_unsupported_version
                TermuxEntryStatus.PermissionRequired -> R.string.settings_termux_permission_required
                TermuxEntryStatus.Available -> R.string.settings_termux_available
            },
        ),
        route = NavDestinations.TERMUX_CONFIG,
        selectedRoute = selectedRoute,
        onOpen = onOpen,
    )
    EnvironmentRow(
        icon = Icons.Rounded.Terminal,
        title = stringResource(R.string.ssh_settings_entry),
        subtitle = state.sshHostCount?.let { stringResource(R.string.settings_ssh_hosts_count, it) } ?: loading,
        route = NavDestinations.SSH_CONFIG,
        selectedRoute = selectedRoute,
        onOpen = onOpen,
    )
    EnvironmentRow(
        icon = Icons.Rounded.Hub,
        title = stringResource(R.string.settings_tailscale_entry),
        subtitle = state.tailscaleState?.let { stringResource(tailscalePhaseText(it)) } ?: loading,
        route = NavDestinations.TAILSCALE_CONFIG,
        selectedRoute = selectedRoute,
        onOpen = onOpen,
    )
    EnvironmentRow(
        icon = Icons.Rounded.WorkHistory,
        title = stringResource(R.string.settings_background_title),
        subtitle = stringResource(R.string.settings_background_entry_subtitle),
        route = NavDestinations.BACKGROUND_ACTIVITY,
        selectedRoute = selectedRoute,
        onOpen = onOpen,
        modifier = Modifier.testTag("background_activity_entry"),
    )
}

@Composable
private fun EnvironmentRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    route: String,
    selectedRoute: String?,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selected = selectedRoute == route
    InfinitySettingsItem(
        icon = icon,
        title = title,
        subtitle = subtitle,
        onClick = { onOpen(route) },
        showChevron = true,
        selected = selected.takeIf { it },
        modifier = if (selected) modifier.background(MaterialTheme.colorScheme.secondaryContainer) else modifier,
    )
}
