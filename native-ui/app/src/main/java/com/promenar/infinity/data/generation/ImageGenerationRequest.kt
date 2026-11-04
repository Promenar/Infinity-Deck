package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.ImageGenerationRequested
import kotlinx.serialization.json.*

data class ImageGenerationRequest(
    val prompt: String,
    val requested: ImageGenerationRequested,
    val images: List<String>,
    val mask: String?,
    val inputFidelity: String?,
    val markup: String? = null,
)

/** 保留未指定参数的空值，交给所选模型使用默认值。 */
fun parseImageGenerationRequest(args: JsonObject): ImageGenerationRequest {
    fun string(key: String): String? {
        if (key !in args) return null
        val value = args[key] as? JsonPrimitive
        require(value != null && value.isString) { "$key 必须是字符串。" }
        return value.content
    }
    fun choice(key: String, allowed: List<String>): String? {
        if (key !in args) return null
        val value = args[key] as? JsonPrimitive
        require(value != null && value.isString && value.content in allowed) { "$key 允许值：${allowed.joinToString("、")}。" }
        return value.content
    }
    fun integer(key: String, range: IntRange): Int? {
        if (key !in args) return null
        val value = args[key] as? JsonPrimitive
        val number = value?.takeUnless { it.isString }?.intOrNull
        require(number != null && number in range) { "$key 必须是 ${range.first}–${range.last} 的整数。" }
        return number
    }
    val prompt = string("prompt")
    require(!prompt.isNullOrBlank() && prompt.length <= 32000) { "prompt 必填，须为非空字符串，最多 32000 字符。" }
    val size = string("size")?.also { value ->
        val dimensions = Regex("([0-9]+)x([0-9]+)").matchEntire(value)
        val width = dimensions?.groupValues?.get(1)?.toLongOrNull()
        val height = dimensions?.groupValues?.get(2)?.toLongOrNull()
        require(value == "auto" || width != null && height != null && width in 16L..3840L && height in 16L..3840L &&
            width % 16 == 0L && height % 16 == 0L && width <= height * 3 && height <= width * 3 && width * height <= 3840L * 2160) {
            "size 允许 auto、1024x1024、1536x1024、1024x1536 或 WIDTHxHEIGHT；宽高须为正的 16 倍数，宽高比 1:3–3:1，最长边 ≤3840，总像素 ≤3840×2160。"
        }
    }
    val quality = choice("quality", listOf("auto", "low", "medium", "high", "xhigh", "max"))
    val background = choice("background", listOf("transparent", "opaque", "auto"))
    val format = choice("output_format", listOf("png", "jpeg", "webp"))
    val compression = integer("output_compression", 0..100)
    require(background != "transparent" || format != "jpeg") { "background=transparent 时 output_format 允许 png/webp。" }
    require(compression == null || format in setOf("jpeg", "webp")) { "output_compression 仅用于 output_format=jpeg/webp，允许 0–100。" }
    val n = integer("n", 1..4)
    val images = if ("images" in args) {
        val array = args["images"] as? JsonArray
        require(array != null && array.size in 1..16) { "images 必须为 1–16 个工作区图片路径的数组。" }
        array.map {
            val path = it as? JsonPrimitive
            require(path != null && path.isString && path.content.isNotBlank()) { "images 必须包含 1–16 个非空工作区图片路径字符串。" }
            path.content
        }
    } else emptyList()
    val mask = string("mask")
    val markup = string("markup")
    require(markup == null || markup.isNotBlank() && images.isNotEmpty()) { "markup 必须为非空工作区 PNG 路径，且必须同时提供 images。" }
    require(markup == null || mask == null) { "markup 与 mask 不能同时提供。" }
    val fidelity = choice("input_fidelity", listOf("high", "low"))
    require(mask == null || mask.isNotBlank() && images.isNotEmpty()) { "mask 必须为工作区 PNG 路径，且必须同时提供 images。" }
    require(fidelity == null || images.isNotEmpty()) { "input_fidelity 仅用于编辑，必须同时提供 images；允许 high/low。" }
    return ImageGenerationRequest(prompt, ImageGenerationRequested(size, quality, background, format, compression, n), images, mask, fidelity, markup)
}
