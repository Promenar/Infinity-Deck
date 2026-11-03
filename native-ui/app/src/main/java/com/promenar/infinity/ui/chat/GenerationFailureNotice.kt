package com.promenar.infinity.ui.chat

import com.promenar.infinity.R
import com.promenar.infinity.domain.generation.GenerationFailure
import com.promenar.infinity.domain.generation.GenerationFailureCode
import com.promenar.infinity.domain.generation.GenerationFailureCodec
import com.promenar.infinity.ui.common.status.NoticeSeverity
import com.promenar.infinity.ui.common.status.ResolvedStatus
import com.promenar.infinity.ui.common.status.UiStatusNotice

/**
 * 生成失败的纯 UI 映射边界。
 *
 * 用户可见层只消费稳定 code、资源 ID 与经过白名单筛选的格式参数；诊断信息、异常原文、
 * 持久化信封和未知旧字符串都不会成为展示模板或参数。服务商错误说明经清洗后只作为 [UiStatusNotice.detail]
 * 补充展示，不参与模板。
 */
object GenerationFailureNotice {
    private const val CODE_NETWORK = "generation.failure.network"
    private const val CODE_AUTH = "generation.failure.auth"
    private const val CODE_RATE_LIMIT = "generation.failure.rate_limit"
    private const val CODE_RATE_LIMIT_RETRY = "generation.failure.rate_limit_retry"
    private const val CODE_QUOTA = "generation.failure.quota"
    private const val CODE_TIMEOUT = "generation.failure.timeout"
    private const val CODE_INVALID_REQUEST = "generation.failure.invalid_request"
    private const val CODE_CONTEXT_LIMIT = "generation.failure.context_limit"
    private const val CODE_CONTEXT_LIMIT_CONVERSATION = "generation.failure.context_limit_conversation"
    private const val CODE_CONTEXT_LIMIT_TURN = "generation.failure.context_limit_turn"
    private const val CODE_CONTEXT_LIMIT_TURN_DOCUMENTS = "generation.failure.context_limit_turn_documents"
    private const val CODE_CONTEXT_LIMIT_UNKNOWN_WINDOW = "generation.failure.context_limit_unknown_window"
    private const val CODE_CONTEXT_LIMIT_UPSTREAM = "generation.failure.context_limit_upstream"
    private const val CODE_OUTPUT_LIMIT = "generation.failure.output_limit"
    private const val CODE_CONTENT_FILTERED = "generation.failure.content_filtered"
    private const val CODE_SERVER = "generation.failure.server"
    private const val CODE_BUSY = "generation.failure.busy"
    private const val CODE_PERSISTENCE = "generation.failure.persistence"
    private const val CODE_TOOL_BUDGET = "generation.failure.tool_budget"
    private const val CODE_TAILNET_DISABLED = "generation.failure.tailnet_disabled"
    private const val CODE_TAILNET_NEEDS_LOGIN = "generation.failure.tailnet_needs_login"
    private const val CODE_TAILNET_FAILED = "generation.failure.tailnet_failed"
    private const val CODE_TAILNET_TIMEOUT = "generation.failure.tailnet_timeout"
    private const val CODE_RUN_MODE_REQUIRED = "generation.failure.run_mode_required"
    private const val CODE_LINUX_UNAVAILABLE = "generation.failure.linux_unavailable"
    private const val CODE_EMPTY_REPLY = "generation.failure.empty_reply"
    private const val CODE_UNKNOWN = "generation.failure.unknown"

    fun from(failure: GenerationFailure): UiStatusNotice {
        val retryAfterSeconds = failure.retryAfterSeconds?.takeIf { it > 0 }
        val code = when (failure.code) {
            GenerationFailureCode.NETWORK -> CODE_NETWORK
            GenerationFailureCode.AUTH -> CODE_AUTH
            GenerationFailureCode.RATE_LIMIT ->
                if (retryAfterSeconds != null) CODE_RATE_LIMIT_RETRY else CODE_RATE_LIMIT
            GenerationFailureCode.QUOTA -> CODE_QUOTA
            GenerationFailureCode.TIMEOUT -> CODE_TIMEOUT
            GenerationFailureCode.INVALID_REQUEST -> CODE_INVALID_REQUEST
            GenerationFailureCode.CONTEXT_LIMIT -> {
                val documents = failure.formatArgs[GenerationFailure.KEY_FULL_CONTEXT_DOCUMENTS] != "false"
                when (failure.formatArgs[GenerationFailure.KEY_CONTEXT_LIMIT_REASON]) {
                    // 当前轮本身放不下：压缩历史无济于事，出路是转存附件或换模型。
                    "CURRENT_TURN" -> if (documents) CODE_CONTEXT_LIMIT_TURN_DOCUMENTS else CODE_CONTEXT_LIMIT_TURN
                    "UNKNOWN_WINDOW" -> CODE_CONTEXT_LIMIT_UNKNOWN_WINDOW
                    "UPSTREAM" -> CODE_CONTEXT_LIMIT_UPSTREAM
                    else -> if (documents) CODE_CONTEXT_LIMIT else CODE_CONTEXT_LIMIT_CONVERSATION
                }
            }
            GenerationFailureCode.OUTPUT_LIMIT -> CODE_OUTPUT_LIMIT
            GenerationFailureCode.CONTENT_FILTERED -> CODE_CONTENT_FILTERED
            GenerationFailureCode.SERVER -> CODE_SERVER
            GenerationFailureCode.BUSY -> CODE_BUSY
            GenerationFailureCode.PERSISTENCE -> CODE_PERSISTENCE
            GenerationFailureCode.TOOL_BUDGET_EXHAUSTED -> CODE_TOOL_BUDGET
            GenerationFailureCode.RUN_MODE_REQUIRED -> CODE_RUN_MODE_REQUIRED
            GenerationFailureCode.LINUX_UNAVAILABLE -> CODE_LINUX_UNAVAILABLE
            GenerationFailureCode.TAILNET_UNAVAILABLE -> when (failure.formatArgs[GenerationFailure.KEY_TAILNET_REASON]) {
                "DISABLED" -> CODE_TAILNET_DISABLED
                "NEEDS_LOGIN" -> CODE_TAILNET_NEEDS_LOGIN
                "TIMEOUT" -> CODE_TAILNET_TIMEOUT
                else -> CODE_TAILNET_FAILED
            }
            GenerationFailureCode.EMPTY_REPLY -> CODE_EMPTY_REPLY
            GenerationFailureCode.UNKNOWN -> CODE_UNKNOWN
        }
        return UiStatusNotice(
            severity = NoticeSeverity.Error,
            code = code,
            formatArgs = when (failure.code) {
                GenerationFailureCode.RATE_LIMIT -> retryAfterSeconds?.let(::listOf).orEmpty()
                GenerationFailureCode.CONTEXT_LIMIT -> if (code == CODE_CONTEXT_LIMIT_UNKNOWN_WINDOW || code == CODE_CONTEXT_LIMIT_UPSTREAM) {
                    emptyList()
                } else {
                    listOfNotNull(
                        failure.formatArgs[GenerationFailure.KEY_REQUIRED_TOKENS]?.toLongOrNull(),
                        failure.formatArgs[GenerationFailure.KEY_AVAILABLE_TOKENS]?.toLongOrNull(),
                    ).takeIf { it.size == 2 }.orEmpty()
                }
                else -> emptyList()
            },
            technical = failure.technical,
            detail = failure.providerMessage,
        )
    }

    /** tailnet 失败在消息下方给出的入口；其它失败为 null。 */
    fun tailnetKind(notice: UiStatusNotice): TailnetFailureKind? = when (notice.code) {
        CODE_TAILNET_DISABLED -> TailnetFailureKind.DISABLED
        CODE_TAILNET_NEEDS_LOGIN -> TailnetFailureKind.NEEDS_LOGIN
        CODE_TAILNET_FAILED, CODE_TAILNET_TIMEOUT -> TailnetFailureKind.UNREACHABLE
        else -> null
    }

    /** Room 旧列只接受有效结构化信封；其它值一律回落通用失败。 */
    fun fromPersisted(raw: String?): UiStatusNotice {
        val decoded = runCatching { GenerationFailureCodec.decode(raw) }.getOrNull()
        return from(decoded ?: GenerationFailure.unknown(technical = null))
    }

    /** 未知 notice code 也由通用资源兜底，绝不展示内部 code 或其参数。 */
    fun template(notice: UiStatusNotice): ResolvedStatus = when (notice.code) {
        CODE_EMPTY_REPLY -> ResolvedStatus(R.string.exec_steer_empty_reply)
        CODE_NETWORK -> ResolvedStatus(R.string.generation_failure_network)
        CODE_AUTH -> ResolvedStatus(R.string.generation_failure_auth)
        CODE_RATE_LIMIT -> ResolvedStatus(R.string.generation_failure_rate_limit)
        CODE_RATE_LIMIT_RETRY -> {
            val seconds = (notice.formatArgs.firstOrNull() as? Int)?.takeIf { it > 0 }
            if (seconds != null) {
                ResolvedStatus(R.string.generation_failure_rate_limit_retry, listOf(seconds))
            } else {
                ResolvedStatus(R.string.generation_failure_rate_limit)
            }
        }
        CODE_QUOTA -> ResolvedStatus(R.string.generation_failure_quota)
        CODE_TIMEOUT -> ResolvedStatus(R.string.generation_failure_timeout)
        CODE_INVALID_REQUEST -> ResolvedStatus(R.string.generation_failure_invalid_request)
        CODE_CONTEXT_LIMIT -> if (notice.formatArgs.size == 2) {
            ResolvedStatus(R.string.chat_document_error_over_capacity, notice.formatArgs)
        } else {
            ResolvedStatus(R.string.chat_document_error_invalid_estimate)
        }
        CODE_CONTEXT_LIMIT_CONVERSATION -> if (notice.formatArgs.size == 2) {
            ResolvedStatus(R.string.chat_context_error_over_capacity, notice.formatArgs)
        } else {
            ResolvedStatus(R.string.chat_document_error_invalid_estimate)
        }
        CODE_CONTEXT_LIMIT_TURN_DOCUMENTS -> if (notice.formatArgs.size == 2) {
            ResolvedStatus(R.string.chat_context_error_turn_documents, notice.formatArgs)
        } else {
            ResolvedStatus(R.string.chat_document_error_invalid_estimate)
        }
        CODE_CONTEXT_LIMIT_TURN -> if (notice.formatArgs.size == 2) {
            ResolvedStatus(R.string.chat_context_error_turn, notice.formatArgs)
        } else {
            ResolvedStatus(R.string.chat_document_error_invalid_estimate)
        }
        CODE_CONTEXT_LIMIT_UNKNOWN_WINDOW -> ResolvedStatus(R.string.chat_document_error_unknown_capacity)
        CODE_CONTEXT_LIMIT_UPSTREAM -> ResolvedStatus(R.string.chat_context_error_upstream)
        CODE_OUTPUT_LIMIT -> ResolvedStatus(R.string.generation_failure_output_limit)
        CODE_CONTENT_FILTERED -> ResolvedStatus(R.string.generation_failure_content_filtered)
        CODE_SERVER -> ResolvedStatus(R.string.generation_failure_server)
        CODE_BUSY -> ResolvedStatus(R.string.generation_failure_busy)
        CODE_PERSISTENCE -> ResolvedStatus(R.string.generation_failure_persistence)
        CODE_TOOL_BUDGET -> ResolvedStatus(R.string.generation_failure_tool_budget)
        CODE_TAILNET_DISABLED -> ResolvedStatus(R.string.generation_failure_tailnet_disabled)
        CODE_TAILNET_NEEDS_LOGIN -> ResolvedStatus(R.string.generation_failure_tailnet_needs_login)
        CODE_TAILNET_FAILED -> ResolvedStatus(R.string.generation_failure_tailnet_failed)
        CODE_TAILNET_TIMEOUT -> ResolvedStatus(R.string.generation_failure_tailnet_timeout)
        CODE_RUN_MODE_REQUIRED -> ResolvedStatus(R.string.run_mode_required)
        CODE_LINUX_UNAVAILABLE -> ResolvedStatus(R.string.run_mode_linux_unavailable)
        CODE_UNKNOWN -> ResolvedStatus(R.string.generation_failure_unknown)
        else -> ResolvedStatus(R.string.generation_failure_unknown)
    }
}

/**
 * tailnet 失败的入口：内置 Tailscale 未开启时“去开启 / 重试 / 换用公网模型”，需要登录时“去登录 / 重试 / 换用公网模型”，
 * 无法连接或超时时“重试 / 换用公网模型 / Tailscale 设置”。
 */
enum class TailnetFailureKind { DISABLED, NEEDS_LOGIN, UNREACHABLE }
