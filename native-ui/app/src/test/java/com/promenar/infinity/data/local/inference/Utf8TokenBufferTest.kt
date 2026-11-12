package com.promenar.infinity.data.local.inference

import org.junit.Assert.assertEquals
import org.junit.Test

class Utf8TokenBufferTest {
    @Test fun splitChineseAndEmojiKeepTheirOriginalCodePoints() {
        val input = "Deck 中🍀文"
        val bytes = input.encodeToByteArray()
        val buffer = Utf8TokenBuffer()
        val output = buildString {
            bytes.forEach { append(buffer.append(byteArrayOf(it))) }
            append(buffer.finish())
        }
        assertEquals(input, output)
    }
}
