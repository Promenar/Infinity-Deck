package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import java.math.BigDecimal
import org.junit.Assert.*
import org.junit.Test

class CloudBackendTest {
    private val inputs = object : CloudMediaInputSource {
        override suspend fun remoteLocation(request: MediaGenerationRequest, input: MediaInput) = input.location
    }
    private fun request(channel: MediaChannel) = MediaGenerationRequest(channel.id, GenerationMode.TEXT, "视频提示词", emptyList(), "source", GenerationSpecs("720p", 5), "session", "productions/test")
    private fun json(s: String) = Json.parseToJsonElement(s).jsonObject
    @Test fun seedanceSubmitPollDownloadAndCosts() = runTest {
        val settings = mediaModelPreset("doubao-seedance-2-0-260128")!!.copy(
            options = CloudMediaOptions(nativeAudio = false, estimatedTokensPerSecond = 100),
            tariff = MediaTariff("CNY", listOf(MediaRate(BillingUnit.TOKENS, "5", 1000))))
        val channel = MediaChannel("cloud:p::ep-model", MediaGroup.VIDEO, settings.capabilities)
        val calls = mutableListOf<Pair<String, JsonObject?>>()
        val backend = SeedanceMediaGenerationBackend(CloudMediaModelSource { CloudMediaModel("p", "ep-model", "https://ark.test/api", settings) }, CloudMediaHttp { _, url, body ->
            calls += url to body
            if (body != null) json("""{"id":"task-1"}""") else json("""{"status":"succeeded","content":{"video_url":"https://cdn.test/a.mp4"},"usage":{"total_tokens":600}}""")
        }, inputs, CloudMediaOutputStore { req, _, _, output -> assertEquals("productions/test", req.outputDir); assertEquals(InputKind.VIDEO, output.kind); "productions/test/a.mp4" })
        assertEquals(BigDecimal("2.5"), backend.estimate(channel, request(channel))!!.amount.stripTrailingZeros())
        val id = backend.submit(channel, request(channel))
        assertTrue(calls[0].first.endsWith("/api/v3/contents/generations/tasks"))
        assertFalse(calls[0].second!!.getValue("generate_audio").jsonPrimitive.boolean)
        val status = backend.poll(channel, id) as RemoteStatus.Succeeded
        assertEquals(0, BigDecimal("3").compareTo(backend.actualCost(channel, status.usage)!!.amount))
        assertEquals(listOf("productions/test/a.mp4"), backend.download(channel, request(channel), id, status.outputs))
    }
    @Test fun referenceLimitIsCheckedBeforeHttp() = runTest {
        val settings = mediaModelPreset("MiniMax-H3")!!
        val channel = MediaChannel("c", MediaGroup.VIDEO, settings.capabilities)
        val backend = MiniMaxMediaGenerationBackend(CloudMediaModelSource { CloudMediaModel("p", "MiniMax-H3", "https://api.test", settings) }, CloudMediaHttp { _, _, _ -> error("禁止发请求") }, inputs, CloudMediaOutputStore { _, _, _, _ -> error("禁止下载") })
        val req = request(channel).copy(mode = GenerationMode.REFERENCE, specs = GenerationSpecs("768P", 5), inputs = List(10) { MediaInput("https://a.test/$it.png", InputKind.IMAGE, InputRole.REFERENCE) })
        try { backend.submit(channel, req); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun minimaxSpeechResumesUsingPersistedUsageAndRetrievesFile() = runTest {
        val settings = mediaModelPreset("speech-2.8-hd")!!.copy(options = CloudMediaOptions(voiceId = "voice"), tariff = MediaTariff("USD", listOf(MediaRate(BillingUnit.CHARACTERS, "0.001"))))
        val channel = MediaChannel("tts", MediaGroup.AUDIO, settings.capabilities)
        val source = CloudMediaModelSource { CloudMediaModel("p", "speech-2.8-hd", "https://api.test", settings) }
        val calls = mutableListOf<String>()
        val http = CloudMediaHttp { _, url, body ->
            calls += url
            when {
                body != null -> json("""{"task_id":123,"usage_characters":42,"base_resp":{"status_code":0}}""")
                "retrieve" in url -> json("""{"file":{"download_url":"https://cdn.test/audio.mp3"}}""")
                else -> json("""{"status":"Success","file_id":456}""")
            }
        }
        val store = CloudMediaOutputStore { req, _, _, output -> assertEquals("https://cdn.test/audio.mp3", output.location); "${req.outputDir}/audio.mp3" }
        val request = request(channel).copy(mode = GenerationMode.SPEECH, specs = GenerationSpecs(), prompt = "你好")
        val first = MiniMaxMediaGenerationBackend(source, http, inputs, store)
        val id = first.submit(channel, request)
        val resumed = MiniMaxMediaGenerationBackend(source, http, inputs, store)
        val status = resumed.poll(channel, id) as RemoteStatus.Succeeded
        assertEquals(42L, status.usage["usage_characters"])
        assertEquals(BigDecimal("0.042"), resumed.actualCost(channel, status.usage)!!.amount)
        assertEquals(listOf("productions/test/audio.mp3"), resumed.download(channel, request, id, status.outputs))
        assertTrue(calls.any { it.endsWith("/v1/files/retrieve?file_id=456") })
    }
    @Test fun minimaxV1PersistsKnownSecondsAndInputSurcharge() = runTest {
        val settings = mediaModelPreset("MiniMax-Hailuo-02")!!.copy(tariff = MediaTariff("USD", listOf(MediaRate(BillingUnit.OUTPUT_SECONDS, "0.1"), MediaRate(BillingUnit.INPUT_IMAGES, "0.02"))))
        val channel = MediaChannel("v1", MediaGroup.VIDEO, settings.capabilities)
        val http = CloudMediaHttp { _, _, body -> if (body != null) json("""{"task_id":"t1"}""") else json("""{"status":"Success","file_id":"f1"}""") }
        val backend = MiniMaxMediaGenerationBackend(CloudMediaModelSource { CloudMediaModel("p", "MiniMax-Hailuo-02", "https://api.test", settings) }, http, inputs, CloudMediaOutputStore { _, _, _, _ -> "x" })
        val req = request(channel).copy(mode = GenerationMode.FIRST_FRAME, inputs = listOf(MediaInput("https://a.test/a.png", InputKind.IMAGE, InputRole.FIRST_FRAME)), specs = GenerationSpecs("768P", 6))
        val id = backend.submit(channel, req)
        val done = backend.poll(channel, id) as RemoteStatus.Succeeded
        assertEquals(BigDecimal("0.62"), backend.actualCost(channel, done.usage)!!.amount)
    }

    @Test fun seedanceRefreshesExpiredDownloadLinkAndRejectsCrossChannelTicket() = runTest {
        val settings = mediaModelPreset("doubao-seedance-2-0-user-model")!!
        val channel = MediaChannel("c", MediaGroup.VIDEO, settings.capabilities)
        var queries = 0
        var downloads = 0
        val http = CloudMediaHttp { _, _, body ->
            if (body != null) json("""{"id":"task"}""") else {
                queries++
                json("""{"status":"succeeded","content":{"video_url":"https://cdn.test/fresh.mp4"}}""")
            }
        }
        val backend = SeedanceMediaGenerationBackend(CloudMediaModelSource { CloudMediaModel("p", "ep-model", "https://api.test", settings) }, http, inputs, CloudMediaOutputStore { _, _, _, output ->
            downloads++
            if (downloads == 1) throw com.promenar.infinity.domain.media.WorkspaceMediaException("HTTP_ERROR", "媒体下载失败：HTTP 403")
            assertEquals("https://cdn.test/fresh.mp4", output.location)
            "saved.mp4"
        })
        val id = backend.submit(channel, request(channel))
        assertEquals(listOf("saved.mp4"), backend.download(channel, request(channel), id, listOf(RemoteOutput("https://cdn.test/expired.mp4", InputKind.VIDEO))))
        assertEquals(1, queries); assertEquals(2, downloads)
        try { backend.poll(channel.copy(id = "different-channel"), id); fail() } catch (_: IllegalArgumentException) { }
        assertEquals(1, queries)
    }
    @Test fun seedanceTranslatesAllTerminalAndRunningStates() = runTest {
        val settings = mediaModelPreset("doubao-seedance-2-0-user-model")!!
        val channel = MediaChannel("c", MediaGroup.VIDEO, settings.capabilities)
        var remoteState = "queued"
        val backend = SeedanceMediaGenerationBackend(CloudMediaModelSource { CloudMediaModel("p", "ep-model", "https://api.test", settings) }, CloudMediaHttp { _, _, body ->
            if (body != null) json("""{"id":"task"}""") else if (remoteState == "http-expired") throw CloudHttpException(410)
            else buildJsonObject { put("status", remoteState) }
        }, inputs, CloudMediaOutputStore { _, _, _, _ -> error("无需下载") })
        val id = backend.submit(channel, request(channel))
        for (state in listOf("queued", "running")) { remoteState = state; assertTrue(backend.poll(channel, id) is RemoteStatus.Running) }
        for (state in listOf("failed", "expired", "cancelled", "future", "http-expired")) { remoteState = state; assertTrue(backend.poll(channel, id) is RemoteStatus.Failed) }
    }
    @Test fun minimaxV2UsesServerUnitsForAllCharges() = runTest {
        val settings = mediaModelPreset("MiniMax-H3")!!.copy(tariff = MediaTariff("USD", listOf(
            MediaRate(BillingUnit.OUTPUT_SECONDS, "0.08"), MediaRate(BillingUnit.INPUT_VIDEO_SECONDS, "0.08"),
            MediaRate(BillingUnit.INPUT_IMAGES, "0.04", freeUnits = 5), MediaRate(BillingUnit.INPUT_AUDIO_SECONDS, "0.01"),
        )))
        val channel = MediaChannel("h3", MediaGroup.VIDEO, settings.capabilities)
        val http = CloudMediaHttp { _, url, body ->
            if (body != null) { assertTrue(url.endsWith("/v2/video_generation")); json("""{"task_id":"h3task"}""") }
            else { assertTrue(url.endsWith("/v2/query/video_generation/h3task")); json("""{"task":{"status":"succeeded","content":{"url":"https://cdn.test/v.mp4"},"usage":{"output_seconds":5,"input_seconds":2,"input_image_count":6,"input_audio_seconds":3}}}""") }
        }
        val backend = MiniMaxMediaGenerationBackend(CloudMediaModelSource { CloudMediaModel("p", "MiniMax-H3", "https://api.test", settings) }, http, inputs, CloudMediaOutputStore { _, _, _, _ -> "v.mp4" })
        val req = request(channel).copy(specs = GenerationSpecs("768P", 5))
        assertEquals(BigDecimal("0.40"), backend.estimate(channel, req)!!.amount)
        val status = backend.poll(channel, backend.submit(channel, req)) as RemoteStatus.Succeeded
        assertEquals(BigDecimal("0.63"), backend.actualCost(channel, status.usage)!!.amount)
    }
}
