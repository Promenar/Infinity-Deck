package com.promenar.infinity.ui.lab

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LabIsolationGuardTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `实验室生产源码不引用真实系统入口`() {
        // Gradle 将工作目录设为 native-ui；也兼容直接从 app 模块启动测试。
        val relative = "src/main/java/com/promenar/infinity/ui/lab"
        val root = listOf(File(relative), File("app/$relative")).firstOrNull { it.isDirectory }
            ?: error("实验室源码目录不存在：$relative 或 app/$relative")
        assertThat(LabSourceIsolation.scan(root)).isEmpty()
    }

    @Test
    fun `违规样例返回文件行号与规则且注释和字面量不误报`() {
        val file = temporaryDirectory.resolve("Unsafe.kt").toFile().apply {
            writeText(
                """
                // InfinityApplication 与 DemoRepository 只是注释
                /* 外层 /* SecretStore */ GitInspector */
                import com.promenar.infinity.data.ssh.SshRuntime as Remote
                val app = InfinityApplication()
                val store: DemoRepository? = null
                val dao: MessageDao? = null
                val client: UnifiedLlmClient? = null
                val secret: AndroidKeystoreSecretStore? = null
                val harmless = "SecretStore // DemoRepository"
                """.trimIndent(),
            )
        }
        val hits = LabSourceIsolation.scan(temporaryDirectory.toFile())
        assertThat(hits.map { it.line }).containsAtLeast(3, 4, 5, 6, 7, 8)
        assertThat(hits.map { it.line }).containsNoneOf(1, 2, 9)
        assertThat(hits.all { it.file == file && it.rule.isNotBlank() }).isTrue()
        assertThat(hits.joinToString("\n")).contains("Unsafe.kt:4")
    }

    @Test
    fun `完整规则清单可捕获全限定名别名通配导入与裸标识符`() {
        val forbidden = listOf(
            "InfinityApplication", "DemoRepository", "DemoDao", "UnifiedLlmClient", "ProviderRequestRouter",
            "ProviderManager", "GenerationCoordinator", "LinuxTerminalSessions", "LinuxPtySession", "LinuxRuntime",
            "GitInspectCommandRunner", "SshRuntime", "SecretStore", "AndroidKeystoreSecretStore", "SshCredentialStore",
            "GitCredentialStore", "LazyGitCredentialStore", "SecretGitCredentialStore", "SessionSecretVault",
            "AndroidTransactionalBackupSecretStore", "TransactionalBackupSecretStore", "AndroidKeystorePendingRestoreCryptor",
        )
        val file = temporaryDirectory.resolve("Symbols.kt").toFile()
        forbidden.forEach { symbol ->
            file.writeText("val value: $symbol? = null")
            assertThat(LabSourceIsolation.scan(temporaryDirectory.toFile())).isNotEmpty()
        }
        listOf("ssh", "git", "linux").forEach { name ->
            file.writeText("import com.promenar.infinity.data.$name.*")
            assertThat(LabSourceIsolation.scan(temporaryDirectory.toFile()).map { it.rule }).contains(LabSourceIsolation.SYSTEM_PACKAGE_RULE)
            file.writeText("import com.promenar.infinity.`data`.`$name`.*")
            assertThat(LabSourceIsolation.scan(temporaryDirectory.toFile()).map { it.rule }).contains(LabSourceIsolation.SYSTEM_PACKAGE_RULE)
        }
        file.writeText("val value = \"\${InfinityApplication()}\"")
        assertThat(LabSourceIsolation.scan(temporaryDirectory.toFile())).isNotEmpty()
        file.writeText("val value = \"\$SecretStore\"")
        assertThat(LabSourceIsolation.scan(temporaryDirectory.toFile())).isNotEmpty()
    }

    @Test
    fun `缺失目录与空目录均不得空扫描通过`() {
        assertThrows(IllegalArgumentException::class.java) { LabSourceIsolation.scan(temporaryDirectory.resolve("missing").toFile()) }
        assertThrows(IllegalArgumentException::class.java) { LabSourceIsolation.scan(temporaryDirectory.toFile()) }
    }
}

/** 仅用于 JVM 守卫的词法扫描器，保留源码偏移以生成准确行号。 */
internal object LabSourceIsolation {
    const val APPLICATION_RULE = "应用全局入口"
    const val STORAGE_RULE = "真实持久化类型"
    const val MODEL_RULE = "真实模型与生成入口"
    const val LINUX_RULE = "真实 Linux 运行时"
    const val SYSTEM_PACKAGE_RULE = "真实 SSH、Git、Linux 包"
    const val EXECUTOR_RULE = "真实 SSH、Git 执行入口"
    const val SECRET_RULE = "真实密钥存储"

    // 由 data/security、data/secret、data/git/remote 与 data/backup 的实际声明核对。
    private val SECRET_TYPES = setOf(
        "SecretStore", "AndroidKeystoreSecretStore", "SshCredentialStore", "GitCredentialStore",
        "LazyGitCredentialStore", "SecretGitCredentialStore", "SessionSecretVault", "AesGcmSecretCodec",
        "SecretCatalog", "SecretDestinationWriter", "DefaultSecretDestinationWriter", "SessionSecretRecovery",
        "DefaultSecretOutputController", "AndroidTransactionalBackupSecretStore", "TransactionalBackupSecretStore",
        "AndroidKeystorePendingRestoreCryptor",
    )
    private val MODEL_TYPES = setOf("UnifiedLlmClient", "ProviderRequestRouter", "ProviderManager", "GenerationCoordinator")
    private val LINUX_TYPES = setOf("LinuxTerminalSessions", "LinuxPtySession", "LinuxRuntime")
    private val EXECUTOR_TYPES = setOf(
        "SshRuntime", "SshExecControl", "GitInspector", "GitChangesService", "SessionGitWorkspaceLocator",
        "GitInspectCommandRunner", "BuiltinGitInspectCommandRunner", "GitPythonRuntime", "BuiltinGitPythonRuntime",
        "GitWorkTreeReader", "NioGitWorkTreeReader", "GitSshBridge", "GitRemoteBroker", "GitCredentialProxy",
        "GitHubApiProxy", "GitHubApiForwarder", "LinuxGitEnvironment", "SshConnectionService",
    )
    private val IDENTIFIER = Regex("`[^`\\n]+`|[\\p{L}_][\\p{L}\\p{N}_]*")
    private val SYSTEM_PACKAGE = Regex("\\b(?:com\\s*\\.\\s*promenar\\s*\\.\\s*infinity\\s*\\.\\s*)?data\\s*\\.\\s*(?:ssh|git|linux)\\b")
    private val EXECUTOR_NAME = Regex("(?:.*(?:Ssh|SSH|Git).*(?:Executor|Runner|Runtime|Service|Broker|Bridge|Forwarder|Proxy|Client|Control|Environment))")

    data class Violation(val file: File, val line: Int, val rule: String, val symbol: String) {
        override fun toString(): String = "${file.path}:$line [$rule] $symbol"
    }

    fun scan(directory: File): List<Violation> {
        require(directory.isDirectory) { "实验室源码目录不存在：${directory.path}" }
        val files = directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }.toList()
        require(files.isNotEmpty()) { "实验室源码目录没有 Kotlin 文件：${directory.path}" }
        return files.flatMap { file ->
            val source = KotlinCodeMask(file.readText()).mask()
            fun hit(offset: Int, rule: String, symbol: String) = Violation(file, source.take(offset).count { it == '\n' } + 1, rule, symbol)
            buildList {
                SYSTEM_PACKAGE.findAll(source.replace('`', ' ')).forEach { add(hit(it.range.first, SYSTEM_PACKAGE_RULE, it.value)) }
                IDENTIFIER.findAll(source).forEach { match ->
                    val name = match.value.removeSurrounding("`")
                    val rule = when {
                        name == "InfinityApplication" -> APPLICATION_RULE
                        name.endsWith("Repository") || name.endsWith("Dao") -> STORAGE_RULE
                        name in MODEL_TYPES -> MODEL_RULE
                        name in LINUX_TYPES -> LINUX_RULE
                        name in SECRET_TYPES -> SECRET_RULE
                        name in EXECUTOR_TYPES || EXECUTOR_NAME.matches(name) -> EXECUTOR_RULE
                        else -> null
                    }
                    if (rule != null) add(hit(match.range.first, rule, name))
                }
            }
        }
    }
}

/** 跳过嵌套注释、字符及字符串字面量，同时扫描字符串模板中的实际代码。 */
private class KotlinCodeMask(private val source: String) {
    private val output = CharArray(source.length) { if (source[it] == '\n') '\n' else ' ' }
    private var index = 0

    fun mask(): String { code(template = false); return String(output) }

    private fun code(template: Boolean) {
        var depth = 0
        while (index < source.length) {
            when {
                source.startsWith("//", index) -> { while (index < source.length && source[index] != '\n') index++ }
                source.startsWith("/*", index) -> comment()
                source[index] == '"' -> string()
                source[index] == '\'' -> {
                    index++
                    while (index < source.length) {
                        val char = source[index++]
                        if (char == '\\') index = (index + 1).coerceAtMost(source.length)
                        else if (char == '\'') break
                    }
                }
                source[index] == '`' -> {
                    output[index] = source[index++]
                    while (index < source.length) { output[index] = source[index]; if (source[index++] == '`') break }
                }
                source[index] == '}' && template && depth == 0 -> { index++; return }
                else -> {
                    if (source[index] == '{') depth++
                    if (source[index] == '}') depth--
                    output[index] = source[index++]
                }
            }
        }
    }

    private fun comment() {
        var depth = 1
        index += 2
        while (index < source.length && depth > 0) {
            when {
                source.startsWith("/*", index) -> { depth++; index += 2 }
                source.startsWith("*/", index) -> { depth--; index += 2 }
                else -> index++
            }
        }
    }

    private fun string() {
        val raw = source.startsWith("\"\"\"", index)
        val delimiter = if (raw) "\"\"\"" else "\""
        index += delimiter.length
        while (index < source.length) {
            when {
                source.startsWith(delimiter, index) -> { index += delimiter.length; return }
                !raw && source[index] == '\\' -> index = (index + 2).coerceAtMost(source.length)
                source.startsWith("\${", index) -> { index += 2; code(template = true) }
                source[index] == '$' -> {
                    index++
                    while (index < source.length && (source[index].isLetterOrDigit() || source[index] == '_')) {
                        output[index] = source[index++]
                    }
                }
                else -> index++
            }
        }
    }
}
