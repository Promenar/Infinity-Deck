package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.git.remote.GitHubDeviceFailure
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshTerminalIdlePolicy
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetSettings
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.data.tailnet.TailnetStatusSummary
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.ui.remote.RemoteAgentUsage
import com.promenar.infinity.ui.remote.remoteAgentUsageItems
import com.promenar.infinity.ui.ssh.SshTailnetAvailability
import com.promenar.infinity.ui.ssh.SshTailnetPickerContent
import com.promenar.infinity.ui.ssh.sshTailnetCandidates
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

// 远程面板追加项：从 Tailscale 选择主机、Tailscale 设备“SSH”入口、空闲自动断开与 GitHub 浏览器登录。只渲染展示状态。

// ── SSH 编辑面板的 Tailscale 入口 ──

@PreviewTest
@Preview(name = "SSH 添加主机 Tailscale 已选设备 深色中文", widthDp = 390, heightDp = 1200, locale = "zh-rCN")
@Composable
fun sshEditorTailnetSelectedDarkPreview() = EditorPreview(dark = true, tailnet = SshTailnetAvailability.CONNECTED, pick = LAPTOP)

@PreviewTest
@Preview(name = "SSH 添加主机 Tailscale 未连接 浅色英文 字号130", widthDp = 360, heightDp = 1500, locale = "en", fontScale = 1.3f)
@Composable
fun sshEditorTailnetDisconnectedPreview() = EditorPreview(dark = false, tailnet = SshTailnetAvailability.DISCONNECTED, pick = null)

@PreviewTest
@Preview(name = "从 Tailscale 选择 深色中文", widthDp = 390, heightDp = 640, locale = "zh-rCN")
@Preview(name = "从 Tailscale 选择 深色英文 字号130", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun sshTailnetPickerPreview() = Frame(dark = true) {
    SshTailnetPickerContent(sshTailnetCandidates(PEERS, HOSTS), "example-tailnet.ts.net") {}
}

// ── Tailscale 设备行的“SSH” ──

@PreviewTest
@Preview(name = "远程 Tailscale 设备添加为 SSH 主机 浅色中文", widthDp = 390, heightDp = 1500, locale = "zh-rCN")
@Preview(name = "远程 Tailscale 设备添加为 SSH 主机 浅色英文 字号130", widthDp = 360, heightDp = 2000, locale = "en", fontScale = 1.3f)
@Composable
fun tailscaleAddSshHostPreview() = Frame(dark = false) {
    TailscaleSettingsContent(state = RUNNING_STATE, sshHosts = HOSTS, onAddSshHost = {})
}

// ── 空闲自动断开 ──

@PreviewTest
@Preview(name = "SSH 主机设置 空闲断开与智能体默认开启 深色中文", widthDp = 390, heightDp = 1100, locale = "zh-rCN")
@Preview(name = "SSH 主机设置 空闲断开 深色英文 字号130", widthDp = 360, heightDp = 1500, locale = "en", fontScale = 1.3f)
@Composable
fun sshSettingsIdlePreview() = Frame(dark = true) {
    SshSettingsContent(
        state = SshSettingsVisualState(hosts = HOSTS, terminalIdlePolicy = SshTerminalIdlePolicy.MINUTES_30),
        actions = SshSettingsVisualActions(),
    )
}

// ── 远程页签：SSH 后台命令只给计数入口 ──

@PreviewTest
@Preview(name = "远程 SSH 智能体后台命令计数入口 深色中文", widthDp = 390, heightDp = 900, locale = "zh-rCN")
@Preview(name = "远程 SSH 智能体后台命令计数入口 浅色英文 字号130", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun remoteAgentJobsEntryPreview() = Frame(dark = true) {
    val usage = RemoteAgentUsage(jobs = listOf(sshJob("j1", "nas"), sshJob("j2", "build-server")))
    SshSettingsContent(
        state = SshSettingsVisualState(hosts = HOSTS),
        actions = SshSettingsVisualActions(),
        leadingContent = { remoteAgentUsageItems(usage, onOpenTerminal = { _, _ -> }, onOpenBackgroundJobs = {}) },
    )
}

private fun sshJob(id: String, host: String) = BackgroundJob(
    id = id, sessionId = "s", runner = BackgroundJobRunner.SSH, summary = "rsync -a /data/ backup:/srv/", state = BackgroundJobState.RUNNING,
    exitCode = null, startedAtMillis = 0L, endedAtMillis = null, maxRuntimeMinutes = 120, onExit = BackgroundJobExitAction.NOTIFY,
    logWorkspacePath = null, liveOutputKey = "job:$id", remoteHost = host,
)

// ── GitHub 浏览器登录 ──

@PreviewTest
@Preview(name = "GitHub 登录 显示代码 深色中文", widthDp = 390, heightDp = 420, locale = "zh-rCN")
@Composable
fun githubLoginCodePreview() = SheetPreview(dark = true, GitHubLoginState.AwaitingUser(CODE, URI, EXPIRES))

@PreviewTest
@Preview(name = "GitHub 登录 等待授权 浅色英文 字号130", widthDp = 360, heightDp = 480, locale = "en", fontScale = 1.3f)
@Composable
fun githubLoginWaitingPreview() = SheetPreview(dark = false, GitHubLoginState.AwaitingUser(CODE, URI, EXPIRES), opened = true)

@PreviewTest
@Preview(name = "GitHub 登录 成功 浅色中文", widthDp = 390, heightDp = 360, locale = "zh-rCN")
@Composable
fun githubLoginSuccessPreview() = SheetPreview(dark = false, GitHubLoginState.Success("promenar"))

@PreviewTest
@Preview(name = "GitHub 登录 代码过期 深色中文", widthDp = 390, heightDp = 360, locale = "zh-rCN")
@Composable
fun githubLoginExpiredPreview() = SheetPreview(dark = true, GitHubLoginState.Failed(GitHubDeviceFailure.EXPIRED))

@PreviewTest
@Preview(name = "GitHub 登录 已拒绝 浅色英文", widthDp = 390, heightDp = 360, locale = "en")
@Composable
fun githubLoginDeniedPreview() = SheetPreview(dark = false, GitHubLoginState.Failed(GitHubDeviceFailure.DENIED))

@Composable
private fun SheetPreview(dark: Boolean, state: GitHubLoginState, opened: Boolean = false) = Frame(dark) {
    Column(Modifier.fillMaxWidth().padding(InfinitySpacing.Large)) {
        // 固定时钟：剩余 13:42。
        GitHubLoginContent(state, GitHubLoginSheetActions(), initiallyOpened = opened, nowMillis = { EXPIRES - 822_000L })
    }
}

@Composable
private fun EditorPreview(dark: Boolean, tailnet: SshTailnetAvailability, pick: TailnetPeer?) = Frame(dark) {
    val fields = remember {
        SshHostEditorFields(null, pick?.let { sshTailnetCandidates(listOf(it), HOSTS).single() }).apply { username = "promenar" }
    }
    SshHostEditorForm(
        fields = fields,
        existing = null,
        aliasSuggestion = "laptop".takeIf { pick != null },
        busy = false,
        selectedKey = null,
        errorCode = null,
        scrollState = rememberScrollState(),
        onAuthTypeChange = {},
        onPickKey = {},
        onCancel = {},
        onSave = {},
        tailnet = tailnet,
    )
}

@Composable
private fun Frame(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLow) { content() }
    }
}

private const val CODE = "WDJB-MJHT"
private const val URI = "https://github.com/login/device"
private const val EXPIRES = 1_800_000_000_000L

private val LAPTOP = TailnetPeer("laptop", "laptop.example-tailnet.ts.net.", listOf("100.64.0.9"), "macOS", online = true)

private val PEERS = listOf(
    TailnetPeer("fnos", "fnos.example-tailnet.ts.net.", listOf("100.101.7.42"), "linux", online = true),
    TailnetPeer("build-server", "build-server.example-tailnet.ts.net.", listOf("100.64.0.8"), "linux", online = false),
    TailnetPeer("desktop", "desktop.example-tailnet.ts.net.", listOf("100.64.0.10"), "windows", online = true),
    LAPTOP,
    TailnetPeer("pixel-tablet", "pixel-tablet.example-tailnet.ts.net.", listOf("100.64.0.12"), "android", online = false),
)

private val HOSTS = listOf(
    SshHost(alias = "fnos", hostname = "100.101.7.42", username = "promenar"),
    SshHost(alias = "devbox", hostname = "192.168.1.20", username = "root"),
)

private val RUNNING_STATE = TailscaleSettingsUiState(
    available = true,
    settings = TailnetSettings(enabled = true),
    tailnet = TailnetState(
        phase = TailnetPhase.RUNNING,
        summary = TailnetStatusSummary(
            backendState = TailnetStatusSummary.STATE_RUNNING,
            selfName = "infinity-pixel",
            selfDnsName = "infinity-pixel.example-tailnet.ts.net",
            selfIps = listOf("100.101.102.103"),
            online = true,
            magicDnsSuffix = "example-tailnet.ts.net",
            tailnetName = "example-tailnet.ts.net",
        ),
        peers = PEERS,
    ),
)
