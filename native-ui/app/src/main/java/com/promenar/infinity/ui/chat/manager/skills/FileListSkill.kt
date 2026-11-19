package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.domain.storage.ExternalFolderException
import kotlinx.coroutines.CancellationException
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.intArgument
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject

class FileListSkill @JvmOverloads constructor(
    private val workspaceRepo: IWorkspaceRepository,
    private val isSystemEntry: (FileEntry) -> Boolean = FileEntry::isSystemEntry,
) : SkillDefinition {
    override val id = "list_files"
    override val name = "list_files"
    override val description = "列出工作区目录内容，返回每项的路径、uuid 与文件 hash（可直接作为 write_file/patch_file 的 expectedHash）。用 parentUuid 或 path 指定目录，缺省为根目录；depth>1 时递归列出子目录。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"parentUuid":{"type":"string","description":"目录UUID(不传则为根目录)"},"path":{"type":"string","description":"目录路径，如 /docs；根目录为 /"},"depth":{"type":"integer","description":"递归深度，默认1，最大5"}}}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult = try {
        listDirectory(args, context)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        workspaceTraversalFailure(id, failure)
    }

    private suspend fun listDirectory(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val root = context.workspaceRootUuid
        val uuid = args.stringArgument("parentUuid")?.trim()?.takeIf(String::isNotEmpty)
        val path = args.stringArgument("path")?.takeIf(String::isNotBlank)
        val directory = when {
            uuid != null -> workspaceRepo.getByUuid(root, uuid) ?: throw ExternalFolderException("NOT_FOUND", "目录不存在：$uuid")
            path != null -> {
                val normalized = if (workspaceRepo is MountedWorkspaceRepository) workspaceRepo.normalizeSearchPath(path)
                    else normalizeWorkspaceToolPath(path)
                workspaceRepo.getByMaterializedPath(root, normalized) ?: throw ExternalFolderException("NOT_FOUND", "目录不存在：$normalized")
            }
            else -> null
        }
        if (directory != null && !directory.isDirectory) {
            throw ExternalFolderException("NOT_DIRECTORY", "不是目录：${directory.materializedPath}")
        }
        val parentUuid = directory?.uuid ?: context.workspaceRootUuid
        val depth = (args.intArgument("depth") ?: 1).coerceIn(1, MAX_DEPTH)
        val lines = mutableListOf<String>()
        val diagnostics = WorkspaceTraversalDiagnostics()
        var truncated = false
        suspend fun walk(uuid: String, path: String, prefix: String, level: Int) {
            val children = diagnostics.attempt(path, strict = level == 1) {
                workspaceRepo.workspaceChildren(root, uuid, diagnostics)
            }.orEmpty().filterNot(isSystemEntry)
            for (entry in children) {
                if (lines.size >= MAX_ENTRIES) {
                    truncated = true
                    return
                }
                val size = if (!entry.isDirectory) " (${formatSize(entry.sizeBytes)})" else ""
                val hash = if (!entry.isDirectory) " [hash=${entry.hash}]" else ""
                lines += "$prefix${if (entry.isDirectory) "[DIR]" else "[FILE]"} ${entry.materializedPath}$size [uuid=${entry.uuid}]$hash"
                if (entry.isDirectory && level < depth) walk(entry.uuid, entry.materializedPath, "$prefix  ", level + 1)
            }
        }
        val label = directory?.materializedPath ?: "/"
        walk(parentUuid, label, "", 1)
        val result = ToolResult(
            "list_files_${System.currentTimeMillis()}",
            if (lines.isEmpty()) "目录 $label 为空" else buildString {
                appendLine("目录 $label（${lines.size} 项${if (truncated) "，已截断至 $MAX_ENTRIES 项" else ""}）:")
                lines.forEach(::appendLine)
            },
        )
        return diagnostics.attach(result, "不可访问条目")
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }

    private companion object {
        const val MAX_DEPTH = 5
        const val MAX_ENTRIES = 300
    }
}

/** 回收站与系统保留目录不向模型展示。 */
internal fun FileEntry.isSystemEntry(): Boolean {
    return WorkspaceInternalPaths.containsReservedPath(materializedPath)
}
