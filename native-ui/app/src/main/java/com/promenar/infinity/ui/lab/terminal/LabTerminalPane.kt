package com.promenar.infinity.ui.lab.terminal

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.promenar.infinity.domain.linux.LinuxEnvironmentInfo
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxTerminalInfo
import com.promenar.infinity.domain.linux.LinuxTerminalLocation
import com.promenar.infinity.ui.linux.terminal.LinuxTerminalContent
import com.promenar.infinity.ui.linux.terminal.LinuxTerminalUiState
import com.promenar.infinity.ui.linux.terminal.TerminalTab
import com.promenar.infinity.ui.linux.terminal.TerminalThemeApplier
import com.promenar.infinity.ui.linux.terminal.TerminalThemeColors
import com.promenar.infinity.ui.ssh.RemoteTerminalBuffer
import com.promenar.infinity.ui.ssh.SSH_TERMINAL_MINE_TAB
import com.promenar.infinity.ui.ssh.SshTerminalContent
import com.promenar.infinity.ui.ssh.SshTerminalUiState
import com.promenar.infinity.ui.ssh.SshTerminalView
import com.promenar.infinity.ui.ssh.TerminalGrid

private const val LAB_SHELL_ID = "lab-shell"

/** 就绪的 Alpine 环境，单个运行中的 shell 页签，从 /root 开始；不绑定会话工作区，因此没有副标题。 */
private val labTerminalUiState = LinuxTerminalUiState(
    runtime = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.24", "aarch64", "5.4.0", 0L, null)),
    tabs = listOf(TerminalTab.Shell(LinuxTerminalInfo(LAB_SHELL_ID, 1, null, LinuxTerminalLocation.Directory(), 0L, running = true))),
    selectedKey = TerminalTab.shellKey(LAB_SHELL_ID),
)

/**
 * 真实的 Linux 终端页外壳，终端区是真实的终端视图，内容由 [script] 按当前列数给出整屏文本。
 * 返回键接 [onExit]，其余操作都是空操作。
 */
@Composable
internal fun LabTerminalShell(onExit: () -> Unit, script: (columns: Int) -> String) {
    LinuxTerminalContent(
        state = labTerminalUiState,
        atBottom = true,
        onBack = onExit,
        onOpenEnvironment = {},
        onNewTerminal = {},
        onRetry = {},
        onSelect = {},
        onClose = {},
        onKey = {},
        onToggleCtrl = {},
        onToggleAlt = {},
        onToggleKeyboard = {},
        onScrollToBottom = {},
        shellPane = { _, modifier -> LabTerminalPane(script, modifier) },
        imeVisible = false,
    )
}

/**
 * 真实的 SSH 终端页外壳：标题为主机别名，副标题只写“已连接”（不给主机配置，因此不显示账号与地址）。
 * 终端区与 [LabTerminalShell] 相同，由 [script] 按列数给出整屏文本；断开、按键等操作都是空操作。
 */
@Composable
internal fun LabSshShell(alias: String, onExit: () -> Unit, script: (columns: Int) -> String) {
    // 只用来让页面进入“已有终端缓冲”的形态，实际渲染由 LabTerminalPane 自己的缓冲完成。
    val placeholder = remember { RemoteTerminalBuffer() }
    SshTerminalContent(
        alias = alias,
        state = SshTerminalUiState(connecting = false, buffer = placeholder),
        readOnlyTabs = emptyList(),
        selectedTab = SSH_TERMINAL_MINE_TAB,
        atBottom = true,
        onSelectTab = {},
        onBack = onExit,
        onDisconnect = {},
        onReconnect = {},
        onKey = {},
        onToggleCtrl = {},
        onToggleAlt = {},
        onToggleKeyboard = {},
        onScrollToBottom = {},
        onStopExec = {},
        shellPane = { modifier -> LabTerminalPane(script, modifier) },
        readOnlyPane = { _, _, _, _, _ -> },
        imeVisible = false,
    )
}

/** 记录上次重放的输入，同样的文本、尺寸与配色不重复清屏。 */
private class ReplayMemo {
    var text: String? = null
    var grid: TerminalGrid? = null
    var theme: TerminalThemeColors? = null
}

/**
 * 与 SSH 用户终端相同的接线：[SshTerminalView] 附着一个 [RemoteTerminalBuffer]，由视图按自身尺寸、
 * 14sp 等宽字体计算网格并调整模拟器。不同之处：这里没有远端，视图禁用触摸与键盘；配色套用本地 Linux 终端的主题色。
 *
 * 每次文本、网格或配色变化时清空缓冲并整段重放：跳转、重播与尺寸变化都得到与时刻一致的画面。
 */
@Composable
private fun LabTerminalPane(script: (columns: Int) -> String, modifier: Modifier) {
    val buffer = remember { RemoteTerminalBuffer() }
    val memo = remember { ReplayMemo() }
    var grid by remember { mutableStateOf<TerminalGrid?>(null) }
    val theme = TerminalThemeColors.from(MaterialTheme.colorScheme)
    // 视图尚未布局时网格是最小值 2×2，等真实尺寸后再写入。
    val currentGrid = grid?.takeIf { it.columns > 2 && it.rows > 2 }
    val text = currentGrid?.let { script(it.columns) }

    AndroidView(
        factory = { context -> SshTerminalView(context) },
        update = { view ->
            // 禁用后点击不弹键盘、不能滚动；渲染不受影响。
            view.isEnabled = false
            view.onGridChanged = { grid = it }
            view.attach(buffer)
            if (text != null && (text != memo.text || currentGrid != memo.grid || theme != memo.theme)) {
                memo.text = text
                memo.grid = currentGrid
                memo.theme = theme
                replay(buffer, text, theme)
                view.scrollToLatest()
                view.invalidate()
            }
        },
        onRelease = { view -> view.detach() },
        modifier = modifier,
    )
}

/** clear() 会解除视图的重绘回调并把颜色表复位，因此先保存回调、清空后恢复，再套主题色后整段写入。 */
private fun replay(buffer: RemoteTerminalBuffer, text: String, theme: TerminalThemeColors) {
    val observer = buffer.onChanged
    buffer.clear()
    buffer.onChanged = observer
    TerminalThemeApplier.apply(buffer.emulator.mColors, theme)
    buffer.append(text)
}
