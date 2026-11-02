package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry

/** 会话在当前时刻实际有权广告和执行的工具集合；提示构建与执行重验必须共用此策略。 */
fun interface SessionToolResolver {
    fun resolve(session: Session): List<ProtocolTool>
    suspend fun resolveForExecution(session: Session): List<ProtocolTool> = resolve(session)
}

class DefaultSessionToolResolver(
    private val settings: SharedPreferences,
    private val skillRegistry: SkillRegistry?,
    private val longTermMemoryAllowed: (Session) -> Boolean = { false },
    private val knowledgeSearchAllowed: (Session) -> Boolean = { false },
    private val knowledgeAccessAllowed: (Session) -> Boolean = { false },
    private val subagentModelPolicy: (Session) -> com.promenar.infinity.data.agent.AgentSubagentModelPolicy = { com.promenar.infinity.data.agent.AgentSubagentModelPolicy() },
    private val modelName: (String) -> String? = { null },
    private val mediaCapabilities: (Session) -> com.promenar.infinity.data.model.ModelCapabilities = { com.promenar.infinity.data.model.ModelCapabilities() },
) : SessionToolResolver {
    override fun resolve(session: Session): List<ProtocolTool> {
        val allTools = skillRegistry?.getAllTools(null).orEmpty()
        val memoryToolsAllowed = allTools.none { it.sourceId == "builtin" && it.runtimeToolId.ifBlank { it.function.name } in com.promenar.infinity.data.memory.LongTermMemoryPreferences.TOOL_IDS } ||
            (com.promenar.infinity.data.memory.LongTermMemoryPreferences.isEnabled(settings) && longTermMemoryAllowed(session))
        val capabilities = mediaCapabilities(session)
        val canView = capabilities.vision || capabilities.audioInput || capabilities.videoUnderstanding
        return allTools.filter { tool ->
            if (!SessionToolAvailability.allows(session, tool)) return@filter false
            if (tool.sourceId == "builtin" &&
                (tool.function.name in setOf("agents_read", "agents_write") || tool.runtimeToolId in setOf("agents_read", "agents_write")) &&
                !com.promenar.infinity.data.agentconfig.AgentConfigService.isMainSession(session)) return@filter false
            if (tool.sourceId == "builtin" && tool.runtimeToolId.ifBlank { tool.function.name } == "knowledge_search" && !knowledgeSearchAllowed(session)) return@filter false
            if (tool.sourceId == "builtin" && tool.runtimeToolId.ifBlank { tool.function.name } == "request_knowledge_access" && !knowledgeAccessAllowed(session)) return@filter false
            if (tool.sourceId == "builtin" && tool.runtimeToolId.ifBlank { tool.function.name } == "copy_knowledge_to_linux" &&
                !com.promenar.infinity.ui.chat.manager.skills.KnowledgeLinuxCopyPolicy.allows(session, knowledgeAccessAllowed(session))) return@filter false
            if (tool.sourceId == "builtin" && tool.function.name == "session_branch" &&
                (session.parentSessionId != null || session.branchPlanJson != null || session.parentSessionDeleted)) return@filter false
            if (tool.sourceId == "builtin" && tool.runtimeToolId.ifBlank { tool.function.name } in com.promenar.infinity.data.memory.LongTermMemoryPreferences.TOOL_IDS && !memoryToolsAllowed) return@filter false
            if (tool.sourceId == "builtin" && session.runMode == com.promenar.infinity.domain.model.SessionRunMode.LINUX &&
                tool.runtimeToolId.ifBlank { tool.function.name } in LINUX_HIDDEN_TOOLS) return@filter false
            if (tool.sourceId == "builtin" && tool.runtimeToolId.ifBlank { tool.function.name } == "view_media" && !canView) return@filter false
            when (tool.sourceId) {
                // 内置工具只受全局启用状态约束；写入、删除、脚本等风险由会话执行模式统一审批。
                // 会话 activeSkillIds 只选择自定义 Skill，其是否为空不得影响内置工具集合。
                "builtin" -> BuiltinToolPreferences.isEnabled(settings, tool.runtimeToolId.ifBlank { tool.function.name })
                // 自定义 DB/script Skill 没有隔离执行器，当前版本只能编辑，不能广告或执行。
                "custom" -> false
                "mcp" -> tool.mcpServerId != null && tool.mcpServerId in session.activeMcpServerIds
                else -> false
            }
        }.map { tool ->
            val definition = SessionRunModeToolDefinitions.forSession(tool, session.runMode)
            if (definition.sourceId == "builtin" && definition.function.name == com.promenar.infinity.data.subagent.SubagentLimits.TOOL_NAME)
                com.promenar.infinity.data.subagent.SubagentModelToolDefinitions.forSession(definition, subagentModelPolicy(session), modelName)
            else definition
        }
    }

    companion object {
        val LINUX_HIDDEN_TOOLS = setOf("python_sandbox", "exec_js", "run_script", "termux_exec")
    }
}

/** 会话工具开关的最小权限门；全局启用状态仍由解析器与执行前重验负责。 */
object SessionToolAvailability {
    fun allows(session: Session, tool: ProtocolTool): Boolean = session.options.toolsEnabled ||
        (session.options.keepWebSearchWhenToolsOff && tool.sourceId == "builtin" &&
            tool.runtimeToolId.ifBlank { tool.function.name } == "web_search" && tool.function.name == "web_search")
}
