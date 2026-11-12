package com.promenar.infinity.data.model

import com.promenar.infinity.data.mediagen.cloud.MediaTariff
import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.*

@Serializable
enum class CloudMediaApi { SEEDANCE, MINIMAX_VIDEO_V1, MINIMAX_VIDEO_V2, MINIMAX_SPEECH }

@Serializable
data class CloudMediaOptions(
    val baseUrl: String? = null,
    val createPath: String? = null,
    val queryPath: String? = null,
    val retrievePath: String = "/v1/files/retrieve",
    val nativeAudio: Boolean? = null,
    val voiceId: String = "",
    val audioFormat: String = "mp3",
    val defaultDurationSeconds: Int? = null,
    val defaultResolution: String? = null,
    val estimatedTokensPerSecond: Long? = null,
    val requiresVisualReferenceForAudio: Boolean = false,
) {
    init {
        require(defaultDurationSeconds == null || defaultDurationSeconds > 0)
        require(estimatedTokensPerSecond == null || estimatedTokensPerSecond > 0)
        require(audioFormat in setOf("mp3", "wav", "flac"))
        baseUrl?.let { value ->
            val uri = java.net.URI(value)
            require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "媒体基础地址不能包含凭证或查询参数" }
        }
        listOfNotNull(createPath, queryPath, retrievePath).forEach { path ->
            require(path.startsWith('/') && !path.startsWith("//") && !path.contains('\\') && !path.contains('?') && !path.contains('#')) { "媒体端点应为不含查询参数的相对路径" }
            require(path.split('/').none { it in setOf(".", "..") })
        }
    }
}

@Serializable
data class MediaModelSettings(
    @Serializable(with = MediaGroupSerializer::class) val group: MediaGroup,
    @Serializable(with = MediaCapabilitiesSerializer::class) val capabilities: MediaCapabilities,
    val api: CloudMediaApi? = null,
    val options: CloudMediaOptions = CloudMediaOptions(),
    val tariff: MediaTariff? = null,
) {
    init {
        require(capabilities.modes.isNotEmpty())
        require(capabilities.referenceOrder.all { it.isNotBlank() } && capabilities.referenceOrder.distinct().size == capabilities.referenceOrder.size)
        require(api == null || group == if (api == CloudMediaApi.MINIMAX_SPEECH) MediaGroup.AUDIO else MediaGroup.VIDEO) { "媒体类型与生成接口不一致" }
    }
    fun withCharacterReferences(mode: CharacterReferenceMode, order: List<String>): MediaModelSettings {
        require(order.all { it.isNotBlank() } && order.distinct().size == order.size)
        return copy(capabilities = capabilities.copy(characterReferenceMode = mode, referenceOrder = order.toList()))
    }
}

object MediaModelSettingsCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun encode(settings: MediaModelSettings): String = json.encodeToString(MediaModelSettings.serializer(), settings)
    fun decode(value: String?): MediaModelSettings? = value?.let {
        runCatching { json.decodeFromString(MediaModelSettings.serializer(), it) }.getOrNull()
    }
}

object MediaGroupSerializer : KSerializer<MediaGroup> {
    override val descriptor = kotlinx.serialization.descriptors.PrimitiveSerialDescriptor("MediaGroup", kotlinx.serialization.descriptors.PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: MediaGroup) = encoder.encodeString(value.name)
    override fun deserialize(decoder: Decoder): MediaGroup = MediaGroup.valueOf(decoder.decodeString())
}

/** 领域契约不承担存储格式，序列化集中在数据层。 */
object MediaCapabilitiesSerializer : KSerializer<MediaCapabilities> {
    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor
    override fun serialize(encoder: Encoder, value: MediaCapabilities) {
        encoder.encodeSerializableValue(JsonObject.serializer(), buildJsonObject {
            put("modes", JsonArray(value.modes.map { JsonPrimitive(it.name) }))
            put("maxImages", value.maxImages); put("maxVideos", value.maxVideos); put("maxAudio", value.maxAudio)
            put("nativeAudio", value.nativeAudio)
            put("resolutions", JsonArray(value.resolutions.map(::JsonPrimitive)))
            value.minDurationSeconds?.let { put("minDurationSeconds", it) }
            value.maxDurationSeconds?.let { put("maxDurationSeconds", it) }
            put("promptFormat", value.promptFormat.name); put("characterReferenceMode", value.characterReferenceMode.name)
            put("referenceOrder", JsonArray(value.referenceOrder.map(::JsonPrimitive)))
        })
    }
    override fun deserialize(decoder: Decoder): MediaCapabilities {
        val o = decoder.decodeSerializableValue(JsonObject.serializer())
        return MediaCapabilities(
            modes = o.getValue("modes").jsonArray.map { GenerationMode.valueOf(it.jsonPrimitive.content) }.toSet(),
            maxImages = o["maxImages"]?.jsonPrimitive?.int ?: 0,
            maxVideos = o["maxVideos"]?.jsonPrimitive?.int ?: 0,
            maxAudio = o["maxAudio"]?.jsonPrimitive?.int ?: 0,
            nativeAudio = o["nativeAudio"]?.jsonPrimitive?.boolean ?: false,
            resolutions = o["resolutions"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
            minDurationSeconds = o["minDurationSeconds"]?.jsonPrimitive?.int,
            maxDurationSeconds = o["maxDurationSeconds"]?.jsonPrimitive?.int,
            promptFormat = o["promptFormat"]?.jsonPrimitive?.content?.let(PromptFormat::valueOf) ?: PromptFormat.GENERIC,
            characterReferenceMode = o["characterReferenceMode"]?.jsonPrimitive?.content?.let(CharacterReferenceMode::valueOf) ?: CharacterReferenceMode.SEPARATE,
            referenceOrder = o["referenceOrder"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty(),
        )
    }
}

private val defaultReferenceOrder = listOf("front_full_body", "side_full_body", "back_full_body", "face_close_up")

/** 接入点 ID 无法推导模型家族时，由用户显式设置媒体配置。 */
fun mediaModelPreset(remoteId: String): MediaModelSettings? {
    val id = remoteId.lowercase()
    val videoModes = setOf(GenerationMode.TEXT, GenerationMode.FIRST_FRAME, GenerationMode.FIRST_LAST_FRAME, GenerationMode.REFERENCE)
    return when {
        id.startsWith("doubao-seedance-2-0") || id.startsWith("doubao-seedance-2-5") -> {
            val v25 = id.startsWith("doubao-seedance-2-5")
            MediaModelSettings(MediaGroup.VIDEO, MediaCapabilities(videoModes, maxImages = if (v25) 30 else 9,
                maxVideos = if (v25) 10 else 3, maxAudio = if (v25) 10 else 3, nativeAudio = true,
                resolutions = when {
                    v25 -> listOf("480p", "720p", "1080p")
                    "fast" in id || "mini" in id -> listOf("480p", "720p")
                    else -> listOf("480p", "720p", "1080p", "4k")
                }, minDurationSeconds = 4, maxDurationSeconds = if (v25) 30 else 15,
                promptFormat = PromptFormat.SEEDANCE, referenceOrder = defaultReferenceOrder), CloudMediaApi.SEEDANCE,
                options = CloudMediaOptions(requiresVisualReferenceForAudio = !v25))
        }
        id.startsWith("doubao-seedance-1-0-pro") -> MediaModelSettings(MediaGroup.VIDEO,
            MediaCapabilities(if ("pro-fast" in id) setOf(GenerationMode.TEXT, GenerationMode.FIRST_FRAME) else videoModes - GenerationMode.REFERENCE, resolutions = listOf("480p", "720p", "1080p"),
                minDurationSeconds = 2, maxDurationSeconds = 12, promptFormat = PromptFormat.SEEDANCE), CloudMediaApi.SEEDANCE)
        id in setOf("minimax-h3", "minimax-h3-highspeed", "minimax-h3-lite", "minimax-h3-max") -> MediaModelSettings(MediaGroup.VIDEO,
            MediaCapabilities(videoModes, maxImages = 9, maxVideos = 3, maxAudio = 3, nativeAudio = true,
                resolutions = if (id == "minimax-h3") listOf("768P", "2K") else listOf("480P", "768P"),
                minDurationSeconds = if (id == "minimax-h3-max") 5 else 4, maxDurationSeconds = 15,
                promptFormat = PromptFormat.H3, referenceOrder = defaultReferenceOrder), CloudMediaApi.MINIMAX_VIDEO_V2)
        id == "minimax-hailuo-02" -> MediaModelSettings(MediaGroup.VIDEO,
            MediaCapabilities(videoModes - GenerationMode.REFERENCE, resolutions = listOf("512P", "768P", "1080P"),
                minDurationSeconds = 6, maxDurationSeconds = 10), CloudMediaApi.MINIMAX_VIDEO_V1)
        id == "s2v-01" -> MediaModelSettings(MediaGroup.VIDEO,
            MediaCapabilities(setOf(GenerationMode.REFERENCE), maxImages = 1, referenceOrder = defaultReferenceOrder), CloudMediaApi.MINIMAX_VIDEO_V1)
        id.matches(Regex("speech-(02|2\\.[568])-(hd|turbo)")) -> MediaModelSettings(MediaGroup.AUDIO,
            MediaCapabilities(setOf(GenerationMode.SPEECH)), CloudMediaApi.MINIMAX_SPEECH)
        else -> null
    }
}

fun ModelInfo.effectiveMediaSettings(): MediaModelSettings? = if (mediaSettingsInvalid) null else mediaSettings ?: mediaModelPreset(remoteModelId) ?: when (type) {
    "image" -> MediaModelSettings(MediaGroup.IMAGE, MediaCapabilities(setOf(GenerationMode.TEXT)))
    else -> null
}

/** 通道 ID 使用持久化选择 ID，名称、远端模型别名和输入设置改变时保持稳定。 */
fun ModelInfo.toMediaChannel(): MediaChannel? = if (!enabled || providerId.isNullOrBlank()) null else
    effectiveMediaSettings()?.let { MediaChannel("cloud:$id", it.group, it.capabilities) }

/** MiniMax 的对话模型列表未必列出媒体模型；把官方已核验的媒体候选交给同一同步入口，默认不启用。 */
fun builtInMediaModelIds(baseUrl: String): List<String> {
    val host = runCatching { java.net.URI(baseUrl).host?.lowercase() }.getOrNull()
    return if (host in setOf("api.minimax.io", "api.minimaxi.com")) listOf(
        "MiniMax-H3", "MiniMax-H3-Highspeed", "MiniMax-H3-Lite", "MiniMax-H3-Max", "MiniMax-Hailuo-02", "S2V-01", "speech-2.8-hd", "speech-2.8-turbo",
    ) else emptyList()
}
