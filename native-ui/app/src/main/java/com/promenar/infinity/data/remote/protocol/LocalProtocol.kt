package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.local.inference.GenerateConfig
import com.promenar.infinity.domain.generation.CompletionReason
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

class LocalProtocol(
    private val engine: LocalInferenceEngineProtocolAdapter,
    private val modelName: String = "",
    private val assistantPrefix: String = "",
) : LlmProtocol {

    constructor(
        engine: com.promenar.infinity.data.local.inference.LocalInferenceEngine,
        modelName: String = "",
        assistantPrefix: String = "",
    ) : this(EngineProtocolAdapter(engine), modelName, assistantPrefix)

    interface LocalInferenceEngineProtocolAdapter {
        fun generate(prompt: String, config: GenerateConfig): Flow<String>
    }

    private class EngineProtocolAdapter(
        private val engine: com.promenar.infinity.data.local.inference.LocalInferenceEngine,
    ) : LocalInferenceEngineProtocolAdapter {
        override fun generate(prompt: String, config: GenerateConfig): Flow<String> =
            engine.generate(prompt, config)
    }

    override val protocolType: ProtocolType = ProtocolType.Local

    private val promptTemplate: PromptTemplate by lazy {
        detectTemplate(modelName)
    }

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = flow {
        val activeTools = request.tools?.canonicalizedForRequest()
            ?.takeIf { request.toolChoice != ProtocolToolChoice.NONE && it.isNotEmpty() }
        val formattedPrompt = promptTemplate.format(
            messages = localMessagesWithToolMedia(request.messages),
            tools = activeTools,
        ) + assistantPrefix

        val genConfig = ProtocolParamAdapter.buildGenerateConfig(request)
        val parser = LocalStreamTagParser(allowToolCalls = activeTools != null)

        engine.generate(formattedPrompt, genConfig).collect { tokenText ->
            parser.feed(tokenText).forEach { emit(it) }
        }
        parser.finish().forEach { emit(it) }

        val toolIds = parser.completedToolCallIds
        if (toolIds.isNotEmpty()) {
            emit(StreamChunk.Completed(CompletionReason.TOOL_CALLS, toolIds))
        } else {
            emit(StreamChunk.Completed(CompletionReason.END_TURN))
        }
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val activeTools = request.tools?.canonicalizedForRequest()
            ?.takeIf { request.toolChoice != ProtocolToolChoice.NONE && it.isNotEmpty() }
        val formattedPrompt = promptTemplate.format(
            messages = localMessagesWithToolMedia(request.messages),
            tools = activeTools,
        ) + assistantPrefix

        val genConfig = ProtocolParamAdapter.buildGenerateConfig(request)
        val parser = LocalStreamTagParser(allowToolCalls = activeTools != null)
        val content = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<ProtocolToolCall>()

        fun consume(chunk: StreamChunk) {
            when (chunk) {
                is StreamChunk.TextDelta -> content.append(chunk.content)
                is StreamChunk.Thinking -> reasoning.append(chunk.content)
                is StreamChunk.ToolCallDelta -> calls += ProtocolToolCall(chunk.id, chunk.name, chunk.arguments)
                else -> Unit
            }
        }

        engine.generate(formattedPrompt, genConfig).collect { tokenText ->
            parser.feed(tokenText).forEach(::consume)
        }
        parser.finish().forEach(::consume)

        return PromptResponse(
            content = content.toString(),
            reasoning = reasoning.toString().takeIf { it.isNotEmpty() },
            toolCalls = calls.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun listModels(): List<String> {
        return listOf(modelName.ifEmpty { "Local Model" })
    }

    override fun cancel() {
        // Kotlin Flow cancellation handles this automatically
    }

    private fun detectTemplate(name: String): PromptTemplate {
        val lower = name.lowercase()
        return when {
            "llama-3" in lower || "llama3" in lower -> Llama3Template()
            "qwen" in lower -> ChatMLTemplate()
            "mistral" in lower -> MistralTemplate()
            "gemma" in lower -> GemmaTemplate()
            else -> ChatMLTemplate()
        }
    }
}

/**
 * 流式标签状态机：实时识别端侧模型输出的 `<think>...</think>` 思考块与
 * `<tool_call>...</tool_call>` 工具调用块，兼容任意 Token 拆分边界，遇到格式无效的 JSON 时降级为普通正文。
 */
internal class LocalStreamTagParser(
    private val allowToolCalls: Boolean,
) {
    private enum class Mode { TEXT, THINK, TOOL_CALL }

    private var mode = Mode.TEXT
    private val pending = StringBuilder()
    private val toolBuffer = StringBuilder()
    private var toolIndex = 0
    private val emittedIds = mutableListOf<String>()
    private var trimLeadingNewline = false

    val completedToolCallIds: List<String> get() = emittedIds.toList()

    fun feed(delta: String): List<StreamChunk> {
        if (delta.isEmpty()) return emptyList()
        pending.append(delta)
        val out = mutableListOf<StreamChunk>()
        drain(final = false, out = out)
        return out
    }

    fun finish(): List<StreamChunk> {
        val out = mutableListOf<StreamChunk>()
        drain(final = true, out = out)
        return out
    }

    private fun drain(final: Boolean, out: MutableList<StreamChunk>) {
        while (pending.isNotEmpty()) {
            when (mode) {
                Mode.TEXT -> {
                    if (trimLeadingNewline) {
                        trimLeadingNewline = false
                        if (pending[0] == '\n') {
                            pending.deleteCharAt(0)
                            continue
                        }
                    }
                    val thinkIdx = pending.indexOf(TAG_THINK_OPEN)
                    val toolIdx = if (allowToolCalls) pending.indexOf(TAG_TOOL_OPEN) else -1
                    val nextHit = pickEarliestTag(thinkIdx, toolIdx)
                    if (nextHit != null) {
                        val (idx, tag, nextMode) = nextHit
                        if (idx > 0) {
                            out += StreamChunk.TextDelta(content = pending.substring(0, idx))
                        }
                        pending.delete(0, idx + tag.length)
                        mode = nextMode
                        if (nextMode == Mode.TOOL_CALL) toolBuffer.clear()
                        continue
                    }
                    if (final) {
                        out += StreamChunk.TextDelta(content = pending.toString())
                        pending.clear()
                        return
                    }
                    val holdLen = longestSuffixMatchingTagPrefix(
                        pending,
                        if (allowToolCalls) listOf(TAG_THINK_OPEN, TAG_TOOL_OPEN) else listOf(TAG_THINK_OPEN),
                    )
                    val safeLen = pending.length - holdLen
                    if (safeLen > 0) {
                        out += StreamChunk.TextDelta(content = pending.substring(0, safeLen))
                        pending.delete(0, safeLen)
                    }
                    return
                }

                Mode.THINK -> {
                    val closeIdx = pending.indexOf(TAG_THINK_CLOSE)
                    if (closeIdx >= 0) {
                        if (closeIdx > 0) {
                            out += StreamChunk.Thinking(content = pending.substring(0, closeIdx))
                        }
                        pending.delete(0, closeIdx + TAG_THINK_CLOSE.length)
                        mode = Mode.TEXT
                        trimLeadingNewline = true
                        continue
                    }
                    if (final) {
                        out += StreamChunk.Thinking(content = pending.toString())
                        pending.clear()
                        return
                    }
                    val holdLen = longestSuffixMatchingTagPrefix(pending, listOf(TAG_THINK_CLOSE))
                    val safeLen = pending.length - holdLen
                    if (safeLen > 0) {
                        out += StreamChunk.Thinking(content = pending.substring(0, safeLen))
                        pending.delete(0, safeLen)
                    }
                    return
                }

                Mode.TOOL_CALL -> {
                    val closeIdx = pending.indexOf(TAG_TOOL_CLOSE)
                    if (closeIdx >= 0) {
                        toolBuffer.append(pending.substring(0, closeIdx))
                        pending.delete(0, closeIdx + TAG_TOOL_CLOSE.length)
                        val body = toolBuffer.toString()
                        toolBuffer.clear()
                        emitParsedToolCallOrText(body, out)
                        mode = Mode.TEXT
                        trimLeadingNewline = true
                        continue
                    }
                    if (final) {
                        toolBuffer.append(pending)
                        pending.clear()
                        val body = toolBuffer.toString()
                        toolBuffer.clear()
                        emitParsedToolCallOrText(body, out, unfinished = true)
                        mode = Mode.TEXT
                        return
                    }
                    val holdLen = longestSuffixMatchingTagPrefix(pending, listOf(TAG_TOOL_CLOSE))
                    val safeLen = pending.length - holdLen
                    if (safeLen > 0) {
                        toolBuffer.append(pending, 0, safeLen)
                        pending.delete(0, safeLen)
                    }
                    return
                }
            }
        }
    }

    private fun emitParsedToolCallOrText(
        rawBody: String,
        out: MutableList<StreamChunk>,
        unfinished: Boolean = false,
    ) {
        val parsed = parseLocalToolCallJson(rawBody)
        if (parsed != null) {
            val id = "local_call_${++toolIndex}"
            emittedIds += id
            out += StreamChunk.ToolCallDelta(
                id = id,
                name = parsed.first,
                arguments = parsed.second,
                index = toolIndex - 1,
            )
        } else {
            val fallbackText = if (unfinished) "$TAG_TOOL_OPEN$rawBody" else "$TAG_TOOL_OPEN$rawBody$TAG_TOOL_CLOSE"
            out += StreamChunk.TextDelta(content = fallbackText)
        }
    }

    private fun pickEarliestTag(thinkIdx: Int, toolIdx: Int): Triple<Int, String, Mode>? = when {
        thinkIdx >= 0 && (toolIdx < 0 || thinkIdx <= toolIdx) -> Triple(thinkIdx, TAG_THINK_OPEN, Mode.THINK)
        toolIdx >= 0 -> Triple(toolIdx, TAG_TOOL_OPEN, Mode.TOOL_CALL)
        else -> null
    }

    private fun longestSuffixMatchingTagPrefix(text: CharSequence, tags: List<String>): Int {
        val maxCheck = minOf(text.length, tags.maxOf { it.length - 1 })
        for (len in maxCheck downTo 1) {
            val start = text.length - len
            if (tags.any { tag -> tag.length > len && regionMatches(text, start, tag, len) }) {
                return len
            }
        }
        return 0
    }

    private fun regionMatches(a: CharSequence, aStart: Int, b: String, len: Int): Boolean {
        for (i in 0 until len) {
            if (a[aStart + i] != b[i]) return false
        }
        return true
    }

    companion object {
        private const val TAG_THINK_OPEN = "<think>"
        private const val TAG_THINK_CLOSE = "</think>"
        private const val TAG_TOOL_OPEN = "<tool_call>"
        private const val TAG_TOOL_CLOSE = "</tool_call>"

        internal fun parseLocalToolCallJson(raw: String): Pair<String, String>? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val root = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: return null
            val name = (root["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() } ?: return null
            val argsElement = root["arguments"]
            val argsJson = when (argsElement) {
                null -> "{}"
                is JsonObject -> argsElement.toString()
                is JsonPrimitive -> if (argsElement.isString) {
                    val inner = runCatching { Json.parseToJsonElement(argsElement.content) as? JsonObject }.getOrNull()
                        ?: return null
                    inner.toString()
                } else return null
                else -> return null
            }
            return name to argsJson
        }
    }
}

internal fun renderLocalToolsSystemInstruction(tools: List<ProtocolTool>): String {
    if (tools.isEmpty()) return ""
    val toolJsonLines = tools.joinToString("\n") { tool ->
        val paramsElement = runCatching { Json.parseToJsonElement(tool.function.parameters) }.getOrNull()
        buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", tool.function.name)
                put("description", tool.function.description)
                if (paramsElement != null) put("parameters", paramsElement)
            }
        }.toString()
    }
    return buildString {
        append("# Tools\n\n")
        append("You may call one or more functions to assist with the user query.\n")
        append("You are provided with function signatures within <tools></tools> XML tags:\n")
        append("<tools>\n")
        append(toolJsonLines)
        append("\n</tools>\n\n")
        append("For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\n")
        append("<tool_call>\n")
        append("{\"name\": \"<function-name>\", \"arguments\": <args-json-object>}\n")
        append("</tool_call>")
    }
}

internal fun ProtocolMessage.renderLocalAssistantBody(): String {
    val calls = toolCalls.orEmpty()
    val base = textWithTurnContext()
    if (calls.isEmpty()) return base
    return buildString {
        if (base.isNotEmpty()) append(base)
        for (call in calls) {
            if (isNotEmpty()) append('\n')
            val args = runCatching { Json.parseToJsonElement(call.arguments) as? JsonObject }.getOrNull()
            val payload = buildJsonObject {
                put("name", call.name)
                put("arguments", args ?: buildJsonObject {})
            }.toString()
            append("<tool_call>\n").append(payload).append("\n</tool_call>")
        }
    }
}

internal fun renderLocalToolResponseBody(msg: ProtocolMessage): String =
    "<tool_response>\n${msg.textWithTurnContext()}\n</tool_response>"

private fun appendToolInstructionToMessages(
    messages: List<ProtocolMessage>,
    tools: List<ProtocolTool>?,
): List<ProtocolMessage> {
    if (tools.isNullOrEmpty()) return messages
    val instruction = renderLocalToolsSystemInstruction(tools)
    val firstSystem = messages.indexOfFirst { it.role == "system" }
    return if (firstSystem >= 0) {
        messages.mapIndexed { index, msg ->
            if (index == firstSystem) {
                val merged = if (msg.content.isBlank()) instruction else "${msg.content}\n\n$instruction"
                msg.copy(content = merged)
            } else msg
        }
    } else {
        listOf(ProtocolMessage(role = "system", content = instruction)) + messages
    }
}

sealed class PromptTemplate {
    abstract fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String
}

class ChatMLTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val prepared = appendToolInstructionToMessages(messages, tools)
        val sb = StringBuilder()
        for (msg in prepared) {
            when (msg.role) {
                "assistant" -> sb.append("<|im_start|>assistant\n${msg.renderLocalAssistantBody()}<|im_end|>\n")
                "tool" -> sb.append("<|im_start|>user\n${renderLocalToolResponseBody(msg)}<|im_end|>\n")
                else -> sb.append("<|im_start|>${msg.role}\n${msg.textWithTurnContext()}<|im_end|>\n")
            }
        }
        sb.append("<|im_start|>assistant\n")
        return sb.toString()
    }
}

class Llama3Template : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val prepared = appendToolInstructionToMessages(messages, tools)
        val sb = StringBuilder("<|begin_of_text|>")
        for (msg in prepared) {
            val role = if (msg.role == "tool") "user" else msg.role
            val body = when (msg.role) {
                "assistant" -> msg.renderLocalAssistantBody()
                "tool" -> renderLocalToolResponseBody(msg)
                else -> msg.textWithTurnContext()
            }
            sb.append("<|start_header_id|>$role<|end_header_id|>\n\n")
            sb.append("$body<|eot_id|>")
        }
        sb.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
        return sb.toString()
    }
}

class MistralTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val prepared = appendToolInstructionToMessages(messages, tools)
        val sb = StringBuilder("<s>")
        val systemMsg = prepared.find { it.role == "system" }
        val userMsgs = prepared.filter { it.role == "user" || it.role == "tool" }

        if (systemMsg != null) {
            sb.append("[INST] ${systemMsg.content}\n\n")
        } else {
            sb.append("[INST] ")
        }

        val lastUser = userMsgs.lastOrNull()
        if (lastUser != null) {
            val body = if (lastUser.role == "tool") renderLocalToolResponseBody(lastUser) else lastUser.textWithTurnContext()
            sb.append("$body [/INST]")
        }

        return sb.toString()
    }
}

class GemmaTemplate : PromptTemplate() {
    override fun format(messages: List<ProtocolMessage>, tools: List<ProtocolTool>?): String {
        val prepared = appendToolInstructionToMessages(messages, tools)
        val sb = StringBuilder("<bos>")
        for (msg in prepared) {
            val role = when (msg.role) {
                "assistant" -> "model"
                "tool" -> "user"
                else -> msg.role
            }
            val body = when (msg.role) {
                "assistant" -> msg.renderLocalAssistantBody()
                "tool" -> renderLocalToolResponseBody(msg)
                else -> msg.textWithTurnContext()
            }
            sb.append("<start_of_turn>$role\n$body<end_of_turn>\n")
        }
        sb.append("<start_of_turn>model\n")
        return sb.toString()
    }
}
