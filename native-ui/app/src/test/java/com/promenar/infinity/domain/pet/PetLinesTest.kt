package com.promenar.infinity.domain.pet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PetLinesTest {
    @Test
    fun `模板替换数据`() {
        assertEquals("写了 12 字", PetLineTemplate.render("写了 {output.chars} 字", mapOf("output.chars" to "12")))
    }

    @Test
    fun `缺键的模板跳过并取下一条`() {
        assertEquals("你好", PetLineTemplate.firstAvailable(listOf("{unknown}", "你好"), emptyMap()))
    }

    @Test
    fun `模板全部不可用时返回空`() {
        assertNull(PetLineTemplate.firstAvailable(listOf("{unknown}"), emptyMap()))
    }

    @Test
    fun `请求之间达到最小间隔才能通过`() {
        var now = 0L
        val limiter = PetLineLimiter({ now })
        assertTrue(limiter.tryAcquire())
        now = 19_999
        assertFalse(limiter.tryAcquire())
        now = 20_000
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `一小时请求上限在窗口过期后恢复`() {
        var now = 0L
        val limiter = PetLineLimiter({ now }, minimumIntervalMillis = 0, maxRequestsPerHour = 1)
        assertTrue(limiter.tryAcquire())
        now = 3_599_999
        assertFalse(limiter.tryAcquire())
        now = 3_600_000
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `记录台词会影响下一次请求间隔`() {
        var now = 10L
        val limiter = PetLineLimiter({ now })
        limiter.recordLine()
        now = 20_009
        assertFalse(limiter.tryAcquire())
        now = 20_010
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `提示词包含角色名与触发信号`() {
        val prompt = PetLinePromptBuilder.build(PetPersona("鱼"), "task.tool", emptyMap())
        assertTrue(prompt.system.contains("角色：鱼"))
        assertTrue(prompt.user.contains("触发信号：task.tool"))
    }

    @Test
    fun `提示词不包含兜底台词`() {
        val prompt = PetLinePromptBuilder.build(PetPersona("鱼", fallback = mapOf("idle" to listOf("秘密台词"))), "idle", emptyMap())
        assertFalse(prompt.system.contains("秘密台词"))
        assertFalse(prompt.user.contains("秘密台词"))
    }

    @Test
    fun `提示词只使用首个语言且默认中文`() {
        val prompt = PetLinePromptBuilder.build(PetPersona("鱼", languages = listOf("en", "ja")), "idle", emptyMap())
        assertTrue(prompt.system.contains("使用语言：en"))
        assertFalse(prompt.system.contains("ja"))
        assertTrue(PetLinePromptBuilder.build(PetPersona("鱼"), "idle", emptyMap()).system.contains("使用语言：zh-CN"))
    }

    @Test
    fun `数据值去除换行并截断至六十字符`() {
        val prompt = PetLinePromptBuilder.build(PetPersona("鱼"), "idle", mapOf("x" to "😀\n".repeat(61)))
        assertTrue(prompt.user.endsWith("x: " + "😀".repeat(60)))
    }

    @Test
    fun `先去换行再去包围引号`() {
        assertEquals("你好世界", cleanPetLine(" \n\"你好\n世界\"\r "))
    }

    @Test
    fun `空白与空引号返回空`() {
        assertNull(cleanPetLine(" \" \" "))
    }

    @Test
    fun `输出按码点截断三十字`() {
        assertEquals("😀".repeat(30), cleanPetLine("😀".repeat(31)))
    }
}
