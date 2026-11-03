package com.promenar.infinity.data.backup

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID
import java.util.concurrent.CancellationException

/** 只打包应用私有头像目录；包内引用不携带源设备路径或文件名。 */
internal object AvatarBackupFiles {
    const val PREFIX = "avatars/"
    const val REFERENCE_PREFIX = "avatar:"
    const val MAX_FILE_BYTES = BackupPackageLimits.MAX_ENTRY_BYTES
    const val MAX_TOTAL_BYTES = BackupPackageLimits.MAX_TOTAL_BYTES
    private val safeId = Regex("[A-Za-z0-9_-]{1,128}")

    data class Snapshot(val payload: DatabaseBackupPayload, val preferences: BackupPreferenceSnapshot, val files: Map<String, ByteArray>)
    data class RestorePlan(val payload: DatabaseBackupPayload, val preferences: BackupPreferenceSnapshot, val files: Map<String, ByteArray>, val blobs: Map<String, BackupBlob> = emptyMap())

    fun requireId(id: String) {
        if (!safeId.matches(id)) throw BackupValidationException("头像文件标识无效")
    }

    fun validateSizes(sizes: Map<String, Long>) {
        var total = 0L
        sizes.forEach { (id, size) ->
            requireId(id)
            if (size < 0 || size > MAX_FILE_BYTES || size > MAX_TOTAL_BYTES - total) {
                throw BackupValidationException("头像文件大小超过限制")
            }
            total += size
        }
    }

    data class StreamSnapshot(
        val payload: DatabaseBackupPayload,
        val preferences: BackupPreferenceSnapshot,
        val blobs: Map<String, BackupBlob>,
        val skipped: List<BackupSkippedItem> = emptyList(),
    )

    /**
     * 悬空引用：头像目录未配置、路径不在头像目录内，或文件已不存在。这类引用没有可备份的内容，
     * 清除引用并列为跳过项；目录内真实存在却无法稳定读取的头像仍让核心层失败。
     */
    fun isDangling(root: Path?, raw: String): Boolean {
        val trusted = root?.toAbsolutePath()?.normalize() ?: return true
        val path = Paths.get(raw).toAbsolutePath().normalize()
        return path == trusted || !path.startsWith(trusted) || !Files.exists(path, NOFOLLOW_LINKS)
    }

    fun snapshotTo(payload: DatabaseBackupPayload, preferences: BackupPreferenceSnapshot, root: Path?, directory: Path): StreamSnapshot {
        val blobs = linkedMapOf<String, BackupBlob>()
        val references = mutableMapOf<Path, String>()
        val skipped = linkedMapOf<String, BackupSkippedItem>()
        fun capture(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            if (isDangling(root, raw)) {
                skipped.getOrPut(raw) { BackupSkippedItem(BackupLayer.CORE, Paths.get(raw).fileName?.toString() ?: raw, BackupSkippedItem.REASON_AVATAR_DANGLING) }
                return null
            }
            try {
                val trusted = root?.let(FileRestoreJournal::requireTrustedDirectory)
                    ?: throw BackupValidationException("头像根目录未配置")
                val path = Paths.get(raw).toAbsolutePath().normalize()
                if (path == trusted || !path.startsWith(trusted)) throw BackupValidationException("头像引用越出私有目录")
                references[path]?.let { return it }
                FileRestoreJournal.rejectSymlinkAncestors(path)
                val size = Files.size(path)
                val id = UUID.randomUUID().toString()
                val target = Files.createTempFile(directory, "avatar-", ".bin")
                blobs[id] = BackupStreams.stableCopy(path, target, size)
                return (REFERENCE_PREFIX + id).also { references[path] = it }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { throw BackupValidationException("核心层头像无法读取", error, error.backupFailureReason() ?: BackupFailureReason.WORKSPACE_FILE_MISMATCH,
                if (error.backupFailureReason() == BackupFailureReason.CAPACITY_EXCEEDED) (error as? BackupValidationException)?.detail else BackupFailureDetail.CORE_CONTENT_UNREADABLE) }
        }
        payload.tables["agents"].orEmpty().forEach { row -> capture(row["avatar_path"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull) }
        val rewrittenPreferences = mapPreferences(preferences, ::capture)
        val rewritten = mapPayload(payload, { raw -> raw?.takeIf { it.isNotBlank() }?.let { references[Paths.get(it).toAbsolutePath().normalize()] } }, lazy = true)
        return StreamSnapshot(rewritten, rewrittenPreferences, blobs, skipped.values.toList())
    }

    fun snapshot(
        payload: DatabaseBackupPayload,
        preferences: BackupPreferenceSnapshot,
        root: Path?,
        memoryBudget: Long,
        entryBudget: Int,
        readStable: (Path, Long, String) -> Pair<ByteArray, String>,
    ): Snapshot {
        val files = linkedMapOf<String, ByteArray>()
        val references = mutableMapOf<Path, String>()
        var remaining = minOf(memoryBudget.coerceAtLeast(0), MAX_TOTAL_BYTES)
        fun capture(raw: String?): String? {
            if (raw.isNullOrBlank() || isDangling(root, raw)) return null
            if (root == null) throw BackupValidationException("核心层头像根目录未配置", reason = BackupFailureReason.WORKSPACE_FILE_MISMATCH, detail = BackupFailureDetail.CORE_CONTENT_UNREADABLE)
            return try {
                val trustedRoot = FileRestoreJournal.requireTrustedDirectory(root)
                val rootIdentity = Files.readAttributes(trustedRoot, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                val path = Paths.get(raw).toAbsolutePath().normalize()
                if (!path.startsWith(trustedRoot) || path == trustedRoot) throw BackupValidationException("核心层头像路径越界")
                FileRestoreJournal.rejectSymlinkAncestors(path)
                references[path]?.let { return it }
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                if (!attributes.isRegularFile || attributes.isSymbolicLink || attributes.size() > MAX_FILE_BYTES ||
                    attributes.size() > remaining || files.size >= entryBudget
                ) throw BackupValidationException("核心层头像属性或容量不符合备份要求", reason = BackupFailureReason.CAPACITY_EXCEEDED)
                val id = UUID.randomUUID().toString()
                val bytes = readStable(path, attributes.size(), id).first
                try {
                    FileRestoreJournal.requireTrustedDirectory(root)
                    FileRestoreJournal.rejectSymlinkAncestors(path)
                    if (bytes.size.toLong() != attributes.size() ||
                        rootIdentity != Files.readAttributes(trustedRoot, BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
                    ) throw BackupValidationException("头像目录或文件在读取期间发生变化")
                } catch (error: Throwable) {
                    bytes.fill(0)
                    throw error
                }
                files[id] = bytes
                remaining -= bytes.size
                (REFERENCE_PREFIX + id).also { references[path] = it }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                throw BackupValidationException("核心层头像无法读取", error, error.backupFailureReason() ?: BackupFailureReason.WORKSPACE_FILE_MISMATCH,
                    if (error.backupFailureReason() == BackupFailureReason.CAPACITY_EXCEEDED) (error as? BackupValidationException)?.detail else BackupFailureDetail.CORE_CONTENT_UNREADABLE)
            }
        }
        try {
            val rewritten = mapPayload(payload, ::capture)
            val rewrittenPreferences = mapPreferences(preferences, ::capture)
            return Snapshot(rewritten, rewrittenPreferences, files)
        } catch (error: Throwable) {
            files.values.forEach { it.fill(0) }
            throw error
        }
    }

    fun planRestore(
        payload: DatabaseBackupPayload,
        preferences: BackupPreferenceSnapshot,
        files: Map<String, ByteArray>,
        root: Path?,
        allowReferences: Boolean,
    ): RestorePlan {
        validateSizes(files.mapValues { it.value.size.toLong() })
        if (!allowReferences && files.isNotEmpty()) throw BackupValidationException("旧格式不支持头像分区")
        if (files.isNotEmpty() && root == null) throw BackupValidationException("头像恢复根目录未配置")
        val names = files.keys.associateWith { UUID.randomUUID().toString() + ".img" }
        val referenced = mutableSetOf<String>()
        fun resolve(raw: String?): String? {
            if (!allowReferences || raw == null || !raw.startsWith(REFERENCE_PREFIX)) return null
            val id = raw.removePrefix(REFERENCE_PREFIX)
            requireId(id)
            val name = names[id] ?: throw BackupValidationException("头像引用缺少文件")
            referenced += id
            return root!!.toAbsolutePath().normalize().resolve(name).toString()
        }
        val rewritten = mapPayload(payload, ::resolve)
        val rewrittenPreferences = mapPreferences(preferences, ::resolve)
        if (referenced != files.keys) throw BackupValidationException("头像分区包含无引用文件")
        return RestorePlan(rewritten, rewrittenPreferences, files.mapKeys { names.getValue(it.key) })
    }

    fun planRestoreBlobs(
        payload: DatabaseBackupPayload,
        preferences: BackupPreferenceSnapshot,
        files: Map<String, BackupBlob>,
        root: Path?,
        allowReferences: Boolean,
    ): RestorePlan {
        validateSizes(files.mapValues { it.value.size })
        if (!allowReferences && files.isNotEmpty()) throw BackupValidationException("旧格式不支持头像分区")
        if (files.isNotEmpty() && root == null) throw BackupValidationException("头像恢复根目录未配置")
        val names = files.keys.associateWith { UUID.randomUUID().toString() + ".img" }
        val referenced = mutableSetOf<String>()
        fun resolve(raw: String?): String? {
            if (!allowReferences || raw == null || !raw.startsWith(REFERENCE_PREFIX)) return null
            val id = raw.removePrefix(REFERENCE_PREFIX)
            requireId(id)
            val name = names[id] ?: throw BackupValidationException("头像引用缺少文件")
            referenced += id
            return root!!.toAbsolutePath().normalize().resolve(name).toString()
        }
        payload.tables["agents"].orEmpty().forEach { row -> resolve(row["avatar_path"]?.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull) }
        val rewrittenPreferences = mapPreferences(preferences, ::resolve)
        val rewritten = mapPayload(payload, ::resolve, lazy = true)
        if (referenced != files.keys) throw BackupValidationException("头像分区包含无引用文件")
        return RestorePlan(rewritten, rewrittenPreferences, emptyMap(), files.mapKeys { names.getValue(it.key) })
    }

    private fun mapPayload(payload: DatabaseBackupPayload, mapPath: (String?) -> String?, lazy: Boolean = false): DatabaseBackupPayload {
        val agents = payload.tables["agents"] ?: return payload
        val transform: (JsonObject) -> JsonObject = { row ->
            val raw = row["avatar_path"]?.let { if (it == JsonNull) null else it.jsonPrimitive.contentOrNull }
            JsonObject(row + ("avatar_path" to (mapPath(raw)?.let(::JsonPrimitive) ?: JsonNull)))
        }
        return payload.copy(tables = payload.tables + ("agents" to if (lazy) BackupDatabaseRows.map(agents, transform) else agents.map(transform)))
    }

    private fun mapPreferences(snapshot: BackupPreferenceSnapshot, mapPath: (String?) -> String?): BackupPreferenceSnapshot =
        snapshot.copy(entries = snapshot.entries.mapNotNull { entry ->
            if (BackupPreferencePolicy.normalizeNamespace(entry.namespace) != "settings" || entry.key != "user_avatar") entry
            else if (entry.type != PreferenceValueType.STRING) throw BackupValidationException("用户头像偏好类型无效")
            else mapPath(entry.value)?.let { entry.copy(value = it) }
        })
}
