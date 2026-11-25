package com.promenar.infinity.domain.generation

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderErrorMessageTest {
    @Test
    fun `取常见网关错误结构中的说明字段`() {
        assertThat(ProviderErrorMessage.extract("""{"error":{"message":"当前分组上游负载已饱和，请稍后再试","type":"new_api_error"}}"""))
            .isEqualTo("当前分组上游负载已饱和，请稍后再试")
        assertThat(ProviderErrorMessage.extract("""{"error":"Rate limit reached"}""")).isEqualTo("Rate limit reached")
        assertThat(ProviderErrorMessage.extract("""{"message":"model not found","code":404}""")).isEqualTo("model not found")
        assertThat(ProviderErrorMessage.extract("""[{"error":{"msg":"busy"}}]""")).isEqualTo("busy")
        assertThat(ProviderErrorMessage.extract("upstream timeout")).isEqualTo("upstream timeout")
    }

    @Test
    fun `HTML 错误页、空响应与过长纯文本不采用`() {
        assertThat(ProviderErrorMessage.extract("<html><body>502 Bad Gateway</body></html>")).isNull()
        assertThat(ProviderErrorMessage.extract("   ")).isNull()
        assertThat(ProviderErrorMessage.extract(null)).isNull()
        assertThat(ProviderErrorMessage.extract("x ".repeat(300))).isNull()
        assertThat(ProviderErrorMessage.extract("""{"error":{"code":500}}""")).isNull()
    }

    @Test
    fun `清洗控制字符、遮蔽疑似密钥并截断`() {
        val cleaned = ProviderErrorMessage.extract(
            """{"error":{"message":"bad key sk-abcdefghijklmnop\u0007 with Authorization: Bearer abc.def-ghi and key=secret123\n(request id: 2026102811014012)"}}""",
        )
        assertThat(cleaned).isEqualTo("bad key sk-*** with Authorization: Bearer *** and key=*** (request id: 2026102811014012)")
        val long = ProviderErrorMessage.sanitize("限".repeat(500))!!
        assertThat(long.length).isEqualTo(ProviderErrorMessage.MAX_LENGTH)
        assertThat(long).endsWith("…")
        assertThat(ProviderErrorMessage.sanitize(long)).isEqualTo(long)
    }

    @Test
    fun `服务商原因经信封往返保持不变，且与限流秒数共存`() {
        val failure = GenerationFailure(
            GenerationFailureCode.RATE_LIMIT,
            mapOf(
                GenerationFailure.KEY_RETRY_AFTER_SECONDS to "20",
                GenerationFailure.KEY_PROVIDER_MESSAGE to "Too many requests & retry=later? 请稍后",
            ),
        )
        val encoded = GenerationFailureCodec.encode(failure)
        val decoded = GenerationFailureCodec.decode(encoded)!!
        assertThat(decoded.code).isEqualTo(GenerationFailureCode.RATE_LIMIT)
        assertThat(decoded.retryAfterSeconds).isEqualTo(20)
        assertThat(decoded.providerMessage).isEqualTo("Too many requests & retry=later? 请稍后")
        assertThat(decoded.technical).isNull()
    }

    @Test
    fun `信封拒绝未清洗的服务商原因与重复参数`() {
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?providerMessage=a%07b")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?providerMessage=sk-abcdefghijk")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?providerMessage=a&providerMessage=a")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?retryAfterSeconds=5")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:SERVER?providerMessage=busy")?.providerMessage).isEqualTo("busy")
    }

    @Test
    fun `诊断按优先顺序提取错误码并递归脱敏`() {
        val body = """{"base_resp":{"status_code":1008,"status_msg":"insufficient balance"},"error":{"code":"upstream_error","type":"invalid_request_error"},"nested":[{"api_key":"sample-key","authorization":"Basic fake","token":{"value":"hidden"},"message":"Bearer abc.def and sk-abcdefghijkl"}]}"""
        val diagnostic = ProviderErrorMessage.extractDiagnostic(429, body, "gateway_error")
        assertThat(diagnostic.providerMessage).isEqualTo("insufficient balance")
        assertThat(diagnostic.httpStatus).isEqualTo(429)
        assertThat(diagnostic.errorCode).isEqualTo("upstream_error")
        val raw = requireNotNull(diagnostic.rawDiagnostic)
        assertThat(raw).contains("\n  \"base_resp\": {")
        assertThat(raw).doesNotContain("sample-key")
        assertThat(raw).doesNotContain("Basic fake")
        assertThat(raw).doesNotContain("hidden")
        assertThat(raw).doesNotContain("abc.def")
        assertThat(raw).doesNotContain("sk-abcdefghijkl")
        assertThat(ProviderErrorMessage.sanitizeRawDiagnostic(raw)).isEqualTo(raw)
        assertThat(ProviderErrorMessage.extractDiagnostic(999, body, code = "credit_balance_too_low").errorCode)
            .isEqualTo("credit_balance_too_low")
        assertThat(ProviderErrorMessage.extractDiagnostic(999, body).httpStatus).isNull()
        assertThat(ProviderErrorMessage.extract("""{"base_resp":{"status_code":1008}}""")).contains("1008")
        assertThat(ProviderErrorMessage.extract("""{"base_resp":{"status_code":0}}""")).isNull()
        assertThat(ProviderErrorMessage.extract("""{"status_msg":"upstream busy"}""")).isEqualTo("upstream busy")
        assertThat(ProviderErrorMessage.sanitizeErrorCode("insufficient_\u0000quota")).isEqualTo("insufficient_quota")
        assertThat(ProviderErrorMessage.sanitizeErrorCode("sk-abcdefghijkl")).isNull()
        assertThat(ProviderErrorMessage.sanitizeRawDiagnostic(" <html>secret</html>")).isNull()
        assertThat(ProviderErrorMessage.sanitizeRawDiagnostic("\uFEFF <html>secret</html>")).isNull()
    }

    @Test
    fun `诊断与现有参数共同入库并适配百分号编码长度`() {
        val failure = GenerationFailure(
            code = GenerationFailureCode.QUOTA,
            formatArgs = mapOf(
                GenerationFailure.KEY_HTTP_STATUS to "402",
                GenerationFailure.KEY_ERROR_CODE to "1008",
                GenerationFailure.KEY_PROVIDER_MESSAGE to "额度不足",
                GenerationFailure.KEY_RAW_DIAGNOSTIC to "额度⚡🚀".repeat(250),
            ),
            technical = "private technical",
            cause = IllegalStateException("private cause"),
        )
        val encoded = GenerationFailureCodec.encode(failure)
        assertThat(encoded.length).isAtMost(4096)
        assertThat(encoded).doesNotContain("private")
        val restored = requireNotNull(GenerationFailureCodec.decode(encoded))
        assertThat(restored.httpStatus).isEqualTo(402)
        assertThat(restored.errorCode).isEqualTo("1008")
        assertThat(restored.providerMessage).isEqualTo("额度不足")
        assertThat(restored.rawDiagnostic).endsWith("…")
        assertThat(restored.rawDiagnostic).doesNotContain("�")
        assertThat(restored.toString()).doesNotContain("额度")
        assertThat(GenerationFailureCodec.decode("@infinity/failure:QUOTA?httpStatus=0402")).isNull()
        assertThat(GenerationFailureCodec.decode("@infinity/failure:QUOTA?rawDiagnostic=Bearer+example")).isNull()
    }

    @Test
    fun `旧信封在增强脱敏后仍可读取`() {
        val legacy = "@infinity/failure:AUTH?providerMessage=bad+password%3Dmock-value"
        assertThat(GenerationFailureCodec.decode(legacy)?.providerMessage).isEqualTo("bad password=***")
        assertThat(GenerationFailureCodec.decode(
            "@infinity/failure:AUTH?httpStatus=401&providerMessage=bad+password%3Dmock-value",
        )).isNull()
    }

    @Test
    fun `JSON 诊断在敏感字段附近截断时保持安全且可往返`() {
        for (length in 760..800) {
            val padding = "汉".repeat(length)
            val raw = requireNotNull(ProviderErrorMessage.sanitizeRawDiagnostic(
                """{"message":"$padding🚀","password":"mock-value","tail":"extra"}""",
            ))
            assertThat(raw.length).isAtMost(ProviderErrorMessage.MAX_RAW_DIAGNOSTIC_LENGTH)
            assertThat(raw).doesNotContain("mock-value")
            assertThat(raw).endsWith("…")
            assertThat(ProviderErrorMessage.sanitizeRawDiagnostic(raw)).isEqualTo(raw)
            val encoded = GenerationFailureCodec.encode(GenerationFailure(
                GenerationFailureCode.SERVER,
                mapOf(GenerationFailure.KEY_RAW_DIAGNOSTIC to raw),
            ))
            assertThat(requireNotNull(GenerationFailureCodec.decode(encoded)).rawDiagnostic).endsWith("…")
        }
    }

    @Test
    fun `多值 Cookie 从说明诊断及旧信封中一起遮蔽`() {
        val body = """{"error":{"message":"认证失败 Cookie: session=样例甲; sid=样例乙"}}"""
        val diagnostic = ProviderErrorMessage.extractDiagnostic(401, body)
        assertThat(diagnostic.providerMessage).isEqualTo("认证失败 Cookie: ***")
        assertThat(diagnostic.rawDiagnostic).doesNotContain("样例甲")
        assertThat(diagnostic.rawDiagnostic).doesNotContain("样例乙")
        assertThat(ProviderErrorMessage.sanitizeRawDiagnostic("Set-Cookie: sid=样例甲; refresh=样例乙\r\n\t续写=样例丙\n请求失败"))
            .isEqualTo("Set-Cookie: ***\n请求失败")
        val restored = GenerationFailureCodec.decode(
            "@infinity/failure:AUTH?providerMessage=Cookie%3A+session%3DexampleA%3B+sid%3DexampleB",
        )
        assertThat(restored?.providerMessage).isEqualTo("Cookie: ***")
        val received = "Cookie: sid=样例甲\n请求失败"
        val failure = GenerationFailure(
            GenerationFailureCode.AUTH,
            mapOf(
                GenerationFailure.KEY_HTTP_STATUS to "401",
                GenerationFailure.KEY_PROVIDER_MESSAGE to received,
            ),
        )
        val decoded = requireNotNull(GenerationFailureCodec.decode(GenerationFailureCodec.encode(failure)))
        assertThat(decoded.httpStatus).isEqualTo(401)
        assertThat(decoded.providerMessage).isEqualTo("Cookie: ***")
    }

    @Test
    fun `不完整 JSON 的可疑对象字段不会泄漏后续值`() {
        val incomplete = "{\"token\":\n  {\"value\":\"mock-value\"}"
        val diagnostic = requireNotNull(ProviderErrorMessage.sanitizeRawDiagnostic(incomplete))
        assertThat(diagnostic).contains("***")
        assertThat(diagnostic).doesNotContain("mock-value")
        assertThat(ProviderErrorMessage.sanitizeRawDiagnostic(diagnostic)).isEqualTo(diagnostic)
    }
}
