package com.promenar.infinity.startup.diagnostics

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.room.withTransaction
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.backup.BackupStartupState
import com.promenar.infinity.ui.developer.DeveloperModeStore
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 由 Activity Manager 的 DUMP 权限保护，普通 ContentResolver 操作没有数据出口。 */
class DiagnosticDumpProvider : ContentProvider() {
    private val worker = ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
        Thread(runnable, "diagnostic-dump").apply { isDaemon = true }
    }

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun getType(uri: Uri): String? = null

    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        val app = context?.applicationContext as? InfinityApplication
        val reason = if (app == null) "application_unavailable" else executeDump(fd, app, args.orEmpty())
        if (reason != null) {
            writer.append(dumpStatus("diagnostics.error", reason)).append('\n')
            writer.flush()
        }
    }

    private fun executeDump(fd: FileDescriptor, app: InfinityApplication, args: Array<out String>): String? {
        val pipe = try {
            ParcelFileDescriptor.dup(fd)
        } catch (_: IOException) {
            return "unavailable"
        }
        val output = AtomicReference(Output.PENDING)
        val task = FutureTask {
            val lines = runBlocking {
                val store = DeveloperModeStore.get(app)
                DiagnosticDump(
                    debug = BuildConfig.DEBUG,
                    developerModeEnabled = { store.isEnabled() },
                    adbBridgeEnabled = { store.adbBridgeEnabled.value },
                    exporter = {
                        if (app.startupState.value != BackupStartupState.Ready) null
                        else SessionTraceExporter(
                            app.database.sessionDao(), app.database.messageDao(), app.database.toolExecutionLedgerDao(),
                            snapshotTransaction = { read -> app.database.withTransaction { read() } },
                        )
                    },
                ).lines(args)
            }
            if (output.compareAndSet(Output.PENDING, Output.WRITING)) {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe).bufferedWriter(Charsets.UTF_8).use { stream ->
                    for (line in lines) {
                        if (Thread.currentThread().isInterrupted) break
                        stream.appendLine(line)
                    }
                }
            }
        }
        val failure = try {
            // 平台在主线程回调，查询和传输都由工作线程在系统约 2 秒的预算内完成。
            worker.execute(task)
            task.get(1_400, TimeUnit.MILLISECONDS)
            null
        } catch (_: RejectedExecutionException) {
            "busy"
        } catch (_: TimeoutException) {
            "timeout"
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            "interrupted"
        } catch (_: ExecutionException) {
            "unavailable"
        }
        // 只在工作线程尚未开始输出时写提示，防止和已准备的 JSONL 交叉。
        val unusedPipe = output.compareAndSet(Output.PENDING, Output.CLOSED)
        if (!task.isDone) task.cancel(true)
        if (!unusedPipe) return null
        runCatching { pipe.close() }
        return failure
    }

    private enum class Output { PENDING, WRITING, CLOSED }
}

internal class DiagnosticDump(
    private val debug: Boolean,
    private val developerModeEnabled: () -> Boolean,
    private val adbBridgeEnabled: () -> Boolean = { true },
    private val exporter: () -> SessionTraceExporter?,
    private val incremental: suspend (Long, String?, Int) -> List<UnifiedDiagnosticRecord> = { since, session, limit ->
        AppDiagnostics.readEvents(since, session, limit, acceptReference = true)
    },
    private val recent: suspend (String?, Int) -> List<UnifiedDiagnosticRecord> = { session, limit ->
        AppDiagnostics.readRecentEvents(session, limit, acceptReference = true)
    },
    private val threadSnapshot: () -> Map<Thread, Array<StackTraceElement>> = Thread::getAllStackTraces,
) {
    suspend fun lines(args: Array<out String>): List<String> {
        if (!debug) {
            if (!developerModeEnabled()) {
                return listOf(dumpStatus("diagnostics.blocked", "developer_mode_disabled"))
            }
            if (!adbBridgeEnabled()) {
                return listOf(dumpStatus("diagnostics.blocked", "adb_bridge_disabled"))
            }
        }
        return when (args.firstOrNull() ?: "--recent") {
            "--help" -> if (args.size == 1) listOf(help()) else invalidArguments()
            "--recent" -> readRecent(args.drop(1))
            "--sessions" -> if (args.size == 1) withExporter { it.listRecentSessions(50) } else invalidArguments()
            "--trace" -> readTrace(args.drop(1))
            "--threads" -> if (args.size == 1) diagnosticThreadLines(threadSnapshot()) else invalidArguments()
            else -> invalidArguments()
        }
    }

    private suspend fun readRecent(options: List<String>): List<String> {
        val values = parseOptions(options, "--since", "--session", "--limit") ?: return invalidArguments()
        val since = values["--since"]?.let { it.toLongOrNull()?.takeIf { seq -> seq >= 0 } ?: return invalidArguments() }
        val session = values["--session"]?.takeIf(::validSession)
        if (session == null && "--session" in values) return invalidArguments()
        val limit = values["--limit"]?.let { it.toIntOrNull()?.takeIf { n -> n in 1..500 } ?: return invalidArguments() } ?: 200
        val events = if (since == null) recent(session, limit) else incremental(since, session, limit)
        return events.map(UnifiedDiagnosticRecord::toJsonLine)
    }

    private suspend fun readTrace(arguments: List<String>): List<String> {
        val session = arguments.firstOrNull()?.takeIf(::validSession) ?: return invalidArguments()
        val options = parseOptions(arguments.drop(1), "--limit", "--before") ?: return invalidArguments()
        val page = if (options.isEmpty()) null else {
            val limit = options["--limit"]?.let {
                it.toIntOrNull()?.takeIf { n -> n in 1..100 } ?: return invalidArguments()
            } ?: 100
            val cursor = options["--before"]?.let { it.takeIf(::validSession) ?: return invalidArguments() }
            TracePage(limit, cursor)
        }
        return withExporter { it.export(session, page) ?: listOf(dumpStatus("diagnostics.error", "session_or_cursor_not_found")) }
    }

    private fun parseOptions(arguments: List<String>, vararg allowed: String): Map<String, String>? {
        if (arguments.size % 2 != 0 || arguments.size > allowed.size * 2) return null
        val pairs = arguments.chunked(2)
        val values = pairs.associate { (key, value) -> key to value }
        return values.takeIf { it.size == pairs.size && it.keys.all { key -> key in allowed } }
    }

    private suspend fun withExporter(read: suspend (SessionTraceExporter) -> List<String>): List<String> =
        exporter()?.let { read(it) } ?: listOf(dumpStatus("diagnostics.error", "startup_unavailable"))

    private fun validSession(id: String): Boolean = id.isNotBlank() && id.length <= 160 && !id.startsWith("--")

    private fun invalidArguments(): List<String> = listOf(dumpStatus("diagnostics.error", "invalid_arguments"))

    private fun help() = buildJsonObject {
        put("event", "diagnostics.help")
        put("usage", "adb shell dumpsys activity provider <applicationId>/com.promenar.infinity.startup.diagnostics.DiagnosticDumpProvider")
        put("recent", "--recent [--since <seq>] [--session <id>] [--limit <1..500>]")
        put("sessions", "--sessions")
        put("threads", "--threads")
        put("trace", "--trace <sessionId|latest> [--limit <1..100>] [--before <messageId>]")
        put("tracePaging", "分页从较新的消息向前读取。收到 trace.end 后使用 nextBeforeMessageId，关联事件用 --recent 增量读取。")
        put("defaultLimit", 200)
    }.toString()
}

private fun dumpStatus(event: String, reason: String): String = buildJsonObject {
    put("event", event)
    put("reason", reason)
}.toString()
