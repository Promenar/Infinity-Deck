package com.promenar.infinity.data.rag

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.remote.protocol.ProtocolMessage
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 只构建候选图；文件图谱只能由文档索引事务提交。 */
class GraphExtractor(
    private val requestClient: KgRequestClient,
    private val graphStore: GraphStore,
    private val modelId: String? = null,
    private val systemPrompt: String = DEFAULT_KG_PROMPT,
    private val chunkSize: Int = 16000,
    @Suppress("UNUSED_PARAMETER") chunkOverlap: Int = 0,
    private val timeoutMs: Long = 120_000L,
    private val checkpointRoot: File? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val budget: KgExtractionBudget = KgExtractionBudget(),
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val budgetRoot: File? = null,
    private val concurrency: Int = 4,
    private val gleaningPasses: Int = 1,
    private val modelLimits: () -> KgModelLimits? = { null },
) {
    init {
        require(chunkSize >= 2 && concurrency in 1..8 && gleaningPasses in 0..2) { "知识图谱窗口配置无效" }
        require(timeoutMs > 0) { "知识图谱超时必须大于 0" }
    }

    private val pendingCommits = ConcurrentHashMap<Pair<String, String>, Pair<GraphCheckpointIdentity, String>>()

    private val checkpoints: GraphExtractionCheckpoints
        get() = GraphExtractionCheckpoints(
            checkpointRoot ?: File(InfinityApplication.instance.cacheDir, "kg_extraction_checkpoint"),
            budgetRoot ?: checkpointRoot ?: File(InfinityApplication.instance.filesDir, "kg_extraction_budgets"),
        )

    suspend fun extractCandidate(
        text: String, docId: String? = null, contentHash: String = kgSha256(text),
        onProgress: suspend (KgExtractionProgress) -> Unit = {},
        retainCompletedCheckpoints: Boolean = false,
        sessionId: String? = null,
        runId: String = UUID.randomUUID().toString(),
    ): ExtractionResult = withContext(dispatcher) {
        val limits = modelLimits()
        val requestLimit = KgWindowing.effectiveOutputLimit(budget.requestMaxOutputTokens, limits)
        val windowChars = KgWindowing.effectiveWindowChars(chunkSize, limits, requestLimit, systemPrompt)
        val identity = checkpointIdentity(text, contentHash, windowChars)
        val ledgerRunId = ledgerRunId(runId, identity, limits)
        val store = docId?.let { checkpoints }
        if (docId != null) {
            store?.prepare(docId, identity)
            store?.prepareBudget(docId, ledgerRunId)
        }
        val windows = KgWindowing.split(text, windowChars)
        val mutex = Mutex()
        val semaphore = Semaphore(concurrency)
        var state = if (docId != null) store?.loadBudget(docId, identity, ledgerRunId) ?: GraphExtractionBudgetState()
            else GraphExtractionBudgetState()
        val previousElapsed = state.elapsedMs
        val startedAt = nowMs()
        val inFlight = mutableMapOf<Int, Long>()
        val completed = arrayOfNulls<ExtractionResult>(windows.size)
        var processed = 0
        var stopped: ExtractionResult? = null
        var budgetCleared = false
        fun elapsed() = previousElapsed + (nowMs() - startedAt).coerceAtLeast(0)
        fun progress(reason: KgExtractionStopReason? = null) =
            KgExtractionProgress(processed, windows.size, reason, state.calls, elapsed())
        fun persistBudget() {
            val remainingInFlight = inFlight.values.maxOfOrNull { (it - nowMs()).coerceAtLeast(0) } ?: 0
            if (docId != null) store?.saveBudget(docId, identity, state.copy(elapsedMs = elapsed() + remainingInFlight), ledgerRunId)
        }
        fun stopReason(): KgExtractionStopReason? = when {
            state.calls >= budget.maxCalls -> KgExtractionStopReason.CALLS
            state.outputTokens >= budget.maxOutputTokens -> KgExtractionStopReason.OUTPUT_TOKENS
            elapsed() >= budget.maxDurationMs -> KgExtractionStopReason.DURATION
            else -> null
        }
        suspend fun request(part: String, history: List<ProtocolMessage>): ExtractionResult {
            val retriedStatuses = mutableSetOf<String>()
            while (true) {
                val reservation = mutex.withLock {
                    val reason = stopReason()
                    if (reason != null) return@withLock null to reason
                    val limit = minOf(requestLimit.toLong(), budget.maxOutputTokens - state.outputTokens).toInt()
                    val duration = minOf(timeoutMs, (budget.maxDurationMs - elapsed()).coerceAtLeast(1))
                    state = state.copy(calls = state.calls + 1, outputTokens = state.outputTokens + limit)
                    val id = state.calls
                    inFlight[id] = nowMs() + duration
                    persistBudget()
                    Triple(id, limit, duration) to null
                }
                val reserved = reservation.first ?: return ExtractionResult(emptyList(), emptyList(),
                    status = KgExtractionStatus.PARTIAL, progress = mutex.withLock { progress(reservation.second) })
                val response = requestClient.extract(modelId, systemPrompt, part, reserved.third, reserved.second,
                    sessionId = sessionId, historyMessages = history)
                mutex.withLock {
                    inFlight.remove(reserved.first)
                    val hasResponse = response.isSuccess || response.status == KgExtractionStatus.PARSE_FAILURE
                    val output = if (hasResponse) {
                        if (response.usage.estimated) maxOf(reserved.second.toLong(), response.usage.outputTokens) else response.usage.outputTokens
                    } else reserved.second.toLong()
                    state = state.copy(outputTokens = state.outputTokens - reserved.second + output,
                        usage = state.usage + if (hasResponse) response.usage else KgTokenUsage(estimated = true))
                    persistBudget()
                }
                val retryStatus = listOf("[HTTP 429]", "[HTTP 503]")
                    .firstOrNull { response.error?.startsWith(it) == true }
                if (response.status == KgExtractionStatus.PROVIDER_ERROR && retryStatus != null && retriedStatuses.add(retryStatus)) {
                    delay(2_000)
                    continue
                }
                if (response.status == KgExtractionStatus.TIMEOUT && mutex.withLock { elapsed() >= budget.maxDurationMs }) {
                    return ExtractionResult(emptyList(), emptyList(), status = KgExtractionStatus.PARTIAL,
                        progress = mutex.withLock { progress(KgExtractionStopReason.DURATION) })
                }
                return response
            }
        }
        suspend fun processWindow(index: Int, window: String): ExtractionResult {
            var checkpoint = if (docId != null) mutex.withLock { store?.loadCheckpoint(docId, identity, index, window) }
                ?: GraphChunkCheckpoint(identity, kgSha256(window)) else GraphChunkCheckpoint(identity, kgSha256(window))
            suspend fun save() {
                if (docId == null) return
                mutex.withLock {
                    try { store?.saveCheckpoint(docId, identity, index, checkpoint) }
                    catch (_: Exception) { InfinityLogger.log("[KG] 断点写入失败") }
                }
            }
            suspend fun extractPart(part: String, path: String, depth: Int): ExtractionResult {
                checkpoint.parts[path]?.let { return it }
                if (path in checkpoint.splitPaths) {
                    val (left, right) = splitForRetry(part)
                    val first = extractPart(left, path + "0", depth + 1)
                    if (!first.isSuccess || first.progress?.isPartial == true) return first
                    val second = extractPart(right, path + "1", depth + 1)
                    if (!second.isSuccess || second.progress?.isPartial == true) return second
                    return ExtractionResult(first.nodes + second.nodes, first.edges + second.edges)
                }
                val response = request(part, emptyList())
                val split = response.status == KgExtractionStatus.PARSE_FAILURE && depth < 2 && part.length > 1 &&
                    !(part.length == 2 && part[0].isHighSurrogate() && part[1].isLowSurrogate())
                if (split) {
                    checkpoint = checkpoint.copy(splitPaths = checkpoint.splitPaths + path)
                    save()
                    return extractPart(part, path, depth)
                }
                if (response.isSuccess && response.progress?.isPartial != true) {
                    checkpoint = checkpoint.copy(parts = checkpoint.parts + (path to response))
                    save()
                }
                return response
            }
            val base = extractPart(window, "", 0)
            if (!base.isSuccess || base.progress?.isPartial == true) return base
            val results = mutableListOf(base)
            val history = mutableListOf<ProtocolMessage>()
            for (pass in 0 until gleaningPasses) {
                history += ProtocolMessage("assistant", compactGraph(results.last()))
                history += ProtocolMessage("user", GLEANING_PROMPT)
                val extra = checkpoint.gleaning[pass] ?: request(window, history).also {
                    if (it.isSuccess && it.progress?.isPartial != true) {
                        checkpoint = checkpoint.copy(gleaning = checkpoint.gleaning + (pass to it))
                        save()
                    }
                }
                if (!extra.isSuccess || extra.progress?.isPartial == true) break
                results += extra
            }
            return ExtractionResult(results.flatMap { it.nodes }, results.flatMap { it.edges })
        }
        try {
            mutex.withLock { onProgress(progress()) }
            coroutineScope {
                windows.mapIndexed { index, window -> async {
                    semaphore.withPermit {
                        val allowed = mutex.withLock { stopped == null }
                        if (!allowed) return@withPermit
                        val result = processWindow(index, window)
                        mutex.withLock {
                            if (result.progress?.isPartial == true || !result.isSuccess) {
                                if (stopped == null) stopped = result
                            } else {
                                completed[index] = result
                                processed++
                                onProgress(progress())
                            }
                        }
                    }
                } }.awaitAll()
            }
            val collected = completed.filterNotNull()
            val merged = KgAliasMerge.merge(collected.flatMap { it.nodes }, collected.flatMap { it.edges })
            val validated = ValidatedGraph.from(merged.nodes, merged.edges).toExtractionResult()
            val failure = stopped
            val reason = failure?.progress?.reason
            val finalProgress = progress(reason)
            if (docId != null) {
                if (failure == null && !retainCompletedCheckpoints) {
                    store?.clear(docId, identity, ledgerRunId)
                    budgetCleared = true
                } else {
                    persistBudget()
                    if (failure == null && retainCompletedCheckpoints) {
                        pendingCommits.keys.removeAll { it.first == docId }
                        pendingCommits[docId to runId] = identity to ledgerRunId
                    }
                }
            }
            onProgress(finalProgress)
            if (failure != null && reason == null) failure.copy(progress = finalProgress, usage = state.usage)
            else validated.copy(status = if (reason == null) KgExtractionStatus.SUCCESS else KgExtractionStatus.PARTIAL,
                progress = finalProgress, usage = state.usage)
        } finally {
            if (docId != null && !budgetCleared) withContext(NonCancellable + dispatcher) {
                mutex.withLock { try { persistBudget() } catch (_: Exception) { InfinityLogger.log("[KG] 预算账本写入失败") } }
            }
        }
    }

    fun clearCheckpoints(docId: String) {
        pendingCommits.keys.removeAll { it.first == docId }
        checkpoints.clear(docId)
    }

    fun clearCommittedCheckpoints(text: String, docId: String, contentHash: String, runId: String? = null) {
        val completed = runId?.let { pendingCommits.remove(docId to it) }
        if (completed != null) {
            checkpoints.clear(docId, completed.first, completed.second)
            return
        }
        val limits = modelLimits()
        val limit = KgWindowing.effectiveOutputLimit(budget.requestMaxOutputTokens, limits)
        val window = KgWindowing.effectiveWindowChars(chunkSize, limits, limit, systemPrompt)
        val identity = checkpointIdentity(text, contentHash, window)
        checkpoints.clear(docId, identity, runId?.let { ledgerRunId(it, identity, limits) })
    }

    private fun checkpointIdentity(text: String, contentHash: String, windowChars: Int) =
        GraphCheckpointIdentity(contentHash, kgSha256(text), modelId, kgSha256(systemPrompt), windowChars, gleaningPasses)

    private fun ledgerRunId(runId: String, identity: GraphCheckpointIdentity, limits: KgModelLimits?): String =
        kgSha256("$runId|$identity|$limits|$budget|$concurrency|$timeoutMs")

    fun estimate(text: String): KgExtractionEstimate = budget.estimate(text, chunkSize, gleaningPasses, modelLimits(), systemPrompt)

    suspend fun clearPersistedGraphForDoc(docId: String) {
        graphStore.clearGraphForDoc(docId)
        clearCheckpoints(docId)
    }

    private fun compactGraph(result: ExtractionResult): String = buildJsonObject {
        put("nodes", buildJsonArray { result.nodes.forEach { node -> add(buildJsonObject {
            put("name", node.name); put("type", node.type)
            node.metadata?.let { value -> runCatching { Json.parseToJsonElement(value) }.getOrNull()?.let { put("metadata", it) } }
        }) } })
        put("edges", buildJsonArray { result.edges.forEach { edge -> add(buildJsonObject {
            put("source", edge.source); put("target", edge.target); put("relation", edge.relation)
            put("weight", edge.weight.takeIf { it.isFinite() } ?: 1.0)
        }) } })
    }.toString()

    /** 在原分块内优先按中点附近的段落、句末边界切分，并保留全部字符。 */
    private fun splitForRetry(text: String): Pair<String, String> {
        val midpoint = text.length / 2
        val candidates = (text.length / 4).coerceAtLeast(1)..((text.length * 3L / 4).toInt().coerceAtMost(text.length - 1))
        fun closest(predicate: (Int) -> Boolean): Int? = candidates.filter(predicate)
            .minByOrNull { kotlin.math.abs(it - midpoint) }
        val paragraph = Regex("\\n[\\t \\r]*\\n").findAll(text).map { it.range.last + 1 }
            .filter { it in candidates }.minByOrNull { kotlin.math.abs(it - midpoint) }
            ?: closest { index -> text[index - 1] == '\n' }
        val sentence = closest { index -> text[index - 1] in "。！？.!?；;" }
        var split = paragraph ?: sentence ?: midpoint.coerceIn(1, text.length - 1)
        if (text[split - 1].isHighSurrogate() && text[split].isLowSurrogate()) {
            split = if (split + 1 < text.length) split + 1 else split - 1
        }
        if (split <= 0 || split >= text.length) split = midpoint.coerceIn(1, text.length - 1)
        return text.substring(0, split) to text.substring(split)
    }

    companion object {
        const val GLEANING_PROMPT = "Find only entities and relationships omitted from the preceding extraction. Use the same JSON format, do not repeat existing nodes, and edges may refer to node names from earlier responses. Return JSON only."
        const val DEFAULT_KG_PROMPT = """You are an expert Knowledge Graph extractor.
Extract every named person, organization, location, event and named item that plays a role in the text, together with its core concepts and meaningful relationships. The number depends on the content. Skip generic terms and fleeting mentions unrelated to other entities. Keep names in the original language.

## Entity Types
- person: Named characters or real people
- organization: Groups, factions, companies, teams
- location: Named places, cities, regions, buildings
- event: Specific plot events, historical incidents, milestones
- item: Named objects, artifacts, technologies, tools
- concept: Core themes, systems, rules, or doctrines

## Output Format
Return a valid JSON object:
{
  "nodes": [
    { "name": "Exact Name", "type": "EntityType", "metadata": { "description": "one short sentence", "aliases": ["other name"] } }
  ],
  "edges": [
    { "source": "SourceNodeName", "target": "TargetNodeName", "relation": "relationship_verb", "weight": 0.8 }
  ]
}

## Rules
1. Give each entity one canonical name; when the text uses multiple names, use the most complete name and list the other forms in metadata.aliases.
2. Each node name must be unique. Edge source and target MUST match node names exactly.
3. Relation should be a concise verb phrase. Weight from 0.0 to 1.0 expresses relationship strength.
4. Return JSON only, without markdown or explanation."""
    }
}
