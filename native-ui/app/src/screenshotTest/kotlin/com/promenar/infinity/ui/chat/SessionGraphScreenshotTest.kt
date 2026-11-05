package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.local.db.dao.KgTurnRelation
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.RagOptions
import com.promenar.infinity.data.model.TurnMemoryState
import com.promenar.infinity.data.rag.SessionGraphQueueStatus
import com.promenar.infinity.data.rag.TurnGraphDetails
import com.promenar.infinity.data.rag.TurnGraphEntity
import com.promenar.infinity.data.rag.TurnGraphState
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

@PreviewTest
@Preview(name = "图谱状态 页脚 深色", widthDp = 411, heightDp = 820, locale = "zh-rCN")
@Composable
fun sessionGraphFooterDarkPreview() = SessionGraphFrame { GraphFooterStates() }

@PreviewTest
@Preview(name = "Graph status footer English 360dp font130", widthDp = 360, heightDp = 960, locale = "en", fontScale = 1.3f)
@Composable
fun sessionGraphFooterNarrowEnglishPreview() = SessionGraphFrame(dark = false) { GraphFooterStates() }

@PreviewTest
@Preview(name = "本轮记忆 含图谱 深色", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun turnMemorySheetWithGraphPreview() = SessionGraphFrame {
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        TurnMemorySheetContent(
            state = TurnMemoryState(TurnMemoryState.Status.STORED, chunkCount = 1),
            details = TurnMemoryDetails(listOf("User: Kotlin 是谁开发的？\nAssistant: Kotlin 由 JetBrains 开发。"), shareWithOtherAgents = true),
            onRemove = {}, onRewrite = {},
        )
        TurnGraphSheetContent(
            state = TurnGraphState(TurnGraphState.Status.DONE, entities = 3, relations = 2),
            details = TurnGraphDetails(
                entities = listOf(TurnGraphEntity("Kotlin", "language"), TurnGraphEntity("JetBrains", "organization"), TurnGraphEntity("Android", "platform")),
                relations = listOf(
                    KgTurnRelation("e1", "Kotlin", "developed_by", "JetBrains"),
                    KgTurnRelation("e2", "Android", "officially_supports", "Kotlin"),
                ),
            ),
            onRetry = {},
            onOpenGraph = {},
        )
    }
}

@PreviewTest
@Preview(name = "Turn graph failed English font130", widthDp = 360, heightDp = 520, locale = "en", fontScale = 1.3f)
@Composable
fun turnGraphSheetFailedEnglishPreview() = SessionGraphFrame(dark = false) {
    TurnGraphSheetContent(
        state = TurnGraphState(TurnGraphState.Status.FAILED, error = "Extraction model returned invalid JSON"),
        details = TurnGraphDetails(emptyList(), emptyList()),
        onRetry = {},
        onOpenGraph = {},
        showTitle = false,
    )
}

@PreviewTest
@Preview(name = "会话知识图谱 并入作品 后台抽取 深色", widthDp = 411, heightDp = 420, locale = "zh-rCN")
@Composable
fun sessionGraphQueuePreview() = SessionGraphFrame {
    val current = GraphChoice(id = "work:demo", group = GraphGroup.Collection, title = "长夜行", detail = "资料库/长夜行", documents = 20, entities = 412)
    SessionGraphBindingSection(
        enabled = true,
        binding = SessionGraphBindingUi(current = current, readOnly = false, contribution = 18 to 9),
        choices = listOf(current),
        onRebind = {},
        onReadOnlyChange = {},
        onRetract = {},
        graphQueue = SessionGraphQueueStatus(pending = 2, failed = 1, todayTurns = 14, todayCalls = 17),
    )
}

@PreviewTest
@Preview(name = "Session graph read only English 360dp font130", widthDp = 360, heightDp = 520, locale = "en", fontScale = 1.3f)
@Composable
fun sessionGraphReadOnlyEnglishPreview() = SessionGraphFrame(dark = false) {
    val current = GraphChoice(id = "shared", group = GraphGroup.Shared, title = "General knowledge", documents = 6, entities = 88)
    SessionGraphBindingSection(
        enabled = true,
        binding = SessionGraphBindingUi(current = current, readOnly = true),
        choices = listOf(current),
        onRebind = {},
        onReadOnlyChange = {},
        onRetract = {},
    )
}

@Composable
private fun GraphFooterStates() {
    val stored = TurnMemoryState(TurnMemoryState.Status.STORED, chunkCount = 2)
    listOf(
        stored to TurnGraphState(TurnGraphState.Status.QUEUED),
        stored to TurnGraphState(TurnGraphState.Status.RUNNING),
        stored to TurnGraphState(TurnGraphState.Status.DONE, entities = 5, relations = 3),
        stored to TurnGraphState(TurnGraphState.Status.PARTIAL, entities = 2, relations = 1),
        stored to TurnGraphState(TurnGraphState.Status.FAILED, error = "超时"),
        null to TurnGraphState(TurnGraphState.Status.DONE, entities = 1, relations = 1),
        TurnMemoryState(TurnMemoryState.Status.WRITING) to TurnGraphState(TurnGraphState.Status.QUEUED),
    ).forEachIndexed { index, (memory, graph) ->
        val message = Message(
            id = "a$index",
            role = MessageRole.ASSISTANT,
            content = "Kotlin 由 JetBrains 开发。",
            modelId = "provider::deepseek-v4-flash",
            vectorizationStatus = memory?.encode(),
            createdAt = 1_760_000_000_000 + index * 60_000L,
        )
        PipelineBubble(
            group = buildPipelineGroups(listOf(message)).single(),
            isGenerating = false,
            streamingContent = "",
            fontSize = 15,
            modelDisplayNames = mapOf("provider::deepseek-v4-flash" to "DeepSeek V4 Flash"),
            onOpenTurnMemory = {},
            turnGraphStates = mapOf(message.id to graph),
            modifier = Modifier.padding(top = if (index == 0) 0.dp else 12.dp),
        )
    }
}

@Composable
private fun SessionGraphFrame(dark: Boolean = true, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp)) {
                content()
            }
        }
    }
}
