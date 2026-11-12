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
import com.promenar.infinity.R
import com.promenar.infinity.ui.theme.*

/*
 * 录音听写页：外屏 360dp 为录音卡加页签，内屏 840dp 为通栏录音卡加逐字稿、整理稿双栏。
 * 覆盖待录音、录音中、暂停、逐字稿（播放中高亮）、整理稿、云端待确认、整理失败提示、
 * 已保存到资料、矮窗口整页滚动，以及编辑面板与录音方式弹层；深浅色、中英文、1.3 倍字号交叉取样。
 * 示例数据均为虚构。
 */

private const val SAMPLE_ORGANIZED =
    "产品讨论\n\n录音转写保留逐字稿与时间点，整理稿按议题分段。\n\nThe transcript preserves timestamps and original wording."

private fun recordingSample(
    phase: RecordingPhase = RecordingPhase.Finished,
    organized: String = SAMPLE_ORGANIZED,
    documentId: String? = null,
    segments: Int = 2,
) = RecordingSession(
    id = "recording-preview",
    createdAt = 0L,
    title = "产品讨论 Product review",
    phase = phase,
    audioBytes = 92_000L * 32,
    segments = listOf(
        RecordingSegment(0, 26_000, "今天确认录音转写的交付范围。The transcript keeps the original wording."),
        RecordingSegment(26_000, 52_000, "逐字稿保留时间点，整理稿按议题分段。Review the English terminology."),
    ).take(segments),
    organized = organized,
    documentId = documentId,
)

@Composable
private fun RecordingFrame(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

@Composable
private fun RecordingExample(
    session: RecordingSession?,
    organized: Boolean = false,
    route: RecordingRoute = RecordingRoute.Local,
    failure: Int? = null,
    playing: Boolean = false,
    partial: String = "下一项：核对中英文界面。Check the controls.",
    history: List<RecordingSession> = listOfNotNull(session),
) {
    val active = session?.phase == RecordingPhase.Recording || session?.phase == RecordingPhase.Paused
    RecordingPageContent(
        state = RecordingScreenState(
            sessions = history,
            activeId = session?.id.takeIf { active },
            durationMs = 92_000,
            levels = List(48) { ((it * 7) % 13) / 13f },
            partial = partial,
            failure = failure,
            ready = true,
            cloudLabel = "DeepSeek · deepseek-v4-flash",
            localLabel = "Qwen3-0.6B",
        ),
        current = session,
        organized = organized,
        route = route,
        playing = playing,
        positionMs = 26_000f,
    )
}

private fun recordingHistory() = listOf(
    recordingSample().copy(id = "history-1", title = "产品讨论 Product review"),
    recordingSample(RecordingPhase.Interrupted).copy(id = "history-2", title = "周会 Weekly sync", audioBytes = 1_260_000L * 32),
    recordingSample().copy(id = "history-3", title = "访谈：离线同步 Offline sync interview", audioBytes = 3_725_000L * 32),
)

@PreviewTest
@Preview(name = "待录音 深中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun recordingIdlePreview() = RecordingFrame(InfinityThemeMode.DARK) {
    RecordingExample(session = null, history = recordingHistory())
}

@PreviewTest
@Preview(name = "待录音 浅英840 内屏 大字", widthDp = 840, heightDp = 720, locale = "en", fontScale = 1.3f)
@Composable
fun recordingIdleWidePreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    RecordingExample(session = null, history = recordingHistory())
}

@PreviewTest
@Preview(name = "待录音 无记录 浅中840 内屏", widthDp = 840, heightDp = 720, locale = "zh-rCN")
@Composable
fun recordingIdleEmptyWidePreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    RecordingExample(session = null, history = emptyList())
}

@PreviewTest
@Preview(name = "录音中 深中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "录音中 深英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Preview(name = "录音中 深中840 内屏", widthDp = 840, heightDp = 720, locale = "zh-rCN")
@Composable
fun recordingRunningPreview() = RecordingFrame(InfinityThemeMode.DARK) { RecordingExample(recordingSample(RecordingPhase.Recording)) }

@PreviewTest
@Preview(name = "录音刚开始 浅中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "录音刚开始 浅英840 内屏", widthDp = 840, heightDp = 720, locale = "en")
@Composable
fun recordingListeningPreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    RecordingExample(recordingSample(RecordingPhase.Recording, organized = "", segments = 0), partial = "")
}

@PreviewTest
@Preview(name = "录音暂停 浅中360 大字", widthDp = 360, heightDp = 800, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "录音暂停 浅英360", widthDp = 360, heightDp = 800, locale = "en")
@Preview(name = "录音暂停 浅英840 内屏 大字", widthDp = 840, heightDp = 720, locale = "en", fontScale = 1.3f)
@Composable
fun recordingPausedPreview() = RecordingFrame(InfinityThemeMode.LIGHT) { RecordingExample(recordingSample(RecordingPhase.Paused)) }

@PreviewTest
@Preview(name = "逐字稿 浅英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Preview(name = "逐字稿 浅中840 内屏", widthDp = 840, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun recordingTranscriptPreview() = RecordingFrame(InfinityThemeMode.LIGHT) { RecordingExample(recordingSample(), playing = true) }

@PreviewTest
@Preview(name = "整理稿 深中360 大字", widthDp = 360, heightDp = 800, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "整理稿 深英840 内屏", widthDp = 840, heightDp = 720, locale = "en")
@Composable
fun recordingOrganizedPreview() = RecordingFrame(InfinityThemeMode.DARK) { RecordingExample(recordingSample(), organized = true) }

@PreviewTest
@Preview(name = "云端待确认 浅中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "云端待确认 浅英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Preview(name = "云端待确认 浅中840 内屏", widthDp = 840, heightDp = 720, locale = "zh-rCN")
@Composable
fun recordingConsentPreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    RecordingExample(recordingSample(RecordingPhase.Recording, organized = ""), organized = true, route = RecordingRoute.Consent)
}

@PreviewTest
@Preview(name = "整理失败提示 深中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Preview(name = "整理失败提示 深英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Preview(name = "整理失败提示 深英840 内屏", widthDp = 840, heightDp = 720, locale = "en")
@Composable
fun recordingFailurePreview() = RecordingFrame(InfinityThemeMode.DARK) {
    RecordingExample(recordingSample(organized = ""), organized = true, failure = R.string.recording_organize_failed)
}

@PreviewTest
@Preview(name = "已保存到资料 浅中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun recordingSavedPreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    RecordingExample(recordingSample(documentId = "/产品讨论 Product review-1a2b3c4d.md"))
}

@PreviewTest
@Preview(name = "已保存到资料 深英840 内屏 大字", widthDp = 840, heightDp = 720, locale = "en", fontScale = 1.3f)
@Composable
fun recordingSavedWidePreview() = RecordingFrame(InfinityThemeMode.DARK) {
    RecordingExample(recordingSample(documentId = "/产品讨论 Product review-1a2b3c4d.md"))
}

@PreviewTest
@Preview(name = "录音中断 深中360 大字", widthDp = 360, heightDp = 800, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun recordingInterruptedPreview() = RecordingFrame(InfinityThemeMode.DARK) {
    RecordingExample(recordingSample(RecordingPhase.Interrupted, organized = ""), route = RecordingRoute.Raw)
}

@PreviewTest
@Preview(name = "录音中断 浅英840 内屏", widthDp = 840, heightDp = 720, locale = "en")
@Composable
fun recordingInterruptedWidePreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    RecordingExample(recordingSample(RecordingPhase.Interrupted, organized = ""), route = RecordingRoute.Raw)
}

@PreviewTest
@Preview(name = "仅转写 整理稿 浅中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun recordingRawOnlyPreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    RecordingExample(recordingSample(organized = ""), organized = true, route = RecordingRoute.Raw)
}

@PreviewTest
@Preview(name = "矮窗口整页滚动 深中360", widthDp = 360, heightDp = 420, locale = "zh-rCN")
@Preview(name = "矮窗口整页滚动 深英800 横屏", widthDp = 800, heightDp = 360, locale = "en")
@Composable
fun recordingShortWindowPreview() = RecordingFrame(InfinityThemeMode.DARK) {
    RecordingExample(recordingSample())
}

@PreviewTest
@Preview(name = "编辑录音 浅中360 大字", widthDp = 360, heightDp = 640, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "编辑录音 浅中640 宽面板", widthDp = 640, heightDp = 640, locale = "zh-rCN")
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

/** 设置页里的整理方式分段控件。 */
@PreviewTest
@Preview(name = "录音方式 浅英360 大字", widthDp = 360, locale = "en", fontScale = 1.3f)
@Preview(name = "录音方式 浅中360", widthDp = 360, locale = "zh-rCN")
@Composable
fun recordingModesPreview() = RecordingFrame(InfinityThemeMode.LIGHT) {
    Column(Modifier.fillMaxWidth().padding(InfinitySpacing.ScreenHorizontal)) {
        RecordingModeOptions(RecordingMode.Auto) {}
    }
}

/** 录音页弹层里的整理方式列表；左右留白与弹层内容区一致。 */
@PreviewTest
@Preview(name = "录音方式弹层 深中360", widthDp = 360, locale = "zh-rCN")
@Preview(name = "录音方式弹层 深英360 大字", widthDp = 360, locale = "en", fontScale = 1.3f)
@Composable
fun recordingModeSheetPreview() = RecordingFrame(InfinityThemeMode.DARK) {
    Column(Modifier.fillMaxWidth().padding(horizontal = InfinitySpacing.XLarge, vertical = InfinitySpacing.Large)) {
        RecordingModeSheetContent(
            selected = RecordingMode.Auto,
            cloudLabel = "DeepSeek · deepseek-v4-flash",
            localLabel = null,
            cloudAllowed = true,
            onSelect = {},
            onRevoke = {},
        )
    }
}
