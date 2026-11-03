package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.termux.TermuxCommandRequest
import com.promenar.infinity.data.termux.TermuxCommandResult
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.coroutines.CancellationException
import java.util.UUID

class TermuxExecSkill(
    private val runner: suspend (TermuxCommandRequest) -> TermuxCommandResult,
    private val jobs: com.promenar.infinity.data.jobs.BackgroundJobManager? = null,
    private val enabled: () -> Boolean,
) : SkillDefinition {
    constructor(runner: suspend (TermuxCommandRequest) -> TermuxCommandResult, enabled: () -> Boolean) : this(runner, null, enabled)
    override val id = "termux_exec"
    override val name = id
    override val risk = ToolRisk.PROCESS
    override val description = "以 Termux 身份运行，访问范围由 Termux 权限决定，不受本应用工作区约束；工作区模式逐条审批，完全访问模式免审批，被拒后不要重复。" + "在同机 Termux 的 bash 中通过 -c 执行命令；background:true 用 setsid nohup 启动并立即返回任务号，最长运行 max_runtime_minutes 默认120、上限1440，后台不使用 timeout_seconds；on_exit 默认 notify，长任务优先 wake；可用 job_status/job_wait/job_stop，取消生成不会停止后台任务。日志在共享工作区 jobs/ 或 Termux 私有 ~/.infinity/jobs/；workdir 可省略，默认为 Termux 主目录；也可传绝对路径、~/ 或 \$PREFIX/ 路径。timeout_seconds 默认60，最多600；支持stdin。超时只停止本地等待，命令可能仍在运行，重试前请检查原任务。输出可能被Termux从开头截断，结果含原始长度。"
    override val mcpServerId: String? = null
    override val parametersSchema = """{"type":"object","properties":{"command":{"type":"string","description":"在 bash -c 中执行的非空命令"},${com.promenar.infinity.domain.tool.ExecToolArguments.TITLE_SCHEMA},"workdir":{"type":"string","description":"可省略，默认 Termux 主目录；或绝对路径、~/、${'$'}PREFIX/ 路径"},"timeout_seconds":{"type":"integer","description":"1–600 秒，默认 60"},"stdin":{"type":"string","description":"可选标准输入"},${BACKGROUND_EXEC_SCHEMA}},"required":["command"],"additionalProperties":false}"""
    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (enabled() && args.backgroundRequested()) return runBackgroundExec(id, parametersSchema, args, context, jobs, com.promenar.infinity.domain.jobs.BackgroundJobRunner.TERMUX, "command")
        val result = try {
            when {
                !enabled() -> TermuxCommandResult(errorCode = "TERMUX_INTEGRATION_DISABLED", guidance = "请先在工具设置中启用 Termux 集成。")
                else -> when (val validation = validator.validate(parametersSchema, args)) {
                    is ToolSchemaValidation.Invalid -> invalidArguments("${validation.error.path}：${validation.error.message}")
                    // Termux 结果由广播一次性回传，没有增量输出。
                    else -> {
                        if (jobs != null) {
                            val timeout = com.promenar.infinity.domain.tool.ExecToolArguments.integer(args, "timeout_seconds", 60, 1..600)
                            val minutes = com.promenar.infinity.domain.tool.ExecToolArguments.integer(args, "max_runtime_minutes", 120, 1..1440)
                            val action = com.promenar.infinity.domain.jobs.BackgroundJobExitAction.fromWire((args["on_exit"] as? JsonPrimitive)?.content ?: "notify")
                                ?: throw IllegalArgumentException("on_exit 无效")
                            val job = jobs.startTermux(context.sessionId, context.workspaceRootUuid,
                                (args.getValue("command") as JsonPrimitive).content, normalizeWorkdir((args["workdir"] as? JsonPrimitive)?.content),
                                (args["stdin"] as? JsonPrimitive)?.content, minutes, action,
                                com.promenar.infinity.domain.tool.ExecToolArguments.title(args), waitForStart = false)
                            val current = jobs.waitFor(context.sessionId, job.id, timeout, foreground = true)
                            val command = termuxForegroundResult(current)
                            return ToolResult("termux_${UUID.randomUUID()}", command.toJson().toString(), command.toolStatus)
                        }
                        context.liveOutput.unsupported()
                        runner(TermuxCommandRequest(
                            command = (args.getValue("command") as JsonPrimitive).content,
                            workdir = normalizeWorkdir((args["workdir"] as? JsonPrimitive)?.content),
                            timeoutSeconds = (args["timeout_seconds"] as? JsonPrimitive)?.intOrNull ?: 60,
                            stdin = (args["stdin"] as? JsonPrimitive)?.content,
                        ))
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (invalid: IllegalArgumentException) { invalidArguments(invalid.message)
        } catch (_: Exception) {
            TermuxCommandResult(errorCode = "TERMUX_EXEC_FAILED", guidance = "Termux 调用失败；请打开工具设置中的 Termux 接入与诊断检查状态。")
        }
        return ToolResult(
            id = "termux_${UUID.randomUUID()}", content = result.toJson().toString(), status = result.toCommandResult().toolStatus,
        )
    }
    private fun invalidArguments(reason: String?) = TermuxCommandResult(
        errorCode = "TERMUX_INVALID_ARGUMENTS",
        guidance = (reason?.take(200)?.let { "参数无效：$it。" } ?: "") +
            "command 必填；workdir 可省略（默认 Termux 主目录）或传绝对路径、~/、\$PREFIX/ 路径；timeout_seconds 为 1–600 的整数；不接受 host 参数。",
    )

    // 模型常为可选参数填空串或主目录简写；按省略处理，避免整次调用被拒。
    private fun normalizeWorkdir(raw: String?): String? = raw?.trim()?.takeUnless { it.isEmpty() || it == "~" || it == "\$HOME" }
}

/** 前台等待返回任务快照，日志只保留尾部时不推测原始长度。 */
internal fun termuxForegroundResult(current: com.promenar.infinity.data.jobs.BackgroundJobStatus): com.promenar.infinity.domain.tool.CommandToolResult =
    com.promenar.infinity.domain.tool.CommandToolResult(
        exitCode = current.record.exitCode, stdout = current.tail.text,
        stdoutOriginalLength = if (current.tail.truncated) null else current.tail.text.length.toLong(),
        stdoutTruncated = current.tail.truncated,
        timedOut = current.record.state == "TIMED_OUT",
        errorCode = when (current.record.state) {
            "FAILED" -> "TERMUX_EXEC_FAILED"
            "TIMED_OUT" -> "TERMUX_TIMEOUT"
            "STOPPED", "INTERRUPTED" -> "TERMUX_STOPPED"
            else -> null
        },
        guidance = if (current.record.running) "命令继续在后台运行；可用 job_status/job_wait 查看。" else current.record.guidance,
        backendFields = JsonObject(backgroundStartJson(current.record) - "guidance"),
    )
