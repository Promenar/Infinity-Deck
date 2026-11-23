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

/** 每次执行读取固定的会话模式；内置模式走真实 Android 路径命名空间，Linux 模式走 proot 命名空间，互不静默回退。 */
class LinuxSessionFileSkill(
    private val original: SkillDefinition,
    private val modeFor: suspend (String) -> SessionRunMode?,
    private val linuxFiles: LinuxSessionExternalWorkspaceAccess,
    workspace: IWorkspaceRepository,
    files: IFileOperationRepository,
    indexEvents: FileIndexEventSink,
    captureFileChanges: Boolean = true,
    private val builtinFiles: BuiltinRealPathWorkspaceAccess? = null,
) : SkillDefinition by original {
    private val linux = MountedWorkspaceSkill(original, linuxFiles, workspace, files, indexEvents, captureFileChanges, linuxPaths = true)
    private val builtin = builtinFiles?.let { MountedWorkspaceSkill(original, it, workspace, files, indexEvents, captureFileChanges, linuxPaths = true) }

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
            SessionRunMode.BUILTIN -> {
                val access = builtinFiles
                val skill = builtin
                if (access == null || skill == null) {
                    original.execute(args, context)
                } else {
                    executeNamespaced(skill, access, args, context, isLinux = false)
                }
            }
            null -> ToolResult("linux_files_error", "请先确定当前会话的运行模式", "error")
            SessionRunMode.LINUX -> {
                linuxSkillPathHint(args)?.let { hint ->
                    return@withContext ToolResult("linux_skills_path_hint", hint, "error")
                }
                executeNamespaced(linux, linuxFiles, args, context, isLinux = true)
            }
        }
    }

    private suspend fun executeNamespaced(
        delegate: MountedWorkspaceSkill,
        access: SessionNamespaceWorkspaceAccess,
        args: JsonObject,
        context: SkillExecutionContext,
        isLinux: Boolean,
    ): ToolResult = try {
        val target = access.executionTarget(context.sessionId, original.toProtocolTool(), args)
            ?: throw ExternalFolderException("INVALID_PATH", if (isLinux) "无法确定 Linux 文件工具目标" else "无法确定文件工具目标路径")
        if ((context.isApprovalBoundExecution || context.approvedExecutionTarget != null) && context.approvedExecutionTarget != target) {
            throw ExternalFolderException("CONFLICT", "批准的路径或命名空间身份已经改变，请重新审批")
        }
        val metadata = Json.parseToJsonElement(target).jsonArray.lastOrNull() as? JsonObject
        val skillsFlag = if (isLinux) "linuxSkills" else "builtinSkills"
        if (metadata?.get(skillsFlag)?.jsonPrimitive?.booleanOrNull == true && !context.isApprovalBoundExecution) {
            throw ExternalFolderException("APPROVAL_REQUIRED", "技能目录变更需要按当前规范路径逐次审批")
        }
        val result = delegate.execute(access.invocationArguments(context.sessionId, original.id, args), context)
        if (original.id == "delete_file" && result.status == "success" &&
            (metadata?.get("linuxRootfs")?.jsonPrimitive?.booleanOrNull == true || metadata?.get("outsideWorkspace")?.jsonPrimitive?.booleanOrNull == true)
        ) {
            val path = Json.parseToJsonElement(target).jsonArray[1].jsonPrimitive.content
            result.copy(content = "已永久删除：$path")
        } else if (original.id in setOf("read_file", "diff_file") && result.status == "success" &&
            (args["mode"] as? JsonPrimitive)?.contentOrNull != "stat"
        ) {
            val path = Json.parseToJsonElement(target).jsonArray[1].jsonPrimitive.content
            result.copy(content = "路径: $path\n${result.content}")
        } else result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: ExternalFolderException) {
        recordFileOpError(context.sessionId, original.id, isLinux, failure.code)
        workspaceTraversalFailure(original.id, failure)
    }

    private fun linuxSkillPathHint(args: JsonObject): String? {
        for (key in listOf("path", "parentPath", "newParentPath")) {
            val raw = (args[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim() ?: continue
            if (raw.contains("\$INFINITY_SKILLS") || raw.contains("\${INFINITY_SKILLS}") ||
                raw.contains("\$NEXARA_SKILLS") || raw.contains("\${NEXARA_SKILLS}")
            ) {
                return "文件工具不解析 Shell 环境变量（例如 \$INFINITY_SKILLS）。请改用挂载绝对路径 /mnt/skills/<技能名>/<相对路径>，或使用 read_skill_file(name=\"<技能名>\", path=\"<相对路径>\") 读取。"
            }
            if (raw == "/skills" || raw.startsWith("/skills/")) {
                val rel = raw.removePrefix("/skills").trimStart('/')
                val suggested = if (rel.isEmpty()) "/mnt/skills/<技能名>" else "/mnt/skills/$rel"
                return "Linux 会话模式下用户级技能挂载于 /mnt/skills/ 而非 /skills/：请改用 $suggested，或使用 read_skill_file 读取技能文件。"
            }
        }
        return null
    }
}
