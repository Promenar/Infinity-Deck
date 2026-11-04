package com.promenar.infinity.data.media.annotation

import com.promenar.infinity.domain.media.ImageAnnotation
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import kotlinx.serialization.json.*

/** 预览 PNG 的私有辅助块；模型图片经 JPEG 编码后不携带编辑状态。 */
object ImageAnnotationPreviewCodec {
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val type = "inAn".toByteArray(Charsets.US_ASCII)
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024

    fun embed(png: ByteArray, annotation: ImageAnnotation, context: ImageAnnotationContext? = null): ByteArray {
        val clean = strip(png)
        val tree = JsonObject(json.encodeToJsonElement(ImageAnnotation.serializer(), annotation).jsonObject +
            if (context == null) emptyMap() else mapOf("infinityContext" to json.encodeToJsonElement(ImageAnnotationContext.serializer(), context)))
        val data = tree.toString().toByteArray(Charsets.UTF_8)
        require(data.size <= MAX_METADATA_BYTES) { "画板编辑数据过大，请减少笔画后保存。" }
        val output = ByteArrayOutputStream(clean.size + data.size + 12)
        output.write(clean, 0, 33)
        DataOutputStream(output).apply {
            writeInt(data.size)
            write(type)
            write(data)
            writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
        }
        output.write(clean, 33, clean.size - 33)
        return output.toByteArray()
    }

    data class Preview(val annotation: ImageAnnotation, val context: ImageAnnotationContext?)

    fun read(bytes: ByteArray): ImageAnnotation? {
        val end = byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, 174.toByte(), 66, 96, 130.toByte())
        if (bytes.size < 45 || !bytes.copyOfRange(bytes.size - 12, bytes.size).contentEquals(end)) return null
        return read(bytes.inputStream())
    }
    fun read(input: InputStream): ImageAnnotation? = readPreview(input)?.annotation

    /** 只读 IHDR 后、首个图像数据块前的元数据，不把完整预览载入内存。流由调用方关闭。 */
    fun readPreview(input: InputStream): Preview? = try {
        val stream = DataInputStream(input)
        val header = ByteArray(8).also(stream::readFully)
        if (!header.contentEquals(signature)) null else {
            if (stream.readInt() != 13 || stream.readInt() != 0x49484452) null else {
                stream.readFully(ByteArray(17))
                val length = stream.readInt()
                val chunkType = ByteArray(4).also(stream::readFully)
                if (!chunkType.contentEquals(type) || length !in 1..MAX_METADATA_BYTES) null else {
                    val data = ByteArray(length).also(stream::readFully)
                    val expected = stream.readInt().toLong() and 0xffffffffL
                    val actual = CRC32().apply { update(type); update(data) }.value
                    if (expected != actual) null else {
                        val tree = json.parseToJsonElement(data.toString(Charsets.UTF_8)).jsonObject
                        Preview(json.decodeFromJsonElement(ImageAnnotation.serializer(), tree),
                            tree["infinityContext"]?.let { json.decodeFromJsonElement(ImageAnnotationContext.serializer(), it) })
                    }
                }
            }
        }
    } catch (_: Exception) { null }

    fun strip(png: ByteArray): ByteArray {
        require(png.size >= 45 && png.copyOfRange(0, 8).contentEquals(signature)) { "画板预览必须为完整 PNG 图片。" }
        val output = ByteArrayOutputStream(png.size)
        output.write(signature)
        var offset = 8
        while (offset <= png.size - 12) {
            val length = ((png[offset].toInt() and 255).toLong() shl 24) or
                ((png[offset + 1].toInt() and 255).toLong() shl 16) or
                ((png[offset + 2].toInt() and 255).toLong() shl 8) or (png[offset + 3].toInt() and 255).toLong()
            require(length <= png.size.toLong() - offset - 12) { "画板 PNG 数据不完整。" }
            val end = offset + length.toInt() + 12
            val name = String(png, offset + 4, 4, Charsets.US_ASCII)
            require(offset != 8 || name == "IHDR" && length == 13L) { "画板 PNG 缺少尺寸信息。" }
            if (name != "inAn") output.write(png, offset, end - offset)
            offset = end
            if (name == "IEND") {
                require(length == 0L && offset == png.size) { "画板 PNG 结束块无效。" }
                return output.toByteArray()
            }
        }
        throw IllegalArgumentException("画板 PNG 缺少结束块。")
    }
}
