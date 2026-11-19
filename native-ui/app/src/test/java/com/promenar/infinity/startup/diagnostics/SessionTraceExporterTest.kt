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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test
    fun `ADB轨迹保留业务入参和真实错误而分享输出沿用严格投影`() = runTest {
        val title = "排查工作区写入"
        val error = "NoSuchMethodError: updateWorkspace\n\t重试失败"
        val operations = buildJsonArray {
            add(buildJsonObject {
                put("op", "replace")
                put("path", "/workspace/draft.md")
                put("content", "替换后的正文")
                put("command", "ls -la")
                put("body", "请求内容")
                put("response", "响应内容")
                put("unknownKey", true)
            })
        }
        val references = JsonArray(listOf(JsonPrimitive("reference.png"), JsonNull))
        val call = traceTool("production_update", buildJsonObject { put("operations", operations) })
        val image = traceTool("generate_image", buildJsonObject {
            put("prompt", "生成一张蓝色海面")
            put("reference_images", references)
            put("images", references)
        })
        val fixture = setupTrace(title, error, call, image)
        val adb = exporter(DiagnosticTraceMode.ADB_LOCAL)
        val rows = traceRows(adb, fixture)
        assertThat(adb.listRecentSessions(1).single().toRow()["title"]).isEqualTo(JsonPrimitive(title))
        assertThat(rows.first()["title"]).isEqualTo(JsonPrimitive(title))
        val turns = rows.filter { it["event"] == JsonPrimitive("trace.turn") }
        assertThat(turns[0].containsKey("errorPreview")).isFalse()
        assertThat(turns[1]["errorPreview"]).isEqualTo(JsonPrimitive("工具轮次失败 token=[omitted]"))
        val tools = rows.filter { it["event"] == JsonPrimitive("trace.tool_call") }
        assertThat(tools[0].arguments()["operations"]).isEqualTo(operations)
        assertThat(tools[0]["errorSummary"]).isEqualTo(JsonPrimitive("NoSuchMethodError: updateWorkspace 重试失败"))
        assertThat(tools[1].arguments()).isEqualTo(diagnosticJson.parseToJsonElement(image.arguments))

        val strict = SessionTraceExporter(sessions, messages, ledger, diagnostics = { emptyList() })
        val strictRows = traceRows(strict, fixture)
        assertThat(strictRows.first()["title"]!!.jsonPrimitive.content).startsWith("会话 ")
        assertThat(strict.listRecentSessions(1).single().toRow()["title"]).isEqualTo(strictRows.first()["title"])
        assertThat(strictRows.none { "errorPreview" in it }).isTrue()
        val strictTools = strictRows.filter { it["event"] == JsonPrimitive("trace.tool_call") }
        assertThat(strictTools[0].arguments()).isEmpty()
        assertThat(strictTools[0]["errorSummary"]).isEqualTo(JsonPrimitive("[内容已隐藏]"))
        assertThat(strictTools[1].arguments()).isEqualTo(buildJsonObject { put("prompt", "[omitted]") })
        assertThat(rows.joinToString()).doesNotContain("正常轮次的正文")
        assertThat(rows.joinToString()).doesNotContain("fixture-turn-secret")
    }

    @Test
    fun `ADB完整JSONL对任意嵌套键值与预览执行硬脱敏`() = runTest {
        val values = listOf(
            "fake-bearer-value", "fake-basic-value", "sk-fixture-openai", "ghp_fixture-github",
            "github_pat_fixture-github", "xoxb-fixture-slack", "AIzaFixtureGoogle",
            "AKIAFIXTUREAMAZON", "eyJhbGciOiJub25lIn0.eyJmaXh0dXJlIjp0cnVlfQ.fixture",
            "fixture-pem-material", "fixture-url-user", "fixture-url-pass", "fixture-query",
            "fixture-fragment", "fixture-api-value", "fixture-token-value", "fixture-password-value",
            "fixture-header-value", "fixture-unlisted-header", "fixture-cookie-one", "fixture-cookie-two",
            "fixture-secret-path", "OpaqueFixtureValueWithoutAnyDelimiters123",
        )
        val args = buildJsonObject {
            put("url", "https://fixture-url-user:fixture-url-pass@example.invalid/v1?token=fixture-query#fixture-fragment")
            put("callbackUrl", "/callback?state=fixture-relative-query#fixture-relative-fragment")
            put("headers", buildJsonObject {
                put("name", "X-API-Key")
                put("value", "fixture-header-pair")
            })
            put("path", "/workspace/.env.local")
            put("prompt", "Bearer fake-bearer-value, Basic fake-basic-value\n" +
                values.subList(2, 9).joinToString(" "))
            put("content", "-----BEGIN RSA PRIVATE KEY-----\nfixture-pem-material\n-----END RSA PRIVATE KEY-----")
            put("operations", buildJsonArray {
                add(buildJsonObject {
                    put("apiKey", "fixture-api-value")
                    put("t-o_K-e_n", buildJsonArray { add(JsonPrimitive("fixture-token-value")) })
                    put("PASSWORD", "fixture-password-value")
                    put("sessionToken", "fixture-session-value")
                    put("databasePassword", "fixture-database-value")
                    put("sshPassphrase", "fixture-ssh-passphrase")
                    put("secretAccessKey", "fixture-secret-access-key")
                    put("requestHeaders", JsonArray(listOf(JsonArray(listOf(
                        JsonPrimitive("Cookie"), JsonPrimitive("session=fixture-header-array"),
                    )))))
                    put("headers", buildJsonObject {
                        put("Authorization", "fixture-header-value")
                        put("X-Trace", "Bearer fixture-unlisted-header")
                        put("X-Debug", JsonArray(listOf(JsonPrimitive("debug"), JsonPrimitive("Basic fixture-basic-header"))))
                        put("Content-Type", "application/json")
                    })
                    put("responseHeaders", buildJsonObject {
                        put("namesAndValues", JsonArray(listOf(JsonPrimitive("Cookie"), JsonPrimitive("fixture-header-values"))))
                    })
                    put("customHeaders", buildJsonObject {
                        put("names", JsonArray(listOf(JsonPrimitive("Authorization"))))
                        put("values", JsonArray(listOf(JsonPrimitive("fixture-parallel-header"))))
                    })
                    put("command", "cat /workspace/.ssh/fixture-secret-path /other/fixture-secret-path.key")
                    put("opaque", values.last())
                })
            })
            put("command", "Cookie: a=fixture-cookie-one; b=fixture-cookie-two")
        }
        val fixture = setupTrace("诊断标题 password=fixture-title", "写入失败 secret=fixture-error",
            traceTool("production_update", args))
        val exporter = exporter(DiagnosticTraceMode.ADB_LOCAL)
        val rows = traceRows(exporter, fixture)
        val output = (rows.map(JsonObject::toString) + exporter.listRecentSessions(1)).joinToString("\n")
        (values + listOf("fixture-title", "fixture-error", "fixture-basic-header", ".env", ".ssh", "fixture-secret-path.key",
            "fixture-session-value", "fixture-database-value", "fixture-header-array", "fixture-relative-query",
            "fixture-relative-fragment", "fixture-header-pair", "fixture-header-values", "fixture-ssh-passphrase",
            "fixture-secret-access-key", "fixture-parallel-header"))
            .forEach { assertThat(output).doesNotContain(it) }
        val result = rows.single { it["event"] == JsonPrimitive("trace.tool_call") }.arguments()
        assertThat(result["url"]).isEqualTo(JsonPrimitive("https://example.invalid/v1"))
        assertThat(result["callbackUrl"]).isEqualTo(JsonPrimitive("/callback"))
        assertThat(result["headers"]).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(result["path"]).isEqualTo(JsonPrimitive("[omitted]"))
        val operation = result.getValue("operations").jsonArray.single().jsonObject
        listOf("apiKey", "t-o_K-e_n", "PASSWORD", "sessionToken", "databasePassword", "sshPassphrase", "secretAccessKey",
            "requestHeaders", "opaque").forEach {
            assertThat(operation[it]).isEqualTo(JsonPrimitive("[omitted]"))
        }
        val headers = operation.getValue("headers").jsonObject
        assertThat(headers["Authorization"]).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(headers["X-Trace"]).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(headers["X-Debug"]).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(headers["Content-Type"]).isEqualTo(JsonPrimitive("application/json"))
        assertThat(operation.getValue("responseHeaders").jsonObject["namesAndValues"]).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(operation["customHeaders"]).isEqualTo(JsonPrimitive("[omitted]"))
    }

    @Test
    fun `ADB对字符串代码点 容器规模 深度和序列化字节数施加独立上限`() = runTest {
        val content = "图😀".repeat(600)
        val wide = JsonArray(List(45) { JsonPrimitive(it) })
        val deep = (1..8).fold(JsonPrimitive("深层内容") as JsonElement) { child, _ ->
            JsonArray(listOf(child))
        }
        val limited = traceTool("limited", buildJsonObject {
            put("content", content)
            put("operations", wide)
            put("nested", deep)
            put("images", JsonArray(List(20) { JsonPrimitive(content) }))
        })
        val huge = ToolCall("oversized", "oversized", """{"content":"${"大".repeat(100_000)}"}""")
        val excessiveNesting = """{"operations":""" + "[".repeat(10_000) + "0" + "]".repeat(10_000) + "}"
        val deepCall = ToolCall("deep", "deep", excessiveNesting)
        val malformed = ToolCall("malformed", "malformed", """{"value":NaN,"scale":-1.5e-3}""")
        val fixture = setupTrace("会".repeat(100), "错".repeat(800), limited, huge, deepCall, malformed,
            turnError = "预".repeat(400))
        val rows = traceRows(exporter(DiagnosticTraceMode.ADB_LOCAL), fixture)
        val tools = rows.filter { it["event"] == JsonPrimitive("trace.tool_call") }
        val args = tools[0].arguments()
        assertThat(args["content"]!!.jsonPrimitive.content).isEqualTo("图😀".repeat(250))
        assertThat(args["operations"]!!.jsonArray).hasSize(40)
        assertThat(args["nested"].toString()).doesNotContain("深层内容")
        assertThat(args["images"]!!.jsonArray.size).isLessThan(20)
        assertThat(args.toString().toByteArray(Charsets.UTF_8).size).isAtMost(6 * 1024)
        assertThat(tools[1]["arguments"]).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(tools[2]["arguments"]).isEqualTo(JsonPrimitive("[omitted]"))
        val malformedArgs = tools[3].arguments()
        assertThat(malformedArgs["value"]).isEqualTo(JsonPrimitive("[omitted]"))
        assertThat(malformedArgs["scale"]!!.toString()).isEqualTo("-1.5e-3")
        assertThat(rows.first()["title"]).isEqualTo(JsonPrimitive("会".repeat(80)))
        assertThat(rows.single { it["isError"] == JsonPrimitive(true) }["errorPreview"])
            .isEqualTo(JsonPrimitive("预".repeat(300)))
        assertThat(tools[0]["errorSummary"]).isEqualTo(JsonPrimitive("错".repeat(600)))
    }

    private fun traceTool(name: String, arguments: JsonObject) = ToolCall(name, name, arguments.toString())

    private fun setupTrace(
        title: String,
        error: String,
        vararg calls: ToolCall,
        turnError: String = "工具轮次失败 token=fixture-turn-secret",
    ): SessionEntity {
        val session = SessionEntity("chat-adb", "assistant", title = title, createdAt = 1, updatedAt = 2)
        val assistant = MessageEntity("assistant-adb", session.id, "assistant",
            turnError, isError = 1,
            toolCalls = diagnosticJson.encodeToString(calls.toList()), createdAt = 2)
        coEvery { sessions.getAll() } returns listOf(session)
        coEvery { sessions.getById(session.id) } returns session
        coEvery { messages.getBySession(session.id) } returns listOf(
            MessageEntity("user-adb", session.id, "user", "正常轮次的正文", createdAt = 1),
            assistant,
        )
        coEvery { ledger.getForAssistant(session.id, assistant.id) } returns listOf(ToolExecutionLedgerEntity(
            session.id, assistant.id, calls.first().id, calls.first().name, "", "", "",
            false, ToolLedgerStatus.FAILED, error = error, createdAt = 2, updatedAt = 2,
        ))
        return session
    }

    private fun exporter(mode: DiagnosticTraceMode) =
        SessionTraceExporter(sessions, messages, ledger, diagnostics = { emptyList() }, mode = mode)

    private suspend fun traceRows(exporter: SessionTraceExporter, session: SessionEntity) =
        checkNotNull(exporter.export(session.id)).map { it.toRow() }

    private fun String.toRow() = diagnosticJson.parseToJsonElement(this).jsonObject

    private fun JsonObject.arguments() = getValue("arguments").jsonObject
}
