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
    @Test fun parsesNewFieldsAndUpdatesLockedTemplateThroughApprovalTool() = runTest {
        val f = ComfyFixture(); f.prepare()
        val template = f.template.copy(role = TemplateRole.DERIVE, operation = TemplateOperation("超分"), group = MediaGroup.VIDEO,
            workflow = Json.parseToJsonElement("""{"1":{"class_type":"Test","inputs":{"file":"a.mp4","mode":"slow","steps":10,"fast":false}},"2":{"class_type":"Save","inputs":{}}}""").jsonObject,
            slots = listOf(ComfySlot("1", "file", SlotKind.INPUT_VIDEO), ComfySlot("1", "mode", SlotKind.PARAM, param = "mode"),
                ComfySlot("1", "steps", SlotKind.PARAM, param = "steps"), ComfySlot("1", "fast", SlotKind.PARAM, param = "fast"),
                ComfySlot("2", "videos", SlotKind.OUTPUT, outputKind = InputKind.VIDEO)),
            params = listOf(TemplateParam("mode", "模式", TemplateParamType.ENUM, JsonPrimitive("slow"), options = listOf("slow", "fast")),
                TemplateParam("steps", "步数", TemplateParamType.NUMBER, JsonPrimitive(10), min = 1.0, max = 30.0, integer = true),
                TemplateParam("fast", "加速", TemplateParamType.BOOLEAN, JsonPrimitive(false))),
            capabilities = MediaCapabilities(setOf(GenerationMode.REFERENCE)))
        f.http.info = Json.parseToJsonElement("""{"Test":{"input":{"required":{"file":["STRING"],"mode":[["slow","fast"]],"steps":["INT",{"min":1,"max":30}],"fast":["BOOLEAN"]}}},"Save":{"input":{"required":{}},"output_node":true,"output":["IMAGE"]}}""").jsonObject
        f.http.calls.clear()
        val skill = ComfyDefineTemplateSkill(f.repository)
        val args = buildJsonObject { put("template", ComfyConfigurationCodec.encodeTemplate(template)) }
        val result = skill.execute(args, context)
        assertNotEquals(result.content, "error", result.status)
        val saved = f.repository.templates().single()
        assertTrue(saved.template.locked)
        assertEquals(TemplateRole.DERIVE, saved.template.role)
        assertEquals(template.operation, saved.template.operation)
        assertEquals(template.params, saved.template.params)
        assertEquals(PreflightStatus.PASS, saved.preflight.status)
        assertEquals(setOf("/object_info/Test", "/object_info/Save"), f.http.calls.map { it.first }.toSet())
        val response = Json.parseToJsonElement(result.content).jsonObject
        assertEquals("DERIVE", response["role"]!!.jsonPrimitive.content)
        assertEquals(3, response["params"]!!.jsonArray.size)
        assertEquals(com.promenar.infinity.domain.tool.ToolRisk.AGENT_CONFIG, skill.risk)
    }

    @Test fun musicContractAndDurationSlotAreAcceptedByTool() = runTest {
        val f = ComfyFixture(); f.repository.saveHost(f.host)
        val raw = Json.parseToJsonElement("""{"id":"music","hostId":"host","group":"AUDIO","role":"GENERATE",
            "workflow":{"1":{"class_type":"Test","inputs":{"prompt":"","seconds":10}},"2":{"class_type":"Save","inputs":{}}},
            "slots":[{"nodeId":"1","input":"prompt","kind":"PROMPT"},{"nodeId":"1","input":"seconds","kind":"DURATION"},{"nodeId":"2","input":"audio","kind":"OUTPUT","outputKind":"AUDIO"}],
            "capabilities":{"modes":["MUSIC"]}}""").jsonObject
        f.http.info = Json.parseToJsonElement("""{"Test":{"input":{"required":{"prompt":["STRING"],"seconds":["FLOAT"]}}},"Save":{"input":{"required":{}},"output_node":true,"output":["AUDIO"]}}""").jsonObject
        val result = ComfyDefineTemplateSkill(f.repository).execute(buildJsonObject { put("template", raw) }, context)
        assertNotEquals(result.content, "error", result.status)
        assertEquals(MediaGroup.AUDIO, f.repository.templates().single().template.group)
        assertEquals(PreflightStatus.PASS, f.repository.templates().single().preflight.status)
    }

    @Test fun fieldErrorsIdentifyParamIndexAndReasonWithoutSavingChanges() = runTest {
        val f = ComfyFixture(); f.prepare()
        val original = f.repository.templates()
        val raw = ComfyConfigurationCodec.encodeTemplate(f.template)
        val invalid = JsonObject(raw + ("params" to Json.parseToJsonElement("""[
            {"key":"fast","label":"加速","type":"BOOLEAN","default":true},
            {"key":"mode","label":"模式","type":"ENUM","default":"other","options":["a","b"]}]
        """)))
        val skill = ComfyDefineTemplateSkill(f.repository)
        val failed = skill.execute(buildJsonObject { put("template", invalid) }, context)
        assertEquals("error", failed.status)
        assertTrue(failed.content, failed.content.contains("params[1].default: 不在 options 内"))
        val wrongType = JsonObject(raw + ("role" to JsonPrimitive(42)))
        val result = skill.execute(buildJsonObject { put("template", wrongType) }, context)
        assertEquals("error", result.status)
        assertTrue(result.content, result.content.contains("role"))
        val invalidRange = JsonObject(raw + ("capabilities" to Json.parseToJsonElement("""{"modes":["TEXT"],"minDurationSeconds":10,"maxDurationSeconds":5}""")))
        val rangeResult = skill.execute(buildJsonObject { put("template", invalidRange) }, context)
        assertEquals("error", rangeResult.status)
        assertTrue(rangeResult.content, rangeResult.content.contains("capabilities.minDurationSeconds/maxDurationSeconds"))
        assertEquals(original, f.repository.templates())
    }

    @Test fun storedConfigurationDecodeErrorsDoNotEchoRawPayload() = runTest {
        val f = ComfyFixture()
        val storage = object : ComfyConfigurationStorage by f.storage {
            override suspend fun read(): ComfyConfiguration = throw kotlinx.serialization.SerializationException("raw-workflow-content")
        }
        val repository = ComfyRepository(storage, ComfyCredentials(f.secrets), f.http)
        val result = ComfyListTemplatesSkill(repository).execute(JsonObject(emptyMap()), context)
        assertEquals("error", result.status)
        assertTrue(result.content.contains("配置字段"))
        assertFalse(result.content.contains("raw-workflow-content"))
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
    @Test fun nonNodeWorkflowEntriesAreRejectedWithActionableMessage() = runTest {
        // 部分中转会给自由对象补占位属性（如 reason），模型随之写进 workflow；ComfyUI 必然拒绝这类条目。
        val f = ComfyFixture(); f.repository.saveHost(f.host)
        val encoded = ComfyConfigurationCodec.encodeTemplate(f.template).jsonObject
        val polluted = JsonObject(encoded + ("workflow" to JsonObject(encoded.getValue("workflow").jsonObject + ("reason" to JsonPrimitive("配置模板")))))
        val result = ComfyDefineTemplateSkill(f.repository).execute(buildJsonObject { put("template", polluted) }, context)
        assertEquals("error", result.status)
        assertTrue(result.content, result.content.contains("reason") && result.content.contains("class_type"))
        assertTrue(f.repository.templates().isEmpty())
    }
    @Test fun rejectsWrongTypesAndUnknownArguments() = runTest {
        val f = ComfyFixture(); f.prepare()
        val probe = ComfyProbeSkill(f.repository)
        assertEquals("error", probe.execute(buildJsonObject { put("host_id", 7) }, context).status)
        assertEquals("error", probe.execute(buildJsonObject { put("host_id", "host"); put("url", "http://arbitrary") }, context).status)
        val list = ComfyListTemplatesSkill(f.repository)
        assertEquals("error", list.execute(buildJsonObject { put("recheck", "true") }, context).status)
        assertEquals("error", list.execute(buildJsonObject { put("recheck", true) }, context).status)
        val define = ComfyDefineTemplateSkill(f.repository)
        val args = buildJsonObject { put("template", ComfyConfigurationCodec.encodeTemplate(f.template)); put("unexpected", true) }
        assertEquals("error", define.execute(args, context).status)
    }
    @Test fun replacesLockedDefinitionAndRecheckReturnsStatus() = runTest {
        val f = ComfyFixture(); f.prepare()
        val define = ComfyDefineTemplateSkill(f.repository)
        val result = define.execute(buildJsonObject { put("template", ComfyConfigurationCodec.encodeTemplate(f.template.copy(name = "新版"))) }, context)
        assertNotEquals("error", result.status)
        assertEquals("新版", f.repository.templates().single().template.name)
        assertTrue(f.repository.templates().single().template.locked)
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

    @Test fun probeRequiresNodeTypeOrNodeTypesAndBatchProbesV3ComboNodes() = runTest {
        val f = ComfyFixture(); f.repository.saveHost(f.host)
        f.http.info = Json.parseToJsonElement("""{
            "VedaSparse":{"input":{"required":{"predictor":["COMBO",{"options":["veda_preview.safetensors"]}]}}},
            "Save":{"input":{"required":{}},"output_node":true,"output":["VIDEO"]}
        }""").jsonObject
        val probe = ComfyProbeSkill(f.repository)
        assertEquals("error", probe.execute(buildJsonObject { put("host_id", "host") }, context).status)
        assertEquals("error", probe.execute(buildJsonObject {
            put("host_id", "host"); put("node_type", "Save"); put("node_types", JsonArray(listOf(JsonPrimitive("VedaSparse"))))
        }, context).status)
        val batch = probe.execute(buildJsonObject {
            put("host_id", "host")
            put("node_types", JsonArray(listOf(JsonPrimitive("VedaSparse"), JsonPrimitive("AbsentNode"))))
        }, context)
        assertNotEquals("error", batch.status)
        val body = Json.parseToJsonElement(batch.content).jsonObject
        assertEquals(1, body["matched_count"]!!.jsonPrimitive.int)
        assertEquals(listOf("AbsentNode"), body["missing_types"]!!.jsonArray.map { it.jsonPrimitive.content })
        val nodeOptions = body["nodes"]!!.jsonArray.single().jsonObject["file_or_enum_options"]!!.jsonObject
        assertEquals("veda_preview.safetensors", nodeOptions["predictor"]!!.jsonObject["values"]!!.jsonArray.single().jsonPrimitive.content)
        val validator = com.promenar.infinity.domain.tool.ToolSchemaValidator()
        assertTrue(validator.validate(probe.parametersSchema, buildJsonObject {
            put("host_id", "host"); put("node_types", JsonArray(listOf(JsonPrimitive("Save"))))
        }) is com.promenar.infinity.domain.tool.ToolSchemaValidation.Valid)
    }

    @Test fun defineTemplateAcceptsFpsAndLastFrameRequiresLastFrameSlot() = runTest {
        val f = ComfyFixture(); f.repository.saveHost(f.host)
        val base = Json.parseToJsonElement("""{
            "id":"h3_l2va","name":"H3 L2VA","hostId":"host","group":"VIDEO","frameBase":5,"frameStep":17,"maxFrames":362,"fps":24,
            "workflow":{"1":{"class_type":"Test","inputs":{"prompt":"","last":"","frames":124}},"2":{"class_type":"Save","inputs":{}}},
            "slots":[{"nodeId":"1","input":"prompt","kind":"PROMPT"},{"nodeId":"1","input":"frames","kind":"FRAMES"},{"nodeId":"2","input":"videos","kind":"OUTPUT","outputKind":"VIDEO"}],
            "capabilities":{"modes":["LAST_FRAME"],"nativeAudio":true,"promptFormat":"H3"}
        }""").jsonObject
        f.http.info = Json.parseToJsonElement("""{"Test":{"input":{"required":{"prompt":["STRING"],"last":["STRING"],"frames":["INT"]}}},"Save":{"input":{"required":{}},"output_node":true,"output":["VIDEO"]}}""").jsonObject
        val define = ComfyDefineTemplateSkill(f.repository)
        assertEquals("error", define.execute(buildJsonObject { put("template", base) }, context).status)
        val withLastSlot = JsonObject(base + ("slots" to Json.parseToJsonElement("""[
            {"nodeId":"1","input":"prompt","kind":"PROMPT"},
            {"nodeId":"1","input":"last","kind":"LAST_FRAME"},
            {"nodeId":"1","input":"frames","kind":"FRAMES"},
            {"nodeId":"2","input":"videos","kind":"OUTPUT","outputKind":"VIDEO"}
        ]""")))
        val ok = define.execute(buildJsonObject { put("template", withLastSlot) }, context)
        assertNotEquals("error", ok.status)
        val resp = Json.parseToJsonElement(ok.content).jsonObject
        assertEquals(24.0, resp["fps"]!!.jsonPrimitive.double, 0.001)
        assertTrue(resp["modes"]!!.jsonArray.any { it.jsonPrimitive.content == "LAST_FRAME" })
    }
    @Test fun schemasAreValidJsonAndMalformedTemplateGivesReadableError() = runTest {
        val f = ComfyFixture()
        comfyTools(f.repository).forEach { assertTrue(Json.parseToJsonElement(it.parametersSchema) is JsonObject) }
        val failed = ComfyDefineTemplateSkill(f.repository).execute(buildJsonObject { put("template", JsonObject(emptyMap())) }, context)
        assertEquals("error", failed.status); assertTrue(failed.content.contains("字段"))
    }

    @Test fun allFourteenComfyToolsPassSchemaValidationAndManageSubmittersWorkflowsAndChains() = runTest {
        val f = ComfyFixture()
        f.repository.saveHost(f.host)
        f.http.info = Json.parseToJsonElement(
            """{"Save":{"input":{"required":{}},"output_node":true,"output":["IMAGE"]}}""",
        ).jsonObject
        val chains = InMemoryStatefulChainStore()
        val tools = comfyTools(f.repository, chainStore = chains)
        assertEquals(14, tools.size)
        val validator = com.promenar.infinity.domain.tool.ToolSchemaValidator()
        tools.forEach { tool ->
            val validation = validator.validateDefinition(tool.parametersSchema)
            assertTrue("${tool.name}: $validation", validation is com.promenar.infinity.domain.tool.ToolSchemaValidation.Valid)
        }

        // 1. 保存并列出提交器脚本
        val saveSub = tools.single { it.name == "comfy_save_submitter" }
        assertEquals(com.promenar.infinity.domain.tool.ToolRisk.AGENT_CONFIG, saveSub.risk)
        val subRes = saveSub.execute(
            buildJsonObject {
                put("host_id", f.host.id)
                put("script_id", "qwen21_py")
                put("file_name", "submit_qwen21.py")
                put("protocol", "CLI_ARGS")
                put("source_text", "print('ok')")
                putJsonObject("support_files") { put("workflow_base.json", "{}") }
                put("run_dry_run", false)
            },
            context,
        )
        assertNotEquals(subRes.content, "error", subRes.status)
        val listedSubs = tools.single { it.name == "comfy_list_submitters" }.execute(buildJsonObject { put("host_id", f.host.id) }, context)
        assertEquals(1, Json.parseToJsonElement(listedSubs.content).jsonObject["count"]!!.jsonPrimitive.int)

        // 2. 创建超过 10 个无限字段的工作流（CONTENT + FIXED + TIMED_FILES/FILE_LIST 等）
        val saveWf = tools.single { it.name == "comfy_save_workflow" }
        assertEquals(com.promenar.infinity.domain.tool.ToolRisk.AGENT_CONFIG, saveWf.risk)
        val fieldsJson = JsonArray(
            (1..12).map { i ->
                buildJsonObject {
                    put("key", if (i <= 6) "c_field_$i" else "f_field_$i")
                    put("label", "字段 $i")
                    put("mutability", if (i <= 6) "CONTENT" else "FIXED")
                    put("valueType", when (i) {
                        1 -> "TEXT"
                        2 -> "FILE_LIST"
                        3 -> "TIMED_FILES"
                        7 -> "INT"
                        else -> "STRING"
                    })
                    if (i == 7) put("defaultValue", 24)
                }
            },
        )
        val wfArgs = buildJsonObject {
            put("reason", "注册多字段测试工作流")
            putJsonObject("workflow") {
                put("workflowId", "wf_unlimited")
                put("hostId", f.host.id)
                put("name", "无限字段视频流")
                put("mediaCategory", "VIDEO")
                put("submitterScriptId", "qwen21_py")
                put("executionMode", "STATEFUL_CHAIN")
                putJsonObject("chainSpec") {
                    putJsonArray("stages") { add("first"); add("next") }
                    put("defaultBranchPolicy", "ISOLATED_DIR")
                }
                put("fields", fieldsJson)
                putJsonObject("capabilities") { putJsonArray("modes") { add("TEXT"); add("REFERENCE") } }
            }
        }
        val savedWf = saveWf.execute(wfArgs, context)
        assertNotEquals(savedWf.content, "error", savedWf.status)
        val listWf = tools.single { it.name == "comfy_list_workflows" }.execute(buildJsonObject { put("workflow_id", "wf_unlimited") }, context)
        val wfItem = Json.parseToJsonElement(listWf.content).jsonObject["workflows"]!!.jsonArray.single().jsonObject
        assertEquals(6, wfItem["content_fields"]!!.jsonArray.size)
        assertEquals(6, wfItem["fixed_fields"]!!.jsonArray.size)

        // 3. 被工作流引用时禁止删除提交器；删除工作流后再删除提交器成功
        val delSub = tools.single { it.name == "comfy_delete_submitter" }
        assertEquals("error", delSub.execute(buildJsonObject { put("host_id", f.host.id); put("script_id", "qwen21_py") }, context).status)
        val delWf = tools.single { it.name == "comfy_delete_workflow" }
        assertNotEquals("error", delWf.execute(buildJsonObject { put("workflow_id", "wf_unlimited") }, context).status)
        assertNotEquals("error", delSub.execute(buildJsonObject { put("host_id", f.host.id); put("script_id", "qwen21_py") }, context).status)

        // 4. 查询与切换有状态链当前末端节点
        val chainRecord = StatefulChainRecord(
            chainId = "chain_1",
            sessionId = context.sessionId,
            workflowId = "wf_unlimited",
            hostId = f.host.id,
            rootNodeId = "n1",
            activeTipNodeId = "n2b",
            nodes = listOf(
                StatefulChainNode(nodeId = "n1", segmentIndex = 1, attemptLabel = "v1", outputMediaPath = "m/1.mp4", stateValidity = ChainNodeValidity.ACTIVE),
                StatefulChainNode(nodeId = "n2a", parentNodeId = "n1", stage = "next", segmentIndex = 2, attemptLabel = "v1", outputMediaPath = "m/2a.mp4", stateValidity = ChainNodeValidity.ACTIVE),
                StatefulChainNode(nodeId = "n2b", parentNodeId = "n1", stage = "next", segmentIndex = 2, attemptLabel = "v2", outputMediaPath = "m/2b.mp4", stateValidity = ChainNodeValidity.OVERWRITTEN_BY_SIBLING),
            ),
        )
        chains.save(chainRecord)
        val chainStateRes = tools.single { it.name == "comfy_get_chain_state" }.execute(buildJsonObject { put("chain_id", "chain_1") }, context)
        assertEquals(1, Json.parseToJsonElement(chainStateRes.content).jsonObject["count"]!!.jsonPrimitive.int)
        val setTip = tools.single { it.name == "comfy_set_chain_tip" }
        assertEquals("error", setTip.execute(buildJsonObject { put("chain_id", "chain_1"); put("node_id", "n2b") }, context).status)
        val okTip = setTip.execute(buildJsonObject { put("chain_id", "chain_1"); put("node_id", "n2a") }, context)
        assertNotEquals("error", okTip.status)
        assertEquals("n2a", chains.get(context.sessionId, "chain_1")!!.activeTipNodeId)
    }
}
