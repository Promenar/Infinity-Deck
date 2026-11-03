package com.promenar.infinity.data.remote.protocol

import com.promenar.infinity.data.remote.applySessionRequestHeaders

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import com.promenar.infinity.data.remote.provider.ParsedVertexCredential
import com.promenar.infinity.data.remote.provider.VertexCredentialException
import com.promenar.infinity.data.remote.provider.VertexCredentialFailure
import com.promenar.infinity.data.remote.provider.VertexCredentialParser
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

class VertexAIProtocol(
    private val serviceAccountJson: String,
    private val projectId: String,
    private val location: String = VERTEX_DEFAULT_LOCATION,
    private val model: String = "",
    private val baseUrl: String = "",
    httpClient: HttpClient? = null
) : LlmProtocol {

    override val protocolType: ProtocolType = ProtocolType.Google_VertexAI

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

    @Volatile
    private var cachedToken: String? = null

    @Volatile
    private var tokenExpiryMs: Long = 0L

    @Volatile
    private var parsedCredential: ParsedVertexCredential? = null

    override suspend fun sendPrompt(request: PromptRequest): Flow<StreamChunk> = channelFlow {
        activeChannel = null

        val credential = try {
            parsedCredential()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            send(StreamChunk.Error(
                code = com.promenar.infinity.domain.generation.GenerationFailureCode.AUTH,
                retryable = false,
                technical = "Vertex AI Authentication Failed: ${safeCredentialError(error)}",
            ))
            return@channelFlow
        }

        val endpoint = try {
            inferenceUrl(request, streaming = true, credential = credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            send(StreamChunk.Error(
                code = com.promenar.infinity.domain.generation.GenerationFailureCode.INVALID_REQUEST,
                retryable = false,
                technical = "Vertex endpoint validation failed",
            ))
            return@channelFlow
        }

        val token: String
        try {
            token = getAccessToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(StreamChunk.Error(
                code = com.promenar.infinity.domain.generation.GenerationFailureCode.AUTH,
                retryable = false,
                technical = "Vertex AI Authentication Failed: ${safeCredentialError(e)}",
            ))
            return@channelFlow
        }

        val response: HttpResponse
        try {
            response = httpClient.post(endpoint) {
                applySessionRequestHeaders(request.sessionHeaders)
                applyModelTimeout(ModelTimeoutKind.LLM)
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(buildRequestBody(request))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(normalizeError(e))
            return@channelFlow
        }

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
            send(ProtocolContextLimit.fromHttp(protocolType, response.status.value, errorBody)
                ?: normalized.toStreamChunkError())
            return@channelFlow
        }

        response.contentType()?.let { ct ->
            if (ct.match(ContentType.Text.Html)) {
                send(StreamChunk.Error(
                    code = com.promenar.infinity.domain.generation.GenerationFailureCode.SERVER,
                    retryable = false,
                    technical = "Received HTML response instead of JSON stream.",
                ))
                return@channelFlow
            }
        }
        val channel = response.body<ByteReadChannel>()
        activeChannel = channel
        try {
            consumeGeminiStream(
                channel = channel,
                state = GeminiStreamState(),
                json = json,
                label = "Vertex",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            send(normalizeError(e))
        } finally {
            activeChannel = null
        }
    }

    override suspend fun sendPromptSync(request: PromptRequest): PromptResponse {
        val credential = try {
            parsedCredential()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw Exception("Vertex AI Authentication Failed: ${safeCredentialError(error)}")
        }
        // 必须在创建 JWT 或交换 OAuth token 前验证 inference origin 与所有路径 segment。
        val endpoint = inferenceUrl(request, streaming = false, credential = credential)
        val token: String
        try {
            token = getAccessToken()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            throw Exception("Vertex AI Authentication Failed: ${safeCredentialError(e)}")
        }

        val response: HttpResponse
        try {
            response = httpClient.post(endpoint) {
                applySessionRequestHeaders(request.sessionHeaders)
                applyModelTimeout(ModelTimeoutKind.LLM)
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $token")
                setBody(buildRequestBody(request))
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

        return GeminiContentCodec.parseSyncResponse(responseText, json, "Vertex AI")
    }


    override suspend fun listModels(): List<String> {
        ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointOperation.MODELS,
        )
        return emptyList()
    }

    override fun cancel() {
        activeChannel?.cancel()
    }

    private suspend fun getAccessToken(): String {
        cachedToken?.let { token ->
            if (System.currentTimeMillis() < tokenExpiryMs - 300_000) {
                return token
            }
        }

        val credential = parsedCredential()
        val jwt = VertexCredentialParser.createJwtAssertion(credential)
        val tokenResponse = exchangeJwtForToken(jwt)

        cachedToken = tokenResponse.accessToken
        tokenExpiryMs = System.currentTimeMillis() + tokenResponse.expiresInSeconds * 1000L

        return tokenResponse.accessToken
    }

    private fun parsedCredential(): ParsedVertexCredential {
        parsedCredential?.let { return it }
        return VertexCredentialParser.parse(serviceAccountJson, projectId).also {
            parsedCredential = it
        }
    }

    private fun safeCredentialError(error: Exception): String = when (
        (error as? VertexCredentialException)?.reason
    ) {
        VertexCredentialFailure.MISSING_CREDENTIALS -> "Service account credentials are missing"
        VertexCredentialFailure.INVALID_JSON -> "Service account credentials are invalid"
        VertexCredentialFailure.MISSING_PROJECT_ID,
        VertexCredentialFailure.MISSING_CLIENT_EMAIL,
        VertexCredentialFailure.MISSING_PRIVATE_KEY,
        VertexCredentialFailure.PROJECT_ID_MISMATCH,
        -> "Service account credentials are incomplete"
        VertexCredentialFailure.INVALID_PRIVATE_KEY -> "Service account private key is invalid"
        null -> "Vertex authentication request failed"
    }

    private data class TokenResponse(
        val accessToken: String,
        val expiresInSeconds: Long
    )

    private suspend fun exchangeJwtForToken(jwt: String): TokenResponse {
        val endpoint = ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointTarget.VertexOAuthToken,
        )
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt"
            )
        }

        val body = try {
            response.bodyAsText()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            ""
        }

        if (!response.status.isSuccess()) {
            throw IllegalStateException("Vertex token exchange failed")
        }

        val parsed = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            throw IllegalStateException("Vertex token exchange response is invalid")
        }

        val accessToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("Vertex token exchange response is invalid")

        val expiresIn = parsed["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L

        return TokenResponse(accessToken, expiresIn)
    }

    private fun inferenceUrl(
        request: PromptRequest,
        streaming: Boolean,
        credential: ParsedVertexCredential,
    ): String {
        return ProviderEndpointResolver.resolve(
            protocolType,
            baseUrl,
            ProviderEndpointTarget.VertexInference(
                projectId = credential.projectId,
                location = location,
                model = request.model.ifBlank { model },
                streaming = streaming,
            ),
        )
    }

    private fun buildRequestBody(request: PromptRequest): String =
        GeminiContentCodec.buildRequestBody(request, json, isVertex = true)

    private fun normalizeError(e: Exception): StreamChunk.Error {
        val normalized = ErrorNormalizer.normalize(e)
        return normalized.toStreamChunkError()
    }

}
