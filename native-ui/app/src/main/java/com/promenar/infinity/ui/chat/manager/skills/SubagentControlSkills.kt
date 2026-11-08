package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.subagent.*
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.ui.chat.manager.registry.*
import kotlinx.serialization.json.*

internal fun validSubagentWaitTimeout(args: JsonObject): Boolean {
    if (args["timeout_seconds"] == null) return true
    val seconds = (args["timeout_seconds"] as? JsonPrimitive)?.doubleOrNull ?: return false
    return seconds.isFinite() && seconds > 0 && seconds <= Long.MAX_VALUE / 1000.0
}
internal fun subagentWaitTimeout(args: JsonObject): Long =
    (args["timeout_seconds"] as? JsonPrimitive)?.doubleOrNull?.let { (it * 1000).toLong().coerceAtLeast(1) } ?: SubagentLimits.DEFAULT_WAIT_TIMEOUT_MS

/** 地址解析与工具参数分离，分支会话可以增加自己的寻址端口。 */
interface SessionAddressHandler {
    fun accepts(id: String): Boolean
    suspend fun execute(operation: String, id: String, args: JsonObject, context: SkillExecutionContext): String
}

class SubagentAddressHandler(private val launcher: () -> SubagentLauncher,
    private val sent: suspend (SkillExecutionContext, SubagentRun, String) -> Unit,
) : SessionAddressHandler {
    constructor(launcher: () -> SubagentLauncher) : this(launcher, { _, _, _ -> })
    override fun accepts(id: String) = id.startsWith("sa_")
    override suspend fun execute(operation: String, id: String, args: JsonObject, context: SkillExecutionContext): String {
        require(context.sourceSubagentId == null) { "子代理只能与自己的主会话通信" }
        val runtime = launcher()
        if (operation == "session_read" && listOf("before", "after", "limit").any { it in args }) {
            val history = runtime.readHistory(context.sessionId, id)
            val before = args["before"]?.jsonPrimitive?.content
            val after = args["after"]?.jsonPrimitive?.content
            val cursor = (before ?: after)?.let { value ->
                val index = value.toIntOrNull()
                require(index != null && index in history.indices && index.toString() == value) { "游标无效" }
                index
            }
            val limit = args["limit"]?.jsonPrimitive?.intOrNull ?: 40
            val start = if (after != null) cursor!! + 1 else 0
            val end = if (before != null) cursor!! else history.size
            val pageStart = if (after != null) start else (end - limit).coerceAtLeast(start)
            val pageEnd = if (after != null) (start + limit).coerceAtMost(end) else end
            return buildJsonObject {
                put("id", id)
                put("has_more_before", pageStart > 0)
                put("has_more_after", pageEnd < history.size)
                put("messages", buildJsonArray {
                    for (index in pageStart until pageEnd) {
                        val message = history[index]
                        add(buildJsonObject {
                            put("index", index.toString()); put("role", message.role); put("content", message.content.take(8_000))
                            put("tool_calls", buildJsonArray { message.toolCalls.orEmpty().forEach { call ->
                                add(buildJsonObject { put("name", call.name); put("arguments", call.arguments.take(500)) })
                            } })
                        })
                    }
                })
            }.toString()
        }
        val run = when (operation) {
            "session_send" -> runtime.send(context.sessionId, id, args["message"]!!.jsonPrimitive.content,
                args["wrapUp"]?.jsonPrimitive?.booleanOrNull ?: false)
            "session_stop" -> {
                runtime.stop(context.sessionId, id, SubagentEvent.Actor.PARENT)
                runtime.read(context.sessionId, id)
            }
            else -> runtime.read(context.sessionId, id)
        }
        if (operation == "session_send") sent(context, run, args["message"]?.jsonPrimitive?.content.orEmpty())
        val elapsed = ((run.endedAt ?: System.currentTimeMillis()) - run.startedAt).coerceAtLeast(0) / 1000
        val events = run.events.takeLast(20).joinToString("\n") { "${it.kind} (${it.by ?: "SUBAGENT"}): ${it.text.take(SubagentLimits.MAX_EVENT_CHARS)}" }
        return AgentTaskSkill.formatResults(listOf(run)) + "\nElapsed: ${elapsed}s; current tool: ${run.currentTool ?: "none"}.\nRecent events:\n$events"
    }
}

/** 父会话只能寻址自己派生的子代理；操作不跨越既有权限或工作区。 */
class SessionSubagentSkill(
    override val name: String,
    private val handlers: List<SessionAddressHandler>,
) : SkillDefinition {
    init { require(name in setOf("session_read", "session_search", "session_send", "session_stop")) }
    override val id = name
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val description = when (name) {
        "session_read" -> "读取家族会话或本会话子代理的状态与最近记录。同一智能体其它会话的读取每次需批准，其它智能体的会话不可见。"
        "session_search" -> "按关键词检索会话原始消息，同一智能体其它会话的读取每次需批准。"
        "session_send" -> "向主会话或本会话的分支、子代理发消息；wrapUp=true 请求收尾并交回结论。其它会话不能发送。停止的子代理不能恢复。"
        else -> "立即停止本会话分支当前一轮生成或本会话子代理；不删除会话。分支不能停止主会话。"
    }
    override val parametersSchema = when (name) {
        "session_send" -> """{"type":"object","properties":{"id":{"type":"string"},"message":{"type":"string"},"wrapUp":{"type":"boolean"}},"required":["id","message"],"additionalProperties":false}"""
        "session_read" -> """{"type":"object","properties":{"id":{"type":"string"},"before":{"type":"string","description":"消息 ID，取其之前的消息"},"after":{"type":"string","description":"消息 ID，取其之后的消息"},"limit":{"type":"integer","description":"1–100，默认 40"}},"additionalProperties":false}"""
        "session_search" -> """{"type":"object","properties":{"id":{"type":"string"},"query":{"type":"string","description":"1–200 个字符"},"role":{"type":"string","enum":["user","assistant","tool"]},"limit":{"type":"integer","description":"1–50，默认 20"}},"required":["query"],"additionalProperties":false}"""
        else -> """{"type":"object","properties":{"id":{"type":"string"}},"required":["id"],"additionalProperties":false}"""
    }
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (ToolSchemaValidator().validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return result("Invalid $name arguments.", false)
        val limit = args["limit"]?.jsonPrimitive?.intOrNull
        val query = args["query"]?.jsonPrimitive?.content.orEmpty()
        if (args["limit"] != null && (limit == null || limit !in 1..(if (name == "session_search") 50 else 100)) ||
            args["before"] != null && args["after"] != null ||
            name == "session_search" && (query.codePointCount(0, query.length) !in 1..200 ||
                args["role"]?.jsonPrimitive?.content?.let { it !in setOf("user", "assistant", "tool") } == true)) return result("Invalid $name arguments.", false)
        val id = args["id"]?.jsonPrimitive?.content ?: context.sessionId
        if (name == "session_search" && id.startsWith("sa_")) return result("子代理记录请使用 session_read 翻页", false)
        val handler = handlers.firstOrNull { it.accepts(id) } ?: return result("未找到该会话或子代理", false)
        return try { result(handler.execute(name, id, args, context), true) }
        catch (failure: IllegalArgumentException) { result(failure.message ?: "子代理参数无效", false) }
        catch (failure: SubagentModelUnavailable) { result(failure.message ?: "子代理不可用", false) }
    }
    private fun result(text: String, success: Boolean) = ToolResult("${name}_${System.nanoTime()}", text, if (success) "success" else "error")
}

class AgentWaitSkill(private val launcher: () -> SubagentLauncher) : SkillDefinition {
    override val id = SubagentLimits.WAIT_TOOL_NAME
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SESSION_STATE
    override val description = "Wait for all listed subagent IDs, or all active subagents spawned by this session when ids is omitted. A timeout returns current progress and leaves subagents running in the background."
    override val parametersSchema = """{
        "type":"object","properties":{"ids":{"type":"array","items":{"type":"string"}},"timeout_seconds":{"type":"number"}},"additionalProperties":false
    }"""
    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (ToolSchemaValidator().validate(parametersSchema, args) is ToolSchemaValidation.Invalid || !validSubagentWaitTimeout(args)) return result("Invalid agent_wait arguments.", false)
        return try {
            val runs = launcher().waitFor(context.sessionId, (args["ids"] as? JsonArray)?.map { it.jsonPrimitive.content }, subagentWaitTimeout(args))
            result(if (runs.isEmpty()) "No active subagents." else AgentTaskSkill.formatResults(runs), true)
        } catch (failure: IllegalArgumentException) { result(failure.message ?: "子代理参数无效", false) }
    }
    private fun result(text: String, success: Boolean) = ToolResult("agent_wait_${System.nanoTime()}", text, if (success) "success" else "error")
}
