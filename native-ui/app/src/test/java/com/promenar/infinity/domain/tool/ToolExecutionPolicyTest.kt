package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import org.junit.Test

class ToolExecutionPolicyTest {
    @Test
    fun `两档按工具能力与显式目标审批而非泛化风险`() {
        val commands = mapOf(
            "shell_exec" to CommandToolBackend.BUILTIN_SHELL,
            "python_exec" to CommandToolBackend.BUILTIN_PYTHON,
            "termux_exec" to CommandToolBackend.TERMUX,
            "ssh_exec" to CommandToolBackend.SSH,
            "ssh_session_open" to CommandToolBackend.SSH,
            "ssh_session_send" to CommandToolBackend.SSH,
        )
        ExecutionMode.entries.forEach { mode ->
            commands.forEach { (tool, backend) ->
                assertThat(CommandToolClassification.backendFor(tool)).isEqualTo(backend)
                assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, ToolRisk.PROCESS))
                    .isEqualTo(mode == ExecutionMode.WORKSPACE)
            }
            ToolRisk.entries.forEach { risk ->
                assertThat(ToolExecutionPolicy.requiresApproval(mode, "other", risk)).isEqualTo(risk in setOf(ToolRisk.SCHEDULE, ToolRisk.ACCESS_GRANT, ToolRisk.AGENT_CONFIG))
            }
            listOf("ssh_session_read", "write_file", "exec_js", "run_script", "python_sandbox", "mcp_demo", "web_fetch", "update_plan").forEach { tool ->
                assertThat(CommandToolClassification.backendFor(tool)).isNull()
                assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, ToolRisk.UNKNOWN,
                    """{"path":"/a","irreversible":true,"url":"https://example.com/?data=secret"}""")).isFalse()
            }
            assertThat(ToolExecutionPolicy.requiresApproval(mode, "delete_file", ToolRisk.DELETE,
                """{"path":"/a"}""")).isTrue()
            listOf("schedule_create", "ssh_host_save", "ssh_host_delete", "ssh_host_install_key").forEach { tool ->
                assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, ToolRisk.UNKNOWN)).isTrue()
            }
            listOf("/skills/demo/SKILL.md", "/.agents/skills/demo/SKILL.md").forEach { path ->
                assertThat(ToolExecutionPolicy.requiresApproval(mode, "write_file", ToolRisk.FILE_WRITE,
                    """{"path":"$path"}""")).isTrue()
            }
        }
    }
}
