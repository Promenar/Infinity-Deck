package com.promenar.infinity.ui.chat

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
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.ui.chat.components.ExternalMountFormContent
import com.promenar.infinity.ui.chat.components.ExternalMountManagerContent
import com.promenar.infinity.ui.chat.components.ExternalMountSource
import com.promenar.infinity.ui.chat.components.KnowledgeMountOption
import com.promenar.infinity.ui.chat.components.PickedMountFolder
import com.promenar.infinity.ui.chat.components.pickedRealPathFolder
import com.promenar.infinity.ui.workspace.WorkspacePickerContent
import com.promenar.infinity.ui.workspace.WorkspacePickerPage
import com.promenar.infinity.ui.workspace.WorkspacePickerUiState
import com.promenar.infinity.data.workspace.WorkspaceFolder
import com.promenar.infinity.R
import androidx.compose.ui.res.stringResource
import java.io.File
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences

private val Mounts = listOf(
    ExternalMount("m1", "termux-home", "saf_tree", "tree-1", "s1", createdAt = 1),
    ExternalMount("m2", "documents", "real_path", "/storage/emulated/0/Documents", "s1", createdAt = 2),
    ExternalMount("m3", "sdcard-backup", "saf_tree", "tree-3", "s1", createdAt = 3, unavailableReason = "revoked"),
)

/** 与 InfinityBottomSheet 全高面板一致的底色与左右留白；页头由内容自带。 */
@Composable
private fun MountSheetFrame(mode: InfinityThemeMode = InfinityThemeMode.DARK, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 32.dp)) { content() }
        }
    }
}

@Composable
private fun ManagerPreview(
    mounts: List<ExternalMount>,
    mode: InfinityThemeMode = InfinityThemeMode.DARK,
    knowledgeMount: KnowledgeMountOption? = null,
) = MountSheetFrame(mode) {
    ExternalMountManagerContent(
        mounts = mounts,
        sources = listOf(ExternalMountSource.Saf, ExternalMountSource.RealPath),
        loading = false,
        busy = false,
        notice = null,
        onRefresh = {},
        onAdd = {},
        onBrowse = {},
        onReauthorize = {},
        onRemove = {},
        onGrantRealPath = {},
        onRecycle = {},
        knowledgeMount = knowledgeMount,
    )
}

@PreviewTest
@Preview(name = "外部文件夹 已挂载 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "外部文件夹 已挂载 浅英360 大字", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun externalMountManagerPreview() = ManagerPreview(Mounts)

@PreviewTest
@Preview(name = "外部文件夹 空 浅中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun externalMountManagerEmptyPreview() = ManagerPreview(emptyList(), InfinityThemeMode.LIGHT)

@Composable
private fun FormPreview(
    source: ExternalMountSource,
    alias: String,
    sourcePath: String = "",
    picked: PickedMountFolder? = null,
    manualPath: Boolean = false,
    realPathPermission: Boolean = true,
    mode: InfinityThemeMode = InfinityThemeMode.DARK,
) = MountSheetFrame(mode) {
    ExternalMountFormContent(
        source = source,
        alias = alias,
        sourcePath = sourcePath,
        pickedFolder = picked,
        manualPath = manualPath,
        hasCurrentSession = true,
        realPathPermission = realPathPermission,
        busy = false,
        notice = null,
        onAliasChanged = {},
        onPathChanged = {},
        onShowManualPath = {},
        onPickFolder = {},
        onGrantAccess = {},
        onBack = {},
        onConfirm = {},
    )
}

// 存储路径未授权：选择行置灰，下方给出授权入口；手动输入展开时显示原路径输入框。
@PreviewTest
@Preview(name = "外部文件夹 添加存储路径 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Composable
fun externalMountFormPreview() = FormPreview(
    source = ExternalMountSource.RealPath,
    alias = "documents",
    sourcePath = "/storage/emulated/0/Documents",
    picked = pickedRealPathFolder("/storage/emulated/0/Documents"),
    manualPath = true,
    realPathPermission = false,
)

@PreviewTest
@Preview(name = "外部文件夹 存储路径未选 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "外部文件夹 存储路径未选 浅英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun externalMountFormUnpickedPreview() = FormPreview(ExternalMountSource.RealPath, alias = "external")

@PreviewTest
@Preview(name = "外部文件夹 存储路径已选 深中360 大字", widthDp = 360, heightDp = 1000, locale = "zh-rCN", fontScale = 1.3f)
@Preview(name = "外部文件夹 存储路径已选 浅英411", widthDp = 411, heightDp = 900, locale = "en")
@Composable
fun externalMountFormPickedPreview() {
    val path = "/storage/emulated/0/Documents/Projects/graduation-thesis/multimodal-retrieval-system"
    FormPreview(
        ExternalMountSource.RealPath,
        alias = "multimodal-retrieval-system",
        sourcePath = path,
        picked = pickedRealPathFolder(path),
    )
}

@PreviewTest
@Preview(name = "外部文件夹 文件夹授权已选 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "外部文件夹 文件夹授权已选 浅英360 大字", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun externalMountFormSafPickedPreview() = FormPreview(
    ExternalMountSource.Saf,
    alias = "reports-2026",
    picked = PickedMountFolder(
        "reports-2026",
        stringResource(R.string.workspace_picker_shared_volume) + "/Documents/reports-2026",
    ),
)

@PreviewTest
@Preview(name = "外部文件夹 文件夹授权未选 浅中360", widthDp = 360, heightDp = 900, locale = "zh-rCN")
@Composable
fun externalMountFormSafUnpickedPreview() = FormPreview(ExternalMountSource.Saf, alias = "external", mode = InfinityThemeMode.LIGHT)

// 存储路径选目录：复用工作区选择器，只列共享存储卷，首段面包屑为“位置”。
@PreviewTest
@Preview(name = "外部文件夹 选择存储目录 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Preview(name = "外部文件夹 选择存储目录 浅英360 大字", widthDp = 360, heightDp = 800, locale = "en", fontScale = 1.3f)
@Composable
fun externalMountPickFolderPreview() = MountSheetFrame {
    val volume = File("/storage/emulated/0")
    val path = File(volume, "Documents")
    WorkspacePickerContent(
        WorkspacePickerUiState(
            page = WorkspacePickerPage.Shared(volume, path),
            sharedSupported = true,
            sharedPermission = true,
            sharedRoots = listOf(volume),
            folders = listOf("notes", "reports-2026", "论文初稿与参考文献整理（第三版）")
                .map { WorkspaceFolder(it, File(path, it)) },
            sharedOnly = true,
        ),
        rootTitle = stringResource(R.string.mount_picker_root),
        onExitRoot = {},
    )
}

@PreviewTest
@Preview(name = "外部文件夹 选择存储卷 深中411", widthDp = 411, heightDp = 600, locale = "zh-rCN")
@Composable
fun externalMountPickVolumePreview() = MountSheetFrame {
    WorkspacePickerContent(
        WorkspacePickerUiState(
            sharedSupported = true,
            sharedPermission = true,
            sharedRoots = listOf(File("/storage/emulated/0"), File("/storage/1A2B-3C4D")),
            sharedOnly = true,
        ),
        rootTitle = stringResource(R.string.mount_picker_root),
        onExitRoot = {},
    )
}

// 知识库挂载：已挂载行带“知识库 · 只读”信息色标签；添加组末尾的“挂载知识库”在已挂载后置灰。
@PreviewTest
@Preview(name = "外部文件夹 知识库只读 深中411", widthDp = 411, heightDp = 1000, locale = "zh-rCN")
@Preview(name = "外部文件夹 知识库只读 浅英360 大字", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun externalMountKnowledgePreview() = ManagerPreview(
    Mounts.take(1) + ExternalMount("k1", "knowledge", "knowledge_base", "root-1", "s1", createdAt = 4, readOnly = true),
    knowledgeMount = KnowledgeMountOption.Mounted,
)

@PreviewTest
@Preview(name = "外部文件夹 可挂载知识库 浅中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Composable
fun externalMountKnowledgeAvailablePreview() = ManagerPreview(
    emptyList(),
    InfinityThemeMode.LIGHT,
    knowledgeMount = KnowledgeMountOption.Available(listOf("/mnt/knowledge")),
)
