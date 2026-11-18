package com.promenar.infinity.recording

import kotlinx.serialization.Serializable
import java.util.Locale

/** 录音资料可阅读和编辑，自动索引不得把原文交给向量或图谱提供商。 */
internal const val LOCAL_RECORDING_MIME = "text/x-infinity-recording-markdown"

/** 长时录音提醒周期（默认 30 分钟提醒一次，未操作默认继续）。 */
internal const val RECORDING_CONTINUE_REMINDER_INTERVAL_MS = 30 * 60 * 1_000L

/** 根据累计活跃录音时长计算已达到的最新 30 分钟提醒节点（分钟数）；未满周期返回 null。 */
internal fun recordingContinueReminderMinutes(
    elapsedMs: Long,
    intervalMs: Long = RECORDING_CONTINUE_REMINDER_INTERVAL_MS,
): Int? {
    if (elapsedMs < intervalMs || intervalMs <= 0L) return null
    val steps = elapsedMs / intervalMs
    val stepMinutes = intervalMs / 60_000L
    if (steps <= 0L || stepMinutes <= 0L) return null
    return (steps * stepMinutes).toInt()
}

@Serializable
internal enum class RecordingMode { Auto, Local, Raw }

internal enum class RecordingRoute { Cloud, Local, Raw, Consent, Unavailable }

@Serializable
internal enum class RecordingPhase { Recording, Paused, Interrupted, Finished }

@Serializable
internal data class RecordingSegment(val startMs: Long, val endMs: Long, val text: String, val speakerId: Int? = null)

@Serializable
internal data class RecordingSession(
    val id: String,
    val createdAt: Long,
    val title: String,
    val phase: RecordingPhase = RecordingPhase.Recording,
    val audioBytes: Long = 0,
    val segments: List<RecordingSegment> = emptyList(),
    val checkpointText: RecordingSegment? = null,
    val organized: String = "",
    val organizedThrough: Int = 0,
    val reprocessThrough: Int? = null,
    val reprocessText: String = "",
    val mode: RecordingMode = RecordingMode.Auto,
    val fallback: Boolean = true,
    val cloudConsent: String? = null,
    val documentId: String? = null,
    val documentHash: String? = null,
    val manualEdit: Boolean = false,
    val hotwords: List<String> = emptyList(),
    val useHotwordLibrary: Boolean = true,
    val distinguishSpeakers: Boolean = false,
    val speakers: List<RecordingSpeaker> = emptyList(),
    val speakerAnalyzedBytes: Long = 0,
    val speakerAssignmentsEdited: Boolean = false,
    val outputLanguage: RecordingLanguage = RecordingLanguage.Chinese,
    val publishedLanguage: RecordingLanguage? = null,
    val liveOrganization: Boolean = true,
    val draftBatches: List<RecordingDraftBatch> = emptyList(),
    val summaryPending: Boolean = false,
    val summaryProgress: RecordingSummaryProgress? = null,
    val captureRollbackPending: Boolean = false,
    val pendingDocumentHash: String? = null,
    val organizedSource: RecordingOrganizeSource? = null,
    val lastOrganizeFailure: RecordingOrganizeFailure? = null,
    val libraryDocumentSnapshotHash: String? = null,
) {
    val durationMs get() = audioBytes / 32
    val organizationCursor get() = reprocessThrough ?: organizedThrough
    val draftCursor get() = draftBatches.lastOrNull()?.through ?: 0
    val canContinue get() = audioBytes < RecordingStore.MAX_AUDIO_BYTES && phase != RecordingPhase.Recording && !captureRollbackPending
}

@Serializable
internal data class RecordingOrganizeEngine(
    val cloud: Boolean,
    val provider: String = "",
    val model: String,
)

@Serializable
internal data class RecordingOrganizeSource(
    val cloud: Boolean,
    val provider: String = "",
    val model: String,
    val completedAt: Long,
    val language: RecordingLanguage = RecordingLanguage.Chinese,
) {
    val engine get() = RecordingOrganizeEngine(cloud, provider, model)
}

@Serializable
internal enum class RecordingOrganizeErrorKind { Timeout, ModelError, LanguageMismatch, EmptyOutput, StorageError }

@Serializable
internal data class RecordingOrganizeFailure(
    val kind: RecordingOrganizeErrorKind,
    val engine: RecordingOrganizeEngine? = null,
    val failedAt: Long,
    val elapsedMs: Long = 0L,
    val retryAtEpochMs: Long? = null,
)

internal enum class RecordingOrganizeStage { LiveDraft, Points, Overview, Publish }

internal data class RecordingOrganizeRun(
    val sessionId: String,
    val engine: RecordingOrganizeEngine,
    val stage: RecordingOrganizeStage,
    val step: Int = 1,
    val totalSteps: Int = 1,
    val startedAtEpochMs: Long = 0L,
    val coveredAudioMs: Long = 0L,
    val lagMs: Long = 0L,
)

internal fun parseRecordingOrganizeEngine(cloud: Boolean, label: String?): RecordingOrganizeEngine? {
    if (label.isNullOrBlank()) return null
    if (cloud && " · " in label) {
        val provider = label.substringBefore(" · ").trim()
        val model = label.substringAfter(" · ").trim()
        if (provider.isNotEmpty() && model.isNotEmpty()) return RecordingOrganizeEngine(true, provider, model)
    }
    return RecordingOrganizeEngine(cloud, model = label.trim())
}

internal const val CLOUD_POINT_BATCH_CHAR_BUDGET = 2800
internal const val LOCAL_POINT_BATCH_CHAR_BUDGET = 420
internal const val CLOUD_OVERVIEW_CHAR_BUDGET = 28_000
internal const val LOCAL_OVERVIEW_CHAR_BUDGET = 640

internal fun countRecordingPointBatches(segments: List<RecordingSegment>, cloud: Boolean, separateSpeakers: Boolean): Int {
    val budget = if (cloud) CLOUD_POINT_BATCH_CHAR_BUDGET else LOCAL_POINT_BATCH_CHAR_BUDGET
    var cursor = 0
    var count = 0
    while (true) {
        val range = nextRecordingBatch(segments, cursor, budget, separateSpeakers = false) ?: break
        count += if (separateSpeakers) {
            range.map { segments[it].speakerId }.distinct().size.coerceAtLeast(1)
        } else 1
        cursor = range.last + 1
    }
    return count
}

internal fun countRecordingOverviewBatches(
    points: List<RecordingSummaryPoint>,
    overviewCursor: Int = 0,
    cloud: Boolean,
): Pair<Int, Int> {
    if (!cloud && points.size <= 1) {
        return 1 to (if (overviewCursor > 0) 1 else 0)
    }
    if (points.isEmpty()) return 1 to 0
    val budget = if (cloud) CLOUD_OVERVIEW_CHAR_BUDGET else LOCAL_OVERVIEW_CHAR_BUDGET
    var index = 0
    var batches = 0
    var completed = 0
    while (index < points.size) {
        var used = 0
        val start = index
        while (index < points.size) {
            val point = points[index].text
            if (index > start && used + point.length > budget) break
            used += point.take(budget).length + 1
            index++
        }
        batches++
        if (index <= overviewCursor) completed++
    }
    val total = batches.coerceAtLeast(1)
    return total to completed.coerceAtMost(total - 1)
}

internal sealed interface RecordingOrganizeState {
    data class LiveDraft(val engine: RecordingOrganizeEngine?, val coveredAudioMs: Long, val lagSeconds: Int, val running: Boolean) : RecordingOrganizeState
    data class LiveAfterRecording(val engine: RecordingOrganizeEngine?) : RecordingOrganizeState
    data class RunningFirstTime(val run: RecordingOrganizeRun, val elapsedSeconds: Int, val progress: Float) : RecordingOrganizeState
    data class RunningRerun(val run: RecordingOrganizeRun, val elapsedSeconds: Int, val progress: Float, val previousSource: RecordingOrganizeSource?) : RecordingOrganizeState
    data class FailedKeptOld(val failure: RecordingOrganizeFailure, val engine: RecordingOrganizeEngine?, val retryRemainingSeconds: Int?, val currentSource: RecordingOrganizeSource?) : RecordingOrganizeState
    data class FailedEmpty(val failure: RecordingOrganizeFailure, val engine: RecordingOrganizeEngine?, val retryRemainingSeconds: Int?, val canSwitchToLocal: Boolean) : RecordingOrganizeState
    data class AwaitingConsent(val engine: RecordingOrganizeEngine?, val activeRecording: Boolean, val canSwitchToLocal: Boolean) : RecordingOrganizeState
    data class WaitingLocal(val engine: RecordingOrganizeEngine?) : RecordingOrganizeState
    data class WaitingCloud(
        val engine: RecordingOrganizeEngine?,
        val waitingPreprocess: Boolean = false,
        val queuedBehindOther: Boolean = false,
    ) : RecordingOrganizeState
    data class WaitingSpeakers(val engine: RecordingOrganizeEngine?, val progressFraction: Float) : RecordingOrganizeState
    data class Unavailable(val activeRecording: Boolean) : RecordingOrganizeState
    data class StaleNewSegments(val engine: RecordingOrganizeEngine?, val addedSegments: Int, val addedDurationMs: Long, val canceled: Boolean) : RecordingOrganizeState
    data class RawOnly(val activeRecording: Boolean) : RecordingOrganizeState
    data class Completed(val source: RecordingOrganizeSource?, val justUpdated: Boolean) : RecordingOrganizeState
    data object IdleEmpty : RecordingOrganizeState
}

internal enum class RecordingOrganizeHistoryBadge { Organizing, Failed }

/** 按录音、运行、路线授权、失败及待办的顺序推导；只读取当前快照。 */
internal fun recordingOrganizeState(
    session: RecordingSession,
    state: RecordingScreenState,
    route: RecordingRoute,
    speakerModel: RecordingAsrModelState = RecordingAsrModelState.Installed,
    nowEpochMs: Long = System.currentTimeMillis(),
): RecordingOrganizeState {
    val run = state.organizeRun?.takeIf { it.sessionId == session.id }
    val currentEngine = when (route) {
        RecordingRoute.Cloud, RecordingRoute.Consent -> parseRecordingOrganizeEngine(true, state.cloudLabel)
        RecordingRoute.Local -> parseRecordingOrganizeEngine(false, state.localLabel)
        else -> null
    }
    val canSwitchToLocal = state.localReady
    val hasOld = session.organized.isNotBlank()
    fun completed() = RecordingOrganizeState.Completed(session.organizedSource, state.organizeUpdatedId == session.id)
    fun running(value: RecordingOrganizeRun): RecordingOrganizeState {
        val elapsed = if (value.startedAtEpochMs > 0L) ((nowEpochMs - value.startedAtEpochMs).coerceAtLeast(0L) / 1000L).toInt() else 0
        val progress = (value.step.toFloat() / value.totalSteps.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
        return if (hasOld) RecordingOrganizeState.RunningRerun(value, elapsed, progress, session.organizedSource)
        else RecordingOrganizeState.RunningFirstTime(value, elapsed, progress)
    }
    if (state.activeId == session.id) {
        return when {
            route == RecordingRoute.Raw -> RecordingOrganizeState.RawOnly(true)
            route == RecordingRoute.Consent -> RecordingOrganizeState.AwaitingConsent(currentEngine, true, canSwitchToLocal)
            route == RecordingRoute.Unavailable -> RecordingOrganizeState.Unavailable(true)
            !session.liveOrganization -> RecordingOrganizeState.LiveAfterRecording(currentEngine)
            else -> {
                val covered = run?.takeIf { it.stage == RecordingOrganizeStage.LiveDraft }?.coveredAudioMs
                    ?: session.draftBatches.lastOrNull()?.let { session.segments.getOrNull(it.through - 1)?.endMs } ?: 0L
                RecordingOrganizeState.LiveDraft(run?.engine ?: currentEngine, covered,
                    ((state.durationMs - covered).coerceAtLeast(0L) / 1000L).toInt(), run != null)
            }
        }
    }
    if (run != null && run.stage != RecordingOrganizeStage.LiveDraft) return running(run)
    if (state.organizingId == session.id && state.organizeRun == null) {
        return running(RecordingOrganizeRun(session.id,
            currentEngine ?: RecordingOrganizeEngine(route == RecordingRoute.Cloud, model = ""), RecordingOrganizeStage.Points))
    }
    if (route == RecordingRoute.Raw) return if (hasOld && !session.summaryPending) completed() else RecordingOrganizeState.RawOnly(false)
    if (route == RecordingRoute.Consent && (session.segments.isNotEmpty() || session.summaryPending)) {
        return if (hasOld && !session.summaryPending && session.organizationCursor >= session.segments.size) completed()
        else RecordingOrganizeState.AwaitingConsent(currentEngine, false, canSwitchToLocal)
    }
    session.lastOrganizeFailure?.let { failure ->
        val retry = failure.retryAtEpochMs?.let { ((it - nowEpochMs + 999L) / 1000L).toInt().coerceAtLeast(0) }
        return if (hasOld) RecordingOrganizeState.FailedKeptOld(failure, failure.engine ?: currentEngine, retry, session.organizedSource)
        else RecordingOrganizeState.FailedEmpty(failure, failure.engine ?: currentEngine, retry, canSwitchToLocal)
    }
    val needsAutoOrPending = session.summaryPending || (session.segments.isNotEmpty() && !session.manualEdit &&
        (session.organized.isBlank() || session.organizationCursor < session.segments.size))
    if (route == RecordingRoute.Unavailable && needsAutoOrPending) return RecordingOrganizeState.Unavailable(false)
    val manualPending = session.manualEdit && session.segments.isNotEmpty() && session.organizedThrough < session.segments.size
    if (needsAutoOrPending || manualPending) {
        val addedSegments = (session.segments.size - session.organizedThrough).coerceAtLeast(0)
        val addedDuration = session.segments.getOrNull(session.organizedThrough)?.let {
            ((session.segments.lastOrNull()?.endMs ?: it.startMs) - it.startMs).coerceAtLeast(0L)
        } ?: 0L
        if (session.id in state.organizeCanceledIds) return RecordingOrganizeState.StaleNewSegments(currentEngine, addedSegments, addedDuration, true)
        if (session.distinguishSpeakers && (state.speakerAnalysisId == session.id ||
                speakerModel == RecordingAsrModelState.Installed && session.audioBytes > 0 && session.speakerAnalyzedBytes != session.audioBytes)) {
            return RecordingOrganizeState.WaitingSpeakers(currentEngine, if (state.speakerAnalysisId == session.id) state.speakerProgress else 0f)
        }
        if (hasOld && session.segments.size > session.organizedThrough) return RecordingOrganizeState.StaleNewSegments(currentEngine, addedSegments, addedDuration, false)
        if (!hasOld && manualPending && !needsAutoOrPending) return RecordingOrganizeState.IdleEmpty
        if (route == RecordingRoute.Local) return RecordingOrganizeState.WaitingLocal(currentEngine)
        if (route == RecordingRoute.Cloud) {
            val waitingPreprocess = state.refiningId == session.id || state.recognizingId == session.id
            val queuedBehindOther = (state.organizingId != null && state.organizingId != session.id) ||
                (state.organizeRun != null && state.organizeRun.sessionId != session.id)
            return RecordingOrganizeState.WaitingCloud(currentEngine, waitingPreprocess, queuedBehindOther)
        }
        if (hasOld) return completed()
        return RecordingOrganizeState.IdleEmpty
    }
    return if (hasOld) completed() else RecordingOrganizeState.IdleEmpty
}

internal fun recordingOrganizeHistoryStatus(session: RecordingSession, state: RecordingScreenState): RecordingOrganizeHistoryBadge? = when {
    state.organizeRun?.sessionId == session.id || state.organizingId == session.id -> RecordingOrganizeHistoryBadge.Organizing
    session.lastOrganizeFailure != null && session.mode != RecordingMode.Raw -> RecordingOrganizeHistoryBadge.Failed
    else -> null
}

internal fun recoverRecordingTranscript(session: RecordingSession, audioBytes: Long): RecordingSession {
    val duration = audioBytes / 32
    val raw = session.segments.filter { it.startMs < duration }.map { it.copy(endMs = minOf(it.endMs, duration)) }
    val recovered = session.checkpointText?.takeIf {
        it.startMs >= (raw.lastOrNull()?.endMs ?: 0L) && it.startMs < duration
    }?.let { raw + it.copy(endMs = minOf(it.endMs, duration)) } ?: raw
    return session.copy(
        phase = RecordingPhase.Interrupted, audioBytes = audioBytes, segments = recovered,
        checkpointText = null, draftBatches = session.draftBatches.takeWhile { it.through <= recovered.size },
        organizedThrough = minOf(session.organizedThrough, recovered.size),
        reprocessThrough = session.reprocessThrough?.coerceAtMost(recovered.size),
    )
}

internal fun chooseRecordingRoute(
    mode: RecordingMode,
    cloudKey: String?,
    consent: String?,
    localReady: Boolean,
    fallback: Boolean = true,
): RecordingRoute = when {
    mode == RecordingMode.Raw -> RecordingRoute.Raw
    mode == RecordingMode.Auto && cloudKey != null ->
        if (cloudKey == consent) RecordingRoute.Cloud else RecordingRoute.Consent
    localReady && (mode == RecordingMode.Local || fallback) -> RecordingRoute.Local
    else -> RecordingRoute.Unavailable
}

internal fun nextRecordingBatch(
    segments: List<RecordingSegment>, from: Int, budget: Int, separateSpeakers: Boolean = false,
): IntRange? {
    if (from >= segments.size) return null
    var end = from
    var chars = 0
    while (end < segments.size) {
        val length = segments[end].text.length
        if (end > from && (chars + length > budget ||
            separateSpeakers && segments[end].speakerId != segments[from].speakerId)) break
        chars += length
        end++
    }
    return from until end
}

internal fun nextLiveRecordingDraft(
    session: RecordingSession, route: RecordingRoute, quiet: Boolean, allowLocalOverlap: Boolean,
): IntRange? {
    val from = session.draftCursor
    if (!session.liveOrganization || from >= session.segments.size) return null
    return when (route) {
        RecordingRoute.Local -> when {
            from == 0 && (quiet || allowLocalOverlap) -> 0..0
            quiet -> nextRecordingBatch(session.segments, from, 300)
            else -> null
        }
        RecordingRoute.Cloud -> nextRecordingBatch(session.segments, from, 1000)
        else -> null
    }
}

internal fun recordingContext(segments: List<RecordingSegment>, before: Int, budget: Int): String =
    segments.subList(0, before).asReversed().asSequence().map { it.text }
        .scan("" to 0) { (_, size), text -> text to size + text.length + 1 }
        .drop(1).takeWhile { it.second <= budget }
        .map { it.first }.toList().asReversed().joinToString("\n")

internal fun recordingFollowing(segments: List<RecordingSegment>, from: Int, budget: Int): String = buildString {
    for (index in from until segments.size) {
        if (length >= budget) break
        if (isNotEmpty()) append('\n')
        append(segments[index].text.take(budget - length))
    }
}

/** 从整个会话挑出与本批术语相关的较远段落，窗口外段落也可参与纠错。 */
internal fun recordingRelatedContext(
    segments: List<RecordingSegment>,
    range: IntRange,
    glossary: String,
    budget: Int,
): String {
    val englishWords = Regex("[A-Za-z][A-Za-z0-9._-]{2,}")
    val source = range.joinToString(" ") { segments[it].text }
    val words = englishWords.findAll(source).map { it.value.lowercase(Locale.ROOT) }.toSet()
    val terms = glossary.split(Regex("[、,，\\n]+")).map(String::trim).filter { it.length >= 2 }
    val relatedTerms = terms.filter { term -> source.contains(term, ignoreCase = true) }
    if (words.isEmpty() && relatedTerms.isEmpty()) return ""
    return segments.withIndex().asSequence()
        .filter { it.index < range.first - 2 || it.index > range.last + 2 }
        .map { entry ->
            val termsMatched = relatedTerms.count { entry.value.text.contains(it, ignoreCase = true) }
            val wordsMatched = englishWords.findAll(entry.value.text)
                .map { it.value.lowercase(Locale.ROOT) }.toSet().count { it in words }
            entry to (termsMatched * 3 + wordsMatched)
        }
        .filter { it.second > 0 }.sortedByDescending { it.second }
        .take(6).map { it.first }.sortedBy { it.index }
        .joinToString("\n") { it.value.text }.take(budget)
}

internal fun recordingTimestamp(ms: Long): String {
    val seconds = ms / 1000
    return if (seconds >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
    else String.format(Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60)
}

/** 播放位置所在的逐字稿段落下标：起点不晚于该位置的最后一段；位置早于首段或没有段落时为 -1。 */
internal fun recordingSegmentAt(segments: List<RecordingSegment>, positionMs: Long): Int =
    segments.indexOfLast { it.startMs <= positionMs }

/** 录音可删除：已结束或中断，且不是正在录的那一条。 */
internal fun recordingDeletable(session: RecordingSession, activeId: String?): Boolean =
    !session.captureRollbackPending && session.id != activeId && session.phase in setOf(RecordingPhase.Interrupted, RecordingPhase.Finished)

internal fun toggleRecordingSearchSelection(selected: Set<String>, matching: Set<String>): Set<String> =
    if (selected.containsAll(matching)) selected - matching else selected + matching

/** 资料文档被删除后一并删除的录音 id：资料文档的 id 就是录音 id，只取可删除的录音。 */
internal fun recordingsRemovedWithDocuments(
    sessions: List<RecordingSession>,
    documentIds: Collection<String>,
    activeId: String?,
): List<String> {
    val ids = documentIds.toSet()
    return sessions.filter { it.id in ids && recordingDeletable(it, activeId) }.map { it.id }
}

internal fun recordingRawText(session: RecordingSession): String =
    session.segments.joinToString("\n") { segment ->
        val speaker = session.exportSpeaker(segment)
        "${recordingTimestamp(segment.startMs)} ${if (speaker == null) "" else "$speaker  "}${segment.text}"
    }

internal fun RecordingSession.exportSpeaker(segment: RecordingSegment): String? =
    segment.speakerId?.takeIf { distinguishSpeakers }?.let { speakerName(it) { index -> "说话人 $index" } }

internal fun recordingMarkdown(session: RecordingSession): String = buildString {
    append("# ").append(session.title.trim().replace(Regex("[\\r\\n]+"), " "))
    if (session.organized.isNotBlank()) append("\n\n## 整理稿\n\n").append(session.organized)
    if (session.segments.isNotEmpty()) {
        append("\n\n## 原始转写\n")
        session.segments.forEach { segment ->
            append("\n- ").append(recordingTimestamp(segment.startMs)).append(' ')
            session.exportSpeaker(segment)?.let { append(it).append("  ") }
            append(segment.text)
        }
    }
}

internal data class ParsedRecordingMarkdown(val title: String, val organized: String)

internal fun parseRecordingMarkdown(markdown: String, fallbackTitle: String): ParsedRecordingMarkdown {
    val lines = markdown.replace("\r\n", "\n").split('\n')
    val first = lines.indexOfFirst(String::isNotBlank)
    if (first < 0) return ParsedRecordingMarkdown(fallbackTitle, "")
    val heading = lines[first].takeIf { it.startsWith("# ") }
    val title = heading?.removePrefix("# ")?.trim()?.ifBlank { fallbackTitle } ?: fallbackTitle
    var start = first + if (heading == null) 0 else 1
    while (start < lines.size && lines[start].isBlank()) start++
    if (lines.getOrNull(start)?.trimEnd() == "## 整理稿") start++
    val fences = recordingMarkdownFences(lines).iterator()
    var fence = if (fences.hasNext()) fences.next() else null
    var end = lines.size
    for (index in start until lines.size) {
        while (fence?.end?.let { it < index } == true) fence = if (fences.hasNext()) fences.next() else null
        if (fence?.let { index >= it.start } == true) continue
        if (lines[index].trimEnd() == "## 原始转写") {
            end = index
            break
        }
    }
    return ParsedRecordingMarkdown(title, lines.subList(start, end).joinToString("\n").trim())
}

internal data class RecordingMarkdownFence(val start: Int, val end: Int?, val marker: String, val info: String)

private val recordingFenceLine = Regex("""^ {0,3}(`{3,}|~{3,})(.*)$""")

internal fun recordingMarkdownFences(lines: List<String>): List<RecordingMarkdownFence> = buildList {
    var open: RecordingMarkdownFence? = null
    lines.forEachIndexed { index, line ->
        val match = recordingFenceLine.matchEntire(line) ?: return@forEachIndexed
        val marker = match.groupValues[1]
        val info = match.groupValues[2].trim()
        val current = open
        if (current == null) {
            if (marker.first() != '`' || '`' !in info) open = RecordingMarkdownFence(index, null, marker, info)
        } else if (marker.first() == current.marker.first() && marker.length >= current.marker.length && info.isEmpty()) {
            add(current.copy(end = index))
            open = null
        }
    }
    open?.let(::add)
}
