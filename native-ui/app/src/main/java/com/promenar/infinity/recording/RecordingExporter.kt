package com.promenar.infinity.recording

import android.content.Context
import com.promenar.infinity.data.export.ExportAssets
import com.promenar.infinity.data.export.ExportHtmlRenderer
import com.promenar.infinity.data.export.ExportWebViewRenderer
import com.promenar.infinity.data.export.RecordingExportDocument
import com.promenar.infinity.data.export.RecordingExportSegment
import com.promenar.infinity.data.export.cleanupExpiredExportDirectories
import com.promenar.infinity.data.export.copyExportStream
import com.promenar.infinity.data.export.exportBaseName
import com.promenar.infinity.data.export.recordingExportMetadata
import com.promenar.infinity.domain.export.SessionExportEstimate
import com.promenar.infinity.domain.export.SessionExportFile
import com.promenar.infinity.domain.export.SessionExportFormat
import com.promenar.infinity.domain.export.SessionExportResult
import kotlinx.coroutines.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.math.ceil

internal enum class RecordingExportScope { ORGANIZED_ONLY, ORGANIZED_AND_RAW, RAW_ONLY }

internal class RecordingExporter(context: Context) {
    private val context = context.applicationContext
    private val html = ExportHtmlRenderer(ExportAssets { path -> this.context.assets.open(path).use { it.readBytes() } })
    private val root get() = File(context.cacheDir, "recording-export")
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun buildDocument(
        session: RecordingSession,
        scope: RecordingExportScope,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): RecordingExportDocument {
        val organized = session.organized.takeIf { scope != RecordingExportScope.RAW_ONLY && it.isNotBlank() }
        val source = session.organizedSource?.takeIf { organized != null }
        val speakers = session.segments.mapNotNull { it.speakerId }.toSet()
        val speakerCount = when {
            session.segments.isEmpty() -> 0
            session.distinguishSpeakers && speakers.isNotEmpty() -> speakers.size
            else -> 1
        }
        return RecordingExportDocument(
            title = session.title,
            createdAt = session.createdAt,
            exportedAt = nowEpochMs,
            durationMs = session.durationMs,
            speakerCount = speakerCount,
            modelSummary = source?.let { listOf(it.provider, it.model).filter(String::isNotBlank).joinToString(" · ") },
            organizedMarkdown = organized,
            rawSegments = if (scope == RecordingExportScope.ORGANIZED_ONLY) emptyList() else session.segments.map {
                RecordingExportSegment(recordingTimestamp(it.startMs), session.exportSpeaker(it), it.text)
            },
        )
    }

    fun estimate(session: RecordingSession, format: SessionExportFormat, scope: RecordingExportScope): SessionExportEstimate {
        val doc = buildDocument(session, scope)
        if (format in setOf(SessionExportFormat.MARKDOWN, SessionExportFormat.TXT, SessionExportFormat.HTML)) {
            return SessionExportEstimate(1, false, renderContent(doc, format).encodeToByteArray().size.toLong())
        }
        val text = renderContent(doc, SessionExportFormat.MARKDOWN)
        val lines = text.lineSequence().sumOf { ceil(it.length / 40.0).toLong().coerceAtLeast(1) }
        val height = 240L + lines * 52L + doc.rawSegments.size * 36L
        return if (format == SessionExportFormat.PNG) {
            SessionExportEstimate(1, false, height * EXPORT_WIDTH_PX / 5)
        } else {
            val pages = ceil(height / 2136.0).toInt().coerceAtLeast(1)
            SessionExportEstimate(1, false, pages * 40_000L + text.length * 2L, pages)
        }
    }

    suspend fun export(session: RecordingSession, format: SessionExportFormat, scope: RecordingExportScope): SessionExportResult {
        var created: File? = null
        return try {
            withContext(Dispatchers.IO) {
                check(root.mkdirs() || root.isDirectory) { "无法创建录音导出目录" }
                cleanupExpiredExportDirectories(root, System.currentTimeMillis())
                val workDir = File(root, UUID.randomUUID().toString())
                check(workDir.mkdir()) { "无法创建临时录音导出目录" }
                created = workDir
                val doc = buildDocument(session, scope)
                val base = exportBaseName(doc.title, doc.createdAt)
                val files = if (format == SessionExportFormat.PNG || format == SessionExportFormat.PDF) {
                    ExportWebViewRenderer(context).renderUnbounded(html.renderRecording(doc, format), workDir, base, format, EXPORT_WIDTH_PX)
                } else {
                    val file = File(workDir, "$base.${format.extension}")
                    val content = renderContent(doc, format)
                    ensureActive()
                    file.outputStream().buffered().use { copyExportStream(content.byteInputStream(), it) }
                    listOf(SessionExportFile(file, file.name, format.mimeType))
                }
                ensureActive()
                workDir.setLastModified(System.currentTimeMillis())
                SessionExportResult(files, workDir)
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) { created?.deleteRecursively() }
            throw failure
        }
    }

    fun release(result: SessionExportResult) {
        cleanupScope.launch {
            val target = result.workDir
            val uuid = runCatching { UUID.fromString(target.name).toString() }.getOrNull()
            if (target.name == uuid && target.parentFile?.canonicalFile == root.canonicalFile && !Files.isSymbolicLink(target.toPath())) {
                target.deleteRecursively()
            }
        }
    }

    private fun renderContent(doc: RecordingExportDocument, format: SessionExportFormat): String {
        if (format == SessionExportFormat.HTML) return html.renderRecording(doc, format)
        val markdown = format == SessionExportFormat.MARKDOWN
        return buildString {
            if (markdown) append("# ")
            appendLine(doc.title.replace(Regex("[\\r\\n]+"), " "))
            appendLine()
            recordingExportMetadata(doc).forEach { appendLine(it) }
            doc.organizedMarkdown?.let {
                appendLine()
                appendLine(if (markdown) "## 整理稿" else "整理稿")
                appendLine()
                appendLine(it)
            }
            if (doc.rawSegments.isNotEmpty()) {
                appendLine()
                appendLine(if (markdown) "## 原始转写" else "原始转写")
                appendLine()
                for (segment in doc.rawSegments) {
                    if (markdown) append("- ")
                    append(segment.timestamp).append(' ')
                    segment.speaker?.let { append(it).append("  ") }
                    appendLine(if (markdown) segment.text.replace("\n", "\n  ") else segment.text)
                }
            }
        }
    }

    private companion object {
        const val EXPORT_WIDTH_PX = 1440
    }
}
