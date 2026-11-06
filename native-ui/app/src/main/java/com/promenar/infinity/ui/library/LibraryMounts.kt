package com.promenar.infinity.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.ui.chat.components.ExternalFoldersPanel

/**
 * 资料页级外部文件夹的管理面板：复用会话的挂载面板，作用域为资料页。
 * 挂在这里的文件夹不随会话删除，出现在资料页“位置”里；会话可在自己的挂载面板引用同一授权。
 */
@Composable
internal fun LibraryMountsSheet(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    ExternalFoldersPanel(
        sessionId = ExternalMountPaths.LIBRARY_SCOPE,
        workspaceRootUuid = null,
        manager = app.externalFolderManager,
        onReindex = null,
        managerOnly = true,
        onManagerDismiss = onDismiss,
    )
}
