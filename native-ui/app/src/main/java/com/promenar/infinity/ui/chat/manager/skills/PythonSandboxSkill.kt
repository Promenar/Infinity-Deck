package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.script.PythonSandboxEngine
import com.promenar.infinity.data.script.ScriptToolRunner
import com.promenar.infinity.domain.script.*
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.*
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

/**
 * 沙箱模式的 Python：在 isolatedProcess 中运行，看不到 Infinity Workbench 私有数据与工作区物理目录，默认无网络。
 * 工作区读写经 `import infinity` 的能力调用，与 exec_js 共用文件审批、挂载校验与联网策略。
 * 结果沿用命令类工具的结构（exit_code、stdout、stderr），另带 result 变量。
 */
class PythonSandboxSkill(private val runner: ScriptToolRunner) : SkillDefinition {
    override val id = "python_sandbox"
    override val name = id
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SCRIPT
    override val description = "在隔离进程运行内置 Python 3.14（仅 arm64 设备）：看不到本应用私有数据与工作区物理目录，没有可写目录，" +
        "默认无网络；只有标准库与 dulwich。用 import infinity 访问工作区虚拟路径：infinity.read_text(path)、infinity.read(path)→{content,sha256}、" +
        "infinity.list(path)、infinity.stat(path)（不存在为 None）、infinity.save(path, text)（不存在则新建，存在则覆盖）、" +
        "infinity.write(path, text, expected_sha256)、infinity.create、infinity.mkdir、infinity.delete（永久删除，需用户批准）；allow_network=true 时可用 " +
        "infinity.fetch(url, method, headers, body)→{status,headers,body}。单个文件只支持不超过 64 KB 的 UTF-8 文本，写入按脚本设置审批，" +
        "失败抛出 infinity.InfinityError（含 code）。结果含 exit_code、stdout、stderr 与 result 变量（可 JSON 序列化）。" +
        "需要直接读写大文件、子进程、git 仓库或 pip 包时改用 python_exec。默认 30 秒，上限 300 秒。"
    override val parametersSchema = """{"type":"object","properties":{"code":{"type":"string","description":"Python 源码；可把返回值赋给 result 变量"},"stdin":{"type":"string","description":"可选标准输入"},"allow_network":{"type":"boolean","default":false},"timeout_seconds":{"type":"integer","default":30,"description":"超时秒数，1–300，默认 30"}},"required":["code"],"additionalProperties":false}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val result = try {
            val request = request(args)
            runner.execute(request, context).toCommandResult(request.timeoutSeconds)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            CommandToolResult(
                errorCode = (failure as? ScriptFailure)?.code ?: "INVALID_ARGUMENT",
                guidance = (failure.message?.take(300)?.let { "$it。" } ?: "") +
                    "code 必填（不超过 50000 字符）；stdin 可选（不超过 32000 字符）；allow_network 为布尔值；timeout_seconds 为 1–300 的整数。",
            )
        }
        return ToolResult("${id}_${System.currentTimeMillis()}", result.toJson().toString(), result.toolStatus)
    }

    private fun request(args: JsonObject): ScriptRequest {
        val unknown = args.keys - setOf("code", "stdin", "allow_network", "timeout_seconds")
        if (unknown.isNotEmpty()) throw ScriptFailure("INVALID_ARGUMENT", "不支持的参数：${unknown.joinToString()}")
        val code = (args["code"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?.takeIf(String::isNotBlank) ?: throw ScriptFailure("INVALID_ARGUMENT", "缺少必填参数 code")
        if (code.length > ScriptLimits.MAX_CODE_CHARS) throw ScriptFailure("INVALID_ARGUMENT", "代码超过 50,000 字符上限")
        val stdin = args["stdin"]?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw ScriptFailure("INVALID_ARGUMENT", "stdin 必须为字符串") }.orEmpty()
        if (stdin.length > ScriptLimits.MAX_STDIN_CHARS) throw ScriptFailure("INVALID_ARGUMENT", "stdin 超过 32,000 字符上限")
        val timeout = args["timeout_seconds"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.intOrNull ?: throw ScriptFailure("INVALID_ARGUMENT", "timeout_seconds 必须为整数") } ?: 30
        if (timeout !in 1..ScriptLimits.MAX_TIMEOUT_SECONDS) throw ScriptFailure("INVALID_ARGUMENT", "timeout_seconds 必须在 1–300 秒之间")
        val network = args["allow_network"]?.let { (it as? JsonPrimitive)?.takeUnless { p -> p.isString }?.booleanOrNull ?: throw ScriptFailure("INVALID_ARGUMENT", "allow_network 必须为布尔值") } ?: false
        return ScriptRequest(code, allowNetwork = network, timeoutSeconds = timeout, language = ScriptLanguages.PYTHON, stdin = stdin)
    }
}

internal fun ScriptResult.toCommandResult(timeoutSeconds: Int): CommandToolResult {
    val output = value as? JsonObject
    fun text(key: String) = output?.get(key)?.jsonPrimitive?.contentOrNull.orEmpty()
    fun length(key: String) = output?.get(key)?.jsonPrimitive?.longOrNull
    val stdout = text("stdout")
    val stderr = text("stderr")
    return CommandToolResult(
        errorCode = errorCode,
        guidance = errorCode?.let { code -> sandboxGuidance(code, error, timeoutSeconds) },
        exitCode = output?.get("exit_code")?.jsonPrimitive?.intOrNull,
        timedOut = errorCode == "TIMEOUT",
        stdout = stdout,
        stderr = stderr,
        stdoutOriginalLength = length("stdout_original_length") ?: stdout.length.toLong(),
        stderrOriginalLength = length("stderr_original_length") ?: stderr.length.toLong(),
        backendFields = buildJsonObject {
            put("backend", "sandbox")
            output?.get("result")?.let { put("result", it) }
            output?.get("result_error")?.let { put("result_error", it) }
        },
    )
}

private fun sandboxGuidance(code: String, error: String?, timeoutSeconds: Int): String = when (code) {
    "TIMEOUT" -> "执行超过 $timeoutSeconds 秒，沙箱进程已终止；stdout 与 stderr 为终止前的输出。可提高 timeout_seconds（上限 300）或减少计算量。"
    "PYTHON_UNAVAILABLE" -> PythonSandboxEngine.PYTHON_UNAVAILABLE
    "CAPABILITY_LIMIT" -> "单次执行的 infinity 能力调用超过 ${ScriptLimits.CAPABILITY_CALLS} 次，执行已终止；请合并读写或改用 python_exec。"
    "NETWORK_LIMIT" -> "网络请求次数或流量超过沙箱上限，执行已终止。"
    else -> error?.take(500) ?: code
}
