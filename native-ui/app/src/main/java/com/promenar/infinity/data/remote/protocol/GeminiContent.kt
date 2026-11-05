package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.parser.HttpStatusException
import com.promenar.infinity.domain.generation.CompletionReason
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8LineTo
import java.util.UUID
import kotlinx.coroutines.channels.SendChannel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gemini generateContent 协议体：Gemini API（API Key）与 Vertex AI 共用同一套 contents/parts 结构。
 *
 * thought signature 规则（官方 thought signatures 文档）：
 * - 签名可出现在任意 part；并行函数调用只有第一个 functionCall 携带，非函数调用响应通常在最后一个 part；
 * - Gemini 3 对当前回合（最近一条非 functionResponse 的用户消息之后）的函数调用签名强制校验，缺失返回 400；
 * - 签名必须连同原 part 原样回传，不得与其它带签名 part 合并。
 */
internal object GeminiContentCodec {
    /** 官方为非本模型生成的 functionCall 提供的占位签名，仅在当前回合缺签名时使用。 */
    const val SKIP_SIGNATURE_VALIDATOR = "skip_thought_signature_validator"

    /** 服务端未返回调用 ID 时本地生成的前缀；回传时不作为 functionCall.id 发送。 */
    const val LOCAL_CALL_ID_PREFIX = "gemini-local-"

    private val SAFETY_CATEGORIES = listOf(
        "HARM_CATEGORY_HARASSMENT",
        "HARM_CATEGORY_HATE_SPEECH",
        "HARM_CATEGORY_SEXUALLY_EXPLICIT",
        "HARM_CATEGORY_DANGEROUS_CONTENT",
        "HARM_CATEGORY_CIVIC_INTEGRITY",
    )

    private val FUNCTION_IMAGE_TYPES = setOf("image/png", "image/jpeg", "image/webp")
    private val GEMINI_AUDIO_TYPES = setOf(
        "audio/wav", "audio/mp3", "audio/aiff", "audio/aac", "audio/ogg", "audio/flac", "audio/mpeg",
        "audio/m4a", "audio/l16", "audio/opus", "audio/alaw", "audio/mulaw", "audio/webm",
    )
    private val VERTEX_AUDIO_TYPES = setOf(
        "audio/x-aac", "audio/flac", "audio/mp3", "audio/m4a", "audio/mpeg", "audio/mpga",
        "audio/mp4", "audio/ogg", "audio/pcm", "audio/wav", "audio/webm",
    )
    private val GEMINI_VIDEO_TYPES = setOf(
        "video/mp4", "video/mpeg", "video/quicktime", "video/avi", "video/x-flv", "video/mpg",
        "video/webm", "video/wmv", "video/3gpp",
    )
    private val VERTEX_VIDEO_TYPES = setOf(
        "video/x-flv", "video/quicktime", "video/mpeg", "video/mpegs", "video/mpg", "video/mp4",
        "video/webm", "video/wmv", "video/3gpp",
    )

    fun newLocalCallId(): String = LOCAL_CALL_ID_PREFIX + UUID.randomUUID().toString()

    fun buildRequestBody(rawRequest: PromptRequest, json: Json, isVertex: Boolean = false): String {
        val adapted = rawRequest.adaptedTo(
            if (isVertex) ProtocolType.Google_VertexAI else ProtocolType.Google_Gemini,
            rawRequest.model,
        )
        val request = adapted.request
        val systemMessages = request.messages.filter { it.role == "system" }
        val originalMessages = request.messages.filter { it.role != "system" }
        val contentMessages = messagesWithToolMedia(originalMessages, request.model, isVertex)
        // 合成媒体消息属于工具结果，不能重置原始用户轮次的签名范围。
        val currentUser = originalMessages.lastOrNull { it.role == "user" && !it.isRuntimeInstruction }
        val currentTurnStart = contentMessages.indexOfLast { it === currentUser }
        val callNames = contentMessages.flatMap { it.toolCalls.orEmpty() }.associate { it.id to it.name }
        val hasFunctionTools = !request.tools.isNullOrEmpty()

        val turns = normalizeTurns(
            contentMessages.mapIndexed { index, message ->
                message to formatParts(message, json, callNames, inCurrentTurn = index > currentTurnStart)
            },
        )

        return buildJsonObject {
            put("contents", JsonArray(turns.map { turn ->
                buildJsonObject {
                    put("role", turn.role)
                    put("parts", JsonArray(turn.parts))
                }
            }))
            put("safetySettings", JsonArray(SAFETY_CATEGORIES.map { category ->
                buildJsonObject {
                    put("category", category)
                    put("threshold", "BLOCK_NONE")
                }
            }))
            put("generationConfig", buildJsonObject {
                // Gemini 3 官方建议保持默认温度；只发送用户显式设置的采样参数。
                request.temperature?.let { put("temperature", it) }
                request.topP?.let { if (it < 1.0) put("topP", it) }
                request.topK?.let { put("topK", it) }
                request.maxTokens?.let { put("maxOutputTokens", it) }
                request.frequencyPenalty?.let { if (it != 0.0) put("frequencyPenalty", it) }
                request.presencePenalty?.let { if (it != 0.0) put("presencePenalty", it) }
                geminiThinkingConfig(adapted)?.let { put("thinkingConfig", it) }
            })
            if (systemMessages.isNotEmpty()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", JsonArray(listOf(
                        buildJsonObject { put("text", systemMessages.joinToString("\n") { it.content }) },
                    )))
                })
            }
            if (hasFunctionTools) {
                put("tools", buildJsonArray {
                    if (hasFunctionTools) {
                        add(buildJsonObject {
                            put("functionDeclarations", JsonArray(request.tools.orEmpty().canonicalizedForRequest().map { tool ->
                                buildJsonObject {
                                    put("name", tool.function.name)
                                    put("description", tool.function.description)
                                    put("parametersJsonSchema", parseParameters(tool.function.parameters, json))
                                }
                            }))
                        })
                    }
                })
            }
            if (hasFunctionTools && request.toolChoice == ProtocolToolChoice.NONE) {
                put("toolConfig", buildJsonObject {
                    put("functionCallingConfig", buildJsonObject { put("mode", "NONE") })
                })
            }
        }.toString()
    }

    private fun parseParameters(raw: String, json: Json): JsonObject =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {})
            }

    private fun messagesWithToolMedia(
        messages: List<ProtocolMessage>,
        model: String,
        isVertex: Boolean,
    ): List<ProtocolMessage> {
        val modelId = model.substringAfterLast('/').lowercase()
        val nativeImages = Regex("^gemini-3(?:[.-]|$)").containsMatchIn(modelId)
        val audioTypes = if (isVertex) VERTEX_AUDIO_TYPES else GEMINI_AUDIO_TYPES
        val videoTypes = if (isVertex) VERTEX_VIDEO_TYPES else GEMINI_VIDEO_TYPES
        fun nativeImage(image: ImageInput) = nativeImages && image.mimeType in FUNCTION_IMAGE_TYPES
        return appendToolGroupMedia(
            messages,
            transformTool = { message ->
                if (!message.hasMediaPayload() && message.mediaPaths.isNullOrEmpty()) message
                else message.copy(
                    content = message.mediaResultText(
                        if (isVertex) "Vertex AI" else "Gemini API",
                        unsupportedAudio = message.audioData.orEmpty().filter { it.mimeType !in audioTypes },
                        unsupportedVideo = message.videoData.orEmpty().filter { it.mimeType !in videoTypes },
                    ),
                    imageUrls = message.imageUrls?.filter(::nativeImage)?.ifEmpty { null },
                    audioData = null,
                    videoData = null,
                )
            },
            fallback = { group ->
                val media = group.filter { message ->
                    message.imageUrls.orEmpty().any { !nativeImage(it) } ||
                        message.audioData.orEmpty().any { it.mimeType in audioTypes } ||
                        message.videoData.orEmpty().any { it.mimeType in videoTypes }
                }
                if (media.isEmpty()) null
                else ProtocolMessage(
                    role = "user",
                    content = media.joinToString("\n") { it.mediaSourceText() },
                    imageUrls = media.flatMap { it.imageUrls.orEmpty() }.filterNot(::nativeImage).ifEmpty { null },
                    audioData = media.flatMap { it.audioData.orEmpty() }.filter { it.mimeType in audioTypes }.ifEmpty { null },
                    videoData = media.flatMap { it.videoData.orEmpty() }.filter { it.mimeType in videoTypes }.ifEmpty { null },
                )
            },
        )
    }

    private data class Turn(val role: String, val parts: List<JsonObject>)

    private fun normalizeTurns(messages: List<Pair<ProtocolMessage, List<JsonObject>>>): List<Turn> {
        val turns = mutableListOf<Turn>()
        for ((message, parts) in messages) {
            if (parts.isEmpty()) continue
            val role = when (message.role) {
                "user", "tool" -> "user"
                "assistant" -> "model"
                else -> continue
            }
            val last = turns.lastOrNull()
            when {
                last == null || last.role != role -> turns += Turn(role, parts)
                // 连续两条用户消息之间补一个模型占位，避免 contents 角色不交替。
                message.role == "user" && last.parts.none { it.containsKey("functionResponse") } -> {
                    turns += Turn("model", listOf(buildJsonObject { put("text", "OK") }))
                    turns += Turn(role, parts)
                }
                else -> turns[turns.lastIndex] = last.copy(parts = last.parts + parts)
            }
        }
        if (turns.firstOrNull()?.role == "model") {
            turns.add(0, Turn("user", listOf(buildJsonObject { put("text", "Please proceed.") })))
        }
        return turns
    }

    private fun formatParts(
        message: ProtocolMessage,
        json: Json,
        callNames: Map<String, String>,
        inCurrentTurn: Boolean,
    ): List<JsonObject> = when (message.role) {
        "tool" -> {
            val callId = message.toolCallId.orEmpty()
            listOf(buildJsonObject {
                put("functionResponse", buildJsonObject {
                    if (callId.isNotBlank() && !callId.startsWith(LOCAL_CALL_ID_PREFIX)) put("id", callId)
                    put("name", message.name ?: callNames[callId] ?: "unknown")
                    put("response", buildJsonObject { put("content", message.content) })
                    if (!message.imageUrls.isNullOrEmpty()) {
                        put("parts", JsonArray(message.imageUrls.map { image ->
                            inlineData(image.mimeType, image.base64.orEmpty())
                        }))
                    }
                })
            })
        }
        "assistant" -> assistantParts(message, json, inCurrentTurn)
        else -> userParts(message)
    }

    private fun assistantParts(message: ProtocolMessage, json: Json, inCurrentTurn: Boolean): List<JsonObject> {
        val signatures = message.reasoningReplay as? ReasoningReplay.GeminiSignatures
        val calls = message.toolCalls.orEmpty()
        val textParts = mutableListOf<JsonObject>()
        // 思考摘要只回传给产生它的同一模型（有签名状态时）；其它模型的思考不作为 Gemini thought 发送。
        if (signatures != null && !message.reasoning.isNullOrEmpty()) {
            textParts += buildJsonObject {
                put("thought", true)
                put("text", message.reasoning)
            }
        }
        if (message.content.isNotEmpty()) textParts += buildJsonObject { put("text", message.content) }
        signatures?.text?.let { signature ->
            if (textParts.isEmpty()) {
                textParts += buildJsonObject { put("text", "") }
            }
            val last = textParts.removeAt(textParts.lastIndex)
            textParts += JsonObject(last + ("thoughtSignature" to JsonPrimitive(signature)))
        }

        val needsPlaceholder = inCurrentTurn && calls.isNotEmpty() &&
            calls.none { signatures?.calls?.get(it.id) != null }
        val callParts = calls.mapIndexed { index, call ->
            val signature = signatures?.calls?.get(call.id)
                ?: SKIP_SIGNATURE_VALIDATOR.takeIf { needsPlaceholder && index == 0 }
            buildJsonObject {
                put("functionCall", buildJsonObject {
                    if (!call.id.startsWith(LOCAL_CALL_ID_PREFIX)) put("id", call.id)
                    put("name", call.name)
                    put("args", runCatching { json.parseToJsonElement(normalizeToolArguments(call.arguments)) }
                        .getOrNull() as? JsonObject ?: buildJsonObject {})
                })
                signature?.let { put("thoughtSignature", it) }
            }
        }
        val parts = textParts + callParts
        return parts.ifEmpty { listOf(buildJsonObject { put("text", "") }) }
    }

    private fun userParts(message: ProtocolMessage): List<JsonObject> = buildList {
        message.userTurnContext()?.let { context -> add(buildJsonObject { put("text", context) }) }
        if (message.content.isNotEmpty()) add(buildJsonObject { put("text", message.content) })
        message.imageUrls?.forEach { image ->
            add(inlineData(image.mimeType, image.base64.orEmpty()))
        }
        message.audioData?.forEach { audio -> add(inlineData(audio.mimeType, audio.base64)) }
        message.videoData?.forEach { video -> add(inlineData(video.mimeType, video.base64)) }
        message.documentData?.forEach { document ->
            if (document.url != null) {
                add(buildJsonObject {
                    put("fileData", buildJsonObject {
                        put("mimeType", document.mimeType)
                        put("fileUri", document.url)
                    })
                })
            } else {
                add(inlineData(document.mimeType, document.base64.orEmpty()))
            }
        }
    }

    private fun inlineData(mimeType: String, data: String) = buildJsonObject {
        put("inlineData", buildJsonObject {
            put("mimeType", mimeType)
            put("data", data)
        })
    }

    /** 同步响应：只接受 STOP；工具调用与签名一并返回。 */
    fun parseSyncResponse(responseText: String, json: Json, label: String): PromptResponse {
        val parsed = runCatching { json.parseToJsonElement(responseText).jsonObject }.getOrNull()
            ?: throw Exception("Invalid JSON response from $label")
        parsed["error"]?.let { error ->
            (error as? JsonObject)?.let {
                ProtocolContextLimit.throwIfPresent(ProtocolContextLimit.fromError(ProtocolType.Google_Gemini, it))
            }
            val message = (error as? JsonObject)?.let { it.string("message").ifEmpty { it.string("status") } }
            throw Exception(message?.ifEmpty { null } ?: "$label returned an error")
        }
        promptBlockReason(parsed)?.let { throw Exception("$label prompt blocked: $it") }
        val candidate = parsed["candidates"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw Exception("$label returned no candidates")
        val finishReason = candidate.string("finishReason")
        if (finishReason != "STOP") {
            throwIfAbnormalSyncFinish(label, finishReason)
            throw Exception("$label response finished with reason: $finishReason")
        }

        val state = GeminiStreamState()
        val content = StringBuilder()
        val reasoning = StringBuilder()
        state.readParts(candidate) { text, thought ->
            content.append(text)
            reasoning.append(thought)
        }
        requireValidSyncCompletion(CompletionReason.END_TURN, state.calls)
        return PromptResponse(
            content = content.toString(),
            reasoning = reasoning.toString().ifEmpty { null },
            toolCalls = state.calls.toList().ifEmpty { null },
            usage = parsed["usageMetadata"]?.jsonObject?.let(::usage),
            citations = citations(candidate)?.ifEmpty { null },
        )
    }

    fun promptBlockReason(chunk: JsonObject): String? =
        chunk["promptFeedback"]?.jsonObject?.get("blockReason")?.jsonPrimitive?.contentOrNull

    fun usage(metadata: JsonObject): ProtocolUsage = ProtocolUsageParsers.gemini(metadata)

    fun citations(candidate: JsonObject): List<ProtocolCitation>? =
        candidate["groundingMetadata"]?.jsonObject?.get("groundingChunks")?.jsonArray?.mapNotNull { chunk ->
            val web = chunk.jsonObject["web"]?.jsonObject ?: return@mapNotNull null
            ProtocolCitation(
                title = web.string("title").ifEmpty { "Web Source" },
                url = web.string("uri"),
                source = "Google",
            )
        }
}

private fun JsonObject.string(key: String): String {
    val element = this[key] ?: return ""
    return if (element is JsonNull) "" else (element as? JsonPrimitive)?.contentOrNull.orEmpty()
}

/** 单次 generateContent 流的累积状态：工具调用、签名与终止原因。 */
internal class GeminiStreamState(
    private val callIdFactory: () -> String = GeminiContentCodec::newLocalCallId,
) {
    val calls = mutableListOf<ProtocolToolCall>()
    private val callSignatures = linkedMapOf<String, String>()
    private var textSignature: String? = null
    private var finishReason: String? = null

    /** 解析 candidate 的 parts；正文与思考增量交给 [onText]，函数调用记入 [calls] 并返回其流式分片。 */
    fun readParts(candidate: JsonObject, onText: (text: String, thought: String) -> Unit): List<StreamChunk> {
        val emitted = mutableListOf<StreamChunk>()
        val parts = candidate["content"]?.jsonObject?.get("parts") as? JsonArray ?: return emitted
        for (element in parts) {
            val part = element as? JsonObject ?: continue
            val signature = part.string("thoughtSignature").ifEmpty { null }
            val functionCall = part["functionCall"] as? JsonObject
            when {
                functionCall != null -> {
                    val id = functionCall.string("id").ifBlank { callIdFactory() }
                    val name = functionCall.string("name")
                    val args = (functionCall["args"] as? JsonObject ?: JsonObject(emptyMap())).toString()
                    calls += ProtocolToolCall(id, name, args)
                    signature?.let { callSignatures[id] = it }
                    emitted += StreamChunk.ToolCallDelta(id, name, args, calls.lastIndex)
                }
                part.isThought() -> {
                    onText("", part.string("text"))
                    signature?.let { textSignature = it }
                }
                else -> {
                    onText(part.string("text"), "")
                    signature?.let { textSignature = it }
                }
            }
        }
        return emitted
    }

    /** 处理一个流式响应对象，返回需发出的分片。 */
    fun process(chunk: JsonObject): List<StreamChunk> {
        (chunk["error"] as? JsonObject)?.let { error ->
            val status = (error["code"] as? JsonPrimitive)?.intOrNull ?: 500
            ProtocolContextLimit.fromError(ProtocolType.Google_Gemini, error, status)?.let { return listOf(it) }
            throw HttpStatusException(status, error.toString())
        }
        GeminiContentCodec.promptBlockReason(chunk)?.let {
            return listOf(StreamChunk.Error(
                code = GenerationFailureCode.INVALID_REQUEST,
                retryable = false,
                technical = "Gemini prompt blocked: $it",
            ))
        }
        val emitted = mutableListOf<StreamChunk>()
        val candidate = (chunk["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (candidate != null) {
            val text = StringBuilder()
            val thought = StringBuilder()
            val calls = readParts(candidate) { t, r ->
                text.append(t)
                thought.append(r)
            }
            if (text.isNotEmpty() || thought.isNotEmpty()) {
                emitted += StreamChunk.TextDelta(text.toString(), thought.toString().ifEmpty { null })
            }
            emitted += calls
            GeminiContentCodec.citations(candidate)?.takeIf { it.isNotEmpty() }?.let {
                emitted += StreamChunk.Citations(it)
            }
            val reason = candidate.string("finishReason")
            if (reason.isNotEmpty()) {
                check(finishReason == null) { "Duplicate Gemini finishReason" }
                finishReason = reason
            }
        }
        (chunk["usageMetadata"] as? JsonObject)?.let { emitted += StreamChunk.Usage(GeminiContentCodec.usage(it)) }
        return emitted
    }

    /** 流结束：STOP 以外的终止原因均为失败；成功时先发出签名状态再发出终态。 */
    fun completion(label: String): List<StreamChunk> {
        val reason = finishReason ?: return listOf(truncatedStreamError("$label stream ended before finishReason"))
        if (reason != "STOP") {
            abnormalFinishError(label, reason)?.let { return listOf(it) }
            return listOf(StreamChunk.Error(
                code = GenerationFailureCode.SERVER,
                retryable = false,
                technical = "$label finished with reason: $reason",
            ))
        }
        val completion = validatedCompletion(CompletionReason.END_TURN, calls)
        val signatures = ReasoningReplay.GeminiSignatures(
            text = textSignature,
            calls = callSignatures.filterKeys { id -> calls.any { it.id == id } },
        )
        return if (completion is StreamChunk.Completed && (signatures.text != null || signatures.calls.isNotEmpty())) {
            listOf(StreamChunk.ReasoningState(signatures), completion)
        } else {
            listOf(completion)
        }
    }

    private fun JsonObject.isThought(): Boolean {
        val element = this["thought"] as? JsonPrimitive ?: return false
        return element.booleanOrNull ?: (element.contentOrNull?.let { it.isNotEmpty() && it != "false" } == true)
    }
}

/**
 * 读取 streamGenerateContent 的 SSE（alt=sse）或 JSON 数组流，逐个对象交给 [state]。
 * 成功结束时发出签名状态与终态；超时、HTML 响应或截断均转为错误分片。
 */
internal suspend fun SendChannel<StreamChunk>.consumeGeminiStream(
    channel: ByteReadChannel,
    state: GeminiStreamState,
    json: Json,
    label: String,
) {
    val line = StringBuilder()
    while (!channel.isClosedForRead) {
        line.clear()
        val read = channel.readUTF8LineTo(line, 1_048_576)
        if (!read) break
        val raw = line.toString()
        if (raw.isBlank()) continue
        if (raw.trimStart().startsWith('<')) {
            send(StreamChunk.Error(
                code = GenerationFailureCode.SERVER,
                retryable = false,
                technical = "Received HTML response instead of JSON stream.",
            ))
            return
        }
        val payload = (if (raw.startsWith("data:")) raw.removePrefix("data:") else raw).trim().removeSuffix(",")
        if (payload.isEmpty() || payload == "[DONE]" || payload == "[" || payload == "]") continue
        val chunk = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: throw IllegalStateException("Malformed $label stream event")
        for (emitted in state.process(chunk)) {
            send(emitted)
            if (emitted is StreamChunk.Error) return
        }
    }
    state.completion(label).forEach { send(it) }
}

/** 非 2xx 响应统一归一化为错误分片。 */
internal fun geminiHttpError(status: Int, body: String): StreamChunk.Error =
    ProtocolContextLimit.fromHttp(ProtocolType.Google_Gemini, status, body)
        ?: ErrorNormalizer.normalize(HttpStatusException(status, body)).toStreamChunkError()
