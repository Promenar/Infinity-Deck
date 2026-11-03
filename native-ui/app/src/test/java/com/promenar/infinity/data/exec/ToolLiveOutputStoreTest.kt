package com.promenar.infinity.data.exec

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.tool.ToolOutputStream.STDERR
import com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolLiveOutputStoreTest {
    @Test fun `相同终端快照仍按真实增量事件发布重复新输出`() {
        val store = ToolLiveOutputStore(limit = 10)
        val sink = store.open("job:repeated", inferOutputEvents = false)
        val terminal = com.promenar.infinity.data.linux.LinuxExecTerminalText(10)
        fun accept(text: String) {
            terminal.accept(text) { store.publishOutput("job:repeated", it) }
            sink.replace(com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT, terminal.snapshot().stdout)
        }
        accept("tick\ntick\n")
        val previous = store.snapshot("job:repeated")!!.text
        val changes = mutableListOf<String>()
        val unsubscribe = store.listenOutput("job:repeated", changes::add)
        accept("tick\n")
        assertThat(store.snapshot("job:repeated")!!.text).isEqualTo(previous)
        assertThat(changes).containsExactly("tick\n")
        unsubscribe()
        store.close("job:repeated", sink)
    }

    @Test fun `完整快照增量游标独立于显示尾部且不重放历史匹配词`() {
        val store = ToolLiveOutputStore(limit = 16_000)
        val sink = store.open("job:a")
        val old = "historical-ready\n" + "x".repeat(20_000)
        sink.replace(com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT, old)
        val changes = mutableListOf<String>()
        val unsubscribe = store.listenOutput("job:a", changes::add)
        sink.replace(com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT, old + "new-output")
        assertThat(changes).containsExactly("new-output")
        unsubscribe()
        sink.replace(com.promenar.infinity.domain.tool.ToolOutputStream.STDOUT, old + "new-output-more")
        assertThat(changes).containsExactly("new-output")
        store.close("job:a", sink)
    }

    @Test
    fun `两路输出按到达顺序合并，只保留尾部且不切开代理对`() {
        val store = ToolLiveOutputStore(limit = 3)
        val sink = store.open("a")

        sink.emit(STDOUT, "ab")
        sink.emit(STDERR, "c")
        assertThat(store.snapshot("a")?.text).isEqualTo("abc")
        assertThat(store.snapshot("a")?.truncated).isFalse()

        // 尾部 3 个 UTF-16 单元会从第一个 😀 的低位代理开始，应整字丢弃。
        sink.emit(STDOUT, "😀😀")
        assertThat(store.snapshot("a")?.text).isEqualTo("😀")
        assertThat(store.snapshot("a")?.truncated).isTrue()
    }

    @Test
    fun `停止只调用一次；先请求停止后登记的停止动作立即执行`() {
        val store = ToolLiveOutputStore()
        val sink = store.open("a")
        // 执行方尚未使用通道时对界面不可见，非命令类工具不会出现空面板。
        assertThat(store.snapshot("a")).isNull()
        var stops = 0
        sink.onStop { stops++ }
        assertThat(store.snapshot("a")?.stoppable).isTrue()
        store.requestStop("a")
        store.requestStop("a")
        assertThat(stops).isEqualTo(1)
        assertThat(sink.stopRequested).isTrue()
        assertThat(store.snapshot("a")?.stopRequested).isTrue()

        val late = store.open("b")
        store.requestStop("b")
        var lateStops = 0
        late.onStop { lateStops++ }
        assertThat(lateStops).isEqualTo(1)
    }

    @Test
    fun `执行方使用过的通道登记为活动，关闭或替换后移除`() {
        val store = ToolLiveOutputStore()
        val first = store.open("a")
        assertThat(store.activeKeys.value).isEmpty()
        first.emit(STDOUT, "x")
        assertThat(store.activeKeys.value).containsExactly("a")

        val replacement = store.open("a")
        assertThat(store.activeKeys.value).isEmpty()
        // 被替换的旧通道继续输出也不会重新登记。
        first.emit(STDOUT, "late")
        assertThat(store.activeKeys.value).isEmpty()
        replacement.onStop {}
        assertThat(store.activeKeys.value).containsExactly("a")
        store.close("a", replacement)
        assertThat(store.activeKeys.value).isEmpty()
    }

    @Test
    fun `关闭只移除自己登记的通道`() {
        val store = ToolLiveOutputStore()
        val old = store.open("a")
        val current = store.open("a")
        current.emit(STDOUT, "new")

        store.close("a", old)
        assertThat(store.snapshot("a")?.text).isEqualTo("new")
        store.close("a", current)
        assertThat(store.snapshot("a")).isNull()
    }

    @Test
    fun `观察按节流间隔合并快速输出`() = runTest {
        val store = ToolLiveOutputStore(throttleMillis = 100, dispatcher = StandardTestDispatcher(testScheduler))
        val sink = store.open("a")
        sink.emit(STDOUT, "-")
        val seen = mutableListOf<String?>()
        val job = launch { store.observe("a").collect { seen.add(it?.text) } }
        runCurrent()
        repeat(10) { sink.emit(STDOUT, "x") }
        advanceTimeBy(50)
        runCurrent()
        assertThat(seen).containsExactly("-")
        advanceTimeBy(60)
        runCurrent()
        assertThat(seen).containsExactly("-", "-xxxxxxxxxx").inOrder()
        store.close("a", sink)
        advanceTimeBy(200)
        runCurrent()
        assertThat(seen.last()).isNull()
        job.cancel()
    }
}
