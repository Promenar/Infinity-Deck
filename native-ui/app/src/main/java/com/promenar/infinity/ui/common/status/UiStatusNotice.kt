package com.promenar.infinity.ui.common.status

/**
 * 发行可达 UI 层通用的结构化状态/错误严重性。
 *
 * 仅靠该枚举决定颜色/图标，**绝不**依赖任何展示字符串前缀（如 `startsWith("同步失败")`）
 * 判定严重性，避免本地化后语义误判。
 */
enum class NoticeSeverity { Info, Success, Warning, Error }

/**
 * 发行可达 UI 层通用的结构化状态/错误契约。
 *
 * 设计原则（F-2/F-3/F-5/F-6 整改基线）：
 * - [code]：稳定、不随 locale 变化的语义码（如 `"provider_models.sync_failed"`、`"rag.index.vectorizing"`）。
 *   只有 code/severity/[formatArgs] 可以穿越 ViewModel→UI 层边界。
 * - [formatArgs]：供展示层 `stringResource` 格式化的位置参数（仅数字/字符串原语）。
 * - [technical]：仅日志/诊断使用，**永不**直接展示给终端用户（底层异常文本、自然语言 subStatus 等）。
 *
 * 契约刻意保持简单、类型安全，可供后续 VectorizationQueue codec / ErrorNormalizer 复用，
 * 无需在本批引入 Room schema 迁移。
 */
data class UiStatusNotice(
    val severity: NoticeSeverity,
    val code: String,
    val formatArgs: List<Any> = emptyList(),
    val technical: String? = null,
    /** 已清洗的外部原因（如服务商错误说明），作为本地化文案下方的补充说明原样展示；不含技术诊断。 */
    val detail: String? = null,
    /** 上游 HTTP 状态码（如 402 / 429），仅作为结构化元信息供错误卡片折叠芯片展示。 */
    val httpStatus: Int? = null,
    /** 上游结构化错误码（如 1008 / insufficient_quota），已做白名单字符清洗。 */
    val errorCode: String? = null,
    /** 已脱敏的上游原始错误负载片段，供用户手动展开诊断与复制；永不进入日志字符串。 */
    val rawDiagnostic: String? = null,
) {
    override fun toString(): String =
        "UiStatusNotice(severity=$severity, code=$code, formatArgCount=${formatArgs.size}, " +
            "hasTechnical=${technical != null}, hasDetail=${detail != null}, " +
            "httpStatus=$httpStatus, hasErrorCode=${errorCode != null}, " +
            "hasRawDiagnostic=${rawDiagnostic != null})"
}

/**
 * 纯函数格式化结果：展示层据 [resourceId] + [args] 调用 `stringResource` 还原本地化文案。
 * 返回 `null` 表示未知内部 code，调用方应回落到安全通用文案，**不得**直接泄漏 code 或裸串。
 */
data class ResolvedStatus(
    val resourceId: Int,
    val args: List<Any> = emptyList(),
)
