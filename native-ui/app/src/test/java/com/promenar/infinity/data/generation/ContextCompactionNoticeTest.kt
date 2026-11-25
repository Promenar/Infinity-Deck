package com.promenar.infinity.data.generation

import android.content.SharedPreferences
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.workspace.WorkspaceSwitchNotice
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import io.mockk.mockk
import org.junit.Test

class ContextCompactionNoticeTest {
    @Test fun `令牌数按固定格式显示千与百万并将负数钳为零`() {
        val tokens = listOf(Int.MIN_VALUE, -1, 0, 999, 1_000, 1_250, 999_999, 1_000_000, 2_450_000)
        assertThat(tokens.map(ContextCompactionNotice::formatTokenCount))
            .containsExactly("0", "0", "0", "999", "1.0k", "1.3k", "1000.0k", "1.0M", "2.5M").inOrder()
        assertThat(ContextCompactionNotice.formatDelta(2_450_000, 24_500)).isEqualTo("2.5M → 24.5k")
        assertThat(ContextCompactionNotice.formatDelta(999, -1)).isEqualTo("999 → 0")
    }

    @Test fun `仅指定名称的SYSTEM消息被识别为压缩提示`() {
        val notice = ContextCompactionNotice.create(2_000, 500, now = 42)
        assertThat(notice.id).startsWith("context_compaction_")
        assertThat(notice.createdAt).isEqualTo(42L)
        assertThat(notice.content).isEqualTo("2.0k → 500")
        assertThat(notice.isContextCompactionNotice).isTrue()
        assertThat(notice.copy(role = MessageRole.USER).isContextCompactionNotice).isFalse()
        assertThat(notice.copy(name = null).isContextCompactionNotice).isFalse()
        assertThat(WorkspaceSwitchNotice.create("切换").isContextCompactionNotice).isFalse()
    }

    @Test fun `尾部锚点兼顾当前时间和会话末尾`() {
        val messages = timeline(10, 20)
        assertThat(resolveCompactionNoticeTimestamp(messages, null, 30)).isEqualTo(30L)
        assertThat(resolveCompactionNoticeTimestamp(messages, null, 15)).isEqualTo(21L)
        assertThat(resolveCompactionNoticeTimestamp(emptyList(), null, 30)).isEqualTo(30L)
        assertThat(resolveCompactionNoticeTimestamp(messages, "missing", 15)).isEqualTo(21L)
    }

    @Test fun `前置锚点能借助稳定插入顺序使用上一条消息的毫秒`() {
        assertThat(resolveCompactionNoticeTimestamp(timeline(10, 20), "current", 100)).isEqualTo(19L)
        assertThat(resolveCompactionNoticeTimestamp(timeline(19, 20), "current", 100)).isEqualTo(19L)
        assertThat(resolveCompactionNoticeTimestamp(timeline(10, 20).drop(1), "current", 100)).isEqualTo(19L)
    }

    @Test fun `当前轮与上一条消息同毫秒时回退到尾部`() {
        val messages = timeline(20, 20)
        assertThat(resolveCompactionNoticeTimestamp(messages, "current", 19)).isEqualTo(21L)
        assertThat(resolveCompactionNoticeTimestamp(messages, "current", 100)).isEqualTo(100L)
    }

    @Test fun `压缩提示不进入协议消息及历史归档候选`() {
        val (previous, current) = timeline(10, 20)
        val messages = listOf(
            previous,
            WorkspaceSwitchNotice.create("切换", now = 11),
            ContextCompactionNotice.create(2_000, 500, now = 12),
            Message("archived", MessageRole.ASSISTANT, "已归档", createdAt = 13, isArchived = true),
            current,
        )
        val strategy = DefaultChatGenerationContentStrategy(mockk<SharedPreferences>(relaxed = true), mockk<SkillRegistry>())
        val protocol = strategy.buildProtocolMessages(Session("s", "agent", messages = messages), "系统", current.id)

        assertThat(protocol.map { it.content }).containsExactly("系统", previous.content, current.content).inOrder()
        assertThat(historyCompactionCandidates(messages, current.id, keepRecentTurns = 1)).containsExactly(previous)
    }

    private fun timeline(previousAt: Long, currentAt: Long) = listOf(
        Message("previous", MessageRole.USER, "上一轮", createdAt = previousAt),
        Message("current", MessageRole.USER, "当前轮", createdAt = currentAt),
    )
}
