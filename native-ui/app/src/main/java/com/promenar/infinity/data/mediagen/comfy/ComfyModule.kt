package com.promenar.infinity.data.mediagen.comfy

import android.content.SharedPreferences
import com.promenar.infinity.data.exec.PackagedPython
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import com.promenar.infinity.domain.media.WorkspaceMediaStore
import okhttp3.OkHttpClient
import java.io.File

/** 由应用持有一个实例，供注册表、队列、ComfyUI 执行引擎与界面复用。 */
class ComfyModule(
    preferences: SharedPreferences,
    secrets: SecretStore,
    client: OkHttpClient,
    resolver: WorkspaceMediaResolver,
    store: WorkspaceMediaStore,
    appFilesDir: File = File("/tmp/infinity_comfy_files"),
    appCacheDir: File = File("/tmp/infinity_comfy_cache"),
    packagedPython: PackagedPython? = null,
) {
    private val credentials = ComfyCredentials(secrets)
    private val http = OkHttpComfyHttp(client, credentials, File(appCacheDir, "comfy_upload_spill"))
    val submitterFiles: ComfySubmitterFileStore = FileComfySubmitterFileStore(File(appFilesDir, "comfy_submitters"))
    val chainStore: StatefulChainStore = FileStatefulChainStore(File(appFilesDir, "comfy_chains"))
    val repository = ComfyRepository(
        storage = PreferenceComfyConfigurationStorage(preferences),
        credentials = credentials,
        http = http,
        submitterFiles = submitterFiles,
    )
    val submitterRunner = ComfySubmitterRunner(
        submitterFiles = submitterFiles,
        scratchRoot = File(appCacheDir, "comfy_submitter_runs"),
        launchProvider = { packagedPython?.let { SubmitterPythonLaunch.packaged(it) } },
    )
    val executionEngine = ComfyWorkflowExecutionEngine(
        repository = repository,
        chainStore = chainStore,
        credentials = credentials,
        http = http,
        proxyClient = client,
        runner = submitterRunner,
        resolver = resolver,
        store = store,
        scratchRoot = File(appCacheDir, "comfy_workflow_exec"),
    )
    val backend = ComfyBackend(repository, http, resolver, store, WebSocketComfyProgress(client, credentials))
    val channels: ComfyTemplateChannelSource get() = repository
}
