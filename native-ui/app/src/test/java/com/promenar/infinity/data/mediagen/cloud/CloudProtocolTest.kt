package com.promenar.infinity.data.mediagen.cloud

import com.promenar.infinity.data.mediagen.*
import com.promenar.infinity.data.model.*
import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CloudProtocolTest {
    private fun request(mode: GenerationMode, inputs: List<MediaInput> = emptyList()) = MediaGenerationRequest("c", mode, "图1和图2", inputs, "src", GenerationSpecs("768P", 5))
    @Test fun seedanceFramesAndAudioSwitch() {
        val body = seedanceCreateBody("ep-user", request(GenerationMode.FIRST_LAST_FRAME, listOf(
            MediaInput("https://a.test/first.png", InputKind.IMAGE, InputRole.FIRST_FRAME),
            MediaInput("https://a.test/last.png", InputKind.IMAGE, InputRole.LAST_FRAME))), false)
        assertFalse(body.getValue("generate_audio").jsonPrimitive.boolean)
        assertEquals("first_frame", body.getValue("content").jsonArray[1].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("last_frame", body.getValue("content").jsonArray[2].jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals(5, body.getValue("duration").jsonPrimitive.int)
    }
    @Test fun minimaxLegacyModes() {
        val refs = listOf(MediaInput("https://a.test/a.png", InputKind.IMAGE, InputRole.REFERENCE))
        val b = miniMaxVideoBody("S2V-01", request(GenerationMode.REFERENCE, refs).copy(specs = GenerationSpecs()), CloudMediaApi.MINIMAX_VIDEO_V1)
        assertEquals("https://a.test/a.png", b.getValue("subject_reference").jsonArray[0].jsonObject.getValue("image").jsonArray[0].jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { miniMaxVideoBody("S2V-01", request(GenerationMode.REFERENCE, refs + refs).copy(specs = GenerationSpecs()), CloudMediaApi.MINIMAX_VIDEO_V1) }
        val frames = refs.map { it.copy(role = InputRole.FIRST_FRAME) } + refs.map { it.copy(role = InputRole.LAST_FRAME) }
        val f = miniMaxVideoBody("MiniMax-Hailuo-02", request(GenerationMode.FIRST_LAST_FRAME, frames).copy(specs = GenerationSpecs("768P", 6)), CloudMediaApi.MINIMAX_VIDEO_V1)
        assertTrue(f.containsKey("first_frame_image")); assertTrue(f.containsKey("last_frame_image"))
        assertFalse(miniMaxVideoBody("m", request(GenerationMode.TEXT), CloudMediaApi.MINIMAX_VIDEO_V1).containsKey("content"))
    }
    @Test fun minimaxV2PreservesAllReferences() {
        val refs = listOf("one", "two").map { MediaInput("https://a.test/$it.png", InputKind.IMAGE, InputRole.REFERENCE) }
        val b = miniMaxVideoBody("MiniMax-H3", request(GenerationMode.REFERENCE, refs), CloudMediaApi.MINIMAX_VIDEO_V2)
        assertEquals("图1和图2", b.getValue("content").jsonArray[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("https://a.test/two.png", b.getValue("content").jsonArray[2].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
    }
    @Test fun speechRequiresVoiceAndRejectsUnsupportedReferenceVoice() {
        val options = CloudMediaOptions(voiceId = "voice-id")
        assertEquals("voice-id", miniMaxSpeechBody("speech-2.8-hd", request(GenerationMode.SPEECH), options).getValue("voice_setting").jsonObject.getValue("voice_id").jsonPrimitive.content)
        assertThrows(IllegalArgumentException::class.java) { miniMaxSpeechBody("m", request(GenerationMode.SPEECH), CloudMediaOptions()) }
    }
    @Test fun statusParsingDoesNotTreatUnknownAsRunning() {
        fun json(s: String) = Json.parseToJsonElement(s).jsonObject
        assertTrue(parseMiniMaxStatus(json("""{"status":"Processing"}"""), false, false) is RemoteStatus.Running)
        assertTrue(parseMiniMaxStatus(json("""{"status":"Expired"}"""), false, true) is RemoteStatus.Failed)
        assertTrue(parseMiniMaxStatus(json("""{"task":{"status":"succeeded","content":{"url":"https://a.test/v.mp4"},"usage":{"output_seconds":5}}}"""), true, false) is RemoteStatus.Succeeded)
        assertTrue(parseMiniMaxStatus(json("""{"status":"Success"}"""), false, false) is RemoteStatus.Failed)
        assertTrue(parseMiniMaxStatus(json("""{"status":"future-state"}"""), false, false) is RemoteStatus.Failed)
        assertEquals(SeedanceRemoteState.FAILED, parseSeedanceStatus(json("""{"status":"expired"}""")).state)
    }
}
