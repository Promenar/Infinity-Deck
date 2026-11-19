package com.promenar.infinity

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.jupiter.api.Test

class ReleaseLoggingSourceContractTest {
    private val projectRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .first { File(it, "app/src/main/java/com/promenar/infinity").isDirectory }
    private val sourceRoot = File(projectRoot, "app/src/main/java/com/promenar/infinity")

    @Test
    fun `release 可达源码只允许统一日志器直接访问平台日志`() {
        // 唯一白名单是平台日志适配边界；平台输出由下一项测试锁定 DEBUG 门禁。
        val violations = sourceRoot.walkTopDown()
            .filter {
                it.isFile &&
                    it.extension in setOf("kt", "java") &&
                    it.relativeTo(sourceRoot).invariantSeparatorsPath != "utils/InfinityLogger.kt"
            }
            .flatMap { file ->
                val lines = file.readLines()
                val logNames = listOf("Log") + lines.mapNotNull { line ->
                    Regex("^\\s*import\\s+android\\.util\\.Log\\s+as\\s+(\\w+)").find(line)?.groupValues?.get(1)
                }
                val logCall = Regex("\\b(?:${logNames.joinToString("|") { Regex.escape(it) }})\\s*\\.\\s*\\w+\\s*\\(")
                lines.asSequence().mapIndexedNotNull { index, line ->
                    val directLog = logCall.containsMatchIn(line)
                    val directPrint = Regex("\\bprintStackTrace\\s*\\(").containsMatchIn(line) ||
                        line.contains("System.err.print") ||
                        line.contains("System.out.print") ||
                        Regex("(?<![A-Za-z])println\\s*\\(").containsMatchIn(line)
                    if (directLog || directPrint) "${file.relativeTo(sourceRoot)}:${index + 1}" else null
                }
            }
            .toList()

        assertThat(violations).isEmpty()
    }

    @Test
    fun `普通日志输出由DEBUG保护且错误先记录私有诊断`() {
        val logger = File(sourceRoot, "utils/InfinityLogger.kt").readText()

        assertThat(logger).contains("fun log(message: String) {\n        if (!com.promenar.infinity.BuildConfig.DEBUG) return")
        assertThat(logger).contains("fun logError(tag: String, throwable: Throwable) {\n        AppDiagnostics.recordError(tag, throwable)\n        if (!com.promenar.infinity.BuildConfig.DEBUG) return")
        assertThat(logger).contains("= AppDiagnostics.record(event, fields)")
        assertThat(logger).doesNotContain("fun metro(")
        assertThat(logger).doesNotContain("infinity_logs.txt")
    }

    @Test
    fun `R8 使用 Kotlin object 的实例方法签名并精确剥离平台输出`() {
        val rules = File(projectRoot, "app/proguard-rules.pro").readText()

        assertThat(rules).contains("public void log(java.lang.String);")
        assertThat(rules).doesNotContain("public void logError(java.lang.String, java.lang.Throwable);")
        assertThat(rules).contains("-keeppackagenames com.promenar.infinity,com.promenar.infinity.**")
        assertThat(rules).contains("-keepnames class com.promenar.infinity.** extends java.lang.Throwable")
        assertThat(rules).doesNotContain("public void metro(")
        assertThat(rules).doesNotContain("public static void log(java.lang.String);")
        assertThat(rules).contains("public static int d(java.lang.String, java.lang.String);")
        assertThat(rules).contains("public static int d(java.lang.String, java.lang.String, java.lang.Throwable);")
        assertThat(rules).contains("public static int e(java.lang.String, java.lang.String);")
        assertThat(rules).contains("public static int e(java.lang.String, java.lang.String, java.lang.Throwable);")
        assertThat(rules).contains("public void printStackTrace();")
        assertThat(rules).contains("public void printStackTrace(java.io.PrintStream);")
        assertThat(rules).contains("public void printStackTrace(java.io.PrintWriter);")
    }
}
