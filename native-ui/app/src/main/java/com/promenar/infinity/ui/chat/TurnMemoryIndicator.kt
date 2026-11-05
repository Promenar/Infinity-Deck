package com.promenar.infinity.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import com.promenar.infinity.ui.theme.forReading
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.TurnMemoryState
import com.promenar.infinity.data.rag.TurnGraphDetails
import com.promenar.infinity.data.rag.TurnGraphState
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import kotlinx.coroutines.CancellationException

internal const val TURN_MEMORY_STATUS_TAG = "CHAT_TURN_MEMORY_STATUS"
internal const val TURN_MEMORY_SHEET_TAG = "CHAT_TURN_MEMORY_SHEET"
internal const val TURN_MEMORY_REMOVE_TAG = "CHAT_TURN_MEMORY_REMOVE"
internal const val TURN_MEMORY_REWRITE_TAG = "CHAT_TURN_MEMORY_REWRITE"
internal const val TURN_GRAPH_RETRY_TAG = "CHAT_TURN_GRAPH_RETRY"
internal const val TURN_GRAPH_OPEN_TAG = "CHAT_TURN_GRAPH_OPEN"

internal fun TurnMemoryState.Status.labelRes(): Int = when (this) {
    TurnMemoryState.Status.WRITING -> R.string.turn_memory_status_writing
    TurnMemoryState.Status.STORED -> R.string.turn_memory_status_stored
    TurnMemoryState.Status.FAILED -> R.string.turn_memory_status_failed
    TurnMemoryState.Status.REMOVED -> R.string.turn_memory_status_removed
}

@Composable
internal fun TurnGraphState.label(): String = when (status) {
    TurnGraphState.Status.QUEUED -> stringResource(R.string.turn_graph_status_queued)
    TurnGraphState.Status.RUNNING -> stringResource(R.string.turn_graph_status_running)
    TurnGraphState.Status.DONE -> stringResource(R.string.turn_graph_status_done, entities, relations)
    TurnGraphState.Status.PARTIAL -> stringResource(R.string.turn_graph_status_partial, entities)
    TurnGraphState.Status.FAILED -> stringResource(R.string.turn_graph_status_failed)
}

/** 页脚显示的阶段：写入中、记忆失败或已移除优先；记入之后进入图谱抽取的各阶段。 */
internal data class TurnFooterStatus(val memory: TurnMemoryState?, val graph: TurnGraphState?) {
    val showsGraph: Boolean
        get() = graph != null && memory?.status.let {
            it == null || it == TurnMemoryState.Status.STORED
        }
    val failed: Boolean
        get() = if (showsGraph) graph?.status == TurnGraphState.Status.FAILED else memory?.status == TurnMemoryState.Status.FAILED
    val dimmed: Boolean
        get() = if (showsGraph) {
            graph?.status == TurnGraphState.Status.QUEUED || graph?.status == TurnGraphState.Status.RUNNING
        } else memory?.status == TurnMemoryState.Status.WRITING || memory?.status == TurnMemoryState.Status.REMOVED
    val isEmpty: Boolean get() = memory == null && graph == null
}

@Composable
internal fun TurnFooterStatus.label(): String? =
    if (showsGraph) graph?.label() else memory?.let { stringResource(it.status.labelRes()) }

/** 回复底部模型名之前的记忆写入状态；点按打开“本轮记忆”面板。 */
@Composable
internal fun TurnMemoryStatusLabel(state: TurnMemoryState, onClick: () -> Unit, modifier: Modifier = Modifier, showText: Boolean = true) =
    TurnMemoryStatusLabel(TurnFooterStatus(state, null), onClick, modifier, showText)

/** 回复底部模型名之前的记忆与图谱状态；点按打开“本轮记忆”面板。 */
@Composable
internal fun TurnMemoryStatusLabel(state: TurnFooterStatus, onClick: () -> Unit, modifier: Modifier = Modifier, showText: Boolean = true) {
    val label = state.label() ?: return
    val failed = state.failed
    val tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    val dimmed = state.dimmed
    Row(
        modifier = modifier
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.turn_memory_open), onClick = onClick)
            .padding(horizontal = 4.dp)
            .alpha(if (dimmed) 0.6f else 1f)
            .testTag(TURN_MEMORY_STATUS_TAG),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = when {
                failed -> Icons.Rounded.ErrorOutline
                state.showsGraph -> Icons.Rounded.Hub
                else -> Icons.Rounded.History
            },
            contentDescription = if (showText) null else label,
            tint = tint,
            modifier = Modifier.size(13.dp),
        )
        if (showText) {
            Text(
                text = label,
                style = InfinityTypography.labelSmall,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * “本轮记忆”面板：写入状态、本轮记忆可被哪些会话检索、本轮写入的片段，以及移除或重新记入。
 * 移除只删除记忆片段，聊天记录保留。
 */
@Composable
internal fun TurnMemorySheet(
    state: TurnMemoryState?,
    loadDetails: suspend () -> TurnMemoryDetails,
    onRemove: () -> Unit,
    onRewrite: () -> Unit,
    onDismiss: () -> Unit,
    graph: TurnGraphState? = null,
    loadGraph: suspend () -> TurnGraphDetails = { TurnGraphDetails(emptyList(), emptyList()) },
    onRetryGraph: () -> Unit = {},
    onOpenGraph: ((String) -> Unit)? = null,
    /** 宽窗口下贴着点按的记忆状态弹出。 */
    popover: com.promenar.infinity.ui.common.InfinityPopoverSpec? = null,
) {
    var details by remember { mutableStateOf<TurnMemoryDetails?>(null) }
    LaunchedEffect(state?.status, state?.updatedAt) {
        if (state == null) return@LaunchedEffect
        details = try {
            loadDetails()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TurnMemoryDetails(emptyList(), shareWithOtherAgents = true)
        }
    }
    var graphDetails by remember { mutableStateOf<TurnGraphDetails?>(null) }
    LaunchedEffect(graph) {
        if (graph == null) return@LaunchedEffect
        graphDetails = try {
            loadGraph()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            TurnGraphDetails(emptyList(), emptyList())
        }
    }
    val title = stringResource(if (state == null) R.string.turn_graph_title else R.string.turn_memory_title)
    InfinityBottomSheet(show = true, onDismiss = onDismiss, title = title, scrollContent = true, popover = popover) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (state != null) TurnMemorySheetContent(state, details, onRemove, onRewrite)
            if (graph != null) TurnGraphSheetContent(graph, graphDetails, onRetryGraph, onOpenGraph, showTitle = state != null)
        }
    }
}

/** 本轮抽取的实体与关系，抽取失败或未完成时提供重新抽取。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TurnGraphSheetContent(
    state: TurnGraphState,
    details: TurnGraphDetails?,
    onRetry: () -> Unit,
    onOpenGraph: ((String) -> Unit)?,
    showTitle: Boolean = true,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (showTitle) SectionTitle(stringResource(R.string.turn_graph_title))
        val failed = state.status == TurnGraphState.Status.FAILED
        Text(
            text = if (failed) stringResource(R.string.turn_graph_failed_title) else turnGraphSheetHeadline(state),
            style = MaterialTheme.typography.titleSmall,
            color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        when (state.status) {
            TurnGraphState.Status.QUEUED, TurnGraphState.Status.RUNNING -> Hint(stringResource(R.string.turn_graph_pending_hint))
            TurnGraphState.Status.PARTIAL -> Hint(stringResource(R.string.turn_graph_partial_hint))
            TurnGraphState.Status.FAILED -> state.error?.takeIf { it.isNotBlank() }?.let { error ->
                Hint(stringResource(R.string.turn_graph_failed_hint, error.take(200)))
            }
            TurnGraphState.Status.DONE -> Unit
        }
        val entities = details?.entities.orEmpty()
        val relations = details?.relations.orEmpty()
        if (state.status == TurnGraphState.Status.DONE && details != null && entities.isEmpty() && relations.isEmpty()) {
            Hint(stringResource(R.string.turn_graph_empty))
        }
        if (entities.isNotEmpty()) {
            SubsectionTitle(stringResource(R.string.turn_graph_entities_title))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                entities.forEach { entity ->
                    Surface(shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Text(
                            entity.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        if (relations.isNotEmpty()) {
            SubsectionTitle(stringResource(R.string.turn_graph_relations_title))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val emphasis = MaterialTheme.colorScheme.onSurface
                val muted = MaterialTheme.colorScheme.onSurfaceVariant
                relations.forEach { relation ->
                    Text(
                        text = buildAnnotatedString {
                            withStyle(SpanStyle(color = emphasis, fontWeight = FontWeight.Medium)) { append(relation.source) }
                            withStyle(SpanStyle(color = muted)) { append("  ${readableRelation(relation.relation)}  →  ") }
                            withStyle(SpanStyle(color = emphasis, fontWeight = FontWeight.Medium)) { append(relation.target) }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val focusEntity = details?.focusEntity
        if (onOpenGraph != null && focusEntity != null) {
            InfinitySettingsItem(
                icon = Icons.Rounded.Hub,
                title = stringResource(R.string.turn_graph_open_graph),
                horizontalPadding = 0.dp,
                onClick = { onOpenGraph(focusEntity) },
                modifier = Modifier.testTag(TURN_GRAPH_OPEN_TAG),
            )
        }
        if (failed || state.status == TurnGraphState.Status.PARTIAL) {
            InfinitySettingsItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(R.string.turn_graph_retry),
                horizontalPadding = 0.dp,
                onClick = onRetry,
                modifier = Modifier.testTag(TURN_GRAPH_RETRY_TAG),
            )
        }
    }
}

/** 面板标题行用完整句子；页脚的“图谱 +N 实体”只适合紧凑状态行。 */
@Composable
private fun turnGraphSheetHeadline(state: TurnGraphState): String {
    val entities = pluralStringResource(R.plurals.turn_graph_sheet_entities, state.entities, state.entities)
    return when (state.status) {
        TurnGraphState.Status.DONE -> stringResource(
            R.string.turn_graph_sheet_done,
            entities,
            pluralStringResource(R.plurals.turn_graph_sheet_relations, state.relations, state.relations),
        )
        TurnGraphState.Status.PARTIAL -> stringResource(R.string.turn_graph_sheet_partial, entities)
        else -> state.label()
    }
}

/** 抽取模型常给出 `developed_by` 这类谓词，展示时换成空格分隔。 */
internal fun readableRelation(relation: String): String = relation.replace('_', ' ').trim()

@Composable
internal fun TurnMemorySheetContent(
    state: TurnMemoryState,
    details: TurnMemoryDetails?,
    onRemove: () -> Unit,
    onRewrite: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag(TURN_MEMORY_SHEET_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(state.status.labelRes()),
                style = MaterialTheme.typography.titleSmall,
                color = if (state.status == TurnMemoryState.Status.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            when (state.status) {
                TurnMemoryState.Status.WRITING -> Hint(stringResource(R.string.turn_memory_writing_hint))
                TurnMemoryState.Status.REMOVED -> Hint(stringResource(R.string.turn_memory_removed_hint))
                TurnMemoryState.Status.FAILED -> Hint(stringResource(R.string.turn_memory_failed_hint))
                TurnMemoryState.Status.STORED -> if (state.chunkCount > 0) {
                    Hint(pluralStringResource(R.plurals.turn_memory_chunk_count, state.chunkCount, state.chunkCount))
                }
            }
        }

        SectionTitle(stringResource(R.string.turn_memory_visibility_title))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.shapes.medium)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            VisibilityRow(stringResource(R.string.turn_memory_visibility_label_session), stringResource(R.string.turn_memory_visibility_session))
            VisibilityRow(stringResource(R.string.turn_memory_visibility_label_agent), stringResource(R.string.turn_memory_visibility_agent))
            VisibilityRow(
                stringResource(R.string.turn_memory_visibility_label_others),
                stringResource(R.string.turn_memory_visibility_shared),
            )
        }

        val chunks = details?.chunks.orEmpty()
        if (state.status == TurnMemoryState.Status.STORED && chunks.isNotEmpty()) {
            SectionTitle(stringResource(R.string.turn_memory_chunks_title))
            val userLabel = stringResource(R.string.turn_memory_role_user)
            val assistantLabel = stringResource(R.string.turn_memory_role_assistant)
            val roleColor = MaterialTheme.colorScheme.primary
            chunks.forEach { chunk ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainer,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = remember(chunk, userLabel, assistantLabel, roleColor) {
                            buildAnnotatedString {
                                memoryChunkParts(chunk).forEachIndexed { index, part ->
                                    if (index > 0) append("\n")
                                    part.role?.let { role ->
                                        withStyle(SpanStyle(color = roleColor, fontWeight = FontWeight.Medium)) {
                                            append(if (role == MemoryChunkRole.USER) userLabel else assistantLabel)
                                        }
                                        append("  ")
                                    }
                                    append(part.text)
                                }
                            }
                        },
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }

        when (state.status) {
            TurnMemoryState.Status.STORED -> InfinitySettingsItem(
                icon = Icons.Rounded.DeleteOutline,
                title = stringResource(R.string.turn_memory_remove),
                subtitle = stringResource(R.string.turn_memory_remove_hint),
                titleColor = MaterialTheme.colorScheme.error,
                horizontalPadding = 0.dp,
                onClick = onRemove,
                modifier = Modifier.testTag(TURN_MEMORY_REMOVE_TAG),
            )
            TurnMemoryState.Status.REMOVED, TurnMemoryState.Status.FAILED -> InfinitySettingsItem(
                icon = Icons.Rounded.Refresh,
                title = stringResource(if (state.status == TurnMemoryState.Status.FAILED) R.string.turn_memory_retry else R.string.turn_memory_readd),
                horizontalPadding = 0.dp,
                onClick = onRewrite,
                modifier = Modifier.testTag(TURN_MEMORY_REWRITE_TAG),
            )
            TurnMemoryState.Status.WRITING -> Unit
        }
    }
}

internal enum class MemoryChunkRole { USER, ASSISTANT }

internal data class MemoryChunkPart(val role: MemoryChunkRole?, val text: String)

/** 记忆片段按“User: / Assistant:”行首标记拆成发言；片段从半句开始时，开头部分没有角色。 */
internal fun memoryChunkParts(chunk: String): List<MemoryChunkPart> {
    val parts = mutableListOf<MemoryChunkPart>()
    var role: MemoryChunkRole? = null
    val buffer = StringBuilder()
    fun flush() {
        val text = buffer.toString().trim()
        if (text.isNotEmpty()) parts += MemoryChunkPart(role, text)
        buffer.clear()
    }
    chunk.lineSequence().forEach { line ->
        val marker = MEMORY_ROLE_MARKERS.entries.firstOrNull { line.startsWith(it.key) }
        if (marker != null) {
            flush()
            role = marker.value
            buffer.append(line.removePrefix(marker.key))
        } else {
            if (buffer.isNotEmpty()) buffer.append('\n')
            buffer.append(line)
        }
    }
    flush()
    return parts
}

private val MEMORY_ROLE_MARKERS = linkedMapOf("User:" to MemoryChunkRole.USER, "Assistant:" to MemoryChunkRole.ASSISTANT)

@Composable
private fun VisibilityRow(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(VisibilityLabelWidth).padding(top = 1.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall.forReading(), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
    }
}

private val VisibilityLabelWidth = 96.dp

@Composable
private fun SectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun SubsectionTitle(text: String) {
    Text(text = text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
