package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionResult
import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test

class ProductionProcessingSkillsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val assistantMessageId = "m"
    }
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject

    @Test fun `通用工作流按已通过预检的类别和视频字段发现并公开内容参数`() = runTest {
        val workflow = deriveWorkflow()
        val workflows = listOf(
            ComfyWorkflowState(workflow.copy(mediaCategory = ComfyMediaCategory.POST_PROCESS, fields = workflow.fields.drop(2)), PreflightResult(PreflightStatus.PASS)),
            ComfyWorkflowState(workflow.copy(workflowId = "by_role", fields = workflow.fields.drop(1)), PreflightResult(PreflightStatus.PASS)),
            ComfyWorkflowState(workflow.copy(workflowId = "by_type", fields = workflow.fields.take(1)), PreflightResult(PreflightStatus.PASS)),
            ComfyWorkflowState(workflow.copy(workflowId = "two_videos", fields = listOf(workflow.fields.first().copy(required = true)) + workflow.fields.drop(1)), PreflightResult(PreflightStatus.PASS)),
            ComfyWorkflowState(workflow.copy(workflowId = "review"), PreflightResult(PreflightStatus.REVIEW)),
            ComfyWorkflowState(workflow.copy(workflowId = "missing"), PreflightResult(PreflightStatus.MISSING)),
            ComfyWorkflowState(workflow.copy(workflowId = "image", fields = emptyList()), PreflightResult(PreflightStatus.PASS)),
        )
        val processing = DefaultProductionProcessing(
            ProgressStore(), ProgressRegistry(), ProgressQueue(), { emptyList() }, { _, _ -> GenerationSpecs() }, { _, _ -> true },
            workflows = { workflows },
        )
        val result = ProductionOperationsSkill { processing }.execute(args("""{"episode_id":"S1E01","shot_code":"SC01-01"}"""), context)
        val derivations = args(result.content).getValue("derivations").jsonArray.map { it.jsonObject }
        assertThat(derivations.map { it.getValue("operation_id").jsonPrimitive.content }).containsExactly(workflow.workflowId, "by_role", "by_type", "two_videos")
        val operation = derivations.single { it["operation_id"] == JsonPrimitive("by_role") }
        assertThat(derivations.single { it["operation_id"] == JsonPrimitive("generic") }["unavailable_reason"]).isNotNull()
        assertThat(derivations.single { it["operation_id"] == JsonPrimitive("two_videos") }["unavailable_reason"]?.jsonPrimitive?.content).contains("other_clip")
        assertThat(operation["unavailable_reason"]).isNull()
        assertThat(operation["source"]).isEqualTo(JsonPrimitive("comfy_workflow"))
        assertThat(operation["label"]).isEqualTo(JsonPrimitive(workflow.name))
        val params = operation.getValue("params").jsonArray.map { it.jsonObject }
        assertThat(params.map { it.getValue("key").jsonPrimitive.content }).containsExactly("quality.settings")
        assertThat(params.single()["type"]).isEqualTo(JsonPrimitive("JSON"))
        assertThat(params.single()["default"]).isEqualTo(args("""{"passes":1}"""))
        assertThat(params.single()["description"]).isEqualTo(JsonPrimitive("处理参数"))
    }

    @Test fun `通用派生用指定来源和声明的槽位运行并登记进度与候选`() = runTest {
        val store = ProgressStore()
        val queue = ProgressQueue()
        val workflow = deriveWorkflow()
        val runs = ProductionRunService(store, { _, _ -> MediaSpec(durationSeconds = 3.0) }, { _, _ -> true })
        val outputDir = "p/S1E01/takes"
        val params = args("""{"quality.settings":{"passes":2}}""")
        val processing = DefaultProductionProcessing(
            store, ProgressRegistry(), queue, { emptyList() }, { _, _ -> GenerationSpecs() }, { _, path -> path == "p/2.mp4" },
            workflows = { listOf(ComfyWorkflowState(workflow, PreflightResult(PreflightStatus.PASS))) },
            runs = runs,
            executeWorkflow = { request ->
                assertThat(request.workflowId).isEqualTo(workflow.workflowId)
                assertThat(request.contentInputs).isEqualTo(params + ("source_clip" to JsonPrimitive("p/2.mp4")))
                assertThat(request.fixedOverrides).isEmpty()
                assertThat(request.outputDir).isEqualTo(outputDir)
                assertThat(store.snapshot.production.runs.single().state).isEqualTo(RunState.RUNNING)
                request.onProgress(0.42f, "处理中")
                assertThat(store.snapshot.production.runs.single().completedSteps).isEqualTo(42)
                ComfyWorkflowExecutionResult(
                    workflow, ComfyHost("host", "测试主机", "http://127.0.0.1:8188"),
                    listOf(MediaRef("$outputDir/result.mp4", "video/mp4", MediaKind.VIDEO, 100L)),
                    summary = "执行完成",
                )
            },
        )
        val tool = ProductionDeriveSkill { processing }
        val input = args("""{"episode_id":"S1E01","shot_code":"SC01-01","take_id":"t2","operation_id":"generic","params":$params}""")
        val response = tool.execute(input, context)
        assertThat(response.status).isNotEqualTo("error")
        val run = store.snapshot.production.runs.single()
        val take = store.shot().takes.last()
        assertThat(run.state).isEqualTo(RunState.COMPLETED)
        assertThat(run.resultTakeId).isEqualTo(take.id)
        assertThat(take.derivedFrom).isEqualTo(TakeDerivation("t2", workflow.name, inputParams = params))
        assertThat(Json.encodeToJsonElement(TakeDerivation.serializer(), take.derivedFrom!!).jsonObject["params"]).isEqualTo(params)
        assertThat(store.snapshot.production.stageStatus(ProductionStage.POST_PROCESS)).isEqualTo(StageStatus.IN_PROGRESS)
        assertThat(args(response.content)).isEqualTo(args(
            """{"operation_id":"generic","status":"completed","run_id":"${run.id}","take_id":"${take.id}","output_paths":["$outputDir/result.mp4"],"summary":"执行完成"}""",
        ))
        assertThat(queue.tasks.value).isEmpty()
        for (key in listOf("baseline", "source_clip", "other_clip")) {
            val invalid = tool.execute(JsonObject(input + ("params" to args("""{"$key":"x"}"""))), context)
            assertThat(invalid.status).isEqualTo("error")
            assertThat(store.snapshot.production.runs).hasSize(1)
        }
    }

    private fun deriveWorkflow() = ComfyWorkflow(
        "generic", "host", "通用后处理", rawWorkflowJson = buildJsonObject {},
        fields = listOf(
            WorkflowFieldSpec("other_clip", "候选输入槽", valueType = FieldValueType.VIDEO_FILE),
            WorkflowFieldSpec("source_clip", "源视频", valueType = FieldValueType.ANY_FILE, semanticRole = FieldSemanticRole.INPUT_VIDEO),
            WorkflowFieldSpec("quality.settings", "质量", description = "处理参数", valueType = FieldValueType.JSON, defaultValue = args("""{"passes":1}""")),
            WorkflowFieldSpec("baseline", "固定项", mutability = FieldMutability.FIXED, defaultValue = JsonPrimitive("default")),
        ),
    )
    @Test fun `能力返回参数表与脚本分界派生不可用保留明确错误`() = runTest {
        val processing = mockk<ProductionProcessing>()
        coEvery { processing.operations(any(), any(), any()) } returns ShotOperations(
            ShotExtensionOperation("v", minDurationSeconds = 2, maxDurationSeconds = 6),
            listOf(ShotDeriveOperation.Template(progressTemplate())),
        )
        val operations = ProductionOperationsSkill { processing }.execute(args("""{"episode_id":"S1E01","shot_code":"SC01-01"}"""), context)
        val json = args(operations.content)
        assertThat(json.getValue("extension").jsonObject.getValue("max_segments").jsonPrimitive.int).isEqualTo(8)
        assertThat(json.getValue("derivations").jsonArray.single().jsonObject.getValue("params").jsonArray).hasSize(1)
        assertThat(json.getValue("derivations").jsonArray.single().jsonObject["source"]).isEqualTo(JsonPrimitive("template"))
        assertThat(json.getValue("script_route").jsonPrimitive.content).contains("production_run")
        coEvery { processing.derive(any(), any(), any(), any(), any(), any()) } throws IllegalArgumentException("处理不可用；当前可用：up（超分）")
        val error = ProductionDeriveSkill { processing }.execute(args("""{"episode_id":"S1E01","shot_code":"SC01-01","take_id":"t1","operation_id":"missing"}"""), context)
        assertThat(error.status).isEqualTo("error"); assertThat(error.content).contains("当前可用：up")
    }
    @Test fun `新工具在调用服务前严格拒绝缺失字段错误类型和越界参数`() = runTest {
        val processing = mockk<ProductionProcessing>()
        val chain = mockk<ShotChainCoordinator>(); val runs = mockk<ProductionRunService>()
        val invalid = listOf(
            ProductionOperationsSkill { processing } to "{}",
            ProductionDeriveSkill { processing } to """{"episode_id":"S1E01","shot_code":"SC01-01","take_id":"t1","operation_id":"up","params":{"scale":null}}""",
            ProductionScoreSkill { processing } to """{"asset_id":"music","versions":0}""",
            ProductionScoreSkill { processing } to """{"asset_id":"music","duration_seconds":"3"}""",
            ProductionExtendSkill { chain } to """{"action":"start","episode_id":"S1E01","shot_code":"SC01-01","segments":9}""",
            ProductionExtendSkill { chain } to """{"action":"status","episode_id":"S1E01","shot_code":"SC01-01"}""",
            ProductionExtendSkill { chain } to """{"action":"start","episode_id":"S1E01","shot_code":"SC01-01","segments":2,"segment_prompts":["一段"]}""",
            ProductionRunSkill { runs } to """{"action":"start","asset_id":"music","episode_id":"S1E01","label":"编排"}""",
            ProductionRunSkill { runs } to """{"action":"progress","run_id":"r","completed_steps":-1,"message":"进度"}""",
            ProductionRunSkill { runs } to """{"action":"finish","run_id":"r","path":"p/out.mp4","segments":[1]}""",
        )
        invalid.forEach { (skill, value) -> assertThat(skill.execute(args(value), context).status).isEqualTo("error") }
        coVerify(exactly = 0) { processing.operations(any(), any(), any()) }
        coVerify(exactly = 0) { processing.derive(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { processing.score(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { chain.start(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { runs.start(any(), any(), any(), any()) }
    }
    @Test fun `普通生成参数按key传递且对象内拒绝null与嵌套`() = runTest {
        val planner = mockk<ShotGenerationPlanner>()
        coEvery { planner.generate("s", "S1E01", "SC01-01", mapOf("scale" to JsonPrimitive(2))) } returns ShotGenerateResult.Submitted("t", false)
        val skill = ProductionGenerateSkill { planner }
        val response = skill.execute(args("""{"episode_id":"S1E01","shot_code":"SC01-01","params":{"scale":2}}"""), context)
        assertThat(args(response.content).getValue("task_id").jsonPrimitive.content).isEqualTo("t")
        listOf("null", "[]", "{\"scale\":null}").forEach { params ->
            assertThat(skill.execute(args("""{"episode_id":"S1E01","shot_code":"SC01-01","params":$params}"""), context).status).isEqualTo("error")
        }
    }
    @Test fun `配乐默认四版登记动作返回id和终态错误`() = runTest {
        val processing = mockk<ProductionProcessing>()
        coEvery { processing.score("s", "music", null, null, null, 4) } returns listOf(MediaTask("q", MediaGenerationRequest("c", GenerationMode.MUSIC, "雨", emptyList(), "source")))
        assertThat(ProductionScoreSkill { processing }.execute(args("""{"asset_id":"music"}"""), context).status).isNotEqualTo("error")
        val service = mockk<ProductionRunService>()
        coEvery { service.start("s", RunTarget(assetId = "music"), "配乐", 2) } returns ProductionRun("r", RunTarget(assetId = "music"), "配乐", steps = 2, startedAt = 0, reportedAt = 0)
        val skill = ProductionRunSkill { service }
        assertThat(args(skill.execute(args("""{"action":"start","asset_id":"music","label":"配乐","steps":2}"""), context).content).getValue("run_id").jsonPrimitive.content).isEqualTo("r")
        coEvery { service.fail(any(), any(), any()) } throws IllegalArgumentException("登记项当前为 COMPLETED，不能再回报。")
        val error = skill.execute(args("""{"action":"fail","run_id":"r","reason":"失败"}"""), context)
        assertThat(error.status).isEqualTo("error"); assertThat(error.content).contains("COMPLETED")
    }
    @Test fun `changes分页包含登记链与未登记文件shot包含谱系`() {
        val store = ProgressStore()
        val chain = ShotChain("chain", ChainState.PLANNED, listOf(ChainSegment(0, 3.0)), createdAt = 0, updatedAt = 0)
        val run = ProductionRun("r", RunTarget(assetId = "music"), "配乐", startedAt = 0, reportedAt = 0)
        val p = store.snapshot.production.mapShot("S1E01", "SC01-01") { it.copy(chains = listOf(chain)) }.copy(runs = listOf(run), unregisteredFiles = listOf("p/a.wav", "p/b.wav"))
        val s = store.snapshot.copy(production = p)
        val result = args(productionGetResult(s, "changes", null, null, limit = 1, offset = 0)).getValue("content").jsonObject
        assertThat(result.getValue("runs").jsonArray).hasSize(1)
        assertThat(result.getValue("chains").jsonArray).hasSize(1)
        assertThat(result.getValue("unregistered_files").jsonArray).hasSize(1)
        assertThat(result.getValue("totals").jsonObject.getValue("unregistered_files").jsonPrimitive.int).isEqualTo(2)
        val shot = args(productionGetResult(s, "shot", "S1E01", "SC01-01")).getValue("content").jsonObject
        assertThat(shot.getValue("chains").jsonArray).hasSize(1)
        assertThat(shot.getValue("takes").jsonArray.first().jsonObject).containsKey("derivedFrom")
    }
}
