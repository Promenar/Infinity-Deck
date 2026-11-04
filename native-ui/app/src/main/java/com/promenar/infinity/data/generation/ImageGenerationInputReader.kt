package com.promenar.infinity.data.generation

import com.promenar.infinity.data.media.MediaTypeSniffer
import com.promenar.infinity.data.rag.ImageGenInput
import com.promenar.infinity.domain.media.*
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

data class ImageGenerationInputs(val images: List<ImageGenInput>, val mask: ImageGenInput?, val paths: List<String>, val maskPath: String?, val markup: ImageGenInput? = null, val markupPath: String? = null)

/** 与 view_media 共用解析器及安全打开逻辑，字节读取单独执行 Images API 的上限。 */
class ImageGenerationInputReader(private val resolver: WorkspaceMediaResolver) {
    suspend fun read(sessionId: String, paths: List<String>, mask: String?, markup: String? = null): ImageGenerationInputs = withContext(Dispatchers.IO) {
        require(paths.size in 1..16) { "images 必须包含 1–16 张 PNG/WebP/JPEG 图片。" }
        require(mask == null || markup == null) { "markup 与 mask 不能同时提供。" }
        suspend fun load(path: String, index: Int, layer: String? = null): Pair<ImageGenInput, MediaRef> {
            val resolved = resolver.resolve(sessionId, path)
            require(resolved is MediaResolution.Available) { "$path：工作区图片路径无效、越界或不可读（${(resolved as? MediaResolution.Unavailable)?.reason}）。" }
            val limit = (if (layer != null) 4 else 50) * 1024 * 1024
            val limitText = if (layer != null) "$layer 必须小于 4 MiB" else "每张图片必须小于 50 MiB"
            require(resolved.ref.kind == MediaKind.IMAGE) { "$path：只允许 PNG/WebP/JPEG 图片。" }
            require(resolved.ref.sizeBytes < limit) { "$path：$limitText。" }
            val bytes = resolver.open(resolved).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size()))
                    if (count < 0) break
                    require(output.size() + count < limit) { "$path：$limitText。" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            // 只按内容检查格式，防止把改扩展名的非图片上传给服务商。
            val mime = MediaTypeSniffer.sniff(bytes.copyOf(minOf(bytes.size, 512)), null).mimeType
            require(mime in setOf("image/png", "image/webp", "image/jpeg")) { "$path：只允许 PNG/WebP/JPEG 图片。" }
            require(layer == null || mime == "image/png") { "$layer 必须为 PNG 图片，且小于 4 MiB。" }
            val extension = MediaTypeSniffer.extension(mime, null)
            return ImageGenInput(bytes, mime, if (layer != null) "$layer.png" else "image-$index.$extension") to resolved.ref
        }
        val loaded = paths.mapIndexed { index, path -> load(path, index + 1) }
        val loadedMask = mask?.let { load(it, 0, "mask") }
        val loadedMarkup = markup?.let { load(it, 0, "markup") }
        for ((name, layer) in listOf("mask" to loadedMask, "markup" to loadedMarkup)) {
            if (layer == null) continue
            val first = loaded.first().second
            val ref = layer.second
            require(first.width == null || first.height == null || ref.width == null || ref.height == null ||
                first.width == ref.width && first.height == ref.height) { "$name 尺寸必须与 images 第一张图片相同。" }
            require(name != "markup" || first.width != null && first.height != null && ref.width != null && ref.height != null) {
                "无法确认 markup 与 images 第一张图片的尺寸。"
            }
        }
        ImageGenerationInputs(loaded.map { it.first }, loadedMask?.first, loaded.map { it.second.path }, loadedMask?.second?.path,
            loadedMarkup?.first, loadedMarkup?.second?.path)
    }
}

/** 格式提示按响应、请求、字节顺序选取；存储层还会以文件头复核 MIME 与扩展名。 */
fun imageGenerationOutputMime(actualFormat: String?, requestedFormat: String?, bytes: ByteArray): String? {
    fun mime(format: String?) = when (format) { "png" -> "image/png"; "jpeg" -> "image/jpeg"; "webp" -> "image/webp"; else -> null }
    return mime(actualFormat) ?: mime(requestedFormat)
        ?: MediaTypeSniffer.sniff(bytes.copyOf(minOf(bytes.size, 512)), null).mimeType.takeIf { it.startsWith("image/") }
}
