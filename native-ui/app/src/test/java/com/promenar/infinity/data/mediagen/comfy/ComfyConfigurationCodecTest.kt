package com.promenar.infinity.data.mediagen.comfy

import com.promenar.infinity.domain.mediagen.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyConfigurationCodecTest {
    @Test fun readsVersionOneWithGenerationDefaultsAndWritesVersionTwo() = runTest {
        val f = ComfyFixture(); f.prepare()
        val current = Json.parseToJsonElement(ComfyConfigurationCodec.encode(f.storage.read())).jsonObject
        val legacy = JsonObject(current + mapOf("version" to JsonPrimitive(1), "templates" to JsonArray(current["templates"]!!.jsonArray.map { state ->
            val t = state.jsonObject["template"]!!.jsonObject
            val old = JsonObject((t - setOf("role", "operation", "params")) + ("slots" to JsonArray(t["slots"]!!.jsonArray.map { JsonObject(it.jsonObject - "param") })))
            JsonObject(state.jsonObject + ("template" to old))
        })))
        val restored = ComfyConfigurationCodec.decode(legacy.toString())
        val template = restored.templates.single().template
        assertEquals(TemplateRole.GENERATE, template.role)
        assertNull(template.operation)
        assertTrue(template.params.isEmpty())
        assertEquals(f.storage.read(), restored)
        val rewritten = Json.parseToJsonElement(ComfyConfigurationCodec.encode(restored)).jsonObject
        assertEquals(2, rewritten["version"]!!.jsonPrimitive.int)
    }

    @Test fun versionTwoRoundTripsVideoOperationAndTypedParams() {
        val f = ComfyFixture()
        val base = f.template.copy(role = TemplateRole.DERIVE, operation = TemplateOperation("补帧"), group = MediaGroup.VIDEO,
            slots = listOf(ComfySlot("1", "video", SlotKind.INPUT_VIDEO, uploadTargets = listOf(ComfyUploadTarget("3", "file"))),
                ComfySlot("1", "steps", SlotKind.PARAM, param = "steps"), ComfySlot("2", "videos", SlotKind.OUTPUT, outputKind = InputKind.VIDEO)),
            params = listOf(TemplateParam("steps", "步数", TemplateParamType.NUMBER, JsonPrimitive(10), min = 1.0, max = 30.0, integer = true)))
        val template = base.copy(capabilities = base.channel().capabilities)
        val configuration = ComfyConfiguration(listOf(f.host), listOf(ComfyTemplateState(template)))
        val encoded = ComfyConfigurationCodec.encode(configuration)
        assertEquals(2, Json.parseToJsonElement(encoded).jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals(configuration, ComfyConfigurationCodec.decode(encoded))
        assertEquals(template, ComfyConfigurationCodec.decodeTemplate(ComfyConfigurationCodec.encodeTemplate(template)))
        assertTrue(ComfyConfigurationCodec.encodeTemplate(template)["params"]!!.jsonArray.single().jsonObject["default"]!!.jsonPrimitive.intOrNull == 10)
    }

    @Test fun invalidParamDefaultNamesItsIndexedField() {
        val f = ComfyFixture()
        val raw = JsonObject(ComfyConfigurationCodec.encodeTemplate(f.template) + ("params" to Json.parseToJsonElement("""[
            {"key":"fast","label":"加速","type":"BOOLEAN","default":true},
            {"key":"mode","label":"模式","type":"ENUM","default":"other","options":["a","b"]}
        ]""")))
        val error = assertThrows(IllegalArgumentException::class.java) { ComfyConfigurationCodec.decodeTemplate(raw) }
        assertEquals("params[1].default: 不在 options 内", error.message)
    }
}
