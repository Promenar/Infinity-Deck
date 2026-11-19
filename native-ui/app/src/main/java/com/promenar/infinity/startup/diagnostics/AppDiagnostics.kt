package com.promenar.infinity.startup.diagnostics

import com.promenar.infinity.utils.sanitizeDiagnosticFields
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

/** 写入入口只排队脱敏后的事件，磁盘操作由专用守护线程执行。 */
object AppDiagnostics {
    @Volatile private var session: RecorderSession? = null
    private val generationAttempt = ThreadLocal<GenerationAttempt?>()
    private val exportMutex = Mutex()
    private val exportName = Regex("infinity_diagnostics_export-[0-9]+\\.jsonl")

    // 复用生成端已有的延迟回调，在当前请求的协程内关联真实重试序号。
    internal suspend fun <T> captureGenerationAttempt(
        sessionId: String?, report: (Int) -> Unit, action: suspend () -> T,
    ): T = withContext(generationAttempt.asContextElement(GenerationAttempt(sessionId, report))) { action() }


    @Synchronized
    fun initialize(filesDir: File) {
        val directory = File(filesDir, "diagnostics").absoluteFile
        if (session?.directory == directory) return
        val previous = session
        session = RecorderSession(directory)
        previous?.commands?.close()
    }

    internal fun record(event: String, fields: Map<String, Any?>) {
        val active = session ?: return
        if (!DiagnosticRedactor.isStructuredEvent(event)) return
        runCatching {
            if (event == "generation.latency" && fields["stage"] == "prepare_ms") {
                val binding = generationAttempt.get()
                val attempt = (fields["attempt"] as? Int)?.takeIf { it >= 0 }
                if (binding != null && binding.sessionId == fields["sessionId"] && attempt != null) {
                    binding.report(attempt)
                }
            }
            val projected = fields.filterKeys { it in DiagnosticRedactor.diagnosticFieldNames(event) }
            val safe = JsonObject(sanitizeDiagnosticFields(projected).mapValues { (key, value) ->
                val scalar = if (DiagnosticRedactor.isCorrelationField(key)) projected[key] as? String else value
                when (scalar) {
                    null -> JsonNull
                    is Boolean -> JsonPrimitive(scalar)
                    is Number -> JsonPrimitive(scalar)
                    else -> JsonPrimitive(scalar.toString())
                }
            })
            active.commands.trySend(Command.Record(UnifiedDiagnosticRecord(
                timestampMs = System.currentTimeMillis(),
                event = event,
                fields = DiagnosticRedactor.projectStructuredFields(event, safe),
            )))
        }
    }

    fun recordError(step: String, error: Throwable) = recordException(step, error)

    fun recordStartupIssue(step: String, scopeKind: String, scopeId: String, error: Throwable) =
        recordException(step, error, scopeKind, scopeId)

    private fun recordException(step: String, error: Throwable, scopeKind: String? = null, scopeId: String? = null) {
        val active = session ?: return
        runCatching {
            val reference = scopeId?.takeIf { scopeKind == "SESSION" }?.let(DiagnosticRedactor::correlationId)
            active.commands.trySend(Command.Exception(DiagnosticRedactor.snapshot(step, error, scopeKind, scopeId), reference))
        }
    }

    suspend fun recent(): String = flushedRecorder()?.recent().orEmpty()

    internal suspend fun readEvents(
        sinceSeq: Long = 0, sessionId: String? = null, limit: Int = 500, acceptReference: Boolean = false,
    ) = flushedRecorder()?.readEvents(sinceSeq, sessionId, limit, acceptReference).orEmpty()

    internal suspend fun readRecentEvents(
        sessionId: String? = null, limit: Int = 200, acceptReference: Boolean = false,
    ) = flushedRecorder()?.readRecentEvents(sessionId, limit, acceptReference).orEmpty()

    internal suspend fun latestSeq(): Long = flushedRecorder()?.latestSeq() ?: 0

    internal suspend fun flush() {
        flushedRecorder()
    }

    suspend fun export(output: OutputStream, issueSummary: String) {
        val recorder = flushedRecorder()
        if (recorder != null) recorder.export(output, issueSummary)
        else withContext(diagnosticsDispatcher) { writeDiagnosticExport(output, issueSummary, "") }
    }

    internal suspend fun prepareJsonlExport(filesDir: File, issueSummary: String): File {
        val snapshot = flushedRecorder()?.snapshot().orEmpty()
        return writeJsonlExport(filesDir, "infinity_diagnostics_export-", exportName) { writer ->
            writer.appendLine(buildJsonObject {
                put("event", "app.build")
                diagnosticBuildFields().forEach { (key, value) -> put(key, value) }
            }.toString())
            for (record in snapshot) {
                coroutineContext.ensureActive()
                writer.appendLine(record.toJsonLine())
            }
            writer.appendLine(buildJsonObject {
                put("event", "app.startup_summary")
                put("summary", DiagnosticRedactor.sanitizeIssueSummary(issueSummary))
            }.toString())
        }
    }

    /** 以安全临时文件写出 JSONL 导出并按前缀保留最近 3 份，路径位于 filesDir 内以匹配 FileProvider。 */
    internal suspend fun writeJsonlExport(
        filesDir: File,
        prefix: String,
        retentionPattern: Regex = Regex("${Regex.escape(prefix)}[0-9]+\\.jsonl"),
        writeLines: suspend (java.io.BufferedWriter) -> Unit,
    ): File = withContext(diagnosticsDispatcher) {
        exportMutex.withLock {
            val file = Files.createTempFile(filesDir.toPath(), prefix, ".jsonl")
            try {
                Files.newOutputStream(file, StandardOpenOption.WRITE, NOFOLLOW_LINKS)
                    .bufferedWriter(Charsets.UTF_8).use { writer -> writeLines(writer) }
            } catch (error: Exception) {
                runCatching { Files.deleteIfExists(file) }
                throw error
            }
            // 每次分享拥有独立的 URI，最近导出可保留供已打开的分享目标使用。
            filesDir.listFiles().orEmpty().asSequence()
                .filter { it.toPath() != file && retentionPattern.matches(it.name) && Files.isRegularFile(it.toPath(), NOFOLLOW_LINKS) }
                .sortedByDescending(File::lastModified)
                .drop(3)
                .forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
            file.toFile()
        }
    }

    private suspend fun flushedRecorder(): AppDiagnosticRecorder? {
        val active = session ?: return null
        val barrier = CompletableDeferred<Unit>()
        active.commands.send(Command.Barrier(barrier))
        barrier.await()
        return active.recorder
    }

    private data class GenerationAttempt(val sessionId: String?, val report: (Int) -> Unit)

    private sealed interface Command {
        data class Record(val value: UnifiedDiagnosticRecord) : Command
        data class Exception(val value: DiagnosticEvent, val sessionReference: String?) : Command
        data class Barrier(val result: CompletableDeferred<Unit>) : Command
    }

    private class RecorderSession(val directory: File) {
        val commands = Channel<Command>(capacity = 512)
        val recorder = AppDiagnosticRecorder(directory)
        private val scope = CoroutineScope(SupervisorJob() + diagnosticsDispatcher)

        init {
            scope.launch {
                try {
                    for (command in commands) {
                        when (command) {
                            is Command.Record -> runCatching { recorder.record(command.value, projected = true) }
                            is Command.Exception -> runCatching { recorder.record(command.value, command.sessionReference) }
                            is Command.Barrier -> command.result.complete(Unit)
                        }
                    }
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
