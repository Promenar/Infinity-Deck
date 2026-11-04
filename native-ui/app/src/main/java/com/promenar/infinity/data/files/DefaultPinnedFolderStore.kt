package com.promenar.infinity.data.files

import android.content.SharedPreferences
import com.promenar.infinity.domain.files.FileRef
import com.promenar.infinity.domain.files.PinnedFolderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal interface PinPersistence {
    fun observe(): Flow<List<FileRef>>
    fun read(): List<FileRef>
    fun write(pins: List<FileRef>)
}

internal class PreferencesPinPersistence(private val preferences: SharedPreferences) : PinPersistence {
    override fun observe(): Flow<List<FileRef>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY) trySend(read())
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        trySend(read())
        awaitClose { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    override fun read(): List<FileRef> = try {
        val array = JSONArray(preferences.getString(KEY, "[]"))
        buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val id = item.optString("location")
                val path = item.optString("path")
                if (parseLocationId(id) != null) runCatching { FileRef(id, normalizeFilePath(path)) }.getOrNull()?.let(::add)
            }
        }.distinct().take(PinnedFolderStore.MAX_PINS)
    } catch (_: Exception) { emptyList() }

    override fun write(pins: List<FileRef>) {
        val array = JSONArray()
        pins.forEach { ref -> array.put(JSONObject().put("location", ref.locationId).put("path", ref.path)) }
        check(preferences.edit().putString(KEY, array.toString()).commit()) { "无法保存固定文件夹" }
    }

    private companion object { const val KEY = "file_manager_pins" }
}

internal class DefaultPinnedFolderStore(
    private val persistence: PinPersistence,
    private val validLocations: Flow<Set<String>>,
) : PinnedFolderStore {
    private val mutex = Mutex()

    override fun observePins(): Flow<List<FileRef>> = combine(persistence.observe(), validLocations) { pins, valid ->
        pins.filter { it.locationId in valid }
    }

    override suspend fun pin(ref: FileRef): Boolean = withContext(Dispatchers.IO) {
        val normalized = ref.copy(path = normalizeFilePath(ref.path))
        require(parseLocationId(normalized.locationId) != null)
        mutex.withLock {
            val valid = validLocations.first()
            val pins = persistence.read().filter { it.locationId in valid }
            if (normalized.locationId !in valid || normalized in pins || pins.size >= PinnedFolderStore.MAX_PINS) false
            else { persistence.write(pins + normalized); true }
        }
    }

    override suspend fun unpin(ref: FileRef) = withContext(Dispatchers.IO) {
        val normalized = ref.copy(path = normalizeFilePath(ref.path))
        mutex.withLock { persistence.write(persistence.read() - normalized) }
    }
}
