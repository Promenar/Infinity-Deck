package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.mediagen.comfy.ComfyHost
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowExecutionResult
import com.promenar.infinity.data.mediagen.comfy.ComfyWorkflowRunMode
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
import com.promenar.infinity.data.production.stageStatus
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.mediagen.ComfyMediaCategory
import com.promenar.infinity.domain.mediagen.ComfyWorkflow
import com.promenar.infinity.domain.mediagen.FieldMutability
import com.promenar.infinity.domain.mediagen.FieldSemanticRole
import com.promenar.infinity.domain.mediagen.FieldValueType
import com.promenar.infinity.domain.mediagen.GenerationMode
import com.promenar.infinity.domain.mediagen.MediaCapabilities
import com.promenar.infinity.domain.mediagen.WorkflowFieldSpec
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

class ComfyProductionRunBridgeTest {
    private class MemoryWorkStore(initial: Production) : ProductionWorkStore {
        var snapshot = ProductionSnapshot(initial, 1L, ManifestSync.InSync)
        override suspend fun load(sessionId: String): ProductionSnapshot = snapshot
        override suspend fun apply(sessionId: String, snapshot: ProductionSnapshot, action: ProductionUserAction): ProductionSnapshot {
            this.snapshot = ProductionUserEngine.apply(this.snapshot, snapshot.revision, action, 1000L)
            return this.snapshot
        }
    }

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

        val handle = bridge.onStart("s1", binding, wf, "single")!!
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
}
