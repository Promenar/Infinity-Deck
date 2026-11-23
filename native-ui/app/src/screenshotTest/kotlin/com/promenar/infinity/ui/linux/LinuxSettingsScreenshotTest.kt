package com.promenar.infinity.ui.linux

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxDiagnostics
import com.promenar.infinity.domain.linux.LinuxEnvironmentInfo
import com.promenar.infinity.domain.linux.LinuxFailure
import com.promenar.infinity.domain.linux.LinuxFailureCode
import com.promenar.infinity.domain.linux.LinuxInstallProgress
import com.promenar.infinity.domain.linux.LinuxInstallStep
import com.promenar.infinity.domain.linux.LinuxInstallStepState
import com.promenar.infinity.domain.linux.LinuxMirrorChoice
import com.promenar.infinity.domain.linux.LinuxMirrorId
import com.promenar.infinity.domain.linux.LinuxOptionalPackage
import com.promenar.infinity.domain.linux.LinuxPackage
import com.promenar.infinity.domain.linux.LinuxPackageAction
import com.promenar.infinity.domain.linux.LinuxPackageOperation
import com.promenar.infinity.domain.linux.LinuxPackageOrigin
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxStepDetail
import com.promenar.infinity.domain.linux.LinuxStepStatus
import com.promenar.infinity.domain.linux.LinuxStorageUsage
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.ui.common.InfinitySettingsPageLayout
import com.promenar.infinity.ui.linux.settings.LinuxEnvironmentActions
import com.promenar.infinity.ui.linux.settings.LinuxEnvironmentContent
import com.promenar.infinity.ui.linux.settings.LinuxEnvironmentUiState
import com.promenar.infinity.ui.linux.settings.LinuxMirrorsContent
import com.promenar.infinity.ui.linux.settings.LinuxMirrorsUiState
import com.promenar.infinity.ui.linux.settings.LinuxPackageFilter
import com.promenar.infinity.ui.linux.settings.LinuxPackagesActions
import com.promenar.infinity.ui.linux.settings.LinuxPackagesContent
import com.promenar.infinity.ui.linux.settings.LinuxPackagesUiState
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private const val MB = 1024L * 1024

@PreviewTest
@Preview(name = "Linux 安装引导 深色", widthDp = 412, heightDp = 1100, locale = "zh-rCN")
@Preview(name = "Linux 安装引导 深色 大字号130", widthDp = 360, heightDp = 1500, locale = "en", fontScale = 1.3f)
@Composable
fun linuxInstallGuidePreview() = LinuxEnvironmentPreview(LinuxRuntimeState.NotInstalled(118L * 1024 * MB), dark = true)

@PreviewTest
@Preview(name = "Linux 安装中 深色", widthDp = 412, heightDp = 1100, locale = "zh-rCN")
@Composable
fun linuxInstallingPreview() = LinuxEnvironmentPreview(LinuxRuntimeState.Installing(previewProgress()), dark = true)

@PreviewTest
@Preview(name = "Linux 安装失败 浅色", widthDp = 412, heightDp = 1100, locale = "zh-rCN")
@Composable
fun linuxInstallFailedPreview() = LinuxEnvironmentPreview(
    LinuxRuntimeState.InstallInterrupted(previewProgress(failed = true), LinuxFailure(LinuxFailureCode.NETWORK, "fetch failed: DNS")),
    dark = false,
)

@PreviewTest
@Preview(name = "Linux 环境管理 深色", widthDp = 412, heightDp = 1400, locale = "zh-rCN")
@Preview(name = "Linux 环境管理 深色 大字号130", widthDp = 360, heightDp = 1900, locale = "en", fontScale = 1.3f)
@Composable
fun linuxManagePreview() = LinuxEnvironmentPreview(
    previewReady(),
    dark = true,
    usage = LinuxStorageUsage(10 * MB, 186 * MB, 42 * MB, 23 * MB),
    optionalInstalled = LinuxOptionalPackage.entries.toSet(),
)

private fun previewReady() = LinuxRuntimeState.Ready(
    LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "5.1.107", 0, LinuxDiagnostics(true, System.currentTimeMillis() - 2 * 3_600_000L, 310, emptyList())),
)

@PreviewTest
@Preview(name = "Linux 安装引导 附加包与默认模式 深色", widthDp = 360, heightDp = 1500, locale = "zh-rCN")
@Composable
fun linuxInstallRunModeDarkPreview() = LinuxEnvironmentPreview(
    LinuxRuntimeState.NotInstalled(118L * 1024 * MB),
    dark = true,
    installPackages = setOf(LinuxOptionalPackage.GIT),
    installDefaultMode = SessionRunMode.BUILTIN,
)

@PreviewTest
@Preview(name = "Linux 安装引导 附加包与默认模式 浅色 大字号130", widthDp = 360, heightDp = 1900, locale = "en", fontScale = 1.3f)
@Composable
fun linuxInstallRunModeLightPreview() = LinuxEnvironmentPreview(
    LinuxRuntimeState.NotInstalled(150L * MB),
    dark = false,
)

@PreviewTest
@Preview(name = "Linux 环境管理 默认模式与补装 深色", widthDp = 360, heightDp = 1900, locale = "zh-rCN")
@Composable
fun linuxManageRunModeDarkPreview() = LinuxEnvironmentPreview(
    previewReady(),
    dark = true,
    usage = LinuxStorageUsage(10 * MB, 186 * MB, 42 * MB, 23 * MB),
    defaultMode = SessionRunMode.BUILTIN,
    optionalInstalled = setOf(LinuxOptionalPackage.GIT),
    optionalSelection = setOf(LinuxOptionalPackage.PYTHON3),
    packageOperation = LinuxPackageOperation(
        LinuxPackageAction.INSTALL, listOf("python3", "py3-pip"), 3, 9, 12 * MB, 18 * MB,
        "(3/9) Installing python3 (3.13.9-r0)\n  Fetching … 12.0 MB / 18.4 MB",
    ),
    installingOptional = true,
)

@PreviewTest
@Preview(name = "Linux 环境管理 默认模式与补装 浅色 大字号130", widthDp = 360, heightDp = 2300, locale = "en", fontScale = 1.3f)
@Composable
fun linuxManageRunModeLightPreview() = LinuxEnvironmentPreview(
    previewReady(),
    dark = false,
    usage = LinuxStorageUsage(10 * MB, 186 * MB, 42 * MB, 23 * MB),
    optionalInstalled = emptySet(),
    optionalSelection = setOf(LinuxOptionalPackage.GIT),
)

@PreviewTest
@Preview(name = "Linux 镜像源 深色", widthDp = 412, heightDp = 1100, locale = "zh-rCN")
@Composable
fun linuxMirrorsPreview() = PreviewTheme(dark = true, title = R.string.linux_mirrors_title) { modifier ->
    LinuxMirrorsContent(
        state = LinuxMirrorsUiState(
            selected = LinuxMirrorChoice(LinuxMirrorId.TUNA),
            latencies = mapOf(LinuxMirrorId.TUNA to 32L, LinuxMirrorId.USTC to 41L, LinuxMirrorId.ALIYUN to 47L, LinuxMirrorId.OFFICIAL to 412L),
        ),
        onSelect = {},
        onDone = {},
        modifier = modifier,
    )
}

@PreviewTest
@Preview(name = "Linux 软件包 搜索 深色", widthDp = 412, heightDp = 1400, locale = "zh-rCN")
@Preview(name = "Linux 软件包 搜索 深色 大字号130", widthDp = 360, heightDp = 1900, locale = "en", fontScale = 1.3f)
@Composable
fun linuxPackagesPreview() = PreviewTheme(dark = true, title = R.string.linux_packages_title) { modifier ->
    fun pkg(name: String, version: String, description: String?, size: Long?, installed: Boolean, available: String? = null, origin: LinuxPackageOrigin? = null) =
        LinuxPackage(name, version, description, size, installed, available, explicit = installed, origin = origin)
    LinuxPackagesContent(
        state = LinuxPackagesUiState(
            installed = listOf(
                pkg("python3", "3.13.9-r0", null, 51 * MB, true, "3.13.10-r0", LinuxPackageOrigin.Agent("s1", "数据清洗")),
                pkg("git", "2.52.0-r0", null, 14 * MB, true, origin = LinuxPackageOrigin.Preset),
                pkg("build-base", "0.5-r3", "gcc, make", 142 * MB, true, origin = LinuxPackageOrigin.Agent("s1", null)),
            ),
            filter = LinuxPackageFilter.ALL,
            query = "node",
            searchResults = listOf(
                pkg("nodejs", "24.11.0-r0", "JavaScript runtime", 18 * MB, false),
                pkg("npm", "11.6.2-r0", "Node package manager", 2 * MB, false),
            ),
            operation = LinuxPackageOperation(
                LinuxPackageAction.INSTALL, listOf("npm"), 1, 2, MB, 2 * MB,
                "(1/2) Installing npm (11.6.2-r0)\n  Fetching … 1.6 MB / 2.4 MB",
            ),
        ),
        actions = LinuxPackagesActions(),
        modifier = modifier,
    )
}

private fun previewProgress(failed: Boolean = false) = LinuxInstallProgress(
    steps = listOf(
        LinuxInstallStepState(LinuxInstallStep.UNPACK, LinuxStepStatus.DONE, 1_800, LinuxStepDetail.Unpacked(10_200_000, true)),
        LinuxInstallStepState(LinuxInstallStep.NETWORK, LinuxStepStatus.DONE, 400, LinuxStepDetail.Network(listOf("100.100.100.100"), viaVpn = true, proxyConfigured = true)),
        LinuxInstallStepState(
            LinuxInstallStep.COMMON_TOOLS,
            if (failed) LinuxStepStatus.FAILED else LinuxStepStatus.RUNNING,
            detail = LinuxStepDetail.Downloading(LinuxMirrorId.TUNA, 34 * MB, 58 * MB),
        ),
        LinuxInstallStepState(LinuxInstallStep.WORKSPACE, LinuxStepStatus.PENDING),
        LinuxInstallStepState(LinuxInstallStep.DIAGNOSTICS, LinuxStepStatus.PENDING),
    ),
    fraction = 0.58f,
    outputTail = "(12/31) Installing libcurl (8.17.0-r0)\n(13/31) Installing git (2.52.0-r0)\n(14/31) Installing python3 (3.13.9-r0)\n  Fetching … 21.4 MB / 34.0 MB",
)

@Composable
private fun LinuxEnvironmentPreview(
    runtime: LinuxRuntimeState,
    dark: Boolean,
    usage: LinuxStorageUsage? = null,
    defaultMode: SessionRunMode = SessionRunMode.LINUX,
    installPackages: Set<LinuxOptionalPackage> = LinuxOptionalPackage.entries.toSet(),
    installDefaultMode: SessionRunMode = SessionRunMode.LINUX,
    optionalInstalled: Set<LinuxOptionalPackage>? = null,
    optionalSelection: Set<LinuxOptionalPackage> = emptySet(),
    packageOperation: LinuxPackageOperation? = null,
    installingOptional: Boolean = false,
) =
    PreviewTheme(dark = dark, title = R.string.linux_title) { modifier ->
        LinuxEnvironmentContent(
            state = LinuxEnvironmentUiState(
                runtime = runtime,
                defaultMode = defaultMode,
                installPackages = installPackages,
                installDefaultMode = installDefaultMode,
                usage = usage,
                mirror = LinuxMirrorChoice(LinuxMirrorId.TUNA),
                mirrorLatencyMs = 32,
                optionalInstalled = optionalInstalled,
                optionalSelection = optionalSelection,
                packageOperation = packageOperation,
                installingOptional = installingOptional,
            ),
            actions = LinuxEnvironmentActions(),
            modifier = modifier,
        )
    }

@Composable
private fun PreviewTheme(dark: Boolean, title: Int, content: @Composable (Modifier) -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            InfinitySettingsPageLayout(
                title = stringResource(title),
                onBack = {},
                topContentPadding = 0.dp,
            ) { padding ->
                content(Modifier.padding(padding))
            }
        }
    }
}
