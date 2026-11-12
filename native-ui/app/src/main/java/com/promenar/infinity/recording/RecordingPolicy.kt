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
internal data class RecordingSegment(val startMs: Long, val endMs: Long, val text: String)

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
    val glossary: String = "Infinity Deck、Zipformer、GGUF、llama.cpp",
) {
    val durationMs get() = audioBytes / 32
    val organizationCursor get() = reprocessThrough ?: organizedThrough
}

internal fun recoverRecordingTranscript(session: RecordingSession, audioBytes: Long): RecordingSession {
    val duration = audioBytes / 32
    val raw = session.segments.filter { it.startMs < duration }.map { it.copy(endMs = minOf(it.endMs, duration)) }
    val recovered = session.checkpointText?.takeIf {
        it.startMs >= (raw.lastOrNull()?.endMs ?: 0L) && it.startMs < duration
    }?.let { raw + it.copy(endMs = minOf(it.endMs, duration)) } ?: raw
    return session.copy(
        phase = RecordingPhase.Interrupted, audioBytes = audioBytes, segments = recovered,
        checkpointText = null, organizedThrough = minOf(session.organizedThrough, recovered.size),
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

internal fun nextRecordingBatch(segments: List<RecordingSegment>, from: Int, budget: Int): IntRange? {
    if (from >= segments.size) return null
    var end = from
    var chars = 0
    while (end < segments.size) {
        val length = segments[end].text.length
        if (end > from && chars + length > budget) break
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

internal fun recordingRawText(session: RecordingSession): String =
    session.segments.joinToString("\n") { "${recordingTimestamp(it.startMs)} ${it.text}" }

internal fun recordingMarkdown(session: RecordingSession): String = buildString {
    append("# ").append(session.title.trim().replace(Regex("[\\r\\n]+"), " "))
    if (session.organized.isNotBlank()) append("\n\n## 整理稿\n\n").append(session.organized)
    if (session.segments.isNotEmpty()) {
        append("\n\n## 原始转写\n")
        session.segments.forEach { append("\n- ").append(recordingTimestamp(it.startMs)).append(' ').append(it.text) }
    }
}
