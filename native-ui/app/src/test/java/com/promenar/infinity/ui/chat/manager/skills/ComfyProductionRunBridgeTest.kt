package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.data.mediagen.comfy.ComfyFailure
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionResult
import com.promenar.infinity.data.mediagen.comfy.ComfyRepository
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionEngine
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionRequest
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowRunMode
import com.promenar.infinity.data.production.Asset
import com.promenar.infinity.data.production.AssetKind
import com.promenar.infinity.data.production.AssetStatus
import com.promenar.infinity.data.production.AssetVersion
import com.promenar.infinity.data.production.Episode
import com.promenar.infinity.data.production.InputRole
import com.promenar.infinity.data.production.ManifestSync
import com.promenar.infinity.data.production.MediaSpec
import com.promenar.infinity.data.production.Production
import com.promenar.infinity.data.production.ProductionKind
import com.promenar.infinity.data.production.ProductionRunService
import com.promenar.infinity.data.production.ProductionSnapshot
import com.promenar.infinity.data.production.ProductionStage
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.production.ProductionUserEngine
import com.promenar.infinity.data.production.ProductionWorkStore
import com.promenar.infinity.data.production.RunState
import com.promenar.infinity.data.production.Scene
import com.promenar.infinity.data.production.Season
import com.promenar.infinity.data.production.Shot
import com.promenar.infinity.data.production.ShotInput
import com.promenar.infinity.data.production.StageStatus
import com.promenar.infinity.data.production.Take
import com.promenar.infinity.data.production.TakeSource
import com.promenar.infinity.data.production.mapShot
import com.promenar.infinity.data.production.occupiedShotCandidates
import com.promenar.infinity.data.production.stageStatus
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.mediagen.ComfyMediaCategory
import com.promenar.infinity.domain.mediagen.ComfyWorkflow
import com.promenar.infinity.domain.mediagen.ComfyWorkflowState
import com.promenar.infinity.domain.mediagen.FieldMutability
import com.promenar.infinity.domain.mediagen.FieldSemanticRole
import com.promenar.infinity.domain.mediagen.FieldValueType
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.WorkflowFieldSpec
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

class ComfyProductionRunBridgeTest {
    @Test
    fun `工具给已绑定镜头推导缺省输出目录并返回自动登记凭据`() = runTest {
        val store = MemoryWorkStore(sampleProduction().mapShot("S1E01", "SC01-03") { shot ->
            shot.copy(takes = shot.takes + Take("t-other", "productions/demo/S1E01/takes/other.mp4", TakeSource("local"), 20L))
        })
        val broken = "productions/demo/broken"
        val runs = ProductionRunService(store, { _, _ -> MediaSpec(durationSeconds = 5.0) }, { _, path -> !path.startsWith("$broken/") })
        val wf = workflow(isVideoDerive = true).let {
            it.copy(fields = it.fields + WorkflowFieldSpec("options", "处理设置", valueType = FieldValueType.JSON))
        }
        val options = buildJsonObject { put("steps", 2) }
        val repository = mockk<ComfyRepository>()
        coEvery { repository.workflowState(wf.workflowId) } returns ComfyWorkflowState(wf)
        val engine = mockk<ComfyWorkflowExecutionEngine>()
        val requests = mutableListOf<ComfyWorkflowExecutionRequest>()
        coEvery { engine.execute(any()) } coAnswers {
            val request = firstArg<ComfyWorkflowExecutionRequest>()
            requests += request
            ComfyWorkflowExecutionResult(
                workflow = wf,
                host = ComfyHost("h1", "测试主机", "http://127.0.0.1:8188"),
                savedMedia = listOf(MediaRef("${request.outputDir}/result-${requests.size}.mp4", "video/mp4", MediaKind.VIDEO, 1024L)),
                summary = "完成",
            )
        }
        val tool = comfyTools(
            repository, executionEngine = engine, productionRunBridge = ComfyProductionRunBridge(store, runs),
        ).single { it.name == "comfy_run_workflow" }
        val context = object : SkillExecutionContext {
            override val sessionId = "s1"
            override val agentId = "a"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
            override val assistantMessageId = "m"
        }
        val defaultDir = "productions/demo/S1E01/takes"
        val custom = "productions/demo/custom"
        for ((supplied, expected) in listOf(null to defaultDir, "   " to defaultDir, custom to custom, "outside" to "outside", broken to broken)) {
            store.throwAfterCompletion = expected == custom
            val response = tool.execute(buildJsonObject {
                put("workflow_id", wf.workflowId)
                put("shot_ref", "S1E01/SC01-03")
                put("content_inputs", buildJsonObject {
                    put("input_video", "productions/demo/S1E01/takes/other.mp4")
                    put("options", options)
                })
                supplied?.let { put("output_dir", it) }
            }, context)
            assertThat(response.status).isNotEqualTo("error")
            assertThat(requests.last().outputDir).isEqualTo(expected)
            assertThat(requests.last().expectedWorkflow).isEqualTo(wf)
            val payload = Json.parseToJsonElement(response.content).jsonObject
            val run = store.snapshot.production.runs.last()
            assertThat(payload["production_run_id"]?.jsonPrimitive?.content).isEqualTo(run.id)
            if (expected == broken) {
                assertThat(run.state).isEqualTo(RunState.FAILED)
                assertThat(payload["production_registration_error"]?.jsonPrimitive?.content).contains("产物不可读")
                assertThat(payload["registered_take_id"]).isNull()
            } else if (expected == "outside") {
                assertThat(run.state).isEqualTo(RunState.COMPLETED)
                assertThat(payload["registered_take_id"]).isNull()
                assertThat(run.resultTakeId).isNull()
            } else {
                assertThat(run.state).isEqualTo(RunState.COMPLETED)
                assertThat(run.resultTakeId).isNotNull()
                assertThat(payload["registered_take_id"]?.jsonPrimitive?.content).isEqualTo(run.resultTakeId)
                assertThat(payload["production_registration_error"]).isNull()
                assertThat(run.resultPath).isEqualTo("$expected/result-${requests.size}.mp4")
                val shot = bridgeShot(store)
                val derivation = shot.takes.single { it.id == run.resultTakeId }.derivedFrom
                assertThat(derivation?.sourceTakeId).isEqualTo("t-other")
                assertThat(derivation?.inputParams?.get("options")).isEqualTo(options)
            }
        }
        coEvery { repository.workflowState(wf.workflowId) } throws ComfyFailure("读取工作流失败")
        val previousRequests = requests.size
        val unavailable = tool.execute(buildJsonObject {
            put("workflow_id", wf.workflowId)
            put("shot_ref", "S1E01/SC01-03")
        }, context)
        assertThat(unavailable.status).isEqualTo("error")
        assertThat(requests).hasSize(previousRequests)
        listOf("任务", "ANCHOR", "takes", "registered_take_id", "add_take").forEach {
            assertThat(tool.description).contains(it)
        }
        val shotRef = Json.parseToJsonElement(tool.parametersSchema).jsonObject.getValue("properties")
            .jsonObject.getValue("shot_ref").jsonObject.getValue("description").jsonPrimitive.content
        listOf("takes", "production_run_id", "registered_take_id", "add_take").forEach {
            assertThat(shotRef).contains(it)
        }
    }

    private class MemoryWorkStore(initial: Production) : ProductionWorkStore {
        var snapshot = ProductionSnapshot(initial, 1L, ManifestSync.InSync)
        var throwAfterCompletion = false
        override suspend fun load(sessionId: String): ProductionSnapshot = snapshot
        override suspend fun apply(sessionId: String, snapshot: ProductionSnapshot, action: ProductionUserAction): ProductionSnapshot {
            this.snapshot = ProductionUserEngine.apply(this.snapshot, snapshot.revision, action, 1000L)
            if (throwAfterCompletion && action is ProductionUserAction.SaveRun && action.run.state == RunState.COMPLETED) {
                throw IllegalStateException("完成后回报失败")
            }
            return this.snapshot
        }
    }

    private fun bridgeShot(store: MemoryWorkStore): Shot =
        store.snapshot.production.seasons.single().episodes.single().scenes.single().shots.single()

    private fun sampleProduction(): Production = Production(
        id = "p-bridge",
        title = "测试短片",
        kind = ProductionKind.FILM,
        rootPath = "productions/demo",
        createdAt = 1L,
        updatedAt = 1L,
        seasons = listOf(
            Season(
                id = "S1",
                number = 1,
                episodes = listOf(
                    Episode(
                        id = "S1E01",
                        number = 1,
                        title = "第一集",
                        scenes = listOf(
                            Scene(
                                code = "SC01",
                                number = 1,
                                heading = "雨夜石坪",
                                storyboardLocked = true,
                                shots = listOf(
                                    Shot(
                                        code = "SC01-03",
                                        title = "低身旋斩",
                                        durationSeconds = 5.0,
                                        inputs = listOf(
                                            ShotInput(InputRole.ANCHOR, "productions/demo/assets/kf0.png", label = "起手帧", atSeconds = 0.0),
                                            ShotInput(InputRole.ANCHOR, "productions/demo/assets/kf60.png", label = "中段帧", atSeconds = 2.5),
                                            ShotInput(InputRole.REFERENCE, "productions/demo/assets/p1.png", label = "P1"),
                                        ),
                                        takes = listOf(
                                            Take("t-orig", "productions/demo/S1E01/takes/orig.mp4", TakeSource("local"), 10L),
                                        ),
                                        selectedTakeId = "t-orig",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    private fun workflow(isVideoDerive: Boolean = false): ComfyWorkflow = ComfyWorkflow(
        workflowId = "wf-generic",
        hostId = "h1",
        name = "通用视频工作流",
        mediaCategory = ComfyMediaCategory.VIDEO,
        rawWorkflowJson = buildJsonObject {},
        fps = 24.0,
        capabilities = MediaCapabilities(setOf(GenerationMode.REFERENCE)),
        fields = buildList {
            add(
                WorkflowFieldSpec(
                    key = "guides",
                    label = "时序锁帧",
                    mutability = FieldMutability.CONTENT,
                    valueType = FieldValueType.TIMED_FILES,
                    semanticRole = FieldSemanticRole.GUIDES,
                ),
            )
            if (isVideoDerive) {
                add(
                    WorkflowFieldSpec(
                        key = "input_video",
                        label = "输入视频",
                        mutability = FieldMutability.CONTENT,
                        valueType = FieldValueType.VIDEO_FILE,
                        semanticRole = FieldSemanticRole.INPUT_VIDEO,
                    ),
                )
            }
        },
    )

    @Test
    fun `按多种shot_ref格式定位镜头并自动将ANCHOR透传至GUIDES槽`() = runTest {
        val store = MemoryWorkStore(sampleProduction())
        val runs = ProductionRunService(store, { _, _ -> MediaSpec(1920, 1088, 24.0, 5.0) }, { _, _ -> true })
        val bridge = ComfyProductionRunBridge(store, runs)

        assertThat(bridge.resolveShot("s1", "S1E01/SC01-03")?.location?.shot?.code).isEqualTo("SC01-03")
        assertThat(bridge.resolveShot("s1", "S1E01:SC01-03")?.location?.shot?.code).isEqualTo("SC01-03")
        assertThat(bridge.resolveShot("s1", "SC01-03")?.location?.shot?.code).isEqualTo("SC01-03")
        assertThat(bridge.resolveShot("s1", "SC99-99")).isNull()

        val shot = bridge.resolveShot("s1", "SC01-03")!!.location.shot
        val merged = bridge.mergeAnchorGuidesIfMissing(workflow(), shot, mapOf("prompt" to JsonPrimitive("测试")))
        val guides = merged["guides"] as JsonArray
        assertThat(guides).hasSize(2)
        assertThat(guides[0].jsonObject["frame_idx"]?.jsonPrimitive?.int).isEqualTo(0)
        assertThat(guides[0].jsonObject["file"]?.jsonPrimitive?.content).isEqualTo("productions/demo/assets/kf0.png")
        assertThat(guides[1].jsonObject["frame_idx"]?.jsonPrimitive?.int).isEqualTo(60)

        val explicit = mapOf("guides" to JsonArray(emptyList()))
        assertThat(bridge.mergeAnchorGuidesIfMissing(workflow(), shot, explicit)["guides"]).isEqualTo(JsonArray(emptyList()))
    }

    @Test
    fun `comfy_run_workflow执行进度同步至总览任务队列并自动登记视频派生候选与激活后处理阶段`() = runTest {
        var clock = 10_000L
        val store = MemoryWorkStore(sampleProduction())
        val runs = ProductionRunService(store, { _, _ -> MediaSpec(2688, 1536, 24.0, 5.0) }, { _, _ -> true }, now = { clock })
        val bridge = ComfyProductionRunBridge(store, runs, now = { clock })
        val wf = workflow(isVideoDerive = true)
        val binding = bridge.resolveShot("s1", "SC01-03")!!

        val handle = bridge.onStart("s1", binding, wf, "single",
            contentInputs = mapOf("input_video" to JsonPrimitive("productions/demo/S1E01/takes/orig.mp4")))
        assertThat(store.snapshot.production.runs).hasSize(1)
        assertThat(store.snapshot.production.runs.single().state).isEqualTo(RunState.RUNNING)

        clock += 1_000L
        bridge.onProgress(handle, 0.45f, "采样中")
        assertThat(store.snapshot.production.runs.single().completedSteps).isEqualTo(45)
        assertThat(store.snapshot.production.runs.single().message).isEqualTo("采样中")

        // 进度不能倒退
        clock += 1_000L
        bridge.onProgress(handle, 0.20f, "倒退测试")
        assertThat(store.snapshot.production.runs.single().completedSteps).isEqualTo(45)

        val execResult = ComfyWorkflowExecutionResult(
            workflow = wf,
            host = ComfyHost("h1", "MAIN", "http://127.0.0.1:8188"),
            runMode = ComfyWorkflowRunMode.EXECUTE,
            savedMedia = listOf(MediaRef("productions/demo/S1E01/takes/derived.mp4", "video/mp4", MediaKind.VIDEO, 1024L)),
            summary = "完成",
        )
        val sync = bridge.onComplete(handle, execResult)
        assertThat(sync?.registeredTakeId).isNotNull()
        val updatedProd = store.snapshot.production
        assertThat(updatedProd.runs.single().state).isEqualTo(RunState.COMPLETED)
        val updatedShot = updatedProd.seasons.single().episodes.single().scenes.single().shots.single()
        val derivedTake = updatedShot.takes.first { it.id == sync!!.registeredTakeId }
        assertThat(derivedTake.derivedFrom?.sourceTakeId).isEqualTo("t-orig")
        assertThat(derivedTake.source.service).contains("ComfyUI")
        assertThat(updatedProd.stageStatus(ProductionStage.POST_PROCESS)).isEqualTo(StageStatus.IN_PROGRESS)
    }

    @Test
    fun `桥接拒绝过期项目绑定并与制作工具共享候选预留`() = runTest {
        val store = MemoryWorkStore(sampleProduction().mapShot("S1E01", "SC01-03") { shot ->
            shot.copy(takes = shot.takes + List(28) { Take("extra-$it", "productions/demo/extra-$it.mp4", TakeSource("源"), 1) })
        })
        val runs = ProductionRunService(store, { _, _ -> MediaSpec() }, { _, _ -> true })
        val bridge = ComfyProductionRunBridge(store, runs)
        val wf = workflow(isVideoDerive = true)
        val stale = bridge.resolveShot("s1", "SC01-03")!!
        store.snapshot = store.snapshot.copy(production = stale.production.copy(id = "different-project"))
        val rebound = runCatching { bridge.onStart("s1", stale, wf, "single") }.exceptionOrNull()
        assertThat(rebound?.message).contains("制作项目或镜头已变化")
        assertThat(store.snapshot.production.runs).isEmpty()

        val binding = bridge.resolveShot("s1", "SC01-03")!!
        val handle = bridge.onStart("s1", binding, wf, "single")
        assertThat(handle.sourceTakeId).isNull()
        assertThat(occupiedShotCandidates(store.snapshot.production, "S1E01", "SC01-03", null)).isEqualTo(30)
        val occupied = runCatching {
            bridge.onStart("s1", bridge.resolveShot("s1", "SC01-03")!!, wf, "single")
        }.exceptionOrNull()
        assertThat(occupied?.message).contains("候选容量不足")
        bridge.onFail(handle, "测试失败")
        assertThat(occupiedShotCandidates(store.snapshot.production, "S1E01", "SC01-03", null)).isEqualTo(29)
    }

    @Test
    fun `工具关联asset_id自动推导assets默认目录并同步总览进度与资产版本登记`() = runTest {
        var clock = 20_000L
        val store = MemoryWorkStore(
            sampleProduction().copy(
                assets = listOf(
                    Asset(id = "char-linwan", kind = AssetKind.CHARACTER, name = "林晚"),
                    Asset(id = "bgm-main", kind = AssetKind.SOUND, name = "主配乐"),
                ),
            ),
        )
        val broken = "productions/demo/broken"
        val runs = ProductionRunService(
            store,
            { _, path -> if (path.endsWith(".mp3")) MediaSpec(durationSeconds = 12.0) else MediaSpec(width = 1024, height = 1024) },
            { _, path -> !path.startsWith("$broken/") },
            now = { clock },
        )
        val imageWf = ComfyWorkflow(
            workflowId = "wf-image",
            hostId = "h1",
            name = "角色资产生图",
            mediaCategory = ComfyMediaCategory.IMAGE,
            rawWorkflowJson = buildJsonObject {},
            capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)),
            fields = emptyList(),
        )
        val repository = mockk<ComfyRepository>()
        coEvery { repository.workflowState(imageWf.workflowId) } returns ComfyWorkflowState(imageWf)
        val engine = mockk<ComfyWorkflowExecutionEngine>()
        val requests = mutableListOf<ComfyWorkflowExecutionRequest>()
        var observedRunningSteps: Int? = null
        var observedRunningAssetId: String? = null
        coEvery { engine.execute(any()) } coAnswers {
            val request = firstArg<ComfyWorkflowExecutionRequest>()
            requests += request
            clock += 1_000L
            request.onProgress(0.5f, "采样中")
            val activeRun = store.snapshot.production.runs.last()
            observedRunningSteps = activeRun.completedSteps
            observedRunningAssetId = activeRun.target.assetId
            ComfyWorkflowExecutionResult(
                workflow = imageWf,
                host = ComfyHost("h1", "测试主机", "http://127.0.0.1:8188"),
                savedMedia = listOf(MediaRef("${request.outputDir}/char-${requests.size}.png", "image/png", MediaKind.IMAGE, 2048L)),
                summary = "完成",
            )
        }
        val tool = comfyTools(
            repository,
            executionEngine = engine,
            productionRunBridge = ComfyProductionRunBridge(store, runs, now = { clock }),
        ).single { it.name == "comfy_run_workflow" }
        val context = object : SkillExecutionContext {
            override val sessionId = "s1"
            override val agentId = "a"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
            override val assistantMessageId = "m"
        }

        val defaultDir = "productions/demo/assets/char-linwan"
        val customDir = "productions/demo/assets/custom"
        var expectedVersion = 0
        for ((supplied, expected) in listOf(null to defaultDir, "   " to defaultDir, customDir to customDir, "outside" to "outside", broken to broken)) {
            store.throwAfterCompletion = expected == customDir
            val response = tool.execute(buildJsonObject {
                put("workflow_id", imageWf.workflowId)
                put("asset_id", "char-linwan")
                supplied?.let { put("output_dir", it) }
            }, context)
            assertThat(response.status).isNotEqualTo("error")
            assertThat(requests.last().outputDir).isEqualTo(expected)
            assertThat(requests.last().expectedWorkflow).isEqualTo(imageWf)
            assertThat(observedRunningAssetId).isEqualTo("char-linwan")
            assertThat(observedRunningSteps).isEqualTo(50)

            val payload = Json.parseToJsonElement(response.content).jsonObject
            val run = store.snapshot.production.runs.last()
            assertThat(payload["production_run_id"]?.jsonPrimitive?.content).isEqualTo(run.id)
            assertThat(run.target.assetId).isEqualTo("char-linwan")
            assertThat(run.target.episodeId).isNull()
            assertThat(run.target.shotCode).isNull()
            when (expected) {
                broken -> {
                    assertThat(run.state).isEqualTo(RunState.FAILED)
                    assertThat(payload["registered_version"]).isNull()
                    assertThat(payload["production_registration_error"]?.jsonPrimitive?.content).contains("产物不可读")
                }
                "outside" -> {
                    assertThat(run.state).isEqualTo(RunState.FAILED)
                    assertThat(payload["registered_version"]).isNull()
                    assertThat(payload["production_registration_error"]?.jsonPrimitive?.content).contains("项目目录内未产出")
                }
                else -> {
                    expectedVersion += 1
                    assertThat(run.state).isEqualTo(RunState.COMPLETED)
                    assertThat(run.resultAssetVersion).isEqualTo(expectedVersion)
                    assertThat(payload["registered_version"]?.jsonPrimitive?.int).isEqualTo(expectedVersion)
                    assertThat(payload["production_registration_error"]).isNull()
                    assertThat(run.resultPath).isEqualTo("$expected/char-${requests.size}.png")
                    val asset = store.snapshot.production.assets.single { it.id == "char-linwan" }
                    assertThat(asset.status).isEqualTo(AssetStatus.REVIEW)
                    assertThat(asset.currentVersion).isEqualTo(expectedVersion)
                    assertThat(asset.versions.last().path).isEqualTo("$expected/char-${requests.size}.png")
                }
            }
        }

        // 工具描述与 schema 均完整暴露 asset_id 与 registered_version 契约并通过门禁校验
        listOf("asset_id", "assets/", "registered_version", "add_asset_version").forEach {
            assertThat(tool.description).contains(it)
        }
        val validator = com.promenar.infinity.domain.tool.ToolSchemaValidator()
        assertThat(validator.validateDefinition(tool.parametersSchema))
            .isInstanceOf(com.promenar.infinity.domain.tool.ToolSchemaValidation.Valid::class.java)
        val assetIdDesc = Json.parseToJsonElement(tool.parametersSchema).jsonObject.getValue("properties")
            .jsonObject.getValue("asset_id").jsonObject.getValue("description").jsonPrimitive.content
        listOf("assets/", "production_run_id", "registered_version", "add_asset_version").forEach {
            assertThat(assetIdDesc).contains(it)
        }
    }

    @Test
    fun `shot_ref与asset_id互斥且资产工作流失败或音频类型不匹配时标记失败`() = runTest {
        val store = MemoryWorkStore(
            sampleProduction().copy(
                assets = listOf(
                    Asset(id = "char-linwan", kind = AssetKind.CHARACTER, name = "林晚"),
                    Asset(id = "bgm-main", kind = AssetKind.SOUND, name = "主配乐"),
                ),
            ),
        )
        val runs = ProductionRunService(
            store,
            { _, path -> if (path.endsWith(".mp3")) MediaSpec(durationSeconds = 15.0) else MediaSpec(width = 1024, height = 1024) },
            { _, _ -> true },
        )
        val wf = ComfyWorkflow(
            workflowId = "wf-asset",
            hostId = "h1",
            name = "通用资产生成",
            mediaCategory = ComfyMediaCategory.IMAGE,
            rawWorkflowJson = buildJsonObject {},
            capabilities = MediaCapabilities(setOf(GenerationMode.TEXT)),
            fields = emptyList(),
        )
        val repository = mockk<ComfyRepository>()
        coEvery { repository.workflowState(wf.workflowId) } returns ComfyWorkflowState(wf)
        val engine = mockk<ComfyWorkflowExecutionEngine>()
        val bridge = ComfyProductionRunBridge(store, runs)
        val tool = comfyTools(repository, executionEngine = engine, productionRunBridge = bridge)
            .single { it.name == "comfy_run_workflow" }
        val context = object : SkillExecutionContext {
            override val sessionId = "s1"
            override val agentId = "a"
            override val workspacePath: String? = null
            override val workspaceRootUuid = "root"
            override val assistantMessageId = "m"
        }

        // shot_ref 与 asset_id 互斥
        val conflict = tool.execute(buildJsonObject {
            put("workflow_id", wf.workflowId)
            put("shot_ref", "S1E01/SC01-03")
            put("asset_id", "char-linwan")
        }, context)
        assertThat(conflict.status).isEqualTo("error")
        assertThat(conflict.content).contains("不能同时指定")
        assertThat(store.snapshot.production.runs).isEmpty()

        // 执行抛异常时同步将资产运行登记项置为 FAILED
        coEvery { engine.execute(any()) } throws ComfyFailure("GPU 显存不足")
        val failed = tool.execute(buildJsonObject {
            put("workflow_id", wf.workflowId)
            put("asset_id", "char-linwan")
        }, context)
        assertThat(failed.status).isEqualTo("error")
        val failedRun = store.snapshot.production.runs.single()
        assertThat(failedRun.target.assetId).isEqualTo("char-linwan")
        assertThat(failedRun.state).isEqualTo(RunState.FAILED)
        assertThat(failedRun.failure).contains("GPU 显存不足")

        // 声音资产仅产出图片时不登记并报错，产出音频时正常登记为 v1
        coEvery { engine.execute(any()) } returnsMany listOf(
            ComfyWorkflowExecutionResult(
                workflow = wf,
                host = ComfyHost("h1", "测试主机", "http://127.0.0.1:8188"),
                savedMedia = listOf(MediaRef("productions/demo/assets/bgm-main/cover.png", "image/png", MediaKind.IMAGE, 512L)),
                summary = "图片输出",
            ),
            ComfyWorkflowExecutionResult(
                workflow = wf,
                host = ComfyHost("h1", "测试主机", "http://127.0.0.1:8188"),
                savedMedia = listOf(MediaRef("productions/demo/assets/bgm-main/theme.mp3", "audio/mpeg", MediaKind.AUDIO, 4096L)),
                summary = "音频输出",
            ),
        )
        val nonAudio = tool.execute(buildJsonObject {
            put("workflow_id", wf.workflowId)
            put("asset_id", "bgm-main")
        }, context)
        val nonAudioPayload = Json.parseToJsonElement(nonAudio.content).jsonObject
        assertThat(store.snapshot.production.runs.last().state).isEqualTo(RunState.FAILED)
        assertThat(nonAudioPayload["registered_version"]).isNull()
        assertThat(nonAudioPayload["production_registration_error"]?.jsonPrimitive?.content).contains("音频")

        val audioOk = tool.execute(buildJsonObject {
            put("workflow_id", wf.workflowId)
            put("asset_id", "bgm-main")
        }, context)
        val audioPayload = Json.parseToJsonElement(audioOk.content).jsonObject
        assertThat(store.snapshot.production.runs.last().state).isEqualTo(RunState.COMPLETED)
        assertThat(audioPayload["registered_version"]?.jsonPrimitive?.int).isEqualTo(1)
        assertThat(store.snapshot.production.assets.single { it.id == "bgm-main" }.versions.single().path)
            .isEqualTo("productions/demo/assets/bgm-main/theme.mp3")

        // 资产版本达到 30 上限时拒绝启动新任务
        val fullVersions = (1..30).map { n -> AssetVersion(n, "productions/demo/assets/char-linwan/v$n.png", n.toLong()) }
        store.snapshot = store.snapshot.copy(
            production = store.snapshot.production.copy(
                assets = store.snapshot.production.assets.map { asset ->
                    if (asset.id == "char-linwan") asset.copy(versions = fullVersions) else asset
                },
            ),
        )
        val fullBinding = bridge.resolveAsset("s1", "char-linwan")!!
        val capError = runCatching { bridge.onStart("s1", fullBinding, wf, "single") }.exceptionOrNull()
        assertThat(capError?.message).contains("资产版本容量不足")
    }
}
