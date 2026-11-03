package com.promenar.infinity.ui.workspace

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.termux.TermuxAvailability
import com.promenar.infinity.domain.linux.LinuxEnvironmentInfo
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.settings.previewNetworkSnapshot
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "可达范围 私有工作区 内置模式 浅色", widthDp = 412, heightDp = 1700, locale = "en")
@Preview(name = "可达范围 私有工作区 内置模式 浅色 大字号130", widthDp = 360, heightDp = 2400, locale = "en", fontScale = 1.3f)
@Composable
fun agentReachPrivateLightPreview() = AgentReachPreview(dark = false, shared = false, runMode = SessionRunMode.BUILTIN)

@PreviewTest
@Preview(name = "可达范围 共享工作区 Linux 模式 深色", widthDp = 412, heightDp = 2300, locale = "zh-rCN")
@Preview(name = "可达范围 共享工作区 Linux 模式 深色 大字号130", widthDp = 360, heightDp = 3600, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun agentReachSharedDarkPreview() = AgentReachPreview(dark = true, shared = true, runMode = SessionRunMode.LINUX)

@PreviewTest
@Preview(name = "可达范围 共享工作区 内置模式 浅色", widthDp = 412, heightDp = 3200, locale = "zh-rCN")
@Composable
fun agentReachSharedBuiltinLightPreview() = AgentReachPreview(dark = false, shared = true, runMode = SessionRunMode.BUILTIN)

/**
 * 私有工作区：无挂载、无 Python、Termux 未启用、无 SSH 主机，“各工具明细”折叠；
 * 共享工作区：带挂载、全部后端可用、tailnet 已连接，“各工具明细”展开。Linux 环境均已就绪，运行模式以会话保存的为准。
 */
@Composable
private fun AgentReachPreview(dark: Boolean, shared: Boolean, runMode: SessionRunMode) {
    val linux = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "preview", 1, null))
    val state = if (shared) {
        AgentReachState(
            workspace = SessionWorkspaceUi.Directory("w1", "infinity-notes", WorkspaceEntity.KIND_SHARED, "/storage/emulated/0/Infinity-Workbench/infinity-notes"),
            mounts = listOf(
                ReachMount("downloads", ReachMountSource.PATH, commandAccessible = true, unavailable = false),
                ReachMount("termux-home", ReachMountSource.SAF, commandAccessible = false, unavailable = false),
                ReachMount("old-photos", ReachMountSource.SAF, commandAccessible = false, unavailable = true),
            ),
            backends = reachBackends(true, true, TermuxAvailability(true, "0.119.1", true), 2, linuxState = linux, linuxAgentEnabled = true),
            executionMode = ExecutionMode.FULL_ACCESS,
            network = previewNetworkSnapshot(configured = true),
        )
    } else {
        AgentReachState(
            workspace = SessionWorkspaceUi.Private,
            backends = reachBackends(false, false, null, 0, linuxState = linux, linuxAgentEnabled = true),
            executionMode = ExecutionMode.WORKSPACE,
            network = previewNetworkSnapshot(configured = false),
        )
    }
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
            AgentReachContent(
                state = state,
                // 从顶栏打开时工作区一行带切换；共享工作区预览为生成进行中，切换停用并提示。
                actions = AgentReachActions(
                    onOpenTailscale = {},
                    onOpenLinux = {},
                    workspaceSwitch = AgentReachWorkspaceSwitch(busy = shared, failed = false, onChoose = {}, onStopGeneration = {}),
                ),
                modifier = Modifier.padding(horizontal = InfinitySpacing.ScreenHorizontal),
                runMode = runMode,
                networkDetailsExpanded = shared,
            )
        }
    }
}
