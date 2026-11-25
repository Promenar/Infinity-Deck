package com.promenar.infinity.data.export

import com.promenar.infinity.data.generation.isContextCompactionNotice
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageQuoteFormatter
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.export.SessionExportOptions
import kotlinx.serialization.json.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 格式无关的消息快照；附件字节只在格式确实需要时读取。 */
internal data class ExportDocument(
    val title: String,
    val model: String,
    val createdAt: Long,
    val exportedAt: Long,
    val messages: List<ExportMessage>,
    /** 以下两项只用于网页、PDF、图片等展示格式；Markdown 与纯文本保持可回传的原始标记。 */
    val agentName: String? = null,
    val modelNames: Map<String, String> = emptyMap(),
)

/** 展示格式的界面文案，跟随应用语言；默认值供测试与无 Context 场景使用。 */
internal data class ExportLabels(
    val user: String = "你",
    val assistant: String = "助手",
    val tool: String = "工具",
    val model: String = "模型",
    val exportedAt: String = "导出时间",
    val thinking: String = "思考过程",
    val attachments: String = "附件",
    val noMessages: String = "（没有消息）",
    val unreadable: String = "（不可读取）",
    val imageAlt: String = "附件图片",
    val htmlLang: String = "zh-CN",
)

internal data class ExportMessage(
    val role: String,
    val timestamp: Long,
    val model: String?,
    val body: String,
    val thinking: String? = null,
    val tools: List<ExportTool> = emptyList(),
    val attachments: List<ExportAttachment> = emptyList(),
)

internal data class ExportTool(val name: String, val status: String, val summary: String)

internal data class ExportAttachment(
    val name: String,
    val mimeType: String,
    val text: String? = null,
    val source: String? = null,
    val workspace: Boolean = false,
    val sizeBytes: Long = 0,
    val dataUri: String? = null,
) {
    // SVG 可携带脚本，不作为可信图片内嵌。
    val isText: Boolean get() = isExportTextMime(mimeType)
    val isImage: Boolean get() = mimeType.lowercase() in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp")
}

internal object ExportDocumentBuilder {
    fun build(session: Session, messages: List<Message>, options: SessionExportOptions, now: Long): ExportDocument {
        val toolMessages = messages.filter { it.role == MessageRole.TOOL }
        val byCall = toolMessages.filter { it.toolCallId != null }.associateBy { it.toolCallId }
        val consumed = mutableSetOf<String>()
        val result = messages.mapNotNull { message ->
            if (message.role == MessageRole.TOOL || message.isContextCompactionNotice) return@mapNotNull null
            val tools = if (!options.includeToolCalls) emptyList() else buildList {
                val steps = message.executionSteps.orEmpty().filter { it.type in setOf("tool_call", "tool_result", "error") }
                val groups = linkedMapOf<String, MutableList<com.promenar.infinity.data.model.ExecutionStep>>()
                steps.forEach { step -> groups.getOrPut(step.toolCallId ?: step.id.removePrefix("res_")) { mutableListOf() } += step }
                message.toolCalls.orEmpty().forEach { call -> groups.getOrPut(call.id) { mutableListOf() } }
                groups.forEach { (id, entries) ->
                    val call = message.toolCalls.orEmpty().find { it.id == id }
                    val stored = byCall[id]
                    stored?.let { consumed += it.id }
                    val completed = entries.lastOrNull { it.type in setOf("tool_result", "error") }
                    val name = entries.firstNotNullOfOrNull { it.toolName } ?: call?.name ?: stored?.name ?: "工具"
                    val failed = completed?.type == "error" || stored?.isError == true || stored?.status in setOf("error", "failed")
                    val status = when { failed -> "失败"; completed != null || stored != null -> "成功"; else -> "未完成" }
                    add(ExportTool(singleLine(name, 100), status, toolSummary(name, completed?.content ?: stored?.content.orEmpty())))
                }
            }
            ExportMessage(
                message.role.name, message.createdAt, message.modelId,
                MessageQuoteFormatter.formatUserContent(message.content, message.quotes),
                message.reasoning?.takeIf { options.includeThinking && message.role == MessageRole.ASSISTANT && it.isNotBlank() },
                tools, (attachments(message) + message.toolCalls.orEmpty().flatMap { call ->
                    byCall[call.id]?.let(::attachments).orEmpty()
                } + message.executionSteps.orEmpty().mapNotNull { it.toolCallId }.distinct().flatMap { id ->
                    byCall[id]?.let(::attachments).orEmpty()
                }).distinctBy { Triple(it.name, it.source, it.text) },
            )
        }.toMutableList()
        if (options.includeToolCalls) {
            toolMessages.filter { it.id !in consumed }.forEach { message ->
                result += ExportMessage("TOOL", message.createdAt, message.modelId, "", tools = listOf(
                    ExportTool(singleLine(message.name ?: "工具", 100),
                        if (message.isError || message.status in setOf("error", "failed")) "失败" else "成功",
                        toolSummary(message.name.orEmpty(), message.content)),
                ), attachments = attachments(message))
            }
        }
        return ExportDocument(session.title, session.modelId.orEmpty(), session.createdAt, now, result.sortedBy { it.timestamp })
    }

    private fun attachments(message: Message): List<ExportAttachment> = buildList {
        message.userDocuments.orEmpty().forEach { add(ExportAttachment(it.name, it.mimeType, text = it.content, sizeBytes = it.sizeBytes)) }
        message.userImages.orEmpty().forEachIndexed { index, uri ->
            val mime = dataUriMime(uri) ?: return@forEachIndexed
            add(ExportAttachment("image-${index + 1}.${imageExtension(mime)}", mime, source = uri, sizeBytes = uri.length.toLong() * 3 / 4,
                dataUri = uri.takeIf { isExportImageDataUri(it) }))
        }
        message.images?.takeIf(::isExportImageDataUri)?.let { uri ->
            val mime = requireNotNull(dataUriMime(uri))
            add(ExportAttachment("tool-image.${imageExtension(mime)}", mime, source = uri,
                dataUri = uri, sizeBytes = uri.length.toLong() * 3 / 4))
        }
        message.toolResults.orEmpty().forEachIndexed { index, artifact ->
            if (isExportImageDataUri(artifact.content)) {
                val mime = requireNotNull(dataUriMime(artifact.content))
                add(ExportAttachment(artifact.name ?: "artifact-${index + 1}.${imageExtension(mime)}", mime,
                    source = artifact.content, dataUri = artifact.content, sizeBytes = artifact.content.length.toLong() * 3 / 4))
            }
        }
        val media = message.executionSteps.orEmpty().flatMap { it.media.orEmpty() }.distinctBy { it.path }
        media.forEach { ref -> add(ExportAttachment(ref.path.substringAfterLast('/').ifBlank { "media" }, ref.mimeType,
            source = ref.path, workspace = true, sizeBytes = ref.sizeBytes)) }
        listOf(message.legacyAttachmentsPayload, message.legacyFilesPayload).forEach { payload ->
            if (!payload.isNullOrBlank()) addAll(parseLegacyExportAttachments(payload))
        }
    }.distinctBy { Triple(it.name, it.source, it.text) }
}

internal fun singleLine(text: String, limit: Int = 160): String {
    val line = text.replace(Regex("\\s+"), " ").trim()
    val count = line.codePointCount(0, line.length)
    return if (count <= limit) line else line.substring(0, line.offsetByCodePoints(0, limit - 1)) + "…"
}

private fun toolSummary(name: String, result: String): String {
    if (result.isBlank()) return "无结果摘要"
    // 文件及命令工具的输出可能就是文件全文或命令日志，只报告体量。
    if (Regex("read|write|edit|file|exec|shell|command|python|terminal|script|bash|powershell", RegexOption.IGNORE_CASE).containsMatchIn(name)) {
        return "结果已记录（${result.length} 字符）"
    }
    val structured = runCatching { Json.parseToJsonElement(result) as? JsonObject }.getOrNull()
    if (structured != null) {
        val summary = listOf("summary", "message", "error").firstNotNullOfOrNull { (structured[it] as? JsonPrimitive)?.contentOrNull }
        return singleLine(summary ?: "结构化结果已记录（${result.length} 字符）")
    }
    return singleLine(result)
}

internal fun parseLegacyExportAttachments(payload: String): List<ExportAttachment> {
    if (payload.length > 24 * 1024 * 1024) return listOf(ExportAttachment("历史附件清单（超限）", "application/octet-stream"))
    val parsed = runCatching { Json.parseToJsonElement(payload) }.getOrNull()
    val items = when (parsed) {
        is JsonArray -> parsed
        is JsonObject -> (parsed["attachments"] ?: parsed["files"]) as? JsonArray ?: JsonArray(listOf(parsed))
        else -> return listOf(ExportAttachment("历史附件（记录不可读）", "application/octet-stream"))
    }
    if (items.size > 100) return listOf(ExportAttachment("历史附件清单（超限）", "application/octet-stream"))
    return items.mapIndexedNotNull { index, item ->
        val obj = item as? JsonObject ?: return@mapIndexedNotNull null
        fun string(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull
        val source = string("uri") ?: string("path")
        val mime = string("mimeType") ?: source?.let(::dataUriMime) ?: "application/octet-stream"
        ExportAttachment(string("fileName") ?: string("name") ?: "attachment-${index + 1}", mime,
            text = string("content")?.takeIf { isExportTextMime(mime) }, source = source,
            sizeBytes = (obj["sizeBytes"] as? JsonPrimitive)?.longOrNull?.coerceAtLeast(0) ?: 0,
            dataUri = source?.takeIf(::isExportImageDataUri))
    }
}

internal fun exportArchiveAttachments(doc: ExportDocument, format: SessionExportFormat, enabled: Boolean): List<ExportAttachment> {
    if (!enabled || !format.supportsAttachments) return emptyList()
    return doc.messages.flatMap { it.attachments }.filter {
        it.text == null && !it.isText && it.source != null && (format != SessionExportFormat.HTML || !it.isImage)
    }
}

internal fun dataUriMime(uri: String): String? = Regex("^data:([A-Za-z0-9.+/-]+);base64,", RegexOption.IGNORE_CASE).find(uri)?.groupValues?.get(1)?.lowercase()
internal fun isExportImageDataUri(uri: String): Boolean =
    dataUriMime(uri) in setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp") &&
        uri.substringAfter(',').all { it.isLetterOrDigit() && it.code < 128 || it in "+/=\r\n" }
internal fun imageExtension(mime: String): String = when (mime) { "image/jpeg" -> "jpg"; "image/png" -> "png"; "image/webp" -> "webp"; "image/gif" -> "gif"; "image/bmp" -> "bmp"; else -> "bin" }
internal fun exportTimestamp(time: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(time))
internal fun exportBaseName(title: String, time: Long): String =
    exportUtf8Prefix(title.replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fff_.\\-]"), "_"), 180).ifBlank { "session" } + "_" +
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date(time))
internal fun exportHeading(message: ExportMessage): String = "${message.role} · ${exportTimestamp(message.timestamp)}" +
    (message.model?.takeIf { it.isNotBlank() }?.let { " · Model: $it" } ?: "")
/** 展示格式的消息头：角色用“你/智能体名”，模型用显示名，找不到时去掉提供商前缀。 */
internal fun exportPresentationHeading(message: ExportMessage, doc: ExportDocument, labels: ExportLabels): String {
    val who = when (message.role) {
        "USER" -> labels.user
        "TOOL" -> labels.tool
        else -> doc.agentName?.takeIf { it.isNotBlank() } ?: labels.assistant
    }
    val model = message.model?.takeIf { it.isNotBlank() && message.role != "USER" }?.let { exportModelName(it, doc) }
    return listOfNotNull(who, exportTimestamp(message.timestamp), model).joinToString(" · ")
}
internal fun exportModelName(modelId: String, doc: ExportDocument): String = doc.modelNames[modelId] ?: modelId.substringAfter("::")
internal fun escapeExportHtml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    .replace("\"", "&quot;").replace("'", "&#39;")

/** 预留时间戳、扩展名与切片序号，避免中文标题越过文件系统的单项字节上限。 */
internal fun exportUtf8Prefix(text: String, maxBytes: Int): String {
    var end = 0
    var bytes = 0
    while (end < text.length) {
        val point = text.codePointAt(end)
        val count = when { point <= 0x7f -> 1; point <= 0x7ff -> 2; point <= 0xffff -> 3; else -> 4 }
        if (bytes + count > maxBytes) break
        bytes += count
        end += Character.charCount(point)
    }
    return text.substring(0, end)
}

internal fun isExportTextMime(mime: String): Boolean = mime.substringBefore(';').lowercase().let {
    it.startsWith("text/") || it in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")
}
