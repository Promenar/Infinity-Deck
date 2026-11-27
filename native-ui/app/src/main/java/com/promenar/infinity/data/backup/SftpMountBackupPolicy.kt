package com.promenar.infinity.data.backup

import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.SFTP_REMOTE_SOURCE_TYPE
import com.promenar.infinity.domain.storage.SftpRemoteMountSpec
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** 可移植的 SFTP 配置投影，物理挂载表还保存本机授权和探测状态。 */
internal object SftpMountBackupPolicy {
    const val TABLE = "sftp_remote_mounts"
    const val SOURCE_TABLE = "external_mounts"
    private val columns = setOf("id", "alias", "source_type", "source_id", "scope_key", "read_only", "created_at")
    val projection = columns.joinToString(", ")
    private val idPattern = Regex("[A-Za-z0-9._:-]{1,128}")

    fun selection(layers: Set<BackupLayer>?): String {
        if (layers != null && BackupLayer.CORE !in layers) return " WHERE 0"
        val sessions = when {
            layers == null || BackupLayer.KNOWLEDGE in layers && BackupLayer.SESSIONS in layers -> "1"
            BackupLayer.KNOWLEDGE in layers -> "agent_id = '${BackupLayerPolicy.KNOWLEDGE_AGENT}'"
            BackupLayer.SESSIONS in layers -> "agent_id != '${BackupLayerPolicy.KNOWLEDGE_AGENT}'"
            else -> "0"
        }
        return " WHERE source_type = '$SFTP_REMOTE_SOURCE_TYPE' AND scope_key != '' " +
            "AND (scope_key = '${ExternalMountPaths.LIBRARY_SCOPE}' OR scope_key IN (SELECT id FROM sessions WHERE $sessions))" +
            " ORDER BY id"
    }

    fun validate(payload: DatabaseBackupPayload) {
        val rows = payload.tables[TABLE].orEmpty()
        if (rows.isEmpty()) return
        val sessions = payload.tables["sessions"].orEmpty().mapTo(hashSetOf()) { BackupLayerPolicy.string(it, "id") }
        val ids = hashSetOf<String>()
        val aliases = hashSetOf<Pair<String, String>>()
        rows.forEach { row ->
            val (mount, spec) = parse(row)
            if (ExternalMountPaths.isLibraryScope(mount.scopeKey)) {
                if (spec.libraryMountId != null) invalid("资料库根挂载不能引用其他挂载")
            } else if (mount.scopeKey !in sessions || spec.libraryMountId == mount.id) {
                invalid("会话范围或资料库引用无效")
            }
            if (!ids.add(mount.id)) invalid("挂载 ID 重复")
            if (!aliases.add(mount.scopeKey to mount.alias)) invalid("作用域内挂载别名重复")
        }
    }

    /** 在主恢复事务内执行，保留来源和作用域相同的挂载所关联的快照。 */
    suspend fun replace(dao: ExternalMountDao, rows: List<JsonObject>) {
        val all = dao.all()
        val (remote, other) = all.partition { it.sourceType == SFTP_REMOTE_SOURCE_TYPE }
        val otherIds = other.mapTo(hashSetOf()) { it.id }
        val otherAliases = other.mapTo(hashSetOf()) { it.scopeKey to it.alias }
        val remoteById = remote.associateBy { it.id }
        val retainedIds = hashSetOf<String>()
        val renamed = mutableListOf<ExternalMountEntity>()
        rows.forEach { row ->
            val (mount, spec) = parse(row)
            if (mount.id in otherIds) invalid("挂载 ID 与本机其他来源冲突")
            if ((mount.scopeKey to mount.alias) in otherAliases) invalid("作用域内挂载别名与本机其他来源冲突")
            val old = remoteById[mount.id]
            if (old != null && old.scopeKey == mount.scopeKey && SftpRemoteMountSpec.decode(old.sourceId) == spec) {
                retainedIds += old.id
                if (old.alias != mount.alias) renamed += old
            }
        }
        // 已删除或已改来源、作用域的挂载通过外键清理旧快照引用。
        remote.forEach { if (it.id !in retainedIds) dao.delete(it.id) }

        // 先让出旧别名，允许作用域内交换别名，同时保留挂载主键及快照。
        val occupied = all.mapTo(hashSetOf()) { it.scopeKey to it.alias }
        var suffix = 0
        renamed.forEach { old ->
            var temporaryAlias: String
            do {
                temporaryAlias = "restore/${suffix++}"
            } while (!occupied.add(old.scopeKey to temporaryAlias))
            dao.update(old.copy(alias = temporaryAlias))
        }
        rows.forEach { row ->
            val (mount, _) = parse(row)
            if (mount.id in retainedIds) dao.update(mount) else dao.insert(mount)
        }
    }

    private fun parse(row: JsonObject): Pair<ExternalMountEntity, SftpRemoteMountSpec> {
        if (row.keys != columns) invalid("配置行必须恰好包含七个字段")
        val id = row.text("id")
        if (!idPattern.matches(id) || id == ExternalMountPaths.SKILLS_MOUNT_ID) invalid("挂载 ID 无效")
        val alias = row.text("alias")
        val canonicalAlias = try {
            ExternalMountPaths.validateAlias(alias)
        } catch (_: ExternalFolderException) {
            invalid("挂载别名无效")
        }
        if (canonicalAlias != alias) invalid("挂载别名无效")
        val type = row.text("source_type")
        if (type != SFTP_REMOTE_SOURCE_TYPE) invalid("来源类型无效")
        val sourceId = row.text("source_id")
        val spec = SftpRemoteMountSpec.decode(sourceId) ?: invalid("远端来源配置无效")
        val scope = row.text("scope_key")
        if (scope.isBlank() || scope.any(Char::isISOControl)) invalid("作用域无效")
        val readOnly = row.integer("read_only")
        if (readOnly !in 0L..1L) invalid("只读标志无效")
        val createdAt = row.integer("created_at")
        if (createdAt < 0) invalid("创建时间无效")
        return ExternalMountEntity(id, alias, type, sourceId, scope, readOnly == 1L, createdAt, unavailableReason = null) to spec
    }

    private fun JsonObject.text(column: String): String =
        (this[column] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: invalid("字段 $column 必须为字符串")

    private fun JsonObject.integer(column: String): Long =
        (this[column] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull ?: invalid("字段 $column 必须为整数")

    private fun invalid(detail: String): Nothing = throw BackupValidationException("SFTP 挂载$detail")
}
