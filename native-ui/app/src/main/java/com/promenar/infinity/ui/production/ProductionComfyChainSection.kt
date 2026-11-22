package com.promenar.infinity.ui.production

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.common.*
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.infinityDomainColors
import com.promenar.infinity.ui.theme.keepCjkTailTogether
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

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
    val wf = workflowState.workflow
    val hostName = hosts.firstOrNull { it.id == wf.hostId }?.name ?: wf.hostId
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val scope = rememberCoroutineScope()

    var runningAction by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var replayPath by remember { mutableStateOf<String?>(null) }
    var selectedChainId by remember(wf.workflowId, shot.code) { mutableStateOf<String?>(null) }
    var selectedParentId by remember(wf.workflowId, shot.code) { mutableStateOf<String?>(null) }
    var branchPolicy by remember(wf.workflowId) {
        mutableStateOf(wf.chainSpec?.defaultBranchPolicy ?: StatefulBranchPolicy.ISOLATED_DIR)
    }
    var segmentPrompt by remember(shot.code, wf.workflowId) { mutableStateOf(defaultPrompt) }
    val busy = parentWorking || runningAction

    val matchingChains = remember(chains, wf.workflowId, shot.code) {
        val byWf = chains.filter { it.workflowId == wf.workflowId }
        val byShot = byWf.filter { it.shotRef == shot.code }
        if (byShot.isNotEmpty()) byShot else byWf
    }
    val activeChain = remember(matchingChains, selectedChainId) {
        matchingChains.firstOrNull { it.chainId == selectedChainId } ?: matchingChains.firstOrNull()
    }
    val activeParent = remember(activeChain, selectedParentId) {
        activeChain?.node(selectedParentId ?: activeChain.activeTipNodeId)
            ?.takeIf { it.stateValidity == ChainNodeValidity.ACTIVE }
            ?: activeChain?.nodes?.lastOrNull { it.stateValidity == ChainNodeValidity.ACTIVE }
    }

    fun launchChainAction(
        stage: String,
        chainId: String?,
        parentNodeId: String?,
        retryNodeId: String?,
    ) {
        val engine = executionEngine ?: return
        if (busy) return
        runningAction = true
        progressText = null
        scope.launch {
            try {
                val contentMap = buildMap<String, kotlinx.serialization.json.JsonElement> {
                    wf.contentFields().firstOrNull { it.semanticRole == FieldSemanticRole.PROMPT || it.key == "prompt" }?.let { f ->
                        if (segmentPrompt.isNotBlank()) put(f.key, JsonPrimitive(segmentPrompt))
                    }
                }
                val result = engine.execute(
                    ComfyWorkflowExecutionRequest(
                        sessionId = sessionId,
                        workflowId = wf.workflowId,
                        contentInputs = contentMap,
                        stage = stage,
                        chainId = chainId,
                        parentNodeId = parentNodeId,
                        retryNodeId = retryNodeId,
                        branchPolicy = branchPolicy,
                        shotRef = shot.code,
                        onProgress = { pct, msg ->
                            progressText = pct?.let { "${(it * 100).toInt()}% $msg" } ?: msg
                        },
                    ),
                )
                result.chainRecord?.let { rec ->
                    selectedChainId = rec.chainId
                    selectedParentId = rec.activeTipNodeId
                }
                onResultMessage(result.summary)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (e: Exception) {
                onErrorMessage(e.message ?: "ComfyUI 工作流执行失败")
            } finally {
                runningAction = false
                progressText = null
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(InfinityMotion.contentSizeSpec())
            .testTag("production_comfy_chain_section"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = domain.infoContainer.copy(alpha = 0.45f),
            border = BorderStroke(0.6.dp, domain.onInfoContainer.copy(alpha = 0.22f)),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(Icons.Rounded.AutoAwesome, null, tint = domain.onInfoContainer, modifier = Modifier.size(16.dp).padding(top = 2.dp))
                Text(
                    text = stringResource(R.string.p2_comfy_agent_hint).keepCjkTailTogether(),
                    style = MaterialTheme.typography.bodySmall,
                    color = domain.onInfoContainer,
                )
            }
        }

        // 工作流固定基线字段摘要
        val fixedFields = wf.fixedFields()
        ProductionSection(
            title = stringResource(R.string.media_settings_fields_fixed),
            trailing = hostName,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                InfinityScrollingLine(Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.p2_comfy_fixed_summary_ok),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                if (fixedFields.isNotEmpty()) {
                    InfinityTagScrollRow(spacing = 6.dp) {
                        fixedFields.forEach { field ->
                            ProductionStatusChip("${field.key}=${field.displayValue(field.defaultValue)}", ProductionTone.NEUTRAL)
                        }
                    }
                }
            }
        }

        AnimatedVisibility(visible = progressText != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            progressText?.let { msg -> InfinityStatusStrip(InfinityStatusKind.Info, msg) }
        }

        if (wf.executionMode == WorkflowExecutionMode.STATEFUL_CHAIN) {
            ProductionSection(
                title = stringResource(R.string.p2_chain_title),
                trailing = activeChain?.let { "${it.nodes.size} nodes" },
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (activeChain == null) {
                        InfinityPillButton(
                            text = stringResource(R.string.p2_chain_start_first),
                            onClick = { launchChainAction(stage = "first", chainId = null, parentNodeId = null, retryNodeId = null) },
                            enabled = !busy && executionEngine != null,
                            tone = InfinityPillTone.PRIMARY,
                            icon = Icons.Rounded.PlayArrow,
                            modifier = Modifier.fillMaxWidth().testTag("comfy_chain_start_first_btn"),
                        )
                    } else {
                        if (matchingChains.size > 1) {
                            InfinityTagScrollRow(spacing = 6.dp) {
                                matchingChains.forEach { rec ->
                                    InfinityChip(
                                        label = rec.chainId,
                                        selected = rec.chainId == activeChain.chainId,
                                        onClick = {
                                            selectedChainId = rec.chainId
                                            selectedParentId = rec.activeTipNodeId
                                        },
                                    )
                                }
                            }
                        }

                        activeChain.nodes.forEach { node ->
                            val isSelected = activeParent?.nodeId == node.nodeId
                            val isTip = activeChain.activeTipNodeId == node.nodeId
                            val canContinue = node.stateValidity == ChainNodeValidity.ACTIVE
                            ChainNodeCard(
                                node = node,
                                isTip = isTip,
                                isSelected = isSelected,
                                busy = busy,
                                canTriggerEngine = executionEngine != null,
                                indent = node.parentNodeId != null,
                                onReplay = { replayPath = node.outputMediaPath },
                                onSelectParent = { if (canContinue) selectedParentId = node.nodeId },
                                onRetryNode = {
                                    launchChainAction(
                                        stage = if (node.parentNodeId == null) "first" else "next",
                                        chainId = activeChain.chainId,
                                        parentNodeId = node.parentNodeId,
                                        retryNodeId = node.nodeId,
                                    )
                                },
                                onSetTip = {
                                    if (chainStore != null && canContinue) {
                                        scope.launch {
                                            runCatching {
                                                val updated = StatefulChainDag.setActiveTip(activeChain, node.nodeId, System.currentTimeMillis())
                                                chainStore.save(updated)
                                                selectedParentId = node.nodeId
                                            }.onFailure { err ->
                                                onErrorMessage(err.message ?: "切换末端节点失败")
                                            }
                                        }
                                    }
                                },
                            )
                        }

                        activeParent?.let { parent ->
                            val nextSeg = parent.segmentIndex + 1
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = scheme.surfaceContainerHigh,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                        Text(
                                            text = stringResource(
                                                R.string.p2_chain_branch_action_hint,
                                                parent.segmentIndex,
                                                parent.attemptLabel,
                                                nextSeg,
                                            ),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = scheme.primary,
                                            maxLines = 1,
                                            softWrap = false,
                                        )
                                    }
                                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                                        InfinityMetaLine(
                                            segments = buildList {
                                                add("parent=${parent.nodeId}")
                                                parent.continuationStateJson["latent_dir"]?.let { add("latent_dir=$it") }
                                            },
                                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                                            color = scheme.onSurfaceVariant,
                                        )
                                    }
                                    Text(stringResource(R.string.media_settings_wf_branch_policy), style = MaterialTheme.typography.labelMedium)
                                    InfinitySegmentedControl(
                                        segments = listOf(
                                            InfinitySegment(StatefulBranchPolicy.ISOLATED_DIR, stringResource(R.string.media_settings_branch_isolated)),
                                            InfinitySegment(StatefulBranchPolicy.IN_PLACE_OVERWRITE, stringResource(R.string.media_settings_branch_overwrite)),
                                        ),
                                        selected = branchPolicy,
                                        onSelect = { branchPolicy = it },
                                        fillWidth = true,
                                    )
                                    OutlinedTextField(
                                        value = segmentPrompt,
                                        onValueChange = { segmentPrompt = it },
                                        label = { Text(stringResource(R.string.media_settings_slot_prompt)) },
                                        textStyle = LocalTextStyle.current.forInput(),
                                        minLines = 2,
                                        maxLines = 4,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    InfinityPillButton(
                                        text = stringResource(
                                            R.string.p2_chain_branch_action_hint,
                                            parent.segmentIndex,
                                            parent.attemptLabel,
                                            nextSeg,
                                        ),
                                        onClick = {
                                            launchChainAction(
                                                stage = "next",
                                                chainId = activeChain.chainId,
                                                parentNodeId = parent.nodeId,
                                                retryNodeId = null,
                                            )
                                        },
                                        enabled = !busy && executionEngine != null,
                                        tone = InfinityPillTone.PRIMARY,
                                        icon = Icons.AutoMirrored.Rounded.CallSplit,
                                        modifier = Modifier.fillMaxWidth().testTag("comfy_chain_branch_submit_btn"),
                                    )
                                }
                            }
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
private fun ChainNodeCard(
    node: StatefulChainNode,
    isTip: Boolean,
    isSelected: Boolean,
    busy: Boolean,
    canTriggerEngine: Boolean,
    indent: Boolean,
    onReplay: () -> Unit,
    onSelectParent: () -> Unit,
    onRetryNode: () -> Unit,
    onSetTip: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val canContinue = node.stateValidity == ChainNodeValidity.ACTIVE
    val container = when {
        isSelected -> scheme.primaryContainer.copy(alpha = 0.35f)
        !canContinue -> scheme.surfaceContainerHighest.copy(alpha = 0.5f)
        else -> scheme.surfaceContainerLow
    }
    val border = when {
        isSelected -> BorderStroke(1.5.dp, scheme.primary)
        !canContinue -> BorderStroke(0.6.dp, domain.onWarningContainer.copy(alpha = 0.3f))
        else -> BorderStroke(0.6.dp, scheme.outlineVariant.copy(alpha = 0.6f))
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 14.dp else 0.dp)
            .then(if (canContinue) Modifier.clickable(role = Role.Button, onClick = onSelectParent) else Modifier),
        shape = RoundedCornerShape(14.dp),
        color = container,
        border = border,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    imageVector = if (indent) Icons.AutoMirrored.Rounded.CallSplit else Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = if (canContinue) scheme.primary else scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                        Text(
                            text = stringResource(R.string.p2_comfy_seg_label, node.segmentIndex, node.attemptLabel),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    InfinityScrollingLine(Modifier.fillMaxWidth()) {
                        InfinityMetaLine(
                            segments = listOfNotNull(
                                node.frames?.let { stringResource(R.string.media_settings_frames, it) },
                                node.parentNodeId?.let { "<- $it" } ?: "first",
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                }
                val (statusRes, tone) = when {
                    !canContinue && node.stateValidity == ChainNodeValidity.OVERWRITTEN_BY_SIBLING ->
                        R.string.p2_chain_status_overwritten to ProductionTone.WARNING
                    !canContinue -> R.string.p2_chain_status_expired to ProductionTone.ERROR
                    isTip -> R.string.p2_chain_status_tip to ProductionTone.INFO
                    else -> R.string.p2_chain_status_active to ProductionTone.SUCCESS
                }
                ProductionStatusChip(stringResource(statusRes), tone)
            }
            if (node.promptSummary.isNotBlank()) {
                Text(
                    text = node.promptSummary.keepCjkTailTogether(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (canContinue) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            InfinityTagScrollRow(spacing = 8.dp) {
                InfinityPillButton(
                    text = stringResource(R.string.p2_chain_replay),
                    onClick = onReplay,
                    tone = InfinityPillTone.NEUTRAL,
                    compact = true,
                    icon = Icons.Rounded.PlayArrow,
                )
                if (canContinue) {
                    InfinityPillButton(
                        text = stringResource(R.string.p2_chain_continue_from),
                        onClick = onSelectParent,
                        tone = if (isSelected) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                        compact = true,
                        icon = if (isSelected) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.CallSplit,
                    )
                    InfinityPillButton(
                        text = stringResource(R.string.p2_chain_retry_segment),
                        onClick = onRetryNode,
                        enabled = !busy && canTriggerEngine,
                        tone = InfinityPillTone.NEUTRAL,
                        compact = true,
                        icon = Icons.Rounded.Replay,
                    )
                    if (!isTip) {
                        InfinityPillButton(
                            text = stringResource(R.string.p2_chain_set_tip),
                            onClick = onSetTip,
                            enabled = !busy,
                            tone = InfinityPillTone.NEUTRAL,
                            compact = true,
                        )
                    }
                }
            }
        }
    }
}
