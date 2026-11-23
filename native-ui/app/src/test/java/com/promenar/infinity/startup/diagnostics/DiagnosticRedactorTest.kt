package com.promenar.infinity.startup.diagnostics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DiagnosticRedactorTest {
    @Test
    fun `凭据与各种正文形态默认整段省略`() {
        val privateValues = listOf(
            "Authorization: Bearer eyJ-secret-jwt",
            "Proxy-Authorization: Basic dXNlcjpwYXNzd29yZA==",
            "Cookie: session=private-cookie; user=private-user",
            "Set-Cookie: session=private-cookie",
            "api_key=sk-proj-private-openai-key",
            "x-api-key: sk-ant-private-anthropic-key",
            "token=ghp_private-github-token",
            "AKIAABCDEFGHIJKLMNOP",
            "AIzaSyPrivateGoogleKey1234567890",
            "https://user:private-password@example.com/path?api_key=private-query#private-fragment",
            "{\"headers\":{\"Authorization\":\"private-header\"},\"body\":{\"nested\":[\"private-body\"]}}",
            "{\"message\":\"private-user-message\",\"response\":\"private-response\",\"content\":\"private-file-content\"}",
            "/data/user/0/com.promenar.infinity/files/private-absolute-name.txt",
            "/Users/private-user/private-file.txt",
            "C:\\Users\\private-user\\private-file.txt",
            "纯文本用户消息没有任何敏感字段标识",
            "PlainAlphanumericPrivateSecret123456",
            "多字节正文🔐".repeat(10_000),
        )

        privateValues.forEach { value ->
            assertThat(DiagnosticRedactor.redact(value)).isEqualTo("[内容已隐藏]")
        }
    }

    @Test
    fun `只保留完整匹配的固定安全恢复消息`() {
        val safeMessage = "移动类 journal 文件布局冲突"

        val fixedReasons = listOf(
            safeMessage, "PREPARED journal 文件布局冲突", "DB_COMMITTED journal 同时存在 source 与 target",
            "工作区 tombstone 恢复存在冲突", "多个工作区根记录指向同一规范物理目录",
            "多个 Session 指向同一规范工作区根", "工作区根不能是符号链接", "会话工作区绑定已变化",
            "会话根身份或路径不一致", "会话根路径不一致", "工作区数据库父子关系不一致",
            "会话 journal 或数据库状态发生变化，未完成安全放弃",
            com.promenar.infinity.data.repository.LEGACY_ROOT_UNVERIFIED, "工作区根在身份格式迁移前发生变化",
            "文件 journal 在身份格式迁移期间发生变化", "工作区新旧身份的私有区同时存在，拒绝合并",
        )
        fixedReasons.forEach { reason ->
            assertThat(DiagnosticRedactor.redact(reason)).isEqualTo(reason)
            assertThat(DiagnosticRedactor.redact("$reason private-body")).isEqualTo("[内容已隐藏]")
        }
        assertThat(DiagnosticRedactor.redact("$safeMessage private-body")).isEqualTo("[内容已隐藏]")
        assertThat(DiagnosticRedactor.redact("private-body\n$safeMessage")).isEqualTo("[内容已隐藏]")
        assertThat(DiagnosticRedactor.summary(IllegalStateException(safeMessage)))
            .isEqualTo("IllegalStateException: 移动类 journal 文件布局冲突")
    }

    @Test
    fun `异常摘要保留类型但不展开异常正文或cause正文`() {
        val error = IllegalStateException("private-body", java.io.IOException("private-cause"))

        assertThat(DiagnosticRedactor.summary(error)).isEqualTo("IllegalStateException: [内容已隐藏]")
        assertThat(DiagnosticRedactor.safeIdentifier("private-body"))
            .doesNotContain("private-body")
        assertThat(DiagnosticRedactor.safeIdentifier("private-body"))
            .isEqualTo(DiagnosticRedactor.safeIdentifier("private-body"))
        assertThat(DiagnosticRedactor.safeIdentifier("private-body"))
            .isNotEqualTo(DiagnosticRedactor.safeIdentifier("other-body"))
    }

    @Test
    fun `包装异常保留脱敏后的原因链与原因调用位置`() {
        val root = identityMismatch()
        val io = java.io.IOException("/data/user/0/com.promenar.infinity/files/private-name.txt", root)
        val wrapper = IllegalStateException("创建 ownership 维护清理失败", io)

        val event = DiagnosticRedactor.sanitize(DiagnosticRedactor.snapshot("WORKSPACE_JOURNAL", wrapper))

        assertThat(event.summary).isEqualTo("IllegalStateException: 创建 ownership 维护清理失败")
        assertThat(event.causes.map { it.summary }).containsExactly(
            "IOException: [内容已隐藏]",
            "SecurityException: 工作区根身份与数据库认领不一致",
        ).inOrder()
        assertThat(event.causes.last().frames).isNotEmpty()
        assertThat(event.toString()).doesNotContain("private-name")
    }

    @Test
    fun `系统调用失败只保留函数名与errno描述`() {
        assertThat(DiagnosticRedactor.summary(java.io.IOException("open failed: EACCES (Permission denied)")))
            .isEqualTo("IOException: open failed: EACCES (Permission denied)")
        assertThat(DiagnosticRedactor.summary(java.io.IOException("/sdcard/a.txt: open failed: ENOENT (No such file or directory)")))
            .isEqualTo("IOException: [内容已隐藏]")
    }

    @Test
    fun `链接错误保留实际子类且严格摘要省略正文`() {
        val errors = listOf(
            NoSuchMethodError("内部方法详细信息") to "NoSuchMethodError",
            NoClassDefFoundError("内部类详细信息") to "NoClassDefFoundError",
            IncompatibleClassChangeError("内部类详细信息") to "IncompatibleClassChangeError",
            object : LinkageError("内部类详细信息") {} to "LinkageError",
        )
        errors.forEach { (error, type) ->
            val event = DiagnosticRedactor.sanitize(DiagnosticRedactor.snapshot("TOOL_LEDGER", error))
            assertThat(event.exceptionType).isEqualTo(type)
            assertThat(event.summary).isEqualTo("$type: [内容已隐藏]")
        }
    }

    @Test
    fun `原因链有上限且不会因循环引用死循环`() {
        val first = IllegalStateException("a")
        val second = IllegalStateException("b", first)
        first.initCause(second)
        val deep = (1..10).fold(IllegalStateException("root") as Throwable) { cause, _ -> IllegalStateException("x", cause) }

        assertThat(DiagnosticRedactor.snapshot("WORKSPACE_JOURNAL", second).causes).hasSize(1)
        assertThat(DiagnosticRedactor.snapshot("WORKSPACE_JOURNAL", deep).causes).hasSize(4)
    }

    @Test
    fun `后台任务诊断事件只保留白名单元字段并丢弃命令正文与秘密`() {
        val started = DiagnosticRedactor.projectStructuredFields(
            "job.start",
            mapOf(
                "sessionId" to kotlinx.serialization.json.JsonPrimitive("s1"),
                "runner" to kotlinx.serialization.json.JsonPrimitive("LINUX"),
                "background" to kotlinx.serialization.json.JsonPrimitive(true),
                "maxRuntimeMinutes" to kotlinx.serialization.json.JsonPrimitive(120),
                "foregroundState" to kotlinx.serialization.json.JsonPrimitive("STARTED"),
                "command" to kotlinx.serialization.json.JsonPrimitive("codex login --secret sk-123"),
            ),
        )
        assertThat(started["runner"]?.toString()).isEqualTo("\"LINUX\"")
        assertThat(started["background"]?.toString()).isEqualTo("true")
        assertThat(started["maxRuntimeMinutes"]?.toString()).isEqualTo("120")
        assertThat(started["foregroundState"]?.toString()).isEqualTo("\"STARTED\"")
        assertThat(started["command"]).isNull()

        val ended = DiagnosticRedactor.projectStructuredFields(
            "job.end",
            mapOf(
                "sessionId" to kotlinx.serialization.json.JsonPrimitive("s1"),
                "runner" to kotlinx.serialization.json.JsonPrimitive("LINUX"),
                "state" to kotlinx.serialization.json.JsonPrimitive("EXITED"),
                "exitCode" to kotlinx.serialization.json.JsonPrimitive(0),
                "timedOut" to kotlinx.serialization.json.JsonPrimitive(false),
                "stoppedByUser" to kotlinx.serialization.json.JsonPrimitive(false),
                "killedExternally" to kotlinx.serialization.json.JsonPrimitive(false),
                "maxRuntimeMinutes" to kotlinx.serialization.json.JsonPrimitive(120),
                "elapsedMs" to kotlinx.serialization.json.JsonPrimitive(81234),
                "stdout" to kotlinx.serialization.json.JsonPrimitive("Bearer secret-token"),
            ),
        )
        assertThat(ended["state"]?.toString()).isEqualTo("\"EXITED\"")
        assertThat(ended["exitCode"]?.toString()).isEqualTo("0")
        assertThat(ended["timedOut"]?.toString()).isEqualTo("false")
        assertThat(ended["stoppedByUser"]?.toString()).isEqualTo("false")
        assertThat(ended["killedExternally"]?.toString()).isEqualTo("false")
        assertThat(ended["elapsedMs"]?.toString()).isEqualTo("81234")
        assertThat(ended["stdout"]).isNull()
    }

    private fun identityMismatch() = SecurityException("工作区根身份与数据库认领不一致")
}
