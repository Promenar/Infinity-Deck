package com.promenar.infinity.domain.tool

import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** Git 参数保留 argv 边界，审批与执行使用同一份解析结果。 */
data class GitExecArguments(
    val args: List<String>,
    val cwd: String?,
    val title: String?,
    val timeoutSeconds: Int,
    val background: Boolean,
    val maxRuntimeMinutes: Int,
    val onExit: BackgroundJobExitAction,
) {
    companion object {
        const val TOOL_NAME = "git_exec"
        const val MAX_ARGS = 128
        const val SCHEMA = """{"type":"object","properties":{
            "args":{"type":"array","items":{"type":"string"},"description":"Git 参数数组，1–128 项，不含 git 自身"},
            "cwd":{"type":"string","description":"命令工作目录，按本会话运行模式解析"},
            ${ExecToolArguments.TITLE_SCHEMA},
            "timeout_seconds":{"type":"integer","description":"前台超时 1–600 秒","default":120},
            "background":{"type":"boolean","default":false},
            "max_runtime_minutes":{"type":"integer","description":"后台最长运行 1–1440 分钟","default":120},
            "on_exit":{"type":"string","enum":["none","notify","wake"],"default":"notify"}
        },"required":["args"],"additionalProperties":false}"""

        fun parse(values: JsonObject): GitExecArguments {
            ExecToolArguments.validate(SCHEMA, values)
            val argv = (values.getValue("args") as JsonArray).map { (it as JsonPrimitive).content }
            require(argv.size in 1..MAX_ARGS && argv.first().isNotBlank() && argv.none { '\u0000' in it }) {
                "args 需要 1–128 个字符串，首项非空，各项不得含空字符。"
            }
            val cwd = (values["cwd"] as? JsonPrimitive)?.content
            require(cwd == null || cwd.isNotBlank() && cwd.none(Char::isISOControl)) { "cwd 必须是有效路径。" }
            val onExit = (values["on_exit"] as? JsonPrimitive)?.content
            return GitExecArguments(
                args = argv,
                cwd = cwd,
                title = ExecToolArguments.title(values),
                timeoutSeconds = ExecToolArguments.integer(values, "timeout_seconds", 120, 1..600),
                background = (values["background"] as? JsonPrimitive)?.booleanOrNull ?: false,
                maxRuntimeMinutes = ExecToolArguments.integer(values, "max_runtime_minutes", 120, 1..1440),
                onExit = BackgroundJobExitAction.fromWire(onExit) ?: BackgroundJobExitAction.NOTIFY,
            )
        }
    }
}
