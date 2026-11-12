package com.promenar.infinity.data.mediagen.comfy

import android.content.SharedPreferences
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.domain.media.WorkspaceMediaResolver
import com.promenar.infinity.domain.media.WorkspaceMediaStore
import okhttp3.OkHttpClient

/** 由应用持有一个实例，供注册表、队列与界面复用。 */
class ComfyModule(preferences: SharedPreferences, secrets: SecretStore, client: OkHttpClient, resolver: WorkspaceMediaResolver, store: WorkspaceMediaStore) {
    private val credentials = ComfyCredentials(secrets)
    private val http = OkHttpComfyHttp(client, credentials)
    val repository = ComfyRepository(PreferenceComfyConfigurationStorage(preferences), credentials, http)
    val backend = ComfyBackend(repository, http, resolver, store, WebSocketComfyProgress(client, credentials))
    val channels: ComfyTemplateChannelSource get() = repository
}
