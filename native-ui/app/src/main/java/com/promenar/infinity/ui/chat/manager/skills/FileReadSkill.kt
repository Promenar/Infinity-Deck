package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.model.ToolResult
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
        "单文件最多 100 MiB，每次最多 2000 行、约 190 KB；结果的“后续”行给出下一次的 startLine，超长行另给 lineByteOffset。"
    override val mcpServerId: String? = null
    override val risk = ToolRisk.SAFE_READ
    override val parametersSchema = """{"type":"object","properties":{"uuid":{"type":"string","description":"文件UUID"},"path":{"type":"string","description":"文件路径，如 /docs/a.md"},"mode":{"type":"string","enum":["page","range"],"default":"page"},"offset":{"type":"integer","description":"分页偏移(行号，0-based)"},"limit":{"type":"integer","description":"分页大小(行数，最多2000)","default":200},"startLine":{"type":"integer","description":"起始行号(1-based)"},"endLine":{"type":"integer","description":"结束行号(1-based)，一次最多返回2000行"},"lineByteOffset":{"type":"integer","description":"续读超长行：起始行内的字节偏移，取结果“后续”行给出的值","default":0}}}"""

    override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
        val (resolvedUuid, resolveError) = resolveWorkspaceFileUuid(workspaceRepo, context.workspaceRootUuid, args, id)
        if (resolveError != null) return resolveError
        val uuid = resolvedUuid!!
        val mode = args.stringArgument("mode") ?: "page"
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
}

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
