package com.promenar.infinity.ui.remote

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.ssh.SshActiveConnection
import com.promenar.infinity.data.ssh.SshAgentPtySession
import com.promenar.infinity.data.ssh.SshConnectionKind
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.domain.jobs.BackgroundJob
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.jobs.BackgroundJobState
import com.promenar.infinity.ui.chat.ChatInspectorBadges
import com.promenar.infinity.ui.chat.ChatPage
import com.promenar.infinity.ui.chat.InspectorContentGroup
import com.promenar.infinity.ui.chat.RemoteDot
import com.promenar.infinity.ui.chat.chatPageStack
import com.promenar.infinity.ui.chat.inspectorGroup
import com.promenar.infinity.ui.chat.remoteDot
import com.promenar.infinity.ui.chat.restoreChatPages
import com.promenar.infinity.ui.chat.saveChatPages
import com.promenar.infinity.ui.settings.sortedActiveConnections
import com.promenar.infinity.ui.ssh.SshReadOnlyTab
import org.junit.Test

class RemotePanelTest {
    @Test fun `远程页签常驻检视器，窄窗口整页承载并可保存恢复`() {
        assertThat(ChatInspectorBadges().pages()).contains(ChatPage.Remote)
        assertThat(chatPageStack(listOf(ChatPage.Remote), wideWindow = false)).containsExactly(ChatPage.Remote)
        assertThat(chatPageStack(listOf(ChatPage.Remote), wideWindow = true)).containsExactly(ChatPage.Remote)
        assertThat(restoreChatPages(saveChatPages(listOf(ChatPage.Remote)))).containsExactly(ChatPage.Remote)
        assertThat(ChatPage.Remote.inspectorGroup).isEqualTo(InspectorContentGroup.Pages)
    }

    @Test fun `圆点：Tailscale 需要处理时橙点优先，其次智能体正在使用`() {
        assertThat(remoteDot(agentUsingSsh = false, tailscaleNeedsAttention = false)).isNull()
        assertThat(remoteDot(agentUsingSsh = true, tailscaleNeedsAttention = false)).isEqualTo(RemoteDot.AGENT)
        assertThat(remoteDot(agentUsingSsh = false, tailscaleNeedsAttention = true)).isEqualTo(RemoteDot.ATTENTION)
        assertThat(remoteDot(agentUsingSsh = true, tailscaleNeedsAttention = true)).isEqualTo(RemoteDot.ATTENTION)
    }

    @Test fun `只有需要登录或失败的 Tailscale 需要处理`() {
        assertThat(TailnetPhase.entries.filter(::tailscaleNeedsAttention)).containsExactly(TailnetPhase.NEEDS_LOGIN, TailnetPhase.FAILED)
        assertThat(tailscaleNeedsAttention(null)).isFalse()
    }

    @Test fun `运行中的 ssh_exec：无结果且有实时通道或仍在生成`() {
        val running = sshCall("c1", "nas", "\n  uptime \nls")
        val finished = listOf(sshCall("c2", "nas", "df"), ExecutionStep("res_c2", "tool_result", toolName = "ssh_exec", toolCallId = "c2", content = "{}"))
        val other = ExecutionStep("c3", "tool_call", toolName = "shell_exec", toolArgs = """{"command":"ls"}""", toolCallId = "c3")
        val steps = listOf(listOf(running), finished, listOf(other))

        assertThat(runningSshExecs(steps, liveKeys = setOf("c1"), generating = false))
            .containsExactly(RemoteAgentExec("c1", "nas", "uptime"))
        assertThat(runningSshExecs(steps, liveKeys = emptySet(), generating = false)).isEmpty()
        assertThat(runningSshExecs(steps, liveKeys = emptySet(), generating = true)).containsExactly(RemoteAgentExec("c1", "nas", "uptime"))
    }

    @Test fun `参数缺主机或无法解析时不计入`() {
        val noHost = ExecutionStep("c1", "tool_call", toolName = "ssh_exec", toolArgs = """{"command":"ls"}""", toolCallId = "c1")
        val broken = ExecutionStep("c2", "tool_call", toolName = "ssh_exec", toolArgs = "{", toolCallId = "c2")
        assertThat(runningSshExecs(listOf(listOf(noHost, broken)), setOf("c1", "c2"), generating = true)).isEmpty()
    }

    @Test fun `只读终端页签按主机过滤，命令在前、交互会话在后`() {
        val usage = RemoteAgentUsage(
            execs = listOf(RemoteAgentExec("s1", "nas", "uptime"), RemoteAgentExec("s2", "pi", "ls")),
            sessions = listOf(SshAgentPtySession("p1", "chat", "nas", "ssh_session:p1", 0L)),
        )
        assertThat(usage.active).isTrue()
        assertThat(usage.readOnlyTabs("nas")).containsExactly(
            SshReadOnlyTab("exec:s1", SshReadOnlyTab.Kind.EXEC, "s1", "uptime"),
            SshReadOnlyTab("pty:p1", SshReadOnlyTab.Kind.SESSION, "ssh_session:p1", null),
        ).inOrder()
        assertThat(usage.readOnlyTabs("other")).isEmpty()
        assertThat(RemoteAgentUsage().active).isFalse()
    }

    @Test fun `只取本会话运行中的 SSH 后台命令`() {
        val jobs = listOf(
            job("a", "chat", BackgroundJobRunner.SSH, BackgroundJobState.RUNNING),
            job("b", "chat", BackgroundJobRunner.SSH, BackgroundJobState.EXITED),
            job("c", "chat", BackgroundJobRunner.LINUX, BackgroundJobState.RUNNING),
            job("d", "other", BackgroundJobRunner.SSH, BackgroundJobState.RUNNING),
        )
        assertThat(runningSshJobs(jobs, "chat").map { it.id }).containsExactly("a")
    }

    @Test fun `活动连接：终端在前，其后转发与智能体，同类按连接先后；后台命令只在后台任务列表`() {
        val connections = listOf(
            connection("job", SshConnectionKind.JOB, 1),
            connection("b", SshConnectionKind.TERMINAL, 5),
            connection("agent", SshConnectionKind.AGENT, 2),
            connection("a", SshConnectionKind.TERMINAL, 3),
            connection("fwd", SshConnectionKind.FORWARD, 4),
        )
        assertThat(sortedActiveConnections(connections).map { it.alias }).containsExactly("a", "b", "fwd", "agent").inOrder()
    }

    private fun sshCall(id: String, host: String, command: String) = ExecutionStep(
        id, "tool_call", toolName = "ssh_exec", toolCallId = id,
        toolArgs = kotlinx.serialization.json.buildJsonObject {
            put("host", kotlinx.serialization.json.JsonPrimitive(host))
            put("command", kotlinx.serialization.json.JsonPrimitive(command))
        }.toString(),
    )

    private fun job(id: String, sessionId: String, runner: BackgroundJobRunner, state: BackgroundJobState) = BackgroundJob(
        id = id, sessionId = sessionId, runner = runner, summary = "cmd", state = state, exitCode = null,
        startedAtMillis = 0L, endedAtMillis = null, maxRuntimeMinutes = 120, onExit = BackgroundJobExitAction.NOTIFY,
        logWorkspacePath = null, liveOutputKey = "job:$id",
    )

    private fun connection(alias: String, kind: SshConnectionKind, at: Long) =
        SshActiveConnection(owner = "o:$alias", alias = alias, kind = kind, sessionId = null, jobId = null, connectedAtMillis = at)
}
