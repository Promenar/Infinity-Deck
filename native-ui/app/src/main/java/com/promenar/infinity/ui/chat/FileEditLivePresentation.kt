package com.promenar.infinity.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.data.model.FileChangeSummary
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.ui.chat.diff.shownAdded
import com.promenar.infinity.ui.chat.diff.shownRemoved
import com.promenar.infinity.ui.common.motion.GenerationMotionPhase
import com.promenar.infinity.ui.common.rememberStreamAnimationsEnabled
import com.promenar.infinity.ui.theme.InfinityMonospace
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.infinityDomainColors
import java.util.Locale

internal const val LIVE_FILE_EDIT_BATCH_TAG = "CHAT_LIVE_FILE_EDIT_BATCH"
internal const val LIVE_FILE_EDIT_ROW_TAG = "CHAT_LIVE_FILE_EDIT_ROW"

/** 产生文件写入/变更的内置工具集合（不含只读 diff_file）。 */
internal val LIVE_FILE_EDIT_TOOL_NAMES = setOf(
    "patch_file",
    "write_file",
    "create_file",
    "move_file",
    "delete_file",
    "create_directory",
)

internal fun isLiveFileEditTool(name: String?): Boolean = name in LIVE_FILE_EDIT_TOOL_NAMES

internal enum class LiveFileEditRowStatus {
    ACTIVE,
    COMPLETED,
    FAILED,
}

/** 从完整或半截流式工具参数中提取出的单次文件编辑估算。 */
internal data class LiveFileEditEstimate(
    val path: String,
    val displayName: String,
    val added: Int?,
    val removed: Int?,
)

/** 实时文件编辑批次中的单行展示模型（同一文件多次编辑合并在原行累加）。 */
internal data class LiveFileEditRowState(
    val key: String,
    val path: String,
    val displayName: String,
    val added: Int?,
    val removed: Int?,
    val status: LiveFileEditRowStatus,
    val motionPhase: GenerationMotionPhase = GenerationMotionPhase.TOOL,
    val startedAt: Long? = null,
    val latestChange: FileChangeSummary? = null,
)

/**
 * 连续文件编辑批次的聚合状态：
 * - [isPureFileEditStep] 为真时，活跃期间整块替代摘要行，结束后平滑折叠回单行汇总；
 * - 为假（混合工具组尾部的连续编辑段）时，上方摘要仅总结前置非编辑工具，下方挂接本批次实时文件行，结束后平滑折叠并回填完整摘要。
 */
internal data class LiveFileEditBatchState(
    val rows: List<LiveFileEditRowState>,
    val isLiveBatch: Boolean,
    val hasActiveRow: Boolean,
    val isPureFileEditStep: Boolean,
    val trailingInvocationCount: Int,
)

internal fun fileEditDisplayName(path: String): String =
    path.trim().trimEnd('/').substringAfterLast('/').ifBlank { path.trim() }

internal fun normalizeFileEditKey(path: String): String =
    path.trim().trimStart('/').trimEnd('/').lowercase(Locale.ROOT)

private val UUID_PATH_PATTERNS = listOf(
    Regex("""文件:\s*(.+?)\s*\[uuid=([^\]]+)]"""),
    Regex("""条目:\s*(.+?)\s*\[uuid=([^\]]+)]"""),
    Regex("""\[(?:FILE|DIR)]\s+(.+?)(?:\s+\([^)]*\))?\s*\[uuid=([^\]]+)]"""),
    Regex("""已创建(?:空文件|目录)?\s+(.+?)\s*\[uuid=([^\]]+)]"""),
    Regex("""已完成：(.+?)\s*\[uuid=([^\]]+)]"""),
)

/** 从本轮已有的工具输出（如 read_file / list_files / create_file）建立 uuid -> 路径映射，便于仅传 uuid 的写入调用仍展示真实文件名。 */
internal fun extractKnownUuidPaths(messages: List<Message>): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    fun scanText(text: String?) {
        if (text.isNullOrBlank() || "[uuid=" !in text) return
        text.lineSequence().forEach { line ->
            if ("[uuid=" !in line) return@forEach
            for (pattern in UUID_PATH_PATTERNS) {
                val match = pattern.find(line) ?: continue
                val path = match.groupValues[1].trim()
                val uuid = match.groupValues[2].trim()
                if (path.isNotEmpty() && uuid.isNotEmpty()) {
                    map[uuid] = path
                    break
                }
            }
        }
    }
    messages.forEach { message ->
        if (message.role == MessageRole.TOOL) scanText(message.content)
        message.executionSteps.orEmpty().forEach { step ->
            if (step.type == "tool_result") scanText(step.content)
        }
    }
    return map
}

/** 获取本轮最后一条助手消息上尚未落库成 executionSteps 的流式工具调用。 */
internal fun unrecordedStreamingToolCalls(messages: List<Message>): List<ToolCall> {
    val finalAssistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT && !it.isSessionTimelineRecord }
        ?: return emptyList()
    val recordedIds = finalAssistant.executionSteps.orEmpty().mapNotNullTo(HashSet()) { it.toolCallId }
    return finalAssistant.toolCalls.orEmpty().filter { it.id !in recordedIds }
}

/** 估算流式或完整文本行数：空文本计 0，末尾单独换行不额外多计一行，与 FileDiffModel 保持一致。 */
internal fun countStreamingLines(text: String): Int {
    if (text.isEmpty()) return 0
    val effective = if (text.endsWith('\n')) text.dropLast(1) else text
    if (effective.isEmpty()) return 0
    var lines = 1
    for (ch in effective) {
        if (ch == '\n') lines++
    }
    return lines
}

/**
 * 解析完整或半截 JSON 参数，提取目标文件路径与实时增删行数估算。
 * 路径/文件名仅在 JSON 字符串闭合后采纳，避免流式输出时文件名逐字抖动；
 * 正文与补丁内容支持未闭合流式字符串，实现 +N / −M 随写入逐行跳动。
 */
internal fun parseLiveFileEditCall(
    toolName: String?,
    rawArgs: String?,
    knownUuidPaths: Map<String, String> = emptyMap(),
): LiveFileEditEstimate? {
    if (!isLiveFileEditTool(toolName) || rawArgs.isNullOrBlank()) return null
    val top = scanTopLevelJsonFields(rawArgs)
    val resolvedPath = when (toolName) {
        "create_file", "create_directory" -> {
            top.closedString("path")
                ?: top.closedString("name")?.let { name -> combineParentPath(top.closedString("parentPath"), name) }
        }
        "move_file" -> {
            top.closedString("newName")?.let { newName ->
                combineParentPath(top.closedString("newParentPath"), newName)
            } ?: top.closedString("path") ?: top.closedString("uuid")?.let(knownUuidPaths::get)
        }
        else -> {
            top.closedString("path") ?: top.closedString("uuid")?.let(knownUuidPaths::get)
        }
    }?.trim()?.takeIf(String::isNotEmpty) ?: return null

    val displayName = fileEditDisplayName(resolvedPath)
    return when (toolName) {
        "patch_file" -> {
            val (added, removed) = estimatePatchOperationsCounts(top.arrayRaw["operations"])
            LiveFileEditEstimate(resolvedPath, displayName, added, removed)
        }
        "write_file" -> {
            val content = top.anyString("content")
            val added = content?.let(::countStreamingLines)?.takeIf { it > 0 }
            LiveFileEditEstimate(resolvedPath, displayName, added = added, removed = null)
        }
        "create_file" -> {
            val content = top.anyString("content")
            val added = content?.let(::countStreamingLines)?.takeIf { it > 0 }
            LiveFileEditEstimate(resolvedPath, displayName, added = added, removed = null)
        }
        else -> LiveFileEditEstimate(resolvedPath, displayName, added = null, removed = null)
    }
}

private fun combineParentPath(parentPath: String?, name: String): String {
    val cleanName = name.trim().trimStart('/')
    val cleanParent = parentPath?.trim()?.trimEnd('/').orEmpty()
    return if (cleanParent.isEmpty()) cleanName else "$cleanParent/$cleanName"
}

private fun estimatePatchOperationsCounts(operationsRaw: String?): Pair<Int?, Int?> {
    if (operationsRaw.isNullOrBlank()) return null to null
    var totalAdded = 0
    var totalRemoved = 0
    var hasAdded = false
    var hasRemoved = false
    extractTopLevelObjectsFromArray(operationsRaw).forEach { opRaw ->
        val op = scanTopLevelJsonFields(opRaw)
        val action = op.closedString("action")
        val startLine = op.intField("startLine")
        val endLine = op.intField("endLine")
        val rangeCount = if (startLine != null && endLine != null && endLine >= startLine) {
            endLine - startLine + 1
        } else null
        val newContent = op.anyString("newContent")
        val expectedContent = op.anyString("expectedContent")

        when (action) {
            "delete_lines" -> {
                val rem = rangeCount ?: expectedContent?.let(::countStreamingLines)
                if (rem != null && rem > 0) {
                    totalRemoved += rem
                    hasRemoved = true
                }
            }
            "insert_after" -> {
                val add = newContent?.let(::countStreamingLines)
                if (add != null && add > 0) {
                    totalAdded += add
                    hasAdded = true
                }
            }
            "replace_lines" -> {
                val rem = rangeCount ?: expectedContent?.let(::countStreamingLines)
                if (rem != null && rem > 0) {
                    totalRemoved += rem
                    hasRemoved = true
                }
                val add = newContent?.let(::countStreamingLines)
                if (add != null && add > 0) {
                    totalAdded += add
                    hasAdded = true
                }
            }
            else -> {
                // 流式参数若先输出 newContent 或行号范围，仍尽量给出增量反馈。
                val add = newContent?.let(::countStreamingLines)
                if (add != null && add > 0) {
                    totalAdded += add
                    hasAdded = true
                }
                if (rangeCount != null && rangeCount > 0) {
                    totalRemoved += rangeCount
                    hasRemoved = true
                }
            }
        }
    }
    return (if (hasAdded) totalAdded else null) to (if (hasRemoved) totalRemoved else null)
}

/**
 * 聚合工具组尾部的连续文件编辑调用与尚未落库的流式文件编辑调用。
 * 同一路径在同批次被连续编辑多次时，保留在原行并累计增删行数。
 */
internal fun buildLiveFileEditBatch(
    invocations: List<ToolInvocationPresentation>,
    streamingCalls: List<ToolCall>,
    knownUuidPaths: Map<String, String>,
    isTurnGenerating: Boolean,
    isLastStepInTurn: Boolean,
    hasPendingApproval: Boolean,
    liveStep: LiveStep?,
): LiveFileEditBatchState? {
    val trailingInvocations = invocations.takeLastWhile { isLiveFileEditTool(it.name) }
    val hasNonEditStreaming = streamingCalls.any { !isLiveFileEditTool(it.name) }
    val streamingEdits = if (hasNonEditStreaming) emptyList() else streamingCalls.filter { isLiveFileEditTool(it.name) }
    if (trailingInvocations.isEmpty() && streamingEdits.isEmpty()) return null

    val isPureFileEditStep = trailingInvocations.size == invocations.size
    val rowsByKey = LinkedHashMap<String, MutableRowAccumulator>()

    trailingInvocations.forEach { invocation ->
        val changes = invocation.fileChanges
        if (invocation.complete && changes.isNotEmpty()) {
            changes.forEach { change ->
                val key = normalizeFileEditKey(change.path)
                val acc = rowsByKey.getOrPut(key) {
                    MutableRowAccumulator(
                        key = key,
                        path = change.path,
                        displayName = fileEditDisplayName(change.path),
                    )
                }
                acc.applyCompletedChange(change)
            }
        } else {
            val estimate = parseLiveFileEditCall(invocation.name, invocation.call?.toolArgs, knownUuidPaths)
            if (estimate != null) {
                val key = normalizeFileEditKey(estimate.path)
                val acc = rowsByKey.getOrPut(key) {
                    MutableRowAccumulator(
                        key = key,
                        path = estimate.path,
                        displayName = estimate.displayName,
                    )
                }
                if (!invocation.complete) {
                    acc.applyActiveEstimate(
                        estimate = estimate,
                        motionPhase = GenerationMotionPhase.TOOL,
                        startedAt = invocation.call?.timestamp?.takeIf { it > 0 },
                    )
                } else if (invocation.failed) {
                    acc.markFailed()
                } else {
                    acc.markCompletedNoCounts()
                }
            }
        }
    }

    if (isTurnGenerating && isLastStepInTurn && !hasPendingApproval) {
        streamingEdits.forEach { call ->
            val estimate = parseLiveFileEditCall(call.name, call.arguments, knownUuidPaths) ?: return@forEach
            val key = normalizeFileEditKey(estimate.path)
            val acc = rowsByKey.getOrPut(key) {
                MutableRowAccumulator(
                    key = key,
                    path = estimate.path,
                    displayName = estimate.displayName,
                )
            }
            acc.applyActiveEstimate(
                estimate = estimate,
                motionPhase = GenerationMotionPhase.STREAM,
                startedAt = liveStep?.clockStart,
            )
        }
    }

    val rows = rowsByKey.values.map { it.toRowState() }
    if (rows.isEmpty()) return null

    val hasActiveRow = rows.any { it.status == LiveFileEditRowStatus.ACTIVE }
    val waitingForNextEditInBatch = isTurnGenerating && isLastStepInTurn && !hasPendingApproval &&
        !hasNonEditStreaming &&
        (liveStep?.kind == LiveStepKind.WAITING_MODEL || liveStep?.kind == LiveStepKind.PREPARING_TOOL)
    val isLiveBatch = isTurnGenerating && isLastStepInTurn && !hasPendingApproval && !hasNonEditStreaming &&
        (hasActiveRow || waitingForNextEditInBatch)

    return LiveFileEditBatchState(
        rows = rows,
        isLiveBatch = isLiveBatch,
        hasActiveRow = hasActiveRow,
        isPureFileEditStep = isPureFileEditStep,
        trailingInvocationCount = trailingInvocations.size,
    )
}

private class MutableRowAccumulator(
    val key: String,
    var path: String,
    var displayName: String,
) {
    private var committedAdded: Int = 0
    private var hasCommittedAdded: Boolean = false
    private var committedRemoved: Int = 0
    private var hasCommittedRemoved: Boolean = false
    private var activeAdded: Int? = null
    private var activeRemoved: Int? = null
    private var status: LiveFileEditRowStatus = LiveFileEditRowStatus.COMPLETED
    private var motionPhase: GenerationMotionPhase = GenerationMotionPhase.TOOL
    private var startedAt: Long? = null
    private var latestChange: FileChangeSummary? = null

    fun applyCompletedChange(change: FileChangeSummary) {
        path = change.path
        displayName = fileEditDisplayName(change.path)
        change.shownAdded?.let {
            committedAdded += it
            hasCommittedAdded = true
        }
        change.shownRemoved?.let {
            committedRemoved += it
            hasCommittedRemoved = true
        }
        activeAdded = null
        activeRemoved = null
        status = LiveFileEditRowStatus.COMPLETED
        startedAt = null
        latestChange = change
    }

    fun applyActiveEstimate(estimate: LiveFileEditEstimate, motionPhase: GenerationMotionPhase, startedAt: Long?) {
        path = estimate.path
        displayName = estimate.displayName
        activeAdded = estimate.added
        activeRemoved = estimate.removed
        status = LiveFileEditRowStatus.ACTIVE
        this.motionPhase = motionPhase
        this.startedAt = startedAt
    }

    fun markFailed() {
        activeAdded = null
        activeRemoved = null
        status = LiveFileEditRowStatus.FAILED
        startedAt = null
    }

    fun markCompletedNoCounts() {
        activeAdded = null
        activeRemoved = null
        status = LiveFileEditRowStatus.COMPLETED
        startedAt = null
    }

    fun toRowState(): LiveFileEditRowState {
        val totalAdded = when {
            hasCommittedAdded || activeAdded != null -> committedAdded + (activeAdded ?: 0)
            else -> null
        }
        val totalRemoved = when {
            hasCommittedRemoved || activeRemoved != null -> committedRemoved + (activeRemoved ?: 0)
            else -> null
        }
        return LiveFileEditRowState(
            key = key,
            path = path,
            displayName = displayName,
            added = totalAdded,
            removed = totalRemoved,
            status = status,
            motionPhase = motionPhase,
            startedAt = startedAt,
            latestChange = latestChange,
        )
    }
}

// ─────────────────────────────────────────────────────────────────
//  轻量级流式 JSON 扫描器（容错未闭合字符串/对象/数组，无正则回溯）
// ─────────────────────────────────────────────────────────────────

private class TopLevelJsonFields(
    val closedStrings: Map<String, String>,
    val partialStrings: Map<String, String>,
    val ints: Map<String, Int>,
    val arrayRaw: Map<String, String>,
) {
    fun closedString(key: String): String? = closedStrings[key]
    fun anyString(key: String): String? = closedStrings[key] ?: partialStrings[key]
    fun intField(key: String): Int? = ints[key]
}

private fun scanTopLevelJsonFields(raw: String): TopLevelJsonFields {
    val closedStrings = LinkedHashMap<String, String>()
    val partialStrings = LinkedHashMap<String, String>()
    val ints = LinkedHashMap<String, Int>()
    val arrayRaw = LinkedHashMap<String, String>()

    val n = raw.length
    var i = skipWhitespace(raw, 0)
    if (i >= n || raw[i] != '{') return TopLevelJsonFields(closedStrings, partialStrings, ints, arrayRaw)
    i++

    while (i < n) {
        i = skipWhitespace(raw, i)
        if (i >= n || raw[i] == '}') break
        if (raw[i] == ',') {
            i++
            continue
        }
        if (raw[i] != '"') break
        val keyRes = readJsonString(raw, i)
        if (!keyRes.closed) break
        val key = keyRes.value
        i = skipWhitespace(raw, keyRes.nextIndex)
        if (i >= n || raw[i] != ':') break
        i = skipWhitespace(raw, i + 1)
        if (i >= n) break

        when (raw[i]) {
            '"' -> {
                val valRes = readJsonString(raw, i)
                if (valRes.closed) closedStrings[key] = valRes.value
                else partialStrings[key] = valRes.value
                i = valRes.nextIndex
            }
            '[' -> {
                val end = findBalancedEnd(raw, i, '[', ']')
                arrayRaw[key] = raw.substring(i, end)
                i = end
            }
            '{' -> {
                i = findBalancedEnd(raw, i, '{', '}')
            }
            else -> {
                val start = i
                while (i < n && raw[i] != ',' && raw[i] != '}' && !raw[i].isWhitespace()) i++
                val token = raw.substring(start, i)
                // 只有在数字后见到逗号、右花括号或空白才视为完整数字，避免半截行号（如 12 截成 1）抖动。
                if (i < n) {
                    token.toIntOrNull()?.let { ints[key] = it }
                }
            }
        }
    }
    return TopLevelJsonFields(closedStrings, partialStrings, ints, arrayRaw)
}

private fun extractTopLevelObjectsFromArray(arrayText: String): List<String> {
    val result = ArrayList<String>()
    val n = arrayText.length
    var i = skipWhitespace(arrayText, 0)
    if (i >= n || arrayText[i] != '[') return result
    i++
    while (i < n) {
        i = skipWhitespace(arrayText, i)
        if (i >= n || arrayText[i] == ']') break
        if (arrayText[i] == ',') {
            i++
            continue
        }
        if (arrayText[i] == '{') {
            val end = findBalancedEnd(arrayText, i, '{', '}')
            result += arrayText.substring(i, end)
            i = end
        } else {
            i++
        }
    }
    return result
}

private fun skipWhitespace(s: String, start: Int): Int {
    var i = start
    while (i < s.length && s[i].isWhitespace()) i++
    return i
}

private data class JsonStringReadResult(val value: String, val closed: Boolean, val nextIndex: Int)

private fun readJsonString(s: String, startQuote: Int): JsonStringReadResult {
    val sb = StringBuilder()
    var i = startQuote + 1
    val n = s.length
    while (i < n) {
        val ch = s[i]
        if (ch == '"') return JsonStringReadResult(sb.toString(), closed = true, nextIndex = i + 1)
        if (ch == '\\') {
            if (i + 1 >= n) return JsonStringReadResult(sb.toString(), closed = false, nextIndex = n)
            when (val esc = s[i + 1]) {
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                '/' -> { sb.append('/'); i += 2 }
                'b' -> { sb.append('\b'); i += 2 }
                'f' -> { sb.append('\u000C'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    if (i + 6 <= n) {
                        val hex = s.substring(i + 2, i + 6)
                        val code = hex.toIntOrNull(16)
                        if (code != null) sb.append(code.toChar())
                        i += 6
                    } else {
                        return JsonStringReadResult(sb.toString(), closed = false, nextIndex = n)
                    }
                }
                else -> { sb.append(esc); i += 2 }
            }
        } else {
            sb.append(ch)
            i++
        }
    }
    return JsonStringReadResult(sb.toString(), closed = false, nextIndex = n)
}

private fun findBalancedEnd(s: String, start: Int, open: Char, close: Char): Int {
    var depth = 0
    var inString = false
    var escaped = false
    var i = start
    val n = s.length
    while (i < n) {
        val ch = s[i]
        if (inString) {
            if (escaped) escaped = false
            else if (ch == '\\') escaped = true
            else if (ch == '"') inString = false
        } else {
            when (ch) {
                '"' -> inString = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return i + 1
                }
            }
        }
        i++
    }
    return n
}

// ─────────────────────────────────────────────────────────────────
//  Compose UI：逐行实时文件变更清单 + 异向纵向滚动行数计数
// ─────────────────────────────────────────────────────────────────

/**
 * 异向纵向滚动行数计数：
 * - `+N`（成功色）向上滚动入场（新值自下而上顶出旧值）；
 * - `−M`（错误色）向下滚动入场（新值自上而下压出旧值）。
 * 遵循系统减少动态效果与静态预览模式。
 */
@Composable
internal fun RollingFileChangeCounts(
    added: Int?,
    removed: Int?,
    modifier: Modifier = Modifier,
) {
    if (added == null && removed == null) return
    val addedColor = MaterialTheme.infinityDomainColors.success
    val removedColor = MaterialTheme.colorScheme.error
    val animate = rememberStreamAnimationsEnabled() == true && !LocalInspectionMode.current
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (added != null) {
            RollingSignedCount(
                prefix = "+",
                value = added,
                color = addedColor,
                rollUp = true,
                animate = animate,
            )
        }
        if (removed != null) {
            RollingSignedCount(
                prefix = "−",
                value = removed,
                color = removedColor,
                rollUp = false,
                animate = animate,
            )
        }
    }
}

@Composable
private fun RollingSignedCount(
    prefix: String,
    value: Int,
    color: Color,
    rollUp: Boolean,
    animate: Boolean,
) {
    val style = MaterialTheme.typography.labelMedium.copy(
        fontFamily = InfinityMonospace,
        fontWeight = FontWeight.Medium,
        fontFeatureSettings = "tnum",
    )
    Box(modifier = Modifier.clipToBounds()) {
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                if (!animate) {
                    (EnterTransition.None togetherWith ExitTransition.None)
                        .using(SizeTransform(clip = false))
                } else {
                    val enter = slideInVertically(
                        animationSpec = tween(220, easing = InfinityMotion.EmphasizedDecelerate),
                        initialOffsetY = { fullHeight -> if (rollUp) fullHeight else -fullHeight },
                    ) + fadeIn(tween(180))
                    val exit = slideOutVertically(
                        animationSpec = tween(180, easing = FastOutLinearInEasing),
                        targetOffsetY = { fullHeight -> if (rollUp) -fullHeight else fullHeight },
                    ) + fadeOut(tween(140))
                    (enter togetherWith exit).using(SizeTransform(clip = true))
                }
            },
            contentAlignment = Alignment.CenterEnd,
            label = if (rollUp) "rollingFileAdded" else "rollingFileRemoved",
        ) { shown ->
            Text(
                text = "$prefix$shown",
                style = style,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/**
 * 连续文件编辑活跃态的逐行清单：
 * - 正在写入的文件直接以生成动画 + 文件名 + 异向滚动行数呈现，不出现“正在修改文件…”；
 * - 已完成文件常驻上方，保留勾号、文件名与行数，点击可直接打开差异面板；
 * - 新增行平滑展开，首行零帧就位以与流末尾等待行等高（36dp）无缝接棒。
 */
@Composable
internal fun LiveFileEditBatchRows(
    rows: List<LiveFileEditRowState>,
    onOpenChange: (FileChangeSummary) -> Unit,
    modifier: Modifier = Modifier,
) {
    val animate = rememberStreamAnimationsEnabled() == true && !LocalInspectionMode.current
    Column(modifier = modifier.fillMaxWidth().testTag(LIVE_FILE_EDIT_BATCH_TAG)) {
        rows.forEachIndexed { index, row ->
            key(row.key) {
                val visibleState = remember(row.key) {
                    MutableTransitionState(!animate || index == 0)
                }
                SideEffect { visibleState.targetState = true }
                AnimatedVisibility(
                    visibleState = visibleState,
                    enter = InfinityMotion.expandIn,
                    exit = InfinityMotion.shrinkOut,
                ) {
                    LiveFileEditRow(
                        row = row,
                        onOpenChange = onOpenChange,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveFileEditRow(
    row: LiveFileEditRowState,
    onOpenChange: (FileChangeSummary) -> Unit,
) {
    val active = row.status == LiveFileEditRowStatus.ACTIVE
    val failed = row.status == LiveFileEditRowStatus.FAILED
    val openDiffLabel = stringResource(R.string.file_change_open_diff_action)
    val countParts = listOfNotNull(
        row.added?.let { pluralStringResource(R.plurals.file_diff_added_lines_a11y, it, it) },
        row.removed?.let { pluralStringResource(R.plurals.file_diff_removed_lines_a11y, it, it) },
    )
    val a11yDescription = (listOf(row.displayName) + countParts)
        .joinToString(stringResource(R.string.chat_step_summary_separator))
    val clickableChange = row.latestChange?.takeUnless { active }
    val nameStyle = InfinityTypography.labelLarge.copy(
        fontSize = 13.sp,
        lineHeight = 18.sp,
        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
    )
    val nameColor = when {
        failed -> MaterialTheme.colorScheme.error
        active -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
    }

    val indicatorStyle = com.promenar.infinity.ui.theme.LocalInfinityMotionStyles.current.indicator
    val leadingSlotWidth = if (indicatorStyle == com.promenar.infinity.ui.theme.GenerationIndicatorStyle.WAVE) {
        18.dp
    } else {
        com.promenar.infinity.ui.common.motion.indicatorDesignSize(indicatorStyle).width * (4f / 6f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CompactStepRowHeight)
            .clip(RoundedCornerShape(8.dp))
            .then(
                if (clickableChange != null) {
                    Modifier.clickable(
                        role = Role.Button,
                        onClickLabel = openDiffLabel,
                        onClick = { onOpenChange(clickableChange) },
                    )
                } else Modifier,
            )
            .semantics(mergeDescendants = true) { contentDescription = a11yDescription }
            .testTag(LIVE_FILE_EDIT_ROW_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.padding(end = 8.dp).widthIn(min = leadingSlotWidth),
            contentAlignment = Alignment.Center,
        ) {
            when (row.status) {
                LiveFileEditRowStatus.ACTIVE -> LiveStepDots(row.motionPhase)
                LiveFileEditRowStatus.COMPLETED -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = MaterialTheme.infinityDomainColors.success.copy(alpha = 0.85f),
                    modifier = Modifier.size(15.dp),
                )
                LiveFileEditRowStatus.FAILED -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
        Text(
            text = row.displayName,
            style = nameStyle,
            color = nameColor,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (row.added != null || row.removed != null) {
            RollingFileChangeCounts(
                added = row.added,
                removed = row.removed,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        if (active && row.startedAt != null) {
            LiveElapsedText(
                startedAt = row.startedAt,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics {},
            )
        }
    }
}
