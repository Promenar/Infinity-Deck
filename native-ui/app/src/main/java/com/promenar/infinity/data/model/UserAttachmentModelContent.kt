package com.promenar.infinity.data.model

import com.promenar.infinity.data.remote.protocol.ImageInput

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
            if (isCurrentTurn || reference == null) {
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

    private fun String.escapeAttribute(): String = replace("&", "&amp;").replace("\"", "&quot;")
        .replace("<", "&lt;").replace(">", "&gt;")
}
