package com.promenar.infinity.data.files

import android.content.Context
import android.net.Uri
import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.R
import com.promenar.infinity.data.workspace.LinuxHomeAvailability
import com.promenar.infinity.data.workspace.LinuxHomeWorkspaceSource
import com.promenar.infinity.domain.files.FileLocationsSource
import com.promenar.infinity.domain.files.FileManagerServices
import com.promenar.infinity.domain.files.FileOperations
import com.promenar.infinity.domain.files.PinnedFolderStore
import com.promenar.infinity.domain.linux.LinuxRuntimeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** 三个服务可独立注入，生产环境通过 create 组合现有应用服务。 */
class DefaultFileManagerServices(
    override val locations: FileLocationsSource,
    override val operations: FileOperations,
    override val pins: PinnedFolderStore,
) : FileManagerServices {
    companion object {
        fun create(app: InfinityApplication, isSessionBusy: suspend (String) -> Boolean): DefaultFileManagerServices {
            val sessions = app.database.sessionDao().observeAll()
            val home = LinuxHomeWorkspaceSource(app.linux.runtime)
            val ready = app.linux.runtime.state.map { state ->
                withContext(Dispatchers.IO) { state is LinuxRuntimeState.Ready && home.availability() == LinuxHomeAvailability.READY }
            }.distinctUntilChanged()
            val workspaceGit by lazy { WorkspaceGitInspector(app) }
            val locations = DefaultFileLocationsSource(
                sessions = sessions,
                workspaces = app.database.workspaceDao().observeAll(),
                linuxReady = ready,
                mounts = app.externalFolderManager::observeMounts,
                resolveSession = app.sessionWorkspaceResolver::resolve,
                resolveWorkspace = app.sessionWorkspaceResolver::resolveWorkspace,
                mountAvailable = { mount ->
                    if (mount.sourceType != "saf_tree") true else {
                        val grant = app.contentResolver.persistedUriPermissions.firstOrNull { it.uri == Uri.parse(mount.sourceId) }
                        grant?.isReadPermission == true && grant.isWritePermission
                    }
                },
                gitStatus = app.gitChangesService::status,
                workspaceGitStatus = { workspaceGit.status(it) },
                titles = LocationTitles(app.getString(R.string.file_manager_location_library),
                    app.getString(R.string.file_manager_location_sessions), app.getString(R.string.file_manager_location_linux)),
            )
            val backends = FileManagerBackends(app)
            val guard = FileMutationGuard(app.sessionExecutionGate, isSessionBusy)
            val operations = DefaultFileOperations(app, backends::resolve, { app.database.sessionDao().getAll() },
                app.workspaceRepository, app.database.vectorizationTaskDao(), app.pendingDocumentIndexCoordinator, guard,
                pendingTargets = app.pendingDocumentIndexCoordinator::snapshot)
            val validLocations = combine(locations.observeLibraryLocations(), sessions) { all, rows ->
                all.map { it.id }.toSet() + rows.filterNot(::isSystemSession).map { "session:${it.id}" }
            }
            val pins = DefaultPinnedFolderStore(
                PreferencesPinPersistence(app.getSharedPreferences("infinity_settings", Context.MODE_PRIVATE)), validLocations,
            )
            return DefaultFileManagerServices(locations, operations, pins)
        }
    }
}
