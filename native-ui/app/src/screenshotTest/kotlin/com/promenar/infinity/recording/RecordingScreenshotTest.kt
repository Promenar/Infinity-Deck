package com.promenar.infinity.recording

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.theme.*

private fun recordingSample(phase: RecordingPhase = RecordingPhase.Finished) = RecordingSession(
    id = "recording-preview",
    createdAt = 0L,
    title = "产品讨论 Product review",
    phase = phase,
    audioBytes = 92_000L * 32,
    segments = listOf(
        RecordingSegment(0, 26_000, "今天确认录音转写的交付范围。The transcript keeps the original wording."),
        RecordingSegment(26_000, 52_000, "逐字稿保留时间点，整理稿按议题分段。Review the English terminology."),
    ),
    organized = "产品讨论\n\n录音转写保留逐字稿与时间点，整理稿按议题分段。\n\nThe transcript preserves timestamps and original wording.",
)

@Composable
private fun RecordingFrame(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun RecordingExample(phase: RecordingPhase, organized: Boolean = false) {
    val session = recordingSample(phase)
    val active = phase == RecordingPhase.Recording || phase == RecordingPhase.Paused
    RecordingPageContent(
        state = RecordingScreenState(
            sessions = listOf(session),
            activeId = session.id.takeIf { active },
            durationMs = 92_000,
            levels = List(48) { ((it * 7) % 13) / 13f },
            partial = "下一项：核对中英文界面。Check the controls.",
            ready = true,
            localLabel = "Qwen3-0.6B",
        ),
        current = session,
        organized = organized,
        route = RecordingRoute.Local,
        positionMs = 26_000f,
    )
}

@PreviewTest
@Preview(name = "录音中 深中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "录音中 深英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun recordingRunningPreview() = RecordingFrame(InfinityThemeMode.DARK) { RecordingExample(RecordingPhase.Recording) }

@PreviewTest
@Preview(name = "录音暂停 浅中360 大字", widthDp = 360, heightDp = 800, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "录音暂停 浅英360", widthDp = 360, heightDp = 800, locale = "en")
@Composable
fun recordingPausedPreview() = RecordingFrame(InfinityThemeMode.LIGHT) { RecordingExample(RecordingPhase.Paused) }

@PreviewTest
@Preview(name = "逐字稿 浅英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Preview(name = "逐字稿 浅中840 内屏", widthDp = 840, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun recordingTranscriptPreview() = RecordingFrame(InfinityThemeMode.LIGHT) { RecordingExample(RecordingPhase.Finished) }

@PreviewTest
@Preview(name = "整理稿 深中360 大字", widthDp = 360, heightDp = 800, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "整理稿 深英840 内屏", widthDp = 840, heightDp = 720, locale = "en")
@Composable
fun recordingOrganizedPreview() = RecordingFrame(InfinityThemeMode.DARK) { RecordingExample(RecordingPhase.Finished, organized = true) }

@PreviewTest
@Preview(name = "编辑录音 浅中360 大字", widthDp = 360, heightDp = 640, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun recordingEditLightPreview() = RecordingFrame(InfinityThemeMode.LIGHT) { RecordingEditExample() }

@PreviewTest
@Preview(name = "编辑录音 深英360 大字", widthDp = 360, heightDp = 640, locale = "en", fontScale = 1.3f)
@Preview(name = "编辑录音 深英360 矮窗口", widthDp = 360, heightDp = 360, locale = "en", fontScale = 1.3f)
@Composable
fun recordingEditDarkPreview() = RecordingFrame(InfinityThemeMode.DARK) { RecordingEditExample() }

@Composable
private fun RecordingEditExample() {
    val sample = recordingSample()
    Column(Modifier.padding(InfinitySpacing.ScreenHorizontal)) {
        RecordingEditorContent(sample.title, sample.glossary, sample.organized, {}, {}, {}, {}, {})
    }
}

@PreviewTest
@Preview(name = "录音方式 浅英360 大字", widthDp = 360, locale = "en", fontScale = 1.3f)
@Preview(name = "录音方式 浅中360", widthDp = 360, locale = "zh-rCN")
@Composable
fun recordingModesPreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    Column(Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal)) {
        RecordingModeOptions(RecordingMode.Auto) {}
    }
}
