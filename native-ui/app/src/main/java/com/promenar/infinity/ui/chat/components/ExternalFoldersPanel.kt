package com.promenar.infinity.ui.chat.components

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.infinity.BuildConfig
import com.promenar.infinity.R
import com.promenar.infinity.data.storage.RealPathMountSupport
import com.promenar.infinity.distribution.DistributionCapabilities
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalMount
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.ui.workspace.KnowledgeReadOnlyTag
import com.promenar.infinity.ui.workspace.SharedFolderPicker
import com.promenar.infinity.ui.theme.breakableIdentifier
import com.promenar.infinity.domain.storage.IExternalFolderManager
import com.promenar.infinity.ui.common.ConfirmDialog
import com.promenar.infinity.ui.common.FolderBreadcrumbsBar
import com.promenar.infinity.ui.common.FolderCrumb
import com.promenar.infinity.ui.common.FolderEntriesList
import com.promenar.infinity.ui.common.FolderStatusMessage
import com.promenar.infinity.ui.common.InfinityBottomSheet
import com.promenar.infinity.ui.common.InfinityDropdownMenu
import com.promenar.infinity.ui.common.InfinityIdentifierText
import com.promenar.infinity.ui.common.InfinityMetaLine
import com.promenar.infinity.ui.common.InfinityNavigationHeader
import com.promenar.infinity.ui.common.InfinityPillButton
import com.promenar.infinity.ui.common.InfinitySettingsGroup
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsRowLayout
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.common.InfinitySheetHeight
import com.promenar.infinity.ui.common.SettingsToggle
import com.promenar.infinity.ui.common.bettboxListGroup
import com.promenar.infinity.ui.theme.InfinityMotion
import com.promenar.infinity.ui.theme.InfinitySpacing
import com.promenar.infinity.ui.theme.InfinityTypography
import com.promenar.infinity.ui.theme.forInput
import com.promenar.infinity.ui.theme.hierarchicalTransition
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.promenar.infinity.ui.common.InfinityMenuItem

/** 挂载状态按当前会话隔离；管理、添加与浏览在同一个全高面板内逐页切换。 */
@Composable
internal fun ExternalFoldersPanel(
    sessionId: String?,
    workspaceRootUuid: String?,
    manager: IExternalFolderManager,
    onReindex: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
    // true 时入口行不自带分组面，由调用方放进共享分组。
    grouped: Boolean = false,
    managerOnly: Boolean = false,
    onManagerDismiss: () -> Unit = {},
) {
    key(sessionId) {
        ExternalFoldersPanelContent(sessionId, workspaceRootUuid, manager, onReindex, modifier, grouped, managerOnly, onManagerDismiss)
    }
}

@Composable
private fun ExternalFoldersPanelContent(
    sessionId: String?,
    workspaceRootUuid: String?,
    manager: IExternalFolderManager,
    onReindex: ((String) -> Unit)?,
    modifier: Modifier,
    grouped: Boolean,
    managerOnly: Boolean,
    onManagerDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val sessionKey = sessionId.orEmpty()
    var mounts by remember { mutableStateOf<List<ExternalMount>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var managerOpen by rememberSaveable { mutableStateOf(managerOnly) }
    var formSource by rememberSaveable { mutableStateOf<ExternalMountSource?>(null) }
    var alias by rememberSaveable { mutableStateOf("") }
    var sourcePath by rememberSaveable { mutableStateOf("") }
    var manualPath by rememberSaveable { mutableStateOf(false) }
    var pickingFolder by rememberSaveable { mutableStateOf(false) }
    // 表单里选定的系统授权文件夹：授权在点“挂载文件夹”时才持久保存，中途离开不留下授权。
    var pickedTreeUri by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedTreeFlags by rememberSaveable { mutableStateOf(0) }
    var pickedTreeName by rememberSaveable { mutableStateOf<String?>(null) }
    var pickedTreeLocation by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFormPick by rememberSaveable { mutableStateOf(false) }
    var pendingMountId by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerActive by rememberSaveable { mutableStateOf(false) }
    var browseMountId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeMountId by rememberSaveable { mutableStateOf<String?>(null) }
    var realPathPermission by remember { mutableStateOf(false) }
    // Linux 会话只能挂载真实路径（以 /mnt/<别名> 绑定进 Linux）；SAF 目录对 Linux 会话的 Agent 不可见。
    val app = context.applicationContext as? com.promenar.infinity.InfinityApplication
    val runMode = remember(app, sessionKey) {
        if (app == null || sessionKey.isBlank()) kotlinx.coroutines.flow.flowOf(null)
        else app.sessionRunModes.observe(sessionKey)
    }.collectAsState(initial = null).value?.mode
    val linuxSession = runMode == com.promenar.infinity.domain.model.SessionRunMode.LINUX
    val sources = availableExternalMountSources(
        BuildConfig.ALL_FILES_ACCESS_AVAILABLE,
        DistributionCapabilities.allFilesAccessAvailable,
        linuxSession = linuxSession,
    )
    // 挂载变化后重算知识库挂载预览：已挂载或当前会话不允许时，入口置灰。
    var knowledgePreview by remember { mutableStateOf<com.promenar.infinity.data.knowledge.KnowledgeAccessPreview?>(null) }
    LaunchedEffect(app, sessionKey, mounts) {
        knowledgePreview = if (app == null || sessionKey.isBlank()) null else try {
            withContext(Dispatchers.IO) { app.knowledgeAccessPlanner.preview(sessionKey) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }

    val perform: (suspend () -> Unit) -> Unit = { action ->
        if (!busy) {
            busy = true
            notice = null
            scope.launch {
                try {
                    action()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    notice = R.string.mount_operation_error
                } finally {
                    busy = false
                }
            }
        }
    }

    val primaryVolumeLabel = stringResource(R.string.workspace_picker_shared_volume)
    // 别名仍是自动生成的默认值时改用选定目录名。
    val adoptFolderAlias: (String?) -> Unit = { folderName ->
        if (folderName != null && isGeneratedMountAlias(alias)) {
            mountAliasFromFolderName(folderName, mounts.map { it.alias })?.let { alias = it }
        }
    }
    val treePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        pickerActive = false
        val formPick = pendingFormPick
        val requestedMountId = pendingMountId
        pendingFormPick = false
        pendingMountId = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val resultIntent = result.data ?: return@rememberLauncherForActivityResult
        val treeUri = resultIntent.data ?: return@rememberLauncherForActivityResult
        if (requestedMountId != null) {
            perform {
                manager.reauthorize(requestedMountId, treeUri.toString(), resultIntent.flags)
                formSource = null
                managerOpen = true
            }
        } else if (formPick && formSource == ExternalMountSource.Saf) {
            pickedTreeUri = treeUri.toString()
            pickedTreeFlags = resultIntent.flags
            pickedTreeName = null
            pickedTreeLocation = null
            perform {
                val picked = withContext(Dispatchers.IO) { describeSafTree(context, treeUri, primaryVolumeLabel) }
                if (pickedTreeUri == treeUri.toString()) {
                    pickedTreeName = picked.name
                    pickedTreeLocation = picked.location
                    adoptFolderAlias(picked.name)
                }
            }
        }
    }

    val launchTreePicker: (Boolean, String?) -> Unit = { formPick, requestedMountId ->
        pendingFormPick = formPick
        pendingMountId = requestedMountId
        pickerActive = true
        notice = null
        try {
            treePicker.launch(
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                ),
            )
        } catch (_: Exception) {
            pickerActive = false
            pendingFormPick = false
            pendingMountId = null
            notice = R.string.mount_operation_error
        }
    }

    val refresh: () -> Unit = {
        scope.launch {
            if (BuildConfig.ALL_FILES_ACCESS_AVAILABLE && DistributionCapabilities.allFilesAccessAvailable) {
                realPathPermission = RealPathMountSupport.hasPermission(context)
            }
            try {
                manager.refresh(sessionKey)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice = R.string.mount_load_error
            }
        }
    }
    val currentRefresh by rememberUpdatedState(refresh)
    DisposableEffect(lifecycleOwner, manager, sessionKey) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) currentRefresh()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 会话面板额外列出资料库里的文件夹，供本会话引用同一授权；资料库面板自身不列。
    val libraryScope = ExternalMountPaths.isLibraryScope(sessionKey)
    var libraryMounts by remember { mutableStateOf<List<ExternalMount>>(emptyList()) }
    LaunchedEffect(manager, sessionKey) {
        if (libraryScope || sessionKey.isBlank()) return@LaunchedEffect
        try {
            manager.observeMounts(ExternalMountPaths.LIBRARY_SCOPE).collect { libraryMounts = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            libraryMounts = emptyList()
        }
    }
    LaunchedEffect(manager, sessionKey) {
        currentRefresh()
        try {
            manager.observeMounts(sessionKey).collect {
                mounts = visibleExternalMounts(it, sessionId)
                loading = false
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loading = false
            notice = R.string.mount_load_error
        }
    }

    val openAccessSettings: (() -> Unit)? =
        if (BuildConfig.ALL_FILES_ACCESS_AVAILABLE && DistributionCapabilities.allFilesAccessAvailable) {
            {
                try {
                    val intent = RealPathMountSupport.permissionIntent(context)
                    if (intent == null) notice = R.string.mount_operation_error
                    else context.startActivity(intent)
                } catch (_: Exception) {
                    notice = R.string.mount_operation_error
                }
            }
        } else null

    if (!managerOnly) ListItem(
        modifier = modifier
            .fillMaxWidth()
            .let { if (grouped) it else it.bettboxListGroup() }
            .testTag("external_folders_entry")
            .clickable(role = Role.Button) { managerOpen = true },
        headlineContent = { Text(stringResource(R.string.mount_title)) },
        supportingContent = {
            val label = notice ?: if (mounts.isEmpty()) R.string.mount_empty else R.string.mount_count
            Text(
                text = if (label == R.string.mount_count) stringResource(label, mounts.size) else stringResource(label),
                color = if (notice != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        leadingContent = { Icon(Icons.Rounded.FolderOpen, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
        trailingContent = {
            if (loading) CircularProgressIndicator(modifier = Modifier.size(24.dp))
            else Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        },
    )

    if (managerOpen) {
        val browseMount = mounts.firstOrNull { it.id == browseMountId }
        val page = when {
            formSource == ExternalMountSource.RealPath && pickingFolder && ExternalMountSource.RealPath in sources ->
                ExternalMountPage.PickFolder
            formSource != null && formSource in sources -> ExternalMountPage.Form(formSource!!)
            browseMount != null -> ExternalMountPage.Browse(browseMount.id)
            else -> ExternalMountPage.Manager
        }
        val backToManager: () -> Unit = {
            formSource = null
            pickingFolder = false
            browseMountId = null
            notice = null
        }
        InfinityBottomSheet(
            show = true,
            onDismiss = {
                managerOpen = false
                backToManager()
                onManagerDismiss()
            },
            height = InfinitySheetHeight.Full,
            dismissEnabled = !(busy || pickerActive),
            // 面板内有可滚动列表与输入框，关闭拖动避免与内容滚动争抢。
            dragGesturesEnabled = false,
        ) {
            BackHandler(enabled = page is ExternalMountPage.Form) {
                if (!busy && !pickerActive) backToManager()
            }
            AnimatedContent(
                targetState = page,
                transitionSpec = { hierarchicalTransition { it.depth } },
                label = "external_mount_page",
            ) { current ->
                when (current) {
                    ExternalMountPage.Manager -> ExternalMountManagerContent(
                        mounts = mounts,
                        sources = sources,
                        loading = loading,
                        busy = busy || pickerActive,
                        notice = notice,
                        onRefresh = refresh,
                        onAdd = { source ->
                            alias = nextGeneratedMountAlias(mounts.map { it.alias })
                            sourcePath = ""
                            manualPath = false
                            pickedTreeUri = null
                            pickedTreeName = null
                            pickedTreeLocation = null
                            notice = null
                            formSource = source
                        },
                        onBrowse = { notice = null; browseMountId = it.id },
                        onReauthorize = { launchTreePicker(false, it.id) },
                        onRemove = { removeMountId = it.id },
                        onGrantRealPath = openAccessSettings,
                        linuxSession = linuxSession,
                        libraryScope = libraryScope,
                        libraryChoices = libraryMountChoices(libraryMounts, mounts, linuxSession),
                        onAttachLibrary = { library -> perform { manager.attachLibraryMount(sessionKey, library.id) } },
                        knowledgeMount = knowledgePreview?.takeUnless { libraryScope }?.let(::knowledgeMountOption),
                        onMountKnowledge = {
                            val planner = app?.knowledgeAccessPlanner
                            if (planner != null && sessionKey.isNotBlank()) perform {
                                withContext(Dispatchers.IO) { planner.mount(planner.preview(sessionKey)) }
                            }
                        },
                    )
                    is ExternalMountPage.Form -> ExternalMountFormContent(
                        source = current.source,
                        alias = alias,
                        sourcePath = sourcePath,
                        pickedFolder = if (current.source == ExternalMountSource.Saf) {
                            pickedTreeUri?.let { uri ->
                                PickedMountFolder(pickedTreeName ?: safTreeFallbackName(Uri.decode(uri.substringAfterLast('/'))) ?: uri, pickedTreeLocation)
                            }
                        } else pickedRealPathFolder(sourcePath),
                        manualPath = manualPath,
                        hasCurrentSession = !sessionId.isNullOrBlank(),
                        realPathPermission = realPathPermission,
                        busy = busy || pickerActive,
                        notice = notice,
                        onAliasChanged = { alias = it },
                        onPathChanged = { sourcePath = it },
                        onShowManualPath = { manualPath = true },
                        onPickFolder = {
                            notice = null
                            if (current.source == ExternalMountSource.Saf) launchTreePicker(true, null)
                            else pickingFolder = true
                        },
                        onGrantAccess = openAccessSettings,
                        onBack = backToManager,
                        onConfirm = {
                            val treeUri = pickedTreeUri
                            if (current.source == ExternalMountSource.Saf) {
                                if (treeUri != null) perform {
                                    manager.addSaf(
                                        alias = alias.trim(),
                                        sessionId = requireNotNull(sessionId?.takeIf(String::isNotBlank)),
                                        treeUri = treeUri,
                                        grantFlags = pickedTreeFlags,
                                    )
                                    pickedTreeUri = null
                                    formSource = null
                                }
                            } else if (BuildConfig.ALL_FILES_ACCESS_AVAILABLE && DistributionCapabilities.allFilesAccessAvailable) {
                                perform {
                                    manager.addRealPath(
                                        alias = alias.trim(),
                                        sessionId = requireNotNull(sessionId?.takeIf(String::isNotBlank)),
                                        source = sourcePath.trim(),
                                    )
                                    formSource = null
                                }
                            }
                        },
                    )
                    ExternalMountPage.PickFolder -> SharedFolderPicker(
                        initialPath = sourcePath,
                        onPicked = { path ->
                            sourcePath = path
                            adoptFolderAlias(pickedRealPathFolder(path)?.name)
                            pickingFolder = false
                        },
                        onExit = { pickingFolder = false },
                    )
                    is ExternalMountPage.Browse -> mounts.firstOrNull { it.id == current.mountId }?.let { mount ->
                        ExternalMountBrowserPage(
                            mount = mount,
                            sessionId = sessionKey,
                            workspaceRootUuid = workspaceRootUuid,
                            manager = manager,
                            onReindex = onReindex,
                            onExit = backToManager,
                        )
                    }
                }
            }
        }
    }

    // 移除资料库文件夹前查出引用同一来源的会话数，提示这些引用会保留。
    var removeReferences by remember { mutableStateOf(0) }
    LaunchedEffect(removeMountId, libraryScope) {
        val mountId = removeMountId
        removeReferences = 0
        if (!libraryScope || mountId == null) return@LaunchedEffect
        removeReferences = try {
            manager.libraryMountReferences(mountId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            0
        }
    }
    removeMountId?.let { mountId ->
        val removeMessage = stringResource(R.string.mount_remove_message)
        val referencesNote = if (removeReferences > 0) {
            pluralStringResource(R.plurals.library_mounts_remove_references, removeReferences, removeReferences)
        } else null
        ConfirmDialog(
            show = true,
            onDismiss = { if (!busy) removeMountId = null },
            onConfirm = {
                perform {
                    manager.remove(mountId)
                    removeMountId = null
                }
            },
            title = stringResource(R.string.mount_remove_title),
            description = listOfNotNull(removeMessage, referencesNote).joinToString("\n\n"),
            confirmLabel = stringResource(R.string.mount_remove),
        )
    }
}

/**
 * 系统授权文件夹的显示名与可读位置：名称取提供方给出的目录显示名，取不到时用文档 ID 末段；
 * 外部存储显示“内部存储/…”位置，其它提供方（如 Termux）显示提供方应用名。查询失败不阻断挂载。
 */
private fun describeSafTree(context: Context, treeUri: Uri, primaryVolumeLabel: String): PickedMountFolder {
    val documentId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
    val displayName = documentId?.let { id ->
        runCatching {
            context.contentResolver.query(
                DocumentsContract.buildDocumentUriUsingTree(treeUri, id),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
        }.getOrNull()
    }?.takeIf(String::isNotBlank)
    val location = safTreeLocationLabel(treeUri.authority, documentId, primaryVolumeLabel)
        ?: runCatching {
            val packageManager = context.packageManager
            treeUri.authority?.let { packageManager.resolveContentProvider(it, 0) }
                ?.applicationInfo?.loadLabel(packageManager)?.toString()
        }.getOrNull()
    return PickedMountFolder(displayName ?: safTreeFallbackName(documentId) ?: treeUri.toString(), location)
}

/** 外部文件夹面板内的页面；挂载管理为根，表单和浏览比它深一层，表单内选目录再深一层。 */
private sealed interface ExternalMountPage {
    data object Manager : ExternalMountPage
    data class Form(val source: ExternalMountSource) : ExternalMountPage
    /** 存储路径表单内浏览手机存储选目录，比表单再深一层。 */
    data object PickFolder : ExternalMountPage
    data class Browse(val mountId: String) : ExternalMountPage

    val depth: Int get() = when (this) {
        Manager -> 0
        PickFolder -> 2
        else -> 1
    }
}

/** 挂载管理首页：已挂载与添加方式，刷新放在页头。 */
@Composable
internal fun ExternalMountManagerContent(
    mounts: List<ExternalMount>,
    sources: List<ExternalMountSource>,
    loading: Boolean,
    busy: Boolean,
    notice: Int?,
    onRefresh: () -> Unit,
    onAdd: (ExternalMountSource) -> Unit,
    onBrowse: (ExternalMount) -> Unit,
    onReauthorize: (ExternalMount) -> Unit,
    onRemove: (ExternalMount) -> Unit,
    onGrantRealPath: (() -> Unit)?,
    modifier: Modifier = Modifier,
    linuxSession: Boolean = false,
    /** 资料库作用域：标题与说明改为资料库文件夹，不随会话删除。 */
    libraryScope: Boolean = false,
    /** 本会话尚未引用的资料库文件夹。 */
    libraryChoices: List<ExternalMount> = emptyList(),
    onAttachLibrary: (ExternalMount) -> Unit = {},
    /** 为空时不显示“挂载知识库”入口（无会话或无法取得预览）。 */
    knowledgeMount: KnowledgeMountOption? = null,
    onMountKnowledge: () -> Unit = {},
) = Column(modifier.fillMaxSize()) {
    InfinityNavigationHeader(
        title = stringResource(if (libraryScope) R.string.library_mounts_title else R.string.mount_title),
        titleStyle = MaterialTheme.typography.titleLarge,
        actions = {
            MountRefreshAction(refreshing = loading || busy, onRefresh = onRefresh)
        },
    )
    MountNoticeBanner(notice)
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = InfinitySpacing.Small)
            .testTag("external_mounts_list"),
    ) {
        InfinitySettingsSection(
            title = stringResource(R.string.mount_section_mounted),
            footer = when {
                libraryScope -> stringResource(R.string.library_mounts_empty_hint)
                mounts.isEmpty() && !loading -> stringResource(R.string.mount_empty_hint)
                else -> null
            },
        ) {
            Column(Modifier.animateContentSize(tween(InfinityMotion.DurationMedium, easing = InfinityMotion.EmphasizedDecelerate))) {
                if (mounts.isEmpty()) {
                    InfinitySettingsRowLayout(
                        title = stringResource(if (loading) R.string.shared_loading else R.string.mount_empty),
                        titleColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        icon = Icons.Rounded.FolderOff,
                    )
                } else {
                    mounts.forEach { mount ->
                        key(mount.id) { ExternalMountRow(mount, busy, onBrowse, onReauthorize, onRemove, onGrantRealPath, linuxSession, libraryScope) }
                    }
                }
            }
        }
        if (libraryChoices.isNotEmpty()) {
            InfinitySettingsSection(
                title = stringResource(R.string.library_mounts_attach_section),
                footer = stringResource(R.string.library_mounts_attach_hint),
            ) {
                libraryChoices.forEach { library ->
                    key(library.id) {
                        InfinitySettingsItem(
                            title = library.alias,
                            subtitle = stringResource(R.string.library_mounts_attach_desc, library.alias),
                            onClick = { onAttachLibrary(library) },
                            enabled = !busy && library.unavailableReason == null,
                            leadingContent = {
                                Icon(Icons.AutoMirrored.Rounded.LibraryBooks, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            },
                            modifier = Modifier.testTag("external_mount_attach_library_${library.id}"),
                        )
                    }
                }
            }
        }
        InfinitySettingsSection(
            title = stringResource(R.string.agent_panels_mount_add),
            footer = listOfNotNull(
                stringResource(R.string.run_mode_folder_real_path_only).takeIf { linuxSession },
                stringResource(R.string.mount_system_limits),
                stringResource(R.string.mount_termux_hint),
            ).joinToString("\n"),
        ) {
            sources.forEach { source ->
                val saf = source == ExternalMountSource.Saf
                InfinitySettingsItem(
                    title = stringResource(if (saf) R.string.mount_add_saf else R.string.mount_add_real_path),
                    subtitle = stringResource(if (saf) R.string.mount_add_saf_desc else R.string.mount_add_real_path_desc),
                    subtitleMaxLines = 2,
                    onClick = { onAdd(source) },
                    enabled = !busy,
                    showChevron = true,
                    leadingContent = {
                        Icon(
                            if (saf) Icons.Rounded.CreateNewFolder else Icons.Rounded.SdStorage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    modifier = Modifier.testTag("external_mount_add_" + source.name.lowercase()),
                )
            }
            knowledgeMount?.let { option ->
                InfinitySettingsItem(
                    title = stringResource(R.string.knowledge_mount_add),
                    subtitle = when (option) {
                        is KnowledgeMountOption.Available -> stringResource(R.string.knowledge_mount_add_desc, option.paths.joinToString("、"))
                        KnowledgeMountOption.Mounted -> stringResource(R.string.knowledge_mount_add_mounted)
                        KnowledgeMountOption.Unavailable -> stringResource(R.string.knowledge_mount_add_unavailable)
                    },
                    subtitleMaxLines = 2,
                    onClick = onMountKnowledge,
                    enabled = !busy && option is KnowledgeMountOption.Available,
                    leadingContent = {
                        Icon(Icons.AutoMirrored.Rounded.LibraryBooks, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    modifier = Modifier.testTag(KNOWLEDGE_MOUNT_ADD_TAG),
                )
            }
        }
    }
}

@Composable
private fun MountRefreshAction(refreshing: Boolean, onRefresh: () -> Unit) {
    Box(Modifier.size(InfinitySpacing.MinimumTouchTarget), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = refreshing,
            transitionSpec = { InfinityMotion.fadeThrough() },
            label = "mount_refresh_action",
        ) { active ->
            if (active) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.mount_refresh))
                }
            }
        }
    }
}

@Composable
private fun ExternalMountRow(
    mount: ExternalMount,
    busy: Boolean,
    onBrowse: (ExternalMount) -> Unit,
    onReauthorize: (ExternalMount) -> Unit,
    onRemove: (ExternalMount) -> Unit,
    onGrantRealPath: (() -> Unit)?,
    linuxSession: Boolean = false,
    /** 资料库挂载不属于任何会话：显示来源位置，不显示 /mnt 别名与命令可见性。 */
    libraryScope: Boolean = false,
) {
    var menuOpen by remember(mount.id) { mutableStateOf(false) }
    val unavailable = mount.unavailableReason != null
    val unavailableLabel = stringResource(R.string.mount_unavailable)
    val knowledgeBase = mount.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE
    val sourceLabel = stringResource(if (mount.sourceType == "real_path") R.string.mount_source_real_path else R.string.mount_source_saf)
    val primaryVolumeLabel = stringResource(R.string.workspace_picker_shared_volume)
    val meta = if (libraryScope) listOfNotNull(
        mountSourceLocationLabel(mount.sourceType, mount.sourceId, primaryVolumeLabel),
        sourceLabel,
    ) else listOfNotNull(
        "/mnt/" + mount.alias,
        sourceLabel.takeUnless { knowledgeBase },
        stringResource(when {
            linuxSession && mount.sourceType != "real_path" -> R.string.run_mode_folder_saf_hidden
            mount.commandAccessible -> R.string.agent_reach_mount_command_visible
            else -> R.string.agent_reach_mount_files_only
        }),
    )
    InfinitySettingsItem(
        title = mount.alias,
        subtitle = unavailableLabel.takeIf { unavailable },
        subtitleSegments = meta.takeUnless { unavailable },
        // 分段排版放不下时整段省略，放宽到三行让访问方式始终可见。
        subtitleMaxLines = if (unavailable) 2 else 3,
        // 不可用的挂载点按后直接给出重新授权或移除等操作。
        onClick = { if (unavailable) menuOpen = true else onBrowse(mount) },
        enabled = !busy,
        modifier = Modifier.semantics { if (unavailable) stateDescription = unavailableLabel },
        leadingContent = {
            Icon(
                if (unavailable) Icons.Rounded.ErrorOutline else Icons.Rounded.Folder,
                contentDescription = null,
                tint = if (unavailable) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (knowledgeBase) KnowledgeReadOnlyTag()
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.mount_more_actions, mount.alias))
                    }
                    InfinityDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.mount_browse)) },
                            leadingIcon = { Icon(Icons.Rounded.FolderOpen, null) },
                            enabled = !unavailable,
                            onClick = { menuOpen = false; onBrowse(mount) },
                        )
                        if (mount.sourceType == "saf_tree") InfinityMenuItem(
                            text = { Text(stringResource(R.string.mount_reauthorize)) },
                            leadingIcon = { Icon(Icons.Rounded.Key, null) },
                            onClick = { menuOpen = false; onReauthorize(mount) },
                        )
                        if (mount.sourceType == "real_path" && onGrantRealPath != null) InfinityMenuItem(
                            text = { Text(stringResource(R.string.mount_grant_access)) },
                            leadingIcon = { Icon(Icons.Rounded.Key, null) },
                            onClick = { menuOpen = false; onGrantRealPath() },
                        )
                        InfinityMenuItem(
                            text = { Text(stringResource(R.string.mount_remove), color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(Icons.Rounded.LinkOff, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menuOpen = false; onRemove(mount) },
                        )
                    }
                }
            }
        },
    )
}

/** “挂载知识库”入口的三种状态：可挂载（列出挂载路径）、已全部挂载、当前会话不允许。 */
internal sealed interface KnowledgeMountOption {
    data class Available(val paths: List<String>) : KnowledgeMountOption
    data object Mounted : KnowledgeMountOption
    data object Unavailable : KnowledgeMountOption
}

internal fun knowledgeMountOption(preview: com.promenar.infinity.data.knowledge.KnowledgeAccessPreview): KnowledgeMountOption {
    if (!preview.allowed || preview.knowledgeBases.isEmpty()) return KnowledgeMountOption.Unavailable
    val pending = preview.knowledgeBases.filterNot { it.alreadyMounted }
    return if (pending.isEmpty()) KnowledgeMountOption.Mounted else KnowledgeMountOption.Available(pending.map { it.path })
}

internal const val KNOWLEDGE_MOUNT_ADD_TAG = "external_mount_add_knowledge"


/**
 * 添加挂载：别名与文件夹，主操作固定在底部。挂载只属于当前会话且可读写。
 * 存储路径在应用内浏览手机存储选定，也可展开手动输入；文件夹授权在系统选择器中选定，点“挂载文件夹”时才保存授权。
 */
@Composable
internal fun ExternalMountFormContent(
    source: ExternalMountSource,
    alias: String,
    sourcePath: String,
    pickedFolder: PickedMountFolder?,
    manualPath: Boolean,
    hasCurrentSession: Boolean,
    realPathPermission: Boolean,
    busy: Boolean,
    notice: Int?,
    onAliasChanged: (String) -> Unit,
    onPathChanged: (String) -> Unit,
    onShowManualPath: () -> Unit,
    onPickFolder: () -> Unit,
    onGrantAccess: (() -> Unit)?,
    onBack: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) = Column(modifier.fillMaxSize()) {
    val isSaf = source == ExternalMountSource.Saf
    InfinityNavigationHeader(
        title = stringResource(R.string.mount_form_title),
        titleStyle = MaterialTheme.typography.titleLarge,
        onBack = onBack.takeUnless { busy },
        depth = 1,
        supporting = {
            Text(
                stringResource(if (isSaf) R.string.mount_source_saf else R.string.mount_source_real_path),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
    MountNoticeBanner(notice)
    Column(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = InfinitySpacing.Small),
    ) {
        InfinitySettingsSection(
            title = stringResource(R.string.mount_form_section_target),
            footer = if (isSaf) {
                listOf(R.string.mount_saf_command_note, R.string.mount_system_limits, R.string.mount_termux_hint)
            } else {
                listOf(R.string.mount_real_path_command_note, R.string.mount_real_path_help)
            }.map { stringResource(it) }.joinToString("\n"),
        ) {
            Column(
                Modifier.padding(start = InfinitySpacing.Large, end = InfinitySpacing.Large, top = InfinitySpacing.Large),
                verticalArrangement = Arrangement.spacedBy(InfinitySpacing.Medium),
            ) {
                OutlinedTextField(
                    value = alias,
                    onValueChange = onAliasChanged,
                    label = { Text(stringResource(R.string.mount_alias)) },
                    supportingText = { Text(stringResource(R.string.mount_alias_hint)) },
                    singleLine = true,
                    enabled = !busy,
                    textStyle = InfinityTypography.bodyLarge.forInput(),
                    modifier = Modifier.fillMaxWidth().testTag("external_mount_alias"),
                )
            }
            val pickEnabled = !busy && (isSaf || realPathPermission)
            InfinitySettingsItem(
                // 目录名与路径都只在分隔符处换行；路径末段是要确认的目录，放宽行数让它不被省略。
                title = pickedFolder?.name?.breakableIdentifier() ?: stringResource(R.string.mount_select_folder),
                subtitle = pickedFolder?.location
                    ?: stringResource(if (isSaf) R.string.mount_pick_saf_desc else R.string.mount_pick_real_path_desc),
                subtitleIsIdentifier = pickedFolder?.location != null,
                subtitleMaxLines = 6,
                onClick = onPickFolder,
                enabled = pickEnabled,
                showChevron = true,
                leadingContent = {
                    Icon(
                        if (pickedFolder == null) Icons.Rounded.CreateNewFolder else Icons.Rounded.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                },
                modifier = Modifier.testTag("external_mount_pick_folder"),
            )
            if (!isSaf) {
                if (manualPath) {
                    OutlinedTextField(
                        value = sourcePath,
                        onValueChange = onPathChanged,
                        label = { Text(stringResource(R.string.mount_path)) },
                        supportingText = { Text(stringResource(R.string.mount_path_hint)) },
                        enabled = !busy,
                        textStyle = InfinityTypography.bodyLarge.forInput(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = InfinitySpacing.Large)
                            .padding(bottom = InfinitySpacing.Small)
                            .testTag("external_mount_real_path"),
                    )
                } else {
                    InfinityPillButton(
                        text = stringResource(R.string.mount_manual_path),
                        onClick = onShowManualPath,
                        enabled = !busy,
                        icon = Icons.Rounded.Edit,
                        modifier = Modifier
                            .padding(horizontal = InfinitySpacing.Large)
                            .testTag("external_mount_manual_path"),
                    )
                }
            }
            if (!isSaf && !realPathPermission) {
                InfinitySettingsItem(
                    title = stringResource(R.string.mount_permission_missing),
                    titleColor = MaterialTheme.colorScheme.error,
                    subtitle = stringResource(R.string.mount_grant_access).takeIf { onGrantAccess != null },
                    onClick = { onGrantAccess?.invoke() },
                    enabled = onGrantAccess != null,
                    showChevron = onGrantAccess != null,
                    leadingContent = { Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error) },
                )
            }
        }

    }
    AnimatedVisibility(visible = busy, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = InfinitySpacing.Small))
    }
    Button(
        onClick = onConfirm,
        enabled = hasCurrentSession && !busy && alias.isNotBlank() &&
            (if (isSaf) pickedFolder != null else realPathPermission && sourcePath.isNotBlank()),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = InfinitySpacing.Small)
            .heightIn(min = InfinitySpacing.MinimumTouchTarget)
            .testTag("external_mount_confirm"),
    ) { Text(stringResource(R.string.mount_confirm)) }
}

@Composable
private fun ExternalMountScopeOption(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    InfinitySettingsItem(
        title = label,
        onClick = onClick,
        enabled = enabled,
        role = Role.RadioButton,
        selected = selected,
        leadingContent = { RadioButton(selected = selected, onClick = null, enabled = enabled) },
    )
}

/** 挂载目录的只读浏览：页头逐级返回，根目录返回挂载管理；文件页可加入资料或用其它应用打开。 */
@Composable
private fun ExternalMountBrowserPage(
    mount: ExternalMount,
    sessionId: String,
    workspaceRootUuid: String?,
    manager: IExternalFolderManager,
    onReindex: ((String) -> Unit)?,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rootPath = "/mnt/" + mount.alias
    val externalOpenTitle = stringResource(R.string.mount_open_external)
    var path by rememberSaveable(mount.id) { mutableStateOf(rootPath) }
    var entries by remember(mount.id) { mutableStateOf<List<ExternalFolderEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var selectedFile by remember(mount.id) { mutableStateOf<ExternalFolderEntry?>(null) }
    var preview by remember { mutableStateOf<ExternalTextPreview>(ExternalTextPreview.Loading) }
    var sourceChanged by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<Int?>(null) }
    // 图片、视频、音频按 `/mnt/<别名>/…` 虚拟路径在会话内查看或播放，其它文件保留文本预览。
    val openMedia = rememberWorkspaceMediaOpener(sessionId.takeIf(String::isNotBlank), mount.id)

    LaunchedEffect(manager, sessionId, path, reload, mount.unavailableReason) {
        entries = emptyList()
        loading = true
        loadError = false
        try {
            if (mount.unavailableReason != null) {
                loadError = true
            } else {
                entries = manager.list(sessionId, path).sortedWith(
                    compareByDescending<ExternalFolderEntry> { it.isDirectory }.thenBy { it.name.lowercase() },
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            loadError = true
        } finally {
            loading = false
        }
    }

    LaunchedEffect(manager, sessionId, selectedFile?.path, reload) {
        val entry = selectedFile ?: return@LaunchedEffect
        notice = null
        sourceChanged = false
        preview = ExternalTextPreview.Loading
        try {
            sourceChanged = manager.snapshotChanged(sessionId, entry.path)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 快照状态失败不阻断独立的文件预览。
        }
        preview = if (entry.size > EXTERNAL_TEXT_PREVIEW_BYTES) {
            ExternalTextPreview.TooLarge
        } else {
            try {
                val bytes = manager.read(sessionId, entry.path, EXTERNAL_TEXT_PREVIEW_BYTES.toLong())
                withContext(Dispatchers.Default) {
                    decodeExternalTextPreview(bytes, EXTERNAL_TEXT_PREVIEW_BYTES)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ExternalTextPreview.Failed
            }
        }
    }

    val parentPath = parentExternalFolderPath(rootPath, path)
    BackHandler {
        when {
            busy -> Unit
            selectedFile != null -> { selectedFile = null; notice = null }
            parentPath != null -> path = parentPath
            else -> onExit()
        }
    }
    AnimatedContent(
        targetState = selectedFile,
        contentKey = { it?.path ?: "" },
        transitionSpec = { hierarchicalTransition { if (it != null) 1 else 0 } },
        label = "external_mount_browser",
    ) { entry ->
        if (entry == null) Column(Modifier.fillMaxSize()) {
            val segments = path.removePrefix(rootPath).split('/').filter(String::isNotEmpty)
            val crumbs = segments.mapIndexed { index, name ->
                FolderCrumb(rootPath + "/" + segments.take(index + 1).joinToString("/"), name)
            }
            FolderBreadcrumbsBar(
                folderStack = crumbs,
                rootTitle = mount.alias,
                onNavigateBack = { parentPath?.let { path = it } },
                onNavigateToCrumb = { index -> path = if (index <= 0) rootPath else crumbs[index - 1].id },
                backButtonTag = "external_mount_back",
                onExitRoot = onExit,
                titleStyle = MaterialTheme.typography.titleLarge,
                actions = {
                    IconButton(onClick = { reload++ }, enabled = !loading) {
                        Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.mount_refresh))
                    }
                },
            )
            if (mount.unavailableReason != null) MountNoticeBanner(R.string.mount_unavailable)
            FolderEntriesList(
                path = path,
                depth = crumbs.size,
                entries = entries,
                loading = loading,
                error = stringResource(R.string.mount_load_error).takeIf { loadError },
                emptyText = stringResource(R.string.mount_directory_empty),
                onEntryClick = { file ->
                    when {
                        file.isDirectory -> path = file.path
                        openMedia != null && isPreviewableMediaName(file.name) -> openMedia(file.path)
                        else -> selectedFile = file
                    }
                },
                onRetry = { reload++ },
                listTag = "external_mount_browser",
                modifier = Modifier.weight(1f, fill = false),
            )
        } else Column(Modifier.fillMaxSize()) {
            InfinityNavigationHeader(
                title = entry.name,
                titleStyle = MaterialTheme.typography.titleLarge,
                onBack = ({ selectedFile = null; notice = null }).takeUnless { busy },
                backButtonTag = "external_mount_file_back",
                depth = 1,
                supporting = {
                    InfinityMetaLine(
                        segments = buildList {
                            add(Formatter.formatShortFileSize(context, entry.size))
                            if (entry.lastModified > 0L) add(
                                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                                    .format(Date(entry.lastModified)),
                            )
                        },
                        style = InfinityTypography.bodySmall,
                    )
                },
            )
            InfinityIdentifierText(
                entry.path,
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (sourceChanged) MountNoticeBanner(R.string.mount_source_changed, error = false)
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = InfinitySpacing.Medium),
                horizontalArrangement = Arrangement.spacedBy(InfinitySpacing.Small),
            ) {
                FilledTonalButton(
                    modifier = Modifier.weight(1f).heightIn(min = InfinitySpacing.MinimumTouchTarget),
                    enabled = !busy && workspaceRootUuid != null && mount.unavailableReason == null,
                    onClick = {
                        val rootUuid = workspaceRootUuid ?: return@FilledTonalButton
                        busy = true
                        notice = null
                        scope.launch {
                            try {
                                val fileUuid = manager.importSnapshot(sessionId, rootUuid, entry.path)
                                onReindex?.invoke(fileUuid)
                                sourceChanged = false
                                notice = R.string.mount_imported
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                notice = R.string.mount_operation_error
                            } finally {
                                busy = false
                            }
                        }
                    },
                ) {
                    Text(
                        stringResource(if (sourceChanged) R.string.mount_reimport_to_kb else R.string.mount_import_to_kb),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f).heightIn(min = InfinitySpacing.MinimumTouchTarget),
                    enabled = !busy && mount.unavailableReason == null,
                    onClick = {
                        if (entry.size > EXTERNAL_OPEN_BYTES) {
                            notice = R.string.mount_open_limit
                        } else {
                            busy = true
                            notice = null
                            scope.launch {
                                try {
                                    val intent = prepareExternalFolderOpen(context, manager, sessionId, entry)
                                    context.startActivity(
                                        Intent.createChooser(intent, externalOpenTitle)
                                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    notice = R.string.mount_open_failed
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    },
                ) { Text(externalOpenTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            AnimatedVisibility(visible = busy, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = InfinitySpacing.Small))
            }
            MountNoticeBanner(notice, error = notice != R.string.mount_imported)
            FileTextPreviewPanel(
                preview = preview,
                failedText = stringResource(R.string.mount_preview_failed),
                binaryText = stringResource(R.string.mount_preview_binary),
                tooLargeText = stringResource(R.string.mount_preview_too_large),
                modifier = Modifier.weight(1f).padding(top = InfinitySpacing.Medium),
            )
            Text(
                stringResource(R.string.mount_snapshot_info),
                style = InfinityTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = InfinitySpacing.RowHorizontal).padding(top = InfinitySpacing.FooterTop),
            )
        }
    }
}

/** 面板顶部的提示条：出现与消失时纵向展开收起，读屏按礼貌级别播报。 */
@Composable
private fun MountNoticeBanner(resourceId: Int?, error: Boolean = true) {
    AnimatedVisibility(visible = resourceId != null, enter = InfinityMotion.expandIn, exit = InfinityMotion.shrinkOut) {
        // 收起动画期间提示已清空，沿用最后一次的文案避免文字先消失。
        val lastResource = remember { IntArray(1) }
        if (resourceId != null) lastResource[0] = resourceId
        val shown = lastResource[0].takeIf { it != 0 } ?: return@AnimatedVisibility
        Text(
            text = stringResource(shown),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = InfinitySpacing.XSmall)
                .clip(RoundedCornerShape(12.dp))
                .background(
                    if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                )
                .padding(horizontal = InfinitySpacing.Large, vertical = InfinitySpacing.Medium)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
            style = InfinityTypography.bodySmall,
        )
    }
}

private suspend fun prepareExternalFolderOpen(
    context: Context,
    manager: IExternalFolderManager,
    sessionId: String,
    entry: ExternalFolderEntry,
): Intent = prepareExternalFileOpen(context, entry.name) { maxBytes -> manager.read(sessionId, entry.path, maxBytes) }

/** 外部应用只获得私有快照的临时读权限，不暴露来源路径或写权限；[read] 由调用方经目录后端读取。 */
internal suspend fun prepareExternalFileOpen(
    context: Context,
    name: String,
    read: suspend (maxBytes: Long) -> ByteArray,
): Intent = withContext(Dispatchers.IO) {
    val bytes = read(EXTERNAL_OPEN_BYTES)
    check(bytes.size.toLong() <= EXTERNAL_OPEN_BYTES)
    val previewsRoot = File(context.filesDir, "external-folder-previews")
    check(previewsRoot.isDirectory || previewsRoot.mkdirs())
    val retained = previewsRoot.listFiles().orEmpty().sortedByDescending { it.lastModified() }
    retained.forEachIndexed { index, file ->
        if (index >= 3 || System.currentTimeMillis() - file.lastModified() > EXTERNAL_PREVIEW_RETENTION_MS) {
            file.deleteRecursively()
        }
    }
    val directory = File(previewsRoot, UUID.randomUUID().toString())
    check(directory.mkdir())
    val snapshot = File(directory, externalPreviewFileName(name))
    snapshot.writeBytes(bytes)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".fileprovider", snapshot)
    val extension = snapshot.extension.lowercase()
    val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mimeType)
        clipData = ClipData.newRawUri(name, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private const val EXTERNAL_TEXT_PREVIEW_BYTES = 256 * 1024
internal const val EXTERNAL_OPEN_BYTES = 8L * 1024 * 1024
private const val EXTERNAL_PREVIEW_RETENTION_MS = 24L * 60 * 60 * 1000
