package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.mediagen.cloud.CloudMediaInputSource
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModel
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModelSource
import com.promenar.infinity.data.mediagen.cloud.GeminiMediaGenerationBackend
import com.promenar.infinity.data.model.CloudMediaApi
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.domain.media.MediaOrigin
import com.promenar.infinity.domain.media.MediaPayload
import com.promenar.infinity.domain.media.WorkspaceMediaException
import com.promenar.infinity.domain.media.WorkspaceMediaStore
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.InputKind
import com.promenar.infinity.domain.mediagen.InputRole
import com.promenar.infinity.domain.mediagen.MediaGenerationRequest
import com.promenar.infinity.domain.mediagen.MediaGroup
import com.promenar.infinity.domain.mediagen.MediaInput
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * 音乐生成内置工具：支持 Google AI Studio Lyria 3 / 3.5（纯文本或带 1–4 张参考图看图配乐）。
 * 产物保存至会话工作区，返回音频虚拟路径、媒体引用及模型生成的歌词/曲式说明。
 */
class MusicGenerationSkill(
    private val providerManager: ProviderManager,
    private val cloudModels: CloudMediaModelSource,
    private val inputSource: CloudMediaInputSource,
    private val backend: () -> GeminiMediaGenerationBackend,
    private val mediaStore: WorkspaceMediaStore? = null,
    private val jobs: com.promenar.infinity.data.jobs.BackgroundJobManager? = null,
) : SkillDefinition {

    override val id = "music_generation"
    override val name = "generate_music"
    override val description =
        "根据曲风、情绪、乐器、节奏或歌词描述生成音乐/配乐（支持 Google AI Studio Lyria 3 / 3.5 及可选 1–4 张工作区参考图看图配乐），" +
            "保存到会话工作区并返回播放路径与歌词结构。"
    override val mcpServerId: String? = null
    override val isAvailable: Boolean
        get() = providerManager.musicModelId.value.isNotBlank() ||
            providerManager.providerModels.value.any { it.isSupportedCloudMusicModel() }
    override val risk = ToolRisk.EXTERNAL_WRITE

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "必填，1–12000 字符。描述风格、乐器编配、情绪起伏、节拍（BPM）、人声或纯音乐（instrumental）及可选歌词。"
                },
                "images": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "可选，1–4 个工作区图片路径，供 Lyria 3 / 3.5 参考画面氛围与情绪进行配乐。"
                },
                "duration_seconds": {
                    "type": "integer",
                    "description": "可选，期望曲目时长参考（5–300 秒）。注意：仅作为曲式结构的软提示，Lyria 3.5 / Lyria 3 Pro 始终生成约 2–3 分钟的完整曲目，Lyria 3 Clip 约 30 秒片段；如需精确片段时长请在剪辑或后处理阶段裁剪。"
                },
                "model_id": {
                    "type": "string",
                    "description": "可选。指定已启用的云端音乐模型（如 lyria-3.5 / lyria-3-clip-preview）；省略时使用默认音乐模型。"
                },
                "yield_after_seconds": {"type":"integer","description":"默认 30 秒让出；0 不让出。"},
                "run_in_background": {"type":"boolean","description":"是否立即转后台执行，默认 false。"},
                "on_exit": {"type":"string","enum":["none","notify","wake"],"description":"后台结束处理，默认 wake。"},
                "max_runtime_minutes": {"type":"integer","description":"最长运行 1–60 分钟，默认 10。"}
            },
            "required": ["prompt"],
            "additionalProperties": false
        }
    """.trimIndent()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val parsed = try {
            parseMusicArgs(args)
        } catch (invalid: IllegalArgumentException) {
            return ToolResult(id = id, content = invalid.message ?: "音乐参数无效。", status = "error")
        }
        return runMediaJob(id, args, context, jobs, 30, "wake", "run_in_background", "生成音乐") { output ->
            executeMusic(parsed, context, output)
        }
    }

    private suspend fun executeMusic(
        req: ParsedMusicArgs,
        context: SkillExecutionContext,
        output: ToolLiveOutputSink,
    ): ToolResult {
        val resultId = "music_${UUID.randomUUID().toString().take(8)}"
        val resolved = resolveCloudMusicModel(providerManager, cloudModels, req.modelId)
            ?: return ToolResult(
                id = resultId,
                content = "未找到可用的云端音乐模型。请在「设置 → 默认模型」选择默认音乐模型，或在提供商模型列表中启用 Lyria 3 / 3.5，或使用 ComfyUI 音乐工作流（comfy_list_workflows）。",
                status = "error",
            )
        val (modelInfo, cloudModel) = resolved
        output.emit(ToolOutputStream.STDOUT, "正在使用 ${modelInfo.name} 生成音乐\n")
        return try {
            val inlineImages = if (req.images.isEmpty()) {
                emptyList()
            } else {
                val dummyReq = MediaGenerationRequest(
                    channelId = "cloud:${modelInfo.id}",
                    mode = GenerationMode.MUSIC,
                    prompt = req.prompt,
                    inputs = req.images.map { MediaInput(it, InputKind.IMAGE, InputRole.REFERENCE) },
                    sourceRef = resultId,
                    sessionId = context.sessionId,
                )
                req.images.map { path ->
                    inputSource.remoteLocation(dummyReq, MediaInput(path, InputKind.IMAGE, InputRole.REFERENCE))
                }
            }
            val synthesized = backend().synthesizeMusicDirect(
                model = cloudModel,
                prompt = req.prompt,
                inlineImageDataUrls = inlineImages,
                durationSeconds = req.durationSeconds,
            )
            val store = mediaStore ?: throw WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用，音乐未保存")
            val savedRef = withContext(Dispatchers.IO) {
                store.save(
                    sessionId = context.sessionId,
                    payload = MediaPayload.Bytes(synthesized.bytes),
                    origin = MediaOrigin.GENERATED,
                    mimeTypeHint = synthesized.mimeType,
                    suggestedName = "music.${synthesized.extension}",
                )
            }
            output.emit(ToolOutputStream.STDOUT, "已保存音乐：${savedRef.path}\n")
            val measuredSeconds = estimateAudioDurationSeconds(synthesized.bytes)
                ?: savedRef.durationMs?.takeIf { it > 0 }?.div(1000.0)
            val durationNote = measuredSeconds?.let { sec ->
                "，时长约 ${String.format(Locale.US, "%.1f", sec)}s"
            }.orEmpty()
            ToolResult(
                id = resultId,
                content = buildString {
                    appendLine("${savedRef.path}（${synthesized.mimeType}，${savedRef.sizeBytes} 字节$durationNote，模型：${modelInfo.name}）")
                    appendLine("回复中可用 [${modelInfo.name} 配乐](${savedRef.path}) 引用此音频供用户播放。")
                    if (req.durationSeconds != null) {
                        val actualPart = measuredSeconds?.let {
                            "实际生成音频时长约 ${String.format(Locale.US, "%.1f", it)}s；"
                        }.orEmpty()
                        appendLine("提示：${actualPart}duration_seconds（${req.durationSeconds}s）仅为曲式软提示（Lyria 3.5 Pro 始终输出完整曲目），如需精确片段时长请在剪辑或后处理阶段裁剪。")
                    }
                    synthesized.transcriptText?.let { lyrics ->
                        appendLine()
                        appendLine("生成歌词与曲式：")
                        appendLine(lyrics)
                    }
                }.trimEnd(),
                status = "success",
                media = listOf(savedRef),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            ToolResult(
                id = resultId,
                content = "音乐生成失败：${e.message ?: "未知错误"}",
                status = "error",
            )
        }
    }

    private data class ParsedMusicArgs(
        val prompt: String,
        val images: List<String>,
        val durationSeconds: Int?,
        val modelId: String?,
    )

    private fun parseMusicArgs(args: JsonObject): ParsedMusicArgs {
        val allowed = setOf(
            "prompt", "images", "duration_seconds", "model_id",
            "yield_after_seconds", "run_in_background", "on_exit", "max_runtime_minutes",
        )
        val unknown = args.keys - allowed
        require(unknown.isEmpty()) { "generate_music 含未知参数：${unknown.joinToString()}" }
        val prompt = (args["prompt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
        require(prompt.isNotEmpty() && prompt.length <= 12_000) { "prompt 必须是 1～12000 字符的非空描述。" }
        val images = (args["images"] as? JsonArray)?.map { el ->
            (el as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw IllegalArgumentException("images 必须是工作区图片路径字符串数组。")
        }.orEmpty()
        require(images.size <= 4) { "images 最多提供 4 张参考图。" }
        val duration = args["duration_seconds"]?.let { el ->
            (el as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
                ?.takeIf { it in 5..300 }
                ?: throw IllegalArgumentException("duration_seconds 需为 5～300 之间的整数。")
        }
        val modelId = (args["model_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
        return ParsedMusicArgs(prompt, images, duration, modelId)
    }
}

private val SUPPORTED_MUSIC_APIS = setOf(CloudMediaApi.GEMINI_LYRIA)

internal fun ModelInfo.isSupportedCloudMusicModel(): Boolean {
    if (!enabled || providerId.isNullOrBlank()) return false
    val settings = effectiveMediaSettings() ?: return false
    return settings.group == MediaGroup.MUSIC && settings.api in SUPPORTED_MUSIC_APIS
}

internal fun resolveCloudMusicModel(
    providerManager: ProviderManager,
    cloudModels: CloudMediaModelSource,
    requestedModelId: String?,
): Pair<ModelInfo, CloudMediaModel>? {
    val all = providerManager.providerModels.value
    val target = if (!requestedModelId.isNullOrBlank()) {
        val needle = requestedModelId.trim()
        all.firstOrNull {
            it.isSupportedCloudMusicModel() && (
                it.id == needle ||
                    it.remoteModelId.equals(needle, ignoreCase = true) ||
                    it.name.equals(needle, ignoreCase = true)
                )
        }
    } else {
        val defaultId = providerManager.musicModelId.value.trim()
        defaultId.takeIf { it.isNotEmpty() }?.let { id ->
            all.firstOrNull { it.id == id && it.isSupportedCloudMusicModel() }
        } ?: all.firstOrNull { it.isSupportedCloudMusicModel() }
    } ?: return null
    val cloud = cloudModels.find("cloud:${target.id}") ?: return null
    return target to cloud
}
