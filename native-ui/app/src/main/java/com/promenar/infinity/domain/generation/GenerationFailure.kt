package com.promenar.infinity.domain.generation

/**
 * 生成失败的稳定内部码。
 *
 * - 不依赖 Android / UI，可被 Domain / Data / UI 任意层引用。
 * - 任何自然语言、底层异常文本、provider 返回原文都禁止穿透为该码之外的展示文案；
 *   上层（UI mapper）只应依据 [code] 与 [GenerationFailure.formatArgs] 映射资源。
 */
enum class GenerationFailureCode {
    NETWORK,
    AUTH,
    RATE_LIMIT,
    QUOTA,
    TIMEOUT,
    INVALID_REQUEST,
    CONTEXT_LIMIT,
    /** 回复达到模型单次输出上限被截断（如 finish_reason=length、max_tokens）。 */
    OUTPUT_LIMIT,
    /** 回复被提供商的内容过滤或安全策略中止。 */
    CONTENT_FILTERED,
    SERVER,
    BUSY,
    PERSISTENCE,
    /** 工具预算已用尽且模型在收尾轮仍未给出正文。 */
    TOOL_BUDGET_EXHAUSTED,
    /** 接口在 tailnet 内，内置 Tailscale 未能接入（需要登录、启动失败或等待超时）；请求未发出。 */
    TAILNET_UNAVAILABLE,
    RUN_MODE_REQUIRED,
    LINUX_UNAVAILABLE,
    /** 工具轮次后的空回复已经提醒重试，仍未获得正文。 */
    EMPTY_REPLY,
    UNKNOWN,
}

/**
 * 结构化生成失败契约。
 *
 * @param code 稳定内部码，是上层映射资源的唯一依据。
 * @param formatArgs 类型化参数（例如 [KEY_RETRY_AFTER_SECONDS]），供 UI 做占位符替换。
 * @param technical 仅供诊断（日志 / 调试）的技术信息；**禁止**作为展示 message 传播到 UI。
 * @param cause 原始异常，仅诊断用途，禁止直接展示 [Throwable.getMessage]。
 * @param retryable 协议层判断原样重发同一请求可能成功（如 5xx、流被截断）；只用于自动重试，不持久化。
 */
data class GenerationFailure(
    val code: GenerationFailureCode,
    val formatArgs: Map<String, String> = emptyMap(),
    val technical: String? = null,
    val cause: Throwable? = null,
    val retryable: Boolean = false,
) {
    /**
     * 限流类失败建议的重试等待秒数；不存在时为 null。
     * 由 [formatArgs] 中的 [KEY_RETRY_AFTER_SECONDS] 派生，避免在上层再解析任意字符串。
     */
    val retryAfterSeconds: Int?
        get() = formatArgs[KEY_RETRY_AFTER_SECONDS]?.toIntOrNull()

    /** 服务商响应里的错误说明，已经 [ProviderErrorMessage.sanitize] 清洗；不存在时为 null。 */
    val providerMessage: String?
        get() = formatArgs[KEY_PROVIDER_MESSAGE]

    override fun toString(): String = buildString {
        append("GenerationFailure(code=")
        append(code)
        retryAfterSeconds?.takeIf { code == GenerationFailureCode.RATE_LIMIT && it > 0 }?.let {
            append(", retryAfterSeconds=")
            append(it)
        }
        append(')')
    }

    companion object {
        const val KEY_RETRY_AFTER_SECONDS = "retryAfterSeconds"
        /** 服务商原因，见 [ProviderErrorMessage]；任何失败码都可携带，作为本地化提示下方的补充说明。 */
        const val KEY_PROVIDER_MESSAGE = "providerMessage"
        const val KEY_REQUIRED_TOKENS = "requiredTokens"
        const val KEY_AVAILABLE_TOKENS = "availableTokens"
        /** 上下文超限原因，供附件预警与失败提示选择入口。 */
        const val KEY_CONTEXT_LIMIT_REASON = "contextLimitReason"
        val CONTEXT_LIMIT_REASONS = setOf("CURRENT_TURN", "TOTAL_INPUT", "UNKNOWN_WINDOW", "UPSTREAM")
        /** 上下文超限时本轮是否携带完整上下文文档："false" 表示仅对话本身超限，提示应引导压缩而非移除文档。 */
        const val KEY_FULL_CONTEXT_DOCUMENTS = "fullContextDocuments"
        /** [GenerationFailureCode.TAILNET_UNAVAILABLE] 的原因，取值为 TailnetUnavailableReason 的名称。 */
        const val KEY_TAILNET_REASON = "tailnetReason"
        /** [KEY_TAILNET_REASON] 允许的取值；持久化信封只接受这些名称。 */
        val TAILNET_REASONS = setOf("DISABLED", "NEEDS_LOGIN", "FAILED", "TIMEOUT")

        fun of(
            code: GenerationFailureCode,
            formatArgs: Map<String, String> = emptyMap(),
            technical: String? = null,
            cause: Throwable? = null,
        ): GenerationFailure = GenerationFailure(code, formatArgs, technical, cause)

        /**
         * 限流 / 429 类失败。重试元数据通过类型化参数携带，不混入自然语言。
         */
        fun rateLimited(
            retryAfterSeconds: Int?,
            technical: String? = null,
            cause: Throwable? = null,
        ): GenerationFailure = GenerationFailure(
            code = GenerationFailureCode.RATE_LIMIT,
            formatArgs = buildRetryAfterArgs(retryAfterSeconds),
            technical = technical,
            cause = cause,
        )

        fun persistence(technical: String?, cause: Throwable? = null): GenerationFailure =
            GenerationFailure(GenerationFailureCode.PERSISTENCE, emptyMap(), technical, cause)

        fun busy(technical: String? = null): GenerationFailure =
            GenerationFailure(GenerationFailureCode.BUSY, emptyMap(), technical, null)

        fun unknown(technical: String?, cause: Throwable? = null): GenerationFailure =
            GenerationFailure(GenerationFailureCode.UNKNOWN, emptyMap(), technical, cause)

        internal fun buildRetryAfterArgs(retryAfterSeconds: Int?): Map<String, String> =
            if (retryAfterSeconds != null && retryAfterSeconds > 0) {
                mapOf(KEY_RETRY_AFTER_SECONDS to retryAfterSeconds.toString())
            } else {
                emptyMap()
            }
    }
}
