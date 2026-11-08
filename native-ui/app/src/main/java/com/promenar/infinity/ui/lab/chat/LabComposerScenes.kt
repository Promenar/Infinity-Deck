package com.promenar.infinity.ui.lab.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.infinity.R
import com.promenar.infinity.ui.chat.ChatScreenActions
import com.promenar.infinity.ui.chat.ChatScreenState
import com.promenar.infinity.ui.chat.ChatUiState
import com.promenar.infinity.ui.chat.ComposerIslandVariant
import com.promenar.infinity.ui.chat.LocalComposerIslandStyle
import com.promenar.infinity.ui.common.InfinitySegment
import com.promenar.infinity.ui.common.InfinitySegmentedControl
import com.promenar.infinity.ui.lab.stage.LabScene
import com.promenar.infinity.ui.lab.stage.LabSceneScope
import com.promenar.infinity.ui.lab.stage.LabStateScene

/**
 * 浮岛质感对比：真实会话页在三档浮岛之间切换，顶部的切换器选档；选中的档位在四个版本之间保留，
 * 输入框可以真实输入，空会话里还能看到字标散开。
 */
internal val labComposerScenes: Map<String, () -> LabScene> = mapOf(
    // 空会话：粒子字标与空闲浮岛。
    "composer.state.empty" to {
        LabStateScene {
            val zh = isChinese(LocalContext.current.resources)
            val frame = remember(zh) { emptyFrame(zh) }
            ComposerCompare(frame, nowScriptMs = 0, hub = null)
        }
    },
    // 长对话结束后的空闲浮岛。
    "composer.state.idle" to {
        LabStateScene {
            val zh = isChinese(LocalContext.current.resources)
            val frame = remember(zh) { adeFrame(AdeState(LabAdeText(zh), stage = ADE_DONE, explorerSteps = listOf(5, 7))) }
            ComposerCompare(frame, nowScriptMs = AdeTimes.DONE + 60_000)
        }
    },
    // 正文流式输出中：描边流光与外圈光晕。
    "composer.state.streaming" to {
        LabStateScene {
            val zh = isChinese(LocalContext.current.resources)
            val frame = remember(zh) {
                val text = LabAdeText(zh)
                adeFrame(AdeState(text, stage = ADE_EXPLORED, partial = text.explored.take(text.explored.length / 2)))
            }
            ComposerCompare(frame, nowScriptMs = AdeTimes.EXPLORED + 1_300)
        }
    },
    // 执行工具中：浮岛顶部展开运行坞，计划进行到一半。
    "composer.state.tools" to {
        LabStateScene {
            val zh = isChinese(LocalContext.current.resources)
            val frame = remember(zh) { adeFrame(AdeState(LabAdeText(zh), stage = ADE_QUICK_RUN, explorerSteps = listOf(5, 7), quickLines = 4)) }
            ComposerCompare(frame, nowScriptMs = AdeTimes.QUICK_RUN + 1_500)
        }
    },
)

/** 当前进程里选中的档位：在版本之间切换时保留，便于同一档看完四个场景。 */
private object LabComposerChoice {
    var variant by mutableStateOf(ComposerIslandVariant.CURRENT)
}

@Composable
private fun LabSceneScope.ComposerCompare(frame: LabChatFrame, nowScriptMs: Long, hub: LabHubStory? = LabHubStory.ADE) {
    var input by rememberSaveable { mutableStateOf("") }
    val typed = remember(frame, input) { frame.copy(screen = frame.screen.copy(inputText = input)) }
    val actions = remember { ChatScreenActions(onTextChange = { input = it }) }
    val variant = LabComposerChoice.variant
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalComposerIslandStyle provides variant.style) {
            LabChatScene(typed, nowScriptMs = nowScriptMs, actions = actions, hub = hub)
        }
        ComposerVariantSwitcher(
            selected = variant,
            onSelect = { LabComposerChoice.variant = it },
            modifier = Modifier.align(Alignment.TopCenter).windowInsetsPadding(WindowInsets.statusBars).padding(top = 64.dp),
        )
    }
}

@Composable
private fun ComposerVariantSwitcher(selected: ComposerIslandVariant, onSelect: (ComposerIslandVariant) -> Unit, modifier: Modifier) {
    val segments = ComposerIslandVariant.entries.map { InfinitySegment(it, stringResource(it.labelRes)) }
    Surface(
        modifier = modifier.testTag("lab_composer_variant_switcher"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 4.dp,
    ) {
        InfinitySegmentedControl(segments = segments, selected = selected, onSelect = onSelect, compact = true, modifier = Modifier.padding(4.dp))
    }
}

private val ComposerIslandVariant.labelRes: Int
    get() = when (this) {
        ComposerIslandVariant.CURRENT -> R.string.lab_composer_current
        ComposerIslandVariant.SQUARE -> R.string.lab_composer_square
        ComposerIslandVariant.LAYERED -> R.string.lab_composer_layered
    }

private fun emptyFrame(zh: Boolean) = LabChatFrame(
    screen = ChatScreenState(
        uiState = ChatUiState(
            session = labSession(if (zh) "新对话" else "New chat", "full_access"),
            agentName = LAB_AGENT,
            messages = emptyList(),
        ),
        modelDisplayNames = mapOf(LAB_MODEL to LAB_MODEL),
        tokenState = labTokenState(0, 1, startTokens = 7_400, endTokens = 7_400),
    ),
)
