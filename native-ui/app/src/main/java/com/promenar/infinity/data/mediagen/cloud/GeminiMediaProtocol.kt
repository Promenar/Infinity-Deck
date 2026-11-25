package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.model.CloudMediaOptions
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.InputKind
import com.promenar.infinity.domain.mediagen.MediaGenerationRequest
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** 单次云端语音或音乐合成结果：已规范为可直接播放/落盘的标准容器音频。 */
data class SynthesizedAudioResult(
    val bytes: ByteArray,
    val mimeType: String,
    val extension: String,
    val transcriptText: String? = null,
    val usage: Map<String, Long> = emptyMap(),
)

/** Gemini TTS 多说话人映射条目。 */
data class SpeechSpeakerVoice(
    val speaker: String,
    val voiceName: String,
)

/**
 * 构造 Google AI Studio Gemini TTS (`:generateContent`) 请求体。
 * 支持单人预设声线（`prebuiltVoiceConfig`）与多角色对话（`multiSpeakerVoiceConfig`）。
 */
fun geminiTtsCreateBody(
    prompt: String,
    voiceName: String = "Kore",
    speakers: List<SpeechSpeakerVoice> = emptyList(),
    styleInstruction: String? = null,
): JsonObject {
    val text = prompt.trim()
    require(text.isNotEmpty()) { "语音合成缺少文本内容" }
    val effectivePrompt = styleInstruction?.trim()?.takeIf { it.isNotEmpty() }?.let { "$it\n$text" } ?: text
    val resolvedVoice = voiceName.trim().ifEmpty { "Kore" }
    return buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", effectivePrompt) })
                })
            })
        })
        put("generationConfig", buildJsonObject {
            put("responseModalities", buildJsonArray { add(JsonPrimitive("AUDIO")) })
            put("speechConfig", buildJsonObject {
                if (speakers.size >= 2) {
                    put("multiSpeakerVoiceConfig", buildJsonObject {
                        put("speakerVoiceConfigs", buildJsonArray {
                            speakers.forEach { entry ->
                                require(entry.speaker.isNotBlank() && entry.voiceName.isNotBlank()) { "多说话人配置缺少角色名或音色名" }
                                add(buildJsonObject {
                                    put("speaker", entry.speaker.trim())
                                    put("voiceConfig", buildJsonObject {
                                        put("prebuiltVoiceConfig", buildJsonObject {
                                            put("voiceName", entry.voiceName.trim())
                                        })
                                    })
                                })
                            }
                        })
                    })
                } else {
                    val singleVoice = speakers.firstOrNull()?.voiceName?.trim()?.takeIf { it.isNotEmpty() } ?: resolvedVoice
                    put("voiceConfig", buildJsonObject {
                        put("prebuiltVoiceConfig", buildJsonObject {
                            put("voiceName", singleVoice)
                        })
                    })
                }
            })
        })
    }
}

/** 构造请求体时从 `MediaGenerationRequest` 派生单人或预设音色。 */
fun geminiTtsBody(request: MediaGenerationRequest, options: CloudMediaOptions): JsonObject {
    require(request.mode == GenerationMode.SPEECH) { "Gemini TTS 仅支持语音合成模式" }
    require(request.inputs.isEmpty()) { "Gemini TTS 使用预置声线名，不接受参考音频文件" }
    val voiceOverride = request.params["voice"]?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val style = request.params["style"]?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val voice = voiceOverride ?: options.voiceId.trim().ifEmpty { "Kore" }
    return geminiTtsCreateBody(prompt = request.prompt, voiceName = voice, styleInstruction = style)
}

/**
 * 构造 Google AI Studio Lyria 3 / 3.5 Interactions API (`POST /v1beta/interactions`) 请求体。
 * 纯文本直接传 string；含参考图时转为多模态 input 数组（text + base64 image）。
 */
fun geminiLyriaInteractionsBody(
    modelId: String,
    prompt: String,
    inlineImageDataUrls: List<String> = emptyList(),
    durationSeconds: Int? = null,
): JsonObject {
    val cleanPrompt = prompt.trim()
    require(modelId.isNotBlank() && cleanPrompt.isNotEmpty()) { "音乐生成缺少模型 ID 或提示词" }
    val fullPrompt = if (durationSeconds != null && durationSeconds > 0 && !containsDurationHint(cleanPrompt)) {
        "$cleanPrompt\nTarget duration: about ${durationSeconds}s."
    } else {
        cleanPrompt
    }
    return buildJsonObject {
        put("model", modelId.removePrefix("models/"))
        put("store", false)
        if (inlineImageDataUrls.isEmpty()) {
            put("input", fullPrompt)
        } else {
            put("input", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", fullPrompt)
                })
                inlineImageDataUrls.forEach { dataUrl ->
                    val (mime, base64) = parseDataUrl(dataUrl)
                    add(buildJsonObject {
                        put("type", "image")
                        put("data", base64)
                        put("mime_type", mime)
                    })
                }
            })
        }
    }
}

/** 兼容仅转发 `:generateContent` 的网关或模型变体。 */
fun geminiLyriaGenerateContentBody(
    prompt: String,
    inlineImageDataUrls: List<String> = emptyList(),
    durationSeconds: Int? = null,
): JsonObject {
    val cleanPrompt = prompt.trim()
    require(cleanPrompt.isNotEmpty()) { "音乐生成缺少提示词" }
    val fullPrompt = if (durationSeconds != null && durationSeconds > 0 && !containsDurationHint(cleanPrompt)) {
        "$cleanPrompt\nTarget duration: about ${durationSeconds}s."
    } else {
        cleanPrompt
    }
    return buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", fullPrompt) })
                    inlineImageDataUrls.forEach { dataUrl ->
                        val (mime, base64) = parseDataUrl(dataUrl)
                        add(buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", mime)
                                put("data", base64)
                            })
                        })
                    }
                })
            })
        })
        put("generationConfig", buildJsonObject {
            put("responseModalities", buildJsonArray {
                add(JsonPrimitive("AUDIO"))
                add(JsonPrimitive("TEXT"))
            })
        })
    }
}

/** OpenAI 兼容 `/v1/audio/speech` 请求体。 */
fun openAiSpeechBody(
    modelId: String,
    prompt: String,
    voice: String = "alloy",
    format: String = "mp3",
    instructions: String? = null,
): JsonObject {
    val text = prompt.trim()
    require(modelId.isNotBlank() && text.isNotEmpty()) { "语音合成缺少模型或文本" }
    return buildJsonObject {
        put("model", modelId)
        put("input", text)
        put("voice", voice.trim().ifEmpty { "alloy" })
        put("response_format", format.lowercase().takeIf { it in setOf("mp3", "wav", "flac", "opus") } ?: "mp3")
        instructions?.trim()?.takeIf { it.isNotEmpty() }?.let { put("instructions", it) }
    }
}

/**
 * 解析 Gemini TTS `:generateContent` 响应：
 * 提取 `candidates[].content.parts[].inlineData`，自动将原始 PCM 封装为可播放 WAV。
 */
fun parseGeminiTtsResponse(response: JsonObject): SynthesizedAudioResult {
    val audioChunks = mutableListOf<Pair<ByteArray, String>>()
    val candidates = response["candidates"] as? JsonArray
        ?: throw IOException("Gemini TTS 响应缺少 candidates")
    candidates.forEach { candEl ->
        val parts = ((candEl as? JsonObject)?.get("content") as? JsonObject)?.get("parts") as? JsonArray ?: return@forEach
        parts.forEach { partEl ->
            val part = partEl as? JsonObject ?: return@forEach
            val inline = (part["inlineData"] ?: part["inline_data"]) as? JsonObject ?: return@forEach
            val data = inline.string("data") ?: return@forEach
            val mime = inline.string("mimeType") ?: inline.string("mime_type") ?: "audio/L16;codec=pcm;rate=24000"
            val decoded = decodeBase64Audio(data)
            if (decoded.isNotEmpty()) audioChunks += decoded to mime
        }
    }
    if (audioChunks.isEmpty()) throw IOException("Gemini TTS 未返回音频数据")
    val firstMime = audioChunks.first().second
    val mergedBytes = if (audioChunks.size == 1) {
        audioChunks.first().first
    } else {
        ByteArrayOutputStream().apply { audioChunks.forEach { write(it.first) } }.toByteArray()
    }
    val usage = parseGeminiUsage(response)
    return normalizeAudioPayload(mergedBytes, firstMime, transcriptText = null, usage = usage)
}

/**
 * 解析 Google AI Studio Lyria 3 / 3.5 响应：
 * 同时兼容 Interactions API (`outputs` / `output_audio` + `output_text`) 与 `:generateContent` (`candidates[].content.parts[]`)。
 */
fun parseGeminiLyriaResponse(response: JsonObject): SynthesizedAudioResult {
    val textParts = mutableListOf<String>()
    var audioBytes: ByteArray? = null
    var audioMime = "audio/mpeg"

    // 1. Interactions API: outputs[]
    (response["outputs"] as? JsonArray)?.forEach { itemEl ->
        val item = itemEl as? JsonObject ?: return@forEach
        val type = item.string("type")?.lowercase()
        when (type) {
            "text" -> item.string("text")?.let(textParts::add)
            "audio" -> {
                val data = item.string("data") ?: ((item["audio"] as? JsonObject)?.string("data"))
                if (data != null) {
                    audioBytes = decodeBase64Audio(data)
                    audioMime = item.string("mime_type") ?: item.string("mimeType") ?: "audio/mpeg"
                }
            }
        }
    }

    // 2. Interactions API shorthand: output_audio / output_text
    if (audioBytes == null) {
        val outputAudio = (response["output_audio"] ?: response["outputAudio"]) as? JsonObject
        val data = outputAudio?.string("data")
        if (data != null) {
            audioBytes = decodeBase64Audio(data)
            audioMime = outputAudio.string("mime_type") ?: outputAudio.string("mimeType") ?: "audio/mpeg"
        }
    }
    (response["output_text"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let(textParts::add)

    // 3. generateContent fallback: candidates[].content.parts[]
    if (audioBytes == null) {
        (response["candidates"] as? JsonArray)?.forEach { candEl ->
            val parts = ((candEl as? JsonObject)?.get("content") as? JsonObject)?.get("parts") as? JsonArray ?: return@forEach
            parts.forEach { partEl ->
                val part = partEl as? JsonObject ?: return@forEach
                part.string("text")?.let(textParts::add)
                val inline = (part["inlineData"] ?: part["inline_data"]) as? JsonObject ?: return@forEach
                val data = inline.string("data") ?: return@forEach
                val mime = inline.string("mimeType") ?: inline.string("mime_type") ?: "audio/mpeg"
                val decoded = decodeBase64Audio(data)
                if (decoded.isNotEmpty()) {
                    audioBytes = decoded
                    audioMime = mime
                }
            }
        }
    }

    val bytes = audioBytes?.takeIf { it.isNotEmpty() } ?: throw IOException("Lyria 响应未返回音频数据")
    val transcript = textParts.joinToString("\n\n").trim().takeIf { it.isNotEmpty() }
    val usage = parseGeminiUsage(response)
    return normalizeAudioPayload(bytes, audioMime, transcriptText = transcript, usage = usage)
}

/**
 * 将云端返回的原始音频字节规范为标准可播放容器（如原始 PCM 自动补齐 44 字节 RIFF/WAVE 头）。
 * 已包含标准文件头（RIFF/ID3/MP3/Ogg/FLAC）时绝不二次封装。
 */
fun normalizeAudioPayload(
    rawBytes: ByteArray,
    mimeHint: String?,
    transcriptText: String? = null,
    usage: Map<String, Long> = emptyMap(),
): SynthesizedAudioResult {
    require(rawBytes.isNotEmpty()) { "音频数据为空" }
    val detected = detectAudioContainer(rawBytes)
    if (detected != null) {
        return SynthesizedAudioResult(rawBytes, detected.first, detected.second, transcriptText, usage)
    }
    val lowerMime = mimeHint?.lowercase().orEmpty()
    val isRawPcm = "pcm" in lowerMime || "audio/l16" in lowerMime || "audio/raw" in lowerMime
    if (isRawPcm) {
        val sampleRate = Regex("""rate=(\d+)""").find(lowerMime)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 8000..192000 } ?: 24000
        val channels = Regex("""channels=(\d+)""").find(lowerMime)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..2 } ?: 1
        val wav = wrapPcmAsWav(rawBytes, sampleRate = sampleRate, channels = channels, bitsPerSample = 16)
        return SynthesizedAudioResult(wav, "audio/wav", "wav", transcriptText, usage)
    }
    val (mime, ext) = when {
        "wav" in lowerMime -> "audio/wav" to "wav"
        "flac" in lowerMime -> "audio/flac" to "flac"
        "ogg" in lowerMime || "opus" in lowerMime -> "audio/ogg" to "ogg"
        "mp4" in lowerMime || "m4a" in lowerMime || "aac" in lowerMime -> "audio/mp4" to "m4a"
        else -> "audio/mpeg" to "mp3"
    }
    return SynthesizedAudioResult(rawBytes, mime, ext, transcriptText, usage)
}

/** 将 16-bit signed little-endian PCM 封装为标准 44 字节 RIFF/WAVE 容器。 */
fun wrapPcmAsWav(
    pcmBytes: ByteArray,
    sampleRate: Int = 24000,
    channels: Int = 1,
    bitsPerSample: Int = 16,
): ByteArray {
    val byteRate = sampleRate * channels * bitsPerSample / 8
    val blockAlign = (channels * bitsPerSample / 8).toShort()
    val dataSize = pcmBytes.size
    val riffChunkSize = 36 + dataSize
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(riffChunkSize)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16) // PCM fmt chunk size
        putShort(1) // PCM audio format
        putShort(channels.toShort())
        putInt(sampleRate)
        putInt(byteRate)
        putShort(blockAlign)
        putShort(bitsPerSample.toShort())
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataSize)
    }.array()
    return header + pcmBytes
}

private fun detectAudioContainer(bytes: ByteArray): Pair<String, String>? {
    if (bytes.size >= 12 &&
        bytes[0] == 'R'.code.toByte() && bytes[1] == 'I'.code.toByte() &&
        bytes[2] == 'F'.code.toByte() && bytes[3] == 'F'.code.toByte() &&
        bytes[8] == 'W'.code.toByte() && bytes[9] == 'A'.code.toByte() &&
        bytes[10] == 'V'.code.toByte() && bytes[11] == 'E'.code.toByte()
    ) return "audio/wav" to "wav"
    if (bytes.size >= 4 &&
        bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
        bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte()
    ) return "audio/flac" to "flac"
    if (bytes.size >= 4 &&
        bytes[0] == 'O'.code.toByte() && bytes[1] == 'g'.code.toByte() &&
        bytes[2] == 'g'.code.toByte() && bytes[3] == 'S'.code.toByte()
    ) return "audio/ogg" to "ogg"
    if (bytes.size >= 3 &&
        bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()
    ) return "audio/mpeg" to "mp3"
    if (bytes.size >= 2 && (bytes[0].toInt() and 0xFF) == 0xFF && (bytes[1].toInt() and 0xE0) == 0xE0) {
        return "audio/mpeg" to "mp3"
    }
    return null
}

private fun decodeBase64Audio(raw: String): ByteArray {
    val clean = raw.trim().replace("\n", "").replace("\r", "")
    return runCatching { Base64.getDecoder().decode(clean) }
        .getOrElse { Base64.getMimeDecoder().decode(clean) }
}

private fun parseDataUrl(dataUrl: String): Pair<String, String> {
    require(dataUrl.startsWith("data:") && ";base64," in dataUrl) { "参考图必须为 Base64 data URL" }
    val mime = dataUrl.substringAfter("data:").substringBefore(";base64,").ifBlank { "image/png" }
    val base64 = dataUrl.substringAfter(";base64,")
    return mime to base64
}

private fun containsDurationHint(prompt: String): Boolean =
    Regex("""\b\d+\s*(?:s|sec|secs|second|seconds|秒)\b""", RegexOption.IGNORE_CASE).containsMatchIn(prompt)

private fun parseGeminiUsage(response: JsonObject): Map<String, Long> = buildMap {
    put("tasks", 1L)
    val meta = (response["usageMetadata"] ?: response["usage"]) as? JsonObject ?: return@buildMap
    (meta["totalTokenCount"] ?: meta["total_tokens"])?.let { el ->
        (el as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { put("total_tokens", it) }
    }
}

/** 验证 Gemini Lyria 请求仅包含文本与不超过上限的参考图片。 */
internal fun extractLyriaInlineImages(request: MediaGenerationRequest): List<String> {
    require(request.mode == GenerationMode.MUSIC) { "Lyria 仅支持音乐生成模式" }
    require(request.inputs.all { it.kind == InputKind.IMAGE }) { "Lyria 参考输入仅支持图片" }
    return request.inputs.map { it.location }
}
