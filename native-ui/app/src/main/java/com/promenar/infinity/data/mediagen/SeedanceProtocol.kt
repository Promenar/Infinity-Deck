package com.promenar.infinity.data.mediagen

import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.*

/** 构造方舟请求时保留输入顺序，使提示词中的参考编号对应媒体顺序。 */
fun seedanceCreateBody(modelId: String, request: MediaGenerationRequest, generateAudio: Boolean? = null): JsonObject {
    require(modelId.isNotBlank())
    val content = buildJsonArray {
        add(buildJsonObject { put("type", "text"); put("text", request.prompt) })
        request.inputs.forEach { input ->
            val type = when (input.kind) { InputKind.IMAGE -> "image_url"; InputKind.VIDEO -> "video_url"; InputKind.AUDIO -> "audio_url" }
            val role = when (input.role) {
                InputRole.FIRST_FRAME -> "first_frame"
                InputRole.LAST_FRAME -> "last_frame"
                InputRole.REFERENCE_VOICE -> "reference_audio"
                InputRole.REFERENCE -> when (input.kind) { InputKind.IMAGE -> "reference_image"; InputKind.VIDEO -> "reference_video"; InputKind.AUDIO -> "reference_audio" }
            }
            require(input.location.isNotBlank())
            add(buildJsonObject {
                put("type", type)
                put(type, buildJsonObject { put("url", input.location) })
                put("role", role)
            })
        }
    }
    return buildJsonObject {
        generateAudio?.let { put("generate_audio", it) }
        put("model", modelId)
        put("content", content)
        request.specs.resolution?.let { put("resolution", it) }
        request.specs.durationSeconds?.let { put("duration", it) }
        request.specs.frames?.let { put("frames", it) }
        request.specs.seed?.let { put("seed", it) }
    }
}

enum class SeedanceRemoteState { QUEUED, RUNNING, SUCCEEDED, FAILED }
data class SeedanceStatus(val state: SeedanceRemoteState, val outputUrl: String? = null, val totalTokens: Long? = null, val error: String? = null)
fun parseSeedanceStatus(response: JsonObject): SeedanceStatus {
    val status = response["status"]?.jsonPrimitive?.contentOrNull ?: error("方舟响应缺少状态")
    val state = when (status) {
        "queued", "pending" -> SeedanceRemoteState.QUEUED
        "running" -> SeedanceRemoteState.RUNNING
        "succeeded" -> SeedanceRemoteState.SUCCEEDED
        "failed", "expired", "cancelled", "canceled" -> SeedanceRemoteState.FAILED
        else -> error("未知方舟任务状态：$status")
    }
    val content = response["content"] as? JsonObject
    val output = (content?.get("video_url") as? JsonPrimitive)?.contentOrNull
    require(state != SeedanceRemoteState.SUCCEEDED || !output.isNullOrBlank()) { "方舟成功响应缺少视频地址" }
    val usage = response["usage"] as? JsonObject
    val tokens = (usage?.get("total_tokens") as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }
    val error = (response["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
    return SeedanceStatus(state, output, tokens, error)
}
