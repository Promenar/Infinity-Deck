package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.production.*
import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.tool.ToolRisk
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.*
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProductionGenerationSkillsTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val assistantMessageId = "m"
    }
    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject
    @Test fun `生成参数严格解析不接受缺失未知字段与错误类型`() = runTest {
        val planner = mockk<ShotGenerationPlanner>()
        val skill = ProductionGenerateSkill { planner }
        listOf("{}", """{"episode_id":2,"shot_code":"SC01-01"}""", """{"episode_id":"S1E01","shot_code":"SC01-01","approve":true}""").forEach {
            assertEquals("error", skill.execute(args(it), context).status)
        }
        coVerify(exactly = 0) { planner.generate(any(), any(), any()) }
        assertEquals(ToolRisk.SESSION_STATE, skill.risk)
    }
    @Test fun `生成返回任务编号或拒绝原因`() = runTest {
        val planner = mockk<ShotGenerationPlanner>()
        coEvery { planner.generate("s", "S1E01", "SC01-01") } returns ShotGenerateResult.Submitted("task", true, listOf("audio"))
        val skill = ProductionGenerateSkill { planner }
        val params = args("""{"episode_id":"S1E01","shot_code":"SC01-01"}""")
        val result = args(skill.execute(params, context).content)
        assertEquals("task", result["task_id"]?.jsonPrimitive?.content)
        assertTrue(result.getValue("awaiting_approval").jsonPrimitive.boolean)
        coEvery { planner.generate(any(), any(), any()) } returns ShotGenerateResult.Rejected("场未锁定")
        assertEquals("rejected", args(skill.execute(params, context).content)["status"]?.jsonPrimitive?.content)
    }
    @Test fun `查看任务只返回当前项目且镜头精确匹配并支持分页`() = runTest {
        val store = mockk<ShotGenerationStore>()
        val queue = mockk<MediaGenerationQueue>()
        val p = Production("p", "项目", ProductionKind.FILM, "productions/p", createdAt = 1, updatedAt = 1)
        coEvery { store.load("s") } returns ProductionSnapshot(p, 1, ManifestSync.InSync)
        fun task(id: String, project: String, shot: String) = MediaTask(id, MediaGenerationRequest("c", GenerationMode.TEXT, "内容", emptyList(), ProductionSourceRef(project, "S1E01", shot).encode(), sessionId = "s", outputDir = "takes"), null)
        every { queue.observeTasks() } returns flowOf(listOf(task("yes", "p", "SC01-01"), task("prefix", "p", "SC01-010"), task("other", "other", "SC01-01")))
        val skill = ProductionGenerationTasksSkill({ store }, { queue })
        val result = args(skill.execute(args("""{"episode_id":"S1E01","shot_code":"SC01-01","limit":1}"""), context).content)
        assertEquals("yes", result.getValue("tasks").jsonArray.single().jsonObject.getValue("id").jsonPrimitive.content)
        assertEquals("error", skill.execute(args("""{"shot_code":"SC01-01"}"""), context).status)
        assertEquals("error", skill.execute(args("""{"limit":"1"}"""), context).status)
        assertEquals(ToolRisk.SAFE_READ, skill.risk)
    }
    @Test fun `预览包含超限清单和图片编号`() = runTest {
        val planner = mockk<ShotGenerationPlanner>()
        val input = PlannedInput(1, "林晚", "正面", InputKind.IMAGE, com.promenar.infinity.domain.mediagen.InputRole.REFERENCE, "ref.png")
        coEvery { planner.preview(any(), any(), any()) } returns ShotPlanPreview(null, true, "", ShotMode.REFERENCE, listOf(input), listOf(input.copy(number = 2)), "图1：林晚", false, PromptFormat.SEEDANCE, null, "超限", listOf("参考包待审"))
        val result = args(ProductionPlanSkill { planner }.execute(args("""{"episode_id":"S1E01","shot_code":"SC01-01"}"""), context).content)
        assertEquals(1, result.getValue("inputs").jsonArray.single().jsonObject.getValue("number").jsonPrimitive.int)
        assertEquals(1, result.getValue("overflow").jsonArray.size)
        assertEquals("超限", result.getValue("blocked_reason").jsonPrimitive.content)
    }
}
