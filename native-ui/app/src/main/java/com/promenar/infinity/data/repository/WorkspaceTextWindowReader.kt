package com.promenar.infinity.data.repository

import com.promenar.infinity.domain.repository.ReadWindowRequest
import com.promenar.infinity.domain.repository.ReadWindowResult
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** 一次流式扫描的结果：窗口正文与续读位置，以及全文行数、字节数和可选 SHA-256。 */
internal data class WorkspaceTextWindowScan(
    val totalLines: Int,
    val startLine: Int,
    val endLine: Int,
    val startLineByteOffset: Long,
    val content: String,
    val nextLine: Int?,
    val nextLineByteOffset: Long,
    val byteCount: Long,
    val sha256: String?,
) {
    fun toResult(uuid: String, name: String, hash: String, lastModified: Long) = ReadWindowResult(
        uuid = uuid,
        name = name,
        totalLines = totalLines,
        startLine = startLine,
        endLine = endLine,
        startLineByteOffset = startLineByteOffset,
        content = content,
        hash = hash,
        lastModified = lastModified,
        nextLine = nextLine,
        nextLineByteOffset = nextLineByteOffset,
    )
}

internal fun workspaceTextWindowTooLarge(): WorkspaceTextPolicyException = WorkspaceTextPolicyException(
    WorkspaceTextErrorCode.INPUT_TOO_LARGE,
    "文件超过 100 MiB 分段读取上限，请改用专用文件处理能力。",
)

/**
 * 顺序扫描 [input]，只保留 [request] 窗口内的字节并按严格 UTF-8 解码。
 *
 * 行语义与 [workspaceTextLines] 一致：`\n`、`\r`、`\r\n` 均为行终止符，末尾终止符之后还有一个空行，
 * 空文件为 0 行；文件开头的 UTF-8 BOM 不计入正文。全文任意位置出现 NUL 字节都按非文本拒绝，
 * 窗口外的字节只统计行数，不做解码。
 */
internal fun scanWorkspaceTextWindow(
    input: InputStream,
    request: ReadWindowRequest,
    maxInputBytes: Long = ReadWindowRequest.MAX_FILE_BYTES,
    computeSha256: Boolean = false,
): WorkspaceTextWindowScan {
    val scanner = WorkspaceTextWindowScanner(request)
    val digest = if (computeSha256) MessageDigest.getInstance("SHA-256") else null
    val buffer = ByteArray(STREAM_BUFFER_BYTES)
    val head = ByteArray(UTF8_BOM_SIZE)
    var headSize = 0
    var headPending = true
    var total = 0L
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > maxInputBytes) throw workspaceTextWindowTooLarge()
        digest?.update(buffer, 0, count)
        var from = 0
        if (headPending) {
            while (headSize < UTF8_BOM_SIZE && from < count) head[headSize++] = buffer[from++]
            if (headSize < UTF8_BOM_SIZE) continue
            headPending = false
            if (!head.isUtf8Bom()) scanner.feed(head, 0, headSize)
        }
        scanner.feed(buffer, from, count - from)
    }
    if (headPending) scanner.feed(head, 0, headSize)
    val hash = digest?.digest()?.joinToString("") { "%02x".format(it) }
    return scanner.finish(total, hash)
}

private class WorkspaceTextWindowScanner(private val request: ReadWindowRequest) {
    private val firstLine = request.startLine
    private val lastLine = minOf(
        request.endLine?.toLong() ?: Long.MAX_VALUE,
        firstLine.toLong() + request.maxLines - 1,
    ).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    private val budget = request.maxContentBudget
    private val out = ByteArray(budget)
    private var size = 0
    private var used = 0

    private var line = 0
    private var lineByte = 0L
    private var pendingCr = false

    private var collecting = true
    private var inWindow = false
    private var lineStartSize = 0
    private var lineStartUsed = 0
    private var skipContinuation = false
    private var startOffset = request.lineByteOffset

    private var stopped = false
    private var stoppedEndLine = 0
    private var stoppedNextLine = 0
    private var stoppedNextOffset = 0L

    fun feed(bytes: ByteArray, offset: Int, length: Int) {
        var index = offset
        val end = offset + length
        while (index < end) {
            val b = bytes[index].toInt() and 0xFF
            index++
            if (b == 0) throw encodingFailure()
            if (pendingCr) {
                pendingCr = false
                if (b == LF) continue
            }
            if (line == 0) beginLine(1)
            if (b == CR || b == LF) {
                if (b == CR) pendingCr = true
                beginLine(line + 1)
                continue
            }
            if (inWindow) acceptContent(b)
            lineByte++
        }
    }

    fun finish(byteCount: Long, sha256: String?): WorkspaceTextWindowScan {
        clampStartOffset()
        val total = line
        if (total == 0 || firstLine > total) {
            return WorkspaceTextWindowScan(total, firstLine, firstLine - 1, 0, "", null, 0, byteCount, sha256)
        }
        val content = decodeStrictUtf8(out, size)
        if (stopped) {
            return WorkspaceTextWindowScan(
                total, firstLine, stoppedEndLine, startOffset, content,
                stoppedNextLine, stoppedNextOffset, byteCount, sha256,
            )
        }
        val endLine = minOf(lastLine, total)
        return WorkspaceTextWindowScan(
            total, firstLine, endLine, startOffset, content,
            (endLine + 1).takeIf { endLine < total }, 0, byteCount, sha256,
        )
    }

    private fun beginLine(next: Int) {
        clampStartOffset()
        line = next
        lineByte = 0
        inWindow = false
        if (!collecting || next < firstLine) return
        if (next > lastLine) {
            collecting = false
            return
        }
        if (next > firstLine) {
            if (used + JOINER_COST > budget) {
                stop(endLine = next - 1, nextLine = next, nextOffset = 0)
                return
            }
            lineStartSize = size
            lineStartUsed = used
            out[size++] = LF.toByte()
            used += JOINER_COST
        } else {
            skipContinuation = request.lineByteOffset > 0
        }
        inWindow = true
    }

    private fun acceptContent(b: Int) {
        if (line == firstLine) {
            if (lineByte < request.lineByteOffset) return
            if (skipContinuation) {
                // 偏移落在多字节字符中间时前移到下一个字符边界。
                if (b and 0xC0 == 0x80) {
                    startOffset = lineByte + 1
                    return
                }
                skipContinuation = false
            }
        }
        val cost = jsonEscapedCost(b)
        if (used + cost <= budget) {
            out[size++] = b.toByte()
            used += cost
            return
        }
        if (line != firstLine) {
            // 后续行放不下时整行留给下一页，保证续读从行首开始。
            size = lineStartSize
            used = lineStartUsed
            stop(endLine = line - 1, nextLine = line, nextOffset = 0)
            return
        }
        // 首行本身超出预算：在字符边界截断，下一页从同一行的剩余字节续读。
        var cut = size
        if (b and 0xC0 == 0x80) {
            var probe = size
            while (probe > 0 && (out[probe - 1].toInt() and 0xC0) == 0x80) probe--
            val leadIndex = probe - 1
            if (leadIndex >= 0 && size - leadIndex < utf8SequenceLength(out[leadIndex].toInt() and 0xFF)) cut = leadIndex
        }
        val removed = size - cut
        size = cut
        used -= removed
        stop(endLine = line, nextLine = line, nextOffset = lineByte - removed)
    }

    private fun stop(endLine: Int, nextLine: Int, nextOffset: Long) {
        stopped = true
        collecting = false
        inWindow = false
        stoppedEndLine = endLine
        stoppedNextLine = nextLine
        stoppedNextOffset = nextOffset
    }

    /** 偏移超过起始行长度时按行尾计。 */
    private fun clampStartOffset() {
        if (line == firstLine && startOffset > lineByte) startOffset = lineByte
    }
}

/** 首字节声明的 UTF-8 序列长度；非首字节返回 0。 */
private fun utf8SequenceLength(lead: Int): Int = when {
    lead and 0xE0 == 0xC0 -> 2
    lead and 0xF0 == 0xE0 -> 3
    lead and 0xF8 == 0xF0 -> 4
    else -> 0
}

private fun jsonEscapedCost(b: Int): Int = when {
    b == '"'.code || b == '\\'.code -> 2
    b == 0x08 || b == 0x09 || b == 0x0C -> 2
    b < 0x20 -> 6
    else -> 1
}

private fun decodeStrictUtf8(bytes: ByteArray, length: Int): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes, 0, length))
        .toString()
} catch (_: CharacterCodingException) {
    throw encodingFailure()
}

private fun encodingFailure() =
    WorkspaceTextPolicyException(WorkspaceTextErrorCode.INVALID_TEXT_ENCODING, "文件不是可安全编辑的 UTF-8 文本。")

private fun ByteArray.isUtf8Bom(): Boolean =
    size >= UTF8_BOM_SIZE && this[0] == 0xEF.toByte() && this[1] == 0xBB.toByte() && this[2] == 0xBF.toByte()

private const val LF = 0x0A
private const val CR = 0x0D
/** 行间换行在 JSON 中转义为 `\n`。 */
private const val JOINER_COST = 2
private const val UTF8_BOM_SIZE = 3
private const val STREAM_BUFFER_BYTES = 64 * 1024
