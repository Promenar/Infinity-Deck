package com.promenar.infinity.data.local.inference

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

data class GgufMetadata(
    val version: Int = 0,
    val architecture: String = "",
    val quantization: String = "",
    val contextLength: Int = 2048,
    val embeddingLength: Int = 0,
    val parameterCount: String = "",
    val vocabSize: Int = 0,
    val modelName: String = "",
    val poolingType: Int? = null
)

object GgufParser {

    private const val GGUF_MAGIC = "GGUF"
    private const val MAX_KV_PAIRS = 10_000L

    private val TYPE_SIZES = longArrayOf(
        1, 1, 2, 2, 4, 4, 4, 1,
        -1, -1, 8, 8, 8
    )

    fun parse(filePath: String): GgufMetadata {
        val file = File(filePath)
        require(file.exists()) { "File not found: $filePath" }

        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            require(String(magic, StandardCharsets.US_ASCII) == GGUF_MAGIC) {
                "Not a valid GGUF file"
            }

            val version = readLeInt32(raf)
            val use64 = version >= 3

            readCount(raf, use64)
            val kvCount = readCount(raf, use64)

            val raw = linkedMapOf<String, Any?>()
            val limit = minOf(kvCount, MAX_KV_PAIRS)

            for (i in 0 until limit) {
                val key = readString(raf, use64)
                val valueType = readLeUInt32(raf).toInt()
                raw[key] = readValue(raf, valueType, use64)
            }

            return buildResult(version, raw, file.nameWithoutExtension)
        }
    }

    private fun readCount(raf: RandomAccessFile, use64: Boolean): Long =
        if (use64) readLeInt64(raf) else readLeUInt32(raf)

    private fun readString(raf: RandomAccessFile, use64: Boolean): String {
        val len = readCount(raf, use64)
        require(len in 0..65536) { "String length out of range: $len" }
        val buf = ByteArray(len.toInt())
        raf.readFully(buf)
        return String(buf, StandardCharsets.UTF_8)
    }

    private fun readValue(raf: RandomAccessFile, type: Int, use64: Boolean): Any? {
        return when (type) {
            0 -> raf.readUnsignedByte()
            1 -> raf.readByte()
            2 -> readLeUInt16(raf)
            3 -> readLeInt16(raf)
            4 -> readLeUInt32(raf)
            5 -> readLeInt32(raf)
            6 -> readLeFloat32(raf)
            7 -> raf.readUnsignedByte() != 0
            8 -> readString(raf, use64)
            9 -> { skipArray(raf, use64); null }
            10 -> readLeInt64(raf)
            11 -> readLeInt64(raf)
            12 -> readLeFloat64(raf)
            else -> { skipBytesSafe(raf, 8); null }
        }
    }

    private fun skipArray(raf: RandomAccessFile, use64: Boolean) {
        val elementType = readLeUInt32(raf).toInt()
        val count = readCount(raf, use64)

        if (elementType in TYPE_SIZES.indices && TYPE_SIZES[elementType] > 0) {
            skipBytesSafe(raf, count * TYPE_SIZES[elementType])
            return
        }

        when (elementType) {
            8 -> {
                var remaining = count
                while (remaining > 0) {
                    val strLen = readCount(raf, use64)
                    skipBytesSafe(raf, strLen)
                    remaining--
                }
            }
            9 -> {
                var remaining = count
                while (remaining > 0) {
                    skipArray(raf, use64)
                    remaining--
                }
            }
            else -> skipBytesSafe(raf, count * 8)
        }
    }

    private fun skipBytesSafe(raf: RandomAccessFile, n: Long) {
        var remaining = n
        while (remaining > 0) {
            val chunk = minOf(remaining, Int.MAX_VALUE.toLong()).toInt()
            val skipped = raf.skipBytes(chunk)
            if (skipped <= 0) break
            remaining -= skipped
        }
    }

    private fun readLeUInt32(raf: RandomAccessFile): Long {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return leInt(buf).toLong() and 0xFFFFFFFFL
    }

    private fun readLeInt32(raf: RandomAccessFile): Int {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return leInt(buf)
    }

    private fun readLeUInt16(raf: RandomAccessFile): Int {
        val buf = ByteArray(2)
        raf.readFully(buf)
        return leShort(buf).toInt() and 0xFFFF
    }

    private fun readLeInt16(raf: RandomAccessFile): Int {
        val buf = ByteArray(2)
        raf.readFully(buf)
        return leShort(buf).toInt()
    }

    private fun readLeInt64(raf: RandomAccessFile): Long {
        val buf = ByteArray(8)
        raf.readFully(buf)
        return leLong(buf)
    }

    private fun readLeFloat32(raf: RandomAccessFile): Float {
        val buf = ByteArray(4)
        raf.readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).float
    }

    private fun readLeFloat64(raf: RandomAccessFile): Double {
        val buf = ByteArray(8)
        raf.readFully(buf)
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).double
    }

    private fun leInt(buf: ByteArray): Int =
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leShort(buf: ByteArray): Short =
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).short

    private fun leLong(buf: ByteArray): Long =
        ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).long

    private fun buildResult(version: Int, raw: Map<String, Any?>, fallbackName: String): GgufMetadata {
        val arch = strVal(raw["general.architecture"])
        val modelName = strVal(raw["general.name"], fallbackName)

        val quantization = raw["general.file_type"]?.let {
            mapFileType(numberVal(it))
        } ?: guessQuantization(fallbackName)

        val contextLength = archKey(raw, arch, "context_length")?.let { numberVal(it).toInt() } ?: 2048
        val embeddingLength = archKey(raw, arch, "embedding_length")?.let { numberVal(it).toInt() } ?: 0
        val vocabSize = archKey(raw, arch, "vocab_size")?.let { numberVal(it).toInt() } ?: 0

        val parameterCount = raw.entries
            .firstOrNull { it.key.contains("parameter_count", ignoreCase = true) }?.value
            ?.let { formatParamCount(numberVal(it)) }
            ?: guessParamCount(fallbackName)

        return GgufMetadata(
            version = version,
            architecture = arch,
            quantization = quantization,
            contextLength = contextLength,
            embeddingLength = embeddingLength,
            parameterCount = parameterCount,
            vocabSize = vocabSize,
            modelName = modelName,
            poolingType = (archKey(raw, arch, "pooling_type") as? Number)?.toInt(),
        )
    }

    private fun archKey(raw: Map<String, Any?>, arch: String, suffix: String): Any? {
        if (arch.isNotEmpty()) {
            val v = raw["$arch.$suffix"]
            if (v != null) return v
        }
        return CANDIDATE_ARCHES.firstNotNullOfOrNull { raw["$it.$suffix"] }
    }

    // general.file_type 使用 llama_ftype 编号。
    private fun mapFileType(type: Long): String = when (type) {
        0L -> "F32"
        1L -> "F16"
        2L -> "Q4_0"
        3L -> "Q4_1"
        7L -> "Q8_0"
        8L -> "Q5_0"
        9L -> "Q5_1"
        10L -> "Q2_K"
        11L -> "Q3_K_S"
        12L -> "Q3_K_M"
        13L -> "Q3_K_L"
        14L -> "Q4_K_S"
        15L -> "Q4_K_M"
        16L -> "Q5_K_S"
        17L -> "Q5_K_M"
        18L -> "Q6_K"
        19L -> "IQ2_XXS"
        20L -> "IQ2_XS"
        21L -> "Q2_K_S"
        22L -> "IQ3_XS"
        23L -> "IQ3_XXS"
        24L -> "IQ1_S"
        25L -> "IQ4_NL"
        26L -> "IQ3_S"
        27L -> "IQ3_M"
        28L -> "IQ2_S"
        29L -> "IQ2_M"
        30L -> "IQ4_XS"
        31L -> "IQ1_M"
        32L -> "BF16"
        36L -> "TQ1_0"
        37L -> "TQ2_0"
        38L -> "MXFP4"
        39L -> "NVFP4"
        40L -> "Q1_0"
        41L -> "Q2_0"
        42L -> "F8_E4M3"
        else -> "Unknown($type)"
    }

    private fun guessQuantization(name: String): String {
        val upper = name.uppercase()
        return QUANT_PATTERNS.firstNotNullOfOrNull { (pat, label) ->
            if (upper.contains(pat)) label else null
        } ?: "Unknown"
    }

    private fun formatParamCount(count: Long): String = when {
        count >= 1_000_000_000_000 -> "%.1fT".format(count / 1_000_000_000_000.0)
        count >= 1_000_000_000 -> "%.1fB".format(count / 1_000_000_000.0)
        count >= 1_000_000 -> "%.1fM".format(count / 1_000_000.0)
        else -> count.toString()
    }

    private fun guessParamCount(name: String): String {
        val match = Regex("(\\d+(?:\\.\\d+)?)\\s*[Bb]", RegexOption.IGNORE_CASE).find(name)
        return match?.groupValues?.get(1)?.let { "${it}B" } ?: ""
    }

    private fun strVal(v: Any?, default: String = ""): String = v as? String ?: default

    private fun numberVal(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is Boolean -> if (v) 1L else 0L
        else -> 0L
    }

    private val CANDIDATE_ARCHES = listOf(
        "llama", "gpt2", "falcon", "mpt", "starcoder", "gptj", "gptneox",
        "phi2", "phi3", "bert", "nomic", "refact", "qwen2", "mistral",
        "gemma", "gemma2", "command-r", "deepseek2", "llama4"
    )

    private val QUANT_PATTERNS = listOf(
        "Q8_0" to "Q8_0",
        "Q6_K_L" to "Q6_K_L",
        "Q6_K" to "Q6_K",
        "Q5_K_M" to "Q5_K_M",
        "Q5_K_S" to "Q5_K_S",
        "Q5_1" to "Q5_1",
        "Q5_0" to "Q5_0",
        "Q4_K_M" to "Q4_K_M",
        "Q4_K_S" to "Q4_K_S",
        "Q4_1" to "Q4_1",
        "Q4_0" to "Q4_0",
        "Q3_K" to "Q3_K",
        "Q2_K" to "Q2_K",
        "IQ4_NL" to "IQ4_NL",
        "IQ3_S" to "IQ3_S",
        "IQ2_S" to "IQ2_S",
        "IQ1_S" to "IQ1_S",
        "BF16" to "BF16",
        "F16" to "F16",
        "F32" to "F32"
    )
}
