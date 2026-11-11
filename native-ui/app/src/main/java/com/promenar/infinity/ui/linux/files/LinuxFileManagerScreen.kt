package com.promenar.infinity.ui.linux.files

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import com.promenar.infinity.ui.files.FileFolderAction
import com.promenar.infinity.ui.files.FileHostAction
import com.promenar.infinity.ui.files.FileHostActionScope
import com.promenar.infinity.ui.files.FileManagerHostSpec
import com.promenar.infinity.ui.files.FileManagerLeading
import com.promenar.infinity.ui.files.FileManagerPane
import com.promenar.infinity.ui.files.state.FileManagerScope
import com.promenar.infinity.ui.files.state.FileManagerViewModel
import com.promenar.infinity.ui.common.InfinitySearchTopBar
import com.promenar.infinity.ui.testing.UiTags

/*
 * Linux 文件页：用统一资源管理器浏览 Linux 主目录，按设置次级页呈现（标准页头、页头新建），宽屏时在设置右列内推入。
 * 单文件的文本编辑、十六进制、权限与运行仍由 LinuxFileScreen 承担，整个根目录由旧浏览页承担。
 */

/** 资源管理器里 Linux 主目录的位置 id。 */
internal const val LINUX_HOME_LOCATION_ID = "linux:home"

/** 主目录位置内路径与 Linux 绝对路径互转；主目录根为 [LINUX_HOME_PATH]。 */
internal fun linuxAbsolutePath(homeRelative: String): String =
    if (homeRelative == "/" || homeRelative.isEmpty()) LINUX_HOME_PATH else LINUX_HOME_PATH + "/" + homeRelative.trim('/')

internal fun linuxHomeRelativePath(absolute: String): String? = when {
    absolute == LINUX_HOME_PATH || absolute == "$LINUX_HOME_PATH/" -> "/"
    absolute.startsWith("$LINUX_HOME_PATH/") -> "/" + absolute.removePrefix("$LINUX_HOME_PATH/").trim('/')
    else -> null
}

@Composable
fun LinuxFileManagerScreen(
    onBack: () -> Unit,
    onOpenTerminal: (directory: String) -> Unit,
    onOpenEnvironment: () -> Unit,
    onOpenFile: (absolutePath: String) -> Unit,
    onBrowseSystemRoot: () -> Unit,
    initialPath: String? = null,
) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    val runtime by app.linux.runtime.state.collectAsStateWithLifecycle()
    // Surface 同时给出底色与内容色，页头图标与文字随主题取色；底色与设置子页一致。
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.LINUX_FILE_MANAGER),
    ) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            if (runtime is LinuxRuntimeState.Ready) {
                LinuxHomeFileManager(app, onBack, onOpenTerminal, onOpenFile, onBrowseSystemRoot, initialPath)
            } else {
                Column(Modifier.fillMaxSize()) {
                    InfinitySearchTopBar(title = stringResource(R.string.linux_files_title), search = null, onBack = onBack)
                    Box(Modifier.fillMaxWidth().weight(1f)) { LinuxNotReady(runtime, onOpenEnvironment) }
                }
            }
        }
    }
}

@Composable
private fun LinuxHomeFileManager(
    app: InfinityApplication,
    onBack: () -> Unit,
    onOpenTerminal: (directory: String) -> Unit,
    onOpenFile: (absolutePath: String) -> Unit,
    onBrowseSystemRoot: () -> Unit,
    initialPath: String?,
) {
    val initial = initialPath?.let(::linuxHomeRelativePath)?.let { FileRef(LINUX_HOME_LOCATION_ID, it) }
    val controller: FileManagerViewModel = viewModel(
        key = "linux-file-manager",
        factory = FileManagerViewModel.factory(app.fileManagerServices, FileManagerScope.LinuxHome, initial),
    )
    val details = stringResource(R.string.linux_file_host_details)
    val terminal = stringResource(R.string.linux_files_action_terminal)
    val terminalHere = stringResource(R.string.linux_files_open_terminal_here)
    val systemRoot = stringResource(R.string.linux_files_switch_to_root)
    val host = remember(details, terminal, terminalHere, systemRoot, onBack, onOpenTerminal, onOpenFile, onBrowseSystemRoot) {
        FileManagerHostSpec(
            leading = FileManagerLeading.Leave(onBack),
            settingsPage = true,
            hostActions = listOf(
                FileHostAction(
                    id = "linux-details", icon = Icons.Rounded.Description, label = details,
                    appliesTo = setOf(FileHostActionScope.FILE), prominent = true,
                    onInvoke = { items -> items.singleOrNull()?.let { onOpenFile(linuxAbsolutePath(it.ref.path)) } },
                ),
                FileHostAction(
                    id = "linux-terminal", icon = Icons.Rounded.Terminal, label = terminal,
                    appliesTo = setOf(FileHostActionScope.FOLDER),
                    onInvoke = { items -> items.singleOrNull()?.let { onOpenTerminal(linuxAbsolutePath(it.ref.path)) } },
                ),
            ),
            folderActions = listOf(
                FileFolderAction(id = "linux-terminal-here", icon = Icons.Rounded.Terminal, label = terminalHere,
                    onInvoke = { _, path -> onOpenTerminal(linuxAbsolutePath(path)) }),
                FileFolderAction(id = "linux-system-root", icon = Icons.Rounded.AccountTree, label = systemRoot,
                    onInvoke = { _, _ -> onBrowseSystemRoot() }),
            ),
        )
    }
    FileManagerPane(controller = controller, host = host, modifier = Modifier.fillMaxSize())
}
