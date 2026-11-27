package com.promenar.infinity.ui.chat

import androidx.compose.foundation.interaction.MutableInteractionSource
import com.promenar.infinity.ui.common.dimOnPress
import com.promenar.infinity.ui.theme.InfinityMonospace
import androidx.compose.material.icons.rounded.TextFields
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.data.generation.isContextCompactionNotice
import com.promenar.infinity.data.workspace.isWorkspaceSwitchNotice
import androidx.compose.animation.*
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.forReading
import com.promenar.infinity.ui.theme.keepCjkTailTogether
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import com.promenar.infinity.ui.common.InlineEditorKeyAction
import com.promenar.infinity.ui.common.infinityContextClickable
import com.promenar.infinity.ui.common.infinityImeSafeKeyEvent
import com.promenar.infinity.ui.common.resolveInlineEditorKeyAction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
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
import com.promenar.infinity.data.model.MessageQuote
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.session.EditedUserMessagePayload
import com.promenar.infinity.data.local.db.recovery.LegacyAttachmentCodec
import com.promenar.infinity.data.local.db.recovery.LegacyAttachmentItem
import com.promenar.infinity.data.local.db.recovery.LegacyAttachmentOpenResult
import com.promenar.infinity.ui.common.GenerationDots
import com.promenar.infinity.ui.common.MarkdownText
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.status.UiStatusNotice
import com.promenar.infinity.ui.renderer.ImageLightbox
import com.promenar.infinity.ui.common.obsidianTopGlint
import com.promenar.infinity.ui.theme.InfinityObsidianDialogChip
import com.promenar.infinity.ui.theme.InfinityObsidianDialogContainer
import com.promenar.infinity.ui.theme.InfinityObsidianSolidContainer
import com.promenar.infinity.ui.theme.InfinityPureWhite
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
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
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillMenuButton
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.Settings
import com.promenar.infinity.ui.common.InfinityPillTone
import androidx.compose.material.icons.automirrored.rounded.OpenInNew

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
        if (msg.isWorkspaceSwitchNotice || msg.isContextCompactionNotice) {
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
                !messages[i].isWorkspaceSwitchNotice && !messages[i].isContextCompactionNotice) {
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
    streamingMessageId: String? = null,
    fontSize: Int,
    modelDisplayNames: Map<String, String> = emptyMap(),
    /** 服务商 ID → 显示名，用于生成统计浮卡。 */
    providerDisplayNames: Map<String, String> = emptyMap(),
    onContentChange: ((String) -> Unit)? = null,
    onCopy: ((String) -> Unit)? = null,
    onReadAloud: ((String, String) -> Unit)? = null,
    readAloudPhase: ReadAloudPhase = ReadAloudPhase.IDLE,
    onRegenerate: ((String) -> Unit)? = null,
    onDelete: ((String) -> Unit)? = null,
    onBranch: ((String) -> Unit)? = null,
    subsequentUserTurns: Int = 0,
    onEditUserMessage: ((String, EditedUserMessagePayload) -> Unit)? = null,
    onBranchWithEdit: ((String, EditedUserMessagePayload) -> Unit)? = null,
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
    ragContent: @Composable () -> Unit = {},
) {
    if (group.isNotice) {
        val first = group.messages.first()
        if (first.isContextCompactionNotice) {
            ContextCompactionTimelineDivider(delta = first.content, modifier = modifier)
        } else {
            WorkspaceSwitchNoticeRow(text = first.content, modifier = modifier)
        }
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
            subsequentUserTurns = subsequentUserTurns,
            onDelete = { onDelete?.invoke(msg.id) },
            onCopy = messageCopyOverride(onCopy, msg.content),
            onRegenerate = onRegenerate?.let { handler -> { handler(msg.id) } },
            onBranch = onBranch?.let { handler -> { handler(msg.id) } },
            onEditSubmit = onEditUserMessage?.let { handler -> { payload -> handler(msg.id, payload) } },
            onBranchWithEdit = onBranchWithEdit?.let { handler -> { payload -> handler(msg.id, payload) } },
        )
        return
    }

    val renderPlan = remember(
        group.messages,
        isGenerating,
        streamingContent.isNotEmpty(),
        streamingMessageId,
        hasPendingApproval,
    ) {
        buildPipelineRenderPlan(
            messages = group.messages,
            isGenerating = isGenerating,
            hasStreamingContent = streamingContent.isNotEmpty(),
            hasPendingApproval = hasPendingApproval,
            streamingMessageId = streamingMessageId,
        )
    }
    val allSteps = renderPlan.steps
    val finalAssistantId = group.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id
    val knownUuidPaths = remember(group.messages) { extractKnownUuidPaths(group.messages) }
    val unrecordedStreamingCalls = remember(group.messages) { unrecordedStreamingToolCalls(group.messages) }
    val lastStepIsToolExec = allSteps.lastOrNull() is PipelineStep.ToolExec
    // 首轮尚未落库成 ToolExec 时的流式文件编辑行：直接替代流末尾“正在准备工具调用”，与落库后的 ToolExec 等高接棒。
    val standaloneStreamingFileBatch = remember(
        lastStepIsToolExec, unrecordedStreamingCalls, knownUuidPaths, isGenerating, hasPendingApproval, liveStep,
    ) {
        if (lastStepIsToolExec || !isGenerating || hasPendingApproval) null
        else buildLiveFileEditBatch(
            invocations = emptyList(),
            streamingCalls = unrecordedStreamingCalls,
            knownUuidPaths = knownUuidPaths,
            isTurnGenerating = isGenerating,
            isLastStepInTurn = true,
            hasPendingApproval = hasPendingApproval,
            liveStep = liveStep,
        )?.takeIf { it.isLiveBatch && it.hasActiveRow }
    }
    val tailToolExecFileBatch = remember(
        allSteps, unrecordedStreamingCalls, knownUuidPaths, isGenerating, hasPendingApproval, liveStep,
    ) {
        val lastToolStep = allSteps.lastOrNull() as? PipelineStep.ToolExec ?: return@remember null
        val asks = askUserSteps(lastToolStep.steps)
        val branches = branchPlanSteps(lastToolStep.steps)
        val hidden = asks.hidden + branches.hidden + sessionSendHiddenSteps(lastToolStep.steps)
        val toolSteps = if (hidden.isEmpty()) lastToolStep.steps else lastToolStep.steps.filterNot { it.id in hidden }
        if (toolSteps.isEmpty()) return@remember null
        buildLiveFileEditBatch(
            invocations = toolInvocations(toolSteps),
            streamingCalls = unrecordedStreamingCalls,
            knownUuidPaths = knownUuidPaths,
            isTurnGenerating = isGenerating,
            isLastStepInTurn = true,
            hasPendingApproval = hasPendingApproval,
            liveStep = liveStep,
        )
    }
    val liveFileEditOwnsTail = standaloneStreamingFileBatch != null ||
        (tailToolExecFileBatch?.let { it.isLiveBatch && it.hasActiveRow } == true)
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
    // 本次组合中是否见过这一轮以平铺形式进行；只作结束收起时是否播放过渡的判断，不触发重组。
    val sawLiveTurn = remember(group.messages.firstOrNull()?.id) { LiveTurnFlag() }
    SideEffect { if (collapsePlan == null && isGenerating) sawLiveTurn.value = true }
    // 正文按所属消息保存为可移动内容：一轮结束收起时，最终正文从平铺列表换到收起布局的固定位置，
    // 仍沿用流式期间的放出进度、解析结果与横向滚动位置。
    val contentSegments = remember(group.messages.firstOrNull()?.id) {
        HashMap<String, @Composable (ContentSegmentArgs) -> Unit>()
    }

    val footerContent = collapsePlan?.finalContent
        ?: allSteps.filterIsInstance<PipelineStep.Content>().lastOrNull { it.content.isNotBlank() }
    val replyCopyText = remember(allSteps, collapsePlan) { collapsePlan?.finalCopyText ?: pipelineReplyCopyText(allSteps) }
    val allProcessCopyText = remember(collapsePlan) { collapsePlan?.distinctProcessCopyText() }
    val wallpaper = com.promenar.infinity.ui.wallpaper.LocalChatWallpaperConfig.current
    val useReadingCard = wallpaper.effectiveAssistantCard &&
        (allSteps.isNotEmpty() || hasRagContent || collapsePlan != null || isGenerating)
    val darkSurface = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val turnBody: @Composable () -> Unit = {
    Column(modifier = if (useReadingCard) Modifier.fillMaxWidth() else modifier.fillMaxWidth()) {
        ragContent()
        if (collapsePlan != null) {
            // 实时生成刚结束进入收起态时，摘要行随过程收起同步平滑展开；翻看历史时首帧直接呈现。
            val summaryVisibility = remember(group.messages.firstOrNull()?.id) {
                MutableTransitionState(!sawLiveTurn.value)
            }
            SideEffect { summaryVisibility.targetState = true }
            AnimatedVisibility(
                visibleState = summaryVisibility,
                enter = InfinityMotion.expandIn,
                exit = ExitTransition.None,
            ) {
                TurnProcessSummaryRow(
                    summary = collapsePlan.summary,
                    expanded = processExpanded,
                    fontSize = fontSize,
                    onToggle = { processExpanded = !processExpanded },
                )
            }
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
                            liveFileEditBatch = if (collapsePlan == null && index == steps.lastIndex) tailToolExecFileBatch else null,
                        )
                        asks.answered.forEach { AskUserAnsweredCard(it) }
                        branches.resolved.forEach { BranchPlanResolvedView(it) }
                        // 生成中画布卡紧跟创建它的步骤；结束后改由正文下方的本轮产物区展示。
                        if (isGenerating) CanvasInvocationCards(step.steps, inline = true)
                    }
                    is PipelineStep.Content -> {
                        // 流式正文属于本组最后一条助手消息，且若提供了流式目标 ID，必须与之匹配才套用流式内容。
                        val streamingTarget = isGenerating &&
                            step.messageId == finalAssistantId &&
                            (streamingMessageId == null || streamingMessageId == step.messageId)
                        val displayContent = if (streamingTarget) {
                            streamingContent.ifEmpty { step.content }
                        } else step.content
                        val sourceMessage = pipelineContentOwner(group.messages, step)
                        val segment = contentSegments.getOrPut(step.messageId.ifEmpty { "content:$index" }, ::movableContentSegment)
                        segment(
                            ContentSegmentArgs(
                                content = displayContent,
                                isStreaming = streamingTarget,
                                showStreamingCursor = renderPlan.showContentCursor,
                                fontSize = fontSize,
                                onContentChange = sourceMessage?.let { msg ->
                                    onMessageContentChange?.let { handler -> { content: String -> handler(msg.id, content) } }
                                } ?: onContentChange,
                                sourceMessageId = sourceMessage?.id ?: group.messages.lastOrNull { it.role == MessageRole.ASSISTANT }?.id,
                            ),
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
            // 本次组合亲眼看着这一轮跑完时，过程先以展开态出现再动画收起；翻看历史时直接是收起态。
            val processVisibility = remember { MutableTransitionState(sawLiveTurn.value || processExpanded) }
            SideEffect { processVisibility.targetState = processExpanded }
            // 过程步骤随摘要行纵向展开、收起，最终正文始终留在下方原位，不随切换重建。
            AnimatedVisibility(visibleState = processVisibility, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                Column { collapsePlan.processSteps.forEachIndexed { index, step -> renderStep(index, step, expandedSteps) } }
            }
            renderStep(expandedSteps.lastIndex, collapsePlan.finalContent, expandedSteps)
        }
        if (collapsePlan == null && standaloneStreamingFileBatch != null) {
            if (allSteps.lastOrNull() is PipelineStep.Content) {
                Spacer(Modifier.height(8.dp))
            }
            LiveFileEditBatchRows(
                rows = standaloneStreamingFileBatch.rows,
                onOpenChange = {},
            )
        }
        val showLiveTail = isGenerating && !hasPendingApproval && !liveFileEditOwnsTail &&
            liveStep?.kind != LiveStepKind.APPROVAL && liveStep?.kind != LiveStepKind.QUESTION
        val immediateLiveTailExit = shouldExitLiveTailImmediately(isGenerating, liveStep, liveFileEditOwnsTail)
        val footerOwner = footerContent?.let { pipelineContentOwner(group.messages, it) }
        if (footerOwner == null) {
            LiveTailSlot(
                visible = showLiveTail,
                liveStep = liveStep,
                immediateExit = immediateLiveTailExit,
            )
        }
        if (!isGenerating) CanvasInvocationCards(
            allSteps.filterIsInstance<PipelineStep.ToolExec>().flatMap { it.steps },
            messageIds = remember(group.messages) { group.messages.map { it.id }.toSet() },
        )
        if (footerOwner != null) {
            val lastMsg = footerOwner
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
            val branchTargetId = group.messages.lastOrNull()?.id ?: lastMsg.id
            val liveTailVisibleInFooter = showLiveTail && resolveLiveTailContent(liveStep) != LiveTailContent.NONE
            val footerSlotVisible = !isGenerating || liveTailVisibleInFooter
            val footerMinHeight = resolveAssistantFooterMinHeight(
                isGenerating = isGenerating,
                lastStepIsContent = allSteps.lastOrNull() is PipelineStep.Content,
            )
            // 已有正文时将生成中尾部状态位与完成态操作行合入同一槽位；被新思考/工具行接管时零帧交接，避免缩高动画与新行叠出列表上下晃动。
            val footerVisibility = remember(group.messages.firstOrNull()?.id) {
                MutableTransitionState(footerSlotVisible)
            }
            SideEffect { footerVisibility.targetState = footerSlotVisible }
            AnimatedVisibility(
                visibleState = footerVisibility,
                enter = InfinityMotion.expandIn,
                exit = if (immediateLiveTailExit) ExitTransition.None else InfinityMotion.shrinkOut,
            ) {
                AnimatedContent(
                    targetState = isGenerating,
                    transitionSpec = {
                        (fadeIn(tween(InfinityMotion.DurationMedium)) togetherWith fadeOut(tween(InfinityMotion.DurationFadeOut)))
                            .using(SizeTransform(clip = false))
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = footerMinHeight),
                    contentAlignment = Alignment.TopStart,
                    label = "assistantFooterSlot",
                ) { generating ->
                    if (generating) {
                        // 用 wrapContentHeight(Alignment.Top) 阻断外层 44dp minHeight 向下传播，
                        // 保持点阵保持原 36dp 轨道内的垂直对齐位置，同时外层仍占满 44dp 实现零跳变淡换。
                        Box(Modifier.fillMaxWidth().wrapContentHeight(Alignment.Top)) {
                            LiveTailSlot(
                                visible = true,
                                liveStep = liveStep,
                                inlineInFooter = true,
                            )
                        }
                    } else {
                        AssistantActionRow(
                            modelDisplayName = modelDisplayName,
                            timestamp = timestamp,
                            isGenerating = false,
                            animateVisibility = false,
                            memoryState = memoryOwner?.turnMemory,
                            graphState = memoryOwner?.let { turnGraphStates[it.id] },
                            onOpenMemory = memoryOwner?.let { owner -> onOpenTurnMemory?.let { open -> { open(owner.id) } } },
                            stats = statsFactory,
                            onCopy = { onCopy?.invoke(replyCopyText) ?: copyToClipboard(context, replyCopyText) },
                            onReadAloud = onReadAloud?.let { handler -> { handler(lastMsg.id, replyCopyText) } },
                            readAloudPhase = readAloudPhase,
                            onCopyAllProcess = allProcessCopyText?.let { text ->
                                { onCopy?.invoke(text) ?: copyToClipboard(context, text) }
                            },
                            onRegenerate = onRegenerate?.let { handler -> { handler(lastMsg.id) } },
                            onBranch = onBranch?.let { handler -> { handler(branchTargetId) } },
                            onDelete = onDelete?.let { handler -> { handler(lastMsg.id) } },
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
    }
    if (useReadingCard) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = if (darkSurface) InfinityObsidianDialogContainer.copy(alpha = 0.78f)
            else InfinityPureWhite.copy(alpha = 0.82f),
            border = BorderStroke(
                0.6.dp,
                if (darkSurface) InfinityPureWhite.copy(alpha = 0.09f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
            ),
            modifier = modifier.fillMaxWidth().testTag("CHAT_ASSISTANT_WALLPAPER_CARD"),
        ) {
            Box(Modifier.padding(horizontal = 15.dp, vertical = 13.dp)) {
                turnBody()
            }
        }
    } else {
        turnBody()
    }

    // ── 错误信息 ──
    group.messages.lastOrNull()?.let { lastMsg ->
        if (lastMsg.isError || lastMsg.errorMessage != null) {
            val notice = remember(lastMsg.errorMessage) {
                historicalGenerationFailureNotice(lastMsg.errorMessage)
            }
            val resolved = remember(notice) { GenerationFailureNotice.template(notice) }
            val tailnetKind = remember(notice) { GenerationFailureNotice.tailnetKind(notice) }
            val emptyReply = remember(notice) { GenerationFailureNotice.isEmptyReply(notice) }
            val visualKind = remember(notice) { GenerationFailureNotice.visualKind(notice) }
            val prefersSwitchModel = remember(notice) { GenerationFailureNotice.prefersSwitchModel(notice) }
            ErrorMessageSegment(
                messageId = lastMsg.id,
                text = stringResource(resolved.resourceId, *resolved.args.toTypedArray()),
                detail = notice.detail?.let { stringResource(R.string.generation_failure_provider_detail, it) },
                httpStatus = notice.httpStatus,
                errorCode = notice.errorCode,
                rawDiagnostic = notice.rawDiagnostic,
                visualKind = visualKind,
                prefersSwitchModel = prefersSwitchModel,
                fontSize = fontSize,
                onDelete = onDelete,
                tailnetKind = tailnetKind,
                emptyReply = emptyReply,
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
    httpStatus: Int? = null,
    errorCode: String? = null,
    rawDiagnostic: String? = null,
    visualKind: FailureVisualKind = FailureVisualKind.GENERAL,
    prefersSwitchModel: Boolean = false,
    tailnetKind: TailnetFailureKind? = null,
    emptyReply: Boolean = false,
    actions: GenerationFailureActions? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    var rawExpanded by rememberSaveable(messageId) { mutableStateOf(false) }
    var copiedRaw by remember(messageId) { mutableStateOf(false) }
    val menuTouch = rememberInfinityMenuTouch()
    val scheme = MaterialTheme.colorScheme
    val dark = MaterialTheme.infinityDomainColors.isDark
    val cardBg = if (dark) InfinityObsidianDialogContainer else scheme.surfaceContainerLowest
    val borderColor = if (dark) scheme.error.copy(alpha = 0.30f) else scheme.error.copy(alpha = 0.24f)
    val iconTileBg = if (dark) scheme.error.copy(alpha = 0.14f) else scheme.errorContainer.copy(alpha = 0.72f)
    val iconTint = if (dark) scheme.error else scheme.onErrorContainer
    val statusIcon = when (visualKind) {
        FailureVisualKind.QUOTA -> Icons.Rounded.AccountBalanceWallet
        FailureVisualKind.AUTH -> Icons.Rounded.VpnKey
        FailureVisualKind.NETWORK -> Icons.Rounded.CloudOff
        FailureVisualKind.TIMEOUT -> Icons.Rounded.Schedule
        FailureVisualKind.RATE_LIMIT -> Icons.Rounded.Speed
        FailureVisualKind.LIMIT -> Icons.Rounded.DataUsage
        FailureVisualKind.POLICY -> Icons.Rounded.Shield
        FailureVisualKind.TAILNET -> Icons.Rounded.WifiOff
        FailureVisualKind.GENERAL -> Icons.Rounded.ErrorOutline
    }
    val diagnosticBody = remember(rawDiagnostic, httpStatus, errorCode, detail) {
        rawDiagnostic?.takeIf { it.isNotBlank() }
            ?: if (httpStatus != null || !errorCode.isNullOrBlank()) {
                listOfNotNull(
                    httpStatus?.let { "HTTP $it" },
                    errorCode?.takeIf { it.isNotBlank() }?.let { "code $it" },
                    detail,
                ).joinToString("\n").ifBlank { null }
            } else {
                null
            }
    }
    val hasRawSection = diagnosticBody != null
    val httpLabel = httpStatus?.let { stringResource(R.string.chat_failure_http_meta, it) }
    val errorCodeLabel = errorCode?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.chat_failure_code_meta, it) }
    val rawDetailsLabel = stringResource(R.string.chat_failure_raw_details)
    val diagnosticCopyHeader = listOfNotNull(httpLabel, errorCodeLabel).joinToString(" / ").ifBlank { rawDetailsLabel }
    LaunchedEffect(copiedRaw) {
        if (copiedRaw) {
            delay(1_800)
            copiedRaw = false
        }
    }

    Box(modifier = Modifier.padding(top = InfinitySpacing.Small)) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = cardBg,
            contentColor = scheme.onSurface,
            border = BorderStroke(1.dp, borderColor),
            modifier = Modifier
                .fillMaxWidth()
                .testTag(UiTags.CHAT_FAILURE_CARD)
                // 长按或鼠标右键只弹菜单，不铺按压涟漪；菜单从按住或右键光标位置长出。
                .infinityMenuTouch(menuTouch)
                .infinityContextClickable(
                    interactionSource = null,
                    indication = null,
                    touch = menuTouch,
                    pointerHand = false,
                    onClick = {},
                    onLongClick = { showMenu = true },
                ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .obsidianTopGlint(
                        color = if (dark) scheme.error.copy(alpha = 0.22f) else Color.Transparent,
                        cornerRadius = 16.dp,
                    )
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = if (detail == null) Alignment.CenterVertically else Alignment.Top,
                ) {
                    Box(
                        modifier = Modifier
                            .padding(top = if (detail == null) 0.dp else 1.dp)
                            .size(28.dp)
                            .background(iconTileBg, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = statusIcon,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        Text(
                            text = text,
                            style = InfinityTypography.bodyMedium.copy(
                                fontSize = (fontSize - 2).coerceAtLeast(10).sp,
                                fontWeight = FontWeight.Medium,
                            ),
                            color = scheme.onSurface,
                        )
                        // 服务商原因经脱敏清洗后附在下方，便于判断余额、限流或参数问题。
                        if (detail != null) {
                            Text(
                                text = detail,
                                style = InfinityTypography.bodySmall.copy(
                                    fontSize = (fontSize - 4).coerceAtLeast(10).sp,
                                ),
                                color = scheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.testTag(UiTags.CHAT_FAILURE_PROVIDER_DETAIL),
                            )
                        }
                    }
                }

                if (hasRawSection) {
                    val toggleCd = stringResource(
                        if (rawExpanded) R.string.chat_failure_toggle_collapse_cd else R.string.chat_failure_toggle_expand_cd,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Surface(
                            onClick = { rawExpanded = !rawExpanded },
                            shape = RoundedCornerShape(14.dp),
                            color = if (dark) InfinityObsidianDialogChip else scheme.surfaceContainerHigh,
                            contentColor = scheme.onSurfaceVariant,
                            border = BorderStroke(
                                0.75.dp,
                                if (rawExpanded) scheme.error.copy(alpha = 0.36f)
                                else scheme.outlineVariant.copy(alpha = if (dark) 0.5f else 0.7f),
                            ),
                            modifier = Modifier
                                .testTag(UiTags.CHAT_FAILURE_RAW_TOGGLE)
                                .semantics { contentDescription = toggleCd },
                        ) {
                            Row(
                                modifier = Modifier
                                    .heightIn(min = 28.dp)
                                    .padding(start = 10.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val chipContentColor = if (rawExpanded) scheme.onSurface else scheme.onSurfaceVariant
                                if (httpLabel != null) {
                                    Text(
                                        text = httpLabel,
                                        style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
                                        color = chipContentColor,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                    Text(
                                        text = InfinityMetaSeparator,
                                        style = InfinityTypography.labelSmall,
                                        color = scheme.onSurfaceVariant,
                                        maxLines = 1,
                                        softWrap = false,
                                    )
                                }
                                Text(
                                    text = rawDetailsLabel,
                                    style = InfinityTypography.labelSmall,
                                    color = chipContentColor,
                                    maxLines = 1,
                                    softWrap = false,
                                )
                                Icon(
                                    imageVector = if (rawExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(15.dp),
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = rawExpanded,
                        enter = InfinityMotion.expandIn,
                        exit = InfinityMotion.shrinkOut,
                    ) {
                        val payload = diagnosticBody.orEmpty()
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (dark) InfinityObsidianSolidContainer else scheme.surfaceContainerHigh,
                            contentColor = scheme.onSurfaceVariant,
                            border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = if (dark) 0.4f else 0.65f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag(UiTags.CHAT_FAILURE_RAW_PANEL),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    val metaTint = scheme.error.copy(alpha = if (dark) 0.9f else 0.85f)
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        if (httpLabel != null) {
                                            Text(
                                                text = httpLabel,
                                                style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
                                                color = metaTint,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                            if (errorCodeLabel != null) {
                                                Text(
                                                    text = InfinityMetaSeparator,
                                                    style = InfinityTypography.labelSmall,
                                                    color = metaTint.copy(alpha = 0.7f),
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            }
                                        }
                                        if (errorCodeLabel != null) {
                                            Text(
                                                text = errorCodeLabel,
                                                style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
                                                color = metaTint,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                        } else if (httpLabel == null) {
                                            Text(
                                                text = rawDetailsLabel,
                                                style = InfinityTypography.labelSmall.copy(fontFamily = InfinityMonospace),
                                                color = metaTint,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                    val copyText = stringResource(
                                        if (copiedRaw) R.string.chat_failure_copied else R.string.chat_failure_copy_raw,
                                    )
                                    Surface(
                                        onClick = {
                                            copyToClipboard(context, "$diagnosticCopyHeader\n$payload")
                                            copiedRaw = true
                                        },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (dark) InfinityObsidianDialogChip else scheme.surfaceContainerLowest,
                                        contentColor = scheme.onSurface,
                                        border = BorderStroke(0.75.dp, scheme.outlineVariant.copy(alpha = 0.55f)),
                                        modifier = Modifier.testTag(UiTags.CHAT_FAILURE_RAW_COPY),
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        ) {
                                            Icon(
                                                imageVector = if (copiedRaw) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Text(
                                                text = copyText,
                                                style = InfinityTypography.labelSmall,
                                                maxLines = 1,
                                                softWrap = false,
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.35f))
                                Text(
                                    text = payload,
                                    style = InfinityTypography.bodySmall.copy(
                                        fontFamily = InfinityMonospace,
                                        fontSize = 11.sp,
                                        lineHeight = 15.sp,
                                    ),
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                if (actions != null) {
                    when {
                        tailnetKind != null -> TailnetFailureActionRow(tailnetKind, actions)
                        emptyReply -> EmptyReplyActionRow(actions)
                        else -> StandardFailureActionRow(prefersSwitchModel, actions)
                    }
                }
            }
        }
        MessageContextMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            touch = menuTouch,
            onCopy = { copyToClipboard(context, listOfNotNull(text, detail, diagnosticBody).joinToString("\n")) },
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

/** 内网失败气泡下的处理方式。 */
internal enum class TailnetFailureAction { RETRY, SWITCH_MODEL, ENABLE, LOGIN, SETTINGS }

/** [row] 为操作行上的胶囊，从左到右排列、最后一个是主操作；[menu] 为行尾“⋯”菜单项，为空时不显示菜单。 */
internal data class TailnetFailureActions(val row: List<TailnetFailureAction>, val menu: List<TailnetFailureAction>)

/**
 * 最多两个胶囊并排，主操作在右；其余处理方式收进行尾“⋯”，避免三个按钮竖排撑高失败气泡。
 * 每种失败都同时提供重试与换用公网模型；有 Tailscale 设置入口时，开启、登录或设置也必须可达。
 */
internal fun tailnetFailureActions(kind: TailnetFailureKind, hasSettings: Boolean): TailnetFailureActions {
    val fix = when (kind) {
        TailnetFailureKind.DISABLED -> TailnetFailureAction.ENABLE
        TailnetFailureKind.NEEDS_LOGIN -> TailnetFailureAction.LOGIN
        TailnetFailureKind.UNREACHABLE -> null
    }
    return when {
        fix != null && hasSettings -> TailnetFailureActions(
            row = listOf(TailnetFailureAction.RETRY, fix),
            menu = listOf(TailnetFailureAction.SWITCH_MODEL),
        )
        else -> TailnetFailureActions(
            row = listOf(TailnetFailureAction.SWITCH_MODEL, TailnetFailureAction.RETRY),
            menu = if (kind == TailnetFailureKind.UNREACHABLE && hasSettings) listOf(TailnetFailureAction.SETTINGS) else emptyList(),
        )
    }
}

@Composable
private fun TailnetFailureActionRow(kind: TailnetFailureKind, actions: GenerationFailureActions) {
    val openSettings = actions.onOpenTailscaleSettings
    val layout = tailnetFailureActions(kind, hasSettings = openSettings != null)
    fun onClickOf(action: TailnetFailureAction): () -> Unit = when (action) {
        TailnetFailureAction.RETRY -> actions.onRetry
        TailnetFailureAction.SWITCH_MODEL -> actions.onSwitchModel
        TailnetFailureAction.ENABLE, TailnetFailureAction.LOGIN, TailnetFailureAction.SETTINGS -> openSettings ?: {}
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.CHAT_TAILNET_FAILURE_ACTIONS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InfinityPillRow(modifier = Modifier.weight(1f), spacing = 8.dp) {
            layout.row.forEachIndexed { index, action ->
                InfinityPillButton(
                    text = stringResource(action.labelRes()),
                    onClick = onClickOf(action),
                    tone = if (index == layout.row.lastIndex) InfinityPillTone.PRIMARY else InfinityPillTone.NEUTRAL,
                    compact = true,
                    modifier = Modifier.testTag(action.testTag()),
                )
            }
        }
        if (layout.menu.isNotEmpty()) {
            InfinityPillMenuButton(contentDescription = stringResource(R.string.tailnet_failure_more_cd)) { dismiss ->
                layout.menu.forEach { action ->
                    InfinityMenuItem(
                        text = { Text(stringResource(action.labelRes())) },
                        leadingIcon = { Icon(action.menuIcon(), contentDescription = null) },
                        onClick = { dismiss(); onClickOf(action)() },
                        modifier = Modifier.testTag(action.testTag()),
                    )
                }
            }
        }
    }
}

/** 空回复失败没有正文，也就没有底部操作行；在气泡内给出重试与换模型，主操作在右。 */
@Composable
private fun EmptyReplyActionRow(actions: GenerationFailureActions) {
    InfinityPillRow(modifier = Modifier.fillMaxWidth().testTag(UiTags.CHAT_EMPTY_REPLY_ACTIONS), spacing = 8.dp) {
        InfinityPillButton(
            text = stringResource(R.string.exec_steer_empty_reply_switch_model),
            onClick = actions.onSwitchModel,
            tone = InfinityPillTone.NEUTRAL,
            compact = true,
            modifier = Modifier.testTag(UiTags.CHAT_EMPTY_REPLY_SWITCH_MODEL),
        )
        InfinityPillButton(
            text = stringResource(R.string.exec_steer_empty_reply_retry),
            onClick = actions.onRetry,
            tone = InfinityPillTone.PRIMARY,
            compact = true,
            modifier = Modifier.testTag(UiTags.CHAT_EMPTY_REPLY_RETRY),
        )
    }
}

/** 标准失败卡片底部动作行：余额/鉴权/参数类主推「换模型」，网络/超时/限流类主推「重试」，主操作始终在右。 */
@Composable
private fun StandardFailureActionRow(
    prefersSwitchModel: Boolean,
    actions: GenerationFailureActions,
) {
    InfinityPillRow(modifier = Modifier.fillMaxWidth().testTag(UiTags.CHAT_FAILURE_ACTIONS), spacing = 8.dp) {
        if (prefersSwitchModel) {
            InfinityPillButton(
                text = stringResource(R.string.chat_failure_retry),
                onClick = actions.onRetry,
                tone = InfinityPillTone.NEUTRAL,
                compact = true,
                modifier = Modifier.testTag(UiTags.CHAT_FAILURE_RETRY),
            )
            InfinityPillButton(
                text = stringResource(R.string.chat_failure_switch_model),
                onClick = actions.onSwitchModel,
                tone = InfinityPillTone.PRIMARY,
                compact = true,
                modifier = Modifier.testTag(UiTags.CHAT_FAILURE_SWITCH_MODEL),
            )
        } else {
            InfinityPillButton(
                text = stringResource(R.string.chat_failure_switch_model),
                onClick = actions.onSwitchModel,
                tone = InfinityPillTone.NEUTRAL,
                compact = true,
                modifier = Modifier.testTag(UiTags.CHAT_FAILURE_SWITCH_MODEL),
            )
            InfinityPillButton(
                text = stringResource(R.string.chat_failure_retry),
                onClick = actions.onRetry,
                tone = InfinityPillTone.PRIMARY,
                compact = true,
                modifier = Modifier.testTag(UiTags.CHAT_FAILURE_RETRY),
            )
        }
    }
}

private fun TailnetFailureAction.labelRes(): Int = when (this) {
    TailnetFailureAction.RETRY -> R.string.tailnet_failure_action_retry
    TailnetFailureAction.SWITCH_MODEL -> R.string.tailnet_failure_action_switch_model
    TailnetFailureAction.ENABLE -> R.string.tailnet_failure_action_enable
    TailnetFailureAction.LOGIN -> R.string.tailnet_failure_action_login
    TailnetFailureAction.SETTINGS -> R.string.tailnet_failure_action_settings
}

private fun TailnetFailureAction.testTag(): String = when (this) {
    TailnetFailureAction.RETRY -> UiTags.CHAT_TAILNET_FAILURE_RETRY
    TailnetFailureAction.SWITCH_MODEL -> UiTags.CHAT_TAILNET_FAILURE_SWITCH
    TailnetFailureAction.ENABLE -> UiTags.CHAT_TAILNET_FAILURE_ENABLE
    TailnetFailureAction.LOGIN -> UiTags.CHAT_TAILNET_FAILURE_LOGIN
    TailnetFailureAction.SETTINGS -> UiTags.CHAT_TAILNET_FAILURE_SETTINGS
}

private fun TailnetFailureAction.menuIcon(): ImageVector = when (this) {
    TailnetFailureAction.RETRY -> Icons.Rounded.Refresh
    TailnetFailureAction.SWITCH_MODEL -> Icons.Rounded.SwapHoriz
    TailnetFailureAction.ENABLE, TailnetFailureAction.LOGIN, TailnetFailureAction.SETTINGS -> Icons.Rounded.Settings
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
    streamingMessageId: String? = null,
): PipelineRenderPlan {
    val finalAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
    // 正文已开始流式到达但尚未落盘且流式目标匹配本条助手消息时，才在正文位置补一个占位，排在它发起的工具之前。
    val matchesStreamingTarget = finalAssistant != null &&
        (streamingMessageId == null || streamingMessageId == finalAssistant.id)
    val streamingPlaceholderId = finalAssistant
        ?.takeIf { isGenerating && hasStreamingContent && matchesStreamingTarget && it.content.isBlank() }
        ?.id
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

    /** 直接写入一段已结束思考的时长，覆盖已有记录；视觉实验室用它给脚本消息定下思考时长。 */
    @Synchronized fun record(key: String, durationMs: Long) {
        durations[key] = durationMs.coerceAtLeast(0)
    }

    /** 清掉某条消息的起点与时长；视觉实验室重放同一条脚本消息前调用，计时从这次出现重新开始。 */
    @Synchronized fun reset(key: String) {
        starts.remove(key)
        durations.remove(key)
    }

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
    onReadAloud: (() -> Unit)? = null,
    readAloudPhase: ReadAloudPhase = ReadAloudPhase.IDLE,
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
    /** 为 false 时交由外层统一页脚槽位控制切换过渡，不再嵌套第二次显隐动画。 */
    animateVisibility: Boolean = true,
) {
    val content: @Composable () -> Unit = {
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
                    if (onReadAloud != null) {
                        val readAloudDesc = stringResource(
                            when (readAloudPhase) {
                                ReadAloudPhase.IDLE -> R.string.chat_action_read_aloud
                                ReadAloudPhase.LOADING -> R.string.chat_action_cancel_read_aloud
                                ReadAloudPhase.PLAYING -> R.string.chat_action_stop_read_aloud
                            },
                        )
                        IconButton(
                            onClick = { haptics.perform(InfinityHapticEvent.CONFIRM); onReadAloud() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("CHAT_ASSISTANT_READ_ALOUD")
                                .semantics { contentDescription = readAloudDesc },
                        ) {
                            AnimatedContent(
                                targetState = readAloudPhase,
                                transitionSpec = { InfinityMotion.fadeThrough() },
                                contentAlignment = Alignment.Center,
                                label = "assistant_read_aloud_icon",
                            ) { phase ->
                                when (phase) {
                                    ReadAloudPhase.IDLE -> Icon(
                                        Icons.AutoMirrored.Rounded.VolumeUp,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    ReadAloudPhase.LOADING -> CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                    ReadAloudPhase.PLAYING -> Icon(
                                        Icons.Rounded.StopCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(19.dp),
                                    )
                                }
                            }
                        }
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
    if (animateVisibility) {
        // 独立使用时生成结束后操作行淡入展开，重新生成时立即收起，不在流式输出期间占位。
        AnimatedVisibility(
            visible = !isGenerating,
            enter = fadeIn(tween(InfinityMotion.DurationMedium, delayMillis = InfinityMotion.DurationFadeOut)) +
                expandVertically(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)),
            exit = ExitTransition.None,
        ) {
            content()
        }
    } else if (!isGenerating) {
        content()
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
    // 媒体调用全部失败时没有产出，只写动作名，失败数由后缀给出。
    if (part.count == 0) return stringResource(toolActionNameRes(part.action))
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
        ToolSummaryAction.SPEECH -> R.plurals.chat_step_speech_done
        ToolSummaryAction.MUSIC -> R.plurals.chat_step_music_done
        ToolSummaryAction.MEDIA_VIEW -> R.plurals.chat_step_media_view_done
        ToolSummaryAction.MEDIA_FETCH -> R.plurals.chat_step_media_fetch_done
        ToolSummaryAction.PLAN -> R.plurals.chat_step_plan_done
        ToolSummaryAction.TIME -> R.plurals.chat_step_time_done
        ToolSummaryAction.CALCULATE -> R.plurals.chat_step_calculate_done
        ToolSummaryAction.PET -> R.plurals.chat_step_pet_done
        ToolSummaryAction.SCHEDULE -> R.plurals.chat_step_schedule_done
        ToolSummaryAction.SKILL -> R.plurals.chat_step_skill_done
        ToolSummaryAction.SUBAGENT -> R.plurals.chat_step_subagent_done
        ToolSummaryAction.SECRET -> R.plurals.chat_step_secret_done
        ToolSummaryAction.OTHER -> R.plurals.chat_step_other_done
    }
    return pluralStringResource(resId, part.count, part.count)
}

@Composable
internal fun toolFriendlyName(toolName: String?): String = stringResource(toolActionNameRes(toolSummaryAction(toolName)))

private fun toolActionNameRes(action: ToolSummaryAction): Int = when (action) {
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
    ToolSummaryAction.SPEECH -> R.string.chat_step_speech_name
    ToolSummaryAction.MUSIC -> R.string.chat_step_music_name
    ToolSummaryAction.MEDIA_VIEW -> R.string.chat_step_media_view_name
    ToolSummaryAction.MEDIA_FETCH -> R.string.chat_step_media_fetch_name
    ToolSummaryAction.PLAN -> R.string.chat_step_plan_name
    ToolSummaryAction.TIME -> R.string.chat_step_time_name
    ToolSummaryAction.CALCULATE -> R.string.chat_step_calculate_name
    ToolSummaryAction.PET -> R.string.chat_step_pet_name
    ToolSummaryAction.SCHEDULE -> R.string.chat_step_schedule_name
    ToolSummaryAction.SKILL -> R.string.chat_step_skill_name
    ToolSummaryAction.SUBAGENT -> R.string.chat_step_subagent_name
    ToolSummaryAction.SECRET -> R.string.chat_step_secret_name
    ToolSummaryAction.OTHER -> R.string.chat_step_other_name
}

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
            ToolSummaryAction.SPEECH -> R.string.chat_step_speech_running
            ToolSummaryAction.MUSIC -> R.string.chat_step_music_running
            ToolSummaryAction.MEDIA_VIEW -> R.string.chat_step_media_view_running
            ToolSummaryAction.MEDIA_FETCH -> R.string.chat_step_media_fetch_running
            ToolSummaryAction.PLAN -> R.string.chat_step_plan_running
            ToolSummaryAction.TIME -> R.string.chat_step_time_running
            ToolSummaryAction.CALCULATE -> R.string.chat_step_calculate_running
            ToolSummaryAction.PET -> R.string.chat_step_pet_running
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
    /** 连续文件编辑的实时批次视图；活跃期间逐行列出文件名与滚动增删行数，全部完成后平滑折叠回单行汇总。 */
    liveFileEditBatch: LiveFileEditBatchState? = null,
) {
    var isExpanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val invocations = remember(steps) { toolInvocations(steps) }
    // 审批通过后的调用不在生成任务内执行，以实时输出通道判断它是否正在运行。
    val liveKeys = LocalToolLiveOutput.current?.activeKeys?.collectAsState()?.value.orEmpty()
    val liveInvocations = activeToolOutputs(invocations, liveKeys)
    val liveRunning = liveInvocations.isNotEmpty()
    val executing = isExecuting || liveRunning
    val liveEditActive = liveFileEditBatch?.isLiveBatch == true
    val pureLiveEditMode = liveEditActive && liveFileEditBatch?.isPureFileEditStep == true
    val mixedLiveEditMode = liveEditActive && liveFileEditBatch?.isPureFileEditStep == false
    // 混合工具组尾部存在活跃文件编辑批次时，上方汇总行仅保留前置非编辑工具，避免被“正在修改文件…”覆盖。
    val summaryInvocations = remember(invocations, mixedLiveEditMode, liveFileEditBatch?.trailingInvocationCount) {
        if (mixedLiveEditMode) invocations.dropLast((liveFileEditBatch?.trailingInvocationCount ?: 0).coerceAtMost(invocations.size))
        else invocations
    }
    val summary = remember(summaryInvocations, executing, turnActive, mixedLiveEditMode) {
        toolGroupDisplaySummary(summaryInvocations, executing && !mixedLiveEditMode, turnActive)
    }
    val fileChanges = remember(summaryInvocations) { toolGroupFileChanges(summaryInvocations) }
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
        summaryInvocations.singleOrNull { !it.complete }?.let { commandStepLabel(it.name, it.call?.toolArgs, jobLabels) }
    } else null
    val runningCommandText = runningCommand?.let { runningCommandText(it) }
    // 单个计划或任务查看调用：摘要直接说明这次改了什么、任务处于什么状态。
    val cardSummaryText = toolCardSummaryText(summaryInvocations, jobLabels)
    val segments = buildList<CharSequence> {
        if (stateSummaries.isNotEmpty()) add(stateSummaries.joinToString("，"))
        // 文件改动只来自成功的文件工具：全是文件编辑时标题直接给出文件与行数，混合工具时作为补充段。
        if (!stateOnly && (!showFileTitle || !summary.isFileEditOnly)) add(runningCommandText ?: cardSummaryText ?: toolGroupSummaryText(summary))
        if (showFileTitle) add(com.promenar.infinity.ui.chat.diff.fileChangesStyledTitle(fileChanges))
        if (summary.backgroundedCount > 0 && summary.runningAction == null) add(stringResource(R.string.tool_card_job_state_backgrounded))
        else if (summary.failedCount == 0 && summary.runningAction == null) summary.durationMs?.let { add(stepDurationText(it)) }
    }
    openedChange?.let { change ->
        com.promenar.infinity.ui.chat.diff.FileChangeDiffSheet(change, onDismissRequest = { openedChange = null })
    }
    val rowLive = live?.takeUnless { liveEditActive }
    val liveStartedAt = rowLive?.startedAt
    val lastLiveEditRows = remember { arrayOfNulls<List<LiveFileEditRowState>>(1) }
    if (liveFileEditBatch != null && liveFileEditBatch.rows.isNotEmpty()) {
        lastLiveEditRows[0] = liveFileEditBatch.rows
    }
    val animateEditCollapse = com.promenar.infinity.ui.common.rememberStreamAnimationsEnabled() == true &&
        !androidx.compose.ui.platform.LocalInspectionMode.current
    Column(modifier = Modifier.fillMaxWidth()) {
        // 逐条显示的工具没有标题行，进行中时在上方单独显示动画与计时。
        val tickerLive = rowLive?.takeIf { memoryOnly || browserOnly || knowledgeOnly }
        // 收起时仍需绘制最后一帧计时，用普通引用保留上一次的进行中步骤，不触发重组。
        val lastTicker = remember { arrayOfNulls<LiveStep>(1) }
        if (tickerLive != null) lastTicker[0] = tickerLive
        AnimatedVisibility(visible = tickerLive != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            (tickerLive ?: lastTicker[0])?.let { LiveToolTicker(it) }
        }
        if (memoryOnly) MemoryChangeRows(memoryChanges) else if (browserOnly) BrowserStepRows(browserSteps, fontSize)
        else if (knowledgeOnly) KnowledgeSearchStepRows(knowledgeSteps, fontSize) else {
            // 纯文件编辑批次在活跃态逐行呈现文件名与异向滚动计数，全部改完后平滑折叠成单行汇总。
            AnimatedContent(
                targetState = pureLiveEditMode,
                transitionSpec = {
                    if (!animateEditCollapse) {
                        (EnterTransition.None togetherWith ExitTransition.None)
                            .using(SizeTransform(clip = false))
                    } else {
                        (fadeIn(tween(InfinityMotion.DurationMedium)) togetherWith
                            fadeOut(tween(InfinityMotion.DurationFadeOut)))
                            .using(
                                SizeTransform(clip = true) { _, _ ->
                                    tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)
                                },
                            )
                    }
                },
                contentAlignment = Alignment.TopStart,
                label = "inlineFileEditBatchCollapse",
            ) { showLiveRows ->
                if (showLiveRows) {
                    val rows = liveFileEditBatch?.rows ?: lastLiveEditRows[0].orEmpty()
                    LiveFileEditBatchRows(
                        rows = rows,
                        onOpenChange = { openedChange = it },
                    )
                } else {
                    CompactStepRow(
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
                        leading = if (rowLive != null) {{ LiveStepLeading(rowLive.motionPhase) }} else null,
                        trailing = if (liveStartedAt != null) {{ LiveStepTrailing(liveStartedAt) }} else null,
                    )
                }
            }
            AnimatedVisibility(
                visible = mixedLiveEditMode,
                enter = if (animateEditCollapse) InfinityMotion.expandIn else EnterTransition.None,
                exit = if (animateEditCollapse) InfinityMotion.shrinkOut else ExitTransition.None,
            ) {
                val rows = liveFileEditBatch?.rows ?: lastLiveEditRows[0].orEmpty()
                LiveFileEditBatchRows(
                    rows = rows,
                    onOpenChange = { openedChange = it },
                )
            }
        }
        // 关键数据变化不随详情折叠，避免用户错过。
        if (runtimeChanges.isNotEmpty()) RuntimeChangesNotice(runtimeChanges)
        if (!memoryOnly && memoryChanges.isNotEmpty()) MemoryChangeRows(memoryChanges)
        if (!knowledgeOnly) knowledgeSteps.filter { it.data != null }.takeIf { it.isNotEmpty() }?.let { KnowledgeSearchStepRows(it, fontSize) }
        // 子代理分组卡始终跟在摘要下方，随轮次一起折叠。
        invocations.filter { it.name == com.promenar.infinity.data.subagent.SubagentLimits.TOOL_NAME }.forEach { invocation ->
            invocation.call?.id?.let { stepId -> key("subagent", stepId) { SubagentGroupCard(unscopedStepId(stepId)) } }
        }
        AnimatedVisibility(visible = isExpanded, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
            // 展开内容底部 8dp，与流水线块间 8dp 合计 16dp，与段落间距一致。
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = ExpandedStepBottomPadding).testTag("CHAT_TOOL_DETAILS"), verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Small)) {
                // 实时输出只在用户展开后显示：默认不在会话流里弹出终端区，命令结束时也不会突然消失。
                if (executing) {
                    liveInvocations.forEach { invocation ->
                        invocation.call?.id?.let { stepId -> key("live", stepId) { RunningToolLiveOutput(unscopedStepId(stepId), modifier = Modifier) } }
                    }
                }
                invocations.forEach { invocation ->
                    key(invocation.steps.firstOrNull()?.id) {
                        ToolInvocationCard(invocation, jobLabels, commandOutputTarget, turnActive, onOpenChange = { openedChange = it })
                    }
                }
            }
        }
    }
}

/**
 * 单次工具调用的详情卡，统一由 [ToolStepCard] 承载：标题行给出工具与状态，摘要标签给出用户关心的结果，
 * 正文依工具取媒体、命令输出、计划、字段表或原文。生图与查看媒体的原文只供模型阅读，收进“完整结果”。
 */
@Composable
private fun ToolInvocationCard(
    invocation: ToolInvocationPresentation,
    jobLabels: Map<String, String>,
    commandOutputTarget: (callStepId: String) -> Pair<String, String>?,
    turnActive: Boolean,
    onOpenChange: (com.promenar.infinity.data.model.FileChangeSummary) -> Unit,
) {
    val linuxNavigation = com.promenar.infinity.ui.linux.LocalLinuxNavigation.current
    val commandStep = remember(invocation, jobLabels) { commandStepLabel(invocation.name, invocation.call?.toolArgs, jobLabels) }
    val heading = commandStep?.let { commandStepHeading(it) }
    val planCard = rememberPlanCardModel(invocation)
    val jobCard = if (planCard == null) rememberJobCardModel(invocation) else null
    val commandResult = remember(invocation, planCard, jobCard) {
        if (planCard != null || jobCard != null) null
        else invocation.results.firstOrNull { it.type != "error" && !it.content.isNullOrBlank() }
            ?.let { subagentCommandResult(invocation.name, it.content.orEmpty()) }
    }
    val outputTarget = remember(invocation) {
        invocation.call?.id?.takeIf { invocation.complete && invocation.isCommand }?.let(commandOutputTarget)
    }
    val openFullOutput: (() -> Unit)? = linuxNavigation?.let { navigation ->
        outputTarget?.let { (messageId, callId) -> { navigation.openCommandOutput(messageId, callId) } }
    }
    val waitLimit = commandStep?.waitLimitSeconds
    val action = toolSummaryAction(invocation.name)
    val arguments = invocation.call?.toolArgs
    val media = remember(invocation) { invocation.results.flatMap { it.media.orEmpty() } }
    val imageData = remember(invocation, action) {
        if (action == ToolSummaryAction.IMAGE) invocation.results.firstNotNullOfOrNull {
            com.promenar.infinity.data.model.ImageGenerationResultData.decode(it.data)
        } else null
    }
    val imageEdit = action == ToolSummaryAction.IMAGE && (imageData?.mode == "edit" || toolArgumentPresent(arguments, "images"))
    var fullResult by remember { mutableStateOf<ToolRawText?>(null) }
    val raw = remember(invocation) { toolRawText(arguments, invocation.results.mapNotNull { it.content?.takeIf(String::isNotBlank) }) }
    // 命令工具的结果不是结构化输出、而是纯文本时，按终端输出展示。
    val plainCommandOutput = remember(invocation, planCard, jobCard, commandResult) {
        if (planCard != null || jobCard != null || commandResult != null || action != ToolSummaryAction.COMMAND) null
        else invocation.results.filter { it.type != "error" }.mapNotNull { it.content?.takeIf(String::isNotBlank) }
            .takeIf { it.isNotEmpty() && it.none(::looksLikeJson) }?.joinToString("\n\n")
    }

    val commandSpec = when {
        jobCard != null -> rememberJobCardSpec(jobCard, onOpenFull = openFullOutput, waitLimit = waitLimit?.let { jobWaitLimitText(it) })
        commandResult != null -> commandResultSpec(commandResult, invocation.durationMs, openFullOutput)
        plainCommandOutput != null -> commandResultSpec(SubagentCommandResult(plainCommandOutput, null), invocation.durationMs, openFullOutput)
        else -> null
    }
    val backgrounded = remember(invocation) { backgroundedJobId(invocation) != null }
    val tone = commandSpec?.tone ?: when {
        invocation.failed -> ToolStepTone.FAILED
        backgrounded -> ToolStepTone.NEUTRAL
        invocation.complete -> ToolStepTone.SUCCESS
        turnActive -> ToolStepTone.RUNNING
        else -> ToolStepTone.NEUTRAL
    }
    val status = commandSpec?.status ?: when {
        invocation.failed -> stringResource(R.string.tool_card_job_state_failed)
        !invocation.complete -> stringResource(if (turnActive) R.string.tool_card_job_state_running else R.string.tool_card_job_state_interrupted)
        backgrounded -> stringResource(R.string.tool_card_job_state_backgrounded)
        else -> invocation.durationMs?.let { stepDurationText(it) } ?: stringResource(R.string.tool_card_job_state_completed)
    }
    val summary = when (action) {
        ToolSummaryAction.IMAGE -> if (media.isEmpty() && imageData == null) emptyList()
            else mediaStepChips(remember(imageData, media) { imageGenerationFacts(imageData, media) }, viewed = false)
        ToolSummaryAction.MEDIA_VIEW -> if (media.isEmpty()) emptyList()
            else mediaStepChips(remember(media) { mediaViewFacts(media) }, viewed = true)
        else -> emptyList()
    }
    val title = when {
        imageEdit -> stringResource(R.string.tool_step_image_edit_title)
        else -> toolFriendlyName(invocation.name)
    }
    ToolStepCard(
        tone = tone,
        status = status,
        icon = toolStepIcon(invocation.name),
        summary = summary,
        statusTag = if (commandSpec != null) COMMAND_STATUS_TAG else null,
        // 给模型的参数与结果原文不在卡内铺开：命令有完整输出页时进输出页，其余进“完整结果”面板。
        onOpenFull = (commandSpec?.onOpenFull ?: openFullOutput) ?: raw?.let { text -> { fullResult = text } },
        openFullLabel = if (commandSpec?.onOpenFull != null || openFullOutput != null) stringResource(R.string.chat_tool_full_output) else null,
        footerMeta = commandSpec?.footerMeta,
        footerMetaColor = if (commandSpec?.footerFailed == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        footerActions = commandSpec?.onStop?.let { stop -> { JobStopButton(stop) } },
        modifier = if (jobCard != null) Modifier.testTag(JOB_CARD_TAG) else Modifier,
        title = {
            if (heading != null) {
                // 有短标题或能认出所操作的任务时，标题占主位，工具名退为小字。
                InfinityMetaLine(heading, style = InfinityTypography.bodySmall, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f), maxLines = 1)
            } else {
                ToolStepTitle(title, invocation.name)
            }
            if (invocation.ranInLinux) LinuxRuntimeChip()
        },
    ) {
        val invocationChanges = remember(invocation) { invocation.fileChanges }
        if (invocationChanges.isNotEmpty()) {
            com.promenar.infinity.ui.chat.diff.FileChangeList(invocationChanges, onOpen = onOpenChange)
        }
        val workspaceChanges = remember(invocation) { invocation.workspaceChanges }
        workspaceChanges?.let { com.promenar.infinity.ui.chat.diff.WorkspaceChangesList(it) }
        if (waitLimit != null && jobCard == null) {
            JobWaitProgress(waitLimit, startedAt = invocation.call?.timestamp, running = !invocation.complete,
                stepId = invocation.call?.id?.let(::unscopedStepId))
        }
        if (planCard != null) PlanToolCard(model = planCard)
        // 参数：生图只留提示词，查看媒体只留提问，命令只留命令本身，其余字段化为两行预览；
        // 文件编辑、子代理与保密输入输出由改动列表和专用卡说明，计划与任务操作的参数已在清单与标题里。
        val argumentsShown = planCard == null &&
            commandStep?.kind !in setOf(CommandStepKind.WAIT, CommandStepKind.STATUS, CommandStepKind.STOP) &&
            action !in setOf(ToolSummaryAction.FILE_EDIT, ToolSummaryAction.SUBAGENT, ToolSummaryAction.SECRET)
        when {
            !argumentsShown || arguments.isNullOrBlank() -> Unit
            action == ToolSummaryAction.IMAGE -> toolArgumentText(arguments, "prompt")?.let {
                ExpandableToolText(text = it, collapsedLines = 2, arguments = true, code = false)
            }
            action == ToolSummaryAction.SPEECH -> toolArgumentText(arguments, "text")?.let {
                ExpandableToolText(text = it, collapsedLines = 2, arguments = true, code = false)
            }
            action == ToolSummaryAction.MUSIC -> toolArgumentText(arguments, "prompt")?.let {
                ExpandableToolText(text = it, collapsedLines = 2, arguments = true, code = false)
            }
            action == ToolSummaryAction.MEDIA_VIEW -> toolArgumentText(arguments, "question")?.let {
                ExpandableToolText(text = it, collapsedLines = 2, arguments = true, code = false)
            }
            action == ToolSummaryAction.COMMAND && commandStep?.kind != CommandStepKind.INPUT ->
                commandArgumentText(arguments)?.let { ExpandableToolText(text = it, collapsedLines = 3, arguments = true) }
            else -> {
                val fields = remember(arguments) { toolFieldRows(arguments) }
                if (fields != null) ToolArgumentsPreview(fields)
            }
        }
        if (imageEdit) imageData?.inputImages?.takeIf { it.isNotEmpty() }?.let { paths ->
            com.promenar.infinity.ui.media.ToolReferenceThumbnails(paths)
        }
        if (commandSpec != null) CommandOutputBox(commandSpec.output, commandSpec.emptyText, commandSpec.note)
        invocation.results.forEach { result ->
            val resultMedia = result.media
            // 新结果以工作区媒体引用承载；旧数据仍从 data 字段取图片地址。
            val images = remember(result.data, resultMedia) {
                if (resultMedia.isNullOrEmpty()) extractToolImageModels(result.data) else emptyList()
            }
            if (!resultMedia.isNullOrEmpty()) {
                com.promenar.infinity.ui.media.ToolMediaList(media = resultMedia)
            }
            val displayedImages = images.take(4)
            var groupViewerStart by remember(displayedImages) { mutableStateOf<Int?>(null) }
            displayedImages.forEachIndexed { idx, imageModel ->
                ChatImageThumbnail(
                    imageModel = imageModel,
                    contentDescription = stringResource(R.string.chat_cd_tool_image),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 220.dp).clip(MaterialTheme.shapes.small),
                    contentScale = ContentScale.Fit,
                    onOpen = if (displayedImages.size > 1) ({ groupViewerStart = idx }) else null,
                )
            }
            groupViewerStart?.let { start ->
                com.promenar.infinity.ui.renderer.ImageLightboxGallery(
                    pages = displayedImages.map { com.promenar.infinity.ui.renderer.ImageLightboxPage(imageUrl = it) },
                    startIndex = start,
                    onDismiss = { groupViewerStart = null },
                )
            }
            // 成功结果的文本是给模型的上下文，经“完整结果”查看；卡内只留错误说明。
            if (result.type == "error") result.content?.takeIf(String::isNotBlank)?.let { ToolErrorBlock(it) }
        }
    }
    ToolFullResultSheet(fullResult) { fullResult = null }
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
private fun JobWaitProgress(limitSeconds: Int, startedAt: Long?, running: Boolean, stepId: String? = null) {
    val limit = jobWaitLimitText(limitSeconds)
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
    val sessionId = com.promenar.infinity.ui.chat.components.LocalAgentActivity.current?.sessionId
    val control by remember(sessionId, stepId, running) {
        if (sessionId == null || stepId == null || !running) kotlinx.coroutines.flow.flowOf(null)
        else com.promenar.infinity.data.exec.ToolExecutionControls.observeStep(sessionId, stepId)
    }.collectAsState(initial = null)
    // 会话底部已有生成状态动画，等待行只写计时；可提前释放时给出“立即检查”。
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = InfinityTypography.labelSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        control?.takeIf { it.kind == com.promenar.infinity.data.exec.ToolExecutionControls.Kind.WAITING && it.canCheckNow }?.let { state ->
            InfinityPillButton(
                text = stringResource(R.string.exec_steer_check_now),
                onClick = { com.promenar.infinity.data.exec.ToolExecutionControls.checkNow(state.sessionId, state.toolCallId) },
                compact = true,
                modifier = Modifier.testTag(UiTags.CHAT_JOB_WAIT_CHECK_NOW),
            )
        }
    }
}

/** “最长等待 5 分钟”：整分钟按分钟写，否则按秒。 */
@Composable
private fun jobWaitLimitText(limitSeconds: Int): String =
    if (limitSeconds % 60 == 0) stringResource(R.string.chat_step_job_wait_limit_minutes, limitSeconds / 60)
    else stringResource(R.string.chat_step_job_wait_limit_seconds, limitSeconds)

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
    /** 以等宽字体放进底色卡片；参数与命令输出使用。 */
    code: Boolean = arguments,
) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    val explicitLineOverflow = remember(text, collapsedLines) {
        text.lineSequence().take(collapsedLines + 1).count() > collapsedLines
    }
    var overflows by remember(text, collapsedLines) { mutableStateOf(explicitLineOverflow) }
    Column(
        modifier = if (code) Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest, RoundedCornerShape(8.dp)).padding(10.dp)
        else Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            style = if (code) InfinityTypography.bodySmall.copy(fontFamily = InfinityMonospace, fontSize = 12.sp, lineHeight = 18.sp)
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
                expanded = expanded,
                onClick = { expanded = !expanded }, modifier = Modifier.testTag("CHAT_TOOL_EXPAND_ALL"),
            )
        }
    }
}

/** 命令结果：退出码非零为失败；耗时与退出码进底栏。 */
@Composable
private fun commandResultSpec(command: SubagentCommandResult, durationMs: Long?, onOpenFull: (() -> Unit)?): CommandCardSpec {
    val failed = command.exitCode != null && command.exitCode != 0
    val duration = durationMs?.let { stringResource(R.string.tool_card_run_duration, stepDurationText(it)) }
    val exit = command.exitCode?.let { stringResource(R.string.tool_card_run_exit_code, it.toString()) }
    return CommandCardSpec(
        tone = if (failed) ToolStepTone.FAILED else ToolStepTone.SUCCESS,
        status = stringResource(if (failed) R.string.tool_card_job_state_failed else R.string.tool_card_job_state_completed),
        output = command.output,
        emptyText = stringResource(R.string.linux_output_empty),
        footerMeta = listOfNotNull(duration, exit).joinToString(" · ").ifEmpty { null }, // wrap-check: allow 单行页脚
        footerFailed = failed,
        onOpenFull = onOpenFull,
    )
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
    // 生成中与结束后正文分处两个父布局。可移动内容让正文换入选区容器时保留放出进度、解析结果与代码块横向滚动位置，
    // 收尾时积压文字继续渐显，不会因重建一次涌出、让条目高度跳变。
    val body = remember {
        movableContentOf { args: AssistantBodyArgs ->
            MarkdownText(
                markdown = args.content,
                isStreaming = args.isStreaming,
                showCursor = args.showCursor,
                fontSize = args.fontSize,
                onContentChange = args.onContentChange,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
    val args = AssistantBodyArgs(content, isStreaming, showStreamingCursor, fontSize, onContentChange)
    Box(modifier = Modifier.fillMaxWidth().testTag("CHAT_ASSISTANT_BODY")) {
        if (isStreaming) body(args) else QuotableSelectionContainer(sourceMessageId) { body(args) }
    }
}

private data class AssistantBodyArgs(
    val content: String,
    val isStreaming: Boolean,
    val showCursor: Boolean,
    val fontSize: Int,
    val onContentChange: ((String) -> Unit)?,
)

/** 一条消息正文的可移动内容；由按消息记住的表调用，每条消息只创建一次。 */
private fun movableContentSegment(): @Composable (ContentSegmentArgs) -> Unit =
    movableContentOf { args: ContentSegmentArgs ->
        ContentSegment(
            content = args.content,
            isStreaming = args.isStreaming,
            showStreamingCursor = args.showStreamingCursor,
            fontSize = args.fontSize,
            onContentChange = args.onContentChange,
            sourceMessageId = args.sourceMessageId,
        )
    }

/** 正文段的全部参数；结束收起时最终正文换到另一处调用位置，整体传给同一份可移动内容。 */
private data class ContentSegmentArgs(
    val content: String,
    val isStreaming: Boolean,
    val showStreamingCursor: Boolean,
    val fontSize: Int,
    val onContentChange: ((String) -> Unit)?,
    val sourceMessageId: String?,
)

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
    subsequentUserTurns: Int = 0,
    onDelete: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
    onRegenerate: (() -> Unit)? = null,
    onBranch: (() -> Unit)? = null,
    onEditSubmit: ((EditedUserMessagePayload) -> Unit)? = null,
    onBranchWithEdit: ((EditedUserMessagePayload) -> Unit)? = null,
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }
    val menuTouch = rememberInfinityMenuTouch()
    var showSelection by remember { mutableStateOf(false) }
    var isEditing by rememberSaveable(message.id) { mutableStateOf(false) }
    LaunchedEffect(onEditSubmit == null) {
        if (onEditSubmit == null) isEditing = false
    }
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
    AnimatedContent(
        targetState = isEditing && onEditSubmit != null,
        transitionSpec = {
            (fadeIn(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)) +
                expandVertically(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate)))
                .togetherWith(
                    fadeOut(tween(InfinityMotion.DurationShort)) +
                        shrinkVertically(tween(InfinityMotion.DurationShort)),
                )
        },
        modifier = modifier.fillMaxWidth(),
        label = "user_message_edit_transition",
    ) { editing ->
        if (editing && onEditSubmit != null) {
            UserMessageInlineEditorCard(
                message = message,
                fontSize = fontSize,
                subsequentUserTurns = subsequentUserTurns,
                onCancel = { isEditing = false },
                onSubmit = { payload ->
                    isEditing = false
                    onEditSubmit(payload)
                },
                onBranchAndGenerate = onBranchWithEdit?.let { handler ->
                    { payload ->
                        isEditing = false
                        handler(payload)
                    }
                },
            )
        } else {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val bubbleMaxWidth = maxWidth * 0.82f
                Box(modifier = Modifier.align(Alignment.TopEnd).widthIn(max = bubbleMaxWidth)) {
                    Column(
                        modifier = Modifier
                            .infinityMenuTouch(menuTouch)
                            .infinityContextClickable(
                                interactionSource = null,
                                indication = null,
                                touch = menuTouch,
                                pointerHand = false,
                                onClick = {},
                                onLongClick = { showMenu = true },
                            ),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (!message.userDocuments.isNullOrEmpty()) MessageDocumentSummary(message.userDocuments.orEmpty())
                        if (!message.userImages.isNullOrEmpty()) {
                            val userImages = message.userImages.orEmpty()
                            var viewerStart by remember(userImages) { mutableStateOf<Int?>(null) }
                            Row(
                                modifier = Modifier.widthIn(max = bubbleMaxWidth).horizontalScroll(rememberScrollState()).testTag("CHAT_USER_IMAGES"),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                userImages.forEachIndexed { idx, dataUrl ->
                                    ChatImageThumbnail(
                                        imageModel = dataUrl,
                                        contentDescription = stringResource(R.string.chat_cd_attached_image),
                                        modifier = Modifier.size(160.dp).clip(RoundedCornerShape(12.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        contentScale = ContentScale.Crop,
                                        onOpen = if (userImages.size > 1) ({ viewerStart = idx }) else null,
                                    )
                                }
                            }
                            viewerStart?.let { start ->
                                com.promenar.infinity.ui.renderer.ImageLightboxGallery(
                                    pages = userImages.map { com.promenar.infinity.ui.renderer.ImageLightboxPage(imageUrl = it) },
                                    startIndex = start,
                                    onDismiss = { viewerStart = null },
                                )
                            }
                        }
                        if (legacyAttachments.isNotEmpty()) LegacyAttachmentSummary(legacyAttachments)
                        UserMessageQuotes(message.quotes.orEmpty(), maxWidth = bubbleMaxWidth, fontSize = fontSize)
                        if (message.content.isNotBlank()) {
                            val wallpaper = com.promenar.infinity.ui.wallpaper.LocalChatWallpaperConfig.current
                            val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
                            val bubbleColor = if (wallpaper.isActive) {
                                if (dark) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f)
                                else MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.90f)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHigh
                            }
                            val bubbleTextColor = if (wallpaper.isActive && !dark) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                            val bubbleBorder = if (wallpaper.isActive) {
                                BorderStroke(
                                    0.5.dp,
                                    if (dark) InfinityPureWhite.copy(alpha = 0.12f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.40f),
                                )
                            } else {
                                null
                            }
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = bubbleColor,
                                border = bubbleBorder,
                                modifier = Modifier.widthIn(max = bubbleMaxWidth).testTag("CHAT_USER_BUBBLE"),
                            ) {
                                Text(
                                    text = message.content,
                                    style = InfinityTypography.bodyLarge.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.5).sp).forReading(),
                                    color = bubbleTextColor,
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
                        onEdit = if (onEditSubmit != null) ({ isEditing = true }) else null,
                        onRegenerate = onRegenerate,
                        onBranch = onBranch,
                        onDelete = { onDelete?.invoke(); showMenu = false },
                        isUser = true,
                        timestamp = timestamp,
                    )
                }
            }
        }
    }
    MessageTextSelectionSheet(
        show = showSelection, text = message.content, sourceMessageId = message.id, fontSize = fontSize,
        onDismiss = { showSelection = false },
    )
}

@Composable
private fun UserMessageInlineEditorCard(
    message: Message,
    fontSize: Int,
    subsequentUserTurns: Int,
    onCancel: () -> Unit,
    onSubmit: (EditedUserMessagePayload) -> Unit,
    onBranchAndGenerate: ((EditedUserMessagePayload) -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    var fieldValue by remember(message.id) {
        mutableStateOf(TextFieldValue(text = message.content, selection = TextRange(message.content.length)))
    }
    var retainedImages by remember(message.id) { mutableStateOf(message.userImages.orEmpty()) }
    var retainedDocuments by remember(message.id) { mutableStateOf(message.userDocuments.orEmpty()) }
    var retainedQuotes by remember(message.id) { mutableStateOf(message.quotes.orEmpty()) }

    LaunchedEffect(message.id) {
        focusRequester.requestFocus()
    }

    val hasAnyPayload = fieldValue.text.isNotBlank() ||
        retainedImages.isNotEmpty() ||
        retainedDocuments.isNotEmpty() ||
        retainedQuotes.isNotEmpty()

    fun buildPayload() = EditedUserMessagePayload(
        content = fieldValue.text,
        userImages = retainedImages,
        userDocuments = retainedDocuments,
        quotes = retainedQuotes,
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, scheme.primary.copy(alpha = 0.42f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.CHAT_USER_EDIT_CARD),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = stringResource(R.string.chat_edit_message_title),
                    style = InfinityTypography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = scheme.onSurface,
                )
            }

            if (retainedImages.isNotEmpty() || retainedDocuments.isNotEmpty()) {
                var editViewerStart by remember(retainedImages) { mutableStateOf<Int?>(null) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    retainedImages.forEachIndexed { idx, dataUrl ->
                        Box(
                            modifier = Modifier
                                .size(width = 92.dp, height = 64.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(scheme.surfaceContainerHighest)
                                .border(0.5.dp, scheme.outlineVariant, RoundedCornerShape(12.dp)),
                        ) {
                            ChatImageThumbnail(
                                imageModel = dataUrl,
                                contentDescription = stringResource(R.string.chat_cd_attached_image),
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                onOpen = if (retainedImages.size > 1) ({ editViewerStart = idx }) else null,
                            )
                            val removeImageCd = stringResource(R.string.chat_cd_remove_image)
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .size(32.dp)
                                    .clickable(role = Role.Button, onClickLabel = removeImageCd) {
                                        retainedImages = retainedImages.filterIndexed { i, _ -> i != idx }
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(scheme.surfaceContainerHighest.copy(alpha = 0.9f)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Close,
                                        contentDescription = removeImageCd,
                                        tint = scheme.onSurface,
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                    retainedDocuments.forEach { doc ->
                        EditableUserDocumentChip(
                            document = doc,
                            onRemove = { retainedDocuments = retainedDocuments.filterNot { it.id == doc.id } },
                        )
                    }
                }
                editViewerStart?.let { start ->
                    com.promenar.infinity.ui.renderer.ImageLightboxGallery(
                        pages = retainedImages.map { com.promenar.infinity.ui.renderer.ImageLightboxPage(imageUrl = it) },
                        startIndex = start.coerceIn(0, (retainedImages.size - 1).coerceAtLeast(0)),
                        onDismiss = { editViewerStart = null },
                    )
                }
            }

            retainedQuotes.forEachIndexed { idx, quote ->
                EditableUserQuoteCard(
                    quote = quote,
                    onRemove = { retainedQuotes = retainedQuotes.filterIndexed { i, _ -> i != idx } },
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = scheme.surfaceContainerLowest,
                border = BorderStroke(0.5.dp, scheme.outlineVariant),
                modifier = Modifier.fillMaxWidth(),
            ) {
                BasicTextField(
                    value = fieldValue,
                    onValueChange = { fieldValue = it },
                    textStyle = InfinityTypography.bodyLarge.copy(
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * 1.5).sp,
                        color = scheme.onSurface,
                    ).forInput(),
                    cursorBrush = SolidColor(scheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 68.dp, max = 220.dp)
                        .focusRequester(focusRequester)
                        .infinityImeSafeKeyEvent { keyEvent ->
                            when (
                                resolveInlineEditorKeyAction(
                                    key = keyEvent.key,
                                    type = keyEvent.type,
                                    isShiftPressed = keyEvent.isShiftPressed,
                                    isCtrlPressed = keyEvent.isCtrlPressed,
                                    isAltPressed = keyEvent.isAltPressed,
                                    isMetaPressed = keyEvent.isMetaPressed,
                                    isComposing = fieldValue.composition != null,
                                    canSubmit = hasAnyPayload,
                                )
                            ) {
                                InlineEditorKeyAction.CANCEL -> {
                                    onCancel()
                                    true
                                }
                                InlineEditorKeyAction.SUBMIT -> {
                                    onSubmit(buildPayload())
                                    true
                                }
                                InlineEditorKeyAction.CONSUME_NOOP -> true
                                InlineEditorKeyAction.PASS_THROUGH -> false
                            }
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .testTag(UiTags.CHAT_USER_EDIT_INPUT),
                )
            }

            if (subsequentUserTurns > 0) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(scheme.surfaceContainer)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Info,
                        contentDescription = null,
                        tint = scheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = stringResource(R.string.chat_edit_subsequent_turns_hint, subsequentUserTurns).keepCjkTailTogether(),
                        style = InfinityTypography.bodySmall.forReading(),
                        color = scheme.onSurfaceVariant,
                    )
                }
            }

            InfinityPillRow(
                modifier = Modifier.fillMaxWidth(),
                spacing = 8.dp,
                stackedSpacing = 8.dp,
                reverseWhenStacked = true,
            ) {
                InfinityPillButton(
                    text = stringResource(R.string.common_btn_cancel),
                    onClick = onCancel,
                    tone = InfinityPillTone.NEUTRAL,
                    modifier = Modifier.testTag(UiTags.CHAT_USER_EDIT_CANCEL),
                )
                if (subsequentUserTurns > 0 && onBranchAndGenerate != null) {
                    InfinityPillButton(
                        text = stringResource(R.string.chat_edit_branch_and_generate),
                        onClick = { onBranchAndGenerate(buildPayload()) },
                        tone = InfinityPillTone.NEUTRAL,
                        icon = Icons.Rounded.ForkRight,
                        enabled = hasAnyPayload,
                        modifier = Modifier.testTag(UiTags.CHAT_USER_EDIT_BRANCH),
                    )
                }
                InfinityPillButton(
                    text = stringResource(
                        if (subsequentUserTurns > 0) R.string.chat_edit_regenerate_here else R.string.chat_action_regenerate,
                    ),
                    onClick = { onSubmit(buildPayload()) },
                    tone = InfinityPillTone.PRIMARY,
                    icon = Icons.Rounded.Refresh,
                    enabled = hasAnyPayload,
                    modifier = Modifier.testTag(UiTags.CHAT_USER_EDIT_SUBMIT),
                )
            }
        }
    }
}

@Composable
private fun EditableUserDocumentChip(
    document: MessageDocumentAttachment,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val reference = document.workspaceReference
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainerLowest,
        border = BorderStroke(0.5.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (reference != null) Icons.Rounded.Folder else Icons.Rounded.Description,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Column(modifier = Modifier.widthIn(max = 150.dp)) {
                Text(
                    text = document.name,
                    style = InfinityTypography.labelMedium,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = reference?.let {
                        com.promenar.infinity.data.workspace.workspaceFileRelativePath(it.path).removePrefix("/")
                    } ?: formatMessageDocumentBytes(document.sizeBytes),
                    style = InfinityTypography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.chat_cd_remove_document),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun EditableUserQuoteCard(
    quote: MessageQuote,
    onRemove: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(0.5.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = Icons.Rounded.FormatQuote,
                contentDescription = null,
                tint = scheme.primary,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(16.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = quote.text,
                    style = InfinityTypography.bodySmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (quote.annotation.isNotBlank()) {
                    Text(
                        text = quote.annotation,
                        style = InfinityTypography.labelMedium,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            IconButton(
                onClick = onRemove,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.ux2_quote_remove_cd),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
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
    onOpen: (() -> Unit)? = null,
) {
    var showLightbox by remember(imageModel) { mutableStateOf(false) }
    val press = remember { MutableInteractionSource() }
    // 缩略图按下整体变暗，不在图片上叠圆角状态层。
    coil3.compose.AsyncImage(
        model = imageModel,
        contentDescription = contentDescription,
        modifier = modifier.dimOnPress(press).clickable(interactionSource = press, indication = null) {
            if (onOpen != null) onOpen() else showLightbox = true
        },
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
                val reference = document.workspaceReference
                Icon(if (reference != null) Icons.Rounded.Folder else Icons.Rounded.Description, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Column(modifier = Modifier.widthIn(max = 220.dp)) {
                    Text(document.name, style = InfinityTypography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // 已存入工作区的文档给出工作区内路径，与 Agent 用 read_file 读到的位置一致。
                    Text(
                        reference?.let { com.promenar.infinity.data.workspace.workspaceFileRelativePath(it.path).removePrefix("/") }
                            ?: formatMessageDocumentBytes(document.sizeBytes),
                        style = InfinityTypography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
                    )
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

/** 普通引用标志位：写入不触发重组。 */
private class LiveTurnFlag { var value: Boolean = false }

/** 流末尾状态位的最小高度：取等待行（36dp）与正文末尾生成动画行（32dp）中较高者，内容贴顶，位置与原先一致。 */
internal val LiveTailSlotHeight = CompactStepRowHeight

/** 流末尾状态位里显示的内容类别；同类之间只更新内容，不同类之间淡入淡出。 */
private enum class LiveTailContent { NONE, CONTENT_DOTS, WAITING }

private fun resolveLiveTailContent(liveStep: LiveStep?): LiveTailContent = when {
    liveStep == null -> LiveTailContent.NONE
    liveStep.kind == LiveStepKind.CONTENT -> LiveTailContent.CONTENT_DOTS
    liveStep.isTailRow -> LiveTailContent.WAITING
    else -> LiveTailContent.NONE
}

/** 思考、工具行或实时文件编辑批次直接接管流末尾状态位时零帧交接（不播缩高动画），避免新步骤行与旧尾部槽位重叠把列表顶起再弹回。 */
internal fun shouldExitLiveTailImmediately(
    isGenerating: Boolean,
    liveStep: LiveStep?,
    liveFileEditOwnsTail: Boolean = false,
): Boolean = isGenerating && (liveFileEditOwnsTail || liveStep?.kind == LiveStepKind.THINKING || liveStep?.kind == LiveStepKind.TOOL)

/**
 * 已有正文时的底部槽位最小高度：
 * - 生成结束态或末步为正文时保留 44dp（8dp 正文段距 + 36dp 行高），结束淡换或正文后接工具行均保持 ΔH = 0；
 * - 生成期间末步为思考/工具步骤时取 36dp，与下一行思考/工具步骤严格等高交接。
 */
internal fun resolveAssistantFooterMinHeight(
    isGenerating: Boolean,
    lastStepIsContent: Boolean,
): androidx.compose.ui.unit.Dp = if (!isGenerating || lastStepIsContent) LiveTailSlotHeight + 8.dp else LiveTailSlotHeight

/**
 * 生成期间流末尾的一行状态位：正文流式时显示生成动画，等待模型或准备工具调用时显示等待行；
 * 思考块或工具行自带运行指示时不占空位；由新思考/工具行接管时直接交接（[immediateExit]），消除列表上下晃动。
 */
@Composable
private fun LiveTailSlot(
    visible: Boolean,
    liveStep: LiveStep?,
    inlineInFooter: Boolean = false,
    immediateExit: Boolean = false,
) {
    val kind = resolveLiveTailContent(liveStep)
    val active = visible && kind != LiveTailContent.NONE
    val livePayload = if (active) (kind to liveStep) else null
    val shown = com.promenar.infinity.ui.theme.rememberLastNonNull(livePayload)
    val body: @Composable () -> Unit = {
        val target = shown ?: (LiveTailContent.NONE to null)
        androidx.compose.animation.AnimatedContent(
            targetState = target,
            contentKey = { it.first },
            transitionSpec = {
                (fadeIn(tween(InfinityMotion.DurationMedium)) togetherWith fadeOut(tween(InfinityMotion.DurationFadeOut)))
                    .using(androidx.compose.animation.SizeTransform(clip = false))
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = LiveTailSlotHeight).testTag(LIVE_TAIL_SLOT_TAG),
            contentAlignment = Alignment.TopStart,
            label = "liveTailSlot",
        ) { (content, step) ->
            when (content) {
                LiveTailContent.CONTENT_DOTS -> GenerationIndicator(step?.motionPhase ?: com.promenar.infinity.ui.common.motion.GenerationMotionPhase.STREAM)
                LiveTailContent.WAITING -> if (step != null) LiveWaitingRow(step)
                LiveTailContent.NONE -> Spacer(Modifier.fillMaxWidth())
            }
        }
    }
    if (inlineInFooter) {
        body()
    } else {
        AnimatedVisibility(
            visible = active,
            enter = InfinityMotion.expandIn,
            exit = if (immediateExit) ExitTransition.None else InfinityMotion.shrinkOut,
        ) {
            body()
        }
    }
}

internal const val LIVE_TAIL_SLOT_TAG = "CHAT_LIVE_TAIL_SLOT"

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
    onEdit: (() -> Unit)? = null,
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
        if (onEdit != null) {
            InfinityMenuItem(
                modifier = Modifier.testTag(UiTags.CHAT_MESSAGE_EDIT),
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                },
                text = { Text(stringResource(R.string.chat_action_edit), style = InfinityTypography.labelLarge) },
                onClick = {
                    onEdit()
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

/**
 * 居中极简细分割线骨架：上下文压缩的「进行中」「已锚定成功」「无需压缩」共用同一 36dp 轨道高度，
 * 确保原地淡换与锚定落位时零高度跳变。
 */
@Composable
internal fun CompactionTimelineDividerFrame(
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    center: @Composable () -> Unit,
) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .padding(vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val reservedForLinesAndTrailing = if (trailing != null) 76.dp else 44.dp
        val maxCenterWidth = (maxWidth - reservedForLinesAndTrailing).coerceAtLeast(120.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 0.5.dp,
                color = lineColor,
            )
            Box(
                modifier = Modifier
                    .widthIn(max = maxCenterWidth)
                    .padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                center()
            }
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.weight(1f),
                thickness = 0.5.dp,
                color = lineColor,
            )
            if (trailing != null) {
                trailing()
            }
        }
    }
}

/**
 * 上下文已压缩的永久时间线锚点分割线：固定在触发压缩的历史位置，随消息自然滚走、不带关闭按钮。
 */
@Composable
internal fun ContextCompactionTimelineDivider(
    delta: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    val title = stringResource(R.string.chat_context_compacted_title)
    CompactionTimelineDividerFrame(
        modifier = modifier
            .testTag("context_compaction_timeline_divider")
            .semantics(mergeDescendants = true) {},
        trailing = trailing,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.CheckCircleOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
                modifier = Modifier.size(13.dp),
            )
            InfinityMetaLine(
                segments = listOfNotNull(title, delta.takeIf(String::isNotBlank)),
                style = InfinityTypography.labelSmall.copy(
                    fontSize = 12.sp,
                    fontFeatureSettings = "tnum",
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.84f),
                maxLines = 1,
            )
        }
    }
}
