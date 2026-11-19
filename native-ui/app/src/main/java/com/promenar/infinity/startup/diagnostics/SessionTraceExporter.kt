package com.promenar.infinity.startup.diagnostics

import com.promenar.infinity.data.files.isSystemSession
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.ToolExecutionLedgerDao
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.infinity.data.local.db.entity.ToolLedgerStatus
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.domain.model.SessionRunMode
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlin.coroutines.coroutineContext

internal class SessionTraceExporter(
    private val sessions: SessionDao,
    private val messages: MessageDao,
    private val ledger: ToolExecutionLedgerDao,
    private val diagnostics: suspend (String) -> List<UnifiedDiagnosticRecord> = {
        AppDiagnostics.readRecentEvents(sessionId = it, limit = Int.MAX_VALUE)
    },
    private val snapshotTransaction: suspend (suspend () -> TraceSnapshot?) -> TraceSnapshot? = { it() },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun listRecentSessions(limit: Int): List<String> =
        recentSessions(limit).map { session ->
            traceLine("trace.session") {
                put("sessionId", DiagnosticRedactor.correlationId(session.id))
                put("title", DiagnosticRedactor.traceTitle(session.title, session.id))
                put("runMode", SessionRunMode.fromStored(session.runMode)?.name)
                put("updatedAt", session.updatedAt)
            }
        }

    suspend fun export(sessionId: String, page: TracePage? = null): List<String>? {
        val snapshot = snapshotTransaction { loadSnapshot(sessionId, page) } ?: return null
        val session = snapshot.session
        val safeSessionId = DiagnosticRedactor.correlationId(session.id)
        val events = (if (page == null) diagnostics(session.id) else emptyList()).filter {
            it.fieldText("sessionId") == safeSessionId &&
                (it.event.startsWith("llm.") || it.event.startsWith("tool.") || it.event.startsWith("linux."))
        }
        val toolEvents = events.asSequence().filter { it.event == "tool.approval" || it.event == "tool.end" }
            .groupBy { it.fieldText("messageId").orEmpty() to it.fieldText("callId").orEmpty() }
        val context = coroutineContext
        return buildList {
            add(traceLine("trace.header") {
                diagnosticBuildFields().forEach { (key, value) -> put(key, value) }
                put("sessionId", safeSessionId)
                put("title", DiagnosticRedactor.traceTitle(session.title, session.id))
                put("runMode", SessionRunMode.fromStored(session.runMode)?.name)
                put("exportedAt", clock())
                put("appDiagnosticsIncluded", page == null)
            })
            for (turn in snapshot.turns) {
                context.ensureActive()
                add(turnLine(turn.message))
                val calls = decodeList<ToolCall>(turn.message.toolCalls).associateBy(ToolCall::id)
                val entries = turn.ledger.associateBy(ToolExecutionLedgerEntity::toolCallId)
                val messageKey = DiagnosticRedactor.correlationId(turn.message.id)
                for (callId in calls.keys + entries.keys) {
                    context.ensureActive()
                    val related = toolEvents[messageKey to DiagnosticRedactor.correlationId(callId)].orEmpty()
                    add(toolLine(turn.message, callId, calls[callId], entries[callId], related))
                }
            }
            for (event in events) {
                context.ensureActive()
                add(event.toJsonLine())
            }
            add(traceLine("trace.end") {
                put("turnCount", snapshot.turns.size)
                if (page != null) put("nextBeforeMessageId", snapshot.nextBeforeMessageId)
            })
        }
    }

    private suspend fun recentSessions(limit: Int): List<SessionEntity> {
        if (limit <= 0) return emptyList()
        return sessions.getAll().asSequence().filterNot(::isSystemSession).take(limit).toList()
    }

    private suspend fun loadSnapshot(sessionId: String, page: TracePage?): TraceSnapshot? {
        val session = when {
            sessionId == "latest" -> recentSessions(1).firstOrNull()
            DiagnosticRedactor.isReference(sessionId) ->
                sessions.getAll().firstOrNull { DiagnosticRedactor.correlationId(it.id) == sessionId }
            else -> sessions.getById(sessionId)
        }?.takeUnless(::isSystemSession) ?: return null
        val rows = if (page == null) {
            messages.getBySession(session.id)
        } else {
            val before = page.beforeMessageId?.let { findMessageCursor(session.id, it) ?: return null }
            messages.historyBefore(session.id, before, page.limit + 1, emptyList()).take(page.limit + 1)
        }
        val hasOlder = page != null && rows.size > page.limit
        val selected = if (page == null) rows else rows.take(page.limit).asReversed()
        val next = if (hasOlder) DiagnosticRedactor.correlationId(selected.first().id) else null
        val turns = selected.map { message ->
            coroutineContext.ensureActive()
            val entries = if (message.role == "assistant") ledger.getForAssistant(session.id, message.id)
                .sortedWith(compareBy(ToolExecutionLedgerEntity::createdAt, ToolExecutionLedgerEntity::toolCallId))
            else emptyList()
            TraceTurn(message, entries)
        }
        return TraceSnapshot(session, turns, nextBeforeMessageId = next)
    }

    private suspend fun findMessageCursor(sessionId: String, reference: String): String? =
        if (DiagnosticRedactor.isReference(reference)) {
            sessions.getContextMessageIds(sessionId).firstOrNull { DiagnosticRedactor.correlationId(it) == reference }
        } else {
            messages.getById(reference)?.takeIf { it.sessionId == sessionId }?.id
        }

    private fun turnLine(message: MessageEntity): String = traceLine("trace.turn") {
        put("messageId", DiagnosticRedactor.correlationId(message.id))
        put("role", message.role.takeIf { it in setOf("user", "assistant", "tool", "system", "function", "developer") })
        put("modelId", message.modelId?.let(DiagnosticRedactor::safeLabel))
        put("createdAt", message.createdAt)
        put("contentLength", message.content.length)
        val steps = message.executionSteps?.let { runCatching { diagnosticJson.parseToJsonElement(it) }.getOrNull() }
        put("stepCount", (steps as? JsonArray)?.size ?: 0)
        put("isError", message.isError != 0)
    }

    private fun toolLine(
        message: MessageEntity,
        callId: String,
        call: ToolCall?,
        entry: ToolExecutionLedgerEntity?,
        events: List<UnifiedDiagnosticRecord>,
    ): String = traceLine("trace.tool_call") {
        val approval = events.lastOrNull { it.event == "tool.approval" }
        val completed = events.lastOrNull {
            it.event == "tool.end" && it.fieldText("status") != "SKIPPED" &&
                (entry == null || it.fieldText("status") == entry.status.name)
        }
        put("callId", DiagnosticRedactor.correlationId(callId))
        put("messageId", DiagnosticRedactor.correlationId(message.id))
        put("toolName", (entry?.toolName ?: call?.name)?.let(DiagnosticRedactor::safeLabel))
        val arguments = call?.arguments?.let { runCatching { diagnosticJson.parseToJsonElement(it) }.getOrNull() }
        put("arguments", if (arguments is JsonObject) DiagnosticRedactor.toolArgumentSummary(arguments) else JsonNull)
        put("requiresApproval", entry?.let { JsonPrimitive(it.requiresApproval) } ?: JsonNull)
        put("approvalOutcome", entry?.approvalOutcome() ?: approval?.fieldText("approvalOutcome"))
        put("ledgerStatus", entry?.status?.name)
        put("status", entry?.status?.name ?: completed?.fieldText("status"))
        put("errorCode", completed?.fieldText("errorCode")
            ?: approval?.takeIf { it.fieldText("status") == entry?.status?.name }?.fieldText("errorCode")
            ?: entry?.status?.takeIf { it in failedStates }?.name)
        put("errorSummary", entry?.error?.let(DiagnosticRedactor::traceError))
        val measured = completed?.takeIf { it.fieldText("status") in measuredStates }
        put("durationMs", measured?.fields?.get("elapsedMs") ?: JsonNull)
    }

    private fun ToolExecutionLedgerEntity.approvalOutcome(): String? = when {
        !requiresApproval -> "NOT_REQUIRED"
        status == ToolLedgerStatus.PENDING_APPROVAL -> "PENDING"
        status == ToolLedgerStatus.REJECTED -> "REJECTED"
        status in approvedStates -> "APPROVED"
        else -> null
    }

    private companion object {
        val approvedStates = setOf(ToolLedgerStatus.APPROVED, ToolLedgerStatus.RUNNING, ToolLedgerStatus.SUCCEEDED)
        val measuredStates = setOf("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT")
        val failedStates = setOf(ToolLedgerStatus.FAILED, ToolLedgerStatus.REJECTED,
            ToolLedgerStatus.CANCELLED, ToolLedgerStatus.TIMED_OUT)
    }
}

internal data class TracePage(val limit: Int, val beforeMessageId: String? = null)

internal data class TraceSnapshot(
    val session: SessionEntity, val turns: List<TraceTurn>, val nextBeforeMessageId: String?,
)

internal data class TraceTurn(val message: MessageEntity, val ledger: List<ToolExecutionLedgerEntity>)

private fun traceLine(event: String, fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String =
    buildJsonObject {
        put("event", event)
        fields()
    }.toString()

private fun UnifiedDiagnosticRecord.fieldText(key: String): String? =
    (fields[key] as? JsonPrimitive)?.contentOrNull?.takeUnless { it == "[omitted]" }

private inline fun <reified T> decodeList(value: String?): List<T> =
    value?.let { runCatching { diagnosticJson.decodeFromString<List<T>>(it) }.getOrNull() }.orEmpty()
