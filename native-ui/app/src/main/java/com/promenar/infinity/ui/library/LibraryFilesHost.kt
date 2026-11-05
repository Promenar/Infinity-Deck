package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.rag.KgCollectionRepository
import com.promenar.infinity.data.rag.KgFolderSetting
import com.promenar.infinity.ui.files.FileHostAction
import com.promenar.infinity.ui.files.FileHostActionScope
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.ui.files.FileManagerHostSpec
import com.promenar.infinity.ui.files.FileManagerLeading
import com.promenar.infinity.ui.files.FileManagerPane
import com.promenar.infinity.ui.files.state.FileManagerScope
import com.promenar.infinity.ui.files.state.FileManagerViewModel

/*
 * 资料页里的资源管理器宿主：组件自带页头但不处理系统栏，资料页负责状态栏留白、最左侧按钮与能否固定到侧栏。
 */

private const val LIBRARY_FILES_VIEW_MODEL_KEY = "library-files"

internal fun LibraryLeading.toFileManagerLeading(): FileManagerLeading = when (this) {
    is LibraryLeading.Expand -> if (expanded) FileManagerLeading.Restore(onToggle) else FileManagerLeading.Expand(onToggle)
    is LibraryLeading.Back -> FileManagerLeading.Leave(onBack)
}

@Composable
internal fun LibraryBrowsePage(ref: FileRef, host: LibraryPageHost, leading: LibraryLeading) {
    val app = LocalContext.current.applicationContext as InfinityApplication
    // 资料页只有一个资源管理器实例：左列切换位置时复用，保留排序、视图等偏好。
    val controller: FileManagerViewModel = viewModel(
        key = LIBRARY_FILES_VIEW_MODEL_KEY,
        factory = FileManagerViewModel.factory(app.fileManagerServices, FileManagerScope.Library, ref),
    )
    LaunchedEffect(ref) {
        val current = controller.state.value
        if (current.location?.id != ref.locationId || current.path != ref.path) controller.openLocation(ref.locationId, ref.path)
    }
    // 资料库文件夹的图谱归属设置：记录文件夹 uuid 与名称，面板关闭时清空。
    var graphFolder by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val graphSettingLabel = stringResource(R.string.ks_folder_action)
    Box(Modifier.fillMaxSize().statusBarsPadding().testTag(LibraryTags.page("browse"))) {
        FileManagerPane(
            controller = controller,
            host = FileManagerHostSpec(
                leading = leading.toFileManagerLeading(),
                showPin = true,
                hostActions = listOf(
                    FileHostAction(
                        id = "library-folder-graph", icon = Icons.Rounded.Hub, label = graphSettingLabel,
                        appliesTo = setOf(FileHostActionScope.FOLDER),
                        visibleFor = { items -> items.singleOrNull()?.knowledge?.folderId != null },
                        onInvoke = { items ->
                            items.singleOrNull()?.let { item ->
                                item.knowledge?.folderId?.let { graphFolder = it to item.name }
                            }
                        },
                    ),
                ),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
    graphFolder?.let { (folderId, folderName) -> FolderGraphSettingHost(app, folderId, folderName) { graphFolder = null } }
}

/** 读取并写入文件夹的图谱归属；切换只按已有证据重新归类，不调用模型。 */
@Composable
private fun FolderGraphSettingHost(app: InfinityApplication, folderId: String, folderName: String, onDismiss: () -> Unit) {
    val repository = androidx.compose.runtime.remember(app) { KgCollectionRepository(app.database) }
    val scope = rememberCoroutineScope()
    val selected by produceState<FolderGraphMode?>(null, folderId) {
        value = runCatching { withContext(Dispatchers.IO) { repository.readFolderSetting(folderId) } }
            .getOrDefault(KgFolderSetting.AUTO).toMode()
    }
    FolderGraphSettingSheet(
        folderName = folderName,
        selected = selected,
        onSelect = { mode ->
            scope.launch {
                runCatching { withContext(Dispatchers.IO) { repository.setFolderSetting(folderId, mode.toSetting()) } }
                    .onFailure { InfinityLogger.logError("[Library] 文件夹图谱设置写入失败", it) }
            }
        },
        onDismiss = onDismiss,
    )
}

private fun KgFolderSetting.toMode() = when (this) {
    KgFolderSetting.SHARED -> FolderGraphMode.SHARED
    KgFolderSetting.MERGE_PARENT -> FolderGraphMode.MERGE_PARENT
    KgFolderSetting.AUTO, KgFolderSetting.WORK -> FolderGraphMode.WORK
}

private fun FolderGraphMode.toSetting() = when (this) {
    FolderGraphMode.WORK -> KgFolderSetting.AUTO
    FolderGraphMode.SHARED -> KgFolderSetting.SHARED
    FolderGraphMode.MERGE_PARENT -> KgFolderSetting.MERGE_PARENT
}
