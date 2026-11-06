package com.promenar.infinity.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.ExternalSnapshotEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.storage.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext

/** 挂载独立于私有工作区的日志与文件身份；所有外部 I/O 均在 IO 调度器执行。 */
class ExternalFolderManager(
    private val context: Context,
    private val dao: ExternalMountDao,
    private val workspace: IWorkspaceRepository,
    private val indexEvents: FileIndexEventSink,
    /** 为 null 时不支持自选目录工作区，所有会话都使用私有工作区。 */
    private val workspaces: SessionWorkspaceResolver? = null,
    /** 与技能目录入口共用真实目录；未注入时使用应用默认用户级技能目录。 */
    private val userSkillsDirectory: File? = null,
    /** 已核验属于系统智能体的知识库根；缺少权威来源时保守拒绝挂载。 */
    private val knowledgeBaseRoots: suspend () -> Set<String> = { emptySet() },
    /** 知识库管理会话拥有的受控根；普通会话不能通过快照导入修改知识库。 */
    private val knowledgeBaseOwnerRoot: suspend (String) -> String? = { null },
    /** 每次通过已批准挂载读取前校验当前会话的资料范围。 */
    private val knowledgeBaseAllowed: suspend (String, String) -> Boolean = { _, _ -> false },
    /** 测试替身；返回 null 时使用真实来源。 */
    private val backendOverride: ((ExternalMount) -> ExternalFolderBackend?)? = null,
) : ExternalWorkspaceAccess {
    private val mutex = Mutex()
    private val versions = File(context.noBackupFilesDir, "external-versions")
    private var versionGeneration = 0L
    private fun skillsMount(sessionId: String) = ExternalMount(
        ExternalMountPaths.SKILLS_MOUNT_ID, ExternalMountPaths.SKILLS_ALIAS, ExternalMountPaths.SKILLS_SOURCE,
        "agent_skills", sessionId, createdAt = 0,
    )
    // filesDir 位于 /data/user/0 这一系统符号链接下，目录后端逐级拒绝符号链接，须先取真实路径。
    private val skillsDirectory by lazy { (userSkillsDirectory ?: File(context.filesDir, "agent_skills")).canonicalFile }
    override fun observeMounts(sessionId: String) = dao.observeVisible(sessionId).map { it.map(ExternalMountEntity::model) }
    override suspend fun visible(sessionId: String) = withContext(Dispatchers.IO) {
        // 历史保留别名仍可在管理入口移除，但不作为工具可达路径列出。
        val library = ExternalMountPaths.isLibraryScope(sessionId)
        val models = dao.visible(sessionId).filterNot { it.alias == ExternalMountPaths.SKILLS_ALIAS }
            .filter { !library || it.sourceType in setOf("saf_tree", "real_path") }.map(ExternalMountEntity::model) +
            if (library) emptyList() else listOf(skillsMount(sessionId))
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        if (bound == null) models else models.filter { bound[it.alias] == it.id }
    }
    override suspend fun resolveUuid(sessionId: String, uuid: String) = ExternalMountPaths.uuidPath(uuid, namespace(sessionId))

    override suspend fun workspaceMount(sessionId: String): ExternalMount? = withContext(Dispatchers.IO) {
        val mount = currentWorkspaceMount(sessionId) ?: return@withContext null
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        mount.takeIf { bound == null || bound[it.alias] == it.id }
    }
    private suspend fun currentWorkspaceMount(sessionId: String): ExternalMount? {
        if (ExternalMountPaths.isLibraryScope(sessionId)) return null
        currentCoroutineContext()[com.promenar.infinity.data.branch.BranchLinuxWorkspaceOperation]
            ?.takeIf { it.sessionId == sessionId }?.let { return it.mount }
        return when (val current = workspaces?.resolve(sessionId) ?: return null) {
            SessionWorkspace.Private -> null
            is SessionWorkspace.Directory -> workspaceMount(current.workspace.id, sessionId, current.workspace.createdAt, null)
            is SessionWorkspace.Unavailable -> workspaceMount(current.workspaceId, sessionId, current.workspace?.createdAt ?: 0, current.reason)
        }
    }
    private fun workspaceMount(workspaceId: String, sessionId: String, createdAt: Long, unavailable: String?) = ExternalMount(
        ExternalMountPaths.workspaceMountId(workspaceId), ExternalMountPaths.WORKSPACE_ALIAS, ExternalMountPaths.WORKSPACE_SOURCE,
        workspaceId, sessionId, createdAt = createdAt, unavailableReason = unavailable,
    )
    /** 可见挂载加当前工作区挂载，用于 uuid 解析。 */
    private suspend fun namespace(sessionId: String) = visible(sessionId) + listOfNotNull(workspaceMount(sessionId))

    override suspend fun addSaf(alias: String, sessionId: String, treeUri: String, grantFlags: Int) =
        withContext(Dispatchers.IO) { mutex.withLock {
            requireSession(sessionId)
            val uri = Uri.parse(treeUri)
            if (uri.scheme != "content" || !DocumentsContract.isTreeUri(uri)) fail("INVALID_PATH", "请选择文件夹授权")
            val mount = newMount(alias, "saf_tree", uri.toString(), sessionId)
            checkAlias(mount)
            val held = context.contentResolver.persistedUriPermissions.any { it.uri == uri }
            takeGrant(uri, grantFlags)
            try {
                requireDirectory(sourceBackend(mount).stat("/"))
                dao.insert(mount.entity())
                mount
            } catch (failure: Exception) {
                if (!held) releaseUnused(uri.toString())
                throw failure
            }
        } }

    override suspend fun addRealPath(alias: String, sessionId: String, source: String) =
        withContext(Dispatchers.IO) { mutex.withLock {
            requireSession(sessionId)
            val canonical = RealPathMountSupport.normalizeSource(context, source)
            val mount = newMount(alias, "real_path", canonical, sessionId)
            checkAlias(mount)
            requireDirectory(sourceBackend(mount).stat("/"))
            dao.insert(mount.entity())
            mount
        } }

    override suspend fun addKnowledgeBase(alias: String, sessionId: String, rootUuid: String) =
        withContext(Dispatchers.IO) { mutex.withLock {
            requireSession(sessionId)
            if (ExternalMountPaths.isLibraryScope(sessionId)) fail("INVALID_SESSION", "知识库挂载必须属于会话")
            val mount = newMount(alias, ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, rootUuid, sessionId).copy(readOnly = true)
            dao.visible(sessionId).firstOrNull {
                it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE && it.sourceId == rootUuid
            }?.let { existing ->
                val current = existing.model()
                requireDirectory(sourceBackend(current).stat("/"))
                return@withLock current
            }
            checkAlias(mount)
            requireDirectory(sourceBackend(mount).stat("/"))
            dao.insert(mount.entity())
            mount
        } }

    override suspend fun attachLibraryMount(sessionId: String, libraryMountId: String, alias: String?) =
        withContext(Dispatchers.IO) { mutex.withLock {
            requireSession(sessionId)
            if (ExternalMountPaths.isLibraryScope(sessionId)) fail("INVALID_SESSION", "资料页不能引用自身的挂载")
            val source = dao.get(libraryMountId) ?: fail("NOT_FOUND", "资料页挂载不存在")
            if (!ExternalMountPaths.isLibraryScope(source.scopeKey) || source.sourceType !in setOf("saf_tree", "real_path")) {
                fail("UNSUPPORTED", "只能引用资料页中的外部文件夹")
            }
            requireDirectory(sourceBackend(source.model()).stat("/"))
            dao.visible(sessionId).firstOrNull { it.sourceType == source.sourceType && it.sourceId == source.sourceId }
                ?.let { return@withLock it.model() }
            val mount = newMount(alias ?: source.alias, source.sourceType, source.sourceId, sessionId).copy(readOnly = source.readOnly)
            checkAlias(mount)
            dao.insert(mount.entity())
            mount
        } }

    override suspend fun libraryMountReferences(libraryMountId: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = dao.get(libraryMountId) ?: fail("NOT_FOUND", "资料页挂载不存在")
            if (!ExternalMountPaths.isLibraryScope(source.scopeKey) || source.sourceType !in setOf("saf_tree", "real_path")) {
                fail("UNSUPPORTED", "只能查询资料页中的外部文件夹")
            }
            dao.all().count { it.scopeKey.isNotBlank() && !ExternalMountPaths.isLibraryScope(it.scopeKey) &&
                it.sourceType == source.sourceType && it.sourceId == source.sourceId }
        }
    }

    override suspend fun remove(mountId: String) = withContext(Dispatchers.IO) { mutex.withLock {
        if (mountId == ExternalMountPaths.SKILLS_MOUNT_ID) fail("INVALID_PATH", "不能移除用户级技能根目录")
        val mount = dao.get(mountId) ?: return@withLock
        dao.delete(mountId)
        if (mount.sourceType == "saf_tree") releaseUnused(mount.sourceId)
    } }

    override suspend fun deleteSessionMounts(sessionId: String) = withContext(Dispatchers.IO) {
        if (sessionId.isNotBlank() && !ExternalMountPaths.isLibraryScope(sessionId)) dao.all().filter { it.scopeKey == sessionId }.forEach { remove(it.id) }
    }

    override suspend fun reauthorize(mountId: String, treeUri: String, grantFlags: Int) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = dao.get(mountId) ?: fail("NOT_FOUND", "挂载点不存在")
        if (old.sourceType != "saf_tree") fail("UNSUPPORTED", "请在系统设置中授予所有文件访问权限")
        val uri = Uri.parse(treeUri)
        if (!DocumentsContract.isTreeUri(uri) || uri.scheme != "content") fail("INVALID_PATH", "请选择文件夹")
        // 不允许重新授权悄悄把既有审批路径指向另一来源。
        if (treeUri != old.sourceId) fail("CONFLICT", "请选择原挂载文件夹；不同来源请先移除挂载并使用新别名")
        takeGrant(uri, grantFlags)
        requireDirectory(sourceBackend(old.model()).stat("/"))
        dao.update(old.copy(unavailableReason = null))
    } }

    override suspend fun refresh(sessionId: String) = withContext(Dispatchers.IO) {
        visible(sessionId).forEach { mount ->
            try { access(sessionId, ExternalMountPaths.virtualPath(mount, "/")) { _, backend, _ -> requireDirectory(backend.stat("/")) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* 可用性已由 access 写入，不把单个离线挂载扩散到整个面板。 */ }
        }
    }

    override suspend fun list(sessionId: String, virtualPath: String): List<ExternalFolderEntry> = withContext(Dispatchers.IO) {
        if (!branchWorkspacePaths(sessionId) && ExternalFolderPaths.normalize(virtualPath) == "/mnt") return@withContext visible(sessionId).filterNot { it.isUserSkills }.map {
            ExternalFolderEntry("/mnt/${it.alias}", it.alias, true, 0, it.createdAt)
        }
        access(sessionId, virtualPath) { mount, backend, relative -> visibleChildren(mount, relative, backend.list(relative)) }
    }

    override suspend fun read(sessionId: String, virtualPath: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val generation = versionGeneration()
        access(sessionId, virtualPath) { mount, backend, path -> backend.read(path, maxBytes).also { remember(mount.id, path, it, generation) } }
    }

    // 流式读取不记录版本快照：快照只保留 1 MiB 以内的小文件，大文件复制走这里。
    override suspend fun <T> readStream(sessionId: String, virtualPath: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T =
        withContext(Dispatchers.IO) { access(sessionId, virtualPath) { _, backend, path -> backend.readStream(path, maxBytes, consume) } }

    override suspend fun entry(sessionId: String, path: String): FileEntry? = withContext(Dispatchers.IO) {
        val generation = versionGeneration()
        if (!branchWorkspacePaths(sessionId) && ExternalFolderPaths.normalize(path) == "/mnt") return@withContext FileEntry(
            ExternalMountPaths.ROOT_UUID, "", null, "mnt", "", isDirectory = true,
            physicalRootPath = "", materializedPath = "/mnt", createdAt = 0, updatedAt = 0,
        )
        access(sessionId, path) { mount, backend, relative ->
            val stat = backend.stat(relative) ?: return@access null
            val hash = stat.sha256 ?: if (!stat.isDirectory && stat.size <= 1024L * 1024) {
                try { externalSha256(backend.read(relative, 1024L * 1024).also { remember(mount.id, relative, it, generation) }) }
                catch (failure: ExternalFolderException) { if (failure.code == "TOO_LARGE") "" else throw failure }
            } else ""
            val parent = when {
                relative != "/" -> ExternalMountPaths.uuid(mount, relative.substringBeforeLast('/').ifEmpty { "/" })
                mount.isWorkspaceRoot || mount.isUserSkills -> null
                else -> ExternalMountPaths.ROOT_UUID
            }
            FileEntry(
                uuid = ExternalMountPaths.uuid(mount, relative), parentUuid = parent,
                name = if (relative == "/") mount.alias else stat.name, hash = hash,
                sizeBytes = stat.size, isDirectory = stat.isDirectory, physicalRootPath = "",
                materializedPath = path, createdAt = mount.createdAt, updatedAt = stat.lastModified,
            )
        }
    }

    override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean): FileEntry =
        withContext(Dispatchers.IO) {
            val bound = writeReceipt(sessionId, path, bytes, expectedSha256, createOnly)
            val receipt = bound.receipt
            val mount = bound.mount
            val relative = bound.relativePath
            FileEntry(
                ExternalMountPaths.uuid(mount, relative), parentUuid = ExternalMountPaths.uuid(mount, relative.substringBeforeLast('/').ifEmpty { "/" }),
                name = receipt.name, hash = externalSha256(bytes), sizeBytes = bytes.size.toLong(),
                physicalRootPath = "", materializedPath = path, createdAt = mount.createdAt, updatedAt = receipt.lastModified,
            )
        }

    private data class BoundWrite(val mount: ExternalMount, val relativePath: String, val receipt: ExternalFolderEntry)
    private suspend fun writeReceipt(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean, bindings: MutableMap<String, String>? = null): BoundWrite =
        withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, bindings, writing = true) { mount, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能覆盖挂载根目录")
                val receipt = backend.write(relative, bytes, expectedSha256, createOnly).copy(path = path, sha256 = externalSha256(bytes))
                remember(mount.id, relative, bytes)
                BoundWrite(mount, relative, receipt)
            }
        } }

    override suspend fun createDirectory(sessionId: String, path: String): FileEntry = withContext(Dispatchers.IO) { mutex.withLock {
        access(sessionId, path, writing = true) { _, backend, relative ->
            if (relative == "/") fail("INVALID_PATH", "不能创建挂载根目录")
            backend.createDirectory(relative)
        }
        entry(sessionId, path) ?: fail("NOT_FOUND", "创建后目录不可用")
    } }

    override suspend fun delete(sessionId: String, path: String, expectedSha256: String?) =
        withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, writing = true) { mount, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能删除挂载根目录，请在管理界面移除挂载")
                val keys = currentVersionKeys(mount.id, backend, relative)
                backend.delete(relative, expectedSha256)
                removeVersionKeys(keys)
            }
        } }

    override suspend fun importSnapshot(sessionId: String, workspaceRootUuid: String, virtualPath: String): String = withContext(Dispatchers.IO) {
        if (ExternalMountPaths.isLibraryScope(sessionId)) fail("INVALID_SESSION", "快照导入必须指定会话")
        val (mount, relative) = resolve(sessionId, virtualPath)
        mount.requireWritable()
        if (workspaceRootUuid in knowledgeBaseRoots() && knowledgeBaseOwnerRoot(sessionId) != workspaceRootUuid) {
            fail("READ_ONLY", "知识库挂载为只读")
        }
        namespace(sessionId).filter { it.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE && it.sourceId == workspaceRootUuid }
            .forEach { it.requireWritable() }
        withContext(ExternalMountOperationBindings(mapOf(mount.alias to mount.id))) {
            val original = entry(sessionId, virtualPath) ?: fail("NOT_FOUND", "源文件不可用")
            if (original.isDirectory) fail("IS_DIRECTORY", "请选择单个文件加入资料")
            val mime = externalSnapshotMime(original.name)
            if (mime !in com.promenar.infinity.share.core.SharedFileImporter.SUPPORTED_MIME_TYPES) fail("UNSUPPORTED", "资料支持 TXT、Markdown、CSV、JSON、HTML、PDF、DOC 和 DOCX")
            val bytes = read(sessionId, virtualPath, 8L * 1024 * 1024)
            val after = entry(sessionId, virtualPath) ?: fail("UNAVAILABLE", "读取后来源不可用")
            if (after.uuid != original.uuid || after.updatedAt != original.updatedAt || after.sizeBytes != original.sizeBytes || after.hash.isNotEmpty() && after.hash != externalSha256(bytes)) fail("CONFLICT", "读取期间源文件发生变化，请重试")
            val uuid = UUID.randomUUID().toString()
            val safeName = "${mount.alias.ifEmpty { "workspace" }}-${uuid.take(8)}-${original.name}"
            val created = workspace.createFileInWorkspaceStreaming(
                workspaceRootUuid, uuid, safeName, mime,
                workspaceRootUuid, "/$safeName", 8L * 1024 * 1024,
            ) { it.write(bytes) }
            try {
                indexEvents.publish(FileIndexEvent.Changed(workspaceRootUuid, uuid, created.hash, created.updatedAt.coerceAtLeast(1)))
                dao.putSnapshot(ExternalSnapshotEntity(mount.id, relative, workspaceRootUuid, uuid, after.updatedAt, externalSha256(bytes), System.currentTimeMillis()))
                workspace.confirmCreatedEntry(workspaceRootUuid, uuid)
            } catch (failure: Exception) {
                withContext(NonCancellable) {
                    try { workspace.rollbackCreatedEntry(workspaceRootUuid, uuid) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
                }
                throw failure
            }
            uuid
        }
    }
    override suspend fun snapshotChanged(sessionId: String, virtualPath: String): Boolean = withContext(Dispatchers.IO) {
        val (mount, relative) = resolve(sessionId, virtualPath)
        val snapshots = dao.snapshots(mount.id, relative)
        if (snapshots.isEmpty()) false else snapshots.any { it.sourceModified != entry(sessionId, virtualPath)?.updatedAt }
    }

    override fun backend(sessionId: String, mountIds: Map<String, String>): ExternalFolderBackend = object : ExternalFolderBackend {
        private val bindings = mountIds.toMutableMap()
        override suspend fun validateTree(path: String) = withContext(Dispatchers.IO) {
            access(sessionId, path, bindings = bindings) { _, backend, relative -> backend.validateTree(relative) }
        }
        override suspend fun move(sourcePath: String, targetPath: String) = transfer(sourcePath, targetPath, move = true)
        override suspend fun copy(sourcePath: String, targetPath: String) = transfer(sourcePath, targetPath, move = false)

        private suspend fun transfer(sourcePath: String, targetPath: String, move: Boolean) = withContext(Dispatchers.IO) {
            mutex.withLock {
                access(sessionId, sourcePath, bindings, writing = true) { sourceMount, backend, source ->
                    access(sessionId, targetPath, bindings, writing = true) { targetMount, _, target ->
                        if (sourceMount.id != targetMount.id) fail("UNSUPPORTED", "直接文件操作不能跨挂载")
                        if (move) backend.move(source, target) else backend.copy(source, target)
                    }
                }
            }
        }
        override suspend fun stat(path: String) = withContext(Dispatchers.IO) {
            access(sessionId, path, bindings = bindings) { _, backend, relative -> backend.stat(relative)?.copy(path = path) }
        }
        override suspend fun list(path: String) = withContext(Dispatchers.IO) {
            access(sessionId, path, bindings = bindings) { mount, backend, relative -> visibleChildren(mount, relative, backend.list(relative)) }
        }
        override suspend fun read(path: String, maxBytes: Long) = withContext(Dispatchers.IO) {
            val generation = versionGeneration()
            access(sessionId, path, bindings = bindings) { mount, backend, relative -> backend.read(relative, maxBytes).also { remember(mount.id, relative, it, generation) } }
        }
        override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T = withContext(Dispatchers.IO) {
            access(sessionId, path, bindings = bindings) { _, backend, relative -> backend.readStream(relative, maxBytes, consume) }
        }
        override suspend fun availableBytes(path: String): Long? = withContext(Dispatchers.IO) {
            access(sessionId, path, bindings = bindings) { _, backend, relative -> backend.availableBytes(relative) }
        }
        override suspend fun writeStream(path: String, expectedSize: Long, createOnly: Boolean, produce: suspend (java.io.OutputStream) -> Unit) = withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, bindings, writing = true) { _, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能覆盖挂载根目录")
                backend.writeStream(relative, expectedSize, createOnly, produce).copy(path = path)
            }
        } }
        override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean) = writeReceipt(sessionId, path, content, expectedSha256, createOnly, bindings).receipt
        override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, bindings, writing = true) { _, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能创建挂载根目录")
                backend.createDirectory(relative).copy(path = path)
            }
        } }
        override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) = withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, bindings, writing = true) { mount, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能移动挂载根目录")
                val keys = currentVersionKeys(mount.id, backend, relative)
                backend.deleteForTransfer(relative, expected.copy(path = relative), expectedSha256)
                removeVersionKeys(keys)
            }
        } }
        override suspend fun delete(path: String, expectedSha256: String?) = withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, path, bindings, writing = true) { mount, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能删除挂载根目录")
                val keys = currentVersionKeys(mount.id, backend, relative)
                backend.delete(relative, expectedSha256)
                removeVersionKeys(keys)
            }
        } }
    }

    override suspend fun version(sessionId: String, path: String, hash: String): ByteArray? = withContext(Dispatchers.IO) {
        val (mount, relative) = resolve(sessionId, path)
        if (!Regex("[0-9a-f]{64}").matches(hash)) null else File(versions, versionKey(mount.id, relative, hash)).takeIf { it.isFile }?.readBytes()
    }
    private fun versionKey(mountId: String, path: String, hash: String) = externalSha256("$mountId\u0000$path\u0000$hash".toByteArray(Charsets.UTF_8))
    private suspend fun currentVersionKeys(mountId: String, backend: ExternalFolderBackend, path: String): List<String> {
        if (!versions.exists()) return emptyList()
        if (!versions.isDirectory) fail("UNAVAILABLE", "文件版本缓存不可用")
        if ((versions.list() ?: fail("UNAVAILABLE", "无法读取文件版本缓存")).isEmpty()) return emptyList()
        val keys = mutableListOf<String>()
        val pending = ArrayDeque<String>()
        val visited = mutableSetOf<String>()
        pending.add(path)
        while (pending.isNotEmpty()) {
            val current = pending.removeLast()
            if (!visited.add(current)) fail("CONFLICT", "目录结构包含重复路径")
            val entry = backend.stat(current) ?: fail("CONFLICT", "删除范围在读取期间发生变化")
            if (entry.isDirectory) {
                backend.list(current).forEach { pending.add(it.path) }
            } else if (entry.size <= 1024L * 1024) {
                val hash = entry.sha256 ?: externalSha256(backend.read(current, 1024L * 1024))
                keys += versionKey(mountId, current, hash)
            }
        }
        return keys
    }
    private fun removeVersionKeys(keys: List<String>) {
        synchronized(versions.path.intern()) {
            versionGeneration++
            keys.forEach { key ->
                val file = File(versions, key)
                if (file.exists() && !file.delete()) fail("UNAVAILABLE", "无法清除文件版本缓存")
            }
        }
    }

    /** 工作区直连后端也使用与文件工具相同的版本缓存键。 */
    fun withVersionCleanup(mountId: String, backend: ExternalFolderBackend): ExternalFolderBackend = object : ExternalFolderBackend by backend {
        override suspend fun delete(path: String, expectedSha256: String?) = withContext(Dispatchers.IO) { mutex.withLock {
            val keys = currentVersionKeys(mountId, backend, path)
            backend.delete(path, expectedSha256)
            removeVersionKeys(keys)
        } }
        override suspend fun deleteForTransfer(path: String, expected: ExternalFolderEntry, expectedSha256: String?) = withContext(Dispatchers.IO) { mutex.withLock {
            val keys = currentVersionKeys(mountId, backend, path)
            backend.deleteForTransfer(path, expected, expectedSha256)
            removeVersionKeys(keys)
        } }
    }
    private fun versionGeneration(): Long = synchronized(versions.path.intern()) { versionGeneration }

    private fun remember(mountId: String, path: String, bytes: ByteArray, generation: Long? = null) {
        if (bytes.size > 1024 * 1024) return
        synchronized(versions.path.intern()) {
            if (generation != null && generation != versionGeneration) return
            versions.mkdirs()
            val file = File(versions, versionKey(mountId, path, externalSha256(bytes)))
            if (!file.exists()) file.writeBytes(bytes)
            var total = versions.listFiles().orEmpty().sumOf { it.length() }
            versions.listFiles().orEmpty().sortedBy { it.lastModified() }.forEach {
                if (total > 32L * 1024 * 1024) { total -= it.length(); it.delete() }
            }
        }
    }

    /** 列表路径改写为虚拟路径；工作区根下的同名条目由保留虚拟根遮蔽。 */
    private suspend fun visibleChildren(mount: ExternalMount, relative: String, children: List<ExternalFolderEntry>): List<ExternalFolderEntry> {
        val workspacePaths = branchWorkspacePaths(mount.sessionId)
        return children.filterNot { !workspacePaths && mount.isWorkspaceRoot && relative == "/" && it.name in setOf("mnt", "skills") }
        .map { it.copy(path = ExternalMountPaths.virtualPath(mount, it.path)) }
    }

    private suspend fun branchWorkspacePaths(sessionId: String): Boolean =
        !ExternalMountPaths.isLibraryScope(sessionId) && currentCoroutineContext()[com.promenar.infinity.data.branch.BranchLinuxWorkspaceOperation]
            ?.let { it.sessionId == sessionId && it.workspacePaths } == true

    private suspend fun resolve(sessionId: String, path: String): Pair<ExternalMount, String> {
        val workspacePaths = branchWorkspacePaths(sessionId)
        val parsed = if (workspacePaths) null else ExternalMountPaths.parse(path)
        if (parsed == null) {
            val normalized = ExternalFolderPaths.normalize(path)
            val root = currentWorkspaceMount(sessionId) ?: fail("INVALID_PATH", "不是挂载路径")
            if (!workspacePaths && normalized == "/mnt") fail("INVALID_PATH", "/mnt 为挂载保留目录")
            val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
            if (bound != null && bound[root.alias] != root.id) fail("CONFLICT", "操作期间会话工作区发生变化，请重新读取目标")
            return root to normalized
        }
        val (alias, relative) = parsed
        if (ExternalMountPaths.isLibraryScope(sessionId) && alias == ExternalMountPaths.SKILLS_ALIAS) fail("NOT_FOUND", "资料页中没有该挂载")
        val mount = if (alias == ExternalMountPaths.SKILLS_ALIAS && !ExternalMountPaths.isLibraryScope(sessionId)) skillsMount(sessionId)
            else dao.visible(sessionId).singleOrNull { it.alias == alias }?.model() ?: fail("NOT_FOUND", "挂载点不属于当前会话或已被移除")
        if (ExternalMountPaths.isLibraryScope(sessionId) && mount.sourceType !in setOf("saf_tree", "real_path")) fail("UNSUPPORTED", "资料页只支持外部文件夹")
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        if (bound != null && bound[alias] != mount.id) fail("CONFLICT", "操作期间挂载身份发生变化，请重新读取目标")
        return mount to relative
    }
    private suspend fun <T> access(sessionId: String, path: String, bindings: MutableMap<String, String>? = null, writing: Boolean = false, block: suspend (ExternalMount, ExternalFolderBackend, String) -> T): T {
        val (mount, relative) = resolve(sessionId, path)
        if (writing) mount.requireWritable()
        if (bindings != null) synchronized(bindings) {
            val previous = bindings.putIfAbsent(mount.alias, mount.id)
            if (previous != null && previous != mount.id) fail("CONFLICT", "操作期间挂载身份发生变化，已取消后续访问")
        }
        try {
            val result = block(mount, sourceBackend(mount, sessionId), relative)
            if (mount.unavailableReason != null && !mount.isWorkspaceRoot && !mount.isUserSkills) dao.update(mount.copy(unavailableReason = null).entity())
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val unavailable = failure is SecurityException || failure is ExternalFolderException && failure.code == "UNAVAILABLE"
            if (unavailable && mount.isUserSkills) fail("UNAVAILABLE", "用户级技能目录不可用，请检查应用存储")
            if (unavailable && mount.isWorkspaceRoot) {
                val reason = (failure as? ExternalFolderException)?.message ?: "目录访问被拒绝"
                fail("UNAVAILABLE", "当前工作区不可用（$reason），不会改用会话私有工作区；请检查目录与授权，或在会话设置中更换工作区")
            }
            if (unavailable) {
                dao.update(mount.copy(unavailableReason = "授权已失效、提供商不可用或存储已卸载，请重新授权").entity())
                fail("UNAVAILABLE", "挂载不可用，请重新授权或检查存储连接")
            }
            throw failure
        }
    }
    private suspend fun sourceBackend(mount: ExternalMount, sessionId: String? = null): ExternalFolderBackend {
        fun guarded(backend: ExternalFolderBackend, physicalRoot: File? = null): ExternalFolderBackend {
            val startupGuard = if (sessionId == null || ExternalMountPaths.isLibraryScope(sessionId)) backend else com.promenar.infinity.startup.StartupGuardedExternalFolderBackend(backend, sessionId) { physicalRoot }
            return if (mount.readOnly || mount.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE) ReadOnlyExternalFolderBackend(startupGuard) else startupGuard
        }
        currentCoroutineContext()[com.promenar.infinity.data.branch.BranchLinuxWorkspaceOperation]
            ?.takeIf { sessionId != null && !ExternalMountPaths.isLibraryScope(sessionId) && it.sessionId == sessionId && it.mount.id == mount.id }
            ?.let { return guarded(it.backend, it.baseRoot) }
        if (mount.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE) {
            if (sessionId != null && !knowledgeBaseAllowed(sessionId, mount.sourceId)) {
                fail("ACCESS_DENIED", "当前会话的资料范围不允许读取该知识库挂载")
            }
            // 知识库标识是 FileEntry 根 UUID，与自选目录的 WorkspaceEntity.id 不同。
            val root = knowledgeBaseRoot(mount.sourceId)
            return guarded(backendOverride?.invoke(mount) ?: RealPathExternalFolderBackend(root), root)
        }
        if (mount.sourceType == ExternalMountPaths.WORKSPACE_SOURCE) {
            val resolver = workspaces ?: fail("UNAVAILABLE", "工作区不可用")
            return when (val current = resolver.resolveWorkspace(mount.sourceId)) {
                is SessionWorkspace.Directory -> guarded(backendOverride?.invoke(mount) ?: resolver.backend(current), current.root)
                is SessionWorkspace.Unavailable -> fail("UNAVAILABLE", current.reason)
                SessionWorkspace.Private -> fail("UNAVAILABLE", "工作区不可用")
            }
        }
        backendOverride?.invoke(mount)?.let { return guarded(it, File(mount.sourceId).takeIf { mount.sourceType == "real_path" }) }
        if (mount.isUserSkills) {
            if (!skillsDirectory.isDirectory && !skillsDirectory.mkdirs()) fail("UNAVAILABLE", "无法创建用户级技能目录")
            return guarded(RealPathExternalFolderBackend(skillsDirectory))
        }
        val backend = when (mount.sourceType) {
            "saf_tree" -> {
                val uri = Uri.parse(mount.sourceId)
                val grant = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri }
                if (grant?.isReadPermission != true || !grant.isWritePermission) fail("UNAVAILABLE", "文件夹授权已失效")
                SafExternalFolderBackend(context.contentResolver, uri)
            }
            "real_path" -> RealPathMountSupport.createBackend(context, mount.sourceId)
            else -> fail("UNSUPPORTED", "不支持的挂载来源")
        }
        return guarded(backend, File(mount.sourceId).takeIf { mount.sourceType == "real_path" })
    }
    private suspend fun knowledgeBaseRoot(rootUuid: String): File {
        if (rootUuid !in knowledgeBaseRoots()) fail("UNAVAILABLE", "根目录不属于可用知识库")
        val root = workspace.getByUuid(rootUuid, rootUuid) ?: fail("UNAVAILABLE", "知识库工作区根不可用")
        if (rootUuid.isBlank() || root.uuid != rootUuid || root.workspaceRootUuid != rootUuid || root.parentUuid != null ||
            !root.isDirectory || root.materializedPath != "/" || root.inRecycleBin || root.physicalRootPath.isBlank()
        ) fail("UNAVAILABLE", "知识库工作区根身份无效")
        val physicalRoot = File(root.physicalRootPath)
        if (!physicalRoot.isAbsolute) fail("UNAVAILABLE", "知识库工作区根路径无效")
        return physicalRoot
    }
    private fun takeGrant(uri: Uri, flags: Int) {
        val read = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val write = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        if (flags and read == 0 || flags and write == 0) fail("READ_ONLY", "提供商未授予所需的读写权限")
        val permissions = context.contentResolver.persistedUriPermissions
        if (permissions.size >= 512 && permissions.none { it.uri == uri }) fail("GRANT_LIMIT", "持久文件夹授权已达上限，请移除不再使用的挂载")
        context.contentResolver.takePersistableUriPermission(uri, flags and (read or write))
    }
    private suspend fun releaseUnused(source: String) {
        if (dao.all().any { it.sourceType == "saf_tree" && it.sourceId == source }) return
        val uri = Uri.parse(source)
        val held = context.contentResolver.persistedUriPermissions.firstOrNull { it.uri == uri } ?: return
        val flags = (if (held.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (held.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        try { context.contentResolver.releasePersistableUriPermission(uri, flags) } catch (_: SecurityException) { /* 已撤销的授权无需重复释放。 */ }
    }
    private suspend fun checkAlias(mount: ExternalMount) {
        if (dao.all().any { ExternalMountPaths.conflicts(mount, it.model()) }) fail("CONFLICT", "别名在会话可见范围内已存在")
    }
    private fun newMount(alias: String, type: String, source: String, session: String) = ExternalMount(
        UUID.randomUUID().toString(), ExternalMountPaths.validateAlias(alias), type, source,
        session, System.currentTimeMillis(),
    )
    private fun requireSession(sessionId: String) {
        if (sessionId.isBlank()) fail("INVALID_SESSION", "新增挂载必须指定会话")
    }
    private fun requireDirectory(entry: ExternalFolderEntry?) {
        if (entry?.isDirectory != true) fail("UNAVAILABLE", "来源文件夹不可用")
    }
    private fun fail(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
}

private fun ExternalMountEntity.model() = ExternalMount(id, alias, sourceType, sourceId, scopeKey, createdAt, unavailableReason,
    readOnly = readOnly || sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE)
private fun ExternalMount.entity() = ExternalMountEntity(id, alias, sourceType, sourceId, sessionId, readOnly, createdAt, unavailableReason)

internal fun externalSnapshotMime(name: String): String = com.promenar.infinity.share.core.AndroidSafImportRequestFactory.resolveMime(null, name)
