package com.promenar.infinity.data.skills.store

import android.content.SharedPreferences
import com.promenar.infinity.data.skills.SkillCatalog
import com.promenar.infinity.data.skills.SkillStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

const val OFFICIAL_SKILL_CATALOG_URL = "https://infinityware.work/skills/index.json"

internal data class StoreRecord(val name: String, val source: String, val official: Boolean, val version: String, val archiveHash: String, val contentHash: String, val installedAt: Long)
internal data class StoreSnapshot(val sources: List<SkillStoreSource> = emptyList(), val categories: List<SkillStoreCategory> = emptyList(), val items: List<StoreSkillItem> = emptyList(), val refreshing: Boolean = false, val autoCheck: Boolean = true, val lastCheckedAt: Long? = null)
internal sealed interface InstallResult {
    data class Confirm(val reason: ConfirmReason) : InstallResult
    data class Done(val name: String, val enabled: Boolean, val updated: Boolean) : InstallResult
}

class SkillStoreRepository internal constructor(
    private val client: HttpClient,
    private val catalog: SkillCatalog,
    private val preferences: SharedPreferences,
    private val cache: File,
    private val channel: SkillChannel,
    private val appVersion: Int,
    private val locale: () -> Locale = { Locale.getDefault() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val lock = Mutex()
    private val autoLock = Mutex()
    private val packages = SkillStorePackage(client, cache, io)
    private val _snapshot = MutableStateFlow(StoreSnapshot())
    internal val snapshot = _snapshot.asStateFlow()
    private val _updateCount = MutableStateFlow(0)
    val updateCount: StateFlow<Int> = _updateCount.asStateFlow()
    private val catalogs = linkedMapOf<String, ParsedStoreCatalog>()
    private val statuses = mutableMapOf<String, SourceStatus>()
    private val fetched = mutableMapOf<String, Long>()
    private var initialized = false
    private val indexes get() = File(cache, "indexes")

    private fun sourceUrls(): List<String> = listOf(OFFICIAL_SKILL_CATALOG_URL) + runCatching {
        (Json.parseToJsonElement(preferences.getString("skill_store_sources", "[]") ?: "[]") as JsonArray)
            .mapNotNull { runCatching { validUrl(it.jsonPrimitive.content) }.getOrNull() }
            .filterNot { it.equals(OFFICIAL_SKILL_CATALOG_URL, true) }.distinct().take(10)
    }.getOrDefault(emptyList())
    private fun records(): List<StoreRecord> = runCatching {
        (Json.parseToJsonElement(preferences.getString("skill_store_records", "[]") ?: "[]") as JsonArray).map { element ->
            val o = element.jsonObject
            StoreRecord(o.getValue("name").jsonPrimitive.content, o.getValue("source").jsonPrimitive.content,
                o.getValue("official").jsonPrimitive.boolean, o.getValue("version").jsonPrimitive.content,
                o.getValue("archiveHash").jsonPrimitive.content, o.getValue("contentHash").jsonPrimitive.content, o.getValue("installedAt").jsonPrimitive.long)
        }
    }.getOrDefault(emptyList())
    private fun saveRecords(records: List<StoreRecord>) {
        val json = buildJsonArray { records.forEach { r -> add(buildJsonObject {
            put("name", r.name); put("source", r.source); put("official", r.official); put("version", r.version)
            put("archiveHash", r.archiveHash); put("contentHash", r.contentHash); put("installedAt", r.installedAt)
        }) } }
        if (!preferences.edit().putString("skill_store_records", json.toString()).commit()) throw StoreException(StoreFailure.STORAGE)
    }
    private fun indexFile(url: String): File = File(indexes, MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".json")
    private fun validUrl(input: String): String {
        val uri = runCatching { URI(input.trim()).normalize() }.getOrNull() ?: throw StoreException(StoreFailure.INVALID_URL)
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrEmpty() || uri.userInfo != null || uri.fragment != null) throw StoreException(StoreFailure.INVALID_URL)
        return uri.toASCIIString()
    }
    private suspend fun fetch(url: String): ByteArray {
        try {
            return client.prepareGet(url).execute { response ->
                if (!response.call.request.url.protocol.name.equals("https", true)) throw StoreException(StoreFailure.INVALID_URL)
                if (response.status.value !in 200..299) throw StoreException(StoreFailure.HTTP, response.status.value)
                val output = ByteArrayOutputStream(); val buffer = ByteArray(8192); val input = response.bodyAsChannel()
                while (true) {
                    val n = input.readAvailable(buffer, 0, buffer.size); if (n == -1) break
                    if (output.size() + n > 1024 * 1024) throw StoreException(StoreFailure.TOO_LARGE)
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
        } catch (e: CancellationException) { throw e } catch (e: StoreException) { throw e } catch (_: Exception) { throw StoreException(StoreFailure.NETWORK) }
    }
    private fun saveIndex(url: String, bytes: ByteArray) {
        try {
            if (!indexes.isDirectory && !indexes.mkdirs()) throw java.io.IOException()
            val target = indexFile(url); val temp = File(indexes, target.name + ".part")
            try { temp.writeBytes(bytes); if (!temp.renameTo(target)) throw java.io.IOException() } finally { temp.delete() }
        } catch (_: java.io.IOException) { throw StoreException(StoreFailure.STORAGE) }
    }
    private suspend fun initializeLocked() {
        if (initialized) return
        sourceUrls().forEach { url ->
            val file = indexFile(url)
            if (file.isFile && file.length() <= 1024 * 1024) runCatching { SkillStoreParser.parse(file.readBytes(), url, locale()) }.getOrNull()?.let {
                catalogs[url] = it; fetched[url] = file.lastModified(); statuses[url] = SourceStatus.Ready
            }
        }
        initialized = true
        publishLocked()
    }
    suspend fun initialize() = withContext(io) { lock.withLock { initializeLocked() } }
    private suspend fun publishLocked(refreshing: Boolean = false) {
        val skills = catalog.scan(null).user.associateBy { it.name }
        val old = records(); val existing = old.filter { it.name in skills }
        if (old != existing) saveRecords(existing)
        val sourceList = sourceUrls().map { url ->
            val c = catalogs[url]
            SkillStoreSource(url, url == OFFICIAL_SKILL_CATALOG_URL, c?.title, statuses[url] ?: SourceStatus.Idle, c?.skills?.size ?: 0, fetched[url])
        }
        val items = sourceList.flatMap { source -> catalogs[source.url]?.skills.orEmpty().map { entry ->
            val local = skills[entry.name]; val record = existing.find { it.name == entry.name && it.source == source.url }
            val state = when {
                local == null -> StoreInstallState.NotInstalled
                record == null -> StoreInstallState.NameTaken
                SkillStoreParser.compareVersions(entry.version, record.version) > 0 -> StoreInstallState.UpdateAvailable(record.version)
                else -> StoreInstallState.Installed(record.version)
            }
            StoreSkillItem("${source.url}#${entry.name}", entry, source, state,
                if (record != null && local != null) local.status == SkillStatus.ENABLED else null,
                record != null && local?.contentHash != record.contentHash, channel !in entry.channels,
                (entry.minAppVersionCode ?: 0) > appVersion)
        } }
        val categories = sourceList.flatMap { catalogs[it.url]?.categories.orEmpty() }.distinctBy { it.id }
        _updateCount.value = items.count { it.installState is StoreInstallState.UpdateAvailable }
        _snapshot.value = StoreSnapshot(sourceList, categories, items, refreshing, preferences.getBoolean("skill_store_auto_check", true), preferences.getLong("skill_store_last_checked", 0).takeIf { it > 0 })
    }
    suspend fun rescan() = withContext(io) { lock.withLock { initializeLocked(); publishLocked() } }
    suspend fun refresh() = withContext(io) {
        lock.withLock {
            initializeLocked(); val urls = sourceUrls(); urls.forEach { statuses[it] = SourceStatus.Loading }; publishLocked(true)
            try {
                urls.forEach { url ->
                    try {
                        val bytes = fetch(url); val parsed = SkillStoreParser.parse(bytes, url, locale()); saveIndex(url, bytes)
                        catalogs[url] = parsed; fetched[url] = clock(); statuses[url] = SourceStatus.Ready
                    } catch (e: StoreException) { statuses[url] = SourceStatus.Failed(e.failure, e.httpStatus) }
                    publishLocked(true)
                }
                if (!preferences.edit().putLong("skill_store_last_checked", clock()).commit()) throw StoreException(StoreFailure.STORAGE)
            } finally {
                withContext(NonCancellable) {
                    urls.forEach { if (statuses[it] == SourceStatus.Loading) statuses[it] = SourceStatus.Idle }
                    publishLocked()
                }
            }
        }
    }
    suspend fun maybeAutoCheck() = autoLock.withLock {
        val due = withContext(io) { preferences.getBoolean("skill_store_auto_check", true) && clock() - preferences.getLong("skill_store_last_checked", 0) > 24 * 60 * 60 * 1000L }
        if (due) refresh() else initialize()
    }
    suspend fun setAutoCheck(value: Boolean) = withContext(io) { lock.withLock {
        initializeLocked(); if (!preferences.edit().putBoolean("skill_store_auto_check", value).commit()) throw StoreException(StoreFailure.STORAGE); publishLocked()
    } }
    suspend fun addSource(input: String): String = withContext(io) { lock.withLock {
        initializeLocked(); val url = validUrl(input); val sources = sourceUrls()
        if (sources.any { it.equals(url, true) }) throw StoreException(StoreFailure.DUPLICATE_SOURCE)
        if (sources.size - 1 >= 10) throw StoreException(StoreFailure.SOURCE_LIMIT)
        val bytes = fetch(url); val parsed = SkillStoreParser.parse(bytes, url, locale()); saveIndex(url, bytes)
        val value = JsonArray((sources.drop(1) + url).map(::JsonPrimitive)).toString()
        if (!preferences.edit().putString("skill_store_sources", value).commit()) throw StoreException(StoreFailure.STORAGE)
        catalogs[url] = parsed; statuses[url] = SourceStatus.Ready; fetched[url] = clock(); publishLocked(); url
    } }
    suspend fun removeSource(url: String) = withContext(io) { lock.withLock {
        initializeLocked(); if (url == OFFICIAL_SKILL_CATALOG_URL) return@withLock
        val value = JsonArray(sourceUrls().drop(1).filterNot { it == url }.map(::JsonPrimitive)).toString()
        if (!preferences.edit().putString("skill_store_sources", value).commit()) throw StoreException(StoreFailure.STORAGE)
        catalogs.remove(url); statuses.remove(url); fetched.remove(url); publishLocked()
    } }
    internal suspend fun markdown(key: String): String {
        val item = withContext(io) { lock.withLock { initializeLocked(); _snapshot.value.items.find { it.key == key } ?: throw StoreException(StoreFailure.INVALID_CATALOG) } }
        val file = packages.download(item.entry); val content = packages.extract(item.entry, file)
        return content.getValue("SKILL.md").toString(Charsets.UTF_8)
    }
    internal suspend fun install(key: String, confirmed: Boolean, progress: (StoreOperation) -> Unit): InstallResult = withContext(io) {
        val (item, originalHash) = lock.withLock {
            initializeLocked(); publishLocked()
            val selected = _snapshot.value.items.find { it.key == key } ?: throw StoreException(StoreFailure.INVALID_CATALOG)
            selected to catalog.scan(null).user.find { it.name == selected.entry.name }?.contentHash
        }
        val reason = when { item.installState == StoreInstallState.NameTaken -> ConfirmReason.NAME_TAKEN; item.localModified -> ConfirmReason.LOCAL_MODIFIED; else -> null }
        if (reason != null && !confirmed) return@withContext InstallResult.Confirm(reason)
        val file = packages.download(item.entry) { current, total -> progress(StoreOperation.Downloading(current, total)) }
        val files = packages.extract(item.entry, file); progress(StoreOperation.Installing)
        lock.withLock {
            publishLocked(); val current = _snapshot.value.items.find { it.key == key } ?: throw StoreException(StoreFailure.INVALID_CATALOG)
            if (current.entry != item.entry) throw StoreException(StoreFailure.INVALID_CATALOG)
            val currentHash = catalog.scan(null).user.find { it.name == current.entry.name }?.contentHash
            val currentReason = when { current.installState == StoreInstallState.NameTaken -> ConfirmReason.NAME_TAKEN; current.localModified -> ConfirmReason.LOCAL_MODIFIED; else -> null }
            if (originalHash != currentHash) return@withLock InstallResult.Confirm(currentReason ?: ConfirmReason.LOCAL_MODIFIED)
            if (currentReason != null && (!confirmed || currentReason != reason)) return@withLock InstallResult.Confirm(currentReason)
            val updating = current.installState is StoreInstallState.Installed || current.installState is StoreInstallState.UpdateAvailable
            val enabled = if (updating) current.enabled == true else current.source.official
            val installed = try { catalog.installApproved(files, overwrite = current.installState != StoreInstallState.NotInstalled, enableAfterInstall = enabled) } catch (e: CancellationException) { throw e } catch (_: Exception) { throw StoreException(StoreFailure.STORAGE) }
            val hash = installed.contentHash ?: throw StoreException(StoreFailure.STORAGE)
            saveRecords(records().filterNot { it.name == installed.name } + StoreRecord(installed.name, current.source.url, current.source.official, current.entry.version, current.entry.sha256, hash, clock()))
            publishLocked(); InstallResult.Done(installed.name, enabled, updating)
        }
    }
    internal suspend fun uninstall(key: String): String = withContext(io) { lock.withLock {
        initializeLocked(); val item = _snapshot.value.items.find { it.key == key } ?: throw StoreException(StoreFailure.INVALID_CATALOG)
        val record = records().find { it.name == item.entry.name && it.source == item.source.url } ?: throw StoreException(StoreFailure.INVALID_PACKAGE)
        val skill = catalog.scan(null).user.find { it.name == record.name } ?: throw StoreException(StoreFailure.INVALID_PACKAGE)
        try { catalog.delete(skill); saveRecords(records().filterNot { it.name == record.name }); publishLocked() } catch (e: CancellationException) { throw e } catch (_: Exception) { throw StoreException(StoreFailure.STORAGE) }
        record.name
    } }
}
