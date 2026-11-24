package com.promenar.infinity.ui.linux.output

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 完整输出查看器的输入：会话里某次命令类工具调用。
 *
 * [messageId] 是发起工具调用的助手消息 id（其 `execution_steps` 中含该调用的 `tool_call` 与 `tool_result` 步骤），
 * [toolCallId] 是协议层工具调用 id（`ExecutionStep.toolCallId`）。
 */
data class CommandOutputSource(
    val sessionId: String,
    val messageId: String,
    val toolCallId: String,
)

/** 命令实际运行的环境，决定标签行的“运行环境”文案。 */
enum class CommandRuntime { LINUX, SYSTEM, TERMUX, SSH, PYTHON, UNKNOWN }

/** 输出被截断的原因，决定界面说明文案。 */
enum class CommandTruncation {
    /** 执行器只保留尾部（内置执行器每路 32,000 字符）。 */
    TAIL_LIMIT,

    /** 失败结果只保存了有界摘要（总长约 4,000 字符），输出保留尾部。 */
    FAILURE_SUMMARY,

    /** 后台任务状态查询只带回日志尾部。 */
    LOG_TAIL,
}

data class CommandStream(
    /** 保存下来的原始文本（可能含 ANSI 控制序列）。 */
    val raw: String,
    /** 原始长度（UTF-16 单元）；未知时为 null。 */
    val originalLength: Long?,
    val truncation: CommandTruncation?,
) {
    val truncated: Boolean get() = truncation != null

    /** 保存下来部分的 UTF-8 字节数。 */
    val retainedBytes: Long get() = utf8Length(raw)

    companion object {
        val EMPTY = CommandStream("", 0L, null)
    }
}

data class CommandOutput(
    val toolName: String?,
    /** 单行展示用的命令；未知时为 null。 */
    val commandLine: String?,
    /** 可在终端重跑的完整命令；只有在本机 shell 中执行的命令才有。 */
    val rerunCommand: String?,
    val exitCode: Int?,
    val timedOut: Boolean,
    val errorCode: String?,
    val durationMs: Long?,
    val runtime: CommandRuntime,
    val stdout: CommandStream,
    val stderr: CommandStream,
    /** 输出来自合并日志（后台任务），不区分 stdout 与 stderr。 */
    val combinedOnly: Boolean,
    /** 后台任务完整日志在会话工作区中的路径。 */
    val logPath: String? = null,
) {
    val truncated: Boolean get() = stdout.truncated || stderr.truncated
}

/** 解析命令类工具的调用参数与结果内容；字段名以 `CommandToolResult.toJson()` 与后台任务结果为准。 */
object CommandOutputParser {
    /** `ToolFailureSummary` 写入失败结果时的前缀。 */
    const val FAILURE_PREFIX = "工具执行失败："

    private val TRUNCATION_MARKER = Regex("""^…\(已截断，原始 (\d+) 字符\)""")
    private val SHELL_TOOLS = setOf("shell_exec")
    private val COMMAND_TOOLS = setOf("shell_exec", "git_exec", "python_exec", "termux_exec", "ssh_exec", "job_status", "job_wait", "job_input")

    fun isCommandTool(toolName: String?): Boolean = toolName in COMMAND_TOOLS

    fun parse(
        toolName: String?,
        argumentsJson: String?,
        resultContent: String,
        durationMs: Long?,
    ): CommandOutput {
        val arguments = parseObject(argumentsJson)
        val failureSummary = resultContent.startsWith(FAILURE_PREFIX)
        val body = if (failureSummary) resultContent.removePrefix(FAILURE_PREFIX) else resultContent
        val result = parseObject(body)
        val gitCommand = (arguments?.get("args") as? kotlinx.serialization.json.JsonArray)
            ?.takeIf { toolName == "git_exec" }?.let { "git $it" }
        val fullCommand = gitCommand ?: arguments?.text("command") ?: arguments?.text("code") ?: result?.text("summary")
        val commandLine = fullCommand?.let(::singleLine)
        val rerun = if (toolName in SHELL_TOOLS) arguments?.text("command")?.takeIf(String::isNotBlank) else null

        if (result == null) {
            // 纯文本结果（例如旧版本或非结构化失败）：整体作为 stdout。
            return CommandOutput(
                toolName = toolName,
                commandLine = commandLine,
                rerunCommand = rerun,
                exitCode = null,
                timedOut = false,
                errorCode = null,
                durationMs = durationMs,
                runtime = runtimeOf(toolName, null),
                stdout = CommandStream(body, body.length.toLong(), null),
                stderr = CommandStream.EMPTY,
                combinedOnly = false,
            )
        }

        val logTail = result.text("log_tail")
        val combined = logTail != null
        val stdout: CommandStream
        val stderr: CommandStream
        if (combined) {
            val truncatedLog = result.bool("log_truncated") == true
            stdout = CommandStream(
                raw = logTail!!,
                originalLength = if (truncatedLog) null else logTail.length.toLong(),
                truncation = if (truncatedLog) CommandTruncation.LOG_TAIL else null,
            )
            stderr = CommandStream.EMPTY
        } else {
            stdout = stream(result, "stdout")
            stderr = stream(result, "stderr")
        }
        val elapsedSeconds = result.long("elapsed_seconds")
        return CommandOutput(
            toolName = toolName,
            commandLine = commandLine,
            rerunCommand = rerun,
            exitCode = result.int("exit_code"),
            timedOut = result.bool("timed_out") == true,
            errorCode = result.text("error_code"),
            durationMs = durationMs ?: elapsedSeconds?.times(1_000),
            runtime = runtimeOf(toolName, result.text("runtime")),
            stdout = stdout,
            stderr = stderr,
            combinedOnly = combined,
            logPath = result.text("log_path").takeIf { combined },
        )
    }

    private fun stream(result: JsonObject, key: String): CommandStream {
        var raw = result.text(key).orEmpty()
        var original = result.long("${key}_original_length")
        val marker = TRUNCATION_MARKER.find(raw)
        if (marker != null) {
            // 摘要在输出前插入了截断标记，原始长度以标记中的数字为准。
            raw = raw.substring(marker.range.last + 1)
            original = maxOf(original ?: 0L, marker.groupValues[1].toLong())
        }
        val flagged = result.bool("${key}_truncated") == true
        val shorter = original != null && original > raw.length
        val truncation = when {
            marker != null -> CommandTruncation.FAILURE_SUMMARY
            flagged || shorter -> CommandTruncation.TAIL_LIMIT
            else -> null
        }
        return CommandStream(raw, original ?: raw.length.toLong(), truncation)
    }

    internal fun runtimeOf(toolName: String?, runtimeField: String?): CommandRuntime = when {
        runtimeField.equals("linux", ignoreCase = true) -> CommandRuntime.LINUX
        runtimeField.equals("system", ignoreCase = true) -> CommandRuntime.SYSTEM
        toolName == "termux_exec" -> CommandRuntime.TERMUX
        toolName == "ssh_exec" -> CommandRuntime.SSH
        toolName == "python_exec" -> CommandRuntime.PYTHON
        toolName in setOf("shell_exec", "git_exec") -> CommandRuntime.SYSTEM
        else -> CommandRuntime.UNKNOWN
    }

    /** 多行命令只取首行，后面加省略号。 */
    internal fun singleLine(command: String): String {
        val trimmed = command.trim()
        val firstLine = trimmed.lineSequence().firstOrNull().orEmpty()
        return if (firstLine.length < trimmed.length) "$firstLine …" else firstLine
    }

    private fun parseObject(text: String?): JsonObject? =
        text?.takeIf(String::isNotBlank)?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    private fun JsonObject.primitive(key: String): JsonPrimitive? = (get(key) as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonObject.text(key: String): String? = primitive(key)?.takeIf { it.isString }?.contentOrNull
    private fun JsonObject.int(key: String): Int? = primitive(key)?.intOrNull
    private fun JsonObject.long(key: String): Long? = primitive(key)?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? = primitive(key)?.booleanOrNull
}

internal fun utf8Length(text: String): Long {
    var bytes = 0L
    var index = 0
    while (index < text.length) {
        val char = text[index]
        bytes += when {
            char.code < 0x80 -> 1
            char.code < 0x800 -> 2
            char.isHighSurrogate() && index + 1 < text.length && text[index + 1].isLowSurrogate() -> { index++; 4 }
            else -> 3
        }
        index++
    }
    return bytes
}
