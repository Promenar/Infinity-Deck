package com.promenar.infinity.ui.lab.chat

import android.content.res.Resources
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.promenar.infinity.data.model.ExecutionStep
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatViewModel
import com.promenar.infinity.ui.chat.ThinkingClock
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.timeline.LabTimelineBuilder

/*
 * 会话页脚本的共用构件。时间一律写成相对脚本零点的毫秒数，由 [LabChatScene] 平移到墙钟。
 * 步骤 id 沿用真实执行器的约定：调用为 step_{时刻}_{callId}，结果为 res_step_{时刻}_{callId}；
 * 子代理分组与实时输出都以调用步骤 id 为键。
 */

internal const val LAB_MODEL = "DeepSeek V4.1 Flash"
internal const val LAB_AGENT = "Infinity"

/** 脚本里每段思考显示的时长。 */
internal const val LAB_THOUGHT_MS = 6_000L

/** 脚本模型的上下文窗口。 */
internal const val LAB_CONTEXT_TOKENS = 131_072

/** 输入框的上下文用量：从 [startTokens] 随阶段线性增长，到 [lastStage] 时为 [endTokens]。 */
internal fun labTokenState(stage: Int, lastStage: Int, startTokens: Int = 9_800, endTokens: Int = 44_600): ChatViewModel.TokenIndicatorState {
    val used = startTokens + (endTokens - startTokens) * stage.coerceIn(0, lastStage) / lastStage
    return ChatViewModel.TokenIndicatorState(used = used, max = LAB_CONTEXT_TOKENS, systemTokens = 7_400, activeTokens = used - 7_400)
}

internal fun isChinese(resources: Resources): Boolean =
    resources.configuration.locales[0].language == "zh"

internal fun labSession(title: String, executionMode: String) = Session(
    id = LAB_SESSION_ID,
    agentId = "lab-agent",
    title = title,
    modelId = LAB_MODEL,
    executionMode = executionMode,
    createdAt = 0,
    updatedAt = 0,
)

internal fun userMessage(id: String, text: String, at: Long) = Message(id, MessageRole.USER, text, createdAt = at)

/** 一条助手消息：正文在前，工具调用在后，与真实会话的排列一致。 */
internal fun assistantMessage(
    id: String,
    content: String,
    at: Long,
    calls: List<LabCall> = emptyList(),
    pendingApproval: Boolean = false,
) = Message(
    id = id,
    role = MessageRole.ASSISTANT,
    content = content,
    modelId = LAB_MODEL,
    createdAt = at,
    toolCalls = calls.map { ToolCall(it.callId, it.tool, it.args) }.takeIf { it.isNotEmpty() },
    executionSteps = if (pendingApproval) null else calls.flatMap { it.steps() }.takeIf { it.isNotEmpty() },
    pendingApprovalToolIds = if (pendingApproval) calls.map { it.callId } else null,
)

/**
 * 一次工具调用。[endAt] 为空表示仍在运行；[displayEndAt] 是结果步骤上记的结束时刻，
 * 决定折叠行里的耗时，可以早于结果在画面上出现的时刻。
 */
internal data class LabCall(
    val callId: String,
    val tool: String,
    val args: String,
    val at: Long,
    val endAt: Long? = null,
    val result: String = "",
    val failed: Boolean = false,
    val fileChanges: List<FileChangeSummary>? = null,
) {
    val stepId: String get() = "step_${at}_$callId"

    fun steps(): List<ExecutionStep> = listOfNotNull(
        ExecutionStep(id = stepId, type = "tool_call", toolName = tool, toolArgs = args, toolCallId = callId, timestamp = at),
        endAt?.let {
            ExecutionStep(
                id = "res_step_${at}_$callId",
                type = if (failed) "error" else "tool_result",
                toolName = tool,
                toolCallId = callId,
                content = result,
                timestamp = it,
                fileChanges = fileChanges,
            )
        },
    )
}

/** 带标题的命令参数；标题显示在运行记录中。 */
internal fun commandArgs(title: String, command: String, vararg extra: Pair<String, Any>): String {
    val fields = listOf("title" to title, "command" to command) + extra
    return fields.joinToString(",", "{", "}") { (key, value) ->
        "\"$key\":" + if (value is String) jsonString(value) else value.toString()
    }
}

internal fun jsonString(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            else -> append(char)
        }
    }
    append('"')
}

/** 在 [durationMs] 内匀速打完 [text]；脚本按时长编排，不随语言改变节奏。 */
internal fun <S> LabTimelineBuilder<S>.streamOver(at: Long, durationMs: Long, text: String, transform: (S, String) -> S) =
    stream(at, (text.length * 1000f / durationMs).coerceAtLeast(1f), text, transform)

/**
 * 会话页场景的统一出口：把脚本快照平移到墙钟后交给 [LabChatHost]。
 * [nowScriptMs] 是画面所处的脚本时刻：工作流传 0（锚点已随播放位置移动），静态版本传定格时刻。
 * 返回键总是接到版本页出口，[actions] 的其它操作由场景决定（检视器页签等），默认全部空操作。
 * [hub] 给出时，内屏按真实首页三列排出导航栏与会话列表（[expanded] 为 true 时以展开态开始）。
 */
@Composable
internal fun LabSceneScope.LabChatScene(
    frame: LabChatFrame,
    nowScriptMs: Long = 0,
    actions: ChatScreenActions = ChatScreenActions(),
    hub: LabHubStory? = null,
    expanded: Boolean = false,
    inspectorPageContent: @Composable BoxScope.() -> Unit = {},
) {
    val anchor = anchorMs - nowScriptMs
    val shifted = remember(frame, anchor) {
        // 思考时长由真实组件按进程内计时记录；脚本消息没有真实计时，先写入定好的时长。
        frame.screen.uiState.messages.forEach { message ->
            if (message.reasoning != null) ThinkingClock.shared.record(message.id, LAB_THOUGHT_MS)
        }
        frame.atWallClock(anchor)
    }
    if (hub == null) {
        LabChatHost(shifted, actions.copy(onNavigateBack = onExit), inspectorPageContent)
        return
    }
    val zh = isChinese(LocalContext.current.resources)
    val list = remember(hub, shifted, zh) { labHubList(hub, shifted, zh) }
    LabHubFrame(list, initialExpanded = expanded) {
        LabChatHost(shifted, actions.copy(onNavigateBack = onExit), inspectorPageContent)
    }
}
