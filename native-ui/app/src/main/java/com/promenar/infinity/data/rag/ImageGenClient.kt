package com.promenar.infinity.data.rag

import com.promenar.infinity.data.remote.ModelHttp
import com.promenar.infinity.data.remote.ModelTimeoutKind
import com.promenar.infinity.data.remote.applyModelTimeout
import com.promenar.infinity.data.remote.withModelTimeoutSupport
import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.*

/**
 * 图像生成 API 客户端。
 * 兼容 OpenAI Images API 的 JSON 生成与 multipart 编辑端点。
 *
 * @param baseUrl  API 基础地址（如 https://api.openai.com）
 * @param apiKey   API 密钥
 * @param model    提供商的远端图像模型 ID
 */
class ImageGenClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    httpClient: HttpClient = ModelHttp.client {
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000  // 图像生成可能较慢
            connectTimeoutMillis = 15_000
            // 上游出图前长时间不回包；不设时沿用 OkHttp 默认 10 秒读超时，十几秒的生图会被切断。
            socketTimeoutMillis = 120_000
        }
    },
    private val responseBodyReader: suspend (HttpResponse) -> String = { response ->
        response.bodyAsText()
    },
) {
    private val httpClient = httpClient.withModelTimeoutSupport()
    private val json = Json { ignoreUnknownKeys = true }

    /** 可选参数只发送调用方显式给出的值，兼容拒绝未知字段的聚合网关。 */
    suspend fun generate(
        prompt: String,
        size: String? = null,
        quality: String? = null,
        background: String? = null,
        outputFormat: String? = null,
        outputCompression: Int? = null,
        n: Int? = null,
    ): ImageGenResult {
        return withModerationFallback { moderation ->
            val requestBody = parameters(prompt, size, quality, background, outputFormat, outputCompression, n, moderation)
            httpClient.post(endpoint("generations")) {
                applyModelTimeout(ModelTimeoutKind.IMAGE)
                contentType(ContentType.Application.Json)
                header("Authorization", "Bearer $apiKey")
                setBody(json.encodeToString(JsonObject.serializer(), requestBody))
            }
        }
    }

    suspend fun edit(
        prompt: String,
        images: List<ImageGenInput>,
        mask: ImageGenInput? = null,
        inputFidelity: String? = null,
        size: String? = null,
        quality: String? = null,
        background: String? = null,
        outputFormat: String? = null,
        outputCompression: Int? = null,
        n: Int? = null,
        markup: ImageGenInput? = null,
    ): ImageGenResult {
        require(markup == null || mask == null) { "markup 与 mask 不能同时提供。" }
        require(markup == null || images.isNotEmpty()) { "markup 必须同时提供 images。" }
        return withModerationFallback { moderation ->
            val fields = parameters(prompt, size, quality, background, outputFormat, outputCompression, n, moderation)
            val body = MultiPartFormDataContent(formData {
                fields.forEach { (key, value) -> append(key, value.jsonPrimitive.content) }
                // 官方指南以重复的 image[] 字段传多图；单图沿用相同字段。
                fun imagePart(key: String, input: ImageGenInput) {
                    append(key, input.bytes, Headers.build {
                        append(HttpHeaders.ContentType, input.mimeType)
                        append(HttpHeaders.ContentDisposition, "filename=\"${input.fileName}\"")
                    })
                }
                images.forEach { imagePart("image[]", it) }
                mask?.let { imagePart("mask", it) }
                markup?.let { imagePart("markup", it.copy(fileName = "markup.png")) }
                inputFidelity?.let { append("input_fidelity", it) }
            })
            httpClient.post(endpoint("edits")) {
                applyModelTimeout(ModelTimeoutKind.IMAGE)
                header("Authorization", "Bearer $apiKey")
                setBody(body)
            }
        }
    }

    /**
     * 审核档位固定取最低档 low（官方默认 auto）；DALL·E 不支持该参数，不发送。
     * 不认识该字段的兼容网关返回 400/422 且错误指向 moderation 时，去掉它重发一次；
     * 内容被安全系统拦截（moderation_blocked）不属于这种情况，照常报错。
     */
    private suspend fun withModerationFallback(send: suspend (moderation: String?) -> HttpResponse): ImageGenResult {
        val moderation = IMAGE_MODERATION_LEVEL.takeIf { imageModelSupportsModeration(model) }
        val first = send(moderation)
        if (moderation == null || first.status.value !in setOf(400, 422)) return readResponse(first)
        val errorBody = readErrorBody(first)
        if (!rejectsModerationParameter(errorBody)) throw failure(first.status.value, errorBody)
        return readResponse(send(null))
    }

    private suspend fun readErrorBody(response: HttpResponse): String = try {
        responseBodyReader(response)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        ""
    }

    private fun failure(status: Int, errorBody: String): IllegalStateException {
        // 按错误体结构提取状态码、错误码与说明，避免硬截断把服务端 JSON 拦腰截断。
        val summary = com.promenar.infinity.data.remote.httpErrorSummary(status, errorBody)
        return IllegalStateException("Image generation failed (${summary.head})" + summary.detail?.let { ": $it" }.orEmpty())
    }

    private fun parameters(prompt: String, size: String?, quality: String?, background: String?, outputFormat: String?, outputCompression: Int?, n: Int?, moderation: String?): JsonObject {
        require(baseUrl.isNotBlank()) { "Image generation base URL not configured" }
        require(apiKey.isNotBlank()) { "Image generation API key not configured" }
        require(prompt.isNotBlank()) { "Prompt must not be blank" }
        return buildJsonObject {
            put("model", model)
            put("prompt", prompt)
            size?.let { put("size", it) }
            quality?.let { put("quality", it) }
            background?.let { put("background", it) }
            outputFormat?.let { put("output_format", it) }
            outputCompression?.let { put("output_compression", it) }
            n?.let { put("n", it) }
            moderation?.let { put("moderation", it) }
        }
    }

    private fun endpoint(action: String): String {
        val cleanBase = baseUrl.trimEnd('/')
        return if (cleanBase.endsWith("/v1")) "$cleanBase/images/$action" else "$cleanBase/v1/images/$action"
    }

    private suspend fun readResponse(response: HttpResponse): ImageGenResult {
        if (!response.status.isSuccess()) throw failure(response.status.value, readErrorBody(response))

        val responseText = responseBodyReader(response)
        val jsonResponse = json.parseToJsonElement(responseText).jsonObject
        val dataArray = jsonResponse["data"]?.jsonArray
            ?: throw IllegalStateException("Missing 'data' array in image generation response")

        val images = dataArray.map { item ->
            val obj = item.jsonObject
            val url = obj["url"]?.jsonPrimitive?.contentOrNull
            val b64 = obj["b64_json"]?.jsonPrimitive?.contentOrNull
            val revisedPrompt = obj["revised_prompt"]?.jsonPrimitive?.contentOrNull
            GeneratedImage(
                url = url,
                b64Json = b64,
                revisedPrompt = revisedPrompt
            )
        }

        return ImageGenResult(
            images = images,
            created = jsonResponse["created"]?.jsonPrimitive?.longOrNull,
            size = jsonResponse["size"]?.jsonPrimitive?.contentOrNull,
            quality = jsonResponse["quality"]?.jsonPrimitive?.contentOrNull,
            background = jsonResponse["background"]?.jsonPrimitive?.contentOrNull,
            outputFormat = jsonResponse["output_format"]?.jsonPrimitive?.contentOrNull,
            usage = jsonResponse["usage"]?.takeUnless { it is JsonNull }?.let { json.decodeFromJsonElement(ImageGenUsage.serializer(), it) },
        )
    }
}

/** 生图审核档位：OpenAI Images API 允许 low / auto，默认 auto；本应用固定取最低档。 */
internal const val IMAGE_MODERATION_LEVEL = "low"

/** DALL·E 系列不支持审核档位；其余（GPT Image 及兼容网关上的同类模型）发送。 */
internal fun imageModelSupportsModeration(model: String): Boolean = !model.contains("dall-e", ignoreCase = true)

/** 错误体指向 moderation 参数本身（未知字段、取值不被接受），而不是内容被安全系统拦截。 */
internal fun rejectsModerationParameter(errorBody: String): Boolean =
    errorBody.contains("moderation", ignoreCase = true) &&
        !errorBody.contains("moderation_blocked", ignoreCase = true) &&
        !errorBody.contains("safety", ignoreCase = true)

/**
 * 单张生成图像的数据。
 * @param url           图像 URL（response_format="url" 时）
 * @param b64Json       Base64 编码图像（response_format="b64_json" 时）
 * @param revisedPrompt 模型改写后的提示词（部分模型支持，如 DALL-E 3）
 */
@Serializable
data class GeneratedImage(
    val url: String? = null,
    val b64Json: String? = null,
    val revisedPrompt: String? = null
)

/**
 * 图像生成结果。
 */
@Serializable
data class ImageGenResult(
    val images: List<GeneratedImage>,
    val created: Long? = null,
    val size: String? = null,
    val quality: String? = null,
    val background: String? = null,
    val outputFormat: String? = null,
    val usage: ImageGenUsage? = null,
)

data class ImageGenInput(val bytes: ByteArray, val mimeType: String, val fileName: String)

@Serializable
data class ImageGenUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("input_tokens_details") val inputTokensDetails: JsonObject? = null,
    @SerialName("output_tokens_details") val outputTokensDetails: JsonObject? = null,
)
