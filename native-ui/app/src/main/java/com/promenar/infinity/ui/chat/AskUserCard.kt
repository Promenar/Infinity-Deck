package com.promenar.infinity.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.domain.ask.AskUserAnswer
import com.promenar.infinity.domain.ask.AskUserLimits
import com.promenar.infinity.domain.ask.AskUserQuestion
import com.promenar.infinity.domain.ask.AskUserRequest
import com.promenar.infinity.ui.common.LocalInfinityHaptics
import com.promenar.infinity.ui.common.InfinityHapticEvent
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinityPillRow
import com.promenar.infinity.ui.common.InfinityPillTone
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.infinityDomainColors
import kotlinx.serialization.json.Json

// ── 选择逻辑（纯函数，界面与测试共用）──

/** 点选第 [index] 个选项后的选择：单选替换，多选切换。 */
internal fun askUserToggle(question: AskUserQuestion, current: Set<Int>, index: Int): Set<Int> = when {
    !question.multiSelect -> setOf(index)
    index in current -> current - index
    else -> current + index
}

/** 每个问题都至少选了一项才能提交。 */
internal fun askUserIsComplete(request: AskUserRequest, selections: List<Set<Int>>): Boolean =
    request.questions.indices.all { selections.getOrNull(it).orEmpty().isNotEmpty() }

/** 单选题选定后跳到的下一题：当前题之后第一道未答的题，其次是之前未答的题；都答完时为 null。 */
internal fun askUserNextUnanswered(request: AskUserRequest, selections: List<Set<Int>>, current: Int): Int? {
    val order = (current + 1 until request.questions.size) + (0 until current)
    return order.firstOrNull { selections.getOrNull(it).isNullOrEmpty() }
}

/** 选择转成交回 Agent 的回答：以问题的 header 为键，选项 label 按原顺序列出。 */
internal fun askUserAnswerOf(request: AskUserRequest, selections: List<Set<Int>>): AskUserAnswer =
    AskUserAnswer(
        answers = request.questions.mapIndexedNotNull { index, question ->
            val picked = selections.getOrNull(index).orEmpty()
            if (picked.isEmpty()) null
            else question.header to question.options.indices.filter { it in picked }.map { question.options[it].label }
        }.toMap(),
    )

/** 已答提问的摘要行：header 与所选项（顿号连接）；自由回答与跳过由界面另行措辞。 */
internal data class AskUserAnswered(val request: AskUserRequest, val answer: AskUserAnswer)

private val askJson = Json { ignoreUnknownKeys = true }

/** 从 `ask_user` 调用的参数与成功结果还原已答提问；任一部分无法解析时返回 null，回退为普通工具行。 */
internal fun askUserAnswered(invocation: ToolInvocationPresentation): AskUserAnswered? {
    if (invocation.name != AskUserLimits.TOOL_NAME || invocation.failed || !invocation.complete) return null
    val request = invocation.call?.toolArgs
        ?.let { runCatching { askJson.decodeFromString(AskUserRequest.serializer(), it) }.getOrNull() }
        ?: return null
    val answer = invocation.results.firstNotNullOfOrNull { result ->
        result.content?.let { runCatching { askJson.decodeFromString(AskUserAnswer.serializer(), it) }.getOrNull() }
    } ?: return null
    return AskUserAnswered(request, answer)
}

/**
 * 提问不经工具执行器，助手消息里只有工具调用、回答是随后的 TOOL 消息；这里补成执行步骤，
 * 让提问与普通工具一样进入工具步骤，再由 [askUserSteps] 换成摘要卡。已有执行步骤的调用不重复补。
 */
internal fun askUserSyntheticSteps(
    message: com.promenar.infinity.data.model.Message,
    followingTools: List<com.promenar.infinity.data.model.Message>,
): List<com.promenar.infinity.data.model.ExecutionStep> {
    val recorded = message.executionSteps.orEmpty().mapNotNullTo(mutableSetOf()) { it.toolCallId }
    return message.toolCalls.orEmpty()
        .filter { it.name == AskUserLimits.TOOL_NAME && it.id !in recorded }
        .flatMap { call ->
            val result = followingTools.firstOrNull { it.toolCallId == call.id }
            listOfNotNull(
                com.promenar.infinity.data.model.ExecutionStep(
                    id = "ask-${call.id}", type = "tool_call", toolName = AskUserLimits.TOOL_NAME,
                    toolArgs = call.arguments, toolCallId = call.id, timestamp = message.createdAt,
                ),
                result?.let {
                    com.promenar.infinity.data.model.ExecutionStep(
                        id = "ask-${call.id}-result", type = if (isAskUserFailure(it.content)) "error" else "tool_result",
                        toolName = AskUserLimits.TOOL_NAME, toolCallId = call.id, content = it.content, timestamp = it.createdAt,
                    )
                },
            )
        }
}

/** 失败结果是 `{"status":"error",…}`；回答 JSON 没有 status 字段。 */
private fun isAskUserFailure(content: String): Boolean = runCatching {
    (askJson.parseToJsonElement(content) as? kotlinx.serialization.json.JsonObject)
        ?.get("status")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content } == "error"
}.getOrDefault(false)

/** 一组工具步骤中的提问：[answered] 渲染为摘要卡，[hidden] 是不再显示为工具行的步骤 ID（已答或仍待回答）。 */
internal data class AskUserSteps(val answered: List<AskUserAnswered>, val hidden: Set<String>)

internal fun askUserSteps(steps: List<com.promenar.infinity.data.model.ExecutionStep>): AskUserSteps {
    val invocations = toolInvocations(steps).filter { it.name == AskUserLimits.TOOL_NAME }
    if (invocations.isEmpty()) return AskUserSteps(emptyList(), emptySet())
    val answered = mutableListOf<AskUserAnswered>()
    val hidden = mutableSetOf<String>()
    invocations.forEach { invocation ->
        val parsed = askUserAnswered(invocation)
        // 失败的提问（参数不合法、被停止）保留为工具行，展示错误；成功与待回答的不再重复成工具行。
        if (parsed != null || !invocation.complete) invocation.steps.mapTo(hidden) { it.id }
        parsed?.let(answered::add)
    }
    return AskUserSteps(answered, hidden)
}

// ── 待回答提问卡 ──

/**
 * 待回答的提问卡：多题时以页签切换，单选题选定后自动跳到下一道未答题；“都不合适”时用户在输入栏写自由回答。
 * [onSubmit] 交回各题所选，[onSkip] 跳过整次提问。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskUserCard(
    request: AskUserRequest,
    onSubmit: (AskUserAnswer) -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** 区分不同提问的键（工具调用 ID），换题时选择状态重置。 */
    stateKey: String = "",
) {
    val haptics = LocalInfinityHaptics.current
    val warning = MaterialTheme.infinityDomainColors.warning
    val count = request.questions.size
    var tab by rememberSaveable(stateKey) { mutableIntStateOf(0) }
    var selections by rememberSaveable(stateKey, saver = SelectionsSaver) { mutableStateOf(List(count) { emptySet<Int>() }) }
    val complete = askUserIsComplete(request, selections)
    val question = request.questions.getOrNull(tab) ?: return
    val last = tab == count - 1
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .animateContentSize()
            .testTag(UiTags.CHAT_ASK_CARD),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, warning.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null, tint = warning, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (count > 1) pluralStringResource(R.plurals.ask_card_title_many, count, count)
                        else stringResource(R.string.ask_card_title),
                    style = MaterialTheme.typography.labelLarge,
                    color = warning,
                    modifier = Modifier.semantics { heading() },
                )
            }
            if (count > 1) {
                FlowRow(
                    modifier = Modifier.padding(top = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    request.questions.forEachIndexed { index, item ->
                        val done = selections.getOrNull(index).orEmpty().isNotEmpty()
                        val doneLabel = stringResource(R.string.ask_card_tab_done)
                        FilterChip(
                            selected = index == tab,
                            onClick = { tab = index },
                            label = { Text("${index + 1} ${item.header}") },
                            leadingIcon = if (done && index != tab) {
                                { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                            } else null,
                            modifier = Modifier
                                .semantics { if (done) stateDescription = doneLabel }
                                .testTag(UiTags.CHAT_ASK_TAB_PREFIX + index),
                        )
                    }
                }
            }
            AnimatedContent(
                targetState = tab,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "ask_card_question",
            ) { shown ->
                val current = request.questions[shown]
                Column(Modifier.padding(top = 14.dp)) {
                    Text(current.question, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium))
                    Text(
                        text = stringResource(if (current.multiSelect) R.string.ask_card_multi_hint else R.string.ask_card_single_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                    Column(Modifier.padding(top = 8.dp).selectableGroup()) {
                        current.options.forEachIndexed { index, option ->
                            val checked = index in selections.getOrNull(shown).orEmpty()
                            val pick = {
                                haptics.perform(InfinityHapticEvent.SELECTION)
                                val next = askUserToggle(current, selections[shown], index)
                                val updated = selections.toMutableList().also { it[shown] = next }
                                selections = updated
                                if (!current.multiSelect) askUserNextUnanswered(request, updated, shown)?.let { tab = it }
                            }
                            val rowModifier = if (current.multiSelect) {
                                Modifier.toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = { pick() })
                            } else {
                                Modifier.selectable(selected = checked, enabled = enabled, role = Role.RadioButton, onClick = pick)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .then(rowModifier)
                                    .padding(vertical = 6.dp)
                                    .testTag("${UiTags.CHAT_ASK_OPTION_PREFIX}${shown}_$index"),
                                verticalAlignment = Alignment.Top,
                            ) {
                                Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                                    if (current.multiSelect) Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
                                    else RadioButton(selected = checked, onClick = null, enabled = enabled)
                                }
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f).padding(top = 5.dp)) {
                                    Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                    if (option.description.isNotBlank()) {
                                        Text(
                                            option.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(top = 2.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(
                modifier = Modifier.padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.ask_card_other_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // 未到最后一题时主按钮是“下一题”；到最后一题时提交，所有题都答了才可用。
            val primaryEnabled = enabled && if (last) complete else selections[tab].isNotEmpty()
            InfinityPillRow(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), spacing = 8.dp) {
                InfinityPillButton(
                    text = stringResource(R.string.ask_card_skip),
                    onClick = onSkip,
                    enabled = enabled,
                    modifier = Modifier.testTag(UiTags.CHAT_ASK_SKIP),
                )
                InfinityPillButton(
                    text = stringResource(if (last) R.string.ask_card_submit else R.string.ask_card_next),
                    onClick = {
                        if (last) {
                            haptics.perform(InfinityHapticEvent.CONFIRM)
                            onSubmit(askUserAnswerOf(request, selections))
                        } else {
                            tab += 1
                        }
                    },
                    tone = InfinityPillTone.PRIMARY,
                    enabled = primaryEnabled,
                    modifier = Modifier.testTag(UiTags.CHAT_ASK_SUBMIT),
                )
            }
        }
    }
}

private val SelectionsSaver = androidx.compose.runtime.saveable.Saver<androidx.compose.runtime.MutableState<List<Set<Int>>>, ArrayList<IntArray>>(
    save = { state -> ArrayList(state.value.map { it.toIntArray() }) },
    restore = { saved -> mutableStateOf(saved.map { it.toSet() }) },
)

// ── 已答提问摘要 ──

/** 回合中已答的提问：每题一行“header：所选项”，自由回答与跳过各占一行。 */
@Composable
internal fun AskUserAnsweredCard(answered: AskUserAnswered, modifier: Modifier = Modifier) {
    val answer = answered.answer
    val rows: List<Pair<String, String>> = when {
        answer.skipped -> listOf(stringResource(R.string.ask_card_answered_skipped_head) to stringResource(R.string.ask_card_answered_skipped))
        else -> buildList {
            answered.request.questions.forEach { question ->
                answer.answers[question.header]?.takeIf { it.isNotEmpty() }?.let {
                    add(question.header to it.joinToString(stringResource(R.string.ask_card_answer_separator)))
                }
            }
            answer.other?.takeIf(String::isNotBlank)?.let { add(stringResource(R.string.ask_card_answered_other) to it) }
        }
    }
    Surface(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(UiTags.CHAT_ASK_ANSWERED),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(if (answer.skipped) R.string.ask_card_answered_title_skipped else R.string.ask_card_answered_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            rows.forEach { (head, value) ->
                Row {
                    Text(
                        head,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(88.dp),
                    )
                    Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
