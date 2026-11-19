package com.promenar.infinity.startup.diagnostics

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.utils.InfinityLogger
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UnifiedDiagnosticRecorderTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `并发滚动保持八片上限且重启后可以继续增量读取`() = runTest {
        val directory = temporaryFolder.newFolder()
        val recorder = AppDiagnosticRecorder(directory, maxTotalBytes = 8_192, maxFileBytes = 512)
        (1..100).map { index ->
            async(Dispatchers.Default) {
                recorder.record(routeEvent(if (index % 2 == 0) "even" else "odd"))
            }
        }.awaitAll()

        val files = directory.listFiles().orEmpty().filter { it.name.startsWith("events-") }
        assertThat(files.size).isEqualTo(8)
        assertThat(files.all { it.length() <= 512 }).isTrue()
        val reopened = AppDiagnosticRecorder(directory, maxTotalBytes = 8_192, maxFileBytes = 512)
        val retained = reopened.readEvents()
        assertThat(retained.map { it.seq }).containsNoDuplicates()
        assertThat(retained.map { it.seq }).isInOrder()
        assertThat(retained.first().seq).isGreaterThan(1L)
        assertThat(reopened.latestSeq()).isEqualTo(100)
        val since = retained.first().seq
        assertThat(reopened.readEvents(since, "even", limit = 2)).isEqualTo(
            retained.filter { it.seq > since && it.fields["sessionId"] == JsonPrimitive("even") }.take(2),
        )
        reopened.recordError("WorkspaceJournal", IllegalArgumentException())
        assertThat(reopened.latestSeq()).isGreaterThan(100)
        assertThat(reopened.readEvents(sinceSeq = 100).single().event).isEqualTo("app.exception")
    }

    @Test
    fun `读取旧版异常并跳过未写完的记录与符号链接`() = runTest {
        val directory = temporaryFolder.newFolder()
        val shard = File(directory, "events-00000000000000000001.jsonl")
        shard.writeText(
            """{"timestamp":123,"step":"WorkspaceJournal","exceptionType":"IOException","summary":"IOException: [内容已隐藏]","frames":[]}""" +
                "\n{\"event\":",
        )
        val outside = temporaryFolder.newFile().apply { writeText("private-file-body") }
        Files.createSymbolicLink(File(directory, "events-00000000000000000002.jsonl").toPath(), outside.toPath())
        val recorder = AppDiagnosticRecorder(directory)
        assertThat(recorder.readEvents().single().exception?.summary).isEqualTo("IOException: [内容已隐藏]")

        recorder.record(routeEvent("safe-session"))
        assertThat(recorder.readEvents().map { it.event }).containsExactly("app.exception", "llm.route").inOrder()
        assertThat(recorder.latestSeq()).isEqualTo(3)
        assertThat(outside.readText()).isEqualTo("private-file-body")
    }

    @Test
    fun `设置导出依序提供构建信息完整快照和脱敏启动摘要`() = runTest {
        val directory = temporaryFolder.newFolder()
        AppDiagnostics.initialize(directory)
        val samples = diagnosticCredentialFixtures
        samples.forEach { secret ->
            InfinityLogger.diagnostic("llm.complete", mapOf(
                "sessionId" to "safe-session",
                "modelId" to secret,
                "httpStatus" to 200,
                "prompt" to "private-prompt-fixture",
            ))
            InfinityLogger.logError("WorkspaceJournal", IllegalStateException(secret))
        }

        val firstExport = AppDiagnostics.prepareJsonlExport(directory, samples.joinToString("\n"))
        val export = firstExport.readText()
        val lines = export.lineSequence().filter(String::isNotBlank)
            .map { diagnosticJson.parseToJsonElement(it).jsonObject }.toList()

        assertThat(lines.first()["event"]).isEqualTo(JsonPrimitive("app.build"))
        assertThat(lines.first()["distribution"]).isNotNull()
        assertThat(lines.last()["event"]).isEqualTo(JsonPrimitive("app.startup_summary"))
        assertThat(lines.drop(1).dropLast(1).map { (it["seq"] as JsonPrimitive).content.toLong() })
            .containsExactlyElementsIn((1L..(samples.size * 2L)).toList()).inOrder()
        assertThat(lines[1]["fields"]).isEqualTo(buildJsonObject {
            put("sessionId", "safe-session")
            put("modelId", "[omitted]")
            put("httpStatus", 200)
        })
        assertThat((samples + "private-prompt-fixture").count { it in export }).isEqualTo(0)
        InfinityLogger.diagnostic("llm.route", mapOf("sessionId" to "new-snapshot"))
        val secondExport = AppDiagnostics.prepareJsonlExport(directory, "")
        assertThat(secondExport).isNotEqualTo(firstExport)
        assertThat(secondExport.readText()).contains("new-snapshot")
        assertThat(firstExport.readText()).isEqualTo(export)
    }

    @Test
    fun `可读后丢失分片尾部重启也不会重用已导出的序号`() = runTest {
        val directory = temporaryFolder.newFolder()
        val before = AppDiagnosticRecorder(directory)
        before.record(routeEvent("before"))
        val shard = directory.listFiles().orEmpty().single { it.name.startsWith("events-") }
        val stablePrefix = shard.readText()
        before.record(routeEvent("lost"))
        val lastExported = before.latestSeq()
        shard.writeText(stablePrefix)

        val restarted = AppDiagnosticRecorder(directory)
        restarted.record(routeEvent("after"))

        assertThat(restarted.readEvents(lastExported).single().fields["sessionId"])
            .isEqualTo(JsonPrimitive("after"))
        assertThat(restarted.latestSeq()).isGreaterThan(lastExported)
        before.record(routeEvent("other-recorder"))
        assertThat(restarted.readEvents(lastExported).map { it.fields["sessionId"] }).containsExactly(
            JsonPrimitive("after"), JsonPrimitive("other-recorder"),
        ).inOrder()
    }

    @Test
    fun `关联引用与碰巧同名的原始标识不会合并且数值不能充当模型ID`() = runTest {
        val directory = temporaryFolder.newFolder()
        AppDiagnostics.initialize(directory)
        val raw = "需要脱敏的会话"
        val reference = DiagnosticRedactor.correlationId(raw)
        InfinityLogger.diagnostic("llm.complete", mapOf(
            "sessionId" to raw, "modelId" to 1234567890123456L, "attempt" to 1,
        ))
        InfinityLogger.diagnostic("llm.route", mapOf("sessionId" to reference))

        val first = AppDiagnostics.readEvents(sessionId = raw).single()
        assertThat(first.event).isEqualTo("llm.complete")
        assertThat(first.fields["modelId"]).isNull()
        assertThat(AppDiagnostics.readEvents(sessionId = reference).single().event).isEqualTo("llm.route")
        assertThat(AppDiagnostics.readEvents(sessionId = reference, acceptReference = true).single().event)
            .isEqualTo("llm.complete")
        assertThat(AppDiagnostics.recent()).doesNotContain("1234567890123456")
    }

    @Test
    fun `从列表复制的会话引用可以查齐该会话的启动异常`() = runTest {
        AppDiagnostics.initialize(temporaryFolder.newFolder())
        val raw = "team blue"
        val reference = DiagnosticRedactor.correlationId(raw)
        InfinityLogger.diagnostic("llm.route", mapOf("sessionId" to raw))
        AppDiagnostics.recordStartupIssue("WORKSPACE_JOURNAL", "SESSION", raw, IllegalStateException())
        val byRawId = AppDiagnostics.readEvents(sessionId = raw)

        assertThat(byRawId.map { it.event }).containsExactly("llm.route", "app.exception").inOrder()
        assertThat(AppDiagnostics.readEvents(sessionId = reference, acceptReference = true))
            .containsExactlyElementsIn(byRawId).inOrder()
    }

    private fun routeEvent(session: String) = UnifiedDiagnosticRecord(
        timestampMs = 123,
        event = "llm.route",
        fields = JsonObject(mapOf(
            "sessionId" to JsonPrimitive(session),
            "providerId" to JsonPrimitive("openai"),
            "protocol" to JsonPrimitive("openai"),
        )),
    )
}

internal val diagnosticCredentialFixtures = listOf(
    "sk-fixture-openai-123456789",
    "Bearer fixture-bearer-123456789",
    "token=fixture-token-123456789",
    "-----BEGIN PRIVATE KEY-----",
    "https://example.invalid/path?password=fixture-query-123456789",
    "fixture-user:fixture-password@example.invalid",
    "Cookie: sid=fixture-cookie-123456789",
)
