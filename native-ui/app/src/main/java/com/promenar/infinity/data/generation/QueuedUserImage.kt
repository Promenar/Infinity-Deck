package com.promenar.infinity.data.generation

import com.promenar.infinity.data.media.annotation.ImageAnnotationPreviewCodec
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.media.ImageAnnotation
import com.promenar.infinity.ui.chat.ChatImageEncoder
import java.io.File
import java.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 循环内队列注入与输入框发送共用画板预览编码规则。 */
internal data class QueuedUserImage(val dataUrl: String, val annotation: ImageAnnotation?) {
    companion object {
        suspend fun read(path: String, session: Session? = null): QueuedUserImage = withContext(Dispatchers.IO) {
            val file = File(path)
            val preview = file.inputStream().use { ImageAnnotationPreviewCodec.readPreview(it) }
            require(preview == null || preview.context?.matches(session) == true) { "排队画板所属的会话或工作区已变化，请重新打开画板确认原图。" }
            val annotation = preview?.annotation
            val dataUrl = if (annotation != null) {
                ChatImageEncoder.encodeAnnotatedPreview(file.readBytes()) ?: error("无法读取排队画板预览。")
            } else {
                val bytes = file.readBytes()
                val mime = when {
                    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
                    bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" -> "image/webp"
                    else -> "image/png"
                }
                // 元数据损坏时也剥离私有块，编辑状态只留在本地队列副本。
                val safeBytes = if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(
                    byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10))) ImageAnnotationPreviewCodec.strip(bytes) else bytes
                "data:$mime;base64," + Base64.getEncoder().encodeToString(safeBytes)
            }
            QueuedUserImage(dataUrl, annotation)
        }
    }
}
