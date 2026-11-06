package com.promenar.infinity.share.core

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.JsonReader
import com.promenar.infinity.ShareDelivery
import com.promenar.infinity.ShareEnqueueResult
import com.promenar.infinity.ShareLease
import com.promenar.infinity.ShareRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class DurableShareCheckpoint {
    AfterRequestRenameBeforeIndexWrite,
}

/**
 * 进程死亡安全的分享收件箱。原始 ContentResolver grant 只在 stage() 内使用一次；
 * 后续 UI、导入和重试只读取 noBackupFilesDir 下的应用私有副本。
 * 已消费指纹只拦截系统重放的同一 Intent（[ShareDelivery.Redelivered]）；用户新的分享即使 URI 与历史请求相同也重新入队。
 * 已消费 requestId 用于区分“ack 已落盘但目录未删除”的残留与“目录已提交但 index 未写入”的新请求。
 * 清单 SHA-256 只用于检测应用私有目录内的损坏与不完整写入，不是跨信任边界的 HMAC。
 */
class DurableShareInbox(
    noBackupFilesDir: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxItemBytes: Long = SharedFileImporter.MAX_ITEM_BYTES,
    private val maxBatchBytes: Long = SharedFileImporter.MAX_BATCH_BYTES,
    private val faultInjector: (DurableShareCheckpoint) -> Unit = {},
) {
    private val root = File(noBackupFilesDir, "share-inbox-v1")
    private val requestsRoot = File(root, "requests")
    private val indexFile = AtomicFile(File(root, "index.json"))
    private val mutex = Mutex()
    private var index = loadIndex()
    private val _pendingCount = MutableStateFlow(index.requests.size)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    suspend fun stage(intent: Intent, resolver: ContentResolver, delivery: ShareDelivery): ShareEnqueueResult =
        withContext(Dispatchers.IO + NonCancellable) {
            mutex.withLock {
                val raw = RawShareIntent.parse(intent) ?: return@withLock ShareEnqueueResult.RejectedInvalid
                recoverStaleLeasesLocked()
                val replayOfConsumed = delivery == ShareDelivery.Redelivered && raw.fingerprint in index.consumed
                if (replayOfConsumed || index.requests.any { it.fingerprint == raw.fingerprint }) {
                    return@withLock ShareEnqueueResult.Duplicate
                }
                if (index.requests.size >= MAX_PENDING) return@withLock ShareEnqueueResult.RejectedCapacity

                val requestId = UUID.randomUUID().toString()
                val temporary = File(requestsRoot, ".stage-$requestId")
                val committed = File(requestsRoot, requestId)
                if (!temporary.mkdirs()) return@withLock ShareEnqueueResult.RejectedInvalid
                var batchBytes = 0L
                val items = mutableListOf<StagedItem>()
                try {
                    raw.uris.forEachIndexed { position, uri ->
                        val metadata = try {
                            queryMetadata(resolver, uri, raw.mimeType)
                        } catch (_: SecurityException) {
                            items += rejectedMetadataItem(position, uri, raw.mimeType, ShareRejectReason.PermissionDenied)
                            return@forEachIndexed
                        } catch (_: IOException) {
                            items += rejectedMetadataItem(position, uri, raw.mimeType, ShareRejectReason.ReadFailed)
                            return@forEachIndexed
                        } catch (_: IllegalArgumentException) {
                            items += rejectedMetadataItem(position, uri, raw.mimeType, ShareRejectReason.ReadFailed)
                            return@forEachIndexed
                        } catch (_: android.database.SQLException) {
                            items += rejectedMetadataItem(position, uri, raw.mimeType, ShareRejectReason.ReadFailed)
                            return@forEachIndexed
                        }
                        val displayName = metadata.displayName?.takeIf { it.isNotBlank() }
                            ?: uri.lastPathSegment?.takeIf { it.isNotBlank() }
                            ?: "shared-$position"
                        val resolvedMime = metadata.mimeType.substringBefore(';').trim().lowercase()
                        val mimeValid = resolvedMime.length <= 512 && resolvedMime.none(Char::isISOControl)
                        val mime = if (mimeValid) resolvedMime else "application/octet-stream"
                        val safeName = SharedFileImporter.normalizeName(displayName)
                        var rejection = when {
                            safeName == null -> ShareRejectReason.InvalidName
                            !mimeValid -> ShareRejectReason.MimeMismatch
                            metadata.sizeBytes != null && metadata.sizeBytes <= 0L -> ShareRejectReason.EmptyFile
                            metadata.sizeBytes != null && metadata.sizeBytes > maxItemBytes ->
                                ShareRejectReason.ItemTooLarge
                            metadata.sizeBytes != null && batchBytes > maxBatchBytes - metadata.sizeBytes ->
                                ShareRejectReason.BatchTooLarge
                            else -> null
                        }
                        val stagedName = "item-${position.toString().padStart(3, '0')}"
                        var size = metadata.sizeBytes
                        var sha256: String? = null
                        if (rejection == null) {
                            val target = File(temporary, stagedName)
                            try {
                                val staged = stageSingleStream(
                                    resolver = resolver,
                                    uri = uri,
                                    target = target,
                                    itemLimit = maxItemBytes,
                                    batchLimit = maxBatchBytes - batchBytes,
                                )
                                size = staged.size
                                sha256 = staged.sha256
                                rejection = validateStaged(target, raw.mimeType, mime, safeName!!)
                                if (rejection == null) batchBytes += staged.size else target.delete()
                            } catch (_: SecurityException) {
                                rejection = ShareRejectReason.PermissionDenied
                            } catch (tooLarge: StageTooLargeException) {
                                rejection = tooLarge.reason
                            } catch (_: IOException) {
                                rejection = ShareRejectReason.ReadFailed
                            } catch (_: IllegalArgumentException) {
                                rejection = ShareRejectReason.ReadFailed
                            }
                            if (rejection != null) {
                                target.delete()
                                sha256 = null
                            }
                        }
                        items += StagedItem(
                            id = position.toString(),
                            stagedName = stagedName,
                            displayName = safeName ?: displayName,
                            mimeType = mime,
                            sizeBytes = size,
                            sha256 = sha256,
                            rejection = rejection,
                            importedName = null,
                            importedSize = null,
                            importedMime = null,
                            importedFileUuid = null,
                            indexTaskId = null,
                        )
                    }
                    writeRequestManifest(temporary, requestId, raw.mimeType, raw.fingerprint, items)
                    fsyncDirectory(temporary)
                    if (!temporary.renameTo(committed)) error("无法提交分享 staging 目录")
                    fsyncDirectory(requestsRoot)
                    faultInjector(DurableShareCheckpoint.AfterRequestRenameBeforeIndexWrite)
                    index.requests += InboxRecord(
                        requestId = requestId,
                        fingerprint = raw.fingerprint,
                        state = STATE_PENDING,
                        leaseToken = null,
                        leasedAt = null,
                        createdAt = now(),
                    )
                    persistIndexLocked()
                    _pendingCount.value = index.requests.size
                    ShareEnqueueResult.Accepted
                } catch (cancelled: CancellationException) {
                    index.requests.removeAll { it.requestId == requestId }
                    temporary.deleteRecursively()
                    committed.deleteRecursively()
                    throw cancelled
                } catch (_: IOException) {
                    index.requests.removeAll { it.requestId == requestId }
                    temporary.deleteRecursively()
                    committed.deleteRecursively()
                    ShareEnqueueResult.RejectedInvalid
                } catch (_: JSONException) {
                    index.requests.removeAll { it.requestId == requestId }
                    temporary.deleteRecursively()
                    committed.deleteRecursively()
                    ShareEnqueueResult.RejectedInvalid
                } catch (_: SecurityException) {
                    index.requests.removeAll { it.requestId == requestId }
                    temporary.deleteRecursively()
                    committed.deleteRecursively()
                    ShareEnqueueResult.RejectedInvalid
                } catch (_: IllegalArgumentException) {
                    index.requests.removeAll { it.requestId == requestId }
                    temporary.deleteRecursively()
                    committed.deleteRecursively()
                    ShareEnqueueResult.RejectedInvalid
                } catch (_: IllegalStateException) {
                    index.requests.removeAll { it.requestId == requestId }
                    temporary.deleteRecursively()
                    committed.deleteRecursively()
                    ShareEnqueueResult.RejectedInvalid
                }
            }
        }

    suspend fun snapshot(): List<ShareRequest> = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverStaleLeasesLocked()
            index.requests.mapNotNull(::readRequest)
        }
    }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverStaleLeasesLocked()
            _pendingCount.value = index.requests.size
        }
    }

    suspend fun awaitCapacity() {
        pendingCount.first { count -> count < MAX_PENDING }
    }

    suspend fun claimNext(): ShareLease? = withContext(Dispatchers.IO) {
        mutex.withLock {
            recoverStaleLeasesLocked()
            val active = index.requests.firstOrNull { it.state == STATE_LEASED }
            val record = active ?: index.requests.firstOrNull { it.state == STATE_PENDING } ?: return@withLock null
            val request = readRequest(record) ?: run {
                dropCorruptLocked(record)
                return@withLock null
            }
            val token = record.leaseToken ?: UUID.randomUUID().toString()
            if (record.state != STATE_LEASED) {
                val leased = record.copy(state = STATE_LEASED, leaseToken = token, leasedAt = now())
                replaceRecordLocked(leased)
                try {
                    persistIndexLocked()
                } catch (failure: Exception) {
                    replaceRecordLocked(record)
                    throw failure
                }
            }
            ShareLease(token, request)
        }
    }

    suspend fun ack(token: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = index.requests.firstOrNull { it.state == STATE_LEASED && it.leaseToken == token }
                ?: return@withLock false
            val consumedBefore = LinkedHashSet(index.consumed)
            val consumedRequestIdsBefore = LinkedHashSet(index.consumedRequestIds)
            index.requests.remove(record)
            index.consumed.remove(record.fingerprint)
            index.consumed.add(record.fingerprint)
            index.consumedRequestIds.add(record.requestId)
            trimConsumedLocked()
            try {
                persistIndexLocked()
            } catch (failure: Exception) {
                index.consumed.clear()
                index.consumed.addAll(consumedBefore)
                index.consumedRequestIds.clear()
                index.consumedRequestIds.addAll(consumedRequestIdsBefore)
                index.requests.add(0, record)
                throw failure
            }
            File(requestsRoot, record.requestId).deleteRecursively()
            fsyncDirectory(requestsRoot)
            _pendingCount.value = index.requests.size
            true
        }
    }

    suspend fun nack(token: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val record = index.requests.firstOrNull { it.state == STATE_LEASED && it.leaseToken == token }
                ?: return@withLock false
            replaceRecordLocked(record.copy(state = STATE_PENDING, leaseToken = null, leasedAt = null))
            try {
                persistIndexLocked()
            } catch (failure: Exception) {
                replaceRecordLocked(record)
                throw failure
            }
            true
        }
    }

    suspend fun drop(token: String): Boolean = ack(token)

    suspend fun recordCreated(requestId: String, created: List<ShareImportItem>) = withContext(Dispatchers.IO) {
        if (created.isEmpty()) return@withContext
        mutex.withLock {
            val directory = File(requestsRoot, requestId)
            readValidatedManifest(directory)
            val manifest = AtomicFile(File(directory, REQUEST_MANIFEST))
            val json = JSONObject(readAuthenticated(manifest))
            val replacements = created.associateBy { it.uri.lastPathSegment }
            val items = json.getJSONArray("items")
            repeat(items.length()) { index ->
                val item = items.getJSONObject(index)
                replacements[item.getString("id")]?.takeIf { it.status == ShareImportStatus.Created }?.let { result ->
                    item.put("importedName", result.displayName)
                        .put("importedSize", result.sizeBytes ?: JSONObject.NULL)
                        .put("importedMime", result.mimeType ?: JSONObject.NULL)
                        .put("importedFileUuid", result.fileUuid ?: JSONObject.NULL)
                        .put("indexTaskId", result.indexTaskId ?: JSONObject.NULL)
                        .put("importedLinuxPath", result.linuxPath ?: JSONObject.NULL)
                }
            }
            writeAuthenticated(manifest, json.toString())
        }
    }

    suspend fun recordTarget(requestId: String, workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        require(workspaceRootUuid.isNotBlank())
        mutex.withLock {
            val directory = File(requestsRoot, requestId)
            readValidatedManifest(directory)
            val manifest = AtomicFile(File(directory, REQUEST_MANIFEST))
            val json = JSONObject(readAuthenticated(manifest))
                .put("targetWorkspaceRootUuid", workspaceRootUuid)
                .put("targetDestination", JSONObject.NULL)
            writeAuthenticated(manifest, json.toString())
        }
    }

    suspend fun recordTarget(requestId: String, destination: ShareImportDestination) = withContext(Dispatchers.IO) {
        val encoded = destination.toJson()
        shareImportDestination(encoded)
        mutex.withLock {
            val directory = File(requestsRoot, requestId)
            readValidatedManifest(directory)
            val manifest = AtomicFile(File(directory, REQUEST_MANIFEST))
            val root = when (destination) {
                is ShareImportDestination.SessionFolder -> destination.workspaceRootUuid
                is ShareImportDestination.KnowledgeFolder -> destination.workspaceRootUuid
                is ShareImportDestination.LinuxDirectory -> null
            }
            val json = JSONObject(readAuthenticated(manifest))
                .put("targetDestination", encoded).put("targetWorkspaceRootUuid", root ?: JSONObject.NULL)
            writeAuthenticated(manifest, json.toString())
        }
    }

    suspend fun recordTargetDurably(requestId: String, destination: ShareImportDestination) = recordTarget(requestId, destination)

    fun contentSource(): SharedContentSource = object : SharedContentSource {
        override fun metadata(uri: Uri): SharedContentMetadata {
            val item = resolveItem(uri)
            return SharedContentMetadata(item.item.displayName, item.item.mimeType, item.item.sizeBytes)
        }

        override fun open(uri: Uri): InputStream {
            val item = resolveItem(uri)
            if (item.item.rejection != null) throw java.io.FileNotFoundException("分享项已在 staging 拒绝")
            return FileInputStream(File(item.directory, item.item.stagedName))
        }

        override fun preflightReason(uri: Uri): ShareRejectReason? = resolveItem(uri).item.rejection
        override fun contentSha256(uri: Uri): String? = resolveItem(uri).item.sha256
        override fun preflightItem(uri: Uri): ShareImportItem? {
            val item = resolveItem(uri).item
            val importedName = item.importedName ?: return null
            return ShareImportItem(
                uri = uri,
                displayName = importedName,
                mimeType = item.importedMime ?: item.mimeType,
                sizeBytes = item.importedSize ?: item.sizeBytes,
                status = ShareImportStatus.Created,
                indexStatus = if (item.importedLinuxPath == null && SharedFileImporter.isParseable(item.mimeType, importedName)) ShareIndexStatus.Pending else null,
                fileUuid = item.importedFileUuid,
                indexTaskId = item.indexTaskId,
                parseable = SharedFileImporter.isParseable(item.importedMime ?: item.mimeType, importedName),
                linuxPath = item.importedLinuxPath,
            )
        }
    }

    private fun readRequest(record: InboxRecord): ShareRequest? {
        return try {
            val manifest = readValidatedManifest(File(requestsRoot, record.requestId))
            if (manifest.fingerprint != record.fingerprint) return null
            manifest.request
        } catch (_: RecoverableInboxException) {
            null
        }
    }

    private fun resolveItem(uri: Uri): ResolvedItem {
        require(uri.scheme == STAGED_SCHEME)
        val requestId = uri.authority ?: error("staged URI 缺少 requestId")
        val itemId = uri.lastPathSegment ?: error("staged URI 缺少 itemId")
        val directory = File(requestsRoot, requestId)
        val manifest = readValidatedManifest(directory)
        val item = manifest.items.firstOrNull { it.id == itemId } ?: error("staged item 不存在")
        return ResolvedItem(directory, item)
    }

    private fun writeRequestManifest(
        directory: File,
        requestId: String,
        mimeType: String,
        fingerprint: String,
        items: List<StagedItem>,
    ) {
        val payload = JSONObject()
            .put("version", VERSION)
            .put("requestId", requestId)
            .put("mimeType", mimeType)
            .put("fingerprint", fingerprint)
            .put("targetWorkspaceRootUuid", JSONObject.NULL)
            .put("items", JSONArray(items.map { it.toJson() }))
            .toString()
        writeAuthenticated(AtomicFile(File(directory, REQUEST_MANIFEST)), payload)
    }

    private fun loadIndex(): InboxIndex {
        root.mkdirs()
        requestsRoot.mkdirs()
        // 正式 request 目录是崩溃恢复的事实源。必须先扫描，不能先按可能落后的 index 删除目录。
        val manifests = scanValidRequestDirectories()
        val loaded = try {
            readIndex()
        } catch (_: RecoverableInboxException) {
            InboxIndex(mutableListOf(), linkedSetOf(), linkedSetOf())
        }
        val indexedRequestIds = loaded.requests.mapTo(hashSetOf()) { it.requestId }
        val activeManifests = manifests.filterNot { manifest ->
            val requestId = manifest.request.requestId
            requestId in loaded.consumedRequestIds ||
                // 旧版 index 只记录指纹：仅对未被索引的残留目录按指纹判定已消费。
                (loaded.legacyConsumedOnly && requestId !in indexedRequestIds && manifest.fingerprint in loaded.consumed)
        }
        val indexedById = loaded.requests.associateBy { it.requestId }
        val repairedRecords = activeManifests.map { manifest ->
            indexedById[manifest.request.requestId]
                ?.takeIf { it.fingerprint == manifest.fingerprint }
                ?: InboxRecord(
                    requestId = manifest.request.requestId,
                    fingerprint = manifest.fingerprint,
                    state = STATE_PENDING,
                    leaseToken = null,
                    leasedAt = null,
                    createdAt = manifest.createdAt,
                )
        }.sortedBy { it.createdAt }.toMutableList()
        val repaired = InboxIndex(repairedRecords, loaded.consumed, loaded.consumedRequestIds)
        if (repaired.requests != loaded.requests || loaded.legacyConsumedOnly || !indexFile.baseFile.exists()) {
            writeIndex(repaired)
        }
        cleanupOrphans(activeManifests.mapTo(hashSetOf()) { it.request.requestId })
        return repaired
    }

    private fun persistIndexLocked() {
        writeIndex(index)
    }

    private fun writeIndex(value: InboxIndex) {
        val payload = JSONObject()
            .put("version", VERSION)
            .put("requests", JSONArray(value.requests.map { it.toJson() }))
            .put("consumed", JSONArray(value.consumed.toList()))
            .put("consumedRequestIds", JSONArray(value.consumedRequestIds.toList()))
            .toString()
        writeAuthenticated(indexFile, payload)
    }

    private fun readIndex(): InboxIndex = deserialize("index") {
        val json = JSONObject(readAuthenticated(indexFile))
        require(json.getInt("version") == VERSION)
        val consumed = linkedSetOf<String>()
        json.optJSONArray("consumed")?.let { array ->
            repeat(array.length()) {
                val fingerprint = array.getString(it)
                require(SHA256_PATTERN.matches(fingerprint))
                consumed += fingerprint
            }
        }
        val consumedRequestIdsJson = json.optJSONArray("consumedRequestIds")
        val consumedRequestIds = linkedSetOf<String>()
        consumedRequestIdsJson?.let { array ->
            repeat(array.length()) {
                val requestId = array.getString(it)
                require(UUID.fromString(requestId).toString() == requestId)
                consumedRequestIds += requestId
            }
        }
        val requests = mutableListOf<InboxRecord>()
        json.optJSONArray("requests")?.let { array ->
            repeat(array.length()) {
                val record = array.getJSONObject(it).toInboxRecord()
                validateRecord(record)
                require(requests.none { existing -> existing.requestId == record.requestId })
                requests += record
            }
        }
        InboxIndex(requests, consumed, consumedRequestIds, legacyConsumedOnly = consumedRequestIdsJson == null)
    }

    private fun validateRecord(record: InboxRecord) {
        require(UUID.fromString(record.requestId).toString() == record.requestId)
        require(SHA256_PATTERN.matches(record.fingerprint))
        require(record.createdAt >= 0L)
        when (record.state) {
            STATE_PENDING -> require(record.leaseToken == null && record.leasedAt == null)
            STATE_LEASED -> {
                require(record.leaseToken != null && record.leasedAt != null)
                require(UUID.fromString(record.leaseToken).toString() == record.leaseToken)
            }
            else -> throw IllegalArgumentException("未知 inbox 状态")
        }
    }

    private inline fun <T> deserialize(label: String, block: () -> T): T = try {
        block()
    } catch (failure: RecoverableInboxException) {
        throw failure
    } catch (failure: IOException) {
        throw RecoverableInboxException("无法读取 $label", failure)
    } catch (failure: JSONException) {
        throw RecoverableInboxException("$label JSON 损坏", failure)
    } catch (failure: IllegalArgumentException) {
        throw RecoverableInboxException("$label 字段非法", failure)
    }

    private fun scanValidRequestDirectories(): List<ValidatedManifest> =
        requestsRoot.listFiles().orEmpty().mapNotNull { directory ->
            if (!directory.isDirectory || directory.name.startsWith(".stage-")) return@mapNotNull null
            try {
                readValidatedManifest(directory)
            } catch (_: RecoverableInboxException) {
                null
            }
        }

    private fun readValidatedManifest(directory: File): ValidatedManifest = deserialize("request manifest") {
        require(directory.isDirectory)
        require(UUID.fromString(directory.name).toString() == directory.name)
        val payload = readAuthenticated(AtomicFile(File(directory, REQUEST_MANIFEST)))
        val json = JSONObject(payload)
        require(json.getInt("version") == VERSION)
        val requestId = json.getString("requestId")
        require(requestId == directory.name)
        require(UUID.fromString(requestId).toString() == requestId)
        val fingerprint = json.getString("fingerprint")
        require(SHA256_PATTERN.matches(fingerprint))
        val mimeType = json.getString("mimeType")
        require(mimeType.length <= 512 && mimeType.none(Char::isISOControl))
        val targetWorkspaceRootUuid = if (json.isNull("targetWorkspaceRootUuid")) null
            else json.getString("targetWorkspaceRootUuid").also { require(it.isNotBlank()) }
        val itemsJson = json.getJSONArray("items")
        require(itemsJson.length() in 1..MAX_ITEMS)
        val uris = ArrayList<Uri>(itemsJson.length())
        val stagedItems = ArrayList<StagedItem>(itemsJson.length())
        repeat(itemsJson.length()) { position ->
            val itemJson = itemsJson.getJSONObject(position)
            val item = itemJson.toStagedItem()
            require(item.id == position.toString() && ITEM_ID_PATTERN.matches(item.id))
            require(item.stagedName == "item-${position.toString().padStart(3, '0')}")
            require('/' !in item.stagedName && '\\' !in item.stagedName)
            if (item.rejection != ShareRejectReason.InvalidName) {
                require(SharedFileImporter.normalizeName(item.displayName) == item.displayName)
            }
            require(item.mimeType.length <= 512 && item.mimeType.none(Char::isISOControl))
            item.importedLinuxPath?.let { path ->
                require(path.startsWith('/') && path.length <= MAX_URI_CHARS && path.none(Char::isISOControl) &&
                    path.split('/').none { it == "." || it == ".." })
                require(item.importedFileUuid == null && item.indexTaskId == null)
            }
            require(item.sizeBytes == null || item.sizeBytes >= 0L)
            item.importedName?.let { require(SharedFileImporter.normalizeName(it) == it) }
            require(item.importedSize == null || item.importedSize >= 0L)
            item.importedFileUuid?.let { require(UUID.fromString(it).toString() == it) }
            item.indexTaskId?.let { require(DURABLE_ID_PATTERN.matches(it)) }
            val stagedFile = File(directory, item.stagedName)
            require(stagedFile.canonicalFile.parentFile == directory.canonicalFile)
            if (item.rejection == null) {
                require(stagedFile.isFile)
                require(item.sizeBytes != null && stagedFile.length() == item.sizeBytes)
                require(item.sha256 != null && SHA256_PATTERN.matches(item.sha256))
                require(stagedFile.sha256Streaming() == item.sha256)
            } else {
                require(!stagedFile.exists() && item.sha256 == null)
            }
            stagedItems += item
            uris += stagedUri(requestId, item.id)
        }
        val request = ShareRequest(
            uris = uris,
            mimeType = mimeType,
            fingerprint = fingerprint,
            canonicalSizeBytes = payload.toByteArray(Charsets.UTF_8).size,
            requestId = requestId,
            targetWorkspaceRootUuid = targetWorkspaceRootUuid,
            targetDestination = if (json.isNull("targetDestination")) null else shareImportDestination(json.getJSONObject("targetDestination")),
        )
        ValidatedManifest(request, fingerprint, directory.lastModified().takeIf { it > 0 } ?: now(), stagedItems)
    }

    private fun recoverStaleLeasesLocked() {
        var changed = false
        index.requests = index.requests.mapTo(mutableListOf()) { record ->
            if (record.state == STATE_LEASED && now() - (record.leasedAt ?: 0L) >= LEASE_TIMEOUT_MS) {
                changed = true
                record.copy(state = STATE_PENDING, leaseToken = null, leasedAt = null)
            } else record
        }
        if (changed) persistIndexLocked()
    }

    private fun replaceRecordLocked(replacement: InboxRecord) {
        val indexOf = index.requests.indexOfFirst { it.requestId == replacement.requestId }
        check(indexOf >= 0)
        index.requests[indexOf] = replacement
    }

    private fun dropCorruptLocked(record: InboxRecord) {
        index.requests.remove(record)
        File(requestsRoot, record.requestId).deleteRecursively()
        persistIndexLocked()
        _pendingCount.value = index.requests.size
    }

    private fun trimConsumedLocked() {
        while (index.consumed.size > MAX_CONSUMED) index.consumed.remove(index.consumed.first())
        while (index.consumedRequestIds.size > MAX_CONSUMED) {
            index.consumedRequestIds.remove(index.consumedRequestIds.first())
        }
    }

    private fun cleanupOrphans(known: Set<String>) {
        requestsRoot.listFiles().orEmpty().forEach { file ->
            if (file.name.startsWith(".stage-") || file.name !in known) file.deleteRecursively()
        }
    }

    private fun stageSingleStream(
        resolver: ContentResolver,
        uri: Uri,
        target: File,
        itemLimit: Long,
        batchLimit: Long,
    ): StagedWrite {
        val input = resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException()
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        input.use { source ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (size > itemLimit - count) throw StageTooLargeException(ShareRejectReason.ItemTooLarge)
                    if (size > batchLimit - count) throw StageTooLargeException(ShareRejectReason.BatchTooLarge)
                    output.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                    size += count
                }
                output.fd.sync()
            }
        }
        return StagedWrite(size, digest.digest().hex())
    }

    private fun validateStaged(
        file: File,
        declaredMime: String,
        resolvedMime: String,
        name: String,
    ): ShareRejectReason? {
        if (file.length() == 0L) return ShareRejectReason.EmptyFile
        val effective = SharedFileImporter.parseableMime(resolvedMime, name)
        if (effective == null) return null
        return try {
            val sniffed = file.inputStream().use { SharedFileImporter.sniffMime(it, effective) }
            if (!SharedFileImporter.mimeCompatible(declaredMime, resolvedMime, sniffed, name)) return ShareRejectReason.MimeMismatch
            if (effective in SharedFileImporter.TEXT_MIME_TYPES) {
                SharedFileImporter.validateText { file.inputStream() }
            }
            if (effective == "application/json" && !isValidJson(file)) ShareRejectReason.MimeMismatch else null
        } catch (_: IOException) { ShareRejectReason.MimeMismatch }
          catch (_: IllegalArgumentException) { ShareRejectReason.MimeMismatch }
    }

    private fun isValidJson(file: File): Boolean = try {
        JsonReader(file.reader(Charsets.UTF_8)).use { reader ->
            reader.isLenient = false
            reader.skipValue()
            if (reader.peek() != android.util.JsonToken.END_DOCUMENT) throw IllegalArgumentException("JSON 尾部存在额外内容")
        }
        true
    } catch (_: IOException) {
        false
    } catch (_: IllegalArgumentException) {
        false
    }

    private fun queryMetadata(resolver: ContentResolver, uri: Uri, fallbackMime: String): Metadata {
        var name: String? = null
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { name = cursor.getString(it) }
                    cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }
                        ?.let { size = cursor.getLong(it) }
                }
            }
        return Metadata(name, resolver.getType(uri) ?: fallbackMime, size)
    }

    private fun rejectedMetadataItem(
        position: Int,
        uri: Uri,
        mimeType: String,
        reason: ShareRejectReason,
    ) = StagedItem(
        id = position.toString(),
        stagedName = "item-${position.toString().padStart(3, '0')}",
        displayName = uri.lastPathSegment ?: "shared-$position",
        mimeType = mimeType,
        sizeBytes = null,
        sha256 = null,
        rejection = reason,
        importedName = null,
        importedSize = null,
        importedMime = null,
        importedFileUuid = null,
        indexTaskId = null,
    )

    private fun writeAuthenticated(file: AtomicFile, payload: String) {
        val bytes = payload.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_MANIFEST_BYTES)
        val envelope = JSONObject().put("payload", payload).put("sha256", bytes.sha256()).toString()
            .toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(envelope)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    private fun readAuthenticated(file: AtomicFile): String = deserialize("authenticated envelope") {
        val bytes = file.readFully()
        require(bytes.size <= MAX_MANIFEST_BYTES)
        val envelope = JSONObject(bytes.toString(Charsets.UTF_8))
        val payload = envelope.getString("payload")
        require(payload.toByteArray(Charsets.UTF_8).sha256() == envelope.getString("sha256"))
        payload
    }

    private fun fsyncDirectory(directory: File) {
        try {
            FileInputStream(directory).use { it.fd.sync() }
        } catch (_: IOException) {
            // 部分 Android 文件系统不允许打开目录 fd；文件与 AtomicFile 本身仍已 fsync。
        }
    }

    private fun stagedUri(requestId: String, itemId: String): Uri =
        Uri.Builder().scheme(STAGED_SCHEME).authority(requestId).appendPath(itemId).build()

    private data class Metadata(val displayName: String?, val mimeType: String, val sizeBytes: Long?)
    private data class StagedWrite(val size: Long, val sha256: String)
    private data class ResolvedItem(val directory: File, val item: StagedItem)
    private data class ValidatedManifest(
        val request: ShareRequest,
        val fingerprint: String,
        val createdAt: Long,
        val items: List<StagedItem>,
    )
    private data class InboxIndex(
        var requests: MutableList<InboxRecord>,
        val consumed: LinkedHashSet<String>,
        val consumedRequestIds: LinkedHashSet<String>,
        val legacyConsumedOnly: Boolean = false,
    )
    private data class InboxRecord(
        val requestId: String,
        val fingerprint: String,
        val state: String,
        val leaseToken: String?,
        val leasedAt: Long?,
        val createdAt: Long,
    )
    private data class StagedItem(
        val id: String,
        val stagedName: String,
        val displayName: String,
        val mimeType: String,
        val sizeBytes: Long?,
        val sha256: String?,
        val rejection: ShareRejectReason?,
        val importedName: String?,
        val importedSize: Long?,
        val importedMime: String?,
        val importedFileUuid: String?,
        val indexTaskId: String?,
        val importedLinuxPath: String? = null,
    )

    private class StageTooLargeException(val reason: ShareRejectReason) : Exception()
    private class RecoverableInboxException(message: String, cause: Throwable? = null) : IOException(message, cause)

    private data class RawShareIntent(
        val action: String,
        val mimeType: String,
        val uris: List<Uri>,
        val fingerprint: String,
    ) {
        companion object {
            fun parse(intent: Intent): RawShareIntent? {
                return try {
                    val action = intent.action?.takeIf { it == Intent.ACTION_SEND || it == Intent.ACTION_SEND_MULTIPLE }
                        ?: return null
                    val mime = intent.type?.substringBefore(';')?.trim()?.lowercase().orEmpty()
                    if (mime.length > 512 || mime.any(Char::isISOControl)) return null
                    @Suppress("DEPRECATION")
                    val extras = if (action == Intent.ACTION_SEND) {
                        listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
                    } else intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                    val clipCount = intent.clipData?.itemCount ?: 0
                    if (extras.size + clipCount !in 1..MAX_ITEMS) return null
                    val clip = buildList {
                        intent.clipData?.let { data -> repeat(data.itemCount) { data.getItemAt(it).uri?.let(::add) } }
                    }
                    val uris = LinkedHashSet(extras + clip).toList()
                    if (uris.size !in 1..MAX_ITEMS || uris.any {
                            val value = it.toString()
                            it.scheme != ContentResolver.SCHEME_CONTENT || it.authority.isNullOrBlank() ||
                                value.length > MAX_URI_CHARS || value.any { character -> character.isISOControl() }
                        }) return null
                    val canonical = buildString {
                        append(action).append('\u0000').append(mime)
                        uris.forEach { append('\u0000').append(it) }
                    }.toByteArray(Charsets.UTF_8)
                    val fingerprint = MessageDigest.getInstance("SHA-256").digest(canonical)
                        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                    RawShareIntent(action, mime, uris, fingerprint)
                } catch (_: IllegalArgumentException) {
                    null
                } catch (_: ClassCastException) {
                    null
                }
            }
        }
    }

    private fun StagedItem.toJson() = JSONObject()
        .put("id", id).put("stagedName", stagedName).put("displayName", displayName)
        .put("mimeType", mimeType).put("sizeBytes", sizeBytes ?: JSONObject.NULL)
        .put("sha256", sha256 ?: JSONObject.NULL).put("rejection", rejection?.name ?: JSONObject.NULL)
        .put("importedName", importedName ?: JSONObject.NULL)
        .put("importedSize", importedSize ?: JSONObject.NULL)
        .put("importedMime", importedMime ?: JSONObject.NULL)
        .put("importedFileUuid", importedFileUuid ?: JSONObject.NULL)
        .put("indexTaskId", indexTaskId ?: JSONObject.NULL)
        .put("importedLinuxPath", importedLinuxPath ?: JSONObject.NULL)

    private fun JSONObject.toStagedItem() = StagedItem(
        id = getString("id"), stagedName = getString("stagedName"), displayName = getString("displayName"),
        mimeType = getString("mimeType"), sizeBytes = if (isNull("sizeBytes")) null else getLong("sizeBytes"),
        sha256 = if (isNull("sha256")) null else getString("sha256"),
        rejection = if (isNull("rejection")) null else ShareRejectReason.valueOf(getString("rejection")),
        importedName = if (isNull("importedName")) null else getString("importedName"),
        importedSize = if (isNull("importedSize")) null else getLong("importedSize"),
        importedMime = if (isNull("importedMime")) null else getString("importedMime"),
        importedFileUuid = if (isNull("importedFileUuid")) null else getString("importedFileUuid"),
        indexTaskId = if (isNull("indexTaskId")) null else getString("indexTaskId"),
        importedLinuxPath = if (isNull("importedLinuxPath")) null else getString("importedLinuxPath"),
    )

    private fun InboxRecord.toJson() = JSONObject()
        .put("requestId", requestId).put("fingerprint", fingerprint).put("state", state)
        .put("leaseToken", leaseToken ?: JSONObject.NULL).put("leasedAt", leasedAt ?: JSONObject.NULL)
        .put("createdAt", createdAt)

    private fun JSONObject.toInboxRecord() = InboxRecord(
        getString("requestId"), getString("fingerprint"), getString("state"),
        if (isNull("leaseToken")) null else getString("leaseToken"),
        if (isNull("leasedAt")) null else getLong("leasedAt"), getLong("createdAt"),
    )

    private fun ByteArray.sha256() = MessageDigest.getInstance("SHA-256").digest(this).hex()
    private fun File.sha256Streaming(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xff) }
    companion object {
        private val instances = ConcurrentHashMap<String, DurableShareInbox>()

        fun get(noBackupFilesDir: File): DurableShareInbox {
            val key = try {
                noBackupFilesDir.canonicalPath
            } catch (_: IOException) {
                noBackupFilesDir.absolutePath
            } catch (_: SecurityException) {
                noBackupFilesDir.absolutePath
            }
            return instances.computeIfAbsent(key) { DurableShareInbox(noBackupFilesDir) }
        }

        private const val VERSION = 1
        private const val REQUEST_MANIFEST = "request.json"
        private const val STAGED_SCHEME = "infinity-stage"
        private const val STATE_PENDING = "pending"
        private const val STATE_LEASED = "leased"
        private const val MAX_ITEMS = 32
        private const val MAX_PENDING = 16
        private const val MAX_URI_CHARS = 4_096
        private const val MAX_CONSUMED = 32
        private const val MAX_MANIFEST_BYTES = 256 * 1024
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val ITEM_ID_PATTERN = Regex("(?:0|[1-9][0-9]?)")
        private val DURABLE_ID_PATTERN = Regex("[A-Za-z0-9._-]{1,128}")
        const val LEASE_TIMEOUT_MS = 30_000L
    }
}
