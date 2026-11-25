package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModel
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModelSource
import com.promenar.infinity.data.mediagen.cloud.GeminiMediaGenerationBackend
import com.promenar.infinity.data.mediagen.cloud.SpeechSpeakerVoice
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
import java.util.UUID
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
            val store = mediaStore ?: throw WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用，音频未保存")
            val savedRef = withContext(Dispatchers.IO) {
                store.save(
                    sessionId = context.sessionId,
                    payload = MediaPayload.Bytes(synthesized.bytes),
                    origin = MediaOrigin.GENERATED,
                    mimeTypeHint = synthesized.mimeType,
                    suggestedName = "speech.${synthesized.extension}",
                )
            }
            output.emit(ToolOutputStream.STDOUT, "已保存语音：${savedRef.path}\n")
            ToolResult(
                id = resultId,
                content = buildString {
                    appendLine("${savedRef.path}（${synthesized.mimeType}，${savedRef.sizeBytes} 字节，模型：${modelInfo.name}）")
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
