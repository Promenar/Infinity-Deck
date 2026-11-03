package com.promenar.infinity.data.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** 完整文件内容进入工具参数摘要；AGENT_CONFIG 强制逐次审批，不接受命令执行后的目录作为批准版本。 */
class InstallApprovedSkillTool(private val catalog: SkillCatalog) : SkillDefinition {
    override val id = "install_skill"
    override val name = id
    override val description = "安装完整用户技能包。files 的键为包内相对路径，必须包含 SKILL.md；每项填写 text 或 base64 之一。用户逐次批准这些文件内容后安装并默认启用，保留已有手动停用选择。覆盖已有技能须设 overwrite=true。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.AGENT_CONFIG
    override val parametersSchema = """{"type":"object","properties":{"files":{"type":"object","description":"包内相对路径到文件内容的映射，包含 SKILL.md","additionalProperties":{"type":"object","properties":{"text":{"type":"string"},"base64":{"type":"string"}},"additionalProperties":false}},"overwrite":{"type":"boolean"}},"required":["files"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            check(context.isApprovalBoundExecution) { "安装技能需要用户逐次批准完整文件内容" }
            val entries = args["files"] as? JsonObject ?: throw AgentSkillFormatException("files 必须是文件映射")
            if (entries.size !in 1..AgentSkillStore.MAX_FILES) throw AgentSkillFormatException("技能包文件数无效")
            var total = 0L
            val payload = entries.mapValues { (_, value) ->
                val file = value as? JsonObject ?: throw AgentSkillFormatException("文件内容必须填写 text 或 base64")
                if (file.size != 1 || file.keys.single() !in setOf("text", "base64")) {
                    throw AgentSkillFormatException("每个文件须且只能填写 text 或 base64")
                }
                val content = (file.values.single() as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw AgentSkillFormatException("文件内容必须是字符串")
                if (content.length.toLong() > AgentSkillStore.MAX_FILE_BYTES * 4L / 3 + 4) {
                    throw AgentSkillFormatException("文件超出大小限制")
                }
                val bytes = if ("text" in file) content.toByteArray(Charsets.UTF_8) else Base64.getDecoder().decode(content)
                if (bytes.size > AgentSkillStore.MAX_FILE_BYTES || bytes.size > AgentSkillStore.MAX_TOTAL_BYTES - total) {
                    throw AgentSkillFormatException("技能包超出大小限制")
                }
                total += bytes.size
                bytes
            }
            val overwrite = args["overwrite"]?.let {
                (it as? JsonPrimitive)?.booleanOrNull ?: throw AgentSkillFormatException("overwrite 必须为布尔值")
            } ?: false
            val installed = catalog.installApproved(payload, overwrite)
            ToolResult("${id}_${System.currentTimeMillis()}", "已安装 ${installed.virtualDir}，状态：${installed.status}")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ToolResult("${id}_${System.currentTimeMillis()}", failure.message?.take(160) ?: "技能安装失败", "error")
        }
    }
}
