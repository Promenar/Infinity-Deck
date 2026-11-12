package com.promenar.infinity.data.mediagen

import com.promenar.infinity.data.mediagen.cloud.CloudMediaModelSource
import com.promenar.infinity.data.mediagen.comfy.ComfyTemplateChannelSource
import com.promenar.infinity.data.mediagen.queue.MediaBackendBinding
import com.promenar.infinity.data.mediagen.registry.ComfyChannelSource
import com.promenar.infinity.domain.mediagen.*
import java.net.URI
import java.security.MessageDigest

/** 模板源首次发射就是存储读取结果，直接衔接，保留首轮加载语义。 */
fun ComfyTemplateChannelSource.asRegistrySource(): ComfyChannelSource = ComfyChannelSource { observeTemplateChannels() }

/** 绑定按后端类型稳定；提供商与主机在每次入队时解析，支持启动后新增配置。 */
fun productionMediaBindings(
    models: CloudMediaModelSource,
    seedance: MediaGenerationBackend,
    miniMax: MediaGenerationBackend,
    comfy: MediaGenerationBackend,
    configurationFor: suspend (MediaChannel) -> String? = { null },
    expectedOutputsFor: suspend (MediaChannel) -> Int = { 1 },
    comfyEndpoint: suspend (MediaChannel) -> String,
): List<MediaBackendBinding> = listOf(
    MediaBackendBinding("production-seedance", "cloud", { requireNotNull(models.find(it.id)).modelId }, seedance,
        providerFor = { requireNotNull(models.find(it.id)).providerId }, configurationFor = configurationFor, expectedOutputsFor = expectedOutputsFor),
    MediaBackendBinding("production-minimax", "cloud", { requireNotNull(models.find(it.id)).modelId }, miniMax,
        providerFor = { requireNotNull(models.find(it.id)).providerId }, configurationFor = configurationFor, expectedOutputsFor = expectedOutputsFor),
    MediaBackendBinding("production-comfy", "comfy", { it.id.removePrefix("comfy:") }, comfy,
        providerFor = { comfyProviderId(comfyEndpoint(it)) }, configurationFor = configurationFor, expectedOutputsFor = expectedOutputsFor),
)

/** 同一端点的多个模板或主机别名共用并发上限；标识不包含地址。 */
internal fun comfyProviderId(endpoint: String): String {
    val uri = URI(endpoint).normalize()
    require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null)
    val port = if (uri.port >= 0) uri.port else if (uri.scheme == "https") 443 else 80
    val canonical = "${uri.scheme.lowercase()}://${uri.host.lowercase()}:$port${uri.rawPath.orEmpty().trimEnd('/')}"
    val hash = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    return "comfy-host:$hash"
}
