package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.remote.applySessionRequestHeaders
import com.promenar.infinity.data.remote.observeFirstByte

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import com.promenar.infinity.data.remote.parser.ErrorNormalizer
import com.promenar.infinity.data.remote.parser.HttpStatusException
import com.promenar.infinity.domain.generation.GenerationFailureCode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.json.Json

/**
 * Google Gemini API（generativelanguage.googleapis.com，API Key 认证）。
 * 请求体与流解析与 Vertex AI 共用 [GeminiContentCodec]；API Key 只经 `x-goog-api-key` 请求头发送。
 */
class GeminiProtocol(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String = "",
    httpClient: HttpClient? = null,
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.Google_Gemini

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
        val endpoint = try {
            inferenceUrl(request, streaming = true)
        } catch (_: IllegalArgumentException) {
            send(StreamChunk.Error(
                code = GenerationFailureCode.INVALID_REQUEST,
                retryable = false,
                technical = "Gemini endpoint validation failed",
            ))
            return@channelFlow
        }
        try {
            httpClient.preparePost(endpoint) {
                applySessionRequestHeaders(request.sessionHeaders)
                applyModelTimeout(ModelTimeoutKind.LLM)
                contentType(ContentType.Application.Json)
                header(API_KEY_HEADER, apiKey)
                setBody(GeminiContentCodec.buildRequestBody(request, json))
                request.latencyTrace?.dispatched()
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    val body = runCatching { response.bodyAsText() }.getOrDefault("")
                    send(geminiHttpError(response.status.value, body))
                    return@execute
                }
                val channel = response.bodyAsChannel()
                activeChannel = channel
                try {
                    channel.observeFirstByte(request.latencyTrace)
                    consumeGeminiStream(
                        channel = channel,
                        state = GeminiStreamState(),
                        json = json,
                        label = "Gemini",
                    )
                } finally {
                    activeChannel = null
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            send(ErrorNormalizer.normalize(error).toStreamChunkError())
        }
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val endpoint = inferenceUrl(request, streaming = false)
        return httpClient.preparePost(endpoint) {
            applySessionRequestHeaders(request.sessionHeaders)
            applyModelTimeout(ModelTimeoutKind.LLM)
            contentType(ContentType.Application.Json)
            header(API_KEY_HEADER, apiKey)
            setBody(GeminiContentCodec.buildRequestBody(request, json))
        }.execute { response ->
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            if (!response.status.isSuccess()) {
                ProtocolContextLimit.throwIfPresent(ProtocolContextLimit.fromHttp(protocolType, response.status.value, text))
                val normalized = ErrorNormalizer.normalize(HttpStatusException(response.status.value, text))
                throw Exception("[HTTP ${response.status.value}][${normalized.category}] ${text.take(300)}")
            }
            GeminiContentCodec.parseSyncResponse(text, json, "Gemini")
        }
    }

    override suspend fun listModels(): List<String> = listModelDescriptors().map { it.id }

    /** 模型列表返回 `models/{id}`，这里去掉前缀，与请求路径中的模型段一致。 */
    override suspend fun listModelDescriptors(): List<RemoteModelDescriptor> {
        val endpoint = ProviderEndpointResolver.resolve(protocolType, baseUrl, ProviderEndpointOperation.MODELS)
        return try {
            httpClient.prepareGet(endpoint) { header(API_KEY_HEADER, apiKey) }.execute { response ->
                if (!response.status.isSuccess()) return@execute emptyList()
                response.readBoundedModelListBody()
                    ?.let(GenericModelsEnvelopeParser::parseDescriptors)
                    .orEmpty()
                    .map { it.copy(id = it.id.removePrefix("models/")) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun cancel() {
        activeChannel?.cancel(null)
    }

    private fun inferenceUrl(request: PromptRequest, streaming: Boolean): String =
        ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointTarget.GeminiInference(model = request.model.ifBlank { model }, streaming = streaming),
        )

    companion object {
        const val API_KEY_HEADER = "x-goog-api-key"
    }
}
