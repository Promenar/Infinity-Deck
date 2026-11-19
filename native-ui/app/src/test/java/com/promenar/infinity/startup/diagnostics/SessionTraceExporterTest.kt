package com.promenar.infinity.startup.diagnostics

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.ToolExecutionLedgerDao
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.infinity.data.local.db.entity.ToolLedgerStatus
import com.promenar.infinity.data.model.ToolCall
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test

class SessionTraceExporterTest {
    private val sessions = mockk<SessionDao>()
    private val messages = mockk<MessageDao>()
    private val ledger = mockk<ToolExecutionLedgerDao>()

    @Test
    fun `latest 排除系统会话并联结消息账本与诊断且全文零凭据命中`() = runTest {
        val id = "chat-1"
        val secrets = diagnosticCredentialFixtures
        val body = secrets.joinToString("\n") + "\n用户正文不导出"
        val session = SessionEntity(id, "assistant", title = body, runMode = "LINUX", createdAt = 10, updatedAt = 30)
        val system = session.copy(id = "system-1", agentId = "__system__", updatedAt = 40)
        val path = "/workspace/" + "资料😀".repeat(100)
        val mail = buildJsonObject {
            put("path", path)
            put("mode", "read")
            put("depth", 2)
            put("startLine", 3)
            put("endLine", 7)
            put("token", secrets[0])
            put("authorization", secrets[1])
            put("password", secrets[2])
            put("private_key", secrets[3])
            put("content", body)
            put("untrusted-key", body)
        }
        coEvery { sessions.getAll() } returns listOf(system, session)
        coEvery { messages.getBySession(id) } returns listOf(
            MessageEntity("user-1", id, "user", body, createdAt = 20),
            MessageEntity("assistant-1", id, "assistant", "回复正文不导出", modelId = "provider::model",
                executionSteps = """[{"type":"tool","content":"步骤正文不导出"}]""",
                toolCalls = diagnosticJson.encodeToString(listOf(
                    ToolCall("call-1", "read_file", mail.toString()),
                    ToolCall("call-2", "read_file", """{"path":"secure_env/settings.pem"}"""),
                )), createdAt = 21),
        )
        coEvery { ledger.getForAssistant(id, "assistant-1") } returns listOf(ToolExecutionLedgerEntity(
            id, "assistant-1", "call-1", "read_file", "read_file", "", "",
            true, ToolLedgerStatus.FAILED, error = body, createdAt = 22, updatedAt = 28,
        ))
        val toolEnd = UnifiedDiagnosticRecord(1, timestampMs = 28, event = "tool.end", fields = buildJsonObject {
            put("sessionId", id)
            put("messageId", "assistant-1")
            put("callId", "call-1")
            put("status", "FAILED")
            put("errorCode", "IO_ERROR")
            put("elapsedMs", 6)
        })
        val exporter = SessionTraceExporter(sessions, messages, ledger, diagnostics = {
            assertThat(it).isEqualTo(id)
            listOf(toolEnd,
                toolEnd.copy(seq = 2, event = "app.exception"),
                toolEnd.copy(seq = 3, fields = buildJsonObject {
                    toolEnd.fields.forEach { (key, value) -> put(key, value) }
                    put("status", "SKIPPED")
                    put("elapsedMs", 0)
                }),
                toolEnd.copy(seq = 4, fields = buildJsonObject { put("sessionId", "unrelated-session") }),
            )
        }, clock = { 50 })

        val recent = exporter.listRecentSessions(1).single().let { diagnosticJson.parseToJsonElement(it).jsonObject }
        assertThat(recent["sessionId"]).isEqualTo(JsonPrimitive(id))
        val lines = checkNotNull(exporter.export("latest"))
        val traced = lines.map { diagnosticJson.parseToJsonElement(it).jsonObject }
        assertThat(traced.map { it["event"] }).containsExactlyElementsIn(
            listOf("trace.header", "trace.turn", "trace.turn", "trace.tool_call", "trace.tool_call", "tool.end", "tool.end", "trace.end").map(::JsonPrimitive),
        ).inOrder()
        assertThat(traced[0]["runMode"]).isEqualTo(JsonPrimitive("LINUX"))
        assertThat(traced[1]["contentLength"]).isEqualTo(JsonPrimitive(body.length))
        assertThat(traced[2]["stepCount"]).isEqualTo(JsonPrimitive(1))
        val tool = traced[3]
        assertThat(tool["ledgerStatus"]).isEqualTo(JsonPrimitive("FAILED"))
        assertThat(tool["errorCode"]).isEqualTo(JsonPrimitive("IO_ERROR"))
        assertThat(tool["durationMs"]).isEqualTo(JsonPrimitive(6))
        val arguments = tool.getValue("arguments").jsonObject
        assertThat(arguments.keys).containsExactly("path", "mode", "depth", "startLine", "endLine",
            "token", "authorization", "password", "privateKey", "content")
        assertThat(arguments["token"]).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(arguments["path"]).isEqualTo(JsonPrimitive(path.substring(0, path.offsetByCodePoints(0, 160))))
        assertThat(traced[4]["arguments"]?.jsonObject?.get("path")).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(traced[4]["ledgerStatus"]).isEqualTo(JsonNull)
        val output = (lines + recent.toString()).joinToString("\n")
        assertThat((secrets + listOf("用户正文不导出", "回复正文不导出", "步骤正文不导出"))
            .count { it in output }).isEqualTo(0)
    }

    @Test
    fun `分页按消息游标向前读取并隔开诊断事件流`() = runTest {
        val session = SessionEntity("paged", "assistant", createdAt = 1, updatedAt = 1)
        val newest = MessageEntity("newest", session.id, "user", "content", createdAt = 3)
        val middle = newest.copy(id = "middle", createdAt = 2)
        val oldest = newest.copy(id = "oldest", createdAt = 1)
        coEvery { sessions.getById(session.id) } returns session
        coEvery { messages.getById(middle.id) } returns middle
        coEvery { messages.historyBefore(session.id, null, 3, emptyList()) } returns listOf(newest, middle, oldest)
        coEvery { messages.historyBefore(session.id, middle.id, 3, emptyList()) } returns listOf(oldest)
        val exporter = SessionTraceExporter(sessions, messages, ledger, diagnostics = { error("分页不读取诊断分片") })

        val first = checkNotNull(exporter.export(session.id, TracePage(2)))
            .map { diagnosticJson.parseToJsonElement(it).jsonObject }
        assertThat(first.first()["nextBeforeMessageId"]).isNull()
        assertThat(first.last()["event"]).isEqualTo(JsonPrimitive("trace.end"))
        val next = first.last().getValue("nextBeforeMessageId") as JsonPrimitive
        assertThat(next.content).isEqualTo(middle.id)
        assertThat(first.first()["appDiagnosticsIncluded"]).isEqualTo(JsonPrimitive(false))
        assertThat(first.drop(1).dropLast(1).map { it["messageId"] }).containsExactly(
            JsonPrimitive(middle.id), JsonPrimitive(newest.id),
        ).inOrder()
        val last = checkNotNull(exporter.export(session.id, TracePage(2, next.content)))
            .map { diagnosticJson.parseToJsonElement(it).jsonObject }
        assertThat(last.last()["nextBeforeMessageId"]).isEqualTo(JsonNull)
        assertThat(last[1]["messageId"]).isEqualTo(JsonPrimitive(oldest.id))
    }

}
