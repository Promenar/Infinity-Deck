package com.promenar.infinity.data.agentconfig

import androidx.room.RoomDatabase
import androidx.room.withTransaction
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.rag.RagConfiguration
import com.promenar.infinity.data.repository.AgentRepository
import com.promenar.infinity.data.repository.SessionRepository
import com.promenar.infinity.domain.agentconfig.*
import com.promenar.infinity.domain.model.*
import com.promenar.infinity.domain.usecase.newSessionDefaults
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import java.util.UUID

/** 只承载引用 id 与显示名；提供商凭据和 MCP 连接参数不进入工具。 */
data class AgentConfigCatalog(
    val models: Map<String, String> = emptyMap(),
    val skills: Map<String, String> = emptyMap(),
    val mcpServers: Map<String, String> = emptyMap(),
)

data class AgentConfigPreflight(val target: String? = null, val error: String? = null)

/** 主会话配置工具的校验、审批快照与持久化入口。 */
class AgentConfigService(
    private val agents: AgentRepository,
    private val sessions: SessionRepository,
    private val catalog: suspend (Session) -> AgentConfigCatalog,
    private val workspaceName: suspend (String) -> String?,
    private val ragConfig: () -> RagConfiguration = { RagConfiguration() },
    private val fallbackModelId: () -> String? = { null },
    private val database: RoomDatabase? = null,
    private val onSessionSaved: suspend (Session, Boolean, Set<String>) -> Unit = { _, _, _ -> },
) {
    private data class Prepared(
        val request: AgentsWriteRequest,
        val target: String,
        val agent: Agent? = null,
        val session: Session? = null,
    )

    /** 无效调用返回错误且无审批目标，生成运行时据此直接执行错误结果。 */
    suspend fun preflight(source: Session, arguments: String): AgentConfigPreflight = try {
        val args = Json.parseToJsonElement(arguments) as? JsonObject ?: throw IllegalArgumentException("参数必须是 JSON 对象")
        val prepared = prepare(args, source.id, source.agentId, null)
        AgentConfigPreflight(target = prepared.target)
    } catch (failure: IllegalArgumentException) {
        AgentConfigPreflight(error = failure.message ?: "配置参数不合法")
    }

    suspend fun read(args: JsonObject, context: SkillExecutionContext): String {
        require(args.keys.all { it in setOf("target", "id", "agentId") }) { "存在未知参数" }
        val source = source(context.sessionId, context.agentId, context.sourceSubagentId)
        val target = requiredString(args, "target")
        val id = optionalString(args, "id")
        val agentId = optionalString(args, "agentId") ?: source.agentId
        val names = catalog(source)
        val result: JsonElement = when (target) {
            "agents" -> {
                val counts = sessions.getAllConfigurations().filter(::isMainSession).groupingBy { it.agentId }.eachCount()
                JsonArray(agents.observeAll().first().map { agent -> buildJsonObject {
                    put("id", agent.id); put("name", agent.name); put("description", agent.description)
                    put("modelId", agent.modelId); put("modelName", AgentConfigValues.value(names.models[agent.modelId]))
                    put("sessionCount", counts[agent.id] ?: 0)
                } })
            }
            "agent" -> {
                val agent = requireNotNull(agents.getById(id ?: source.agentId)) { "智能体不存在" }
                buildJsonObject {
                    put("id", agent.id)
                    AgentConfigValues.agent(agent).forEach { (key, value) -> put(key, value) }
                    put("executionMode", ExecutionModeCodec.serialize(agent.executionMode))
                    put("defaultWorkspaceName", AgentConfigValues.value(agent.defaultWorkspaceId?.let { workspaceName(it) }))
                    put("mcpServers", displayReferences(agent.mcpServerIds, names.mcpServers))
                    put("userEditableOnly", userOnlyFields(agent.name))
                }
            }
            "sessions" -> {
                require(agents.getById(agentId) != null) { "智能体不存在" }
                JsonArray(sessions.getAllConfigurations().filter { it.agentId == agentId && isMainSession(it) }
                    .sortedByDescending { it.updatedAt }.map { session -> buildJsonObject {
                        put("id", session.id); put("title", session.title); put("modelId", AgentConfigValues.value(session.modelId))
                        put("modelName", AgentConfigValues.value(session.modelId?.let { names.models[it] })); put("updatedAt", session.updatedAt)
                    } })
            }
            "session" -> {
                val session = targetSession(id ?: source.id)
                val sessionNames = catalog(session)
                buildJsonObject {
                    put("id", session.id); put("agentId", session.agentId)
                    AgentConfigValues.session(session).forEach { (key, value) -> put(key, value) }
                    put("skills", displayReferences(session.activeSkillIds, sessionNames.skills))
                    put("mcpServers", displayReferences(session.activeMcpServerIds, sessionNames.mcpServers))
                    put("runMode", AgentConfigValues.value(session.runMode?.name))
                    put("executionMode", session.executionMode)
                    put("workspaceId", AgentConfigValues.value(session.workspaceId))
                    put("workspaceName", AgentConfigValues.value(session.workspaceId?.let { workspaceName(it) }))
                    put("userEditableOnly", userOnlyFields(null))
                }
            }
            else -> throw IllegalArgumentException("target 必须为 agents、agent、sessions 或 session")
        }
        return result.toString()
    }

    suspend fun write(args: JsonObject, context: SkillExecutionContext): String {
        val operation: suspend () -> Pair<String, Session?> = {
            val prepared = prepare(args, context.sessionId, context.agentId, context.sourceSubagentId)
            require(context.isApprovalBoundExecution && context.approvedExecutionTarget == prepared.target) { CHANGED }
            val request = prepared.request
            val id = when (request.op) {
                AgentConfigOp.CREATE_AGENT -> {
                    val id = UUID.randomUUID().toString()
                    val agent = AgentConfigValues.applyAgent(Agent(id, "", createdAt = System.currentTimeMillis()), request.fields)
                    agents.create(agent, AgentPromptRevision.Source.AGENT)
                    id
                }
                AgentConfigOp.UPDATE_AGENT -> {
                    val current = requireNotNull(prepared.agent)
                    val fields = request.fields.keys.mapTo(linkedSetOf()) { AgentField.byToolKey(it)!! }
                    val result = agents.updateFields(current.id, fields, AgentConfigValues.applyAgent(current, request.fields),
                        expected = current, promptSource = AgentPromptRevision.Source.AGENT)
                    require(result is AgentFieldUpdate.Updated) { CHANGED }
                    current.id
                }
                AgentConfigOp.CREATE_SESSION -> {
                    val agent = requireNotNull(prepared.agent)
                    val id = UUID.randomUUID().toString()
                    val defaults = newSessionDefaults(id, agent.id, agent, fallbackModelId(), ragConfig(), System.currentTimeMillis())
                    val session = defaults.copy(
                        title = request.fields["title"]?.jsonPrimitive?.content ?: defaults.title,
                        customPrompt = if ("customPrompt" in request.fields) request.fields["customPrompt"]?.jsonPrimitive?.contentOrNull else defaults.customPrompt,
                        modelId = if ("modelId" in request.fields) request.fields["modelId"]?.jsonPrimitive?.contentOrNull else defaults.modelId,
                    )
                    sessions.create(session)
                    id
                }
                AgentConfigOp.UPDATE_SESSION -> {
                    val current = requireNotNull(prepared.session)
                    sessions.updatePartial(current.id, AgentConfigValues.sessionUpdates(current, request.fields))
                    current.id
                }
            }
            val saved = if (request.op.targetsAgent) null else sessions.getConfigurationById(id)
            buildJsonObject {
                put("id", id); put("op", request.op.wireName)
                put("changedFields", JsonArray(request.fields.keys.sorted().map(::JsonPrimitive)))
                put("message", if (request.op == AgentConfigOp.CREATE_AGENT || request.op == AgentConfigOp.CREATE_SESSION)
                    "已创建，新的对象 id：$id；从下一轮生效" else "已修改 ${request.fields.keys.sorted().joinToString()}；从下一轮生效")
            }.toString() to saved
        }
        // 在生产库内把重验与会话局部更新放在同一事务，封闭审批重验后的竞争窗口。
        val createsSession = (args["op"] as? JsonPrimitive)?.content == "create_session"
        // 新建会话的附加挂载回调会取得自己的锁，必须在仓储插入提交后运行。
        val (result, saved) = if (database != null && !createsSession) database.withTransaction { operation() } else operation()
        saved?.let { onSessionSaved(it, createsSession, (args["fields"] as JsonObject).keys) }
        return result
    }

    private suspend fun prepare(args: JsonObject, sessionId: String, agentId: String, subagentId: String?): Prepared {
        val source = source(sessionId, agentId, subagentId)
        require(args.keys.all { it in setOf("op", "id", "fields", "reason") }) { "存在未知参数" }
        val rawOp = requiredString(args, "op")
        val op = AgentConfigOp.fromWire(rawOp)
            ?: throw IllegalArgumentException(if (rawOp.contains("delete", ignoreCase = true)) "删除只能由用户在界面修改；入口：智能体或会话菜单 → 删除" else "op 不受支持")
        optionalString(args, "id")
        val reason = requiredString(args, "reason")
        require(reason.isNotBlank()) { "reason 不能为空" }
        val fields = args["fields"] as? JsonObject ?: throw IllegalArgumentException("fields 必须是对象")
        require(fields.isNotEmpty()) { "fields 不能为空" }
        val allowed = when (op) {
            AgentConfigOp.CREATE_AGENT, AgentConfigOp.UPDATE_AGENT -> AgentField.toolWritable.map { it.toolKey!! }
            AgentConfigOp.CREATE_SESSION -> SessionConfigKeys.createSession
            AgentConfigOp.UPDATE_SESSION -> SessionConfigKeys.updateSession
        }
        val requestedId = optionalString(args, "id")
        val agent = when (op) {
            AgentConfigOp.UPDATE_AGENT -> agents.getById(requestedId ?: source.agentId)
            AgentConfigOp.CREATE_SESSION -> agents.getById(optionalString(fields, "agentId") ?: source.agentId)
            else -> null
        }
        fields.keys.firstOrNull { it !in allowed }?.let { key ->
            throw IllegalArgumentException("$key：该字段只能由用户在界面修改；入口：${entryFor(key, agent?.name)}")
        }
        fields.forEach { (key, value) -> AgentConfigValues.validate(key, value) }
        if (op == AgentConfigOp.CREATE_AGENT) require(fields["name"] != null) { "创建智能体必须提供 name" }
        if (op == AgentConfigOp.UPDATE_AGENT || op == AgentConfigOp.CREATE_SESSION) require(agent != null) { "智能体不存在" }
        val session = if (op == AgentConfigOp.UPDATE_SESSION) targetSession(requestedId ?: source.id) else null
        val names = catalog(session ?: source)
        fields["modelId"]?.takeUnless { it == JsonNull }?.let { require(it.jsonPrimitive.content in names.models) { "modelId 不在已配置提供商的模型目录中" } }
        for (key in listOf("mcpServerIds", "activeMcpServerIds")) fields[key]?.let { value ->
            require(AgentConfigValues.strings(value).all { it in names.mcpServers }) { "$key 含不存在的 MCP id" }
        }
        fields["activeSkillIds"]?.let { value -> require(AgentConfigValues.strings(value).all { it in names.skills }) { "activeSkillIds 含不存在的技能 id" } }
        fields["defaultWorkspaceId"]?.takeUnless { it == JsonNull }?.let {
            require(workspaceName(it.jsonPrimitive.content) != null) { "defaultWorkspaceId 不是已登记的工作区" }
        }
        val request = AgentsWriteRequest(op, requestedId, fields, reason)
        val current = when (op) {
            AgentConfigOp.UPDATE_AGENT -> buildJsonObject {
                fields.keys.forEach { key ->
                    val field = AgentField.byToolKey(key)!!
                    put(key, AgentConfigValues.agent(requireNotNull(agent)).getValue(key))
                    if (field == AgentField.NAME) put("nameCustomized", agent.nameCustomized)
                    if (field == AgentField.DESCRIPTION) put("descriptionCustomized", agent.descriptionCustomized)
                }
            }
            AgentConfigOp.UPDATE_SESSION -> JsonObject(AgentConfigValues.session(requireNotNull(session)).filterKeys { it in fields })
            else -> JsonPrimitive("new")
        }
        val targetId = when (op) {
            AgentConfigOp.UPDATE_AGENT -> agent!!.id
            AgentConfigOp.UPDATE_SESSION -> session!!.id
            else -> "new"
        }
        return Prepared(request, "${AgentConfigTools.TARGET_PREFIX}${op.wireName}:$targetId:${AgentConfigValues.digest(current)}", agent, session)
    }

    private suspend fun source(id: String, agentId: String, subagentId: String?): Session {
        require(subagentId == null) { "配置工具仅主会话可用，子代理不能访问" }
        val source = targetSession(id)
        require(source.agentId == agentId && agents.getById(agentId) != null) { "当前智能体不存在或已变化" }
        return source
    }

    private suspend fun targetSession(id: String): Session {
        val session = requireNotNull(sessions.getConfigurationById(id)) { "会话不存在" }
        require(isMainSession(session)) { "配置工具仅主会话可用，不能访问子代理或分支会话" }
        return session
    }

    private fun displayReferences(ids: List<String>, names: Map<String, String>) = JsonArray(ids.map { id -> buildJsonObject {
        put("id", id); put("name", AgentConfigValues.value(names[id]))
    } })

    private fun userOnlyFields(name: String?) = buildJsonObject {
        for (key in listOf("executionMode", "runMode", "workspaceId", "icon", "color", "avatarPath", "skills", "delete")) put(key, entryFor(key, name))
    }

    private fun entryFor(key: String, name: String?) = when (key) {
        "executionMode" -> if (name == null) "会话设置 → 工具 → 审批" else "智能体 → $name → 编辑 → 权限"
        "runMode" -> "聊天顶部 → 运行模式"
        "workspaceId" -> "会话菜单 → 工作区"
        "icon", "color", "avatarPath" -> "智能体 → ${name ?: "目标智能体"} → 编辑 → 头像"
        "skills" -> "设置 → 技能包（Agent.skills 运行时未启用）"
        "delete" -> "智能体或会话菜单 → 删除"
        else -> "智能体或会话 → 编辑"
    }

    private fun requiredString(args: JsonObject, key: String): String =
        (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IllegalArgumentException("$key 必须是 string")

    private fun optionalString(args: JsonObject, key: String): String? =
        if (key !in args) null else requiredString(args, key).also { require(it.isNotBlank()) { "$key 不能为空" } }

    companion object {
        const val CHANGED = "目标已变化，请重新读取后再提交"
        fun isMainSession(session: Session): Boolean = !session.id.startsWith("sa_") && session.parentSessionId == null &&
            session.branchPlanJson == null && !session.parentSessionDeleted
    }
}
