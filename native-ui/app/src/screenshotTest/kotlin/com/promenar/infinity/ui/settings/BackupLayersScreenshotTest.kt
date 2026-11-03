package com.promenar.infinity.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.backup.BackupDegradedReference
import com.promenar.infinity.data.backup.BackupLayer
import com.promenar.infinity.data.backup.BackupLayerStats
import com.promenar.infinity.data.backup.BackupRestoreResult
import com.promenar.infinity.data.backup.BackupResultSummary
import com.promenar.infinity.data.backup.BackupSkippedItem
import com.promenar.infinity.data.backup.SkillBackupSummary
import com.promenar.infinity.data.backup.SkillGitRemote
import com.promenar.infinity.data.backup.SkippedSkillGitRepository
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private const val MiB = 1024L * 1024

private val PreviewEstimates = mapOf(
    BackupLayer.CORE to BackupLayerStats(412, 31 * MiB + 420 * 1024),
    BackupLayer.KNOWLEDGE to BackupLayerStats(86, 6 * MiB + 200 * 1024),
    BackupLayer.SESSIONS to BackupLayerStats(2130, 9 * MiB + 800 * 1024),
    BackupLayer.WORKSPACE_FILES to BackupLayerStats(318, 118 * MiB),
)

private val PreviewSkipped = listOf(
    BackupSkippedItem(BackupLayer.KNOWLEDGE, "海报草稿-ref-03.png", BackupSkippedItem.REASON_FILE_UNAVAILABLE),
    BackupSkippedItem(BackupLayer.CORE, "agent-2.img", BackupSkippedItem.REASON_AVATAR_DANGLING),
)

@Composable
private fun BackupLayersTheme(dark: Boolean, content: @Composable () -> Unit) {
    InfinityTheme(
        preferences = InfinityThemePreferences(
            mode = if (dark) InfinityThemeMode.DARK else InfinityThemeMode.LIGHT,
            colorSource = InfinityColorSource.INFINITY,
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
    }
}

@PreviewTest
@Preview(name = "备份分层 深色中文 411", widthDp = 411, heightDp = 1400, locale = "zh-rCN")
@Composable
fun backupLayersDarkPreview() = BackupLayersTheme(dark = true) {
    BackupSettingsScreenContent(BackupUiState(layerEstimates = PreviewEstimates))
}

@PreviewTest
@Preview(name = "备份分层 浅色英文 360 字号130", widthDp = 360, heightDp = 1600, locale = "en", fontScale = 1.3f)
@Composable
fun backupLayersLightPreview() = BackupLayersTheme(dark = false) {
    BackupSettingsScreenContent(
        BackupUiState(
            layerEstimates = PreviewEstimates,
            selectedLayers = BackupLayer.DEFAULT + BackupLayer.WORKSPACE_FILES + BackupLayer.SECRETS,
            includeKeys = true,
            encryptBackup = true,
        ),
    )
}

@PreviewTest
@Preview(name = "备份导出结果 深色中文 411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Preview(name = "备份导出结果 深色英文 360 字号130", widthDp = 360, heightDp = 760, locale = "en", fontScale = 1.3f)
@Composable
fun backupExportResultPreview() = BackupLayersTheme(dark = true) {
    BackupExportResultContent(
        BackupResultSummary(layers = PreviewEstimates.filterKeys { it in BackupLayer.DEFAULT }, skipped = PreviewSkipped),
        onDismiss = {},
    )
}

@PreviewTest
@Preview(name = "备份恢复结果 深色中文 411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "备份恢复结果 深色英文 360 字号130", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun backupRestoreResultPreview() = BackupLayersTheme(dark = true) {
    val skills = SkillBackupSummary(
        skillCount = 58,
        skippedSymbolicLinks = 0,
        skippedGitRepositories = listOf(
            SkippedSkillGitRepository("hyperframes", listOf(SkillGitRemote("origin", "https://github.com/example/hyperframes.git")), "main", null),
        ),
    )
    BackupRestoreResultDialog(
        BackupRestoreResult(
            skills = skills,
            summary = BackupResultSummary(
                layers = PreviewEstimates.filterKeys { it == BackupLayer.CORE || it == BackupLayer.SESSIONS },
                degraded = listOf(BackupDegradedReference("session_workspace_unavailable", 17)),
                skills = skills,
            ),
        ),
        onDismiss = {},
    )
}
