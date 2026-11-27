package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.catalog.MetadataSource
import com.promenar.infinity.data.remote.protocol.ProtocolType
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

/** 提供商卡片短协议标签（2 行卡片精简展示，保持高可读性与零截断）。 */
internal fun providerShortProtocolTag(protocolType: ProtocolType): String = when (protocolType) {
    ProtocolType.OpenAI_ChatCompletions,
    ProtocolType.OpenAI_Responses,
    ProtocolType.Generic_OpenAI_Compat,
    -> "OpenAI"
    ProtocolType.Anthropic_Messages -> "Anthropic"
    ProtocolType.Google_Gemini -> "Gemini"
    ProtocolType.Google_VertexAI -> "Vertex"
    ProtocolType.DeepSeek -> "DeepSeek"
    ProtocolType.Qwen_DashScope -> "DashScope"
    ProtocolType.Zhipu_GLM -> "GLM"
    ProtocolType.Moonshot_Kimi -> "Kimi"
    ProtocolType.Doubao_ByteDance -> "Ark"
    ProtocolType.Mistral_Chat -> "Mistral"
    ProtocolType.Baichuan -> "Baichuan"
    ProtocolType.Cohere_Chat -> "Cohere"
    ProtocolType.Yi_ZeroOne -> "Yi"
    ProtocolType.Local -> "Local"
}

/** 列表行中显示名与远端 ID 大小写忽略完全一致时隐藏重复副行，避免视觉噪声。 */
internal fun shouldShowModelRemoteId(displayName: String, remoteModelId: String): Boolean {
    val cleanName = displayName.trim()
    val cleanId = remoteModelId.trim()
    if (cleanId.isEmpty()) return false
    return !cleanName.equals(cleanId, ignoreCase = true)
}

/** 模型管理页筛选维度：全部 / 已启用 / 按模型类型。 */
internal sealed interface ProviderModelFilter {
    val key: String

    data object All : ProviderModelFilter {
        override val key: String = "all"
    }

    data object Enabled : ProviderModelFilter {
        override val key: String = "enabled"
    }

    data class Type(val typeKey: String) : ProviderModelFilter {
        override val key: String = "type:$typeKey"
    }
}

internal data class ProviderModelFilterOption(
    val filter: ProviderModelFilter,
    val count: Int,
)

private val ModelFilterTypeOrder = listOf(
    "reasoning",
    "chat",
    "speech",
    "music",
    "image",
    "video",
    "realtime",
    "embedding",
    "rerank",
)

internal fun normalizedModelFilterType(rawType: String): String = when (val lower = rawType.lowercase()) {
    "audio" -> "speech"
    else -> lower
}

/** 根据当前提供商实际模型列表动态生成筛选胶囊选项：全部 → 已启用（若有）→ 按预设顺序出现过的各类型。 */
internal fun buildModelFilterOptions(models: List<ModelInfo>): List<ProviderModelFilterOption> {
    if (models.isEmpty()) return emptyList()
    val options = mutableListOf<ProviderModelFilterOption>()
    options += ProviderModelFilterOption(ProviderModelFilter.All, models.size)
    val enabledCount = models.count { it.enabled }
    if (enabledCount in 1 until models.size) {
        options += ProviderModelFilterOption(ProviderModelFilter.Enabled, enabledCount)
    }
    val countsByType = models.groupingBy { normalizedModelFilterType(it.type) }.eachCount()
    val presentKnownTypes = ModelFilterTypeOrder.filter { (countsByType[it] ?: 0) > 0 }
    if (presentKnownTypes.size > 1) {
        presentKnownTypes.forEach { typeKey ->
            options += ProviderModelFilterOption(ProviderModelFilter.Type(typeKey), countsByType.getValue(typeKey))
        }
    }
    return options
}

internal fun applyProviderModelFilter(models: List<ModelInfo>, filter: ProviderModelFilter): List<ModelInfo> =
    when (filter) {
        ProviderModelFilter.All -> models
        ProviderModelFilter.Enabled -> models.filter { it.enabled }
        is ProviderModelFilter.Type -> models.filter { normalizedModelFilterType(it.type) == filter.typeKey }
    }
