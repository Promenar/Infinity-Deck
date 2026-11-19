package com.promenar.infinity.startup.diagnostics

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.utils.InfinityLogger
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppDiagnosticsTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `结构化事件和异常共用诊断目录并为每行分配递增序号`() = runTest {
        AppDiagnostics.initialize(temporaryFolder.newFolder())
        InfinityLogger.diagnostic("llm.route", mapOf("sessionId" to "session-1", "providerId" to "openai"))
        InfinityLogger.logError("WorkspaceJournal", IllegalStateException("token=synthetic-hidden"))

        val lines = AppDiagnostics.recent().lineSequence().filter(String::isNotBlank).map {
            kotlinx.serialization.json.Json.parseToJsonElement(it).let { value ->
                value as kotlinx.serialization.json.JsonObject
            }
        }.toList()

        assertThat(lines.map { it["event"].toString() }).containsExactly("\"llm.route\"", "\"app.exception\"").inOrder()
        assertThat(lines.map { it["seq"].toString() }).containsExactly("1", "2").inOrder()
        assertThat(lines.toString()).doesNotContain("synthetic-hidden")
    }

    @Test
    fun `已处置问题的历史记录保留脱敏范围且不保存界面名称`() = runTest {
        AppDiagnostics.initialize(temporaryFolder.newFolder())
        val manager = com.promenar.infinity.startup.StartupIssueManager()
        val scopeId = "private-root-identifier"
        var broken = true
        manager.recover(com.promenar.infinity.startup.StartupStep.WORKSPACE_JOURNAL,
            com.promenar.infinity.startup.StartupIssueScope(com.promenar.infinity.startup.StartupScopeKind.WORKSPACE_ROOT,
                scopeId, "用户私密会话标题")) {
            check(!broken) { "移动类 journal 文件布局冲突" }
        }
        broken = false
        manager.resolve(manager.issues.value.single().id, com.promenar.infinity.startup.StartupIssueAction.RETRY)

        val recent = AppDiagnostics.recent()

        assertThat(manager.issues.value).isEmpty()
        assertThat(recent).contains("WORKSPACE_ROOT")
        assertThat(recent).contains(DiagnosticRedactor.safeIdentifier(scopeId))
        assertThat(recent).doesNotContain(scopeId)
        assertThat(recent).doesNotContain("用户私密会话标题")
    }

    @Test
    fun `日志错误调用进入私有诊断目录且recent等待先前的异步记录`() = runTest {
        val filesDirectory = temporaryFolder.newFolder()
        AppDiagnostics.initialize(filesDirectory)

        InfinityLogger.logError("private-message-tag", IllegalStateException("移动类 journal 文件布局冲突"))
        val recent = AppDiagnostics.recent()

        assertThat(java.io.File(filesDirectory, "diagnostics").isDirectory).isTrue()
        assertThat(recent).contains("移动类 journal 文件布局冲突")
        assertThat(recent).doesNotContain("private-message-tag")
        val output = ByteArrayOutputStream()
        AppDiagnostics.export(output, "private-issue-message")
        assertThat(output.toString(Charsets.UTF_8.name())).contains("移动类 journal 文件布局冲突")
        assertThat(output.toString(Charsets.UTF_8.name())).doesNotContain("private-issue-message")
    }

    @Test
    fun `file_op 事件通过白名单脱敏写入并保留结构字段`() = runTest {
        AppDiagnostics.initialize(temporaryFolder.newFolder())
        InfinityLogger.diagnostic(
            "file.op",
            mapOf(
                "sessionId" to "raw-session-secret",
                "toolName" to "search_files",
                "routeKind" to "LINUX",
                "status" to "error",
                "errorCode" to "ACCESS_DENIED",
                "content" to "用户文件内容不得泄露",
            ),
        )
        val events = AppDiagnostics.readEvents()
        val fileOp = events.single { it.event == "file.op" }
        assertThat(fileOp.fields["toolName"].toString()).isEqualTo("\"search_files\"")
        assertThat(fileOp.fields["routeKind"].toString()).isEqualTo("\"LINUX\"")
        assertThat(fileOp.fields["errorCode"].toString()).isEqualTo("\"ACCESS_DENIED\"")
        assertThat(fileOp.fields.containsKey("content")).isFalse()
        assertThat(fileOp.toJsonLine()).doesNotContain("用户文件内容不得泄露")
    }
}
