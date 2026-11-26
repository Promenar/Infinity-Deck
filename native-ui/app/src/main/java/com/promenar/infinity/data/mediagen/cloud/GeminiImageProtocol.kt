package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.rag.GeneratedImage
import com.promenar.infinity.data.rag.ImageGenInput
import com.promenar.infinity.data.rag.ImageGenResult
import com.promenar.infinity.data.rag.ImageGenUsage
import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.ResolvedProviderModel
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.httpErrorSummary
import com.promenar.infinity.data.remote.protocol.GeminiProtocol
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.remote.protocol.ProviderEndpointResolver
import com.promenar.infinity.data.remote.protocol.ProviderEndpointTarget
import com.promenar.infinity.data.remote.provider.ParsedVertexCredential
import com.promenar.infinity.data.remote.provider.VertexCredentialException
import com.promenar.infinity.data.remote.provider.VertexCredentialFailure
import com.promenar.infinity.data.remote.provider.VertexCredentialParser
import com.promenar.infinity.data.remote.routeViaBuiltInProxy
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import java.util.Base64
import kotlin.math.abs
import kotlin.math.ln
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

private val GEMINI_IMAGE_SIZE_REGEX = Regex("""^(\d+)[xX](\d+)$""")

private val GEMINI_IMAGE_ASPECT_RATIOS: List<Pair<String, Double>> = listOf(
    "1:1" to 1.0,
    "3:2" to 3.0 / 2.0,
    "2:3" to 2.0 / 3.0,
    "4:3" to 4.0 / 3.0,
    "3:4" to 3.0 / 4.0,
    "5:4" to 5.0 / 4.0,
    "4:5" to 4.0 / 5.0,
    "16:9" to 16.0 / 9.0,
    "9:16" to 9.0 / 16.0,
    "21:9" to 21.0 / 9.0,
)

private val GEMINI_BLOCKED_FINISH_REASONS = setOf(
    "SAFETY",
    "IMAGE_SAFETY",
    "PROHIBITED_CONTENT",
    "IMAGE_PROHIBITED_CONTENT",
    "RECITATION",
    "IMAGE_RECITATION",
    "BLOCKLIST",
    "SPII",
    "NO_IMAGE",
)

/**
 * 将 `generate_image` 的 `size` 参数（如 `1024x1024`、`1536x1024`、`1920x1080`）映射为
 * Google Gemini 原生生图支持的 `imageConfig.aspectRatio`；`null` 或 `auto` 返回 `null`（省略 `imageConfig`）。
 */
fun geminiImageAspectRatio(size: String?): String? {
    val clean = size?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (clean.equals("auto", ignoreCase = true)) return null
    GEMINI_IMAGE_ASPECT_RATIOS.firstOrNull { (label, _) -> label == clean }?.let { return it.first }
    val match = GEMINI_IMAGE_SIZE_REGEX.matchEntire(clean) ?: return null
    val width = match.groupValues[1].toLongOrNull() ?: return null
    val height = match.groupValues[2].toLongOrNull() ?: return null
    if (width <= 0L || height <= 0L) return null
    val targetRatio = width.toDouble() / height.toDouble()
    return GEMINI_IMAGE_ASPECT_RATIOS.minByOrNull { (_, candidateRatio) ->
        abs(ln(targetRatio) - ln(candidateRatio))
    }?.first
}

/**
 * 构造 Google Gemini / Vertex AI 原生图像生成与编辑（`:generateContent`）请求体。
 * 支持纯文生图、多图参考编辑，以及将 mask/markup 附带为额外内联图片并在提示词中说明用途。
 */
fun geminiImageGenerateContentBody(
    prompt: String,
    images: List<ImageGenInput> = emptyList(),
    mask: ImageGenInput? = null,
    markup: ImageGenInput? = null,
    size: String? = null,
): JsonObject {
    val cleanPrompt = prompt.trim()
    require(cleanPrompt.isNotEmpty()) { "Prompt must not be blank" }
    require(mask == null || markup == null) { "markup 与 mask 不能同时提供。" }
    require((mask == null && markup == null) || images.isNotEmpty()) { "mask 或 markup 必须同时提供 images。" }

    val effectivePrompt = buildString {
        append(cleanPrompt)
        if (mask != null) {
            append("\n\n[Edit mask: The final attached PNG image is an edit mask matching the first reference image. Modify only the transparent regions of the mask and preserve the opaque regions unchanged.]")
        }
        if (markup != null) {
            append("\n\n[Visual markup: The final attached transparent PNG image contains visual annotations overlaid on the first reference image. Follow those annotations when editing.]")
        }
    }

    val aspectRatio = geminiImageAspectRatio(size)
    return buildJsonObject {
        put("contents", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("parts", buildJsonArray {
                    add(buildJsonObject { put("text", effectivePrompt) })
                    images.forEach { input -> add(inlineImagePart(input)) }
                    mask?.let { add(inlineImagePart(it)) }
                    markup?.let { add(inlineImagePart(it)) }
                })
            })
        })
        put("generationConfig", buildJsonObject {
            put("responseModalities", buildJsonArray {
                add(JsonPrimitive("IMAGE"))
                add(JsonPrimitive("TEXT"))
            })
            aspectRatio?.let { ratio ->
                put("imageConfig", buildJsonObject {
                    put("aspectRatio", ratio)
                })
            }
        })
    }
}

private fun inlineImagePart(input: ImageGenInput): JsonObject = buildJsonObject {
    put("inlineData", buildJsonObject {
        put("mimeType", input.mimeType)
        put("data", Base64.getEncoder().encodeToString(input.bytes))
    })
}

/**
 * 解析 Gemini / Vertex AI `:generateContent` 图像响应：
 * - 提取 `candidates[].content.parts[]` 中所有非思考阶段（`thought != true`）的图片内联数据（`image/` 前缀 MIME）；
 * - 汇总文本说明写入 `revisedPrompt`，并解析 `usageMetadata` 中的输入/输出/总 token 数。
 */
fun parseGeminiImageResponse(response: JsonObject): ImageGenResult {
    val blockReason = (response["promptFeedback"] as? JsonObject)?.string("blockReason")
    val candidates = response["candidates"] as? JsonArray
    if (candidates == null) {
        if (blockReason != null) {
            throw IllegalStateException("Image generation failed: Gemini blocked the request ($blockReason)")
        }
        throw IllegalStateException("Missing 'candidates' array in Gemini image generation response")
    }

    val rawImages = mutableListOf<Pair<String, String>>()
    val textParts = mutableListOf<String>()
    var notableFinishReason: String? = null

    candidates.forEach { candEl ->
        val cand = candEl as? JsonObject ?: return@forEach
        val finishReason = cand.string("finishReason")
        if (finishReason != null && finishReason.uppercase() in GEMINI_BLOCKED_FINISH_REASONS && notableFinishReason == null) {
            notableFinishReason = finishReason
        }
        val parts = (cand["content"] as? JsonObject)?.get("parts") as? JsonArray ?: return@forEach
        parts.forEach { partEl ->
            val part = partEl as? JsonObject ?: return@forEach
            // 跳过 Gemini 思考模型的中间草稿或思维链分片，只保留最终输出。
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) return@forEach

            part.string("text")?.trim()?.takeIf(String::isNotEmpty)?.let(textParts::add)

            val inline = (part["inlineData"] ?: part["inline_data"]) as? JsonObject ?: return@forEach
            val mime = (inline.string("mimeType") ?: inline.string("mime_type"))?.trim()
            val rawData = inline.string("data")?.trim()?.replace("\n", "")?.replace("\r", "")
            if (!rawData.isNullOrEmpty() && mime != null && mime.startsWith("image/", ignoreCase = true)) {
                rawImages += rawData to mime
            }
        }
    }

    val combinedText = textParts.distinct().joinToString("\n").takeIf { it.isNotBlank() }
    if (rawImages.isEmpty() && combinedText == null) {
        if (blockReason != null) {
            throw IllegalStateException("Image generation failed: Gemini blocked the request ($blockReason)")
        }
        if (notableFinishReason != null) {
            throw IllegalStateException("Image generation failed: Gemini stopped without image ($notableFinishReason)")
        }
    }

    val images = rawImages.map { (data, _) ->
        GeneratedImage(b64Json = data, revisedPrompt = combinedText)
    }
    val formats = rawImages.mapNotNull { (_, mime) -> imageMimeToOutputFormat(mime) }.distinct()
    val outputFormat = formats.singleOrNull()
    val usage = parseGeminiImageUsage(response)

    return ImageGenResult(
        images = images,
        outputFormat = outputFormat,
        usage = usage,
    )
}

private fun imageMimeToOutputFormat(mime: String): String? = when (mime.substringBefore(';').trim().lowercase()) {
    "image/png" -> "png"
    "image/jpeg", "image/jpg" -> "jpeg"
    "image/webp" -> "webp"
    else -> null
}

private fun parseGeminiImageUsage(response: JsonObject): ImageGenUsage? {
    val meta = response["usageMetadata"] as? JsonObject ?: return null
    val promptTokens = (meta["promptTokenCount"] as? JsonPrimitive)?.longOrNull
    val candidatesTokens = (meta["candidatesTokenCount"] as? JsonPrimitive)?.longOrNull
    val totalTokens = (meta["totalTokenCount"] as? JsonPrimitive)?.longOrNull
    if (promptTokens == null && candidatesTokens == null && totalTokens == null) return null
    return ImageGenUsage(
        inputTokens = promptTokens,
        outputTokens = candidatesTokens,
        totalTokens = totalTokens,
    )
}

/**
 * Google Gemini / Vertex AI 原生图像生成与编辑客户端（`:generateContent`）。
 * 复用 [ModelHttp]、内置代理选路、图像超时与 [ProviderEndpointResolver]，支持 Nano Banana 等原生多模态生图模型。
 */
class GeminiImageClient(
    private val route: ResolvedProviderModel,
    httpClient: HttpClient = ModelHttp.client {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
        }
    },
    private val responseBodyReader: suspend (HttpResponse) -> String = { response -> response.bodyAsText() },
) {
    private val httpClient = httpClient.withModelTimeoutSupport()
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var cachedVertexToken: String? = null
    @Volatile private var vertexTokenExpiryMs: Long = 0L

    /** 执行原生图像生成或基于工作区参考图的编辑；`n > 1` 且单次返回不足时按需追加请求（上限 4 张）。 */
    suspend fun generateOrEdit(
        prompt: String,
        images: List<ImageGenInput> = emptyList(),
        mask: ImageGenInput? = null,
        markup: ImageGenInput? = null,
        size: String? = null,
        n: Int? = null,
    ): ImageGenResult {
        val requestBody = geminiImageGenerateContentBody(
            prompt = prompt,
            images = images,
            mask = mask,
            markup = markup,
            size = size,
        )
        val encodedBody = json.encodeToString(JsonObject.serializer(), requestBody)
        val targetCount = (n ?: 1).coerceIn(1, 4)

        val first = sendOnce(encodedBody)
        if (targetCount <= 1 || first.images.isEmpty() || first.images.size >= targetCount) {
            return first
        }

        val collectedImages = first.images.toMutableList()
        val collectedFormats = mutableSetOf<String>()
        first.outputFormat?.let(collectedFormats::add)
        var mergedUsage = first.usage

        for (attempt in 1 until targetCount) {
            if (collectedImages.size >= targetCount) break
            val next = try {
                sendOnce(encodedBody)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 已有成功图片时保留已产出结果，避免丢弃前面已计费的图像。
                break
            }
            if (next.images.isEmpty()) break
            collectedImages += next.images
            next.outputFormat?.let(collectedFormats::add)
            mergedUsage = mergeImageUsage(mergedUsage, next.usage)
        }

        return ImageGenResult(
            images = collectedImages.take(targetCount),
            outputFormat = collectedFormats.singleOrNull(),
            usage = mergedUsage,
        )
    }

    private suspend fun sendOnce(encodedBody: String): ImageGenResult {
        val response = when (route.config.protocolType) {
            ProtocolType.Google_Gemini -> sendGeminiRequest(encodedBody)
            ProtocolType.Google_VertexAI -> sendVertexRequest(encodedBody)
            else -> throw IllegalStateException("Unsupported Gemini image protocol: ${route.config.protocolType}")
        }
        if (!response.status.isSuccess()) {
            val errorBody = readErrorBody(response)
            val summary = httpErrorSummary(response.status.value, errorBody)
            throw IllegalStateException("Image generation failed (${summary.head})" + summary.detail?.let { ": $it" }.orEmpty())
        }
        val responseText = responseBodyReader(response)
        val parsed = try {
            json.parseToJsonElement(responseText).jsonObject
        } catch (_: Exception) {
            throw IllegalStateException("Image generation failed: invalid JSON response from Gemini")
        }
        return parseGeminiImageResponse(parsed)
    }

    private suspend fun sendGeminiRequest(encodedBody: String): HttpResponse {
        require(route.config.apiKey.isNotBlank()) { "Image generation API key not configured" }
        val endpoint = ProviderEndpointResolver.resolve(
            protocol = ProtocolType.Google_Gemini,
            configuredBaseUrl = route.config.baseUrl,
            target = ProviderEndpointTarget.GeminiInference(model = route.remoteModelId, streaming = false),
        )
        return httpClient.post(endpoint) {
            routeViaBuiltInProxy(route.config.useBuiltInProxy, route.providerId)
            applyModelTimeout(ModelTimeoutKind.IMAGE)
            contentType(ContentType.Application.Json)
            header(GeminiProtocol.API_KEY_HEADER, route.config.apiKey)
            setBody(encodedBody)
        }
    }

    private suspend fun sendVertexRequest(encodedBody: String): HttpResponse {
        val credential = try {
            VertexCredentialParser.parse(route.config.serviceAccountJson, route.config.projectId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw IllegalStateException("Image generation failed: Vertex AI authentication error (${safeVertexError(error)})")
        }
        val endpoint = ProviderEndpointResolver.resolve(
            protocol = ProtocolType.Google_VertexAI,
            configuredBaseUrl = route.config.baseUrl,
            target = ProviderEndpointTarget.VertexInference(
                projectId = credential.projectId,
                location = route.config.location,
                model = route.remoteModelId,
                streaming = false,
            ),
        )
        val token = getVertexAccessToken(credential)
        return httpClient.post(endpoint) {
            routeViaBuiltInProxy(route.config.useBuiltInProxy, route.providerId)
            applyModelTimeout(ModelTimeoutKind.IMAGE)
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Authorization, "Bearer $token")
            setBody(encodedBody)
        }
    }

    private suspend fun getVertexAccessToken(credential: ParsedVertexCredential): String {
        cachedVertexToken?.let { token ->
            if (System.currentTimeMillis() < vertexTokenExpiryMs - 300_000L) return token
        }
        val jwt = VertexCredentialParser.createJwtAssertion(credential)
        val tokenEndpoint = ProviderEndpointResolver.resolve(
            protocol = ProtocolType.Google_VertexAI,
            configuredBaseUrl = route.config.baseUrl,
            target = ProviderEndpointTarget.VertexOAuthToken,
        )
        val response = httpClient.post(tokenEndpoint) {
            routeViaBuiltInProxy(route.config.useBuiltInProxy, route.providerId)
            applyModelTimeout(ModelTimeoutKind.IMAGE)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion=$jwt")
        }
        val body = readErrorBody(response)
        if (!response.status.isSuccess()) {
            val summary = httpErrorSummary(response.status.value, body)
            throw IllegalStateException("Image generation failed (${summary.head}): Vertex token exchange failed")
        }
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw IllegalStateException("Image generation failed: Vertex token exchange response is invalid")
        val accessToken = parsed["access_token"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
            ?: throw IllegalStateException("Image generation failed: Vertex token exchange response is invalid")
        val expiresIn = parsed["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
        cachedVertexToken = accessToken
        vertexTokenExpiryMs = System.currentTimeMillis() + expiresIn * 1000L
        return accessToken
    }

    private suspend fun readErrorBody(response: HttpResponse): String = try {
        responseBodyReader(response)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ""
    }

    private fun safeVertexError(error: Exception): String = when ((error as? VertexCredentialException)?.reason) {
        VertexCredentialFailure.MISSING_CREDENTIALS -> "Service account credentials are missing"
        VertexCredentialFailure.INVALID_JSON -> "Service account credentials are invalid"
        VertexCredentialFailure.MISSING_PROJECT_ID,
        VertexCredentialFailure.MISSING_CLIENT_EMAIL,
        VertexCredentialFailure.MISSING_PRIVATE_KEY,
        VertexCredentialFailure.PROJECT_ID_MISMATCH,
        -> "Service account credentials are incomplete"
        VertexCredentialFailure.INVALID_PRIVATE_KEY -> "Service account private key is invalid"
        null -> "Vertex authentication failed"
    }

    private fun mergeImageUsage(a: ImageGenUsage?, b: ImageGenUsage?): ImageGenUsage? {
        if (a == null) return b
        if (b == null) return a
        fun sum(x: Long?, y: Long?): Long? = when {
            x != null && y != null -> x + y
            else -> x ?: y
        }
        return ImageGenUsage(
            inputTokens = sum(a.inputTokens, b.inputTokens),
            outputTokens = sum(a.outputTokens, b.outputTokens),
            totalTokens = sum(a.totalTokens, b.totalTokens),
        )
    }
}
