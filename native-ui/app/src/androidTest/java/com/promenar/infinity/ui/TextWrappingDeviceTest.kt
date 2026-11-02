package com.promenar.infinity.ui

import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.then
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.ui.skills.SkillCatalogActions
import com.promenar.infinity.ui.chat.ApprovalCard
import com.promenar.infinity.ui.chat.ApprovalDisplayCall
import com.promenar.infinity.ui.chat.ApprovalPreview
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.settings.AgentSkillsSection
import com.promenar.infinity.ui.settings.ProviderListScreenActions
import com.promenar.infinity.ui.settings.ProviderListScreenContent
import com.promenar.infinity.ui.settings.ProviderListScreenState
import com.promenar.infinity.ui.testing.findOrphanLines
import com.promenar.infinity.ui.testing.orphanLastLine
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityTypography
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 全站换行策略的设备门禁：在中文、窄屏与放大字号下，界面文本不得出现末行孤字。
 * 贪心换行对照组用于证明判定器能复现原问题。
 */
@RunWith(AndroidJUnit4::class)
class TextWrappingDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val corpus = listOf(
        "OpenAI 兼容（通用） · 已启用 · API Key 已配置",
        "技能包是可复用的任务指令（SKILL.md）。启用后，助手只在任务与技能描述相符时加载其指令与参考文件；技能包不会执行代码。",
        "选择会被保存；在安全沙箱可用前，自定义脚本不会被广告或执行。",
        "每次请求的最大工具调用轮次（1–100）。达到后助手停止调用工具并总结进度。",
        "将工作区文件与目录移入回收站，用户可在界面中恢复。",
        "根据提交记录与变更文件撰写结构化发行说明，适用于版本发布前整理。",
    )

    @Test
    fun uiTypographyAvoidsOrphansThatGreedyBreakingProduces() {
        lateinit var measurer: TextMeasurer
        lateinit var density: Density
        rule.setContent {
            measurer = rememberTextMeasurer()
            density = androidx.compose.ui.platform.LocalDensity.current
        }
        rule.runOnIdle {
            val ui = InfinityTypography.bodyMedium
            val greedy = ui.copy(lineBreak = LineBreak.Simple)
            var greedyOrphans = 0
            val uiOrphans = mutableListOf<String>()
            for (text in corpus) {
                for (widthDp in 140..420) {
                    val px = with(density) { widthDp.dp.roundToPx() }
                    if (measure(measurer, text, greedy, px).orphanLastLine() != null) greedyOrphans++
                    measure(measurer, text, ui, px).orphanLastLine()?.let { uiOrphans += "${widthDp}dp: ${it.lastLine} ← $text" }
                }
            }
            Log.i("TextWrapping", "greedy=$greedyOrphans ui=${uiOrphans.size}")
            assertWithMessage("贪心换行对照组应能复现孤字").that(greedyOrphans).isGreaterThan(0)
            assertWithMessage("界面换行策略仍出现孤字：\n${uiOrphans.take(20).joinToString("\n")}")
                .that(uiOrphans).isEmpty()
        }
    }

    @Test
    fun providerSkillAndApprovalSurfacesHaveNoOrphansInChineseNarrowLargeFont() {
        var current by mutableStateOf(300.dp to 1f)
        rule.setContent { Surfaces(width = current.first, fontScale = current.second) }
        for (width in listOf(300.dp, 340.dp, 380.dp)) {
            for (scale in listOf(1f, 1.3f)) {
                rule.runOnIdle { current = width to scale }
                rule.waitForIdle()
                val orphans = rule.findOrphanLines()
                assertWithMessage("宽度 $width 字号 ${scale}x 出现孤字：$orphans").that(orphans).isEmpty()
            }
        }
    }

    @Test
    fun metaLineKeepsSegmentsWholeAndNeverStartsLineWithSeparator() {
        rule.setContent {
            InfinityTheme(dynamicColor = false) {
                Box(Modifier.width(150.dp)) {
                    InfinityMetaLine(
                        segments = listOf("OpenAI 兼容（通用）", "已启用", "API Key 已配置"),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        val orphans = rule.findOrphanLines()
        assertThat(orphans).isEmpty()
        rule.onAllNodes(androidx.compose.ui.test.hasText("API Key 已配置"), useUnmergedTree = true)
            .assertCountEquals(1)
    }

    @Composable
    private fun Surfaces(width: Dp, fontScale: Float) {
        DeviceConfigurationOverride(
            DeviceConfigurationOverride.Locales(androidx.compose.ui.text.intl.LocaleList("zh-CN")) then
                DeviceConfigurationOverride.FontScale(fontScale),
        ) {
            InfinityTheme(dynamicColor = false) {
                Box(Modifier.width(width)) {
                    androidx.compose.foundation.layout.Column {
                        ProviderListScreenContent(
                            state = ProviderListScreenState(providers = providers),
                            actions = ProviderListScreenActions(),
                        )
                        AgentSkillsSection(
                            skills = skills,
                            actions = SkillCatalogActions(),
                            onImport = {},
                            onCreate = {},
                        )
                        ApprovalCard(
                            toolName = "patch_file",
                            description = "执行模式：半自动，写入前需要你确认",
                            calls = listOf(
                                ApprovalDisplayCall(
                                    name = "patch_file",
                                    argumentsSummary = "{\"path\":\"/notes/plan.md\"}",
                                    riskLabel = "编辑",
                                    preview = ApprovalPreview.Preview(
                                        ApprovalPreview.Action.EDIT,
                                        "/notes/plan.md",
                                        "~ L3–4\n  + 完成工具预算收尾",
                                    ),
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }

    private val providers = listOf(
        ProviderListItem(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.promenar.com/v1",
            protocolType = ProtocolType.Generic_OpenAI_Compat,
            hasApiKey = true,
            enabled = true,
        ),
        ProviderListItem(
            id = "anthropic",
            name = "Anthropic 官方接口（企业账号，海外线路）",
            baseUrl = "https://api.anthropic.example.com/v1/messages",
            protocolType = ProtocolType.Anthropic_Messages,
            hasApiKey = false,
            enabled = false,
        ),
    )

    private val skills = listOf(
        DiscoveredSkill(
            id = "user:release-notes",
            scope = SkillScope.USER,
            name = "release-notes",
            description = "根据提交记录与变更文件撰写结构化发行说明，适用于版本发布前整理。",
            displayName = null,
            shortDescription = null,
            virtualDir = "/skills/release-notes",
            files = listOf("templates/notes.md"),
            status = SkillStatus.ENABLED,
            modifiedSinceConfirmed = false,
            allowImplicitInvocation = true,
            brokenReason = null,
            metadata = null,
        ),
    )

    private fun measure(measurer: TextMeasurer, text: String, style: TextStyle, widthPx: Int) =
        measurer.measure(text, style, constraints = Constraints(maxWidth = widthPx))
}
