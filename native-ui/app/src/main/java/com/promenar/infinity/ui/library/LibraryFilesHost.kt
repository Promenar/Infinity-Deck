package com.promenar.infinity.ui.library

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.infinity.domain.storage.RemoteOverwritePolicy
import com.promenar.infinity.domain.storage.RemoteTransferRequest
import com.promenar.infinity.ui.common.InfinityNoticeSeverity
import com.promenar.infinity.ui.common.LocalInfinityNoticeController
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LinkOff
import com.promenar.infinity.data.files.isTextFile
import com.promenar.infinity.domain.files.FileItem
import com.promenar.infinity.domain.files.FileLocationStorageKind
import com.promenar.infinity.domain.files.FolderCollectionSummary
import com.promenar.infinity.domain.files.KnowledgeIndexState
import com.promenar.infinity.navigation.NavDestinations
import com.promenar.infinity.ui.common.InfinityConfirmDialog
import com.promenar.infinity.ui.files.FileFolderAction
import com.promenar.infinity.ui.files.FileHeaderBadge
import com.promenar.infinity.ui.files.metaSegments
import com.promenar.infinity.ui.linux.files.LINUX_HOME_LOCATION_ID
import com.promenar.infinity.ui.linux.files.linuxAbsolutePath
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
    var pendingIndexedEdit by remember { mutableStateOf<Pair<String, FileItem>?>(null) }
    // 合集设置关闭、批量抽取入队或归属变化后递增，让当前文件夹的合集副标题重读。
    var refreshKey by remember { mutableIntStateOf(0) }
    val fm by controller.state.collectAsState()
    val rootUuid by host.workspaceRootUuid.collectAsState()
    val current = rememberCurrentFolderCollection(app, fm.location?.id, fm.path, fm.items, refreshKey)
    val settingsLabel = stringResource(R.string.kgc_action_settings)
    val extractLabel = stringResource(R.string.kgc_action_extract)
    val assignLabel = stringResource(R.string.lrc_assign_action)
    val detailsLabel = stringResource(R.string.library_doc_details)
    val editLabel = stringResource(R.string.library_doc_edit)
    val linuxEditLabel = stringResource(R.string.linux_file_host_details)
    val renameWorkspaceLabel = stringResource(R.string.library_location_action_rename_workspace)
    val deleteWorkspaceLabel = stringResource(R.string.library_location_action_delete_workspace)
    val removeListLabel = stringResource(R.string.library_location_action_remove_list)
    val resources = LocalContext.current.resources
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val noticeController = LocalInfinityNoticeController.current
    val transferSummary by remember(host.remoteTransfers) {
        host.remoteTransfers?.summary ?: flowOf(null)
    }.collectAsStateWithLifecycle(initialValue = null)
    val folderName = fm.path.trimEnd('/').substringAfterLast('/')
    val atLocationRoot = fm.path.trimEnd('/').isEmpty()
    val currentLocation = fm.location
    val locationActions = host.locationActions
    val isSftpRemote = currentLocation?.storageKind == FileLocationStorageKind.SFTP_REMOTE
    val uploadQueuedNotice = stringResource(R.string.sftp_mount_notice_upload_queued)
    val uploadFailedNotice = stringResource(R.string.sftp_mount_notice_upload_failed)
    val cacheClearedNotice = stringResource(R.string.sftp_mount_notice_cache_cleared)
    val sftpUploadLabel = stringResource(R.string.sftp_mount_action_upload_archive)
    val sftpEditLabel = stringResource(R.string.sftp_mount_action_edit)
    val sftpToggleRoLabel = stringResource(R.string.sftp_mount_action_toggle_ro)
    val sftpToggleRwLabel = stringResource(R.string.sftp_mount_action_toggle_rw)
    val sftpClearCacheLabel = stringResource(R.string.sftp_mount_action_clear_cache)
    val sftpBadgeText = stringResource(
        if (currentLocation?.remoteReadOnly == true) R.string.mount_sftp_chip_ro else R.string.mount_sftp_chip_rw,
    )

    val uploadArchiveLauncher = if (LocalActivityResultRegistryOwner.current != null) {
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            val picked = uri ?: return@rememberLauncherForActivityResult
            val loc = fm.location ?: return@rememberLauncherForActivityResult
            val mountId = loc.mountId ?: return@rememberLauncherForActivityResult
            val hostAlias = loc.remoteHostAlias ?: return@rememberLauncherForActivityResult
            val rootPath = loc.remoteRootPath ?: return@rememberLauncherForActivityResult
            val currentDir = fm.path
            scope.launch {
                try {
                    val staged = withContext(Dispatchers.IO) {
                        val fileName = resolvePickedDocumentName(context, picked)
                        val stagingDir = File(context.cacheDir, "remote-upload-staging/${UUID.randomUUID()}")
                        check(stagingDir.mkdirs())
                        val outFile = File(stagingDir, fileName)
                        context.contentResolver.openInputStream(picked)?.use { input ->
                            outFile.outputStream().use { output -> input.copyTo(output) }
                        } ?: error("Cannot open selected document")
                        fileName to outFile
                    }
                    val (fileName, outFile) = staged
                    val relPath = (currentDir.trimEnd('/') + "/" + fileName).let { if (it.startsWith("/")) it else "/$it" }
                    val remoteFull = rootPath.trimEnd('/') + relPath
                    app.remoteTransferManager.enqueueUpload(
                        RemoteTransferRequest(
                            mountId = mountId,
                            hostAlias = hostAlias,
                            mountVirtualPath = "/mnt/${loc.title}$relPath",
                            remoteFullPath = remoteFull,
                            localPath = outFile.absolutePath,
                            sourceLabel = fileName,
                            targetLabel = "$hostAlias:$remoteFull",
                            overwritePolicy = RemoteOverwritePolicy.REFUSE_DIFFERING,
                            sessionId = null,
                        ),
                    )
                    noticeController?.show(InfinityNoticeSeverity.SUCCESS, uploadQueuedNotice)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    noticeController?.show(
                        InfinityNoticeSeverity.ERROR,
                        error.message ?: uploadFailedNotice,
                    )
                }
            }
        }
    } else {
        null
    }

    val sftpFolderActions = buildList {
        if (isSftpRemote && currentLocation != null) {
            val mountId = currentLocation.mountId
            if (currentLocation.capabilities.writable && uploadArchiveLauncher != null) {
                add(
                    FileFolderAction("library-sftp-upload", Icons.Rounded.UploadFile, sftpUploadLabel) { _, _ ->
                        uploadArchiveLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
            if (mountId != null) {
                add(
                    FileFolderAction("library-sftp-edit", Icons.Rounded.Edit, sftpEditLabel) { _, _ ->
                        host.onEditSftpMount(mountId)
                    },
                )
                add(
                    FileFolderAction(
                        id = "library-sftp-toggle-rw",
                        icon = if (currentLocation.remoteReadOnly) Icons.Rounded.LockOpen else Icons.Rounded.Lock,
                        label = if (currentLocation.remoteReadOnly) sftpToggleRwLabel else sftpToggleRoLabel,
                    ) { _, _ ->
                        host.viewModel.setSftpMountReadOnly(mountId, !currentLocation.remoteReadOnly)
                    },
                )
            }
            add(
                FileFolderAction("library-sftp-clear-cache", Icons.Rounded.CleaningServices, sftpClearCacheLabel) { _, _ ->
                    scope.launch {
                        app.remotePreviewCache.clear()
                        noticeController?.show(InfinityNoticeSeverity.SUCCESS, cacheClearedNotice)
                    }
                },
            )
        }
    }

    val rootManagementActions = buildList {
        if (atLocationRoot && currentLocation != null && locationActions != null) {
            val wsId = currentLocation.workspaceId
            if (wsId != null) {
                add(
                    FileFolderAction("library-workspace-rename", Icons.Rounded.DriveFileRenameOutline, renameWorkspaceLabel) { _, _ ->
                        locationActions.openRenameWorkspace(wsId, currentLocation.title)
                    },
                )
            }
            if (wsId != null || currentLocation.mountId != null) {
                val deletesFiles = currentLocation.storageKind == FileLocationStorageKind.APP_PROJECT ||
                    currentLocation.storageKind == FileLocationStorageKind.RETAINED_SESSION
                add(
                    FileFolderAction(
                        id = if (deletesFiles) "library-workspace-delete" else "library-location-remove",
                        icon = if (deletesFiles) Icons.Rounded.DeleteOutline else Icons.Rounded.LinkOff,
                        label = if (deletesFiles) deleteWorkspaceLabel else removeListLabel,
                        destructive = true,
                    ) { _, _ ->
                        locationActions.requestRemoveLocation(currentLocation)
                    },
                )
            }
        }
    }
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
                } ?: if (isSftpRemote && currentLocation != null) {
                    val hostPath = listOfNotNull(
                        currentLocation.remoteHostAlias?.let { alias ->
                            val base = currentLocation.remoteRootPath.orEmpty().trimEnd('/')
                            val rel = fm.path.takeUnless { it == "/" }.orEmpty()
                            "$alias:$base$rel"
                        },
                        sftpBadgeText,
                    ).joinToString(" · ") // wrap-check: allow 无障碍描述
                    FileHeaderBadge(
                        text = sftpBadgeText,
                        description = hostPath,
                        icon = Icons.Rounded.Cloud,
                    )
                } else {
                    null
                },
                headerActions = if (isSftpRemote) {
                    {
                        RemoteTransferHeaderButton(
                            activeCount = transferSummary?.activeCount ?: 0,
                            onClick = { host.navigator.openSection(LibraryPageKeys.TRANSFERS) },
                        )
                    }
                } else {
                    null
                },
                onOpenTransfers = { host.navigator.openSection(LibraryPageKeys.TRANSFERS) },
                folderActions = buildList {
                    current?.let { folder ->
                        add(
                            FileFolderAction("library-current-extract", Icons.Rounded.AutoAwesome, extractLabel) { _, _ ->
                                batchTarget = KgBatchTarget.Folder(folder.folderId)
                            },
                        )
                        add(
                            FileFolderAction("library-current-settings", Icons.Rounded.Hub, settingsLabel) { _, _ ->
                                settingsFolder = folder.folderId to folderName
                            },
                        )
                    }
                    addAll(sftpFolderActions)
                    addAll(rootManagementActions)
                },
                hostActions = listOf(
                    FileHostAction(
                        id = "library-file-details", icon = Icons.Outlined.Info, label = detailsLabel,
                        appliesTo = setOf(FileHostActionScope.FILE), headerAction = true,
                        visibleFor = { items -> items.singleOrNull()?.knowledge?.documentId != null },
                        onInvoke = { items -> items.singleOrNull()?.knowledge?.documentId?.let { host.navigator.push(LibraryPageKeys.document(it)) } },
                    ),
                    FileHostAction(
                        id = "library-file-edit", icon = Icons.Rounded.Edit, label = editLabel,
                        appliesTo = setOf(FileHostActionScope.FILE), headerAction = true,
                        visibleFor = { items ->
                            val item = items.singleOrNull()
                            rootUuid != null && item?.knowledge?.documentId != null && isTextFile(item.name, item.mimeType)
                        },
                        onInvoke = { items ->
                            val item = items.singleOrNull() ?: return@FileHostAction
                            val knowledge = item.knowledge ?: return@FileHostAction
                            val docId = knowledge.documentId ?: return@FileHostAction
                            val root = rootUuid ?: return@FileHostAction
                            val state = knowledge.state
                            if (state == KnowledgeIndexState.INDEXED || state == KnowledgeIndexState.INDEXING) {
                                pendingIndexedEdit = docId to item
                            } else {
                                host.onNavigateToSecondary(NavDestinations.docEditor(root, docId))
                            }
                        },
                    ),
                    FileHostAction(
                        id = "library-linux-edit", icon = Icons.Rounded.Edit, label = linuxEditLabel,
                        appliesTo = setOf(FileHostActionScope.FILE), headerAction = true,
                        visibleFor = { items -> items.singleOrNull()?.ref?.locationId == LINUX_HOME_LOCATION_ID },
                        onInvoke = { items ->
                            items.singleOrNull()?.let { host.onNavigateToSecondary(NavDestinations.linuxFile(linuxAbsolutePath(it.ref.path))) }
                        },
                    ),
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
    pendingIndexedEdit?.let { (docId, item) ->
        InfinityConfirmDialog(
            title = stringResource(R.string.fm_edit_indexed_title),
            message = stringResource(R.string.fm_edit_indexed_message, item.name),
            confirmText = stringResource(R.string.fm_edit_indexed_confirm),
            icon = Icons.Rounded.Edit,
            onConfirm = {
                pendingIndexedEdit = null
                rootUuid?.let { host.onNavigateToSecondary(NavDestinations.docEditor(it, docId)) }
            },
            onCancel = { pendingIndexedEdit = null },
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

private fun resolvePickedDocumentName(context: android.content.Context, uri: Uri): String {
    val display = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()?.trim()
    val raw = display?.takeIf { it.isNotBlank() }
        ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
        ?: "upload.bin"
    val sanitized = raw.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("").trim()
    return sanitized.takeUnless { it.isBlank() || it == "." || it == ".." || it.startsWith(".infinity-part-") }
        ?: "upload-${System.currentTimeMillis()}.bin"
}
