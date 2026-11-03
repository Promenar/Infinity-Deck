package com.promenar.infinity.ui.chat.manager.skills

import android.content.Context
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.rag.ImageGenClient
import com.promenar.infinity.data.remote.DefaultProviderRequestRouter
import com.promenar.infinity.data.remote.ProviderRequestRouter
import com.promenar.infinity.data.remote.ModelRequestRouteException
import com.promenar.infinity.data.remote.requireModelRoute
import com.promenar.infinity.data.remote.requireOpenAiModelRoute
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.model.ImageGenerationResultData
import com.promenar.infinity.data.model.ImageGenerationActual
import com.promenar.infinity.data.model.ImageGenerationUsage
import com.promenar.infinity.data.generation.ImageGenerationInputReader
import com.promenar.infinity.data.generation.imageGenerationOutputMime
import com.promenar.infinity.data.generation.parseImageGenerationRequest
import com.promenar.infinity.domain.tool.ToolRisk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import com.promenar.infinity.domain.media.*
import com.promenar.infinity.data.media.download.WorkspaceMediaDownloader
import java.util.UUID
import kotlinx.serialization.json.JsonObject

/**
 * 图像生成工具。
 *
 * 供 LLM 调用，接收提示词和工作区参考图，调用默认图像模型生成或编辑图片，
 * 把生成结果保存到会话工作区，返回虚拟路径和媒体引用。
 */
class ImageGenerationSkill(
    @Suppress("UNUSED_PARAMETER") appContext: Context,
    private val providerManager: ProviderManager,
    private val mediaStore: WorkspaceMediaStore? = null,
    private val mediaDownloader: WorkspaceMediaDownloader? = null,
    private val providerRequestRouter: ProviderRequestRouter = DefaultProviderRequestRouter(providerManager),
    private val mediaResolver: WorkspaceMediaResolver? = mediaStore as? WorkspaceMediaResolver,
    private val imageGenClientFactory: (baseUrl: String, apiKey: String, modelId: String) -> ImageGenClient =
        { baseUrl, apiKey, modelId -> ImageGenClient(baseUrl = baseUrl, apiKey = apiKey, model = modelId) },
) : SkillDefinition {

    override val id = "image_generation"
    override val name = "generate_image"
    override val description =
        "根据提示词生成图片，或以工作区图片为参考进行编辑。" +
        "提示词应描述主体、动作、环境、构图、光照和风格；images 提供时使用图片编辑接口。"
    override val mcpServerId: String? = null
    override val isAvailable: Boolean get() = providerManager.imageModelId.value.isNotBlank()
    override val risk = ToolRisk.EXTERNAL_WRITE

    override val parametersSchema = """
        {
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "必填，最多 32000 字符。描述目标主体、动作、环境、构图、光照和风格。"
                },
                "size": {
                    "type": "string",
                    "description": "默认 auto；可用 auto、1024x1024、1536x1024、1024x1536。gpt-image-2 系列可用 WIDTHxHEIGHT：宽高为正的 16 倍数，宽高比 1:3–3:1，最长边 ≤3840，总像素 ≤3840×2160；超过 2560×1440 属实验性。其它模型的尺寸支持以服务商为准。"
                },
                "quality": {
                    "type": "string",
                    "enum": ["auto", "low", "medium", "high", "xhigh", "max"],
                    "description": "默认 auto；xhigh/max 仅部分模型支持，如 gpt-image-2.5 系列。"
                },
                "background": {
                    "type": "string",
                    "enum": ["transparent", "opaque", "auto"],
                    "description": "默认 auto；transparent 需要 png 或 webp 输出。"
                },
                "output_format": {
                    "type": "string",
                    "enum": ["png", "jpeg", "webp"],
                    "description": "输出格式，默认 png。"
                },
                "output_compression": {
                    "type": "integer",
                    "description": "仅用于 jpeg/webp 输出，压缩参数范围 0–100。"
                },
                "n": {
                    "type": "integer",
                    "description": "生成数量，默认 1；移动端每次最多 4 张。"
                },
                "images": {
                    "type": "array",
                    "items": {"type": "string"},
                    "description": "1–16 个工作区图片路径。提供时使用编辑接口；每张必须为 PNG/WebP/JPEG，且小于 50 MiB。"
                },
                "mask": {
                    "type": "string",
                    "description": "工作区 PNG 蒙版路径，小于 4 MiB，尺寸与 images 第一张相同；仅与 images 同用，透明区域表示编辑范围。"
                },
                "input_fidelity": {
                    "type": "string", "enum": ["high", "low"],
                    "description": "仅编辑且仅部分模型支持。默认不发送；gpt-image-2 应省略。"
                }
            },
            "required": ["prompt"]
        }
    """.trimIndent()

    override suspend fun execute(
        args: JsonObject,
        context: SkillExecutionContext
    ): ToolResult {
        val resultId = "img_${UUID.randomUUID().toString().take(8)}"

        val request = try {
            parseImageGenerationRequest(args)
        } catch (invalid: IllegalArgumentException) {
            return ToolResult(id = resultId, content = invalid.message ?: "图片参数无效。", status = "error")
        }

        // ── 读取图像模型配置 ──
        val modelId = providerManager.imageModelId.value.takeIf { it.isNotBlank() } ?: ""

        if (modelId.isBlank()) {
            return ToolResult(id = resultId, content = "Image generation not configured: no image model selected. Please select an image model in Settings → Default Image Model.", status = "error")
        }

        return try {
            val (remoteModelId, inputs, result) = withContext(Dispatchers.IO) {
                val inputs = if (request.images.isEmpty()) null else ImageGenerationInputReader(
                    mediaResolver ?: throw WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用，无法读取输入图片")
                ).read(context.sessionId, request.images, request.mask)
                val resolved = providerRequestRouter.requireModelRoute(modelId).requireOpenAiModelRoute()
                val client = imageGenClientFactory(resolved.config.baseUrl, resolved.config.apiKey, resolved.remoteModelId)
                val options = request.requested
                val result = if (inputs == null) client.generate(
                    prompt = request.prompt, size = options.size, quality = options.quality, background = options.background,
                    outputFormat = options.outputFormat, outputCompression = options.outputCompression, n = options.n,
                ) else client.edit(
                    prompt = request.prompt, images = inputs.images, mask = inputs.mask, inputFidelity = request.inputFidelity,
                    size = options.size, quality = options.quality, background = options.background,
                    outputFormat = options.outputFormat, outputCompression = options.outputCompression, n = options.n,
                )
                Triple(resolved.remoteModelId, inputs, result)
            }

            val saved = mutableListOf<MediaRef>()
            val failures = mutableListOf<String>()
            val revised = mutableListOf<String>()
            withContext(Dispatchers.IO) {
                for ((index, image) in result.images.withIndex()) {
                    image.revisedPrompt?.takeIf(String::isNotBlank)?.let(revised::add)
                    try {
                        val store = mediaStore ?: throw WorkspaceMediaException("WORKSPACE_UNAVAILABLE", "当前会话工作区不可用，图片未保存")
                        val ref = when {
                            image.url != null -> {
                                val downloader = mediaDownloader ?: throw WorkspaceMediaException("DOWNLOAD_UNAVAILABLE", "图片下载服务不可用")
                                downloader.save(image.url, context.sessionId, store, MediaOrigin.GENERATED,
                                    maxBytes = MAX_WORKSPACE_MEDIA_BYTES, imageOnly = true)
                            }
                            image.b64Json != null -> {
                                val bytes = decodeMediaBase64(image.b64Json)
                                store.save(context.sessionId, MediaPayload.Bytes(bytes), MediaOrigin.GENERATED,
                                    imageGenerationOutputMime(result.outputFormat, request.requested.outputFormat, bytes))
                            }
                            else -> throw WorkspaceMediaException("IMAGE_MISSING", "生图结果没有 URL 或 base64 图片")
                        }
                        saved += ref
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        failures += "第 ${index + 1} 张：${mediaFailureDescription(failure)}"
                    }
                }
            }
            if (result.images.isEmpty()) failures += "服务端未返回图片。"
            ToolResult(
                id = resultId,
                content = buildString {
                    saved.forEach {
                        appendLine("${it.path}（${if (it.width != null && it.height != null) "${it.width}×${it.height}" else "像素尺寸未知"}，${it.sizeBytes} 字节）")
                    }
                    if (saved.isNotEmpty()) appendLine("回复中用 ![说明](工作区路径) 引用图片。")
                    failures.forEach { appendLine("保存失败：$it") }
                    revised.distinct().forEach { appendLine("模型修订提示词：$it") }
                }.trimEnd(),
                status = if (saved.isEmpty()) "error" else "success",
                media = saved.takeIf { it.isNotEmpty() },
                data = ImageGenerationResultData(
                    mode = if (inputs == null) "generate" else "edit", model = remoteModelId,
                    requested = request.requested,
                    actual = ImageGenerationActual(result.size, result.quality, result.background, result.outputFormat),
                    inputImages = inputs?.paths.orEmpty(), mask = inputs?.maskPath,
                    usage = result.usage?.let { ImageGenerationUsage(it.inputTokens, it.outputTokens, it.totalTokens) },
                    revisedPrompts = revised.distinct(), failedCount = failures.size,
                ).encode(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: ModelRequestRouteException) {
            ToolResult(id = resultId, content = "Image generation unavailable: ${failure.message} Select an image model in Settings → Default Image Model.", status = "error")
        } catch (e: Exception) {
            ToolResult(
                id = resultId,
                // 客户端的 HTTP 失败已带“Image generation failed”前缀，不再重复包裹。
                content = e.message?.takeIf { it.startsWith("Image generation failed") }
                    ?: "Image generation failed: ${e.message ?: "Unknown error"}",
                status = "error"
            )
        }
    }

}
