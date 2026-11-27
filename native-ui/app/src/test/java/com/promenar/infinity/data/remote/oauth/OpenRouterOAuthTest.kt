package com.promenar.infinity.data.remote.oauth

import com.google.common.truth.Truth.assertThat
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class OpenRouterOAuthTest {

    @Test
    fun `PKCE S256 遵循 RFC 7636 向量且生成高熵 verifier 与 state`() {
        // RFC 7636 Appendix B 公开测试向量
        val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertThat(OpenRouterOAuth.sha256Base64Url(rfcVerifier))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")

        val pair = OpenRouterOAuth.generatePkcePair(SecureRandom())
        assertThat(pair.codeVerifier.length).isAtLeast(43)
        assertThat(pair.codeChallenge).isEqualTo(OpenRouterOAuth.sha256Base64Url(pair.codeVerifier))
        assertThat(pair.state.length).isAtLeast(32)
        assertThat(pair.codeVerifier).doesNotContain("=")
        assertThat(pair.codeChallenge).doesNotContain("=")
    }

    @Test
    fun `构造授权地址正确编码回环回调地址与 PKCE 参数`() {
        val url = OpenRouterOAuth.buildAuthorizationUrl(
            callbackUrl = "http://127.0.0.1:45123/callback",
            codeChallenge = "abc_123-XYZ",
            state = "st_999",
        )
        assertThat(url).startsWith("https://openrouter.ai/auth?")
        assertThat(url).contains("code_challenge=abc_123-XYZ")
        assertThat(url).contains("code_challenge_method=S256")
        assertThat(url).contains("state=st_999")
        assertThat(URLDecoder.decode(url, "UTF-8")).contains("callback_url=http://127.0.0.1:45123/callback")
    }

    @Test
    fun `本机回环接收器验证 state 捕获授权码并拒绝伪造 state`() = runBlocking {
        OpenRouterLoopbackServer.bind().use { server ->
            val deferred = async(Dispatchers.IO) {
                server.awaitAuthorizationCode(expectedState = "expected-state", timeoutMillis = 5_000)
            }
            val client = OkHttpClient()
            client.newCall(
                Request.Builder().url("${server.callbackUrl}?code=or_auth_code_42&state=expected-state").build(),
            ).execute().use { resp ->
                assertThat(resp.code).isEqualTo(200)
                assertThat(resp.body?.string().orEmpty()).contains(OpenRouterOAuthDeepLinkBus.DONE_DEEP_LINK)
            }
            val result = deferred.await()
            assertThat(result).isEqualTo(OpenRouterCallbackResult.Authorized("or_auth_code_42"))
        }

        OpenRouterLoopbackServer.bind().use { server ->
            val deferred = async(Dispatchers.IO) {
                server.awaitAuthorizationCode(expectedState = "expected-state", timeoutMillis = 5_000)
            }
            val client = OkHttpClient()
            client.newCall(
                Request.Builder().url("${server.callbackUrl}?code=or_auth_code_42&state=wrong-state").build(),
            ).execute().use { resp ->
                assertThat(resp.code).isEqualTo(400)
            }
            assertThat(deferred.await()).isEqualTo(OpenRouterCallbackResult.StateMismatch)
        }
    }

    @Test
    fun `授权码换取密钥支持成功解析与 403 或 400 分类`() = runBlocking {
        val responses = listOf(
            200 to """{"key":"sk-or-v1-testkey123456"}""",
            403 to """{"error":"region blocked"}""",
            400 to """{"error":"invalid_grant"}""",
        )
        val requestBodies = CopyOnWriteArrayList<String>()
        val callIndex = AtomicInteger(0)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/auth/keys") { exchange ->
            val body = exchange.requestBody.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
            requestBodies.add(body)
            val (status, responseBody) = responses[callIndex.getAndIncrement()]
            val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val url = "http://127.0.0.1:${server.address.port}/api/v1/auth/keys"

            val ok = OpenRouterOAuth.exchangeAuthorizationCode(
                code = "code_1",
                codeVerifier = "verifier_1",
                exchangeUrl = url,
                clientBuilder = { OkHttpClient.Builder() },
            )
            assertThat(ok).isEqualTo(OpenRouterExchangeResult.Success("sk-or-v1-testkey123456"))
            val reqBody = requestBodies.first()
            assertThat(reqBody).contains("\"code\":\"code_1\"")
            assertThat(reqBody).contains("\"code_verifier\":\"verifier_1\"")
            assertThat(reqBody).contains("\"code_challenge_method\":\"S256\"")

            val blocked = OpenRouterOAuth.exchangeAuthorizationCode(
                code = "code_2",
                codeVerifier = "verifier_2",
                exchangeUrl = url,
                clientBuilder = { OkHttpClient.Builder() },
            )
            assertThat(blocked).isEqualTo(OpenRouterExchangeResult.NetworkOrBlocked(403))

            val rejected = OpenRouterOAuth.exchangeAuthorizationCode(
                code = "code_3",
                codeVerifier = "verifier_3",
                exchangeUrl = url,
                clientBuilder = { OkHttpClient.Builder() },
            )
            assertThat(rejected).isEqualTo(OpenRouterExchangeResult.Rejected(400))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `剪贴板密钥识别器仅放行合法密钥形态并脱敏展示`() {
        assertThat(ApiKeyClipboardDetector.detectCandidate("sk-7a9f1234567890abcdef3e21"))
            .isEqualTo("sk-7a9f1234567890abcdef3e21")
        assertThat(ApiKeyClipboardDetector.detectCandidate("  ms-9a8b7c6d5e4f3a2b1c0d9e8f  "))
            .isEqualTo("ms-9a8b7c6d5e4f3a2b1c0d9e8f")
        assertThat(ApiKeyClipboardDetector.detectCandidate("AIzaSyD1234567890abcdefghijk"))
            .isEqualTo("AIzaSyD1234567890abcdefghijk")
        assertThat(ApiKeyClipboardDetector.detectCandidate("0123456789abcdef0123456789abcdef.AbCdEf1234567890"))
            .isEqualTo("0123456789abcdef0123456789abcdef.AbCdEf1234567890")

        // 普通文本、短串与含空白文本不得误判
        assertThat(ApiKeyClipboardDetector.detectCandidate("hello world this is normal text")).isNull()
        assertThat(ApiKeyClipboardDetector.detectCandidate("sk-short")).isNull()
        assertThat(ApiKeyClipboardDetector.detectCandidate("sk-12345678901234567890 with space")).isNull()

        assertThat(ApiKeyClipboardDetector.maskSecret("sk-7a9f1234567890abcdef3e21"))
            .isEqualTo("sk-7a9f••••••••3e21")
    }
}
