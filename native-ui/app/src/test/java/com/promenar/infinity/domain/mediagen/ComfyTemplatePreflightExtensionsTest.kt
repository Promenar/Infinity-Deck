package com.promenar.infinity.domain.mediagen

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfyTemplatePreflightExtensionsTest {
    private val graph = Json.parseToJsonElement("""{"1":{"class_type":"Process","inputs":{"video":["3",0],"mode":"slow","steps":10}},"2":{"class_type":"Save","inputs":{}},"3":{"class_type":"FileInput","inputs":{"file":"source.mp4"}}}""").jsonObject
    private val info = Json.parseToJsonElement("""{
        "Process":{"input":{"required":{"video":["VIDEO"],"mode":[["slow","fast"]],"steps":["INT",{"min":1,"max":30}]}}},
        "Save":{"input":{"required":{}},"output_node":true,"output":["IMAGE"]},
        "FileInput":{"input":{"required":{"file":["STRING"]}},"output":["VIDEO"]}
    }""").jsonObject
    private val params = listOf(
        TemplateParam("mode", "模式", TemplateParamType.ENUM, JsonPrimitive("slow"), options = listOf("slow", "fast")),
        TemplateParam("steps", "步数", TemplateParamType.NUMBER, JsonPrimitive(10), min = 1.0, max = 30.0, integer = true),
    )
    private val template = ComfyTemplate("process", graph, listOf(
        ComfySlot("1", "video", SlotKind.INPUT_VIDEO, uploadTargets = listOf(ComfyUploadTarget("3", "file"))),
        ComfySlot("1", "mode", SlotKind.PARAM, param = "mode"), ComfySlot("1", "steps", SlotKind.PARAM, param = "steps"),
        ComfySlot("2", "videos", SlotKind.OUTPUT, outputKind = InputKind.VIDEO)),
        role = TemplateRole.DERIVE, operation = TemplateOperation("补帧"), params = params)

    @Test fun acceptsVideoImageSequenceOutputAndValidParameterRanges() {
        assertEquals(PreflightStatus.PASS, preflightComfy(template, info).status)
        assertEquals(PreflightStatus.PASS, preflightComfy(template, outputInfo("VIDEO")).status)
    }

    @Test fun videoUploadChecksHostNodeAndInputSchemaAndWorkflowLink() {
        val missingNode = preflightComfy(template, JsonObject(info - "FileInput"))
        assertTrue(missingNode.missing.any { it.contains("节点") })
        val absentInput = preflightComfy(template, Json.parseToJsonElement(info.toString().replace("\"file\":[\"STRING\"]", "\"other\":[\"STRING\"]")).jsonObject)
        assertTrue(absentInput.missing.contains("主机输入 3.file"))
        val wrongTarget = template.copy(slots = template.slots.map { if (it.kind == SlotKind.INPUT_VIDEO) it.copy(uploadTargets = listOf(ComfyUploadTarget("3", "absent"))) else it })
        assertTrue(preflightComfy(wrongTarget, info).missing.any { it.contains("3.absent") })
        val direct = template.copy(slots = template.slots.map { if (it.kind == SlotKind.INPUT_VIDEO) it.copy(nodeId = "3", input = "file", uploadTargets = emptyList()) else it })
        assertEquals(PreflightStatus.PASS, preflightComfy(direct, info).status)
    }

    @Test fun enumOptionsMustAllExistAndParameterTargetsMustExistInHost() {
        val invalid = template.copy(params = params.map { if (it.key == "mode") it.copy(options = listOf("slow", "absent")) else it })
        assertTrue(preflightComfy(invalid, info).missing.any { it.contains("params.mode.options") })
        val missingInput = template.copy(slots = template.slots.map { if (it.param == "steps") it.copy(input = "unknown") else it })
        assertTrue(preflightComfy(missingInput, info).missing.contains("主机输入 1.unknown"))
        val noEnum = Json.parseToJsonElement(info.toString().replace("[\"slow\",\"fast\"]", "\"STRING\"")).jsonObject
        assertEquals(PreflightStatus.PASS, preflightComfy(template, noEnum).status)
    }

    @Test fun numericRangeBeyondHostIsUncertainIncludingUnboundedRanges() {
        for (steps in listOf(params[1].copy(min = 0.0), params[1].copy(max = 31.0), params[1].copy(min = null, max = null))) {
            val result = preflightComfy(template.copy(params = listOf(params[0], steps)), info)
            assertEquals(PreflightStatus.REVIEW, result.status)
            assertTrue(result.uncertain.any { it.contains("params.steps.min/max") })
        }
    }

    @Test fun dynamicComboParameterInputsUseSelectedChildSchema() {
        val graph = Json.parseToJsonElement("""{"1":{"class_type":"Dynamic","inputs":{"mode":"slow","mode.steps":10,"mode.quality":"low"}}}""").jsonObject
        val info = Json.parseToJsonElement("""{"Dynamic":{"input":{"required":{"mode":["COMFY_DYNAMICCOMBO_V3",{"options":[
            {"key":"slow","inputs":{"required":{"steps":["INT",{"min":1,"max":30}],"quality":[["low","high"]]}}},
            {"key":"fast","inputs":{"required":{}}}
        ]}]}}}}""").jsonObject
        val t = ComfyTemplate("dynamic_param", graph, listOf(ComfySlot("1", "mode.steps", SlotKind.PARAM, param = "steps"),
            ComfySlot("1", "mode.quality", SlotKind.PARAM, param = "quality")), params = listOf(params[1],
            TemplateParam("quality", "质量", TemplateParamType.ENUM, JsonPrimitive("low"), options = listOf("low", "high"))))
        assertEquals(PreflightStatus.PASS, preflightComfy(t, info).status)
        val badEnum = t.copy(params = t.params.map { if (it.key == "quality") it.copy(options = listOf("low", "absent")) else it })
        assertTrue(preflightComfy(badEnum, info).missing.any { it.contains("params.quality.options") })
        val wide = t.copy(params = t.params.map { if (it.key == "steps") it.copy(max = 31.0) else it })
        assertEquals(PreflightStatus.REVIEW, preflightComfy(wide, info).status)
        val selector = ComfyTemplate("selector", graph, listOf(ComfySlot("1", "mode", SlotKind.PARAM, param = "mode")),
            params = listOf(TemplateParam("mode", "模式", TemplateParamType.ENUM, JsonPrimitive("slow"), options = listOf("slow", "absent"))))
        assertTrue(preflightComfy(selector, info).missing.any { it.contains("params.mode.options") })
    }

    @Test fun audioOutputsRequireAudioAndUnknownOutputTypesRequireReview() {
        val audio = template.copy(slots = template.slots.map { if (it.kind == SlotKind.OUTPUT) it.copy(outputKind = InputKind.AUDIO, input = "audio") else it })
        assertEquals(PreflightStatus.PASS, preflightComfy(audio, outputInfo("AUDIO")).status)
        assertEquals(PreflightStatus.MISSING, preflightComfy(audio, info).status)
        assertEquals(PreflightStatus.MISSING, preflightComfy(template, outputInfo("AUDIO")).status)
        val implicitAudio = audio.copy(slots = audio.slots.map { if (it.kind == SlotKind.OUTPUT) it.copy(outputKind = null) else it })
        assertEquals(PreflightStatus.MISSING, preflightComfy(implicitAudio, info).status)
        assertEquals(PreflightStatus.PASS, preflightComfy(implicitAudio, outputInfo("AUDIO")).status)
        for (field in listOf("videos", "gifs", "video")) {
            val implicitVideo = template.copy(slots = template.slots.map { if (it.kind == SlotKind.OUTPUT) it.copy(input = field, outputKind = null) else it })
            assertEquals(PreflightStatus.MISSING, preflightComfy(implicitVideo, outputInfo("AUDIO")).status)
            assertEquals(PreflightStatus.PASS, preflightComfy(implicitVideo, info).status)
        }
        val save = info.getValue("Save").jsonObject
        val unknown = JsonObject(info + ("Save" to JsonObject(save - "output")))
        assertEquals(PreflightStatus.REVIEW, preflightComfy(audio, unknown).status)
        assertEquals(PreflightStatus.REVIEW, preflightComfy(template, unknown).status)
    }

    @Test fun preflightDefendsAgainstLegacyObjectsThatBypassConstructors() {
        // 模拟旧加载路径绕过构造约束的对象，仅用于检查预检的防御边界。
        val legacy = template.copy()
        ComfyTemplate::class.java.getDeclaredField("operation").apply { isAccessible = true }.set(legacy, null)
        ComfyTemplate::class.java.getDeclaredField("slots").apply { isAccessible = true }.set(legacy, template.slots.filterNot { it.kind == SlotKind.INPUT_VIDEO })
        val result = preflightComfy(legacy, info)
        assertTrue(result.missing.any { it.contains("operation.label") })
        assertTrue(result.missing.any { it.contains("slots.INPUT_VIDEO") })
    }

    private fun outputInfo(type: String): JsonObject = JsonObject(info + ("Save" to JsonObject(info.getValue("Save").jsonObject +
        ("output" to JsonArray(listOf(JsonPrimitive(type)))))))
}
