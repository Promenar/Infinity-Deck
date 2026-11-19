package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.storage.externalStreamHash
import com.promenar.infinity.data.storage.externalSnapshotMime
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.startup.StartupWriteBarrier
import com.promenar.infinity.ui.chat.diff.PrivateWorkspaceReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.promenar.infinity.data.repository.WorkspaceTextPolicyException
import com.promenar.infinity.data.repository.WorkspaceTextContentPolicy
import com.promenar.infinity.data.repository.WorkspaceTextErrorCode
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.ReadWindowRequest
import com.promenar.infinity.domain.repository.ReadWindowResult
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.intArgument
import com.promenar.infinity.ui.chat.manager.registry.stringArgument
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class FileReadSkill(
    private val fileOpRepo: IFileOperationRepository,
    private val workspaceRepo: IWorkspaceRepository? = null,
) : SkillDefinition {
    override val id = "read_file"
    override val name = "read_file"
    override val description = "读取工作区文本文件，每行带 `行号| ` 前缀（前缀不属于文件内容，patch_file 的行号即以此为准）。用 uuid 或 path 指定文件；支持分页（offset/limit）和行号范围（startLine/endLine）两种模式。" +
        "大文件和 .wav、.pdf 等二进制可用 mode=stat 查看类型、大小、权限和兼容 expectedHash 的流式 sha256。" +
        "单文件最多 100 MiB，每次最多 2000 行、约 190 KB；结果的“后续”行给出下一次的 startLine，超长行另给 lineByteOffset。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"文件UUID"},"path":{"type":"string","description":"文件路径，如 /docs/a.md"},"mode":{"type":"string","enum":["page","range","stat"],"default":"page"},"offset":{"type":"integer","description":"分页偏移(行号，0-based)"},"limit":{"type":"integer","description":"分页大小(行数，最多2000)","default":200},"startLine":{"type":"integer","description":"起始行号(1-based)"},"endLine":{"type":"integer","description":"结束行号(1-based)，一次最多返回2000行"},"lineByteOffset":{"type":"integer","description":"续读超长行：起始行内的字节偏移，取结果“后续”行给出的值","default":0}}}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val mode = args.stringArgument("mode") ?: "page"
        if (mode == "stat") return stat(args, context)
        val (resolvedUuid, resolveError) = resolveWorkspaceFileUuid(workspaceRepo, context.workspaceRootUuid, args, id)
        if (resolveError != null) return resolveError
        val uuid = resolvedUuid!!
        val lineByteOffset = (args["lineByteOffset"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: 0L
        if (lineByteOffset < 0) return workspaceToolError(id, "lineByteOffset 不能为负数")

        val request = when (mode) {
            "range" -> {
                val requestedStart = args.intArgument("startLine")
                val requestedEnd = args.intArgument("endLine")
                if (requestedStart != null && requestedEnd != null && requestedStart > requestedEnd) {
                    return workspaceToolError(id, "startLine($requestedStart) 不能大于 endLine($requestedEnd)")
                }
                val startLine = (requestedStart ?: 1).coerceAtLeast(1)
                ReadWindowRequest(startLine, requestedEnd?.coerceAtLeast(startLine), lineByteOffset)
            }
            else -> {
                val offset = (args.intArgument("offset") ?: 0).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE - 1)
                val limit = (args.intArgument("limit") ?: 200).coerceIn(1, ReadWindowRequest.MAX_LINES)
                val startLine = offset + 1
                val endLine = (startLine.toLong() + limit - 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                ReadWindowRequest(startLine, endLine, lineByteOffset)
            }
        }

        val result = try {
            fileOpRepo.readFileWindow(context.workspaceRootUuid, uuid, request)
        } catch (failure: WorkspaceTextPolicyException) {
            return workspaceTextFailureResult("read_file", failure)
        }

        return enforceWorkspaceToolResultBudget("read_file", ToolResult(
            "read_file_${System.currentTimeMillis()}",
            buildString {
                appendLine("文件: ${result.name} [uuid=${result.uuid}]")
                appendLine("行数: ${result.totalLines} (${returnedRange(result)})")
                appendLine("Hash: ${result.hash}")
                appendLine("后续: ${continuation(result)}")
                appendLine("---")
                if (result.totalLines > 0 && result.startLine > result.totalLines) {
                    append("（起始行 ${result.startLine} 超出文件总行数 ${result.totalLines}）")
                } else {
                    append(numberLines(result.content, result.startLine, result.totalLines))
                }
            }
        ))
    }

    private suspend fun stat(args: JsonObject, context: SkillExecutionContext): ToolResult = withContext(Dispatchers.IO) {
        try {
            val repository = workspaceRepo ?: throw ExternalFolderException("UNAVAILABLE", "当前工作区元数据不可用")
            val root = context.workspaceRootUuid
            val uuid = args.stringArgument("uuid")?.trim()?.takeIf(String::isNotEmpty)
            val path = args.stringArgument("path")?.takeIf(String::isNotBlank)?.let {
                if (repository is MountedWorkspaceRepository) repository.normalizeSearchPath(it) else normalizeWorkspaceToolPath(it)
            }
            if (uuid == null && path == null) throw ExternalFolderException("INVALID_ARGUMENT", "缺少 uuid 或 path 参数")
            val entry = (when {
                repository is MountedWorkspaceRepository -> repository.statEntry(root, uuid, path)
                uuid != null -> repository.getByUuid(root, uuid)
                else -> repository.getByMaterializedPath(root, requireNotNull(path))
            }) ?: throw ExternalFolderException("NOT_FOUND", "目标条目不存在")
            val stat = if (repository is MountedWorkspaceRepository) repository.fileStat(context, entry)
                else privateFileStat(repository, root, entry.materializedPath, entry, context.sessionId)
            val type = if (entry.isDirectory) "目录" else "文件"
            val mime = if (entry.isDirectory) null else entry.mimeType ?: externalSnapshotMime(entry.name)
            val permission = if (stat.writable) "可读写" else "只读"
            ToolResult(
                "read_file_stat_${System.currentTimeMillis()}",
                buildString {
                    appendLine("条目: ${entry.materializedPath} [uuid=${entry.uuid}]")
                    appendLine("类型: $type" + (mime?.let { " ($it)" } ?: ""))
                    appendLine("大小: ${entry.sizeBytes} B")
                    appendLine("权限: $permission")
                    appendLine("更新时间: ${entry.updatedAt}")
                    stat.hash?.let { appendLine("sha256: $it") }
                }.trimEnd(),
                data = buildJsonObject {
                    put("uuid", entry.uuid)
                    put("path", entry.materializedPath)
                    put("type", if (entry.isDirectory) "directory" else "file")
                    mime?.let { put("mimeType", it) }
                    put("sizeBytes", entry.sizeBytes)
                    put("updatedAt", entry.updatedAt)
                    put("writable", stat.writable)
                    stat.hash?.let { put("sha256", it) }
                }.toString(),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: com.promenar.infinity.data.repository.WorkspaceFileTooLargeException) {
            workspaceTraversalFailure(id, ExternalFolderException("TOO_LARGE", "文件超过 100 MiB 元数据散列预算"))
        } catch (failure: Exception) {
            workspaceTraversalFailure(id, failure)
        }
    }

}

internal data class WorkspaceFileStat(val hash: String?, val writable: Boolean)

internal suspend fun privateFileStat(
    workspace: IWorkspaceRepository,
    root: String,
    path: String,
    entry: FileEntry,
    sessionId: String,
    reader: PrivateWorkspaceReader = PrivateWorkspaceReader.Default,
): WorkspaceFileStat {
    val writable = !StartupWriteBarrier.isReadOnly(
        rootId = root, sessionId = sessionId,
        path = entry.physicalRootPath.takeIf(String::isNotBlank)?.let { java.io.File(it, path.removePrefix("/")).path },
    )
    val hash = workspaceStatHash(entry) { reader.readStream(workspace, root, path, ReadWindowRequest.MAX_FILE_BYTES, ::externalStreamHash) }
    return WorkspaceFileStat(hash, writable)
}

internal suspend fun workspaceStatHash(entry: FileEntry, readHash: suspend () -> String): String? {
    if (entry.isDirectory) return null
    if (STAT_SHA256.matches(entry.hash)) return entry.hash
    if (entry.sizeBytes > ReadWindowRequest.MAX_FILE_BYTES) {
        throw ExternalFolderException("TOO_LARGE", "文件超过 100 MiB 元数据散列预算")
    }
    return readHash()
}

private val STAT_SHA256 = Regex("[0-9a-f]{64}")

private fun returnedRange(result: ReadWindowResult): String = when {
    result.endLine < result.startLine -> "未返回内容"
    result.startLineByteOffset > 0 -> "返回 ${result.startLine}-${result.endLine}，第 ${result.startLine} 行自字节 ${result.startLineByteOffset} 起"
    else -> "返回 ${result.startLine}-${result.endLine}"
}

private fun continuation(result: ReadWindowResult): String {
    val next = result.nextLine ?: return "已到文件末尾"
    return if (result.nextLineByteOffset > 0) {
        "mode=range startLine=$next lineByteOffset=${result.nextLineByteOffset}（第 $next 行过长，本次只返回到此）"
    } else {
        "mode=range startLine=$next"
    }
}

/** 为模型输出加行号前缀，宽度按全文行数对齐。 */
internal fun numberLines(content: String, firstLine: Int, totalLines: Int): String {
    if (content.isEmpty()) return ""
    val width = maxOf(totalLines, 1).toString().length
    return content.split('\n').mapIndexed { index, line ->
        (firstLine + index).toString().padStart(width) + "| " + line
    }.joinToString("\n")
}

internal fun workspaceTextFailureResult(
    operation: String,
    failure: WorkspaceTextPolicyException,
): ToolResult = ToolResult(
    id = "${operation}_error_${System.currentTimeMillis()}",
    content = failure.safeMessage.take(512),
    status = "error",
    data = buildJsonObject {
        put("operation", operation)
        put("errorCode", failure.code.name)
        put("retrySuggestion", "请缩小读取或差异范围，或使用兼容该文件类型的专用工具。")
    }.toString(),
)

internal fun enforceWorkspaceToolResultBudget(operation: String, result: ToolResult): ToolResult {
    val serializedBytes = Json.encodeToString(result).toByteArray(Charsets.UTF_8).size.toLong()
    return if (serializedBytes <= WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxOutputBytes) {
        result
    } else {
        workspaceTextFailureResult(
            operation,
            WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.OUTPUT_TOO_LARGE,
                "结果超过输出上限，请缩小读取或差异范围后重试。",
            ),
        )
    }
}
