package com.promenar.infinity.data.remote.provider

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.remote.UnifiedProviderConfig
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.VERTEX_DEFAULT_LOCATION
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.security.KeyPairGenerator
import java.util.Base64
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ProviderConnectionProbeTest {
    @Test fun `HTTP提供商可使用模型目录探测端点`() = runTest {
        var requested: String? = null
        val probe = ProviderConnectionProbe(HttpClient(MockEngine { request ->
            requested = request.url.toString()
            respond("""{"data":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }))
        val result = probe.probe(UnifiedProviderConfig(ProtocolType.OpenAI_ChatCompletions, "http://192.168.1.2/v1", "test-key", "model"))
        assertThat(result).isEqualTo(ProviderConnectionProbeResult.Success)
        assertThat(requested).isEqualTo("http://192.168.1.2/v1/models")
    }

    @Test
    fun `Anthropic empty models response is a successful no inference probe`() = runTest {
        var capturedMethod: HttpMethod? = null
        var capturedApiKey: String? = null
        var capturedVersion: String? = null
        val urls = mutableListOf<String>()
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine { request ->
                urls += request.url.toString()
                capturedMethod = request.method
                capturedApiKey = request.headers["x-api-key"]
                capturedVersion = request.headers["anthropic-version"]
                respond(
                    content = """{"data":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Anthropic_Messages,
                baseUrl = "https://api.anthropic.com",
                apiKey = "fake-key",
                defaultModel = "",
            ),
        )

        assertThat(result).isEqualTo(ProviderConnectionProbeResult.Success)
        assertThat(urls).containsExactly("https://api.anthropic.com/v1/models?limit=1")
        assertThat(capturedMethod).isEqualTo(HttpMethod.Get)
        assertThat(capturedApiKey).isEqualTo("fake-key")
        assertThat(capturedVersion).isEqualTo("2023-06-01")
    }

    @Test
    fun `Vertex connection probe performs OAuth exchange only and never generation`() = runTest {
        val fixture = validCredentialJson()
        val urls = mutableListOf<String>()
        var formBody = ""
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine { request ->
                urls += request.url.toString()
                formBody = request.body.toByteReadPacket().readText()
                respond(
                    content = """{"access_token":"fake-access-token","expires_in":3600}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Google_VertexAI,
                baseUrl = "https://us-central1-aiplatform.googleapis.com",
                apiKey = "",
                defaultModel = "gemini-2.5-pro",
                serviceAccountJson = fixture.json,
                projectId = "",
                location = VERTEX_DEFAULT_LOCATION,
            ),
        )

        assertThat(result).isEqualTo(ProviderConnectionProbeResult.Success)
        assertThat(urls).containsExactly("https://oauth2.googleapis.com/token")
        assertThat(urls.joinToString()).doesNotContain("generateContent")
        assertThat(formBody).contains("grant_type=")
        assertThat(formBody).contains("assertion=")
        assertThat(formBody).doesNotContain(fixture.privateKeyPem)
    }

    @Test
    fun `unsupported no cost probe is not reported as failure or success and sends no HTTP`() = runTest {
        var requestCount = 0
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine {
                requestCount++
                error("不应请求")
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Qwen_DashScope,
                baseUrl = ProtocolType.Qwen_DashScope.defaultBaseUrl,
                apiKey = "fake-key",
                defaultModel = "",
            ),
        )

        assertThat(result).isEqualTo(ProviderConnectionProbeResult.Unsupported)
        assertThat(requestCount).isEqualTo(0)
    }

    @Test
    fun `未知持久化协议与退役Yi探测均零网络fail closed`() = runTest {
        var requestCount = 0
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine {
                requestCount++
                error("未知或退役协议不应请求网络")
            }),
        )

        val unknown = probe.probePersisted(
            persistedProtocol = "Future_Unknown_Protocol",
            baseUrl = "https://unknown.invalid/v1/chat/completions",
            apiKey = "fake-key",
        )
        val retiredYi = probe.probePersisted(
            persistedProtocol = ProtocolType.Yi_ZeroOne::class.simpleName.orEmpty(),
            baseUrl = ProtocolType.Yi_ZeroOne.defaultBaseUrl,
            apiKey = "fake-key",
        )

        assertThat(unknown).isEqualTo(ProviderConnectionProbeResult.Unsupported)
        assertThat(retiredYi).isEqualTo(ProviderConnectionProbeResult.Unsupported)
        assertThat(requestCount).isEqualTo(0)
    }

    @Test
    fun `Local is not remotely probed or unconditionally reported connected`() = runTest {
        var requestCount = 0
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine {
                requestCount++
                error("Local 不应进入远程探测")
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Local,
                baseUrl = "",
                apiKey = "",
                defaultModel = "local.gguf",
            ),
        )

        assertThat(result).isEqualTo(ProviderConnectionProbeResult.Unsupported)
        assertThat(requestCount).isEqualTo(0)
    }

    @Test
    fun `Vertex attacker endpoint is rejected before OAuth exchange`() = runTest {
        val fixture = validCredentialJson()
        var requestCount = 0
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine {
                requestCount++
                error("非法 Vertex origin 不应进入 OAuth")
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Google_VertexAI,
                baseUrl = "https://attacker.example.invalid",
                apiKey = "",
                defaultModel = "gemini-2.5-pro",
                serviceAccountJson = fixture.json,
                projectId = "",
                location = VERTEX_DEFAULT_LOCATION,
            ),
        )

        assertThat(result).isEqualTo(
            ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.ENDPOINT_INVALID,
            ),
        )
        assertThat(requestCount).isEqualTo(0)
    }

    @Test
    fun `Vertex 非字符串凭证字段报告 credentials invalid 而不是 endpoint invalid`() = runTest {
        var requestCount = 0
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine {
                requestCount++
                error("非法凭证不应进入网络")
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Google_VertexAI,
                baseUrl = "https://us-central1-aiplatform.googleapis.com",
                apiKey = "",
                defaultModel = "gemini-2.5-pro",
                serviceAccountJson =
                    """{"project_id":"project-safe","client_email":{},"private_key":"unused"}""",
                location = VERTEX_DEFAULT_LOCATION,
            ),
        )

        assertThat(result).isEqualTo(
            ProviderConnectionProbeResult.Failure(
                ProviderConnectionProbeFailure.CREDENTIALS_INVALID,
            ),
        )
        assertThat(requestCount).isEqualTo(0)
    }

    @Test
    fun `models probe rejects 2xx without JSON object data array`() = runTest {
        listOf(
            "" to HttpStatusCode.NoContent,
            "<html>ok</html>" to HttpStatusCode.OK,
            "{}" to HttpStatusCode.OK,
            "{\"data\":{}}" to HttpStatusCode.OK,
        ).forEach { (body, status) ->
            val probe = ProviderConnectionProbe(
                HttpClient(MockEngine {
                    respond(
                        content = body,
                        status = status,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }),
            )

            val result = probe.probe(
                UnifiedProviderConfig(
                    protocolType = ProtocolType.OpenAI_ChatCompletions,
                    baseUrl = "https://api.openai.com",
                    apiKey = "fake-key",
                    defaultModel = "",
                ),
            )

            assertThat(result).isEqualTo(
                ProviderConnectionProbeResult.Failure(
                    ProviderConnectionProbeFailure.RESPONSE_INVALID,
                ),
            )
        }
    }

    @Test
    fun `Generic models probe与listModels共享裸数组兼容及畸形元素失败关闭`() = runTest {
        suspend fun probe(body: String): ProviderConnectionProbeResult = ProviderConnectionProbe(
            HttpClient(MockEngine {
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }),
        ).probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Generic_OpenAI_Compat,
                baseUrl = "https://generic.example.test/v1/chat/completions",
                apiKey = "fake-key",
                defaultModel = "",
            ),
        )

        assertThat(probe("""[{"id":"bare-model"}]"""))
            .isEqualTo(ProviderConnectionProbeResult.Success)
        assertThat(probe("""{"data":[{"id":"valid"},{}]}"""))
            .isEqualTo(
                ProviderConnectionProbeResult.Failure(
                    ProviderConnectionProbeFailure.RESPONSE_INVALID,
                ),
            )
    }

    @Test
    fun `probe failure never includes upstream body credentials or private key`() = runTest {
        val fixture = validCredentialJson()
        val upstreamMarker = "UPSTREAM-SECRET-MARKER"
        val probe = ProviderConnectionProbe(
            HttpClient(MockEngine {
                respond(
                    content = upstreamMarker,
                    status = HttpStatusCode.Unauthorized,
                    headers = headersOf(HttpHeaders.ContentType, "text/plain"),
                )
            }),
        )

        val result = probe.probe(
            UnifiedProviderConfig(
                protocolType = ProtocolType.Google_VertexAI,
                baseUrl = "https://us-central1-aiplatform.googleapis.com",
                apiKey = "",
                defaultModel = "gemini-2.5-pro",
                serviceAccountJson = fixture.json,
                location = VERTEX_DEFAULT_LOCATION,
            ),
        )

        assertThat(result).isInstanceOf(ProviderConnectionProbeResult.Failure::class.java)
        assertThat(result.toString()).doesNotContain(upstreamMarker)
        assertThat(result.toString()).doesNotContain(fixture.privateKeyPem)
        assertThat(result.toString()).doesNotContain(fixture.json)
    }

    private fun validCredentialJson(): CredentialFixture {
        val encoded = KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }
            .generateKeyPair().private.encoded
        val pem = "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getEncoder().encodeToString(encoded) +
            "\n-----END PRIVATE KEY-----"
        encoded.fill(0)
        val json = """{"project_id":"project-safe","client_email":"service@example.invalid","private_key":${jsonString(pem)}}"""
        return CredentialFixture(json, pem)
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(char)
            }
        }
        append('"')
    }

    private data class CredentialFixture(val json: String, val privateKeyPem: String)
}
