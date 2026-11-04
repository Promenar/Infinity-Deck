package com.promenar.infinity.ui.chat.diff

import com.promenar.infinity.data.model.FileChangeHunk
import com.promenar.infinity.data.model.FileChangeHunkOmission
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.FileChangeType
import com.promenar.infinity.ui.common.diff.DiffLineKind
import com.promenar.infinity.ui.common.diff.DiffSegment
import com.promenar.infinity.ui.common.diff.FileChangeKind
import com.promenar.infinity.ui.common.diff.FileDiffContent
import com.promenar.infinity.ui.common.diff.FileDiffLimits
import com.promenar.infinity.ui.common.diff.FileDiffModel
import com.promenar.infinity.ui.common.diff.FileDiffSummaryReason
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** 文件在某一时刻的内容快照；除 [Text] 外都不能逐行比较。 */
sealed interface FileSnapshot {
    data class Text(val text: String) : FileSnapshot

    /** 含 NUL 或不是合法 UTF-8。 */
    data object Binary : FileSnapshot

    /** 超过快照读取上限，未读取内容。 */
    data object TooLarge : FileSnapshot

    data object Directory : FileSnapshot

    data object Missing : FileSnapshot

    /** 读取失败。 */
    data object Unavailable : FileSnapshot
}

/**
 * 由写入前后的快照生成 [FileChangeSummary]。计数与降级规则与 [FileDiffModel.fromTexts] 一致：
 * 超过 [FileDiffLimits] 的行数或字节阈值标为“大文件”并尽量给出计数，二进制不给计数，
 * hunk 只在逐行差异可用且文本不超过 [FileChangeSummary.MAX_FILE_HUNK_BYTES] 时保存。
 * 纯计算，输入可达 [SNAPSHOT_MAX_BYTES]，应在后台线程调用。
 */
object FileChangeSummaries {
    /** 计数用快照的读取上限；超过时只标“大文件”，不给计数。 */
    const val SNAPSHOT_MAX_BYTES: Long = 2L * 1024L * 1024L

    /** 严格按 UTF-8 解码并去掉 BOM；含 NUL 或解码失败视为二进制。 */
    fun decode(bytes: ByteArray): FileSnapshot {
        if (bytes.any { it == 0.toByte() }) return FileSnapshot.Binary
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return FileSnapshot.Binary
        }
        return FileSnapshot.Text(text.removePrefix("\uFEFF"))
    }

    fun created(path: String, after: FileSnapshot, limits: FileDiffLimits = FileDiffLimits.Default): FileChangeSummary =
        contentChange(path, FileChangeType.CREATED, FileSnapshot.Text(""), after, limits)

    fun modified(
        path: String,
        before: FileSnapshot,
        after: FileSnapshot,
        limits: FileDiffLimits = FileDiffLimits.Default,
    ): FileChangeSummary = contentChange(path, FileChangeType.MODIFIED, before, after, limits)

    fun deleted(path: String, before: FileSnapshot, limits: FileDiffLimits = FileDiffLimits.Default): FileChangeSummary =
        contentChange(path, FileChangeType.DELETED, before, FileSnapshot.Text(""), limits).let { summary ->
            if (summary.hunks == null) summary else summary.copy(hunks = null, hunksOmitted = FileChangeHunkOmission.PERMANENT_DELETE)
        }

    /** 移动或重命名不改内容：计数为 0，不保存 hunk。 */
    fun moved(fromPath: String, toPath: String, directory: Boolean): FileChangeSummary = FileChangeSummary(
        path = toPath,
        change = FileChangeType.MOVED,
        fromPath = fromPath,
        added = 0,
        removed = 0,
        directory = directory,
        hunksOmitted = if (directory) FileChangeHunkOmission.DIRECTORY else FileChangeHunkOmission.UNCHANGED,
    )

    private fun contentChange(
        path: String,
        type: FileChangeType,
        before: FileSnapshot,
        after: FileSnapshot,
        limits: FileDiffLimits,
    ): FileChangeSummary {
        val base = FileChangeSummary(path = path, change = type)
        val snapshots = listOf(before, after)
        return when {
            snapshots.any { it is FileSnapshot.Directory } ->
                base.copy(directory = true, hunksOmitted = FileChangeHunkOmission.DIRECTORY)
            snapshots.any { it is FileSnapshot.Binary } ->
                base.copy(binary = true, hunksOmitted = FileChangeHunkOmission.BINARY)
            snapshots.any { it is FileSnapshot.TooLarge } ->
                base.copy(large = true, hunksOmitted = FileChangeHunkOmission.LARGE)
            before !is FileSnapshot.Text || after !is FileSnapshot.Text ->
                base.copy(hunksOmitted = FileChangeHunkOmission.UNAVAILABLE)
            else -> fromModel(
                base,
                FileDiffModel.fromTexts(before.text, after.text, changeKind = type.diffKind(), limits = limits),
            )
        }
    }

    private fun fromModel(base: FileChangeSummary, model: FileDiffModel): FileChangeSummary {
        val stats = model.stats
        val counted = base.copy(
            added = stats?.added,
            removed = stats?.removed,
            approximate = stats?.isApproximate == true,
        )
        return when (val content = model.content) {
            FileDiffContent.Binary -> base.copy(binary = true, hunksOmitted = FileChangeHunkOmission.BINARY)
            is FileDiffContent.Summary -> when (content.reason) {
                FileDiffSummaryReason.LARGE_FILE -> counted.copy(large = true, hunksOmitted = FileChangeHunkOmission.LARGE)
                FileDiffSummaryReason.TOO_COMPLEX -> counted.copy(hunksOmitted = FileChangeHunkOmission.TOO_COMPLEX)
            }
            is FileDiffContent.Lines -> {
                val hunks = hunksOf(content.segments)
                when {
                    hunks.isEmpty() -> counted.copy(hunksOmitted = FileChangeHunkOmission.UNCHANGED)
                    else -> counted.copy(hunks = hunks).let { summary ->
                        if (summary.storedHunkBytes > FileChangeSummary.MAX_FILE_HUNK_BYTES) {
                            counted.copy(hunksOmitted = FileChangeHunkOmission.SIZE_LIMIT)
                        } else summary
                    }
                }
            }
        }
    }

    /** 每个始终显示的区段对应一个 hunk；起始行号按此前各区段的行数推算，与 `MyersDiff.computeHunks` 一致。 */
    private fun hunksOf(segments: List<DiffSegment>): List<FileChangeHunk> {
        val hunks = ArrayList<FileChangeHunk>()
        var oldSeen = 0
        var newSeen = 0
        segments.forEach { segment ->
            val oldCount = segment.lines.count { it.kind != DiffLineKind.ADDED }
            val newCount = segment.lines.count { it.kind != DiffLineKind.REMOVED }
            if (segment is DiffSegment.Shown && segment.lines.any { it.kind != DiffLineKind.CONTEXT }) {
                val lines = ArrayList<String>(segment.lines.size)
                segment.lines.forEach { line ->
                    val mark = when (line.kind) {
                        DiffLineKind.CONTEXT -> FileChangeHunk.CONTEXT_MARK
                        DiffLineKind.ADDED -> FileChangeHunk.ADDED_MARK
                        DiffLineKind.REMOVED -> FileChangeHunk.REMOVED_MARK
                    }
                    lines += mark + line.content
                    if (line.missingFinalNewline) lines += FileChangeHunk.NO_NEWLINE_MARK
                }
                hunks += FileChangeHunk(oldSeen + 1, oldCount, newSeen + 1, newCount, lines)
            }
            oldSeen += oldCount
            newSeen += newCount
        }
        return hunks
    }

    private fun FileChangeType.diffKind(): FileChangeKind = when (this) {
        FileChangeType.CREATED -> FileChangeKind.CREATED
        FileChangeType.DELETED -> FileChangeKind.DELETED
        FileChangeType.MODIFIED, FileChangeType.MOVED -> FileChangeKind.MODIFIED
    }
}
