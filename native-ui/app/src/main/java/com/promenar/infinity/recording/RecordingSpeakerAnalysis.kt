package com.promenar.infinity.recording

import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.roundToLong

internal data class RecordingSpeakerResult(
    val turns: List<RecordingSpeakerTurn>,
    val speakers: List<RecordingSpeaker>,
)

internal object RecordingSpeakerAnalysis {
    private const val RATE = RecordingCapturePolicy.SAMPLE_RATE
    private const val WINDOW_MS = 60_000L
    private const val MATCH = 0.62f
    private const val MIN_SAMPLE_MS = 1_200L
    private const val MAX_SAMPLE_MS = 15_000L

    private fun config(files: RecordingSpeakerModels) =
        SpeakerEmbeddingExtractorConfig(model = files.embedding.absolutePath, numThreads = 2)

    suspend fun analyze(
        files: RecordingSpeakerModels,
        wav: File,
        durationMs: Long,
        people: List<RecordingVoicePerson>,
        onProgress: suspend (Float) -> Unit,
    ): RecordingSpeakerResult {
        val engine = OfflineSpeakerDiarization(config = OfflineSpeakerDiarizationConfig(
            segmentation = OfflineSpeakerSegmentationModelConfig(
                pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = files.segmentation.absolutePath),
                numThreads = 2,
            ),
            embedding = config(files),
        ))
        try {
            require(engine.sampleRate() == RATE)
            val embedding = SpeakerEmbeddingExtractor(config = config(files))
            try {
                val profiles = mutableListOf<List<Float>>()
                val counts = mutableListOf<Int>()
                val turns = mutableListOf<RecordingSpeakerTurn>()
                val coroutine = currentCoroutineContext()
                RandomAccessFile(wav, "r").use { file ->
                    require(file.length() >= RecordingStore.WAV_HEADER_BYTES + durationMs * 32L)
                    var cursor = 0L
                    while (cursor < durationMs) {
                        coroutine.ensureActive()
                        val end = minOf(cursor + WINDOW_MS, durationMs)
                        val samples = readPcm(file, cursor, end)
                        // 每个窗口前后检查取消，原生推理只持有当前窗口的 PCM。
                        val found = engine.process(samples).sortedBy { it.start }
                        coroutine.ensureActive()
                        val mapped = mutableMapOf<Int, Int>()
                        for ((local, spans) in found.groupBy { it.speaker }) {
                            val longest = spans.maxBy { it.end - it.start }
                            val begin = (longest.start * RATE).toInt().coerceIn(0, samples.size)
                            val length = minOf((longest.end * RATE).toInt(), samples.size) - begin
                            val vector = if (length >= RATE * MIN_SAMPLE_MS / 1_000) {
                                embeddingVector(embedding, samples.copyOfRange(begin, begin + minOf(length, RATE * 8)))
                            } else emptyList()
                            val best = profiles.indices.filterNot(mapped.values::contains)
                                .maxByOrNull { recordingCosine(vector, profiles[it]) }
                                ?.takeIf { recordingCosine(vector, profiles[it]) >= MATCH }
                            val global = if (best == null) {
                                profiles += vector
                                counts += 1
                                profiles.lastIndex
                            } else {
                                val count = counts[best]
                                profiles[best] = profiles[best].zip(vector) { a, b -> (a * count + b) / (count + 1) }
                                counts[best] = count + 1
                                best
                            }
                            mapped[local] = global
                        }
                        found.forEach {
                            val start = cursor + (it.start * 1_000).roundToLong().coerceIn(0L, end - cursor)
                            val stop = cursor + (it.end * 1_000).roundToLong().coerceIn(0L, end - cursor)
                            if (stop > start) turns += RecordingSpeakerTurn(start, stop, mapped.getValue(it.speaker))
                        }
                        cursor = end
                        onProgress(cursor.toFloat() / durationMs)
                    }
                }
                val matched = mutableSetOf<String>()
                val speakers = profiles.mapIndexed { index, vector ->
                    val match = people.asSequence().filterNot { it.id in matched }.mapNotNull { person ->
                        person.samples.maxOfOrNull { recordingCosine(vector, it.embedding) }?.let { person to it }
                    }.maxByOrNull { it.second }?.takeIf { it.second >= MATCH }
                    match?.first?.id?.let(matched::add)
                    RecordingSpeaker(index, match?.first?.name.orEmpty(), match?.first?.id, match?.second)
                }
                return RecordingSpeakerResult(turns, speakers)
            } finally {
                embedding.release()
            }
        } finally {
            engine.release()
        }
    }

    fun sample(
        files: RecordingSpeakerModels,
        wav: File,
        segment: RecordingSegment,
        title: String,
    ): RecordingVoiceSample {
        val end = minOf(segment.endMs, segment.startMs + MAX_SAMPLE_MS)
        require(end - segment.startMs >= MIN_SAMPLE_MS)
        val input = RandomAccessFile(wav, "r").use { readPcm(it, segment.startMs, end) }
        return sample(files, input, title)
    }

    fun sample(files: RecordingSpeakerModels, input: FloatArray, title: String): RecordingVoiceSample {
        val duration = input.size * 1_000L / RATE
        require(duration in MIN_SAMPLE_MS..MAX_SAMPLE_MS)
        require(input.any { kotlin.math.abs(it) >= 0.025f })
        val extractor = SpeakerEmbeddingExtractor(config = config(files))
        try {
            val vector = embeddingVector(extractor, input)
            check(vector.size == 192 && vector.all(Float::isFinite))
            return RecordingVoiceSample(source = title.take(100), durationMs = duration, embedding = vector)
        } finally {
            extractor.release()
        }
    }

    private fun embeddingVector(extractor: SpeakerEmbeddingExtractor, samples: FloatArray): List<Float> {
        val stream = extractor.createStream()
        try {
            stream.acceptWaveform(samples, RATE)
            stream.inputFinished()
            return if (extractor.isReady(stream)) extractor.compute(stream).toList() else emptyList()
        } finally {
            stream.release()
        }
    }

    private fun readPcm(file: RandomAccessFile, start: Long, end: Long): FloatArray {
        require(start >= 0 && end >= start && end - start <= WINDOW_MS)
        val bytes = ByteArray(((end - start) * 32L).toInt())
        file.seek(RecordingStore.WAV_HEADER_BYTES + start * 32L)
        file.readFully(bytes)
        return FloatArray(bytes.size / 2) { index ->
            val offset = index * 2
            ((bytes[offset].toInt() and 0xff) or (bytes[offset + 1].toInt() shl 8)).toShort() / 32768f
        }
    }
}
