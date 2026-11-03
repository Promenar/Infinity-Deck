package com.promenar.infinity.domain.generation

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 结构化失败与持久化 String 列之间的稳定编解码。
 *
 * 背景：当前 Room schema 中消息错误仍为单一 `errorMessage: String?` 列，本批次不迁移 schema。
 * 为满足“禁止把 Throwable.message 或协议原文写入历史 errorMessage”，同时保证上层可回映射，
 * 这里定义一个稳定、机器可读、**不含自然语言**的信封格式：
 *
 *   `@infinity/failure:CODE?k1=v1&k2=v2`
 *
 * - 仅写入 [GenerationFailure.code] 与白名单内的 [GenerationFailure.formatArgs]（限流秒数、tailnet 原因、上下文原因与计数、
 *   经 [ProviderErrorMessage.sanitize] 清洗并百分号编码的服务商原因）；
 * - 严禁写入 [GenerationFailure.technical] / [GenerationFailure.cause]（仅诊断，不入库展示列）；
 * - 下一批次的 UI mapper 识别该前缀后映射资源，未识别或未知串一律回落到通用安全文案。
 */
object GenerationFailureCodec {

    const val ENVELOPE_PREFIX = "@infinity/failure:"

    private const val MAX_ENVELOPE_LENGTH = 2048
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
    fun encode(failure: GenerationFailure): String {
        val builder = StringBuilder(ENVELOPE_PREFIX.length + 16)
        builder.append(ENVELOPE_PREFIX)
        builder.append(failure.code.name)
        val allowedArgs = allowedArgs(failure)
        if (allowedArgs.isNotEmpty()) {
            builder.append('?')
            builder.append(
                allowedArgs.toSortedMap().entries.joinToString(separator = "&") { (key, value) ->
                    "$key=$value"
                },
            )
        }
        return builder.toString()
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
                    key == GenerationFailure.KEY_PROVIDER_MESSAGE -> {
                        val text = runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrNull() ?: return null
                        val clean = ProviderErrorMessage.sanitize(text) ?: return null
                        if (clean != text) return null
                        args[key] = clean
                    }
                    code == GenerationFailureCode.CONTEXT_LIMIT && validContextArg(key, value) -> args[key] = value
                    else -> return null
                }
            }
            GenerationFailure(code = code, formatArgs = args)
        }
        return decoded.takeIf { encode(it) == raw }
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
        // 服务商原因让历史失败也能说明“为什么”；只存清洗后的文本，编码后入串。
        val provider = failure.providerMessage?.let(ProviderErrorMessage::sanitize)
            ?.let { URLEncoder.encode(it, Charsets.UTF_8.name()) }
        return if (provider == null) args else args + (GenerationFailure.KEY_PROVIDER_MESSAGE to provider)
    }

    private fun validContextArg(key: String, value: String): Boolean = when (key) {
        GenerationFailure.KEY_CONTEXT_LIMIT_REASON -> value in GenerationFailure.CONTEXT_LIMIT_REASONS
        GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS -> value == "true" || value == "false"
        GenerationFailure.KEY_REQUIRED_TOKENS, GenerationFailure.KEY_AVAILABLE_TOKENS ->
            value.toLongOrNull()?.let { it >= 0 && it.toString() == value } == true
        else -> false
    }
}
