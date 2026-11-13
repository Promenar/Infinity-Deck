package com.promenar.infinity.recording

import android.util.AtomicFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID
import kotlin.math.sqrt

@Serializable
internal data class RecordingSpeaker(
    val id: Int,
    val name: String = "",
    val personId: String? = null,
    val confidence: Float? = null,
)

@Serializable
internal data class RecordingVoiceSample(
    val id: String = UUID.randomUUID().toString(),
    val source: String,
    val durationMs: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val embedding: List<Float>,
)

@Serializable
internal data class RecordingVoicePerson(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val samples: List<RecordingVoiceSample> = emptyList(),
)

internal class RecordingVoiceStore(file: File) {
    private val atomic = AtomicFile(file)

    fun read(): List<RecordingVoicePerson> = synchronized(lock) {
        if (!atomic.baseFile.exists() && !File("${atomic.baseFile.path}.bak").exists()) return@synchronized emptyList()
        atomic.openRead().use { input ->
            val length = input.channel.size()
            require(length in 2..MAX_BYTES.toLong())
            val bytes = ByteArray(length.toInt())
            try {
                java.io.DataInputStream(input).readFully(bytes)
                require(input.read() == -1)
                decode(bytes.decodeToString(throwOnInvalidSequence = true))
            } finally { bytes.fill(0) }
        }
    }

    fun write(people: List<RecordingVoicePerson>) = synchronized(lock) {
        val payload = encode(people).encodeToByteArray()
        require(payload.size <= MAX_BYTES)
        val stream = atomic.startWrite()
        try {
            stream.write(payload)
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        } finally {
            payload.fill(0)
        }
    }

    companion object {
        const val FILE_NAME = "recording-voiceprints.json"
        const val MAX_BYTES = 4 * 1024 * 1024
        private val lock = Any()
        private val json = Json { ignoreUnknownKeys = false }
        fun decode(value: String): List<RecordingVoicePerson> {
            require(value.length <= MAX_BYTES)
            return validate(json.decodeFromString<List<RecordingVoicePerson>>(value))
        }
        fun encode(value: List<RecordingVoicePerson>) = json.encodeToString(validate(value))

        private fun validate(people: List<RecordingVoicePerson>): List<RecordingVoicePerson> {
            require(people.size <= 100 && people.map { it.id }.toSet().size == people.size)
            people.forEach { person ->
                require(person.id.length in 1..100 && person.name == person.name.trim() && person.name.length in 1..50 &&
                    person.name.none(Char::isISOControl))
                require(person.samples.size <= 12 && person.samples.map { it.id }.toSet().size == person.samples.size)
                require(person.samples.all {
                    it.id.length in 1..100 && it.source.length <= 100 && it.durationMs in 1_200..15_000 &&
                        it.createdAt > 0 && it.embedding.size == 192 && it.embedding.all(Float::isFinite)
                })
            }
            return people
        }
    }
}

internal data class RecordingSpeakerTurn(val startMs: Long, val endMs: Long, val speakerId: Int)

internal fun RecordingSession.speakerName(id: Int, defaultName: (Int) -> String): String =
    speakers.firstOrNull { it.id == id }?.name?.takeIf(String::isNotBlank) ?: defaultName(id + 1)

internal fun recordingCosine(a: List<Float>, b: List<Float>): Float {
    if (a.isEmpty() || a.size != b.size) return -1f
    var ab = 0.0
    var aa = 0.0
    var bb = 0.0
    for (index in a.indices) {
        val x = a[index].toDouble()
        val y = b[index].toDouble()
        ab += x * y
        aa += x * x
        bb += y * y
    }
    return if (aa > 0 && bb > 0) (ab / sqrt(aa * bb)).toFloat().coerceIn(-1f, 1f) else -1f
}

internal fun recordingSpeakerForSegment(segment: RecordingSegment, turns: List<RecordingSpeakerTurn>): Int? =
    turns.mapNotNull { turn ->
        val overlap = minOf(turn.endMs, segment.endMs) - maxOf(turn.startMs, segment.startMs)
        if (overlap > 0) turn.speakerId to overlap else null
    }.groupingBy { it.first }.fold(0L) { sum, item -> sum + item.second }
        .maxWithOrNull(compareBy<Map.Entry<Int, Long>> { it.value }.thenBy { -it.key })?.key

internal fun RecordingSession.withSpeakerChange(
    from: Int,
    to: Int,
    segmentStartMs: Long? = null,
): RecordingSession = copy(
    segments = segments.map {
        if (it.speakerId == from && (segmentStartMs == null || segmentStartMs == it.startMs)) it.copy(speakerId = to) else it
    },
    speakers = if (segmentStartMs == null && from != to) speakers.filterNot { it.id == from } else speakers,
    speakerAssignmentsEdited = true,
)

internal fun RecordingSession.preserveSpeakerAssignments(previous: RecordingSession): RecordingSession {
    if (!distinguishSpeakers || previous.speakers.isEmpty()) return this
    val prior = previous.assignedSpeakerTurns()
    return copy(segments = segments.map { it.copy(speakerId = recordingSpeakerForSegment(it, prior)) })
}

internal fun RecordingSession.assignedSpeakerTurns(): List<RecordingSpeakerTurn> =
    segments.mapNotNull { it.speakerId?.let { id -> RecordingSpeakerTurn(it.startMs, it.endMs, id) } }

internal fun RecordingSession.applySpeakerAnalysis(result: RecordingSpeakerResult): RecordingSession {
    if (!speakerAssignmentsEdited) return copy(
        segments = segments.map { it.copy(speakerId = recordingSpeakerForSegment(it, result.turns)) },
        speakers = result.speakers,
        speakerAnalyzedBytes = audioBytes,
    )
    val remap = mutableMapOf<Int, Int>()
    val used = mutableSetOf<Int>()
    var next = (speakers.maxOfOrNull { it.id } ?: -1) + 1
    val additional = mutableListOf<RecordingSpeaker>()
    for (speaker in result.speakers) {
        val agreement = segments.filter { it.speakerId != null && recordingSpeakerForSegment(it, result.turns) == speaker.id }
            .groupingBy { requireNotNull(it.speakerId) }.eachCount()
        val existing = agreement.entries.filterNot { it.key in used }.maxByOrNull { it.value }?.key
        val resolved = existing ?: next++.also { additional += speaker.copy(id = it) }
        remap[speaker.id] = resolved
        used += resolved
    }
    return copy(
        segments = segments.map { item ->
            item.speakerId?.let { item } ?: item.copy(speakerId = recordingSpeakerForSegment(item, result.turns)?.let(remap::get))
        },
        speakers = speakers + additional,
        speakerAnalyzedBytes = audioBytes,
    )
}

/** 将同一原始段内的接话边界定位在两段语音之间，覆盖原段完整时间区间。 */
internal fun recordingSpeakerSpans(
    segment: RecordingSegment,
    turns: List<RecordingSpeakerTurn>,
    minimumOverlapMs: Long = 350,
): List<RecordingSpeakerTurn> {
    val audible = turns.asSequence().map {
        it.copy(startMs = maxOf(segment.startMs, it.startMs), endMs = minOf(segment.endMs, it.endMs))
    }.filter { it.endMs - it.startMs >= minimumOverlapMs }.sortedBy { it.startMs }
    val runs = mutableListOf<RecordingSpeakerTurn>()
    for (turn in audible) {
        val previous = runs.lastOrNull()
        if (previous != null && previous.speakerId == turn.speakerId) {
            runs[runs.lastIndex] = previous.copy(endMs = maxOf(previous.endMs, turn.endMs))
        } else if (previous == null || turn.endMs > previous.endMs) {
            runs += turn
        }
    }
    if (runs.size < 2) return emptyList()
    val boundaries = runs.zipWithNext { first, second -> (first.endMs + second.startMs) / 2 }
    return runs.mapIndexed { index, turn ->
        turn.copy(startMs = boundaries.getOrElse(index - 1) { segment.startMs },
            endMs = boundaries.getOrElse(index) { segment.endMs })
    }
}

/** 识别各段的开头只用于定位，切分后的文字始终来自同一份原始听写。 */
internal fun splitRecordingSpeakerText(original: String, decoded: List<String>): List<String>? {
    if (decoded.size < 2) return null
    val positions = original.indices.filter { original[it].isLetterOrDigit() }
    val text = positions.map { original[it].lowercaseChar() }.joinToString("")
    fun words(value: String) = value.filter(Char::isLetterOrDigit).lowercase(java.util.Locale.ROOT)
    val pieces = decoded.map(::words)
    if (pieces.any { it.length < 3 }) return null
    val leading = pieces.first()
    if (!(3..minOf(6, leading.length)).any { text.take(12).contains(leading.take(it)) }) return null

    val offsets = mutableListOf(0)
    var after = 0
    var expected = pieces.first().length
    for ((left, right) in pieces.zipWithNext()) {
        val next = (minOf(9, right.length) downTo 3).firstNotNullOfOrNull { count ->
            val anchor = right.take(count)
            val candidates = text.indices.asSequence().drop(after + 3).filter { text.startsWith(anchor, it) }
                .filter { index ->
                    val offset = positions[index]
                    val before = original.getOrNull(offset - 1)
                    fun asciiLetter(char: Char) = char in 'A'..'Z' || char in 'a'..'z'
                    !(before != null && asciiLetter(before) && asciiLetter(original[offset]))
                }.toList()
            val leftAnchored = candidates.filter { index ->
                val matching = minOf(12, left.length, index - after)
                matching >= 3 && (matching downTo 3).any { length ->
                    text.regionMatches(index - length, left, left.length - length, length)
                }
            }
            when {
                leftAnchored.size == 1 -> leftAnchored.single()
                candidates.size == 1 && count >= 8 -> candidates.single()
                else -> (leftAnchored.ifEmpty { candidates }).minByOrNull { kotlin.math.abs(it - expected) }
                    ?.takeIf { kotlin.math.abs(it - expected) <= maxOf(8, right.length / 3) }
            }
        } ?: return null
        offsets += positions[next]
        after = next
        expected = next + right.length
    }
    offsets += original.length
    return offsets.zipWithNext { start, end -> original.substring(start, end).trim() }.takeIf { it.none(String::isBlank) }
}
