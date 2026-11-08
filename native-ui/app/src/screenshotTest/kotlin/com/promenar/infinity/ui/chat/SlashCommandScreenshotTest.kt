package com.promenar.infinity.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.skills.DiscoveredSkill
import com.promenar.infinity.data.skills.SkillScope
import com.promenar.infinity.data.skills.SkillStatus
import com.promenar.infinity.ui.chat.command.SlashCommandCatalog
import com.promenar.infinity.ui.chat.command.rememberBuiltinSlashCommands
import com.promenar.infinity.ui.theme.*

// 生成中输入 `/`：排队托盘与命令候选同在输入浮岛内上下排列，候选最多约五行、可滚动，
// 技能行标注来源（全局、工作区）。
@PreviewTest
@Preview(name = "斜杠命令 生成中 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun slashCommandPaletteDarkPreview() = SlashCommandPage(english = false, inputText = "/", generating = true)

// 输入 `/re` 按前缀过滤：命令名前缀优先，其次是别名前缀，最后是包含匹配。
@PreviewTest
@Preview(name = "斜杠命令 过滤 浅英360 大字号", widthDp = 360, heightDp = 892, locale = "en", fontScale = 1.3f)
@Composable
fun slashCommandPaletteLightPreview() =
    SlashCommandPage(english = true, inputText = "/re", generating = false, mode = InfinityThemeMode.LIGHT)

private fun previewSkill(name: String, scope: SkillScope, description: String) = DiscoveredSkill(
    id = "${scope.name}:$name", scope = scope, name = name, description = description,
    displayName = null, shortDescription = null,
    virtualDir = if (scope == SkillScope.USER) "/skills/$name" else "/.agents/skills/$name",
    files = emptyList(), status = SkillStatus.ENABLED, modifiedSinceConfirmed = false,
    allowImplicitInvocation = true, brokenReason = null, metadata = null,
)

@Composable
private fun SlashCommandPage(
    english: Boolean,
    inputText: String,
    generating: Boolean,
    mode: InfinityThemeMode = InfinityThemeMode.DARK,
) {
    val skills = if (english) {
        listOf(
            previewSkill("weekly-report", SkillScope.USER, "Turn notes into a weekly progress report"),
            previewSkill("release-notes", SkillScope.PROJECT, "Draft release notes from recent commits"),
        )
    } else {
        listOf(
            previewSkill("weekly-report", SkillScope.USER, "把零散笔记整理成周报"),
            previewSkill("release-notes", SkillScope.PROJECT, "根据最近的提交起草发布说明"),
        )
    }
    val question = if (english) "Summarize what changed in the repo this week." else "总结一下这周仓库里有哪些改动。"
    val answer = if (english) {
        "Three areas changed: the chat composer, the skill catalog and the export flow."
    } else {
        "这周主要改了三处：输入框、技能目录和导出流程。"
    }
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            val builtins = rememberBuiltinSlashCommands()
            ChatScreenContent(
                state = ChatScreenState(
                    uiState = ChatUiState(
                        session = Session("slash", "assistant", title = if (english) "Weekly sync" else "周会准备", modelId = "deepseek::v4-flash"),
                        agentName = "Infinity Deck",
                        messages = listOf(
                            Message("user-1", MessageRole.USER, question, createdAt = 1700000000000L),
                            Message("assistant-1", MessageRole.ASSISTANT, answer, modelId = "deepseek::v4-flash", createdAt = 1700000030000L),
                        ),
                        isGenerating = generating,
                        status = if (generating) GenerationStatus.RECEIVING else GenerationStatus.IDLE,
                    ),
                    inputText = inputText,
                    modelDisplayNames = mapOf("deepseek::v4-flash" to "DeepSeek V4 Flash"),
                    tokenState = ChatViewModel.TokenIndicatorState(used = 9800, max = 128000),
                    composerQueue = if (generating) {
                        ComposerQueueUiState(listOf(QueuedMessageUi("q1", if (english) "Then list the open issues" else "然后列出还没解决的问题")))
                    } else {
                        ComposerQueueUiState()
                    },
                    slashCommands = SlashCommandCatalog.build(builtins, skills),
                ),
                actions = ChatScreenActions(),
            )
        }
    }
}
