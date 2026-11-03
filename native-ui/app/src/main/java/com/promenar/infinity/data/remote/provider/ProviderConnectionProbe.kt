package com.promenar.infinity.data.remote.provider

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.ProviderEndpointOperation
import com.promenar.infinity.data.remote.protocol.ProviderEndpointResolver
import com.promenar.infinity.data.remote.protocol.ProviderEndpointTarget
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.GeminiProtocol
import com.promenar.infinity.data.remote.protocol.GenericModelsEnvelopeParser
import com.promenar.infinity.data.remote.protocol.UnsupportedProviderOperationException
import com.promenar.infinity.data.remote.protocol.UnsupportedProviderProtocolException
import com.promenar.infinity.data.remote.protocol.UnsupportedPersistedProtocolException
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI

enum class ProviderConnectionProbeFailure {
    CREDENTIALS_MISSING,
    CREDENTIALS_INVALID,
    AUTHENTICATION_REJECTED,
    NETWORK_UNAVAILABLE,
    RESPONSE_INVALID,
    ENDPOINT_INVALID,
}

sealed interface ProviderConnectionProbeResult {
    data object Success : ProviderConnectionProbeResult
    data object Unsupported : ProviderConnectionProbeResult

    data class Failure(
        val reason: ProviderConnectionProbeFailure,
        val statusCode: Int? = null,
        val detail: String? = null,
    ) : ProviderConnectionProbeResult {
        override fun toString(): String = "ProviderConnectionProbeResult.Failure(reason=$reason, statusCode=$statusCode)"
    }
}

/**
 * 唯一 Provider 连接探测入口。只访问权威无推理成本端点；不发送 prompt，也不调用 generation。
 */
class ProviderConnectionProbe(
    private val httpClient: HttpClient = ModelHttp.client {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
    },
) {
    companion object {
        /**
         * 进程级共享探测器。其 HttpClient 与应用进程同生命周期，避免 Compose 重组反复创建连接池。
         * 单元测试仍通过公开构造器注入 MockEngine。
         */
        val processScoped: ProviderConnectionProbe by lazy { ProviderConnectionProbe() }
    }

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun probePersisted(
        persistedProtocol: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String = "",
        serviceAccountJson: String = "",
        projectId: String = "",
        location: String = VERTEX_DEFAULT_LOCATION,
    ): ProviderConnectionProbeResult {
        val protocol = try {
            ProtocolType.fromLegacyName(persistedProtocol)
        } catch (_: UnsupportedPersistedProtocolException) {
            return ProviderConnectionProbeResult.Unsupported
        }
        return probe(
            UnifiedProviderConfig(
                protocolType = protocol,
                baseUrl = baseUrl,
                apiKey = apiKey,
                defaultModel = defaultModel,
                serviceAccountJson = serviceAccountJson,
                projectId = projectId,
                location = location,
            ),
        )
    }

    suspend fun probe(config: UnifiedProviderConfig): ProviderConnectionProbeResult {
        return try {
            when (config.protocolType) {
                ProtocolType.Local -> ProviderConnectionProbeResult.Unsupported
                ProtocolType.Google_VertexAI -> probeVertex(config)
                else -> probeApiKeyProvider(config)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: UnsupportedProviderOperationException) {
            ProviderConnectionProbeResult.Unsupported
        } catch (_: UnsupportedProviderProtocolException) {
            ProviderConnectionProbeResult.Unsupported
        } catch (_: VertexCredentialException) {
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.CREDENTIALS_INVALID)
        } catch (_: IllegalArgumentException) {
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.ENDPOINT_INVALID)
        } catch (_: Exception) {
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.NETWORK_UNAVAILABLE)
        }
    }

    private suspend fun probeApiKeyProvider(
        config: UnifiedProviderConfig,
    ): ProviderConnectionProbeResult {
        if (config.apiKey.isBlank()) {
            return ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.CREDENTIALS_MISSING,
            )
        }
        val endpoint = ProviderEndpointResolver.resolve(
            config.protocolType,
            config.baseUrl,
            ProviderEndpointOperation.CONNECTION_PROBE,
        )
        requireHttpEndpoint(endpoint)
        val response = httpClient.get(endpoint) {
            when (config.protocolType) {
                ProtocolType.Anthropic_Messages -> {
                    header("x-api-key", config.apiKey)
                    header("anthropic-version", "2023-06-01")
                }
                ProtocolType.Google_Gemini -> header(GeminiProtocol.API_KEY_HEADER, config.apiKey)
                else -> header(HttpHeaders.Authorization, "Bearer ${config.apiKey}")
            }
        }
        if (!response.status.isSuccess()) {
            return ProviderConnectionProbeResult.Failure(
                reason = ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED,
                statusCode = response.status.value,
                detail = response.status.description,
            )
        }
        val hasModelsEnvelope = if (
            config.protocolType == ProtocolType.Generic_OpenAI_Compat ||
            config.protocolType == ProtocolType.Google_Gemini
        ) {
            GenericModelsEnvelopeParser.parse(response.bodyAsText()) != null
        } else {
            hasCanonicalModelsEnvelope(response.bodyAsText())
        }
        return if (hasModelsEnvelope) {
            ProviderConnectionProbeResult.Success
        } else {
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.RESPONSE_INVALID)
        }
    }

    private suspend fun probeVertex(
        config: UnifiedProviderConfig,
    ): ProviderConnectionProbeResult {
        if (config.serviceAccountJson.isBlank()) {
            return ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.CREDENTIALS_MISSING,
            )
        }
        val credential = VertexCredentialParser.parse(
            serviceAccountJson = config.serviceAccountJson,
            explicitProjectId = config.projectId,
        )
        ProviderEndpointResolver.resolve(
            ProtocolType.Google_VertexAI,
            config.baseUrl,
            ProviderEndpointTarget.VertexInference(
                projectId = credential.projectId,
                location = config.location.ifBlank { VERTEX_DEFAULT_LOCATION },
                model = config.defaultModel.ifBlank { "endpoint-check" },
                streaming = false,
            ),
        )
        val assertion = VertexCredentialParser.createJwtAssertion(credential)
        val endpoint = ProviderEndpointResolver.resolve(
            ProtocolType.Google_VertexAI,
            config.baseUrl,
            ProviderEndpointTarget.VertexOAuthToken,
        )
        val response = httpClient.post(endpoint) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(
                "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$assertion",
            )
        }
        if (!response.status.isSuccess()) {
            return ProviderConnectionProbeResult.Failure(
                reason = ProviderConnectionProbeFailure.AUTHENTICATION_REJECTED,
                statusCode = response.status.value,
                detail = response.status.description,
            )
        }
        val hasAccessToken = try {
            json.parseToJsonElement(response.bodyAsText()).jsonObject["access_token"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.isNotBlank() == true
        } catch (_: Exception) {
            false
        }
        return if (hasAccessToken) {
            ProviderConnectionProbeResult.Success
        } else {
            ProviderConnectionProbeResult.Failure(ProviderConnectionProbeFailure.RESPONSE_INVALID)
        }
    }

    private fun hasCanonicalModelsEnvelope(body: String): Boolean = try {
        json.parseToJsonElement(body).jsonObject["data"]?.jsonArray != null
    } catch (_: Exception) {
        false
    }

    private fun requireHttpEndpoint(endpoint: String) {
        val uri = try {
            URI(endpoint)
        } catch (_: Exception) {
            throw IllegalArgumentException("Provider endpoint 无效")
        }
        require((uri.scheme.equals("https", ignoreCase = true) || uri.scheme.equals("http", ignoreCase = true)) && !uri.host.isNullOrBlank()) {
            "Provider endpoint 必须使用 HTTP 或 HTTPS"
        }
    }
}
