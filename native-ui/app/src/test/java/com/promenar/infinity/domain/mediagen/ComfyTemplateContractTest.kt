package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyTemplateContractTest {
    private val workflow = Json.parseToJsonElement("""{"1":{"class_type":"Loader","inputs":{"model":"a.safetensors","ref_image_0":"","ref_image_1":"","width":0,"frames":0}}}""").jsonObject
    private val template = ComfyTemplate("x", workflow, listOf(
        ComfySlot("1", "ref_image_0", SlotKind.REFERENCE_IMAGES, 2),
        ComfySlot("1", "width", SlotKind.WIDTH),
        ComfySlot("1", "frames", SlotKind.FRAMES),
    ), frameBase = 5, frameStep = 17, models = listOf(ComfyModelSlot("1", "model")))

    @Test fun fillsReferencesInOrderAndAlignsWidthAndFrames() {
        val filled = fillComfyWorkflow(template, mapOf(SlotKind.REFERENCE_IMAGES to listOf("a.png", "b.png"), SlotKind.WIDTH to listOf("101"), SlotKind.FRAMES to listOf("22")))
        val inputs = filled.getValue("1").jsonObject.getValue("inputs").jsonObject
        assertEquals("a.png", inputs.getValue("ref_image_0").jsonPrimitive.content)
        assertEquals("b.png", inputs.getValue("ref_image_1").jsonPrimitive.content)
        assertEquals(128, inputs.getValue("width").jsonPrimitive.int)
        assertEquals(22, inputs.getValue("frames").jsonPrimitive.int)
    }

    @Test fun rejectsInvalidFrameCountAndTooManyImages() {
        assertThrows(IllegalArgumentException::class.java) { fillComfyWorkflow(template, mapOf(SlotKind.FRAMES to listOf("23"))) }
        assertThrows(IllegalArgumentException::class.java) { fillComfyWorkflow(template, mapOf(SlotKind.REFERENCE_IMAGES to listOf("a", "b", "c"))) }
    }

    @Test fun preflightDistinguishesMissingAndUnknownModelLists() {
        val info = Json.parseToJsonElement("""{"Loader":{"input":{"required":{"model":[["a.safetensors"],{}]}}}}""").jsonObject
        assertEquals(PreflightStatus.PASS, preflightComfy(template, info).status)
        assertEquals(PreflightStatus.MISSING, preflightComfy(template, JsonObject(emptyMap())).status)
        val unknown = Json.parseToJsonElement("""{"Loader":{"input":{"required":{"model":["STRING",{}]}}}}""").jsonObject
        assertEquals(PreflightStatus.REVIEW, preflightComfy(template, unknown).status)
    }

    @Test fun lockedTemplateRequiresUserToUnlockBeforeEditing() {
        val locked = template.copy(locked = true)
        assertThrows(IllegalStateException::class.java) { locked.updateByAgent(template) }
        assertThrows(IllegalStateException::class.java) { locked.unlock(userApproved = false) }
        assertFalse(locked.unlock(userApproved = true).locked)
    }

    @Test fun explicitModelDeclarationDoesNotDependOnFieldName() {
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Loader","inputs":{"weights":"missing.bin"}}}""").jsonObject
        val t = ComfyTemplate("weights", graph, emptyList(), models = listOf(ComfyModelSlot("1", "weights")))
        val info = Json.parseToJsonElement("""{"Loader":{"input":{"required":{"weights":[["ok.bin"],{}]}}}}""").jsonObject
        assertEquals(PreflightStatus.MISSING, preflightComfy(t, info).status)
        assertEquals(PreflightStatus.PASS, preflightComfy(t.copy(models = emptyList()), info).status)
    }

    @Test fun dynamicReferencesPreserveLinksAndRemoveUnusedMembers() {
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Encode","inputs":{"ref_images.ref_image_0":["2",0],"ref_images.ref_image_1":["3",0]}},"2":{"class_type":"LoadImage","inputs":{"image":"old.png"}},"3":{"class_type":"LoadImage","inputs":{"image":"old2.png"}}}""").jsonObject
        val t = ComfyTemplate("refs", graph, listOf(ComfySlot("1", "ref_images.ref_image_0", SlotKind.REFERENCE_IMAGES, 2,
            uploadTargets = listOf(ComfyUploadTarget("2", "image"), ComfyUploadTarget("3", "image")))))
        val filled = fillComfyWorkflow(t, mapOf(SlotKind.REFERENCE_IMAGES to listOf("input/a.png")))
        assertEquals(JsonArray(listOf(JsonPrimitive("2"), JsonPrimitive(0))), filled.getValue("1").jsonObject.getValue("inputs").jsonObject["ref_images.ref_image_0"])
        assertFalse(filled.getValue("1").jsonObject.getValue("inputs").jsonObject.containsKey("ref_images.ref_image_1"))
        assertEquals("input/a.png", filled.getValue("2").jsonObject.getValue("inputs").jsonObject.getValue("image").jsonPrimitive.content)
        val broken = t.copy(slots = listOf(t.slots.single().copy(uploadTargets = listOf(ComfyUploadTarget("3", "image"), ComfyUploadTarget("2", "image")))))
        assertThrows(IllegalArgumentException::class.java) { fillComfyWorkflow(broken, mapOf(SlotKind.REFERENCE_IMAGES to listOf("x"))) }
    }
}
