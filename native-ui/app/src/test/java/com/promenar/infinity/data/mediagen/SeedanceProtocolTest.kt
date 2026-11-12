package com.promenar.infinity.data.mediagen

import com.promenar.infinity.domain.mediagen.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class SeedanceProtocolTest {
    @Test fun preservesReferenceOrderAndRoles() {
        val request = MediaGenerationRequest("c", GenerationMode.REFERENCE, "图1与图2", listOf(
            MediaInput("https://example.test/1.png", InputKind.IMAGE, InputRole.REFERENCE),
            MediaInput("https://example.test/2.png", InputKind.IMAGE, InputRole.REFERENCE),
        ), "source")
        val content = seedanceCreateBody("model-id", request).getValue("content").jsonArray
        assertEquals("图1与图2", content[0].jsonObject.getValue("text").jsonPrimitive.content)
        assertEquals("https://example.test/1.png", content[1].jsonObject.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content)
        assertEquals("reference_image", content[2].jsonObject.getValue("role").jsonPrimitive.content)
    }

    @Test fun parsesTerminalResponseAndActualUsage() {
        val result = parseSeedanceStatus(Json.parseToJsonElement("""{"status":"succeeded","content":{"video_url":"https://example.test/a.mp4"},"usage":{"total_tokens":123}}""").jsonObject)
        assertEquals(SeedanceRemoteState.SUCCEEDED, result.state)
        assertEquals("https://example.test/a.mp4", result.outputUrl)
        assertEquals(123L, result.totalTokens)
        assertThrows(IllegalArgumentException::class.java) { parseSeedanceStatus(Json.parseToJsonElement("""{"status":"succeeded"}""").jsonObject) }
    }
}
