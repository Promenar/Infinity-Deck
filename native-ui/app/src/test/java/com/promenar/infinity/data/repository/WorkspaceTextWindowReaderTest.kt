package com.promenar.infinity.data.repository

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.repository.ReadWindowRequest
import com.promenar.infinity.infra.util.Sha256Utils
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class WorkspaceTextWindowReaderTest {
    private val lines = List(LINE_COUNT) { index -> "第${index + 1}行 " + "x".repeat(40) }
    private val bytes = lines.joinToString("\n").toByteArray(Charsets.UTF_8)

    @Test
    fun `大于1MiB文件首页返回请求行并给出下一页`() {
        assertThat(bytes.size).isGreaterThan(1024 * 1024)

        val scan = scan(bytes, ReadWindowRequest(startLine = 1, endLine = 200))

        assertThat(scan.totalLines).isEqualTo(LINE_COUNT)
        assertThat(scan.byteCount).isEqualTo(bytes.size.toLong())
        assertThat(scan.startLine).isEqualTo(1)
        assertThat(scan.endLine).isEqualTo(200)
        assertThat(scan.content).isEqualTo(lines.subList(0, 200).joinToString("\n"))
        assertThat(scan.nextLine).isEqualTo(201)
        assertThat(scan.nextLineByteOffset).isEqualTo(0)
    }

    @Test
    fun `大于1MiB文件中段只返回窗口内的行`() {
        val scan = scan(bytes, ReadWindowRequest(startLine = 15_000, endLine = 15_999))

        assertThat(scan.content).isEqualTo(lines.subList(14_999, 15_999).joinToString("\n"))
        assertThat(scan.endLine).isEqualTo(15_999)
        assertThat(scan.nextLine).isEqualTo(16_000)
    }

    @Test
    fun `大于1MiB文件末页读到末尾且没有后续`() {
        val scan = scan(bytes, ReadWindowRequest(startLine = LINE_COUNT - 99))

        assertThat(scan.endLine).isEqualTo(LINE_COUNT)
        assertThat(scan.content).isEqualTo(lines.takeLast(100).joinToString("\n"))
        assertThat(scan.nextLine).isNull()
    }

    @Test
    fun `未给结束行时单次最多返回2000行`() {
        val scan = scan(bytes, ReadWindowRequest(startLine = 1))

        assertThat(scan.endLine).isEqualTo(ReadWindowRequest.MAX_LINES)
        assertThat(scan.nextLine).isEqualTo(ReadWindowRequest.MAX_LINES + 1)
    }

    @Test
    fun `字节预算在行边界截断且按续读位置拼回原文`() {
        val wide = List(3000) { index -> "${index + 1}:" + "y".repeat(200) }
        val text = wide.joinToString("\n")
        val pages = mutableListOf<String>()
        var next: Int? = 1
        var pageCount = 0
        while (next != null) {
            val scan = scan(text.toByteArray(), ReadWindowRequest(startLine = next))
            assertThat(scan.nextLineByteOffset).isEqualTo(0)
            assertThat(scan.content.length).isAtMost(ReadWindowRequest.MAX_CONTENT_BUDGET)
            pages += scan.content
            next = scan.nextLine
            pageCount++
        }

        assertThat(pageCount).isGreaterThan(1)
        assertThat(pages.joinToString("\n")).isEqualTo(text)
    }

    @Test
    fun `超长单行在字符边界截断并可按字节偏移续读`() {
        val longLine = "汉字a".repeat(250_000)
        val text = "$longLine\n尾行"
        val data = text.toByteArray()
        assertThat(data.size).isGreaterThan(1024 * 1024)
        val parts = StringBuilder()
        var offset = 0L
        var pageCount = 0
        while (true) {
            val scan = scan(data, ReadWindowRequest(startLine = 1, endLine = 1, lineByteOffset = offset))
            assertThat(scan.totalLines).isEqualTo(2)
            assertThat(scan.startLineByteOffset).isEqualTo(offset)
            parts.append(scan.content)
            pageCount++
            if (scan.nextLine != 1) {
                assertThat(scan.nextLine).isEqualTo(2)
                break
            }
            assertThat(scan.nextLineByteOffset).isGreaterThan(offset)
            offset = scan.nextLineByteOffset
        }

        assertThat(pageCount).isGreaterThan(1)
        assertThat(parts.toString()).isEqualTo(longLine)
        assertThat(scan(data, ReadWindowRequest(startLine = 2)).content).isEqualTo("尾行")
    }

    @Test
    fun `字节偏移落在多字节字符中间时前移到下一个字符`() {
        val scan = scan("汉字\n".toByteArray(), ReadWindowRequest(startLine = 1, endLine = 1, lineByteOffset = 1))

        assertThat(scan.startLineByteOffset).isEqualTo(3)
        assertThat(scan.content).isEqualTo("字")
    }

    @Test
    fun `字节偏移超过行长时按行尾处理`() {
        val scan = scan("ab\ncd".toByteArray(), ReadWindowRequest(startLine = 1, lineByteOffset = 99))

        assertThat(scan.startLineByteOffset).isEqualTo(2)
        assertThat(scan.content).isEqualTo("\ncd")
    }

    @Test
    fun `预算按JSON转义长度计算`() {
        val quotes = "\"".repeat(150_000)

        val scan = scan(quotes.toByteArray(), ReadWindowRequest(startLine = 1))

        assertThat(scan.content.length).isEqualTo(ReadWindowRequest.MAX_CONTENT_BUDGET / 2)
        assertThat(scan.nextLine).isEqualTo(1)
        assertThat(scan.nextLineByteOffset).isEqualTo((ReadWindowRequest.MAX_CONTENT_BUDGET / 2).toLong())
    }

    @Test
    fun `起始行超出文件末尾时返回空窗口`() {
        val scan = scan(bytes, ReadWindowRequest(startLine = LINE_COUNT + 10))

        assertThat(scan.totalLines).isEqualTo(LINE_COUNT)
        assertThat(scan.endLine).isLessThan(scan.startLine)
        assertThat(scan.content).isEmpty()
        assertThat(scan.nextLine).isNull()
    }

    @Test
    fun `结束行超出文件末尾时截到最后一行`() {
        val scan = scan("a\nb\nc".toByteArray(), ReadWindowRequest(startLine = 2, endLine = 50))

        assertThat(scan.endLine).isEqualTo(3)
        assertThat(scan.content).isEqualTo("b\nc")
        assertThat(scan.nextLine).isNull()
    }

    @Test
    fun `CRLF CR与尾换行的行数与文本策略一致且跨读取块处理`() {
        val text = "a\r\nb\rc\n\r\nd\r"
        val scan = scan(text.toByteArray(), ReadWindowRequest(startLine = 1), oneBytePerRead = true)

        assertThat(scan.totalLines).isEqualTo(workspaceTextLineCount(text))
        assertThat(scan.content).isEqualTo(workspaceTextLines(text).joinToString("\n"))
        assertThat(scan.content).doesNotContain("\r")
    }

    @Test
    fun `空文件与只有BOM的文件为0行`() {
        listOf(ByteArray(0), UTF8_BOM).forEach { data ->
            val scan = scan(data, ReadWindowRequest(startLine = 1))
            assertThat(scan.totalLines).isEqualTo(0)
            assertThat(scan.content).isEmpty()
            assertThat(scan.nextLine).isNull()
        }
    }

    @Test
    fun `开头BOM不进入正文但计入sha256`() {
        val data = UTF8_BOM + "x\ny".toByteArray()

        val scan = scanWorkspaceTextWindow(SlowInputStream(data), ReadWindowRequest(startLine = 1), computeSha256 = true)

        assertThat(scan.content).isEqualTo("x\ny")
        assertThat(scan.sha256).isEqualTo(Sha256Utils.hash(data))
    }

    @Test
    fun `窗口外的NUL字节也按非文本拒绝`() {
        val data = "a\nb\n".toByteArray() + byteArrayOf(0) + "c".toByteArray()

        val failure = runCatching { scan(data, ReadWindowRequest(startLine = 1, endLine = 1)) }.exceptionOrNull()

        assertThat((failure as WorkspaceTextPolicyException).code).isEqualTo(WorkspaceTextErrorCode.INVALID_TEXT_ENCODING)
    }

    @Test
    fun `窗口内非法UTF8拒绝`() {
        val data = "ok\n".toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)

        val failure = runCatching { scan(data, ReadWindowRequest(startLine = 2)) }.exceptionOrNull()

        assertThat((failure as WorkspaceTextPolicyException).code).isEqualTo(WorkspaceTextErrorCode.INVALID_TEXT_ENCODING)
    }

    @Test
    fun `超过总上限时拒绝`() {
        val failure = runCatching {
            scanWorkspaceTextWindow(ByteArrayInputStream(bytes), ReadWindowRequest(startLine = 1), maxInputBytes = 1024)
        }.exceptionOrNull()

        assertThat((failure as WorkspaceTextPolicyException).code).isEqualTo(WorkspaceTextErrorCode.INPUT_TOO_LARGE)
    }

    private fun scan(data: ByteArray, request: ReadWindowRequest, oneBytePerRead: Boolean = false) =
        scanWorkspaceTextWindow(if (oneBytePerRead) SlowInputStream(data) else ByteArrayInputStream(data), request)

    /** 每次只返回一个字节，覆盖 CRLF 与 BOM 跨读取块的情形。 */
    private class SlowInputStream(private val data: ByteArray) : InputStream() {
        private var position = 0
        override fun read(): Int = if (position < data.size) data[position++].toInt() and 0xFF else -1
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            val value = read()
            if (value < 0) return -1
            buffer[offset] = value.toByte()
            return 1
        }
    }

    private companion object {
        const val LINE_COUNT = 30_000
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
