package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import java.util.Base64
import kotlinx.serialization.json.*

internal fun CloudMediaModel.requestWithDefaults(channel: MediaChannel, request: MediaGenerationRequest): MediaGenerationRequest {
    require(channel.group == settings.group && channel.capabilities == settings.capabilities) { "媒体通道能力已变化，请重新选择通道" }
    val options = settings.options
    val effective = request.copy(specs = request.specs.copy(
        durationSeconds = request.specs.durationSeconds ?: options.defaultDurationSeconds,
        resolution = request.specs.resolution ?: options.defaultResolution,
    ))
    require(validateRequest(channel, effective).valid) { "生成请求的模式、素材数量、素材角色或规格不符合通道能力" }
    if (settings.group == MediaGroup.AUDIO) require(effective.specs == GenerationSpecs()) { "语音接口不接受视频规格参数" }
    require(effective.specs.width == null && effective.specs.height == null) { "云端接口使用分辨率选项，不接受自定义宽高" }
    if (effective.mode == GenerationMode.REFERENCE) require(effective.inputs.isNotEmpty()) { "参考模式需要素材" }
    if (effective.mode == GenerationMode.REFERENCE && options.requiresVisualReferenceForAudio && effective.inputs.any { it.kind == InputKind.AUDIO }) {
        require(effective.inputs.any { it.kind == InputKind.IMAGE || it.kind == InputKind.VIDEO }) { "该模型的音频参考需要搭配图片或视频" }
    }
    require(options.nativeAudio != true || channel.capabilities.nativeAudio) { "通道不支持原生音频" }
    return effective
}

internal suspend fun CloudMediaInputSource.resolveInputs(request: MediaGenerationRequest): MediaGenerationRequest {
    var chars = request.prompt.length.toLong()
    val inputs = request.inputs.map { input ->
        val location = remoteLocation(request, input)
        chars += location.length + 256L
        require(chars <= 60L * 1024 * 1024) { "媒体请求的内联素材总量超过上限，请使用素材 URL" }
        input.copy(location = location)
    }
    return request.copy(inputs = inputs)
}

internal suspend fun estimatedUsage(request: MediaGenerationRequest, options: CloudMediaOptions, source: CloudMediaInputSource): Map<String, Long> {
    val result = mutableMapOf("tasks" to 1L, "input_image_count" to request.inputs.count { it.kind == InputKind.IMAGE }.toLong())
    request.specs.durationSeconds?.let { seconds ->
        result["output_seconds"] = seconds.toLong()
        options.estimatedTokensPerSecond?.let { rate -> result["total_tokens"] = Math.multiplyExact(rate, seconds.toLong()) }
    }
    if (request.mode == GenerationMode.SPEECH) result["usage_characters"] = speechBillableCharacters(request.prompt)
    for ((kind, key) in listOf(InputKind.VIDEO to "input_seconds", InputKind.AUDIO to "input_audio_seconds")) {
        val durations = request.inputs.filter { it.kind == kind }.map { source.durationSeconds(request, it) }
        if (durations.all { it != null && it >= 0 }) result[key] = durations.sumOf { it!! }
    }
    return result
}

/** 只保存接口路径与明确用量，不保存提示词、素材地址和鉴权信息。 */
internal data class CloudTaskTicket(
    val id: String,
    val api: CloudMediaApi,
    val providerId: String,
    val channelId: String,
    val baseUrl: String,
    val query: String,
    val retrieve: String,
    val usage: Map<String, Long> = emptyMap(),
    val audioFormat: String = "mp3",
) {
    fun encode(): String = "cloud-v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(buildJsonObject {
        put("id", id); put("api", api.name); put("provider", providerId); put("channel", channelId); put("base", baseUrl); put("query", query); put("retrieve", retrieve)
        put("audioFormat", audioFormat)
        put("usage", buildJsonObject { usage.forEach { (k, v) -> put(k, v) } })
    }.toString().toByteArray(Charsets.UTF_8))
    companion object {
        fun decode(value: String): CloudTaskTicket {
            require(value.startsWith("cloud-v1:") && value.length <= 16_384) { "远端任务记录无效" }
            val o = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(value.removePrefix("cloud-v1:")), Charsets.UTF_8)).jsonObject
            return CloudTaskTicket(safeTaskId(o.getValue("id").jsonPrimitive.content), CloudMediaApi.valueOf(o.getValue("api").jsonPrimitive.content),
                o.getValue("provider").jsonPrimitive.content, o.getValue("channel").jsonPrimitive.content,
                o.getValue("base").jsonPrimitive.content, o.getValue("query").jsonPrimitive.content,
                o.getValue("retrieve").jsonPrimitive.content, o.getValue("usage").jsonObject.nonNegativeUsage(), o.string("audioFormat") ?: "mp3")
        }
    }
}

internal fun CloudTaskTicket.requireBinding(model: CloudMediaModel, channel: MediaChannel) {
    require(providerId == model.providerId && channelId == channel.id) { "远端任务不属于此通道" }
    val submitted = java.net.URI(baseUrl)
    val current = java.net.URI(model.baseUrl)
    require(submitted.scheme == current.scheme && submitted.host == current.host && submitted.port == current.port) { "提供商主机已变化，请恢复任务提交时的媒体地址后续查" }
}
