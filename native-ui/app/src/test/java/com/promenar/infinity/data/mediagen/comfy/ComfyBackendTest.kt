package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ComfyBackendTest {
    @Test fun submitsUploadsAndNumericValuesAndCostsNothing() = runTest {
        val f = ComfyFixture(); f.prepare()
        val id = f.backend.submit(f.template.channel(), f.request)
        val ticket = ComfyRemoteTicket.decode(id)
        assertEquals("prompt-1", ticket.promptId)
        assertEquals(listOf("abc"), f.http.uploads.map { it.second })
        val payload = f.http.calls.single { it.first == "/prompt" }.second!!
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
