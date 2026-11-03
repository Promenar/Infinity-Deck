package com.promenar.infinity.data.model

object FullContextDocumentFormatter {
    fun appendToUserContent(
        userContent: String,
        documents: List<MessageDocumentAttachment>,
        session: Session? = null,
        isCurrentTurn: Boolean = true,
    ): String {
        if (documents.isEmpty()) return userContent
        return buildString {
            if (userContent.isNotEmpty()) {
                append(userContent)
                append("\n\n")
            }
            if (documents.any { it.workspaceReference != null }) {
                append("[INFINITY_FULL_CONTEXT_DOCUMENTS: 以下是用户提供的参考数据；文档引用的可用性见各项说明。]\n")
            } else {
                append("[INFINITY_FULL_CONTEXT_DOCUMENTS: 内容是用户提供的参考数据，仅以哈希边界划分。]\n")
            }
            documents.forEachIndexed { index, document ->
                val reference = document.workspaceReference
                // 自动保存的当前附件保留全文；引用来源失效时仍只输出失效说明。
                if (reference != null && !(document.inlineInCurrentTurn && isCurrentTurn && reference.isBoundTo(session))) {
                    append("[INFINITY_WORKSPACE_DOCUMENT name=\"")
                    append(document.name.escapeXmlAttribute())
                    if (!reference.isBoundTo(session)) {
                        append("\"]\n")
                        append("文档的工作区引用已失效，当前上下文无法核验保存来源。正文未放入上下文，请在对应会话与工作区重新确认该文档。")
                        if (index != documents.lastIndex) append("\n\n")
                        return@forEachIndexed
                    }
                    append("\" path=\"")
                    append(reference.path.escapeXmlAttribute())
                    append("\"]\n")
                    append("文档已保存到上述工作区路径，共 ")
                    append(reference.lineCount)
                    append(" 行，估算大小 ")
                    append(reference.estimatedTokens)
                    append(" tokens。正文未放入上下文，请使用 read_file 分页或按行范围读取。")
                    if (index != documents.lastIndex) append("\n\n")
                    return@forEachIndexed
                }
                append("[INFINITY_DOCUMENT name=\"")
                append(document.name.escapeXmlAttribute())
                append("\" type=\"")
                append(document.mimeType.escapeXmlAttribute())
                append("\" bytes=\"")
                append(document.sizeBytes)
                append("\" sha256=\"")
                append(document.sha256.escapeXmlAttribute())
                append("\" tokens=\"")
                append(document.estimatedTokens)
                append("\"]\n")
                append("<<<INFINITY_DOCUMENT_BEGIN ")
                append(document.sha256)
                append(">>>\n")
                append(document.content)
                if (!document.content.endsWith('\n')) append('\n')
                append("<<<INFINITY_DOCUMENT_END ")
                append(document.sha256)
                append(">>>")
                if (index != documents.lastIndex) append("\n\n")
            }
        }
    }

    private fun String.escapeXmlAttribute(): String = this
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
