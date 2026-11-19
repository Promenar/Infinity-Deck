package com.promenar.infinity.utils

import com.google.common.truth.Truth.assertThat
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Headers.Companion.toHeaders
import org.junit.jupiter.api.Test

class SensitiveDataRedactorTest {
    @Test
    fun `redactHeaders removes credential values`() {
        val headers = mapOf(
            "Authorization" to "Bearer obviously-fake-token",
            "Cookie" to "session=obviously-fake-cookie",
            "X-Api-Key" to "obviously-fake-api-key",
            "Content-Type" to "application/json"
        ).toHeaders()

        val redacted = SensitiveDataRedactor.redactHeaders(headers)

        assertThat(redacted["Authorization"]).isEqualTo("[REDACTED]")
        assertThat(redacted["Cookie"]).isEqualTo("[REDACTED]")
        assertThat(redacted["X-Api-Key"]).isEqualTo("[REDACTED]")
        assertThat(redacted["Content-Type"]).isEqualTo("application/json")
        assertThat(redacted.toString()).doesNotContain("obviously-fake")
    }

    @Test
    fun `redactUrl removes query and fragment`() {
        val redacted = SensitiveDataRedactor.redactUrl(
            "https://example.invalid/v1/chat?api_key=obviously-fake-api-key#private-fragment"
        )

        assertThat(redacted).isEqualTo("https://example.invalid/v1/chat")
    }

    @Test
    fun `redactMessage removes structured user content and credentials`() {
        val message = """
            Authorization: Bearer obviously-fake-token
            Cookie: session=obviously-fake-cookie
            prompt=obviously-fake-prompt
            response=obviously-fake-response
            body=obviously-fake-body
            bindArgs=[obviously-fake-sql-value]
            url=https://example.invalid/chat?token=obviously-fake-query
        """.trimIndent()

        val redacted = SensitiveDataRedactor.redactMessage(message)

        assertThat(redacted).doesNotContain("obviously-fake")
        assertThat(redacted).doesNotContain("?token=")
        assertThat(redacted).contains("[REDACTED]")
    }

    @Test
    fun `redactMessage redacts JSON keys while preserving non-sensitive structure`() {
        val message = """
            {
              "event": "request_complete",
              "prompt": "obviously-fake-prompt with \"escaped quotes\"",
              "response" : "obviously-fake-response",
              "body": "obviously-fake-body",
              "apiKey": "obviously-fake-camel-key",
              "api_key" : "obviously-fake-snake-key",
              "authorization": "Bearer obviously-fake-token",
              "token" : "obviously-fake-token",
              "nested": {"count": 2, "label": "keep-me"}
            }
        """.trimIndent()

        val redacted = SensitiveDataRedactor.redactMessage(message)
        val parsed = Json.parseToJsonElement(redacted).jsonObject

        assertThat(redacted).doesNotContain("obviously-fake")
        assertThat(parsed["event"]!!.jsonPrimitive.content).isEqualTo("request_complete")
        assertThat(parsed["prompt"]!!.jsonPrimitive.content).isEqualTo("[REDACTED]")
        assertThat(parsed["response"]!!.jsonPrimitive.content).isEqualTo("[REDACTED]")
        assertThat(parsed["body"]!!.jsonPrimitive.content).isEqualTo("[REDACTED]")
        assertThat(parsed["apiKey"]!!.jsonPrimitive.content).isEqualTo("[REDACTED]")
        assertThat(parsed["api_key"]!!.jsonPrimitive.content).isEqualTo("[REDACTED]")
        assertThat(parsed["authorization"]!!.jsonPrimitive.content).isEqualTo("[REDACTED]")
        assertThat(parsed["token"]!!.jsonPrimitive.content).isEqualTo("[REDACTED]")
        assertThat(parsed["nested"]!!.jsonObject["count"]!!.jsonPrimitive.content).isEqualTo("2")
        assertThat(parsed["nested"]!!.jsonObject["label"]!!.jsonPrimitive.content).isEqualTo("keep-me")
    }

    @Test
    fun `safeThrowable never includes message or stack trace`() {
        val throwable = IllegalStateException("request failed with obviously-fake-token")
            .apply { stackTrace = arrayOf(StackTraceElement("SecretClass", "secretMethod", "Secret.kt", 42)) }

        val debugText = SensitiveDataRedactor.safeThrowable(throwable, debug = true)
        val releaseText = SensitiveDataRedactor.safeThrowable(throwable, debug = false)

        assertThat(debugText).doesNotContain("obviously-fake")
        assertThat(debugText).doesNotContain("SecretClass")
        assertThat(releaseText).doesNotContain("obviously-fake")
        assertThat(releaseText).doesNotContain("SecretClass")
        assertThat(releaseText).contains("IllegalStateException")
    }

    @Test
    fun `凭据清洗保留业务键和安全 URL 路径`() {
        val input = """
            prompt=绘制蓝色海面
            content=替换正文
            command=ls -la
            body=请求主体
            response=已接收
            url=https://user-fixture:pass-fixture@example.invalid/v1?raw=query-fixture#fragment-fixture
            url=ssh://ssh-user-fixture@host.invalid/project
            url=https://example.invalid/path#fragment@host.invalid
            callback=/callback?state=relative-fixture#fragment-fixture
            GET oauth/callback?code=oauth-fixture
            GET callback?flag#fragment-fixture
            GET callback#fragment-fixture
            error {"token":"fixture-json-value", "status":503}
            error {"token":{"value":"fixture-nested"}, "status":401}
            error {"client\u0053ecret":123456, "status":502}
            caught ForegroundServiceStartNotAllowedException
        """.trimIndent()
        val expected = """
            prompt=绘制蓝色海面
            content=替换正文
            command=ls -la
            body=请求主体
            response=已接收
            url=https://example.invalid/v1
            url=ssh://host.invalid/project
            url=https://example.invalid/path
            callback=/callback
            GET oauth/callback
            GET callback
            GET callback
            error {"token":"[omitted]", "status":503}
            error {"token":"[omitted]", "status":401}
            error {"client\u0053ecret":"[omitted]", "status":502}
            caught ForegroundServiceStartNotAllowedException
        """.trimIndent()
        assertThat(SensitiveDataRedactor.redactSecretsOnly(input)).isEqualTo(expected)
        val repeatedPaths = "(/dir".repeat(10_000)
        assertThat(SensitiveDataRedactor.redactSecretsOnly(repeatedPaths)).isEqualTo(repeatedPaths)
        val jwtHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(" \t{\"alg\":\"none\"}".toByteArray())
        val jwt = "$jwtHeader.eyAic3ViIjoiMSJ9."
        assertThat(SensitiveDataRedactor.redactSecretsOnly("token received $jwt")).isEqualTo("token received [omitted]")
        val escapedJson = """error {\"api\u005fkey\":\"fixture-escaped-secret\"}"""
        assertThat(SensitiveDataRedactor.redactSecretsOnly(escapedJson)).doesNotContain("fixture-escaped-secret")
        val coloredHeader = "\u001B[31mBearer \u001B[0mfixture-colored-value"
        assertThat(SensitiveDataRedactor.redactSecretsOnly(coloredHeader)).isEqualTo("[omitted]")
    }

    @Test
    fun `凭据清洗覆盖折行头部 编码路径和不完整私钥`() {
        val input = """
            Set-Cookie: fixture-a=folded-first, fixture-b=folded-second
                fixture-c=folded-third
            credential=fixture-credential
            signature="fixture-signature, remaining-signature"
            ssh://host.invalid/%2e%73sh/fixture-path
            C:\fixture-key-directory\local.p12
            /workspace/secure_env/fixture-env
            /user/fixture/.aws/credentials /user/fixture/.netrc
            /user/fixture/.docker/config.json /user/fixture/.kube/config
            cat "/workspace/private directory/quoted-fixture.key"
            .ssh: denied
            Bearer%20encoded-fixture
            password='multiline-start
            multiline-end'
            tool --api-key 'flag-fixture'
            session token = fixture-assignment
            -----BEGIN PGP PRIVATE KEY BLOCK-----
            pgp-fixture
            -----END PGP PRIVATE KEY BLOCK-----
            -----BEGIN OPENSSH PRIVATE KEY-----
            fixture-incomplete-pem
        """.trimIndent()
        val redacted = SensitiveDataRedactor.redactSecretsOnly(input)
        listOf("folded-first", "folded-second", "folded-third", "fixture-credential", "fixture-signature",
            "remaining-signature", "fixture-path", "fixture-key-directory", "fixture-env", "fixture-assignment",
            "quoted-fixture", ".ssh", "encoded-fixture", "multiline-start", "multiline-end", "flag-fixture",
            ".aws/credentials", ".netrc", ".docker/config.json", ".kube/config", "pgp-fixture", "fixture-incomplete-pem")
            .forEach { assertThat(redacted).doesNotContain(it) }
    }

    @Test
    fun `https validator accepts only absolute https URLs`() {
        assertThat(HttpsUrlValidator.isAllowed("https://example.invalid/v1")).isTrue()
        assertThat(HttpsUrlValidator.isAllowed("HTTPS://example.invalid/v1")).isTrue()
        assertThat(HttpsUrlValidator.isAllowed("http://example.invalid/v1")).isFalse()
        assertThat(HttpsUrlValidator.isAllowed("//example.invalid/v1")).isFalse()
        assertThat(HttpsUrlValidator.isAllowed("not-a-url")).isFalse()
    }
}
