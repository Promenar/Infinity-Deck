package com.promenar.infinity.data.linux

import com.promenar.infinity.data.exec.BuiltinCommandOutput

/**
 * 有界的流式终端文本：保留未换行提示，过滤跨块 ANSI，回车覆盖当前行。
 *
 * 也充当无界面终端的应答方：gh、survey、bubbletea 等交互程序启动时会查询光标位置（`ESC[6n`）、
 * 设备属性与前景/背景色，真实终端会写回答复，否则程序一直阻塞在读取答复上、吞掉后续按键。
 * 识别到的查询答复由 [takeReplies] 取出，调用方写回伪终端输入端。
 */
class LinuxExecTerminalText(private val limit: Int, private val rows: Int = 40, private val columns: Int = 120) {
    private val history = StringBuilder()
    private val sequence = StringBuilder()
    private val replies = StringBuilder()
    private var lines = 0
    private val line = StringBuilder()
    private var committed = 0L
    private var lineLength = 0L
    private var carriage = false
    private var escape = 0

    fun accept(text: String): String = accept(text, null)

    /** 规范化的增量事件独立于有界快照，重复输出也会逐段发布。 */
    @Synchronized fun accept(text: String, onOutput: ((String) -> Unit)?): String {
        val complete = StringBuilder()
        val events = if (onOutput != null) StringBuilder() else null
        for (c in text) {
            if (escape != 0) {
                if (sequence.length < MAX_SEQUENCE) sequence.append(c)
                escape = when (escape) {
                    1 -> when (c) { '[' -> 2; ']', 'P', '_', '^' -> 3; in ' '..'/' -> 5; else -> 0 }
                    2 -> if (c in '@'..'~') 0 else 2
                    3 -> when(c) { '\u0007', '\u009c' -> 0; '\u001b' -> 4; else -> 3 }
                    4 -> if (c == '\\') 0 else 3
                    else -> if (c in '0'..'~') 0 else 5
                }
                if (escape == 0) answer(sequence.toString())
                continue
            }
            if (c == '\u001b') { escape = 1; sequence.clear(); continue }
            if (c == '\u009b') { escape = 2; sequence.clear().append('['); continue }
            if (c == '\u009d') { escape = 3; sequence.clear().append(']'); continue }
            when (c) {
                '\r' -> { carriage = true; events?.append(c) }
                '\n' -> {
                    events?.append(c)
                    val value = line.toString() + "\n"
                    complete.append(value); history.append(value); trim(history)
                    committed += lineLength + 1; line.clear(); lineLength = 0; carriage = false; lines++
                }
                '\b' -> if (line.isNotEmpty()) { events?.append(c); line.deleteCharAt(line.lastIndex); lineLength = (lineLength - 1).coerceAtLeast(0) }
                else -> if (c == '\t' || !c.isISOControl()) {
                    if (carriage) { line.clear(); lineLength = 0; carriage = false }
                    events?.append(c)
                    line.append(c); lineLength++; trim(line)
                }
            }
        }
        events?.takeIf { it.isNotEmpty() }?.let { onOutput?.invoke(it.toString()) }
        return complete.toString()
    }
    @Synchronized fun snapshot(): BuiltinCommandOutput {
        val text = (history.toString() + line).takeLast(limit).let { if (it.isNotEmpty() && it[0].isLowSurrogate()) it.drop(1) else it }
        return BuiltinCommandOutput(null, false, text, "", committed + lineLength, 0, committed + lineLength > text.length, false)
    }
    @Synchronized fun finish(): String = line.toString()

    /** 取出并清空待写回的查询答复。 */
    @Synchronized fun takeReplies(): String = replies.toString().also { replies.clear() }

    /** [body] 为 ESC 之后的完整序列；只答复只读查询，其余控制序列照旧丢弃。 */
    private fun answer(body: String) {
        val reply = when {
            body == "[6n" || body == "[?6n" -> {
                // 光标停在当前未换行内容之后；行号按已输出行数推算并限制在可见行内。
                val row = (lines + 1).coerceAtMost(rows)
                val column = if (carriage) 1 else (lineLength % columns + 1).toInt()
                "\u001b[$row;${column}R"
            }
            body == "[5n" -> "\u001b[0n"
            body == "[c" || body == "[0c" -> "\u001b[?1;2c"
            body == "[>c" || body == "[>0c" -> "\u001b[>0;10;0c"
            body == "[18t" -> "\u001b[8;$rows;${columns}t"
            body.startsWith("]10;?") -> "\u001b]10;rgb:ffff/ffff/ffff" + oscTerminator(body)
            body.startsWith("]11;?") -> "\u001b]11;rgb:0000/0000/0000" + oscTerminator(body)
            else -> null
        }
        if (reply != null) replies.append(reply)
    }

    /** 按查询使用的结束符（BEL 或 ST）答复。 */
    private fun oscTerminator(body: String): String = if (body.endsWith('\u0007')) "\u0007" else "\u001b\\"

    private companion object {
        const val MAX_SEQUENCE = 64
    }
    private fun trim(text: StringBuilder) { if (text.length > limit) text.delete(0, text.length - limit) }
}
