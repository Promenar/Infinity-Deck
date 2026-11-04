package com.promenar.infinity.ui.workspace

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.data.termux.TermuxAvailability
import com.promenar.infinity.domain.linux.LinuxEnvironmentInfo
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.domain.linux.LinuxUnavailableReason
import com.promenar.infinity.domain.network.AgentNetworkSnapshot
import com.promenar.infinity.domain.network.NetworkAccessState
import com.promenar.infinity.domain.network.NetworkTargetClass
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.ui.settings.NetworkCellMeaning
import com.promenar.infinity.ui.settings.NetworkCellTone
import com.promenar.infinity.ui.settings.networkCell
import org.junit.Test

class AgentReachModelsTest {
    @Test
    fun `只有本会话文件夹进备份，共享文件夹、Linux 主目录与手机存储不进`() {
        assertThat(workspaceTraits(null)).isEqualTo(WorkspaceTraits(backedUp = true, termuxVisible = false))
        assertThat(workspaceTraits(WorkspaceEntity.KIND_PROJECT)).isEqualTo(WorkspaceTraits(backedUp = false, termuxVisible = false))
        assertThat(workspaceTraits(WorkspaceEntity.KIND_SHARED)).isEqualTo(WorkspaceTraits(backedUp = false, termuxVisible = true))
        assertThat(workspaceTraits(WorkspaceEntity.KIND_LINUX_HOME)).isEqualTo(WorkspaceTraits(backedUp = false, termuxVisible = false))
    }

    @Test
    fun `Linux 工作区路径取绑定的 Linux 目录，其它工作区为 workspace 挂载点`() {
        val identity = com.promenar.infinity.data.repository.PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
        assertThat(linuxWorkspacePath(SessionWorkspaceUi.Directory("w", "demo", WorkspaceEntity.KIND_LINUX_HOME, "/opt/demo#$identity"))).isEqualTo("/opt/demo")
        assertThat(linuxWorkspacePath(SessionWorkspaceUi.Directory("w", "repo", WorkspaceEntity.KIND_SHARED, "/storage/emulated/0/repo"))).isEqualTo("/workspace")
        assertThat(linuxWorkspacePath(SessionWorkspaceUi.Directory("w", "bad", WorkspaceEntity.KIND_LINUX_HOME, "/etc#$identity"))).isEqualTo("/workspace")
        assertThat(linuxWorkspacePath(SessionWorkspaceUi.Private)).isEqualTo("/workspace")
    }

    @Test
    fun `unavailable and loading workspaces expose no traits`() {
        assertThat(SessionWorkspaceUi.Loading.traits).isNull()
        assertThat(SessionWorkspaceUi.Unavailable("w", null, null, null, "gone").traits).isNull()
        assertThat(SessionWorkspaceUi.Directory("w", "repo", WorkspaceEntity.KIND_SHARED, "/storage/emulated/0/repo").traits?.termuxVisible).isTrue()
    }

    @Test
    fun `挂载隐藏保留技能别名并按会话别名排序`() {
        val mounts = listOf(
            mount("skills", "builtin_skills", "s1"),
            mount("termux-home", "saf_tree", "s1"),
            mount("downloads", "real_path", "s1"),
            mount("broken", "saf_tree", "s1", unavailable = "revoked"),
        )
        val reach = reachMounts(mounts)
        assertThat(reach.map { it.alias }).containsExactly("broken", "downloads", "termux-home").inOrder()
        assertThat(reach.first { it.alias == "downloads" }).isEqualTo(ReachMount("downloads", ReachMountSource.PATH, commandAccessible = true, unavailable = false))
        assertThat(reach.first { it.alias == "termux-home" }.commandAccessible).isFalse()
        assertThat(reach.first { it.alias == "broken" }.unavailable).isTrue()
    }

    @Test
    fun `termux status is disabled before the integration is turned on`() {
        assertThat(termuxReachStatus(false, TermuxAvailability(true, "0.119.1", true))).isEqualTo(ReachBackendStatus.DISABLED)
        assertThat(termuxReachStatus(true, null)).isEqualTo(ReachBackendStatus.LOADING)
        assertThat(termuxReachStatus(true, TermuxAvailability(false, null, false))).isEqualTo(ReachBackendStatus.NOT_INSTALLED)
        assertThat(termuxReachStatus(true, TermuxAvailability(true, "0.108.0", true))).isEqualTo(ReachBackendStatus.UNSUPPORTED_VERSION)
        assertThat(termuxReachStatus(true, TermuxAvailability(true, "0.119.1", false))).isEqualTo(ReachBackendStatus.PERMISSION_REQUIRED)
        assertThat(termuxReachStatus(true, TermuxAvailability(true, "0.119.1", true))).isEqualTo(ReachBackendStatus.READY)
    }

    @Test
    fun `backends report missing python and missing ssh hosts`() {
        val backends = reachBackends(
            pythonAvailable = false, termuxEnabled = false, termuxAvailability = null, sshHostCount = 0,
            linuxState = LinuxRuntimeState.NotInstalled(0), linuxAgentEnabled = true,
        )
        assertThat(backends.map { it.kind }).containsExactlyElementsIn(ReachBackendKind.entries).inOrder()
        assertThat(backends.first { it.kind == ReachBackendKind.BUILTIN }.status).isEqualTo(ReachBackendStatus.SHELL_ONLY)
        assertThat(backends.first { it.kind == ReachBackendKind.SANDBOX }.status).isEqualTo(ReachBackendStatus.JS_ONLY)
        assertThat(backends.first { it.kind == ReachBackendKind.SSH }.status).isEqualTo(ReachBackendStatus.NO_HOSTS)
        val ready = reachBackends(pythonAvailable = true, termuxEnabled = false, termuxAvailability = null, sshHostCount = 3)
        assertThat(ready.first { it.kind == ReachBackendKind.SSH }).isEqualTo(ReachBackend(ReachBackendKind.SSH, ReachBackendStatus.READY, 3))
        assertThat(reachBackends(true, false, null, null).first { it.kind == ReachBackendKind.SSH }.status).isEqualTo(ReachBackendStatus.LOADING)
    }

    @Test
    fun `linux row stays listed and is marked channel-unavailable when the distribution has no linux environment`() {
        fun linux(state: LinuxRuntimeState) =
            reachBackends(true, false, null, 0, linuxState = state, linuxAgentEnabled = true).firstOrNull { it.kind == ReachBackendKind.LINUX }
        assertThat(linux(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION))?.status).isEqualTo(ReachBackendStatus.CHANNEL_UNAVAILABLE)
        assertThat(linux(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.UNSUPPORTED_ABI))?.status).isEqualTo(ReachBackendStatus.UNAVAILABLE)
        assertThat(linux(LinuxRuntimeState.NotInstalled(0))?.status).isEqualTo(ReachBackendStatus.NOT_INSTALLED)
    }

    @Test
    fun `shell is attributed to linux only when the environment is ready and enabled`() {
        val ready = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "test", 1, null))
        fun runsShell(state: LinuxRuntimeState, enabled: Boolean) =
            reachBackends(true, false, null, 0, linuxState = state, linuxAgentEnabled = enabled).linuxRunsShell()
        assertThat(runsShell(ready, enabled = true)).isTrue()
        assertThat(runsShell(ready, enabled = false)).isFalse()
        assertThat(runsShell(LinuxRuntimeState.NotInstalled(0), enabled = true)).isFalse()
        assertThat(runsShell(LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION), enabled = true)).isFalse()
    }

    @Test
    fun `会话保存了运行模式时以它为准，未取得时按 Linux 是否接管 shell 推断`() {
        val readyLinux = listOf(ReachBackend(ReachBackendKind.LINUX, ReachBackendStatus.READY))
        val brokenLinux = listOf(ReachBackend(ReachBackendKind.LINUX, ReachBackendStatus.BROKEN))
        // 内置模式会话在 Linux 已就绪的设备上仍按内置模式描述。
        assertThat(reachRunMode(SessionRunMode.BUILTIN, readyLinux)).isEqualTo(SessionRunMode.BUILTIN)
        assertThat(reachRunMode(SessionRunMode.LINUX, emptyList())).isEqualTo(SessionRunMode.LINUX)
        assertThat(reachRunMode(null, readyLinux)).isEqualTo(SessionRunMode.LINUX)
        assertThat(reachRunMode(null, brokenLinux)).isEqualTo(SessionRunMode.BUILTIN)
        assertThat(reachRunMode(null, emptyList())).isEqualTo(SessionRunMode.BUILTIN)
    }

    @Test
    fun `Linux 模式只列 Linux 命令与 SSH，不列被工具集去掉的内置后端`() {
        val backends = reachBackends(true, true, TermuxAvailability(true, "0.119.1", true), 2, linuxState = readyLinux, linuxAgentEnabled = true)
        val runners = reachRunners(SessionRunMode.LINUX, backends)
        assertThat(runners.shown.map { it.kind }).containsExactly(ReachBackendKind.LINUX, ReachBackendKind.SSH).inOrder()
        assertThat(runners.inactive).isEmpty()
        assertThat(runners.linuxOmitsBuiltins).isTrue()
        // 与执行层一致：Linux 会话没有 exec_js、python_sandbox、termux_exec；python_exec 改在 Linux 的 Python 中运行。
        val hidden = com.promenar.infinity.data.generation.DefaultSessionToolResolver.LINUX_HIDDEN_TOOLS
        assertThat(hidden).containsAtLeast("python_sandbox", "exec_js", "termux_exec")
        assertThat(hidden).doesNotContain("python_exec")
    }

    @Test
    fun `内置模式列内置命令与隔离脚本，未开启的 Termux 与无主机的 SSH 合成未启用`() {
        val backends = reachBackends(false, false, null, 0, linuxState = readyLinux, linuxAgentEnabled = true)
        val runners = reachRunners(SessionRunMode.BUILTIN, backends)
        assertThat(runners.shown.map { it.kind }).containsExactly(ReachBackendKind.BUILTIN, ReachBackendKind.SANDBOX).inOrder()
        assertThat(runners.shown.map { it.status }).containsExactly(ReachBackendStatus.SHELL_ONLY, ReachBackendStatus.JS_ONLY).inOrder()
        assertThat(runners.inactive).containsExactly(ReachBackendKind.TERMUX, ReachBackendKind.SSH).inOrder()
        assertThat(runners.linuxOmitsBuiltins).isFalse()

        val enabled = reachRunners(SessionRunMode.BUILTIN, reachBackends(true, true, TermuxAvailability(true, "0.119.1", true), 1))
        assertThat(enabled.shown.map { it.kind })
            .containsExactly(ReachBackendKind.BUILTIN, ReachBackendKind.SANDBOX, ReachBackendKind.TERMUX, ReachBackendKind.SSH).inOrder()
        assertThat(enabled.inactive).isEmpty()
        // 开启了但没装 Termux 时单列一行说明状态，而不是归入“未启用”；SSH 仍在读取时暂不出现。
        val pending = reachRunners(SessionRunMode.BUILTIN, reachBackends(true, true, TermuxAvailability(false, null, false), null))
        assertThat(pending.shown.map { it.kind }).containsExactly(ReachBackendKind.BUILTIN, ReachBackendKind.SANDBOX, ReachBackendKind.TERMUX).inOrder()
        assertThat(pending.inactive).isEmpty()
    }

    @Test
    fun `文件位置随运行模式切换，本会话文件夹只在工作区不是它时单列`() {
        val mounts = listOf(ReachMount("photos", ReachMountSource.SAF, commandAccessible = false, unavailable = false))
        val repo = SessionWorkspaceUi.Directory("w", "repo", WorkspaceEntity.KIND_SHARED, "/storage/emulated/0/repo")
        assertThat(reachPaths(SessionRunMode.LINUX, repo, mounts).map { it.kind to it.path }).containsExactly(
            ReachPathKind.WORKSPACE to "/workspace",
            ReachPathKind.SESSION to "/home/session",
            ReachPathKind.MOUNT to "/mnt/photos",
            ReachPathKind.SKILLS to "/mnt/skills",
        ).inOrder()
        assertThat(reachPaths(SessionRunMode.BUILTIN, repo, mounts).map { it.kind to it.path }).containsExactly(
            ReachPathKind.WORKSPACE to "/",
            ReachPathKind.SESSION to "/session",
            ReachPathKind.MOUNT to "/mnt/photos",
            ReachPathKind.SKILLS to "/skills",
        ).inOrder()
        assertThat(reachPaths(SessionRunMode.LINUX, repo, mounts).first { it.kind == ReachPathKind.MOUNT }.mount).isEqualTo(mounts.single())

        assertThat(reachPaths(SessionRunMode.LINUX, SessionWorkspaceUi.Private, emptyList()).map { it.kind to it.path })
            .containsExactly(ReachPathKind.WORKSPACE to "/workspace", ReachPathKind.SKILLS to "/mnt/skills").inOrder()
        assertThat(reachPaths(SessionRunMode.BUILTIN, SessionWorkspaceUi.Private, emptyList()).map { it.kind })
            .doesNotContain(ReachPathKind.SESSION)

        val identity = com.promenar.infinity.data.repository.PersistentFileIdentity.encode("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "ino=1")
        val linuxHome = SessionWorkspaceUi.Directory("w", "demo", WorkspaceEntity.KIND_LINUX_HOME, "/root/demo#$identity")
        assertThat(reachPaths(SessionRunMode.LINUX, linuxHome, emptyList()).first().path).isEqualTo("/root/demo")
    }

    @Test fun `网络单元格显示直连与Tailscale路径状态`() {
        val empty = snapshot()
        for (target in listOf(NetworkTargetClass.PUBLIC, NetworkTargetClass.LAN, NetworkTargetClass.LOOPBACK)) {
            assertThat(networkCell(target, NetworkAccessState.ALLOWED, empty).meaning).isEqualTo(NetworkCellMeaning.ALLOWED)
            assertThat(networkCell(target, NetworkAccessState.ALLOWED, empty).tone).isEqualTo(NetworkCellTone.OPEN)
        }

        val tailnet = NetworkTargetClass.TAILNET
        assertThat(networkCell(tailnet, NetworkAccessState.TAILNET_REQUIRED, snapshot(available = false)).meaning).isEqualTo(NetworkCellMeaning.TAILNET_MISSING)
        assertThat(networkCell(tailnet, NetworkAccessState.TAILNET_REQUIRED, snapshot()).meaning).isEqualTo(NetworkCellMeaning.TAILNET_DISABLED)
        assertThat(networkCell(tailnet, NetworkAccessState.TAILNET_REQUIRED, snapshot(enabled = true)).meaning).isEqualTo(NetworkCellMeaning.TAILNET_OFFLINE)
        val running = networkCell(tailnet, NetworkAccessState.TAILNET_REQUIRED, snapshot(enabled = true, running = true))
        assertThat(running.meaning).isEqualTo(NetworkCellMeaning.TAILNET_READY)
        assertThat(running.tone).isEqualTo(NetworkCellTone.OPEN)

        assertThat(networkCell(NetworkTargetClass.PUBLIC, NetworkAccessState.DENIED, empty).tone).isEqualTo(NetworkCellTone.CLOSED)
        assertThat(networkCell(NetworkTargetClass.PUBLIC, NetworkAccessState.UNRESTRICTED, empty).tone).isEqualTo(NetworkCellTone.OPEN)
        assertThat(networkCell(NetworkTargetClass.PUBLIC, NetworkAccessState.CONFIGURED_ONLY, empty).tone).isEqualTo(NetworkCellTone.LIMITED)
    }

    private val readyLinux = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "test", 1, null))

    private fun snapshot(
        available: Boolean = true,
        enabled: Boolean = false,
        running: Boolean = false,
    ) = AgentNetworkSnapshot(available, enabled, running, emptyList())

    private fun mount(alias: String, source: String, sessionId: String, unavailable: String? = null) =
        ExternalMount("id-$alias", alias, source, "src-$alias", sessionId, 1L, unavailable)

}
