package com.promenar.infinity.data.files

import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.storage.ExternalRecycleStore
import com.promenar.infinity.data.storage.ExternalTreeTransfer
import com.promenar.infinity.data.storage.externalSha256
import com.promenar.infinity.domain.files.*
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.ExternalFolderEntry
import com.promenar.infinity.domain.storage.ExternalWorkspaceAccess
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@OptIn(ExperimentalCoroutinesApi::class)
internal class FileManagerRecycle(
    directory: File,
    private val repository: IWorkspaceRepository,
    private val sessions: Flow<List<SessionEntity>>,
    private val libraryRoot: suspend () -> String,
    private val resolve: suspend (String) -> FileBackendHandle,
    private val external: ExternalWorkspaceAccess,
    private val guard: FileMutationGuard,
) {
    private val ownedDirectory = File(directory, "file-manager-recycle")
    private val legacyDirectory = File(directory, "external-recycle")
    private val owned = ExternalRecycleStore(ownedDirectory, retentionMillis = Long.MAX_VALUE)
    private val legacy = ExternalRecycleStore(legacyDirectory)
    private val mutex = Mutex()
    private val version = MutableStateFlow(0L)

    fun observe(): Flow<List<RecycledItem>> = sessions.flatMapLatest { rows ->
        flow {
            val library = libraryRoot()
            val roots = mutableListOf("kb" to library)
            rows.filter { !isSystemSession(it) && !it.workspaceRootUuid.isNullOrBlank() }.forEach { row ->
                val root = try { repository.ensureSessionRoot(row.id).uuid }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (root != null && roots.none { it.second == root }) roots += "session:${row.id}" to root
            }
            emit(roots)
        }.flatMapLatest { roots ->
            val roomFlows = roots.map { (location, root) ->
                repository.observeRecycleBin(root).let { observed ->
                    observed.map { entries -> roomItems(location, entries) }
                }
            }
            val room = combine(roomFlows) { lists -> lists.flatMap { it } }
            val refresh = flow { while (true) { emit(Unit); delay(15_000) } }
            combine(room, version, refresh) { roomItems, _, _ ->
                withContext(Dispatchers.IO) {
                    val ownItems = mutex.withLock { owned.items().mapNotNull { item ->
                        val key = parseLocationId(item.mountId) ?: return@mapNotNull null
                        if (key == LocationKey.Sessions) return@mapNotNull null
                        val directory = recycleRootIsDirectory(ownedDirectory, item.id) ?: return@mapNotNull null
                        RecycledItem(encodeRecycleId(RecycleKey(item.mountId, "owned:${item.id}")), item.mountId,
                            item.originalPath, item.originalPath.substringAfterLast('/'), directory,
                            item.size, item.deletedAt, null)
                    } }
                    val old = mutableListOf<RecycledItem>()
                    for (row in rows) {
                        val snapshot = try {
                            val mounts = external.visible(row.id) + listOfNotNull(external.workspaceMount(row.id))
                            mounts to external.recycleItems(row.id)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { continue }
                        val (visible, recycled) = snapshot
                        for (item in recycled) {
                            val mount = visible.singleOrNull { it.id == item.mountId } ?: continue
                            val id = if (mount.alias.isEmpty()) "workspace:${mount.sourceId}" else "external:${mount.id}"
                            val relative = if (mount.alias.isEmpty()) item.originalPath else
                                item.originalPath.removePrefix("/mnt/${mount.alias}").ifEmpty { "/" }
                            val directory = recycleRootIsDirectory(legacyDirectory, item.id) ?: continue
                            old += RecycledItem(encodeRecycleId(RecycleKey(id, "legacy:${row.id}:${item.id}")), id,
                                relative, relative.substringAfterLast('/'), directory,
                                item.size, item.deletedAt, item.deletedAt + SEVEN_DAYS)
                        }
                    }
                    (roomItems + ownItems + old.distinctBy { decodeRecycleId(it.id)?.itemId?.substringAfterLast(':') })
                        .sortedByDescending { it.deletedAt }
                }
            }
        }
    }

    suspend fun delete(handle: FileBackendHandle, entry: ExternalFolderEntry) = mutex.withLock {
        handle.backend.validateTree(entry.path)
        val tree = ExternalTreeTransfer.capture(handle.backend, entry.path)
        val item = owned.save(handle.locationId, entry.path, tree)
        if (ExternalTreeTransfer.capture(handle.backend, entry.path).digest() != tree.digest()) {
            owned.remove(item.id)
            throw FileOpException(FileOpCode.CONFLICT)
        }
        val hash = tree.nodes.singleOrNull()?.takeUnless { it.isDirectory }?.let { externalSha256(it.bytes) }
        handle.backend.delete(entry.path, hash)
        version.update { it + 1 }
    }

    suspend fun restore(ids: List<String>): FileOpOutcome {
        val success = mutableListOf<FileRef>()
        val failed = mutableListOf<FileOpFailure>()
        for (id in ids.distinct()) {
            val key = decodeRecycleId(id)
            val fallback = FileRef(key?.locationId ?: "", "/")
            try {
                val item = observe().first().firstOrNull { it.id == id } ?: throw FileOpException(FileOpCode.NOT_FOUND)
                val handle = resolve(item.locationId)
                guard.run(listOf(handle)) {
                    val token = requireNotNull(key).itemId
                    when {
                        token.startsWith("room:") -> repository.restoreFromRecycleBin(requireNotNull(handle.roomRoot), token.removePrefix("room:"))
                        token.startsWith("owned:") -> mutex.withLock {
                            val uuid = token.removePrefix("owned:")
                            val stored = owned.items().singleOrNull { it.id == uuid && it.mountId == item.locationId }
                                ?: throw FileOpException(FileOpCode.NOT_FOUND)
                            ExternalTreeTransfer.copy(owned.load(uuid), handle.backend, stored.originalPath)
                            handle.confirmCreated()
                            owned.remove(uuid)
                            if (owned.items().any { it.id == uuid }) throw FileOpException(FileOpCode.IO)
                        }
                        token.startsWith("legacy:") -> {
                            val parts = token.removePrefix("legacy:").split(':')
                            if (parts.size < 2) throw FileOpException(FileOpCode.NOT_FOUND)
                            external.restore(parts.dropLast(1).joinToString(":"), parts.last())
                        }
                        else -> throw FileOpException(FileOpCode.NOT_FOUND)
                    }
                    version.update { it + 1 }
                    success += FileRef(item.locationId, item.originalPath)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) { failed += FileOpFailure(fallback, mapped(error).code) }
        }
        return FileOpOutcome(success, failed)
    }

    suspend fun purge(ids: List<String>) {
        val snapshot = observe().first().associateBy { it.id }
        val requested = ids.distinct()
        if (requested.any { it !in snapshot }) throw FileOpException(FileOpCode.NOT_FOUND)
        if (requested.any { decodeRecycleId(it)?.itemId?.startsWith("legacy:") == true }) throw FileOpException(FileOpCode.UNSUPPORTED)
        for (id in requested) {
            val item = snapshot[id] ?: throw FileOpException(FileOpCode.NOT_FOUND)
            val key = decodeRecycleId(id) ?: throw FileOpException(FileOpCode.NOT_FOUND)
            val handle = resolve(item.locationId)
            guard.run(listOf(handle)) {
                when {
                    key.itemId.startsWith("room:") -> repository.permanentDelete(requireNotNull(handle.roomRoot), key.itemId.removePrefix("room:"))
                    key.itemId.startsWith("owned:") -> mutex.withLock {
                        val uuid = key.itemId.removePrefix("owned:")
                        if (owned.items().none { it.id == uuid && it.mountId == item.locationId }) throw FileOpException(FileOpCode.NOT_FOUND)
                        owned.remove(uuid)
                        if (owned.items().any { it.id == uuid }) throw FileOpException(FileOpCode.IO)
                    }
                    // 旧挂载管理器没有暴露与恢复共用锁的彻底删除入口。
                    key.itemId.startsWith("legacy:") -> throw FileOpException(FileOpCode.UNSUPPORTED)
                    else -> throw FileOpException(FileOpCode.NOT_FOUND)
                }
                version.update { it + 1 }
            }
        }
    }

    private fun roomItems(location: String, entries: List<FileEntry>): List<RecycledItem> = entries.filter { entry ->
        entries.none { parent -> parent.uuid != entry.uuid && entry.materializedPath.startsWith(parent.materializedPath.trimEnd('/') + "/") }
    }.mapNotNull { entry ->
        val original = entry.originalMaterializedPath ?: return@mapNotNull null
        val deleted = entry.recycledAt ?: return@mapNotNull null
        RecycledItem(encodeRecycleId(RecycleKey(location, "room:${entry.uuid}")), location, original, entry.name,
            entry.isDirectory, entry.sizeBytes, deleted, deleted + THIRTY_DAYS)
    }

    private fun recycleRootIsDirectory(directory: File, id: String): Boolean? {
        if (!Regex("[0-9a-f-]{36}").matches(id)) return null
        val manifest = File(File(directory, id), "manifest.json")
        if (!manifest.isFile || manifest.length() > 1024 * 1024) return null
        return try {
            Json.parseToJsonElement(manifest.readText()).jsonObject["nodes"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("directory")?.jsonPrimitive?.booleanOrNull
        } catch (_: Exception) { null }
    }

    private companion object {
        const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
        const val THIRTY_DAYS = 30L * 24 * 60 * 60 * 1000
    }
}
