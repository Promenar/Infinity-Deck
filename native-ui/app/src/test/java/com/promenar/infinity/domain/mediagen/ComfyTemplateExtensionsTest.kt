package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyTemplateExtensionsTest {
    private val graph = Json.parseToJsonElement("""{"1":{"class_type":"Process","inputs":{"video":"old.mp4","mode":"slow","steps":10,"gain":0.5,"fast":false,"seconds":5}},"2":{"class_type":"Save","inputs":{}}}""").jsonObject
    private val parameters = listOf(
        TemplateParam("mode", "模式", TemplateParamType.ENUM, JsonPrimitive("slow"), options = listOf("slow", "fast")),
        TemplateParam("steps", "步数", TemplateParamType.NUMBER, JsonPrimitive(10), min = 1.0, max = 30.0, integer = true),
        TemplateParam("gain", "强度", TemplateParamType.NUMBER, JsonPrimitive(0.5), min = 0.0, max = 1.0),
        TemplateParam("fast", "加速", TemplateParamType.BOOLEAN, JsonPrimitive(false)),
    )
    private val template = ComfyTemplate("derive", graph,
        listOf(ComfySlot("1", "video", SlotKind.INPUT_VIDEO), ComfySlot("2", "videos", SlotKind.OUTPUT, outputKind = InputKind.VIDEO)) +
            parameters.map { ComfySlot("1", it.key, SlotKind.PARAM, param = it.key) },
        capabilities = MediaCapabilities(setOf(GenerationMode.REFERENCE)), group = MediaGroup.VIDEO,
        role = TemplateRole.DERIVE, operation = TemplateOperation("超分"), params = parameters)

    @Test fun acceptsGenerationDerivationAndMusicContracts() {
        assertEquals(1, template.channel().capabilities.maxVideos)
        val generate = ComfyTemplate("image", graph, emptyList(), capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)))
        assertEquals(TemplateRole.GENERATE, generate.role)
        assertEquals(0, generate.channel().capabilities.maxVideos)
        val music = generate.copy(group = MediaGroup.AUDIO, capabilities = MediaCapabilities(setOf(GenerationMode.MUSIC)),
            slots = listOf(ComfySlot("1", "seconds", SlotKind.DURATION), ComfySlot("2", "audio", SlotKind.OUTPUT, outputKind = InputKind.AUDIO)))
        assertEquals(MediaGroup.AUDIO, music.channel().group)
    }

    @Test fun rejectsInvalidRoleOperationAndParameterBindingsWithFieldNames() {
        invalid("operation") { template.copy(operation = null) }
        invalid("operation") { template.copy(role = TemplateRole.GENERATE) }
        invalid("INPUT_VIDEO") { template.copy(slots = template.slots.filterNot { it.kind == SlotKind.INPUT_VIDEO }) }
        invalid("INPUT_VIDEO") { template.copy(slots = template.slots + ComfySlot("1", "other", SlotKind.INPUT_VIDEO)) }
        invalid("steps") { template.copy(slots = template.slots.filterNot { it.param == "steps" }) }
        invalid("steps") { template.copy(slots = template.slots + ComfySlot("1", "other", SlotKind.PARAM, param = "steps")) }
        invalid("slots.param") { template.copy(slots = template.slots + ComfySlot("1", "other", SlotKind.PARAM, param = "unknown")) }
        invalid("params.key") { template.copy(params = parameters + parameters.first()) }
        invalid("slots.param") { ComfySlot("1", "steps", SlotKind.PARAM) }
        invalid("slots.param") { ComfySlot("1", "video", SlotKind.INPUT_VIDEO, param = "steps") }
        invalid("容量") { ComfySlot("1", "video", SlotKind.INPUT_VIDEO, capacity = 2) }
        invalid("operation.label") { TemplateOperation(" ") }
        invalid("operation.label") { TemplateOperation("长".repeat(21)) }
        invalid("params") { template.copy(params = parameters + List(3) { parameters.first().copy(key = "extra_$it") }) }
    }

    @Test fun rejectsInvalidParameterDeclarationsAndDefaults() {
        invalid("key") { parameters[0].copy(key = "Bad") }
        invalid("label") { parameters[0].copy(label = "") }
        invalid("options") { parameters[0].copy(options = listOf("slow", "slow")) }
        invalid("options") { parameters[0].copy(options = listOf("slow")) }
        invalid("options") { parameters[1].copy(options = listOf("a", "b")) }
        invalid("min/max/integer") { parameters[0].copy(integer = true) }
        invalid("min/max") { parameters[1].copy(min = 40.0) }
        invalid("min/max") { parameters[1].copy(max = Double.POSITIVE_INFINITY) }
        invalid("default") { parameters[0].copy(default = JsonPrimitive("missing")) }
        invalid("default") { parameters[0].copy(default = JsonPrimitive(1)) }
        invalid("default") { parameters[1].copy(default = JsonPrimitive("10")) }
        invalid("default") { parameters[1].copy(default = JsonPrimitive(1.5)) }
        invalid("default") { parameters[1].copy(default = JsonPrimitive(31)) }
        invalid("default") { parameters[3].copy(default = JsonPrimitive("false")) }
        invalid("default") { parameters[3].copy(default = JsonNull) }
    }

    @Test fun resolvesDefaultsAndTypedOverridesAndRejectsInvalidValues() {
        assertEquals(parameters.associate { it.key to it.default }, template.resolveParams(emptyMap()))
        val overrides = mapOf("mode" to JsonPrimitive("fast"), "steps" to JsonPrimitive(20), "fast" to JsonPrimitive(true))
        assertEquals(parameters.associate { it.key to it.default } + overrides, template.resolveParams(overrides))
        invalid("unknown") { template.resolveParams(mapOf("unknown" to JsonPrimitive(1))) }
        invalid("mode") { template.resolveParams(mapOf("mode" to JsonPrimitive("other"))) }
        invalid("steps") { template.resolveParams(mapOf("steps" to JsonPrimitive("20"))) }
        invalid("steps") { template.resolveParams(mapOf("steps" to JsonPrimitive(false))) }
        invalid("steps") { template.resolveParams(mapOf("steps" to JsonPrimitive(0))) }
        invalid("steps") { template.resolveParams(mapOf("steps" to JsonPrimitive(31))) }
        invalid("整数") { template.resolveParams(mapOf("steps" to JsonPrimitive(2.25))) }
        invalid("fast") { template.resolveParams(mapOf("fast" to JsonPrimitive(1))) }
    }

    @Test fun fillsVideoNameTypedParametersAndDuration() {
        val t = template.copy(slots = template.slots + ComfySlot("1", "seconds", SlotKind.DURATION))
        val filled = fillComfyWorkflow(t, mapOf(SlotKind.INPUT_VIDEO to listOf("infinity/source.webm"), SlotKind.DURATION to listOf("12.5")),
            mapOf("steps" to JsonPrimitive(20.0), "mode" to JsonPrimitive("fast"), "fast" to JsonPrimitive(true)))
        val inputs = filled["1"]!!.jsonObject["inputs"]!!.jsonObject
        assertEquals(JsonPrimitive("infinity/source.webm"), inputs["video"])
        assertEquals(JsonPrimitive("fast"), inputs["mode"])
        assertEquals("20", inputs["steps"]!!.toString())
        assertEquals(JsonPrimitive(true), inputs["fast"])
        assertEquals(JsonPrimitive(0.5), inputs["gain"])
        assertEquals(JsonPrimitive(12.5), inputs["seconds"])
        assertEquals(graph["2"], filled["2"])
        invalid("DURATION") { fillComfyWorkflow(t, mapOf(SlotKind.DURATION to listOf("0"))) }
    }

    @Test fun videoUploadTargetsPreserveLinks() {
        val t = template.copy(workflow = Json.parseToJsonElement("""{"1":{"class_type":"Process","inputs":{"video":["3",0],"mode":"slow","steps":10,"gain":0.5,"fast":false}},"3":{"class_type":"FileInput","inputs":{"file":"old.mov"}}}""").jsonObject,
            slots = template.slots.map { if (it.kind == SlotKind.INPUT_VIDEO) it.copy(uploadTargets = listOf(ComfyUploadTarget("3", "file"))) else it })
        val filled = fillComfyWorkflow(t, mapOf(SlotKind.INPUT_VIDEO to listOf("infinity/source.mov")))
        assertEquals(JsonPrimitive("infinity/source.mov"), filled["3"]!!.jsonObject["inputs"]!!.jsonObject["file"])
        assertEquals(JsonArray(listOf(JsonPrimitive("3"), JsonPrimitive(0))), filled["1"]!!.jsonObject["inputs"]!!.jsonObject["video"])
    }

    @Test fun fingerprintCanonicalizesKeysAndExcludesDisplayIdentity() {
        val first = template.fingerprint()
        assertTrue(first.matches(Regex("[0-9a-f]{64}")))
        assertEquals(first, template.copy().fingerprint())
        fun reversed(value: JsonElement): JsonElement = when (value) {
            is JsonObject -> JsonObject(value.entries.reversed().associate { it.key to reversed(it.value) })
            is JsonArray -> JsonArray(value.map(::reversed))
            else -> value
        }
        assertEquals(first, template.copy(workflow = reversed(graph).jsonObject, name = "显示名称", locked = true, hostId = "other").fingerprint())
        assertNotEquals(first, template.copy(workflow = JsonObject(graph + ("extra" to graph.getValue("2")))).fingerprint())
        assertNotEquals(first, template.copy(slots = template.slots.map { if (it.kind == SlotKind.INPUT_VIDEO) it.copy(input = "file") else it }).fingerprint())
        assertNotEquals(first, template.copy(params = parameters.map { if (it.key == "steps") it.copy(default = JsonPrimitive(12)) else it }).fingerprint())
        assertNotEquals(first, template.copy(operation = TemplateOperation("补帧")).fingerprint())
    }

    private fun invalid(field: String, action: () -> Unit) {
        val error = assertThrows(IllegalArgumentException::class.java) { action() }
        assertTrue(error.message, error.message.orEmpty().contains(field))
    }
}
