package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModel
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModelSource
import com.promenar.infinity.data.mediagen.cloud.GeminiMediaGenerationBackend
import com.promenar.infinity.data.mediagen.cloud.SpeechSpeakerVoice
import com.promenar.infinity.data.mediagen.cloud.wrapPcmAsWav
import com.promenar.infinity.data.model.CloudMediaApi
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.domain.media.MediaOrigin
import com.promenar.infinity.domain.media.MediaPayload
import com.promenar.infinity.domain.media.WorkspaceMediaException
import com.promenar.infinity.domain.media.WorkspaceMediaStore
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.util.Locale
import java.util.UUID
import kotlin.math.min
import kotlin.math.pow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 语音合成（TTS）内置工具：支持 Google AI Studio Gemini TTS（单人/多角色）、MiniMax Speech 及 OpenAI 兼容语音接口。
 * 产物保存至会话工作区，返回虚拟路径与音频引用。
 */
class SpeechGenerationSkill(
    private val providerManager: ProviderManager,
    private val cloudModels: CloudMediaModelSource,
    private val backend: () -> GeminiMediaGenerationBackend,
    private val mediaStore: WorkspaceMediaStore? = null,
    private val jobs: com.promenar.infinity.data.jobs.BackgroundJobManager? = null,
) : SkillDefinition {

    override val id = "speech_generation"
    override val name = "generate_speech"
    override val description =
        "将文本合成为自然语音音频（TTS）并保存到会话工作区。支持默认语音模型或显式指定已启用的云端语音模型（如 Gemini TTS、MiniMax Speech），" +
            "支持覆盖音色、语气风格指导，以及 Gemini TTS 多说话人对话配音。"
    override val mcpServerId: String? = null
    override val isAvailable: Boolean
        get() = providerManager.speechModelId.value.isNotBlank() ||
            providerManager.providerModels.value.any { it.isSupportedCloudSpeechModel() }
    override val risk = ToolRisk.EXTERNAL_WRITE

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "text": {
                    "type": "string",
                    "description": "必填，1–20000 字符。需要朗读或配音的正文文本；多说话人模式可用「角色名: 台词」分行编排。"
                },
                "voice": {
                    "type": "string",
                    "description": "可选。覆盖模型默认音色，如 Gemini 预设声线 Kore/Puck/Zephyr/Aoede/Charon/Fenrir 或 MiniMax 音色 ID。"
                },
                "speakers": {
                    "type": "array",
                    "description": "可选。多说话人角色与音色映射（需至少 2 位，供 Gemini TTS 多角色对白使用）。",
                    "items": {
                        "type": "object",
                        "properties": {
                            "speaker": { "type": "string", "description": "对话文本中的角色名" },
                            "voice": { "type": "string", "description": "对应预置音色名" }
                        },
                        "required": ["speaker", "voice"],
                        "additionalProperties": false
                    }
                },
                "style": {
                    "type": "string",
                    "description": "可选。语速、语气、情绪或旁白表演指导（如「温柔缓慢地朗读」）。"
                },
                "model_id": {
                    "type": "string",
                    "description": "可选。指定已启用的云端语音模型（模型 ID 或名称）；省略时使用默认语音模型。"
                },
                "output_format": {
                    "type": "string",
                    "enum": ["wav", "mp3", "flac"],
                    "description": "可选。期望输出音频格式（以模型接口实际支持为准）。"
                },
                "yield_after_seconds": {"type":"integer","description":"默认 25 秒让出；0 不让出。"},
                "run_in_background": {"type":"boolean","description":"是否立即转后台执行，默认 false。"},
                "on_exit": {"type":"string","enum":["none","notify","wake"],"description":"后台结束处理，默认 wake。"},
                "max_runtime_minutes": {"type":"integer","description":"最长运行 1–60 分钟，默认 10。"}
            },
            "required": ["text"],
            "additionalProperties": false
        }
    """.trimIndent()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val parsed = try {
            parseSpeechArgs(args)
        } catch (invalid: IllegalArgumentException) {
            return ToolResult(id = id, content = invalid.message ?: "语音参数无效。", status = "error")
        }
        return runMediaJob(id, args, context, jobs, 25, "wake", "run_in_background", "合成语音") { output ->
            executeSpeech(parsed, context, output)
        }
    }

    private suspend fun executeSpeech(
        req: ParsedSpeechArgs,
        context: SkillExecutionContext,
        output: ToolLiveOutputSink,
    ): ToolResult {
        val resultId = "tts_${UUID.randomUUID().toString().take(8)}"
        val resolved = resolveCloudSpeechModel(providerManager, cloudModels, req.modelId)
            ?: return ToolResult(
                id = resultId,
                content = "未找到可用的云端语音模型。请在「设置 → 默认模型」选择默认语音模型，或在提供商模型列表中启用受支持的语音模型（如 Gemini TTS / MiniMax Speech），或使用 ComfyUI 语音工作流（comfy_list_workflows）。",
                status = "error",
            )
        val (modelInfo, cloudModel) = resolved
        output.emit(ToolOutputStream.STDOUT, "正在使用 ${modelInfo.name} 合成语音（${req.text.length} 字）\n")
        return try {
            val synthesized = backend().synthesizeSpeechDirect(
                model = cloudModel,
                text = req.text,
                voiceOverride = req.voice,
                speakers = req.speakers,
                styleInstruction = req.style,
                formatOverride = req.outputFormat,
            )
            val processed = trimWavSilence(synthesized.bytes)
            val store = mediaStore ?: throw WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用，音频未保存")
            val savedRef = withContext(Dispatchers.IO) {
                store.save(
                    sessionId = context.sessionId,
                    payload = MediaPayload.Bytes(processed.bytes),
                    origin = MediaOrigin.GENERATED,
                    mimeTypeHint = synthesized.mimeType,
                    suggestedName = "speech.${synthesized.extension}",
                )
            }
            output.emit(ToolOutputStream.STDOUT, "已保存语音：${savedRef.path}\n")
            val measuredSeconds = processed.durationSeconds
                ?: estimateAudioDurationSeconds(processed.bytes)
                ?: savedRef.durationMs?.takeIf { it > 0 }?.div(1000.0)
            val durationNote = measuredSeconds?.let { sec ->
                val base = "，时长约 ${String.format(Locale.US, "%.1f", sec)}s"
                if (processed.trimmed && processed.originalDurationSeconds != null) {
                    "$base（已裁剪首尾多余静音，原长约 ${String.format(Locale.US, "%.1f", processed.originalDurationSeconds)}s）"
                } else {
                    base
                }
            }.orEmpty()
            ToolResult(
                id = resultId,
                content = buildString {
                    appendLine("${savedRef.path}（${synthesized.mimeType}，${savedRef.sizeBytes} 字节$durationNote，模型：${modelInfo.name}）")
                    appendLine("回复中可用 [${modelInfo.name} 配音](${savedRef.path}) 引用此音频供用户播放。")
                }.trimEnd(),
                status = "success",
                media = listOf(savedRef),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolResult(
                id = resultId,
                content = "语音合成失败：${e.message ?: "未知错误"}",
                status = "error",
            )
        }
    }

    private data class ParsedSpeechArgs(
        val text: String,
        val voice: String?,
        val speakers: List<SpeechSpeakerVoice>,
        val style: String?,
        val modelId: String?,
        val outputFormat: String?,
    )

    private fun parseSpeechArgs(args: JsonObject): ParsedSpeechArgs {
        val allowed = setOf(
            "text", "voice", "speakers", "style", "model_id", "output_format",
            "yield_after_seconds", "run_in_background", "on_exit", "max_runtime_minutes",
        )
        val unknown = args.keys - allowed
        require(unknown.isEmpty()) { "generate_speech 含未知参数：${unknown.joinToString()}" }
        val text = (args["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        require(text.isNotEmpty() && text.length <= 20_000) { "text 必须是 1～20000 字符的非空文本。" }
        val voice = (args["voice"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val style = (args["style"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val modelId = (args["model_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        val outputFormat = (args["output_format"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        require(outputFormat == null || outputFormat in setOf("wav", "mp3", "flac")) { "output_format 仅支持 wav、mp3 或 flac。" }
        val speakers = (args["speakers"] as? JsonArray)?.map { el ->
            val obj = el as? JsonObject ?: throw IllegalArgumentException("speakers 元素必须是对象。")
            val sp = (obj["speaker"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            val vc = (obj["voice"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            require(sp.isNotEmpty() && vc.isNotEmpty()) { "speakers 每项必须包含非空 speaker 和 voice。" }
            SpeechSpeakerVoice(sp, vc)
        }.orEmpty()
        if (speakers.isNotEmpty()) {
            require(speakers.size in 2..8) { "speakers 多说话人配置需提供 2～8 位角色。" }
        }
        return ParsedSpeechArgs(text, voice, speakers, style, modelId, outputFormat)
    }
}

private val SUPPORTED_SPEECH_APIS = setOf(
    CloudMediaApi.GEMINI_TTS,
    CloudMediaApi.MINIMAX_SPEECH,
    CloudMediaApi.OPENAI_SPEECH,
)

internal fun ModelInfo.isSupportedCloudSpeechModel(): Boolean {
    if (!enabled || providerId.isNullOrBlank()) return false
    val settings = effectiveMediaSettings() ?: return false
    return settings.group == MediaGroup.SPEECH && settings.api in SUPPORTED_SPEECH_APIS
}

internal fun resolveCloudSpeechModel(
    providerManager: ProviderManager,
    cloudModels: CloudMediaModelSource,
    requestedModelId: String?,
): Pair<ModelInfo, CloudMediaModel>? {
    val all = providerManager.providerModels.value
    val target = if (!requestedModelId.isNullOrBlank()) {
        val needle = requestedModelId.trim()
        all.firstOrNull {
            it.isSupportedCloudSpeechModel() && (
                it.id == needle ||
                    it.remoteModelId.equals(needle, ignoreCase = true) ||
                    it.name.equals(needle, ignoreCase = true)
                )
        }
    } else {
        val defaultId = providerManager.speechModelId.value.trim()
        defaultId.takeIf { it.isNotEmpty() }?.let { id ->
            all.firstOrNull { it.id == id && it.isSupportedCloudSpeechModel() }
        } ?: all.firstOrNull { it.isSupportedCloudSpeechModel() }
    } ?: return null
    val cloud = cloudModels.find("cloud:${target.id}") ?: return null
    return target to cloud
}

/** 标准 16-bit PCM WAV 首尾静音裁剪结果。 */
internal data class WavTrimResult(
    val bytes: ByteArray,
    val durationSeconds: Double?,
    val trimmed: Boolean,
    val originalDurationSeconds: Double? = durationSeconds,
)

private data class ParsedWavHeader(
    val audioFormat: Int,
    val channels: Int,
    val sampleRate: Int,
    val byteRate: Int,
    val bitsPerSample: Int,
    val pcmOffset: Int,
    val pcmLength: Int,
)

/**
 * 解析标准 RIFF/WAVE 16-bit PCM 音频，计算实际时长，并安全裁剪首尾过长的近静音片段。
 * 仅当检测到有效人声且被裁静音总计超过 [minTrimSilenceMs]（默认 400ms）时才重封装 WAV，
 * 两端各保留 [padMs]（默认 150ms）自然余量；非 WAV、全静音或紧密录音均原样透传。
 */
internal fun trimWavSilence(
    bytes: ByteArray,
    padMs: Int = 150,
    thresholdDb: Double = -45.0,
    minTrimSilenceMs: Double = 400.0,
): WavTrimResult {
    val header = parseWavHeader(bytes) ?: return WavTrimResult(bytes, durationSeconds = null, trimmed = false)
    if (header.audioFormat != 1 || header.bitsPerSample != 16 || header.channels !in 1..8 || header.sampleRate !in 8000..192000) {
        val fallbackDuration = if (header.byteRate > 0) header.pcmLength.toDouble() / header.byteRate.toDouble() else null
        return WavTrimResult(bytes, durationSeconds = fallbackDuration, trimmed = false)
    }

    val frameBytes = header.channels * 2
    val totalFrames = header.pcmLength / frameBytes
    if (totalFrames <= 0) {
        return WavTrimResult(bytes, durationSeconds = 0.0, trimmed = false)
    }
    val originalDuration = totalFrames.toDouble() / header.sampleRate.toDouble()

    // 以 10ms 为窗口计算均方根能量，避免偶发单采样脉冲干扰静音检测。
    val windowFrames = (header.sampleRate / 100).coerceAtLeast(1)
    val windowCount = (totalFrames + windowFrames - 1) / windowFrames
    val rmsThreshold = 32768.0 * 10.0.pow(thresholdDb / 20.0)
    val thresholdSq = rmsThreshold * rmsThreshold

    var firstActiveWindow = -1
    var lastActiveWindow = -1
    for (w in 0 until windowCount) {
        val startFrame = w * windowFrames
        val endFrame = min(totalFrames, startFrame + windowFrames)
        var sumSq = 0.0
        var sampleCount = 0
        var bytePos = header.pcmOffset + startFrame * frameBytes
        val byteEnd = header.pcmOffset + endFrame * frameBytes
        while (bytePos + 1 < byteEnd) {
            val sample = (bytes[bytePos].toInt() and 0xFF) or (bytes[bytePos + 1].toInt() shl 8)
            val v = sample.toDouble()
            sumSq += v * v
            sampleCount++
            bytePos += 2
        }
        if (sampleCount > 0 && (sumSq / sampleCount.toDouble()) >= thresholdSq) {
            if (firstActiveWindow < 0) firstActiveWindow = w
            lastActiveWindow = w
        }
    }

    // 全静音音频原样保留，避免产出空文件。
    if (firstActiveWindow < 0) {
        return WavTrimResult(bytes, durationSeconds = originalDuration, trimmed = false)
    }

    val padFrames = ((header.sampleRate.toLong() * padMs.coerceAtLeast(0)) / 1000L).toInt()
    val firstSpeechFrame = firstActiveWindow * windowFrames
    val lastSpeechEndFrame = min(totalFrames, (lastActiveWindow + 1) * windowFrames)
    val keepStartFrame = (firstSpeechFrame - padFrames).coerceAtLeast(0)
    val keepEndFrame = (lastSpeechEndFrame + padFrames).coerceAtMost(totalFrames)
    val trimmedFrames = keepStartFrame + (totalFrames - keepEndFrame)
    val trimmedMs = trimmedFrames.toDouble() * 1000.0 / header.sampleRate.toDouble()

    if (trimmedMs <= minTrimSilenceMs || keepEndFrame <= keepStartFrame) {
        return WavTrimResult(bytes, durationSeconds = originalDuration, trimmed = false)
    }

    val startByte = header.pcmOffset + keepStartFrame * frameBytes
    val endByte = header.pcmOffset + keepEndFrame * frameBytes
    val trimmedPcm = bytes.copyOfRange(startByte, endByte)
    val trimmedWav = wrapPcmAsWav(
        pcmBytes = trimmedPcm,
        sampleRate = header.sampleRate,
        channels = header.channels,
        bitsPerSample = 16,
    )
    val newDuration = (keepEndFrame - keepStartFrame).toDouble() / header.sampleRate.toDouble()
    return WavTrimResult(
        bytes = trimmedWav,
        durationSeconds = newDuration,
        trimmed = true,
        originalDurationSeconds = originalDuration,
    )
}

/** 纯 JVM 估算 WAV 或 MP3 字节流时长（秒）；非受支持或损坏数据时返回 null，绝不抛异常。 */
internal fun estimateAudioDurationSeconds(bytes: ByteArray): Double? {
    measureWavDurationSeconds(bytes)?.let { return it }
    return estimateMp3DurationSeconds(bytes)
}

internal fun measureWavDurationSeconds(bytes: ByteArray): Double? {
    val header = parseWavHeader(bytes) ?: return null
    val bytesPerSecond = if (header.byteRate > 0) {
        header.byteRate.toDouble()
    } else if (header.sampleRate > 0 && header.channels > 0 && header.bitsPerSample > 0) {
        header.sampleRate.toDouble() * header.channels * (header.bitsPerSample / 8.0)
    } else {
        return null
    }
    if (bytesPerSecond <= 0.0) return null
    return header.pcmLength.toDouble() / bytesPerSecond
}

private fun parseWavHeader(bytes: ByteArray): ParsedWavHeader? {
    if (bytes.size < 44) return null
    if (!matchesAscii(bytes, 0, "RIFF") || !matchesAscii(bytes, 8, "WAVE")) return null

    var audioFormat = -1
    var channels = -1
    var sampleRate = -1
    var byteRate = -1
    var bitsPerSample = -1
    var pcmOffset = -1
    var pcmLength = -1

    var offset = 12
    while (offset + 8 <= bytes.size) {
        val chunkSize = readLeUInt(bytes, offset + 4)
        val dataOffset = offset + 8
        if (chunkSize < 0L || dataOffset > bytes.size) break
        val available = min(chunkSize, (bytes.size - dataOffset).toLong()).toInt()
        when {
            matchesAscii(bytes, offset, "fmt ") && available >= 16 -> {
                audioFormat = readLeUShort(bytes, dataOffset)
                channels = readLeUShort(bytes, dataOffset + 2)
                sampleRate = readLeUInt(bytes, dataOffset + 4).toInt()
                byteRate = readLeUInt(bytes, dataOffset + 8).toInt()
                bitsPerSample = readLeUShort(bytes, dataOffset + 14)
            }
            matchesAscii(bytes, offset, "data") -> {
                pcmOffset = dataOffset
                pcmLength = available
            }
        }
        val padded = chunkSize + (chunkSize and 1L)
        if (padded < 0L || dataOffset.toLong() + padded > bytes.size.toLong()) break
        offset = (dataOffset.toLong() + padded).toInt()
    }
    if (audioFormat < 0 || pcmOffset < 0 || pcmLength < 0) return null
    return ParsedWavHeader(audioFormat, channels, sampleRate, byteRate, bitsPerSample, pcmOffset, pcmLength)
}

private val MPEG1_BITRATES_L1 = intArrayOf(0, 32, 64, 96, 128, 160, 192, 224, 256, 288, 320, 352, 384, 416, 448, 0)
private val MPEG1_BITRATES_L2 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 0)
private val MPEG1_BITRATES_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
private val MPEG2_BITRATES_L1 = intArrayOf(0, 32, 48, 56, 64, 80, 96, 112, 128, 144, 160, 176, 192, 224, 256, 0)
private val MPEG2_BITRATES_L23 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
private val MPEG1_SAMPLE_RATES = intArrayOf(44100, 48000, 32000)
private val MPEG2_SAMPLE_RATES = intArrayOf(22050, 24000, 16000)
private val MPEG25_SAMPLE_RATES = intArrayOf(11025, 12000, 8000)

private data class Mp3FrameHeader(
    val versionBits: Int,
    val layerBits: Int,
    val sampleRate: Int,
    val samplesPerFrame: Int,
    val frameSize: Int,
    val isMono: Boolean,
)

private fun parseMp3FrameHeader(bytes: ByteArray, offset: Int): Mp3FrameHeader? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    val b0 = bytes[offset].toInt() and 0xFF
    val b1 = bytes[offset + 1].toInt() and 0xFF
    if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) return null
    val versionBits = (b1 ushr 3) and 0x03
    val layerBits = (b1 ushr 1) and 0x03
    if (versionBits == 1 || layerBits == 0) return null

    val b2 = bytes[offset + 2].toInt() and 0xFF
    val bitrateIdx = (b2 ushr 4) and 0x0F
    val sampleRateIdx = (b2 ushr 2) and 0x03
    val padding = (b2 ushr 1) and 0x01
    if (bitrateIdx !in 1..14 || sampleRateIdx !in 0..2) return null

    val sampleRate = when (versionBits) {
        3 -> MPEG1_SAMPLE_RATES[sampleRateIdx]
        2 -> MPEG2_SAMPLE_RATES[sampleRateIdx]
        else -> MPEG25_SAMPLE_RATES[sampleRateIdx]
    }
    val bitrateKbps = when {
        versionBits == 3 && layerBits == 3 -> MPEG1_BITRATES_L1[bitrateIdx]
        versionBits == 3 && layerBits == 2 -> MPEG1_BITRATES_L2[bitrateIdx]
        versionBits == 3 && layerBits == 1 -> MPEG1_BITRATES_L3[bitrateIdx]
        layerBits == 3 -> MPEG2_BITRATES_L1[bitrateIdx]
        else -> MPEG2_BITRATES_L23[bitrateIdx]
    }
    if (bitrateKbps <= 0) return null

    val samplesPerFrame = when {
        layerBits == 3 -> 384
        layerBits == 2 -> 1152
        versionBits == 3 -> 1152
        else -> 576
    }
    val bitrateBps = bitrateKbps * 1000
    val frameSize = if (layerBits == 3) {
        ((12 * bitrateBps) / sampleRate + padding) * 4
    } else {
        ((samplesPerFrame / 8) * bitrateBps) / sampleRate + padding
    }
    if (frameSize < 4) return null

    val b3 = bytes[offset + 3].toInt() and 0xFF
    val isMono = ((b3 ushr 6) and 0x03) == 3
    return Mp3FrameHeader(versionBits, layerBits, sampleRate, samplesPerFrame, frameSize, isMono)
}

private fun estimateMp3DurationSeconds(bytes: ByteArray): Double? {
    if (bytes.size < 4) return null
    var start = 0
    // 跳过可选的 ID3v2 标签头。
    if (bytes.size >= 10 && matchesAscii(bytes, 0, "ID3")) {
        val s0 = bytes[6].toInt() and 0xFF
        val s1 = bytes[7].toInt() and 0xFF
        val s2 = bytes[8].toInt() and 0xFF
        val s3 = bytes[9].toInt() and 0xFF
        if ((s0 or s1 or s2 or s3) and 0x80 == 0) {
            val tagSize = (s0 shl 21) or (s1 shl 14) or (s2 shl 7) or s3
            val footer = if ((bytes[5].toInt() and 0x10) != 0) 10 else 0
            start = 10 + tagSize + footer
        }
    }
    if (start >= bytes.size - 3) return null

    val searchLimit = min(bytes.size - 4, start + 65_536)
    var pos = start
    var firstHeader: Mp3FrameHeader? = null
    while (pos <= searchLimit) {
        val candidate = parseMp3FrameHeader(bytes, pos)
        if (candidate != null) {
            // 仅剩单帧时要求帧长度完整落在缓冲内，避免把随机 0xFFE 误判为 MP3。
            if (pos + candidate.frameSize <= bytes.size) {
                firstHeader = candidate
                break
            }
        }
        pos++
    }
    val first = firstHeader ?: return null

    // 若首帧带 Xing / Info / VBRI VBR 头，直接按总帧数计算精确时长。
    if (first.layerBits == 1) {
        val sideInfo = if (first.versionBits == 3) {
            if (first.isMono) 17 else 32
        } else {
            if (first.isMono) 9 else 17
        }
        val xingOffset = pos + 4 + sideInfo
        val frameEnd = min(bytes.size, pos + first.frameSize)
        if (xingOffset + 12 <= frameEnd &&
            (matchesAscii(bytes, xingOffset, "Xing") || matchesAscii(bytes, xingOffset, "Info"))
        ) {
            val flags = readBeInt(bytes, xingOffset + 4)
            if ((flags and 0x01) != 0) {
                val totalFrames = readBeInt(bytes, xingOffset + 8)
                if (totalFrames > 0) {
                    return totalFrames.toDouble() * first.samplesPerFrame.toDouble() / first.sampleRate.toDouble()
                }
            }
        }
        val vbriOffset = pos + 4 + 32
        if (vbriOffset + 18 <= frameEnd && matchesAscii(bytes, vbriOffset, "VBRI")) {
            val totalFrames = readBeInt(bytes, vbriOffset + 14)
            if (totalFrames > 0) {
                return totalFrames.toDouble() * first.samplesPerFrame.toDouble() / first.sampleRate.toDouble()
            }
        }
    }

    var totalSeconds = 0.0
    var frameCount = 0
    while (pos + 4 <= bytes.size) {
        val frame = parseMp3FrameHeader(bytes, pos) ?: break
        if (pos + frame.frameSize > bytes.size) break
        totalSeconds += frame.samplesPerFrame.toDouble() / frame.sampleRate.toDouble()
        frameCount++
        pos += frame.frameSize
    }
    return totalSeconds.takeIf { frameCount > 0 && it > 0.0 }
}

private fun matchesAscii(bytes: ByteArray, offset: Int, ascii: String): Boolean {
    if (offset < 0 || offset + ascii.length > bytes.size) return false
    for (i in ascii.indices) {
        if (bytes[offset + i] != ascii[i].code.toByte()) return false
    }
    return true
}

private fun readLeUShort(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)

private fun readLeUInt(bytes: ByteArray, offset: Int): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)

private fun readBeInt(bytes: ByteArray, offset: Int): Int =
    ((bytes[offset].toInt() and 0xFF) shl 24) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
        (bytes[offset + 3].toInt() and 0xFF)
