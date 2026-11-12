package com.promenar.infinity.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.local.inference.LocalModelRevision
import com.promenar.infinity.data.local.inference.RecordingModelPackage
import com.promenar.infinity.data.local.inference.StoredModel
import com.promenar.infinity.ui.theme.*

@PreviewTest
@Preview(name = "录音模型下载 浅英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Preview(name = "录音模型下载 浅中840 内屏", widthDp = 840, heightDp = 720, locale = "zh-rCN")
@Composable
fun recordingModelDownloadPreview() {
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.LIGHT, colorSource = InfinityColorSource.INFINITY)) {
        LocalModelsScreenContent(engineEnabled = true, onInstall = {}, downloadProgress = 42)
    }
}

@PreviewTest
@Preview(name = "录音模型固定 深中360 大字", widthDp = 360, heightDp = 1000, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "录音模型固定 深英840 内屏", widthDp = 840, heightDp = 900, locale = "en")
@Composable
fun recordingModelPinnedPreview() {
    val model = StoredModel(
        id = "recording-model-preview",
        fileName = RecordingModelPackage.filename,
        filePath = "models/${RecordingModelPackage.filename}",
        sizeBytes = RecordingModelPackage.bytes,
        format = "GGUF",
        addedAt = 0,
    )
    InfinityTheme(preferences = InfinityThemePreferences(mode = InfinityThemeMode.DARK, colorSource = InfinityColorSource.INFINITY)) {
        LocalModelsScreenContent(
            availableModels = listOf(model), engineEnabled = true, onTogglePin = {},
            pinned = LocalModelRevision(model.filePath, RecordingModelPackage.sha256),
        )
    }
}
