package com.promenar.infinity.ui.chat.manager.skills

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.production.*
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
    @Test fun `能力返回参数表与脚本分界派生不可用保留明确错误`() = runTest {
        val processing = mockk<ProductionProcessing>()
        coEvery { processing.operations(any(), any(), any()) } returns ShotOperations(ShotExtensionOperation("v", minDurationSeconds = 2, maxDurationSeconds = 6), listOf(ShotDeriveOperation("up", "超分", listOf(TemplateParam("scale", "倍率", TemplateParamType.NUMBER, JsonPrimitive(2))))))
        val operations = ProductionOperationsSkill { processing }.execute(args("""{"episode_id":"S1E01","shot_code":"SC01-01"}"""), context)
        val json = args(operations.content)
        assertThat(json.getValue("extension").jsonObject.getValue("max_segments").jsonPrimitive.int).isEqualTo(8)
        assertThat(json.getValue("derivations").jsonArray.single().jsonObject.getValue("params").jsonArray).hasSize(1)
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
            ProductionDeriveSkill { processing } to """{"episode_id":"S1E01","shot_code":"SC01-01","take_id":"t1","operation_id":"up","params":{"scale":{}}}""",
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
