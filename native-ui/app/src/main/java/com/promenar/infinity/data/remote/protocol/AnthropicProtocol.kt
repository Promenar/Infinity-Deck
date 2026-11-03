package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.remote.applySessionRequestHeaders

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import com.promenar.infinity.data.model.catalog.ModelCatalogRuntime
import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.parser.HttpStatusException
import io.ktor.client.*
import io.ktor.client.call.body
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*
import com.promenar.infinity.domain.generation.CompletionReason

/** 流式思考块：thinking_delta 累积正文，signature_delta 给出签名；redacted_thinking 在开始事件中给出 data。 */
private data class ThinkingAccumulator(
    val type: String,
    val thinking: StringBuilder = StringBuilder(),
    var signature: String = "",
    val data: String = "",
) {
    fun toBlock(): AnthropicThinkingBlock = if (type == AnthropicThinkingBlock.REDACTED) {
        AnthropicThinkingBlock(type = type, data = data)
    } else {
        AnthropicThinkingBlock(type = type, thinking = thinking.toString(), signature = signature)
    }
}

private data class ToolUseAccumulator(
    val id: String,
    val name: String,
    val initialArguments: String,
    val arguments: StringBuilder = StringBuilder()
)

class AnthropicProtocol(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    private val anthropicVersion: String = "2023-06-01",
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.Anthropic_Messages

    private val httpClient: HttpClient = (httpClient ?: ModelHttp.client {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 120_000
        }
    }).withModelTimeoutSupport()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var activeChannel: ByteReadChannel? = null

    private val toolUseAccumulator = mutableMapOf<Int, ToolUseAccumulator>()
    private val completedToolUses = mutableMapOf<Int, ProtocolToolCall>()
    private var pendingStopReason: CompletionReason? = null
    private val thinkingAccumulators = mutableMapOf<Int, ThinkingAccumulator>()
    private val completedThinking = sortedMapOf<Int, AnthropicThinkingBlock>()

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = channelFlow {
        activeChannel = null
        toolUseAccumulator.clear()
        completedToolUses.clear()
        thinkingAccumulators.clear()
        completedThinking.clear()
        pendingStopReason = null
        startUsage = null

        try {
            // 回传的思考块被拒绝（签名失效、跨账号或兼容网关不支持）时，去掉全部思考块重发一次；官方允许整体省略。
            var outgoing = request
            while (true) {
                var retryWithoutThinking = false
                httpClient.preparePost(inferenceUrl()) {
                    applySessionRequestHeaders(request.sessionHeaders)
                    applyModelTimeout(ModelTimeoutKind.LLM)
                    contentType(ContentType.Application.Json)
                    header("x-api-key", apiKey)
                    header("anthropic-version", anthropicVersion)
                    header("Accept", "text/event-stream")
                    header("Accept-Encoding", "identity")
                    header("Cache-Control", "no-cache")
                    header("Connection", "keep-alive")
                    setBody(buildRequestBody(outgoing, stream = true))
                }.execute { response ->
                    if (!response.status.isSuccess()) {
                        val errorBody = try {
                            response.bodyAsText()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            ""
                        }
                        if (shouldRetryWithoutThinking(response.status.value, errorBody, outgoing)) {
                            retryWithoutThinking = true
                            return@execute
                        }
                        send(classifyProtocolError(
                            statusCode = response.status.value,
                            responseBody = errorBody,
                            retryAfterHeader = response.headers[HttpHeaders.RetryAfter],
                        ))
                        return@execute
                    }

                    response.contentType()?.let { ct ->
                        if (ct.match(ContentType.Text.Html)) {
                            send(StreamChunk.Error(
                                code = com.promenar.infinity.domain.generation.GenerationFailureCode.SERVER,
                                retryable = true,
                                technical = "Received HTML response instead of JSON stream.",
                            ))
                            return@execute
                        }
                    }

                    val channel = response.body<ByteReadChannel>()
                    activeChannel = channel
                    val sb = StringBuilder()
                    var currentEvent = ""
                    var currentData = ""
                    var sawTerminal = false

                    while (!channel.isClosedForRead) {
                        sb.clear()
                        val readSuccess = channel.readUTF8LineTo(sb, 1_048_576)
                        if (!readSuccess) break

                        val line = sb.toString()
                        if (line.isEmpty()) {
                            if (currentData.isNotEmpty()) {
                                sawTerminal = processSseEvent(currentEvent, currentData)
                                currentEvent = ""
                                currentData = ""
                                if (sawTerminal) break
                            }
                            continue
                        }

                        if (line.trimStart().startsWith('<')) {
                            send(StreamChunk.Error(
                                code = com.promenar.infinity.domain.generation.GenerationFailureCode.SERVER,
                                retryable = true,
                                technical = "Received HTML response instead of JSON stream.",
                            ))
                            sawTerminal = true
                            break
                        }

                        when {
                            line.startsWith("event: ") -> {
                                currentEvent = line.substring(7).trim()
                            }
                            line.startsWith("data: ") -> {
                                currentData = line.substring(6).trim()
                                if (currentEvent.isEmpty()) {
                                    currentEvent = "message"
                                }
                            }
                            line.startsWith("data:") -> {
                                currentData = line.substring(5).trim()
                                if (currentEvent.isEmpty()) {
                                    currentEvent = "message"
                                }
                            }
                        }
                    }

                    if (currentData.isNotEmpty()) {
                        sawTerminal = processSseEvent(currentEvent, currentData)
                    }
                    if (!sawTerminal) {
                        send(truncatedStreamError("Anthropic stream ended before message_stop"))
                    }
                }
                if (!retryWithoutThinking) break
                outgoing = outgoing.withoutThinkingReplay()
                toolUseAccumulator.clear()
                completedToolUses.clear()
                thinkingAccumulators.clear()
                completedThinking.clear()
                pendingStopReason = null
                startUsage = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(normalizeError(e))
        } finally {
            activeChannel = null
        }
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val response: HttpResponse
        try {
            response = httpClient.post(inferenceUrl()) {
                applySessionRequestHeaders(request.sessionHeaders)
                applyModelTimeout(ModelTimeoutKind.LLM)
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", anthropicVersion)
                setBody(buildRequestBody(request, stream = false))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val normalized = ErrorNormalizer.normalize(e)
            throw Exception("[${normalized.category}] ${normalized.technicalMessage}")
        }

        val responseText = try {
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }

        if (!response.status.isSuccess()) {
            val normalized = ErrorNormalizer.normalize(
                HttpStatusException(response.status.value, responseText)
            )
            throw Exception("[HTTP ${response.status.value}][${normalized.category}] ${responseText.take(300)}")
        }

        return parseSyncResponse(responseText)
    }

    override suspend fun listModels(): List<String> {
        return listModelDescriptors().map { it.id }
    }

    override suspend fun listModelDescriptors(): List<RemoteModelDescriptor> {
        val endpoint = ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointOperation.MODELS,
        )
        return try {
            httpClient.prepareGet(endpoint) {
                header("x-api-key", apiKey)
                header("anthropic-version", anthropicVersion)
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute emptyList()
                response.readBoundedModelListBody()
                    ?.let(GenericModelsEnvelopeParser::parseDescriptors)
                    .orEmpty()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun cancel() {
        activeChannel?.cancel()
    }

    private fun inferenceUrl(): String = ProviderEndpointResolver.resolve(
        protocolType,
        baseUrl,
        ProviderEndpointOperation.INFERENCE,
    )

    private fun buildRequestBody(rawRequest: PromptRequest, stream: Boolean): String {
        val adapted = rawRequest.adaptedTo(protocolType, rawRequest.model.ifEmpty { this@AnthropicProtocol.model }, baseUrl)
        val request = adapted.request
        val maxTokens = request.maxTokens ?: anthropicDefaultMaxTokens(request.model.ifEmpty { this@AnthropicProtocol.model })
        val body = buildJsonObject {
            put("model", request.model.ifEmpty { this@AnthropicProtocol.model })
            put("stream", stream)
            put("max_tokens", maxTokens)

            val systemMessage = request.messages.find { it.role == "system" }
            if (systemMessage != null) {
                put("system", systemMessage.content)
            }

            val nonSystemMessages = request.messages.filter { it.role != "system" }
            put("messages", JsonArray(nonSystemMessages.map { msg ->
                buildJsonObject {
                    put("role", if (msg.role == "tool") "user" else msg.role)
                    
                    val contentParts = mutableListOf<JsonObject>()

                    // 0. 同一模型当前回合的思考块必须原样、按原顺序放在助手内容最前面。
                    if (msg.role == "assistant") {
                        (msg.reasoningReplay as? ReasoningReplay.AnthropicBlocks)?.blocks?.forEach { block ->
                            contentParts.add(block.toJson())
                        }
                    }

                    msg.userTurnContext()?.let { context ->
                        contentParts.add(buildJsonObject {
                            put("type", "text")
                            put("text", context)
                        })
                    }
                    
                    // 1. Handle Text Content
                    if (msg.content.isNotEmpty() && msg.role != "tool") {
                        contentParts.add(buildJsonObject {
                            put("type", "text")
                            put("text", msg.content)
                        })
                    }
                    
                    // 2. Handle Images
                    msg.imageUrls?.takeIf { msg.role != "tool" }?.forEach { img ->
                        contentParts.add(buildJsonObject {
                            put("type", "image")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", img.mimeType)
                                put("data", img.base64 ?: "")
                            })
                        })
                    }
                    
                    // 3. Handle Documents
                    msg.documentData?.takeIf { msg.role != "tool" }?.forEach { doc ->
                        contentParts.add(buildJsonObject {
                            put("type", "document")
                            put("source", buildJsonObject {
                                put("type", "base64")
                                put("media_type", doc.mimeType)
                                put("data", doc.base64 ?: "")
                            })
                        })
                    }

                    // 4. Handle Assistant Tool Calls
                    if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                        msg.toolCalls.forEach { tc ->
                            val input = json.parseToJsonElement(tc.arguments)
                            require(input is JsonObject) {
                                "Anthropic assistant tool arguments must be a JSON object"
                            }
                            contentParts.add(buildJsonObject {
                                put("type", "tool_use")
                                put("id", tc.id)
                                put("name", tc.name)
                                put("input", input)
                            })
                        }
                    }

                    if (msg.role == "tool") {
                        contentParts.add(buildJsonObject {
                            put("type", "tool_result")
                            put("tool_use_id", msg.toolCallId.orEmpty())
                            val resultText = msg.mediaResultText(
                                "Anthropic Messages",
                                unsupportedAudio = msg.audioData.orEmpty(),
                                unsupportedVideo = msg.videoData.orEmpty(),
                            )
                            if (!msg.hasMediaPayload() && msg.documentData.isNullOrEmpty()) {
                                put("content", resultText)
                            } else {
                                put("content", buildJsonArray {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", resultText)
                                    })
                                    msg.imageUrls.orEmpty().forEach { image ->
                                        add(buildJsonObject {
                                            put("type", "image")
                                            put("source", buildJsonObject {
                                                put("type", "base64")
                                                put("media_type", image.mimeType)
                                                put("data", image.base64.orEmpty())
                                            })
                                        })
                                    }
                                    msg.documentData.orEmpty().forEach { document ->
                                        add(buildJsonObject {
                                            put("type", "document")
                                            put("source", buildJsonObject {
                                                put("type", "base64")
                                                put("media_type", document.mimeType)
                                                put("data", document.base64.orEmpty())
                                            })
                                        })
                                    }
                                })
                            }
                        })
                    }

                    // 5. Finalize Content Field
                    if (contentParts.size == 1 && contentParts[0]["type"]?.jsonPrimitive?.content == "text" && msg.role != "tool" && msg.userTurnContext() == null) {
                        // Optimization: plain text string if only one text part (and not a tool result which might prefer blocks)
                        put("content", msg.content)
                    } else if (contentParts.isNotEmpty()) {
                        put("content", JsonArray(contentParts))
                    } else {
                        put("content", "")
                    }

                }
            }))

            val modelId = request.model.ifEmpty { this@AnthropicProtocol.model }
            val reasoning = anthropicReasoningFields(modelId, adapted, maxTokens)
            val thinking = reasoning.thinking
            // 新模型对非默认采样参数一律返回 400；旧模型开启思考时也不接受 temperature / top_k。
            if (!rejectsSamplingParams(modelId) && thinking == null) {
                ProtocolParamAdapter.mapCommonParams(this, request)
                ProtocolParamAdapter.mapSamplingParams(this, request)
            }
            ProtocolParamAdapter.mapPenaltyParams(this, request, protocolType)
            thinking?.let { put("thinking", it) }
            reasoning.effort?.let { effort -> put("output_config", buildJsonObject { put("effort", effort) }) }

            if (request.tools != null && request.tools.isNotEmpty()) {
                put("tools", JsonArray(request.tools.canonicalizedForRequest().map { tool ->
                    buildJsonObject {
                        put("name", tool.function.name)
                        put("description", tool.function.description)
                        put("input_schema", try {
                            json.parseToJsonElement(tool.function.parameters)
                        } catch (_: Exception) {
                            buildJsonObject {
                                put("type", "object")
                                put("properties", buildJsonObject {})
                            }
                        })
                    }
                }))
                if (request.toolChoice == ProtocolToolChoice.NONE) {
                    put("tool_choice", buildJsonObject { put("type", "none") })
                }
            }
        }

        return body.toString()
    }

    /** 单次请求最新的累计 usage；后续分片只覆盖已报告字段。 */
    private var startUsage: JsonObject? = null

    private suspend fun SendChannel<StreamChunk>.processSseEvent(event: String, data: String): Boolean {
        if (data.isEmpty()) return false

        val eventData = try {
            json.parseToJsonElement(data).jsonObject
        } catch (_: Exception) {
            send(streamContractError("Malformed Anthropic stream event"))
            return true
        }

        val type = eventData["type"]?.jsonPrimitive?.contentOrNull ?: event

        when (type) {
            "content_block_delta" -> processContentBlockDelta(eventData)
            "content_block_start" -> processContentBlockStart(eventData)
            "content_block_stop" -> processContentBlockStop(eventData)
            "message_delta" -> try {
                processMessageDelta(eventData)
            } catch (abnormal: AbnormalFinishException) {
                send(abnormal.error)
                return true
            }
            "message_start" -> processMessageStart(eventData)
            "message_stop" -> {
                val reason = pendingStopReason
                    ?: run {
                        send(streamContractError("Anthropic message_stop missing stop_reason"))
                        return true
                    }
                if (toolUseAccumulator.isNotEmpty()) {
                    send(streamContractError("Anthropic message_stop has incomplete tool blocks"))
                    return true
                }
                val completion = validatedCompletion(
                    reason,
                    completedToolUses.toSortedMap().values.toList(),
                )
                if (completion is StreamChunk.Completed && completedThinking.isNotEmpty()) {
                    send(StreamChunk.ReasoningState(ReasoningReplay.AnthropicBlocks(completedThinking.values.toList())))
                }
                send(completion)
                return true
            }
            "ping" -> { }
            "error" -> {
                val errorObject = eventData["error"] as? JsonObject
                send(ProtocolErrorClassifier.classify(
                    type = errorObject?.stringField("type"),
                    code = errorObject?.stringField("code"),
                    technical = errorObject?.stringField("message")?.ifBlank { data } ?: data,
                ))
                return true
            }
        }
        return false
    }

    private suspend fun SendChannel<StreamChunk>.processContentBlockDelta(eventData: JsonObject) {
        val delta = eventData["delta"]?.jsonObject ?: return
        val deltaType = delta["type"]?.jsonPrimitive?.contentOrNull ?: return

        when (deltaType) {
            "text_delta" -> {
                val text = delta["text"]?.jsonPrimitive?.contentOrNull ?: return
                if (text.isNotEmpty()) {
                    send(StreamChunk.TextDelta(content = text))
                }
            }
            "thinking_delta" -> {
                val thinking = delta["thinking"]?.jsonPrimitive?.contentOrNull ?: return
                val index = eventData["index"]?.jsonPrimitive?.intOrNull ?: 0
                thinkingAccumulators[index]?.thinking?.append(thinking)
                if (thinking.isNotEmpty()) {
                    send(StreamChunk.Thinking(content = thinking))
                }
            }
            "signature_delta" -> {
                val signature = delta["signature"]?.jsonPrimitive?.contentOrNull ?: return
                val index = eventData["index"]?.jsonPrimitive?.intOrNull ?: 0
                thinkingAccumulators[index]?.let { it.signature += signature }
            }
            "input_json_delta" -> {
                val partialJson = delta["partial_json"]?.jsonPrimitive?.contentOrNull ?: return
                val index = eventData["index"]?.jsonPrimitive?.intOrNull ?: 0
                val acc = toolUseAccumulator[index] ?: return
                acc.arguments.append(partialJson)

                send(StreamChunk.ToolCallDelta(
                    id = acc.id,
                    name = acc.name,
                    arguments = partialJson,
                    index = index
                ))
            }
        }
    }

    private suspend fun SendChannel<StreamChunk>.processMessageDelta(eventData: JsonObject) {
        val usage = eventData["usage"] as? JsonObject
        if (usage != null) {
            val merged = ProtocolUsageParsers.anthropic(startUsage, usage)
            val reported = usage.filterValues { it !is JsonNull }.toMutableMap()
            val previousCreation = startUsage?.get("cache_creation") as? JsonObject
            val reportedCreation = reported["cache_creation"] as? JsonObject
            if (previousCreation != null && reportedCreation != null) {
                reported["cache_creation"] = JsonObject(previousCreation + reportedCreation.filterValues { it !is JsonNull })
            }
            startUsage = JsonObject(startUsage.orEmpty() + reported)
            send(StreamChunk.Usage(merged))
        }
        val stopReason = (eventData["delta"] as? JsonObject)?.stringField("stop_reason").orEmpty()
        if (stopReason.isNotEmpty()) {
            if (pendingStopReason != null) throw IllegalStateException("Duplicate Anthropic stop_reason")
            pendingStopReason = when (stopReason) {
                "end_turn" -> CompletionReason.END_TURN
                "tool_use" -> CompletionReason.TOOL_CALLS
                else -> throw abnormalFinishError("Anthropic stream", stopReason)?.let(::AbnormalFinishException)
                    ?: IllegalStateException("Unknown Anthropic stop_reason: $stopReason")
            }
        }
    }

    private suspend fun SendChannel<StreamChunk>.processMessageStart(eventData: JsonObject) {
        startUsage = (eventData["message"] as? JsonObject)?.get("usage") as? JsonObject
        startUsage?.let { send(StreamChunk.Usage(ProtocolUsageParsers.anthropic(it))) }
    }

    private suspend fun SendChannel<StreamChunk>.processContentBlockStart(eventData: JsonObject) {
        val contentBlock = eventData["content_block"]?.jsonObject ?: return
        val blockType = contentBlock["type"]?.jsonPrimitive?.contentOrNull ?: return
        if (blockType == AnthropicThinkingBlock.THINKING || blockType == AnthropicThinkingBlock.REDACTED) {
            val index = eventData["index"]?.jsonPrimitive?.intOrNull ?: 0
            thinkingAccumulators[index] = ThinkingAccumulator(
                type = blockType,
                thinking = StringBuilder(contentBlock.stringField("thinking")),
                signature = contentBlock.stringField("signature"),
                data = contentBlock.stringField("data"),
            )
            return
        }
        if (blockType != "tool_use") return

        val id = contentBlock["id"]?.jsonPrimitive?.contentOrNull ?: return
        val name = contentBlock["name"]?.jsonPrimitive?.contentOrNull ?: return
        val index = eventData["index"]?.jsonPrimitive?.intOrNull ?: 0

        check(index !in toolUseAccumulator && index !in completedToolUses) {
            "Duplicate Anthropic content block index"
        }
        val initialArguments = (contentBlock["input"] as? JsonObject)?.toString().orEmpty()
        toolUseAccumulator[index] = ToolUseAccumulator(id, name, initialArguments)

        send(StreamChunk.ToolCallDelta(
            id = id,
            name = name,
            arguments = "",
            index = index
        ))
    }

    private suspend fun SendChannel<StreamChunk>.processContentBlockStop(eventData: JsonObject) {
        val index = eventData["index"]?.jsonPrimitive?.intOrNull ?: return
        thinkingAccumulators.remove(index)?.let { thinking ->
            val block = thinking.toBlock()
            // 缺签名的块无法回传（官方要求原样回传），整块放弃比回传残缺块安全。
            if (!block.signature.isNullOrEmpty() || !block.data.isNullOrEmpty()) completedThinking[index] = block
            return
        }
        val acc = toolUseAccumulator.remove(index) ?: return

        val streamedArguments = acc.arguments.toString()
        val argsStr = streamedArguments.ifEmpty { acc.initialArguments }
        check(runCatching { json.parseToJsonElement(argsStr) is JsonObject }.getOrDefault(false)) {
            "Anthropic tool input is not a JSON object"
        }
        if (streamedArguments.isEmpty()) {
            send(StreamChunk.ToolCallDelta(acc.id, acc.name, argsStr, index))
        }
        completedToolUses[index] = ProtocolToolCall(acc.id, acc.name, argsStr)

        // 工具调用参数已通过 input_json_delta incremental fragments 在流式过程中发送完毕
        // ViewModel 侧已完成累积，content_block_stop 不再重复发送，避免双重累积
    }

    private fun parseSyncResponse(responseText: String): PromptResponse {
        val parsed = json.parseToJsonElement(responseText).jsonObject

        val error = parsed["error"]
        if (error != null) {
            val errorMsg = error.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                ?: responseText
            throw Exception(errorMsg)
        }

        val contentArray = parsed["content"]?.jsonArray
        var textContent = ""
        var thinkingContent = ""

        if (contentArray != null) {
            for (block in contentArray) {
                val blockObj = block.jsonObject
                when (blockObj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> {
                        textContent += blockObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    "thinking" -> {
                        thinkingContent += blockObj["thinking"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                }
            }
        }

        val usageRaw = parsed["usage"]?.jsonObject
        val usage = usageRaw?.let { ProtocolUsageParsers.anthropic(it) }

        val toolCalls = contentArray.orEmpty().mapNotNull { block ->
            val blockObj = block as? JsonObject
                ?: throw IllegalStateException("Anthropic sync content block is not an object")
            if (blockObj.stringField("type") == "tool_use") {
                val input = blockObj["input"] as? JsonObject
                    ?: throw IllegalStateException("Anthropic sync tool_use input is not an object")
                ProtocolToolCall(
                    id = blockObj.stringField("id"),
                    name = blockObj.stringField("name"),
                    arguments = json.encodeToString(JsonElement.serializer(), input),
                )
            } else null
        }
        val completionReason = when (val stopReason = parsed.stringField("stop_reason")) {
            "end_turn" -> CompletionReason.END_TURN
            "tool_use" -> CompletionReason.TOOL_CALLS
            else -> throw IllegalStateException("Unknown Anthropic stop_reason: $stopReason")
        }
        requireValidSyncCompletion(completionReason, toolCalls)

        return PromptResponse(
            content = textContent,
            reasoning = thinkingContent.ifEmpty { null },
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage
        )
    }

    private fun normalizeError(e: Exception): StreamChunk.Error {
        val normalized = ErrorNormalizer.normalize(e)
        return normalized.toStreamChunkError()
    }

    private fun shouldRetryWithoutThinking(statusCode: Int, errorBody: String, outgoing: PromptRequest): Boolean =
        statusCode == 400 && outgoing.hasThinkingReplay() && isThinkingReplayRejection(errorBody)

    private fun classifyProtocolError(
        statusCode: Int,
        responseBody: String,
        retryAfterHeader: String?,
    ): StreamChunk.Error {
        val errorObject = runCatching {
            json.parseToJsonElement(responseBody).jsonObject["error"] as? JsonObject
        }.getOrNull()
        return ProtocolErrorClassifier.classify(
            statusCode = statusCode,
            type = errorObject?.stringField("type"),
            code = errorObject?.stringField("code"),
            retryAfterHeader = retryAfterHeader,
            technical = errorObject?.stringField("message")?.ifBlank { responseBody } ?: responseBody,
        )
    }

    private fun JsonObject.stringField(key: String): String {
        val el = this[key] ?: return ""
        return if (el is JsonNull) "" else el.jsonPrimitive.contentOrNull ?: ""
    }

    internal companion object {
        /** 模型输出上限未知时的 max_tokens；思考与正文共用这一上限。 */
        const val DEFAULT_MAX_TOKENS = 16_384
        /** 已知输出上限时的封顶值，避免单次请求预留过大。 */
        const val MAX_DEFAULT_MAX_TOKENS = 64_000
    }
}

private fun AnthropicThinkingBlock.toJson(): JsonObject = buildJsonObject {
    put("type", type)
    if (type == AnthropicThinkingBlock.REDACTED) {
        put("data", data.orEmpty())
    } else {
        put("thinking", thinking.orEmpty())
        put("signature", signature.orEmpty())
    }
}

internal fun PromptRequest.hasThinkingReplay(): Boolean =
    messages.any { it.reasoningReplay is ReasoningReplay.AnthropicBlocks }

internal fun PromptRequest.withoutThinkingReplay(): PromptRequest = copy(
    messages = messages.map { message ->
        if (message.reasoningReplay is ReasoningReplay.AnthropicBlocks) message.copy(reasoningReplay = null) else message
    },
)

/** 官方对思考块签名失效、被改动或结构不合法的 400 都会在错误信息中提到 thinking 块与 signature。 */
internal fun isThinkingReplayRejection(errorBody: String): Boolean {
    val text = errorBody.lowercase()
    return "thinking" in text && ("signature" in text || "block" in text)
}

/** 思考默认开启且默认隐藏思考文本的 Claude 5 系列；对它们请求摘要显示，计费不变。 */
private val CLAUDE_ALWAYS_THINKING = Regex("""(^|[/.:])claude-(opus|sonnet|haiku|fable|mythos)-5(\b|[-.@])""")

/** 只接受自适应思考、不再接受 budget_tokens 的模型（4.6 起支持自适应，4.7 起拒绝 enabled）。 */
private val CLAUDE_ADAPTIVE_ONLY = Regex("""(^|[/.:])claude-(opus|sonnet)-4-([7-9])(\b|[-.@])""")
private val CLAUDE_ADAPTIVE_CAPABLE = Regex("""(^|[/.:])claude-(opus|sonnet)-4-6(\b|[-.@])""")

/** Anthropic 必须带 max_tokens；未自定义时取模型输出上限，避免思考加正文被截断。 */
internal fun anthropicDefaultMaxTokens(model: String): Int =
    ModelCatalogRuntime.resolver.resolveExactOrNull(model)?.outputTokens
        ?.takeIf { it > 0 }
        ?.coerceAtMost(AnthropicProtocol.MAX_DEFAULT_MAX_TOKENS)
        ?: AnthropicProtocol.DEFAULT_MAX_TOKENS

/** Claude 4.7 起非默认 temperature / top_p / top_k 都返回 400。 */
internal fun rejectsSamplingParams(model: String): Boolean {
    val id = model.lowercase()
    return CLAUDE_ALWAYS_THINKING.containsMatchIn(id) || CLAUDE_ADAPTIVE_ONLY.containsMatchIn(id)
}

/**
 * thinking 字段：Claude 5 系列始终请求摘要显示；其它模型只在显式要求思考时开启，
 * 4.6 及以后用自适应思考，更早的模型用 budget_tokens（须不少于 1024 且小于 max_tokens）。
 */
internal fun anthropicThinkingConfig(model: String, reasoningRequested: Boolean?, maxTokens: Int): JsonObject? {
    val id = model.lowercase()
    fun adaptive() = buildJsonObject {
        put("type", "adaptive")
        put("display", "summarized")
    }
    return when {
        CLAUDE_ALWAYS_THINKING.containsMatchIn(id) -> adaptive()
        reasoningRequested != true -> null
        CLAUDE_ADAPTIVE_ONLY.containsMatchIn(id) || CLAUDE_ADAPTIVE_CAPABLE.containsMatchIn(id) -> adaptive()
        maxTokens <= 1024 -> null
        else -> buildJsonObject {
            put("type", "enabled")
            put("budget_tokens", minOf(10_000, maxTokens - 1))
        }
    }
}
