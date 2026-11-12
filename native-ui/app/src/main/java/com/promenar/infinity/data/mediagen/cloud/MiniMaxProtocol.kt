package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.mediagen.seedanceCreateBody
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.*

fun miniMaxVideoBody(modelId: String, request: MediaGenerationRequest, api: CloudMediaApi): JsonObject {
    require(modelId.isNotBlank())
    if (api == CloudMediaApi.MINIMAX_VIDEO_V2) {
        require(request.specs.durationSeconds != null) { "MiniMax H3 需要视频时长" }
        val body = seedanceCreateBody(modelId, request)
        require(request.specs.frames == null && request.specs.seed == null) { "MiniMax V2 不支持 frames 或 seed" }
        return body
    }
    require(api == CloudMediaApi.MINIMAX_VIDEO_V1)
    require(request.inputs.all { it.kind == InputKind.IMAGE }) { "MiniMax V1 只支持图片输入" }
    if (modelId.equals("MiniMax-Hailuo-02", true)) {
        request.specs.durationSeconds?.let { require(it in setOf(6, 10)) { "Hailuo-02 时长只能为 6 或 10 秒" } }
        if (request.specs.resolution == "1080P") require(request.specs.durationSeconds == null || request.specs.durationSeconds == 6) { "1080P 仅支持 6 秒" }
        if (request.mode == GenerationMode.FIRST_LAST_FRAME) require(request.specs.resolution != "512P") { "首尾帧不支持 512P" }
    }
    if (modelId.equals("S2V-01", true)) require(request.specs.durationSeconds == null && request.specs.resolution == null) { "S2V-01 使用接口固定规格" }

    require(request.specs.frames == null && request.specs.seed == null) { "MiniMax V1 不支持 frames 或 seed" }
    return buildJsonObject {
        put("model", modelId); put("prompt", request.prompt); put("prompt_optimizer", false)
        when (request.mode) {
            GenerationMode.TEXT -> require(request.inputs.isEmpty())
            GenerationMode.FIRST_FRAME, GenerationMode.FIRST_LAST_FRAME -> {
                val first = request.inputs.singleOrNull { it.role == InputRole.FIRST_FRAME } ?: error("缺少唯一首帧")
                put("first_frame_image", first.location)
                if (request.mode == GenerationMode.FIRST_LAST_FRAME) {
                    require(request.inputs.size == 2)
                    put("last_frame_image", request.inputs.single { it.role == InputRole.LAST_FRAME }.location)
                } else require(request.inputs.size == 1)
            }
            GenerationMode.REFERENCE -> {
                require(request.inputs.size == 1 && request.inputs.single().role == InputRole.REFERENCE) { "S2V-01 需要一张参考图" }
                put("subject_reference", buildJsonArray { add(buildJsonObject {
                    put("type", "character"); put("image", buildJsonArray { add(request.inputs.single().location) })
                }) })
            }
            else -> error("MiniMax V1 不支持该模式")
        }
        request.specs.resolution?.let { put("resolution", it) }
        request.specs.durationSeconds?.let { put("duration", it) }
    }
}

fun miniMaxSpeechBody(modelId: String, request: MediaGenerationRequest, options: CloudMediaOptions): JsonObject {
    require(request.mode == GenerationMode.SPEECH && request.inputs.isEmpty()) { "语音合成需要文字与已配置的音色 ID" }
    require(options.voiceId.isNotBlank() && request.prompt.isNotBlank()) { "语音合成缺少文字或音色" }
    require(speechBillableCharacters(request.prompt) <= 100_000) { "语音文字超过 100000 个计费字符" }
    return buildJsonObject {
        put("model", modelId); put("text", request.prompt)
        put("voice_setting", buildJsonObject { put("voice_id", options.voiceId) })
        put("audio_setting", buildJsonObject { put("format", options.audioFormat) })
    }
}

internal fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
internal fun JsonObject.nonNegativeUsage(): Map<String, Long> = mapNotNull { (key, value) ->
    (value as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { key to it }
}.toMap()

/** 只返回稳定错误类别，供应商错误原文可能回显提示词、签名地址或凭证。 */
fun parseMiniMaxStatus(response: JsonObject, v2: Boolean, speech: Boolean): RemoteStatus {
    val errorCode = (response["base_resp"] as? JsonObject)?.get("status_code")?.jsonPrimitive?.intOrNull
    if (errorCode != null && errorCode != 0) return RemoteStatus.Failed("MiniMax 接口错误：$errorCode", errorCode in setOf(1000, 1001, 1002))
    val task = if (v2) response["task"] as? JsonObject ?: return RemoteStatus.Failed("MiniMax 响应缺少任务") else response
    val status = task.string("status")?.lowercase() ?: return RemoteStatus.Failed("MiniMax 响应缺少状态")
    return when (status) {
        "preparing", "queueing", "queued", "processing", "running" -> RemoteStatus.Running()
        "success", "succeeded" -> {
            val location = if (v2) (task["content"] as? JsonObject)?.string("url")
                else task.string("file_id")?.let { "minimax-file:$it" }
            if (location == null) RemoteStatus.Failed("MiniMax 成功响应缺少产物")
            else RemoteStatus.Succeeded(listOf(RemoteOutput(location, if (speech) InputKind.AUDIO else InputKind.VIDEO)),
                (task["usage"] as? JsonObject)?.nonNegativeUsage().orEmpty() +
                    listOfNotNull((task["usage_characters"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { "usage_characters" to it }).toMap() + ("tasks" to 1L))
        }
        "fail", "failed", "expired", "cancelled", "canceled" -> RemoteStatus.Failed("MiniMax 任务${if (status == "expired") "已过期" else "未完成"}")
        else -> RemoteStatus.Failed("MiniMax 返回未知任务状态")
    }
}
