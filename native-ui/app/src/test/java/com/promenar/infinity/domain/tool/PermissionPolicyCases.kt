package com.promenar.infinity.domain.tool

data class PermissionPolicyCase(
    val tool: String,
    val risk: ToolRisk,
    val arguments: String = "{}",
    val alwaysApprove: Boolean = false,
    val command: Boolean = false,
)

val permissionPolicyCases = listOf(
    PermissionPolicyCase("shell_exec", ToolRisk.PROCESS, command = true),
    PermissionPolicyCase("python_exec", ToolRisk.PROCESS, command = true),
    PermissionPolicyCase("termux_exec", ToolRisk.PROCESS, command = true),
    PermissionPolicyCase("ssh_exec", ToolRisk.PROCESS, command = true),
    PermissionPolicyCase("ssh_session_open", ToolRisk.PROCESS, command = true),
    PermissionPolicyCase("ssh_session_send", ToolRisk.PROCESS, command = true),
    PermissionPolicyCase("ssh_session_read", ToolRisk.SAFE_READ),
    PermissionPolicyCase("write_file", ToolRisk.FILE_WRITE, """{"path":"/a.txt"}"""),
    PermissionPolicyCase("delete_file", ToolRisk.DELETE, """{"path":"/a.txt"}""", alwaysApprove = true),
    PermissionPolicyCase("exec_js", ToolRisk.PROCESS),
    PermissionPolicyCase("run_script", ToolRisk.PROCESS, """{"path":"/script.js"}"""),
    PermissionPolicyCase("python_sandbox", ToolRisk.PROCESS),
    PermissionPolicyCase("mcp_demo", ToolRisk.UNKNOWN),
    PermissionPolicyCase("web_fetch", ToolRisk.SAFE_READ, """{"url":"https://example.com/?data=secret"}"""),
    PermissionPolicyCase("schedule_create", ToolRisk.SCHEDULE, alwaysApprove = true),
    PermissionPolicyCase("write_file", ToolRisk.FILE_WRITE, """{"path":"/skills/demo/SKILL.md"}""", alwaysApprove = true),
    PermissionPolicyCase("delete_file", ToolRisk.DELETE, """{"path":"/.agents/skills"}""", alwaysApprove = true),
    PermissionPolicyCase("ssh_host_save", ToolRisk.FILE_WRITE, alwaysApprove = true),
    PermissionPolicyCase("ssh_host_delete", ToolRisk.DELETE, alwaysApprove = true),
    PermissionPolicyCase("ssh_host_install_key", ToolRisk.PROCESS, alwaysApprove = true),
    PermissionPolicyCase("unknown_tool", ToolRisk.UNKNOWN),
)
