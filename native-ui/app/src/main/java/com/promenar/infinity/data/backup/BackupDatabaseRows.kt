package com.promenar.infinity.data.backup

import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import java.nio.file.Path

/** 行偏移索引驻留内存，文本载荷只在访问当前行时解码。 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
internal class BackupDatabaseRows private constructor(private val rows: List<Row>) : AbstractList<JsonObject>() {
    private data class Row(val path: Path, val offset: Long, val length: Int)
    override val size get() = rows.size
    override fun get(index: Int): JsonObject {
        val row = rows[index]
        return java.nio.channels.FileChannel.open(row.path, java.nio.file.StandardOpenOption.READ).use { channel ->
            channel.position(row.offset)
            BackupStreams.limited(java.nio.channels.Channels.newInputStream(channel), row.length.toLong()).use { input ->
                Json.decodeFromStream<JsonObject>(input)
            }
        }
    }
    companion object {
        fun read(blobs: List<BackupBlob>): List<JsonObject> {
            val rows = mutableListOf<Row>()
            blobs.forEach { blob ->
                val path = (blob as? BackupBlob.File)?.path ?: throw BackupValidationException("数据库行必须来自暂存文件")
                blob.open().use { input ->
                    val buffer = ByteArray(BackupStreams.BUFFER_BYTES)
                    var offset = 0L
                    var start = 0L
                    try {
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            for (index in 0 until count) {
                                if (buffer[index] == 10.toByte()) {
                                    val length = offset + index - start
                                    if (length <= 0 || length > BackupPackageLimits.MAX_ENTRY_BYTES) throw BackupValidationException("数据库行长度无效")
                                    rows += Row(path, start, length.toInt())
                                    start = offset + index + 1
                                }
                            }
                            offset += count
                        }
                        if (offset != start) throw BackupValidationException("数据库行未完整结束")
                    } finally { buffer.fill(0) }
                }
            }
            return BackupDatabaseRows(rows)
        }
        fun write(rows: Sequence<JsonObject>, directory: Path, table: String, layer: BackupLayer): List<BackupSourceEntry> {
            val result = mutableListOf<BackupSourceEntry>()
            var output: java.io.OutputStream? = null
            var size = 0L
            fun next() {
                output?.close()
                val path = Files.createTempFile(directory, "rows-", ".jsonl")
                result += BackupSourceEntry("database/$table/${layer.ordinal.toString() + result.size.toString().padStart(5, '0')}.jsonl", layer, BackupBlob.File(path))
                output = Files.newOutputStream(path)
                size = 0
            }
            try {
                next()
                rows.forEach { row ->
                    val bytes = row.toString().toByteArray()
                    try {
                        if (bytes.size + 1L > BackupPackageLimits.MAX_ENTRY_BYTES) throw BackupValidationException("数据库单行超过条目上限", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                        if (size > 0 && size + bytes.size + 1 > 1024 * 1024) next()
                        output!!.write(bytes); output!!.write(10); size += bytes.size + 1
                    } finally { bytes.fill(0) }
                }
            } finally { output?.close() }
            return result
        }
        fun select(rows: List<JsonObject>, predicate: (JsonObject) -> Boolean): List<JsonObject> {
            val indices = rows.indices.filter { predicate(rows[it]) }.toIntArray()
            return object : AbstractList<JsonObject>() {
                override val size get() = indices.size
                override fun get(index: Int) = rows[indices[index]]
            }
        }
        fun concat(first: List<JsonObject>, second: List<JsonObject>): List<JsonObject> = object : AbstractList<JsonObject>() {
            override val size get() = first.size + second.size
            override fun get(index: Int) = if (index < first.size) first[index] else second[index - first.size]
        }
        fun map(rows: List<JsonObject>, transform: (JsonObject) -> JsonObject): List<JsonObject> = object : AbstractList<JsonObject>() {
            override val size get() = rows.size
            override fun get(index: Int) = transform(rows[index])
        }
    }
}
