package com.promenar.infinity.data.browser

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertThrows
import org.junit.Test

class BrowserDeveloperStateTest {
    @Test fun `控制台环形缓冲按级别读取且增量读取推进游标`() {
        val buffer = BrowserConsoleBuffer(2)
        buffer.append("log", "one", "https://example.com/a?token=private", 1, 10)
        buffer.append("error", "two", "source", 2, 20)
        buffer.append("warning", "three", "source", 3, 30)
        assertThat(buffer.read(setOf("error"), true).map { it.text }).containsExactly("two")
        assertThat(buffer.read(emptySet(), true)).isEmpty()
        buffer.append("log", "four", "source", 4, 40)
        assertThat(buffer.read(emptySet(), true).map { it.text }).containsExactly("four")
        assertThat(buffer.read(emptySet(), false).map { it.text }).containsExactly("three", "four").inOrder()
        buffer.clear()
        assertThat(buffer.read(emptySet(), true)).isEmpty()
    }

    @Test fun `控制台预算分页后增量读取覆盖全部记录`() {
        val buffer = BrowserConsoleBuffer()
        repeat(20) { buffer.append("log", "x".repeat(2000), "https://example.com/source", 1, it.toLong()) }
        val returned = mutableListOf<Long>()
        repeat(10) { returned += buffer.read(emptySet(), true).map { it.sequence } }
        assertThat(returned).containsExactlyElementsIn((1L..20L).toList()).inOrder()
        assertThat(buffer.read(emptySet(), true)).isEmpty()
    }

    @Test fun `网络摘要只保存安全网址与元数据且容量有界`() {
        val buffer = BrowserNetworkBuffer(1)
        buffer.request("POST", "https://user:password@example.com/api?token=secret#secret", true, 10)
        buffer.request("GET", "https://example.com/image?Authorization=secret", false, 20)
        val entries = buffer.read()
        assertThat(entries).hasSize(1)
        assertThat(entries.single().url).isEqualTo("https://example.com/image")
        assertThat(entries.single().statusCode).isNull()
        assertThat(entries.single().durationMs).isNull()
        assertThat(browserDiagnosticUrl("https://user:password@example.com/a?Cookie=secret#value")).isEqualTo("https://example.com/a")
        assertThat(browserDiagnosticUrl("data:text/plain,secret")).isEqualTo("[non-http URL]")
    }

    @Test fun `标签切换关闭与释放状态不丢失URL和显示模式`() {
        var seq = 0
        val tabs = BrowserTabs { "tab-${++seq}" }
        val first = tabs.create("https://example.com/a")
        val second = tabs.create("https://example.com/b")
        tabs.setDisplayMode(BrowserDisplayMode.DESKTOP)
        tabs.update(first.id) { it.copy(title = "A", released = true) }
        tabs.select(first.id)
        assertThat(tabs.currentId).isEqualTo(first.id)
        assertThat(tabs.list().first().url).isEqualTo("https://example.com/a")
        assertThat(tabs.list().first().released).isTrue()
        assertThat(tabs.displayMode).isEqualTo(BrowserDisplayMode.DESKTOP)
        tabs.close(first.id)
        assertThat(tabs.currentId).isEqualTo(second.id)
        tabs.close(second.id)
        assertThat(tabs.currentId).isNull()
        assertThrows(BrowserActionException::class.java) { tabs.select(first.id) }
    }

    @Test fun `全局页面配额淘汰最久未用的可释放页面并保护忙碌页面`() {
        val released = mutableListOf<String>()
        val pool = BrowserPagePool(2)
        pool.acquire("a", { true }) { released += "a" }
        pool.acquire("b", { false }) { released += "b" }
        pool.touch("a")
        pool.acquire("c", { true }) { released += "c" }
        assertThat(released).containsExactly("a")
        assertThat(pool.size).isEqualTo(2)
        pool.remove("c")
        pool.acquire("d", { false }) { released += "d" }
        assertThrows(BrowserActionException::class.java) { pool.acquire("e", { true }) {} }
        assertThat(pool.size).isEqualTo(2)
    }

    @Test fun `关闭会话使旧排队动作失效且允许显式重新打开`() {
        val epochs = BrowserSessionEpochs()
        val pending = epochs.capture("chat")
        val other = epochs.capture("other")
        epochs.close("chat")
        assertThat(epochs.isCurrent("chat", pending)).isFalse()
        assertThat(epochs.isCurrent("other", other)).isTrue()
        assertThat(epochs.isCurrent("chat", epochs.capture("chat"))).isTrue()
    }

    @Test fun `开发工具拒绝错误类型与超限参数`() {
        fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
        assertThrows(BrowserArgumentException::class.java) { BrowserDevArguments.code(args("""{"code":true}""")) }
        assertThrows(BrowserArgumentException::class.java) { BrowserDevArguments.code(args("""{"code":" "}""")) }
        assertThrows(BrowserArgumentException::class.java) { BrowserDevArguments.limit(args("""{"limit":0}"""), 20, 50) }
        assertThrows(BrowserArgumentException::class.java) { BrowserDevArguments.displayMode(args("""{"mode":"tablet"}""")) }
        assertThrows(BrowserArgumentException::class.java) { BrowserDevArguments.levels(args("""{"levels":["fatal"]}""")) }
        assertThat(BrowserDevArguments.displayMode(args("""{"mode":"desktop"}"""))).isEqualTo(BrowserDisplayMode.DESKTOP)
    }
}
