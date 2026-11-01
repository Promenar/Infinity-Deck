package com.promenar.infinity.ui.lab.fakes

import com.promenar.infinity.data.exec.ToolLiveOutputStore
import com.promenar.infinity.domain.tool.ToolLiveOutputSink
import com.promenar.infinity.domain.tool.ToolOutputStream

/** 直接复用生产的内存输出存储；停止动作只记录 key。 */
internal class LabToolLiveOutput(
    private val onStop: (String) -> Unit = {},
) : LabCallRecorder() {
    val store = ToolLiveOutputStore(throttleMillis = 0)
    private val sinks = mutableMapOf<String, ToolLiveOutputSink>()

    /** 完整替换单个通道，适用于时间轴倒退、跳转和重播。 */
    fun setLines(key: String, lines: Iterable<String>, stream: ToolOutputStream = ToolOutputStream.STDOUT) {
        sinks[key] = open(key)
        appendLines(key, lines, stream)
    }

    /** 每个条目补一个换行；条目本身应是不含行尾换行的单行。 */
    fun appendLines(key: String, lines: Iterable<String>, stream: ToolOutputStream = ToolOutputStream.STDOUT) {
        val sink = sinks.getOrPut(key) { open(key) }
        lines.forEach { sink.emit(stream, "$it\n") }
    }

    fun clear(key: String) { sinks.remove(key)?.let { store.close(key, it) } }

    private fun open(key: String): ToolLiveOutputSink = store.open(key).also { sink ->
        sink.onStop { record("stopOutput", key); onStop(key) }
    }
}
