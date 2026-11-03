package com.promenar.infinity.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.infinity.ui.canvas.ArtifactKindIcon
import com.promenar.infinity.ui.canvas.labelRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.Text
import com.promenar.infinity.R
import com.promenar.infinity.data.canvas.CanvasMetadata
import com.promenar.infinity.data.canvas.CanvasWorkspaceSync
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.ui.canvas.CanvasCard
import com.promenar.infinity.ui.canvas.CanvasInlineCard
import com.promenar.infinity.ui.canvas.CanvasSessionAccess
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityTypography
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import com.promenar.infinity.ui.canvas.LocalCanvasSession
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import com.promenar.infinity.ui.common.InfinityMetaLine

internal data class CanvasInvocation(val id: String, val title: String, val version: Int)

internal fun canvasTimelineReferences(invocations: List<ToolInvocationPresentation>): List<CanvasInvocation> =
    invocations.mapNotNull(::canvasInvocation).asReversed().distinctBy { it.id }.asReversed()

internal fun canvasInvocation(invocation: ToolInvocationPresentation): CanvasInvocation? {
    if (invocation.name !in setOf("canvas_create", "canvas_update") || invocation.failed) return null
    return invocation.results.filter { it.type == "tool_result" }.firstNotNullOfOrNull { result ->
        val value = runCatching { Json.parseToJsonElement(result.content.orEmpty()) as? JsonObject }.getOrNull() ?: return@firstNotNullOfOrNull null
        val id = (value["canvasId"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf(String::isNotBlank) ?: return@firstNotNullOfOrNull null
        val version = (value["version"] as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull?.takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
        val title = (value["title"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull.orEmpty()
        CanvasInvocation(id, title, version)
    }
}

/**
 * 画布入口有两种位置：生成中 [inline] 为 true，紧凑卡锚定在产生它的工具步骤下方；
 * 回合结束后在正文之后汇总为“本轮产物”，独立于执行过程折叠，避免已完成轮次把画布入口一同隐藏。
 */
@Composable
internal fun CanvasInvocationCards(
    steps: List<ExecutionStep>,
    inline: Boolean = false,
    /** 本轮的消息 ID；结束后据此列出本轮写入但未收录的工作区文件。 */
    messageIds: Set<String> = emptySet(),
) {
    val access = LocalCanvasSession.current ?: return
    val invocations = remember(steps) { toolInvocations(steps) }
    val references = remember(invocations) { canvasTimelineReferences(invocations) }
    val files = remember(invocations, inline) {
        if (inline) emptyList() else invocations.mapNotNull(::artifactInvocation).asReversed().distinctBy { it.id }.asReversed()
    }
    val turnCandidates = if (inline) 0 else access.candidates.count { it.changedByMessageId in messageIds }
    if (references.isEmpty() && files.isEmpty() && turnCandidates == 0) return
    if (inline) {
        Column(modifier = Modifier.padding(top = 6.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            references.forEachIndexed { index, reference -> key(index, reference.id) { CanvasReferenceCard(access, reference, inline = true) } }
        }
        return
    }
    // 产物区跟在正文之后，与上方文字留出段落级间隔，避免顶边贴住最后一行。
    Column(
        modifier = Modifier.padding(top = 16.dp).testTag(UiTags.CANVAS_TURN_ARTIFACTS),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.canvas_turn_artifacts),
            style = InfinityTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics { heading() },
        )
        references.forEachIndexed { index, reference -> key(index, reference.id) { CanvasReferenceCard(access, reference, inline = false) } }
        files.forEach { file -> key(file.id) { FileArtifactCard(file, onOpen = { access.openFile(file.id, file.version) }) } }
        if (turnCandidates > 0) TurnCandidatesHint(turnCandidates, onView = access.openList)
    }
}

@Composable
private fun FileArtifactCard(file: ArtifactInvocation, onOpen: () -> Unit) {
    Surface(
        onClick = onOpen,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth().testTag(UiTags.artifactItem(file.id)),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ArtifactKindIcon(file.kind, size = 36.dp)
            Column(Modifier.weight(1f)) {
                Text(file.title, style = InfinityTypography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 1, overflow = TextOverflow.Ellipsis)
                InfinityMetaLine(
                    segments = listOf(stringResource(file.kind.labelRes), stringResource(R.string.artifact_meta_version, file.version)),
                    style = InfinityTypography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 本轮写入工作区却没有声明的文件数，点“查看”进入产物页的候选区。 */
@Composable
private fun TurnCandidatesHint(count: Int, onView: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onView)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(UiTags.ARTIFACT_TURN_CANDIDATES),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.FolderOpen, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            text = pluralStringResource(R.plurals.artifact_turn_candidates, count, count),
            style = InfinityTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.artifact_turn_candidates_view),
            style = InfinityTypography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun CanvasReferenceCard(access: CanvasSessionAccess, reference: CanvasInvocation, inline: Boolean) {
    val metadata = access.canvases.find { it.id == reference.id }
    var html by remember(access.sessionId, reference.id, metadata?.currentVersion) { mutableStateOf<String?>(null) }
    var failed by remember(access.sessionId, reference.id, metadata?.currentVersion) { mutableStateOf(false) }
    // 持久元数据保存逻辑路径；卡片显示按会话运行模式呈现的路径（Linux 会话为 /home/session 或 /workspace）。
    var presentedPath by remember(access.sessionId, reference.id, metadata?.currentVersion) { mutableStateOf<String?>(null) }
    LaunchedEffect(access.sessionId, reference.id, metadata?.currentVersion, access.loaded) {
        if (!access.loaded) return@LaunchedEffect
        try {
            val document = access.store.read(access.sessionId, reference.id)
            html = document?.html
            presentedPath = document?.metadata?.workspacePath
            failed = html == null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val fallback = CanvasMetadata(reference.id, reference.title, 0, 0, reference.version, "", listOf(reference.version))
    if (inline) {
        CanvasInlineCard(
            metadata = metadata ?: fallback,
            version = reference.version,
            html = html,
            onOpen = { access.openVersion(reference.id, reference.version) },
            previewFailed = failed,
        )
        return
    }
    CanvasCard(
        metadata = metadata ?: fallback,
        version = reference.version,
        html = html,
        onOpen = { access.openVersion(reference.id, reference.version) },
        previewFailed = failed,
        workspacePath = metadata?.workspacePath?.let { presentedPath ?: it },
        workspaceWarning = metadata?.workspaceSync == CanvasWorkspaceSync.FAILED,
        onOpenLatest = { access.open(reference.id) },
        onCopySource = { access.copySource(reference.id, reference.version) },
        onRevealInWorkspace = metadata
            ?.let { com.promenar.infinity.ui.canvas.canvasRevealPath(it, access.primaryWorkspaceId) }
            ?.let { path -> { access.revealInWorkspace(path) } },
    )
}
