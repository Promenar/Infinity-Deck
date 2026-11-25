package com.promenar.infinity.domain.generation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class ProviderErrorDiagnostic(
    val providerMessage: String? = null,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val rawDiagnostic: String? = null,
) {
    internal fun toFormatArgs(): Map<String, String> = buildMap {
        providerMessage?.let { put(GenerationFailure.KEY_PROVIDER_MESSAGE, it) }
        httpStatus?.takeIf { it in 100..599 }?.let { put(GenerationFailure.KEY_HTTP_STATUS, it.toString()) }
        errorCode?.let { put(GenerationFailure.KEY_ERROR_CODE, it) }
        rawDiagnostic?.let { put(GenerationFailure.KEY_RAW_DIAGNOSTIC, it) }
    }

    override fun toString(): String = "ProviderErrorDiagnostic(httpStatus=$httpStatus)"
}

/** 从上游错误响应提取可展示的原因，并生成可持久化的脱敏诊断。 */
object ProviderErrorMessage {
    const val MAX_LENGTH = 200
    const val MAX_RAW_DIAGNOSTIC_LENGTH = 800
    const val MAX_ERROR_CODE_LENGTH = 64

    private const val MAX_PLAIN_BODY = 400
    private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
    private val compactIdentifier = Regex("""[A-Za-z0-9][A-Za-z0-9_.:-]*""")
    private val whitespace = Regex("""\s+""")
    private val legacyRedactions = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{6,}""") to "sk-***",
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=\-]+""") to "Bearer ***",
        Regex("""(?i)\b(api[_-]?key|access[_-]?token|token|key)\s*[=:]\s*[^\s&,;"']+""") to "$1=***",
        Regex("""[A-Za-z0-9_\-]{40,}""") to "***",
    )
    private val keyLike = listOf(
        Regex("""(?i)\bsk-[A-Za-z0-9_-]{6,}""") to "sk-***",
        Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]+""") to "$1 ***",
        Regex("""\b(?:gh[pousr]_[A-Za-z0-9_]{12,}|AKIA[A-Z0-9]{16}|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+)\b""") to "***",
        Regex("""[A-Za-z0-9_-]{40,}""") to "***",
    )
    private val cookieHeader = Regex("""(?i)\b((?:set[-_ ]?)?cookie[ \t]*[:=])[^\r\n]*(?:\r?\n[ \t]+[^\r\n]*)*""")
    private val privateKeyBlock = Regex("""(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)""")
    private val credentialAssignment = Regex(
        """(?i)(?<![\w-])("?(?:x[-_])?(?:api[-_ ]?key|(?:access|refresh|id|auth)[-_ ]?token|token|(?:proxy[-_])?authorization|client[-_ ]?secret|secret|(?:private[-_ ]?)?key|password|passwd|credential|(?:set[-_])?cookie)"?[ \t]*)([:=])\s*("(?:\\.|[^"\\\r\n])*(?:"|$)|'[^'\r\n]*(?:'|$)|[\[{][\s\S]*|(?:(?:bearer|basic)[ \t]+)?[^\s&,;"'}\]]+)""",
    )
    private val sensitiveJsonKeys = setOf(
        "key", "token", "accesstoken", "refreshtoken", "idtoken", "authtoken", "credential", "credentials",
        "authorization", "proxyauthorization", "auth", "privatekey", "passwd", "cookie", "setcookie",
        "secretkey", "accesskey", "accesskeyid", "xamzsignature", "signature",
    )
    private val messageKeys = listOf("message", "msg", "detail", "error_msg", "errorMessage", "status_msg")
    private val quotaIdentifiers = setOf(
        "billing_error", "billing_hard_limit_reached", "insufficient_quota", "quota_exceeded",
        "insufficient_balance", "balance_not_enough", "insufficient_funds",
        "account_balance_insufficient", "1008", "credit_balance_too_low",
    )
    private val quotaPhrases = listOf(
        "insufficient balance", "credit balance is too low", "余额不足",
        "insufficient_balance", "balance_not_enough", "insufficient_funds",
        "account_balance_insufficient", "credit_balance_too_low",
    )

    fun extract(body: String?): String? {
        val text = body.usableBody() ?: return null
        return extractMessage(text, parseContainer(text))
    }

    /** 持久化与展示前清洗服务商说明。 */
    fun sanitize(raw: String): String? = raw
        .withoutControls()
        .let(::redactText)
        .replace(whitespace, " ")
        .trim()
        .takeIf(String::isNotEmpty)
        ?.let { truncate(it, MAX_LENGTH) }

    internal fun isLegacyProviderMessage(text: String): Boolean {
        val legacy = legacyRedactions.fold(text.withoutControls()) { value, (pattern, replacement) ->
            pattern.replace(value, replacement)
        }.replace(whitespace, " ").trim()
        return legacy.isNotEmpty() && truncate(legacy, MAX_LENGTH) == text
    }

    fun sanitizeErrorCode(raw: String?): String? = raw
        ?.filterNot(Char::isISOControl)
        ?.trim()
        ?.let(::redactText)
        ?.takeIf { it.length in 1..MAX_ERROR_CODE_LENGTH && compactIdentifier.matches(it) }

    fun sanitizeRawDiagnostic(raw: String?): String? {
        val text = raw.usableBody() ?: return null
        return sanitizeDiagnostic(text, parseContainer(text))
    }

    fun extractDiagnostic(
        statusCode: Int?,
        body: String?,
        type: String? = null,
        code: String? = null,
    ): ProviderErrorDiagnostic {
        val text = body.usableBody()
        val payload = text?.let(::parseContainer)
        val root = payload.asErrorObject()
        val error = root?.nestedError() as? JsonObject
        val miniMax = root?.miniMaxStatusCode()
        return ProviderErrorDiagnostic(
            providerMessage = text?.let { extractMessage(it, payload) },
            httpStatus = statusCode?.takeIf { it in 100..599 },
            errorCode = sequenceOf(code, error?.field("code"), miniMax, type, error?.field("type"), root?.field("code"))
                .firstNotNullOfOrNull(::sanitizeErrorCode),
            rawDiagnostic = text?.let { sanitizeDiagnostic(it, payload) },
        )
    }

    internal fun isQuotaExhaustion(
        diagnostic: ProviderErrorDiagnostic,
        responseBody: String?,
        vararg identifiers: String?,
    ): Boolean {
        if (diagnostic.httpStatus == 402 ||
            sequenceOf(diagnostic.errorCode, *identifiers).any(::isQuotaIdentifier) ||
            diagnostic.providerMessage?.lowercase()?.let { text ->
                isQuotaIdentifier(text) || quotaPhrases.any { it in text }
            } == true
        ) return true

        val root = responseBody.usableBody()?.let(::parseContainer).asErrorObject() ?: return false
        val error = root.nestedError() as? JsonObject
        return root.miniMaxStatusCode() == "1008" ||
            sequenceOf(error?.field("code"), error?.field("type"), root.field("code")).any(::isQuotaIdentifier)
    }

    private fun isQuotaIdentifier(value: String?): Boolean = value?.trim()?.lowercase() in quotaIdentifiers

    internal fun truncate(text: String, maxLength: Int): String {
        if (text.length <= maxLength) return text
        if (maxLength <= 1) return "…".take(maxLength)
        val end = maxLength - 1
        val safeEnd = if (text[end - 1].isHighSurrogate()) end - 1 else end
        return text.take(safeEnd) + "…"
    }

    private fun extractMessage(text: String, payload: JsonElement?): String? =
        (if (isContainer(text)) payload?.let(::fromJson) else text.takeIf { it.length <= MAX_PLAIN_BODY })
            ?.let(::sanitize)

    private fun sanitizeDiagnostic(text: String, payload: JsonElement?): String? {
        val clean = if (payload != null) {
            prettyJson.encodeToString(JsonElement.serializer(), redactJson(payload))
        } else {
            sanitizeDiagnosticText(text)
        }
        if (clean.isEmpty() || clean.startsWith('<')) return null
        return diagnosticExcerpt(clean, MAX_RAW_DIAGNOSTIC_LENGTH)
    }

    internal fun diagnosticExcerpt(text: String, maxLength: Int): String {
        val excerpt = truncate(text, maxLength)
        if (excerpt == text) return text
        val cleaned = sanitizeDiagnosticText(excerpt)
        if (cleaned.length <= maxLength) return cleaned
        // 截断点落在引号或转义符中时，退回到前一行，避免生成不稳定的脱敏片段。
        return cleaned.substringBeforeLast('\n', "").trimEnd() + "…"
    }

    private fun sanitizeDiagnosticText(text: String): String {
        val truncated = text.endsWith('…')
        val prefix = if (truncated) text.dropLast(1) else text
        val clean = redactText(prefix.withoutControls(keepNewlines = true)).trim()
        return if (truncated) "$clean…" else clean
    }

    private fun redactText(raw: String): String {
        val withoutHeaders = cookieHeader.replace(privateKeyBlock.replace(raw, "***")) { match ->
            "${match.groupValues[1]} ***"
        }
        val assignmentsRedacted = credentialAssignment.replace(withoutHeaders) { match ->
            val (label, delimiter, value) = match.destructured
            when {
                label.trim().lowercase() in setOf("cookie", "set-cookie", "set_cookie", "set cookie") -> "$label$delimiter ***"
                value.startsWith('"') -> "$label$delimiter \"***\""
                value.startsWith('\'') -> "$label$delimiter'***'"
                value.startsWith("Bearer ", ignoreCase = true) || value.startsWith("Basic ", ignoreCase = true) ->
                    "$label$delimiter ${value.substringBefore(' ')} ***"
                else -> "$label$delimiter***"
            }
        }
        return keyLike.fold(assignmentsRedacted) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
    }

    private fun redactJson(element: JsonElement, depth: Int = 0): JsonElement {
        if (depth == 64) return JsonPrimitive("***")
        return when (element) {
            is JsonObject -> JsonObject(element.entries.associate { (key, value) ->
                val normalizedKey = key.filter(Char::isLetterOrDigit).lowercase()
                val sensitive = normalizedKey in sensitiveJsonKeys ||
                    listOf("apikey", "password", "secret").any(normalizedKey::endsWith)
                redactText(key.withoutControls()) to if (sensitive) JsonPrimitive("***") else redactJson(value, depth + 1)
            })
            is JsonArray -> JsonArray(element.map { redactJson(it, depth + 1) })
            is JsonPrimitive -> {
                val text = redactText(element.content.withoutControls())
                if (element.isString || text != element.content) JsonPrimitive(text) else element
            }
        }
    }

    private fun fromJson(element: JsonElement): String? = when (element) {
        is JsonPrimitive -> element.contentOrNull
        is JsonArray -> element.firstOrNull()?.let(::fromJson)
        is JsonObject -> {
            val error = element.nestedError()
            val baseResponse = element["base_resp"] as? JsonObject
            (error as? JsonObject)?.let(::messageFromObject)
                ?: (error as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
                ?: baseResponse?.let(::messageFromObject)
                ?: messageFromObject(element)
                ?: element.miniMaxStatusCode()?.let { "MiniMax status_code: $it" }
        }
    }

    private fun messageFromObject(obj: JsonObject): String? =
        messageKeys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank)
        } ?: (obj["errors"] as? JsonArray)?.firstOrNull()?.let(::fromJson)

    private fun JsonObject.nestedError(): JsonElement? =
        this["error"]?.takeUnless { it is JsonNull } ?: (this["response"] as? JsonObject)?.get("error")

    private fun JsonObject.field(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.miniMaxStatusCode(): String? = (this["base_resp"] as? JsonObject)
        ?.field("status_code")?.toIntOrNull()?.takeIf { it != 0 }?.toString()

    private fun String?.usableBody(): String? =
        this?.trim { it.isWhitespace() || it.isISOControl() || it == '\uFEFF' }
            ?.takeIf { it.isNotEmpty() && !it.startsWith('<') }

    private fun isContainer(text: String): Boolean = text.startsWith('{') || text.startsWith('[')

    private fun parseContainer(text: String): JsonElement? =
        if (isContainer(text)) runCatching { Json.parseToJsonElement(text) }.getOrNull() else null

    private fun JsonElement?.asErrorObject(): JsonObject? =
        this as? JsonObject ?: (this as? JsonArray)?.firstOrNull() as? JsonObject

    private fun String.withoutControls(keepNewlines: Boolean = false): String = buildString(length) {
        this@withoutControls.forEach { char ->
            append(if (char.isISOControl() && !(keepNewlines && char == '\n')) ' ' else char)
        }
    }
}
