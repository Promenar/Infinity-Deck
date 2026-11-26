package com.promenar.infinity.ui.production

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionEngine
import com.promenar.infinity.data.mediagen.comfy.StatefulChainStore
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.domain.mediagen.ChainNodeValidity
import com.promenar.infinity.domain.mediagen.ComfyWorkflowState
import com.promenar.infinity.domain.mediagen.StatefulChainNode
import com.promenar.infinity.domain.mediagen.StatefulChainRecord
import com.promenar.infinity.domain.mediagen.WorkflowExecutionMode
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityChip
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityTagScrollRow
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether

/**
 * 镜头详情页 ComfyUI 工作流检视区（匹配已确认 v1.3 只读检视原型）：
 * 仅展示链式生成分段记录与固定基线摘要，支持点按任意分段缩略图回放成片；
 * 续写、重跑、分支与锁帧编排统一由智能体在对话中管理，不在面板堆叠复杂编辑表单。
 */
@Composable
internal fun ProductionComfyChainSection(
    workflowState: ComfyWorkflowState,
    hosts: List<ComfyHost>,
    chains: List<StatefulChainRecord>,
    sessionId: String,
    shot: Shot,
    defaultPrompt: String,
    executionEngine: ComfyWorkflowExecutionEngine?,
    chainStore: StatefulChainStore?,
    parentWorking: Boolean,
    onResultMessage: (String) -> Unit,
    onErrorMessage: (String) -> Unit,
) {
    // 保留签名兼容既有调用方；纯只读检视不直接发起写入动作。
    @Suppress("UNUSED_EXPRESSION")
    listOf(sessionId, defaultPrompt, executionEngine, chainStore, parentWorking, onResultMessage, onErrorMessage)

    val wf = workflowState.workflow
    val hostName = hosts.firstOrNull { it.id == wf.hostId }?.name ?: wf.hostId
    val scheme = MaterialTheme.colorScheme
    var replayPath by remember { mutableStateOf<String?>(null) }
    var selectedChainId by remember(wf.workflowId, shot.code) { mutableStateOf<String?>(null) }

    val matchingChains = remember(chains, wf.workflowId, shot.code) {
        val byWf = chains.filter { it.workflowId == wf.workflowId }
        val byShot = byWf.filter { it.shotRef == shot.code }
        if (byShot.isNotEmpty()) byShot else byWf
    }
    val activeChain = remember(matchingChains, selectedChainId) {
        matchingChains.firstOrNull { it.chainId == selectedChainId } ?: matchingChains.firstOrNull()
    }
    val fixedFields = remember(wf.fields) { wf.fixedFields() }
    val shotAnchorSummary = remember(shot.inputs) {
        shot.inputs.firstOrNull { it.role == InputRole.ANCHOR }?.atSeconds?.let(::formatShotSeconds)
    }

    if (wf.executionMode != WorkflowExecutionMode.STATEFUL_CHAIN && fixedFields.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(InfinityMotion.contentSizeSpec())
            .testTag("production_comfy_chain_section"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (wf.executionMode == WorkflowExecutionMode.STATEFUL_CHAIN) {
            val sectionTitle = stringResource(R.string.p2_chain_readonly_title)
            val totalFrames = activeChain?.nodes?.sumOf { it.frames ?: 0 } ?: 0
            val titleSegments = buildList {
                add(sectionTitle)
                if (activeChain != null && activeChain.nodes.isNotEmpty()) {
                    add(stringResource(R.string.p2_chain_readonly_meta, activeChain.nodes.size, totalFrames))
                }
            }
            ProductionSection(
                title = sectionTitle,
                titleSegments = titleSegments,
                trailing = stringResource(R.string.p2_chain_managed_by_agent),
            ) {
                if (activeChain == null || activeChain.nodes.isEmpty()) {
                    ProductionMutedRow(stringResource(R.string.p2_chain_readonly_empty))
                } else {
                    if (matchingChains.size > 1) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 8.dp),
                        ) {
                            InfinityTagScrollRow(spacing = 6.dp) {
                                matchingChains.forEach { rec ->
                                    InfinityChip(
                                        label = rec.chainId,
                                        selected = rec.chainId == activeChain.chainId,
                                        onClick = { selectedChainId = rec.chainId },
                                    )
                                }
                            }
                        }
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f), modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal))
                    }
                    val nodesById = remember(activeChain.nodes) { activeChain.nodes.associateBy { it.nodeId } }
                    activeChain.nodes.forEachIndexed { index, node ->
                        if (index > 0) {
                            HorizontalDivider(
                                color = scheme.outlineVariant.copy(alpha = 0.4f),
                                modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                            )
                        }
                        val parentNode = node.parentNodeId?.let(nodesById::get)
                        val continuedFrom = parentNode?.let { parent ->
                            stringResource(
                                R.string.p2_chain_continued_from,
                                stringResource(R.string.p2_comfy_seg_label, parent.segmentIndex, parent.attemptLabel),
                            )
                        }
                        val guideTag = if (node.parentNodeId == null && shotAnchorSummary != null) {
                            stringResource(R.string.p2_chain_guide_summary, shotAnchorSummary)
                        } else null
                        ReadOnlyChainSegmentRow(
                            node = node,
                            isTip = activeChain.activeTipNodeId == node.nodeId,
                            continuedFrom = continuedFrom,
                            guideTag = guideTag,
                            fps = wf.fps,
                            onReplay = { replayPath = node.outputMediaPath },
                        )
                    }
                    HorizontalDivider(
                        color = scheme.outlineVariant.copy(alpha = 0.35f),
                        modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Rounded.Info, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                        Text(
                            text = stringResource(R.string.p2_chain_readonly_hint).keepCjkTailTogether(),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (fixedFields.isNotEmpty()) {
            ProductionSection(
                title = stringResource(R.string.media_settings_fields_fixed),
                trailing = hostName,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 10.dp),
                ) {
                    InfinityTagScrollRow(spacing = 6.dp) {
                        fixedFields.forEach { field ->
                            ProductionStatusChip("${field.key}=${field.displayValue(field.defaultValue)}", ProductionTone.NEUTRAL)
                        }
                    }
                }
            }
        }
    }

    replayPath?.let { path ->
        InfinityBottomSheet(show = true, onDismiss = { replayPath = null }, title = path.substringAfterLast('/')) {
            Box(Modifier.fillMaxWidth().padding(16.dp)) {
                ProductionMediaReference(path, Modifier.fillMaxWidth(), alt = shot.title)
            }
        }
    }
}

@Composable
private fun ReadOnlyChainSegmentRow(
    node: StatefulChainNode,
    isTip: Boolean,
    continuedFrom: String?,
    guideTag: String?,
    fps: Double?,
    onReplay: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val canContinue = node.stateValidity == ChainNodeValidity.ACTIVE
    val (statusRes, tone) = when {
        !canContinue && node.stateValidity == ChainNodeValidity.OVERWRITTEN_BY_SIBLING ->
            R.string.p2_chain_status_overwritten to ProductionTone.WARNING
        !canContinue -> R.string.p2_chain_status_expired to ProductionTone.ERROR
        isTip -> R.string.p2_chain_status_tip to ProductionTone.INFO
        else -> R.string.p2_chain_status_active to ProductionTone.SUCCESS
    }
    val timeSegments = buildList {
        val frames = node.frames
        if (frames != null && fps != null && fps > 0.0) {
            add(secondsLabel(frames / fps))
        }
        if (frames != null) {
            add(stringResource(R.string.media_settings_frames, frames))
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onReplay)
            .padding(horizontal = InfinitySpacing.RowHorizontal, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (continuedFrom != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Rounded.SubdirectoryArrowRight, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(15.dp))
                Text(continuedFrom, style = MaterialTheme.typography.labelSmall, color = scheme.primary)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(76.dp)
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                ProductionThumbnail(
                    path = node.outputMediaPath,
                    modifier = Modifier.width(76.dp).height(48.dp),
                    contentDescription = stringResource(R.string.p2_chain_replay),
                )
                Surface(shape = CircleShape, color = scheme.scrim.copy(alpha = 0.5f)) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(R.string.p2_chain_replay),
                        tint = MaterialTheme.infinityDomainColors.overlayContent,
                        modifier = Modifier.padding(4.dp).size(16.dp),
                    )
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.p2_comfy_seg_label, node.segmentIndex, node.attemptLabel),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    ProductionStatusChip(stringResource(statusRes), tone)
                }
                if (timeSegments.isNotEmpty()) {
                    InfinityMetaLine(
                        segments = timeSegments,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (guideTag != null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Rounded.PushPin, contentDescription = null, tint = domain.onInfoContainer, modifier = Modifier.size(12.dp))
                        Text(guideTag, style = MaterialTheme.typography.labelSmall, color = domain.onInfoContainer)
                    }
                }
                if (node.promptSummary.isNotBlank()) {
                    Text(
                        text = node.promptSummary.keepCjkTailTogether(),
                        style = MaterialTheme.typography.bodySmall.forReading(),
                        color = scheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
