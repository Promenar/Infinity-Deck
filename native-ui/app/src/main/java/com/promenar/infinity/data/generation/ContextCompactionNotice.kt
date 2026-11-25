package com.promenar.infinity.data.generation

import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import java.util.Locale
import java.util.UUID

/** 压缩成果在会话时间线中的持久化提示。 */
object ContextCompactionNotice {
    const val MESSAGE_NAME = "context_compaction"

    fun formatTokenCount(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> String.format(Locale.ROOT, "%.1fk", tokens / 1_000.0)
        else -> tokens.coerceAtLeast(0).toString()
    }

    fun formatDelta(beforeTokens: Int, afterTokens: Int): String =
        "${formatTokenCount(beforeTokens)} → ${formatTokenCount(afterTokens)}"

    fun create(beforeTokens: Int, afterTokens: Int, now: Long = System.currentTimeMillis()): Message = Message(
        id = "context_compaction_${UUID.randomUUID()}",
        role = MessageRole.SYSTEM,
        content = formatDelta(beforeTokens, afterTokens),
        name = MESSAGE_NAME,
        createdAt = now,
    )
}

val Message.isContextCompactionNotice: Boolean
    get() = role == MessageRole.SYSTEM && name == ContextCompactionNotice.MESSAGE_NAME

internal fun resolveCompactionNoticeTimestamp(messages: List<Message>, anchorBeforeMessageId: String?, now: Long): Long {
    if (anchorBeforeMessageId != null) {
        val index = messages.indexOfFirst { it.id == anchorBeforeMessageId }
        if (index >= 0) {
            val userAt = messages[index].createdAt
            val previousAt = messages.getOrNull(index - 1)?.createdAt
            // 相差 1ms 时，Room 按 rowid 将锚点排在同毫秒的旧消息之后。
            if (userAt > Long.MIN_VALUE && (previousAt == null || userAt > previousAt)) return userAt - 1L
        }
    }
    val tail = messages.lastOrNull()?.createdAt ?: 0L
    return maxOf(now, tail.coerceAtMost(Long.MAX_VALUE - 1L) + 1L)
}
