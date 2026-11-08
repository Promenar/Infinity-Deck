package com.promenar.infinity.data.termux

import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.domain.tool.CommandTruncation
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class TermuxAvailability(val installed: Boolean, val versionName: String?, val permissionGranted: Boolean) {
    val errorCode: String? get() = when {
        !installed -> "TERMUX_NOT_INSTALLED"
        !supportedVersion(versionName) -> "TERMUX_VERSION_UNSUPPORTED"
        !permissionGranted -> "TERMUX_PERMISSION_DENIED"
        else -> null
    }
    val guidance: String? get() = when (errorCode) {
        "TERMUX_NOT_INSTALLED" -> "请从 F-Droid 或 GitHub 安装 Termux，并打开一次完成初始化。"
        "TERMUX_VERSION_UNSUPPORTED" -> "请安装可识别版本号的 Termux 0.109 或更高版本，以接收命令结果。"
        "TERMUX_PERMISSION_DENIED" -> "请在设置 → 工具 → Termux 接入与诊断中授予 RUN_COMMAND 权限，或打开 Infinity Deck 的系统应用权限页。"
        else -> null
    }

    private fun supportedVersion(raw: String?): Boolean {
        val match = raw?.let { VERSION.matchEntire(it) } ?: return false
        val major = match.groupValues[1].toIntOrNull() ?: return false
        val minor = match.groupValues[2].toIntOrNull() ?: return false
        return major > 0 || minor >= 109
    }
    private companion object { val VERSION = Regex("^[vV]?(\\d+)\\.(\\d+)(?:\\.\\d+)?(?:[-+].*)?$") }
}

data class TermuxCommandResult(
    val stdout: String = "", val stderr: String = "", val exitCode: Int? = null,
    val err: Int? = null, val errmsg: String? = null,
    val stdoutOriginalLength: Long = stdout.length.toLong(),
    val stderrOriginalLength: Long = stderr.length.toLong(),
    val errorCode: String? = null, val guidance: String? = null, val timedOut: Boolean = false,
) {
    val stdoutTruncated: Boolean get() = stdoutOriginalLength > stdout.length
    val stderrTruncated: Boolean get() = stderrOriginalLength > stderr.length
    /** Termux 输出超限时去掉开头；err、errmsg 为 RUN_COMMAND 协议专有字段。 */
    fun toCommandResult(): CommandToolResult = CommandToolResult(
        errorCode = errorCode, guidance = guidance, exitCode = exitCode, timedOut = timedOut,
        stdout = stdout, stderr = stderr,
        stdoutOriginalLength = stdoutOriginalLength, stderrOriginalLength = stderrOriginalLength,
        removedWhenTruncated = CommandTruncation.HEAD_REMOVED,
        backendFields = buildJsonObject {
            put("err", err?.let(::JsonPrimitive) ?: JsonNull)
            put("errmsg", errmsg?.let(::JsonPrimitive) ?: JsonNull)
            // 官方协议未提供 errmsg 的原始长度，不能从已截断文本推算。
            put("errmsg_original_length", JsonNull)
            put("errmsg_truncation_possible", (errmsg?.length ?: 0) >= 25_000)
        },
    )
    fun toJson(): JsonObject = toCommandResult().toJson()
}

object TermuxResultParser {
    fun parse(values: Map<String, Any?>?): TermuxCommandResult {
        if (values == null) return malformed()
        val err = values["err"] as? Int ?: return malformed()
        val exit = values["exitCode"] as? Int
        if (err == -1 && exit == null) return malformed()
        val stdout = values["stdout"] as? String ?: ""
        val stderr = values["stderr"] as? String ?: ""
        val errmsg = values["errmsg"] as? String
        val externalAppsDenied = err != -1 && errmsg?.contains("allow-external-apps", ignoreCase = true) == true
        val code = when {
            externalAppsDenied -> "TERMUX_EXTERNAL_APPS_DISABLED"
            err != -1 -> "TERMUX_INTERNAL_ERROR"
            exit != 0 -> "TERMUX_COMMAND_FAILED"
            else -> null
        }
        return TermuxCommandResult(
            stdout, stderr, exit, err, errmsg,
            originalLength(values["stdout_original_length"], stdout),
            originalLength(values["stderr_original_length"], stderr),
            errorCode = code,
            guidance = when (code) {
                "TERMUX_EXTERNAL_APPS_DISABLED" -> "请在 Termux 的 ~/.termux/termux.properties 设置 allow-external-apps=true，执行 termux-reload-settings 后重试诊断。"
                "TERMUX_INTERNAL_ERROR" -> "Termux 未完成执行；请在 Termux 检查初始化、命令路径、工作目录及后台进程状态。"
                "TERMUX_COMMAND_FAILED" -> "命令返回非零退出码；请检查 stderr 和命令参数。"
                else -> null
            },
        )
    }

    private fun originalLength(raw: Any?, text: String): Long =
        (when (raw) { is Int -> raw.toLong(); is Long -> raw; is String -> raw.toLongOrNull(); else -> null }
            ?: text.length.toLong()).coerceAtLeast(text.length.toLong())

    private fun malformed() = TermuxCommandResult(
        errorCode = "TERMUX_RESULT_MALFORMED", guidance = "Termux 未回传有效结果包；请更新 Termux 并重新运行诊断命令。",
    )
}
