package com.promenar.infinity.data.cut

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.background.cut.CutExportForegroundService
import com.promenar.infinity.data.production.ProductionManifestCodec
import com.promenar.infinity.data.production.ProductionUserAction
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.data.storage.namespaceBindings
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.domain.storage.ExternalMountOperationBindings
import com.promenar.infinity.ui.chat.manager.skills.PrivateWorkspaceTransferBackend
import kotlinx.coroutines.currentCoroutineContext

/** 面板和智能体复用同一控制器、同一个导出槽位。 */
@androidx.annotation.OptIn(UnstableApi::class)
class ProductionCutRuntime(private val app: InfinityApplication) {
    private val store = object : CutProjectStore {
        override suspend fun load(sessionId: String) = app.productionRepository.get(sessionId)
        override suspend fun apply(sessionId: String, productionId: String, revision: Long, action: ProductionUserAction) = app.productionRepository.applyForProduction(sessionId, productionId, revision, action)
    }
    private val output = WorkspaceCutOutputStore(app.cacheDir, resolveTarget = { sessionId, path ->
        ProductionManifestCodec.validatePath(path)
        val binding = requireNotNull(currentCoroutineContext()[CutWorkspaceOperation]?.binding)
        binding.verify()
        val bindings = app.externalFolderManager.namespaceBindings(sessionId)
        val mounted = ExternalMountPaths.aliasOf("/$path", ExternalMountPaths.WORKSPACE_ALIAS in bindings) != null
        if (mounted) {
            CutOutputTarget(GuardedCutBackend(app.externalFolderManager.backend(sessionId, bindings), binding.verify))
        } else {
            val root = app.workspaceRepository.ensureSessionRoot(sessionId)
            val backend = PrivateWorkspaceTransferBackend(app.workspaceRepository, root.uuid)
            CutOutputTarget(GuardedCutBackend(backend, binding.verify), afterWrite = { binding.verify(); backend.confirmCreated(); Unit })
        }
    })
    val controller: DefaultCutExportController = DefaultCutExportController(store, WorkspaceCutMediaProbe(app, app.workspaceMediaResolver),
        Media3CutExporter(app, app.workspaceMediaResolver), output,
        startService = { lease, version -> ContextCompat.startForegroundService(app, Intent(app, CutExportForegroundService::class.java)
            .putExtra(CutExportForegroundService.EXTRA_REQUEST, lease).putExtra(CutExportForegroundService.EXTRA_VERSION, version)) },
        messages = CutExportMessages(
            app.getString(R.string.production_cut_busy), app.getString(R.string.production_cut_missing_project),
            app.getString(R.string.production_cut_missing_version), app.getString(R.string.production_cut_changed),
            app.getString(R.string.production_cut_failed), app.getString(R.string.production_cut_interrupted),
            app.getString(R.string.production_cut_recovery_required),
            mediaUnavailable = app.getString(R.string.production_cut_media_unavailable),
            unsupportedSize = app.getString(R.string.production_cut_unsupported_size),
            sourceChanged = app.getString(R.string.production_cut_source_changed),
            invalidOutput = app.getString(R.string.production_cut_invalid_output),
            encodingFailed = app.getString(R.string.production_cut_encoding_failed),
            storageFull = app.getString(R.string.production_cut_storage_full),
        ), captureWorkspace = ::captureWorkspace, onRecoveryRequired = { reference ->
            com.promenar.infinity.utils.InfinityLogger.logError("成片产物需要恢复", IllegalStateException("恢复目录：${reference.orEmpty()}"))
        })

    private suspend fun captureWorkspace(sessionId: String): CutWorkspaceBinding {
        val workspace = workspaceIdentity(sessionId)
        val mode = app.sessionRunModes.modeFor(sessionId)
        val bindings = app.externalFolderManager.namespaceBindings(sessionId)
        return CutWorkspaceBinding(ExternalMountOperationBindings(bindings)) {
            require(workspaceIdentity(sessionId) == workspace && app.sessionRunModes.modeFor(sessionId) == mode &&
                app.externalFolderManager.namespaceBindings(sessionId) == bindings) { app.getString(R.string.production_cut_changed) }
        }
    }

    private suspend fun workspaceIdentity(sessionId: String): List<String?> = when (val workspace = app.sessionWorkspaceResolver.resolve(sessionId)) {
        SessionWorkspace.Private -> app.workspaceRepository.ensureSessionRoot(sessionId).let { listOf("private", it.uuid, it.hash, it.physicalRootPath) }
        is SessionWorkspace.Directory -> listOf("directory", workspace.workspace.id, workspace.workspace.kind, workspace.workspace.location, workspace.root.path)
        is SessionWorkspace.Unavailable -> throw IllegalStateException(app.getString(R.string.production_cut_changed))
    }

}
