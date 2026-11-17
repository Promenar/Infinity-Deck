package com.promenar.infinity.data.export

import android.graphics.Bitmap
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream

/** 逐扫描线写出 RGBA PNG，输出流的关闭由调用方负责。 */
internal object PngStreamEncoder {
    private const val CHUNK_BYTES = 64 * 1024
    private val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
    private val ihdr = "IHDR".encodeToByteArray()
    private val idat = "IDAT".encodeToByteArray()
    private val iend = "IEND".encodeToByteArray()

    internal fun encode(
        width: Int,
        height: Int,
        output: OutputStream,
        bandHeight: Int = 1024,
        renderBand: (topY: Int, bandWidth: Int, rows: Int, argbOut: IntArray) -> Unit,
    ) = encodePixels(width, height, output, bandHeight, renderBand)

    /** 回调交付的位图归编码器所有，每带读取完成即回收。 */
    internal fun encode(
        width: Int,
        height: Int,
        output: OutputStream,
        bandHeight: Int = 1024,
        renderBitmapBand: (topY: Int, rows: Int) -> Bitmap,
    ) = encodePixels(width, height, output, bandHeight) { top, bandWidth, rows, pixels ->
        val bitmap = renderBitmapBand(top, rows)
        try {
            require(bitmap.width == bandWidth && bitmap.height == rows) { "PNG 条带尺寸不匹配" }
            bitmap.getPixels(pixels, 0, bandWidth, 0, 0, bandWidth, rows)
        } finally {
            bitmap.recycle()
        }
    }

    internal suspend fun encodeBands(
        width: Int,
        height: Int,
        output: OutputStream,
        bandHeight: Int,
        renderBand: suspend (topY: Int, bandWidth: Int, rows: Int, argbOut: IntArray) -> Unit,
    ) = encodePixels(width, height, output, bandHeight) { top, bandWidth, rows, pixels ->
        renderBand(top, bandWidth, rows, pixels)
    }

    private inline fun encodePixels(
        width: Int,
        height: Int,
        output: OutputStream,
        bandHeight: Int,
        renderBand: (topY: Int, bandWidth: Int, rows: Int, argbOut: IntArray) -> Unit,
    ) {
        require(width > 0 && height > 0 && bandHeight > 0)
        val bands = minOf(bandHeight, height)
        val pixels = IntArray(Math.multiplyExact(width, bands))
        val scanline = ByteArray(Math.addExact(1, Math.multiplyExact(4, width)))
        val png = DataOutputStream(output)
        png.write(signature)
        val header = java.nio.ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(byteArrayOf(8, 6, 0, 0, 0)).array()
        png.writeChunk(ihdr, header, header.size)

        val chunks = BufferedOutputStream(IdatOutput(png), CHUNK_BYTES)
        val deflater = Deflater()
        try {
            val compressed = DeflaterOutputStream(chunks, deflater, 8192)
            var top = 0
            while (top < height) {
                val rows = minOf(bands, height - top)
                renderBand(top, width, rows, pixels)
                var pixelIndex = 0
                repeat(rows) {
                    var offset = 1
                    repeat(width) {
                        val argb = pixels[pixelIndex++]
                        scanline[offset++] = (argb ushr 16).toByte()
                        scanline[offset++] = (argb ushr 8).toByte()
                        scanline[offset++] = argb.toByte()
                        scanline[offset++] = (argb ushr 24).toByte()
                    }
                    compressed.write(scanline)
                }
                top += rows
            }
            compressed.finish()
            chunks.flush()
        } finally {
            deflater.end()
        }
        png.writeChunk(iend, ByteArray(0), 0)
        png.flush()
    }

    private class IdatOutput(private val png: DataOutputStream) : OutputStream() {
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            png.writeChunk(idat, bytes, length, offset)
        }
    }

    private fun DataOutputStream.writeChunk(type: ByteArray, bytes: ByteArray, length: Int, offset: Int = 0) {
        val crc = CRC32()
        crc.update(type)
        crc.update(bytes, offset, length)
        writeInt(length)
        write(type)
        write(bytes, offset, length)
        writeInt(crc.value.toInt())
    }
}
