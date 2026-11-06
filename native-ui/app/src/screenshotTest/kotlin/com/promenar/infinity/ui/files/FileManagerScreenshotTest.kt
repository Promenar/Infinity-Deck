package com.promenar.infinity.ui.files

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.ui.files.preview.FakeFileManagerController
import com.promenar.infinity.ui.files.preview.FileManagerSamples
import com.promenar.infinity.ui.files.preview.itemAt
import com.promenar.infinity.ui.files.state.FileTransferMode
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

/*
 * 统一资源管理器的截图：单列列表、单列子目录“..”、双栏列表 + 预览、选择模式、目标选择面板、
 * 搜索结果与单列预览页。每组深色中文与浅色英文各一，浅色组同时覆盖 360dp 与 1.3 倍字号。
 */

@Composable
private fun FilesFrame(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) { content() }
    }
}

private fun innerHost() = FileManagerHostSpec(leading = FileManagerLeading.Expand(onExpand = {}), showPin = true)

private fun libraryHost(english: Boolean) = FileManagerHostSpec(
    leading = FileManagerLeading.Restore(onRestore = {}),
    showPin = true,
    hostActions = listOf(
        FileHostAction(
            id = "add",
            icon = Icons.Rounded.LibraryAdd,
            label = if (english) "Add to library" else "加入资料",
            appliesTo = setOf(FileHostActionScope.FILE, FileHostActionScope.FOLDER, FileHostActionScope.MULTI),
            prominent = true,
            onInvoke = {},
        ),
        FileHostAction(
            id = "chat",
            icon = Icons.AutoMirrored.Rounded.Chat,
            label = if (english) "Use in chat" else "在对话中使用",
            appliesTo = setOf(FileHostActionScope.FILE, FileHostActionScope.MULTI),
            prominent = true,
            onInvoke = {},
        ),
    ),
)

@Composable
private fun PaneSample(
    mode: InfinityThemeMode,
    host: FileManagerHostSpec,
    setup: () -> FakeFileManagerController,
) = FilesFrame(mode) {
    val controller = remember { setup() }
    FileManagerPane(controller = controller, host = host)
}

// ---------- 单列列表（存储空间根目录） ----------

@PreviewTest
@Preview(name = "资源管理器 单列 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun fileManagerCompactDarkPreview() = PaneSample(InfinityThemeMode.DARK, innerHost()) {
    FileManagerSamples.controller(english = false)
}

@PreviewTest
@Preview(name = "资源管理器 单列 浅英360 大字", widthDp = 360, heightDp = 900, locale = "en", fontScale = 1.3f)
@Composable
fun fileManagerCompactLightPreview() = PaneSample(InfinityThemeMode.LIGHT, innerHost()) {
    FileManagerSamples.controller(english = true)
}

// ---------- 单列子目录：首行“..” ----------

@PreviewTest
@Preview(name = "资源管理器 子目录 深中411", widthDp = 411, heightDp = 760, locale = "zh-rCN")
@Composable
fun fileManagerSubfolderDarkPreview() = PaneSample(
    InfinityThemeMode.DARK,
    FileManagerHostSpec(leading = FileManagerLeading.Leave(onLeave = {})),
) {
    FileManagerSamples.controller(english = false, path = FileManagerSamples.folderPath(false))
}

@PreviewTest
@Preview(name = "资源管理器 子目录 浅英360 大字", widthDp = 360, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun fileManagerSubfolderLightPreview() = PaneSample(
    InfinityThemeMode.LIGHT,
    FileManagerHostSpec(leading = FileManagerLeading.Leave(onLeave = {})),
) {
    FileManagerSamples.controller(english = true, path = FileManagerSamples.nestedFolderPath(true))
}

// ---------- 双栏：列表 + 预览（资料页展开态） ----------

private fun dualSample(english: Boolean): FakeFileManagerController =
    FileManagerSamples.controller(english = english).apply {
        itemAt(FileManagerSamples.markdownPath(english))?.let { showPreview(it) }
    }

@PreviewTest
@Preview(name = "资源管理器 双栏 深中742", widthDp = 742, heightDp = 720, locale = "zh-rCN")
@Composable
fun fileManagerDualDarkPreview() = PaneSample(InfinityThemeMode.DARK, libraryHost(false)) { dualSample(false) }

@PreviewTest
@Preview(name = "资源管理器 双栏 浅英600 大字", widthDp = 600, heightDp = 760, locale = "en", fontScale = 1.3f)
@Composable
fun fileManagerDualLightPreview() = PaneSample(InfinityThemeMode.LIGHT, libraryHost(true)) { dualSample(true) }

// ---------- 选择模式 ----------

private fun selectionSample(english: Boolean): FakeFileManagerController =
    FileManagerSamples.controller(english = english).apply {
        state.value.items.filterNot { it.isDirectory }.take(2).forEach { toggleSelection(it) }
    }

@PreviewTest
@Preview(name = "资源管理器 选择模式 深中411", widthDp = 411, heightDp = 760, locale = "zh-rCN")
@Composable
fun fileManagerSelectionDarkPreview() = PaneSample(InfinityThemeMode.DARK, libraryHost(false)) { selectionSample(false) }

@PreviewTest
@Preview(name = "资源管理器 选择模式 浅英360 大字", widthDp = 360, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun fileManagerSelectionLightPreview() = PaneSample(InfinityThemeMode.LIGHT, libraryHost(true)) { selectionSample(true) }

// ---------- 搜索结果 ----------

private fun searchSample(english: Boolean): FakeFileManagerController =
    FileManagerSamples.controller(english = english).apply {
        startSearch()
        updateSearch { it.copy(text = FileManagerSamples.searchText(english)) }
    }

@PreviewTest
@Preview(name = "资源管理器 搜索 浅中411", widthDp = 411, heightDp = 760, locale = "zh-rCN")
@Composable
fun fileManagerSearchLightPreview() = PaneSample(InfinityThemeMode.LIGHT, innerHost()) { searchSample(false) }

@PreviewTest
@Preview(name = "资源管理器 搜索 深英360 大字", widthDp = 360, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun fileManagerSearchDarkPreview() = PaneSample(InfinityThemeMode.DARK, innerHost()) { searchSample(true) }

// ---------- 单列预览页：内屏宿主用“‹ 文件夹名”，其它宿主用返回键 ----------

@PreviewTest
@Preview(name = "资源管理器 单列预览 内屏 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Composable
fun fileManagerCompactPreviewDarkPreview() = PaneSample(InfinityThemeMode.DARK, innerHost()) {
    FileManagerSamples.controller(english = false).apply {
        itemAt(FileManagerSamples.markdownPath(false))?.let { showPreview(it) }
    }
}

@PreviewTest
@Preview(name = "资源管理器 单列预览 外屏 浅英360 大字", widthDp = 360, heightDp = 860, locale = "en", fontScale = 1.3f)
@Composable
fun fileManagerCompactPreviewLightPreview() = PaneSample(
    InfinityThemeMode.LIGHT,
    FileManagerHostSpec(leading = FileManagerLeading.Leave(onLeave = {})),
) {
    FileManagerSamples.controller(english = true).apply {
        state.value.items.firstOrNull { it.name.endsWith(".pdf") }?.let { showPreview(it) }
    }
}

// ---------- 复制到 / 移动到 ----------

@Composable
private fun TargetPickerSample(mode: InfinityThemeMode, english: Boolean) = FilesFrame(mode) {
    val controller = remember {
        FileManagerSamples.controller(english = english).apply {
            val first = state.value.items.firstOrNull { !it.isDirectory }
            if (first != null) openTargetPicker(FileTransferMode.MOVE, listOf(first))
        }
    }
    val state by controller.state.collectAsState()
    val picker = state.targetPicker ?: return@FilesFrame
    // 与 InfinityModalSheet 的底色与左右留白一致；面板外壳本身是弹出窗口，截图只渲染内容。
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp)) {
            FileTargetPickerContent(
                picker = picker,
                locations = state.locations,
                onLocation = controller::pickerOpenLocation,
                onGo = controller::pickerGoTo,
                onNewFolder = {},
                onCancel = controller::dismissTargetPicker,
                onConfirm = controller::confirmTargetPicker,
            )
        }
    }
}

@PreviewTest
@Preview(name = "资源管理器 目标选择 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun fileManagerTargetPickerDarkPreview() = TargetPickerSample(InfinityThemeMode.DARK, english = false)

@PreviewTest
@Preview(name = "资源管理器 目标选择 浅英360 大字", widthDp = 360, heightDp = 760, locale = "en", fontScale = 1.3f)
@Composable
fun fileManagerTargetPickerLightPreview() = TargetPickerSample(InfinityThemeMode.LIGHT, english = true)
