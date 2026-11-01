package com.promenar.infinity.data.linux

import android.content.Context
import com.promenar.infinity.data.exec.ExecKeepAlive
import com.promenar.infinity.distribution.DistributionCapabilities
import com.promenar.infinity.domain.linux.LinuxFileSystem
import com.promenar.infinity.domain.linux.LinuxMirrorSettings
import com.promenar.infinity.domain.linux.LinuxPackageManager
import com.promenar.infinity.domain.linux.LinuxPackageProvenance
import com.promenar.infinity.domain.linux.LinuxPaths
import com.promenar.infinity.domain.linux.LinuxRuntime
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxTerminalSessions
import com.promenar.infinity.domain.linux.LinuxUnavailableReason
import kotlinx.coroutines.CoroutineScope
import java.io.File

/**
 * 内置 Linux 环境的装配点。界面与执行层只依赖 domain/linux 的接口。
 * 各实现分块装配，块之间互不依赖实现细节。
 */
class LinuxModule(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: LinuxHostServices,
) {
    val paths: LinuxPaths by lazy {
        val rootfs = File(context.filesDir, "linux/alpine")
        LinuxPaths(rootfs = rootfs, home = File(rootfs, "root"))
    }

    // ── 运行时：打包的 proot、rootfs 安装与启动参数 ──
    val runtime: LinuxRuntime by lazy {
        when {
            !DistributionCapabilities.linuxRuntimeAvailable ->
                UnavailableLinuxRuntime(LinuxUnavailableReason.DISTRIBUTION, paths)
            android.os.Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a" ->
                UnavailableLinuxRuntime(LinuxUnavailableReason.UNSUPPORTED_ABI, paths)
            else -> ProcessLinuxRuntime.create(context, scope, paths, host.keepAlive, { packages }, { mirrors })
        }
    }

    /** 渠道或 ABI 不支持时各模块保留不可用实现；该状态在进程内不会变化。 */
    private val unavailable: Boolean get() = runtime.state.value is LinuxRuntimeState.Unavailable

    // ── 软件包、来源记录与镜像 ──
    private val unavailablePackages by lazy { UnavailableLinuxPackages(runtime) }
    private val packageBackend by lazy { LinuxPackageBackend(context, runtime, host) }
    val packages: LinuxPackageManager get() = if (unavailable) unavailablePackages else packageBackend.packages
    val provenance: LinuxPackageProvenance get() = if (unavailable) unavailablePackages else packageBackend.provenance
    val mirrors: LinuxMirrorSettings get() = if (unavailable) unavailablePackages else packageBackend.mirrors

    // ── 文件浏览 ──
    val files: LinuxFileSystem by lazy {
        if (unavailable) UnavailableLinuxFiles()
        else LinuxFileSystemImpl(runtime)
    }

    // ── 本地终端 ──
    val terminals: LinuxTerminalSessions by lazy {
        if (unavailable) UnavailableLinuxTerminals()
        else LinuxTerminalSessionsImpl(runtime, scope, host, sessionHome = host.sessionHome, reconcileSession = host.reconcileSession)
    }
}

/** 应用提供给 Linux 环境实现的宿主服务。 */
class LinuxHostServices(
    /** 安装、后台命令与终端会话的前台服务保活。 */
    val keepAlive: ExecKeepAlive,
    /** 会话私有目录，独立于会话选中的主工作区。 */
    val sessionHome: suspend (sessionId: String) -> File? = { null },
    /** 终端启动与结束时请求整理会话私有索引，不等待全量扫描。 */
    val reconcileSession: suspend (sessionId: String) -> Unit = {},
    /** 登记活跃写入，返回在终端退出后归还的租约。 */
    val beginSessionMutation: suspend (sessionId: String) -> (suspend () -> Unit) = { {} },
    /** 准备会话收件箱并开始监听；维护终端不调用。 */
    val wakeInbox: suspend (sessionId: String) -> File? = { null },
    /** 会话工作区的宿主根目录，不持工作区写锁；会话工作区不可用时为 null。 */
    val sessionWorkspaceRoot: suspend (sessionId: String) -> File?,
)
