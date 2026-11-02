package com.promenar.infinity.data.generation

import com.promenar.infinity.data.workspace.isWorkspaceSwitchNotice
import android.content.SharedPreferences
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.FullContextDocumentFormatter
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.MessageQuoteFormatter
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.remote.parser.DsmlStreamParser
import com.promenar.infinity.data.remote.protocol.ImageInput
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.remote.protocol.ProtocolTool
import com.promenar.infinity.data.remote.protocol.ProtocolToolCall
import com.promenar.infinity.data.remote.protocol.ReasoningReplay
import com.promenar.infinity.data.remote.protocol.ReasoningReplayCodec
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.domain.model.ExecutionModeCodec
import com.promenar.infinity.domain.tool.ToolExecutionPolicy
import com.promenar.infinity.domain.tool.ToolRisk
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** 补齐缺失工具结果时回传给模型的说明。 */
internal const val MISSING_TOOL_RESULT_CONTENT = "工具未执行：该调用在得到结果前已被中断，没有可用结果。"

/**
 * 发送前的协议历史结构修复，保证 Provider 接受的调用/结果配对约束：
 * - 跳过正文为空且没有工具调用的助手消息（失败占位、本轮尚未生成的目标消息）；
 * - 工具结果只保留紧随其调用方助手消息、且尚未出现过的那一条，其余孤立或重复结果丢弃；
 * - 助手消息中没有结果的工具调用补一条“未执行”结果，避免历史损坏后每次请求都被拒绝。
 */
/**
 * 助手消息保存的协议原生思考状态：来源模型与当前路由不同则不回传；
 * 仅限当前回合的状态（Anthropic 思考块）在更早的回合中省略，官方允许省略之前回合的思考块。
 */
internal fun replayableReasoning(message: Message, replaySource: String?, inCurrentTurn: Boolean): ReasoningReplay? {
    if (replaySource.isNullOrBlank()) return null
    val envelope = ReasoningReplayCodec.decode(message.thoughtSignature) ?: return null
    if (envelope.source != replaySource) return null
    return envelope.replay.takeIf { !it.currentTurnOnly || inCurrentTurn }
}

internal fun sanitizeProtocolHistory(messages: List<Message>): List<Message> {
    val result = mutableListOf<Message>()
    var openCalls: Map<String, ToolCall> = emptyMap()
    val answered = mutableSetOf<String>()
    fun closeOpenCalls() {
        openCalls.values.filter { it.id !in answered }.forEach { call ->
            result += Message(
                id = "synthetic_tool_result_${call.id}",
                role = MessageRole.TOOL,
                content = MISSING_TOOL_RESULT_CONTENT,
                toolCallId = call.id,
                name = call.name,
            )
        }
        openCalls = emptyMap()
        answered.clear()
    }
    for (message in messages) {
        if (message.role == MessageRole.TOOL) {
            val callId = message.toolCallId
            if (callId != null && callId in openCalls && answered.add(callId)) result += message
            continue
        }
        closeOpenCalls()
        if (message.role != MessageRole.ASSISTANT) {
            result += message
            continue
        }
        val original = message.toolCalls.orEmpty()
        val calls = original.filter { it.id.isNotBlank() }.distinctBy { it.id }
        if (message.content.isBlank() && calls.isEmpty() && message.requestInstruction.isNullOrBlank()) continue
        result += if (calls.size == original.size) message else message.copy(toolCalls = calls)
        openCalls = calls.associateBy { it.id }
    }
    closeOpenCalls()
    return result
}

data class GenerationContentNormalization(
    val content: String,
    val syntheticSteps: List<ExecutionStep> = emptyList(),
)

interface ChatGenerationContentStrategy {
    fun buildProtocolMessages(
        session: Session,
        systemPrompt: String,
        pinnedUserMessageId: String? = null,
        excludedMessageIds: Set<String> = emptySet(),
        /** 当前路由的应用模型 ID；只有同一模型产生的思考状态才会回传。 */
        replaySource: String? = null,
    ): List<ProtocolMessage>
    fun buildTools(session: Session): List<ProtocolTool>
    fun normalize(content: String, toolCalls: List<ToolCall>): GenerationContentNormalization
    fun extractFallbackToolCalls(content: String): List<ToolCall>
    fun stripToolCallMarkup(content: String): String
    fun mergeToolCalls(existing: List<ToolCall>, incoming: List<ToolCall>): List<ToolCall>
    fun pendingApprovalIds(toolCalls: List<ToolCall>, executionMode: String): List<String>
    fun riskForTool(toolName: String): ToolRisk
}

/** 不依赖 ViewModel 的纯内容与工具协议策略，可由应用级生成协调器长期持有。 */
class DefaultChatGenerationContentStrategy(
    private val settings: SharedPreferences,
    private val skillRegistry: SkillRegistry?,
    private val toolResolver: SessionToolResolver = DefaultSessionToolResolver(settings, skillRegistry),
) : ChatGenerationContentStrategy {
    @Volatile
    private var knownToolNames: Set<String>? = null
    @Volatile
    private var knownToolRisks: Map<String, ToolRisk> = emptyMap()

    override fun buildProtocolMessages(
        session: Session,
        systemPrompt: String,
        pinnedUserMessageId: String?,
        excludedMessageIds: Set<String>,
        replaySource: String?,
    ): List<ProtocolMessage> = buildList {
        if (systemPrompt.isNotBlank()) add(ProtocolMessage(role = "system", content = systemPrompt))
        // 工作区切换提示只供界面展示；当前工作区由系统提示词说明。
        val visibleMessages = session.messages.filterNot { it.id in excludedMessageIds || it.isWorkspaceSwitchNotice ||
            com.promenar.infinity.data.branch.SessionMessageStore.isInternal(it.name) || com.promenar.infinity.data.branch.SessionMessageStore.isTimelineRecord(it.name) }
        val currentStart = visibleMessages.indexOfFirst {
            it.id == pinnedUserMessageId && it.role == MessageRole.USER
        }
        val currentEnd = if (currentStart < 0) -1 else {
            ((currentStart + 1) until visibleMessages.size)
                .firstOrNull { visibleMessages[it].role == MessageRole.USER } ?: visibleMessages.size
        }
        // 重新生成可复用已摘要的用户消息；当前轮始终保留，其它已摘要历史不重复发送。
        val protocolMessages = visibleMessages.filterIndexed { index, message ->
            !message.isArchived || (currentStart >= 0 && index in currentStart until currentEnd)
        }
        val history = sanitizeProtocolHistory(protocolMessages)
        val currentTurnStart = history.indexOfLast { it.role == MessageRole.USER }
        history.forEachIndexed { index, message ->
            if (message.role == MessageRole.ASSISTANT) {
                message.requestInstruction?.takeIf(String::isNotEmpty)?.let { add(ProtocolMessage("user", it, isRuntimeInstruction = true)) }
                if (message.content.isBlank() && message.toolCalls.isNullOrEmpty()) return@forEachIndexed
            }
            add(
                when (message.role) {
                    MessageRole.USER -> ProtocolMessage(
                        role = "user",
                        content = FullContextDocumentFormatter.appendToUserContent(
                            MessageQuoteFormatter.formatUserContent(message.content, message.quotes),
                            message.userDocuments.orEmpty(),
                        ),
                        turnContext = com.promenar.infinity.ui.chat.manager.turnContextForModel(message.turnContext),
                        imageUrls = message.userImages?.map { dataUrl ->
                            val base64Index = dataUrl.indexOf("base64,")
                            val mimeEnd = dataUrl.indexOf(';')
                            ImageInput(
                                url = dataUrl,
                                base64 = if (base64Index >= 0) dataUrl.substring(base64Index + 7) else "",
                                mimeType = if (mimeEnd > 5) dataUrl.substring(5, mimeEnd) else "image/jpeg",
                            )
                        },
                    )
                    MessageRole.ASSISTANT -> ProtocolMessage(
                        role = "assistant",
                        content = message.content,
                        reasoning = message.reasoning,
                        toolCalls = message.toolCalls?.map {
                            ProtocolToolCall(id = it.id, name = it.name, arguments = it.arguments)
                        },
                        reasoningReplay = replayableReasoning(message, replaySource, index > currentTurnStart),
                    )
                    MessageRole.SYSTEM -> ProtocolMessage(role = "system", content = message.content)
                    MessageRole.TOOL -> ProtocolMessage(
                        role = "tool",
                        content = if (index <= currentTurnStart && message.name == "view_media" && !message.viewedMedia.isNullOrEmpty()) {
                            message.content + "\n" + message.viewedMedia.joinToString("\n") {
                                "已查看 ${it.ref.path}（${if (it.mode == com.promenar.infinity.domain.media.MediaViewMode.KEYFRAMES) "关键帧" else "原文件"}）"
                            }
                        } else message.content,
                        toolCallId = message.toolCallId,
                        name = message.name,
                    )
                },
            )
        }
    }

    override fun buildTools(session: Session): List<ProtocolTool> {
        knownToolRisks = emptyMap()
        knownToolNames = emptySet()
        val resolved = toolResolver.resolve(session)
        knownToolNames = resolved.mapTo(mutableSetOf()) { it.function.name }
        return resolved.also { tools ->
            knownToolRisks = tools.associate { it.function.name to it.risk }
        }
    }

    override fun normalize(content: String, toolCalls: List<ToolCall>): GenerationContentNormalization {
        if (toolCalls.isNotEmpty()) return GenerationContentNormalization(content)
        val match = TOOL_RESULT_SEPARATOR_PATTERN.find(content) ?: return GenerationContentNormalization(content)
        val separatorIndex = content.indexOf(match.value)
        return GenerationContentNormalization(
            content = content.substring(0, separatorIndex).trimEnd(),
            syntheticSteps = listOf(
                ExecutionStep(
                    id = "stream-sniff-${System.currentTimeMillis()}",
                    type = "tool_result",
                    content = content.substring(separatorIndex).take(500),
                ),
            ),
        )
    }

    override fun extractFallbackToolCalls(content: String): List<ToolCall> {
        val results = mutableListOf<ToolCall>()
        val dsmlParser = DsmlStreamParser()
        val outputText = StringBuilder()
        val dsmlCalls = dsmlParser.process(content, outputText)
        dsmlParser.flush(outputText)
        dsmlCalls.mapTo(results) { call ->
            val arguments = buildJsonObject {
                call.args.forEach { (key, value) ->
                    put(
                        key,
                        when (value) {
                            is String -> JsonPrimitive(value)
                            is Number -> JsonPrimitive(value)
                            is Boolean -> JsonPrimitive(value)
                            is JsonElement -> value
                            else -> JsonPrimitive(value.toString())
                        },
                    )
                }
            }.toString()
            ToolCall(stableId("dsml", call.toolName, arguments, results.size), call.toolName, arguments)
        }
        results.filter(::isKnownTool).takeIf { it.isNotEmpty() }?.let { return it }
        results.clear()

        XML_TOOL_CALL_PATTERN.findAll(content).forEach { match ->
            val attributes = match.groupValues[1].trim()
            val body = match.groupValues[2].trim()
            if (body.isEmpty()) return@forEach
            val attributeName = Regex("""name\s*=\s*"([^"]+)"""", RegexOption.IGNORE_CASE)
                .find(attributes)?.groupValues?.get(1)
            if (body.startsWith("{") || body.startsWith("[")) {
                val elements = buildList {
                    runCatching { Json.parseToJsonElement(body) }.getOrNull()?.let(::add)
                    scanBalancedJsonSegments(body).forEach { segment ->
                        runCatching { Json.parseToJsonElement(segment) }.getOrNull()?.let(::add)
                    }
                }
                elements.firstNotNullOfOrNull { parseToolCall(it, results.size) }?.let { call ->
                    if (results.none { it.name == call.name }) results += call
                }
            } else {
                val name = attributeName ?: body.lineSequence().first().trim()
                if (name.isNotEmpty() && name.none { it == '{' || it == '<' } && isKnownTool(name)) {
                    results += ToolCall(stableId("xml", name, "{}", results.size), name, "{}")
                }
            }
        }
        return results
    }

    override fun stripToolCallMarkup(content: String): String = content
        .replace(XML_TOOL_PATTERN, "")
        .replace(TOOL_RESULT_SEPARATOR_PATTERN, "")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

    override fun mergeToolCalls(existing: List<ToolCall>, incoming: List<ToolCall>): List<ToolCall> {
        val merged = linkedMapOf<String, ToolCall>()
        existing.forEach { merged.putIfAbsent(it.id, it) }
        incoming.forEach { call ->
            val previous = merged[call.id]
            merged[call.id] = previous?.copy(
                name = call.name.ifBlank { previous.name },
                arguments = call.arguments.ifBlank { previous.arguments },
            ) ?: call
        }
        return merged.values.toList()
    }

    override fun pendingApprovalIds(toolCalls: List<ToolCall>, executionMode: String): List<String> {
        val mode = ExecutionModeCodec.parseOrWorkspace(executionMode)
        return toolCalls.filter { call ->
            ToolExecutionPolicy.requiresApproval(mode, call.name, riskForTool(call.name), call.arguments)
        }.map { it.id }
    }

    override fun riskForTool(toolName: String): ToolRisk = knownToolRisks[toolName] ?: ToolRisk.UNKNOWN

    private fun parseToolCall(element: JsonElement, index: Int): ToolCall? {
        if (element !is JsonObject) return null
        val name = element["name"]?.jsonPrimitive?.content
            ?: element["tool"]?.jsonPrimitive?.content
            ?: element["tool_name"]?.jsonPrimitive?.content
            ?: element["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content
            ?: return null
        if (!isKnownTool(name)) return null
        val rawArguments: Any? = element["arguments"] ?: element["parameters"] ?: element["input"]
            ?: element["args"] ?: element["function"]?.jsonObject?.get("arguments")
        val arguments = when (rawArguments) {
            is JsonObject -> rawArguments.toString()
            is JsonPrimitive -> rawArguments.content
            is String -> rawArguments
            else -> "{}"
        }
        return ToolCall(stableId("fallback", name, arguments, index), name, arguments)
    }

    private fun isKnownTool(call: ToolCall): Boolean = isKnownTool(call.name)

    private fun isKnownTool(name: String): Boolean {
        val known = knownToolNames ?: skillRegistry?.getAllTools()?.map { it.function.name }?.toSet().orEmpty()
            .also { knownToolNames = it }
        return name in known
    }

    private fun stableId(prefix: String, name: String, arguments: String, index: Int): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest("$prefix\u0000$name\u0000$arguments\u0000$index".toByteArray())
        return "${prefix}_" + bytes.take(16).joinToString("") { "%02x".format(it) }
    }

    private fun scanBalancedJsonSegments(text: String): List<String> {
        val segments = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val start = text.indexOf('{', index)
            if (start < 0) break
            val end = findMatchingCloseBrace(text, start)
            if (end >= 0) {
                text.substring(start, end + 1).takeIf { candidate ->
                    listOf("\"name\"", "\"tool\"", "\"tool_name\"", "\"function\"")
                        .any(candidate::contains)
                }?.let(segments::add)
                index = end + 1
            } else {
                index = start + 1
            }
        }
        return segments
    }

    private fun findMatchingCloseBrace(text: String, start: Int): Int {
        var depth = 0
        var quoted = false
        var escaped = false
        for (index in start until text.length) {
            val character = text[index]
            if (escaped) {
                escaped = false
            } else when (character) {
                '\\' -> escaped = true
                '"' -> quoted = !quoted
                '{' -> if (!quoted) depth++
                '}' -> if (!quoted && --depth == 0) return index
            }
        }
        return -1
    }

    private companion object {
        val XML_TOOL_CALL_PATTERN = Regex(
            """<\s*(?:FunctionCall|tool_call|function_call|func_call|tool-call|function-call)\s*([^>]*)>([\s\S]*?)</\s*(?:FunctionCall|tool_call|function_call|func_call|tool-call|function-call)\s*>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
        val XML_TOOL_PATTERN = Regex(
            """<\s*(?:FunctionCall|tool_call|function_call|function_name|func_call|tool-call|function-call)[^>]*/?>[\s\S]*?</\s*(?:FunctionCall|tool_call|function_call|func_call|tool-call|function-call)\s*>""",
            RegexOption.IGNORE_CASE,
        )
        val TOOL_RESULT_SEPARATOR_PATTERN = Regex(
            """(?:^|\n)---(?!-{2,})\s*(?:工具|tool|search)?\s*(?:调用|执行)?\s*结果\s*[：:]\s*[\s\S]*?(?=\n\n|\n?$)""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE),
        )
    }
}
