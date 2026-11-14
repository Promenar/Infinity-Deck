package com.promenar.infinity.recording

import kotlinx.serialization.Serializable

@Serializable
internal enum class RecordingLanguage(val label: String, val instruction: String) {
    Chinese("简体中文", "简体中文"), English("English", "English"), Japanese("日本語", "日本語");

    companion object {
        fun fromLocale(tag: String): RecordingLanguage = when (tag.substringBefore('-').substringBefore('_')) {
            "ja" -> Japanese
            "zh" -> Chinese
            else -> English
        }
    }
}

@Serializable
internal data class RecordingDraftBatch(val from: Int, val through: Int, val text: String)

@Serializable
internal data class RecordingSummaryPoint(val speakerId: Int?, val text: String, val sources: List<Long>)

@Serializable
internal data class RecordingSummaryProgress(
    val cursor: Int = 0,
    val points: List<RecordingSummaryPoint> = emptyList(),
    val actions: List<String> = emptyList(),
    val fingerprint: String,
    val overviewCursor: Int = 0,
    val overview: String = "",
)

internal fun RecordingSession.summaryFingerprint(glossary: String, route: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    fun put(value: Any?) {
        val bytes = value.toString().toByteArray()
        md.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.size).array())
        md.update(bytes)
    }
    put(route); put(glossary); put(outputLanguage); put(distinguishSpeakers)
    for (segment in segments) { put(segment.startMs); put(segment.endMs); put(segment.speakerId); put(segment.text) }
    for (speaker in speakers) { put(speaker.id); put(speaker.name) }
    return md.digest().joinToString("") { "%02x".format(it) }
}

internal data class RecordingSummaryLabels(
    val summary: String, val speakers: String, val discussion: String, val actions: String, val unnamed: String,
)

internal fun RecordingLanguage.labels() = when (this) {
    RecordingLanguage.Chinese -> RecordingSummaryLabels("摘要", "发言人观点", "讨论要点", "待办", "说话人")
    RecordingLanguage.English -> RecordingSummaryLabels("Summary", "Speakers", "Discussion points", "Next steps", "Speaker")
    RecordingLanguage.Japanese -> RecordingSummaryLabels("概要", "発言者ごとの要点", "議論の要点", "次の対応", "発言者")
}

internal fun renderRecordingSummary(
    item: RecordingSession,
    overview: String,
    points: List<RecordingSummaryPoint>,
    actions: List<String> = emptyList(),
): String = buildString {
    val labels = item.outputLanguage.labels()
    append("### ").append(labels.summary).append("\n\n").append(overview.trim())
    val groups = points.filter { it.text.isNotBlank() }.groupBy { it.speakerId.takeIf { item.distinguishSpeakers } }
    val sources = item.segments.associateBy { it.startMs }
    for ((speaker, entries) in groups.filterKeys { it != null }.entries.withIndex()) {
        if (speaker == 0) append("\n\n### ").append(labels.speakers)
        val id = requireNotNull(entries.key)
        val name = item.speakerName(id) { "${labels.unnamed} $it" }
        append("\n\n**").append(name.replace("*", "＊").replace('\n', ' ')).append("**")
        for (entry in entries.value) appendRecordingPoint(entry, sources, id)
    }
    groups[null]?.let { entries ->
        append("\n\n### ").append(labels.discussion)
        for (entry in entries) appendRecordingPoint(entry, sources, null, checkSpeaker = item.distinguishSpeakers)
    }
    if (actions.isNotEmpty()) {
        append("\n\n### ").append(labels.actions)
        actions.forEach { append("\n\n- ").append(it.trim()) }
    }
}

private fun StringBuilder.appendRecordingPoint(
    point: RecordingSummaryPoint,
    sources: Map<Long, RecordingSegment>,
    speaker: Int?,
    checkSpeaker: Boolean = true,
) {
    append("\n\n").append(point.text.trim())
    val links = point.sources.distinct().filter { time ->
        val segment = sources[time]
        segment != null && (!checkSpeaker || segment.speakerId == speaker)
    }
    if (links.isNotEmpty()) append("\n\n").append(links.joinToString(" ") {
        "[${recordingTimestamp(it)}](recording://seek/$it)"
    })
}

internal fun recordingLanguageMatches(language: RecordingLanguage, text: String): Boolean {
    val letters = text.filter(Char::isLetter)
    if (letters.isEmpty()) return false
    val han = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN }
    val kana = letters.count {
        Character.UnicodeScript.of(it.code) in setOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)
    }
    val cjk = han + kana
    val latin = letters.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.LATIN }
    return when (language) {
        RecordingLanguage.Chinese -> han >= 2 && han * 2 >= letters.length && kana == 0
        RecordingLanguage.English -> latin >= letters.length * .8
        RecordingLanguage.Japanese -> cjk >= 2 && cjk * 2 >= letters.length
    }
}
