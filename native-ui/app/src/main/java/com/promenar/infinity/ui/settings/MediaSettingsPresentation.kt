package com.promenar.infinity.ui.settings

import com.promenar.infinity.data.mediagen.cloud.BillingUnit
import com.promenar.infinity.data.mediagen.cloud.MediaRate
import com.promenar.infinity.data.mediagen.cloud.MediaTariff
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.data.model.MediaModelSettings
import com.promenar.infinity.domain.mediagen.*
import java.net.URI
import java.security.MessageDigest
import java.util.Locale

internal data class ReferenceInputDescription(val mode: CharacterReferenceMode, val limit: Int)
internal fun referenceInputDescription(caps: MediaCapabilities): ReferenceInputDescription? =
    caps.maxImages.takeIf { it > 0 }?.let { ReferenceInputDescription(caps.characterReferenceMode, it) }

internal data class MediaChannelRow(
    val channel: MediaChannel,
    val name: String,
    val sourceSegments: List<String>,
    val isDefault: Boolean,
    val modelId: String? = null,
    val templateId: String? = null,
) {
    val references: ReferenceInputDescription? get() = referenceInputDescription(channel.capabilities)
}

/** 注册表返回的组内顺序就是默认优先级；名称通过稳定 id 关联。 */
internal fun mediaChannelRows(
    channels: List<MediaChannel>, models: List<ModelInfo>, templates: List<ComfyTemplateState>, hosts: List<ComfyHost>,
): Map<MediaGroup, List<MediaChannelRow>> {
    val cloud = models.associateBy { "cloud:${it.id}" }
    val comfy = templates.associateBy { it.template.channelId }
    val hostNames = hosts.associate { it.id to it.name }
    return MediaGroup.entries.associateWith { group ->
        channels.filter { it.group == group }.mapIndexed { index, channel ->
            val model = cloud[channel.id]
            val template = comfy[channel.id]?.template
            val source = when {
                model != null -> listOf(model.providerName)
                template != null -> listOfNotNull("ComfyUI", hostNames[template.hostId])
                else -> emptyList()
            }
            MediaChannelRow(channel, model?.name ?: template?.name ?: channel.id, source, index == 0, model?.id, template?.id)
        }
    }
}

internal fun <T> moveMediaItemUp(items: List<T>, value: T): List<T> {
    val index = items.indexOf(value)
    if (index <= 0) return items
    return items.toMutableList().also { it[index] = items[index - 1]; it[index - 1] = value }
}

internal fun changeMediaReferences(settings: MediaModelSettings, mode: CharacterReferenceMode): MediaModelSettings =
    settings.withCharacterReferences(mode, settings.capabilities.referenceOrder)

internal data class ProviderMediaCount(val chat: Int = 0, val video: Int = 0, val speech: Int = 0, val image: Int = 0)
internal fun providerMediaCounts(models: List<ModelInfo>): Map<String, ProviderMediaCount> = models
    .filter { it.providerId != null }.groupBy { it.providerId.orEmpty() }.mapValues { (_, list) ->
        fun group(m: ModelInfo) = m.effectiveMediaSettings()?.group ?: when (m.type) {
            "image" -> MediaGroup.IMAGE; "video" -> MediaGroup.VIDEO; "audio" -> MediaGroup.AUDIO; else -> null
        }
        ProviderMediaCount(list.count { group(it) == null && it.type in setOf("chat", "reasoning") },
            list.count { group(it) == MediaGroup.VIDEO }, list.count { group(it) == MediaGroup.AUDIO }, list.count { group(it) == MediaGroup.IMAGE })
    }

internal fun mediaProviderListSummary(providers: List<ProviderListItem>, unsupportedCount: Int, hostCount: Int): ProviderListSummary {
    val cloud = providerListSummary(providers, unsupportedCount)
    return cloud.copy(total = cloud.total + hostCount, enabled = cloud.enabled + hostCount)
}

internal data class TemplateStatusLabel(val status: PreflightStatus, val missingCount: Int)
internal fun templateStatusLabel(result: PreflightResult) = TemplateStatusLabel(result.status, result.missing.distinct().size)

internal enum class MediaFormError { NAME, ADDRESS, CREDENTIAL, CURRENCY, AMOUNT, PER_UNITS, FREE_UNITS, DUPLICATE_UNIT }
internal data class MediaRateDraft(
    val unit: BillingUnit = BillingUnit.TASK,
    val amount: String = "0",
    val perUnits: String = "1",
    val freeUnits: String = "0",
)
internal data class MediaTariffDraft(val currency: String = "CNY", val rates: List<MediaRateDraft> = emptyList()) {
    fun validationError(): MediaFormError? {
        if (!currency.trim().matches(Regex("[A-Za-z]{3}"))) return MediaFormError.CURRENCY
        if (rates.map { it.unit }.distinct().size != rates.size) return MediaFormError.DUPLICATE_UNIT
        rates.forEach { rate ->
            if (!rate.amount.trim().matches(Regex("[0-9]{1,12}(\\.[0-9]{1,12})?"))) return MediaFormError.AMOUNT
            if (rate.perUnits.toLongOrNull()?.let { it > 0 } != true) return MediaFormError.PER_UNITS
            if (rate.freeUnits.toLongOrNull()?.let { it >= 0 } != true) return MediaFormError.FREE_UNITS
        }
        return null
    }
    fun toTariff(): MediaTariff? {
        require(validationError() == null)
        return rates.takeIf { it.isNotEmpty() }?.let { MediaTariff(currency.trim().uppercase(Locale.ROOT),
            it.map { r -> MediaRate(r.unit, r.amount.trim(), r.perUnits.toLong(), r.freeUnits.toLong()) }) }
    }
    companion object {
        fun from(tariff: MediaTariff?) = MediaTariffDraft(tariff?.currency ?: "CNY",
            tariff?.rates.orEmpty().map { MediaRateDraft(it.unit, it.amount, it.perUnits.toString(), it.freeUnits.toString()) })
    }
}

internal data class ComfyHostDraft(val name: String = "", val address: String = "", val authKind: ComfyAuthKind = ComfyAuthKind.NONE) {
    fun toHost(id: String, previous: ComfyHost? = null): ComfyHost = (previous ?: ComfyHost(id, name.trim(), address.trim(), authKind))
        .copy(name = name.trim(), baseUrl = address.trim(), authKind = authKind)
}

/** 凭据只在表单及提交回调中短暂存活，不进入可保存状态、日志或智能体指令。 */
internal class ComfyCredentialInput(val token: String = "", val username: String = "", val password: String = "") {
    val empty: Boolean get() = token.isEmpty() && username.isEmpty() && password.isEmpty()
    override fun toString() = "ComfyCredentialInput(已隐藏)"
    fun credential(kind: ComfyAuthKind): ComfyCredential? = when {
        empty || kind == ComfyAuthKind.NONE -> null
        kind == ComfyAuthKind.BEARER -> ComfyCredential.Bearer(token)
        else -> ComfyCredential.Basic(username, password)
    }
}

internal fun validateComfyHost(
    draft: ComfyHostDraft, credential: ComfyCredentialInput, hasCredential: Boolean, previousKind: ComfyAuthKind?,
): MediaFormError? {
    if (draft.name.trim().length !in 1..120) return MediaFormError.NAME
    val address = draft.address.trim()
    val uri = runCatching { URI(address) }.getOrNull()
    if (uri == null || uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank() || uri.rawUserInfo != null ||
        uri.rawQuery != null || uri.rawFragment != null || address.contains('\\') ||
        uri.path.orEmpty().split('/').any { it in setOf(".", "..") } || uri.port > 65535 || uri.port == 0) return MediaFormError.ADDRESS
    if (draft.authKind == ComfyAuthKind.NONE) return null
    if (credential.empty) return if (hasCredential && previousKind == draft.authKind) null else MediaFormError.CREDENTIAL
    val invalid = when (draft.authKind) {
        ComfyAuthKind.BEARER -> credential.token.isBlank() || credential.token.any { it <= ' ' || it == '\u007f' }
        ComfyAuthKind.BASIC -> credential.username.isBlank() || ':' in credential.username ||
            (credential.username + credential.password).any { it < ' ' || it == '\u007f' }
        ComfyAuthKind.NONE -> false
    }
    return if (invalid) MediaFormError.CREDENTIAL else null
}

/** 时间记录绑定完整模板及预检结果；后台或智能体改变配置后不会沿用旧时间。 */
internal fun mediaPreflightStamp(state: ComfyTemplateState, atMillis: Long): String {
    require(atMillis > 0)
    return "${mediaPreflightSignature(state)}|$atMillis"
}
internal fun mediaPreflightTime(state: ComfyTemplateState, raw: String?): Long? {
    val parts = raw?.split('|', limit = 2) ?: return null
    if (parts.firstOrNull() != mediaPreflightSignature(state)) return null
    return parts.getOrNull(1)?.toLongOrNull()?.takeIf { it > 0 }
}
private fun mediaPreflightSignature(state: ComfyTemplateState): String {
    val value = ComfyConfigurationCodec.encodeTemplate(state.template).toString() + state.preflight.toString() + state.passedFingerprint.orEmpty()
    return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
}
