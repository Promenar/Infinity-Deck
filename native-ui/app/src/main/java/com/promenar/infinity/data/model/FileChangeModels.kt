@file:OptIn(ExperimentalSerializationApi::class)

package com.promenar.infinity.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 文件工具一次成功执行对单个文件的改动摘要，只用于界面展示：随 `tool_result` 步骤持久化，
 * 不进入发给模型的工具结果文本、上下文或工具账本。
 *
 * - [path] 是工具面向模型的虚拟路径（如 `/src/a.kt`、`/mnt/x/y`）；移动时为目标路径，[fromPath] 为原路径；
 * - [added] / [removed] 为增删行数，规则与 `FileDiffModel` 一致：新建只计新增，删除只计删除，移动不改内容为 0；
 *   无法得出时为空（如二进制、超出读取上限或读取失败）；
 * - [binary] 为真时不给计数；[large] 为真表示超过逐行差异阈值，计数可能是估算值（见 [approximate]）；
 * - [hunks] 只在文本、未超限且未超出存储上限时保存，否则为空并由 [hunksOmitted] 说明原因。
 *
 * 可选字段取默认值时不写入 JSON，历史消息缺少整个字段时按空处理。
 */
@Serializable
data class FileChangeSummary(
    val path: String,
    val change: FileChangeType,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fromPath: String? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val added: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val removed: Int? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val binary: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val large: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val approximate: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val directory: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hunks: List<FileChangeHunk>? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val hunksOmitted: FileChangeHunkOmission? = null,
) {
    /** 已存 hunk 的文本字节数（UTF-8，含行首标记），用于存储上限核算。 */
    val storedHunkBytes: Long
        get() = hunks.orEmpty().sumOf { hunk -> hunk.lines.sumOf { utf8Bytes(it) } }

    companion object {
        /** 单个文件可保存的 hunk 文本上限。 */
        const val MAX_FILE_HUNK_BYTES: Long = 32L * 1024L

        /** 单个工具结果中全部文件可保存的 hunk 文本上限。 */
        const val MAX_RESULT_HUNK_BYTES: Long = 128L * 1024L
    }
}

@Serializable
enum class FileChangeType {
    @SerialName("created") CREATED,
    @SerialName("modified") MODIFIED,
    @SerialName("deleted") DELETED,
    @SerialName("moved") MOVED,
}

/** 未保存 hunk 的原因。 */
@Serializable
enum class FileChangeHunkOmission {
    /** 永久删除仅保留统计，不持久化被删除的文本。 */
    @SerialName("permanent_delete") PERMANENT_DELETE,

    /** 二进制文件不做逐行差异。 */
    @SerialName("binary") BINARY,

    /** 超过逐行差异的行数或字节阈值（“大文件”）。 */
    @SerialName("large") LARGE,

    /** 差异过于复杂，逐行计算会占用过多内存。 */
    @SerialName("too_complex") TOO_COMPLEX,

    /** hunk 文本超出单文件或单个工具结果的存储上限。 */
    @SerialName("size_limit") SIZE_LIMIT,

    /** 写入前后内容无法读取（读取失败或执行期间被其它操作改动）。 */
    @SerialName("unavailable") UNAVAILABLE,

    /** 目录没有逐行内容。 */
    @SerialName("directory") DIRECTORY,

    /** 内容没有变化（如仅移动或重命名）。 */
    @SerialName("unchanged") UNCHANGED,
}

/**
 * 统一差异格式的一段改动。[oldStart] / [newStart] 为本段第一行在旧 / 新文件中的行号（1 起）；
 * 本段在某一侧没有行时，表示该侧在此位置之前的行数加一。
 * [lines] 每行以一个标记字符开头：空格为未变更、`+` 为新增、`-` 为删除；
 * 单独的 `\` 行表示上一行所在一侧缺少末尾换行（与 Git 一致）。
 */
@Serializable
data class FileChangeHunk(
    val oldStart: Int,
    val oldCount: Int,
    val newStart: Int,
    val newCount: Int,
    val lines: List<String>,
) {
    companion object {
        const val CONTEXT_MARK = ' '
        const val ADDED_MARK = '+'
        const val REMOVED_MARK = '-'
        const val NO_NEWLINE_MARK = "\\"
    }
}

/**
 * 按单个工具结果的总上限裁剪：依次保留各文件的 hunk，累计超过 [maxBytes] 后，
 * 其余文件的 hunk 改为不保存并标记 [FileChangeHunkOmission.SIZE_LIMIT]，计数保持不变。
 * 单文件超过 [FileChangeSummary.MAX_FILE_HUNK_BYTES] 的 hunk 同样丢弃。
 */
fun List<FileChangeSummary>.withinHunkBudget(
    maxBytes: Long = FileChangeSummary.MAX_RESULT_HUNK_BYTES,
    maxFileBytes: Long = FileChangeSummary.MAX_FILE_HUNK_BYTES,
): List<FileChangeSummary> {
    var remaining = maxBytes
    return map { summary ->
        if (summary.hunks.isNullOrEmpty()) return@map summary
        val bytes = summary.storedHunkBytes
        if (bytes > maxFileBytes || bytes > remaining) {
            summary.copy(hunks = null, hunksOmitted = FileChangeHunkOmission.SIZE_LIMIT)
        } else {
            remaining -= bytes
            summary
        }
    }
}

private fun utf8Bytes(text: String): Long {
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val char = text[index]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                index++
                4
            }
            else -> 3
        }
        index++
    }
    return bytes
}
