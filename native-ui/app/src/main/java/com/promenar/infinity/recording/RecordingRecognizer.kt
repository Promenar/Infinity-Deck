package com.promenar.infinity.recording

import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.EndpointRule
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.promenar.infinity.utils.InfinityLogger
import java.io.File

internal data class RecordingDecoderRequest(
    val directory: File?,
    val hotwords: List<RecordingHotword>,
    val vocabulary: Set<Char> = emptySet(),
)

internal fun interface RecordingDecoderFactory {
    fun create(request: RecordingDecoderRequest): RecordingDecoder
}

// 已校验的 X-ASR encoder 按 48 个 10 ms 特征帧推进一次。Sherpa JNI 没有暴露已处理帧数。
private const val X_ASR_DECODE_STEP_MS = 480L

internal object EmptyRecordingDecoder : RecordingDecoder {
    override fun accept(pcm: ShortArray) = RecordingRecognition("", false)
    override fun reset() = Unit
    override fun finish() = ""
}

internal fun createRecordingDecoder(request: RecordingDecoderRequest): RecordingDecoder {
    val directory = request.directory ?: return RecordingTimedDecoder(EmptyRecordingDecoder, false, 0)
    val phrases = recordingRecognizerHotwords(request.hotwords, request.vocabulary)
    fun path(name: String) = File(directory, name).absolutePath
    val rate = RecordingCapturePolicy.SAMPLE_RATE
    val beamSearch = phrases.isNotEmpty()
    val recognizer = OnlineRecognizer(assetManager = null, config = OnlineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = rate, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(path("encoder.int8.onnx"), path("decoder.onnx"), path("joiner.int8.onnx")),
            tokens = path("tokens.txt"), numThreads = 2, modelType = "zipformer2",
            modelingUnit = if (beamSearch) "bpe" else "",
            bpeVocab = if (beamSearch) path("bpe.vocab") else "",
        ),
        endpointConfig = EndpointConfig(
            rule1 = EndpointRule(false, RecordingCapturePolicy.ENDPOINT_EMPTY_SILENCE_SECONDS, RecordingCapturePolicy.ENDPOINT_NO_MIN_UTTERANCE_SECONDS),
            rule2 = EndpointRule(true, RecordingCapturePolicy.ENDPOINT_TEXT_SILENCE_SECONDS, RecordingCapturePolicy.ENDPOINT_NO_MIN_UTTERANCE_SECONDS),
            rule3 = EndpointRule(false, RecordingCapturePolicy.ENDPOINT_LONG_SILENCE_SECONDS, RecordingCapturePolicy.ENDPOINT_LONG_UTTERANCE_SECONDS),
        ),
        enableEndpoint = true,
        decodingMethod = if (beamSearch) "modified_beam_search" else "greedy_search",
        maxActivePaths = 4, hotwordsScore = 2.5f,
    ))
    val hotwordList = phrases.joinToString("/")
    val decoder = object : RecordingDecoder {
        private var stream = try { recognizer.createStream(hotwordList) } catch (error: Throwable) {
            recognizer.release()
            throw error
        }
        private var text = ""
        private val tail = FloatArray(rate * RecordingCapturePolicy.TAIL_SILENCE_SECONDS)
        private var hasAudio = false
        private var inputFinished = false
        private var decodedMs = 0L

        override fun accept(pcm: ShortArray): RecordingRecognition {
            hasAudio = true
            stream.acceptWaveform(FloatArray(pcm.size) { pcm[it] / 32768f }, rate)
            var decoded = false
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                decodedMs += X_ASR_DECODE_STEP_MS
                decoded = true
            }
            if (decoded) text = recognizer.getResult(stream).text
            return RecordingRecognition(text, decoded && recognizer.isEndpoint(stream), decodedMs)
        }
        override fun reset() {
            if (inputFinished) {
                val previous = stream
                stream = recognizer.createStream(hotwordList)
                previous.release()
                hasAudio = false
                decodedMs = 0
            } else {
                // 分段保留编码器上下文和已接收但还没解码的音频。
                recognizer.reset(stream)
            }
            text = ""
            inputFinished = false
        }
        override fun finish(): String {
            if (inputFinished) return text
            inputFinished = true
            if (!hasAudio) return ""
            stream.acceptWaveform(tail, rate)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            text = recognizer.getResult(stream).text.ifBlank { text }
            return text
        }
        override fun close() {
            try { stream.release() } finally { recognizer.release() }
        }
    }
    return RecordingReplacingDecoder(RecordingTimedDecoder(decoder, beamSearch, phrases.size), request.hotwords)
}

internal class RecordingReplacingDecoder(
    private val delegate: RecordingDecoder,
    entries: List<RecordingHotword>,
) : RecordingDecoder {
    private val replacer = RecordingHotwordReplacer(entries)
    override fun accept(pcm: ShortArray): RecordingRecognition = delegate.accept(pcm).let { it.copy(text = replacer.replace(it.text)) }
    override fun finish() = replacer.replace(delegate.finish())
    override fun reset() = delegate.reset()
    override fun close() = delegate.close()
}

private class RecordingTimedDecoder(
    private val delegate: RecordingDecoder,
    private val beamSearch: Boolean,
    private val hotwordCount: Int,
) : RecordingDecoder {
    private var nanos = 0L
    private var samples = 0L
    private fun <T> timed(action: () -> T): T {
        val start = System.nanoTime()
        return try { action() } finally { nanos += System.nanoTime() - start }
    }
    override fun accept(pcm: ShortArray): RecordingRecognition {
        samples += pcm.size
        return timed { delegate.accept(pcm) }
    }
    override fun reset() = timed { delegate.reset() }
    override fun finish() = timed { delegate.finish() }
    override fun close() {
        try { delegate.close() } finally {
            InfinityLogger.diagnostic("recording.decode", mapOf(
                "decode_ms" to nanos / 1_000_000,
                "audio_ms" to samples * 1_000 / RecordingCapturePolicy.SAMPLE_RATE,
                "beam_search" to beamSearch,
                "hotword_count" to hotwordCount,
            ))
        }
    }
}
