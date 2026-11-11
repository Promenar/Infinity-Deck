package com.promenar.infinity.ui.linux.files

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.domain.linux.LinuxEnvironmentInfo
import com.promenar.infinity.domain.linux.LinuxFileEntry
import com.promenar.infinity.domain.linux.LinuxFileType
import com.promenar.infinity.domain.linux.LinuxInstallProgress
import com.promenar.infinity.domain.linux.LinuxLineEnding
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.ui.chat.components.DirectoryClipboard
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import java.time.LocalDateTime
import java.time.ZoneId

private val PreviewNow = LocalDateTime.of(2026, 10, 25, 23, 10)

private fun at(minutesAgo: Long): Long = PreviewNow.minusMinutes(minutesAgo).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private val NowMs = at(0)

private val Ready = LinuxRuntimeState.Ready(LinuxEnvironmentInfo("Alpine", "3.24.3", "aarch64", "5.4.0", NowMs, null))

private fun entry(
    name: String,
    type: LinuxFileType,
    minutesAgo: Long,
    size: Long = 4096,
    mode: Int = "755".toInt(8),
    target: String? = null,
    targetIsDirectory: Boolean = false,
) = LinuxFileEntry(
    path = "/root/projects/$name",
    name = name,
    type = type,
    size = size,
    modifiedMs = at(minutesAgo),
    mode = mode,
    owner = "root",
    group = "root",
    symlinkTarget = target,
    targetIsDirectory = targetIsDirectory,
)

private val Entries = listOf(
    entry("scraper", LinuxFileType.DIRECTORY, 3),
    entry("run.sh", LinuxFileType.FILE, 12, size = 1_229),
    entry(".venv", LinuxFileType.DIRECTORY, 62),
    entry("data", LinuxFileType.SYMLINK, 90, mode = "777".toInt(8), target = "/workspace/data"),
    entry("requirements.txt", LinuxFileType.FILE, 26 * 60, size = 214, mode = "644".toInt(8)),
    entry("model.bin", LinuxFileType.FILE, 27 * 60, size = 412L * 1024 * 1024, mode = "644".toInt(8)),
    entry("archive", LinuxFileType.SYMLINK, 15 * 24 * 60, mode = "777".toInt(8), target = "../archive", targetIsDirectory = true),
)

private val BrowseState = LinuxFilesUiState(
    runtime = Ready,
    path = "/root/projects",
    entries = Entries,
    listedPath = "/root/projects",
    sort = LinuxFileSort.MODIFIED,
    nowMs = NowMs,
)

@Composable
private fun Themed(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) { content() }
}

@PreviewTest
@Preview(name = "Linux 文件 浏览 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Preview(name = "Linux 文件 浏览 深英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun linuxFilesBrowseDarkPreview() = Themed(InfinityThemeMode.DARK) {
    LinuxFilesContent(BrowseState, LinuxFilesActions())
}

@PreviewTest
@Preview(name = "Linux 文件 浏览 浅中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun linuxFilesBrowseLightPreview() = Themed(InfinityThemeMode.LIGHT) {
    LinuxFilesContent(
        BrowseState.copy(
            notice = LinuxFilesNotice.RuntimeBinding("data", "/workspace/data"),
            clipboard = DirectoryClipboard(listOf("/root/shared/notes.md"), cut = false),
        ),
        LinuxFilesActions(),
    )
}

@PreviewTest
@Preview(name = "Linux 文件 多选 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Preview(name = "Linux 文件 多选 深英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun linuxFilesSelectionPreview() = Themed(InfinityThemeMode.DARK) {
    LinuxFilesContent(
        BrowseState.copy(selection = setOf("/root/projects/scraper", "/root/projects/run.sh")),
        LinuxFilesActions(),
    )
}

@PreviewTest
@Preview(name = "Linux 文件 多选 浅中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun linuxFilesSelectionLightPreview() = Themed(InfinityThemeMode.LIGHT) {
    LinuxFilesContent(
        BrowseState.copy(selection = setOf("/root/projects/scraper", "/root/projects/run.sh")),
        LinuxFilesActions(),
    )
}

@PreviewTest
@Preview(name = "Linux 文件 未就绪 深中411", widthDp = 411, heightDp = 700, locale = "zh-rCN")
@Preview(name = "Linux 文件 未就绪 浅英360 大字", widthDp = 360, heightDp = 700, locale = "en", fontScale = 1.3f)
@Composable
fun linuxFilesNotReadyPreview() = Themed(InfinityThemeMode.DARK) {
    LinuxFilesContent(
        LinuxFilesUiState(runtime = LinuxRuntimeState.Installing(LinuxInstallProgress(emptyList(), 0.4f)), nowMs = NowMs),
        LinuxFilesActions(),
    )
}

private val Script = """
    #!/bin/bash
    set -euo pipefail

    cd /workspace/data
    python3 -m venv /root/projects/.venv
    source /root/projects/.venv/bin/activate
    pip install -r requirements.txt

    for f in raw/*.csv; do
      python3 clean.py "${'$'}f" > out/"${'$'}(basename "${'$'}f")"
    done

    echo "done: ${'$'}(ls out | wc -l) files"
""".trimIndent() + "\n"

private val ScriptEntry = LinuxFileEntry(
    path = "/root/projects/run.sh",
    name = "run.sh",
    type = LinuxFileType.FILE,
    size = Script.length.toLong(),
    modifiedMs = at(12),
    mode = "755".toInt(8),
    owner = "root",
    group = "root",
)

private val FileState = LinuxFileUiState(
    path = ScriptEntry.path,
    entry = ScriptEntry,
    loading = false,
    text = LinuxTextPreview(
        text = Script,
        isBinary = false,
        truncated = false,
        shownBytes = Script.length,
        totalSize = Script.length.toLong(),
        encoding = "UTF-8",
        lineEnding = LinuxLineEnding.LF,
        lineCount = countLines(Script),
        modifiedMs = ScriptEntry.modifiedMs,
        kind = LinuxFileKind.SHELL,
    ),
    nowMs = NowMs,
)

@PreviewTest
@Preview(name = "Linux 文件详情 内容 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Preview(name = "Linux 文件详情 内容 深英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun linuxFileContentDarkPreview() = Themed(InfinityThemeMode.DARK) {
    LinuxFileContent(FileState, LinuxFileActions())
}

@PreviewTest
@Preview(name = "Linux 文件详情 内容 浅中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun linuxFileContentLightPreview() = Themed(InfinityThemeMode.LIGHT) {
    LinuxFileContent(FileState, LinuxFileActions())
}

/** 长文件名：页头省略中间、保留扩展名，完整路径在页头下方换行显示。 */
private val LongNameEntry = ScriptEntry.copy(
    path = "/root/projects/reports/助手功能介绍_20261029_131502_final_review.md",
    name = "助手功能介绍_20261029_131502_final_review.md",
    mode = "644".toInt(8),
)

private val LongNameState = FileState.copy(
    path = LongNameEntry.path,
    entry = LongNameEntry,
    text = FileState.text?.copy(kind = LinuxFileKind.MARKDOWN),
)

@PreviewTest
@Preview(name = "Linux 文件详情 长文件名 浅中392", widthDp = 392, heightDp = 860, locale = "zh-rCN")
@Preview(name = "Linux 文件详情 长文件名 深英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun linuxFileLongNamePreview() = Themed(InfinityThemeMode.LIGHT) {
    LinuxFileContent(LongNameState, LinuxFileActions())
}

private val HexState = FileState.copy(tab = LinuxFileTab.HEX, hex = LinuxHexPage(0, 0, Script.toByteArray()))

@PreviewTest
@Preview(name = "Linux 文件详情 十六进制 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Preview(name = "Linux 文件详情 十六进制 浅英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun linuxFileHexPreview() = Themed(InfinityThemeMode.DARK) {
    LinuxFileContent(HexState, LinuxFileActions())
}

@PreviewTest
@Preview(name = "Linux 文件详情 十六进制 浅中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun linuxFileHexLightPreview() = Themed(InfinityThemeMode.LIGHT) {
    LinuxFileContent(HexState, LinuxFileActions())
}

@PreviewTest
@Preview(name = "Linux 文件详情 属性 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Preview(name = "Linux 文件详情 属性 深英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun linuxFilePropertiesPreview() = Themed(InfinityThemeMode.DARK) {
    LinuxFileContent(FileState.copy(tab = LinuxFileTab.PROPERTIES), LinuxFileActions())
}

@PreviewTest
@Preview(name = "Linux 文件详情 属性 浅中360", widthDp = 360, heightDp = 800, locale = "zh-rCN")
@Composable
fun linuxFilePropertiesLightPreview() = Themed(InfinityThemeMode.LIGHT) {
    LinuxFileContent(FileState.copy(tab = LinuxFileTab.PROPERTIES), LinuxFileActions())
}
