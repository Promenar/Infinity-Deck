package com.promenar.infinity.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 生图卡片的数据契约；图片本身通过 ToolResult.media 传递。 */
@Serializable
data class ImageGenerationResultData(
    val schema: String = SCHEMA,
    val mode: String,
    val model: String,
    val requested: ImageGenerationRequested = ImageGenerationRequested(),
    val actual: ImageGenerationActual = ImageGenerationActual(),
    val inputImages: List<String> = emptyList(),
    val mask: String? = null,
    val usage: ImageGenerationUsage? = null,
    val revisedPrompts: List<String> = emptyList(),
    val failedCount: Int = 0,
    val markup: String? = null,
) {
    fun encode(): String = resultJson.encodeToString(serializer(), this)

    companion object {
        const val SCHEMA = "infinity-image-generation-v1"
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
        private val resultJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        /** 接收 ToolResult.data；旧数据、未知版本与损坏内容交给其它渲染路径。 */
        fun decode(data: String?): ImageGenerationResultData? {
            if (data.isNullOrBlank()) return null
            return runCatching {
                val tree = resultJson.parseToJsonElement(data).jsonObject
                if (tree["schema"]?.jsonPrimitive?.content != SCHEMA) return null
                resultJson.decodeFromJsonElement(serializer(), tree).takeIf { it.mode in setOf("generate", "edit") }
            }.getOrNull()
        }
    }
}

@Serializable
data class ImageGenerationRequested(
    val size: String? = null,
    val quality: String? = null,
    val background: String? = null,
    val outputFormat: String? = null,
    val outputCompression: Int? = null,
    val n: Int? = null,
)

@Serializable
data class ImageGenerationActual(
    val size: String? = null,
    val quality: String? = null,
    val background: String? = null,
    val outputFormat: String? = null,
)

@Serializable
data class ImageGenerationUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val totalTokens: Long? = null,
)
