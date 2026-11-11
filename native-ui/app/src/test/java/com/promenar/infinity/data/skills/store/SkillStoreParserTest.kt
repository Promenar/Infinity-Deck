package com.promenar.infinity.data.skills.store

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class SkillStoreParserTest {
    private fun entry(name: String = "demo", extra: String = "") = """{"name":"$name","version":"1.2.3","title":{"zh-CN":"示例","en":"Example"},"summary":{"en":"summary"},"category":"writing","author":"a","license":"MIT","archive":"demo.zip","size":10,"sha256":"${"a".repeat(64)}","files":[{"path":"SKILL.md","size":5},{"path":"scripts/a.txt","size":1}],"channels":["play"],"minAppVersionCode":127$extra}"""
    private fun parse(entries: String, locale: Locale = Locale.SIMPLIFIED_CHINESE) = SkillStoreParser.parse("""{"schema":1,"categories":[{"id":"writing","title":{"en":"Writing"}}],"skills":[$entries]}""".toByteArray(), "https://example.com/catalog/index.json", locale)
    @Test fun `解析相对地址语言脚本分类和最低版本`() {
        val value = parse(entry()).skills.single()
        assertThat(value.title).isEqualTo("示例")
        assertThat(value.summary).isEqualTo("summary")
        assertThat(value.archiveUrl).isEqualTo("https://example.com/catalog/demo.zip")
        assertThat(value.hasScripts).isTrue()
        assertThat(value.minAppVersionCode).isEqualTo(127)
        assertThat(value.categoryId).isEqualTo("writing")
        assertThat(parse(entry(), Locale.FRENCH).skills.single().title).isEqualTo("Example")
    }
    @Test fun `非法条目逐项跳过并保留首个同名`() {
        val valid = entry(); val invalid = listOf(
            entry("Bad"), entry("a".repeat(65)), valid.replace("1.2.3", "1.2"), valid.replace("a".repeat(64), "bad"),
            valid.replace("\"size\":10", "\"size\":0"), valid.replace("SKILL.md", "../SKILL.md"),
            valid.replace("\"play\"", "\"other\""), valid.replace("demo.zip", "http://bad/path"),
            valid.replace("\"path\":\"scripts/a.txt\"", "\"path\":\"SKILL.md\""),
            valid.replace("SKILL.md", "other.md"), valid.replace("[\"play\"]", "[]")
        )
        assertThat(parse((invalid + valid + valid).joinToString(",")).skills.map { it.name }).containsExactly("demo")
        assertThat(parse(valid.replace("writing\",\"author", "unknown\",\"author")).skills.single().categoryId).isNull()
        assertThat(parse(valid.replace("scripts/a.txt", "lib/run.py")).skills.single().hasScripts).isTrue()
    }
    @Test fun `文件数量大小目录上限与语言回退`() {
        val valid = entry()
        val tooMany = (0..1000).joinToString(",") { "{\"path\":\"f$it\",\"size\":0}" }
        val oversizedFiles = valid.replace("[{\"path\":\"SKILL.md\",\"size\":5},{\"path\":\"scripts/a.txt\",\"size\":1}]", "[$tooMany]")
        assertThat(parse(oversizedFiles).skills).isEmpty()
        assertThat(parse(valid.replace("\"size\":10", "\"size\":33554433")).skills).isEmpty()
        assertThat(parse(valid.replace("\"size\":5", "\"size\":8388609")).skills).isEmpty()
        val language = valid.replace("\"zh-CN\":\"示例\"", "\"zh\":\"中文\"")
        assertThat(parse(language, Locale.TAIWAN).skills.single().title).isEqualTo("中文")
        val error = runCatching { SkillStoreParser.parse(ByteArray(1024 * 1024 + 1), "https://example.com/", Locale.ENGLISH) }.exceptionOrNull() as StoreException
        assertThat(error.failure).isEqualTo(StoreFailure.TOO_LARGE)
    }
    @Test fun `不支持的模式及数字版本比较`() {
        val error = runCatching { SkillStoreParser.parse("""{"schema":2,"skills":[]}""".toByteArray(), "https://example.com/index", Locale.ENGLISH) }.exceptionOrNull() as StoreException
        assertThat(error.failure).isEqualTo(StoreFailure.UNSUPPORTED_SCHEMA)
        assertThat(SkillStoreParser.compareVersions("1.10.0", "1.9.99")).isGreaterThan(0)
        assertThat(SkillStoreParser.compareVersions("1.0.0-beta", "1.0.0")).isLessThan(0)
    }
}
