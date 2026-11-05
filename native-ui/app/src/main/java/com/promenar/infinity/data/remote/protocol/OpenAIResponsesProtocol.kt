package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.remote.applySessionRequestHeaders

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.parser.HttpStatusException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readUTF8LineTo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.promenar.infinity.domain.generation.CompletionReason

private data class ResponsesToolAccumulator(
    var itemId: String = "",
    var callId: String = "",
    var name: String = "",
    var arguments: String = "",
    var emittedArgumentsLength: Int = 0,
)

class OpenAIResponsesProtocol(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.OpenAI_Responses

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
        try {
            httpClient.preparePost(inferenceUrl()) {
                applySessionRequestHeaders(request.sessionHeaders)
                applyModelTimeout(ModelTimeoutKind.LLM)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                header(HttpHeaders.Accept, "text/event-stream")
                header(HttpHeaders.CacheControl, "no-cache")
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
                    send(classifyProtocolError(
                        statusCode = response.status.value,
                        responseBody = errorBody,
                        retryAfterHeader = response.headers[HttpHeaders.RetryAfter],
                    ))
                    return@execute
                }

                val channel = response.body<ByteReadChannel>()
                activeChannel = channel
                val sb = StringBuilder()
                val toolCalls = mutableMapOf<Int, ResponsesToolAccumulator>()
                var sawTerminal = false
                while (!channel.isClosedForRead) {
                    sb.clear()
                    val readSuccess = channel.readUTF8LineTo(sb, 1_048_576)
                    if (!readSuccess) break

                    val data = extractSseData(sb.toString().trim()) ?: continue
                    if (data.trim() == "[DONE]") continue
                    val chunk = try {
                        json.parseToJsonElement(data).jsonObject
                    } catch (_: Exception) {
                        send(streamContractError("Malformed OpenAI Responses stream event"))
                        sawTerminal = true
                        break
                    }
                    val terminal = processResponsesStreamChunk(chunk, toolCalls)
                    if (terminal != null) {
                        send(terminal)
                        sawTerminal = true
                        break
                    }
                }
                if (!sawTerminal) {
                    send(truncatedStreamError("OpenAI Responses stream ended before response.completed"))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(ErrorNormalizer.normalize(e).toStreamChunkError())
        } finally {
            activeChannel = null
        }
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val response: HttpResponse = try {
            httpClient.post(inferenceUrl()) {
                applySessionRequestHeaders(request.sessionHeaders)
                applyModelTimeout(ModelTimeoutKind.LLM)
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $apiKey")
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
            val normalized = ErrorNormalizer.normalize(HttpStatusException(response.status.value, responseText))
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
                header(HttpHeaders.Authorization, "Bearer $apiKey")
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
        val adapted = rawRequest.adaptedTo(protocolType, rawRequest.model.ifEmpty { this@OpenAIResponsesProtocol.model }, baseUrl)
        val request = adapted.request
        return buildJsonObject {
            put("model", request.model.ifEmpty { this@OpenAIResponsesProtocol.model })
            put("stream", stream)
            put("input", JsonArray(request.messages.flatMap { msg -> msg.toResponsesInputItems() }))
            request.temperature?.let { put("temperature", it) }
            request.topP?.let { put("top_p", it) }
            request.maxTokens?.let { put("max_output_tokens", it) }
            putResponsesReasoning(adapted)
            if (!request.tools.isNullOrEmpty()) {
                put("tools", JsonArray(request.tools.canonicalizedForRequest().map { tool ->
                    buildJsonObject {
                        put("type", "function")
                        put("name", tool.function.name)
                        put("description", tool.function.description)
                        put("parameters", json.parseToJsonElement(tool.function.parameters))
                    }
                }))
                put("tool_choice", if (request.toolChoice == ProtocolToolChoice.NONE) "none" else "auto")
            }
        }.toString()
    }

    private fun ProtocolMessage.toResponsesInputItems(): List<JsonObject> {
        val resultText = mediaResultText(
            "Responses",
            unsupportedAudio = audioData.orEmpty(),
            unsupportedVideo = videoData.orEmpty(),
        )
        if (role == "tool") {
            val callId = toolCallId.orEmpty()
            return listOf(buildJsonObject {
                put("type", "function_call_output")
                put("call_id", callId)
                if (!hasMediaPayload()) {
                    put("output", resultText)
                } else {
                    put("output", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", resultText)
                        })
                        imageUrls.orEmpty().forEach { image ->
                            add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", image.url ?: "data:${image.mimeType};base64,${image.base64.orEmpty()}")
                            })
                        }
                    })
                }
            })
        }
        val items = mutableListOf<JsonObject>()
        if (content.isNotEmpty() || userTurnContext() != null || toolCalls.isNullOrEmpty()) {
            items += buildJsonObject {
                put("role", if (role == "system") "developer" else role)
                val hasMultimodal = imageUrls?.isNotEmpty() == true
                if (!hasMultimodal) {
                    put("content", textWithTurnContext(resultText))
                } else {
                    put("content", buildJsonArray {
                        userTurnContext()?.let { context ->
                            add(buildJsonObject {
                                put("type", "input_text")
                                put("text", context)
                            })
                        }
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", resultText)
                        })
                        imageUrls?.forEach { img ->
                            add(buildJsonObject {
                                put("type", "input_image")
                                put("image_url", img.url ?: "data:${img.mimeType};base64,${img.base64}")
                            })
                        }
                    })
                }
            }
        }
        if (role == "assistant") {
            toolCalls.orEmpty().forEach { call ->
                items += buildJsonObject {
                    put("type", "function_call")
                    put("call_id", call.id)
                    put("name", call.name)
                    put("arguments", call.arguments)
                }
            }
        }
        return items
    }

    private suspend fun SendChannel<StreamChunk>.processResponsesStreamChunk(
        chunk: JsonObject,
        toolCalls: MutableMap<Int, ResponsesToolAccumulator>,
    ): StreamChunk? {
        when (val type = chunk.stringField("type")) {
            "response.output_text.delta" -> {
                send(StreamChunk.TextDelta(chunk.stringField("delta")))
            }
            "response.output_item.added", "response.output_item.done" -> {
                val index = chunk["output_index"]?.jsonPrimitive?.intOrNull
                    ?: return streamContractError("Responses function_call missing output_index")
                val item = chunk["item"] as? JsonObject ?: return null
                if (item.stringField("type") == "function_call") {
                    mergeResponsesToolCall(index, item, toolCalls)
                }
            }
            "response.function_call_arguments.delta" -> {
                val index = chunk["output_index"]?.jsonPrimitive?.intOrNull
                    ?: return streamContractError("Responses arguments delta missing output_index")
                val accumulator = toolCalls[index]
                    ?: return streamContractError("Responses arguments delta missing function_call")
                val delta = chunk.stringField("delta")
                accumulator.arguments += delta
                send(StreamChunk.ToolCallDelta(
                    accumulator.callId,
                    accumulator.name,
                    delta,
                    index,
                ))
                accumulator.emittedArgumentsLength = accumulator.arguments.length
            }
            "response.function_call_arguments.done" -> {
                val index = chunk["output_index"]?.jsonPrimitive?.intOrNull
                    ?: return streamContractError("Responses arguments done missing output_index")
                val accumulator = toolCalls[index]
                    ?: return streamContractError("Responses arguments done missing function_call")
                val finalArguments = chunk.stringField("arguments")
                if (finalArguments.isNotEmpty() && finalArguments != accumulator.arguments) {
                    if (!finalArguments.startsWith(accumulator.arguments)) {
                        return streamContractError("Responses argument fragments do not match done event")
                    }
                    val remainder = finalArguments.removePrefix(accumulator.arguments)
                    accumulator.arguments = finalArguments
                    if (remainder.isNotEmpty()) {
                        send(StreamChunk.ToolCallDelta(
                            accumulator.callId,
                            accumulator.name,
                            remainder,
                            index,
                        ))
                        accumulator.emittedArgumentsLength = accumulator.arguments.length
                    }
                }
            }
            "response.completed" -> {
                val response = chunk["response"]?.jsonObject
                    ?: return streamContractError("Responses completed event missing response")
                if (response.stringField("status") != "completed") {
                    return streamContractError("Responses completed event has unknown status")
                }
                for ((index, element) in (response["output"] as? JsonArray).orEmpty().withIndex()) {
                    val item = element as? JsonObject ?: continue
                    if (item.stringField("type") == "function_call") {
                        mergeResponsesToolCall(index, item, toolCalls)
                    }
                }
                val usage = response?.get("usage")?.jsonObject?.toResponsesUsage()
                if (usage != null) send(StreamChunk.Usage(usage))
                val calls = toolCalls.toSortedMap().values.map {
                    ProtocolToolCall(it.callId, it.name, it.arguments)
                }
                return validatedCompletion(
                    if (calls.isEmpty()) CompletionReason.END_TURN else CompletionReason.TOOL_CALLS,
                    calls,
                )
            }
            "response.failed", "response.incomplete", "error" -> {
                if (type == "response.incomplete") {
                    val reason = ((chunk["response"] as? JsonObject)?.get("incomplete_details") as? JsonObject)
                        ?.stringField("reason").orEmpty()
                    abnormalFinishError("OpenAI Responses stream", reason)?.let { return it }
                }
                val errorObject = when (type) {
                    "response.failed", "response.incomplete" ->
                        (chunk["response"] as? JsonObject)?.get("error") as? JsonObject
                        ?: chunk["error"] as? JsonObject
                    else -> chunk["error"] as? JsonObject
                }
                return errorObject?.let { ProtocolContextLimit.fromError(protocolType, it) }
                    ?: ProtocolContextLimit.fromError(protocolType, chunk)
                    ?: ProtocolErrorClassifier.classify(
                        type = errorObject?.stringField("type"),
                        code = errorObject?.stringField("code"),
                        technical = errorObject?.stringField("message")
                            ?.ifBlank { chunk.stringField("message") }
                            ?.ifBlank { null }
                            ?: "OpenAI Responses request failed.",
                    )
            }
            else -> {
                val delta = chunk.stringField("delta")
                if (delta.isNotEmpty() && type.contains("output_text")) {
                    send(StreamChunk.TextDelta(delta))
                }
            }
        }
        return null
    }

    private suspend fun SendChannel<StreamChunk>.mergeResponsesToolCall(
        index: Int,
        item: JsonObject,
        toolCalls: MutableMap<Int, ResponsesToolAccumulator>,
    ) {
        val accumulator = toolCalls.getOrPut(index) { ResponsesToolAccumulator() }
        item.stringField("id").takeIf(String::isNotEmpty)?.let { accumulator.itemId = it }
        item.stringField("call_id").takeIf(String::isNotEmpty)?.let { accumulator.callId = it }
        item.stringField("name").takeIf(String::isNotEmpty)?.let { accumulator.name = it }
        val arguments = item.stringField("arguments")
        if (arguments.isNotEmpty()) {
            if (accumulator.arguments.isNotEmpty() && arguments != accumulator.arguments) {
                check(arguments.startsWith(accumulator.arguments)) {
                    "Responses function_call arguments mismatch"
                }
            }
            accumulator.arguments = arguments
        }
        if (
            accumulator.callId.isNotBlank() &&
            accumulator.name.isNotBlank() &&
            accumulator.emittedArgumentsLength < accumulator.arguments.length
        ) {
            val pendingArguments = accumulator.arguments.substring(accumulator.emittedArgumentsLength)
            send(StreamChunk.ToolCallDelta(
                id = accumulator.callId,
                name = accumulator.name,
                arguments = pendingArguments,
                index = index,
            ))
            accumulator.emittedArgumentsLength = accumulator.arguments.length
        }
    }

    private fun parseSyncResponse(responseText: String): PromptResponse {
        val root = json.parseToJsonElement(responseText).jsonObject
        (root["error"] as? JsonObject)?.let {
            ProtocolContextLimit.throwIfPresent(ProtocolContextLimit.fromError(protocolType, it))
        }
        if (root.stringField("status") == "incomplete") {
            (root["incomplete_details"] as? JsonObject)?.stringField("reason")
                ?.let { throwIfAbnormalSyncFinish("Responses", it) }
        }
        check(root.stringField("status") == "completed") {
            "Responses sync response did not complete successfully"
        }
        val toolCalls = root["output"]?.jsonArray
            ?.mapNotNull { outputItem ->
                val item = outputItem as? JsonObject ?: return@mapNotNull null
                if (item.stringField("type") != "function_call") return@mapNotNull null
                ProtocolToolCall(
                    id = item.stringField("call_id"),
                    name = item.stringField("name"),
                    arguments = item.stringField("arguments"),
                )
            }
            .orEmpty()
        requireValidSyncCompletion(
            if (toolCalls.isEmpty()) CompletionReason.END_TURN else CompletionReason.TOOL_CALLS,
            toolCalls,
        )
        val outputText = root.stringField("output_text").ifBlank {
            root["output"]?.jsonArray
                ?.flatMap { outputItem ->
                    outputItem.jsonObject["content"]?.jsonArray?.mapNotNull { contentItem ->
                        val contentObject = contentItem.jsonObject
                        if (contentObject.stringField("type") == "output_text") contentObject.stringField("text") else null
                    } ?: emptyList()
                }
                ?.joinToString("")
                .orEmpty()
        }
        val usage = root["usage"]?.jsonObject?.toResponsesUsage()
        return PromptResponse(
            content = outputText,
            toolCalls = toolCalls.ifEmpty { null },
            usage = usage,
        )
    }

    private fun JsonObject.toResponsesUsage(): ProtocolUsage = ProtocolUsageParsers.openAiResponses(this)

    private fun extractSseData(line: String): String? {
        if (line.startsWith("data: ")) return line.substring(6)
        if (line.startsWith("data:")) return line.substring(5)
        return null
    }

    private fun classifyProtocolError(
        statusCode: Int,
        responseBody: String,
        retryAfterHeader: String?,
    ): StreamChunk.Error {
        ProtocolContextLimit.fromHttp(protocolType, statusCode, responseBody)?.let { return it }
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
}
