package com.promenar.infinity.data.backup

/** ZipInputStream 不暴露 Unix 节点类型，必须先检查中央目录中的外部属性。 */
internal object BackupZipEntryTypes {
    fun rejectExtraLinks(extra: ByteArray?) {
        if (extra == null) return
        fun u16(offset: Int) = (extra[offset].toInt() and 255) or ((extra[offset + 1].toInt() and 255) shl 8)
        var offset = 0
        while (offset < extra.size) {
            if (offset + 4 > extra.size) throw BackupValidationException("ZIP 扩展属性无效")
            val length = u16(offset + 2)
            if (offset + 4 + length > extra.size) throw BackupValidationException("ZIP 扩展属性越界")
            if (u16(offset) == 0x756e && length >= 6 && (u16(offset + 8) and 0xf000) == 0xa000) {
                throw BackupValidationException("备份包包含符号链接条目")
            }
            offset += 4 + length
        }
    }

    fun rejectLinks(bytes: ByteArray): Set<String> {
        fun u16(offset: Int): Int {
            if (offset < 0 || offset + 2 > bytes.size) throw BackupValidationException("ZIP 中央目录越界")
            return (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
        }
        fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
        fun checkExtra(start: Int, size: Int) {
            var extra = start
            val end = start + size
            while (extra < end) {
                if (extra + 4 > end) throw BackupValidationException("ZIP 扩展属性无效")
                val id = u16(extra)
                val length = u16(extra + 2)
                if (extra + 4 + length > end) throw BackupValidationException("ZIP 扩展属性越界")
                if (id == 0x756e && length >= 6 && (u16(extra + 8) and 0xf000) == 0xa000) {
                    throw BackupValidationException("备份包包含符号链接条目")
                }
                extra += 4 + length
            }
        }
        val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 65535)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == bytes.size
        } ?: throw BackupValidationException("ZIP 中央目录缺失")
        val count = u16(end + 10)
        val size = u32(end + 12)
        val start = u32(end + 16)
        if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != count ||
            start + size != end.toLong() || count > BackupPackageLimits.MAX_ENTRIES + 1
        ) throw BackupValidationException("ZIP 中央目录结构无效或内容项过多")
        var offset = start.toInt()
        val seen = mutableSetOf<String>()
        val localOffsets = mutableSetOf<Long>()
        repeat(count) {
            if (u32(offset) != 0x02014b50L || offset + 46L > end) {
                throw BackupValidationException("ZIP 中央目录条目无效")
            }
            val nameSize = u16(offset + 28)
            val extraSize = u16(offset + 30)
            val commentSize = u16(offset + 32)
            val next = offset + 46L + nameSize + extraSize + commentSize
            if (next > end || u16(offset + 34) != 0) throw BackupValidationException("ZIP 中央目录条目越界")
            val mode = (u32(offset + 38) ushr 16).toInt() and 0xf000
            if (mode != 0 && mode != 0x8000) throw BackupValidationException("备份包包含符号链接或非普通文件条目")
            val name = bytes.copyOfRange(offset + 46, offset + 46 + nameSize).toString(Charsets.UTF_8)
            if (!seen.add(name)) throw BackupValidationException("备份包包含重复路径")
            val local = u32(offset + 42)
            if (local + 30 > start || !localOffsets.add(local) || u32(local.toInt()) != 0x04034b50L) {
                throw BackupValidationException("ZIP 本地条目头无效")
            }
            val localNameSize = u16(local.toInt() + 26)
            val localExtraSize = u16(local.toInt() + 28)
            if (localNameSize != nameSize || local + 30 + localNameSize + localExtraSize > start ||
                !bytes.copyOfRange(local.toInt() + 30, local.toInt() + 30 + localNameSize)
                    .contentEquals(bytes.copyOfRange(offset + 46, offset + 46 + nameSize))) {
                throw BackupValidationException("ZIP 本地条目与中央目录路径不一致")
            }
            // ASi Unix extra 可在未设置 external mode 时携带符号链接目标。
            checkExtra(local.toInt() + 30 + localNameSize, localExtraSize)
            checkExtra(offset + 46 + nameSize, extraSize)
            offset = next.toInt()
        }
        if (offset != end) throw BackupValidationException("ZIP 中央目录长度不一致")
        return seen
    }

    fun rejectLinks(path: java.nio.file.Path): Set<String> = java.io.RandomAccessFile(path.toFile(), "r").use { file ->
        fun range(start: Int, end: Int): ByteArray = ByteArray(end - start).also { file.seek(start.toLong()); file.readFully(it) }
        val fileSize = file.length().toInt()
        fun u16(offset: Int): Int {
            if (offset < 0 || offset + 2 > fileSize) throw BackupValidationException("ZIP 中央目录越界")
            return run { file.seek(offset.toLong()); file.readUnsignedByte() or (file.readUnsignedByte() shl 8) }
        }
        fun u32(offset: Int): Long = u16(offset).toLong() or (u16(offset + 2).toLong() shl 16)
        fun checkExtra(start: Int, size: Int) {
            var extra = start
            val end = start + size
            while (extra < end) {
                if (extra + 4 > end) throw BackupValidationException("ZIP 扩展属性无效")
                val id = u16(extra)
                val length = u16(extra + 2)
                if (extra + 4 + length > end) throw BackupValidationException("ZIP 扩展属性越界")
                if (id == 0x756e && length >= 6 && (u16(extra + 8) and 0xf000) == 0xa000) {
                    throw BackupValidationException("备份包包含符号链接条目")
                }
                extra += 4 + length
            }
        }
        val end = (fileSize - 22 downTo maxOf(0, fileSize - 22 - 65535)).firstOrNull {
            u32(it) == 0x06054b50L && it + 22 + u16(it + 20) == fileSize
        } ?: throw BackupValidationException("ZIP 中央目录缺失")
        val count = u16(end + 10)
        val size = u32(end + 12)
        val start = u32(end + 16)
        if (u16(end + 4) != 0 || u16(end + 6) != 0 || u16(end + 8) != count ||
            start + size != end.toLong() || count > BackupPackageLimits.MAX_ENTRIES + 1
        ) throw BackupValidationException("ZIP 中央目录结构无效或内容项过多")
        var offset = start.toInt()
        val seen = mutableSetOf<String>()
        val localOffsets = mutableSetOf<Long>()
        repeat(count) {
            if (u32(offset) != 0x02014b50L || offset + 46L > end) {
                throw BackupValidationException("ZIP 中央目录条目无效")
            }
            val nameSize = u16(offset + 28)
            val extraSize = u16(offset + 30)
            val commentSize = u16(offset + 32)
            val next = offset + 46L + nameSize + extraSize + commentSize
            if (next > end || u16(offset + 34) != 0) throw BackupValidationException("ZIP 中央目录条目越界")
            val mode = (u32(offset + 38) ushr 16).toInt() and 0xf000
            if (mode != 0 && mode != 0x8000) throw BackupValidationException("备份包包含符号链接或非普通文件条目")
            val name = range(offset + 46, offset + 46 + nameSize).toString(Charsets.UTF_8)
            if (!seen.add(name)) throw BackupValidationException("备份包包含重复路径")
            val local = u32(offset + 42)
            if (local + 30 > start || !localOffsets.add(local) || u32(local.toInt()) != 0x04034b50L) {
                throw BackupValidationException("ZIP 本地条目头无效")
            }
            val localNameSize = u16(local.toInt() + 26)
            val localExtraSize = u16(local.toInt() + 28)
            if (localNameSize != nameSize || local + 30 + localNameSize + localExtraSize > start ||
                !range(local.toInt() + 30, local.toInt() + 30 + localNameSize)
                    .contentEquals(range(offset + 46, offset + 46 + nameSize))) {
                throw BackupValidationException("ZIP 本地条目与中央目录路径不一致")
            }
            // ASi Unix extra 可在未设置 external mode 时携带符号链接目标。
            checkExtra(local.toInt() + 30 + localNameSize, localExtraSize)
            checkExtra(offset + 46 + nameSize, extraSize)
            offset = next.toInt()
        }
        if (offset != end) throw BackupValidationException("ZIP 中央目录长度不一致")
        seen
    }
}
