package com.promenar.infinity.ui.linux.settings

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.domain.linux.LinuxFailureCode
import com.promenar.infinity.domain.linux.LinuxInstallStep
import com.promenar.infinity.domain.linux.LinuxMirrorChoice
import com.promenar.infinity.domain.linux.LinuxMirrorId
import com.promenar.infinity.domain.linux.LinuxOptionalPackage
import java.net.URI

/** 文件大小按系统区域格式（例如 “186 MB”）。 */
@Composable
internal fun linuxBytes(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes.coerceAtLeast(0))

/** 一秒以内显示毫秒，否则保留一位小数的秒。 */
@Composable
internal fun linuxDuration(millis: Long): String =
    if (millis < 1_000) stringResource(R.string.linux_milliseconds, millis.toInt())
    else stringResource(R.string.linux_seconds, millis / 1_000.0)

@Composable
internal fun linuxRelativeTime(atMs: Long): String =
    DateUtils.getRelativeTimeSpanString(atMs, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

@StringRes
internal fun linuxMirrorName(id: LinuxMirrorId): Int = when (id) {
    LinuxMirrorId.TUNA -> R.string.linux_mirror_tuna
    LinuxMirrorId.USTC -> R.string.linux_mirror_ustc
    LinuxMirrorId.ALIYUN -> R.string.linux_mirror_aliyun
    LinuxMirrorId.OFFICIAL -> R.string.linux_mirror_official
    LinuxMirrorId.CUSTOM -> R.string.linux_mirror_custom
}

/** 自定义镜像显示主机名，其余显示预置名称。 */
@Composable
internal fun linuxMirrorLabel(choice: LinuxMirrorChoice): String =
    if (choice.id == LinuxMirrorId.CUSTOM) customMirrorHost(choice.customUrl) ?: stringResource(R.string.linux_mirror_custom)
    else stringResource(linuxMirrorName(choice.id))

internal fun customMirrorHost(url: String?): String? =
    url?.let { runCatching { URI(it.trim()).host }.getOrNull() }?.takeIf(String::isNotBlank)

/** 自定义镜像只接受带主机名的 https 地址。 */
internal fun isValidCustomMirror(url: String): Boolean {
    val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
    return uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()
}

@StringRes
internal fun linuxStepTitle(step: LinuxInstallStep): Int = when (step) {
    LinuxInstallStep.UNPACK -> R.string.linux_step_unpack
    LinuxInstallStep.NETWORK -> R.string.linux_step_network
    LinuxInstallStep.COMMON_TOOLS -> R.string.linux_step_tools
    LinuxInstallStep.WORKSPACE -> R.string.linux_step_workspace
    LinuxInstallStep.DIAGNOSTICS -> R.string.linux_step_diagnostics
}

@StringRes
internal fun linuxStepPendingText(step: LinuxInstallStep): Int = when (step) {
    LinuxInstallStep.UNPACK -> R.string.linux_step_unpack_pending
    LinuxInstallStep.NETWORK -> R.string.linux_step_network_pending
    LinuxInstallStep.COMMON_TOOLS -> R.string.linux_step_tools_pending
    LinuxInstallStep.WORKSPACE -> R.string.linux_step_workspace_pending
    LinuxInstallStep.DIAGNOSTICS -> R.string.linux_step_diagnostics_pending
}

@StringRes
internal fun linuxFailureText(code: LinuxFailureCode): Int = when (code) {
    LinuxFailureCode.INSUFFICIENT_STORAGE -> R.string.linux_failure_storage
    LinuxFailureCode.ASSET_CORRUPT -> R.string.linux_failure_asset
    LinuxFailureCode.EXTRACT_FAILED -> R.string.linux_failure_extract
    LinuxFailureCode.NETWORK -> R.string.linux_failure_network
    LinuxFailureCode.PACKAGE_INSTALL_FAILED -> R.string.linux_failure_packages
    LinuxFailureCode.DIAGNOSTICS_FAILED -> R.string.linux_failure_diagnostics
    LinuxFailureCode.PROOT_MISSING -> R.string.linux_failure_proot
    LinuxFailureCode.ROOTFS_DAMAGED -> R.string.linux_failure_rootfs
    LinuxFailureCode.IO -> R.string.linux_failure_io
}

/** 基础系统所需空间：解包约 10 MB，加 bash、curl、证书与下载缓存余量。 */
internal const val LINUX_BASE_REQUIRED_BYTES = 64L * 1024 * 1024

/** git 及其依赖安装后约 15 MB，按下载缓存与余量估算。 */
internal const val LINUX_GIT_REQUIRED_BYTES = 36L * 1024 * 1024

/** python3 与 py3-pip 安装后约 60 MB，按下载缓存与余量估算。 */
internal const val LINUX_PYTHON3_REQUIRED_BYTES = 100L * 1024 * 1024

/** ripgrep 安装后约 3 MB（Alpine 3.24 aarch64，下载约 1.3 MB），加下载缓存与余量。 */
internal const val LINUX_RIPGREP_REQUIRED_BYTES = 8L * 1024 * 1024

/** nodejs 与 npm 在已装 git、Python 之上新增约 63 MB（下载约 23 MB），加下载缓存与余量。 */
internal const val LINUX_NODEJS_REQUIRED_BYTES = 100L * 1024 * 1024

/** ffmpeg 连同约百个编解码依赖新增约 99 MB（下载约 42 MB），加下载缓存与余量；全选约 470 MB。 */
internal const val LINUX_FFMPEG_REQUIRED_BYTES = 160L * 1024 * 1024

/** 单项附加包的空间估算。 */
internal fun linuxOptionalPackageBytes(item: LinuxOptionalPackage): Long = when (item) {
    LinuxOptionalPackage.GIT -> LINUX_GIT_REQUIRED_BYTES
    LinuxOptionalPackage.RIPGREP -> LINUX_RIPGREP_REQUIRED_BYTES
    LinuxOptionalPackage.PYTHON3 -> LINUX_PYTHON3_REQUIRED_BYTES
    LinuxOptionalPackage.NODEJS -> LINUX_NODEJS_REQUIRED_BYTES
    LinuxOptionalPackage.FFMPEG -> LINUX_FFMPEG_REQUIRED_BYTES
}

/** 安装前要求的最小剩余空间：基础系统加所勾选的附加包。 */
internal fun linuxRequiredBytes(selection: Set<LinuxOptionalPackage>): Long =
    LINUX_BASE_REQUIRED_BYTES + selection.sumOf(::linuxOptionalPackageBytes)
