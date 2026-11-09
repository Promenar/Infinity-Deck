package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.termux.TermuxAvailability
import com.promenar.infinity.data.termux.TermuxCommandResult
import com.promenar.infinity.ui.common.InfinityCommandBlock
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinityStepperSetting
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "工具设置顶部 浅色", widthDp = 412, heightDp = 1700, locale = "en")
@Preview(name = "工具设置顶部 浅色 大字号130", widthDp = 412, heightDp = 2200, locale = "en", fontScale = 1.3f)
@Composable
fun toolsHeaderLightPreview() = ToolsHeaderPreview(dark = false)

@PreviewTest
@Preview(name = "工具设置顶部 深色", widthDp = 412, heightDp = 1700, locale = "zh-rCN")
@Preview(name = "工具设置顶部 深色 大字号130", widthDp = 412, heightDp = 2200, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun toolsHeaderDarkPreview() = ToolsHeaderPreview(dark = true)

@PreviewTest
@Preview(name = "Termux 未安装 浅色", widthDp = 412, heightDp = 3000, locale = "en")
@Preview(name = "Termux 未安装 浅色 大字号130", widthDp = 412, heightDp = 3700, locale = "en", fontScale = 1.3f)
@Composable
fun termuxNotInstalledLightPreview() = TermuxVisualPreview(dark = false, ready = false)

@PreviewTest
@Preview(name = "Termux 未安装 深色", widthDp = 412, heightDp = 3000, locale = "zh-rCN")
@Preview(name = "Termux 未安装 深色 大字号130", widthDp = 412, heightDp = 3700, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun termuxNotInstalledDarkPreview() = TermuxVisualPreview(dark = true, ready = false)

@PreviewTest
@Preview(name = "Termux 已就绪 浅色", widthDp = 412, heightDp = 3000, locale = "en")
@Preview(name = "Termux 已就绪 浅色 大字号130", widthDp = 412, heightDp = 3700, locale = "en", fontScale = 1.3f)
@Composable
fun termuxReadyLightPreview() = TermuxVisualPreview(dark = false, ready = true)

@PreviewTest
@Preview(name = "Termux 已就绪 深色", widthDp = 412, heightDp = 3000, locale = "zh-rCN")
@Preview(name = "Termux 已就绪 深色 大字号130", widthDp = 412, heightDp = 3700, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun termuxReadyDarkPreview() = TermuxVisualPreview(dark = true, ready = true)

@PreviewTest
@Preview(name = "SSH 空态 浅色", widthDp = 412, heightDp = 760, locale = "en")
@Preview(name = "SSH 空态 浅色 大字号130", widthDp = 412, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun sshEmptyLightPreview() = SshVisualPreview(dark = false, populated = false)

@PreviewTest
@Preview(name = "SSH 空态 深色", widthDp = 412, heightDp = 760, locale = "zh-rCN")
@Preview(name = "SSH 空态 深色 大字号130", widthDp = 412, heightDp = 1000, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sshEmptyDarkPreview() = SshVisualPreview(dark = true, populated = false)

@PreviewTest
@Preview(name = "SSH 主机列表 浅色", widthDp = 412, heightDp = 1900, locale = "en")
@Preview(name = "SSH 主机列表 浅色 大字号130", widthDp = 412, heightDp = 2500, locale = "en", fontScale = 1.3f)
@Composable
fun sshHostsLightPreview() = SshVisualPreview(dark = false, populated = true)

@PreviewTest
@Preview(name = "SSH 主机列表 深色", widthDp = 412, heightDp = 1900, locale = "zh-rCN")
@Preview(name = "SSH 主机列表 深色 大字号130", widthDp = 412, heightDp = 2500, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sshHostsDarkPreview() = SshVisualPreview(dark = true, populated = true)

@PreviewTest
@Preview(name = "命令块 浅色", widthDp = 412, heightDp = 460, locale = "en")
@Preview(name = "命令块 浅色 大字号130", widthDp = 412, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun commandBlockLightPreview() = CommandVisualPreview(dark = false)

@PreviewTest
@Preview(name = "命令块 深色", widthDp = 412, heightDp = 460, locale = "zh-rCN")
@Preview(name = "命令块 深色 大字号130", widthDp = 412, heightDp = 560, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun commandBlockDarkPreview() = CommandVisualPreview(dark = true)

@PreviewTest
@Preview(name = "步进设置行 浅色", widthDp = 412, heightDp = 820, locale = "en")
@Preview(name = "步进设置行 浅色 大字号130", widthDp = 412, heightDp = 1080, locale = "en", fontScale = 1.3f)
@Composable
fun stepperSettingLightPreview() = StepperVisualPreview(dark = false)

@PreviewTest
@Preview(name = "步进设置行 深色", widthDp = 412, heightDp = 820, locale = "zh-rCN")
@Preview(name = "步进设置行 深色 大字号130", widthDp = 412, heightDp = 1080, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun stepperSettingDarkPreview() = StepperVisualPreview(dark = true)

@PreviewTest
@Preview(name = "Termux 未安装下半页 浅色", widthDp = 412, heightDp = 2400, locale = "en")
@Preview(name = "Termux 未安装下半页 浅色 大字号130", widthDp = 412, heightDp = 3200, locale = "en", fontScale = 1.3f)
@Composable
fun termuxNotInstalledLowerLightPreview() = TermuxVisualPreview(dark = false, ready = false, lower = true)

@PreviewTest
@Preview(name = "Termux 未安装下半页 深色", widthDp = 412, heightDp = 2400, locale = "zh-rCN")
@Preview(name = "Termux 未安装下半页 深色 大字号130", widthDp = 412, heightDp = 3200, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun termuxNotInstalledLowerDarkPreview() = TermuxVisualPreview(dark = true, ready = false, lower = true)

@PreviewTest
@Preview(name = "Termux 已就绪下半页 浅色", widthDp = 412, heightDp = 2400, locale = "en")
@Preview(name = "Termux 已就绪下半页 浅色 大字号130", widthDp = 412, heightDp = 3200, locale = "en", fontScale = 1.3f)
@Composable
fun termuxReadyLowerLightPreview() = TermuxVisualPreview(dark = false, ready = true, lower = true)

@PreviewTest
@Preview(name = "Termux 已就绪下半页 深色", widthDp = 412, heightDp = 2400, locale = "zh-rCN")
@Preview(name = "Termux 已就绪下半页 深色 大字号130", widthDp = 412, heightDp = 3200, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun termuxReadyLowerDarkPreview() = TermuxVisualPreview(dark = true, ready = true, lower = true)

@Composable
private fun SettingsVisualTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun ToolsHeaderPreview(dark: Boolean) = SettingsVisualTheme(dark) {
    InfinitySettingsPageLayout(title = stringResource(R.string.skills_title), onBack = {}) { padding ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding)) {
            // 执行环境与连接各行已移到设置首页“执行环境与连接”分组。
            InfinitySettingsSection(title = stringResource(R.string.settings_ia_section_environment)) {
                EnvironmentConnectionRows(
                    state = EnvironmentConnectionState(
                        termuxAvailability = TermuxAvailability(true, "0.119.1", true),
                        sshHostCount = 2,
                    ),
                    onOpen = {},
                )
            }
        }
    }
}

@Composable
private fun TermuxVisualPreview(dark: Boolean, ready: Boolean, lower: Boolean = false) = SettingsVisualTheme(dark) {
    InfinitySettingsPageLayout(title = stringResource(R.string.termux_title), onBack = {}) { padding ->
        TermuxSettingsContent(
            state = TermuxSettingsUiState(
                enabled = ready,
                settingsLoaded = true,
                availability = TermuxAvailability(ready, if (ready) "0.119.1" else null, ready),
                diagnostic = if (ready) TermuxCommandResult(stdout = "INFINITY_TERMUX_OK\n", exitCode = 0) else null,
                keyHosts = if (ready) listOf(TermuxSettingsKeyHost("preview-key", "termux-local")) else emptyList(),
            ),
            contentPadding = padding,
            listState = rememberLazyListState(initialFirstVisibleItemIndex = if (lower) 4 else 0),
        )
    }
}

@Composable
private fun SshVisualPreview(dark: Boolean, populated: Boolean) = SettingsVisualTheme(dark) {
    SshSettingsContent(
        state = SshSettingsVisualState(
            hosts = if (populated) listOf(
                SshHost(
                    id = "preview-production", alias = "production", hostname = "server.example.com", username = "deploy",
                    trustedKeyType = "ssh-ed25519", trustedFingerprint = "SHA256:preview-public-fingerprint",
                    keepTerminal = true, localForwardPort = 8080, forwardPort = 3000,
                ),
                SshHost(
                    id = "preview-lab", alias = "homelab", hostname = "long-hostname.home-network.example.com",
                    username = "developer", authType = SshAuthType.PASSWORD,
                ),
            ) else emptyList(),
            forwards = if (populated) mapOf("production" to 8080) else emptyMap(),
        ),
        actions = SshSettingsVisualActions(),
    )
}

@Composable
private fun CommandVisualPreview(dark: Boolean) = SettingsVisualTheme(dark) {
    Column(
        modifier = Modifier.fillMaxSize().padding(InfinitySpacing.Large),
        verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Large),
    ) {
        InfinityCommandBlock(
            command = "sed -i '/^allow-external-apps=/d' ~/.termux/termux.properties && printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && termux-reload-settings",
        )
        InfinityCommandBlock(command = "pkg install openssh\nwhoami", enabled = false)
    }
}

@Composable
private fun StepperVisualPreview(dark: Boolean) = SettingsVisualTheme(dark) {
    Column(Modifier.fillMaxSize().padding(InfinitySpacing.Large)) {
        InfinitySettingsSection(stringResource(R.string.settings_execution_budget)) {
            InfinityStepperSetting(
                title = stringResource(R.string.p0_repeat_limit), value = "2",
                canDecrease = false, onDecrease = {}, onIncrease = {},
            )
            InfinityStepperSetting(
                title = stringResource(R.string.p0_repeat_limit), value = "3",
                onDecrease = {}, onIncrease = {},
            )
            InfinityStepperSetting(
                title = stringResource(R.string.p0_repeat_limit), value = "20",
                canIncrease = false, onDecrease = {}, onIncrease = {},
            )
        }
    }
}
