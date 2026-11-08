package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.skills.AgentSkillFormatException
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.SkillCatalog
import com.promenar.infinity.data.skills.SkillSnapshot
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** 每次操作重新扫描会话工作区，不复用其它会话的项目技能或旧启用状态。 */
private class SkillToolAccess(
    val catalog: SkillCatalog,
    private val workspaceResolver: (suspend (String) -> SessionWorkspace)?,
) {
    suspend fun scan(sessionId: String?): SkillSnapshot {
        val workspace = try {
            sessionId?.let { workspaceResolver?.invoke(it) } as? SessionWorkspace.Directory
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.log("[AgentSkills] 工作区解析失败，仅提供内置指南：${failure.javaClass.simpleName}")
            val fallback = catalog.scan(null)
            return SkillSnapshot(null, fallback.builtin)
        }
        return catalog.scan(workspace?.let { SkillWorkspace(it.workspace.id, it.root) })
    }

    fun select(snapshot: SkillSnapshot, name: String?, path: String?): DiscoveredSkill {
        val selector = path?.trim()?.takeIf(String::isNotEmpty)
        val skillName = name?.trim()?.takeIf(String::isNotEmpty)
        val matches = if (selector != null) {
            val directory = selector.trimEnd('/').removeSuffix("/SKILL.md")
            snapshot.skills.filter { it.virtualDir == directory && (skillName == null || it.name == skillName) }
        } else if (skillName != null) {
            val named = snapshot.byName(skillName)
            named.filter { it.scope == SkillScope.BUILTIN }.ifEmpty { named }
        } else emptyList()
        if (matches.size > 1) {
            throw AgentSkillFormatException("技能名称 `$skillName` 对应多个来源，请用 path 指定：\n" +
                matches.joinToString("\n") { "- ${it.virtualDir}/SKILL.md" })
        }
        val skill = matches.singleOrNull() ?: throw AgentSkillFormatException("未找到技能 `${selector ?: skillName.orEmpty()}`，请使用清单中的名称或虚拟路径。")
        if (skill.status != SkillStatus.ENABLED) {
            throw AgentSkillFormatException("技能 `${skill.virtualDir}` 未启用（${skill.status}），无法加载。")
        }
        return skill
    }
}

private suspend fun skillToolResult(id: String, block: suspend () -> String): ToolResult = try {
    ToolResult("${id}_${System.currentTimeMillis()}", block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: AgentSkillFormatException) {
    ToolResult("${id}_${System.currentTimeMillis()}", failure.message.orEmpty(), "error")
} catch (failure: Exception) {
    InfinityLogger.log("[AgentSkills] $id 失败：${failure.javaClass.simpleName}")
    ToolResult("${id}_${System.currentTimeMillis()}", "技能目录或工作区无法读取，请检查当前工作区与技能状态。", "error")
}

/** 加载已启用技能的完整指令；来源路径用于区分两级同名技能。 */
class ActivateSkillTool(
    catalog: SkillCatalog,
    workspaceResolver: (suspend (String) -> SessionWorkspace)? = null,
) : SkillDefinition {
    private val access = SkillToolAccess(catalog, workspaceResolver)
    override val id = "activate_skill"
    override val name = "activate_skill"
    override val description = "读取内置、已启用的用户级或当前工作区项目级技能的 SKILL.md。管理技能、规则或询问能力与权限时先读取 infinity-deck。任务匹配描述或用户以 \$名称点名技能时先读取并遵循指令；同名时内置技能优先，其它来源以 path 区分。附带脚本沿用 shell_exec/python_exec 的审批规则。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","description":"name 或 path 至少提供一个；同时提供时必须指向同一技能","properties":{"name":{"type":"string","description":"技能名称；两级同名时须用 path"},"path":{"type":"string","description":"技能目录或 SKILL.md 的虚拟路径，例如 /skills/demo/SKILL.md 或 /.agents/skills/demo"}}}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        skillToolResult(id) {
            val skill = access.select(access.scan(context.sessionId), args.stringArgument("name"), args.stringArgument("path"))
            val document = access.catalog.readDocument(skill)
                ?: throw AgentSkillFormatException("技能 `${skill.virtualDir}/SKILL.md` 无法读取。")
            SlashSkillActivation.render(skill, document)
        }
    }
}

/** 读取两级技能的附带文件；目录边界、编码与大小约束由 SkillCatalog 统一执行。 */
class ReadSkillFileTool(
    catalog: SkillCatalog,
    workspaceResolver: (suspend (String) -> SessionWorkspace)? = null,
) : SkillDefinition {
    private val access = SkillToolAccess(catalog, workspaceResolver)
    override val id = "read_skill_file"
    override val name = "read_skill_file"
    override val description = "读取已启用技能的文本文件。可传 name 与相对 path，或直接传完整虚拟文件 path；同名时用 skill_path 指定技能目录或 SKILL.md。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"name":{"type":"string","description":"技能名称"},"skill_path":{"type":"string","description":"技能目录或 SKILL.md 的虚拟路径，用于搭配相对 path"},"path":{"type":"string","description":"技能内相对文件路径，或完整虚拟文件路径，例如 /.agents/skills/demo/references/guide.md"}},"required":["path"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        skillToolResult(id) {
            val snapshot = access.scan(context.sessionId)
            val path = args.stringArgument("path").orEmpty()
            val skillPath = args.stringArgument("skill_path")
            val absoluteSkill = if (path.startsWith('/')) snapshot.skills.firstOrNull { path.startsWith("${it.virtualDir}/") } else null
            val skill = access.select(snapshot, args.stringArgument("name"), skillPath ?: absoluteSkill?.virtualDir)
            if (path.startsWith('/') && absoluteSkill?.id != skill.id) {
                throw AgentSkillFormatException("文件路径不属于所选技能。")
            }
            val relative = if (absoluteSkill != null) path.removePrefix("${skill.virtualDir}/") else path
            access.catalog.readFile(skill, relative)
        }
    }
}

/** 广告时只扫描用户级与所绑定会话的项目级技能，执行入口始终保留以便重验。 */
class AgentSkillToolRegistry(
    catalog: SkillCatalog,
    workspaceResolver: (suspend (String) -> SessionWorkspace)? = null,
    private val sessionId: String? = null,
) : SkillRegistry {
    private val access = SkillToolAccess(catalog, workspaceResolver)
    private val tools: List<SkillDefinition> = listOf(ActivateSkillTool(catalog, workspaceResolver), ReadSkillFileTool(catalog, workspaceResolver))

    override fun getSkill(name: String): SkillDefinition? = tools.firstOrNull { it.name == name }

    override fun getAllSkills(): List<SkillDefinition> = tools

    override fun getAllTools(allowedIds: List<String>?): List<ProtocolTool> {
        // 现有注册表接口是同步的，磁盘扫描与工作区解析统一派发到 IO。
        val enabled = try {
            runBlocking(Dispatchers.IO) { access.scan(sessionId).skills.any { it.status == SkillStatus.ENABLED } }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            InfinityLogger.log("[AgentSkills] 工具目录扫描失败：${failure.javaClass.simpleName}")
            false
        }
        if (!enabled) return emptyList()
        return tools.filter { allowedIds == null || it.id in allowedIds }.map(SkillDefinition::toProtocolTool)
    }
}
