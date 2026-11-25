package com.promenar.infinity.data.generation

import com.promenar.infinity.data.generation.ContextCompactionOutcome.Failure
import com.promenar.infinity.data.generation.ContextCompactionOutcome.Success
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.Session
import com.promenar.infinity.data.remote.protocol.PromptRequest
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.data.repository.ContextFoldCache
import com.promenar.infinity.data.workspace.isWorkspaceSwitchNotice
import java.io.IOException
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

class ContextCompactionTurnState {
    internal var historySummaryFailure: ContextCompactionFailureReason? = null
    var historySummaryAttempted: Boolean = false
        set(value) {
            field = value
            if (!value) historySummaryFailure = null
        }
}

data class ContextCompactionInput(
    val session: Session,
    val currentTurnId: String?,
    val keepRecentTurns: Int,
    val threshold: Double,
    val window: PromptWindow,
    val manual: Boolean = false,
    val turnState: ContextCompactionTurnState = ContextCompactionTurnState(),
    val stableModelId: String? = null,
    val inputBudgetOverride: Int? = null,
)

data class ContextCompactionResult(
    val prompt: PromptRequest,
    val foldedToolResults: Int,
    val summarizedTurns: Int,
    val beforeTokens: Int,
    val afterTokens: Int,
    val summaryFailed: Boolean = false,
    val newlyFoldedToolResults: Int = 0,
    val blockedFloor: ContextInputFloor? = null,
    val failureReason: ContextCompactionFailureReason? = null,
    val noticeMessageId: String? = null,
)

enum class ContextCompactionStage { FOLDING, SUMMARIZING, COMPLETE }

/** 请求构建及摘要持久化由调用方负责；工具折叠只改写请求副本。 */
class ContextCompactionPipeline(private val folds: ContextFoldCache? = null) {
    private val memoryFolds = java.util.concurrent.ConcurrentHashMap<Pair<String, FoldKey>, String>()
    fun inputFloor(session: Session, prompt: PromptRequest, window: PromptWindow, stableModelId: String? = null): ContextInputFloor =
        ContextInputFloor.evaluate(session, prompt, window, stableModelId, foldingCandidates(prompt.messages).toSet())
    /** 只读取已有折叠，供用量面板估算；不生成摘要、不改缓存或原始消息。 */
    suspend fun preview(session: Session, prompt: PromptRequest, stableModelId: String? = null): ContextCompactionResult {
        val prepared = applyCached(session, prompt, mutableMapOf(), readOnly = true)
        val tokens = PromptUsageCalibration.estimate(session, prepared.prompt, stableModelId)
        return ContextCompactionResult(prepared.prompt, prepared.foldedIndexes.size, 0, tokens, tokens)
    }

    suspend fun compact(
        input: ContextCompactionInput,
        buildPrompt: suspend (Session) -> PromptRequest,
        summarizeTool: suspend (String) -> ContextCompactionOutcome<String> = { Failure(ContextCompactionFailureReason.EMPTY_OUTPUT) },
        summarizeHistory: suspend (Session, List<Message>) -> ContextCompactionOutcome<Session> = { _, _ ->
            Failure(ContextCompactionFailureReason.EMPTY_OUTPUT)
        },
        onProgress: (ContextCompactionStage) -> Unit = {},
    ): ContextCompactionResult {
        var session = input.session
        val localFolds = mutableMapOf<FoldKey, String>()
        val original = buildPrompt(session)
        val floor = inputFloor(session, original, input.window, input.stableModelId)
        if (floor.exceeded) {
            val tokens = PromptUsageCalibration.estimate(session, original, input.stableModelId)
            onProgress(ContextCompactionStage.COMPLETE)
            return ContextCompactionResult(
                original, 0, 0, tokens, tokens, blockedFloor = floor,
                failureReason = ContextCompactionFailureReason.CONTEXT_LIMIT,
            )
        }
        var prepared = applyCached(session, original, localFolds)
        val beforeTokens = PromptUsageCalibration.estimate(session, prepared.prompt, input.stableModelId)
        var tokens = beforeTokens
        val budget = budget(input)
        val newlyFolded = mutableSetOf<FoldKey>()
        val attemptedFolds = mutableSetOf<FoldKey>()
        var summarizedTurns = 0
        var archivedThisPass: Set<String> = emptySet()
        var historyFailure: ContextCompactionFailureReason? = null
        var toolFailure: ContextCompactionFailureReason? = null
        var foldingAnnounced = false

        suspend fun foldTools(currentTurnOnly: Boolean) {
            val currentIds = if (currentTurnOnly) currentTurnMessageIds(session.messages, input.currentTurnId) else null
            val candidates = foldingCandidates(prepared.prompt.messages)
            for (index in candidates) {
                currentCoroutineContext().ensureActive()
                if (!input.manual && tokens <= budget) break
                if (index in prepared.foldedIndexes) continue
                val source = prepared.sources[index]
                if (currentIds != null && source?.id !in currentIds) continue
                if (input.manual && source?.id in archivedThisPass) continue
                val message = prepared.prompt.messages[index]
                val key = foldKey(source, message, index)
                if (!attemptedFolds.add(key)) continue
                if (!foldingAnnounced) {
                    onProgress(ContextCompactionStage.FOLDING)
                    foldingAnnounced = true
                }
                val replacement = when (val outcome = foldContent(message.content, source?.content, summarizeTool)) {
                    is Success -> outcome.value
                    is Failure -> {
                        if (toolFailure == null) toolFailure = outcome.reason
                        preserveOutputReferences(headTail(message.content), source?.content.orEmpty())
                            .takeIf { reduces(message, it) } ?: continue
                    }
                }
                if (replacement == message.content) continue
                localFolds[key] = replacement
                val updated = message.copy(content = replacement)
                prepared = prepared.copy(
                    prompt = prepared.prompt.copy(messages = prepared.prompt.messages.toMutableList().also { it[index] = updated }),
                    foldedIndexes = prepared.foldedIndexes + index,
                )
                session = session.copy(stats = session.stats?.copy(contextUsageBaseline = null))
                tokens = PromptUsageCalibration.estimate(session, prepared.prompt, input.stableModelId)
                newlyFolded += key
                if (source != null) cacheWrite(session.id, source, replacement)
            }
        }

        suspend fun archiveHistory() {
            val candidates = historyCompactionCandidates(session.messages, input.currentTurnId, input.keepRecentTurns)
            if (candidates.isEmpty()) return
            if (input.turnState.historySummaryAttempted) {
                historyFailure = input.turnState.historySummaryFailure ?: ContextCompactionFailureReason.UNKNOWN
                return
            }
            input.turnState.historySummaryAttempted = true
            onProgress(ContextCompactionStage.SUMMARIZING)
            val outcome = try {
                summarizeHistory(session, candidates)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Failure(error.toCompactionFailure())
            }
            when (outcome) {
                is Failure -> historyFailure = outcome.reason
                is Success -> if (outcome.value == session) {
                    historyFailure = ContextCompactionFailureReason.UNKNOWN
                } else {
                    summarizedTurns = candidates.count { it.role == MessageRole.USER }
                    if (input.manual) archivedThisPass = candidates.mapTo(mutableSetOf(), Message::id)
                    session = outcome.value
                    prepared = applyCached(session, buildPrompt(session), localFolds)
                    session = session.copy(stats = session.stats?.copy(contextUsageBaseline = null))
                    tokens = PromptUsageCalibration.estimate(session, prepared.prompt, input.stableModelId)
                }
            }
            input.turnState.historySummaryFailure = historyFailure
        }

        if (input.manual) {
            archiveHistory()
            if (historyFailure == null) foldTools(currentTurnOnly = false)
        } else if (tokens > budget) {
            foldTools(currentTurnOnly = false)
            if (tokens > budget && !input.turnState.historySummaryAttempted) archiveHistory()
            if (tokens > budget) foldTools(currentTurnOnly = true)
        }
        onProgress(ContextCompactionStage.COMPLETE)
        return ContextCompactionResult(
            prompt = prepared.prompt,
            foldedToolResults = prepared.foldedIndexes.size,
            summarizedTurns = summarizedTurns,
            beforeTokens = beforeTokens,
            afterTokens = tokens,
            summaryFailed = historyFailure != null,
            failureReason = historyFailure ?: toolFailure,
            newlyFoldedToolResults = newlyFolded.size,
        )
    }

    private suspend fun applyCached(
        session: Session,
        prompt: PromptRequest,
        localFolds: MutableMap<FoldKey, String>,
        readOnly: Boolean = false,
    ): Prepared {
        val byCall = session.messages.filter { it.role == MessageRole.TOOL && it.toolCallId != null }
            .associateBy { it.toolCallId }
        val sources = mutableMapOf<Int, Message>()
        val foldedIndexes = mutableSetOf<Int>()
        val messages = prompt.messages.mapIndexed { index, message ->
            if (message.role != "tool") return@mapIndexed message
            val source = byCall[message.toolCallId]
            if (source != null) sources[index] = source
            val key = foldKey(source, message, index)
            val cached = localFolds[key] ?: source?.let { cacheRead(session.id, it, readOnly) } ?: return@mapIndexed message
            val replacement = preserveOutputReferences(cached, message.content, source?.content.orEmpty())
            // 只有带摘要标记的缓存可以比原文长。头尾兜底及旧缓存仍需缩短正文。
            val hasSummary = cached.startsWith(TOOL_SUMMARY_PREFIX) && cached.substringAfter("]\n", "").isNotBlank()
            if (replacement == message.content || (!hasSummary && !reduces(message, replacement))) return@mapIndexed message
            localFolds[key] = replacement
            foldedIndexes += index
            message.copy(content = replacement)
        }
        return Prepared(prompt.copy(messages = messages), sources, foldedIndexes)
    }

    private suspend fun foldContent(
        content: String,
        originalContent: String?,
        summarizeTool: suspend (String) -> ContextCompactionOutcome<String>,
    ): ContextCompactionOutcome<String> {
        val outcome = try {
            withTimeoutOrNull(15_000) { summarizeTool(headTail(content, 1_800)) }
                ?: Failure(ContextCompactionFailureReason.TIMEOUT)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Failure(error.toCompactionFailure())
        }
        return when (outcome) {
            is Failure -> outcome
            is Success -> {
                val summary = outcome.value.trim()
                if (summary.isEmpty()) return Failure(ContextCompactionFailureReason.EMPTY_OUTPUT)
                Success(preserveOutputReferences(
                    "$TOOL_SUMMARY_PREFIX${originalContent?.length ?: content.length}]\n$summary",
                    content, originalContent.orEmpty(),
                ))
            }
        }
    }

    private suspend fun cacheRead(sessionId: String, message: Message, readOnly: Boolean): String? = try {
        if (folds == null) memoryFolds[sessionId to FoldKey(message.id, message.content)]
        else if (readOnly) folds.peek(sessionId, message.id, message.content) else folds.get(sessionId, message.id, message.content)
    } catch (_: IOException) {
        null
    } catch (_: SecurityException) {
        null
    }

    private suspend fun cacheWrite(sessionId: String, message: Message, content: String) {
        if (folds == null) {
            memoryFolds[sessionId to FoldKey(message.id, message.content)] = content
            if (memoryFolds.size > 512) memoryFolds.keys.firstOrNull()?.let(memoryFolds::remove)
            return
        }
        try {
            folds.put(sessionId, message.id, message.content, content)
        } catch (_: IOException) {
            // 缓存不能持久化时，当前请求中的折叠结果仍然有效。
        } catch (_: SecurityException) {
            // 缓存权限变化不影响生成。
        }
    }

    private fun foldingCandidates(messages: List<ProtocolMessage>): List<Int> {
        val rounds = messages.indices.filter { messages[it].role == "assistant" && !messages[it].toolCalls.isNullOrEmpty() }
        val protected = rounds.takeLast(KEEP_RECENT_TOOL_ROUNDS).toSet()
        val owners = mutableMapOf<String, Int>()
        return buildList {
            messages.forEachIndexed { index, message ->
                if (message.role == "assistant") message.toolCalls.orEmpty().forEach { owners[it.id] = index }
                if (message.role == "tool" && message.name != "view_media" && message.content.length > 512) {
                    val owner = owners[message.toolCallId]
                    if (owner != null && owner !in protected) add(index)
                }
            }
        }
    }

    private fun foldKey(source: Message?, message: ProtocolMessage, index: Int) = FoldKey(
        source?.id ?: "protocol:$index:${message.toolCallId.orEmpty()}", source?.content ?: message.content,
    )

    private fun reduces(message: ProtocolMessage, content: String): Boolean =
        content.isNotBlank() && ApproximateTokenEstimator.estimate(message.copy(content = content)) <
            ApproximateTokenEstimator.estimate(message)

    private fun budget(input: ContextCompactionInput): Int {
        val context = input.window.modelContextTokens?.takeIf { it > 0 } ?: 128_000
        val threshold = input.threshold.takeIf(Double::isFinite)?.coerceIn(0.0, 1.0) ?: 0.8
        val thresholdBudget = floor(threshold * context).toLong()
        // 与发送门禁共用同一可用输入上限（含安全余量），保证门禁拒绝前自动压缩已先触发。
        val available = input.window.copy(modelContextTokens = context).inputBudgetTokens ?: 0
        return minOf(thresholdBudget, available, input.inputBudgetOverride?.coerceAtLeast(0)?.toLong() ?: Long.MAX_VALUE).toInt()
    }

    private data class FoldKey(val messageId: String, val originalContent: String)
    private data class Prepared(
        val prompt: PromptRequest,
        val sources: Map<Int, Message>,
        val foldedIndexes: Set<Int>,
    )

    companion object {
        private const val KEEP_RECENT_TOOL_ROUNDS = 3
        private const val TOOL_SUMMARY_PREFIX = "[Tool result summary; original_chars="
        private val outputReference = Regex("output_id=out_[a-f0-9]{64}")

        fun headTail(content: String, retainedChars: Int = 384): String {
            require(retainedChars >= 0) { "保留字符数不能为负。" }
            if (content.length <= retainedChars) return content
            val half = retainedChars / 2
            val headEnd = if (half > 0 && content[half - 1].isHighSurrogate()) half - 1 else half
            val tailStart = (content.length - half).let {
                if (it < content.length && content[it].isLowSurrogate()) it + 1 else it
            }
            return preserveOutputReferences(
                content.substring(0, headEnd) + "\n[Truncated tool result; original_chars=${content.length}]\n" +
                    content.substring(tailStart), content,
            )
        }

        private fun preserveOutputReferences(content: String, vararg originals: String): String = buildString {
            append(content)
            originals.asSequence().flatMap { outputReference.findAll(it).map(MatchResult::value) }.distinct()
                .filterNot(content::contains).forEach { reference -> append('\n').append(reference) }
        }
    }
}

/** 按用户消息划分完整轮次；前置非用户消息随最早用户轮一起处理。 */
fun historyCompactionCandidates(messages: List<Message>, currentTurnId: String?, keepRecentTurns: Int): List<Message> {
    val turns = messageTurns(messages)
    val current = currentTurnId?.let { id -> turns.indexOfFirst { turn -> turn.any { it.id == id } } }
        ?.takeIf { it >= 0 } ?: turns.size
    val protectedFrom = (turns.size - keepRecentTurns.coerceIn(1, 20)).coerceAtLeast(0)
    return turns.take(minOf(current, protectedFrom)).flatten().filterNot {
        it.isArchived || it.isWorkspaceSwitchNotice || it.isContextCompactionNotice
    }
}

private fun currentTurnMessageIds(messages: List<Message>, currentTurnId: String?): Set<String> {
    if (currentTurnId == null) return emptySet()
    return messageTurns(messages).firstOrNull { turn -> turn.any { it.id == currentTurnId } }
        ?.mapTo(mutableSetOf(), Message::id).orEmpty()
}

private fun messageTurns(messages: List<Message>): List<List<Message>> {
    if (messages.none { it.role == MessageRole.USER }) return emptyList()
    val turns = mutableListOf<MutableList<Message>>()
    val leading = mutableListOf<Message>()
    messages.forEach { message ->
        if (message.role == MessageRole.USER) {
            turns += mutableListOf<Message>().apply {
                if (turns.isEmpty()) addAll(leading)
                add(message)
            }
        } else if (turns.isEmpty()) {
            leading += message
        } else {
            turns.last() += message
        }
    }
    return turns
}
