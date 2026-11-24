package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.exec.BuiltinExecService
import com.promenar.infinity.data.jobs.BackgroundJobManager
import com.promenar.infinity.data.jobs.JobAccessException
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.jobs.BackgroundJobRunner
import com.promenar.infinity.domain.tool.CommandToolResult
import com.promenar.infinity.domain.tool.GitExecArguments
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import java.util.UUID

class GitExecSkill(
    private val service: BuiltinExecService,
    private val jobs: BackgroundJobManager,
) : SkillDefinition {
    override val id = GitExecArguments.TOOL_NAME
    override val name = id
    override val risk = ToolRisk.PROCESS
    override val mcpServerId: String? = null
    override val parametersSchema = GitExecArguments.SCHEMA
    override val description = "执行 APK 内的原生 Git。通过 args 逐项传参数，cwd 可用工作区相对路径或已授权的真实路径。" +
        "支持 HTTPS 凭据代理和 SSH 桥。只读操作按会话审批策略执行，写入和远程修改按命令审批。background=true 返回任务号。"

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val result = try {
            val request = GitExecArguments.parse(args)
            val mode = context.runMode ?: service.runModeForSession(context.sessionId)
            if (mode == null) CommandToolResult(errorCode = "EXEC_RUN_MODE_REQUIRED", guidance = "请先选择会话运行模式。")
            else jobs.runForeground(
                context.sessionId, context.workspaceRootUuid, "git", 0, request.maxRuntimeMinutes, request.onExit,
                context.liveOutput, request.title ?: "Git", BackgroundJobRunner.SHELL, startInBackground = request.background,
            ) { attachment, live ->
                service.runGit(context.workspaceRootUuid, context.sessionId, request, mode, live, attachment)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: JobAccessException) {
            CommandToolResult(errorCode = failure.code, guidance = failure.message)
        } catch (_: IllegalArgumentException) {
            CommandToolResult(errorCode = "EXEC_INVALID_ARGUMENTS", guidance = "请检查 Git 参数、工作目录和超时。")
        } catch (_: Exception) {
            CommandToolResult(errorCode = "EXEC_FAILED", guidance = "原生 Git 未能完成执行。")
        }
        return ToolResult("${id}_${UUID.randomUUID()}", result.toJson().toString(), result.toolStatus)
    }
}
