package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.material.icons.rounded.TextFields
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.data.workspace.isWorkspaceSwitchNotice
import androidx.compose.animation.*
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.forReading
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.promenar.infinity.ui.common.InfinityMetaSeparator
import com.promenar.infinity.ui.common.infinityPopoverAnchor
import com.promenar.infinity.ui.common.rememberInfinityPopoverAnchor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.turnMemory
import com.promenar.infinity.data.model.TurnMemoryState
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.local.db.recovery.LegacyAttachmentCodec
import com.promenar.infinity.data.local.db.recovery.LegacyAttachmentItem
import com.promenar.infinity.data.local.db.recovery.LegacyAttachmentOpenResult
import com.promenar.infinity.ui.common.GenerationDots
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.ui.renderer.ImageLightbox
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.chat.quote.QuotableSelectionContainer
import com.promenar.infinity.ui.chat.quote.UserMessageQuotes
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import com.promenar.infinity.ui.common.InfinityMenuItem
import com.promenar.infinity.ui.common.rememberInfinityMenuTouch
import com.promenar.infinity.ui.common.infinityMenuTouch
import com.promenar.infinity.ui.common.InfinityMenuTouch
import com.promenar.infinity.ui.common.InfinityMenuDivider

// ─────────────────────────────────────────────────────────────────
//  Pipeline 数据结构
// ─────────────────────────────────────────────────────────────────

/**
 * 将连续 ASSISTANT+TOOL 消息合并为一组。
 * USER 消息始终独立成组。
 */
data class PipelineGroup(
    val messages: List<Message>,
    val isUser: Boolean,
    /** 工作区切换提示：独立成组，居中显示为一行说明。 */
    val isNotice: Boolean = false,
) {
    /** 组内所有 ASSISTANT 消息 */
    val assistantMessages: List<Message>
        get() = messages.filter { it.role == MessageRole.ASSISTANT }
}

fun buildPipelineGroups(allMessages: List<Message>): List<PipelineGroup> {
    // 送达回执、唤醒与 worktree 授权等内部记录不显示。
    val messages = allMessages.filterNot { com.promenar.infinity.data.branch.SessionMessageStore.isInternal(it.name) }
    val groups = mutableListOf<PipelineGroup>()
    var i = 0
    while (i < messages.size) {
        val msg = messages[i]
        if (msg.isWorkspaceSwitchNotice) {
            groups.add(PipelineGroup(listOf(msg), isUser = false, isNotice = true))
            i++
        } else if (msg.role == MessageRole.USER && !msg.isSessionTimelineRecord) {
            // USER 消息：每个独立成组（与后续 AI 回复分离以保持锚定语义）
            groups.add(PipelineGroup(listOf(msg), isUser = true))
            i++
        } else {
            // ASSISTANT / TOOL：视作同一轮 AI 响应，全部合并到一组
            val groupMsgs = mutableListOf<Message>()
            // 会话消息发送副本写在工具调用与结果之间，留在本轮内，不切分。
            while (i < messages.size && (messages[i].role != MessageRole.USER || messages[i].isSessionTimelineRecord) &&
                !messages[i].isWorkspaceSwitchNotice) {
                val candidate = messages[i]
                val candidateIsError = candidate.isError || candidate.errorMessage != null
                if (candidateIsError && groupMsgs.isNotEmpty()) break
                groupMsgs.add(candidate)
                i++
                if (candidateIsError) break
            }
            groups.add(PipelineGroup(groupMsgs, isUser = false))
        }
    }
    return groups
}

/** 只在会话记录中显示的会话消息条目与暂停标记。 */
internal val Message.isSessionTimelineRecord: Boolean
    get() = com.promenar.infinity.data.branch.SessionMessageStore.isTimelineRecord(name)

internal fun messageCopyOverride(
    onCopy: ((String) -> Unit)?,
    content: String
): (() -> Unit)? = onCopy?.let { copyHandler ->
    { copyHandler(content) }
}

internal fun pipelineReplyCopyText(steps: List<PipelineStep>): String =
    steps.filterIsInstance<PipelineStep.Content>().map { it.content }.filter(String::isNotBlank).joinToString("\n\n")

internal fun historicalGenerationFailureNotice(errorMessage: String?): UiStatusNotice =
    GenerationFailureNotice.fromPersisted(errorMessage)

internal fun streamingReasoningPreview(reasoning: String, maxChars: Int = 180): String =
    reasoning.takeLast(maxChars.coerceAtLeast(1))
        .dropWhile { it.isLowSurrogate() }
        .replace(Regex("\\s+"), " ")
        .trim()

// ─────────────────────────────────────────────────────────────────
//  PipelineBubble — 单轮 AI 对话的线性格局
// ─────────────────────────────────────────────────────────────────

/**
 * 将一轮助手响应渲染为通栏内容，步骤相邻排列，正文边界保留阅读间距。
 * 检索、思考与工具详情由对应步骤按需展开。
 */
@Composable
fun PipelineBubble(
    group: PipelineGroup,
    isGenerating: Boolean,
    status: GenerationStatus = GenerationStatus.IDLE,
    streamingContent: String,
    fontSize: Int,
    modelDisplayNames: Map<String, String> = emptyMap(),
    /** 服务商 ID → 显示名，用于生成统计浮卡。 */
    providerDisplayNames: Map<String, String> = emptyMap(),
    onContentChange: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onRegenerate: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
    hasRagContent: Boolean = false,
    onMessageContentChange: ((String, String) -> Unit)? = null,
    /** 外观设置“完成后折叠过程”；关闭时保持逐步平铺。 */
    collapseFinishedTurn: Boolean = false,
    /** 本组存在等待用户审批的工具调用，此时不进入完成态。 */
    hasPendingApproval: Boolean = false,
    /** 本组因紧随其后的错误消息被提前截断，此时不进入完成态。 */
    interruptedByError: Boolean = false,
    /** 完成态时间线的初始展开状态，仅供预览使用。 */
    initialProcessExpanded: Boolean = false,
    /** 点按记忆写入状态时以该轮记忆所在的助手消息 ID 回调；为 null 时不显示记忆状态（会话关闭了记忆）。 */
    onOpenTurnMemory: ((String) -> Unit)? = null,
    /** 本会话各轮的图谱抽取状态，键为该轮最后一条助手消息。 */
    turnGraphStates: Map<String, com.promenar.infinity.data.rag.TurnGraphState> = emptyMap(),
    /** 最新一轮的失败入口；只传给最后一组，历史失败不提供操作。 */
    failureActions: GenerationFailureActions? = null,
    /** 本组当前唯一的进行中元素；会话页按生成阶段判定后只传给最后一组，预览按消息内容推断。 */
    liveStep: LiveStep? = previewLiveStep(group, isGenerating, streamingContent),
    /** 等待模型超过 30 秒时的“停止生成”；为 null 时不显示按钮。 */
    onStopGeneration: (() -> Unit)? = null,
    ragContent: @Composable () -> Unit = {},
) {
    if (group.isNotice) {
        WorkspaceSwitchNoticeRow(text = group.messages.first().content, modifier = modifier)
        return
    }
    if (group.isUser) {
        val msg = group.messages.first()
        msg.systemTrigger?.let { trigger ->
            if (trigger.kind == com.promenar.infinity.domain.trigger.SystemTurnKind.SESSION_MESSAGE) SessionRecordRows(msg, modifier)
            else SystemTriggerRow(trigger = trigger, modifier = modifier)
            return
        }
        if (msg.name == com.promenar.infinity.data.branch.BranchPlanLedger.PLAN_MESSAGE_NAME) {
            val access = LocalBranchAccess.current
            BranchOriginCard(
                parentTitle = access?.parentTitle,
                goal = access?.branchPlanTitle.orEmpty(),
                modifier = modifier,
                onOpenParent = access?.parentSessionId?.let { parent -> { access.onOpenSession(parent) } },
            )
            return
        }
        UserMessageBubble(
            message = msg,
            fontSize = fontSize,
            modifier = modifier,
            onDelete = { onDelete?.invoke(msg.id) },
            onCopy = messageCopyOverride(onCopy, msg.content),
            onRegenerate = onRegenerate?.let { handler -> { handler(msg.id) } },
            onBranch = onBranch?.let { handler -> { handler(msg.id) } },
        )
        return
    }

    val renderPlan = remember(group.messages, isGenerating, streamingContent.isNotEmpty(), hasPendingApproval) {
        buildPipelineRenderPlan(
            messages = group.messages,
            isGenerating = isGenerating,
            hasStreamingContent = streamingContent.isNotEmpty(),
            hasPendingApproval = hasPendingApproval,
        )
    }
    val allSteps = renderPlan.steps
    val finalAssistantId = group.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
    val collapsePlan = remember(allSteps, group.messages, isGenerating, hasPendingApproval, interruptedByError, collapseFinishedTurn) {
        buildCollapsedTurnPlan(
            messages = group.messages,
            steps = allSteps,
            isGenerating = isGenerating,
            hasPendingApproval = hasPendingApproval,
            enabled = collapseFinishedTurn,
            interruptedByError = interruptedByError,
        )
    }
    // 展开状态只保存在界面层，以组首条消息 ID 为稳定键，不写数据库。
    var processExpanded by rememberSaveable(group.messages.firstOrNull()?.id) { mutableStateOf(initialProcessExpanded) }

    val footerContent = collapsePlan?.finalContent
        ?: allSteps.filterIsInstance<PipelineStep.Content>().lastOrNull { it.content.isNotBlank() }
    val replyCopyText = remember(allSteps, collapsePlan) { collapsePlan?.finalCopyText ?: pipelineReplyCopyText(allSteps) }
    val allProcessCopyText = remember(collapsePlan) { collapsePlan?.allProcessCopyText() }
    Column(modifier = modifier.fillMaxWidth()) {
        ragContent()
        if (collapsePlan != null) {
            TurnProcessSummaryRow(
                summary = collapsePlan.summary,
                expanded = processExpanded,
                fontSize = fontSize,
                onToggle = { processExpanded = !processExpanded },
            )
            // 展开后由对应工具行显示，避免同一提示出现两次。
            AnimatedVisibility(
                visible = !processExpanded && (collapsePlan.summary.runtimeChanges.isNotEmpty() || collapsePlan.summary.memoryChanges.isNotEmpty()),
                enter = InfinityMotion.expandIn,
                exit = InfinityMotion.shrinkOut,
            ) {
                Column {
                    if (collapsePlan.summary.runtimeChanges.isNotEmpty()) {
                        RuntimeChangesNotice(collapsePlan.summary.runtimeChanges, Modifier.padding(top = 4.dp))
                    }
                    if (collapsePlan.summary.memoryChanges.isNotEmpty()) MemoryChangeRows(collapsePlan.summary.memoryChanges)
                }
            }
        }
        // 单步渲染：间距与“组内末步”按步骤在所属列表中的位置判定，平铺与收起两种布局共用。
        val renderStep: @Composable (Int, PipelineStep, List<PipelineStep>) -> Unit = { index, step, steps ->
            val followsContent = steps.getOrNull(index - 1) is PipelineStep.Content
            if ((index > 0 && (step is PipelineStep.Content || followsContent)) ||
                (index == 0 && (hasRagContent || collapsePlan != null) && step is PipelineStep.Content)
            ) {
                Spacer(Modifier.height(8.dp))
            }
            key(when (step) {
                is PipelineStep.Thinking -> "thinking:${step.messageId}"
                is PipelineStep.ToolExec -> "tools:${step.steps.firstOrNull()?.id}"
                // 最终正文在收起与展开之间位置会变，使用固定键避免重建正文。
                is PipelineStep.Content -> if (collapsePlan != null && step === collapsePlan.finalContent) "content:final" else "content:$index"
                is PipelineStep.SessionRecord -> "record:${step.message.id}"
            }) {
                when (step) {
                    is PipelineStep.Thinking -> ThinkingTrace(
                        reasoning = step.reasoning,
                        isGenerating = liveStep?.kind == LiveStepKind.THINKING && liveStep.targetId == step.messageId,
                        fontSize = fontSize,
                        clockKey = step.messageId,
                        livePhase = liveStep?.motionPhase,
                    )
                    is PipelineStep.ToolExec -> Column {
                        // 已答的提问以摘要卡呈现，其余调用照常显示为工具行；待回答时提问卡在会话流底部，这里不重复。
                        val asks = remember(step.steps) { askUserSteps(step.steps) }
                        // 分支方案同理：已处理的显示为分支卡片或选择摘要，待处理时方案卡在会话流底部。
                        val branches = remember(step.steps) { branchPlanSteps(step.steps) }
                        val hidden = asks.hidden + branches.hidden + remember(step.steps) { sessionSendHiddenSteps(step.steps) }
                        val toolSteps = if (hidden.isEmpty()) step.steps else step.steps.filterNot { it.id in hidden }
                        if (toolSteps.isNotEmpty()) InlineToolRow(
                            steps = toolSteps, isExecuting = step.isExecuting, fontSize = fontSize, turnActive = step.isTurnActive,
                            commandOutputTarget = { callStepId -> commandOutputTarget(group.messages, callStepId) },
                            live = liveStep?.takeIf { live -> live.kind == LiveStepKind.TOOL && toolSteps.any { it.id == live.targetId } },
                        )
                        asks.answered.forEach { AskUserAnsweredCard(it) }
                        branches.resolved.forEach { BranchPlanResolvedView(it) }
                        // 生成中画布卡紧跟创建它的步骤；结束后改由正文下方的本轮产物区展示。
                        if (isGenerating) CanvasInvocationCards(step.steps, inline = true)
                    }
                    is PipelineStep.Content -> {
                        // 流式正文属于本组最后一条助手消息；它之后可能还有本条消息发起的工具行，不能按“组内末步”判断。
                        val streamingTarget = isGenerating && step.messageId == finalAssistantId
                        val displayContent = if (streamingTarget) {
                            streamingContent.ifEmpty { step.content }
                        } else step.content
                        val sourceMessage = pipelineContentOwner(group.messages, step)
                        ContentSegment(
                            content = displayContent,
                            isStreaming = streamingTarget,
                            showStreamingCursor = renderPlan.showContentCursor,
                            fontSize = fontSize,
                            sourceMessageId = sourceMessage?.id ?: group.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id,
                            onContentChange = sourceMessage?.let { msg ->
                                onMessageContentChange?.let { handler -> { content: String -> handler(msg.id, content) } }
                            } ?: onContentChange,
                        )
                    }
                    is PipelineStep.SessionRecord -> SessionRecordRows(step.message)
                }
            }
        }
        if (collapsePlan == null) {
            allSteps.forEachIndexed { index, step -> renderStep(index, step, allSteps) }
        } else {
            val expandedSteps = remember(collapsePlan) { collapsePlan.processSteps + collapsePlan.finalContent }
            // 过程步骤随摘要行纵向展开、收起，最终正文始终留在下方原位，不随切换重建。
            AnimatedVisibility(visible = processExpanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                Column { collapsePlan.processSteps.forEachIndexed { index, step -> renderStep(index, step, expandedSteps) } }
            }
            renderStep(expandedSteps.lastIndex, collapsePlan.finalContent, expandedSteps)
        }
        // 进行中元素在流末尾：正文流式时动画紧跟正文，等待模型或准备工具调用时单独一行。
        liveStep?.let { live ->
            if (live.kind == LiveStepKind.CONTENT) GenerationIndicator(live.motionPhase)
            else if (live.isTailRow) LiveWaitingRow(live, onStopGeneration)
        }
        if (!isGenerating) CanvasInvocationCards(
            allSteps.filterIsInstance<PipelineStep.ToolExec>().flatMap { it.steps },
            messageIds = remember(group.messages) { group.messages.map { it.id }.toSet() },
        )
        if (footerContent != null) {
            pipelineContentOwner(group.messages, footerContent)?.let { lastMsg ->
                val context = LocalContext.current
                val timestamp = remember(lastMsg.createdAt) {
                    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(lastMsg.createdAt))
                }
                val modelDisplayName = if (lastMsg.modelId.isNullOrBlank()) "" else {
                    modelDisplayNames[lastMsg.modelId]?.takeIf(String::isNotBlank)
                        ?: lastMsg.modelId.substringAfter("::", lastMsg.modelId)
                }
                val providerName = lastMsg.modelId?.substringBefore("::", "")
                    ?.takeIf(String::isNotBlank)
                    ?.let(providerDisplayNames::get)
                // 统计只在打开浮卡时计算，列表渲染时每个气泡只保留一个轻量工厂。
                val statsFactory = remember(group.messages, modelDisplayName, providerName, lastMsg.createdAt) {
                    {
                        GenerationStatsCardModel(
                            modelName = modelDisplayName,
                            providerName = providerName,
                            generatedAt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                                .format(java.util.Date(lastMsg.createdAt)),
                            summary = summarizeGenerationStats(group.messages),
                        )
                    }
                }
                val memoryOwner = if (onOpenTurnMemory == null) null
                    else group.assistantMessages.lastOrNull { it.turnMemory != null || it.id in turnGraphStates }
                AssistantActionRow(
                    modelDisplayName = modelDisplayName,
                    timestamp = timestamp,
                    isGenerating = isGenerating,
                    memoryState = memoryOwner?.turnMemory,
                    graphState = memoryOwner?.let { turnGraphStates[it.id] },
                    onOpenMemory = memoryOwner?.let { owner -> onOpenTurnMemory?.let { open -> { open(owner.id) } } },
                    stats = statsFactory,
                    onCopy = { onCopy?.invoke(replyCopyText) ?: copyToClipboard(context, replyCopyText) },
                    onCopyAllProcess = allProcessCopyText?.let { text ->
                        { onCopy?.invoke(text) ?: copyToClipboard(context, text) }
                    },
                    onRegenerate = onRegenerate?.let { handler -> { handler(lastMsg.id) } },
                    onBranch = onBranch?.let { handler -> { handler(lastMsg.id) } },
                    onDelete = onDelete?.let { handler -> { handler(lastMsg.id) } },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    // ── 错误信息 ──
    group.messages.lastOrNull()?.let { lastMsg ->
        if (lastMsg.isError || lastMsg.errorMessage != null) {
            val resolved = remember(lastMsg.errorMessage) {
                GenerationFailureNotice.template(
                    historicalGenerationFailureNotice(lastMsg.errorMessage),
                )
            }
            val tailnetKind = remember(lastMsg.errorMessage) {
                GenerationFailureNotice.tailnetKind(historicalGenerationFailureNotice(lastMsg.errorMessage))
            }
            val detail = remember(lastMsg.errorMessage) { historicalGenerationFailureNotice(lastMsg.errorMessage).detail }
            ErrorMessageSegment(
                messageId = lastMsg.id,
                text = stringResource(resolved.resourceId, *resolved.args.toTypedArray()),
                detail = detail?.let { stringResource(R.string.generation_failure_provider_detail, it) },
                fontSize = fontSize,
                onDelete = onDelete,
                tailnetKind = tailnetKind,
                actions = failureActions,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ErrorMessageSegment(
    messageId: String,
    text: String,
    fontSize: Int,
    onDelete: ((String) -> Unit)?,
    detail: String? = null,
    tailnetKind: TailnetFailureKind? = null,
    actions: GenerationFailureActions? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val menuTouch = rememberInfinityMenuTouch()
    Box(modifier = Modifier.padding(top = InfinitySpacing.Small)) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                // 长按只弹菜单，不铺按压涟漪；长按震动由 combinedClickable 自带。菜单从按住的位置长出。
                .infinityMenuTouch(menuTouch)
                .combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = { showMenu = true }),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                    .padding(horizontal = InfinitySpacing.Large, vertical = 6.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Column {
                    Text(
                        text = text,
                        style = InfinityTypography.bodyMedium.copy(
                            fontSize = (fontSize - 2).coerceAtLeast(10).sp,
                        ),
                    )
                    // 服务商原因原样附在下方，便于判断限流、额度或参数问题。
                    if (detail != null) {
                        Text(
                            text = detail,
                            style = InfinityTypography.bodySmall.copy(
                                fontSize = (fontSize - 4).coerceAtLeast(10).sp,
                            ),
                            color = LocalContentColor.current.copy(alpha = 0.8f),
                            modifier = Modifier.padding(top = 2.dp).testTag(UiTags.CHAT_FAILURE_PROVIDER_DETAIL),
                        )
                    }
                    if (tailnetKind != null && actions != null) {
                        TailnetFailureActionRow(tailnetKind, actions)
                    }
                }
            }
        }
        MessageContextMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            touch = menuTouch,
            onCopy = { copyToClipboard(context, listOfNotNull(text, detail).joinToString("\n")) },
            onDelete = {
                onDelete?.invoke(messageId)
                showMenu = false
            },
        )
    }
}

/** 失败消息下方的操作；由聊天页提供，预览与历史组为 null。 */
data class GenerationFailureActions(
    val onRetry: () -> Unit,
    val onSwitchModel: () -> Unit,
    val onOpenTailscaleSettings: (() -> Unit)?,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailnetFailureActionRow(kind: TailnetFailureKind, actions: GenerationFailureActions) {
    val contentColor = MaterialTheme.colorScheme.onErrorContainer
    val buttonColors = ButtonDefaults.textButtonColors(contentColor = contentColor)
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .testTag(UiTags.CHAT_TAILNET_FAILURE_ACTIONS),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        @Composable
        fun Action(label: Int, tag: String, onClick: () -> Unit) {
            TextButton(
                onClick = onClick,
                colors = buttonColors,
                modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(tag),
            ) { Text(stringResource(label), fontWeight = FontWeight.Medium) }
        }
        when (kind) {
            // 开启或登录后回到会话，用“重试”重新发送这条消息。
            TailnetFailureKind.DISABLED -> {
                actions.onOpenTailscaleSettings?.let { Action(R.string.tailnet_failure_action_enable, UiTags.CHAT_TAILNET_FAILURE_ENABLE, it) }
                Action(R.string.tailnet_failure_action_retry, UiTags.CHAT_TAILNET_FAILURE_RETRY, actions.onRetry)
                Action(R.string.tailnet_failure_action_switch_model, UiTags.CHAT_TAILNET_FAILURE_SWITCH, actions.onSwitchModel)
            }
            TailnetFailureKind.NEEDS_LOGIN -> {
                actions.onOpenTailscaleSettings?.let { Action(R.string.tailnet_failure_action_login, UiTags.CHAT_TAILNET_FAILURE_LOGIN, it) }
                Action(R.string.tailnet_failure_action_retry, UiTags.CHAT_TAILNET_FAILURE_RETRY, actions.onRetry)
                Action(R.string.tailnet_failure_action_switch_model, UiTags.CHAT_TAILNET_FAILURE_SWITCH, actions.onSwitchModel)
            }
            TailnetFailureKind.UNREACHABLE -> {
                Action(R.string.tailnet_failure_action_retry, UiTags.CHAT_TAILNET_FAILURE_RETRY, actions.onRetry)
                Action(R.string.tailnet_failure_action_switch_model, UiTags.CHAT_TAILNET_FAILURE_SWITCH, actions.onSwitchModel)
                actions.onOpenTailscaleSettings?.let { Action(R.string.tailnet_failure_action_settings, UiTags.CHAT_TAILNET_FAILURE_SETTINGS, it) }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  Pipeline 步骤构建
// ─────────────────────────────────────────────────────────────────

internal sealed class PipelineStep {
    data class Thinking(
        val reasoning: String,
        val messageId: String = "",
    ) : PipelineStep()

    data class ToolExec(
        val steps: List<ExecutionStep>,
        val isExecuting: Boolean,
        /** 本轮是否仍在进行（生成中或等待审批）；结束后未完成的调用显示为已中断。 */
        val isTurnActive: Boolean = true,
    ) : PipelineStep()

    data class Content(
        val content: String,
        val messageId: String = "",
    ) : PipelineStep()

    /** 会话消息发送记录或自动处理暂停标记，按写入位置显示在本轮中。 */
    data class SessionRecord(val message: Message) : PipelineStep()
}

/** 正文的展示来源由消息 ID 定位，缺失来源时不猜测其它消息。 */
internal fun pipelineContentOwner(messages: List<Message>, content: PipelineStep.Content): Message? =
    messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.id == content.messageId }

internal data class PipelineRenderPlan(
    val steps: List<PipelineStep>,
    val showContentCursor: Boolean
)

internal fun buildPipelineRenderPlan(
    messages: List<Message>,
    isGenerating: Boolean,
    hasStreamingContent: Boolean,
    hasPendingApproval: Boolean = false,
): PipelineRenderPlan {
    val finalAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    // 正文已开始流式到达但尚未落盘时，为最后一条助手消息在正文位置补一个占位，排在它发起的工具之前。
    val streamingPlaceholderId = finalAssistant?.takeIf { isGenerating && hasStreamingContent && it.content.isBlank() }?.id
    val steps = buildPipelineSteps(messages, streamingPlaceholderId).map { step ->
        if (step is PipelineStep.ToolExec) step.copy(
            isExecuting = isGenerating && finalAssistant != null && toolInvocations(step.steps).any { invocation ->
                !invocation.complete && (invocation.call?.timestamp ?: 0) > 0 &&
                    invocation.call?.id?.startsWith("${finalAssistant.id}::") == true
            },
            // 审批通过后的调用在生成任务外执行，审批请求清除前本轮仍算进行中。
            isTurnActive = isGenerating || hasPendingApproval,
        ) else step
    }

    // 生成指示挂在哪一步由进行中元素（见 resolveLiveStep）决定。
    return PipelineRenderPlan(
        steps = steps,
        showContentCursor = false
    )
}

/** 预览与测试未提供会话阶段时，按本组消息内容推断进行中元素。 */
internal fun previewLiveStep(group: PipelineGroup, isGenerating: Boolean, streamingContent: String): LiveStep? =
    if (!isGenerating || group.isUser || group.isNotice) null
    else resolveLiveStep(group.messages, LiveTurnSignals(phase = null, isGenerating = true, hasStreamingContent = streamingContent.isNotEmpty()))

// ── 工具内容嗅探：覆盖 JSON 代码块、裸 JSON、分隔符文本、XML 四种格式 ──
// 只认工具信封字段；name、query 等普通数据字段很常见，按它们嗅探会把正文里的 JSON 删掉并虚构出成批未完成调用。
private val TOOL_CONTENT_SNIFFER = Regex(
    """(?:
        ```(?:json)?\s*\n?\{[\s\S]*?"(?:tool_name|tool_call_id|tool|function|arguments)"[\s\S]*?\}\s*\n?```
        |\{[^}]*"(?:tool_name|tool_call_id)"[^}]*\}
        |---\s*(?:工具|tool|search)?\s*(?:调用|执行)?\s*结果\s*[：:]
        |<(?:tool_call|function_call|function_name)[\s\S]*?/>
    )""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.COMMENTS)
)

private fun hasInlineToolData(content: String): Boolean =
    TOOL_CONTENT_SNIFFER.containsMatchIn(content)

private fun stripToolArtifacts(content: String): String {
    var clean = content
    clean = TOOL_CONTENT_SNIFFER.replace(clean, "")
    return clean.trim()
}

private fun buildSyntheticExecutionSteps(
    toolCalls: List<String>,
    toolResults: List<String>
): List<ExecutionStep> {
    if (toolCalls.isEmpty() && toolResults.isEmpty()) return emptyList()

    val steps = mutableListOf<ExecutionStep>()
    val identity = "${toolCalls.hashCode()}-${toolResults.hashCode()}"

    toolCalls.forEachIndexed { index, args ->
        val toolName = inferredToolName(ExecutionStep("source", "tool_call", toolArgs = args, timestamp = 0))

        steps.add(
            ExecutionStep(
                id = "synthetic-tool-$identity-$index",
                type = "tool_call",
                toolName = toolName,
                toolArgs = args,
                timestamp = 0
            )
        )
    }

    toolResults.forEachIndexed { index, result ->
        steps.add(
            ExecutionStep(
                id = "synthetic-result-$identity-$index",
                type = if (result.contains("error", ignoreCase = true)) "error" else "tool_result",
                content = result,
                timestamp = 0
            )
        )
    }

    return steps
}

private fun extractSyntheticExecutionSteps(content: String): List<ExecutionStep> {
    val jsonPattern = Regex("""\{[^{}]*"(?:tool_name|tool_call_id|tool|function|arguments)"[^{}]*\}""")
    val separatorPattern = Regex("""---\s*(?:工具|tool|search)?\s*(?:调用|执行)?\s*结果\s*[：:]""", RegexOption.IGNORE_CASE)

    val toolCalls = jsonPattern.findAll(content).map { it.value }.toList()
    val toolResults = separatorPattern.split(content).drop(1).map { it.trim() }

    return buildSyntheticExecutionSteps(toolCalls, toolResults)
}

/**
 * 展示副本中的调用步骤 id 形如 `消息id::原始步骤id`，工具调用 id 也被改写；
 * 完整输出页按原始记录定位，因此从所属消息取回持久化的工具调用 id。返回 (消息 id, 工具调用 id)。
 */
internal fun commandOutputTarget(messages: List<Message>, callStepId: String): Pair<String, String>? {
    val messageId = callStepId.substringBefore("::", "").takeIf(String::isNotEmpty) ?: return null
    val toolCallId = messages.firstOrNull { it.id == messageId }?.executionSteps
        ?.firstOrNull { it.id == unscopedStepId(callStepId) }?.toolCallId
    return toolCallId?.takeIf(String::isNotBlank)?.let { messageId to it }
}

/** 消息只在展示副本中限定作用域，避免跨工具轮次复用调用 ID 时混淆真实调用。 */
/** 界面按消息限定的步骤 id 还原为执行时登记的原始 id；消息 id 不含 `::`。 */
internal fun unscopedStepId(id: String): String = id.substringAfter("::", id)

private fun scopedToolSteps(messageId: String, steps: List<ExecutionStep>): List<ExecutionStep> =
    toolInvocations(steps).flatMapIndexed { index, invocation ->
        invocation.steps.map { step ->
            step.copy(id = "$messageId::${step.id}", toolCallId = "$messageId::invocation-$index")
        }
    }

/**
 * 同一条助手消息按“思考 → 正文 → 工具”排列：模型先说明再发起调用，正在运行的工具出现在那句话下方。
 * [streamingPlaceholderId] 为正文尚未落盘的流式目标消息，在其正文位置放一个空占位。
 */
private fun buildPipelineSteps(messages: List<Message>, streamingPlaceholderId: String? = null): List<PipelineStep> {
    val steps = mutableListOf<PipelineStep>()
    for ((messageIndex, msg) in messages.withIndex()) {
        val followingTools = messages.subList(messageIndex + 1, messages.size)
            .takeWhile { it.role == MessageRole.TOOL || it.isSessionTimelineRecord }
            .filter { it.role == MessageRole.TOOL }
        val knownToolNames = buildMap {
            followingTools.forEach { tool ->
                if (!tool.toolCallId.isNullOrBlank() && !tool.name.isNullOrBlank()) put(tool.toolCallId, tool.name)
            }
            msg.executionSteps.orEmpty().forEach { execution ->
                if (!execution.toolCallId.isNullOrBlank()) inferredToolName(execution)?.let { put(execution.toolCallId, it) }
            }
            msg.toolCalls.orEmpty().forEach { put(it.id, it.name) }
        }
        if (msg.isSessionTimelineRecord) {
            steps.add(PipelineStep.SessionRecord(msg))
            continue
        }
        if (msg.role == MessageRole.ASSISTANT) {
            // 1. 推理 → Thinking step
            if (!msg.reasoning.isNullOrBlank()) {
                steps.add(PipelineStep.Thinking(reasoning = msg.reasoning!!, messageId = msg.id))
            }

            // 2. 正文 → Content step（含工具内容嗅探）
            if (msg.content.isNotBlank()) {
                // 渲染层最后防线：对 content 做工具特征检测
                if (hasInlineToolData(msg.content)) {
                    // 结构化执行事件是权威来源；正文嗅探仅兼容缺少执行事件的历史消息。
                    val hasStructuredTools = msg.executionSteps.orEmpty().any {
                        it.type == "tool_call" || it.type == "tool_result" || it.type == "error"
                    }
                    if (!hasStructuredTools) {
                        val syntheticSteps = scopedToolSteps(msg.id, extractSyntheticExecutionSteps(msg.content))
                        if (syntheticSteps.isNotEmpty()) steps.add(PipelineStep.ToolExec(
                            steps = syntheticSteps,
                            isExecuting = false,
                        ))
                    }
                    // 清洗后的剩余正文（移除工具 JSON/XML 部分）
                    val cleanContent = stripToolArtifacts(msg.content)
                    if (cleanContent.isNotBlank()) {
                        steps.add(PipelineStep.Content(content = cleanContent, messageId = msg.id))
                    }
                } else {
                    steps.add(PipelineStep.Content(content = msg.content, messageId = msg.id))
                }
            } else if (msg.id == streamingPlaceholderId) {
                steps.add(PipelineStep.Content(content = "", messageId = msg.id))
            }

            // 3. 工具执行 → ToolExec step
            val executionSteps = msg.executionSteps.orEmpty() + askUserSyntheticSteps(msg, followingTools)
            if (executionSteps.isNotEmpty()) {
                val namedSteps = executionSteps.map { execution ->
                    execution.copy(toolName = execution.toolName?.takeIf(String::isNotBlank)
                        ?: knownToolNames[execution.toolCallId] ?: inferredToolName(execution))
                }
                val scoped = scopedToolSteps(msg.id, namedSteps)
                if (scoped.isNotEmpty()) steps.add(PipelineStep.ToolExec(steps = scoped, isExecuting = false))
            }
        }
        // TOOL 消息的内容已经在 executionSteps 中展示，此处跳过
    }

    val merged = mutableListOf<PipelineStep>()
    steps.forEach { step ->
        val previous = merged.lastOrNull()
        if (step is PipelineStep.ToolExec && previous is PipelineStep.ToolExec) {
            merged[merged.lastIndex] = previous.copy(steps = previous.steps + step.steps)
        } else merged.add(step)
    }
    return merged
}

/**
 * 思考计时的进程内记录：起点按消息记第一次看到思考的时刻，结束时记下时长。
 * 会话页离开后组合被销毁，靠它让回来时继续计时；进程重启后没有记录，只显示“已思考”。
 */
internal class ThinkingClock(private val capacity: Int = 64) {
    private val starts = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > capacity
    }
    private val durations = object : LinkedHashMap<String, Long>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>) = size > capacity
    }

    @Synchronized fun start(key: String, now: Long): Long = starts.getOrPut(key) { now }

    @Synchronized fun finish(key: String, now: Long) {
        if (key in durations) return
        starts[key]?.let { durations[key] = (now - it).coerceAtLeast(0) }
    }

    @Synchronized fun duration(key: String): Long? = durations[key]

    companion object {
        val shared = ThinkingClock()
    }
}

// ─────────────────────────────────────────────────────────────────
//  ThinkingTrace — 紧凑思考指示器（气泡内联版本）
// ─────────────────────────────────────────────────────────────────

@Composable
internal fun ThinkingTrace(
    reasoning: String,
    isGenerating: Boolean,
    fontSize: Int,
    modifier: Modifier = Modifier,
    elapsedMs: Long? = null,
    initiallyExpanded: Boolean = false,
    /** 计时按此键（消息 id）记在进程内，离开会话再回来不从零开始；为空时只按本次组合计时。 */
    clockKey: String? = null,
    /** 思考进行中时标题前动画的阶段；为 null 时按流式。 */
    livePhase: com.promenar.infinity.ui.common.motion.GenerationMotionPhase? = null,
) {
    var internalExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    var measuredElapsed by remember(clockKey) { mutableStateOf(clockKey?.let(ThinkingClock.shared::duration)) }
    // 本次组合是否亲眼看到思考进行：只有这样结束时刻才可信；不在场时结束的不补记，避免把离开的时间算进去。
    var sawThinking by remember(clockKey) { mutableStateOf(false) }
    LaunchedEffect(isGenerating, clockKey) {
        fun now() = android.os.SystemClock.elapsedRealtime()
        if (isGenerating) {
            sawThinking = true
            val start = clockKey?.let { ThinkingClock.shared.start(it, now()) } ?: now()
            try {
                // 标题只显示到秒：按整秒边界每秒刷新一次。
                while (true) {
                    val elapsed = now() - start
                    measuredElapsed = elapsed
                    delay(1_000L - elapsed.mod(1_000L))
                }
            } finally {
                measuredElapsed = now() - start
            }
        } else if (clockKey != null) {
            if (sawThinking) ThinkingClock.shared.finish(clockKey, now())
            ThinkingClock.shared.duration(clockKey)?.let { measuredElapsed = it }
        }
    }
    val seconds = (elapsedMs ?: measuredElapsed)?.coerceAtLeast(0)?.div(1_000)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt()
    val summary = when {
        isGenerating -> stringResource(R.string.live_step_thinking_elapsed, liveClockText((seconds ?: 0).toLong()))
        seconds != null -> pluralStringResource(R.plurals.chat_step_thought_duration, seconds, seconds)
        else -> stringResource(R.string.chat_step_thought)
    }
    val previewRate = remember { ThinkingRateHolder() }
    Column(modifier = modifier.fillMaxWidth().testTag(UiTags.CHAT_THINKING_TRACE)) {
        CompactStepRow(
            segments = listOf(summary),
            icon = Icons.Rounded.CheckCircle,
            active = isGenerating,
            fontSize = fontSize,
            expanded = internalExpanded,
            state = stringResource(if (internalExpanded) R.string.common_state_expanded else R.string.common_state_collapsed),
            tag = UiTags.CHAT_THINKING_TOGGLE,
            clickLabel = stringResource(if (internalExpanded) R.string.common_cd_collapse else R.string.common_cd_expand),
            onClick = { internalExpanded = !internalExpanded },
            shimmerSpeed = { thinkingShimmerSpeed(previewRate.value) },
            preview = if (isGenerating) {{ ThinkingPreview(reasoning, previewRate) }} else null,
            // 思考行本身就是进行中的一步：生成动画挂在标题前。
            leading = if (isGenerating) {{ LiveStepLeading(livePhase ?: com.promenar.infinity.ui.common.motion.GenerationMotionPhase.STREAM) }} else null,
        )
        AnimatedVisibility(
            visible = internalExpanded && reasoning.isNotBlank(),
            enter = InfinityMotion.expandIn,
            exit = InfinityMotion.shrinkOut,
        ) {
            val ruleColor = MaterialTheme.colorScheme.outlineVariant
            Box(modifier = Modifier.fillMaxWidth().padding(bottom = ExpandedStepBottomPadding).testTag(UiTags.CHAT_THINKING_CONTENT)) {
                Box(Modifier.matchParentSize()) {
                    Spacer(Modifier.width(2.dp).fillMaxHeight().background(ruleColor))
                }
                MarkdownText(
                    markdown = reasoning,
                    fontSize = InfinityTypography.bodySmall.fontSize.value.toInt(),
                    overrideColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    showCursor = false,
                    modifier = Modifier.fillMaxWidth().padding(start = 14.dp),
                )
            }
        }
    }
}

/** 可见操作行 36dp，按钮使用独立的 48dp 触控区域。 */
@Composable
internal fun AssistantActionRow(
    modelDisplayName: String,
    timestamp: String,
    isGenerating: Boolean,
    onCopy: () -> Unit,
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    /** 完成态折叠后，在“更多”菜单中提供复制全部过程；为 null 时不显示该项。 */
    onCopyAllProcess: (() -> Unit)? = null,
    /** 生成统计工厂，打开浮卡时才调用；为 null 时模型名与时间不可点击。生成进行中整行不显示，因此不会弹出。 */
    stats: (() -> GenerationStatsCardModel)? = null,
    /** 本轮的记忆写入状态，显示在模型名之前；为 null 时不显示。 */
    memoryState: TurnMemoryState? = null,
    /** 本轮的图谱抽取状态；记忆已记入或未写入记忆时取代记忆状态显示。 */
    graphState: com.promenar.infinity.data.rag.TurnGraphState? = null,
    onOpenMemory: (() -> Unit)? = null,
) {
    // 生成结束后操作行淡入展开，重新生成时立即收起，不在流式输出期间占位。
    AnimatedVisibility(
        visible = !isGenerating,
        enter = fadeIn(tween(InfinityMotion.DurationMedium, delayMillis = InfinityMotion.DurationFadeOut)) +
            expandVertically(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)),
        exit = ExitTransition.None,
    ) {
        val haptics = LocalInfinityHaptics.current
        var showMore by remember { mutableStateOf(false) }
        var showStats by remember { mutableStateOf(false) }
        // 锚点坐标只在打开浮卡时读取，用普通引用持有，滚动时不触发重组。
        val statsAnchor = remember { StatsAnchorHolder() }
        val metaStyle = InfinityTypography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth().height(36.dp).testTag("CHAT_ASSISTANT_ACTIONS")) {
                Row(
                    modifier = Modifier.align(Alignment.Center).layout { measurable, constraints ->
                        val bleed = 17.5.dp.roundToPx()
                        val row = measurable.measure(constraints.copy(minWidth = constraints.maxWidth + bleed, maxWidth = constraints.maxWidth + bleed))
                        layout(constraints.maxWidth, row.height) { row.placeRelative(-bleed, 0) }
                    }.fillMaxWidth().wrapContentHeight(unbounded = true).height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = { haptics.perform(InfinityHapticEvent.CONFIRM); onCopy() }, modifier = Modifier.size(48.dp).testTag("CHAT_ASSISTANT_COPY")) {
                        Icon(Icons.Rounded.ContentCopy, stringResource(R.string.chat_action_copy), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    if (onRegenerate != null) {
                        IconButton(onClick = { haptics.perform(InfinityHapticEvent.CONFIRM); onRegenerate() }, modifier = Modifier.size(48.dp).testTag("CHAT_ASSISTANT_REGENERATE")) {
                            Icon(Icons.Rounded.Refresh, stringResource(R.string.chat_action_regenerate), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                    }
                    Box {
                        IconButton(
                            onClick = { showMore = true },
                            enabled = onBranch != null || onDelete != null || onCopyAllProcess != null,
                            modifier = Modifier.size(48.dp).testTag("CHAT_ASSISTANT_MORE"),
                        ) {
                            Icon(Icons.Rounded.MoreHoriz, stringResource(R.string.chat_step_more_actions), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        }
                        InfinityDropdownMenu(expanded = showMore, onDismissRequest = { showMore = false }) {
                            if (onCopyAllProcess != null) {
                                InfinityMenuItem(
                                    text = { Text(stringResource(R.string.turn_collapse_copy_all_process)) },
                                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.testTag(TURN_COPY_ALL_PROCESS_TAG),
                                    haptic = InfinityHapticEvent.CONFIRM,
                                    onClick = { onCopyAllProcess(); showMore = false },
                                )
                            }
                            if (onBranch != null) {
                                InfinityMenuItem(
                                    text = { Text(stringResource(R.string.chat_action_branch)) },
                                    leadingIcon = { Icon(Icons.Rounded.ForkRight, null, modifier = Modifier.size(18.dp)) },
                                    modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_BRANCH),
                                    onClick = { onBranch(); showMore = false },
                                )
                            }
                            if (onDelete != null) {
                                InfinityMenuItem(
                                    text = { Text(stringResource(R.string.chat_action_delete), color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) },
                                    onClick = { onDelete(); showMore = false },
                                )
                            }
                        }
                    }
                    BoxWithConstraints(modifier = Modifier.weight(1f)) {
                        val footerStatus = TurnFooterStatus(memoryState, graphState)
                        val memoryLabel = footerStatus.label()
                        // 宽度不足时记忆状态只保留图标：时间始终完整，模型名其次，最后才是状态文字。
                        val memoryTextFits = memoryLabel != null && turnMemoryTextFits(
                            maxWidth = maxWidth, style = metaStyle, memoryLabel = memoryLabel,
                            modelDisplayName = modelDisplayName, timestamp = timestamp,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                            if (!footerStatus.isEmpty && onOpenMemory != null) {
                                // “本轮记忆”面板由各轮共用，点按时把这一轮状态的位置交给面板。
                                val sharedMemoryAnchor = LocalChatPopoverAnchors.current?.turnMemory
                                val memoryAnchor = rememberInfinityPopoverAnchor()
                                TurnMemoryStatusLabel(
                                    footerStatus,
                                    onClick = {
                                        if (sharedMemoryAnchor?.consumeRecentDismiss() != true) {
                                            sharedMemoryAnchor?.pointAt(memoryAnchor)
                                            onOpenMemory()
                                        }
                                    },
                                    modifier = Modifier.infinityPopoverAnchor(memoryAnchor),
                                    showText = memoryTextFits,
                                )
                                Text(InfinityMetaSeparator, style = metaStyle, modifier = Modifier.padding(end = 4.dp).clearAndSetSemantics {})
                            }
                            val statsLabel = stringResource(R.string.generation_stats_open_action)
                            Box(
                                modifier = if (stats != null) {
                                    Modifier.onGloballyPositioned { statsAnchor.coordinates = it }
                                } else Modifier,
                            ) {
                                Row(
                                    modifier = Modifier
                                        .heightIn(min = InfinitySpacing.MinimumTouchTarget)
                                        .then(
                                            if (stats != null) {
                                                Modifier.clickable(onClickLabel = statsLabel, role = androidx.compose.ui.semantics.Role.Button) { showStats = true }
                                                    .testTag(UiTags.CHAT_ASSISTANT_STATS_TRIGGER)
                                            } else Modifier,
                                        ),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (modelDisplayName.isNotBlank()) {
                                        Text(
                                            text = modelDisplayName,
                                            style = metaStyle,
                                            modifier = Modifier.weight(1f, fill = false).testTag(UiTags.CHAT_ASSISTANT_MODEL_METADATA),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(InfinityMetaSeparator, style = metaStyle, modifier = Modifier.padding(horizontal = 4.dp).clearAndSetSemantics {})
                                    }
                                    Text(timestamp, style = metaStyle, modifier = Modifier.testTag(UiTags.CHAT_ASSISTANT_TIME_METADATA), maxLines = 1)
                                }
                                if (showStats && stats != null) {
                                    val model = remember { stats() }
                                    val anchorBounds = remember {
                                        statsAnchor.coordinates?.takeIf { it.isAttached }?.boundsInWindow()?.let {
                                            androidx.compose.ui.unit.IntRect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt())
                                        }
                                    }
                                    GenerationStatsPopup(
                                        model = model,
                                        anchorBoundsInWindow = anchorBounds,
                                        onDismissRequest = { showStats = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
}
}

/** 记忆状态文字、模型名（至少保留一段）与时间能否在 [maxWidth] 内同时排下。 */
@Composable
private fun turnMemoryTextFits(
    maxWidth: androidx.compose.ui.unit.Dp,
    style: androidx.compose.ui.text.TextStyle,
    memoryLabel: String,
    modelDisplayName: String,
    timestamp: String,
): Boolean {
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val density = androidx.compose.ui.platform.LocalDensity.current
    return remember(maxWidth, style, memoryLabel, modelDisplayName, timestamp, density) {
        fun width(text: String) = with(density) { measurer.measure(text, style, maxLines = 1).size.width.toDp() }
        val separator = width(InfinityMetaSeparator) + 8.dp
        val model = if (modelDisplayName.isBlank()) 0.dp else minOf(width(modelDisplayName), 72.dp) + separator
        // 图标、间距与左右内边距见 TurnMemoryStatusLabel。
        24.dp + width(memoryLabel) + separator + model + width(timestamp) <= maxWidth
    }
}

private class StatsAnchorHolder {
    var coordinates: androidx.compose.ui.layout.LayoutCoordinates? = null
}

@Composable
private fun toolSummaryCompletedText(part: ToolSummaryPart): String {
    val resId = when (part.action) {
        ToolSummaryAction.COMMAND -> R.plurals.chat_step_command_done
        ToolSummaryAction.WEB_SEARCH -> if (part.count == 1) R.plurals.chat_step_web_search_once else R.plurals.chat_step_web_search_done
        ToolSummaryAction.WEB_READ -> R.plurals.chat_step_web_read_done
        ToolSummaryAction.BROWSER -> R.plurals.chat_step_browser_done
        ToolSummaryAction.FILE_READ -> R.plurals.chat_step_file_read_done
        ToolSummaryAction.FILE_EDIT -> R.plurals.chat_step_file_edit_done
        ToolSummaryAction.FILE_TRANSFER -> R.plurals.chat_step_file_transfer_done
        ToolSummaryAction.KNOWLEDGE_SEARCH -> R.plurals.chat_step_knowledge_done
        ToolSummaryAction.MEMORY_SEARCH -> R.plurals.chat_step_memory_done
        ToolSummaryAction.MEMORY_WRITE -> R.plurals.chat_step_memory_write_done
        ToolSummaryAction.IMAGE -> R.plurals.chat_step_image_done
        ToolSummaryAction.MEDIA_VIEW -> R.plurals.chat_step_media_view_done
        ToolSummaryAction.MEDIA_FETCH -> R.plurals.chat_step_media_fetch_done
        ToolSummaryAction.PLAN -> R.plurals.chat_step_plan_done
        ToolSummaryAction.TIME -> R.plurals.chat_step_time_done
        ToolSummaryAction.CALCULATE -> R.plurals.chat_step_calculate_done
        ToolSummaryAction.SCHEDULE -> R.plurals.chat_step_schedule_done
        ToolSummaryAction.SKILL -> R.plurals.chat_step_skill_done
        ToolSummaryAction.SUBAGENT -> R.plurals.chat_step_subagent_done
        ToolSummaryAction.SECRET -> R.plurals.chat_step_secret_done
        ToolSummaryAction.OTHER -> R.plurals.chat_step_other_done
    }
    return pluralStringResource(resId, part.count, part.count)
}

@Composable
internal fun toolFriendlyName(toolName: String?): String = stringResource(when (toolSummaryAction(toolName)) {
    ToolSummaryAction.COMMAND -> R.string.chat_step_command_name
    ToolSummaryAction.WEB_SEARCH -> R.string.chat_step_web_search_name
    ToolSummaryAction.WEB_READ -> R.string.chat_step_web_read_name
    ToolSummaryAction.BROWSER -> R.string.chat_step_browser_name
    ToolSummaryAction.FILE_READ -> R.string.chat_step_file_read_name
    ToolSummaryAction.FILE_EDIT -> R.string.chat_step_file_edit_name
    ToolSummaryAction.FILE_TRANSFER -> R.string.chat_step_file_transfer_name
    ToolSummaryAction.KNOWLEDGE_SEARCH -> R.string.chat_step_knowledge_name
    ToolSummaryAction.MEMORY_SEARCH -> R.string.chat_step_memory_name
    ToolSummaryAction.MEMORY_WRITE -> R.string.chat_step_memory_write_name
    ToolSummaryAction.IMAGE -> R.string.chat_step_image_name
    ToolSummaryAction.MEDIA_VIEW -> R.string.chat_step_media_view_name
    ToolSummaryAction.MEDIA_FETCH -> R.string.chat_step_media_fetch_name
    ToolSummaryAction.PLAN -> R.string.chat_step_plan_name
    ToolSummaryAction.TIME -> R.string.chat_step_time_name
    ToolSummaryAction.CALCULATE -> R.string.chat_step_calculate_name
    ToolSummaryAction.SCHEDULE -> R.string.chat_step_schedule_name
    ToolSummaryAction.SKILL -> R.string.chat_step_skill_name
    ToolSummaryAction.SUBAGENT -> R.string.chat_step_subagent_name
    ToolSummaryAction.SECRET -> R.string.chat_step_secret_name
    ToolSummaryAction.OTHER -> R.string.chat_step_other_name
})

@Composable
internal fun toolGroupSummaryText(summary: ToolGroupSummary): String {
    if (summary.status == ToolSummaryStatus.PENDING) {
        return pluralStringResource(R.plurals.chat_step_pending_count, summary.pendingCount, summary.pendingCount)
    }
    if (summary.status == ToolSummaryStatus.INTERRUPTED) {
        return pluralStringResource(R.plurals.chat_step_interrupted_count, summary.pendingCount, summary.pendingCount)
    }
    summary.runningAction?.let { action ->
        return stringResource(when (action) {
            ToolSummaryAction.COMMAND -> R.string.chat_step_command_running
            ToolSummaryAction.WEB_SEARCH -> R.string.chat_step_web_search_running
            ToolSummaryAction.WEB_READ -> R.string.chat_step_web_read_running
            ToolSummaryAction.BROWSER -> R.string.chat_step_browser_running
            ToolSummaryAction.FILE_READ -> R.string.chat_step_file_read_running
            ToolSummaryAction.FILE_EDIT -> R.string.chat_step_file_edit_running
            ToolSummaryAction.FILE_TRANSFER -> R.string.chat_step_file_transfer_running
            ToolSummaryAction.KNOWLEDGE_SEARCH -> R.string.chat_step_knowledge_running
            ToolSummaryAction.MEMORY_SEARCH -> R.string.chat_step_memory_running
            ToolSummaryAction.MEMORY_WRITE -> R.string.chat_step_memory_write_running
            ToolSummaryAction.IMAGE -> R.string.chat_step_image_running
            ToolSummaryAction.MEDIA_VIEW -> R.string.chat_step_media_view_running
            ToolSummaryAction.MEDIA_FETCH -> R.string.chat_step_media_fetch_running
            ToolSummaryAction.PLAN -> R.string.chat_step_plan_running
            ToolSummaryAction.TIME -> R.string.chat_step_time_running
            ToolSummaryAction.CALCULATE -> R.string.chat_step_calculate_running
            ToolSummaryAction.SCHEDULE -> R.string.chat_step_schedule_running
            ToolSummaryAction.SKILL -> R.string.chat_step_skill_running
            ToolSummaryAction.SUBAGENT -> R.string.chat_step_subagent_running
            ToolSummaryAction.SECRET -> R.string.chat_step_secret_running
            ToolSummaryAction.OTHER -> R.string.chat_step_other_running
        })
    }
    if (summary.useGenericSummary || summary.parts.isEmpty()) {
        return pluralStringResource(R.plurals.chat_step_other_done, summary.count, summary.count)
    }
    val separator = stringResource(R.string.chat_step_summary_separator)
    return summary.parts.map { toolSummaryCompletedText(it) }.joinToString(separator)
}

@Composable
private fun stepDurationText(durationMs: Long): String = if (durationMs < 1_000) {
    pluralStringResource(R.plurals.chat_step_duration_ms, durationMs.toInt(), durationMs)
} else {
    pluralStringResource(
        R.plurals.chat_step_duration_seconds,
        (durationMs / 1_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        String.format(java.util.Locale.ROOT, "%.1f", durationMs / 1_000.0),
    )
}

// ─────────────────────────────────────────────────────────────────
//  InlineToolRow — 工具摘要与按需展开的调用详情
// ─────────────────────────────────────────────────────────────────

/** 步骤展开内容的底部留白。 */
internal val ExpandedStepBottomPadding = 8.dp

@Composable
internal fun InlineToolRow(
    steps: List<ExecutionStep>,
    isExecuting: Boolean,
    fontSize: Int,
    initiallyExpanded: Boolean = false,
    /** 由调用步骤 id 取回完整输出页所需的 (消息 id, 工具调用 id)；取不到时不显示入口。 */
    commandOutputTarget: (callStepId: String) -> Pair<String, String>? = { null },
    /** 本轮是否仍在进行；为 false 时未完成的调用显示为已中断而不是等待处理。 */
    turnActive: Boolean = true,
    /** 本行持有本轮唯一的进行中元素时非 null：标题前显示工具阶段的生成动画，标题后显示从调用起算的计时。 */
    live: LiveStep? = null,
) {
    var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val linuxNavigation = com.promenar.infinity.ui.linux.LocalLinuxNavigation.current
    val invocations = remember(steps) { toolInvocations(steps) }
    // 审批通过后的调用不在生成任务内执行，以实时输出通道判断它是否正在运行。
    val liveKeys = LocalToolLiveOutput.current?.activeKeys?.collectAsState()?.value.orEmpty()
    val liveInvocations = activeToolOutputs(invocations, liveKeys)
    val liveRunning = liveInvocations.isNotEmpty()
    val executing = isExecuting || liveRunning
    val summary = remember(invocations, executing, turnActive) { toolGroupDisplaySummary(invocations, executing, turnActive) }
    val fileChanges = remember(invocations) { toolGroupFileChanges(invocations) }
    val runtimeChanges = remember(invocations) { toolGroupRuntimeChanges(invocations) }
    var openedChange by remember { mutableStateOf<com.promenar.infinity.data.model.FileChangeSummary?>(null) }
    val showFileTitle = fileChanges.isNotEmpty() && summary.runningAction == null &&
        summary.status != ToolSummaryStatus.PENDING && summary.status != ToolSummaryStatus.INTERRUPTED
    val stateSummaries = invocations.mapNotNull(::stateToolSummary).map { stateToolSummaryText(it) }
    val stateOnly = stateSummaries.size == invocations.size && stateSummaries.isNotEmpty()
    val memoryChanges = remember(invocations) { invocations.mapNotNull(::memoryChange) }
    // 只有记忆写入时用逐条“已记住”行代替摘要，混合工具时追加在摘要下方。
    val memoryOnly = memoryChanges.isNotEmpty() && memoryChanges.size == invocations.size
    // 只有浏览器调用时逐条显示“打开网页 · 主机”等步骤行，点按打开浏览器。
    val browserSteps = remember(invocations) { invocations.mapNotNull(::browserStep) }
    val browserOnly = browserSteps.isNotEmpty() && browserSteps.size == invocations.size
    // 知识检索逐条显示结果数，点按打开检索详情；混合工具时追加在摘要下方。
    val knowledgeSteps = remember(invocations) { invocations.mapNotNull(::knowledgeSearchStep) }
    val knowledgeOnly = knowledgeSteps.isNotEmpty() && knowledgeSteps.size == invocations.size
    val jobLabels = LocalBackgroundJobLabels.current
    // 只有一条命令在跑时，摘要直接说它在做什么或在等哪个后台任务。
    val runningCommand = if (summary.runningAction == ToolSummaryAction.COMMAND) {
        invocations.singleOrNull { !it.complete }?.let { commandStepLabel(it.name, it.call?.toolArgs, jobLabels) }
    } else null
    val runningCommandText = runningCommand?.let { runningCommandText(it) }
    // 单个计划或任务查看调用：摘要直接说明这次改了什么、任务处于什么状态。
    val cardSummaryText = toolCardSummaryText(invocations, jobLabels)
    val segments = buildList<CharSequence> {
        if (stateSummaries.isNotEmpty()) add(stateSummaries.joinToString("，"))
        // 文件改动只来自成功的文件工具：全是文件编辑时标题直接给出文件与行数，混合工具时作为补充段。
        if (!stateOnly && (!showFileTitle || !summary.isFileEditOnly)) add(runningCommandText ?: cardSummaryText ?: toolGroupSummaryText(summary))
        if (showFileTitle) add(com.promenar.infinity.ui.chat.diff.fileChangesStyledTitle(fileChanges))
        if (summary.failedCount == 0 && summary.runningAction == null) summary.durationMs?.let { add(stepDurationText(it)) }
    }
    openedChange?.let { change ->
        com.promenar.infinity.ui.chat.diff.FileChangeDiffSheet(change, onDismissRequest = { openedChange = null })
    }
    val liveStartedAt = live?.startedAt
    Column(modifier = Modifier.fillMaxWidth()) {
        // 逐条显示的工具没有标题行，进行中时在上方单独显示动画与计时。
        if (live != null && (memoryOnly || browserOnly || knowledgeOnly)) LiveToolTicker(live)
        if (memoryOnly) MemoryChangeRows(memoryChanges) else if (browserOnly) BrowserStepRows(browserSteps, fontSize)
        else if (knowledgeOnly) KnowledgeSearchStepRows(knowledgeSteps, fontSize) else CompactStepRow(
            segments = segments,
            icon = Icons.Rounded.CheckCircle,
            active = summary.status == ToolSummaryStatus.RUNNING,
            fontSize = fontSize,
            expanded = isExpanded,
            state = stringResource(if (isExpanded) R.string.common_state_expanded else R.string.common_state_collapsed),
            clickLabel = stringResource(if (isExpanded) R.string.common_cd_collapse else R.string.common_cd_expand),
            tag = if (stateOnly) com.promenar.infinity.ui.testing.UiTags.CHAT_STATE_SUMMARY else "CHAT_TOOL_TOGGLE",
            failureText = if (summary.failedCount > 0) pluralStringResource(R.plurals.chat_step_failed_count, summary.failedCount, summary.failedCount) else null,
            onClick = { isExpanded = !isExpanded },
            leading = if (live != null) {{ LiveStepLeading(live.motionPhase) }} else null,
            trailing = if (liveStartedAt != null) {{ LiveStepTrailing(liveStartedAt) }} else null,
        )
        // 关键数据变化不随详情折叠，避免用户错过。
        if (runtimeChanges.isNotEmpty()) RuntimeChangesNotice(runtimeChanges)
        if (!memoryOnly && memoryChanges.isNotEmpty()) MemoryChangeRows(memoryChanges)
        if (!knowledgeOnly) knowledgeSteps.filter { it.data != null }.takeIf { it.isNotEmpty() }?.let { KnowledgeSearchStepRows(it, fontSize) }
        // 子代理分组卡始终跟在摘要下方，随轮次一起折叠。
        invocations.filter { it.name == com.promenar.infinity.data.subagent.SubagentLimits.TOOL_NAME }.forEach { invocation ->
            invocation.call?.id?.let { stepId -> key("subagent", stepId) { SubagentGroupCard(unscopedStepId(stepId)) } }
        }
        if (executing) {
            // 运行中的命令在摘要下方显示实时输出，结束后由结果展示接替。
            liveInvocations.forEach { invocation ->
                invocation.call?.id?.let { stepId -> key(stepId) { RunningToolLiveOutput(unscopedStepId(stepId)) } }
            }
        }
        AnimatedVisibility(visible = isExpanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            // 展开内容底部 8dp，与流水线块间 8dp 合计 16dp，与段落间距一致。
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = ExpandedStepBottomPadding).testTag("CHAT_TOOL_DETAILS"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                invocations.forEach { invocation ->
                    key(invocation.steps.firstOrNull()?.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val commandStep = remember(invocation, jobLabels) { commandStepLabel(invocation.name, invocation.call?.toolArgs, jobLabels) }
                            val heading = commandStep?.let { commandStepHeading(it) }
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (heading != null) {
                                    // 有短标题或能认出所操作的任务时，标题占主位，工具名退为小字。
                                    InfinityMetaLine(heading, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.weight(1f), maxLines = 2)
                                    invocation.name?.let { name ->
                                        Text(name, style = InfinityTypography.labelSmall.copy(fontSize = 11.sp, fontFamily = InfinityMonospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                } else {
                                    Text(toolFriendlyName(invocation.name), style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                                    invocation.name?.let { name ->
                                        Text(
                                            name,
                                            style = InfinityTypography.labelSmall.copy(fontSize = 11.sp, fontFamily = InfinityMonospace),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                if (invocation.ranInLinux) LinuxRuntimeChip()
                                invocation.durationMs?.let { Text(stepDurationText(it), style = InfinityTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                            val outputTarget = remember(invocation) {
                                invocation.call?.id?.takeIf { invocation.complete && invocation.isCommand }?.let(commandOutputTarget)
                            }
                            if (linuxNavigation != null && outputTarget != null) {
                                TextButton(
                                    onClick = { linuxNavigation.openCommandOutput(outputTarget.first, outputTarget.second) },
                                    contentPadding = PaddingValues(horizontal = InfinitySpacing.Small),
                                    modifier = Modifier.heightIn(min = InfinitySpacing.MinimumTouchTarget).testTag(UiTags.CHAT_TOOL_FULL_OUTPUT),
                                ) { Text(stringResource(R.string.chat_tool_full_output), style = InfinityTypography.labelLarge) }
                            }
                            val invocationChanges = remember(invocation) { invocation.fileChanges }
                            if (invocationChanges.isNotEmpty()) {
                                com.promenar.infinity.ui.chat.diff.FileChangeList(invocationChanges, onOpen = { openedChange = it })
                            }
                            val workspaceChanges = remember(invocation) { invocation.workspaceChanges }
                            workspaceChanges?.let { com.promenar.infinity.ui.chat.diff.WorkspaceChangesList(it) }
                            commandStep?.waitLimitSeconds?.let { limit ->
                                JobWaitProgress(limit, startedAt = invocation.call?.timestamp, running = !invocation.complete)
                            }
                            // 计划与任务查看调用改用专用卡片；其余工具的 JSON 参数与结果按字段列出。
                            val planCard = rememberPlanCardModel(invocation)
                            val jobCard = if (planCard == null) rememberJobCardModel(invocation) else null
                            val fieldFallback = planCard == null && jobCard == null && usesFieldFallback(invocation.name)
                            if (planCard != null) {
                                PlanToolCard(
                                    model = planCard,
                                    arguments = invocation.call?.toolArgs,
                                    results = invocation.results.mapNotNull { it.content },
                                )
                            } else if (jobCard != null) {
                                JobToolCard(jobCard)
                            }
                            // 等待、查看、停止任务的参数只有任务号，标题已说明对象，不再显示原始参数。
                            val argumentsShown = planCard == null &&
                                commandStep?.kind !in setOf(CommandStepKind.WAIT, CommandStepKind.STATUS, CommandStepKind.STOP)
                            invocation.call?.toolArgs?.takeIf { argumentsShown && it.isNotBlank() }?.let { args ->
                                val fields = remember(args, fieldFallback) { if (fieldFallback) toolFieldRows(args) else null }
                                if (fields != null) ToolFieldList(fields, arguments = true)
                                else ExpandableToolText(text = args, collapsedLines = 6, arguments = true)
                            }
                            invocation.results.forEach { result ->
                                val media = result.media
                                // 新结果以工作区媒体引用承载；旧数据仍从 data 字段取图片地址。
                                val images = remember(result.data, media) {
                                    if (media.isNullOrEmpty()) extractToolImageModels(result.data) else emptyList()
                                }
                                if (!media.isNullOrEmpty()) {
                                    com.promenar.infinity.ui.media.ToolMediaList(media = media)
                                }
                                images.take(4).forEach { imageModel ->
                                    ChatImageThumbnail(
                                        imageModel = imageModel,
                                        contentDescription = stringResource(R.string.chat_cd_tool_image),
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp).clip(MaterialTheme.shapes.small),
                                        contentScale = ContentScale.Fit,
                                    )
                                }
                                result.content?.takeIf(String::isNotBlank)?.let { content ->
                                    if (result.type == "error") ToolErrorBlock(content)
                                    else if (planCard == null && jobCard == null) {
                                        val fields = remember(content, fieldFallback) { if (fieldFallback) toolFieldRows(content) else null }
                                        if (fields != null) ToolFieldList(fields, arguments = false)
                                        else ExpandableToolText(text = content, collapsedLines = 12)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 运行中摘要：有短标题的命令说明在做什么，等待任务说明在等哪个；其余返回 null 沿用通用文案。 */
@Composable
private fun runningCommandText(step: CommandStepLabel): String? = when (step.kind) {
    CommandStepKind.RUN -> step.label?.let { stringResource(R.string.chat_step_command_running_titled, it) }
    CommandStepKind.WAIT -> stringResource(R.string.chat_step_job_wait_running, commandJobName(step))
    else -> null
}

/** 明细标题分段：命令为短标题；任务操作为“动作、任务名”两段，由 [InfinityMetaLine] 排版。没有可说的内容时返回 null，沿用工具通称。 */
@Composable
private fun commandStepHeading(step: CommandStepLabel): List<String>? = when (step.kind) {
    CommandStepKind.RUN -> step.label?.let(::listOf)
    else -> {
        val action = stringResource(when (step.kind) {
            CommandStepKind.WAIT -> R.string.chat_step_job_wait_name
            CommandStepKind.STATUS -> R.string.chat_step_job_status_name
            CommandStepKind.STOP -> R.string.chat_step_job_stop_name
            else -> R.string.chat_step_job_input_name
        })
        listOf(action, commandJobName(step))
    }
}

/** 任务名：找到记录时为短标题或命令原文，否则为短任务号。 */
@Composable
private fun commandJobName(step: CommandStepLabel): String = step.label
    ?: step.jobId?.let { stringResource(R.string.chat_step_job_unknown, shortJobId(it)) }
    ?: stringResource(R.string.chat_step_job_wait_name)

/** job_wait 的等待进度：运行中每秒刷新“已等待 / 最长”，结束后只留最长等待时间。 */
@Composable
private fun JobWaitProgress(limitSeconds: Int, startedAt: Long?, running: Boolean) {
    val limit = if (limitSeconds % 60 == 0) stringResource(R.string.chat_step_job_wait_limit_minutes, limitSeconds / 60)
        else stringResource(R.string.chat_step_job_wait_limit_seconds, limitSeconds)
    val elapsedSeconds by produceState(initialValue = -1L, running, startedAt) {
        if (!running || startedAt == null || startedAt <= 0) { value = -1L; return@produceState }
        while (true) {
            value = ((System.currentTimeMillis() - startedAt) / 1000).coerceIn(0, limitSeconds.toLong())
            delay(1_000)
        }
    }
    val text = if (elapsedSeconds >= 0) {
        stringResource(R.string.chat_step_job_wait_elapsed, clockText(elapsedSeconds), clockText(limitSeconds.toLong()))
    } else limit
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 等待中挂工具阶段的生成动画，结束后只留最长等待时间。
        if (elapsedSeconds >= 0) LiveStepDots(com.promenar.infinity.ui.common.motion.GenerationMotionPhase.TOOL, Modifier.padding(end = 6.dp))
        Text(text, style = InfinityTypography.labelSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun clockText(seconds: Long): String = "%d:%02d".format(seconds / 60, seconds % 60)

/** 标明这条命令在内置 Linux 环境中运行，与系统 shell 的结果区分。 */
@Composable
private fun LinuxRuntimeChip() {
    Text(
        stringResource(R.string.linux_runner_name),
        style = InfinityTypography.labelSmall,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        maxLines = 1,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/** 命令改动了运行时关键数据时的提醒：列出前几项位置，其余以数量概括。 */
@Composable
internal fun RuntimeChangesNotice(changes: List<String>, modifier: Modifier = Modifier) {
    val shown = changes.take(RuntimeChangesVisibleLimit)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .testTag(UiTags.CHAT_RUNTIME_CHANGES),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            Icons.Rounded.Warning,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 1.dp).size(16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.chat_runtime_changes_title),
                style = InfinityTypography.labelMedium,
                color = MaterialTheme.colorScheme.error,
            )
            shown.forEach { change ->
                Text(
                    change,
                    style = InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (changes.size > shown.size) {
                val more = changes.size - shown.size
                Text(
                    pluralStringResource(R.plurals.chat_runtime_changes_more, more, more),
                    style = InfinityTypography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private const val RuntimeChangesVisibleLimit = 5

@Composable
internal fun ExpandableToolText(
    text: String,
    collapsedLines: Int,
    arguments: Boolean = false,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val explicitLineOverflow = remember(text, collapsedLines) {
        text.lineSequence().take(collapsedLines + 1).count() > collapsedLines
    }
    var overflows by remember(text, collapsedLines) { mutableStateOf(explicitLineOverflow) }
    Column(
        modifier = if (arguments) Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(8.dp)).padding(10.dp)
        else Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = if (arguments) InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp, lineHeight = 18.sp)
                else InfinityTypography.bodySmall.forReading(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = explicitLineOverflow || it.hasVisualOverflow },
            modifier = Modifier.testTag(if (arguments) "CHAT_TOOL_ARGUMENTS" else "CHAT_TOOL_RESULT"),
        )
        if (overflows || expanded) {
            ChatExpandLink(
                text = stringResource(if (expanded) R.string.chat_step_collapse_all else R.string.chat_step_expand_all),
                onClick = { expanded = !expanded }, modifier = Modifier.testTag("CHAT_TOOL_EXPAND_ALL"),
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
//  ContentSegment — 正文渲染
// ─────────────────────────────────────────────────────────────────

/**
 * 助手正文。生成结束后长按直接进入系统文字选择（复制、全选、引用），正文不再弹消息菜单：
 * 复制、重新生成、分支与删除由本轮末尾的操作栏提供。生成中不开放选择，避免选区随文字增长跳动。
 */
@Composable
internal fun ContentSegment(
    content: String,
    isStreaming: Boolean,
    showStreamingCursor: Boolean,
    fontSize: Int,
    onContentChange: ((String) -> Unit)?,
    /** 正文所属消息；引用时作为来源，为 null 时工具条不出现“引用”。 */
    sourceMessageId: String? = null,
) {
    val body = @Composable {
        MarkdownText(
            markdown = content,
            isStreaming = isStreaming,
            showCursor = showStreamingCursor,
            fontSize = fontSize,
            onContentChange = onContentChange,
            modifier = Modifier.fillMaxWidth()
        )
    }
    Box(modifier = Modifier.fillMaxWidth().testTag("CHAT_ASSISTANT_BODY")) {
        if (isStreaming) body() else QuotableSelectionContainer(sourceMessageId) { body() }
    }
}

// ─────────────────────────────────────────────────────────────────
//  工具图片结果解析
// ─────────────────────────────────────────────────────────────────

private fun extractToolImageModels(data: String?): List<String> {
    if (data.isNullOrBlank()) return emptyList()
    val raw = data.trim()
    if (raw.startsWith("data:image/") || raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("/")) {
        return listOf(raw)
    }

    fun JSONObject.imageModel(): String? {
        val localPath = optString("localPath").takeIf { it.isNotBlank() && it != "null" }
        val url = optString("url").takeIf { it.isNotBlank() && it != "null" }
        val b64 = optString("b64Json").takeIf { it.isNotBlank() && it != "null" }
        return localPath ?: url ?: b64?.let { "data:image/png;base64,$it" }
    }

    return runCatching {
        if (raw.startsWith("[")) {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.imageModel()
            }
        } else {
            listOfNotNull(JSONObject(raw).imageModel())
        }
    }.getOrDefault(emptyList())
}

// ─────────────────────────────────────────────────────────────────
//  UserMessageBubble — 用户消息（从 ChatBubble 抽取）
// ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun UserMessageBubble(
    message: Message,
    fontSize: Int,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val menuTouch = rememberInfinityMenuTouch()
    var showSelection by remember { mutableStateOf(false) }
    val timestamp = remember(message.createdAt) {
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(message.createdAt))
    }
    val legacyAttachments by produceState(
        initialValue = emptyList(), key1 = message.legacyAttachmentsPayload, key2 = message.userImages,
    ) {
        val parsed = withContext(Dispatchers.Default) {
            LegacyAttachmentCodec.parse(message.legacyAttachmentsPayload).filterNot { item ->
                item.type == "IMAGE" && item.uri in message.userImages.orEmpty()
            }
        }
        // 回到主线程再写状态：效果协程的调度器不保证切回主线程（测试环境为非受限调度器），
        // 后台线程的写入若恰好落在组合读取之间，变更通知会丢失，附件区永远停在空列表。
        withContext(Dispatchers.Main.immediate) { value = parsed }
    }
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val bubbleMaxWidth = maxWidth * 0.82f
        Box(modifier = Modifier.align(Alignment.TopEnd).widthIn(max = bubbleMaxWidth)) {
            Column(
                modifier = Modifier.infinityMenuTouch(menuTouch).combinedClickable(interactionSource = null, indication = null, onClick = {}, onLongClick = { showMenu = true }),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (!message.userDocuments.isNullOrEmpty()) MessageDocumentSummary(message.userDocuments.orEmpty())
                if (!message.userImages.isNullOrEmpty()) {
                    Row(
                        modifier = Modifier.widthIn(max = bubbleMaxWidth).horizontalScroll(rememberScrollState()).testTag("CHAT_USER_IMAGES"),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        message.userImages.orEmpty().forEach { dataUrl ->
                            ChatImageThumbnail(
                                imageModel = dataUrl,
                                contentDescription = stringResource(R.string.chat_cd_attached_image),
                                modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
                if (legacyAttachments.isNotEmpty()) LegacyAttachmentSummary(legacyAttachments)
                UserMessageQuotes(message.quotes.orEmpty(), maxWidth = bubbleMaxWidth, fontSize = fontSize)
                if (message.content.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.widthIn(max = bubbleMaxWidth).testTag("CHAT_USER_BUBBLE"),
                    ) {
                        Text(
                            text = message.content,
                            style = InfinityTypography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5).sp).forReading(),
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            MessageContextMenu(
                expanded = showMenu,
                onDismiss = { showMenu = false },
                touch = menuTouch,
                onCopy = { onCopy?.invoke() ?: copyToClipboard(context, message.content); showMenu = false },
                onSelectText = if (message.content.isNotBlank()) ({ showSelection = true }) else null,
                onRegenerate = onRegenerate,
                onBranch = onBranch,
                onDelete = { onDelete?.invoke(); showMenu = false },
                isUser = true,
                timestamp = timestamp,
            )
        }
    }
    MessageTextSelectionSheet(
        show = showSelection, text = message.content, sourceMessageId = message.id, fontSize = fontSize,
        onDismiss = { showSelection = false },
    )
}

/** 用户气泡的长按留给消息菜单，自由选字放进底部面板：长按或双击文字即可选择；选中后可以引用，引用后面板收起。 */
@Composable
private fun MessageTextSelectionSheet(show: Boolean, text: String, sourceMessageId: String, fontSize: Int, onDismiss: () -> Unit) {
    InfinityBottomSheet(
        show = show,
        onDismiss = onDismiss,
        title = stringResource(R.string.chat_action_select_text),
        scrollContent = true,
        modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_SELECTION_SHEET),
    ) {
        QuotableSelectionContainer(sourceMessageId, onQuoted = onDismiss) {
            Text(
                text = text,
                style = InfinityTypography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5).sp).forReading(),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small),
            )
        }
    }
}

@Composable
private fun LegacyAttachmentSummary(items: List<LegacyAttachmentItem>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .testTag(UiTags.CHAT_LEGACY_ATTACHMENTS),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(stringResource(R.string.chat_legacy_attachments_title), style = InfinityTypography.labelMedium)
        items.forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(UiTags.CHAT_LEGACY_ATTACHMENT_ENTRY)
                    .clickable {
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { LegacyAttachmentCodec.open(context, item) }
                            when (result) {
                                is LegacyAttachmentOpenResult.Ready -> runCatching { context.startActivity(result.intent) }
                                    .onFailure {
                                        Toast.makeText(context, R.string.chat_legacy_attachment_unreadable, Toast.LENGTH_SHORT).show()
                                    }
                                is LegacyAttachmentOpenResult.Unreadable ->
                                    Toast.makeText(context, R.string.chat_legacy_attachment_unreadable, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(vertical = InfinitySpacing.XSmall),
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.AttachFile, contentDescription = null, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.fileName, style = InfinityTypography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (item.unreadableReason == null) item.mimeType else stringResource(R.string.chat_legacy_attachment_unreadable),
                        style = InfinityTypography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChatImageThumbnail(
    imageModel: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    var showLightbox by remember(imageModel) { mutableStateOf(false) }
    coil3.compose.AsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        modifier = modifier.clickable { showLightbox = true },
        contentScale = contentScale,
    )
    if (showLightbox) {
        ImageLightbox(imageUrl = imageModel, onDismiss = { showLightbox = false })
    }
}

@Composable
private fun MessageDocumentSummary(documents: List<MessageDocumentAttachment>) {
    Column(
        modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_DOCUMENTS),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        documents.forEach { document ->
            Row(
                modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow, RoundedCornerShape(12.dp)).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Description, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.widthIn(max = 220.dp)) {
                    Text(document.name, style = InfinityTypography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(formatMessageDocumentBytes(document.sizeBytes), style = InfinityTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun formatMessageDocumentBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
}

// ─────────────────────────────────────────────────────────────────
//  GenerationIndicator — 本轮生成期间的生成状态指示
// ─────────────────────────────────────────────────────────────────

@Composable
private fun GenerationIndicator(phase: com.promenar.infinity.ui.common.motion.GenerationMotionPhase) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .padding(vertical = 4.dp)
            .testTag(UiTags.CHAT_GENERATING_INDICATOR),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LiveStepDots(phase, dotSize = 6.dp)
    }
}

// ─────────────────────────────────────────────────────────────────
//  MessageContextMenu — 原生 MD3 消息上下文菜单
// ─────────────────────────────────────────────────────────────────

@Composable
fun MessageContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCopy: () -> Unit,
    onSelectText: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onDelete: () -> Unit,
    isUser: Boolean = false,
    offset: DpOffset = DpOffset.Zero,
    timestamp: String? = null,
    touch: InfinityMenuTouch? = null,
) {
    InfinityDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        offset = offset,
        touch = touch,
    ) {
        if (timestamp != null) {
            InfinityMenuItem(text = { Text(timestamp, style = InfinityTypography.labelSmall) }, onClick = {}, enabled = false)
        }
        InfinityMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.ContentCopy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            },
            text = { Text(stringResource(R.string.chat_action_copy), style = InfinityTypography.labelLarge) },
            haptic = InfinityHapticEvent.CONFIRM,
            onClick = {
                onCopy()
                onDismiss()
            }
        )
        if (onSelectText != null) {
            InfinityMenuItem(
                modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_SELECT_TEXT),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.TextFields,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = { Text(stringResource(R.string.chat_action_select_text), style = InfinityTypography.labelLarge) },
                onClick = {
                    onSelectText()
                    onDismiss()
                },
            )
        }
        
        if (onRegenerate != null) {
            InfinityMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = { 
                    Text(
                        text = if (isUser) {
                            stringResource(R.string.chat_action_resend)
                        } else {
                            stringResource(R.string.chat_action_regenerate)
                        },
                        style = InfinityTypography.labelLarge,
                    ) 
                },
                onClick = {
                    onRegenerate()
                    onDismiss()
                }
            )
        }

        if (onBranch != null) {
            InfinityMenuItem(
                modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_BRANCH),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.ForkRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = {
                    Text(
                        stringResource(R.string.chat_action_branch),
                        style = InfinityTypography.labelLarge,
                    )
                },
                onClick = {
                    onBranch()
                    onDismiss()
                },
            )
        }

        InfinityMenuDivider()
        InfinityMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Rounded.DeleteOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
            },
            text = { 
                Text(
                    text = stringResource(R.string.chat_action_delete),
                    style = InfinityTypography.labelLarge,
                    color = MaterialTheme.colorScheme.error
                ) 
            },
            onClick = {
                onDelete()
                onDismiss()
            }
        )
    }
}

fun copyToClipboard(context: android.content.Context, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    val clip = android.content.ClipData.newPlainText("InfinityMessage", text)
    clipboard.setPrimaryClip(clip)
}

/** 工作区切换提示：居中的一行次要文字，不带操作菜单。 */
@Composable
internal fun WorkspaceSwitchNoticeRow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Small)
            .semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite },
    )
}
