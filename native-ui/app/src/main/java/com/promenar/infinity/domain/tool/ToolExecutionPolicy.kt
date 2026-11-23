package com.promenar.infinity.domain.tool

import com.promenar.infinity.domain.model.ExecutionMode

enum class ToolRisk {
    SAFE_READ,
    /** 只修改会话内部状态（如任务计划），不触及工作区文件或外部系统。 */
    SESSION_STATE,
    /** 创建会在未来自行执行的任务，所有模式均需用户批准。 */
    SCHEDULE,
    /** 授予智能体新的数据访问权限，所有模式均需用户批准。 */
    ACCESS_GRANT,
    /** 修改智能体或会话配置，所有模式逐次审批，不可限时免审批。 */
    AGENT_CONFIG,
    FILE_WRITE,
    PATCH,
    DELETE,
    PROCESS,
    SCRIPT,
    EXTERNAL_WRITE,
    UNKNOWN,
}

object ToolExecutionPolicy {
    /**
     * 检查是否为不可审批、两档模式均硬拦截的毁灭性根目录破坏命令。
     * 返回非 null 表示必须直接拒绝执行且不得弹出审批卡。
     */
    fun catastrophicBlock(
        toolName: String,
        arguments: String = "{}",
        usesLinux: Boolean = false,
        appDataDir: String? = null,
    ): CommandSafetyDecision? {
        val args = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(arguments) as? kotlinx.serialization.json.JsonObject }.getOrNull()
            ?: return null
        return when (toolName) {
            "shell_exec" -> {
                val command = (args["command"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                ShellCommandSafetyClassifier.classifyShell(command, appDataDir = appDataDir, usesLinux = usesLinux)
                    .takeIf { it.verdict == CommandSafetyVerdict.BLOCKED_CATASTROPHIC }
            }
            "python_exec" -> {
                val code = (args["code"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
                ShellCommandSafetyClassifier.classifyPython(code, appDataDir = appDataDir)
                    .takeIf { it.verdict == CommandSafetyVerdict.BLOCKED_CATASTROPHIC }
            }
            else -> null
        }
    }

    fun requiresApproval(
        mode: ExecutionMode,
        toolName: String,
        risk: ToolRisk,
        arguments: String = "{}",
        resolvedTarget: String? = null,
        usesLinux: Boolean = false,
    ): Boolean = (toolName == "browser_eval" && mode == ExecutionMode.WORKSPACE) ||
        requiresNonGrantableApproval(toolName, risk, resolvedTarget) ||
        grantCategory(mode, toolName, risk, arguments, resolvedTarget, usesLinux) != null

    fun grantCategory(
        mode: ExecutionMode,
        toolName: String,
        risk: ToolRisk,
        arguments: String = "{}",
        resolvedTarget: String? = null,
        usesLinux: Boolean = false,
    ): ApprovalGrantCategory? {
        // 页面脚本在风险审批档逐次确认，不受命令的限时授权影响。
        if (toolName == "browser_eval") return null
        if (requiresNonGrantableApproval(toolName, risk, resolvedTarget)) return null
        val target = resolvedTarget ?: ToolExecutionTargets.extract(
            com.promenar.infinity.data.remote.protocol.ProtocolTool(
                function = com.promenar.infinity.data.remote.protocol.ProtocolToolFunction(toolName, "", "{}"),
            ), arguments,
        )
        return when {
            ToolExecutionTargets.requiresSkillsApproval(toolName, target) -> ApprovalGrantCategory.SKILLS
            mode != ExecutionMode.WORKSPACE -> null
            toolName == "delete_file" -> ApprovalGrantCategory.DELETE
            toolName == "shell_exec" -> shellGrantCategory(arguments, usesLinux)
            CommandToolClassification.backendFor(toolName, usesLinux) != null -> ApprovalGrantCategory.COMMAND
            usesLinux && ToolExecutionTargets.isPathMutation(toolName) && isLinuxRootfsTarget(resolvedTarget) -> ApprovalGrantCategory.LINUX_ROOTFS
            !usesLinux && ToolExecutionTargets.isPathMutation(toolName) && isOutsideWorkspaceTarget(resolvedTarget) -> ApprovalGrantCategory.OUTSIDE_WORKSPACE
            else -> null
        }
    }

    private fun shellGrantCategory(arguments: String, usesLinux: Boolean): ApprovalGrantCategory? {
        val args = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(arguments) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        val command = (args?.get("command") as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            ?: return ApprovalGrantCategory.COMMAND
        val decision = ShellCommandSafetyClassifier.classifyShell(command, usesLinux = usesLinux)
        return if (decision.verdict == CommandSafetyVerdict.SAFE_READ_ONLY) null else ApprovalGrantCategory.COMMAND
    }

    private fun requiresNonGrantableApproval(toolName: String, risk: ToolRisk, resolvedTarget: String?): Boolean =
        risk in setOf(ToolRisk.SCHEDULE, ToolRisk.ACCESS_GRANT, ToolRisk.AGENT_CONFIG) ||
            toolName in setOf("schedule_create", "request_knowledge_access") ||
            ToolArgumentApprovalRules.requiresNonGrantableApproval(toolName, resolvedTarget)

    private fun isLinuxRootfsTarget(target: String?): Boolean = metadataFlag(target, "linuxRootfs")

    private fun isOutsideWorkspaceTarget(target: String?): Boolean = metadataFlag(target, "outsideWorkspace")

    private fun metadataFlag(target: String?, key: String): Boolean = runCatching {
        val parts = kotlinx.serialization.json.Json.parseToJsonElement(target ?: return false) as? kotlinx.serialization.json.JsonArray
        val metadata = parts?.lastOrNull() as? kotlinx.serialization.json.JsonObject
        (metadata?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    }.getOrDefault(false)
}
