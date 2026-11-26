package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.model.CloudMediaApi
import com.promenar.infinity.data.model.CloudMediaPreset
import com.promenar.infinity.data.model.MediaModelSettingsCodec
import com.promenar.infinity.data.model.ModelInfo
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelMetadataResolver
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.model.effectiveMediaSettings
import com.promenar.infinity.data.model.matchCloudMediaPreset
import com.promenar.infinity.data.model.mediaModelPreset
import com.promenar.infinity.data.model.mergeResolvedMetadata
import com.promenar.infinity.data.model.toMediaChannel
import com.promenar.infinity.data.production.*
import com.promenar.infinity.data.remote.protocol.GenericModelsEnvelopeParser
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.chat.readAloudFailureMessage
import com.promenar.infinity.ui.chat.speechReadableText
import com.promenar.infinity.ui.common.ModelBrandResolver
import com.promenar.infinity.ui.common.isChatSelectionCandidate
import com.promenar.infinity.ui.common.matchesModelPickerFilter
import com.promenar.infinity.ui.common.toModelSelectionUiModel
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class GeminiAudioAndReadAloudTest {

    @Test
    fun `Google AI Studio TTS、Lyria 与实时模型自动分类及排除出对话选择器`() {
        val resolver = ModelMetadataResolver()

        for (ttsId in listOf("gemini-3.8-flash-tts", "gemini-3.8-flash-lite-tts", "gemini-2.5-flash-tts")) {
            val resolved = resolver.resolve(ttsId)
            assertEquals("TTS $ttsId 应归入 SPEECH", ModelWorkload.SPEECH, resolved.workload)
            assertEquals(SupportState.UNSUPPORTED, resolved.capabilities[ModelCapability.CHAT_ENDPOINT])
            val preset = mediaModelPreset(ttsId)
            assertNotNull(preset)
            assertEquals(MediaGroup.SPEECH, preset!!.group)
            assertEquals(CloudMediaApi.GEMINI_TTS, preset.api)
            assertEquals("Kore", preset.options.voiceId)
            val ui = ModelInfo(ttsId, "g::$ttsId", "", true, "unknown", providerId = "g", remoteModelId = ttsId)
                .mergeResolvedMetadata(resolved)
                .toModelSelectionUiModel(resolver)
            assertFalse(ui.isChatSelectionCandidate())
            assertTrue(ui.matchesModelPickerFilter("cloud_speech"))
            assertFalse(ui.matchesModelPickerFilter("cloud_music"))
        }

        for (musicId in listOf("lyria-3-clip-preview", "lyria-3-pro-preview", "lyria-3.5")) {
            val resolved = resolver.resolve(musicId)
            assertEquals("Music $musicId 应归入 MUSIC", ModelWorkload.MUSIC, resolved.workload)
            assertEquals(SupportState.UNSUPPORTED, resolved.capabilities[ModelCapability.CHAT_ENDPOINT])
            assertEquals(SupportState.SUPPORTED, resolved.capabilities[ModelCapability.VISION_INPUT])
            assertNull(resolved.contextTokens)
            val preset = mediaModelPreset(musicId)
            assertNotNull(preset)
            assertEquals(MediaGroup.MUSIC, preset!!.group)
            assertEquals(CloudMediaApi.GEMINI_LYRIA, preset.api)
            assertEquals("google", ModelBrandResolver.brandKey(musicId))
            val ui = ModelInfo(musicId, "g::$musicId", "", true, "chat", providerId = "g", remoteModelId = musicId)
                .mergeResolvedMetadata(resolved)
                .toModelSelectionUiModel(resolver)
            assertFalse(ui.isChatSelectionCandidate())
            assertTrue(ui.matchesModelPickerFilter("cloud_music"))
            assertFalse(ui.matchesModelPickerFilter("cloud_speech"))
        }

        for (rtId in listOf("gemini-3.8-live", "lyria-realtime-exp", "gemini-2.5-flash-native-audio-latest", "gemini-2.5-flash-native-audio-preview-12-2025")) {
            val resolved = resolver.resolve(rtId)
            assertEquals("Realtime $rtId 应归入 REALTIME", ModelWorkload.REALTIME, resolved.workload)
            assertEquals(SupportState.UNSUPPORTED, resolved.capabilities[ModelCapability.CHAT_ENDPOINT])
            assertNull(mediaModelPreset(rtId))
            val ui = ModelInfo(rtId, "g::$rtId", "", true, "unknown", providerId = "g", remoteModelId = rtId)
                .mergeResolvedMetadata(resolved)
                .toModelSelectionUiModel(resolver)
            assertFalse(ui.isChatSelectionCandidate())
            assertFalse(ui.matchesModelPickerFilter("cloud_speech"))
            assertFalse(ui.matchesModelPickerFilter("cloud_music"))
        }
    }

    @Test
    fun `Gemini 模型列表 bidiGenerateContent 自动识别为 REALTIME 并排除对话端点`() {
        val body = """
            {"models":[
              {"name":"models/gemini-3.8-live","displayName":"Gemini 3.8 Live","supportedGenerationMethods":["bidiGenerateContent"]},
              {"name":"models/gemini-3.8-flash","displayName":"Gemini 3.8 Flash","supportedGenerationMethods":["generateContent","countTokens"]}
            ]}
        """.trimIndent()
        val parsed = GenericModelsEnvelopeParser.parseDescriptors(body)!!
        val live = parsed.first { it.id == "models/gemini-3.8-live" }
        assertEquals(ModelWorkload.REALTIME, live.metadata.workload)
        assertEquals(SupportState.UNSUPPORTED, live.metadata.capabilities[ModelCapability.CHAT_ENDPOINT])
        val flash = parsed.first { it.id == "models/gemini-3.8-flash" }
        assertNull(flash.metadata.workload)
    }

    @Test
    fun `旧版 AUDIO 媒体配置与 ComfyMediaCategory 反序列化无损兼容`() {
        val legacySpeechJson = """
            {"group":"AUDIO","capabilities":{"modes":["SPEECH"]},"api":"MINIMAX_SPEECH","options":{"voiceId":"v1","audioFormat":"mp3"}}
        """.trimIndent()
        val decodedSpeech = MediaModelSettingsCodec.decode(legacySpeechJson)
        assertNotNull(decodedSpeech)
        assertEquals(MediaGroup.SPEECH, decodedSpeech!!.group)
        assertEquals(CloudMediaApi.MINIMAX_SPEECH, decodedSpeech.api)

        val legacyMusicJson = """
            {"group":"AUDIO","capabilities":{"modes":["MUSIC"]},"options":{"audioFormat":"mp3"}}
        """.trimIndent()
        val decodedMusic = MediaModelSettingsCodec.decode(legacyMusicJson)
        assertNotNull(decodedMusic)
        assertEquals(MediaGroup.MUSIC, decodedMusic!!.group)

        val legacyCategory = Json.decodeFromString(ComfyMediaCategory.serializer(), "\"AUDIO\"")
        assertEquals(ComfyMediaCategory.MUSIC, legacyCategory)
        assertEquals(MediaGroup.SPEECH, legacyCategory.toMediaGroup(setOf(GenerationMode.SPEECH)))
        assertEquals(MediaGroup.MUSIC, legacyCategory.toMediaGroup(setOf(GenerationMode.MUSIC)))
    }

    @Test
    fun `Gemini TTS 请求构造与 PCM 自动封装标准 44 字节 WAV 容器`() {
        val singleBody = geminiTtsCreateBody("你好世界", voiceName = "Puck", styleInstruction = "轻快朗读")
        val singleSpeech = singleBody["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject
        assertEquals("Puck", singleSpeech["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)

        val multiBody = geminiTtsCreateBody(
            prompt = "A: 你好\nB: 晚上好",
            speakers = listOf(SpeechSpeakerVoice("A", "Kore"), SpeechSpeakerVoice("B", "Fenrir")),
        )
        val speakerConfigs = multiBody["generationConfig"]!!.jsonObject["speechConfig"]!!
            .jsonObject["multiSpeakerVoiceConfig"]!!.jsonObject["speakerVoiceConfigs"]!!.jsonArray
        assertEquals(2, speakerConfigs.size)
        assertEquals("Fenrir", speakerConfigs[1].jsonObject["voiceConfig"]!!.jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]!!.jsonPrimitive.content)

        val rawPcm = ByteArray(160) { (it % 127).toByte() }
        val response = buildJsonObject {
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
        val parsed = parseGeminiTtsResponse(response)
        assertEquals("audio/wav", parsed.mimeType)
        assertEquals("wav", parsed.extension)
        assertEquals(44 + rawPcm.size, parsed.bytes.size)
        assertEquals("RIFF", String(parsed.bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(parsed.bytes, 8, 4, Charsets.US_ASCII))
        val rate = ByteBuffer.wrap(parsed.bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(24000, rate)

        // 已带 RIFF 头的字节不应被二次封装。
        val alreadyWav = normalizeAudioPayload(parsed.bytes, "audio/L16;codec=pcm;rate=24000")
        assertEquals(parsed.bytes.size, alreadyWav.bytes.size)
    }

    @Test
    fun `Gemini Lyria 3 兼容 Interactions 与 generateContent 回退并解析音频与歌词`() = runTest {
        val mp3Bytes = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0, 1, 2)
        val mp3B64 = Base64.getEncoder().encodeToString(mp3Bytes)
        val calledUrls = mutableListOf<String>()
        val http = object : CloudMediaHttp {
            override suspend fun request(providerId: String, url: String, body: JsonObject?): JsonObject =
                requestLarge(providerId, url, body)

            override suspend fun requestLarge(providerId: String, url: String, body: JsonObject?): JsonObject {
                calledUrls += url
                if (url.endsWith("/interactions")) throw CloudHttpException(404)
                return buildJsonObject {
                    put("candidates", buildJsonArray {
                        add(buildJsonObject {
                            put("content", buildJsonObject {
                                put("parts", buildJsonArray {
                                    add(buildJsonObject { put("text", "[Verse 1]\nRain on stone") })
                                    add(buildJsonObject {
                                        put("inlineData", buildJsonObject {
                                            put("mimeType", "audio/mpeg")
                                            put("data", mp3B64)
                                        })
                                    })
                                })
                            })
                        })
                    })
                }
            }
        }
        val settings = mediaModelPreset("lyria-3.5")!!
        val cloudModel = CloudMediaModel("g", "lyria-3.5", "https://generativelanguage.googleapis.com/v1beta", settings)
        val staging = createTempDirectory("gemini-audio-test").toFile()
        try {
            var savedBytes: ByteArray? = null
            val backend = GeminiMediaGenerationBackend(
                models = CloudMediaModelSource { if (it == "cloud:g::lyria-3.5") cloudModel else null },
                http = http,
                inputs = object : CloudMediaInputSource {
                    override suspend fun remoteLocation(request: MediaGenerationRequest, input: MediaInput) = input.location
                },
                outputs = object : CloudMediaOutputStore {
                    override suspend fun save(request: MediaGenerationRequest, remoteId: String, index: Int, output: RemoteOutput) = ""
                    override suspend fun saveBytes(request: MediaGenerationRequest, remoteId: String, index: Int, kind: InputKind, bytes: ByteArray, mimeType: String, extension: String): String {
                        savedBytes = bytes
                        return "${request.outputDir}/score-$index.$extension"
                    }
                },
                stagingDir = staging,
            )

            // 1. 直接同步调用（通用工具路径）：验证 404 时从 /interactions 自动回退 :generateContent。
            val direct = backend.synthesizeMusicDirect(cloudModel, "古筝与弦乐雨夜配乐", durationSeconds = 30)
            assertEquals("mp3", direct.extension)
            assertArrayEquals(mp3Bytes, direct.bytes)
            assertTrue(direct.transcriptText!!.contains("Rain on stone"))
            assertEquals(
                listOf(
                    "https://generativelanguage.googleapis.com/v1beta/interactions",
                    "https://generativelanguage.googleapis.com/v1beta/models/lyria-3.5:generateContent",
                ),
                calledUrls,
            )

            // 2. 队列 3 阶段路径（制作面板 production_score 路径）。
            val channel = MediaChannel("cloud:g::lyria-3.5", MediaGroup.MUSIC, settings.capabilities)
            val request = MediaGenerationRequest(
                channelId = channel.id,
                mode = GenerationMode.MUSIC,
                prompt = "激昂古风配乐",
                inputs = emptyList(),
                sourceRef = "prod/score",
                specs = GenerationSpecs(durationSeconds = 30),
                sessionId = "s1",
                outputDir = "productions/p/assets/bgm",
            )
            val remoteId = backend.submit(channel, request)
            val status = backend.poll(channel, remoteId) as RemoteStatus.Succeeded
            val downloaded = backend.download(channel, request, remoteId, status.outputs)
            assertEquals(listOf("productions/p/assets/bgm/score-0.mp3"), downloaded)
            assertArrayEquals(mp3Bytes, savedBytes)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun `制作面板 production_score 可直接调用 Lyria 云端音乐通道`() = runTest {
        val lyria = ModelInfo("Lyria 3.5", "g::lyria-3.5", "", true, "music", providerName = "Gemini", providerId = "g", remoteModelId = "lyria-3.5")
        val lyriaChannel = lyria.toMediaChannel()!!
        assertEquals(MediaGroup.MUSIC, lyriaChannel.group)
        assertEquals(CloudMediaPreset.GEMINI_LYRIA_3_5, matchCloudMediaPreset(lyria.effectiveMediaSettings()))

        val prod = Production(
            id = "p1",
            title = "测试片",
            kind = ProductionKind.FILM,
            rootPath = "productions/test",
            assets = listOf(Asset("bgm-1", AssetKind.SOUND, "雨夜剑舞配乐", description = "清亮古筝转低音弦乐")),
            createdAt = 1L,
            updatedAt = 1L,
        )
        val snapshot = ProductionSnapshot(prod, 1L, manifest = ManifestSync.InSync)
        val workStore = object : ProductionWorkStore {
            override suspend fun load(sessionId: String) = snapshot
            override suspend fun apply(
                sessionId: String,
                snapshot: ProductionSnapshot,
                action: ProductionUserAction,
            ) = snapshot
        }
        val registry = object : MediaChannelRegistry {
            override fun observeChannels(): Flow<List<MediaChannel>> = MutableStateFlow(listOf(lyriaChannel))
            override fun observeOrder(group: MediaGroup): Flow<List<String>> =
                MutableStateFlow(if (group == MediaGroup.MUSIC) listOf(lyriaChannel.id) else emptyList())
            override suspend fun channel(id: String): MediaChannel? = lyriaChannel.takeIf { it.id == id }
            override suspend fun setOrder(group: MediaGroup, channelIds: List<String>) = Unit
        }
        val submitted = mutableListOf<MediaGenerationRequest>()
        val queue = object : MediaGenerationQueue {
            override suspend fun submit(request: MediaGenerationRequest): MediaTask {
                submitted += request
                return MediaTask("t-${submitted.size}", request, state = MediaTaskState.QUEUED, createdAt = 1L)
            }
            override suspend fun cancel(id: String) = Unit
            override fun observeTasks(): Flow<List<MediaTask>> = MutableStateFlow(emptyList())
            override fun observeTask(id: String): Flow<MediaTask?> = MutableStateFlow(null)
        }
        val processing = DefaultProductionProcessing(
            store = workStore,
            registry = registry,
            queue = queue,
            templates = { emptyList() },
            specsFor = { _, _ -> GenerationSpecs() },
            exists = { _, _ -> true },
        )
        val tasks = processing.score(sessionId = "s1", assetId = "bgm-1", durationSeconds = 30.0, versions = 2)
        assertEquals(2, tasks.size)
        assertTrue(submitted.all { it.channelId == lyriaChannel.id && it.mode == GenerationMode.MUSIC && it.specs.durationSeconds == 30 })
    }

    @Test
    fun `气泡朗读正文清洗跳过代码块与纯符号，空正文返回空串`() {
        val markdown = """
            ### 总结
            今天完成了 **Gemini TTS** 与 [Lyria 文档](https://ai.google.dev/music) 接入。
            ![截图](/media/demo.png)
            ```kotlin
            val ignored = 42
            ```
            - 第一点：支持自动封装 WAV
            - 第二点：跳过思考与工具日志
        """.trimIndent()
        val cleaned = speechReadableText(markdown)
        assertTrue(cleaned.contains("总结"))
        assertTrue(cleaned.contains("Gemini TTS"))
        assertTrue(cleaned.contains("Lyria 文档"))
        assertFalse(cleaned.contains("https://"))
        assertFalse(cleaned.contains("val ignored"))
        assertFalse(cleaned.contains("demo.png"))
        assertFalse(cleaned.contains("**"))

        assertEquals("", speechReadableText("   \n```json\n{\"a\":1}\n```\n![](a.png)  "))
        assertEquals("", speechReadableText("   "))
        assertTrue(readAloudFailureMessage(CloudHttpException(401)).contains("密钥"))
        assertTrue(readAloudFailureMessage(CloudHttpException(429)).contains("配额"))
    }

    @Test
    fun `Lyria 响应解析覆盖 steps inline_data、nested outputs、shorthand 与脱敏错误摘要`() {
        val mp3Bytes = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0, 0, 0, 0, 0, 1, 2)
        val mp3B64 = Base64.getEncoder().encodeToString(mp3Bytes)

        // 1. Interactions API steps[].content.parts[].inline_data 形态
        val stepsPayload = buildJsonObject {
            put("steps", buildJsonArray {
                add(buildJsonObject {
                    put("step_number", 1)
                    put("role", "model")
                    put("content", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject { put("text", "[Verse]\nCalm piano") })
                        })
                    })
                })
                add(buildJsonObject {
                    put("step_number", 2)
                    put("role", "model")
                    put("content", buildJsonObject {
                        put("parts", buildJsonArray {
                            add(buildJsonObject {
                                put("inline_data", buildJsonObject {
                                    put("mime_type", "audio/mp3")
                                    put("data", mp3B64)
                                })
                            })
                        })
                    })
                })
            })
        }
        val fromSteps = parseGeminiLyriaResponse(stepsPayload)
        assertArrayEquals(mp3Bytes, fromSteps.bytes)
        assertEquals("mp3", fromSteps.extension)
        assertTrue(fromSteps.transcriptText!!.contains("Calm piano"))

        // 2. Interactions API outputs[].content[] 嵌套形态
        val nestedOutputsPayload = buildJsonObject {
            put("outputs", buildJsonArray {
                add(buildJsonObject {
                    put("type", "model_output")
                    put("content", buildJsonArray {
                        add(buildJsonObject { put("type", "text"); put("text", "Solo acoustic") })
                        add(buildJsonObject {
                            put("type", "audio")
                            put("mime_type", "audio/mpeg")
                            put("data", mp3B64)
                        })
                    })
                })
            })
        }
        val fromNested = parseGeminiLyriaResponse(nestedOutputsPayload)
        assertArrayEquals(mp3Bytes, fromNested.bytes)
        assertEquals("Solo acoustic", fromNested.transcriptText)

        // 3. 无音频时抛出含脱敏结构摘要的异常（不泄露敏感字段值）
        val missingPayload = buildJsonObject {
            put("status", "completed")
            put("outputs", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "only text no audio") })
            })
        }
        val err = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException::class.java) {
            parseGeminiLyriaResponse(missingPayload)
        }
        assertTrue(err.message!!.contains("Lyria 响应未返回音频数据"))
        assertTrue(err.message!!.contains("status=completed"))
        assertTrue(err.message!!.contains("outputs=1(text)"))
        assertFalse(err.message!!.contains("only text no audio"))
    }
}
