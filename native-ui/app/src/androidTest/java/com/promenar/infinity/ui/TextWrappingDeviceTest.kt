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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.then
import androidx.compose.ui.text.TextLayoutResult
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
import com.promenar.infinity.data.skills.SkillSourceGroup
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.ui.chat.ApprovalCard
import com.promenar.infinity.ui.chat.ApprovalDisplayCall
import com.promenar.infinity.ui.chat.ApprovalPreview
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.settings.AgentSkillsSection
import com.promenar.infinity.ui.settings.ProviderListScreenActions
import com.promenar.infinity.ui.settings.ProviderListScreenContent
import com.promenar.infinity.ui.settings.ProviderListScreenState
import com.promenar.infinity.ui.skills.SkillCatalogActions
import com.promenar.infinity.ui.testing.findOrphanLines
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.InfinityUiLineBreak
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 全站换行策略的设备门禁：
 * 1. [InfinityUiLineBreak] 采用 HighQuality + Strict + WordBreak.Default，优先排满首行（消除 Balanced 在 1.1–1.5 行短文本首行产生的右侧大留白），并严格遵守 CJK 避头点规则；
 * 2. [InfinityMetaLine] 保持各元数据分段完整，绝不以分隔符“·”作为新行开头。
 */
@RunWith(AndroidJUnit4::class)
class TextWrappingDeviceTest {
    @get:Rule
    val rule = createComposeRule()

    private val corpus = listOf(
        "硬件检测：12 GB 内存 · arm64 旗舰处理器（当前空闲 3.4 GB）",
        "当前策略：录音中专注低延迟流式识别，暂停与结束时统一精修",
        "流式模型保证边说边出字；独立二遍精修模型（SenseVoice）在停顿或句末自动复核，减少漏词并校准标点与中英混读。",
        "技能包是可复用的任务指令（SKILL.md）。启用后，助手只在任务与技能描述相符时加载其指令与参考文件；技能包不会执行代码。",
        "每次请求的最大工具调用轮次（1–100）。达到后助手停止调用工具并总结进度。",
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
            assertThat(ui.lineBreak).isEqualTo(InfinityUiLineBreak)
            val balanced = ui.copy(
                lineBreak = LineBreak(
                    strategy = LineBreak.Strategy.Balanced,
                    strictness = LineBreak.Strictness.Strict,
                    wordBreak = LineBreak.WordBreak.Phrase,
                ),
            )
            var balancedPrematureGaps = 0
            val kinsokuViolations = mutableListOf<String>()
            for (text in corpus) {
                for (widthDp in 160..420) {
                    val px = with(density) { widthDp.dp.roundToPx() }
                    val minFillAdvancePx = with(density) { 28.dp.toPx() }
                    val uiLayout = measure(measurer, text, ui, px)
                    val balancedLayout = measure(measurer, text, balanced, px)
                    if (uiLayout.lineCount == 2 && balancedLayout.lineCount == 2) {
                        val uiFirstWidth = uiLayout.getLineRight(0) - uiLayout.getLineLeft(0)
                        val balancedFirstWidth = balancedLayout.getLineRight(0) - balancedLayout.getLineLeft(0)
                        if (uiFirstWidth - balancedFirstWidth >= minFillAdvancePx) {
                            balancedPrematureGaps++
                        }
                    }
                    uiLayout.firstKinsokuViolation()?.let { violation ->
                        kinsokuViolations += "${widthDp}dp: $violation ← $text"
                    }
                }
            }
            Log.i("TextWrapping", "balancedPrematureGaps=$balancedPrematureGaps kinsoku=${kinsokuViolations.size}")
            assertWithMessage("HighQuality 相比 Balanced 应在双行短文本中消除首行提前折行留白")
                .that(balancedPrematureGaps).isGreaterThan(0)
            assertWithMessage("界面换行策略出现行首避头标点违规：\n${kinsokuViolations.take(20).joinToString("\n")}")
                .that(kinsokuViolations).isEmpty()
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
                val violations = rule.findKinsokuViolations()
                assertWithMessage("宽度 $width 字号 ${scale}x 出现行首标点违规：$violations").that(violations).isEmpty()
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
                            groups = listOf(SkillSourceGroup(SkillScope.USER, skills)),
                            actions = SkillCatalogActions(),
                            onSetGroupEnabled = { _, _ -> },
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

    private fun TextLayoutResult.firstKinsokuViolation(): String? {
        if (lineCount < 2) return null
        val text = layoutInput.text.text
        for (line in 1 until lineCount) {
            val start = getLineStart(line)
            if (start > 0 && text[start - 1] == '\n') continue
            val lineText = text.substring(start, getLineEnd(line, visibleEnd = true)).trimStart()
            val firstChar = lineText.firstOrNull() ?: continue
            if (firstChar in FORBIDDEN_LINE_START_CHARS) {
                return "L${line + 1} starts with '$firstChar' in \"$lineText\""
            }
        }
        return null
    }

    private fun ComposeContentTestRule.findKinsokuViolations(): List<String> {
        val violations = mutableListOf<String>()
        fun visit(node: SemanticsNode) {
            if (SemanticsActions.GetTextLayoutResult in node.config) {
                val results = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
                for (result in results) {
                    result.firstKinsokuViolation()?.let { violations += it }
                }
            }
            node.children.forEach(::visit)
        }
        onRoot(useUnmergedTree = true).fetchSemanticsNode().let(::visit)
        return violations
    }

    private companion object {
        private const val FORBIDDEN_LINE_START_CHARS = "。，、；：？！）》」』】"
    }
}
