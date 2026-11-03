package com.promenar.infinity.ui.workspace

import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.LinuxWorkspaceLocation
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.termux.TermuxAvailability
import com.promenar.infinity.domain.model.ExecutionMode
import com.promenar.infinity.domain.network.AgentNetworkSnapshot
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.ui.settings.TermuxEntryStatus
import com.promenar.infinity.ui.settings.termuxEntryStatus
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxUnavailableReason
import com.promenar.infinity.domain.tool.LinuxExecRouting

/**
 * “智能体可达范围”面板的展示数据：当前会话的智能体此刻能碰到的工作区、文件位置、执行方式、审批与网络。
 * 只描述现状，不参与任何授权判定；判定仍由各工具与执行策略完成。
 */
internal data class AgentReachState(
    val workspace: SessionWorkspaceUi = SessionWorkspaceUi.Loading,
    val mounts: List<ReachMount> = emptyList(),
    val backends: List<ReachBackend> = emptyList(),
    val executionMode: ExecutionMode = ExecutionMode.WORKSPACE,
    /** 网络访问总表；尚未取得时为 null。 */
    val network: AgentNetworkSnapshot? = null,
    /** 侧载渠道才有共享存储工作区与路径挂载。 */
    val sideload: Boolean = true,
)

internal enum class ReachMountSource { SAF, PATH }

internal data class ReachMount(
    val alias: String,
    val source: ReachMountSource,
    /** 设备路径来源可被命令以真实路径访问；系统授权来源只对文件工具可见。 */
    val commandAccessible: Boolean,
    val unavailable: Boolean,
    /** 只读挂载的知识库；路径区以“知识库 · 只读”标签代替授权方式。 */
    val knowledgeBase: Boolean = false,
)

internal enum class ReachBackendKind { BUILTIN, SANDBOX, LINUX, TERMUX, SSH }

internal enum class ReachBackendStatus {
    READY,
    /** 内置进程只有系统 shell，打包 Python 不可用（非 arm64 设备等）。 */
    SHELL_ONLY,
    /** 隔离脚本只有 JavaScript，打包 Python 不可用。 */
    JS_ONLY,
    DISABLED,
    NOT_INSTALLED,
    PERMISSION_REQUIRED,
    UNSUPPORTED_VERSION,
    /** SSH 未登记任何主机。 */
    NO_HOSTS,
    LOADING,
    INSTALLING,
    BROKEN,
    UNAVAILABLE,
    /** 安装渠道不包含 Linux 环境：保留该行并灰显，说明原因。 */
    CHANNEL_UNAVAILABLE,
}

/** [count] 只用于 SSH 的已登记主机数。 */
internal data class ReachBackend(val kind: ReachBackendKind, val status: ReachBackendStatus, val count: Int? = null, val destination: String? = null)

internal fun linuxReachStatus(state: LinuxRuntimeState, agentEnabled: Boolean): ReachBackendStatus = when (state) {
    is LinuxRuntimeState.Unavailable ->
        if (state.reason == LinuxUnavailableReason.DISTRIBUTION) ReachBackendStatus.CHANNEL_UNAVAILABLE else ReachBackendStatus.UNAVAILABLE
    is LinuxRuntimeState.NotInstalled -> ReachBackendStatus.NOT_INSTALLED
    is LinuxRuntimeState.Installing, is LinuxRuntimeState.Maintaining -> ReachBackendStatus.INSTALLING
    is LinuxRuntimeState.InstallInterrupted, is LinuxRuntimeState.Broken -> ReachBackendStatus.BROKEN
    is LinuxRuntimeState.Ready -> if (agentEnabled) ReachBackendStatus.READY else ReachBackendStatus.DISABLED
}

/** 工作区类型决定的备份与可见性，与备份范围和执行层规则一一对应。 */
internal data class WorkspaceTraits(
    /** 只有本会话文件夹（Room 登记的文件）进入本地与 WebDAV 备份；共享文件夹、Linux 主目录与手机存储不进。 */
    val backedUp: Boolean,
    /** Termux 能直接看到这个目录（需 termux-setup-storage）。 */
    val termuxVisible: Boolean,
)

internal fun workspaceTraits(kind: String?): WorkspaceTraits = when (kind) {
    WorkspaceEntity.KIND_PROJECT -> WorkspaceTraits(backedUp = false, termuxVisible = false)
    WorkspaceEntity.KIND_SHARED -> WorkspaceTraits(backedUp = false, termuxVisible = true)
    WorkspaceEntity.KIND_LINUX_HOME -> WorkspaceTraits(backedUp = false, termuxVisible = false)
    else -> WorkspaceTraits(backedUp = true, termuxVisible = false)
}

/** 不可用的目录工作区不给出特征，避免描述一个当前不能使用的位置。 */
internal val SessionWorkspaceUi.traits: WorkspaceTraits?
    get() = when (this) {
        SessionWorkspaceUi.Private -> workspaceTraits(null)
        is SessionWorkspaceUi.Directory -> workspaceTraits(kind)
        else -> null
    }

internal fun ExternalMount.toReach(): ReachMount = ReachMount(
    alias = alias,
    source = if (sourceType == "real_path") ReachMountSource.PATH else ReachMountSource.SAF,
    commandAccessible = commandAccessible,
    unavailable = unavailableReason != null,
    knowledgeBase = sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE,
)

/** Linux 就绪且开启时 shell_exec 在 Alpine 中运行。只在会话尚未保存运行模式时用来推断。 */
internal fun List<ReachBackend>.linuxRunsShell(): Boolean =
    any { it.kind == ReachBackendKind.LINUX && it.status == ReachBackendStatus.READY }

/** 面板所有分区共用的运行模式：以会话保存的模式为准，未取得时按 Linux 环境是否接管 shell 推断。 */
internal fun reachRunMode(saved: SessionRunMode?, backends: List<ReachBackend>): SessionRunMode =
    saved ?: if (backends.linuxRunsShell()) SessionRunMode.LINUX else SessionRunMode.BUILTIN

internal enum class ReachPathKind { WORKSPACE, SESSION, MOUNT, SKILLS }

/** “文件位置”一行：含义由 [kind] 决定，[path] 是当前运行模式下智能体看到的路径。 */
internal data class ReachPath(val kind: ReachPathKind, val path: String, val mount: ReachMount? = null)

/**
 * 文件位置随运行模式切换：Linux 模式下文件工具与命令共用 guest 路径，内置模式下为文件工具的虚拟路径。
 * 本会话文件夹只在工作区不是它本身时单列。
 */
internal fun reachPaths(mode: SessionRunMode, workspace: SessionWorkspaceUi, mounts: List<ReachMount>): List<ReachPath> {
    val linux = mode == SessionRunMode.LINUX
    return buildList {
        add(ReachPath(ReachPathKind.WORKSPACE, if (linux) linuxWorkspacePath(workspace) else "/"))
        if (workspace is SessionWorkspaceUi.Directory || workspace is SessionWorkspaceUi.Unavailable) {
            add(ReachPath(ReachPathKind.SESSION, if (linux) LINUX_SESSION_PATH else BUILTIN_SESSION_PATH))
        }
        mounts.forEach { add(ReachPath(ReachPathKind.MOUNT, "/mnt/${it.alias}", it)) }
        add(ReachPath(ReachPathKind.SKILLS, if (linux) "/mnt/skills" else "/skills"))
    }
}

/** 绑定 Linux 目录时命令工作目录就是该目录；其它工作区经绑定挂到 /workspace。 */
internal fun linuxWorkspacePath(workspace: SessionWorkspaceUi): String =
    (workspace as? SessionWorkspaceUi.Directory)
        ?.takeIf { it.kind == WorkspaceEntity.KIND_LINUX_HOME }
        ?.let { LinuxWorkspaceLocation.parse(it.location)?.directory }
        ?: "/workspace"

private const val LINUX_SESSION_PATH = "/home/session"
private const val BUILTIN_SESSION_PATH = "/session"

/** “运行代码”一节：[shown] 逐项展开，[inactive] 合成一行“未启用”。 */
internal data class ReachRunners(
    val shown: List<ReachBackend>,
    val inactive: List<ReachBackendKind>,
    /** Linux 会话的工具集去掉了内置 Python（python_exec 改用 Linux 的 Python）、隔离脚本与 Termux，单独一行说明。 */
    val linuxOmitsBuiltins: Boolean,
)

/**
 * 只列本会话可用的执行方式，与 `SessionToolResolver.LINUX_HIDDEN_TOOLS` 一致：
 * Linux 模式为 Linux 命令与 SSH；内置模式为内置命令、隔离脚本、Termux 与 SSH。
 * Termux 未开启、SSH 未登记主机时归入“未启用”；仍在读取时暂不显示。
 */
internal fun reachRunners(mode: SessionRunMode, backends: List<ReachBackend>): ReachRunners {
    val linux = mode == SessionRunMode.LINUX
    val offered = if (linux) LINUX_RUNNERS else BUILTIN_RUNNERS
    val candidates = backends.filter { it.kind in offered }
    val inactive = candidates.filter { it.kind in OPTIONAL_RUNNERS && (it.status == ReachBackendStatus.DISABLED || it.status == ReachBackendStatus.NO_HOSTS) }
    return ReachRunners(
        shown = candidates.filter { it !in inactive && !(it.kind in OPTIONAL_RUNNERS && it.status == ReachBackendStatus.LOADING) },
        inactive = inactive.map { it.kind },
        linuxOmitsBuiltins = linux,
    )
}

private val LINUX_RUNNERS = setOf(ReachBackendKind.LINUX, ReachBackendKind.SSH)
private val BUILTIN_RUNNERS = setOf(ReachBackendKind.BUILTIN, ReachBackendKind.SANDBOX, ReachBackendKind.TERMUX, ReachBackendKind.SSH)
private val OPTIONAL_RUNNERS = setOf(ReachBackendKind.TERMUX, ReachBackendKind.SSH)

/** 用户级技能以保留别名 `skills` 出现在挂载列表中，面板单独说明，不作为普通挂载重复列出。 */
internal fun reachMounts(mounts: List<ExternalMount>): List<ReachMount> =
    mounts.filterNot { it.alias == ExternalMountPaths.SKILLS_ALIAS }.map(ExternalMount::toReach).sortedBy { it.alias }

/** 安装、版本与权限的判定沿用工具设置页入口的同一规则。 */
internal fun termuxReachStatus(enabled: Boolean, availability: TermuxAvailability?): ReachBackendStatus =
    if (!enabled) ReachBackendStatus.DISABLED else when (termuxEntryStatus(availability)) {
        TermuxEntryStatus.Loading -> ReachBackendStatus.LOADING
        TermuxEntryStatus.NotInstalled -> ReachBackendStatus.NOT_INSTALLED
        TermuxEntryStatus.UnsupportedVersion -> ReachBackendStatus.UNSUPPORTED_VERSION
        TermuxEntryStatus.PermissionRequired -> ReachBackendStatus.PERMISSION_REQUIRED
        TermuxEntryStatus.Available -> ReachBackendStatus.READY
    }

internal fun reachBackends(
    pythonAvailable: Boolean,
    termuxEnabled: Boolean,
    termuxAvailability: TermuxAvailability?,
    sshHostCount: Int?,
    linuxState: LinuxRuntimeState = LinuxExecRouting.state,
    linuxAgentEnabled: Boolean = LinuxExecRouting.agentEnabled,
): List<ReachBackend> = listOf(
    ReachBackend(ReachBackendKind.BUILTIN, if (pythonAvailable) ReachBackendStatus.READY else ReachBackendStatus.SHELL_ONLY),
    ReachBackend(ReachBackendKind.SANDBOX, if (pythonAvailable) ReachBackendStatus.READY else ReachBackendStatus.JS_ONLY),
    // 渠道不提供 Linux 环境时仍列出该行，状态为不可用并说明原因；该行不带跳转，不会让人误以为可安装。
    ReachBackend(ReachBackendKind.LINUX, linuxReachStatus(linuxState, linuxAgentEnabled), destination = "linux_environment"),
    ReachBackend(ReachBackendKind.TERMUX, termuxReachStatus(termuxEnabled, termuxAvailability)),
    ReachBackend(
        ReachBackendKind.SSH,
        when (sshHostCount) {
            null -> ReachBackendStatus.LOADING
            0 -> ReachBackendStatus.NO_HOSTS
            else -> ReachBackendStatus.READY
        },
        sshHostCount,
    ),
)
