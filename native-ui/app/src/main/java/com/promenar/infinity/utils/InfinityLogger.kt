package com.promenar.infinity.utils

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.startup.diagnostics.AppDiagnostics
import com.promenar.infinity.startup.diagnostics.SessionTraceExporter
import java.io.File
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

object InfinityLogger {
    private const val TAG = "InfinityLogger"
    private val isAndroid = System.getProperty("java.vendor") == "The Android Project"

    @Synchronized
    fun init(context: Context) {
        if (!isAndroid) return
        AppDiagnostics.initialize(context.filesDir)
        val originalHandler = Thread.getDefaultUncaughtExceptionHandler()
        if (originalHandler is DiagnosticExceptionHandler) return
        Thread.setDefaultUncaughtExceptionHandler(DiagnosticExceptionHandler(originalHandler))
    }

    private class DiagnosticExceptionHandler(
        private val delegate: Thread.UncaughtExceptionHandler?,
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(thread: Thread, error: Throwable) {
            try {
                diagnostic("app.uncaught", mapOf("exception" to error.javaClass.simpleName))
                logError("FATAL EXCEPTION", error)
                runBlocking { withTimeoutOrNull(300) { AppDiagnostics.flush() } }
            } finally {
                delegate?.uncaughtException(thread, error)
            }
        }
    }

    /** 发行版可用。调用方只提供经过审定的结构化元数据。 */
    fun diagnostic(event: String, fields: Map<String, Any?> = emptyMap()) = AppDiagnostics.record(event, fields)

    suspend fun prepareDiagnosticExport(context: Context): File = AppDiagnostics.prepareJsonlExport(
        context.filesDir,
        (context.applicationContext as? InfinityApplication)?.startupIssueManager?.formatIssues().orEmpty(),
    )

    suspend fun prepareSessionTraceExport(context: Context, sessionId: String): File? {
        val app = context.applicationContext as? InfinityApplication ?: return null
        val lines = SessionTraceExporter(
            app.database.sessionDao(),
            app.database.messageDao(),
            app.database.toolExecutionLedgerDao(),
            snapshotTransaction = { read -> app.database.withTransaction { read() } },
        ).export(sessionId) ?: return null
        return AppDiagnostics.writeJsonlExport(context.filesDir, "infinity_session_trace-") { writer ->
            for (line in lines) {
                coroutineContext.ensureActive()
                writer.appendLine(line)
            }
        }
    }

    fun log(message: String) {
        if (!com.promenar.infinity.BuildConfig.DEBUG) return
        val safeMessage = SensitiveDataRedactor.redactMessage(message)
        if (!isAndroid) {
            println("[$TAG] $safeMessage")
            return
        }
        Log.d(TAG, safeMessage)
    }

    fun logError(tag: String, throwable: Throwable) {
        AppDiagnostics.recordError(tag, throwable)
        if (!com.promenar.infinity.BuildConfig.DEBUG) return
        val safeTag = SensitiveDataRedactor.redactMessage(tag).take(80)
        val safeError = SensitiveDataRedactor.safeThrowable(throwable, debug = true)
        if (!isAndroid) {
            System.err.println("[$TAG] ERROR [$safeTag]: $safeError")
            return
        }
        Log.e(TAG, "$safeTag: $safeError")
    }
}

private val forbiddenDiagnosticFieldFragments = setOf(
    "authorization", "apikey", "api_key", "secret", "password", "cookie",
    "prompt", "content", "message", "request", "response", "body", "url",
)
private val diagnosticKey = Regex("[^A-Za-z0-9_.-]")

internal fun sanitizeDiagnosticFields(fields: Map<String, Any?>): Map<String, Any?> =
    fields.entries.take(16).associate { (key, value) ->
        val safeKey = key.replace(diagnosticKey, "_").take(48)
        val lowerKey = safeKey.lowercase(Locale.ROOT)
        val forbidden = (safeKey != "firstTokenMs" && "token" in lowerKey) ||
            (safeKey != "messageId" && forbiddenDiagnosticFieldFragments.any(lowerKey::contains))
        safeKey to if (forbidden) {
            "[omitted]"
        } else when (value) {
            null, is Boolean, is Byte, is Short, is Int, is Long -> value
            is Float -> value.takeIf(Float::isFinite)
            is Double -> value.takeIf(Double::isFinite)
            is String -> if (value.length <= 4_096) SensitiveDataRedactor.redactMessage(value) else "[omitted]"
            else -> "[omitted]"
        }
    }
