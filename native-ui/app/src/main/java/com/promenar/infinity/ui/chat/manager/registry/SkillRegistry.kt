package com.promenar.infinity.ui.chat.manager.registry

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolFunction
import com.promenar.infinity.domain.tool.ToolRisk
import kotlinx.serialization.json.JsonObject

interface SkillRegistry {
    fun getSkill(name: String): SkillDefinition?
    fun getSkillByRuntimeToolId(runtimeToolId: String): SkillDefinition? =
        getAllSkills().singleOrNull { it.runtimeToolId == runtimeToolId }
            ?: getSkill(runtimeToolId)?.takeIf { it.runtimeToolId == runtimeToolId }
    fun getAllSkills(): List<SkillDefinition>
    fun getAllTools(allowedIds: List<String>? = null): List<ProtocolTool>
}

interface AsyncSkillRegistry : SkillRegistry {
    suspend fun getAllToolsAsync(allowedIds: List<String>? = null): List<ProtocolTool>
}

suspend fun SkillRegistry.getAllToolsAsync(allowedIds: List<String>? = null): List<ProtocolTool> =
    if (this is AsyncSkillRegistry) getAllToolsAsync(allowedIds) else getAllTools(allowedIds)

interface SkillDefinition {
    val id: String
    val runtimeToolId: String get() = id
    val sourceId: String get() = "builtin"
    val name: String
    val description: String
    val mcpServerId: String?
    val parametersSchema: String
    /** 按当前配置决定是否向模型暴露；技能登记与设置列表不受影响。 */
    val isAvailable: Boolean get() = true
    val risk: ToolRisk get() = ToolRisk.UNKNOWN
    suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult
}

fun SkillDefinition.toProtocolTool(): ProtocolTool = ProtocolTool(
    type = "function",
    function = ProtocolToolFunction(
        name = name,
        description = description,
        parameters = parametersSchema,
    ),
    risk = risk,
    runtimeToolId = runtimeToolId,
    sourceId = sourceId,
    mcpServerId = mcpServerId,
)

interface SkillExecutionContext {
    /** 原生子代理的发送者身份，模型参数不能指定。 */
    val sourceSubagentId: String? get() = null
    val sessionId: String
    val agentId: String
    /** 该调用准入时的会话模式快照，后端不能把已批准的调用改到其它环境。 */
    val runMode: com.promenar.infinity.domain.model.SessionRunMode? get() = null
    /** 发起本次工具调用的助手消息，供会话状态版本绑定与会话画布记录来源。 */
    val assistantMessageId: String? get() = null
    val workspacePath: String?
    val workspaceRootUuid: String
    /** 执行底座已核验的审批目标，供技能固定来源身份。 */
    val approvedExecutionTarget: String? get() = null
    val isApprovalBoundExecution: Boolean get() = false
    /** 运行中输出推送到界面的通道；只用于展示。 */
    val liveOutput: com.promenar.infinity.domain.tool.ToolLiveOutputSink
        get() = com.promenar.infinity.domain.tool.ToolLiveOutputSink.None
    /** 本次调用的执行步骤 id，供需要按调用归档过程的工具使用。 */
    val toolStepId: String? get() = null
    /** 执行器的 step_<毫秒>_<调用ID> 步骤键中保留原始调用 ID。 */
    val toolCallId: String? get() = toolStepId?.let { Regex("^step_[0-9]+_(.+)$").matchEntire(it)?.groupValues?.get(1) }
}
