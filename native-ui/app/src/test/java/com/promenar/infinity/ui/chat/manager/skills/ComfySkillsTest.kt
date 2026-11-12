package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.mediagen.comfy.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComfySkillsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "session"
        override val agentId = "agent"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "workspace"
    }
    @Test fun toolSchemasPassClientValidationAndAcceptAgentTemplate() = runTest {
        // 客户端在发给模型前校验工具 schema；不支持的关键字会让整个工具不可调用。
        val f = ComfyFixture(); f.prepare()
        val validator = com.promenar.infinity.domain.tool.ToolSchemaValidator()
        comfyTools(f.repository).forEach { tool ->
            assertTrue(tool.name, validator.validateDefinition(tool.parametersSchema) is com.promenar.infinity.domain.tool.ToolSchemaValidation.Valid)
        }
        val template = Json.parseToJsonElement("""{"id":"h3_smoke","name":"H3","hostId":"host","group":"VIDEO","frameBase":5,"frameStep":17,"maxFrames":22,
            "workflow":{"1":{"class_type":"Test","inputs":{"prompt":""}}},
            "slots":[{"nodeId":"1","input":"prompt","kind":"PROMPT"},{"nodeId":"2","input":"images","kind":"OUTPUT","outputKind":"VIDEO","capacity":1}],
            "models":[{"nodeId":"1","input":"prompt"}],"capabilities":{"modes":["TEXT"],"nativeAudio":true,"promptFormat":"H3"}}""").jsonObject
        val define = comfyTools(f.repository).single { it.name == "comfy_define_template" }
        assertTrue(validator.validate(define.parametersSchema, buildJsonObject { put("template", template) }) is com.promenar.infinity.domain.tool.ToolSchemaValidation.Valid)
    }
    @Test fun rejectsWrongTypesUnknownArgumentsAndUserUnlockParameters() = runTest {
        val f = ComfyFixture(); f.prepare()
        val probe = ComfyProbeSkill(f.repository)
        assertEquals("error", probe.execute(buildJsonObject { put("host_id", 7) }, context).status)
        assertEquals("error", probe.execute(buildJsonObject { put("host_id", "host"); put("url", "http://arbitrary") }, context).status)
        val list = ComfyListTemplatesSkill(f.repository)
        assertEquals("error", list.execute(buildJsonObject { put("recheck", "true") }, context).status)
        assertEquals("error", list.execute(buildJsonObject { put("recheck", true) }, context).status)
        val define = ComfyDefineTemplateSkill(f.repository)
        val args = buildJsonObject { put("template", ComfyConfigurationCodec.encodeTemplate(f.template)); put("unlock", true) }
        assertEquals("error", define.execute(args, context).status)
        assertFalse(comfyTools(f.repository).any { it.name.contains("unlock") })
    }
    @Test fun lockedDefinitionGivesUserUnlockGuidanceAndRecheckReturnsStatus() = runTest {
        val f = ComfyFixture(); f.prepare()
        val define = ComfyDefineTemplateSkill(f.repository)
        val result = define.execute(buildJsonObject { put("template", ComfyConfigurationCodec.encodeTemplate(f.template)) }, context)
        assertEquals("error", result.status); assertTrue(result.content.contains("用户")); assertTrue(result.content.contains("解锁"))
        f.repository.unlockTemplateByUser(f.template.id)
        assertNotEquals("error", define.execute(buildJsonObject { put("template", ComfyConfigurationCodec.encodeTemplate(f.template)) }, context).status)
        val listed = ComfyListTemplatesSkill(f.repository).execute(buildJsonObject { put("template_id", "test"); put("recheck", true) }, context)
        assertNotEquals("error", listed.status)
        val template = Json.parseToJsonElement(listed.content).jsonObject["templates"]!!.jsonArray.single().jsonObject
        assertEquals("PASS", template["preflight"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("comfy:test", template["channel_id"]!!.jsonPrimitive.content)
    }
    @Test fun probeFiltersAndTruncatesModelCandidates() = runTest {
        val f = ComfyFixture(); f.repository.saveHost(f.host)
        f.http.info = buildJsonObject { repeat(100) { n -> put("Node$n", buildJsonObject { put("input", buildJsonObject { put("required", buildJsonObject {
            put("weights", JsonArray(listOf(JsonArray((0..30).map { JsonPrimitive("model-$it") }))))
        }) }) }) } }
        val summary = summarizeComfyHost(f.http.info)
        assertEquals(100, summary["node_count"]!!.jsonPrimitive.int)
        assertTrue(summary["truncated"]!!.jsonPrimitive.boolean)
        val filtered = f.repository.probe("host", "Node1")
        assertEquals(1, filtered["matched_count"]!!.jsonPrimitive.int)
        assertTrue(f.http.calls.last().first.endsWith("/object_info/Node1"))
        assertTrue(filtered.toString().length < 16000)
    }
    @Test fun schemasAreValidJsonAndMalformedTemplateGivesReadableError() = runTest {
        val f = ComfyFixture()
        comfyTools(f.repository).forEach { assertTrue(Json.parseToJsonElement(it.parametersSchema) is JsonObject) }
        val failed = ComfyDefineTemplateSkill(f.repository).execute(buildJsonObject { put("template", JsonObject(emptyMap())) }, context)
        assertEquals("error", failed.status); assertTrue(failed.content.contains("字段"))
    }
}
