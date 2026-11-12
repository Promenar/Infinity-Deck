package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyProgressTest {
    @Test fun websocketProgressIsBoundToPromptAndRejectsMalformedEvents() {
        assertEquals(ComfyProgressEvent("p", 0.5f), parseComfyProgressEvent("""{"type":"progress","data":{"prompt_id":"p","value":5,"max":10}}"""))
        assertEquals(ComfyProgressEvent("p", null), parseComfyProgressEvent("""{"type":"executing","data":{"prompt_id":"p","node":"2"}}"""))
        assertNull(parseComfyProgressEvent("""{"type":"progress","data":{"prompt_id":"p","value":5,"max":0}}"""))
        assertNull(parseComfyProgressEvent("""{"type":"progress","data":{"value":5,"max":10}}"""))
        assertNull(parseComfyProgressEvent("invalid"))
        assertEquals(ComfyProgressEvent("p", null, true), parseComfyProgressEvent("""{"type":"executing","data":{"prompt_id":"p","node":null}}"""))
    }
    @Test fun backendUsesRealProgressAndClosesProgressOnCompletion() = runTest {
        val f = ComfyFixture(); f.prepare()
        val tracker = object : ComfyProgress {
            var closed = false
            override suspend fun connect(host: ComfyHost, clientId: String) = Unit
            override fun value(clientId: String, promptId: String) = 0.75f
            override fun close(clientId: String) { closed = true }
        }
        val backend = ComfyBackend(f.repository, f.http, f.media, f.media, tracker)
        val remote = backend.submit(f.template.channel(), f.request)
        val prompt = ComfyRemoteTicket.decode(remote).promptId
        f.http.queue = Json.parseToJsonElement("""{"queue_running":[[0,"$prompt"]]}""").jsonObject
        assertEquals(RemoteStatus.Running(0.75f), backend.poll(f.template.channel(), remote))
        f.http.history = Json.parseToJsonElement("""{"$prompt":{"status":{"completed":true},"outputs":{"2":{"images":[{"filename":"a.png"}]}}}}""").jsonObject
        assertTrue(backend.poll(f.template.channel(), remote) is RemoteStatus.Succeeded)
        assertTrue(tracker.closed)
    }
}
