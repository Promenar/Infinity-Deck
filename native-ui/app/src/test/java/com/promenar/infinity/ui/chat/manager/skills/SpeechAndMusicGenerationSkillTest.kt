package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.mediagen.cloud.CloudMediaHttp
import com.promenar.infinity.data.mediagen.cloud.CloudMediaInputSource
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModel
import com.promenar.infinity.data.mediagen.cloud.CloudMediaModelSource
import com.promenar.infinity.data.mediagen.cloud.CloudMediaOutputStore
import com.promenar.infinity.data.mediagen.cloud.GeminiMediaGenerationBackend
import com.promenar.infinity.data.mediagen.cloud.wrapPcmAsWav
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.mediaModelPreset
import com.promenar.infinity.domain.mediagen.InputKind
import com.promenar.infinity.domain.mediagen.MediaGenerationRequest
import com.promenar.infinity.domain.mediagen.MediaInput
import com.promenar.infinity.domain.mediagen.RemoteOutput
import io.mockk.every
import io.mockk.mockk
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Test

class SpeechAndMusicGenerationSkillTest {
    private val jobFixture = MediaTestJobFixture()
    @After fun closeJobs() = jobFixture.close()

    private fun pcmSine(
        durationSec: Double,
        sampleRate: Int = 24_000,
        channels: Int = 1,
        amplitude: Int = 8_000,
    ): ByteArray {
        val frames = (durationSec * sampleRate).toInt()
        val buf = ByteBuffer.allocate(frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until frames) {
            val sample = (sin(2.0 * Math.PI * 440.0 * i / sampleRate) * amplitude).toInt().toShort()
            repeat(channels) { buf.putShort(sample) }
        }
        return buf.array()
    }

    private fun pcmSilence(durationSec: Double, sampleRate: Int = 24_000, channels: Int = 1): ByteArray =
        ByteArray((durationSec * sampleRate).toInt() * channels * 2)

    private fun buildWav(vararg chunks: ByteArray, sampleRate: Int = 24_000, channels: Int = 1): ByteArray {
        val out = ByteArrayOutputStream()
        chunks.forEach(out::write)
        return wrapPcmAsWav(out.toByteArray(), sampleRate = sampleRate, channels = channels, bitsPerSample = 16)
    }

    private fun syntheticCbrMp3(frameCount: Int): ByteArray {
        // MPEG1 Layer III, 192 kbps, 48000 Hz, 无 padding, 立体声 => 每帧 576 字节、0.024s。
        val frameSize = 576
        val out = ByteArray(frameCount * frameSize)
        for (i in 0 until frameCount) {
            val base = i * frameSize
            out[base] = 0xFF.toByte()
            out[base + 1] = 0xFB.toByte()
            out[base + 2] = 0xB4.toByte()
            out[base + 3] = 0x00.toByte()
        }
        return out
    }

    private fun syntheticId3XingMp3(declaredFrames: Int): ByteArray {
        val id3 = ByteArray(26).apply {
            this[0] = 'I'.code.toByte()
            this[1] = 'D'.code.toByte()
            this[2] = '3'.code.toByte()
            this[3] = 3
            this[9] = 16
        }
        val frame = syntheticCbrMp3(1)
        // MPEG1 立体声 side info 为 32 字节，Xing 头位于帧内偏移 36。
        val xingOffset = 36
        frame[xingOffset] = 'X'.code.toByte()
        frame[xingOffset + 1] = 'i'.code.toByte()
        frame[xingOffset + 2] = 'n'.code.toByte()
        frame[xingOffset + 3] = 'g'.code.toByte()
        ByteBuffer.wrap(frame, xingOffset + 4, 4).order(ByteOrder.BIG_ENDIAN).putInt(1)
        ByteBuffer.wrap(frame, xingOffset + 8, 4).order(ByteOrder.BIG_ENDIAN).putInt(declaredFrames)
        return id3 + frame
    }

    @Test
    fun `trimWavSilence 裁剪首尾长静音并保留 150ms 自然余量且重算 WAV 头`() {
        val wav = buildWav(pcmSilence(1.0), pcmSine(1.5), pcmSilence(8.0))
        val trimmed = trimWavSilence(wav, padMs = 150)

        assertThat(trimmed.trimmed).isTrue()
        assertThat(trimmed.originalDurationSeconds!!).isWithin(0.02).of(10.5)
        // 1.5s 语音 + 首尾各 0.15s 自然余量 ≈ 1.80s。
        assertThat(trimmed.durationSeconds!!).isWithin(0.05).of(1.80)
        assertThat(String(trimmed.bytes, 0, 4, Charsets.US_ASCII)).isEqualTo("RIFF")
        assertThat(String(trimmed.bytes, 8, 4, Charsets.US_ASCII)).isEqualTo("WAVE")
        val riffSize = ByteBuffer.wrap(trimmed.bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val dataSize = ByteBuffer.wrap(trimmed.bytes, 40, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertThat(riffSize).isEqualTo(trimmed.bytes.size - 8)
        assertThat(dataSize).isEqualTo(trimmed.bytes.size - 44)
        // 确认语音主段峰值完好保留。
        var maxSample = 0
        var pos = 44
        while (pos + 1 < trimmed.bytes.size) {
            val s = (trimmed.bytes[pos].toInt() and 0xFF) or (trimmed.bytes[pos + 1].toInt() shl 8)
            maxSample = maxOf(maxSample, abs(s))
            pos += 2
        }
        assertThat(maxSample).isAtLeast(7_500)
    }

    @Test
    fun `trimWavSilence 对紧凑录音保持原字节不变`() {
        // 0.10s 前静音 + 1.50s 语音 + 0.25s 尾静音 => 保留 150ms 后仅可裁 100ms（<= 400ms 阈值）。
        val tight = buildWav(pcmSilence(0.10), pcmSine(1.50), pcmSilence(0.25))
        val result = trimWavSilence(tight, padMs = 150)

        assertThat(result.trimmed).isFalse()
        assertThat(result.bytes).isEqualTo(tight)
        assertThat(result.durationSeconds!!).isWithin(0.02).of(1.85)
    }

    @Test
    fun `trimWavSilence 对非 WAV MP3 字节原样透传`() {
        val mp3 = syntheticCbrMp3(10)
        val result = trimWavSilence(mp3)

        assertThat(result.trimmed).isFalse()
        assertThat(result.durationSeconds).isNull()
        assertThat(result.bytes).isEqualTo(mp3)
    }

    @Test
    fun `trimWavSilence 对全静音 WAV 安全原样保留`() {
        val silent = buildWav(pcmSilence(5.0))
        val result = trimWavSilence(silent)

        assertThat(result.trimmed).isFalse()
        assertThat(result.bytes).isEqualTo(silent)
        assertThat(result.durationSeconds!!).isWithin(0.02).of(5.0)
    }

    @Test
    fun `trimWavSilence 正确处理立体声 WAV 并保持声道帧对齐`() {
        val stereo = buildWav(
            pcmSilence(1.0, channels = 2),
            pcmSine(1.0, channels = 2),
            pcmSilence(2.0, channels = 2),
            channels = 2,
        )
        val result = trimWavSilence(stereo, padMs = 150)

        assertThat(result.trimmed).isTrue()
        assertThat(result.durationSeconds!!).isWithin(0.05).of(1.30)
        assertThat((result.bytes.size - 44) % 4).isEqualTo(0)
    }

    @Test
    fun `estimateAudioDurationSeconds 支持 WAV 与 CBR 及 Xing MP3 且对损坏字节不崩溃`() {
        val wav = buildWav(pcmSine(3.0))
        assertThat(estimateAudioDurationSeconds(wav)!!).isWithin(0.01).of(3.0)

        val cbr = syntheticCbrMp3(100)
        assertThat(estimateAudioDurationSeconds(cbr)!!).isWithin(0.01).of(2.4)

        val xing = syntheticId3XingMp3(5_000)
        assertThat(estimateAudioDurationSeconds(xing)!!).isWithin(0.01).of(120.0)

        assertThat(estimateAudioDurationSeconds(byteArrayOf())).isNull()
        assertThat(estimateAudioDurationSeconds(byteArrayOf(1, 2, 3, 4, 5))).isNull()
        assertThat(estimateAudioDurationSeconds("ID3\u0003\u0000\u0000\u0000\u0000\u0000\u0010".toByteArray())).isNull()
    }

    @Test
    fun `SpeechGenerationSkill 自动裁剪长尾静音并在返回文案中报告实测时长`() = runTest {
        val ttsModel = ModelInfo(
            name = "Gemini 2.5 Flash TTS",
            id = "g::gemini-2.5-flash-tts",
            description = "",
            enabled = true,
            type = "speech",
            providerName = "Gemini",
            providerId = "g",
            remoteModelId = "gemini-2.5-flash-tts",
        )
        val settings = mediaModelPreset("gemini-2.5-flash-tts")!!
        val cloudModel = CloudMediaModel("g", "gemini-2.5-flash-tts", "https://generativelanguage.googleapis.com/v1beta", settings)
        val rawPcm = pcmSine(1.0) + pcmSilence(6.0)
        val http = object : CloudMediaHttp {
            override suspend fun request(providerId: String, url: String, body: JsonObject?): JsonObject =
                buildJsonObject {
                    put("candidates", buildJsonArray {
                        add(buildJsonObject {
                            put("content", buildJsonObject {
                                put("parts", buildJsonArray {
                                    add(buildJsonObject {
                                        put("inlineData", buildJsonObject {
                                            put("mimeType", "audio/L16;codec=pcm;rate=24000")
                                            put("data", Base64.getEncoder().encodeToString(rawPcm))
                                        })
                                    })
                                })
                            })
                        })
                    })
                }
        }
        val providerManager = mockk<ProviderManager> {
            every { speechModelId } returns MutableStateFlow(ttsModel.id)
            every { providerModels } returns MutableStateFlow(listOf(ttsModel))
        }
        val staging = createTempDirectory("speech-skill-test").toFile()
        val store = RecordingMediaStore()
        try {
            val backend = GeminiMediaGenerationBackend(
                models = CloudMediaModelSource { if (it == "cloud:${ttsModel.id}") cloudModel else null },
                http = http,
                inputs = object : CloudMediaInputSource {
                    override suspend fun remoteLocation(request: MediaGenerationRequest, input: MediaInput) = input.location
                },
                outputs = noopOutputStore(),
                stagingDir = staging,
            )
            val skill = SpeechGenerationSkill(
                providerManager = providerManager,
                cloudModels = CloudMediaModelSource { if (it == "cloud:${ttsModel.id}") cloudModel else null },
                backend = { backend },
                mediaStore = store,
                jobs = jobFixture.jobs,
            )
            val result = skill.execute(buildJsonObject { put("text", "你好，这是一句简短台词。") }, mediaTestContext)
            assertThat(result.status).isEqualTo("success")
            assertThat(store.saved).hasSize(1)
            val savedDuration = measureWavDurationSeconds(store.saved.single().bytes)!!
            assertThat(savedDuration).isWithin(0.05).of(1.15)
            assertThat(result.content).contains("时长约 1.2s")
            assertThat(result.content).contains("已裁剪首尾多余静音")
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `MusicGenerationSkill schema 明确说明软提示且执行后报告实测时长与裁剪提醒`() = runTest {
        val musicModel = ModelInfo(
            name = "Lyria 3.5",
            id = "g::lyria-3.5",
            description = "",
            enabled = true,
            type = "music",
            providerName = "Gemini",
            providerId = "g",
            remoteModelId = "lyria-3.5",
        )
        val settings = mediaModelPreset("lyria-3.5")!!
        val cloudModel = CloudMediaModel("g", "lyria-3.5", "https://generativelanguage.googleapis.com/v1beta", settings)
        val mp3Bytes = syntheticId3XingMp3(5_000) // 120.0s
        val mp3B64 = Base64.getEncoder().encodeToString(mp3Bytes)
        val http = object : CloudMediaHttp {
            override suspend fun request(providerId: String, url: String, body: JsonObject?): JsonObject =
                buildJsonObject {
                    put("outputs", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "audio")
                            put("mime_type", "audio/mpeg")
                            put("data", mp3B64)
                        })
                    })
                }
        }
        val providerManager = mockk<ProviderManager> {
            every { musicModelId } returns MutableStateFlow(musicModel.id)
            every { providerModels } returns MutableStateFlow(listOf(musicModel))
        }
        val staging = createTempDirectory("music-skill-test").toFile()
        val store = RecordingMediaStore()
        try {
            val backend = GeminiMediaGenerationBackend(
                models = CloudMediaModelSource { if (it == "cloud:${musicModel.id}") cloudModel else null },
                http = http,
                inputs = object : CloudMediaInputSource {
                    override suspend fun remoteLocation(request: MediaGenerationRequest, input: MediaInput) = input.location
                },
                outputs = noopOutputStore(),
                stagingDir = staging,
            )
            val skill = MusicGenerationSkill(
                providerManager = providerManager,
                cloudModels = CloudMediaModelSource { if (it == "cloud:${musicModel.id}") cloudModel else null },
                inputSource = object : CloudMediaInputSource {
                    override suspend fun remoteLocation(request: MediaGenerationRequest, input: MediaInput) = input.location
                },
                backend = { backend },
                mediaStore = store,
                jobs = jobFixture.jobs,
            )

            val durationDesc = Json.parseToJsonElement(skill.parametersSchema).jsonObject["properties"]!!
                .jsonObject["duration_seconds"]!!.jsonObject["description"]!!.jsonPrimitive.content
            assertThat(durationDesc).contains("软提示")
            assertThat(durationDesc).contains("Lyria 3.5")
            assertThat(durationDesc).contains("30 秒")

            val withDuration = skill.execute(buildJsonObject {
                put("prompt", "钢琴与弦乐氛围配乐")
                put("duration_seconds", 20)
            }, mediaTestContext)
            assertThat(withDuration.status).isEqualTo("success")
            assertThat(withDuration.content).contains("时长约 120.0s")
            assertThat(withDuration.content).contains("duration_seconds（20s）")
            assertThat(withDuration.content).contains("裁剪")

            val withoutDuration = skill.execute(buildJsonObject {
                put("prompt", "纯钢琴独奏")
            }, mediaTestContext)
            assertThat(withoutDuration.status).isEqualTo("success")
            assertThat(withoutDuration.content).contains("时长约 120.0s")
            assertThat(withoutDuration.content).doesNotContain("duration_seconds（")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun noopOutputStore() = object : CloudMediaOutputStore {
        override suspend fun save(request: MediaGenerationRequest, remoteId: String, index: Int, output: RemoteOutput) = ""
        override suspend fun saveBytes(
            request: MediaGenerationRequest,
            remoteId: String,
            index: Int,
            kind: InputKind,
            bytes: ByteArray,
            mimeType: String,
            extension: String,
        ) = ""
    }
}
