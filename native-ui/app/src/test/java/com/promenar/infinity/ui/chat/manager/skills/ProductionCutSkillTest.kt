package com.promenar.infinity.ui.chat.manager.skills

import com.promenar.infinity.data.cut.*
import com.promenar.infinity.data.production.*
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProductionCutSkillTest {
    private val context = object : SkillExecutionContext {
        override val sessionId = "s"
        override val agentId = "a"
        override val workspacePath: String? = null
        override val workspaceRootUuid = "root"
        override val assistantMessageId = "m"
    }
    private fun args(value: String) = Json.parseToJsonElement(value).jsonObject
    private val version = CutVersion("cut", "S1E01", 1, 1, listOf(CutClip("S1E01", "SC01-01", seconds = 2.5)))
    private fun snapshot() = ProductionSnapshot(Production("p", "项目", ProductionKind.FILM, "p", createdAt = 1, updatedAt = 1,
        cuts = listOf(version), seasons = listOf(Season("S1", 1, episodes = listOf(Episode("S1E01", 1, "集"))))), 1, ManifestSync.InSync)

    @Test fun `动作参数拒绝缺失未知字段和错误类型`() = runTest {
        val controller = mockk<CutExportController>()
        val skill = ProductionCutSkill({ error("不应读库") }, { controller })
        listOf("{}", """{"action":"remove"}""", """{"action":"create"}""", """{"action":"export","versionId":3}""",
            """{"action":"export","versionId":"cut","burnSubtitles":"true"}""", """{"action":"list","extra":1}""",
            """{"action":"status","versionId":"cut","burnSubtitles":false}""", """{"action":"list","episodeId":null}""").forEach {
            assertEquals(it, "error", skill.execute(args(it), context).status)
        }
        coVerify(exactly = 0) { controller.export(any(), any()) }
    }

    @Test fun `列表提供缺口总时长和导出字段`() = runTest {
        val skill = ProductionCutSkill({ snapshot() }, { mockk() })
        val result = args(skill.execute(args("""{"action":"list"}"""), context).content).getValue("versions").jsonArray.single().jsonObject
        assertEquals(1, result.getValue("gapCount").jsonPrimitive.int)
        assertEquals(2.5, result.getValue("durationSeconds").jsonPrimitive.double, 0.0)
        assertTrue(result.containsKey("export"))
    }

    @Test fun `发起导出不等待并且状态读取当前进度`() = runTest {
        val controller = mockk<CutExportController>()
        coEvery { controller.export("s", "cut", true) } just Runs
        every { controller.observe() } returns MutableStateFlow(mapOf("cut" to CutExportState.Running(0.4f)))
        val skill = ProductionCutSkill({ snapshot() }, { controller })
        val accepted = args(skill.execute(args("""{"action":"export","versionId":"cut","burnSubtitles":true}"""), context).content)
        assertEquals("accepted", accepted.getValue("status").jsonPrimitive.content)
        coVerify(exactly = 1) { controller.export("s", "cut", true) }
        val result = args(skill.execute(args("""{"action":"status","versionId":"cut"}"""), context).content)
        assertEquals("running", result.getValue("status").jsonPrimitive.content)
        assertEquals(0.4f, result.getValue("progress").jsonPrimitive.float, 0.00001f)
    }

    @Test fun `查询其它项目版本被拒绝且无内存状态时读取持久结果`() = runTest {
        val controller = mockk<CutExportController>()
        val record = CutExportRecord("p/cuts/S1E01-v1.mp4", "p/cuts/S1E01-v1.srt", 2.5, 10, 2)
        every { controller.observe() } returns MutableStateFlow(emptyMap())
        val skill = ProductionCutSkill({ snapshot().let { it.copy(production = it.production.copy(cuts = listOf(version.copy(export = record)))) } }, { controller })
        assertEquals("error", skill.execute(args("""{"action":"status","versionId":"other"}"""), context).status)
        assertEquals("succeeded", args(skill.execute(args("""{"action":"status","versionId":"cut"}"""), context).content).getValue("status").jsonPrimitive.content)
    }
}
