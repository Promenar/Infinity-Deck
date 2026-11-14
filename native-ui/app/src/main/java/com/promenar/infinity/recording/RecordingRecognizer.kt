package com.promenar.infinity.recording

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.promenar.infinity.utils.InfinityLogger
import java.io.File

internal data class RecordingDecoderRequest(
    val directory: File?,
    val hotwords: List<RecordingHotword>,
    val vocabulary: Set<Char>,
    val assets: AssetManager,
)

internal fun interface RecordingDecoderFactory {
    fun create(request: RecordingDecoderRequest): RecordingDecoder
}

// 已校验的 X-ASR encoder 按 48 个 10 ms 特征帧推进一次。Sherpa JNI 没有暴露已处理帧数。
private const val X_ASR_DECODE_STEP_MS = 480L

internal object EmptyRecordingDecoder : RecordingDecoder {
    override fun accept(pcm: ShortArray) = RecordingRecognition("")
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
        enableEndpoint = false,
        decodingMethod = if (beamSearch) "modified_beam_search" else "greedy_search",
        maxActivePaths = 4, hotwordsScore = 2.5f,
    ))
    val hotwordList = phrases.joinToString("/")
    val decoder = object : RecordingDecoder {
        private val vad = try {
            Vad(request.assets, VadModelConfig(sileroVadModelConfig = SileroVadModelConfig(
                model = "asr/silero_vad.onnx",
                windowSize = RecordingSpeechBoundary.WINDOW_SAMPLES,
            )))
        } catch (error: Throwable) {
            recognizer.release()
            throw error
        }
        private var stream = try { recognizer.createStream(hotwordList) } catch (error: Throwable) {
            try { vad.release() } finally { recognizer.release() }
            throw error
        }
        private val boundary = RecordingSpeechBoundary(vad::compute)
        private var text = ""
        private val tail = FloatArray(rate * RecordingCapturePolicy.TAIL_SILENCE_SECONDS)
        private var inputFinished = false
        private var acceptedSamples = 0L
        private var streamStart = 0L
        private var decodedMs = 0L
        private var lead: RecordingUtteranceLead? = null
        private var lastToken: Long? = null

        override fun speechWindow(pcm: ShortArray): RecordingSpeechWindow = boundary.accept(pcm)

        override fun accept(pcm: ShortArray): RecordingRecognition {
            lead?.append(pcm)
            acceptedSamples += pcm.size
            return decode(pcm)
        }

        override fun restartAtOnset(onset: Int, pcm: ShortArray): RecordingRecognition {
            val history = lead ?: return accept(pcm)
            lead = null
            // 停顿里偶发的早期伪文本不能阻止对真实起声重新解码。
            val replay = history.replayFrom(onset, lastToken)
            if (replay != null) {
                startStream(streamStart + replay.start)
                decode(replay.pcm)
            }
            return accept(pcm)
        }

        private fun decode(pcm: ShortArray): RecordingRecognition {
            val audio = FloatArray(pcm.size) { pcm[it] / 32768f }
            stream.acceptWaveform(audio, rate)
            var decoded = false
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream)
                decodedMs += X_ASR_DECODE_STEP_MS
                decoded = true
            }
            if (decoded) {
                val result = recognizer.getResult(stream)
                text = result.text
                lastToken = if (text.isBlank()) null else recordingLastTokenSample(result.timestamps)
            }
            return RecordingRecognition(text, decodedMs)
        }
        override fun reset() {
            if (inputFinished) {
                startStream(acceptedSamples)
            } else {
                // 连续说话到达段落上限时，保留尚未解码的音频。
                recognizer.reset(stream)
            }
            lead = null
            text = ""
        }
        override fun nextUtterance() {
            startStream(acceptedSamples)
            lead = RecordingUtteranceLead()
        }
        private fun startStream(start: Long) {
            val previous = stream
            stream = recognizer.createStream(hotwordList)
            previous.release()
            streamStart = start
            decodedMs = start * 1_000 / rate
            inputFinished = false
            text = ""
            lastToken = null
        }
        override fun finish(): String {
            if (inputFinished) return text
            inputFinished = true
            if (acceptedSamples == streamStart) return ""
            stream.acceptWaveform(tail, rate)
            stream.inputFinished()
            while (recognizer.isReady(stream)) recognizer.decode(stream)
            text = recognizer.getResult(stream).text.ifBlank { text }
            return text
        }
        override fun close() {
            try { stream.release() } finally {
                try { recognizer.release() } finally { vad.release() }
            }
        }
    }
    return RecordingReplacingDecoder(RecordingTimedDecoder(decoder, beamSearch, phrases.size), request.hotwords)
}

internal class RecordingReplacingDecoder(
    private val delegate: RecordingDecoder,
    entries: List<RecordingHotword>,
) : RecordingDecoder by delegate {
    private val replacer = RecordingHotwordReplacer(entries)
    override fun accept(pcm: ShortArray): RecordingRecognition = delegate.accept(pcm).let { it.copy(text = replacer.replace(it.text)) }
    override fun restartAtOnset(onset: Int, pcm: ShortArray): RecordingRecognition =
        delegate.restartAtOnset(onset, pcm).let { it.copy(text = replacer.replace(it.text)) }
    override fun finish() = replacer.replace(delegate.finish())
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
    override fun speechWindow(pcm: ShortArray) = timed { delegate.speechWindow(pcm) }
    override fun restartAtOnset(onset: Int, pcm: ShortArray): RecordingRecognition {
        samples += pcm.size
        return timed { delegate.restartAtOnset(onset, pcm) }
    }
    override fun nextUtterance() = timed { delegate.nextUtterance() }
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
