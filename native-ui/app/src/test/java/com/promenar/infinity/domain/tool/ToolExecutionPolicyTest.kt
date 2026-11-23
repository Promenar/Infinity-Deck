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
                """{"path":"/a"}""")).isEqualTo(mode == ExecutionMode.WORKSPACE)
            assertThat(ToolExecutionPolicy.requiresApproval(mode, "shell_exec", ToolRisk.PROCESS,
                """{"command":"ls -la"}""")).isFalse()
            assertThat(ToolExecutionPolicy.requiresApproval(mode, "shell_exec", ToolRisk.PROCESS,
                """{"command":"touch a.txt"}""")).isEqualTo(mode == ExecutionMode.WORKSPACE)
            assertThat(ToolExecutionPolicy.catastrophicBlock("shell_exec", """{"command":"rm -rf /"}"""))
                .isNotNull()
            assertThat(ToolExecutionPolicy.catastrophicBlock("python_exec", """{"code":"import shutil; shutil.rmtree('/')"}"""))
                .isNotNull()
            listOf("schedule_create", "ssh_host_save", "ssh_host_delete", "ssh_host_install_key").forEach { tool ->
                assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, ToolRisk.UNKNOWN)).isTrue()
            }
            listOf(
                "/skills/demo/SKILL.md",
                "/.agents/skills/demo/SKILL.md",
                "/data/user/0/com.promenar.infinity/files/agent_skills/demo/SKILL.md",
                "/data/user/0/com.promenar.infinity/files/session_workspaces/s1/.agents/skills/demo/SKILL.md",
            ).forEach { path ->
                assertThat(ToolExecutionPolicy.requiresApproval(mode, "write_file", ToolRisk.FILE_WRITE,
                    """{"path":"$path"}""")).isTrue()
            }
            assertThat(
                ToolExecutionPolicy.requiresApproval(mode, "production_update", ToolRisk.SESSION_STATE, "{}"),
            ).isFalse()
            assertThat(
                ToolExecutionPolicy.grantCategory(mode, "production_update", ToolRisk.SESSION_STATE, "{}"),
            ).isNull()
            val lockTarget = "production-lock:p1:r3:0123456789abcdef01234567"
            assertThat(
                ToolExecutionPolicy.requiresApproval(
                    mode = mode,
                    toolName = "production_update",
                    risk = ToolRisk.SESSION_STATE,
                    arguments = "{}",
                    resolvedTarget = lockTarget,
                ),
            ).isTrue()
            assertThat(
                ToolExecutionPolicy.grantCategory(
                    mode = mode,
                    toolName = "production_update",
                    risk = ToolRisk.SESSION_STATE,
                    arguments = "{}",
                    resolvedTarget = lockTarget,
                ),
            ).isNull()
        }
    }
}
