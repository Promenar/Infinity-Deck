package com.promenar.infinity

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Android 的正则由 ICU 实现：花括号不属于 {n}、{n,}、{n,m} 量词时必须转义，否则构造 Regex 即抛
 * PatternSyntaxException。JVM 单测接受未转义的右花括号，只有扫描源码才能在上真机前发现。
 */
class AndroidRegexBraceContractTest {
    private val literal = Regex("""Regex\(\s*(?:"{3}([\s\S]*?)"{3}|"((?:[^"\\]|\\.)*)")""")
    private val quantifier = Regex("""\{\d+(,\d*)?}""")
    private val template = Regex("""\$\{[^}]*}|\$[A-Za-z_]\w*""")

    @Test fun `生产代码的正则字面量不含未转义的花括号`() {
        val root = listOf(File("src/main/java"), File("app/src/main/java")).first { it.isDirectory }
        val offenders = root.walkTopDown().filter { it.extension == "kt" }.flatMap { file ->
            literal.findAll(file.readText()).mapNotNull { match ->
                val pattern = match.groupValues[1].ifEmpty { unescapeKotlin(match.groupValues[2]) }
                val raw = match.groupValues[1].isNotEmpty() || match.groupValues[2].isNotEmpty()
                if (raw && hasBareBrace(pattern.replace(template, "1"))) "${file.relativeTo(root)}: $pattern" else null
            }
        }.toList()
        assertWithMessage("以下正则在 Android 上会构造失败，花括号需写成 \\\\{ 或 \\\\}").that(offenders).isEmpty()
    }

    @Test fun `检查器识别量词、转义与字符类`() {
        assertWithMessage("未转义右花括号").that(hasBareBrace("\\{([^{}]+)}")).isTrue()
        assertWithMessage("未转义左花括号").that(hasBareBrace("a{b")).isTrue()
        assertWithMessage("已转义").that(hasBareBrace("\\{([^{}]+)\\}")).isFalse()
        assertWithMessage("量词").that(hasBareBrace("[a-z]{1,96}x{3}y{2,}")).isFalse()
        assertWithMessage("Unicode 属性").that(hasBareBrace("[\\p{L}]+\\p{N}{2,}")).isFalse()
    }

    private fun unescapeKotlin(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) { append(text[i + 1]); i += 2 } else { append(c); i++ }
        }
    }

    private fun hasBareBrace(pattern: String): Boolean {
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            val c = pattern[i]
            when {
                c == '\\' && pattern.getOrNull(i + 1) in setOf('p', 'P') && pattern.getOrNull(i + 2) == '{' ->
                    i = pattern.indexOf('}', i).let { if (it < 0) return true else it + 1 }
                c == '\\' -> i += 2
                inClass -> { if (c == ']') inClass = false; i++ }
                c == '[' -> { inClass = true; i++; if (pattern.getOrNull(i) == '^') i++; if (pattern.getOrNull(i) == ']') i++ }
                c == '{' -> {
                    val q = quantifier.matchAt(pattern, i) ?: return true
                    i = q.range.last + 1
                }
                c == '}' -> return true
                else -> i++
            }
        }
        return false
    }
}
