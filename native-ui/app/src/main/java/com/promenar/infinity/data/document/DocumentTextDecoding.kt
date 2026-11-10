package com.promenar.infinity.data.document

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * 文本文档的字节解码：按 BOM 识别 UTF-8 / UTF-16，无 BOM 时按 UTF-8。
 * 会话文档附件与文档查看器共用，保证同一份文件在两处得到相同的文本与哈希。
 */
object DocumentTextDecoding {
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    /** 严格解码：出现非法字节序列时抛出 [CharacterCodingException]。 */
    @Throws(CharacterCodingException::class)
    fun decode(bytes: ByteArray): String {
        val (charset, offset) = charsetOf(bytes)
        return charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset))
            .toString()
    }

    /** 宽松解码：非法字节替换为 U+FFFD，用于应用自己生成的文件。 */
    fun decodeLenient(bytes: ByteArray): String {
        val (charset, offset) = charsetOf(bytes)
        return String(bytes, offset, bytes.size - offset, charset)
    }

    /** 含 NUL 或换行、回车、制表符以外的控制字符时视为二进制内容。 */
    fun hasBinaryControlCharacters(text: String): Boolean = text.any { character ->
        character == '\u0000' || (character.isISOControl() && character !in "\n\r\t")
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun charsetOf(bytes: ByteArray): Pair<Charset, Int> = when {
        bytes.hasPrefix(UTF8_BOM) -> Charsets.UTF_8 to UTF8_BOM.size
        bytes.hasPrefix(UTF16_LE_BOM) -> Charsets.UTF_16LE to UTF16_LE_BOM.size
        bytes.hasPrefix(UTF16_BE_BOM) -> Charsets.UTF_16BE to UTF16_BE_BOM.size
        else -> Charsets.UTF_8 to 0
    }

    private fun ByteArray.hasPrefix(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
