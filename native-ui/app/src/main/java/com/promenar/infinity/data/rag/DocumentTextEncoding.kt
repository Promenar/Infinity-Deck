package com.promenar.infinity.data.rag

import java.io.File
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

enum class DocumentFormatError { PDF_NO_TEXT_LAYER, TEXT_ENCODING_UNRECOGNIZED, UNSUPPORTED_FORMAT, DOC_INVALID }
class DocumentFormatException(val code: DocumentFormatError, cause: Throwable? = null) : java.io.IOException(code.name, cause)

/** 逐流严格校验编码，再打开读取器；不缓存完整文件。 */
internal object DocumentTextEncoding {
    fun open(file: File): java.io.Reader {
        val prefix = file.inputStream().use { input ->
            val bytes = ByteArray(3)
            var count = 0
            while (count < bytes.size) {
                val read = input.read(bytes, count, bytes.size - count)
                if (read < 0) break
                if (read == 0) continue
                count += read
            }
            bytes.copyOf(count)
        }
        val bom = when {
            prefix.size >= 3 && prefix[0] == 0xef.toByte() && prefix[1] == 0xbb.toByte() && prefix[2] == 0xbf.toByte() -> Charsets.UTF_8 to 3
            prefix.size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> Charsets.UTF_16LE to 2
            prefix.size >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> Charsets.UTF_16BE to 2
            else -> null
        }
        val candidates = bom?.let { listOf(it) } ?: listOf(Charsets.UTF_8 to 0, Charset.forName("GB18030") to 0)
        val selected = candidates.firstOrNull { (charset, skip) -> valid(file, charset, skip) }
            ?: throw DocumentFormatException(DocumentFormatError.TEXT_ENCODING_UNRECOGNIZED)
        return reader(file, selected.first, selected.second)
    }

    private fun reader(file: File, charset: Charset, skip: Int): java.io.Reader {
        val input = file.inputStream().buffered()
        repeat(skip) { if (input.read() < 0) throw DocumentFormatException(DocumentFormatError.TEXT_ENCODING_UNRECOGNIZED) }
        return InputStreamReader(input, charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    }

    private fun valid(file: File, charset: Charset, skip: Int): Boolean = try {
        reader(file, charset, skip).use { input ->
            val buffer = CharArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if ((0 until count).any { buffer[it] == '\u0000' }) return false
            }
        }
        true
    } catch (_: CharacterCodingException) { false }
}
