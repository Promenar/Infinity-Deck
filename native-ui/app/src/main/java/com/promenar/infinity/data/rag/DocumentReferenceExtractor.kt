package com.promenar.infinity.data.rag

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.apache.poi.hwpf.HWPFDocument
import java.io.File
import java.io.IOException
import java.io.Writer

data class DocumentReferenceExtraction(
    val chunks: List<String>,
    val graphText: String,
    val truncated: Boolean,
)

/** 从工作区文件引用按增量读取，任何格式提取后的 UTF-8 文本最多 16 MiB。 */
class DocumentReferenceExtractor(
    private val chunkSize: Int,
    private val chunkOverlap: Int,
    private val maxExtractedUtf8Bytes: Long = MAX_EXTRACTED_UTF8_BYTES,
) {
    init {
        require(chunkSize > 0) { "文档切块大小必须大于 0" }
        require(chunkOverlap in 0 until chunkSize) { "文档切块重叠必须在 0 到切块大小之间" }
        require(maxExtractedUtf8Bytes in 0..MAX_EXTRACTED_UTF8_BYTES) {
            "文档提取预算必须在 0 到 16 MiB 之间"
        }
    }

    fun extract(entry: FileEntry): DocumentReferenceExtraction {
        val root = File(entry.physicalRootPath).canonicalFile
        val file = File(root, entry.materializedPath.trimStart('/')).canonicalFile
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
            throw SecurityException("索引文件超出工作区或不存在")
        }
        if (file.length() > MAX_SOURCE_BYTES) throw IllegalArgumentException("索引源文件超过 100 MiB")
        val collector = IncrementalCollector(chunkSize, chunkOverlap, maxExtractedUtf8Bytes)
        when (entry.mimeType) {
            "application/pdf" -> extractPdf(file, collector)
            DOCX_MIME -> extractDocx(file, collector)
            DOC_MIME -> extractDoc(file, collector)
            HTML_MIME -> extractHtml(file, collector)
            in TEXT_MIME_TYPES -> extractText(file, collector)
            else -> throw DocumentFormatException(DocumentFormatError.UNSUPPORTED_FORMAT)
        }
        return collector.finish()
    }

    private fun extractText(file: File, collector: IncrementalCollector) {
        DocumentTextEncoding.open(file).use { reader ->
            val buffer = CharArray(16 * 1024)
            while (!collector.truncated) {
                val count = reader.read(buffer)
                if (count < 0) break
                collector.append(String(buffer, 0, count))
            }
        }
    }

    private fun extractPdf(file: File, collector: IncrementalCollector) {
        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            val stripper = PDFTextStripper().apply { sortByPosition = true }
            val writer = CollectorWriter(collector)
            for (page in 1..document.numberOfPages) {
                if (collector.truncated) break
                stripper.startPage = page
                stripper.endPage = page
                try {
                    stripper.writeText(document, writer)
                } catch (_: ExtractionLimitReached) {
                    // 达到统一文本预算后停止当前 PDF；其它解析或 I/O 异常继续向上抛出。
                    break
                }
                collector.append("\n")
            }
            if (!collector.truncated && writer.nonWhitespace < 200 && writer.nonWhitespace < document.numberOfPages * 20L) {
                throw DocumentFormatException(DocumentFormatError.PDF_NO_TEXT_LAYER)
            }
        }
    }

    private fun extractDocx(file: File, collector: IncrementalCollector) {
        require(file.length() <= MAX_DOCX_SOURCE_BYTES) { "DOCX 源文件超过安全阈值" }
        validateDocxArchive(file)
        file.inputStream().buffered().use { input ->
            XWPFDocument(input).use { document ->
                document.bodyElements.forEach { element ->
                    if (collector.truncated) return@forEach
                    when (element) {
                        is org.apache.poi.xwpf.usermodel.XWPFParagraph -> {
                            collector.append(element.text)
                            collector.append("\n")
                        }
                        is org.apache.poi.xwpf.usermodel.XWPFTable -> element.rows.forEach { row ->
                            collector.append(row.tableCells.joinToString(" | ") { it.text })
                            collector.append("\n")
                        }
                    }
                }
            }
        }
    }

    private fun extractDoc(file: File, collector: IncrementalCollector) {
        require(file.length() <= MAX_DOCX_SOURCE_BYTES) { "DOC 源文件超过安全阈值" }
        try {
            file.inputStream().buffered().use { input ->
                HWPFDocument(input).use { document ->
                    val range = document.range
                    for (index in 0 until range.numParagraphs()) {
                        if (collector.truncated) break
                        collector.append(range.getParagraph(index).text())
                    }
                }
            }
        } catch (failure: Exception) {
            throw DocumentFormatException(DocumentFormatError.DOC_INVALID, failure)
        }
    }

    private fun extractHtml(file: File, collector: IncrementalCollector) {
        require(file.length() <= MAX_HTML_SOURCE_BYTES) { "HTML 源文件超过安全阈值" }
        val html = DocumentTextEncoding.open(file).use { reader ->
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = reader.read(buffer)
                if (count < 0) break
                require(result.length.toLong() + count <= MAX_HTML_SOURCE_BYTES) { "HTML 源文件超过安全阈值" }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
        collector.append(OfflineHtmlDocumentExtractor.extract(html))
    }

    private fun validateDocxArchive(file: File) {
        var entries = 0
        var expandedBytes = 0L
        var hasContentTypes = false
        var hasDocument = false
        java.util.zip.ZipInputStream(file.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++
                require(entries <= MAX_DOCX_ENTRIES) { "DOCX 条目数超过安全阈值" }
                if (entry.name == "[Content_Types].xml") hasContentTypes = true
                if (entry.name == "word/document.xml") hasDocument = true
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    expandedBytes += count
                    require(expandedBytes <= MAX_DOCX_EXPANDED_BYTES) { "DOCX 解压体积超过安全阈值" }
                }
            }
        }
        require(hasContentTypes && hasDocument) { "DOCX 结构无效" }
    }

    private class IncrementalCollector(
        private val chunkSize: Int,
        private val overlap: Int,
        private val maxUtf8Bytes: Long,
    ) {
        private val pending = StringBuilder()
        private val graph = StringBuilder()
        private val chunks = mutableListOf<String>()
        private var acceptedBytes = 0L
        private var pendingCodePoints = 0
        private var pendingHighSurrogate: Char? = null
        var truncated = false
            private set

        fun append(value: String) {
            if (truncated || value.isEmpty()) return
            var offset = 0
            pendingHighSurrogate?.let { high ->
                if (!value[0].isLowSurrogate()) {
                    throw IllegalArgumentException("提取文本包含不完整 Unicode 代理项")
                }
                appendCodePoint(Character.toCodePoint(high, value[0]))
                pendingHighSurrogate = null
                offset = 1
            }
            while (offset < value.length && !truncated) {
                val first = value[offset]
                if (first.isHighSurrogate()) {
                    if (offset + 1 == value.length) {
                        pendingHighSurrogate = first
                        break
                    }
                    val second = value[offset + 1]
                    if (!second.isLowSurrogate()) {
                        throw IllegalArgumentException("提取文本包含不完整 Unicode 代理项")
                    }
                    appendCodePoint(Character.toCodePoint(first, second))
                    offset += 2
                    continue
                }
                if (first.isLowSurrogate()) {
                    throw IllegalArgumentException("提取文本包含不完整 Unicode 代理项")
                }
                appendCodePoint(first.code)
                offset += 1
            }
        }

        private fun appendCodePoint(codePoint: Int) {
            val text = String(Character.toChars(codePoint))
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (acceptedBytes > maxUtf8Bytes - bytes) {
                truncated = true
                return
            }
            pending.append(text)
            graph.append(text)
            acceptedBytes += bytes
            pendingCodePoints += 1
            emitFullChunks()
        }

        private fun emitFullChunks() {
            while (pendingCodePoints >= chunkSize) {
                val chunkEnd = pending.offsetByCodePoints(0, chunkSize)
                chunks += pending.substring(0, chunkEnd)
                val removeCodePoints = chunkSize - overlap
                val removeEnd = pending.offsetByCodePoints(0, removeCodePoints)
                pending.delete(0, removeEnd)
                pendingCodePoints -= removeCodePoints
            }
        }

        fun finish(): DocumentReferenceExtraction {
            if (pendingHighSurrogate != null) {
                throw IllegalArgumentException("提取文本包含不完整 Unicode 代理项")
            }
            if (pending.isNotBlank()) chunks += pending.toString()
            return DocumentReferenceExtraction(chunks, graph.toString(), truncated)
        }
    }

    private class ExtractionLimitReached : IOException()

    /** 将 PDFBox 输出直接送入 collector，避免先构造整页文本字符串。 */
    private class CollectorWriter(
        private val collector: IncrementalCollector,
    ) : Writer() {
        var nonWhitespace: Long = 0
            private set
        override fun write(cbuf: CharArray, off: Int, len: Int) {
            if (len == 0) return
            nonWhitespace += (off until off + len).count { !cbuf[it].isWhitespace() }
            collector.append(String(cbuf, off, len))
            if (collector.truncated) throw ExtractionLimitReached()
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    companion object {
        /** 按实际读取字节计费，防止文件在长度检查后增长。 */
        internal fun readHtmlSource(input: java.io.InputStream): ByteArray {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                var count = input.read(buffer, 0, minOf(buffer.size.toLong(), MAX_HTML_SOURCE_BYTES + 1 - result.size()).toInt())
                if (count < 0) break
                if (count == 0) {
                    val byte = input.read()
                    if (byte < 0) break
                    buffer[0] = byte.toByte()
                    count = 1
                }
                require(result.size().toLong() + count <= MAX_HTML_SOURCE_BYTES) { "HTML 源文件超过安全阈值" }
                result.write(buffer, 0, count)
            }
            return result.toByteArray()
        }

        const val MAX_EXTRACTED_UTF8_BYTES = 16L * 1024 * 1024
        private const val MAX_SOURCE_BYTES = 100L * 1024 * 1024
        private const val MAX_DOCX_SOURCE_BYTES = 50L * 1024 * 1024
        internal const val MAX_HTML_SOURCE_BYTES = 4L * 1024 * 1024
        private const val MAX_DOCX_EXPANDED_BYTES = 64L * 1024 * 1024
        private const val MAX_DOCX_ENTRIES = 2_048
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val DOC_MIME = "application/msword"
        const val HTML_MIME = "text/html"
        val TEXT_MIME_TYPES = setOf("text/plain", "text/markdown", "text/csv", "application/json")
        val SUPPORTED_MIME_TYPES = TEXT_MIME_TYPES + setOf("application/pdf", DOCX_MIME, DOC_MIME, HTML_MIME)

        fun supports(entry: FileEntry): Boolean = !entry.isDirectory && entry.mimeType in SUPPORTED_MIME_TYPES &&
            entry.sizeBytes <= when (entry.mimeType) {
                DOCX_MIME, DOC_MIME -> MAX_DOCX_SOURCE_BYTES
                HTML_MIME -> MAX_HTML_SOURCE_BYTES
                else -> MAX_SOURCE_BYTES
            }
    }
}
