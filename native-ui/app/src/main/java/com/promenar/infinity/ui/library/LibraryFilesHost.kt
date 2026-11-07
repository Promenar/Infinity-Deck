package com.promenar.infinity.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.Category
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FolderCollectionSummary
import com.promenar.infinity.ui.files.FileFolderAction
import com.promenar.infinity.ui.files.FileHeaderBadge
import com.promenar.infinity.ui.files.metaSegments
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.mutableIntStateOf
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
 * 存储空间文件夹的合集与抽取进度放在页头副标题（点按打开合集设置），“更多”菜单与行菜单提供抽取图谱与合集设置，
 * 多选可一起抽取或设置合集；各行的图谱状态与合集标签由资源管理器自己显示。
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
    // 存储空间文件夹的合集设置、批量抽取与文档合集归属；面板关闭时清空。
    var settingsFolder by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    var batchTarget by remember { mutableStateOf<KgBatchTarget?>(null) }
    var assignTarget by remember { mutableStateOf<List<AssignmentDocument>?>(null) }
    // 合集设置关闭、批量抽取入队或归属变化后递增，让当前文件夹的合集副标题重读。
    var refreshKey by remember { mutableIntStateOf(0) }
    val fm by controller.state.collectAsState()
    val current = rememberCurrentFolderCollection(app, fm.location?.id, fm.path, fm.items, refreshKey)
    val settingsLabel = stringResource(R.string.kgc_action_settings)
    val extractLabel = stringResource(R.string.kgc_action_extract)
    val assignLabel = stringResource(R.string.lrc_assign_action)
    val resources = LocalContext.current.resources
    val folderName = fm.path.trimEnd('/').substringAfterLast('/')
    fun refreshListing() {
        refreshKey++
        controller.refresh()
    }
    Box(Modifier.fillMaxSize().statusBarsPadding().testTag(LibraryTags.page("browse"))) {
        FileManagerPane(
            controller = controller,
            host = FileManagerHostSpec(
                leading = leading.toFileManagerLeading(),
                showPin = true,
                folderBadge = current?.summary?.let { summary ->
                    FileHeaderBadge(
                        text = "${summary.extracted}/${summary.total}",
                        description = summary.metaSegments(resources).joinToString(" · "), // wrap-check: allow 无障碍描述
                        icon = Icons.Rounded.Hub,
                        onClickLabel = settingsLabel,
                        onClick = { settingsFolder = current.folderId to folderName },
                    )
                },
                folderActions = current?.let { folder ->
                    listOf(
                        FileFolderAction("library-current-extract", Icons.Rounded.AutoAwesome, extractLabel) { _, _ ->
                            batchTarget = KgBatchTarget.Folder(folder.folderId)
                        },
                        FileFolderAction("library-current-settings", Icons.Rounded.Hub, settingsLabel) { _, _ ->
                            settingsFolder = folder.folderId to folderName
                        },
                    )
                }.orEmpty(),
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
                        visibleFor = { items -> items.any { it.knowledge?.graph != null } },
                        onInvoke = { items ->
                            val ids = items.filter { it.knowledge?.graph != null }.mapNotNull { it.knowledge?.documentId }.distinct()
                            if (ids.isNotEmpty()) batchTarget = KgBatchTarget.Documents(ids)
                        },
                    ),
                    FileHostAction(
                        id = "library-multi-assign", icon = Icons.Rounded.Category, label = assignLabel,
                        appliesTo = setOf(FileHostActionScope.FILE, FileHostActionScope.MULTI),
                        visibleFor = { items -> items.any { it.knowledge?.graph != null } },
                        onInvoke = { items ->
                            val docs = items.mapNotNull { item ->
                                item.knowledge?.takeIf { it.graph != null }?.documentId?.let { AssignmentDocument(it, item.name) }
                            }.distinctBy { it.id }
                            if (docs.isNotEmpty()) assignTarget = docs
                        },
                    ),
                ),
            ),
            modifier = Modifier.fillMaxSize(),
        )
    }
    settingsFolder?.let { (folderId, name) ->
        KgCollectionSettingsHost(app, name, folderId = folderId, collectionId = null) {
            settingsFolder = null
            refreshListing()
        }
    }
    batchTarget?.let { target ->
        KgBatchExtractionHost(app, target, onQueued = host.viewModel::reportGraphQueued) {
            batchTarget = null
            refreshListing()
        }
    }
    assignTarget?.let { docs ->
        DocumentCollectionAssignmentSheet(
            app = app,
            documents = docs,
            onChanged = ::refreshListing,
            onDismiss = { assignTarget = null },
        )
    }
}

/** 当前存储空间文件夹的 id 与合集进度；根目录与其它位置为 null。 */
private data class CurrentFolderCollection(val folderId: String, val summary: FolderCollectionSummary?)

/**
 * 读取当前文件夹的合集与抽取进度。条目的索引与图谱状态变化（逐篇抽完）或 [refreshKey] 递增时重读；
 * 路径变化时先清空，避免副标题停留在上一个文件夹。
 */
@Composable
private fun rememberCurrentFolderCollection(
    app: InfinityApplication,
    locationId: String?,
    path: String,
    items: List<FileItem>,
    refreshKey: Int,
): CurrentFolderCollection? {
    var current by remember(locationId, path) { mutableStateOf<CurrentFolderCollection?>(null) }
    val signature = items.map { Triple(it.knowledge?.state, it.knowledge?.stage, it.knowledge?.graph) }
    LaunchedEffect(locationId, path, signature, refreshKey) {
        if (locationId != LIBRARY_LOCATION_ID || path == "/") {
            current = null
            return@LaunchedEffect
        }
        current = try {
            app.fileManagerServices.operations.stat(FileRef(locationId, path))?.knowledge?.let { knowledge ->
                knowledge.folderId?.let { CurrentFolderCollection(it, knowledge.folderCollection) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            InfinityLogger.logError("[Library] 读取当前文件夹合集失败", error)
            null
        }
    }
    return current
}
