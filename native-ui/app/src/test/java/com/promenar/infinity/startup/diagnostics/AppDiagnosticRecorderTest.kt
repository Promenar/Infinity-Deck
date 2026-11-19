package com.promenar.infinity.startup.diagnostics

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.CodingErrorAction
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppDiagnosticRecorderTest {
    @Test fun `默认录制在独立线程完成并跨片读取最新匹配序号`() = runBlocking<Unit> {
        val root = temporaryFolder.newFolder()
        val observed = mutableSetOf<String>()
        val observedRoot = object : File(root.path) {
            override fun listFiles(): Array<File>? {
                observed += Thread.currentThread().name.substringBefore(" @")
                return super.listFiles()
            }
        }
        val recorder = AppDiagnosticRecorder(observedRoot, maxTotalBytes = 4_096, maxFileBytes = 512)
        val io = kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1)
        kotlinx.coroutines.withTimeout(5_000) {
            kotlinx.coroutines.withContext(io) {
                repeat(14) { index ->
                    recorder.record(UnifiedDiagnosticRecord(timestampMs = index.toLong(), event = "generation.completed",
                        fields = kotlinx.serialization.json.buildJsonObject {
                            put("sessionId", kotlinx.serialization.json.JsonPrimitive(if (index % 2 == 0) "session-a" else "session-b"))
                        }))
                }
                val shards = root.listFiles().orEmpty().filter { it.name.endsWith(".jsonl") }.sortedBy(File::getName)
                assertThat(shards.size).isGreaterThan(1)
                shards.last().appendText("{坏行\n")
                val recent = recorder.readRecentEvents("session-a", 3)
                assertThat(recent.map { it.timestampMs }).containsExactly(8L, 10L, 12L).inOrder()
                assertThat(recent.map { it.seq }).isInStrictOrder()
                assertThat(recorder.readRecentEvents(limit = 2).map { it.timestampMs })
                    .containsExactly(12L, 13L).inOrder()
                assertThat(recorder.readRecentEvents("未匹配会话", 1)).isEmpty()
                assertThat(recorder.readRecentEvents(limit = 0)).isEmpty()
                assertThat(recorder.readRecentEvents(limit = -1)).isEmpty()
            }
        }
        assertThat(observed).containsExactly("infinity-diagnostics")
    }

    @Test fun `读取尾行达到数量后立即停止且保留旧版行位置`() {
        val visited = mutableListOf<String>()
        val payload = "陈旧\n坏行\n最新\n".toByteArray()
        visitDiagnosticLinesFromEnd(payload) { line, index ->
            visited += "$line:${index()}"
            line != "坏行"
        }
        assertThat(visited).containsExactly("最新:2", "坏行:1").inOrder()
    }

    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `记录保留安全恢复原因和应用栈帧但不含外部栈帧及路径`() = runTest {
        val recorder = AppDiagnosticRecorder(temporaryFolder.newFolder())
        val error = IllegalStateException("移动类 journal 文件布局冲突").apply {
            stackTrace = arrayOf(
                StackTraceElement("com.promenar.infinity.Sample", "recover", "Sample.kt", 42),
                StackTraceElement("com.other.PrivateClass", "run", "Private.kt", 12),
                StackTraceElement("com.promenar.infinity.Other", "run", "/Users/private-name/Other.kt", 7),
            )
        }

        recorder.recordError("WorkspaceJournal", error)
        val recent = recorder.recent()

        assertThat(recent).contains("IllegalStateException")
        assertThat(recent).contains("移动类 journal 文件布局冲突")
        assertThat(recent).contains("com.promenar.infinity.Sample.recover(Sample.kt:42)")
        assertThat(recent).doesNotContain("com.other")
        assertThat(recent).doesNotContain("private-name")
        assertThat(recent).doesNotContain("/Users/")
    }

    @Test
    fun `日志标签即使形似内部UUID也不保留调用方任意文本`() = runTest {
        val recorder = AppDiagnosticRecorder(temporaryFolder.newFolder())
        recorder.recordError("00000000-0000-4000-8000-000000000001", IllegalStateException("private-body"))

        assertThat(recorder.recent()).doesNotContain("00000000-0000-4000-8000-000000000001")
    }

    @Test
    fun `滚动删除最旧文件且总大小与每个文件都有上限`() = runTest {
        val directory = temporaryFolder.newFolder()
        val recorder = AppDiagnosticRecorder(directory, maxTotalBytes = 1_536, maxFileBytes = 512)
        recorder.recordError("first-only-marker", IllegalStateException("移动类 journal 文件布局冲突"))
        repeat(50) { recorder.recordError("next-$it", java.io.IOException("private-body-$it")) }

        val recent = recorder.recent()
        val files = directory.listFiles().orEmpty().filter { it.name.startsWith("events-") }

        assertThat(files.size).isAtLeast(2)
        assertThat(files.sumOf(File::length)).isAtMost(1_536L)
        assertThat(files.all { it.length() <= 512L }).isTrue()
        assertThat(recent).doesNotContain("移动类 journal 文件布局冲突")
        assertThat(recent).contains("IOException")
        assertThat(recent).doesNotContain("private-body")
    }

    @Test
    fun `超长多字节异常与栈帧不会穿透大小上限或产生损坏UTF8`() = runTest {
        val directory = temporaryFolder.newFolder()
        val recorder = AppDiagnosticRecorder(directory, maxTotalBytes = 1_024, maxFileBytes = 512)
        val error = IllegalArgumentException("私有正文🔐".repeat(50_000)).apply {
            stackTrace = Array(100) {
                StackTraceElement("com.promenar.infinity.${"Long".repeat(100)}", "run", "私有路径🔐.kt", 42)
            }
        }
        recorder.recordError("私有步骤🔐".repeat(10_000), error)
        val bytes = directory.listFiles().orEmpty().filter { it.name.startsWith("events-") }.flatMap { it.readBytes().toList() }.toByteArray()
        val decoded = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()

        assertThat(bytes.size).isAtMost(1_024)
        assertThat(decoded).contains("IllegalArgumentException")
        assertThat(decoded).doesNotContain("私有")
        assertThat(decoded).doesNotContain("🔐")
    }

    @Test
    fun `预置诊断符号链接不会向链接目标追加内容`() = runTest {
        val directory = temporaryFolder.newFolder()
        val outside = temporaryFolder.newFile().apply { writeText("原文件内容") }
        try {
            java.nio.file.Files.createSymbolicLink(
                File(directory, "events-00000000000000000001.jsonl").toPath(), outside.toPath(),
            )
        } catch (error: Exception) {
            assumeNoException("测试文件系统无法创建符号链接", error)
        }
        val recorder = AppDiagnosticRecorder(directory)

        runCatching { recorder.recordError("WorkspaceJournal", IllegalStateException("private-body")) }

        assertThat(outside.readText()).isEqualTo("原文件内容")
    }

    @Test
    fun `并发写入完整记录且新记录器可以读取前一实例记录`() = runTest {
        val directory = temporaryFolder.newFolder()
        val recorder = AppDiagnosticRecorder(directory)
        (1..20).map { async { recorder.recordError("same-step", IllegalStateException("private-body")) } }
            .forEach { it.await() }

        val recent = AppDiagnosticRecorder(directory).recent()

        assertThat(recent.lineSequence().filter(String::isNotBlank).count()).isEqualTo(20)
        assertThat(recent).doesNotContain("private-body")
    }

    @Test
    fun `导出包含结构化问题与记录且不会关闭调用方输出流`() = runTest {
        val recorder = AppDiagnosticRecorder(temporaryFolder.newFolder())
        recorder.recordError("WorkspaceJournal", IllegalStateException("移动类 journal 文件布局冲突"))
        val output = object : ByteArrayOutputStream() {
            var wasClosed = false
            override fun close() { wasClosed = true; super.close() }
        }
        val issues = "issue=WORKSPACE_JOURNAL:WORKSPACE_ROOT:00000000-0000-4000-8000-000000000001 type=WORKSPACE_JOURNAL severity=ISOLATED scope=00000000-0000-4000-8000-000000000002 step=WORKSPACE_JOURNAL summary=IllegalStateException: 移动类 journal 文件布局冲突 actions=RETRY,ABANDON occurredAt=1234"

        recorder.export(output, issues)
        val exported = output.toString(Charsets.UTF_8.name())

        assertThat(exported).contains("issue=WORKSPACE_JOURNAL:WORKSPACE_ROOT:00000000-0000-4000-8000-000000000001")
        assertThat(exported).contains("WORKSPACE_JOURNAL")
        assertThat(exported).contains("移动类 journal 文件布局冲突")
        assertThat(output.wasClosed).isFalse()
        output.write(10)
    }

    @Test
    fun `导出保留全部当前问题而不会在一百项后静默省略`() = runTest {
        val recorder = AppDiagnosticRecorder(temporaryFolder.newFolder())
        val output = ByteArrayOutputStream()
        val issues = (1..105).joinToString("\n") { index ->
            "issue=WORKSPACE_JOURNAL:WORKSPACE_ROOT:00000000-0000-4000-8000-${index.toString().padStart(12, '0')} " +
                "type=WORKSPACE_JOURNAL severity=ISOLATED scope=00000000-0000-4000-8000-000000000001 " +
                "step=WORKSPACE_JOURNAL summary=IllegalStateException: [内容已隐藏] actions=RETRY occurredAt=$index"
        }

        recorder.export(output, issues)

        assertThat(output.toString(Charsets.UTF_8.name())).contains("occurredAt=105")
    }

    @Test
    fun `导出再次集中脱敏问题自由文本与伪造安全字段`() = runTest {
        val recorder = AppDiagnosticRecorder(temporaryFolder.newFolder())
        val output = ByteArrayOutputStream()
        val issues = """
            Authorization: Bearer private-bearer
            issue=private-id type=private-type severity=private-severity scope=private-scope step=private-step summary=private-user-body actions=private-action occurredAt=private-time
            issue=00000000-0000-4000-8000-000000000001 type=WORKSPACE_JOURNAL severity=ISOLATED scope=00000000-0000-4000-8000-000000000002 step=WORKSPACE_JOURNAL summary=IllegalStateException: 移动类 journal 文件布局冲突 private-tail actions=RETRY occurredAt=1234
        """.trimIndent()

        recorder.export(output, issues)
        val exported = output.toString(Charsets.UTF_8.name())

        assertThat(exported).doesNotContain("private-")
        assertThat(exported).doesNotContain("Authorization")
        assertThat(exported).contains("[内容已隐藏]")
    }

    @Test
    fun `读取与导出丢弃损坏或夹带正文的存量记录`() = runTest {
        val directory = temporaryFolder.newFolder()
        val recorder = AppDiagnosticRecorder(directory)
        recorder.recordError("WorkspaceJournal", IllegalStateException("移动类 journal 文件布局冲突"))
        directory.listFiles().orEmpty().first { it.name.startsWith("events-") }.appendText(
            "\nprivate-file-body\n" +
                """{"timestamp":0,"step":"private-step","exceptionType":"private-type","summary":"private-embedded-body","frames":["private-stack-body"]}""" + "\n",
        )

        assertThat(recorder.recent()).doesNotContain("private-")
        val output = ByteArrayOutputStream()
        recorder.export(output, "private-issue-body")
        assertThat(output.toString(Charsets.UTF_8.name())).doesNotContain("private-")
    }
}
