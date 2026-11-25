package com.promenar.infinity.domain.generation

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 结构化失败与持久化 String 列之间的稳定编解码。
 *
 * 背景：当前 Room schema 中消息错误仍为单一 `errorMessage: String?` 列，本批次不迁移 schema。
 * 信封格式为 `@infinity/failure:CODE?k1=v1&k2=v2`。仅允许稳定失败码及白名单参数，
 * 服务商说明、错误码和脱敏诊断经百分号编码后写入。
 * [GenerationFailure.technical] 与 [GenerationFailure.cause] 不进入持久化信封。
 */
object GenerationFailureCodec {

    const val ENVELOPE_PREFIX = "@infinity/failure:"

    private const val MAX_ENVELOPE_LENGTH = 4096
    private const val LEGACY_ENVELOPE_LENGTH = 2048
    private val httpStatusPattern = Regex("[1-5][0-9]{2}")
    private val textSanitizers: Map<String, (String) -> String?> = mapOf(
        GenerationFailure.KEY_PROVIDER_MESSAGE to ProviderErrorMessage::sanitize,
        GenerationFailure.KEY_ERROR_CODE to ProviderErrorMessage::sanitizeErrorCode,
        GenerationFailure.KEY_RAW_DIAGNOSTIC to ProviderErrorMessage::sanitizeRawDiagnostic,
    )
    private val codeByName = GenerationFailureCode.entries.associateBy { it.name }

    /**
     * 是否为结构化失败信封。UI mapper 用它在“信封 → 资源”与“未知串 → 通用安全文案”之间分流。
     */
    fun isFailureEnvelope(raw: String?): Boolean {
        return decode(raw) != null
    }

    /**
     * 编码为持久化字符串。technical / cause 永不入此串。
     */
    fun encode(failure: GenerationFailure): String = encode(failure.code, allowedArgs(failure))

    private fun encode(code: GenerationFailureCode, args: Map<String, String>): String = buildString {
        append(ENVELOPE_PREFIX)
        append(code.name)
        if (args.isNotEmpty()) {
            append('?')
            append(args.toQuery())
        }
    }

    /**
     * 反解为结构化失败；无法识别时返回 null（调用方应回落到 [GenerationFailure.unknown]）。
     * 反解结果只含 code + formatArgs，绝不会凭空产生 technical / cause。
     */
    fun decode(raw: String?): GenerationFailure? {
        if (raw.isNullOrEmpty() || raw.length > MAX_ENVELOPE_LENGTH) return null
        if (!raw.startsWith(ENVELOPE_PREFIX)) return null
        val withoutPrefix = raw.substring(ENVELOPE_PREFIX.length)
        val questionIdx = withoutPrefix.indexOf('?')
        val codeName = if (questionIdx < 0) withoutPrefix else withoutPrefix.substring(0, questionIdx)
        val code = codeByName[codeName] ?: return null
        var legacyProviderMessage: String? = null
        val decoded = if (questionIdx < 0) {
            GenerationFailure(code = code)
        } else {
            val query = withoutPrefix.substring(questionIdx + 1)
            if (query.isEmpty()) return null
            val args = linkedMapOf<String, String>()
            for (pair in query.split('&')) {
                if (pair.count { it == '=' } != 1) return null
                val (key, value) = pair.split('=', limit = 2)
                if (key in args) return null
                when {
                    key == GenerationFailure.KEY_TAILNET_REASON && code == GenerationFailureCode.TAILNET_UNAVAILABLE -> {
                        if (value !in GenerationFailure.TAILNET_REASONS) return null
                        args[key] = value
                    }
                    key == GenerationFailure.KEY_RETRY_AFTER_SECONDS && code == GenerationFailureCode.RATE_LIMIT -> {
                        if (value.isEmpty() || value.any { !it.isDigit() }) return null
                        val seconds = value.toIntOrNull()?.takeIf { it > 0 } ?: return null
                        if (seconds.toString() != value) return null
                        args[key] = value
                    }
                    key == GenerationFailure.KEY_HTTP_STATUS && httpStatusPattern.matches(value) -> args[key] = value
                    key in textSanitizers -> {
                        val text = runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull() ?: return null
                        val clean = textSanitizers.getValue(key)(text) ?: return null
                        if (clean != text) {
                            if (key != GenerationFailure.KEY_PROVIDER_MESSAGE ||
                                !ProviderErrorMessage.isLegacyProviderMessage(text)
                            ) return null
                            legacyProviderMessage = text
                        }
                        args[key] = clean
                    }
                    code == GenerationFailureCode.CONTEXT_LIMIT && validContextArg(key, value) -> args[key] = value
                    else -> return null
                }
            }
            GenerationFailure(code = code, formatArgs = args)
        }
        val legacyMessage = legacyProviderMessage ?: return decoded.takeIf { encode(it) == raw }
        if (raw.length > LEGACY_ENVELOPE_LENGTH ||
            decoded.httpStatus != null || decoded.errorCode != null || decoded.rawDiagnostic != null
        ) return null

        val legacyArgs = allowedArgs(decoded) + (GenerationFailure.KEY_PROVIDER_MESSAGE to encodeText(legacyMessage))
        return decoded.takeIf { encode(it.code, legacyArgs) == raw }
    }

    private fun allowedArgs(failure: GenerationFailure): Map<String, String> {
        val args = when (failure.code) {
            GenerationFailureCode.CONTEXT_LIMIT -> failure.formatArgs.filter { (key, value) -> validContextArg(key, value) }
            GenerationFailureCode.RATE_LIMIT -> failure.retryAfterSeconds?.takeIf { it > 0 }
                ?.let { mapOf(GenerationFailure.KEY_RETRY_AFTER_SECONDS to it.toString()) }
                .orEmpty()
            // tailnet 原因决定提示与入口（去登录或重试），历史消息也要能还原。
            GenerationFailureCode.TAILNET_UNAVAILABLE -> failure.formatArgs[GenerationFailure.KEY_TAILNET_REASON]
                ?.takeIf { it in GenerationFailure.TAILNET_REASONS }
                ?.let { mapOf(GenerationFailure.KEY_TAILNET_REASON to it) }
                .orEmpty()
            else -> emptyMap()
        }
        return args.toMutableMap().apply {
            failure.formatArgs[GenerationFailure.KEY_HTTP_STATUS]?.takeIf(httpStatusPattern::matches)?.let {
                put(GenerationFailure.KEY_HTTP_STATUS, it)
            }
            textSanitizers.forEach { (key, sanitize) ->
                val text = failure.formatArgs[key]?.let(sanitize) ?: return@forEach
                if (key == GenerationFailure.KEY_RAW_DIAGNOSTIC) {
                    val fixedLength = ENVELOPE_PREFIX.length + failure.code.name.length + toQuery().length
                    val overhead = 1 + (if (isEmpty()) 0 else 1) + key.length + 1
                    encodeDiagnostic(text, MAX_ENVELOPE_LENGTH - fixedLength - overhead)?.let { put(key, it) }
                } else {
                    put(key, encodeText(text))
                }
            }
        }
    }

    private fun Map<String, String>.toQuery(): String =
        toSortedMap().entries.joinToString("&") { (key, value) -> "$key=$value" }

    private fun encodeText(text: String): String = URLEncoder.encode(text, Charsets.UTF_8.name())

    private fun encodeDiagnostic(text: String, maxLength: Int): String? {
        val encoded = encodeText(text)
        if (encoded.length <= maxLength) return encoded

        var low = 1
        var high = text.length - 1
        while (low <= high) {
            val middle = (low + high) / 2
            if (encodeText(ProviderErrorMessage.truncate(text, middle)).length <= maxLength) {
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        while (high > 0) {
            val excerpt = ProviderErrorMessage.diagnosticExcerpt(text, high)
            val clean = encodeText(excerpt)
            if (clean.length <= maxLength) return clean
            high -= 1
        }
        return null
    }

    private fun validContextArg(key: String, value: String): Boolean = when (key) {
        GenerationFailure.KEY_CONTEXT_LIMIT_REASON -> value in GenerationFailure.CONTEXT_LIMIT_REASONS
        GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS -> value == "true" || value == "false"
        GenerationFailure.KEY_REQUIRED_TOKENS, GenerationFailure.KEY_AVAILABLE_TOKENS ->
            value.toLongOrNull()?.let { it >= 0 && it.toString() == value } == true
        else -> false
    }
}
