package com.promenar.infinity.domain.tool

import com.promenar.infinity.data.storage.ExternalMountPaths
import com.promenar.infinity.domain.storage.ExternalFolderException
import com.promenar.infinity.domain.storage.ExternalFolderPaths
import com.promenar.infinity.domain.storage.SftpRemoteMountSpec
import kotlinx.serialization.json.*

internal enum class RemoteMountOperation(val toolName: String, val risk: ToolRisk) {
    ARCHIVE("remote_mount_archive", ToolRisk.EXTERNAL_WRITE),
    DOWNLOAD("remote_mount_download", ToolRisk.FILE_WRITE),
    STATUS("remote_mount_transfer_status", ToolRisk.SAFE_READ),
    MKDIR("remote_mount_mkdir", ToolRisk.EXTERNAL_WRITE),
    RENAME("remote_mount_rename", ToolRisk.EXTERNAL_WRITE),
    DELETE("remote_mount_delete", ToolRisk.DELETE);

    val writesRemote: Boolean get() = this in setOf(ARCHIVE, MKDIR, RENAME, DELETE)
    val transfers: Boolean get() = this == ARCHIVE || this == DOWNLOAD
    val defaultWaitMs: Int get() = if (transfers) 5_000 else 0

    val requiredKeys: Set<String> get() = when (this) {
        ARCHIVE, DOWNLOAD -> setOf("workspace_path", "mount_path")
        STATUS -> setOf("transfer_id")
        MKDIR -> setOf("mount_path")
        RENAME -> setOf("mount_path", "target_mount_path", "expected_source_sha256")
        DELETE -> setOf("mount_path", "expected_sha256")
    }
    val optionalKeys: Set<String> get() = when {
        transfers -> setOf("overwrite", "wait_ms")
        this == STATUS -> setOf("wait_ms")
        else -> emptySet()
    }

    companion object {
        fun fromTool(name: String) = entries.singleOrNull { it.toolName == name }
        val writeToolNames = entries.filter { it.writesRemote }.map { it.toolName }.toSet()
    }
}

internal data class RemoteMountPath(val alias: String, val relative: String) {
    val virtual = "/mnt/$alias$relative"
    fun under(root: String) = root.trimEnd('/') + relative

    companion object {
        fun parse(raw: String): RemoteMountPath {
            if (raw.length > 4096 || !raw.startsWith("/mnt/")) invalid("请使用 /mnt/<alias>/ 下的文件或目录路径")
            val path = SftpRemoteMountSpec.canonicalPath(raw)
            val (alias, relative) = ExternalMountPaths.parse(path) ?: invalid("远程挂载路径无效")
            if (relative == "/" || "/mnt/$alias$relative" != path) invalid("请指定挂载根目录下的条目")
            relative.drop(1).split('/').forEach {
                ExternalFolderPaths.validateName(it)
                if (it.startsWith(".infinity-part-")) invalid("传输临时文件为保留路径")
            }
            return RemoteMountPath(alias, relative)
        }

        private fun invalid(message: String): Nothing = throw ExternalFolderException("INVALID_PATH", message)
    }
}

internal data class RemoteMountArguments(
    val path: RemoteMountPath?,
    val workspacePath: String?,
    val targetPath: RemoteMountPath?,
    val expectedSha256: String?,
    val transferId: String?,
    val overwrite: Boolean,
    val waitMs: Long,
) {
    companion object {
        private val sha256 = Regex("[A-Fa-f0-9]{64}")

        fun parse(operation: RemoteMountOperation, args: JsonObject): RemoteMountArguments {
            fun invalid(): Nothing = throw ExternalFolderException("INVALID_ARGUMENTS", "远程挂载工具参数缺失或格式无效")
            fun string(key: String): String = (args[key] as? JsonPrimitive)
                ?.takeIf { it.isString && it.content.isNotBlank() && it.content.length <= 4096 && it.content.none(Character::isISOControl) }
                ?.content ?: invalid()
            if (!args.keys.containsAll(operation.requiredKeys) || args.keys.any { it !in operation.requiredKeys && it !in operation.optionalKeys }) invalid()
            val path = if (operation == RemoteMountOperation.STATUS) null else RemoteMountPath.parse(string("mount_path"))
            val workspace = if (operation.transfers) string("workspace_path") else null
            val target = if (operation == RemoteMountOperation.RENAME) RemoteMountPath.parse(string("target_mount_path")) else null
            if (target != null && (target.alias != path?.alias || target == path)) {
                throw ExternalFolderException("INVALID_PATH", "重命名须使用同一挂载下的不同目标路径")
            }
            val hash = when (operation) {
                RemoteMountOperation.RENAME -> string("expected_source_sha256")
                RemoteMountOperation.DELETE -> string("expected_sha256")
                else -> null
            }?.let { it.takeIf(sha256::matches)?.lowercase(java.util.Locale.ROOT) ?: invalid() }
            val id = if (operation == RemoteMountOperation.STATUS) string("transfer_id").takeIf { it.length <= 128 } ?: invalid() else null
            val overwrite = args["overwrite"]?.let { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.booleanOrNull ?: invalid() } ?: false
            val waitMs = args["wait_ms"]?.let { (it as? JsonPrimitive)?.takeUnless(JsonPrimitive::isString)?.longOrNull?.takeIf { n -> n in 0L..15_000L } ?: invalid() }
                ?: operation.defaultWaitMs.toLong()
            return RemoteMountArguments(path, workspace, target, hash, id, overwrite, waitMs)
        }
    }
}
