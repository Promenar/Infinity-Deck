package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ComfyBackendTest {
    @Test fun derivesVideoWithUploadedNamesMimeFallbackAndParameterOverrides() = runTest {
        val f = ComfyFixture()
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Test","inputs":{"file":"old.mp4","steps":10}},"2":{"class_type":"Save","inputs":{}}}""").jsonObject
        val template = f.template.copy(workflow = graph, slots = listOf(ComfySlot("1", "file", SlotKind.INPUT_VIDEO),
            ComfySlot("1", "steps", SlotKind.PARAM, param = "steps"), ComfySlot("2", "videos", SlotKind.OUTPUT, outputKind = InputKind.VIDEO)),
            role = TemplateRole.DERIVE, operation = TemplateOperation("超分"), group = MediaGroup.VIDEO,
            params = listOf(TemplateParam("steps", "步数", TemplateParamType.NUMBER, JsonPrimitive(10), min = 1.0, max = 30.0, integer = true)),
            capabilities = MediaCapabilities(setOf(GenerationMode.REFERENCE)))
        f.http.info = Json.parseToJsonElement("""{"Test":{"input":{"required":{"file":["STRING"],"steps":["INT",{"min":1,"max":30}]}}},"Save":{"input":{"required":{}},"output_node":true,"output":["IMAGE"]}}""").jsonObject
        f.repository.saveHost(f.host); f.repository.defineTemplateByAgent(template)
        for ((suffix, mime) in listOf("mp4" to "video/mp4", "mov" to "video/quicktime", "webm" to "video/webm")) {
            val request = f.request.copy(prompt = "", inputs = listOf(MediaInput("refs/source.$suffix", InputKind.VIDEO, InputRole.REFERENCE)), specs = GenerationSpecs(),
                params = mapOf("steps" to JsonPrimitive(20.0)))
            f.backend.submit(template.channel(), request)
            val inputs = f.http.calls.last { it.first == "/prompt" }.second!!["prompt"]!!.jsonObject["1"]!!.jsonObject["inputs"]!!.jsonObject
            assertTrue(inputs["file"]!!.jsonPrimitive.content.endsWith(".$suffix"))
            assertEquals("20", inputs["steps"].toString())
            assertEquals(InputKind.VIDEO to mime, f.http.uploadTypes.last())
        }
        f.media.videoMime = "video/webm"
        f.backend.submit(template.channel(), f.request.copy(inputs = listOf(MediaInput("refs/source.mp4", InputKind.VIDEO, InputRole.REFERENCE)), specs = GenerationSpecs()))
        assertTrue(f.http.uploads.last().first.endsWith(".webm"))
    }

    @Test fun musicDurationUsesSecondsWithoutRequiringFramesAndUnknownParamsFailBeforeUpload() = runTest {
        val f = ComfyFixture()
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Test","inputs":{"prompt":"","seconds":5}},"2":{"class_type":"Save","inputs":{}}}""").jsonObject
        val template = f.template.copy(workflow = graph, slots = listOf(ComfySlot("1", "prompt", SlotKind.PROMPT),
            ComfySlot("1", "seconds", SlotKind.DURATION), ComfySlot("2", "audio", SlotKind.OUTPUT, outputKind = InputKind.AUDIO)),
            group = MediaGroup.AUDIO, capabilities = MediaCapabilities(setOf(GenerationMode.MUSIC)))
        f.http.info = Json.parseToJsonElement("""{"Test":{"input":{"required":{"prompt":["STRING"],"seconds":["FLOAT"]}}},"Save":{"input":{"required":{}},"output_node":true,"output":["AUDIO"]}}""").jsonObject
        f.repository.saveHost(f.host); f.repository.defineTemplateByAgent(template)
        val request = f.request.copy(mode = GenerationMode.MUSIC, inputs = emptyList(), specs = GenerationSpecs(durationSeconds = 12))
        f.backend.submit(template.channel(), request)
        val inputs = f.http.calls.last { it.first == "/prompt" }.second!!["prompt"]!!.jsonObject["1"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals(JsonPrimitive(12), inputs["seconds"])
        assertTrue(f.http.uploads.isEmpty())
        try { f.backend.submit(template.channel(), request.copy(params = mapOf("unknown" to JsonPrimitive(1)))); fail("应拒绝未知参数") }
        catch (e: IllegalArgumentException) { assertTrue(e.message.orEmpty().contains("unknown")) }
        assertEquals(1, f.http.calls.count { it.first == "/prompt" })
        assertTrue(f.http.uploads.isEmpty())
    }

    @Test fun historySelectsKindsAcrossVideoKeysAndKeepsAllProductsInStableOrder() {
        val history = Json.parseToJsonElement("""{"p":{"status":{"completed":true},"outputs":{"2":{
            "audio":[{"filename":"a.flac"},{"filename":"b.mp3"}],"videos":[{"filename":"v2.webm"}],
            "images":[{"filename":"preview.png"}],"video":{"filename":"v3.mov"},"gifs":[{"filename":"v1.mp4"},{"filename":"v0.mp4"}]
        }}}}""").jsonObject
        fun files(kind: InputKind) = (parseComfyHistory(history, "p", listOf(ComfySlot("2", "images", SlotKind.OUTPUT, outputKind = kind))) as RemoteStatus.Succeeded).outputs
        fun names(kind: InputKind) = files(kind).map { ("http://comfy.invalid" + it.location).toHttpUrl().queryParameter("filename") }
        assertEquals(listOf("preview.png"), names(InputKind.IMAGE))
        assertEquals(listOf("v1.mp4", "v0.mp4", "v2.webm", "v3.mov"), names(InputKind.VIDEO))
        assertEquals(listOf("a.flac", "b.mp3"), names(InputKind.AUDIO))
        assertTrue(files(InputKind.VIDEO).all { it.kind == InputKind.VIDEO })
        val reordered = JsonObject(history.mapValues { (_, entry) ->
            val e = entry.jsonObject
            val node = e["outputs"]!!.jsonObject["2"]!!.jsonObject
            JsonObject(e + ("outputs" to buildJsonObject { put("2", JsonObject(node.entries.reversed().associate { it.key to it.value })) }))
        })
        assertEquals(files(InputKind.VIDEO), (parseComfyHistory(reordered, "p", listOf(ComfySlot("2", "videos", SlotKind.OUTPUT))) as RemoteStatus.Succeeded).outputs)
    }

    @Test fun downloadsEveryProductWithFilenameExtensionOrKindFallback() = runTest {
        val f = ComfyFixture(); f.prepare()
        val id = f.backend.submit(f.template.channel(), f.request)
        val names = listOf("a.flac", "b.mp3", "c.wav", "d.ogg", "e.mp4", "f.webm", "g.mov", "audio", "video")
        val outputs = names.mapIndexed { index, name -> RemoteOutput("/view?filename=$name&type=output", if (index in 4..6 || index == 8) InputKind.VIDEO else InputKind.AUDIO) }
        val paths = f.backend.download(f.template.channel(), f.request, id, outputs)
        assertEquals(listOf("flac", "mp3", "wav", "ogg", "mp4", "webm", "mov", "wav", "mp4"), paths.map { it.substringAfterLast('.') })
        assertEquals(paths.map { "/$it" }, f.media.saved)
    }

    @Test fun submitsUploadsAndNumericValuesAndCostsNothing() = runTest {
        val f = ComfyFixture(); f.prepare()
        val id = f.backend.submit(f.template.channel(), f.request)
        val ticket = ComfyRemoteTicket.decode(id)
        assertEquals("prompt-1", ticket.promptId)
        assertEquals(listOf("abc"), f.http.uploads.map { it.second })
        val payload = f.http.calls.single { it.first == "/prompt" }.second!!
        assertTrue(f.http.calls.none { it.first == "/object_info" })
        assertTrue(payload["client_id"]!!.jsonPrimitive.content.isNotBlank())
        val inputs = payload.getValue("prompt").jsonObject.getValue("1").jsonObject.getValue("inputs").jsonObject
        assertEquals("提示", inputs["prompt"]!!.jsonPrimitive.content)
        assertTrue(inputs["image"]!!.jsonPrimitive.content.startsWith("infinity/"))
        assertEquals(128, inputs["width"]!!.jsonPrimitive.int)
        assertEquals(160, inputs["height"]!!.jsonPrimitive.int)
        assertEquals(22, inputs["frames"]!!.jsonPrimitive.int)
        assertEquals(42L, inputs["seed"]!!.jsonPrimitive.long)
        assertEquals(0, f.backend.estimate(f.template.channel(), f.request).amount.signum())
        assertEquals(0, f.backend.actualCost(f.template.channel(), emptyMap()).amount.signum())
    }

    @Test fun uploadsFirstLastFramesAndReferenceAudioByTheirDeclaredSlots() = runTest {
        val f = ComfyFixture()
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Test","inputs":{"prompt":"","first":"","last":"","voice":""}},"2":{"class_type":"Save","inputs":{}}}""").jsonObject
        val template = f.template.copy(workflow = graph, slots = listOf(ComfySlot("1", "prompt", SlotKind.PROMPT),
            ComfySlot("1", "first", SlotKind.FIRST_FRAME), ComfySlot("1", "last", SlotKind.LAST_FRAME),
            ComfySlot("1", "voice", SlotKind.REFERENCE_AUDIO), ComfySlot("2", "videos", SlotKind.OUTPUT)), group = MediaGroup.VIDEO,
            capabilities = MediaCapabilities(setOf(GenerationMode.FIRST_LAST_FRAME, GenerationMode.SPEECH)))
        f.repository.saveHost(f.host); f.repository.defineTemplateByAgent(template)
        val firstLast = f.request.copy(mode = GenerationMode.FIRST_LAST_FRAME, specs = GenerationSpecs(), inputs = listOf(
            MediaInput("refs/first.png", InputKind.IMAGE, InputRole.FIRST_FRAME), MediaInput("refs/last.png", InputKind.IMAGE, InputRole.LAST_FRAME)))
        f.backend.submit(template.channel(), firstLast)
        val frames = f.http.calls.last { it.first == "/prompt" }.second!!["prompt"]!!.jsonObject["1"]!!.jsonObject["inputs"]!!.jsonObject
        assertTrue(frames["first"]!!.jsonPrimitive.content.startsWith("infinity/")); assertTrue(frames["last"]!!.jsonPrimitive.content.startsWith("infinity/"))
        f.backend.submit(template.channel(), f.request.copy(mode = GenerationMode.SPEECH, specs = GenerationSpecs(),
            inputs = listOf(MediaInput("voice/ref.wav", InputKind.AUDIO, InputRole.REFERENCE_VOICE))))
        val speech = f.http.calls.last { it.first == "/prompt" }.second!!["prompt"]!!.jsonObject["1"]!!.jsonObject["inputs"]!!.jsonObject
        assertTrue(speech["voice"]!!.jsonPrimitive.content.endsWith(".wav"))
        assertFalse(speech.containsKey("first")); assertFalse(speech.containsKey("last"))
    }

    @Test fun invalidFramesAndPathFailBeforeUpload() = runTest {
        val f = ComfyFixture(); f.prepare()
        expectFailure { f.backend.submit(f.template.channel(), f.request.copy(specs = f.request.specs.copy(frames = 23))) }
        assertTrue(f.http.uploads.isEmpty())
        expectFailure { f.backend.submit(f.template.channel(), f.request.copy(inputs = listOf(f.request.inputs.single().copy(location = "../outside.png")))) }
        assertTrue(f.http.uploads.isEmpty())
    }

    @Test fun queuedRunningAndSuccessAreParsedAndResumedByAnotherBackend() = runTest {
        val f = ComfyFixture(); f.prepare()
        val remote = f.backend.submit(f.template.channel(), f.request)
        val ticket = ComfyRemoteTicket.decode(remote)
        f.http.queue = Json.parseToJsonElement("""{"queue_pending":[[1,"${ticket.promptId}",{},{}]]}""").jsonObject
        assertEquals(RemoteStatus.Running(0f), f.backend.poll(f.template.channel(), remote))
        f.http.queue = Json.parseToJsonElement("""{"queue_running":[[1,"${ticket.promptId}",{},{}]]}""").jsonObject
        assertEquals(RemoteStatus.Running(), f.backend.poll(f.template.channel(), remote))
        f.http.history = success(ticket.promptId)
        val restarted = ComfyBackend(f.repository, f.http, f.media, f.media)
        val state = restarted.poll(f.template.channel(), remote) as RemoteStatus.Succeeded
        assertEquals(1, state.outputs.size)
        assertEquals(InputKind.IMAGE, state.outputs.single().kind)
        val paths = restarted.download(f.template.channel(), f.request, remote, state.outputs)
        assertTrue(paths.single().startsWith("productions/test/takes/comfy-"))
        assertEquals("/${paths.single()}", f.media.saved.single())
    }

    @Test fun failuresAndMissingOutputAreReadableAndDoNotExposeRemoteExceptionText() {
        val slots = listOf(ComfySlot("2", "images", SlotKind.OUTPUT))
        val history = Json.parseToJsonElement("""{"x":{"status":{"completed":false,"status_str":"error","messages":[["execution_error",{"exception_message":"remote-secret"}]]}}}""").jsonObject
        val failed = parseComfyHistory(history, "x", slots) as RemoteStatus.Failed
        assertTrue(failed.message.contains("执行失败")); assertFalse(failed.message.contains("remote-secret"))
        assertTrue(parseComfyHistory(Json.parseToJsonElement("""{"x":{"status":{"completed":true},"outputs":{}}}""").jsonObject, "x", slots) is RemoteStatus.Failed)
        assertTrue(parseComfyHistory(JsonObject(emptyMap()), "x", slots) is RemoteStatus.Running)
    }

    @Test fun pollingTimeoutBecomesRemoteFailed() = runTest {
        val f = ComfyFixture(); f.prepare()
        val remote = f.backend.submit(f.template.channel(), f.request)
        val failingHttp = object : ComfyHttp by f.http {
            override suspend fun get(host: ComfyHost, path: String): JsonObject = throw ComfyFailure("ComfyUI 请求超时，请检查主机连接", true)
        }
        val failed = ComfyBackend(f.repository, failingHttp, f.media, f.media).poll(f.template.channel(), remote) as RemoteStatus.Failed
        assertTrue(failed.retryable); assertTrue(failed.message.contains("超时"))
    }

    @Test fun hostAddressChangeRejectsOldTaskAndDoesNotQueryNewHost() = runTest {
        val f = ComfyFixture(); f.prepare()
        val remote = f.backend.submit(f.template.channel(), f.request)
        f.repository.saveHost(f.host.copy(baseUrl = "http://other.test:8188"))
        val count = f.http.calls.size
        val failed = f.backend.poll(f.template.channel(), remote) as RemoteStatus.Failed
        assertTrue(failed.message.contains("主机地址已变化"))
        assertEquals(count, f.http.calls.size)
    }

    @Test fun serializesSameHostAcrossBackendInstances() = runTest {
        val f = ComfyFixture(); f.prepare()
        val active = AtomicInteger(); val max = AtomicInteger()
        f.http.onPrompt = { val value = active.incrementAndGet(); max.accumulateAndGet(value, ::maxOf); delay(30); active.decrementAndGet() }
        val other = ComfyBackend(f.repository, f.http, f.media, f.media)
        val jobs = (0 until 6).map { n -> async(Dispatchers.Default) { (if (n % 2 == 0) f.backend else other).submit(f.template.channel(), f.request) } }
        jobs.awaitAll()
        assertEquals(1, max.get())
        assertEquals(6, f.http.calls.count { it.first == "/prompt" })
    }

    @Test fun historyFiltersDeclaredOutputNodesAndEncodesViewQuery() {
        val slots = listOf(ComfySlot("2", "audio", SlotKind.OUTPUT))
        val history = Json.parseToJsonElement("""{"p":{"status":{"completed":true,"status_str":"success"},"outputs":{"2":{"audio":[{"filename":"a b.wav","subfolder":"中文","type":"output"}]},"3":{"images":[{"filename":"ignore.png"}]}}}}""").jsonObject
        val state = parseComfyHistory(history, "p", slots) as RemoteStatus.Succeeded
        assertEquals(InputKind.AUDIO, state.outputs.single().kind)
        assertTrue(state.outputs.single().location.contains("a%20b.wav"))
        validateComfyView(state.outputs.single().location)
        assertThrows(IllegalArgumentException::class.java) { validateComfyView("https://other.test/private") }
        assertThrows(IllegalArgumentException::class.java) { validateComfyView("/view?filename=..%2Fx&type=output") }
    }
    private fun success(id: String) = Json.parseToJsonElement("""{"$id":{"status":{"completed":true,"status_str":"success"},"outputs":{"2":{"images":[{"filename":"out.png","subfolder":"test","type":"output"}]}}}}""").jsonObject
    private suspend fun expectFailure(action: suspend () -> Unit) {
        try { action(); fail("应拒绝无效输入") } catch (_: IllegalArgumentException) { }
    }
}
