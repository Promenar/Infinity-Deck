package com.promenar.infinity.ui.workspace

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.R
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.workspace.WorkspaceFolder
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.workspace.DirectoryFileOpCode
import com.promenar.infinity.domain.workspace.PrivateWorkspaceSummary
import com.promenar.infinity.ui.chat.components.DirectoryBrowserState
import com.promenar.infinity.ui.chat.components.DirectoryClipboard
import com.promenar.infinity.ui.chat.components.DirectoryFileActions
import com.promenar.infinity.ui.chat.components.DirectoryOp
import com.promenar.infinity.ui.chat.components.DirectoryOpOutcome
import com.promenar.infinity.ui.chat.components.DirectoryWorkspaceBrowser
import com.promenar.infinity.ui.chat.components.ExternalTextPreview
import com.promenar.infinity.ui.theme.InfinityColorSource
import com.promenar.infinity.ui.theme.InfinityTheme
import com.promenar.infinity.ui.theme.InfinityThemeMode
import com.promenar.infinity.ui.theme.InfinityThemePreferences
import java.io.File

private val Volume = File("/storage/emulated/0")
private val Project = WorkspaceEntity("ws-project", WorkspaceEntity.KIND_PROJECT, "infinity-android", "infinity-android", 1)
private val Shared = WorkspaceEntity(
    "ws-shared", WorkspaceEntity.KIND_SHARED, "毕业设计-多模态检索系统",
    "/storage/emulated/0/Documents/Projects/graduation-thesis/multimodal-retrieval-system", 2,
)

@Composable
private fun WorkspaceTheme(mode: InfinityThemeMode = InfinityThemeMode.DARK, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** 与 InfinityBottomSheet 一致的标题与左右留白。 */
@Composable
private fun SheetFrame(
    title: String,
    mode: InfinityThemeMode = InfinityThemeMode.DARK,
    content: @Composable () -> Unit,
) = WorkspaceTheme(mode) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 24.dp, bottom = 32.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(bottom = 16.dp))
        content()
    }
}

@PreviewTest
@Preview(name = "选择器 项目根 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Preview(name = "选择器 项目根 深中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun workspacePickerProjectsPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(
        WorkspacePickerUiState(
            page = WorkspacePickerPage.Projects,
            folders = listOf("demo", "my-app", "infinity-android", "scratch_2026.10").map { WorkspaceFolder(it, File("/projects/$it")) },
            selectedProject = "my-app",
        ),
    )
}

@PreviewTest
@Preview(name = "选择器 Linux 目录 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Preview(name = "选择器 Linux 目录 深中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun workspacePickerLinuxPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(
        WorkspacePickerUiState(
            page = WorkspacePickerPage.Linux("/home/alice"),
            folders = listOf(".config", "code", "notes").map { WorkspaceFolder(it, File("/home/alice/$it")) },
            linuxHome = com.promenar.infinity.data.workspace.LinuxHomeAvailability.READY,
        ),
    )
}

@PreviewTest
@Preview(name = "选择器 Linux 起点列表 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Composable
fun workspacePickerLinuxRootsPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(
        WorkspacePickerUiState(
            page = WorkspacePickerPage.Linux(null),
            folders = listOf("/root", "/home", "/opt").map { WorkspaceFolder(it.removePrefix("/"), File(it)) },
            linuxHome = com.promenar.infinity.data.workspace.LinuxHomeAvailability.READY,
        ),
    )
}

/** Linux 模式会话：三组都可选，本会话文件夹补 Linux 中的路径。 */
private val PermissionState = WorkspacePickerUiState(
    registered = listOf(Project, Shared),
    currentWorkspaceId = Project.id,
    showPrivate = true,
    sharedSupported = true,
    sharedPermission = false,
    sharedRoots = listOf(Volume),
    linuxHome = com.promenar.infinity.data.workspace.LinuxHomeAvailability.READY,
    runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX,
)

@PreviewTest
@Preview(name = "选择器 共享未授权 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Composable
fun workspacePickerPermissionPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(PermissionState)
}

@PreviewTest
@Preview(name = "选择器 共享未授权 浅英360", widthDp = 360, heightDp = 720, locale = "en")
@Composable
fun workspacePickerPermissionLightPreview() =
    SheetFrame(stringResource(R.string.workspace_picker_title), InfinityThemeMode.LIGHT) {
        WorkspacePickerContent(PermissionState)
    }

@PreviewTest
@Preview(name = "选择器 内置模式会话 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "选择器 内置模式会话 浅英360 大字", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun workspacePickerBuiltinSessionPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(
        WorkspacePickerUiState(
            showPrivate = true,
            sharedSupported = true,
            sharedPermission = true,
            sharedRoots = listOf(Volume, File("/storage/1A2B-3C4D")),
            linuxHome = com.promenar.infinity.data.workspace.LinuxHomeAvailability.READY,
            runMode = com.promenar.infinity.domain.model.SessionRunMode.BUILTIN,
        ),
    )
}

@PreviewTest
@Preview(name = "选择器 会话 深中411", widthDp = 411, heightDp = 820, locale = "zh-rCN")
@Preview(name = "选择器 会话 浅英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun workspacePickerSessionPreview() = SessionPickerPreview(busy = false)

@PreviewTest
@Preview(name = "选择器 会话生成中 深中411", widthDp = 411, heightDp = 820, locale = "zh-rCN")
@Preview(name = "选择器 会话生成中 深中360 大字", widthDp = 360, heightDp = 1000, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun workspacePickerSessionBusyPreview() = SessionPickerPreview(busy = true)

/** 从会话打开：顶部为运行模式、权限模式与可达范围链接；生成中时说明原因并只保留当前工作区可点。 */
@Composable
private fun SessionPickerPreview(busy: Boolean) = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(
        WorkspacePickerUiState(
            registered = listOf(Project),
            currentWorkspaceId = Project.id,
            showPrivate = true,
            sharedSupported = true,
            sharedPermission = true,
            sharedRoots = listOf(Volume),
            linuxHome = com.promenar.infinity.data.workspace.LinuxHomeAvailability.READY,
            runMode = com.promenar.infinity.domain.model.SessionRunMode.LINUX,
            switchLocked = busy,
        ),
        switchStatus = WorkspaceSwitchStatus(
            executionMode = com.promenar.infinity.domain.model.ExecutionMode.WORKSPACE,
            busy = busy,
            failed = false,
            onOpenReach = {},
            onStopGeneration = {},
        ),
    )
}

@PreviewTest
@Preview(name = "选择器 Play 起始页 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Composable
fun workspacePickerPlayHomePreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    WorkspacePickerContent(WorkspacePickerUiState(registered = listOf(Project), sharedSupported = false))
}

@PreviewTest
@Preview(name = "选择器 共享目录列表 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Preview(name = "选择器 共享目录列表 深中360", widthDp = 360, heightDp = 720, locale = "zh-rCN")
@Composable
fun workspacePickerSharedListPreview() = SheetFrame(stringResource(R.string.workspace_picker_title)) {
    val path = File(Volume, "Documents/Projects/graduation-thesis")
    WorkspacePickerContent(
        WorkspacePickerUiState(
            page = WorkspacePickerPage.Shared(Volume, path),
            sharedSupported = true,
            sharedPermission = true,
            sharedRoots = listOf(Volume, File("/storage/1A2B-3C4D")),
            folders = listOf("data", "multimodal-retrieval-system", "论文初稿与参考文献整理（第三版）", "slides")
                .map { WorkspaceFolder(it, File(path, it)) },
        ),
    )
}

@PreviewTest
@Preview(name = "Agent 默认工作区 深中411", widthDp = 411, heightDp = 620, locale = "zh-rCN")
@Preview(name = "Agent 默认工作区 深中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun agentDefaultWorkspacePreview() = WorkspaceTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        AgentDefaultWorkspaceRow(workspaceId = null, workspace = null, onChoose = {})
        AgentDefaultWorkspaceRow(workspaceId = Shared.id, workspace = Shared, onChoose = {})
        AgentDefaultWorkspaceRow(workspaceId = "gone", workspace = null, onChoose = {})
    }
}

@PreviewTest
@Preview(name = "知识库工作区 分组 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Composable
fun knowledgeWorkspaceListPreview() = KnowledgeListPreview(InfinityThemeMode.DARK)

@PreviewTest
@Preview(name = "知识库工作区 分组 浅英360", widthDp = 360, heightDp = 720, locale = "en")
@Composable
fun knowledgeWorkspaceListLightPreview() = KnowledgeListPreview(InfinityThemeMode.LIGHT)

/** 知识库文件页顶部的“工作区”入口，以及点开后底部弹层中的登记列表。 */
@Composable
private fun KnowledgeListPreview(mode: InfinityThemeMode) = WorkspaceTheme(mode) {
    Column(Modifier.fillMaxSize()) {
        KnowledgeWorkspacesEntry(count = 2, onClick = {}, modifier = Modifier.padding(16.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
            Column(Modifier.padding(horizontal = 24.dp).padding(top = 24.dp)) {
                Text(stringResource(R.string.workspace_kb_entry), style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 16.dp))
                KnowledgeWorkspaceListContent(
                    workspaces = listOf(Project, Shared),
                    notice = stringResource(R.string.workspace_kb_delete_referenced, 2, 1),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "知识库工作区 浏览 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun knowledgeWorkspaceBrowserPreview() = SheetFrame(stringResource(R.string.workspace_kb_entry)) {
    KnowledgeWorkspaceBrowserContent(
        workspace = Shared,
        path = "/docs/design",
        entries = listOf(
            ExternalFolderEntry("/docs/design/assets", "assets", true, 0, 0),
            ExternalFolderEntry("/docs/design/架构说明-检索与重排流程.md", "架构说明-检索与重排流程.md", false, 18_432, 0),
            ExternalFolderEntry("/docs/design/benchmark.csv", "benchmark.csv", false, 2_048_000, 0),
        ),
        loading = false,
        error = null,
    )
}

@PreviewTest
@Preview(name = "知识库工作区 文件预览 深中411", widthDp = 411, heightDp = 720, locale = "zh-rCN")
@Preview(name = "知识库工作区 文件预览 深中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun knowledgeWorkspaceFilePreview() = SheetFrame(stringResource(R.string.workspace_kb_entry)) {
    KnowledgeWorkspaceFileContent(
        entry = ExternalFolderEntry("/docs/design/架构说明-检索与重排流程.md", "架构说明-检索与重排流程.md", false, 18_432, 0),
        preview = ExternalTextPreview.Text("# 检索与重排\n\n1. 向量召回 Top-50\n2. 关键词补召回\n3. 交叉编码器重排\n\n> 只读预览，不会修改源文件。\n"),
        notice = KnowledgeImportNotice.Imported,
    )
}

private val DirectoryEntries = listOf(
    ExternalFolderEntry("/assets", "assets", true, 0, 0),
    ExternalFolderEntry("/src", "src", true, 0, 0),
    ExternalFolderEntry("/README.md", "README.md", false, 4_096, 0),
    ExternalFolderEntry("/架构说明-检索与重排流程.md", "架构说明-检索与重排流程.md", false, 18_432, 0),
)

private val NoopFileActions = DirectoryFileActions(
    onToggleSelect = {}, onClearSelection = {}, onCopy = {}, onPaste = {}, onCancelClipboard = {},
    onDelete = {}, onCreateFolder = { _, _ -> }, onRename = { _, _, _ -> }, onDismissOutcome = {},
)

@Composable
private fun DirectoryBrowserPreview(state: DirectoryBrowserState, mode: InfinityThemeMode = InfinityThemeMode.DARK) = WorkspaceTheme(mode) {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        DirectoryWorkspaceBrowser(
            rootTitle = Project.displayName,
            state = state,
            onNavigate = {}, onOpenFile = {}, onCloseFile = {}, onRefresh = {},
            readFile = { _, _ -> ByteArray(0) },
            fileActions = NoopFileActions,
        )
    }
}

@PreviewTest
@Preview(name = "目录工作区 多选 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Preview(name = "目录工作区 多选 深中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun directoryWorkspaceSelectionPreview() = DirectoryBrowserPreview(
    DirectoryBrowserState(entries = DirectoryEntries, selection = setOf("/src", "/README.md")),
)

@PreviewTest
@Preview(name = "目录工作区 粘贴与部分失败 浅英411", widthDp = 411, heightDp = 640, locale = "en")
@Composable
fun directoryWorkspacePastePreview() = DirectoryBrowserPreview(
    DirectoryBrowserState(
        path = "/src",
        entries = listOf(ExternalFolderEntry("/src/main.kt", "main.kt", false, 1_024, 0)),
        clipboard = DirectoryClipboard(listOf("/README.md", "/assets"), cut = true),
        outcome = DirectoryOpOutcome(DirectoryOp.MOVE, succeeded = 1, failed = 1, reason = DirectoryFileOpCode.NAME_EXISTS),
    ),
    InfinityThemeMode.LIGHT,
)

@PreviewTest
@Preview(name = "删除会话 保留文件 深中411", widthDp = 411, heightDp = 480, locale = "zh-rCN")
@Preview(name = "删除会话 保留文件 浅英360 大字", widthDp = 360, heightDp = 560, locale = "en", fontScale = 1.3f)
@Composable
fun deleteSessionKeepFilesPreview() = WorkspaceTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        DeleteSessionDialogContent(PrivateWorkspaceSummary(12, 3_480_000), keep = true, onKeepChange = {}, onConfirm = {}, onCancel = {})
    }
}

@PreviewTest
@Preview(name = "删除会话 空间为空 深中411", widthDp = 411, heightDp = 320, locale = "zh-rCN")
@Composable
fun deleteSessionEmptyPreview() = WorkspaceTheme {
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        DeleteSessionDialogContent(summary = null, keep = true, onKeepChange = {}, onConfirm = {}, onCancel = {})
    }
}
