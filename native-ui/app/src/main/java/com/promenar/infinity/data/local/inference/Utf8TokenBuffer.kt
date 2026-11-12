package com.promenar.infinity.data.local.inference

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** 一个模型 token 可能只含 UTF-8 字符的部分字节。 */
internal class Utf8TokenBuffer {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun append(bytes: ByteArray): String {
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining() + 1)
        decoder.decode(input, output, false)
        pending = ByteArray(input.remaining()).also { input.get(it) }
        return output.flip().toString()
    }

    fun finish(): String = pending.decodeToString().also { pending = ByteArray(0); decoder.reset() }
}
