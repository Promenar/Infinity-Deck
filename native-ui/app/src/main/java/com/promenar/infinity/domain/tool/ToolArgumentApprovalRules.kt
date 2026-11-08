package com.promenar.infinity.domain.tool

import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
/** 与执行范围无关的逐次审批规则。 */
object ToolArgumentApprovalRules {
    private val SSH_HOST_CONFIG_TOOLS = setOf("ssh_host_save", "ssh_host_delete", "ssh_host_install_key")

    fun requiresExplicitApproval(toolName: String, arguments: String, resolvedTarget: String? = null): Boolean =
        requiresNonGrantableApproval(toolName, resolvedTarget) || ToolExecutionTargets.requiresSkillsApproval(
            toolName,
            resolvedTarget ?: ToolExecutionTargets.extract(
                ProtocolTool(function = ProtocolToolFunction(toolName, "", "{}")), arguments,
            ),
        )
    internal fun requiresNonGrantableApproval(toolName: String, resolvedTarget: String?): Boolean =
        (toolName in setOf("session_read", "session_search", "session_list") && resolvedTarget?.startsWith("session-approval:") == true) ||
            toolName == "agents_write" || toolName == "resolve_startup_issue" || toolName in SSH_HOST_CONFIG_TOOLS
}
