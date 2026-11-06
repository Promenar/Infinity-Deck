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
        val encoding = detect { check -> file.inputStream().use(check) }
        return reader(file.inputStream(), encoding)
    }

    data class Encoding(val charset: Charset, val skip: Int)

    /** 通过可重新打开的受控输入逐次校验完整流。 */
    fun detect(withStream: ((java.io.InputStream) -> Boolean) -> Boolean): Encoding {
        var prefix = ByteArray(0)
        withStream { input ->
            val bytes = ByteArray(3)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                if (n > 0) count += n else { val b = input.read(); if (b < 0) break; bytes[count++] = b.toByte() }
            }
            prefix = bytes.copyOf(count)
            true
        }
        return candidates(prefix).firstOrNull { encoding -> withStream { valid(it, encoding) } }
            ?: throw DocumentFormatException(DocumentFormatError.TEXT_ENCODING_UNRECOGNIZED)
    }

    suspend fun detectSuspending(withStream: suspend ((java.io.InputStream) -> Boolean) -> Boolean): Encoding {
        var prefix = ByteArray(0)
        withStream { input ->
            val bytes = ByteArray(3)
            var count = 0
            while (count < bytes.size) {
                val n = input.read(bytes, count, bytes.size - count)
                if (n < 0) break
                if (n > 0) count += n else { val b = input.read(); if (b < 0) break; bytes[count++] = b.toByte() }
            }
            prefix = bytes.copyOf(count)
            true
        }
        for (encoding in candidates(prefix)) if (withStream { valid(it, encoding) }) return encoding
        throw DocumentFormatException(DocumentFormatError.TEXT_ENCODING_UNRECOGNIZED)
    }

    private fun candidates(prefix: ByteArray): List<Encoding> {
        val bom = when {
            prefix.size >= 3 && prefix[0] == 0xef.toByte() && prefix[1] == 0xbb.toByte() && prefix[2] == 0xbf.toByte() -> Encoding(Charsets.UTF_8, 3)
            prefix.size >= 2 && prefix[0] == 0xff.toByte() && prefix[1] == 0xfe.toByte() -> Encoding(Charsets.UTF_16LE, 2)
            prefix.size >= 2 && prefix[0] == 0xfe.toByte() && prefix[1] == 0xff.toByte() -> Encoding(Charsets.UTF_16BE, 2)
            else -> null
        }
        return bom?.let { listOf(it) } ?: listOf(Encoding(Charsets.UTF_8, 0), Encoding(Charset.forName("GB18030"), 0))
    }

    fun reader(input: java.io.InputStream, encoding: Encoding): java.io.Reader {
        val buffered = input.buffered()
        repeat(encoding.skip) { if (buffered.read() < 0) throw DocumentFormatException(DocumentFormatError.TEXT_ENCODING_UNRECOGNIZED) }
        return InputStreamReader(buffered, encoding.charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
    }

    private fun valid(input: java.io.InputStream, encoding: Encoding): Boolean {
        return try {
        val reader = reader(input, encoding)
        val buffer = CharArray(8192)
        var total = 0L
        var controls = 0L
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            for (i in 0 until count) {
                val c = buffer[i]
                if (c == '\u0000') return false
                if (Character.isISOControl(c) && c !in "\t\n\r\u000c") controls++
            }
            total += count
        }
        encoding.charset.name() != "GB18030" || controls * 100 <= total
        } catch (_: CharacterCodingException) { false }
    }
}
