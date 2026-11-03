package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.remote.applySessionRequestHeaders

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import com.promenar.infinity.data.remote.ThinkingDetector
import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.parser.HttpStatusException
import io.ktor.client.*
import kotlinx.coroutines.CancellationException
import io.ktor.client.call.body
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.*
import com.promenar.infinity.domain.generation.CompletionReason

class OpenAIProtocol(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.OpenAI_ChatCompletions

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

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = channelFlow {
        activeChannel = null
        val thinkingDetector = ThinkingDetector()
        val toolCallAccumulator = mutableMapOf<Int, AccumulatedToolCall>()
        val reasoningField = ReasoningFieldTracker()

        try {
            httpClient.preparePost(inferenceUrl()) {
                applySessionRequestHeaders(request.sessionHeaders)
                applyModelTimeout(ModelTimeoutKind.LLM)
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                header("Accept", "text/event-stream")
                header("Accept-Encoding", "identity") // 强制禁用压缩，防止 Gzip 导致流式输出攒块
                header("Cache-Control", "no-cache")
                header("Connection", "keep-alive")
                setBody(buildRequestBody(request, stream = true))
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val errorBody = try {
                        response.bodyAsText()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        ""
                    }
                    val normalized = ErrorNormalizer.normalize(
                        HttpStatusException(response.status.value, errorBody)
                    )
                    val error = ProtocolContextLimit.fromHttp(protocolType, response.status.value, errorBody)
                        ?: normalized.toStreamChunkError()
                    // 保留服务商给出的限流等待秒数，无有效响应头时沿用归一化默认值。
                    val retryAfter = response.headers[HttpHeaders.RetryAfter]?.trim()?.toLongOrNull()
                        ?.takeIf { it > 0 }?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
                    send(if (error.code == com.promenar.infinity.domain.generation.GenerationFailureCode.RATE_LIMIT && retryAfter != null) {
                        error.copy(retryAfterSeconds = retryAfter)
                    } else error)
                    return@execute
                }

                response.contentType()?.let { ct ->
                    if (ct.match(ContentType.Text.Html)) {
                        send(StreamChunk.Error(
                            code = com.promenar.infinity.domain.generation.GenerationFailureCode.SERVER,
                            retryable = false,
                            technical = "Received HTML response instead of JSON stream.",
                        ))
                        return@execute
                    }
                }

                val channel = response.body<ByteReadChannel>()
                activeChannel = channel
                val sb = StringBuilder()
                var completionReason: CompletionReason? = null
                var sawDone = false
                var failed = false

                while (!channel.isClosedForRead) {
                    sb.clear()
                    val readSuccess = channel.readUTF8LineTo(sb, 1_048_576)
                    if (!readSuccess) break

                    val line = sb.toString().trim()
                    if (line.isEmpty()) continue

                    if (line.trimStart().startsWith('<')) {
                        send(StreamChunk.Error(
                            code = com.promenar.infinity.domain.generation.GenerationFailureCode.SERVER,
                            retryable = false,
                            technical = "Received HTML response instead of JSON stream.",
                        ))
                        failed = true
                        break
                    }

                    val data = extractSseData(line) ?: continue
                    if (data.trim() == "[DONE]") {
                        sawDone = true
                        break
                    }

                    try {
                        val chunkJson = json.parseToJsonElement(data).jsonObject
                        val parsedReason = processStreamChunk(chunkJson, thinkingDetector, toolCallAccumulator, reasoningField)
                        if (parsedReason != null) {
                            if (completionReason != null) {
                                throw IllegalStateException("Duplicate OpenAI finish_reason")
                            }
                            completionReason = parsedReason
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (abnormal: AbnormalFinishException) {
                        send(abnormal.error)
                        failed = true
                        break
                    } catch (_: Exception) {
                        send(streamContractError("Malformed OpenAI stream event"))
                        failed = true
                        break
                    }
                }
                if (!failed) {
                    flushRemaining(thinkingDetector)
                    val reason = completionReason
                    when {
                        !sawDone -> send(truncatedStreamError("OpenAI stream ended before [DONE]"))
                        reason == null -> send(streamContractError("OpenAI stream missing finish_reason"))
                        else -> {
                            val completion = validatedCompletion(
                                reason,
                                toolCallAccumulator.toSortedMap().values.map {
                                    ProtocolToolCall(it.id, it.name, it.arguments)
                                },
                            )
                            if (completion is StreamChunk.Completed) {
                                reasoningField.replay()?.let { send(StreamChunk.ReasoningState(it)) }
                            }
                            send(completion)
                        }
                    }
                }
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
                header("Authorization", "Bearer $apiKey")
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
            ProtocolContextLimit.throwIfPresent(ProtocolContextLimit.fromHttp(protocolType, response.status.value, responseText))
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
                header("Authorization", "Bearer $apiKey")
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
        val adapted = rawRequest.adaptedTo(protocolType, rawRequest.model.ifEmpty { this@OpenAIProtocol.model }, baseUrl)
        val request = adapted.request
        val messages = chatMessagesWithToolMedia(request.messages)
        val body = buildJsonObject {
            put("model", request.model.ifEmpty { this@OpenAIProtocol.model })
            put("stream", stream)

            put("messages", JsonArray(messages.map { msg ->
                buildJsonObject {
                    put("role", msg.role)
                    
                    val hasMultimodal = msg.imageUrls?.isNotEmpty() == true || msg.audioData?.isNotEmpty() == true
                    if (!hasMultimodal) {
                        put("content", msg.textWithTurnContext())
                    } else {
                        val contentParts = buildJsonArray {
                            msg.userTurnContext()?.let { context ->
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", context)
                                })
                            }
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", msg.content)
                            })
                            msg.imageUrls?.forEach { img ->
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", img.url ?: "data:${img.mimeType};base64,${img.base64}")
                                    })
                                })
                            }
                            msg.audioData?.forEach { aud ->
                                add(buildJsonObject {
                                    put("type", "input_audio")
                                    put("input_audio", buildJsonObject {
                                        put("data", aud.base64)
                                        put("format", requireNotNull(chatAudioFormat(aud.mimeType)))
                                    })
                                })
                            }
                        }
                        put("content", contentParts)
                    }

                    msg.name?.let { put("name", it) }

                    putChatReasoningReplay(msg)

                    if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                        put("tool_calls", JsonArray(msg.toolCalls.map { tc ->
                            buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            }
                        }))
                    }

                    if (msg.role == "tool") {
                        msg.toolCallId?.let { put("tool_call_id", it) }
                        msg.name?.let { put("name", it) }
                    }
                }
            }))

            val hasAudio = messages.any { it.audioData?.isNotEmpty() == true }
            if (hasAudio) {
                put("modalities", buildJsonArray {
                    add(JsonPrimitive("text"))
                })
            }

            ProtocolParamAdapter.mapCommonParams(this, request)
            ProtocolParamAdapter.mapPenaltyParams(this, request, protocolType)
            ProtocolParamAdapter.mapSamplingParams(this, request)
            putChatReasoning(adapted)

            if (stream) {
                put("stream_options", buildJsonObject { put("include_usage", true) })
            }

            if (!request.tools.isNullOrEmpty()) {
                put("tools", JsonArray(request.tools.canonicalizedForRequest().map { tool ->
                    buildJsonObject {
                        put("type", tool.type)
                        put("function", buildJsonObject {
                            put("name", tool.function.name)
                            put("description", tool.function.description)
                            put("parameters", json.parseToJsonElement(tool.function.parameters))
                        })
                    }
                }))
                put("tool_choice", if (request.toolChoice == ProtocolToolChoice.NONE) "none" else "auto")
            }
        }

        return body.toString()
    }

    private suspend fun SendChannel<StreamChunk>.processStreamChunk(
        chunk: JsonObject,
        thinkingDetector: ThinkingDetector,
        toolCallAccumulator: MutableMap<Int, AccumulatedToolCall>,
        reasoningField: ReasoningFieldTracker,
    ): CompletionReason? {
        chunk["error"]?.takeUnless { it is JsonNull }?.let { throw AbnormalFinishException(streamErrorEvent(chunk, protocolType)) }
        val choice = (chunk["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (choice == null) {
            val usageRaw = chunk["usage"]?.takeUnless { it is JsonNull }?.jsonObject
            if (usageRaw != null) send(StreamChunk.Usage(usageRaw.toOpenAIUsage()))
            return null
        }
        val delta = choice["delta"] as? JsonObject ?: JsonObject(emptyMap())
        reasoningField.observe(delta)

        var content = delta.stringField("content")
        var reasoning = delta.stringField("reasoning_content")

        val thinkingResult = thinkingDetector.process(content)
        content = thinkingResult.content
        reasoning += thinkingResult.reasoning

        content = cleanSpecialTokens(content)
        reasoning = cleanSpecialTokens(reasoning)

        val deltaToolCalls = delta["tool_calls"]?.takeUnless { it is JsonNull }?.jsonArray
        if (deltaToolCalls != null) {
            for (tcElement in deltaToolCalls) {
                val tc = tcElement.jsonObject
                val index = tc["index"]?.jsonPrimitive?.intOrNull ?: continue

                val existing = toolCallAccumulator[index]
                // OpenAI SSE streaming 中 function.arguments 字段携带的是增量片段 (partial)
                // 协议层在本地累积完整 JSON，但发送给 ViewModel 的 ToolCallDelta 必须只携带
                // 本次新增的 fragment。否则 ViewModel 二次累积会导致参数膨胀/损坏。
                val fragment = tc["function"]?.jsonObject?.get("arguments")?.jsonPrimitive?.contentOrNull ?: ""
                if (existing == null) {
                    toolCallAccumulator[index] = AccumulatedToolCall(
                        id = tc["id"]?.jsonPrimitive?.contentOrNull ?: "",
                        name = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                        arguments = fragment
                    )
                } else {
                    val newId = tc["id"]?.jsonPrimitive?.contentOrNull
                    val newName = tc["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull

                    toolCallAccumulator[index] = existing.copy(
                        id = if (newId.isNullOrEmpty()) existing.id else newId,
                        name = if (newName.isNullOrEmpty()) existing.name else newName,
                        arguments = existing.arguments + fragment
                    )
                }

                // 发送增量 fragment（而非完整累积值），与 AnthropicProtocol 保持一致
                send(StreamChunk.ToolCallDelta(
                    id = toolCallAccumulator[index]!!.id,
                    name = toolCallAccumulator[index]!!.name,
                    arguments = fragment,
                    index = index
                ))
            }
        }

        // 无条件发送 TextDelta：即使 content/reasoning 为空（ThinkingDetector 可能临时扣留内容），
        // 也必须让消费者感知到流中有数据到来，否则 UI 会陷入"长时间静止→全量弹出"的假死状态。
        // 空 TextDelta 仅触发轻量 recomposition，性能影响可忽略。
        send(StreamChunk.TextDelta(content, reasoning.ifEmpty { null }))

        val usageRaw = chunk["usage"]?.takeUnless { it is JsonNull }?.jsonObject
        if (usageRaw != null) {
            send(StreamChunk.Usage(ProtocolUsageParsers.openAiChat(usageRaw)))
        }

        return when (val finishReason = choice.stringField("finish_reason")) {
            "" -> null
            "stop" -> CompletionReason.END_TURN
            "tool_calls" -> CompletionReason.TOOL_CALLS
            else -> throw abnormalFinishError("OpenAI stream", finishReason)?.let(::AbnormalFinishException)
                ?: IllegalStateException("Unknown OpenAI finish_reason: $finishReason")
        }
    }

    private suspend fun SendChannel<StreamChunk>.flushRemaining(
        thinkingDetector: ThinkingDetector,
    ) {
        val remaining = thinkingDetector.flush()
        if (remaining.content.isNotEmpty() || remaining.reasoning.isNotEmpty()) {
            send(StreamChunk.TextDelta(remaining.content, remaining.reasoning.ifEmpty { null }))
        }

        // 成功 terminal 由调用方在 [DONE] 与 finish_reason 双重确认后发出。
    }

    private fun JsonObject.toOpenAIUsage(): ProtocolUsage = ProtocolUsageParsers.openAiChat(this)

    private fun parseSyncResponse(responseText: String): PromptResponse {
        val parsed = json.parseToJsonElement(responseText).jsonObject
        (parsed["error"] as? JsonObject)?.let {
            ProtocolContextLimit.throwIfPresent(ProtocolContextLimit.fromError(protocolType, it))
        }
        val choice = parsed["choices"]
            ?.jsonArray?.firstOrNull() as? JsonObject
            ?: throw IllegalStateException("OpenAI sync response missing choice")
        val message = choice["message"] as? JsonObject
            ?: throw IllegalStateException("OpenAI sync response missing message")

        var content = message.stringField("content")
        var reasoning = message.stringField("reasoning_content")

        if (content.contains("<think")) {
            val match = Regex("""<think[^>]*>([\s\S]*?)</think\s*>""", RegexOption.IGNORE_CASE)
                .find(content)
            if (match != null) {
                reasoning = match.groupValues[1] + reasoning
                content = content.replace(
                    Regex("""<think[^>]*>[\s\S]*?</think\s*>""", RegexOption.IGNORE_CASE),
                    ""
                ).trim()
            }
        }

        content = cleanSpecialTokens(content)
        reasoning = cleanSpecialTokens(reasoning)

        val toolCallElements = message["tool_calls"]?.takeUnless { it is JsonNull }?.let { element ->
            element as? JsonArray
                ?: throw IllegalStateException("OpenAI sync tool_calls is not an array")
        }.orEmpty()
        val toolCalls = toolCallElements.map { tcElement ->
            val tc = tcElement as? JsonObject
                ?: throw IllegalStateException("OpenAI sync tool call is not an object")
            val function = tc["function"] as? JsonObject
                ?: throw IllegalStateException("OpenAI sync tool call missing function")
            ProtocolToolCall(
                id = tc.stringField("id"),
                name = function.stringField("name"),
                arguments = function.stringField("arguments"),
            )
        }
        val completionReason = when (val finishReason = choice.stringField("finish_reason")) {
            "stop" -> CompletionReason.END_TURN
            "tool_calls" -> CompletionReason.TOOL_CALLS
            else -> throw IllegalStateException("Unknown OpenAI finish_reason: $finishReason")
        }
        requireValidSyncCompletion(completionReason, toolCalls)

        val usageRaw = parsed["usage"]?.takeUnless { it is JsonNull }?.jsonObject
        val usage = usageRaw?.let(ProtocolUsageParsers::openAiChat)

        return PromptResponse(
            content = content,
            reasoning = reasoning.ifEmpty { null },
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage
        )
    }

    private fun extractSseData(line: String): String? {
        if (line.startsWith("data: ")) return line.substring(6)
        if (line.startsWith("data:")) return line.substring(5)
        return null
    }

    private fun cleanSpecialTokens(text: String): String {
        if (text.isEmpty()) return text
        return text
            .replace("<|end_of_thinking|>", "")
            .replace("< | end__of__thinking | >", "")
            .replace("<|endofthinking|>", "")
    }

    private fun normalizeError(e: Exception): StreamChunk.Error {
        val normalized = ErrorNormalizer.normalize(e)
        return normalized.toStreamChunkError()
    }

    private fun JsonObject.stringField(key: String): String {
        val el = this[key] ?: return ""
        return if (el is JsonNull) "" else el.jsonPrimitive.contentOrNull ?: ""
    }

    private data class AccumulatedToolCall(
        val id: String,
        val name: String,
        val arguments: String
    )
}
