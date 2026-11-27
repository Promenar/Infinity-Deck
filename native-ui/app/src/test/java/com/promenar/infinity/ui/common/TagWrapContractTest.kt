package com.promenar.infinity.ui.common

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import org.junit.Test

/**
 * 标签与选项不再平铺换行：数量会增长的筛选用分段滑块或摘要行加面板，只读数据标签单行横向滚动，
 * 按钮组放不下时整组竖排。流式换行只保留给数量固定且只在极窄屏才换行的状态徽标、色条图例与对齐网格。
 */
class TagWrapContractTest {
    private val sourceRoot = Path.of("app/src/main/java/com/promenar/infinity/ui")

    /** 允许保留流式换行的文件：每处只有 1–4 个固定徽标或图例，或是固定列数的对齐网格。 */
    private val allowedFlowRowFiles = setOf(
        "common/CacheSegmentBar.kt",
        "common/params/SamplingParamsEditor.kt",
        "linux/output/CommandOutputScreen.kt",
        "linux/settings/LinuxManageContent.kt",
        // 场头卡片仅含「场号 · 编号」与当前审核状态 2 枚固定徽标，窄屏大字号下自动折行防挤压。
        "production/ProductionManagement.kt",
        "settings/AgentSkillsSection.kt",
        // 模型编辑页仅含 9 项固定枚举类型（两行全可见防横向裁切）与至多 9 项固定能力微标摘要。
        "settings/ModelEditorScreen.kt",
        // 提供商下拉预设行与已选摘要行仅含品牌名与至多 2 枚固定权益微标，窄屏大字号下自动折行防挤压。
        "settings/ProviderFormScreen.kt",
        "settings/TailscalePeerPath.kt",
        "settings/ThemeScreen.kt",
        // 新手引导下拉预设行与已选摘要行复用同等至多 2 枚固定权益微标。
        "welcome/OnboardingProviderStep.kt",
        "workspace/AgentReachSheet.kt",
        "workspace/WorkspaceKindTags.kt",
        "workspace/WorkspaceSwitchStatus.kt",
    )

    @Test
    fun `流式换行只出现在约定保留的固定徽标处`() {
        val offenders = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" }
                .filter { Files.readAllBytes(it).toString(Charsets.UTF_8).contains("FlowRow(") }
                .map { it.relativeTo(sourceRoot).invariantSeparatorsPathString }
                .filter { it !in allowedFlowRowFiles }
                .toList()
        }
        assertWithMessage("新增的流式换行，改用分段滑块、摘要行加面板、单行横向滚动或整组竖排按钮").that(offenders).isEmpty()
    }

    @Test
    fun `已移除放不下就换行的选项标签组件`() {
        val users = Files.walk(sourceRoot).use { paths ->
            paths.filter { it.isRegularFile() && it.extension == "kt" }
                .filter { Files.readAllBytes(it).toString(Charsets.UTF_8).contains("InfinityChoiceChips") }
                .map { it.relativeTo(sourceRoot).invariantSeparatorsPathString }
                .toList()
        }
        assertThat(users).isEmpty()
    }

    @Test
    fun `按钮横排宽度为自然宽度之和加间距`() {
        assertThat(buttonRowWidth(emptyList(), gap = 8)).isEqualTo(0)
        assertThat(buttonRowWidth(listOf(120), gap = 8)).isEqualTo(120)
        assertThat(buttonRowWidth(listOf(120, 90, 100), gap = 8)).isEqualTo(326)
    }

    @Test
    fun `摘要按选项顺序列出已选项，未选时为空`() {
        val choices = listOf(InfinityChoice("a", "视觉"), InfinityChoice("b", "工具"), InfinityChoice("c", "联网"))
        assertThat(selectedChoiceSummary(choices, setOf("c", "a"), "、")).isEqualTo("视觉、联网")
        assertThat(selectedChoiceSummary(choices, emptySet(), "、")).isNull()
        assertThat(selectedChoiceSummary(choices, setOf("x"), "、")).isNull()
    }
}
