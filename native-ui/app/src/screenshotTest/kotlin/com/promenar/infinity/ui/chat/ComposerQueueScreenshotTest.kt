package com.promenar.infinity.ui.chat

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.*
import com.promenar.infinity.ui.theme.*

// 生成中排队：活动栏下方是排队托盘（一条已插队、一条带附件），输入框已写下一条，
// 发送键为排队发送，左侧是次级停止键。
@PreviewTest
@Preview(name = "排队 生成中 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun composerQueueGeneratingDarkPreview() = ComposerQueuePage(english = false, generating = true)

@PreviewTest
@Preview(name = "排队 生成中 浅英360 大字号", widthDp = 360, heightDp = 892, locale = "en", fontScale = 1.3f)
@Composable
fun composerQueueGeneratingLightPreview() =
    ComposerQueuePage(english = true, generating = true, mode = InfinityThemeMode.LIGHT)

// 停止后暂停：托盘顶部说明原因并提供“继续”，超过三条时折叠为“展开全部”。
@PreviewTest
@Preview(name = "排队 已暂停 深中411", widthDp = 411, heightDp = 892, locale = "zh-rCN")
@Composable
fun composerQueuePausedDarkPreview() = ComposerQueuePage(english = false, generating = false)

@PreviewTest
@Preview(name = "排队 已暂停 浅英360", widthDp = 360, heightDp = 892, locale = "en")
@Composable
fun composerQueuePausedLightPreview() =
    ComposerQueuePage(english = true, generating = false, mode = InfinityThemeMode.LIGHT)

private fun queueItems(english: Boolean, generating: Boolean): List<QueuedMessageUi> {
    val texts = if (english) {
        listOf(
            "Skip the MP4 copies and only look at the chart image",
            "Then compare it with last week's numbers",
            "Write the summary as a short table",
            "Also list anything you could not open",
        )
    } else {
        listOf(
            "跳过两个 mp4 副本，只看那张图表",
            "然后和上周的数据对比一下",
            "总结写成一个简短的表格",
            "另外列出打不开的文件",
        )
    }
    return if (generating) {
        listOf(
            QueuedMessageUi("q1", texts[0], steering = true),
            QueuedMessageUi("q2", texts[1], imageCount = 2, documentNames = listOf("weekly.csv")),
        )
    } else {
        texts.mapIndexed { index, text -> QueuedMessageUi("q$index", text, imageCount = if (index == 1) 1 else 0) }
    }
}

@Composable
private fun ComposerQueuePage(
    english: Boolean,
    generating: Boolean,
    mode: InfinityThemeMode = InfinityThemeMode.DARK,
) {
    val question = if (english) {
        "Look through the media test folder and tell me what is inside each file."
    } else {
        "看看 media/test 目录，告诉我每个文件里是什么。"
    }
    val answer = if (english) {
        "The folder holds four files: two MP4 clips, one chart image and one tone recording."
    } else {
        "目录里有 4 个文件：两个 mp4 片段、一张图表和一段音频。"
    }
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatScreenContent(
                state = ChatScreenState(
                    uiState = ChatUiState(
                        session = Session("queue", "assistant", title = if (english) "Media test" else "媒体测试", modelId = "deepseek::v4-flash"),
                        agentName = "Infinity Deck",
                        messages = listOf(
                            Message("user-1", MessageRole.USER, question, createdAt = 1700000000000L),
                            Message("assistant-1", MessageRole.ASSISTANT, answer, modelId = "deepseek::v4-flash", createdAt = 1700000030000L),
                        ),
                        isGenerating = generating,
                        status = if (generating) GenerationStatus.RECEIVING else GenerationStatus.IDLE,
                    ),
                    inputText = if (generating) {
                        if (english) "And keep it under 100 words" else "控制在一百字以内"
                    } else {
                        ""
                    },
                    modelDisplayNames = mapOf("deepseek::v4-flash" to "DeepSeek V4 Flash"),
                    tokenState = ChatViewModel.TokenIndicatorState(used = 9800, max = 128000),
                    composerQueue = ComposerQueueUiState(
                        items = queueItems(english, generating),
                        pauseReason = if (generating) null else ComposerQueuePauseReason.STOPPED,
                    ),
                ),
                actions = ChatScreenActions(),
            )
        }
    }
}
