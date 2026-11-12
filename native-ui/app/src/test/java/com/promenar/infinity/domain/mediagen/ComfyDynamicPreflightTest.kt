package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyDynamicPreflightTest {
    private val info = Json.parseToJsonElement("""{
      "Encode":{"input":{"optional":{"ref_images":["COMFY_AUTOGROW_V3",{"template":{"prefix":"ref_image_","min":0,"max":2,"input":{"required":{"image":["IMAGE"]}}}}]}}},
      "Load":{"input":{"required":{"image":["STRING"]}},"output":["IMAGE"]}
    }""").jsonObject
    private fun template(inputs: String): ComfyTemplate {
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Encode","inputs":$inputs},"2":{"class_type":"Load","inputs":{"image":"a.png"}}}""").jsonObject
        return ComfyTemplate("dynamic", graph, emptyList())
    }
    @Test fun acceptsFlattenedKeysAndRejectsNestedOrMissingLinks() {
        assertEquals(PreflightStatus.PASS, preflightComfy(template("""{"ref_images.ref_image_0":["2",0]}"""), info).status)
        assertEquals(PreflightStatus.MISSING, preflightComfy(template("""{"ref_images":{"ref_image_0":["2",0]}}"""), info).status)
        assertEquals(PreflightStatus.MISSING, preflightComfy(template("""{"ref_images.ref_image_0":["absent",0]}"""), info).status)
        assertEquals(PreflightStatus.MISSING, preflightComfy(template("""{"ref_images.ref_image_0":["2",1]}"""), info).status)
        assertEquals(PreflightStatus.MISSING, preflightComfy(template("""{"ref_images.ref_image_0":"a.png"}"""), info).status)
    }
    @Test fun variableSlotsCanStartAtOneAndUploadInputsMustBeDistinct() {
        assertEquals(listOf("images.image_1", "images.image_2"), ComfySlot("1", "images.image_1", SlotKind.REFERENCE_IMAGES, 2).names())
        val graph = template("""{"ref_images.ref_image_0":["2",0],"ref_images.ref_image_1":["2",0]}""").workflow
        assertThrows(IllegalArgumentException::class.java) {
            ComfyTemplate("shared", graph, listOf(ComfySlot("1", "ref_images.ref_image_0", SlotKind.REFERENCE_IMAGES, 2,
                uploadTargets = listOf(ComfyUploadTarget("2", "image"), ComfyUploadTarget("2", "image")))))
        }
    }
    @Test fun unknownDynamicSchemaRequiresReview() {
        val unknown = Json.parseToJsonElement(info.toString().replace("COMFY_AUTOGROW_V3", "COMFY_DYNAMICSLOT_V3")).jsonObject
        assertEquals(PreflightStatus.REVIEW, preflightComfy(template("{}"), unknown).status)
    }
    @Test fun fillsAllScalarAndMediaSlotsAndPreservesOutputNode() {
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Test","inputs":{"prompt":"","refs_0":"","refs_1":"","first":"","last":"","audio":"","w":32,"h":32,"length":5,"seed":0}},"2":{"class_type":"Save","inputs":{}}}""").jsonObject
        val kinds = listOf(SlotKind.PROMPT to "prompt", SlotKind.REFERENCE_IMAGES to "refs_0", SlotKind.FIRST_FRAME to "first", SlotKind.LAST_FRAME to "last",
            SlotKind.REFERENCE_AUDIO to "audio", SlotKind.WIDTH to "w", SlotKind.HEIGHT to "h", SlotKind.FRAMES to "length", SlotKind.SEED to "seed")
        val template = ComfyTemplate("all", graph, kinds.map { (kind, input) -> ComfySlot("1", input, kind, if (kind == SlotKind.REFERENCE_IMAGES) 2 else 1) } + ComfySlot("2", "videos", SlotKind.OUTPUT), frameBase = 5, frameStep = 17, maxFrames = 39)
        val values = mapOf(SlotKind.PROMPT to listOf("内容"), SlotKind.REFERENCE_IMAGES to listOf("r0.png", "r1.png"), SlotKind.FIRST_FRAME to listOf("first.png"),
            SlotKind.LAST_FRAME to listOf("last.png"), SlotKind.REFERENCE_AUDIO to listOf("voice.wav"), SlotKind.WIDTH to listOf("33"), SlotKind.HEIGHT to listOf("64"),
            SlotKind.FRAMES to listOf("39"), SlotKind.SEED to listOf(Long.MAX_VALUE.toString()))
        val filled = fillComfyWorkflow(template, values)
        val inputs = filled.getValue("1").jsonObject.getValue("inputs").jsonObject
        assertEquals("first.png", inputs["first"]!!.jsonPrimitive.content)
        assertEquals("last.png", inputs["last"]!!.jsonPrimitive.content)
        assertEquals("voice.wav", inputs["audio"]!!.jsonPrimitive.content)
        assertEquals(64, inputs["w"]!!.jsonPrimitive.int); assertEquals(64, inputs["h"]!!.jsonPrimitive.int)
        assertEquals(Long.MAX_VALUE, inputs["seed"]!!.jsonPrimitive.long)
        assertEquals(graph["2"], filled["2"])
        assertThrows(IllegalArgumentException::class.java) { fillComfyWorkflow(template, values + (SlotKind.FRAMES to listOf("56"))) }
        assertThrows(IllegalArgumentException::class.java) { fillComfyWorkflow(template, values + (SlotKind.WIDTH to listOf(Int.MAX_VALUE.toString()))) }
    }
    @Test fun distributesReferencesAcrossSlotsAndDoesNotKeepOldReferences() {
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Load","inputs":{"image":"old-a"}},"2":{"class_type":"Load","inputs":{"image":"old-b"}}}""").jsonObject
        val template = ComfyTemplate("multi", graph, listOf(ComfySlot("1", "image", SlotKind.REFERENCE_IMAGES), ComfySlot("2", "image", SlotKind.REFERENCE_IMAGES)))
        val filled = fillComfyWorkflow(template, mapOf(SlotKind.REFERENCE_IMAGES to listOf("a", "b")))
        assertEquals("a", filled["1"]!!.jsonObject["inputs"]!!.jsonObject["image"]!!.jsonPrimitive.content)
        assertEquals("b", filled["2"]!!.jsonObject["inputs"]!!.jsonObject["image"]!!.jsonPrimitive.content)
        val empty = fillComfyWorkflow(template, emptyMap())
        assertTrue(empty.values.all { it.jsonObject["inputs"]!!.jsonObject.isEmpty() })
    }
}
