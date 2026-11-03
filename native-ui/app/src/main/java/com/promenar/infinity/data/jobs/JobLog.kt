package com.promenar.infinity.data.jobs

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** 只维护当前日志和一个旧文件；固定大小块直接写盘。 */
class RotatingJobLog(private val file: File, private val limitBytes: Long = 20L * 1024 * 1024,
    private val startedAtMillis: Long? = null, private val now: () -> Long = System::currentTimeMillis) : Closeable {
    private var lineStart = true
    private var output: FileOutputStream
    private var size: Long
    init {
        require(limitBytes > 0)
        file.parentFile?.mkdirs()
        size = file.length()
        if (size > 0) RandomAccessFile(file, "r").use { it.seek(size - 1); lineStart = it.read() == 10 }
        if (startedAtMillis != null) File(file.path + ".timestamps").apply { if (!exists()) writeText("relative-line-v1") }
        output = FileOutputStream(file, true)
    }
    @Synchronized fun write(bytes: ByteArray) {
        if (startedAtMillis == null) { writeRaw(bytes); return }
        // 只在行首写 ASCII 前缀，不拆解 UTF-8 字节，跨分块的文字保持原样。
        var begin = 0
        for (index in bytes.indices) {
            if (lineStart) {
                val tenths = ((now() - startedAtMillis).coerceAtLeast(0)) / 100
                writeRaw("[+%02d:%02d.%d] ".format(java.util.Locale.ROOT, tenths / 600, tenths / 10 % 60, tenths % 10).toByteArray(Charsets.US_ASCII))
                lineStart = false
            }
            if (bytes[index] == 10.toByte()) {
                writeRaw(bytes.copyOfRange(begin, index + 1))
                begin = index + 1
                lineStart = true
            }
        }
        if (begin < bytes.size) writeRaw(bytes.copyOfRange(begin, bytes.size))
    }
    private fun writeRaw(bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            if (size >= limitBytes) {
                output.close()
                Files.move(file.toPath(), File(file.path + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING)
                output = FileOutputStream(file)
                size = 0
            }
            val count = minOf((bytes.size - offset).toLong(), limitBytes - size).toInt()
            output.write(bytes, offset, count)
            output.flush()
            offset += count
            size += count
        }
    }
    @Synchronized override fun close() = output.close()
}

data class JobLogTail(val text: String, val truncated: Boolean)

/** 读盘上限为字符预算四倍，避免为大日志分配完整字符串。 */
private val JOB_LOG_TIMESTAMP = Regex("(?m)^\\[\\+\\d{2,}:\\d{2}\\.\\d] ")

fun readJobLogTail(file: File, limit: Int = 8_000, stripTimestamps: Boolean = true): JobLogTail {
    if (!file.isFile) return JobLogTail("", false)
    return RandomAccessFile(file, "r").use { input ->
        val size = input.length()
        val bytes = ByteArray(minOf(size, limit * 4L + 4).toInt())
        input.seek(size - bytes.size)
        input.readFully(bytes)
        var text = bytes.toString(Charsets.UTF_8)
        if (stripTimestamps && File(file.path + ".timestamps").isFile) {
            // 有界读取可能从前缀中间开始，丢弃首个不完整行后再去除完整前缀。
            if (size > bytes.size && '\n' in text) text = text.substringAfter('\n')
            text = text.replace(JOB_LOG_TIMESTAMP, "")
        }
        val truncated = size > bytes.size || text.length > limit || File(file.path + ".1").exists()
        if (text.length > limit) text = text.takeLast(limit).dropWhile { Character.isLowSurrogate(it) }
        JobLogTail(text, truncated)
    }
}
