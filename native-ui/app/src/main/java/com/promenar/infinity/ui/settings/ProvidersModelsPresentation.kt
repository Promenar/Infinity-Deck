package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.catalog.MetadataSource
import java.net.URI
import java.util.Locale

/*
 * 提供商与模型管理页的展示用纯函数：不依赖 Compose 与资源，便于单元测试。
 */

/** 一个提供商下的模型数：已启用 / 全部。 */
internal data class ProviderModelCount(val enabled: Int, val total: Int)

/** 按稳定 providerId 分组统计模型数；没有模型的提供商不出现在结果中。 */
internal fun providerModelCounts(models: List<ModelInfo>): Map<String, ProviderModelCount> =
    models.filter { it.providerId != null }
        .groupBy { it.providerId.orEmpty() }
        .mapValues { (_, group) -> ProviderModelCount(enabled = group.count { it.enabled }, total = group.size) }

/** 提供商列表顶部概况：总数与已启用数（不含协议不受支持的提供商）。 */
internal data class ProviderListSummary(val total: Int, val enabled: Int)

internal fun providerListSummary(providers: List<ProviderListItem>, unsupportedCount: Int): ProviderListSummary =
    ProviderListSummary(total = providers.size + unsupportedCount, enabled = providers.count { it.enabled })

/**
 * 列表行只显示主机名（含非默认端口），去掉协议、路径与凭据；无法解析时退回去掉协议头的原文。
 */
internal fun providerHostLabel(baseUrl: String): String {
    val trimmed = baseUrl.trim()
    if (trimmed.isEmpty()) return ""
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    val parsed = runCatching { URI(withScheme) }.getOrNull()
    val host = parsed?.host?.takeIf { it.isNotBlank() }
        ?: return trimmed.substringAfter("://").substringBefore('/').substringAfter('@')
    val port = parsed.port.takeIf { it > 0 }?.let { ":$it" }.orEmpty()
    return host + port
}

/** 相对时间分档：刚刚 / N 分钟前 / N 小时前 / N 天前 / 具体日期。 */
internal sealed interface RelativeTimeBucket {
    data object JustNow : RelativeTimeBucket
    data class Minutes(val value: Int) : RelativeTimeBucket
    data class Hours(val value: Int) : RelativeTimeBucket
    data class Days(val value: Int) : RelativeTimeBucket
    data class Date(val epochMillis: Long) : RelativeTimeBucket
}

/** 一分钟内（含时钟回拨造成的未来时间）为“刚刚”；七天以上显示日期。 */
internal fun relativeTimeBucket(thenMillis: Long, nowMillis: Long): RelativeTimeBucket {
    val elapsed = nowMillis - thenMillis
    val minute = 60_000L
    return when {
        elapsed < minute -> RelativeTimeBucket.JustNow
        elapsed < 60 * minute -> RelativeTimeBucket.Minutes((elapsed / minute).toInt())
        elapsed < 24 * 60 * minute -> RelativeTimeBucket.Hours((elapsed / (60 * minute)).toInt())
        elapsed < 7 * 24 * 60 * minute -> RelativeTimeBucket.Days((elapsed / (24 * 60 * minute)).toInt())
        else -> RelativeTimeBucket.Date(thenMillis)
    }
}

/**
 * token 数的简写：1000 的整倍数按十进制（128000 → 128K），其余 1024 的整倍数按二进制
 * （131072 → 128K），再其余按十进制；保留至多一位小数。非正数返回空串，由调用方显示“未知”。
 */
internal fun formatTokenShort(tokens: Int): String {
    if (tokens <= 0) return ""
    if (tokens < 1000) return tokens.toString()
    val base = if (tokens % 1000 != 0 && tokens % 1024 == 0) 1024.0 else 1000.0
    val kilo = tokens / base
    return if (kilo >= 1000) "${trimDecimal(kilo / base)}M" else "${trimDecimal(kilo)}K"
}

/** 精确 token 数，按区域习惯分组（128,000）。 */
internal fun formatTokenExact(tokens: Int, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%,d", tokens)

private fun trimDecimal(value: Double): String {
    val rounded = Math.round(value * 10) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
}

/** 规格格子标注的数据来源。 */
internal enum class ModelSpecSource { Catalog, Provider, Manual, Inferred }

/** 规格格子对应的元数据字段。[sourceKey] 对应 [ModelInfo.metadataSourceByField]，[userField] 对应可编辑字段名单。 */
internal enum class ModelSpecField(val sourceKey: String, val userField: String?) {
    Context("contextTokens", "contextLength"),
    Input("inputTokens", null),
    Output("outputTokens", "maxOutputTokens"),
    KnowledgeCutoff("knowledgeCutoff", null),
}

/**
 * 字段来源：用户改过的字段一律为“手动”；其余按解析来源归为提供商、目录或推断；
 * 兜底值（[MetadataSource.FALLBACK]）与缺失记录返回 null，不标来源。
 */
internal fun modelSpecSource(model: ModelInfo, field: ModelSpecField): ModelSpecSource? {
    if (field.userField != null && field.userField in model.userEditedFields) return ModelSpecSource.Manual
    return when (model.metadataSourceByField[field.sourceKey]) {
        MetadataSource.USER -> ModelSpecSource.Manual
        MetadataSource.PROVIDER -> ModelSpecSource.Provider
        MetadataSource.INFINITY_OVERRIDE,
        MetadataSource.MODELS_DEV,
        MetadataSource.LITELLM,
        MetadataSource.OPENROUTER,
        -> ModelSpecSource.Catalog
        MetadataSource.FAMILY -> ModelSpecSource.Inferred
        MetadataSource.FALLBACK, null -> null
    }
}

/** 类型与其基础能力；切换类型时先移除全部基础能力，再加上新类型的基础能力，其余能力保留。 */
internal val ModelTypeBaseCapabilities: Map<String, Set<String>> = linkedMapOf(
    "chat" to setOf("chat"),
    "reasoning" to setOf("chat", "reasoning"),
    "image" to setOf("image"),
    "video" to setOf("video"),
    "speech" to setOf("speech"),
    "music" to setOf("music"),
    "realtime" to setOf("realtime"),
    "audio" to setOf("audio"),
    "embedding" to setOf("embedding"),
    "rerank" to setOf("rerank"),
)

private val AllBaseCapabilities = ModelTypeBaseCapabilities.values.flatten().toSet()

internal fun capabilitiesForTypeChange(current: Set<String>, newType: String): Set<String> =
    (current - AllBaseCapabilities) + (ModelTypeBaseCapabilities[newType] ?: setOf("chat"))

/** 页内模型编辑页的选择状态：打开、关闭，以及同步后模型已不存在时自动退出。 */
internal fun reconcileEditingModelId(editingId: String?, models: List<ModelInfo>): String? =
    editingId?.takeIf { id -> models.any { it.id == id } }
