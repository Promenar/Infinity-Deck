package com.promenar.infinity.data.generation

import com.promenar.infinity.data.remote.protocol.DocumentInput
import java.util.Base64

/** 有界读取 PDF 明文页树元数据；对象流、加密、远程 URL 或无法确认页数时回退。 */
object PdfInputTokenEstimator {
    // 采用文本高位 3000 token/页并为页面图像留约 1000 token；这是本地预算，不是服务商计费数。
    const val TOKENS_PER_PAGE = 4096
    const val FALLBACK_TOKENS = 32768
    private const val MAX_BYTES = 8 * 1024 * 1024
    private val objects = Regex("(?s)\\b\\d+\\s+\\d+\\s+obj\\b(.*?)\\bendobj\\b")
    private val pageTree = Regex("/Type\\s*/Pages\\b")
    private val page = Regex("/Type\\s*/Page\\b")
    private val count = Regex("/Count\\s+(\\d+)\\b")
    private val streams = Regex("(?s)\\bstream(?:\\r\\n|\\n|\\r).*?\\bendstream\\b")

    fun estimate(document: DocumentInput): Int {
        if (!document.mimeType.equals("application/pdf", ignoreCase = true)) return FALLBACK_TOKENS
        val encoded = document.base64 ?: return FALLBACK_TOKENS
        if (encoded.length > (MAX_BYTES.toLong() + 2) / 3 * 4) return FALLBACK_TOKENS
        val bytes = try { Base64.getDecoder().decode(encoded) } catch (_: IllegalArgumentException) { return FALLBACK_TOKENS }
        val pages = pageCount(bytes) ?: return FALLBACK_TOKENS
        return (pages.toLong() * TOKENS_PER_PAGE).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    internal fun pageCount(bytes: ByteArray): Int? {
        if (bytes.size > MAX_BYTES) return null
        val text = bytes.toString(Charsets.ISO_8859_1)
        if (!text.startsWith("%PDF-") || "/Encrypt" in text || "/ObjStm" in text) return null
        val plain = streams.replace(text, "")
        var treeCount = 0
        var leafCount = 0
        objects.findAll(plain).forEach { match ->
            val body = match.groupValues[1]
            if (pageTree.containsMatchIn(body)) {
                val value = count.find(body)?.groupValues?.get(1)?.toIntOrNull() ?: return null
                treeCount = maxOf(treeCount, value)
            } else if (page.containsMatchIn(body)) leafCount++
        }
        return maxOf(treeCount, leafCount).takeIf { it > 0 }
    }
}
