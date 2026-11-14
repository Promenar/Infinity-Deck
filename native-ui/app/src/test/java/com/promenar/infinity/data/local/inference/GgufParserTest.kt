package com.promenar.infinity.data.local.inference

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GgufParserTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `依据模型文件类型显示量化规格`() {
        val file = directory.resolve("model.gguf")
        val name = "general.file_type".encodeToByteArray()
        val header = ByteBuffer.allocate(40 + name.size).order(ByteOrder.LITTLE_ENDIAN)
            .put("GGUF".encodeToByteArray())
            .putInt(3).putLong(0).putLong(1)
            .putLong(name.size.toLong()).put(name).putInt(4)
        val types = mapOf(7 to "Q8_0", 8 to "Q5_0", 9 to "Q5_1", 15 to "Q4_K_M", 32 to "BF16")
        for ((type, expected) in types) {
            header.putInt(header.capacity() - 4, type)
            file.writeBytes(header.array())
            assertEquals(expected, GgufParser.parse(file.path).quantization, "general.file_type=$type")
        }
    }
}
