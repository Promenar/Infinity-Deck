package com.promenar.infinity.ui.settings

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.network.AgentNetworkTool
import com.promenar.infinity.domain.network.NetworkAccessState
import com.promenar.infinity.domain.network.NetworkTargetClass
import org.junit.Test

class NetworkRowScopeTest {
    @Test
    fun `每行适用范围：脚本仅内置模式，其余不标注`() {
        assertThat(networkRowScope(AgentNetworkTool.SCRIPT)).isEqualTo(NetworkRowScope.BUILTIN_ONLY)
        assertThat(networkRowScope(AgentNetworkTool.BUILTIN_PROCESS)).isEqualTo(NetworkRowScope.ALL_MODES)
        listOf(
            AgentNetworkTool.WEB_FETCH, AgentNetworkTool.MEDIA_FETCH, AgentNetworkTool.MCP,
            AgentNetworkTool.TERMUX, AgentNetworkTool.SSH,
        ).forEach { assertThat(networkRowScope(it)).isEqualTo(NetworkRowScope.ALL_MODES) }
    }

    @Test
    fun `会话为 Linux 模式时总表去掉脚本与 Termux 行，内置命令行沿用同一组规则，全局页列出全部`() {
        val rows = com.promenar.infinity.domain.network.AgentNetworkPolicy.rows
        assertThat(networkRowsFor(rows, null)).isEqualTo(rows)
        assertThat(networkRowsFor(rows, NetworkRunMode.BUILTIN)).isEqualTo(rows)
        val linux = networkRowsFor(rows, NetworkRunMode.LINUX)
        assertThat(linux.map { it.tool }).isEqualTo(AgentNetworkTool.entries.filter { it != AgentNetworkTool.SCRIPT && it != AgentNetworkTool.TERMUX })
        // Linux 命令与内置命令注入同一组 tailnet 代理变量：公网、局域网、本机不限，tailnet 需内置 Tailscale。
        val commands = linux.first { it.tool == AgentNetworkTool.BUILTIN_PROCESS }.cells.associate { it.target to it.state }
        assertThat(commands).containsExactly(
            NetworkTargetClass.PUBLIC, NetworkAccessState.UNRESTRICTED,
            NetworkTargetClass.LAN, NetworkAccessState.UNRESTRICTED,
            NetworkTargetClass.LOOPBACK, NetworkAccessState.UNRESTRICTED,
            NetworkTargetClass.TAILNET, NetworkAccessState.TAILNET_REQUIRED,
        )
    }
}
