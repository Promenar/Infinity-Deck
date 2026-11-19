package com.promenar.infinity.startup

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.AsyncSkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.getAllToolsAsync
import kotlinx.serialization.json.JsonObject

/** 包含动态 MCP/子 Agent 在内的工具都经过同一范围闸门，读取工具不取得写权限。 */
class StartupGuardedSkillRegistry(private val delegate: SkillRegistry) : AsyncSkillRegistry {
    override fun getSkill(name: String): SkillDefinition? = delegate.getSkill(name)?.let(::guard)
    override fun getSkillByRuntimeToolId(runtimeToolId: String): SkillDefinition? = delegate.getSkillByRuntimeToolId(runtimeToolId)?.let(::guard)
    override fun getAllSkills(): List<SkillDefinition> = delegate.getAllSkills().map(::guard)
    override fun getAllTools(allowedIds: List<String>?) = delegate.getAllTools(allowedIds).filter(::allowsTool)
    override suspend fun getAllToolsAsync(allowedIds: List<String>?) =
        delegate.getAllToolsAsync(allowedIds).filter(::allowsTool)

    private fun allowsTool(tool: ProtocolTool): Boolean =
        !StartupWriteBarrier.isSubsystemIsolated(StartupSubsystem.TOOL_LEDGER) ||
            tool.sourceId == "builtin" && tool.function.name == "app_diagnostics" && tool.runtimeToolId == "app_diagnostics"

    private fun guard(skill: SkillDefinition): SkillDefinition = object : SkillDefinition by skill {
        override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
            if (skill.name != "app_diagnostics" || skill.sourceId != "builtin" || skill.runtimeToolId != "app_diagnostics") {
                StartupWriteBarrier.requireWritable(subsystem = StartupSubsystem.TOOL_LEDGER)
            }
            val isIssueResolver = skill.name == "resolve_startup_issue" && skill.sourceId == "builtin" &&
                skill.runtimeToolId == "resolve_startup_issue" && skill.mcpServerId == null
            if (!isIssueResolver && skill.risk != ToolRisk.SAFE_READ) {
                StartupWriteBarrier.requireWritable(sessionId = context.sessionId, rootId = context.workspaceRootUuid,
                    path = context.workspacePath)
            }
            if (skill.name.startsWith("job_") || args["background"]?.toString() == "true") {
                StartupWriteBarrier.requireWritable(subsystem = StartupSubsystem.BACKGROUND_JOBS)
            }
            if (skill.name.startsWith("schedule_")) {
                StartupWriteBarrier.requireWritable(subsystem = StartupSubsystem.SCHEDULED_TASKS)
            }
            skill.execute(args, context)
        } catch (blocked: StartupIsolationException) {
            ToolResult(id = skill.id, content = blocked.message.orEmpty(), status = "error")
        }
    }
}
