package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.domain.model.SessionRunMode
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** 每次执行读取固定的会话模式；内置模式完整委派原工具，Linux 模式不静默回退。 */
class LinuxSessionFileSkill(
    private val original: SkillDefinition,
    private val modeFor: suspend (String) -> SessionRunMode?,
    private val linuxFiles: LinuxSessionExternalWorkspaceAccess,
    workspace: IWorkspaceRepository,
    files: IFileOperationRepository,
    indexEvents: FileIndexEventSink,
    captureFileChanges: Boolean = true,
) : SkillDefinition by original {
    private val linux = MountedWorkspaceSkill(original, linuxFiles, workspace, files, indexEvents, captureFileChanges, linuxPaths = true)

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        if (com.promenar.infinity.domain.tool.ToolExecutionTargets.isPathMutation(original.id) &&
            listOf("path", "parentPath", "newParentPath").any { key ->
                (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(
                    com.promenar.infinity.data.skills.BuiltinSkillCatalog::isVirtualPath,
                ) == true
            }
        ) return@withContext ToolResult("builtin_skill_readonly", "内置技能只读，不能通过文件工具修改；请使用 activate_skill 或 read_skill_file 读取。", "error")
        val mode = modeFor(context.sessionId)
        if (context.runMode != null && context.runMode != mode) {
            return@withContext ToolResult("linux_files_error", "EXEC_MODE_CHANGED：会话运行模式已改变，请重新发起工具调用", "error")
        }
        when (mode) {
            SessionRunMode.BUILTIN -> original.execute(args, context)
            null -> ToolResult("linux_files_error", "请先确定当前会话的运行模式", "error")
            SessionRunMode.LINUX -> try {
                val target = linuxFiles.executionTarget(context.sessionId, original.toProtocolTool(), args)
                    ?: throw ExternalFolderException("INVALID_PATH", "无法确定 Linux 文件工具目标")
                if ((context.isApprovalBoundExecution || context.approvedExecutionTarget != null) && context.approvedExecutionTarget != target) {
                    throw ExternalFolderException("CONFLICT", "批准的 Linux 路径或命名空间身份已经改变，请重新审批")
                }
                val metadata = Json.parseToJsonElement(target).jsonArray.lastOrNull() as? JsonObject
                if (metadata?.get("linuxSkills")?.jsonPrimitive?.booleanOrNull == true && !context.isApprovalBoundExecution) {
                    throw ExternalFolderException("APPROVAL_REQUIRED", "技能目录变更需要按当前 Linux 路径逐次审批")
                }
                val result = linux.execute(linuxFiles.invocationArguments(context.sessionId, original.id, args), context)
                if (original.id == "delete_file" && result.status == "success" && metadata?.get("linuxRootfs")?.jsonPrimitive?.booleanOrNull == true) {
                    val path = Json.parseToJsonElement(target).jsonArray[1].jsonPrimitive.content
                    result.copy(content = "已永久删除：$path")
                } else if (original.id in setOf("read_file", "diff_file") && result.status == "success") {
                    val path = Json.parseToJsonElement(target).jsonArray[1].jsonPrimitive.content
                    result.copy(content = "路径: $path\n${result.content}")
                } else result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: ExternalFolderException) { ToolResult("linux_files_error", "${failure.code}：${failure.message}", "error") }
        }
    }
}
