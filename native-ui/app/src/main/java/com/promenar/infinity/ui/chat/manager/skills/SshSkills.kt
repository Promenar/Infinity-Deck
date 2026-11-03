package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.secret.SecretCapture
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshPolicy
import com.promenar.infinity.data.ssh.SshRuntime
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.data.jobs.BackgroundJobManager
import com.promenar.infinity.data.jobs.JobAccessException
import com.promenar.infinity.data.ssh.commandResult
import com.promenar.infinity.domain.jobs.BackgroundJobExitAction
import com.promenar.infinity.domain.tool.ExecToolArguments
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.tool.ToolSchemaValidation
import com.promenar.infinity.domain.tool.ToolSchemaValidator
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

fun sshTools(runtime: SshRuntime, jobs: BackgroundJobManager? = null, transferAccess: SftpTransferAccess = SftpTransferAccess()): List<SkillDefinition> =
    SshOperation.entries.map { SshSkill(runtime, jobs, it) } + sftpTransferTools(runtime, jobs, transferAccess)

// 数值边界由运行时强制校验；工具定义仅使用公共 schema 门禁支持的关键字。
private enum class SshOperation(val tool: String, val risk: ToolRisk, val summary: String, val fields: String, val required: String) {
    EXEC("ssh_exec", ToolRisk.PROCESS, "远程运维首选：每条命令独立执行，可让出到后台；默认60秒，最多600秒，输出有上限。background=true 立即返回任务号，后台按 max_runtime_minutes 限时，不使用 timeout_seconds；on_exit 为 none/notify/wake，默认 notify。停止只断开通道，远端进程可能仍在运行。",
        """"host":{"type":"string"},"command":{"type":"string"},${ExecToolArguments.TITLE_SCHEMA},"timeout_seconds":{"type":"integer","description":"1–600 秒，默认 60"},"max_output_chars":{"type":"integer","description":"1–256000 个字符，默认 32000"},"yield_after_seconds":{"type":"integer","description":"前台让出阈值；默认 min(15,timeout_seconds)，0 不让出，不得超过 timeout_seconds"},${BACKGROUND_EXEC_SCHEMA},${SecretCapture.schema}""", "\"host\",\"command\""),
    LIST("sftp_list", ToolRisk.SAFE_READ, "列出远端目录，最多512项，返回截断标记。",
        """"host":{"type":"string"},"path":{"type":"string"}""", "\"host\",\"path\""),
    READ("sftp_read", ToolRisk.SAFE_READ, "分段读取UTF-8文本；offset和limit使用UTF-16单元，二进制及超过16MiB的文件拒绝。",
        """"host":{"type":"string"},"path":{"type":"string"},"offset":{"type":"integer","description":"非负 UTF-16 偏移，默认 0"},"limit":{"type":"integer","description":"2–65536 个 UTF-16 单元，默认 16384"}""", "\"host\",\"path\""),
    WRITE("sftp_write", ToolRisk.EXTERNAL_WRITE, "写入远端UTF-8文件；expected_sha256存在时校验原文件，不一致拒绝。SFTP不提供跨客户端原子比较写入。",
        """"host":{"type":"string"},"path":{"type":"string"},"content":{"type":"string"},"expected_sha256":{"type":"string"}""", "\"host\",\"path\",\"content\""),
    OPEN("ssh_session_open", ToolRisk.PROCESS, "只在确需交互式程序或跨命令保留 shell 状态时打开PTY；优先使用 ssh_exec，Agent PTY 随本轮生成结束释放。",
        """"host":{"type":"string"}""", "\"host\""),
    SEND("ssh_session_send", ToolRisk.PROCESS, "向当前聊天所属PTY发送原始输入；执行回车需在input中明确包含换行。审批绑定真实主机别名及完整输入。",
        """"session_id":{"type":"string"},"input":{"type":"string"}""", "\"session_id\",\"input\""),
    READ_SESSION("ssh_session_read", ToolRisk.SESSION_STATE, "读取当前聊天所属PTY的有界输出缓冲；wait_ms最多5000毫秒。",
        """"session_id":{"type":"string"},"wait_ms":{"type":"integer","description":"0–5000 毫秒，默认 0"}""", "\"session_id\""),
    CLOSE("ssh_session_close", ToolRisk.SESSION_STATE, "关闭当前聊天所属PTY并释放其连接。",
        """"session_id":{"type":"string"}""", "\"session_id\""),
}

private class SshSkill(private val runtime: SshRuntime, private val jobs: BackgroundJobManager?, private val operation: SshOperation) : SkillDefinition {
    override val id = operation.tool
    override val name = operation.tool
    override val risk = operation.risk
    override val mcpServerId: String? = null
    override val description = operation.summary +
        (if (com.promenar.infinity.domain.tool.CommandToolClassification.backendFor(name) != null)
            " 命令在远程主机运行，访问范围由远程账号权限决定，不受本应用工作区约束；工作区模式逐条审批，完全访问模式免审批，被拒后不要重复。" else "") + " 仅使用用户在设置中创建的主机别名，禁止地址、用户名、密码、私钥或口令参数；未信任或指纹变化需用户在设置中处理。"
    override val parametersSchema = """{"type":"object","properties":{${operation.fields}},"required":[${operation.required}],"additionalProperties":false}"""
    private val validator = ToolSchemaValidator()

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        if (validator.validate(parametersSchema, args) is ToolSchemaValidation.Invalid) return failure("INVALID_ARGUMENTS")
        val owner = "agent:${context.sessionId}"
        return try {
            if ("host" in args) SshPolicy.alias(args.text("host"))
            val snapshot = when (operation) {
                SshOperation.EXEC, SshOperation.LIST, SshOperation.READ, SshOperation.WRITE, SshOperation.OPEN ->
                    runtime.hostSnapshot(args.text("host"))
                SshOperation.SEND -> runtime.sessionSnapshot(owner, args.text("session_id"))
                else -> null
            }
            if (snapshot != null && (context.isApprovalBoundExecution || context.approvedExecutionTarget != null)) {
                val resolved = if (operation == SshOperation.SEND) mapOf("ssh_session:${args.text("session_id")}" to snapshot.alias) else emptyMap()
                val target = ToolExecutionTargets.extract(toProtocolTool(), args.toString(), resolved, mapOf(snapshot.alias to snapshot.executionIdentity()))
                if (target == null || target != context.approvedExecutionTarget) throw SshFailure("HOST_AUTHORIZATION_CHANGED")
            }
            var status = "success"
            // 协程局部快照贯穿连接获取与实际操作，禁止按别名换源或跨等待窗口复用旧连接。
            val result = runtime.withHostSnapshot(snapshot) { when (operation) {
                SshOperation.EXEC -> {
                    val capture = SecretCapture.parse(args)
                    if (capture != null && args.backgroundRequested()) throw JobAccessException("SECRET_CAPTURE_BACKGROUND_UNSUPPORTED", "capture_secret 仅支持前台执行，请移除 background=true；捕获期间忽略让出阈值。")
                    val timeout = args.integer("timeout_seconds", 60)
                    SshPolicy.timeout(timeout)
                    val threshold = ExecToolArguments.integer(args, "yield_after_seconds", minOf(15, timeout), 0..timeout)
                    val minutes = ExecToolArguments.integer(args, "max_runtime_minutes", 120, 1..1440)
                    val action = BackgroundJobExitAction.fromWire((args["on_exit"] as? JsonPrimitive)?.content ?: "notify")
                        ?: throw SshFailure("INVALID_ARGUMENTS")
                    val command: CommandToolResult
                    if (capture != null && jobs == null) {
                        command = runtime.capture(owner, args.text("host"), args.text("command"), timeout, context.sessionId, capture)
                    } else if (jobs != null) {
                        val run = jobs.runSsh(runtime, requireNotNull(snapshot), context.sessionId, context.workspaceRootUuid,
                            args.text("command"), timeout, args.integer("max_output_chars", 32_000), if (capture != null) 0 else threshold,
                            args.backgroundRequested(), minutes, action, context.liveOutput, title = ExecToolArguments.title(args), capture = capture)
                        val job = run.job
                        if (job != null) {
                            val started = backgroundStartJson(job)
                            val fields = buildJsonObject {
                                started.forEach { (key, value) -> put(key, value) }
                                run.yieldedAfterSeconds?.let { put("yielded_after_seconds", it); put("launch_confirmed", true) }
                                put("runtime", "ssh")
                            }
                            return@withHostSnapshot if (run.yieldedAfterSeconds == null) fields else CommandToolResult(
                                guidance = "命令仍在运行；用 job_status/job_wait 查看，用 job_stop 断开通道；远端进程可能仍在运行。SSH 后台命令不支持 job_input。",
                                backendFields = fields,
                            ).toJson()
                        }
                        command = checkNotNull(run.command)
                    } else {
                        if (args.backgroundRequested()) throw JobAccessException("JOB_UNAVAILABLE", "后台任务服务不可用，请重新打开应用。")
                        command = runtime.exec(owner, args.text("host"), args.text("command"), timeout,
                            args.integer("max_output_chars", 32_000), context.liveOutput).commandResult(timeout)
                    }
                    status = command.toolStatus
                    command.toJson()
                }
                SshOperation.LIST -> {
                    val value = runtime.list(owner, args.text("host"), args.text("path"))
                    buildJsonObject {
                        put("entries", JsonArray(value.entries.map { entry -> buildJsonObject { put("name", entry.name); put("size", entry.size); put("directory", entry.directory) } }))
                        put("truncated", value.truncated)
                    }
                }
                SshOperation.READ -> {
                    val value = runtime.read(owner, args.text("host"), args.text("path"), args.number("offset", 0), args.integer("limit", 16_384))
                    buildJsonObject { put("content", value.content); put("next_offset", value.nextOffset); put("eof", value.eof); put("offset_unit", "UTF-16") }
                }
                SshOperation.WRITE -> buildJsonObject {
                    put("sha256", runtime.write(owner, args.text("host"), args.text("path"), args.text("content"), args["expected_sha256"]?.let { args.text("expected_sha256") }))
                }
                SshOperation.OPEN -> {
                    val value = runtime.openSession(owner, args.text("host"))
                    buildJsonObject { put("session_id", value.sessionId); put("host", value.host) }
                }
                SshOperation.SEND -> { runtime.sendSession(owner, args.text("session_id"), args.text("input")); buildJsonObject { put("sent", true) } }
                SshOperation.READ_SESSION -> {
                    val value = runtime.readSession(owner, args.text("session_id"), args.integer("wait_ms", 0))
                    buildJsonObject { put("output", value.output); put("closed", value.closed); put("truncated", value.truncated) }
                }
                SshOperation.CLOSE -> { runtime.closeSession(owner, args.text("session_id")); buildJsonObject { put("closed", true) } }
            } }
            ToolResult(id = "ssh_${UUID.randomUUID()}", content = runtime.redactOutput(context.sessionId, result).toString(), status = status)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: SshFailure) { failure(failure.code)
        } catch (failure: JobAccessException) {
            ToolResult("ssh_${UUID.randomUUID()}", buildJsonObject { put("error_code", failure.code); put("guidance", failure.message) }.toString(), "error")
        } catch (_: IllegalArgumentException) { failure("INVALID_ARGUMENTS")
        } catch (_: Exception) { failure("SSH_OPERATION_FAILED") }
    }
    private fun failure(code: String) = ToolResult(
        id = "ssh_${UUID.randomUUID()}",
        content = buildJsonObject {
            put("error_code", if (code.startsWith("SSH_")) code else "SSH_$code")
            put("guidance", sshGuidance(code))
        }.toString(),
        status = "error",
    )
    private fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw SshFailure("INVALID_ARGUMENTS")
    private fun JsonObject.integer(key: String, default: Int): Int = if (key !in this) default else (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull ?: throw SshFailure("INVALID_ARGUMENTS")
    private fun JsonObject.number(key: String, default: Long): Long = if (key !in this) default else (get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: throw SshFailure("INVALID_ARGUMENTS")
}

/** 与 SSH 错误码配套的下一步指引；不含主机地址、用户名或凭据。 */
internal fun sshGuidance(code: String): String = when (code.removePrefix("SSH_")) {
    "INVALID_ARGUMENTS", "INVALID_HOST_ALIAS", "INVALID_REMOTE_PATH", "INVALID_TIMEOUT", "INVALID_OUTPUT_LIMIT",
    "INVALID_COMMAND", "INVALID_READ_RANGE", "INVALID_WAIT", "INVALID_SHA256", "INPUT_TOO_LARGE", "WRITE_TOO_LARGE" ->
        "参数不符合工具定义的取值范围，请按工具说明修正后重试。"
    "HOST_NOT_FOUND" -> "主机别名未配置，请只使用用户在设置中创建的主机别名。"
    "CREDENTIAL_MISSING" ->
        "主机配置缺少当前认证方式所需的凭据，需要用户在设置中补充口令或私钥；不要重试。"
    "INVALID_PRIVATE_KEY", "INVALID_KEY_PASSPHRASE", "UNSUPPORTED_USER_KEY", "UNSUPPORTED_KEY_FORMAT", "UNSUPPORTED_KEY_CIPHER" ->
        "主机凭据不可用，需要用户在设置中重新配置凭据；不要重试。"
    "HOST_KEY_UNTRUSTED" ->
        "主机指纹尚未确认，需要用户通过可信渠道确认指纹并在设置中信任；不要重试。"
    "HOST_KEY_CHANGED" ->
        "主机指纹与已信任值不匹配，需要用户通过可信渠道核对指纹，再在设置中处理；不要重试。"
    "HOST_CONFIGURATION_CHANGED", "HOST_AUTHORIZATION_CHANGED" ->
        "主机指纹或配置未经确认，需要用户在设置中处理；不要重试。"
    "CONNECTION_REFUSED" ->
        "连接被拒绝，目标端口上的 SSH 服务可能未启动；请用户检查 SSH 服务、端口配置及防火墙，服务可用后再重试。"
    "DNS_FAILED" ->
        "无法解析主机域名，请检查设置中的地址、DNS 和 tailnet 连接，确认解析恢复后再重试。"
    "HOST_UNREACHABLE" ->
        "主机网络不可达，请检查网络、VPN/tailnet 连接和路由，并确认主机在线后再重试。"
    "TIMEOUT" ->
        "SSH 连接或文件操作超时，请检查网络、目标 SSH 服务和主机负载；操作结果可能未知，重试写入前先检查远端状态。"
    "AUTH_REJECTED" ->
        "SSH 认证被拒绝，需要用户核对设置中的用户名、凭据及远端账号的认证权限；不要重试。"
    "AUTH_OR_CONNECTION_FAILED", "CONNECTION_FAILED", "HOST_UNAVAILABLE" ->
        "连接或认证失败，请确认主机在线后再重试一次；仍失败时请用户检查设置中的主机配置。"
    "SESSION_NOT_FOUND" -> "会话不存在或已关闭，请先用 ssh_session_open 打开新会话。"
    "SECRET_SLOT_UNAVAILABLE" -> "引用的本会话保密槽位已销毁或不能作为环境变量，命令未执行；请重新提供保密信息。"
    "SESSION_LIMIT", "CONNECTION_LIMIT", "CLEANUP_BACKLOG_LIMIT" -> "已达到并发上限，请先用 ssh_session_close 关闭不再使用的会话。"
    "PROTECTED_LOCAL_PATH" -> "传输工具不写入技能目录，请使用现有带技能审批的文件工具。"
    "ATOMIC_PUBLISH_UNSUPPORTED" -> "服务器未提供安全的不覆盖发布扩展，无法创建新目标；请管理员启用 SFTP hardlink 扩展后重试。"
    "TRANSFER_TOO_LARGE" -> "文件超过单文件 4 GiB 上限，请拆分文件后再传输。"
    "CHECKSUM_MISMATCH" -> "SHA-256 校验不一致，目标未替换；请检查源文件和存储后重新传输。"
    "TARGET_CONFLICT", "SOURCE_CHANGED" -> "源文件或目标已变化，或目标存在而未允许覆盖；请检查文件，确认后再传输，覆盖需显式 overwrite=true。"
    "LOCAL_READ_ONLY" -> "该本地位置只读，请选择可写工作区目录。"
    "LOCAL_AUTHORIZATION_CHANGED" -> "会话工作区身份发生变化，请刷新工作区并重新审批后再传输。"
    "SERVICE_TIME_LIMIT" -> "系统后台服务时限已到，传输已停止；请回到前台确认网络与临时文件状态后重新传输。"
    "NO_SPACE" -> "存储空间不足，请清理目标存储空间后重试。"
    "INVALID_LOCAL_PATH" -> "本地路径无效或越出允许范围，请使用工作区内路径或 Linux 内绝对路径，避开保留目录和符号链接。"
    "LINUX_SCOPE_REQUIRES_LINUX_SESSION" -> "linux 范围只适用于 Linux 会话；请切换会话运行模式，或使用 workspace 范围。"
    "LINUX_SCOPE_UNAVAILABLE" -> "Linux 环境不可用，Play 版不支持 linux 范围；请使用 workspace 范围。"
    "REMOTE_PARENT_MISSING", "LOCAL_PARENT_MISSING" -> "目标父目录不存在；请先创建父目录再传输。"
    "REMOTE_NOT_FOUND", "LOCAL_NOT_FOUND" -> "源文件不存在，请检查路径后重试。"
    "UNSUPPORTED_TRANSFER_TARGET" -> "只允许普通文件，目录和符号链接不可作为传输目标；请选择普通文件路径。"
    "WORKSPACE_UNAVAILABLE" -> "会话工作区不可用，请重新选择工作区后重试。"
    "WORKSPACE_INDEX_FAILED" -> "文件已保存，但工作区索引对账失败；请刷新文件列表确认文件，避免重复覆盖。"
    "LOCAL_IO_FAILED" -> "本地读写失败，请检查文件权限、剩余空间和工作区状态。"
    "STOPPED" -> "传输已停止，目标未替换；请确认后再启动新传输。"
    "FILE_TOO_LARGE_OR_DIRECTORY" -> "目标是目录或超过 16MiB，目录请用 sftp_list，大文件请用 ssh_exec 处理。"
    "BINARY_FILE" -> "目标不是 UTF-8 文本，sftp_read 只读取文本文件。"
    "SHA256_CONFLICT" -> "远端文件已变化，请先重新读取并计算 sha256，再决定是否写入。"
    "UNSUPPORTED_WRITE_TARGET" -> "目标是目录或符号链接，sftp_write 只写入普通文件。"
    else -> "请检查设置中的主机配置、凭据和指纹信任。"
}
