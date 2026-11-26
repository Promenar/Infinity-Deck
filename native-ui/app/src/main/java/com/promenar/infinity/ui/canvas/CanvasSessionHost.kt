package com.promenar.infinity.ui.canvas

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.ui.common.InfinityFullscreenDialog
import com.promenar.infinity.R
import com.promenar.infinity.data.canvas.CanvasDocument
import com.promenar.infinity.data.canvas.CanvasMetadata
import com.promenar.infinity.data.canvas.CanvasStore
import com.promenar.infinity.data.canvas.CanvasWorkspaceSync
import com.promenar.infinity.data.canvas.CanvasKind
import com.promenar.infinity.data.artifact.ArtifactCandidate
import com.promenar.infinity.data.artifact.ArtifactException
import com.promenar.infinity.data.artifact.ArtifactFileSnapshot
import com.promenar.infinity.data.artifact.ArtifactOrigin
import com.promenar.infinity.data.artifact.ArtifactSummary
import com.promenar.infinity.data.artifact.SessionArtifactRepository
import com.promenar.infinity.data.artifact.WorkspaceChangedFile
import com.promenar.infinity.data.artifact.isPathExcluded
import com.promenar.infinity.data.artifact.toArtifactSummary
import android.widget.Toast
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.platform.LocalContext
import com.promenar.infinity.data.workspace.WorkspaceDefaultFileDestination
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

internal data class CanvasSessionAccess(
    val sessionId: String,
    val store: CanvasStore,
    val canvases: List<CanvasMetadata>,
    val loaded: Boolean,
    val open: (String) -> Unit,
    /** 打开产物列表：窄窗口为整页，宽窗口为检视器页签，由宿主决定。 */
    val openList: () -> Unit,
    val listFailed: Boolean = false,
    /** 打开指定版本；不早于当前版本时按跟随最新打开。 */
    val openVersion: (String, Int) -> Unit = { id, _ -> open(id) },
    val copySource: (String, Int) -> Unit = { _, _ -> },
    val revealInWorkspace: (String) -> Unit = {},
    /** 当前主工作区登记 ID；为空表示主工作区就是会话私有空间。 */
    val primaryWorkspaceId: String? = null,
    /** 画布与文件快照合并后的产物，按更新时间倒序。 */
    val artifacts: List<ArtifactSummary> = canvases.map { it.toArtifactSummary() },
    /** 工作区里未收录的新文件。 */
    val candidates: List<ArtifactCandidate> = emptyList(),
    /** 打开任一产物：画布进全屏或原型画板，文件进文件查看。 */
    val openArtifact: (ArtifactSummary) -> Unit = { open(it.id) },
    /** 打开文件产物的指定版本，版本为空时打开最新版。 */
    val openFile: (String, Int?) -> Unit = { _, _ -> },
    val claimCandidate: (ArtifactCandidate) -> Unit = {},
    val ignoreCandidate: (ArtifactCandidate) -> Unit = {},
)

/**
 * 资源管理器只浏览当前主工作区：画布镜像的绑定与当前主工作区一致时才返回可定位路径。
 * 无绑定的旧画布按主工作区处理；落在会话私有空间而主工作区是登记项目时不提供定位。
 */
internal fun canvasRevealPath(metadata: CanvasMetadata, primaryWorkspaceId: String?): String? {
    if (metadata.workspaceSync == CanvasWorkspaceSync.MISSING) return null
    val path = metadata.workspacePath ?: return null
    val relative = listOf("/home/session/", "/workspace/", "/session/", "/").firstOrNull(path::startsWith)
        ?.let(path::removePrefix) ?: path
    val binding = metadata.workspaceDestination ?: return "/$relative"
    val inPrimary = when (binding.destination) {
        WorkspaceDefaultFileDestination.PRIMARY_WORKSPACE -> primaryWorkspaceId != null && binding.workspaceId == primaryWorkspaceId
        WorkspaceDefaultFileDestination.SESSION_PRIVATE -> primaryWorkspaceId == null
    }
    return "/$relative".takeIf { inPrimary }
}

internal val LocalCanvasSession = staticCompositionLocalOf<CanvasSessionAccess?> { null }

/** 过滤掉位于排除目录前缀下、已由其它面板托管的工作区文件改动。 */
internal fun filterWorkspaceChangesByExcludedPrefixes(
    changes: List<WorkspaceChangedFile>,
    excludedPathPrefixes: Set<String>,
): List<WorkspaceChangedFile> =
    if (excludedPathPrefixes.isEmpty()) changes
    else changes.filterNot { change -> isPathExcluded(change.path, excludedPathPrefixes) }

/** 会话切换会销毁所有页面；仅跟随最新时订阅当前版本，历史查看保持固定。 */
@Composable
fun CanvasSessionHost(
    sessionId: String,
    store: CanvasStore,
    onInsertText: (String) -> Unit,
    onOpenList: () -> Unit,
    onRevealInWorkspace: (String) -> Unit = {},
    primaryWorkspaceId: String? = null,
    /** 为空时产物只含画布。 */
    artifactRepository: SessionArtifactRepository? = null,
    /** 本会话工具调用新增或修改的工作区文件，用于找出未收录的候选。 */
    workspaceChanges: List<WorkspaceChangedFile> = emptyList(),
    /** 由其它面板托管、不应计入未收录候选的目录前缀（例如当前绑定的制作项目根目录）。 */
    excludedPathPrefixes: Set<String> = emptySet(),
    content: @Composable () -> Unit,
) = key(sessionId, store, artifactRepository) {
    var canvases by remember { mutableStateOf<List<CanvasMetadata>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var listFailed by remember { mutableStateOf(false) }
    var openedId by remember { mutableStateOf<String?>(null) }
    var selectedVersion by remember { mutableStateOf<Int?>(null) }
    val currentInsert by rememberUpdatedState(onInsertText)
    val loadError = stringResource(R.string.canvas_load_error)
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(sessionId, store) {
        try {
            store.observe(sessionId).collect { canvases = it; loaded = true; listFailed = false }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            listFailed = true
            loaded = true
        }
    }
    var fileArtifacts by remember { mutableStateOf<List<ArtifactSummary>?>(null) }
    LaunchedEffect(sessionId, artifactRepository) {
        val repository = artifactRepository ?: return@LaunchedEffect
        try {
            repository.observe(sessionId).collect { fileArtifacts = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 文件产物失败时退回只列画布，画布列表自身的失败另有提示。
            fileArtifacts = null
        }
    }
    val artifacts = fileArtifacts ?: canvases.map { it.toArtifactSummary() }
    var candidates by remember { mutableStateOf<List<ArtifactCandidate>>(emptyList()) }
    var candidateRevision by remember { mutableIntStateOf(0) }
    val sourcePaths = artifacts.mapNotNull { it.sourcePath }
    LaunchedEffect(workspaceChanges, excludedPathPrefixes, sourcePaths, candidateRevision, artifactRepository) {
        val repository = artifactRepository ?: return@LaunchedEffect
        candidates = try {
            val filteredChanges = filterWorkspaceChangesByExcludedPrefixes(workspaceChanges, excludedPathPrefixes)
            if (filteredChanges.isEmpty()) {
                emptyList()
            } else {
                repository.candidates(sessionId, filteredChanges, excludedPathPrefixes)
                    .filterNot { candidate -> isPathExcluded(candidate.path, excludedPathPrefixes) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            emptyList()
        }
    }
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    fun reportFailure(failure: Exception) {
        val detail = (failure as? ArtifactException)?.message ?: failure.message.orEmpty()
        Toast.makeText(context, resources.getString(R.string.artifact_action_failed, detail), Toast.LENGTH_SHORT).show()
    }
    var openedFile by remember { mutableStateOf<Pair<String, Int?>?>(null) }
    val currentReveal by rememberUpdatedState(onRevealInWorkspace)
    val access = CanvasSessionAccess(sessionId, store, canvases, loaded,
        open = { id -> selectedVersion = null; openedId = id },
        openList = onOpenList,
        listFailed = listFailed,
        openVersion = { id, version ->
            val current = canvases.find { it.id == id }?.currentVersion
            selectedVersion = version.takeIf { current != null && it < current }
            openedId = id
        },
        copySource = { id, version ->
            scope.launch {
                val snapshot = try { store.read(sessionId, id, version) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { null }
                snapshot?.let { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(it.metadata.title, prototypeSource(it)))) }
            }
        },
        revealInWorkspace = { path -> currentReveal(path) },
        primaryWorkspaceId = primaryWorkspaceId,
        artifacts = artifacts,
        candidates = candidates,
        openArtifact = { artifact ->
            if (artifact.origin == ArtifactOrigin.CANVAS) { selectedVersion = null; openedId = artifact.id }
            else openedFile = artifact.id to null
        },
        openFile = { id, version -> openedFile = id to version },
        claimCandidate = { candidate ->
            scope.launch {
                try {
                    artifactRepository?.publishFile(sessionId, candidate.path, origin = ArtifactOrigin.CLAIMED, messageId = null)
                    candidateRevision++
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportFailure(failure)
                }
            }
        },
        ignoreCandidate = { candidate ->
            candidates = candidates - candidate
            scope.launch {
                try {
                    artifactRepository?.ignoreCandidate(sessionId, candidate.path)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    reportFailure(failure)
                }
                candidateRevision++
            }
        },
    )
    CompositionLocalProvider(LocalCanvasSession provides access) { content() }
    openedId?.let { id ->
        val metadata = canvases.find { it.id == id }
        val latestRevisionKey = metadata?.currentVersion.takeIf { selectedVersion == null }
        var document by remember(id, selectedVersion, latestRevisionKey) { mutableStateOf<CanvasDocument?>(null) }
        var error by remember(id, selectedVersion, latestRevisionKey) { mutableStateOf<String?>(null) }
        LaunchedEffect(id, selectedVersion, latestRevisionKey, loaded) {
            if (!loaded) return@LaunchedEffect
            try {
                // 跟随最新时先导入工作区里的外部修改；查看历史版本只读私有快照。
                document = if (selectedVersion == null) store.syncFromWorkspace(sessionId, id) else store.read(sessionId, id, selectedVersion)
                if (document == null) error = loadError
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                error = loadError
            }
        }
        val prototype = (metadata ?: document?.metadata)?.kind == CanvasKind.PROTOTYPE
        InfinityFullscreenDialog(onDismissRequest = { openedId = null }) {
            if (prototype) PrototypeBoardContent(
                document = document,
                metadata = metadata,
                selectedVersion = selectedVersion,
                onSelectVersion = { selectedVersion = it },
                onCopy = { document?.let { snapshot -> scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(snapshot.metadata.title, prototypeSource(snapshot)))) } } },
                onClose = { openedId = null },
                onInsertText = { text -> if (selectedVersion == null && openedId == id) currentInsert(text) },
                modifier = Modifier.fillMaxSize(),
                error = error,
            ) else CanvasFullscreenContent(
                document = document,
                metadata = metadata,
                selectedVersion = selectedVersion,
                onSelectVersion = { selectedVersion = it },
                onCopy = { document?.let { snapshot -> scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(snapshot.metadata.title, snapshot.html))) } } },
                onClose = { openedId = null },
                onInsertText = { text -> if (selectedVersion == null && openedId == id) currentInsert(text) },
                modifier = Modifier.fillMaxSize(),
                error = error,
            )
        }
    }
    openedFile?.let { (id, version) ->
        var snapshot by remember(id, version) { mutableStateOf<ArtifactFileSnapshot?>(null) }
        var failed by remember(id, version) { mutableStateOf(false) }
        LaunchedEffect(id, version, artifactRepository) {
            snapshot = try {
                artifactRepository?.readFile(sessionId, id, version)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            failed = snapshot == null
        }
        val image = snapshot?.let { fileArtifactPreview(it.summary.kind, it.summary.fileName ?: it.file.name, it.file.length()) } == FileArtifactPreview.IMAGE
        // 图片查看器自带全屏窗口；其它预览放进全屏对话框。
        if (image) FileArtifactViewer(snapshot = snapshot, error = failed, onClose = { openedFile = null })
        else InfinityFullscreenDialog(onDismissRequest = { openedFile = null }) {
            FileArtifactViewer(snapshot = snapshot, error = failed, onClose = { openedFile = null })
        }
    }
}

/** 复制原型源码：各屏按顺序拼接，屏前注明 ID 与标题。 */
internal fun prototypeSource(document: CanvasDocument): String =
    if (document.screens.isEmpty()) document.html
    else document.screens.joinToString("\n\n") { screen -> "<!-- screen: ${screen.id} · ${screen.title} -->\n${screen.html}" }  // wrap-check: allow 复制的源码注释，非界面文本
