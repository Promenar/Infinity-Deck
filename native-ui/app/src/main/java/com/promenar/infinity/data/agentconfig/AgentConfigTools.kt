package com.promenar.infinity.data.agentconfig

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.agentconfig.AgentConfigTools
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.JsonObject

class AgentsReadTool(private val service: () -> AgentConfigService) : SkillDefinition {
    override val id = AgentConfigTools.READ
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val description = "只读智能体与主会话配置，不读取消息、草稿或摘要。target 为 agents、agent、sessions、session。agent 用 id（或 agentId）指定智能体，session 用 id 指定会话，sessions 用 agentId 指定所属智能体；省略时取当前对象。"
    override val parametersSchema = """{"type":"object","additionalProperties":false,"required":["target"],"properties":{"target":{"type":"string","enum":["agents","agent","sessions","session"]},"id":{"type":"string"},"agentId":{"type":"string"}}}"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        ToolResult(id, service().read(args, context), "success")
    } catch (failure: IllegalArgumentException) {
        ToolResult(id, failure.message ?: "无法读取配置", "error")
    }
}

class AgentsWriteTool(private val service: () -> AgentConfigService) : SkillDefinition {
    override val id = AgentConfigTools.WRITE
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.AGENT_CONFIG
    override val description = "经用户逐次审批创建或更新智能体与主会话配置，不可限时免审批。先用 agents_read 读取现值。fields 使用配置键，reason 说明目的；修改从下一轮生效，禁止访问子代理和分支会话。Agent 字段：name、description、systemPrompt、modelId、temperature、topP、maxTokens、topK、frequencyPenalty、presencePenalty、repetitionPenalty、reasoningEffort、mcpServerIds、defaultWorkspaceId、toolsEnabledByDefault。create_session：agentId、title、customPrompt、modelId；update_session：title、customPrompt、modelId、inferenceParams、activeSkillIds、activeMcpServerIds。reasoningEffort 使用已支持的 wireName；可空字段传 null 清空。"
    override val parametersSchema = """{"type":"object","additionalProperties":false,"required":["op","fields","reason"],"properties":{"op":{"type":"string","enum":["create_agent","update_agent","create_session","update_session"]},"id":{"type":"string"},"fields":{"type":"object","description":"按 op 的字段白名单填写。inferenceParams 只允许采样参数与 reasoningEffort。"},"reason":{"type":"string"}}}"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        ToolResult(id, service().write(args, context), "success")
    } catch (failure: IllegalArgumentException) {
        ToolResult(id, failure.message ?: "无法修改配置", "error")
    }
}
