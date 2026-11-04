package com.promenar.infinity.domain.tool

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.model.ExecutionMode
import org.junit.Test

class ApprovalGrantCategoryTest {
    @Test fun `删除在所有模式和后端均需审批且技能目录优先`() {
        for (mode in ExecutionMode.entries) for (linux in listOf(false, true)) {
            checkCategory(ApprovalGrantCategory.DELETE, mode, "delete_file", ToolRisk.DELETE,
                """{"path":"/normal.txt"}""", linux = linux)
            checkCategory(ApprovalGrantCategory.DELETE, mode, "delete_file", ToolRisk.DELETE,
                target = """["path","/etc/example",{"linuxRootfs":true}]""", linux = linux)
            checkCategory(ApprovalGrantCategory.SKILLS, mode, "delete_file", ToolRisk.DELETE,
                """{"path":"/skills/demo"}""", linux = linux)
        }
    }

    @Test fun `技能变更覆盖全部写工具与两档并优先于rootfs类别`() {
        for (mode in ExecutionMode.entries) for (root in listOf("/skills", "/.agents/skills")) {
            for ((tool, args) in listOf(
                "write_file" to """{"path":"$root/demo/SKILL.md"}""",
                "patch_file" to """{"path":"$root/demo/SKILL.md"}""",
                "create_file" to """{"parentPath":"$root/demo","name":"SKILL.md"}""",
                "create_directory" to """{"parentPath":"$root","name":"demo"}""",
                "move_file" to """{"path":"/a","newParentPath":"$root"}""",
                "delete_file" to """{"path":"$root/demo"}""",
            )) checkCategory(ApprovalGrantCategory.SKILLS, mode, tool, ToolRisk.FILE_WRITE, args)
        }
        for (mode in ExecutionMode.entries) for (tool in listOf("media_fetch", "copy_knowledge_to_linux", "sftp_download")) {
            checkCategory(ApprovalGrantCategory.SKILLS, mode, tool, ToolRisk.EXTERNAL_WRITE,
                target = """["path","/skills/demo/SKILL.md"]""")
        }
        checkCategory(ApprovalGrantCategory.SKILLS, ExecutionMode.WORKSPACE, "write_file", ToolRisk.FILE_WRITE,
            target = """["path","/mnt/skills/demo",{"linuxSkills":true,"linuxRootfs":true}]""", linux = true)
    }

    @Test fun `命令与rootfs只在工作区档可授权而普通写入与只读无需授权`() {
        for (linux in listOf(false, true)) for (mode in ExecutionMode.entries) {
            val workspace = mode == ExecutionMode.WORKSPACE
            for (command in listOf("shell_exec", "python_exec", "termux_exec", "ssh_exec", "ssh_session_open", "ssh_session_send", "sftp_upload", "sftp_download", "job_input")) {
                checkCategory(if (workspace) ApprovalGrantCategory.COMMAND else null, mode, command, ToolRisk.PROCESS, linux = linux)
            }
            val target = """["path","/etc/profile",{"linuxRootfs":true}]"""
            checkCategory(if (workspace && linux) ApprovalGrantCategory.LINUX_ROOTFS else null, mode, "write_file", ToolRisk.FILE_WRITE, target = target, linux = linux)
            checkCategory(null, mode, "read_file", ToolRisk.SAFE_READ, target = target, linux = linux)
            checkCategory(null, mode, "write_file", ToolRisk.FILE_WRITE, """{"path":"/normal.md"}""", linux = linux)
        }
    }

    @Test fun `不可授权原因单独或叠加技能路径及命令均不可授权`() {
        for (mode in ExecutionMode.entries) {
            for (tool in listOf("agents_write", "schedule_create", "request_knowledge_access", "resolve_startup_issue", "ssh_host_save", "ssh_host_delete", "ssh_host_install_key", "session_read", "session_list")) {
                val target = if (tool.startsWith("session_")) "session-approval:other" else null
                checkCategory(null, mode, tool, ToolRisk.UNKNOWN, target = target)
                assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, ToolRisk.UNKNOWN, resolvedTarget = target)).isTrue()
            }
            for (risk in listOf(ToolRisk.SCHEDULE, ToolRisk.ACCESS_GRANT, ToolRisk.AGENT_CONFIG)) {
                for (tool in listOf("write_file", "shell_exec")) {
                    checkCategory(null, mode, tool, risk, """{"path":"/skills/demo/SKILL.md"}""")
                    assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, risk)).isTrue()
                }
            }
            for (tool in listOf("session_read", "session_list")) checkCategory(null, mode, tool, ToolRisk.SAFE_READ)
        }
    }

    private fun checkCategory(expected: ApprovalGrantCategory?, mode: ExecutionMode, tool: String, risk: ToolRisk,
        args: String = "{}", target: String? = null, linux: Boolean = false) {
        val category = ToolExecutionPolicy.grantCategory(mode, tool, risk, args, target, linux)
        assertThat(category).isEqualTo(expected)
        if (category != null) assertThat(ToolExecutionPolicy.requiresApproval(mode, tool, risk, args, target, linux)).isTrue()
    }
}
