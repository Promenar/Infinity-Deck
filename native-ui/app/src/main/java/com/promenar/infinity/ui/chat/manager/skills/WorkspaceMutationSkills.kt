package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.repository.WorkspaceTextPolicyException
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** 解析新条目的父目录：parentUuid 优先，其次 parentPath，均缺省时为工作区根目录。 */
private suspend fun resolveParent(
    repository: IWorkspaceRepository,
    context: SkillExecutionContext,
    args: JsonObject,
    operation: String,
): Pair<FileEntry?, ToolResult?> {
    val hasParent = args.stringArgument("parentUuid")?.isNotBlank() == true ||
        args.stringArgument("parentPath")?.isNotBlank() == true
    val resolution = if (hasParent) {
        resolveWorkspaceEntry(repository, context.workspaceRootUuid, args, operation, "parentUuid", "parentPath")
    } else {
        repository.getByUuid(context.workspaceRootUuid, context.workspaceRootUuid)
            ?.let(WorkspaceEntryResolution::Found)
            ?: WorkspaceEntryResolution.Failed(workspaceToolError(operation, "工作区根目录不可用"))
    }
    return when (resolution) {
        is WorkspaceEntryResolution.Failed -> null to resolution.result
        is WorkspaceEntryResolution.Found -> if (resolution.entry.isDirectory) {
            resolution.entry to null
        } else {
            null to workspaceToolError(operation, "父节点不是目录：${resolution.entry.materializedPath}")
        }
    }
}

private fun childPath(parent: FileEntry, name: String): String =
    parent.materializedPath.trimEnd('/') + "/" + name

private suspend fun <T> mutationFailure(operation: String, block: suspend () -> T): Pair<T?, ToolResult?> = try {
    block() to null
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (failure: Exception) {
    null to workspaceMutationFailureResult(operation, failure)
}

class CreateFileSkill(
    private val workspaceRepo: IWorkspaceRepository,
    private val fileOpRepo: IFileOperationRepository,
) : SkillDefinition {
    override val id = "create_file"
    override val name = "create_file"
    override val description = "在工作区新建文本文件，可同时写入初始内容。父目录用 parentUuid 或 parentPath 指定，缺省为根目录；同名文件已存在时失败。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val parametersSchema = """{"type":"object","properties":{"name":{"type":"string","description":"文件名（含扩展名，不含 /）"},"content":{"type":"string","description":"初始内容，可省略"},"parentUuid":{"type":"string","description":"父目录UUID"},"parentPath":{"type":"string","description":"父目录路径，如 /docs"}},"required":["name"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val fileName = args.stringArgument("name")?.trim().orEmpty()
        if (invalidWorkspaceName(fileName)) return workspaceToolError(id, "文件名无效：$fileName")
        val content = args.stringArgument("content").orEmpty()
        val (parent, parentError) = resolveParent(workspaceRepo, context, args, id)
        if (parentError != null) return parentError
        val path = childPath(parent!!, fileName)
        val uuid = UUID.randomUUID().toString()
        val (created, createError) = mutationFailure(id) {
            workspaceRepo.createFileInWorkspace(context.workspaceRootUuid, uuid, fileName, "", parent.uuid, path)
        }
        if (createError != null) return createError
        if (content.isEmpty()) {
            return ToolResult(
                "create_file_${System.currentTimeMillis()}",
                "已创建空文件 $path [uuid=${created!!.uuid}] [hash=${created.hash}]",
            )
        }
        // 初始内容走 write_file 同一事务写入路径，以获得版本记录与索引事件。
        val write = try {
            fileOpRepo.writeFileAtomic(context.workspaceRootUuid, created!!.uuid, content, context.sessionId, Sha256Utils.hash(""))
        } catch (failure: WorkspaceTextPolicyException) {
            return workspaceToolError(id, "文件已创建为空文件 $path [uuid=${created!!.uuid}]，但内容未写入：${failure.safeMessage}")
        }
        return when (write) {
            is WriteResult.Success -> ToolResult(
                "create_file_${System.currentTimeMillis()}",
                "已创建 $path [uuid=${created!!.uuid}] [hash=${write.newHash}]" +
                    if (write.indexQueued) "" else "（索引尚未入队，可稍后对该文件 write_file 原样写回补偿）",
            )
            is WriteResult.Conflict, WriteResult.NotFound -> workspaceToolError(
                id,
                "文件已创建为空文件 $path [uuid=${created!!.uuid}]，但内容写入未完成，请用 write_file 重试。",
            )
        }
    }
}

class CreateDirectorySkill(
    private val workspaceRepo: IWorkspaceRepository,
) : SkillDefinition {
    override val id = "create_directory"
    override val name = "create_directory"
    override val description = "在工作区新建目录。父目录用 parentUuid 或 parentPath 指定，缺省为根目录。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val parametersSchema = """{"type":"object","properties":{"name":{"type":"string","description":"目录名（不含 /）"},"parentUuid":{"type":"string","description":"父目录UUID"},"parentPath":{"type":"string","description":"父目录路径，如 /docs"}},"required":["name"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val dirName = args.stringArgument("name")?.trim().orEmpty()
        if (invalidWorkspaceName(dirName)) return workspaceToolError(id, "目录名无效：$dirName")
        val (parent, parentError) = resolveParent(workspaceRepo, context, args, id)
        if (parentError != null) return parentError
        val path = childPath(parent!!, dirName)
        val (created, error) = mutationFailure(id) {
            workspaceRepo.createDirectoryInWorkspace(
                context.workspaceRootUuid,
                UUID.randomUUID().toString(),
                dirName,
                parent.uuid,
                path,
            )
        }
        if (error != null) return error
        return ToolResult("create_directory_${System.currentTimeMillis()}", "已创建目录 $path [uuid=${created!!.uuid}]")
    }
}

class MoveFileSkill(
    private val workspaceRepo: IWorkspaceRepository,
    private val indexEventSink: FileIndexEventSink,
) : SkillDefinition {
    override val id = "move_file"
    override val name = "move_file"
    override val description = "移动或重命名工作区中的文件或目录。用 uuid 或 path 指定条目；newParentUuid/newParentPath 指定目标目录，newName 指定新名称，至少提供一项。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.FILE_WRITE
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"条目UUID"},"path":{"type":"string","description":"条目路径，如 /docs/a.md"},"newParentUuid":{"type":"string","description":"目标父目录UUID"},"newParentPath":{"type":"string","description":"目标父目录路径，根目录为 /"},"newName":{"type":"string","description":"新名称（不含 /）"}}}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val target = when (val resolved = resolveWorkspaceEntry(workspaceRepo, context.workspaceRootUuid, args, id)) {
            is WorkspaceEntryResolution.Failed -> return resolved.result
            is WorkspaceEntryResolution.Found -> resolved.entry
        }
        if (target.uuid == context.workspaceRootUuid) return workspaceToolError(id, "不能移动或重命名工作区根目录")
        val newName = args.stringArgument("newName")?.trim()?.takeIf(String::isNotEmpty)
        val wantsMove = args.stringArgument("newParentUuid")?.isNotBlank() == true ||
            args.stringArgument("newParentPath")?.isNotBlank() == true
        if (newName == null && !wantsMove) return workspaceToolError(id, "需要提供 newName 或目标父目录")
        if (newName != null && invalidWorkspaceName(newName)) return workspaceToolError(id, "名称无效：$newName")

        if (wantsMove) {
            val parent = when (
                val resolved = resolveWorkspaceEntry(
                    workspaceRepo, context.workspaceRootUuid, args, id, "newParentUuid", "newParentPath",
                )
            ) {
                is WorkspaceEntryResolution.Failed -> return resolved.result
                is WorkspaceEntryResolution.Found -> resolved.entry
            }
            if (!parent.isDirectory) return workspaceToolError(id, "目标不是目录：${parent.materializedPath}")
            if (parent.materializedPath == target.materializedPath ||
                parent.materializedPath.startsWith(target.materializedPath.trimEnd('/') + "/")
            ) {
                return workspaceToolError(id, "不能把目录移动到自身或其子目录中")
            }
            if (parent.uuid != target.parentUuid) {
                val (_, moveError) = mutationFailure(id) {
                    workspaceRepo.updateParent(context.workspaceRootUuid, target.uuid, parent.uuid)
                }
                if (moveError != null) return moveError
            }
        }
        if (newName != null && newName != target.name) {
            val (renamed, renameError) = mutationFailure(id) {
                workspaceRepo.rename(context.workspaceRootUuid, target.uuid, newName, expectedName = target.name)
            }
            if (renameError != null) return renameError
            when (renamed) {
                is RenameResult.Success -> renamed.affectedTargets.forEach { affected ->
                    try {
                        indexEventSink.publish(
                            FileIndexEvent.Changed(
                                workspaceRootUuid = context.workspaceRootUuid,
                                fileUuid = affected.fileUuid,
                                contentHash = affected.targetHash,
                                targetEpoch = affected.targetEpoch,
                            ),
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // 重命名已提交；索引事件由待处理索引协调器在下次恢复时补偿。
                    }
                }
                is RenameResult.Conflict -> return workspaceToolError(id, "名称已被修改为 ${renamed.current}，请重新定位后再试")
                RenameResult.NotFound, null -> return workspaceToolError(id, "条目已不存在")
            }
        }
        val current = workspaceRepo.getByUuid(context.workspaceRootUuid, target.uuid)
        return ToolResult(
            "move_file_${System.currentTimeMillis()}",
            "已完成：${current?.describeForTool() ?: target.uuid}",
        )
    }
}

class DeleteFileSkill(
    private val workspaceRepo: IWorkspaceRepository,
) : SkillDefinition {
    override val id = "delete_file"
    override val name = "delete_file"
    override val description = "永久删除工作区中的文件或目录，需要用户批准。用 uuid 或 path 指定。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.DELETE
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"条目UUID"},"path":{"type":"string","description":"条目路径，如 /docs/a.md"}}}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val target = when (val resolved = resolveWorkspaceEntry(workspaceRepo, context.workspaceRootUuid, args, id)) {
            is WorkspaceEntryResolution.Failed -> return resolved.result
            is WorkspaceEntryResolution.Found -> resolved.entry
        }
        if (target.uuid == context.workspaceRootUuid) return workspaceToolError(id, "不能删除工作区根目录")
        val (_, error) = mutationFailure(id) {
            val expected = args.stringArgument("expectedHash") ?: args.stringArgument("expected_sha256")
            if (expected == null) workspaceRepo.permanentDelete(context.workspaceRootUuid, target.uuid)
            else workspaceRepo.permanentDeleteIfHash(context.workspaceRootUuid, target.uuid, expected)
        }
        if (error != null) return error
        return ToolResult("delete_file_${System.currentTimeMillis()}", "已永久删除：${target.materializedPath}")
    }
}
