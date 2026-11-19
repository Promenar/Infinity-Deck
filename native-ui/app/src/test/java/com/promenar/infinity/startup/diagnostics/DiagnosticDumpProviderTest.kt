package com.promenar.infinity.startup.diagnostics

import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class DiagnosticDumpProviderTest {
    @Test fun `线程转储仅输出白名单线程名和安全栈且优先保留相关线程`() = runTest {
        val unknown = Thread("pool-private.example-uuid")
        val dispatcher = Thread("DefaultDispatcher-worker-7 @session-identifier#9").apply { isDaemon = true }
        val misleading = Thread("OkHttp private.example")
        val frame = StackTraceElement("com.promenar.infinity.Sample", "read", "/private/tok_abc.kt", 42)
        val stack = Array(15) { frame }
        val snapshot = linkedMapOf(unknown to stack, misleading to stack)
        repeat(82) { snapshot[Thread("private-$it")] = emptyArray() }
        snapshot[dispatcher] = stack
        var captures = 0
        val dump = DiagnosticDump(debug = true, developerModeEnabled = { false }, exporter = { null },
            threadSnapshot = { captures++; snapshot })
        val lines = dump.lines(arrayOf("--threads"))
        assertThat(lines).hasSize(81)
        val events = lines.map { Json.parseToJsonElement(it).jsonObject }
        val summary = events.first()
        assertThat(summary.getValue("event").jsonPrimitive.content).isEqualTo("diagnostics.threads_summary")
        assertThat(summary.getValue("totalThreads").jsonPrimitive.content).isEqualTo("85")
        assertThat(summary.getValue("defaultDispatcherCount").jsonPrimitive.content).isEqualTo("1")
        assertThat(summary.getValue("ioLikeThreadCount").jsonPrimitive.content).isEqualTo("1")
        assertThat(summary.getValue("stateCounts").jsonObject.getValue("NEW").jsonPrimitive.content).isEqualTo("85")
        val firstThread = events[1]
        assertThat(firstThread.getValue("event").jsonPrimitive.content).isEqualTo("diagnostics.thread")
        assertThat(firstThread.getValue("name").jsonPrimitive.content).isEqualTo("DefaultDispatcher-worker-7")
        assertThat(firstThread.getValue("daemon").jsonPrimitive.content).isEqualTo("true")
        assertThat(firstThread.getValue("frames").jsonArray).hasSize(12)
        assertThat(lines.joinToString()).doesNotContain("private.example")
        assertThat(lines.joinToString()).doesNotContain("tok_abc")
        assertThat(lines.joinToString()).doesNotContain("session-identifier")
        assertThat(dump.lines(arrayOf("--help")).single()).contains("--threads")
        assertThat(dump.lines(arrayOf("--threads", "extra")).single()).contains("invalid_arguments")
        val blocked = DiagnosticDump(debug = false, developerModeEnabled = { true }, adbBridgeEnabled = { false },
            exporter = { null }, threadSnapshot = { error("不应获取线程") })
        assertThat(blocked.lines(arrayOf("--threads")).single()).contains("adb_bridge_disabled")
        assertThat(captures).isEqualTo(1)
    }

    @Test
    fun `正式版开发者模式关闭时所有命令均在读取前被拦截`() = runTest {
        val dump = DiagnosticDump(
            debug = false,
            developerModeEnabled = { false },
            exporter = { error("不应打开数据库") },
            incremental = { _, _, _ -> error("不应读取诊断记录") },
            recent = { _, _ -> error("不应读取诊断记录") },
        )
        listOf(arrayOf("--help"), arrayOf("--trace", "latest"), arrayOf("--sessions"), emptyArray(),
            arrayOf("--recent", "--since", "1"), arrayOf("--threads")).forEach { args ->
            assertThat(dump.lines(args))
                .containsExactly("""{"event":"diagnostics.blocked","reason":"developer_mode_disabled"}""")
        }
    }

    @Test
    fun `正式版单独关闭ADB调试桥时同样阻断并标明原因`() = runTest {
        val dump = DiagnosticDump(
            debug = false,
            developerModeEnabled = { true },
            adbBridgeEnabled = { false },
            exporter = { error("不应打开数据库") },
            incremental = { _, _, _ -> error("不应读取诊断记录") },
            recent = { _, _ -> error("不应读取诊断记录") },
        )
        listOf(arrayOf("--help"), arrayOf("--trace", "latest"), arrayOf("--recent")).forEach { args ->
            assertThat(dump.lines(args))
                .containsExactly("""{"event":"diagnostics.blocked","reason":"adb_bridge_disabled"}""")
        }
    }

    @Test
    fun `默认读取最近两百条而指定以来序号会分页并保留会话过滤`() = runTest {
        val record = UnifiedDiagnosticRecord(seq = 11, timestampMs = 1, event = "llm.route")
        val dump = DiagnosticDump(
            debug = true,
            developerModeEnabled = { error("调试版不读取开发者模式开关") },
            exporter = { null },
            incremental = { since, session, limit ->
                assertThat(since).isEqualTo(10)
                assertThat(session).isEqualTo("chat-1")
                assertThat(limit).isEqualTo(3)
                listOf(record)
            },
            recent = { session, limit ->
                assertThat(session).isNull()
                assertThat(limit).isEqualTo(200)
                listOf(record)
            },
        )
        assertThat(dump.lines(emptyArray())).containsExactly(record.toJsonLine())
        assertThat(dump.lines(arrayOf("--recent", "--since", "10", "--session", "chat-1", "--limit", "3")))
            .containsExactly(record.toJsonLine())
        assertThat(dump.lines(arrayOf("--help")).single()).contains("adb shell dumpsys activity provider")
        listOf(arrayOf("--recent", "--since"), arrayOf("--recent", "--limit", "501"),
            arrayOf("--recent", "--since", "-1"), arrayOf("--recent", "--session", "--other"),
            arrayOf("--recent", "--limit", "3", "--limit", "4"), arrayOf("--trace")).forEach { args ->
            assertThat(dump.lines(args).single()).contains("invalid_arguments")
        }
    }

    @Test
    fun `开发者模式开启时列表和轨迹能调用数据库导出器`() = runTest {
        val exporter = mockk<SessionTraceExporter>()
        coEvery { exporter.listRecentSessions(50) } returns listOf("summary")
        coEvery { exporter.export("latest") } returns listOf("trace")
        coEvery { exporter.export("unknown") } returns null
        coEvery { exporter.export("chat-1", TracePage(20, "msg-1")) } returns listOf("page")
        val dump = DiagnosticDump(debug = false, developerModeEnabled = { true }, exporter = { exporter })
        assertThat(dump.lines(arrayOf("--sessions"))).containsExactly("summary")
        assertThat(dump.lines(arrayOf("--trace", "latest"))).containsExactly("trace")
        assertThat(dump.lines(arrayOf("--trace", "unknown")).single()).contains("session_or_cursor_not_found")
        assertThat(dump.lines(arrayOf("--trace", "chat-1", "--limit", "20", "--before", "msg-1")))
            .containsExactly("page")
    }
}
