package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.BookmarkAdded
import androidx.compose.material.icons.rounded.BookmarkRemove
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.memory.LongTermMemory
import com.promenar.infinity.data.memory.LongTermMemoryKind
import com.promenar.infinity.data.memory.LongTermMemoryRepository
import com.promenar.infinity.data.memory.LongTermMemorySource
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal enum class MemoryChangeAction { CREATED, UPDATED, FORGOTTEN }

/** 记忆的一份内容快照：工具参数、结果与撤销都只关心这三项。 */
internal data class MemorySnapshot(val kind: LongTermMemoryKind, val title: String, val content: String)

/** 一次成功的记忆写入或删除，带撤销所需的旧值。 */
internal data class MemoryChange(
    val callId: String,
    val action: MemoryChangeAction,
    val id: String,
    val written: MemorySnapshot,
    /** 覆盖前的内容，仅 [MemoryChangeAction.UPDATED] 有。 */
    val previous: MemorySnapshot? = null,
    /** 被删除记忆的来源与时间，撤销删除时按原样写回；结果缺字段时来源为空、时间取撤销时刻。 */
    val removedSource: LongTermMemorySource? = null,
    val removedCreatedAt: Long? = null,
    val removedUpdatedAt: Long? = null,
)

internal enum class MemoryUndoState { AVAILABLE, UNDONE, UNAVAILABLE }

private val MemoryJson = Json { ignoreUnknownKeys = true }

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull

private fun JsonObject.snapshot(): MemorySnapshot? {
    val kind = text("kind")?.let { raw -> LongTermMemoryKind.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } } ?: return null
    return MemorySnapshot(kind, text("title") ?: return null, text("content") ?: return null)
}

/** 从一次 `memory_save` / `memory_forget` 调用解析出记忆改动；失败、未完成或其它工具返回 null。 */
internal fun memoryChange(invocation: ToolInvocationPresentation): MemoryChange? {
    val name = invocation.name
    if (name != "memory_save" && name != "memory_forget") return null
    if (invocation.failed || !invocation.complete) return null
    val callId = invocation.call?.toolCallId ?: invocation.call?.id ?: return null
    val result = invocation.results.lastOrNull { it.type == "tool_result" }?.content ?: return null
    val data = runCatching { MemoryJson.parseToJsonElement(result).jsonObject }.getOrNull() ?: return null
    val id = data.text("id") ?: return null
    return if (name == "memory_forget") {
        MemoryChange(
            callId = callId,
            action = MemoryChangeAction.FORGOTTEN,
            id = id,
            written = data.snapshot() ?: return null,
            removedSource = LongTermMemorySource(data.text("source_agent_id"), data.text("source_session_id"), data.text("source_message_id")),
            removedCreatedAt = data["created_at"]?.jsonPrimitive?.longOrNull,
            removedUpdatedAt = data["updated_at"]?.jsonPrimitive?.longOrNull,
        )
    } else {
        // 结果里有写入后的内容时以结果为准，旧结果只带标题时回退到调用参数。
        val args = invocation.call?.toolArgs?.let { runCatching { MemoryJson.parseToJsonElement(it).jsonObject }.getOrNull() }
        val written = data.snapshot() ?: args?.snapshot()?.copy(title = data.text("title") ?: args.text("title").orEmpty()) ?: return null
        val previous = (data["previous"] as? JsonObject)?.snapshot()
        MemoryChange(
            callId = callId,
            action = if (data.text("action") == "updated" && previous != null) MemoryChangeAction.UPDATED else MemoryChangeAction.CREATED,
            id = id,
            written = written,
            previous = previous,
        )
    }
}

private fun LongTermMemory.matches(snapshot: MemorySnapshot): Boolean =
    kind == snapshot.kind && title == snapshot.title && content == snapshot.content

/**
 * 撤销只在记忆仍是这次改动留下的样子时提供，之后被再次修改的记忆不回退；
 * 已恢复到改动前的样子显示“已撤销”。
 */
internal fun memoryUndoState(change: MemoryChange, current: LongTermMemory?): MemoryUndoState = when (change.action) {
    MemoryChangeAction.CREATED -> when {
        current == null -> MemoryUndoState.UNDONE
        current.matches(change.written) -> MemoryUndoState.AVAILABLE
        else -> MemoryUndoState.UNAVAILABLE
    }
    MemoryChangeAction.UPDATED -> when {
        current == null -> MemoryUndoState.UNAVAILABLE
        current.matches(change.written) -> MemoryUndoState.AVAILABLE
        change.previous != null && current.matches(change.previous) -> MemoryUndoState.UNDONE
        else -> MemoryUndoState.UNAVAILABLE
    }
    MemoryChangeAction.FORGOTTEN -> when {
        current == null -> MemoryUndoState.AVAILABLE
        current.matches(change.written) -> MemoryUndoState.UNDONE
        else -> MemoryUndoState.UNAVAILABLE
    }
}

/** 按当前记忆状态撤销一次改动；记忆已被再次修改或已撤销时不做任何事。 */
internal suspend fun undoMemoryChange(
    change: MemoryChange,
    repository: LongTermMemoryRepository,
    now: () -> Long = System::currentTimeMillis,
) {
    val current = repository.get(change.id)
    if (memoryUndoState(change, current) != MemoryUndoState.AVAILABLE) return
    when (change.action) {
        MemoryChangeAction.CREATED -> repository.delete(change.id)
        MemoryChangeAction.UPDATED -> {
            val previous = change.previous ?: return
            repository.restore(checkNotNull(current).copy(kind = previous.kind, title = previous.title, content = previous.content))
        }
        MemoryChangeAction.FORGOTTEN -> {
            val time = now()
            repository.restore(
                LongTermMemory(
                    id = change.id,
                    kind = change.written.kind,
                    title = change.written.title,
                    content = change.written.content,
                    sourceAgentId = change.removedSource?.agentId,
                    sourceSessionId = change.removedSource?.sessionId,
                    sourceMessageId = change.removedSource?.messageId,
                    createdAt = change.removedCreatedAt ?: time,
                    updatedAt = change.removedUpdatedAt ?: time,
                ),
            )
        }
    }
}

/** 会话页提供的长期记忆当前状态与撤销入口；没有提供时步骤行不显示撤销。 */
internal interface MemoryChangeHost {
    val memories: State<Map<String, LongTermMemory>>
    fun undo(change: MemoryChange)
}

internal val LocalMemoryChangeHost = compositionLocalOf<MemoryChangeHost?> { null }

@Composable
internal fun rememberMemoryChangeHost(repository: LongTermMemoryRepository): MemoryChangeHost {
    val scope = rememberCoroutineScope()
    val memories = remember(repository) { repository.observeAll().map { list -> list.associateBy { it.id } } }
        .collectAsState(initial = emptyMap())
    return remember(repository, memories) {
        object : MemoryChangeHost {
            override val memories: State<Map<String, LongTermMemory>> = memories
            // 撤销失败（如容量已满）时保持原状，步骤行仍按当前状态显示。
            override fun undo(change: MemoryChange) {
                scope.launch { runCatching { undoMemoryChange(change, repository) } }
            }
        }
    }
}

@Composable
internal fun MemoryChangeRows(changes: List<MemoryChange>, modifier: Modifier = Modifier) {
    val host = LocalMemoryChangeHost.current
    val current = host?.memories?.value.orEmpty()
    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.CHAT_MEMORY_CHANGES)) {
        changes.forEach { change ->
            val undo = if (host == null) MemoryUndoState.UNAVAILABLE else memoryUndoState(change, current[change.id])
            MemoryChangeRow(change, undo, onUndo = { host?.undo(change) })
        }
    }
}

@Composable
private fun MemoryChangeRow(change: MemoryChange, undo: MemoryUndoState, onUndo: () -> Unit) {
    val (icon, label) = when (change.action) {
        MemoryChangeAction.CREATED -> Icons.Rounded.BookmarkAdded to R.string.chat_memory_saved
        MemoryChangeAction.UPDATED -> Icons.Rounded.EditNote to R.string.chat_memory_updated
        MemoryChangeAction.FORGOTTEN -> Icons.Rounded.BookmarkRemove to R.string.chat_memory_forgotten
    }
    val text = stringResource(label, change.written.title)
    val tone = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
    ) {
        Icon(icon, contentDescription = null, tint = tone, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = InfinityTypography.labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp),
            color = tone,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when (undo) {
            MemoryUndoState.AVAILABLE -> {
                val description = stringResource(R.string.chat_memory_undo_cd, change.written.title)
                IconButton(onClick = onUndo, modifier = Modifier.testTag(UiTags.chatMemoryUndo(change.callId))) {
                    Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = description, tint = tone, modifier = Modifier.size(20.dp))
                }
            }
            MemoryUndoState.UNDONE -> Text(
                stringResource(R.string.chat_memory_undone),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.outline,
            )
            MemoryUndoState.UNAVAILABLE -> Unit
        }
    }
}
