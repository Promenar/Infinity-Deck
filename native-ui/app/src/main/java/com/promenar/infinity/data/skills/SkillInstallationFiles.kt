package com.promenar.infinity.data.skills

import com.promenar.infinity.data.storage.BoundRealPathDirectory
import com.promenar.infinity.data.storage.RealPathFileSystem
import java.io.File
import java.util.UUID

/** 暂存、发布、恢复与清理始终使用同一组目录句柄，路径根被替换时不会转向其它目录。 */
internal class SkillInstallationFiles(private val fileSystem: RealPathFileSystem) {
    fun <T> install(
        userRoot: File,
        stateRoot: File,
        name: String,
        entries: Map<String, ByteArray>,
        overwrite: Boolean,
        validate: (File) -> Unit,
        commit: (Boolean) -> T,
    ): T {
        val opened = mutableListOf<BoundRealPathDirectory>()
        val stagingName = ".approved-${UUID.randomUUID()}"
        val backupName = ".replaced-${UUID.randomUUID()}"
        try {
            val users = open(userRoot, opened)
            val state = open(stateRoot, opened)
            val usersId = users.state().objectId
            val stateId = state.state().objectId
            state.createDirectory(stagingName)
            val staging = state.openDirectory(stagingName).also(opened::add)
            var originalId: String? = null
            var stagedId: String? = null
            var backupAttempted = false
            var publishAttempted = false
            var committed = false
            try {
                staging.createDirectory(name)
                staging.openDirectory(name).use { write(it, entries) }
                validate(File(stateRoot, stagingName))
                requireCurrent(userRoot, usersId)
                requireCurrent(stateRoot, stateId)
                originalId = users.stat(name)?.objectId
                stagedId = requireNotNull(staging.stat(name)).objectId
                val existed = originalId != null
                if (existed) {
                    if (!overwrite) throw AgentSkillConflictException(name)
                    backupAttempted = true
                    users.moveNew(name, state, backupName)
                }
                publishAttempted = true
                staging.moveNew(name, users, name)
                check(users.stat(name)?.objectId == stagedId) { "发布后的技能目录身份已变化" }
                requireCurrent(userRoot, usersId)
                requireCurrent(stateRoot, stateId)
                val result = commit(existed)
                committed = true
                return result
            } catch (failure: Exception) {
                // rename 已完成而随后 fsync 失败时也会抛异常，按对象身份判断实际落点。
                if (publishAttempted) runCatching {
                    val current = users.stat(name)?.objectId
                    if (current == stagedId && stagedId != null) delete(users, name)
                    else check(current == null || current == originalId) { "安装后的技能目录已被其它操作替换" }
                }.exceptionOrNull()?.let(failure::addSuppressed)
                if (backupAttempted) runCatching {
                    if (state.stat(backupName)?.objectId == originalId && originalId != null) state.moveNew(backupName, users, name)
                }.exceptionOrNull()?.let(failure::addSuppressed)
                throw failure
            } finally {
                runCatching { delete(state, stagingName) }
                // 恢复失败时备份留在私有状态根；成功提交后才清理旧包。
                if (committed && backupAttempted) runCatching { delete(state, backupName) }
            }
        } finally {
            opened.asReversed().forEach { runCatching { it.close() } }
        }
    }

    private fun open(root: File, opened: MutableList<BoundRealPathDirectory>): BoundRealPathDirectory {
        val path = File(root.parentFile!!.canonicalFile, root.name).toPath()
        var directory = fileSystem.openFileSystemRoot().also(opened::add)
        path.forEach { segment -> directory = directory.openDirectory(segment.toString()).also(opened::add) }
        return directory
    }

    private fun requireCurrent(root: File, identity: String) {
        val opened = mutableListOf<BoundRealPathDirectory>()
        try { check(open(root, opened).state().objectId == identity) { "技能根目录在安装期间变化" } }
        finally { opened.asReversed().forEach { runCatching { it.close() } } }
    }

    private fun write(root: BoundRealPathDirectory, entries: Map<String, ByteArray>) {
        entries.forEach { (path, bytes) ->
            var parent = root
            val segments = path.split('/')
            val nested = mutableListOf<BoundRealPathDirectory>()
            try {
                segments.dropLast(1).forEach { segment ->
                    if (parent.stat(segment) == null) parent.createDirectory(segment)
                    parent = parent.openDirectory(segment).also(nested::add)
                }
                parent.createFile(segments.last()).use { file -> file.output.write(bytes); file.sync() }
            } finally { nested.asReversed().forEach { runCatching { it.close() } } }
        }
    }

    private fun delete(parent: BoundRealPathDirectory, name: String) {
        val stat = parent.stat(name) ?: return
        if (stat.isDirectory && !stat.isSymbolicLink) {
            parent.openDirectory(name).use { directory -> directory.names().forEach { delete(directory, it) } }
            parent.deleteDirectory(name)
        } else parent.deleteFile(name)
    }
}
