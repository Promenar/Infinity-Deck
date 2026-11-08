package com.promenar.infinity.ui.memory

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.data.memory.LongTermMemoryKind
import org.junit.jupiter.api.Test

class LongTermMemoryGroupingTest {
    private val items = listOf(
        item("1", LongTermMemoryKind.PROJECT, "Infinity Deck 验收", "先跑定向测试"),
        item("2", LongTermMemoryKind.USER, "称呼", "叫我小冷"),
        item("3", LongTermMemoryKind.PROJECT, "发布", "只推送当前分支"),
        item("4", LongTermMemoryKind.FEEDBACK, "语言", "使用简体中文"),
    )

    @Test
    fun `按类型固定顺序分组，组内保持原有顺序并省略空组`() {
        val groups = groupLongTermMemories(items, "")
        assertThat(groups.map { it.first }).containsExactly(
            LongTermMemoryKind.USER, LongTermMemoryKind.FEEDBACK, LongTermMemoryKind.PROJECT,
        ).inOrder()
        assertThat(groups.last().second.map { it.memory.id }).containsExactly("1", "3").inOrder()
    }

    @Test
    fun `搜索忽略大小写并同时匹配标题与正文`() {
        assertThat(groupLongTermMemories(items, " infinity ").flatMap { it.second }.map { it.memory.id }).containsExactly("1")
        assertThat(groupLongTermMemories(items, "中文").flatMap { it.second }.map { it.memory.id }).containsExactly("4")
        assertThat(groupLongTermMemories(items, "不存在")).isEmpty()
    }

    private fun item(id: String, kind: LongTermMemoryKind, title: String, content: String) =
        LongTermMemoryListItem(LongTermMemory(id, kind, title, content, null, null, null, 1, 1), agentName = null)
}
