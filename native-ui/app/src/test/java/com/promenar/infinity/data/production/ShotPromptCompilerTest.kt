package com.promenar.infinity.data.production

import com.promenar.infinity.domain.mediagen.*
import com.promenar.infinity.domain.mediagen.InputRole as MediaRole
import org.junit.Assert.*
import org.junit.Test

class ShotPromptCompilerTest {
    private val production = planningProduction()
    private val shot = production.seasons.single().episodes.single().scenes.single().shots.single().copy(dialogue = listOf(ShotDialogue("lin", "你迟到了", "压低声音")), sound = "海浪，脚步声")
    private val inputs = listOf(PlannedInput(1, "林晚 · 正面全身", "风衣", InputKind.IMAGE, MediaRole.REFERENCE, "ref/front.png"), PlannedInput(2, "码头", "场景", InputKind.IMAGE, MediaRole.REFERENCE, "ref/pier.png"))
    @Test fun `H3参考格式保留六段顺序与编号`() {
        val prompt = ShotPromptCompiler.compile(production, shot, inputs, PromptFormat.H3)
        val sections = listOf("subject_definitions:", "summary:", "retention_analysis:", "detailed_description:", "overall_soundscape:", "non_diegetic_music:")
        assertEquals(sections, sections.sortedBy { prompt.indexOf(it) })
        assertTrue(prompt.contains("<Picture 1>（图1）")); assertTrue(prompt.contains("<Picture 2>（图2）"))
        assertTrue(prompt.contains("迟疑，眼神警惕")); assertTrue(prompt.contains("压低声音")); assertTrue(prompt.contains("<d>[Chinese] 你迟到了</d>")); assertTrue(prompt.contains("(S1)"))
    }
    @Test fun `H3首尾帧声明对齐时间并包含三段结构`() {
        val prompt = ShotPromptCompiler.compile(production, shot.copy(mode = ShotMode.FIRST_LAST), inputs, PromptFormat.H3)
        assertTrue(prompt.contains("0.00 秒首帧")); assertTrue(prompt.contains("5.00 秒尾帧"))
        assertTrue(prompt.indexOf("integrated_multimodal_description:") < prompt.indexOf("overall_soundscape:"))
        assertTrue(prompt.endsWith("non_diegetic_music:\n无"))
    }
    @Test fun `Seedance自然语言引用图号并保留分镜事实`() {
        val prompt = ShotPromptCompiler.compile(production, shot, inputs, PromptFormat.SEEDANCE)
        assertTrue(prompt.startsWith("图1：林晚")); assertTrue(prompt.contains("图2：码头")); assertTrue(prompt.contains("缓慢推近"))
        assertTrue(prompt.contains("海浪，脚步声")); assertFalse(prompt.contains("subject_definitions:"))
    }
    @Test fun `通用格式不添加不存在的剧情和风格`() {
        val prompt = ShotPromptCompiler.compile(production, shot, inputs, PromptFormat.GENERIC)
        assertTrue(prompt.contains("递出信封")); assertTrue(prompt.contains("图2：码头")); assertFalse(prompt.contains("冷色夜景"))
        assertFalse(prompt.contains("integrated_multimodal_description:"))
    }
}
