package com.promenar.infinity.data.model

import com.promenar.infinity.data.remote.protocol.ImageInput
import com.promenar.infinity.domain.media.ImageAnnotation
import com.promenar.infinity.domain.media.ImageAnnotationKind

/** 纯格式化：附件引用只核验逻辑绑定，不读取文件，也不改变持久消息。 */
object UserAttachmentModelContent {
    data class Result(val content: String, val imageUrls: List<ImageInput>?)

    fun format(message: Message, session: Session?, isCurrentTurn: Boolean): Result {
        val content = FullContextDocumentFormatter.appendToUserContent(
            MessageQuoteFormatter.formatUserContent(message.content, message.quotes),
            message.userDocuments.orEmpty(), session, isCurrentTurn,
        )
        val notes = mutableListOf<String>()
        val images = message.userImages.orEmpty().mapIndexedNotNull { index, dataUrl ->
            val reference = message.userImageReferences?.getOrNull(index)
            val image = if (isCurrentTurn || reference == null) {
                val base64Index = dataUrl.indexOf("base64,")
                val mimeEnd = dataUrl.indexOf(';')
                ImageInput(
                    url = dataUrl,
                    base64 = if (base64Index >= 0) dataUrl.substring(base64Index + 7) else "",
                    mimeType = if (mimeEnd > 5) dataUrl.substring(5, mimeEnd) else "image/jpeg",
                )
            } else {
                notes += imageReferenceLine(index, reference, session)
                null
            }
            message.userImageAnnotations?.getOrNull(index)?.let {
                val bound = message.userImageAnnotationContext?.matches(session) == true
                notes += if (bound) annotationLine(index, it) else
                    "[INFINITY_IMAGE_ANNOTATION index=\"${index + 1}\"] 画板图层的来源会话或工作区已失效，请在当前会话重新打开画板并确认原图。"
            }
            image
        }.takeIf { it.isNotEmpty() }
        return Result((listOf(content).filter(String::isNotEmpty) + notes).joinToString("\n\n"), images)
    }

    /** 摘要的附件清单独立于正文截断；只收录用户消息中通过来源核验的引用路径。 */
    fun summaryReferences(message: Message, session: Session?): String {
        if (message.role != MessageRole.USER) return ""
        val lines = buildList {
            message.userImageReferences.orEmpty().forEachIndexed { index, reference ->
                if (reference != null) add(if (reference.isBoundTo(session)) {
                    "- 图片 ${index + 1}：${reference.path}；用途：本条用户消息的参考图片，使用 view_media 查看。"
                } else "- 图片 ${index + 1}：工作区引用已失效，请在对应会话与工作区重新确认。")
            }
            message.userDocuments.orEmpty().forEachIndexed { index, document ->
                document.workspaceReference?.let { reference ->
                    add(if (reference.isBoundTo(session)) {
                        "- 文档 ${index + 1}：${reference.path}；用途：本条用户消息的参考文档，使用 read_file 分页或按行范围读取。"
                    } else "- 文档 ${index + 1}：工作区引用已失效，请在对应会话与工作区重新确认。")
                }
            }
        }
        return if (lines.isEmpty()) "" else "[INFINITY_USER_ATTACHMENT_REFERENCES]\n" + lines.joinToString("\n")
    }

    private fun imageReferenceLine(index: Int, reference: WorkspaceDocumentReference, session: Session?): String =
        if (reference.isBoundTo(session)) {
            "[INFINITY_WORKSPACE_IMAGE index=\"${index + 1}\" path=\"${reference.path.escapeAttribute()}\"] 图片已保存到上述工作区路径，请使用 view_media 重新查看。"
        } else {
            "[INFINITY_WORKSPACE_IMAGE index=\"${index + 1}\"] 图片的工作区引用已失效，当前上下文无法核验保存来源。请在对应会话与工作区重新确认该图片。"
        }

    private fun annotationLine(index: Int, annotation: ImageAnnotation): String {
        val number = index + 1
        val kind = annotation.kind.name.lowercase()
        val source = if (annotation.kind == ImageAnnotationKind.SKETCH) "" else annotation.sourcePath?.let { " source=\"${it.escapeAttribute()}\"" }.orEmpty()
        val size = "${annotation.width}x${annotation.height}"
        val explanation = when (annotation.kind) {
            ImageAnnotationKind.MARKUP -> "图片 $number 是用户在原图上画的标注预览。改图时 images 第一张填 source，markup 填标注文件，按圈出的位置和文字说明修改，结果中不要出现标注笔画。markup 仅 GPT Image 2.5 系列支持；接口报不支持时去掉 markup，按预览图在提示词里写清修改位置。"
            ImageAnnotationKind.MASK -> "图片 $number 是用户在原图上画的选区预览。改图时 images 第一张填 source，mask 填选区文件（透明处为重绘区域），只改动选区内的内容。"
            ImageAnnotationKind.SKETCH -> "图片 $number 是用户手绘的构图草图。生图时 images 填草图路径，size 填 $size，提示词按草图的构图、主体位置和文字标注生成完整画面，不保留线稿笔触和标注文字。"
        }
        return "[INFINITY_IMAGE_ANNOTATION index=\"$number\" kind=\"$kind\"$source $kind=\"${annotation.layerPath.escapeAttribute()}\" size=\"$size\"] $explanation"
    }

    private fun String.escapeAttribute(): String = replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;").replace("\r", "&#13;").replace("\n", "&#10;")
}
