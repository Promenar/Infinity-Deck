package com.promenar.infinity.data.skills.store

import java.net.URI

enum class SkillChannel { SIDELOAD, PLAY }
enum class StoreFailure {
    NETWORK, HTTP, TOO_LARGE, HASH_MISMATCH, FILE_LIST_MISMATCH, NAME_MISMATCH,
    INVALID_PACKAGE, INVALID_CATALOG, UNSUPPORTED_SCHEMA, INVALID_URL, DUPLICATE_SOURCE, SOURCE_LIMIT, STORAGE,
}
data class SkillStoreCategory(val id: String, val title: String)
data class SkillStoreFile(val path: String, val size: Long)
data class SkillStoreEntry(
    val name: String, val version: String, val title: String, val summary: String,
    val categoryId: String?, val author: String, val license: String,
    val archiveUrl: String, val size: Long, val sha256: String,
    val files: List<SkillStoreFile>, val hasScripts: Boolean,
    val channels: Set<SkillChannel>, val minAppVersionCode: Int?,
)
sealed interface SourceStatus {
    data object Idle : SourceStatus
    data object Loading : SourceStatus
    data object Ready : SourceStatus
    data class Failed(val failure: StoreFailure, val httpStatus: Int? = null) : SourceStatus
}
data class SkillStoreSource(
    val url: String, val official: Boolean, val title: String?,
    val status: SourceStatus, val skillCount: Int, val fetchedAt: Long?,
) { val host: String get() = runCatching { URI(url).host.orEmpty() }.getOrDefault("") }
sealed interface StoreInstallState {
    data object NotInstalled : StoreInstallState
    data class Installed(val version: String) : StoreInstallState
    data class UpdateAvailable(val installedVersion: String) : StoreInstallState
    data object NameTaken : StoreInstallState
}
data class StoreSkillItem(
    val key: String, val entry: SkillStoreEntry, val source: SkillStoreSource,
    val installState: StoreInstallState, val enabled: Boolean?, val localModified: Boolean,
    val limitedOnChannel: Boolean, val requiresAppUpdate: Boolean,
)
sealed interface StoreOperation {
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : StoreOperation
    data object Installing : StoreOperation
}
enum class StoreFilter { ALL, INSTALLED, UPDATES }
enum class ConfirmReason { NAME_TAKEN, LOCAL_MODIFIED }
