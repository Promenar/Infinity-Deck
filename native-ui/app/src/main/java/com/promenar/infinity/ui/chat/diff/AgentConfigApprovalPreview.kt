package com.promenar.infinity.ui.chat.diff

import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.agentconfig.AgentConfigOp
import com.promenar.infinity.domain.agentconfig.AgentsWriteRequest
import com.promenar.infinity.domain.agentconfig.SessionConfigKeys
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField
import com.promenar.infinity.ui.common.diff.FileChangeKind
import com.promenar.infinity.ui.common.toModelSelectionUiModel
import com.promenar.infinity.ui.common.diff.FileDiffContent
import com.promenar.infinity.ui.common.diff.FileDiffModel
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 审批卡预览读取的现值与显示名；实现只读，不改任何数据。 */
interface AgentConfigPreviewSources {
    suspend fun agent(id: String): Agent?
    suspend fun session(id: String): Session?
    suspend fun sessionCount(agentId: String): Int
    /** 模型显示为“提供商 · 模型名”；找不到时原样返回 id。 */
    suspend fun modelLabel(modelId: String): String
    suspend fun mcpName(id: String): String
    suspend fun workspaceName(id: String): String
}

/** 字段值中需要本地化的固定文本。 */
data class AgentConfigValueLabels(
    val on: String,
    val off: String,
    val workspaceMode: String,
    val untitledSession: String,
    val reasoningEffort: (String) -> String = { it },
)

/**
 * 把 `agents_write` 参数整理成审批卡预览：字段表只列有变化的字段，提示词单独给出差异。
 * 目标不存在或参数不成形时返回空，卡片回退到参数文本。
 */
object AgentConfigPreviewBuilder {
    /** 提示词与 `inferenceParams` 不进字段表：前者用差异展示，后者展开成各采样参数。 */
    private val promptKeys = setOf(AgentField.SYSTEM_PROMPT.toolKey, SessionConfigKeys.CUSTOM_PROMPT)

    suspend fun build(
        request: AgentsWriteRequest,
        currentSessionId: String,
        sources: AgentConfigPreviewSources,
        labels: AgentConfigValueLabels,
    ): ApprovalDiffPreview.AgentConfig? {
        val current = sources.session(currentSessionId)
        return when (request.op) {
            AgentConfigOp.CREATE_AGENT -> createAgent(request, sources, labels)
            AgentConfigOp.UPDATE_AGENT -> {
                val id = request.id ?: current?.agentId ?: return null
                val agent = sources.agent(id) ?: return null
                updateAgent(request, agent, isCurrent = id == current?.agentId, sources, labels)
            }
            AgentConfigOp.CREATE_SESSION -> {
                val ownerId = request.fields.string(SessionConfigKeys.AGENT_ID) ?: current?.agentId ?: return null
                val owner = sources.agent(ownerId) ?: return null
                createSession(request, owner, sources, labels)
            }
            AgentConfigOp.UPDATE_SESSION -> {
                val id = request.id ?: currentSessionId
                val session = (if (id == currentSessionId) current else sources.session(id)) ?: return null
                updateSession(request, session, isCurrent = id == currentSessionId, sources, labels)
            }
        }
    }

    private suspend fun createAgent(
        request: AgentsWriteRequest,
        sources: AgentConfigPreviewSources,
        labels: AgentConfigValueLabels,
    ): ApprovalDiffPreview.AgentConfig {
        val rows = request.fields.entries.filter { it.key !in promptKeys }.mapNotNull { (key, value) ->
            display(key, value, sources, labels)?.let { FieldChange(key, null, it) }
        } + FieldChange(EXECUTION_MODE_KEY, null, labels.workspaceMode)
        return ApprovalDiffPreview.AgentConfig(
            op = request.op,
            targetName = request.fields.string(AgentField.NAME.toolKey!!).orEmpty(),
            fields = rows,
            promptDiff = promptDiff("", request.fields.string(AgentField.SYSTEM_PROMPT.toolKey!!)),
            impact = AgentConfigImpact.NewAgent,
            reason = request.reason,
        )
    }

    private suspend fun updateAgent(
        request: AgentsWriteRequest,
        agent: Agent,
        isCurrent: Boolean,
        sources: AgentConfigPreviewSources,
        labels: AgentConfigValueLabels,
    ): ApprovalDiffPreview.AgentConfig {
        val before = agentJson(agent)
        val rows = changedRows(request.fields, before, sources, labels)
        val promptKey = AgentField.SYSTEM_PROMPT.toolKey!!
        val prompt = if (promptKey in request.fields) promptDiff(agent.systemPrompt, request.fields.string(promptKey)) else null
        val count = sources.sessionCount(agent.id)
        return ApprovalDiffPreview.AgentConfig(
            op = request.op,
            targetName = agent.name,
            isCurrentTarget = isCurrent,
            fields = rows,
            promptDiff = prompt,
            impact = if (prompt != null) AgentConfigImpact.AgentPrompt(agent.name, count)
                else AgentConfigImpact.AgentFields(agent.name, count),
            reason = request.reason,
        )
    }

    private suspend fun createSession(
        request: AgentsWriteRequest,
        owner: Agent,
        sources: AgentConfigPreviewSources,
        labels: AgentConfigValueLabels,
    ): ApprovalDiffPreview.AgentConfig {
        val rows = request.fields.entries
            .filter { it.key !in promptKeys && it.key != SessionConfigKeys.AGENT_ID }
            .mapNotNull { (key, value) -> display(key, value, sources, labels)?.let { FieldChange(key, null, it) } }
        return ApprovalDiffPreview.AgentConfig(
            op = request.op,
            targetName = request.fields.string(SessionConfigKeys.TITLE)?.takeIf(String::isNotBlank) ?: labels.untitledSession,
            ownerAgentName = owner.name,
            fields = rows,
            promptDiff = promptDiff("", request.fields.string(SessionConfigKeys.CUSTOM_PROMPT)),
            sessionPrompt = true,
            impact = AgentConfigImpact.NewSession,
            reason = request.reason,
        )
    }

    private suspend fun updateSession(
        request: AgentsWriteRequest,
        session: Session,
        isCurrent: Boolean,
        sources: AgentConfigPreviewSources,
        labels: AgentConfigValueLabels,
    ): ApprovalDiffPreview.AgentConfig {
        // null 清空工具开放的全部采样参数与推理强度，按子项逐行显示为“默认”。
        val requested = when (val raw = request.fields[SessionConfigKeys.INFERENCE_PARAMS]) {
            is JsonObject -> raw
            JsonNull -> JsonObject(com.promenar.infinity.data.agentconfig.AgentConfigValues.samplingKeys.associateWith { JsonNull })
            else -> null
        }
        // 推理参数按子项展开，与其它字段一样逐行“旧值 → 新值”。
        val flattened = JsonObject(request.fields.filterKeys { it != SessionConfigKeys.INFERENCE_PARAMS } +
            requested.orEmpty().mapKeys { (key, _) -> "${SessionConfigKeys.INFERENCE_PARAMS}.$key" })
        val rows = changedRows(flattened, sessionJson(session), sources, labels)
        val prompt = if (SessionConfigKeys.CUSTOM_PROMPT in request.fields) {
            promptDiff(session.customPrompt.orEmpty(), request.fields.string(SessionConfigKeys.CUSTOM_PROMPT))
        } else null
        return ApprovalDiffPreview.AgentConfig(
            op = request.op,
            targetName = session.title,
            isCurrentTarget = isCurrent,
            fields = rows,
            promptDiff = prompt,
            sessionPrompt = true,
            impact = if (isCurrent) AgentConfigImpact.CurrentSession else AgentConfigImpact.OtherSession,
            reason = request.reason,
        )
    }

    private suspend fun changedRows(
        requested: JsonObject,
        before: JsonObject,
        sources: AgentConfigPreviewSources,
        labels: AgentConfigValueLabels,
    ): List<FieldChange> = requested.entries.filter { it.key !in promptKeys }.mapNotNull { (key, value) ->
        val old = before[key] ?: JsonNull
        if (normalized(old) == normalized(value)) return@mapNotNull null
        FieldChange(key, display(key, old, sources, labels), display(key, value, sources, labels))
    }

    /** 数值 1 与 1.0 视为相同；其余按 JSON 文本比较。 */
    private fun normalized(value: JsonElement): String = when (value) {
        is JsonPrimitive -> if (value.isString) "s:${value.content}" else value.content.toDoubleOrNull()?.let { "n:$it" } ?: value.toString()
        else -> value.toString()
    }

    /** 显示文本；null 表示“默认 / 未设置”，由界面本地化。 */
    private suspend fun display(
        key: String,
        value: JsonElement,
        sources: AgentConfigPreviewSources,
        labels: AgentConfigValueLabels,
    ): String? {
        if (value is JsonNull) return null
        if (value is JsonArray) {
            val ids = value.mapNotNull { (it as? JsonPrimitive)?.content }
            if (ids.isEmpty()) return null
            val names = when (key) {
                AgentField.MCP_SERVER_IDS.toolKey, SessionConfigKeys.ACTIVE_MCP_SERVER_IDS -> ids.map { sources.mcpName(it) }
                else -> ids
            }
            return names.joinToString("、")
        }
        val primitive = value as? JsonPrimitive ?: return value.toString()
        primitive.booleanOrNull?.takeIf { !primitive.isString }?.let { return if (it) labels.on else labels.off }
        val text = primitive.content
        if (text.isEmpty()) return null
        return when (key) {
            AgentField.MODEL_ID.toolKey -> sources.modelLabel(text)
            AgentField.DEFAULT_WORKSPACE_ID.toolKey -> sources.workspaceName(text)
            AgentField.REASONING_EFFORT.toolKey, "${SessionConfigKeys.INFERENCE_PARAMS}.reasoningEffort" -> labels.reasoningEffort(text)
            else -> text
        }
    }

    /** 提示词差异；新内容缺省（参数中为 null）视为清空。内容未变或差异不可逐行展示时返回空。 */
    private fun promptDiff(old: String, new: String?): FileDiffModel? {
        val next = new.orEmpty()
        if (old == next) return null
        val kind = if (old.isEmpty()) FileChangeKind.CREATED else FileChangeKind.MODIFIED
        return FileDiffModel.fromTexts(old, next, changeKind = kind).takeIf { it.content is FileDiffContent.Lines }
    }

    /** Agent 可写字段的现值，键与工具参数一致。 */
    internal fun agentJson(agent: Agent): JsonObject = buildJsonObject {
        put(AgentField.NAME.toolKey!!, agent.name)
        put(AgentField.DESCRIPTION.toolKey!!, agent.description)
        put(AgentField.SYSTEM_PROMPT.toolKey!!, agent.systemPrompt)
        put(AgentField.MODEL_ID.toolKey!!, agent.modelId)
        put(AgentField.TEMPERATURE.toolKey!!, agent.temperature)
        put(AgentField.TOP_P.toolKey!!, agent.topP)
        put(AgentField.MAX_TOKENS.toolKey!!, agent.maxTokens)
        put(AgentField.TOP_K.toolKey!!, agent.topK)
        put(AgentField.FREQUENCY_PENALTY.toolKey!!, agent.frequencyPenalty)
        put(AgentField.PRESENCE_PENALTY.toolKey!!, agent.presencePenalty)
        put(AgentField.REPETITION_PENALTY.toolKey!!, agent.repetitionPenalty)
        put(AgentField.REASONING_EFFORT.toolKey!!, agent.reasoningEffort)
        put(AgentField.MCP_SERVER_IDS.toolKey!!, JsonArray(agent.mcpServerIds.map(::JsonPrimitive)))
        put(AgentField.DEFAULT_WORKSPACE_ID.toolKey!!, agent.defaultWorkspaceId)
        put(AgentField.TOOLS_ENABLED_BY_DEFAULT.toolKey!!, agent.toolsEnabledByDefault)
    }

    /** 会话可写字段的现值；推理参数展开为 `inferenceParams.<子项>`。 */
    internal fun sessionJson(session: Session): JsonObject = buildJsonObject {
        put(SessionConfigKeys.TITLE, session.title)
        put(SessionConfigKeys.CUSTOM_PROMPT, session.customPrompt)
        put(SessionConfigKeys.MODEL_ID, session.modelId)
        put(SessionConfigKeys.ACTIVE_SKILL_IDS, JsonArray(session.activeSkillIds.map(::JsonPrimitive)))
        put(SessionConfigKeys.ACTIVE_MCP_SERVER_IDS, JsonArray(session.activeMcpServerIds.map(::JsonPrimitive)))
        val params = session.inferenceParams
        val prefix = SessionConfigKeys.INFERENCE_PARAMS
        put("$prefix.temperature", params?.temperature)
        put("$prefix.topP", params?.topP)
        put("$prefix.maxTokens", params?.maxTokens)
        put("$prefix.topK", params?.topK)
        put("$prefix.frequencyPenalty", params?.frequencyPenalty)
        put("$prefix.presencePenalty", params?.presencePenalty)
        put("$prefix.repetitionPenalty", params?.repetitionPenalty)
        put("$prefix.reasoningEffort", params?.reasoningEffort)
    }

    /** 新建 Agent 时额外展示的只读行：权限档固定为工作区。 */
    const val EXECUTION_MODE_KEY = "executionMode"

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}

/** 应用内数据源：Agent 与会话来自仓库，模型显示名来自提供商目录。 */
internal class AppAgentConfigPreviewSources(
    private val app: com.promenar.infinity.InfinityApplication,
) : AgentConfigPreviewSources {
    override suspend fun agent(id: String): Agent? = app.agentRepository.getById(id)
    override suspend fun session(id: String): Session? = app.sessionRepository.getConfigurationById(id)
    // 与 agents_read 的会话数一致，只计主会话。
    override suspend fun sessionCount(agentId: String): Int = app.sessionRepository.getAllConfigurations()
        .count { it.agentId == agentId && com.promenar.infinity.data.agentconfig.AgentConfigService.isMainSession(it) }

    override suspend fun modelLabel(modelId: String): String {
        val model = com.promenar.infinity.data.manager.ProviderManager.getInstance().providerModels.value
            .firstOrNull { it.id == modelId } ?: return modelId
        val resolved = model.toModelSelectionUiModel()
        return "${resolved.displayName} (${resolved.providerName})"
    }

    override suspend fun mcpName(id: String): String =
        app.skillRepository.getAllMcpServers().first().firstOrNull { it.id == id }?.name ?: id

    override suspend fun workspaceName(id: String): String =
        app.database.workspaceDao().observeAll().first().firstOrNull { it.id == id }?.displayName ?: id
}
