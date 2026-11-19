package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.tool.ToolRisk
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.serialization.json.JsonObject

class FileSearchSkill @JvmOverloads constructor(
    private val workspaceRepo: IWorkspaceRepository,
    private val fileOpRepo: IFileOperationRepository? = null,
    private val isSystemEntry: (FileEntry) -> Boolean = FileEntry::isSystemEntry,
) : SkillDefinition {
    override val id = "search_files"
    override val name = "search_files"
    override val description = "在工作区中搜索。mode=name 按文件名匹配（默认）；mode=content 在文本文件内容中逐行查找关键词，返回路径与行号。可用 path 指定检索起始目录。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"query":{"type":"string","description":"搜索关键词（不区分大小写）"},"mode":{"type":"string","enum":["name","content"],"default":"name"},"path":{"type":"string","description":"检索起始目录，缺省为当前会话默认范围"}},"required":["query"]}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val query = args.stringArgument("query")?.trim()?.takeIf(String::isNotEmpty)
            ?: return workspaceToolError(id, "缺少 query")
        val mode = args.stringArgument("mode") ?: "name"
        return try {
            val path = args.stringArgument("path")?.takeIf(String::isNotBlank)?.let {
                if (workspaceRepo is MountedWorkspaceRepository) workspaceRepo.normalizeSearchPath(it)
                else ExternalFolderPaths.normalize(normalizeWorkspaceToolPath(it))
            }
            val diagnostics = WorkspaceTraversalDiagnostics()
            val result = when (mode) {
                "name" -> searchNames(query, context, path, diagnostics)
                "content" -> searchContent(query, context, path, diagnostics)
                else -> workspaceToolError(id, "不支持的 mode：$mode，可用 name 或 content")
            }
            diagnostics.attach(result, "不可访问目录或条目")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            workspaceTraversalFailure(id, failure)
        }
    }

    private suspend fun searchNames(
        query: String, context: SkillExecutionContext, path: String?, diagnostics: WorkspaceTraversalDiagnostics,
    ): ToolResult {
        val root = context.workspaceRootUuid
        val entries = when {
            workspaceRepo is MountedWorkspaceRepository && (path == null || workspaceRepo.searchesMount(path)) ->
                workspaceRepo.searchNames(root, query, path, diagnostics)
            path != null -> collectEntries(root, listOf(requireSearchDirectory(workspaceRepo, root, path)), true, diagnostics, MAX_SCAN_ENTRIES)
                .filter { it.name.contains(query, ignoreCase = true) }
            else -> workspaceRepo.searchByName(root, query).firstOrNull().orEmpty()
        }
        val matches = entries
            .filter { it.uuid != context.workspaceRootUuid && !isSystemEntry(it) }
            .sortedBy { it.materializedPath }
        if (matches.isEmpty()) return ToolResult(resultId(), "未找到名称包含 '$query' 的条目。")
        val shown = matches.take(MAX_NAME_RESULTS)
        return ToolResult(
            resultId(),
            buildString {
                appendLine("找到 ${matches.size} 个条目${if (matches.size > shown.size) "（显示前 ${shown.size} 个）" else ""}:")
                shown.forEach { appendLine("  ${it.describeForTool()}") }
            },
        )
    }

    private suspend fun searchContent(
        query: String, context: SkillExecutionContext, path: String?, diagnostics: WorkspaceTraversalDiagnostics,
    ): ToolResult {
        val operations = fileOpRepo ?: return workspaceToolError(id, "当前不支持内容搜索")
        val root = context.workspaceRootUuid
        val starts = when {
            path != null -> listOf(requireSearchDirectory(workspaceRepo, root, path))
            workspaceRepo is MountedWorkspaceRepository -> workspaceRepo.contentSearchRoots(root, diagnostics)
            else -> listOf(WorkspaceTraversalDirectory(root, "/"))
        }
        val files = collectEntries(root, starts, path != null, diagnostics, MAX_SCAN_ENTRIES, MAX_SCAN_FILES) { !it.isDirectory }
        val hits = mutableListOf<String>()
        var scanned = 0
        var skipped = 0
        for (file in files) {
            if (hits.size >= MAX_CONTENT_HITS) break
            if (file.sizeBytes > MAX_SCAN_BYTES) {
                skipped++
                continue
            }
            val content = try {
                operations.readFileRange(context.workspaceRootUuid, file.uuid).content
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // 二进制、超预算或读取失败的文件不阻断整体搜索。
                skipped++
                continue
            }
            scanned++
            content.lineSequence().forEachIndexed { index, line ->
                if (hits.size < MAX_CONTENT_HITS && line.contains(query, ignoreCase = true)) {
                    hits += "${file.materializedPath}:${index + 1}: ${line.trim().take(MAX_LINE_CHARS)}"
                }
            }
        }
        val summary = "扫描 $scanned 个文本文件" + if (skipped > 0) "，跳过 $skipped 个过大或非文本文件" else ""
        if (hits.isEmpty()) return ToolResult(resultId(), "未在内容中找到 '$query'（$summary）。")
        return ToolResult(
            resultId(),
            buildString {
                appendLine("找到 ${hits.size} 处匹配${if (hits.size >= MAX_CONTENT_HITS) "（已达上限）" else ""}，$summary:")
                hits.forEach { appendLine("  $it") }
                append("可用 read_file(path=..., mode=\"range\") 查看上下文。")
            },
        )
    }

    private suspend fun collectEntries(
        root: String,
        starts: List<WorkspaceTraversalDirectory>,
        strictStart: Boolean,
        diagnostics: WorkspaceTraversalDiagnostics,
        limit: Int,
        resultLimit: Int = limit,
        include: (FileEntry) -> Boolean = { true },
    ): List<FileEntry> {
        val result = mutableListOf<FileEntry>()
        val pending = ArrayDeque(starts)
        val visited = mutableSetOf<String>()
        var inspected = 0
        while (pending.isNotEmpty() && inspected < limit) {
            val directory = pending.removeFirst()
            if (!visited.add(directory.uuid)) continue
            val children = diagnostics.attempt(directory.path, strictStart && directory == starts.singleOrNull()) {
                workspaceRepo.workspaceChildren(root, directory.uuid, diagnostics)
            }.orEmpty()
            for (entry in children) {
                if (inspected++ >= limit) break
                if (isSystemEntry(entry)) continue
                if (entry.isDirectory && entry.name != ".git") pending += WorkspaceTraversalDirectory(entry.uuid, entry.materializedPath)
                if (include(entry)) result += entry
                if (result.size >= resultLimit) return result
            }
        }
        return result
    }

    private fun resultId() = "search_files_${System.currentTimeMillis()}"

    private companion object {
        const val MAX_NAME_RESULTS = 100
        const val MAX_CONTENT_HITS = 50
        const val MAX_SCAN_ENTRIES = 5_000
        const val MAX_SCAN_FILES = 200
        const val MAX_SCAN_BYTES = 1024L * 1024L
        const val MAX_LINE_CHARS = 200
    }
}


internal data class WorkspaceTraversalDirectory(val uuid: String, val path: String)

internal suspend fun requireSearchDirectory(
    workspace: IWorkspaceRepository, root: String, path: String,
): WorkspaceTraversalDirectory {
    val entry = workspace.getByMaterializedPath(root, path) ?: throw ExternalFolderException("NOT_FOUND", "目录不存在：$path")
    if (!entry.isDirectory) throw ExternalFolderException("NOT_DIRECTORY", "目标不是目录：$path")
    return WorkspaceTraversalDirectory(entry.uuid, entry.materializedPath)
}

internal suspend fun IWorkspaceRepository.workspaceChildren(
    root: String, parent: String, diagnostics: WorkspaceTraversalDiagnostics,
): List<FileEntry> = if (this is MountedWorkspaceRepository) observeChildren(root, parent, diagnostics).firstOrNull().orEmpty()
else observeChildren(root, parent).firstOrNull().orEmpty()

internal class WorkspaceTraversalDiagnostics {
    private var count = 0
    private var first: String? = null

    fun skipped(path: String, failure: Exception) {
        count++
        if (first == null) first = "${path.take(256)} -> ${workspaceTraversalFailure("filesystem", failure).content.take(256)}"
    }

    suspend fun <T> attempt(path: String, strict: Boolean = false, action: suspend () -> T): T? = try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        if (strict) throw failure
        skipped(path, failure)
        null
    }

    fun attach(result: ToolResult, label: String): ToolResult = first?.let {
        result.copy(content = result.content.trimEnd() + "\n跳过 $count 个$label（首例：$it）")
    } ?: result
}

internal fun workspaceTraversalFailure(operation: String, failure: Exception): ToolResult = when (failure) {
    is ExternalFolderException -> ToolResult(
        id = "${operation}_error",
        content = "${failure.code}：${failure.message.orEmpty().take(480)}",
        status = "error",
        data = buildJsonObject { put("errorCode", failure.code) }.toString(),
    )
    is SecurityException, is java.nio.file.AccessDeniedException -> workspaceTraversalFailure(operation, ExternalFolderException("ACCESS_DENIED", "目录或条目访问被拒绝"))
    is java.io.FileNotFoundException, is java.nio.file.NoSuchFileException -> workspaceTraversalFailure(operation, ExternalFolderException("NOT_FOUND", "目录或条目不存在"))
    else -> workspaceMutationFailureResult(operation, failure)
}
