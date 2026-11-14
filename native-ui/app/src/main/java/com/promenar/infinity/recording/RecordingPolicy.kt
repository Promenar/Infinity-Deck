package com.promenar.infinity.recording

import kotlinx.serialization.Serializable
import java.util.Locale

/** 录音资料可阅读和编辑，自动索引不得把原文交给向量或图谱提供商。 */
internal const val LOCAL_RECORDING_MIME = "text/x-infinity-recording-markdown"

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
) {
    val durationMs get() = audioBytes / 32
    val organizationCursor get() = reprocessThrough ?: organizedThrough
    val draftCursor get() = draftBatches.lastOrNull()?.through ?: 0
    val canContinue get() = audioBytes < RecordingStore.MAX_AUDIO_BYTES && phase != RecordingPhase.Recording && !captureRollbackPending
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

private fun RecordingSession.exportSpeaker(segment: RecordingSegment): String? =
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
