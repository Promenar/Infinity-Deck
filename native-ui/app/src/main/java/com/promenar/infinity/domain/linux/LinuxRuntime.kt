package com.promenar.infinity.domain.linux

import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * 内置 Linux 环境（proot + Alpine）的运行时契约。
 *
 * 侧载版由进程内实现提供；Play 版或不支持的 ABI 恒为 [LinuxRuntimeState.Unavailable]。
 * 将来的“Linux 扩展应用”实现同样实现本接口，调用方不感知差异。
 */
interface LinuxRuntime {
    val state: StateFlow<LinuxRuntimeState>

    /** 新会话默认使用 Linux；已确定运行模式的会话不随此偏好变化。默认开启。 */
    val agentEnabled: StateFlow<Boolean>

    /** 兼容旧调用方的默认偏好可用性查询；命令路由必须使用会话运行模式。 */
    val agentUsesLinux: Boolean
        get() = agentEnabled.value && state.value is LinuxRuntimeState.Ready

    suspend fun setAgentEnabled(enabled: Boolean)

    /**
     * 开始安装或从中断处继续，立即返回；进度经 [state] 发布。安装在应用级作用域运行，离开页面不中断。
     * 已就绪时无操作。
     */
    fun startInstall(options: LinuxInstallOptions)

    /** 取消正在进行的安装；已解包的内容保留，状态转为 [LinuxRuntimeState.InstallInterrupted]。 */
    fun cancelInstall()

    /** 补齐缺失基础文件与基础软件包，保留用户目录、已有文件和包数据库。进度经 [state] 发布。 */
    suspend fun repair(): LinuxRepairResult = LinuxRepairResult(false, LinuxFailure(LinuxFailureCode.ROOTFS_DAMAGED))

    /** 统计占用；目录遍历在 IO 线程完成。 */
    suspend fun storageUsage(): LinuxStorageUsage

    /** 删除 apk 下载缓存，返回释放的字节数。 */
    suspend fun clearDownloadCache(): Long

    /** 运行一组固定的诊断命令并更新 [LinuxEnvironmentInfo.lastDiagnostics]。 */
    suspend fun runDiagnostics(): LinuxDiagnostics

    /** 删除已装软件包与 `/root`，恢复为内置 rootfs；工作区文件不受影响。 */
    suspend fun reset()

    /** 删除整个环境，状态回到 [LinuxRuntimeState.NotInstalled]。 */
    suspend fun uninstall()

    /**
     * 为一次进程启动生成可直接交给 ProcessBuilder 或 PTY 的参数。
     * 每次调用都会按当前网络刷新 `resolv.conf`。环境未就绪时抛出 [LinuxUnavailableException]。
     */
    suspend fun prepareLaunch(spec: LinuxLaunchSpec): LinuxProcessLaunch

    /** 宿主侧目录；未安装时目录可能不存在。 */
    val paths: LinuxPaths
}

class LinuxUnavailableException(val state: LinuxRuntimeState) :
    IllegalStateException("Linux environment is not ready: $state")

sealed interface LinuxRuntimeState {
    data class Unavailable(val reason: LinuxUnavailableReason) : LinuxRuntimeState

    /** [freeBytes] 为数据分区剩余空间，用于安装引导页。 */
    data class NotInstalled(val freeBytes: Long) : LinuxRuntimeState

    data class Installing(val progress: LinuxInstallProgress) : LinuxRuntimeState

    /** 安装被取消或失败；再次 [LinuxRuntime.startInstall] 会从中断处继续。[failure] 为 null 表示用户取消。 */
    data class InstallInterrupted(
        val progress: LinuxInstallProgress,
        val failure: LinuxFailure?,
    ) : LinuxRuntimeState

    data class Ready(val info: LinuxEnvironmentInfo) : LinuxRuntimeState

    /** 重置或卸载进行中。 */
    data class Maintaining(val operation: LinuxMaintenance, val progress: LinuxInstallProgress? = null) : LinuxRuntimeState

    /** 已安装但无法运行，例如 rootfs 缺失关键文件或 proot 库不存在；界面提供重置与卸载。 */
    data class Broken(val failure: LinuxFailure) : LinuxRuntimeState
}

enum class LinuxUnavailableReason { DISTRIBUTION, UNSUPPORTED_ABI }

enum class LinuxMaintenance { RESETTING, UNINSTALLING }

/** 失败的分类码加上一段可展示的英文/原始细节（界面按 [code] 选文案，[detail] 折叠显示）。 */
data class LinuxFailure(val code: LinuxFailureCode, val detail: String? = null)

enum class LinuxFailureCode {
    INSUFFICIENT_STORAGE,
    ASSET_CORRUPT,
    EXTRACT_FAILED,
    NETWORK,
    PACKAGE_INSTALL_FAILED,
    DIAGNOSTICS_FAILED,
    PROOT_MISSING,
    ROOTFS_DAMAGED,
    IO,
}

data class LinuxInstallOptions(
    /** 兼容入口；false 表示不选附加包，基础包始终安装。显式 [optionalPackages] 优先。 */
    val installCommonTools: Boolean = true,
    val optionalPackages: Set<LinuxOptionalPackage> = if (installCommonTools) LinuxOptionalPackage.entries.toSet() else emptySet(),
) {
    val packages: List<String> get() = LinuxPackagePresets.BASE + LinuxPackagePresets.optional(optionalPackages)
}

data class LinuxRepairResult(val success: Boolean, val failure: LinuxFailure? = null)

enum class LinuxInstallStep { UNPACK, NETWORK, COMMON_TOOLS, WORKSPACE, DIAGNOSTICS }

enum class LinuxStepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

data class LinuxInstallStepState(
    val step: LinuxInstallStep,
    val status: LinuxStepStatus,
    val durationMs: Long? = null,
    /** 步骤的结构化细节，界面按步骤类型格式化。 */
    val detail: LinuxStepDetail? = null,
)

sealed interface LinuxStepDetail {
    data class Unpacked(val bytes: Long, val sha256Verified: Boolean) : LinuxStepDetail
    data class Network(val dnsServers: List<String>, val viaVpn: Boolean, val proxyConfigured: Boolean) : LinuxStepDetail
    data class Downloading(val mirror: LinuxMirrorId, val downloadedBytes: Long, val totalBytes: Long?) : LinuxStepDetail
    data class Diagnostics(val startupMs: Long) : LinuxStepDetail
}

data class LinuxInstallProgress(
    val steps: List<LinuxInstallStepState>,
    /** 0..1；无法估计时为 null。 */
    val fraction: Float?,
    /** 当前步骤的实时输出尾部（最多约 4 KB）。 */
    val outputTail: String = "",
) {
    val current: LinuxInstallStepState? get() = steps.firstOrNull { it.status == LinuxStepStatus.RUNNING }
}

data class LinuxEnvironmentInfo(
    val distribution: String,
    val version: String,
    val architecture: String,
    val prootVersion: String,
    val installedAtMs: Long,
    val lastDiagnostics: LinuxDiagnostics?,
)

data class LinuxDiagnostics(
    val passed: Boolean,
    val atMs: Long,
    /** 冷启动一次 `true` 的耗时。 */
    val startupMs: Long?,
    val checks: List<LinuxDiagnosticCheck>,
)

data class LinuxDiagnosticCheck(val id: String, val passed: Boolean, val detail: String? = null)

/** 各部分占用；[packagesBytes] = rootfs 总量 − 基础系统 − 主目录 − 下载缓存。 */
data class LinuxStorageUsage(
    val baseBytes: Long,
    val packagesBytes: Long,
    val homeBytes: Long,
    val cacheBytes: Long,
) {
    val totalBytes: Long get() = baseBytes + packagesBytes + homeBytes + cacheBytes
}

data class LinuxPaths(
    /** rootfs 的宿主目录（容器内的 `/`）。 */
    val rootfs: File,
    /** 容器内 `/root` 对应的宿主目录。 */
    val home: File,
)

/** 一次进程启动的意图；由运行时翻译成 proot 参数。 */
data class LinuxLaunchSpec(
    /** 容器内执行的 argv；为空时启动交互式登录 shell（有 bash 用 bash，否则 sh）。 */
    val argv: List<String>,
    /** 绑定到 `/workspace` 的宿主目录；为 null 时不绑定。 */
    val workspace: File? = null,
    /** 额外绑定，guest 路径必须位于 `/mnt/` 下。 */
    val mounts: List<LinuxBind> = emptyList(),
    /** 容器内的工作目录；为 null 时有工作区用 `/workspace`，否则 `/root`。 */
    val workingDirectory: String? = null,
    /** 追加的环境变量，覆盖默认值（PATH、HOME、LANG、TERM、代理、PIP_BREAK_SYSTEM_PACKAGES 等）。 */
    val environment: Map<String, String> = emptyMap(),
    /** 会话私有空间，仅绑定到 `/home/session`，独立于当前主工作区。 */
    val sessionHome: File? = null,
    /** 会话脚本唤醒收件箱，宿主侧 box 目录；无会话的维护启动不绑定。 */
    val wakeInbox: File? = null,
) {
    companion object {
        /** `sh -c <command>`；有 bash 时仍用 POSIX sh，保持与系统 sh 一致的语义。 */
        fun shell(command: String, workspace: File? = null, mounts: List<LinuxBind> = emptyList()) =
            LinuxLaunchSpec(listOf("/bin/sh", "-c", command), workspace, mounts)
    }
}

data class LinuxBind(val host: File, val guestPath: String, val readOnly: Boolean = false)

/** proot 启动参数。调用方负责进程组、超时与输出采集。 */
data class LinuxProcessLaunch(
    val executable: String,
    val arguments: List<String>,
    /** 完整环境（不继承宿主环境）。 */
    val environment: Map<String, String>,
    /** 宿主侧工作目录，供 ProcessBuilder.directory 使用。 */
    val hostWorkingDirectory: File,
    val memoryBudgetBytes: Long? = null,
) {
    val command: List<String> get() = listOf(executable) + arguments

    override fun toString(): String = "LinuxProcessLaunch(arguments=${arguments.size}, environmentKeys=${environment.keys})"
}
