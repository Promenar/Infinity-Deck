package com.promenar.infinity.ui.chat


import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.animation.*
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.theme.forReading
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.Citation
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.PhaseStatus
import com.promenar.infinity.data.model.PostProcessStatus
import com.promenar.infinity.data.model.PostProcessTask
import com.promenar.infinity.data.model.PostProcessType
import com.promenar.infinity.data.model.RagMetadata
import com.promenar.infinity.data.model.RagPhase
import com.promenar.infinity.data.model.RagProgress
import com.promenar.infinity.data.model.RagReference
import com.promenar.infinity.data.model.RagRetrievalTrace
import com.promenar.infinity.domain.jobs.SftpTransferDirection
import com.promenar.infinity.domain.tool.CommandToolBackend
import com.promenar.infinity.domain.tool.CommandToolClassification
import com.promenar.infinity.domain.tool.ToolExecutionTargets
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.infinity.ui.chat.components.RagDetailsSheet
import com.promenar.infinity.ui.chat.components.ragScopeSegment
import com.promenar.infinity.ui.chat.components.ragSourceBreakdown
import com.promenar.infinity.ui.chat.components.resolve
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.InfinityElevation
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors

/**
 * Summary Indicator: A pulsing pill for high-level status (e.g., "Analyzing document structure...")
 */
@Composable
fun SummaryIndicator(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .wrapContentWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1000),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .alpha(alpha)
                    .background(MaterialTheme.colorScheme.primary)
            )
            Text(
                text = text,
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Deprecated("Use RagProgressCard instead", ReplaceWith("RagProgressCard"))
@Composable
fun RagOmniIndicator(
    progress: RagProgress?,
    metadata: RagMetadata?,
    references: List<RagReference>?,
    kgPaths: List<KgPath>? = null,
    isLoading: Boolean
) {
    var showDetailsSheet by remember { mutableStateOf(false) }

    val showProgress = when {
        isLoading -> true
        progress == null -> false
        (progress.percentage ?: 0) < 100 -> true
        else -> false
    }

    if (!showProgress && (references == null || references.isEmpty())) return

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = (!references.isNullOrEmpty() || !kgPaths.isNullOrEmpty())) { showDetailsSheet = true },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Rounded.CloudSync,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.chat_rag_knowledge_retrieval),
                        style = InfinityTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (showProgress) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))
                    ) {
                        Text(
                            text = stringResource(R.string.chat_rag_active),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = InfinityTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (showProgress) {
                val percentage = progress?.percentage ?: if (isLoading) 30 else 0
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = progress?.stage ?: stringResource(R.string.chat_rag_scanning),
                            style = InfinityTypography.labelSmall.copy(fontSize = 11.sp, fontFamily = InfinityMonospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "$percentage%",
                            style = InfinityTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    ) {
                        val animatedProgress by animateFloatAsState(
                            targetValue = percentage / 100f,
                            animationSpec = tween(500),
                            label = "rag_progress"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(animatedProgress)
                                .fillMaxHeight()
                                .background(
                                    Brush.horizontalGradient(
                                        colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                                    )
                                )
                        )
                    }
                }
            }

            // References
            if (references != null && references.isNotEmpty()) {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 4.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(references) { ref ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.6f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(10.dp))
                                Text(
                                    text = ref.source.substringAfterLast("/"),
                                    style = InfinityTypography.labelSmall.copy(fontSize = 10.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDetailsSheet) {
        RagDetailsSheet(
            references = references,
            kgPaths = kgPaths,
            onDismissRequest = { showDetailsSheet = false }
        )
    }
}

@Composable
fun RagProgressCard(
    phases: List<RagPhase>,
    references: List<RagReference>?,
    kgPaths: List<KgPath>? = null,
    citations: List<Citation>? = null,
    isComplete: Boolean,
    modifier: Modifier = Modifier,
    trace: RagRetrievalTrace? = null,
) {
    var showDetailsSheet by remember { mutableStateOf(false) }

    val summary = ragInlineSummary(
        phases = phases,
        referenceCount = references.orEmpty().size,
        knowledgePathCount = kgPaths.orEmpty().size,
        citationCount = citations.orEmpty().size,
        isComplete = isComplete,
        trace = trace,
    )
    val activePhase = phases.find { it.status == PhaseStatus.ACTIVE }
    val ready = summary.status != RagInlineStatus.RUNNING
    val progress = if (ready) 1f else (activePhase?.progress ?: 0).coerceIn(0, 100) / 100f
    val webOnly = summary.source == RagInlineSource.WEB
    val summaryText = stringResource(
        when (summary.status) {
            RagInlineStatus.RUNNING -> if (webOnly) R.string.chat_inline_retrieval_web_running else R.string.chat_inline_retrieval_knowledge_running
            RagInlineStatus.COMPLETED -> if (webOnly) R.string.chat_inline_retrieval_web_completed else R.string.chat_inline_retrieval_knowledge_completed
            RagInlineStatus.FAILED -> if (webOnly) R.string.chat_inline_retrieval_web_failed else R.string.chat_inline_retrieval_knowledge_failed
        },
    )
    val scopeSegment = if (ready) ragScopeSegment(trace)?.resolve() else null
    val segments = buildList {
        add(summaryText)
        summary.resultCount?.let { count ->
            add(pluralStringResource(R.plurals.chat_inline_retrieval_result_count, count, count))
        }
        scopeSegment?.let(::add)
    }
    val breakdown = if (ready) ragSourceBreakdown(references.orEmpty(), kgPaths.orEmpty().size, trace).map { it.resolve() } else emptyList()
    val partialFailureText = if (summary.partialFailure) {
        stringResource(R.string.chat_inline_retrieval_partial_failure)
    } else null
    val openDetailsLabel = stringResource(R.string.chat_rag_open_details)
    Column(modifier) {
        CompactStepRow(
            segments = segments,
            icon = Icons.Rounded.Search,
            active = !ready,
            progress = if (ready) null else progress,
            tone = if (summary.status == RagInlineStatus.FAILED) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            state = partialFailureText ?: if (summary.status == RagInlineStatus.COMPLETED) stringResource(R.string.chat_rag_done) else summaryText,
            clickLabel = openDetailsLabel,
            tag = UiTags.RAG_PROGRESS_CARD,
            onClick = { showDetailsSheet = true },
            failureText = partialFailureText,
            leading = {
                AnimatedVisibility(
                    visible = !ready,
                    enter = fadeIn(tween(160)) + expandHorizontally(tween(160)),
                    exit = fadeOut(tween(200)) + shrinkHorizontally(tween(200)),
                ) {
                    RetrievalScanIcon(tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f))
                }
            },
        )
        if (breakdown.isNotEmpty()) {
            InfinityMetaLine(
                segments = breakdown,
                style = InfinityTypography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.padding(bottom = 4.dp).testTag(UiTags.RAG_SOURCE_BREAKDOWN),
            )
        }
    }

    if (showDetailsSheet) {
        RagDetailsSheet(
            references = references,
            kgPaths = kgPaths,
            citations = citations,
            trace = trace,
            onDismissRequest = { showDetailsSheet = false }
        )
    }
}

@Composable
fun ToolExecutionTimeline(
    steps: List<ExecutionStep>,
    isExecuting: Boolean = false
) {
    if (steps.isEmpty()) return

    Surface(
        modifier = Modifier
            .fillMaxWidth(0.85f)
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Terminal,
                        null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = stringResource(R.string.chat_tool_execution_pipeline),
                        style = InfinityTypography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isExecuting) stringResource(R.string.chat_tool_pipeline_running) else stringResource(R.string.chat_tool_pipeline_completed),
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Column(
                modifier = Modifier.padding(start = 12.dp)
            ) {
                steps.forEachIndexed { index, step ->
                    TimelineStep(
                        step = step,
                        isLast = index == steps.size - 1,
                        isActive = isExecuting && (index == steps.size - 1)
                    )
                }
            }
        }
    }
}

@Composable
private fun TimelineStep(
    step: ExecutionStep,
    isLast: Boolean,
    isActive: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = if (isLast) 0.dp else 20.dp)
    ) {
        // Vertical Line & Dot
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(14.dp)
        ) {
            val dotColor = if (isActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
            val dotAlpha by if (isActive) {
                rememberInfiniteTransition(label = "dot_pulse").animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                    label = "alpha"
                )
            } else {
                remember { mutableStateOf(1f) }
            }

            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .alpha(dotAlpha)
                    .background(dotColor)
                    .border(2.dp, MaterialTheme.colorScheme.surfaceContainerLow, CircleShape)
            )
            
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                )
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.toolName ?: step.type,
                style = InfinityTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
            )
            
            if (!step.toolArgs.isNullOrBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.6f),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.padding(vertical = 6.dp),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                ) {
                    Text(
                        text = step.toolArgs,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace, fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isActive) stringResource(R.string.chat_tool_running) else stringResource(R.string.chat_tool_completed),
                    style = InfinityTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Medium),
                    color = if (isActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )
                
            }
        }
    }
}

@Composable
fun PostProcessBar(
    tasks: List<PostProcessTask>,
    onRemoveTask: (String) -> Unit
) {
    if (tasks.isEmpty()) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tasks.forEach { task ->
            PostProcessChip(
                task = task,
                onRemove = { onRemoveTask(task.id) }
            )
        }
    }
}

@Composable
fun PostProcessChip(
    task: PostProcessTask,
    onRemove: () -> Unit
) {
    val label = when (task.type) {
        PostProcessType.ARCHIVE_TO_RAG -> stringResource(R.string.chat_postprocess_memory)
        PostProcessType.AUTO_SUMMARY -> stringResource(R.string.chat_postprocess_summary)
    }

    val iconColor = when (task.status) {
        PostProcessStatus.RUNNING -> MaterialTheme.colorScheme.primary
        PostProcessStatus.DONE -> MaterialTheme.colorScheme.primary
        PostProcessStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    val icon = when (task.status) {
        PostProcessStatus.RUNNING -> Icons.Rounded.Sync
        PostProcessStatus.DONE -> Icons.Rounded.CheckCircle
        PostProcessStatus.ERROR -> Icons.Rounded.Error
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (task.status == PostProcessStatus.RUNNING) {
                val infiniteTransition = rememberInfiniteTransition(label = "pp_pulse_${task.id}")
                val alpha by infiniteTransition.animateFloat(
                    initialValue = 0.4f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(800),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "pp_alpha_${task.id}"
                )
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier
                        .size(12.dp)
                        .alpha(alpha)
                )
            } else {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(12.dp)
                )
            }

            Text(
                text = label,
                style = InfinityTypography.labelSmall.copy(fontSize = 10.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    if (task.status == PostProcessStatus.DONE) {
        LaunchedEffect(task.id) {
            kotlinx.coroutines.delay(3000)
            onRemove()
        }
    }
}

@Composable
fun SummaryCard(
    isCompressing: Boolean,
    progress: Float,
    detail: String,
    result: String?,
    modifier: Modifier = Modifier
) {
    var isExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = InfinitySpacing.XSmall),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = InfinityElevation.Level0
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (isCompressing) {
                        val infiniteTransition = rememberInfiniteTransition(label = "summary_pulse")
                        val alpha by infiniteTransition.animateFloat(
                            initialValue = 0.4f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(800),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "summary_alpha"
                        )
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .alpha(alpha)
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    } else {
                        Icon(
                            Icons.Rounded.CheckCircle,
                            null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.chat_summary_card_title),
                        style = InfinityTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = if (isCompressing) stringResource(R.string.chat_summary_card_compressing) else stringResource(R.string.chat_summary_card_done),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = InfinityTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (isCompressing) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = detail,
                        style = InfinityTypography.labelSmall.copy(fontSize = 11.sp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = InfinityTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    val animatedProgress by animateFloatAsState(
                        targetValue = progress,
                        animationSpec = tween(500),
                        label = "summary_progress"
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(animatedProgress)
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary)
                                )
                            )
                    )
                }
            }

            if (result != null) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { isExpanded = !isExpanded }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (isExpanded) stringResource(R.string.chat_summary_card_collapse) else stringResource(R.string.chat_summary_card_expand),
                        style = InfinityTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Icon(
                        if (isExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }

                AnimatedVisibility(
                    visible = isExpanded,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = result,
                            style = InfinityTypography.bodyMedium.copy(fontSize = 15.sp).forReading(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }
        }
    }
}

data class ApprovalDisplayCall(
    val name: String,
    val argumentsSummary: String,
    val riskLabel: String,
    val preview: ApprovalPreview.Preview? = null,
    val executionTarget: String? = null,
    val highRisk: Boolean = false,
    /** 文件写入类工具的差异预览；为空时使用 [preview] 的文本片段。 */
    val diffPreview: com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview? = null,
    /** schedule_create 的名称、触发规则与接下来几次时间；存在时替代参数文本。 */
    val schedule: ScheduleApprovalPreview? = null,
    /** 命令以后台任务启动时的运行方式。 */
    val background: BackgroundRunPreview? = null,
    /** 知识库访问授权的申请原因与挂载目标；存在时替代参数文本与通用类别说明。 */
    val knowledgeAccess: KnowledgeAccessApproval? = null,
    /** sftp_upload / sftp_download 的方向与两端路径；存在时替代参数文本。 */
    val transfer: SftpTransferApprovalPreview? = null,
)

@Composable
private fun approvalPreviewTitle(preview: ApprovalPreview.Preview, displayedTarget: String? = null): String {
    val action = stringResource(
        when (preview.action) {
            ApprovalPreview.Action.OVERWRITE -> R.string.chat_approval_preview_overwrite
            ApprovalPreview.Action.CREATE_FILE -> R.string.chat_approval_preview_create_file
            ApprovalPreview.Action.EDIT -> R.string.chat_approval_preview_edit
            ApprovalPreview.Action.MOVE -> R.string.chat_approval_preview_move
            ApprovalPreview.Action.DELETE -> R.string.chat_approval_preview_delete
            ApprovalPreview.Action.CREATE_DIRECTORY -> R.string.chat_approval_preview_create_directory
            ApprovalPreview.Action.SCRIPT -> R.string.chat_approval_preview_script
        },
    )
    return if (preview.target.isBlank() || preview.target == displayedTarget) action else "$action  ${preview.target}"
}

@Composable
private fun approvalPreviewDetail(preview: ApprovalPreview.Preview, detail: String): String =
    localizeApprovalPreviewDetail(
        preview = preview,
        detail = detail,
        irreversibleWarning = stringResource(R.string.agent_panels_irreversible_warning),
        networkAllowed = stringResource(R.string.agent_panels_network_allowed),
    )

/** 只本地化格式化器生成的完整静态提示，目标与动态预览正文保持原文。 */
internal fun localizeApprovalPreviewDetail(
    preview: ApprovalPreview.Preview,
    detail: String,
    irreversibleWarning: String,
    networkAllowed: String,
): String = when {
    preview.action == ApprovalPreview.Action.DELETE && detail == ApprovalPreview.IRREVERSIBLE_WARNING ->
        irreversibleWarning
    preview.action == ApprovalPreview.Action.SCRIPT && preview.scriptAllowsNetwork && detail == ApprovalPreview.NETWORK_ALLOWED ->
        networkAllowed
    preview.action == ApprovalPreview.Action.SCRIPT && preview.scriptAllowsNetwork && detail.startsWith(ApprovalPreview.NETWORK_ALLOWED + "\n") ->
        networkAllowed + detail.removePrefix(ApprovalPreview.NETWORK_ALLOWED)
    else -> detail
}

private val approvalTechnicalLine = Regex("^(?:Execution mode|Arguments?|Parameters?|参数)\\s*[:：]", RegexOption.IGNORE_CASE)
private val approvalRiskStatement = Regex(
    "不可(?:恢复|撤销|逆)|高风险|(?:允许|可)联网|会(?:修改|删除|覆盖)|" +
        "irreversible|cannot be undone|high[- ]risk|network (?:access|enabled)|" +
        "(?:will|can) (?:modify|delete|overwrite|remove)|access(?:es)? the network",
    RegexOption.IGNORE_CASE,
)

private fun approvalExplanationLines(description: String): List<String> = description.lineSequence()
    .map(String::trim).filter { it.isNotBlank() && !approvalTechnicalLine.containsMatchIn(it) }.toList()

internal fun approvalRequestReason(description: String): String = approvalExplanationLines(description)
    .filterNot(approvalRiskStatement::containsMatchIn).joinToString("\n")

/** 结构化风险事实在前，说明文字中的风险句在后；去重后逐条展示。 */
internal fun approvalRiskLines(description: String, facts: List<String>): List<String> =
    (facts + approvalExplanationLines(description).filter(approvalRiskStatement::containsMatchIn))
        .map(String::trim).filter(String::isNotBlank).distinct()

/** 审批标题优先给出具体动作；没有专门名称时回落到工具类别名。 */
@Composable
internal fun approvalToolTitle(name: String): String = when {
    name == "python_sandbox" || name == "python_exec" -> stringResource(R.string.chat_approval_title_python)
    name == "exec_js" -> stringResource(R.string.chat_approval_title_js)
    name == "ssh_host_save" -> stringResource(R.string.chat_approval_title_ssh_host_save)
    name == "ssh_host_delete" -> stringResource(R.string.chat_approval_title_ssh_host_delete)
    name == "ssh_host_install_key" -> stringResource(R.string.chat_approval_title_ssh_install_key)
    name.startsWith("mcp__") -> stringResource(R.string.chat_approval_title_mcp)
    name == REQUEST_KNOWLEDGE_ACCESS_TOOL -> stringResource(R.string.chat_approval_title_knowledge_access)
    else -> toolFriendlyName(name)
}

/**
 * 没有授权目标、但写入位置固定的工具在元信息行显示的位置。仅用于显示，不参与授权比较或“始终允许”。
 * 生图结果总是保存到默认媒体目录（`/media/<日期>/`），与 `media_fetch` 未指定路径时的显示一致。
 */
internal fun approvalFixedWriteTarget(name: String): String? = when (name) {
    "generate_image" -> "/media"
    else -> null
}

/** 元信息行的目标：与工具 ID 相同（如隔离脚本的执行范围就是工具本身）时不再重复。 */
internal fun approvalHeaderTarget(name: String, target: String?): String? =
    target?.takeIf { it.isNotBlank() && !it.equals(name, ignoreCase = true) }

/**
 * 审批卡顶部类别条：图标、类别（命令或内置工具）、执行位置与风险标签。
 * 命令用主色淡底提示“能力更大”，内置工具用中性底；风险标签统一用琥珀色小标签，不使用红色色块。
 */
@Composable
internal fun ApprovalKindBand(
    toolName: String,
    where: String?,
    riskLabel: String,
    modifier: Modifier = Modifier,
    /** 完整授权目标；Linux 会话的命令据此显示为 Linux 命令。 */
    executionTarget: String? = null,
) {
    val backend = CommandToolClassification.backendFor(toolName, ToolExecutionTargets.runsInLinux(executionTarget))
    val label = approvalKindLabel(toolName, executionTarget)
    val transfer = sftpTransferDirection(toolName) != null
    val scheme = MaterialTheme.colorScheme
    val accent = if (backend != null) scheme.primary else scheme.onSurfaceVariant
    val band = if (backend != null) scheme.primary.copy(alpha = 0.10f).compositeOver(scheme.surfaceContainerLow) else scheme.surfaceContainerHigh
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(band)
            .heightIn(min = 40.dp)
            .padding(start = 14.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
            .testTag(if (backend != null) UiTags.CHAT_APPROVAL_KIND_COMMAND else UiTags.CHAT_APPROVAL_KIND_TOOL),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when {
                transfer -> Icons.Rounded.ImportExport
                backend != null -> Icons.Rounded.Terminal
                toolName == REQUEST_KNOWLEDGE_ACCESS_TOOL -> Icons.Rounded.Key
                else -> Icons.Rounded.Extension
            },
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (backend != null) accent else scheme.onSurface,
                maxLines = 1,
            )
            if (!where.isNullOrBlank()) {
                Text(
                    text = " $InfinityMetaSeparator $where",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // 位置已写明类别（如“访问授权”）时不再重复同名风险标签。
        if (riskLabel.isNotBlank() && riskLabel != where) ApprovalTag(riskLabel)
    }
}

/** 琥珀色小标签：风险类别、“等你批准”等需要留意但不是错误的状态。 */
@Composable
internal fun ApprovalTag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.infinityDomainColors.onWarningContainer,
        maxLines = 1,
        modifier = modifier
            .background(MaterialTheme.infinityDomainColors.warningContainer, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** 卡片标题：待批准时写成“需要批准：动作”；下一行是可复制的原始工具 ID。 */
@Composable
internal fun ApprovalCallTitle(name: String, pending: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val title = approvalToolTitle(name)
        Text(
            text = if (pending) stringResource(R.string.chat_approval_title_required, title) else title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        SelectionContainer {
            Text(
                text = name,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = InfinityMonospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 类别条文案。SFTP 传输与远程命令同走 SSH 后端（路由、风险与后台任务归类不变），这里只把显示类别写成“文件传输”。
 */
internal fun approvalKindLabel(toolName: String, executionTarget: String?): Int =
    if (sftpTransferDirection(toolName) != null) R.string.chat_approval_kind_file_transfer
    else when (CommandToolClassification.backendFor(toolName, ToolExecutionTargets.runsInLinux(executionTarget))) {
        CommandToolBackend.BUILTIN_SHELL, CommandToolBackend.BUILTIN_PYTHON -> R.string.chat_approval_kind_builtin_command
        CommandToolBackend.LINUX -> R.string.chat_approval_kind_linux_command
        CommandToolBackend.TERMUX -> R.string.chat_approval_kind_termux_command
        CommandToolBackend.SSH -> R.string.chat_approval_kind_ssh_command
        null -> R.string.chat_approval_kind_tool
    }

/** 待本地化的类别说明：资源 ID 与格式参数。 */
internal data class ApprovalKindDetailText(val resId: Int, val args: List<Any> = emptyList())

/** 类别说明文案；传输写成“上传到 / 从 主机 下载”，取不到主机时不显示。 */
internal fun approvalKindDetail(toolName: String, executionTarget: String?): ApprovalKindDetailText? {
    sftpTransferDirection(toolName)?.let { direction ->
        val host = sftpTransferHost(executionTarget) ?: return null
        val resId = if (direction == SftpTransferDirection.UPLOAD) R.string.chat_approval_kind_upload_detail
        else R.string.chat_approval_kind_download_detail
        return ApprovalKindDetailText(resId, listOf(host))
    }
    return ApprovalKindDetailText(when (CommandToolClassification.backendFor(toolName, ToolExecutionTargets.runsInLinux(executionTarget))) {
        CommandToolBackend.BUILTIN_SHELL, CommandToolBackend.BUILTIN_PYTHON -> R.string.chat_approval_kind_builtin_command_detail
        CommandToolBackend.LINUX -> R.string.chat_approval_kind_linux_command_detail
        CommandToolBackend.TERMUX -> R.string.chat_approval_kind_termux_command_detail
        CommandToolBackend.SSH -> R.string.chat_approval_kind_ssh_command_detail
        null -> R.string.chat_approval_kind_tool_detail
    })
}

/** 类别说明：命令写清以什么身份运行、能触及哪些范围；内置工具说明由 Infinity Workbench 按固定规则执行；传输写明方向与主机。 */
@Composable
internal fun ApprovalKindDetail(toolName: String, executionTarget: String? = null) {
    val detail = approvalKindDetail(toolName, executionTarget) ?: return
    Text(
        stringResource(detail.resId, *detail.args.toTypedArray()),
        style = MaterialTheme.typography.bodySmall.forReading(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 风险事实只占一行图标加文字，不使用整块红底。 */
@Composable
internal fun ApprovalRiskLine(text: String) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = Icons.Rounded.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 2.dp).size(14.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall.forReading(), color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun ApprovalReason(description: String) {
    val reason = approvalRequestReason(description)
    if (reason.isNotBlank()) {
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("CHAT_APPROVAL_REASON"),
        )
    }
}

/** 类别条上的执行位置：命令正文在下方完整展示时只给执行范围，不重复命令。 */
@Composable
private fun approvalCallWhere(call: ApprovalDisplayCall): String? {
    // 授权目标是挂载预览 JSON，不作为位置显示；类别条写“访问授权”。
    if (call.knowledgeAccess != null) return call.riskLabel
    val preview = call.preview
    val target = call.executionTarget?.let(ToolExecutionTargets::display)
        ?: preview?.target?.takeIf(String::isNotBlank)
    val headerTarget = if (preview?.action == ApprovalPreview.Action.SCRIPT && !preview.detail.isNullOrBlank()) {
        call.executionTarget?.let(ToolExecutionTargets::processScope) ?: target
    } else target
    return approvalHeaderTarget(
        call.name,
        headerTarget ?: approvalFixedWriteTarget(call.name)
            ?: call.schedule?.let { stringResource(R.string.schedule_approval_target) }
            ?: stringResource(R.string.chat_approval_execution_target_unknown),
    )
}

@Composable
private fun ApprovalCallBody(call: ApprovalDisplayCall, description: String, pending: Boolean) {
    val preview = call.preview
    val target = call.executionTarget?.let(ToolExecutionTargets::display)
        ?: preview?.target?.takeIf(String::isNotBlank)
    call.knowledgeAccess?.let { access ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ApprovalCallTitle(call.name, pending)
            KnowledgeAccessApprovalBody(access)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ApprovalCallTitle(call.name, pending)
        ApprovalReason(description)
        if (call.schedule != null) {
            ScheduleApprovalBlock(call.schedule)
        } else if (call.transfer != null) {
            SftpTransferApprovalBlock(call.transfer)
        } else if (preview == null) {
            ApprovalTextBlock(text = call.argumentsSummary)
        } else {
            val previewTitle = approvalPreviewTitle(preview, target)
            val body = preview.detail?.takeIf { preview.action != ApprovalPreview.Action.DELETE }?.let { detail ->
                if (preview.action == ApprovalPreview.Action.SCRIPT && preview.scriptAllowsNetwork) {
                    detail.removePrefix(ApprovalPreview.NETWORK_ALLOWED).removePrefix("\n")
                } else approvalPreviewDetail(preview, detail)
            }.orEmpty()
            // 脚本类预览标题只是“执行脚本”，与卡片标题重复，正文直接展示代码。
            if (preview.action != ApprovalPreview.Action.SCRIPT || body.isBlank()) {
                Text(
                    text = previewTitle,
                    style = MaterialTheme.typography.bodyMedium.forReading(),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            val diffPreview = call.diffPreview
            when {
                diffPreview is com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview.Diff && preview.action in DiffPreviewActions ->
                    // 审批卡位于聊天列表中，差异视口必须限高。
                    com.promenar.infinity.ui.common.diff.FileDiffView(
                        model = diffPreview.model,
                        maxViewportHeight = ApprovalDiffViewportHeight,
                        modifier = Modifier.testTag("CHAT_APPROVAL_DIFF"),
                    )
                diffPreview is com.promenar.infinity.ui.chat.diff.ApprovalDiffPreview.Deletion && preview.action == ApprovalPreview.Action.DELETE ->
                    Text(
                        text = pluralStringResource(R.plurals.file_change_approval_delete_lines, diffPreview.lines, diffPreview.lines),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("CHAT_APPROVAL_DELETE_LINES"),
                    )
                body.isNotBlank() -> ApprovalTextBlock(body)
            }
        }
        ApprovalKindDetail(call.name, call.executionTarget)
        call.background?.let { BackgroundRunLine(it) }
        // 命令的访问范围已由类别条说明，不再重复通用高风险提示。
        val command = CommandToolClassification.backendFor(call.name) != null
        val riskFacts = buildList {
            if (preview?.action == ApprovalPreview.Action.DELETE && !preview.detail.isNullOrBlank()) {
                add(approvalPreviewDetail(preview, preview.detail))
            } else if (call.highRisk && !target.isNullOrBlank() && !command) {
                add(stringResource(R.string.chat_approval_high_risk_target_warning))
            }
            if (preview?.scriptAllowsNetwork == true) add(stringResource(R.string.agent_panels_network_allowed))
            if (call.transfer?.overwrite == true) add(stringResource(R.string.sftp_transfer_overwrite_warning))
        }
        approvalRiskLines(description, riskFacts).forEach { ApprovalRiskLine(it) }
    }
}

/** 可用差异预览替换文本片段的动作。 */
private val DiffPreviewActions = setOf(
    ApprovalPreview.Action.OVERWRITE,
    ApprovalPreview.Action.CREATE_FILE,
    ApprovalPreview.Action.EDIT,
)

/** 审批卡内差异视口的最大高度；超出部分在视口内滚动。 */
private val ApprovalDiffViewportHeight = 280.dp

/** 审批卡内长参数展开后的最大视口高度；超出部分在视口内滚动，避免在聊天列表中无界嵌套滚动。 */
internal val ApprovalTextViewportHeight = 360.dp

/** 审批参数文本块的折叠规则：收起态只渲染前几行，展开态始终渲染完整原文。 */
internal object ApprovalTextBlockLayout {
    const val COLLAPSED_LINES = 6

    /** 收起态单行最多渲染的字符数；只限制渲染量，超出的部分由省略号和展开入口表达。 */
    private const val COLLAPSED_LINE_CHARS = 400

    /**
     * 不依赖布局即可确定收起态放不下全文：原文行数超过收起行数，或前几行中有超长行被渲染截断。
     * 软换行造成的溢出由界面布局结果补充判断。
     */
    fun hasExplicitOverflow(text: String): Boolean {
        val head = text.lineSequence().take(COLLAPSED_LINES + 1).toList()
        return head.size > COLLAPSED_LINES || head.any { it.length > COLLAPSED_LINE_CHARS }
    }

    /** 收起态渲染的文本：多取一行保证省略号出现，超长参数不在折叠时整段排版。 */
    fun collapsedText(text: String): String =
        text.lineSequence().take(COLLAPSED_LINES + 1).joinToString("\n") { it.take(COLLAPSED_LINE_CHARS) }

    /** 展开态渲染的文本：必须是完整原文，不做任何截断。 */
    fun expandedText(text: String): String = text
}

@Composable
internal fun ApprovalTextBlock(text: String, initiallyExpanded: Boolean = false, initialScroll: Int = 0) {
    val collapsedLines = ApprovalTextBlockLayout.COLLAPSED_LINES
    var expanded by remember(text) { mutableStateOf(initiallyExpanded) }
    val explicitLineOverflow = remember(text) { ApprovalTextBlockLayout.hasExplicitOverflow(text) }
    val collapsedText = remember(text) { ApprovalTextBlockLayout.collapsedText(text) }
    var hasOverflow by remember(text) { mutableStateOf(explicitLineOverflow) }
    val textStyle = MaterialTheme.typography.bodySmall.copy(
        fontFamily = InfinityMonospace,
        fontSize = 12.sp,
        lineHeight = 18.sp,
    )
    Surface(
        modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Column(Modifier.padding(10.dp)) {
            if (expanded) {
                // 展开态展示完整参数：固定最大高度内自有滚动，滚到边界后交还外层聊天列表。
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = ApprovalTextViewportHeight)
                        .verticalScroll(rememberScrollState(initialScroll))
                        .testTag("CHAT_APPROVAL_TEXT_EXPANDED"),
                ) {
                    SelectionContainer {
                        Text(
                            text = ApprovalTextBlockLayout.expandedText(text),
                            style = textStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                SelectionContainer {
                    Text(
                        text = collapsedText,
                        style = textStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = collapsedLines,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout ->
                            hasOverflow = explicitLineOverflow || layout.hasVisualOverflow
                        },
                    )
                }
            }
            if (hasOverflow || expanded) {
                ChatExpandLink(
                    text = stringResource(if (expanded) R.string.chat_inline_collapse else R.string.chat_inline_expand_all),
                    expanded = expanded,
                    onClick = { expanded = !expanded },
                )
            }
        }
    }
}

@Composable
fun ApprovalCard(
    toolName: String,
    description: String,
    calls: List<ApprovalDisplayCall> = emptyList(),
    isExecuted: Boolean = false,
    executionTime: String? = null,
    enabled: Boolean = true,
    onApprove: () -> Unit = {},
    onDecline: () -> Unit = {},
    onApproveAll: (() -> Unit)? = null,
) {
    val currentCall = calls.firstOrNull()
    val remainingCount = (calls.size - 1).coerceAtLeast(0)
    val currentName = currentCall?.name ?: toolName
    val currentRisk = currentCall?.riskLabel.orEmpty()
    val approveA11y = stringResource(R.string.chat_approval_approve_current_a11y, currentName, currentRisk, remainingCount)
    val declineA11y = stringResource(R.string.chat_approval_decline_current_a11y, currentName, currentRisk, remainingCount)
    val statusLabel = if (isExecuted) {
        executionTime?.let { stringResource(R.string.chat_approval_executed_at, it) }
            ?: stringResource(R.string.chat_approval_executed)
    } else stringResource(R.string.chat_approval_required)

    val first = calls.firstOrNull()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = InfinitySpacing.Small)
            .animateContentSize()
            .testTag(UiTags.CHAT_APPROVAL_CARD),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        tonalElevation = InfinityElevation.Level0,
    ) {
        Column {
            ApprovalKindBand(
                toolName = first?.name ?: toolName,
                where = first?.let { approvalCallWhere(it) },
                riskLabel = first?.riskLabel.orEmpty(),
                executionTarget = first?.executionTarget,
            )
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // “需要审批 / 已执行”进入无障碍状态与标题措辞；已执行时底部另有状态行。
                Box(
                    modifier = Modifier
                        .semantics(mergeDescendants = false) { stateDescription = statusLabel }
                        .testTag(if (isExecuted) UiTags.CHAT_APPROVAL_EXECUTED else UiTags.CHAT_APPROVAL_REQUIRED),
                ) {
                    if (first == null) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ApprovalCallTitle(toolName, pending = !isExecuted)
                            ApprovalReason(description)
                            ApprovalKindDetail(toolName)
                            approvalRiskLines(description, emptyList()).forEach { ApprovalRiskLine(it) }
                        }
                    } else {
                        ApprovalCallBody(first, description, pending = !isExecuted)
                    }
                }
                if (remainingCount > 0) {
                    Text(
                        text = stringResource(R.string.chat_approval_remaining, remainingCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    calls.drop(1).forEach { call ->
                        // 后续调用各带一条类别条，命令与内置工具并排时一眼可分。
                        Column(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp)),
                        ) {
                            ApprovalKindBand(call.name, approvalCallWhere(call), call.riskLabel, executionTarget = call.executionTarget)
                            Box(Modifier.padding(12.dp)) { ApprovalCallBody(call, "", pending = false) }
                        }
                    }
                }
                if (isExecuted) {
                    Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    ApprovalActionBar(
                        remainingCount = remainingCount,
                        totalCount = calls.size,
                        enabled = enabled,
                        approveA11y = approveA11y,
                        declineA11y = declineA11y,
                        onApprove = onApprove,
                        onDecline = onDecline,
                        onApproveAll = onApproveAll?.takeIf { remainingCount > 0 },
                    )
                }
            }
        }
    }
}

/**
 * 拒绝与批准等宽并排，批准是卡片里唯一的实心按钮；“全部批准”作为次要文字操作放在上方。
 * 大字号时按钮文字最多两行，按钮随之长高，触控高度始终不小于 48dp。
 */
@Composable
internal fun ApprovalActionBar(
    remainingCount: Int,
    totalCount: Int,
    enabled: Boolean,
    approveA11y: String,
    declineA11y: String,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onApproveAll: (() -> Unit)?,
    approveLabel: String? = null,
) {
    val haptics = LocalInfinityHaptics.current
    val confirm: (() -> Unit) -> () -> Unit = { action -> { haptics.perform(InfinityHapticEvent.CONFIRM); action() } }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        onApproveAll?.let { handler ->
            ApprovalSecondaryAction(
                label = stringResource(R.string.chat_approval_approve_all, totalCount),
                onClick = confirm(handler),
                enabled = enabled,
                modifier = Modifier.testTag(UiTags.CHAT_APPROVAL_APPROVE_ALL),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ApprovalPillButton(
                label = stringResource(if (remainingCount > 0) R.string.chat_inline_approval_reject_current else R.string.chat_inline_approval_reject),
                onClick = { haptics.perform(InfinityHapticEvent.REJECT); onDecline() },
                primary = false,
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { contentDescription = declineA11y }.testTag(UiTags.CHAT_APPROVAL_DECLINE),
            )
            ApprovalPillButton(
                label = approveLabel ?: stringResource(if (remainingCount > 0) R.string.chat_inline_approval_approve_current else R.string.chat_inline_approval_approve),
                onClick = confirm(onApprove),
                primary = true,
                enabled = enabled,
                modifier = Modifier.weight(1f).semantics { contentDescription = approveA11y }.testTag(UiTags.CHAT_APPROVAL_APPROVE),
            )
        }
    }
}

/** 审批胶囊按钮：可见高度 44dp；主按钮为主色实心，次按钮为中性色块。 */
@Composable
private fun ApprovalPillButton(label: String, onClick: () -> Unit, primary: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val fill = when {
        !enabled -> colors.onSurface.copy(alpha = 0.12f)
        primary -> colors.primary
        else -> colors.surfaceContainerHighest
    }
    val textColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        primary -> colors.onPrimary
        else -> colors.onSurface
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(fill)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
            fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal,
            color = textColor,
            maxLines = 2,
            textAlign = TextAlign.Center,
        )
    }
}

/** 次要操作的文字与卡片内容左缘对齐：按钮内边距向左让出，触控范围仍为 48dp。 */
@Composable
private fun ApprovalSecondaryAction(label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        modifier = modifier.offset(x = (-12).dp).heightIn(min = 40.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 2)
    }
}
