package com.promenar.infinity.ui.lab.fakes

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.ui.chat.MemoryChange
import com.promenar.infinity.ui.chat.MemoryChangeHost

/** 撤销只产生内存调用记录，不变更展示记忆；脚本可显式设置任意时间点的完整快照。 */
internal class LabMemoryChangeHost(
    private val onUndo: (MemoryChange) -> Unit = {},
) : LabCallRecorder(), MemoryChangeHost {
    private val current = mutableStateOf<Map<String, LongTermMemory>>(emptyMap())
    override val memories: State<Map<String, LongTermMemory>> = current

    fun setMemories(value: Map<String, LongTermMemory>) { current.value = value.toMap() }

    override fun undo(change: MemoryChange) {
        record("undo", change)
        onUndo(change)
    }
}
