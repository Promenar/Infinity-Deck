package com.promenar.infinity.data.export

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.InflaterInputStream
import javax.imageio.ImageIO

class PngStreamEncoderTest {
    @Test fun 流式长图的尺寸块校验及像素精确一致() {
        val width = 1600
        val height = 3500
        val output = ByteArrayOutputStream()
        fun pixel(x: Int, y: Int): Int =
            ((32 + y % 224) shl 24) or ((x % 256) shl 16) or ((y % 256) shl 8) or ((x xor y) and 255)

        PngStreamEncoder.encode(width, height, output, 256) { top, bandWidth, rows, argb ->
            assertEquals(width, bandWidth)
            for (y in 0 until rows) for (x in 0 until width) argb[y * width + x] = pixel(x, top + y)
        }

        val bytes = output.toByteArray()
        val input = DataInputStream(bytes.inputStream())
        assertArrayEquals(byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10), ByteArray(8).also(input::readFully))
        val compressed = ByteArrayOutputStream()
        var idatCount = 0
        while (true) {
            val length = input.readInt()
            val type = ByteArray(4).also(input::readFully)
            val data = ByteArray(length).also(input::readFully)
            assertEquals(CRC32().apply { update(type); update(data) }.value.toInt(), input.readInt())
            when (type.decodeToString()) {
                "IHDR" -> DataInputStream(data.inputStream()).use {
                    assertEquals(width, it.readInt())
                    assertEquals(height, it.readInt())
                    assertArrayEquals(byteArrayOf(8, 6, 0, 0, 0), it.readBytes())
                }
                "IDAT" -> { compressed.write(data); idatCount++ }
                "IEND" -> break
            }
        }
        assertTrue(idatCount > 1)
        assertEquals(-1, input.read())
        val image = ImageIO.read(bytes.inputStream())
        assertEquals(width, image.width)
        assertEquals(height, image.height)
        for (y in listOf(0, 255, 256, 2048, height - 1)) for (x in listOf(0, 1, 867, width - 1)) {
            assertEquals(pixel(x, y), image.getRGB(x, y), "像素 ($x, $y)")
        }
        DataInputStream(InflaterInputStream(compressed.toByteArray().inputStream())).use { scanlines ->
            for (y in 0 until height) {
                assertEquals(0, scanlines.readUnsignedByte())
                scanlines.skipNBytes(4L * width)
            }
            assertEquals(-1, scanlines.read())
        }
    }

    @Test fun 超过两万像素仍可编码为单张图() {
        val output = ByteArrayOutputStream()
        PngStreamEncoder.encode(3, 20_513, output, 2048) { top, width, rows, pixels ->
            for (y in 0 until rows) pixels.fill(0xff000000.toInt() or (top + y), y * width, (y + 1) * width)
        }
        val image = ImageIO.read(output.toByteArray().inputStream())
        assertEquals(20_513, image.height)
        assertEquals(0xff000000.toInt() or 20_512, image.getRGB(2, 20_512))
    }
}
