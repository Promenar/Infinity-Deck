package com.promenar.infinity.ui.chat

import com.promenar.infinity.data.generation.ContextUsageCategory
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.settings.DefaultModelRole
import com.promenar.infinity.ui.settings.DefaultModelSettingItem
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "上下文管理 浅色", widthDp = 412, heightDp = 650, locale = "en")
@Preview(name = "上下文管理 浅色 大字号130", widthDp = 412, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun sessionContextManagementLightPreview() = SessionContextManagementPreview(dark = false)

@PreviewTest
@Preview(name = "上下文管理 深色", widthDp = 412, heightDp = 650, locale = "zh-rCN")
@Preview(name = "上下文管理 深色 大字号130", widthDp = 412, heightDp = 860, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun sessionContextManagementDarkPreview() = SessionContextManagementPreview(dark = true)

@PreviewTest
@Preview(name = "上下文用量 浅色", widthDp = 412, heightDp = 540, locale = "en")
@Preview(name = "上下文用量 浅色 大字号130", widthDp = 412, heightDp = 680, locale = "en", fontScale = 1.3f)
@Composable
fun contextUsageLightPreview() = ContextUsagePreview(dark = false)

@PreviewTest
@Preview(name = "上下文用量 深色", widthDp = 412, heightDp = 540, locale = "zh-rCN")
@Preview(name = "上下文用量 深色 大字号130", widthDp = 412, heightDp = 680, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun contextUsageDarkPreview() = ContextUsagePreview(dark = true)

@PreviewTest
@Preview(name = "快速模型 浅色", widthDp = 412, heightDp = 440, locale = "en")
@Preview(name = "快速模型 浅色 大字号130", widthDp = 412, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun quickModelLightPreview() = QuickModelPreview(dark = false)

@PreviewTest
@Preview(name = "快速模型 深色", widthDp = 412, heightDp = 440, locale = "zh-rCN")
@Preview(name = "快速模型 深色 大字号130", widthDp = 412, heightDp = 560, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun quickModelDarkPreview() = QuickModelPreview(dark = true)

@Composable
private fun ContextCompactionTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun SessionContextManagementPreview(dark: Boolean) = ContextCompactionTheme(dark) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Large),
    ) {
        SessionContextSettingsSection(
            autoCompactionThreshold = 0.8,
            keepRecentTurns = 5,
            isGenerating = false,
            isCompressing = false,
            onThresholdChange = {},
            onKeepRecentTurnsChange = {},
            onCompactNow = {},
        )
    }
}

@Composable
private fun ContextUsagePreview(dark: Boolean) = ContextCompactionTheme(dark) {
    Box(Modifier.fillMaxSize().padding(InfinitySpacing.Large), contentAlignment = Alignment.TopCenter) {
        Surface(
            modifier = Modifier.width(300.dp).heightIn(max = 480.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            ContextUsagePanel(
                state = ChatViewModel.TokenIndicatorState(
                    used = 91_300,
                    max = 128_000,
                    systemTokens = 3_200,
                    summaryTokens = 8_500,
                    activeTokens = 75_300,
                    ragTokens = 4_300,
                    foldedToolResults = 7,
                    autoCompactionThreshold = 0.8,
                    keepRecentTurns = 5,
                    categories = previewContextCategories(),
                    mcpServerNames = mapOf("gh" to "GitHub"),
                ),
                onManualSummary = {},
                // 两个分区展开，验证列表在构成条以下滚动、按钮固定在底部。
                expandedSections = setOf("system_prompt", "conversation"),
            )
        }
    }
}

@Composable
private fun QuickModelPreview(dark: Boolean) = ContextCompactionTheme(dark) {
    Column(Modifier.fillMaxSize().padding(InfinitySpacing.Large)) {
        InfinitySettingsSection(title = stringResource(R.string.settings_section_ai_models)) {
            DefaultModelSettingItem(
                role = DefaultModelRole.SUMMARY,
                selectedModelName = "Example Chat Model",
                onSelectModel = {},
                onClearModel = {},
            )
        }
    }
}

private fun previewContextCategories(): List<ContextUsageCategory> {
    fun section(id: String, vararg leaves: Pair<String, Int>) =
        ContextUsageCategory(id, leaves.sumOf { it.second }, leaves.map { ContextUsageCategory(it.first, it.second) })
    return listOf(
        section("system_prompt", "assistant_prompt" to 1_200, "session_instructions" to 300, "project_rules" to 2_800, "runtime" to 1_100,
            "permissions" to 900, "tool_guidance" to 2_400, "skill_catalog" to 1_900, "knowledge_catalog" to 0, "history_summary" to 8_500),
        section("tool_definitions", "builtin" to 6_100, "mcp:gh" to 1_800),
        section("conversation", "user_body" to 2_200, "assistant_body" to 14_600, "reasoning" to 9_100, "tool_calls" to 3_400,
            "tool_results" to 26_900, "media" to 4_000, "memory" to 600, "retrieval" to 4_300, "system_time" to 100),
    )
}
