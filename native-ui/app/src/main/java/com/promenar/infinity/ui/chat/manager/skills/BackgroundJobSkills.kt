package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.jobs.*
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.jobs.*
import com.promenar.infinity.domain.tool.*
import com.promenar.infinity.ui.chat.manager.registry.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.*

internal const val BACKGROUND_EXEC_SCHEMA = """"background":{"type":"boolean","description":"后台立即返回任务号；shell 使用会话固定运行模式，环境不可用时返回错误；默认 false"},"on_exit":{"type":"string","enum":["none","notify","wake"],"description":"后台结束处理，默认 notify；长任务优先 wake"},"max_runtime_minutes":{"type":"integer","description":"仅后台模式：最长运行 1–1440 分钟，默认 120；后台模式不使用 timeout_seconds"}"""
internal fun JsonObject.backgroundRequested() = (this["background"] as? JsonPrimitive)?.booleanOrNull == true
internal fun backgroundStartJson(record: BackgroundJobRecord): JsonObject = buildJsonObject {
    if(record.runnerKind!=BackgroundJobRunner.TERMUX) put("runtime", when(record.runnerKind) { BackgroundJobRunner.SSH -> "ssh"; BackgroundJobRunner.LINUX -> "linux"; else -> "system" })
    put("runner",record.runnerKind.name.lowercase())
    put("job_id",record.id);put("state",record.state.lowercase());put("started_at",java.time.Instant.ofEpochMilli(record.startedAtMillis).toString())
    put("log_reference",BackgroundJobLogs.reference(record.id))
    val confirmed=record.runnerKind!=BackgroundJobRunner.SSH && (record.runnerKind!=BackgroundJobRunner.TERMUX || record.termuxPid!=null)
    put("launch_confirmed",confirmed)
    put("guidance",if(record.runnerKind==BackgroundJobRunner.SSH) "SSH 后台任务已登记；连接与启动结果通过 job_status/job_wait 查看，job_stop 只断开通道，远端进程可能仍在运行。" else if(confirmed) "后台任务已启动；可以继续其它工作，需要结果时用 job_status 或 job_wait；不要循环轮询。" else record.guidance ?: "启动结果尚未确认；请用 job_status 查询或 job_stop 撤销，避免重复执行。")
    record.guidance?.let { put("warning",it) }
    if(record.runnerKind == BackgroundJobRunner.LINUX) put("stderr_merged",true)
    if(record.isolatedWorkspace) put("workspace_mode","snapshot_merge_on_exit")
}
internal suspend fun runBackgroundExec(
    id:String, schema:String, rawArgs:JsonObject, context:SkillExecutionContext, jobs:BackgroundJobManager?, kind:BackgroundJobRunner, sourceField:String,
): ToolResult = jobToolResult(id) {
    val normalized = if (kind == BackgroundJobRunner.SHELL || kind == BackgroundJobRunner.LINUX) ExecToolArguments.normalize(rawArgs) else ExecToolArguments.Normalized(rawArgs, emptyList())
    val args = normalized.arguments
    ExecToolArguments.validate(schema, args)
    val source=(args[sourceField] as JsonPrimitive).content
    require(source.isNotBlank() && '\u0000' !in source && source.length <= (if(kind==BackgroundJobRunner.PYTHON) 40_000 else 32_000)) { "$sourceField 为空或过长，请写入脚本文件后执行。" }
    if(kind==BackgroundJobRunner.PYTHON) require(source.toByteArray(Charsets.UTF_8).size<=120_000) { "code 的 UTF-8 编码超过 120000 字节。" }
    val minutes=ExecToolArguments.integer(args, "max_runtime_minutes", 120, 1..1440)
    ExecToolArguments.integer(args, "timeout_seconds", 60, 1..600)
    if (args.containsKey("yield_after_seconds")) ExecToolArguments.integer(args, "yield_after_seconds", 15, 0..ExecToolArguments.integer(args, "timeout_seconds", 60, 1..600))
    val action=BackgroundJobExitAction.fromWire((args["on_exit"] as? JsonPrimitive)?.content ?: "notify") ?: throw IllegalArgumentException("on_exit 必须为 none、notify 或 wake")
    val manager=jobs ?: throw JobAccessException("JOB_UNAVAILABLE","后台任务服务不可用，请重新打开应用。")
    val stdin=(args["stdin"] as? JsonPrimitive)?.content
    val title=ExecToolArguments.title(args)
    val record=if(kind==BackgroundJobRunner.TERMUX) manager.startTermux(context.sessionId,context.workspaceRootUuid,source,(args["workdir"] as? JsonPrimitive)?.content?.trim()?.takeUnless{it.isEmpty() || it=="~" || it=="\$HOME"},stdin,minutes,action,title)
        else manager.startBuiltin(context.sessionId,context.workspaceRootUuid,kind,source,stdin,minutes,action, expectedMode = context.runMode, title = title)
    normalized.result(backgroundStartJson(record))
}
internal suspend fun jobToolResult(id:String, body:suspend()->JsonObject):ToolResult = try {
    ToolResult("${id}_${UUID.randomUUID()}",body().toString(),"success")
} catch(cancelled:CancellationException){throw cancelled
} catch(failure:Exception) {
    val code=(failure as? JobAccessException)?.code ?: if(failure is IllegalArgumentException) "JOB_INVALID_ARGUMENTS" else "JOB_OPERATION_FAILED"
    val guidance=if(failure is JobAccessException || failure is IllegalArgumentException) failure.message else "后台任务操作失败，请检查工作区或 Termux 接入后重试；已启动的任务可用 job_list 查询。"
    ToolResult("${id}_${UUID.randomUUID()}",buildJsonObject {put("error_code",code);put("guidance",guidance)}.toString(),"error")
}

fun backgroundJobTools(jobs:BackgroundJobManager):List<SkillDefinition> = listOf("status","wait","stop","list","input").map { JobSkill(jobs,it) }
private class JobSkill(private val jobs:BackgroundJobManager,private val operation:String):SkillDefinition {
    override val id="job_$operation"
    override val name=id
    override val risk=if (operation == "input") ToolRisk.PROCESS else ToolRisk.SESSION_STATE
    override val mcpServerId:String?=null
    override val description=when(operation) {"status"->"读取本会话后台任务状态和最多 8000 字符日志末尾。";"wait"->"等待本会话后台任务结束、后续新输出匹配 until_output 正则、until_file 工作区路径出现或 1–600 秒超时，任一条件触发即返回；用户立即检查与插队可提前返回，任务继续运行。";"stop"->"停止本会话后台任务：本机任务停止整个进程组（TERM，2 秒后 KILL）；SSH 任务断开通道，远端进程可能仍在运行。";"input"->"向本会话运行中的 Linux 任务输入文本；工作区档逐次审批，完全访问档免批。需要交互的命令不要传 stdin；newline 默认 true 追加回车，eof 默认 false；返回写入后1秒的输出尾部。";else->"列出本会话后台任务，不包含日志。"}
    override val parametersSchema=if(operation=="input") """{"type":"object","properties":{"job_id":{"type":"string"},"text":{"type":"string","description":"要写入的文本，可为空，最多32000字符"},"newline":{"type":"boolean"},"eof":{"type":"boolean"}},"required":["job_id","text"],"additionalProperties":false}""" else if(operation=="list") """{"type":"object","properties":{},"additionalProperties":false}""" else """{"type":"object","properties":{"job_id":{"type":"string"}${if(operation=="wait") ",\"timeout_seconds\":{\"type\":\"integer\",\"description\":\"1–600 秒，默认 60\"},\"until_output\":{\"type\":\"string\",\"description\":\"匹配后续新输出的正则，返回匹配所在行\"},\"until_file\":{\"type\":\"string\",\"description\":\"工作区相对路径或 /workspace 路径，文件或目录出现即返回\"}" else ""}},"required":["job_id"],"additionalProperties":false}"""
    override suspend fun execute(rawArgs:JsonObject,context:SkillExecutionContext)=jobToolResult(id) {
        val normalized = ExecToolArguments.normalize(rawArgs)
        val args = normalized.arguments
        ExecToolArguments.validate(parametersSchema, args)
        val response = if(operation=="list") buildJsonObject {put("jobs",JsonArray(jobs.list(context.sessionId).map {info(it)}))} else {
            val jobId=(args.getValue("job_id") as JsonPrimitive).content
            val status=when(operation) {"status"->jobs.status(context.sessionId,jobId);"stop"->jobs.stop(context.sessionId,jobId);"input"->jobs.input(context.sessionId,jobId,args.getValue("text").jsonPrimitive.content,(args["newline"] as? JsonPrimitive)?.booleanOrNull ?: true,(args["eof"] as? JsonPrimitive)?.booleanOrNull ?: false);else->jobs.waitFor(context.sessionId,jobId,ExecToolArguments.integer(args,"timeout_seconds",60,1..600),
                (args["until_output"] as? JsonPrimitive)?.content, (args["until_file"] as? JsonPrimitive)?.content)}
            JsonObject(info(status.record)+buildJsonObject{put("log_tail",status.tail.text);put("log_truncated",status.tail.truncated);put("last_output_seconds_ago", status.lastOutputSecondsAgo?.let(::JsonPrimitive) ?: JsonNull);if(operation=="wait") { put("timed_out",status.timedOut);put("wait_reason",status.waitReason?.let(::JsonPrimitive) ?: JsonNull);status.matchedLine?.let { put("matched_line",it) } }})
        }
        normalized.result(response)
    }
    private suspend fun info(record:BackgroundJobRecord):JsonObject {
        val runtime = if (record.directWorkspace || record.runnerKind == BackgroundJobRunner.SSH) record.commandResult ?: JsonObject(emptyMap()) else if (record.runnerKind == BackgroundJobRunner.TERMUX) JsonObject(emptyMap()) else
            com.promenar.infinity.data.exec.RuntimeDataMonitor.backgroundResult(java.io.File(record.runDirectory), expectResult = !record.running)
        return JsonObject(buildJsonObject {
    if(record.runnerKind!=BackgroundJobRunner.TERMUX) put("runtime", when(record.runnerKind) { BackgroundJobRunner.SSH -> "ssh"; BackgroundJobRunner.LINUX -> "linux"; else -> "system" })
    put("runner",record.runnerKind.name.lowercase())
    put("job_id",record.id);put("state",record.state.lowercase());put("exit_code",record.exitCode?.let(::JsonPrimitive) ?: JsonNull)
        put("summary",record.summary);put("elapsed_seconds",BackgroundJobCompletion.elapsedSeconds(record));put("started_at",java.time.Instant.ofEpochMilli(record.startedAtMillis).toString())
        put("log_reference",BackgroundJobLogs.reference(record.id));put("keep_alive",if(record.runnerKind==BackgroundJobRunner.SSH) "service" else jobs.keepAliveMode)
        if (record.runnerKind == BackgroundJobRunner.LINUX) put("stderr_merged", true)
        if (record.directWorkspace && record.runnerKind != BackgroundJobRunner.SSH) put("workspace_mode", "direct_reconcile_on_exit")
        record.transfer?.let { put("transfer", transferJson(it)) }
        record.guidance?.let{put("guidance",it)}
        } + runtime + buildJsonObject {
            if (record.runnerKind == BackgroundJobRunner.SSH) {
                record.guidance?.let { put("guidance", it) }
                put("exit_code", record.exitCode?.let(::JsonPrimitive) ?: JsonNull)
            }
        })
    }
}
