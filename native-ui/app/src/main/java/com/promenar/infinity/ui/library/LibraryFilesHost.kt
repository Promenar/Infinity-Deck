package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.ui.files.FileHostAction
import com.promenar.infinity.ui.files.FileHostActionScope
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
    // 资料库文件夹的合集设置与批量抽取；面板关闭时清空。
    var settingsFolder by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    var batchTarget by remember { mutableStateOf<KgBatchTarget?>(null) }
    val settingsLabel = stringResource(R.string.kgc_action_settings)
    val extractLabel = stringResource(R.string.kgc_action_extract)
    Box(Modifier.fillMaxSize().statusBarsPadding().testTag(LibraryTags.page("browse"))) {
        FileManagerPane(
            controller = controller,
            host = FileManagerHostSpec(
                leading = leading.toFileManagerLeading(),
                showPin = true,
                hostActions = listOf(
                    FileHostAction(
                        id = "library-folder-extract", icon = Icons.Rounded.AutoAwesome, label = extractLabel,
                        appliesTo = setOf(FileHostActionScope.FOLDER),
                        visibleFor = { items -> items.singleOrNull()?.knowledge?.folderId != null },
                        onInvoke = { items -> items.singleOrNull()?.knowledge?.folderId?.let { batchTarget = KgBatchTarget.Folder(it) } },
                    ),
                    FileHostAction(
                        id = "library-folder-graph", icon = Icons.Rounded.Hub, label = settingsLabel,
                        appliesTo = setOf(FileHostActionScope.FOLDER),
                        visibleFor = { items -> items.singleOrNull()?.knowledge?.folderId != null },
                        onInvoke = { items ->
                            items.singleOrNull()?.let { item ->
                                item.knowledge?.folderId?.let { settingsFolder = it to item.name }
                            }
                        },
                    ),
                    // 单个文件与多选共用：选择模式里从 1 项加到多项时菜单不跳变。
                    // 多选跨合集时各篇仍归入各自的合集，一起抽取不会合并实体。
                    FileHostAction(
                        id = "library-multi-extract", icon = Icons.Rounded.AutoAwesome, label = extractLabel,
                        appliesTo = setOf(FileHostActionScope.FILE, FileHostActionScope.MULTI),
                        visibleFor = { items -> items.any { it.knowledge?.documentId != null } },
                        onInvoke = { items ->
                            val ids = items.mapNotNull { it.knowledge?.documentId }.distinct()
                            if (ids.isNotEmpty()) batchTarget = KgBatchTarget.Documents(ids)
                        },
                    ),
                ),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
    settingsFolder?.let { (folderId, folderName) ->
        KgCollectionSettingsHost(app, folderName, folderId = folderId, collectionId = null) { settingsFolder = null }
    }
    batchTarget?.let { target ->
        KgBatchExtractionHost(app, target, onQueued = host.viewModel::reportGraphQueued) { batchTarget = null }
    }
}

