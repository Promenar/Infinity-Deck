package com.promenar.infinity.ui.hub

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.model.Agent
import com.promenar.infinity.domain.model.AgentField
import com.promenar.infinity.domain.model.AgentPromptRevision
import com.promenar.infinity.ui.common.PromptEditorHandle
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private const val MINE = "你是写作助手。\n- 回答控制在三段以内。\n- 使用 Markdown。"
private const val THEIRS = "你是写作助手。\n- 先给结论，再补充必要细节。\n- 使用 Markdown。"

// 编辑页冲突：字段冲突条列出“你的 / 智能体的”两边取值，按钮“采用新内容 / 保留我的”；
// 提示词编辑器内的冲突条带“查看差异”；下方是处理后的一次性说明。
@Composable
private fun AgentEditConflictPage(mode: InfinityThemeMode) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AgentFieldConflictBanner(
                    conflict = AgentEditConflict(
                        fields = setOf(AgentField.DESCRIPTION),
                        remote = Agent(id = "a1", name = "写作助手", description = "润色中文稿件，先给结论"),
                    ),
                    local = { "润色中英文稿件" },
                    onAdopt = {},
                    onKeep = {},
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                val state = rememberTextFieldState(MINE)
                AgentPromptConflictBanner(
                    conflict = AgentPromptConflict(THEIRS),
                    notice = null,
                    handle = PromptEditorHandle(state, onReplace = {}, onClose = {}),
                    onAdopt = { null },
                    onKeep = { Result.success(Unit) },
                    onNoticeShown = {},
                )
                AgentEditNoticeLine(AgentEditNotice.KEPT_MY_PROMPT, onShown = {}, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@PreviewTest
@Preview(name = "智能体编辑 冲突 浅中390", widthDp = 390, heightDp = 700, locale = "zh-rCN")
@Preview(name = "智能体编辑 冲突 浅英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun agentEditConflictPreview() = AgentEditConflictPage(InfinityThemeMode.LIGHT)

private val PreviewRevisions = listOf(
    AgentPromptRevision("r4", "a1", THEIRS, AgentPromptRevision.Source.AGENT, 1_793_588_400_000L),
    AgentPromptRevision("r3", "a1", MINE, AgentPromptRevision.Source.USER, 1_793_502_000_000L),
    AgentPromptRevision("r2", "a1", "你是写作助手。\n- 使用 Markdown。", AgentPromptRevision.Source.ROLLBACK, 1_793_415_600_000L),
    AgentPromptRevision("r1", "a1", "", AgentPromptRevision.Source.USER, 1_793_329_200_000L),
)

// 提示词历史版本页：说明保留规则；每版显示时间、来源与字数，当前版本用主色描边并标“当前”。
@Composable
private fun AgentPromptHistoryPage(mode: InfinityThemeMode, revisions: List<AgentPromptRevision>) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AgentPromptHistoryContent(revisions = revisions, currentPrompt = THEIRS, onBack = {}, onSelect = {})
        }
    }
}

@PreviewTest
@Preview(name = "提示词历史 深中390", widthDp = 390, heightDp = 760, locale = "zh-rCN")
@Preview(name = "提示词历史 深英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun agentPromptHistoryPreview() = AgentPromptHistoryPage(InfinityThemeMode.DARK, PreviewRevisions)

@PreviewTest
@Preview(name = "提示词历史 空 浅中360", widthDp = 360, heightDp = 480, locale = "zh-rCN")
@Composable
fun agentPromptHistoryEmptyPreview() = AgentPromptHistoryPage(InfinityThemeMode.LIGHT, emptyList())
