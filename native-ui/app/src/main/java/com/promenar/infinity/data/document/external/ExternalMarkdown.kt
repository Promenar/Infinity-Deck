package com.promenar.infinity.data.document.external

/** 外部应用交来的 Markdown 文件识别：声明类型或文件名二者之一能确认即可。 */
object ExternalMarkdown {
    const val MARKDOWN_MIME = "text/markdown"
    val MIME_TYPES = setOf(MARKDOWN_MIME, "text/x-markdown")
    private val EXTENSIONS = listOf(".md", ".markdown")

    /** 没有可信类型时（通配、二进制流或纯文本）才允许只凭文件名判断。 */
    private val GENERIC_MIME_TYPES = setOf("*/*", "application/octet-stream", "text/plain", "text/*")

    fun normalizeMime(mimeType: String?): String? =
        mimeType?.substringBefore(';')?.trim()?.lowercase()?.takeIf(String::isNotEmpty)

    fun hasMarkdownName(name: String?): Boolean {
        val candidate = name?.substringAfterLast('/')?.trim() ?: return false
        return EXTENSIONS.any { candidate.endsWith(it, ignoreCase = true) && candidate.length > it.length }
    }

    /**
     * 打开或单个分享的流是否按 Markdown 处理：类型为 Markdown；或类型缺失、通用，且路径或文件名以 .md、.markdown 结尾。
     * 明确声明为其它类型（例如 text/html、application/pdf）时即使扩展名像 Markdown 也不接管。
     */
    fun accepts(mimeType: String?, pathOrName: String?): Boolean {
        val mime = normalizeMime(mimeType)
        if (mime in MIME_TYPES) return true
        return (mime == null || mime in GENERIC_MIME_TYPES) && hasMarkdownName(pathOrName)
    }

    /** 文件名无 Markdown 扩展名时补上 .md，让暂存副本、另存为与存入资料都按 Markdown 处理。 */
    fun ensureMarkdownName(name: String): String = if (hasMarkdownName(name)) name else "$name.md"
}
