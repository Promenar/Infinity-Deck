package com.promenar.infinity.data.mediagen.comfy

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 定界头尾双缓冲：保留最先输出的 [headLimit] 字符（常见脚本头、命令行/阶段信息）
 * 与最新输出的 [tailLimit] 字符（Traceback、最终异常与退出摘要），中间溢出部分安全折叠。
 */
internal class HeadTailBuffer(
    private val headLimit: Int = 8_192,
    private val tailLimit: Int = 24_576,
) {
    private val head = StringBuilder()
    private val tail = StringBuilder()
    private var droppedChars: Long = 0L

    @Synchronized
    fun appendLine(line: String) {
        append(line)
        append("\n")
    }

    @Synchronized
    fun append(text: String) {
        if (text.isEmpty()) return
        var index = 0
        if (head.length < headLimit) {
            val takeHead = minOf(headLimit - head.length, text.length)
            head.append(text, 0, takeHead)
            index = takeHead
        }
        if (index < text.length) {
            val rest = text.substring(index)
            if (tailLimit <= 0) {
                droppedChars += rest.length
                return
            }
            if (rest.length >= tailLimit) {
                droppedChars += tail.length + (rest.length - tailLimit).toLong()
                tail.setLength(0)
                tail.append(rest, rest.length - tailLimit, rest.length)
            } else {
                val overflow = (tail.length + rest.length) - tailLimit
                if (overflow > 0) {
                    tail.delete(0, overflow)
                    droppedChars += overflow
                }
                tail.append(rest)
            }
        }
    }

    @Synchronized
    fun headText(): String = head.toString()

    @Synchronized
    fun tailText(): String = tail.toString()

    @Synchronized
    fun combined(): String = when {
        tail.isEmpty() -> head.toString()
        droppedChars == 0L -> head.toString() + tail.toString()
        else -> "${head}\n…[已省略 ${droppedChars} 字符]…\n${tail}"
    }
}

/**
 * 统一提取 ComfyUI `/prompt` 拒绝、`/history` `execution_error` 与提交器子进程失败摘要：
 * - 主动剥离 `current_inputs` / `current_outputs` 等巨型 Tensor 数据块，避免淹没真正的异常类型、节点与堆栈；
 * - 强制脱敏代理凭据、Authorization 头、Bearer/Basic/URL userinfo 与 token 字段。
 */
internal object ComfyErrorDiagnostics {
    private const val MAX_DIAGNOSTIC_CHARS = 1_200

    private val authHeaderRegex = Regex(
        """(?i)\b(Proxy-Authorization|Authorization)\s*[:=]\s*([^\r\n,;]+)""",
    )
    private val bearerBasicRegex = Regex(
        """(?i)\b(Bearer|Basic)\s+[A-Za-z0-9._~+/=-]{6,}""",
    )
    private val urlCredentialsRegex = Regex(
        """(https?|socks5h?)://([^:/\s@]+):([^@/\s]+)@""",
        RegexOption.IGNORE_CASE,
    )
    private val secretKvRegex = Regex(
        """(?i)(password|passwd|api[_-]?key|secret|token)\s*[:=]\s*(['"]?)[^\s,'"&}{]{4,}\2""",
    )
    private val longTensorLineRegex = Regex("""(?:\[?\s*-?\d+(?:\.\d+)?(?:e[+-]?\d+)?\s*,?){24,}""", RegexOption.IGNORE_CASE)

    fun redactSensitive(text: String): String {
        if (text.isBlank()) return text
        var result = urlCredentialsRegex.replace(text) { m -> "${m.groupValues[1]}://***:***@" }
        result = authHeaderRegex.replace(result) { m -> "${m.groupValues[1]}: ***" }
        result = bearerBasicRegex.replace(result) { m -> "${m.groupValues[1]} ***" }
        result = secretKvRegex.replace(result) { m -> "${m.groupValues[1]}=***" }
        return result
    }

    fun stripLargePayloads(text: String): String {
        if (text.isBlank()) return text
        var cleaned = stripKeyedBlocks(text, setOf("current_inputs", "current_outputs"))
        cleaned = longTensorLineRegex.replace(cleaned, "[…Tensor 数值已省略…]")
        return cleaned
    }

    fun summarizeHistoryError(history: JsonObject, promptId: String): String? {
        val entry = history[promptId] as? JsonObject ?: return null
        val status = entry["status"] as? JsonObject ?: return null
        val messages = status["messages"] as? JsonArray ?: return null
        for (raw in messages) {
            val pair = raw as? JsonArray ?: continue
            val kind = (pair.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: continue
            val payload = pair.getOrNull(1) as? JsonObject
            if (kind == "execution_interrupted") {
                val nodeId = payload?.stringField("node_id")
                val nodeType = payload?.stringField("node_type")
                val who = formatNodeLabel(nodeId, nodeType)
                return listOfNotNull("执行被中断", who).joinToString(" ").take(MAX_DIAGNOSTIC_CHARS)
            }
            if (kind == "execution_error" && payload != null) {
                return formatExecutionErrorObject(payload)
            }
        }
        return null
    }

    fun summarizePromptRejection(response: JsonObject): String? {
        val parts = mutableListOf<String>()
        val err = response["error"]
        if (err != null && err != JsonNull) {
            when (err) {
                is JsonObject -> {
                    val type = err.stringField("type")
                    val message = err.stringField("message")
                    val details = err.stringField("details")
                    val head = listOfNotNull(type, message, details?.take(240)).joinToString("：")
                    if (head.isNotBlank()) parts += head
                }
                is JsonPrimitive -> err.contentOrNull?.takeIf { it.isNotBlank() }?.let { parts += it }
                else -> Unit
            }
        }
        val nodeErrors = response["node_errors"] as? JsonObject
        if (!nodeErrors.isNullOrEmpty()) {
            val renderedNodes = nodeErrors.entries.take(5).mapNotNull { (nodeId, rawNode) ->
                val obj = rawNode as? JsonObject ?: return@mapNotNull null
                val classType = obj.stringField("class_type")
                val nodeLabel = formatNodeLabel(nodeId, classType) ?: "节点 #$nodeId"
                val errs = (obj["errors"] as? JsonArray).orEmpty().take(3).mapNotNull { item ->
                    val errObj = item as? JsonObject ?: return@mapNotNull null
                    val msg = errObj.stringField("message")
                    val det = errObj.stringField("details")
                    listOfNotNull(msg, det?.take(160)).joinToString(" - ").takeIf { it.isNotBlank() }
                }
                if (errs.isEmpty()) nodeLabel else "$nodeLabel: ${errs.joinToString("；")}"
            }
            if (renderedNodes.isNotEmpty()) parts += renderedNodes.joinToString(" | ")
        }
        if (parts.isEmpty()) return null
        return redactSensitive(stripLargePayloads(parts.joinToString("；"))).take(MAX_DIAGNOSTIC_CHARS)
    }

    fun summarizeSubprocessFailure(
        exitCode: Int,
        stderrHead: String,
        stderrTail: String,
        stdoutHead: String,
        stdoutTail: String,
    ): String {
        val stderrClean = stripLargePayloads(combineHeadAndTail(stderrHead, stderrTail))
        val stdoutClean = stripLargePayloads(combineHeadAndTail(stdoutHead, stdoutTail))
        val candidates = listOf(stderrClean, stdoutClean).filter { it.isNotBlank() }

        for (text in candidates) {
            extractStructuredComfyError(text)?.let { structured ->
                return redactSensitive(structured).take(MAX_DIAGNOSTIC_CHARS)
            }
        }
        for (text in candidates) {
            extractPythonTraceback(text)?.let { tb ->
                return redactSensitive(tb).take(MAX_DIAGNOSTIC_CHARS)
            }
        }
        val fallback = stderrClean.trim().takeLast(800).ifBlank { stdoutClean.trim().takeLast(600) }
        val cleanFallback = redactSensitive(fallback).trim()
        return cleanFallback.ifBlank { "子进程非零退出（退出码 $exitCode），未输出可见错误信息" }
            .take(MAX_DIAGNOSTIC_CHARS)
    }

    private fun combineHeadAndTail(head: String, tail: String): String = when {
        tail.isBlank() -> head
        head.isBlank() -> tail
        else -> "$head\n$tail"
    }

    private fun extractStructuredComfyError(text: String): String? {
        // 1. 优先尝试解析单行 JSON 负载
        val lines = text.lineSequence().toList()
        for (line in lines.asReversed()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("{")) continue
            val obj = runCatching { Json.parseToJsonElement(trimmed) as? JsonObject }.getOrNull() ?: continue
            val direct = formatExecutionErrorObject(obj)
            if (direct != null) return direct
            (obj["error"] as? JsonObject)?.let { inner ->
                formatExecutionErrorObject(inner)?.let { return it }
            }
        }
        // 2. 兼容 Python pprint/str(dict) 输出或 ComfyUI execution_error 关键字段提取
        val nodeId = regexField(text, "node_id")
        val nodeType = regexField(text, "node_type")
        val excType = regexField(text, "exception_type")
        val excMsg = regexField(text, "exception_message")
        if (excType == null && excMsg == null && nodeType == null) return null
        val nodeLabel = formatNodeLabel(nodeId, nodeType)
        val excHead = listOfNotNull(excType, excMsg).joinToString(": ")
        return listOfNotNull(nodeLabel, excHead.takeIf { it.isNotBlank() }).joinToString(" ")
            .takeIf { it.isNotBlank() }
    }

    private fun formatExecutionErrorObject(payload: JsonObject): String? {
        val nodeId = payload.stringField("node_id")
        val nodeType = payload.stringField("node_type")
        val excType = payload.stringField("exception_type")
        val excMsg = payload.stringField("exception_message")
        if (nodeType == null && excType == null && excMsg == null) return null
        val nodeLabel = formatNodeLabel(nodeId, nodeType)
        val excPart = listOfNotNull(excType, excMsg?.let(::stripLargePayloads)?.trim()?.take(360))
            .joinToString(": ")
        val frames = (payload["traceback"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotBlank() }
            .takeLast(4)
            .joinToString(" <- ") { it.replace(Regex("""\s+"""), " ").take(140) }
        val combined = buildList {
            nodeLabel?.let(::add)
            if (excPart.isNotBlank()) add(excPart)
            if (frames.isNotBlank()) add("堆栈: $frames")
        }.joinToString(" | ")
        return redactSensitive(combined).take(MAX_DIAGNOSTIC_CHARS)
    }

    private fun extractPythonTraceback(text: String): String? {
        val marker = "Traceback (most recent call last):"
        val idx = text.lastIndexOf(marker)
        if (idx < 0) return null
        val slice = text.substring(idx).lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotBlank() }
            .toList()
        if (slice.size <= 1) return slice.firstOrNull()
        val head = slice.take(1)
        val body = if (slice.size > 9) listOf("  …[略 ${slice.size - 9} 行]") + slice.takeLast(8) else slice.drop(1)
        return (head + body).joinToString("\n")
    }

    private fun regexField(text: String, key: String): String? {
        val re = Regex("""['"]$key['"]\s*:\s*(['"])((?:\\.|(?!\1).){1,320})\1""")
        return re.find(text)?.groupValues?.get(2)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun formatNodeLabel(nodeId: String?, nodeType: String?): String? = when {
        !nodeId.isNullOrBlank() && !nodeType.isNullOrBlank() -> "[节点 #$nodeId ($nodeType)]"
        !nodeType.isNullOrBlank() -> "[节点 $nodeType]"
        !nodeId.isNullOrBlank() -> "[节点 #$nodeId]"
        else -> null
    }

    private fun JsonObject.stringField(key: String): String? {
        val v = this[key] ?: return null
        if (v is JsonNull) return null
        return (v as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotBlank() }
    }

    /** 基于括号平衡剔除 `'current_inputs': {...}` / `"current_inputs": {...}` 等超大嵌套字典。 */
    private fun stripKeyedBlocks(text: String, keys: Set<String>): String {
        val keyPattern = keys.joinToString("|") { Regex.escape(it) }
        val trigger = Regex("""(['"])($keyPattern)\1\s*:\s*[\{\[]""")
        val out = StringBuilder(text.length)
        var cursor = 0
        while (cursor < text.length) {
            val match = trigger.find(text, cursor) ?: break
            out.append(text, cursor, match.range.first)
            val keyName = match.groupValues[2]
            val openerIndex = match.range.last
            val endIndex = findMatchingBracket(text, openerIndex)
            if (endIndex < 0) {
                out.append("'$keyName': […大数据已省略…]")
                cursor = text.length
                break
            } else {
                out.append("'$keyName': […大数据已省略…]")
                cursor = endIndex + 1
            }
        }
        if (cursor < text.length) out.append(text, cursor, text.length)
        return out.toString()
    }

    private fun findMatchingBracket(text: String, openerIndex: Int): Int {
        val open = text[openerIndex]
        val close = if (open == '{') '}' else ']'
        var depth = 0
        var inString: Char? = null
        var escaped = false
        for (i in openerIndex until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (inString != null) {
                if (c == '\\') escaped = true
                else if (c == inString) inString = null
                continue
            }
            if (c == '\'' || c == '"') {
                inString = c
            } else if (c == open) {
                depth++
            } else if (c == close) {
                depth--
                if (depth == 0) return i
            }
        }
        return -1
    }
}
