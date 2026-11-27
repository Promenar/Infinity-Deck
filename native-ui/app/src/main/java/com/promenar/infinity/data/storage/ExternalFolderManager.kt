package com.promenar.infinity.data.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.promenar.infinity.data.knowledge.KnowledgeAccessTarget
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.ExternalSnapshotEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import com.promenar.infinity.data.workspace.SessionWorkspace
import com.promenar.infinity.data.workspace.SessionWorkspaceResolver
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.data.repository.WorkspaceTextContentPolicy
import com.promenar.infinity.data.repository.WorkspaceTextOperation
import com.promenar.infinity.ui.chat.manager.skills.PrivateWorkspaceTransferBackend
import com.promenar.infinity.domain.storage.*
import com.promenar.infinity.recording.LOCAL_RECORDING_MIME
import com.promenar.infinity.recording.recordingLibraryDocumentLock
import com.promenar.infinity.startup.StartupWriteBarrier
import java.io.File
import java.nio.charset.CharacterCodingException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ensureActive
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
    private val fileOperations: IFileOperationRepository? = null,
    private val recordingAudioFile: ((String) -> File?)? = null,
    private val onRecordingDocumentsDeleted: (List<String>) -> Unit = {},
    private val onRecordingDocumentSaved: (suspend (docId: String, content: String, newHash: String) -> Unit)? = null,
    private val sftpBackendFactory: ((ExternalMount, SftpRemoteMountSpec) -> ExternalFolderBackend)? = null,
    private val sftpHostExists: (suspend (String) -> Boolean)? = null,
    private val sftpProbe: (suspend (String, String) -> SftpRemoteProbeResult)? = null,
    private val sftpBrowse: (suspend (String, String) -> List<SftpRemoteDirEntry>)? = null,
) : ExternalWorkspaceAccess {
    /** 兼容已有的尾随后端替身回调。 */
    constructor(
        context: Context,
        dao: ExternalMountDao,
        workspace: IWorkspaceRepository,
        indexEvents: FileIndexEventSink,
        workspaces: SessionWorkspaceResolver? = null,
        userSkillsDirectory: File? = null,
        knowledgeBaseRoots: suspend () -> Set<String> = { emptySet() },
        knowledgeBaseOwnerRoot: suspend (String) -> String? = { null },
        knowledgeBaseAllowed: suspend (String, String) -> Boolean = { _, _ -> false },
        backendOverride: (ExternalMount) -> ExternalFolderBackend?,
    ) : this(
        context, dao, workspace, indexEvents, workspaces, userSkillsDirectory, knowledgeBaseRoots,
        knowledgeBaseOwnerRoot, knowledgeBaseAllowed, backendOverride, fileOperations = null,
    )

    private val mutex = Mutex()
    private val versions = File(context.noBackupFilesDir, "external-versions")
    private var versionGeneration = 0L
    private fun skillsMount(sessionId: String) = ExternalMount(
        ExternalMountPaths.SKILLS_MOUNT_ID, ExternalMountPaths.SKILLS_ALIAS, ExternalMountPaths.SKILLS_SOURCE,
        "agent_skills", sessionId, createdAt = 0,
    )
    // filesDir 位于 /data/user/0 这一系统符号链接下，目录后端逐级拒绝符号链接，须先取真实路径。
    private val skillsDirectory by lazy { (userSkillsDirectory ?: File(context.filesDir, "agent_skills")).canonicalFile }
    override fun observeMounts(sessionId: String) =
        if (ExternalMountPaths.isLibraryScope(sessionId)) dao.observeVisible(sessionId).map { it.map(ExternalMountEntity::model) }
        else combine(dao.observeVisible(sessionId), dao.observeVisible(ExternalMountPaths.LIBRARY_SCOPE)) { rows, library ->
            val sources = library.associate { it.id to it.model() }
            rows.map { effectiveVisibleMount(it.model(), sources::get) }
        }
    override suspend fun visible(sessionId: String) = withContext(Dispatchers.IO) {
        // 历史保留别名仍可在管理入口移除，但不作为工具可达路径列出。
        val library = ExternalMountPaths.isLibraryScope(sessionId)
        val models = dao.visible(sessionId).filterNot { it.alias == ExternalMountPaths.SKILLS_ALIAS }
            .filter { !library || ExternalMountPaths.isAttachableLibrarySource(it.sourceType) }
            .map { effectiveVisibleMount(it.model()) { id -> dao.get(id)?.model() } } +
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
            knowledgeBaseRoot(rootUuid)
            requireKnowledgeAccess(sessionId, rootUuid)
            val mount = newMount(alias, ExternalMountPaths.KNOWLEDGE_BASE_SOURCE, rootUuid, sessionId).copy(readOnly = true)
            dao.visible(sessionId).firstOrNull {
                it.model().isKnowledgeBaseSource && it.sourceId == rootUuid
            }?.let { existing ->
                val current = existing.model()
                if (!current.readOnly) fail("CONFLICT", "知识库已拥有读写授权，请通过授权流程变更档位")
                requireDirectory(sourceBackend(current, sessionId).stat("/"))
                return@withLock current
            }
            checkAlias(mount)
            requireDirectory(sourceBackend(mount, sessionId).stat("/"))
            requireKnowledgeAccess(sessionId, rootUuid)
            dao.insert(mount.entity())
            mount
        } }

    /** 在管理器写锁内复核整批目标，保留原挂载身份并补偿中途失败。 */
    internal suspend fun grantKnowledgeBaseAccess(
        sessionId: String, targets: List<KnowledgeAccessTarget>,
    ): List<ExternalMount> = withContext(Dispatchers.IO) { mutex.withLock {
        requireSession(sessionId)
        if (ExternalMountPaths.isLibraryScope(sessionId)) fail("INVALID_SESSION", "知识库挂载必须属于会话")
        val visible = dao.visible(sessionId).map(ExternalMountEntity::model)
        val changes = targets.map { target ->
            requireKnowledgeAccess(sessionId, target.rootUuid)
            val matching = visible.filter { it.isKnowledgeBaseSource && it.sourceId == target.rootUuid }
            val existing = matching.singleOrNull()
            if (matching.size > 1 || (existing != null) != target.alreadyMounted ||
                existing?.id != target.mountedId || existing?.sourceType != target.mountedSourceType ||
                existing?.readOnly != target.mountedReadOnly || existing?.let { it.alias != target.alias || it.sessionId != sessionId } == true
            ) fail("APPROVAL_TARGET_CHANGED", "知识库挂载已变化，请重新申请授权")
            val source = if (target.readOnly) ExternalMountPaths.KNOWLEDGE_BASE_SOURCE else ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE
            val mount = (existing ?: newMount(target.alias, source, target.rootUuid, sessionId)).copy(
                sourceType = source, readOnly = target.readOnly, unavailableReason = null,
            )
            checkAlias(mount)
            requireDirectory(sourceBackend(mount, sessionId).stat("/"))
            existing to mount
        }
        changes.forEach { (_, mount) -> requireKnowledgeAccess(sessionId, mount.sourceId) }
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            val applied = mutableListOf<Pair<ExternalMount?, ExternalMount>>()
            try {
                for ((existing, mount) in changes) {
                    if (existing == mount) continue
                    if (existing == null) dao.insert(mount.entity()) else dao.update(mount.entity())
                    applied += existing to mount
                }
            } catch (failure: Exception) {
                val rollbackFailures = applied.asReversed().mapNotNull { (existing, mount) ->
                    try {
                        if (existing == null) dao.delete(mount.id) else dao.update(existing.entity())
                        null
                    } catch (rollback: Exception) {
                        rollback
                    }
                }
                if (rollbackFailures.isNotEmpty()) {
                    throw ExternalFolderException("RECOVERY_REQUIRED", "知识库授权批次未能完全恢复，请检查会话挂载档位")
                        .apply { addSuppressed(failure); rollbackFailures.forEach(::addSuppressed) }
                }
                throw failure
            }
            changes.map { (_, mount) -> mount }
        }
    } }

    override suspend fun addSftpRemote(alias: String, sessionId: String, spec: SftpRemoteMountSpec, readOnly: Boolean) =
        withContext(Dispatchers.IO) { mutex.withLock {
            requireSession(sessionId)
            val direct = directSftpSpec(spec)
            val mount = newMount(alias, SFTP_REMOTE_SOURCE_TYPE, SftpRemoteMountSpec.encode(direct), sessionId)
                .copy(readOnly = readOnly)
            checkAlias(mount)
            dao.insert(mount.entity())
            mount
        } }

    override suspend fun setMountReadOnly(mountId: String, readOnly: Boolean) = withContext(Dispatchers.IO) { mutex.withLock {
        val old = dao.get(mountId)?.model() ?: fail("NOT_FOUND", "挂载点不存在")
        if (!old.isSftpRemoteSource) fail("UNSUPPORTED", "该来源的挂载权限由其授权管理")
        val updated = old.copy(readOnly = readOnly)
        val effective = effectiveSftpMount(updated)
        dao.update(updated.entity())
        effective
    } }

    override suspend fun updateSftpRemote(mountId: String, alias: String, spec: SftpRemoteMountSpec, readOnly: Boolean) =
        withContext(Dispatchers.IO) { mutex.withLock {
            val old = dao.get(mountId)?.model() ?: fail("NOT_FOUND", "挂载点不存在")
            if (!old.isSftpRemoteSource) fail("UNSUPPORTED", "该挂载点不是 SFTP 来源")
            val oldSpec = sftpSpec(old)
            val updatedSpec = if (oldSpec.libraryMountId != null) {
                if (spec.normalized() != oldSpec) fail("CONFLICT", "请在资料页修改远程来源")
                oldSpec
            } else directSftpSpec(spec)
            val mount = old.copy(
                alias = ExternalMountPaths.validateAlias(alias), sourceId = SftpRemoteMountSpec.encode(updatedSpec),
                readOnly = readOnly, unavailableReason = null,
            )
            checkAlias(mount)
            val effective = effectiveSftpMount(mount)
            dao.update(mount.entity())
            effective
        } }

    override suspend fun probeSftpRemote(hostAlias: String, remotePath: String): SftpRemoteProbeResult = withContext(Dispatchers.IO) {
        val spec = SftpRemoteMountSpec(hostAlias, remotePath).normalized()
        sftpProbe?.invoke(spec.hostAlias, spec.rootPath) ?: fail("UNSUPPORTED", "未配置 SFTP 探测")
    }

    override suspend fun browseSftpDirectories(hostAlias: String, remotePath: String): List<SftpRemoteDirEntry> = withContext(Dispatchers.IO) {
        val spec = SftpRemoteMountSpec(hostAlias, remotePath).normalized()
        sftpBrowse?.invoke(spec.hostAlias, spec.rootPath) ?: fail("UNSUPPORTED", "未配置 SFTP 浏览")
    }

    private fun directSftpSpec(spec: SftpRemoteMountSpec): SftpRemoteMountSpec {
        if (spec.libraryMountId != null) fail("INVALID_PATH", "请通过附挂接口引用资料页挂载")
        return spec.normalized()
    }

    private fun sftpSpec(mount: ExternalMount): SftpRemoteMountSpec =
        SftpRemoteMountSpec.decode(mount.sourceId) ?: fail("INVALID_MOUNT_SPEC", "远程挂载配置无效")

    private suspend fun effectiveSftpMount(mount: ExternalMount): ExternalMount =
        effectiveSftpMount(mount) { id -> dao.get(id)?.model() }

    private suspend fun effectiveVisibleMount(mount: ExternalMount, source: suspend (String) -> ExternalMount?): ExternalMount {
        if (!mount.isSftpRemoteSource) return mount
        return try { effectiveSftpMount(mount, source) } catch (failure: ExternalFolderException) {
            mount.copy(readOnly = true, unavailableReason = failure.message)
        }
    }

    private suspend fun effectiveSftpMount(mount: ExternalMount, lookup: suspend (String) -> ExternalMount?): ExternalMount {
        val spec = sftpSpec(mount)
        val sourceId = spec.libraryMountId ?: return mount
        val source = lookup(sourceId)
        if (source == null || !source.isSftpRemoteSource || !ExternalMountPaths.isLibraryScope(source.sessionId)) {
            fail("LIBRARY_SOURCE_MISSING", "资料页中的远程来源已移除，请重新附挂")
        }
        if (SftpRemoteMountSpec.decode(source.sourceId) != spec.copy(libraryMountId = null)) {
            fail("LIBRARY_SOURCE_CHANGED", "资料页中的远程来源已变更，请重新附挂")
        }
        return mount.copy(readOnly = mount.readOnly || source.readOnly)
    }

    private suspend fun updateSftpAvailability(mount: ExternalMount, reason: String?) {
        mutex.withLock {
            val current = dao.get(mount.id)?.model() ?: return@withLock
            if (current == mount && current.unavailableReason != reason) {
                dao.update(current.copy(unavailableReason = reason).entity())
            }
        }
    }

    override suspend fun attachLibraryMount(sessionId: String, libraryMountId: String, alias: String?) =
        withContext(Dispatchers.IO) { mutex.withLock {
            requireSession(sessionId)
            if (ExternalMountPaths.isLibraryScope(sessionId)) fail("INVALID_SESSION", "资料页不能引用自身的挂载")
            val source = dao.get(libraryMountId) ?: fail("NOT_FOUND", "资料页挂载不存在")
            if (!ExternalMountPaths.isLibraryScope(source.scopeKey) || !ExternalMountPaths.isAttachableLibrarySource(source.sourceType)) {
                fail("UNSUPPORTED", "只能引用资料页中的外部文件夹")
            }
            val remote = source.model().isSftpRemoteSource
            val sourceId = if (remote) SftpRemoteMountSpec.encode(sftpSpec(source.model()).copy(libraryMountId = source.id))
                else source.sourceId
            if (!remote) requireDirectory(sourceBackend(source.model()).stat("/"))
            dao.visible(sessionId).firstOrNull { it.sourceType == source.sourceType && it.sourceId == sourceId }
                ?.let { return@withLock if (remote) effectiveSftpMount(it.model()) else it.model() }
            val mount = newMount(alias ?: source.alias, source.sourceType, sourceId, sessionId).copy(readOnly = source.readOnly)
            checkAlias(mount)
            dao.insert(mount.entity())
            mount
        } }

    override suspend fun libraryMountReferences(libraryMountId: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = dao.get(libraryMountId) ?: fail("NOT_FOUND", "资料页挂载不存在")
            if (!ExternalMountPaths.isLibraryScope(source.scopeKey) || !ExternalMountPaths.isAttachableLibrarySource(source.sourceType)) {
                fail("UNSUPPORTED", "只能查询资料页中的外部文件夹")
            }
            dao.all().count { it.scopeKey.isNotBlank() && !ExternalMountPaths.isLibraryScope(it.scopeKey) &&
                it.sourceType == source.sourceType && if (source.model().isSftpRemoteSource) {
                    SftpRemoteMountSpec.decode(it.sourceId)?.libraryMountId == source.id
                } else it.sourceId == source.sourceId }
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
            val current = if (mount.isSftpRemoteSource) dao.get(mount.id)?.model() ?: return@forEach else mount
            try {
                access(sessionId, ExternalMountPaths.virtualPath(current, "/")) { _, backend, _ ->
                    requireDirectory(backend.stat("/"))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (current.isSftpRemoteSource) updateSftpAvailability(current, sftpAvailabilityMessage(failure))
            }
        }
    }

    /** 传输入队和发布可通过稳定挂载 ID 重新校验范围、引用和读写能力。 */
    suspend fun requireSftpTransferMount(mountId: String, scopeKey: String): Pair<ExternalMount, SftpRemoteMountSpec> =
        withContext(Dispatchers.IO) {
            val mount = dao.get(mountId)?.model()?.takeIf { it.sessionId == scopeKey && it.isSftpRemoteSource }
                ?: fail("NOT_FOUND", "远程挂载已移除或不属于当前范围")
            val effective = effectiveSftpMount(mount)
            val spec = sftpSpec(effective)
            if (sftpHostExists?.invoke(spec.hostAlias) == false) fail("SSH_HOST_MISSING", "SSH 主机配置缺失")
            effective to spec
        }

    override suspend fun list(sessionId: String, virtualPath: String): List<ExternalFolderEntry> = withContext(Dispatchers.IO) {
        if (!branchWorkspacePaths(sessionId) && ExternalFolderPaths.normalize(virtualPath) == "/mnt") return@withContext visible(sessionId).filterNot { it.isUserSkills }.map {
            ExternalFolderEntry("/mnt/${it.alias}", it.alias, true, 0, it.createdAt)
        }
        access(sessionId, virtualPath) { mount, backend, relative -> visibleChildren(mount, relative, backend.list(relative)) }
    }

    override suspend fun read(sessionId: String, virtualPath: String, maxBytes: Long): ByteArray = withContext(Dispatchers.IO) {
        val generation = versionGeneration()
        access(sessionId, virtualPath) { mount, backend, path -> backend.read(path, maxBytes).also { rememberRead(mount, path, it, generation) } }
    }

    // 流式读取不记录版本快照：快照只保留 1 MiB 以内的小文件，大文件复制走这里。
    override suspend fun <T> readStream(sessionId: String, virtualPath: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T =
        withContext(Dispatchers.IO) { access(sessionId, virtualPath) { _, backend, path -> backend.readStream(path, maxBytes, consume) } }

    override suspend fun entry(sessionId: String, path: String): FileEntry? = entry(sessionId, path, fillHash = true)

    internal suspend fun statEntry(sessionId: String, path: String): FileEntry? = entry(sessionId, path, fillHash = false)

    private suspend fun entry(sessionId: String, path: String, fillHash: Boolean): FileEntry? = withContext(Dispatchers.IO) {
        val generation = versionGeneration()
        if (!branchWorkspacePaths(sessionId) && ExternalFolderPaths.normalize(path) == "/mnt") return@withContext FileEntry(
            ExternalMountPaths.ROOT_UUID, "", null, "mnt", "", isDirectory = true,
            physicalRootPath = "", materializedPath = "/mnt", createdAt = 0, updatedAt = 0,
        )
        access(sessionId, path) { mount, backend, relative ->
            val stat = backend.stat(relative) ?: return@access null
            val hash = stat.sha256 ?: if (fillHash && !stat.isDirectory && stat.size <= 1024L * 1024) {
                try { externalSha256(backend.read(relative, 1024L * 1024).also { rememberRead(mount, relative, it, generation) }) }
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
                mimeType = if (mount.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE && !stat.isDirectory) knowledgeEntry(mount, relative)?.mimeType else null,
                sizeBytes = stat.size, isDirectory = stat.isDirectory, physicalRootPath = "",
                materializedPath = path, createdAt = mount.createdAt, updatedAt = stat.lastModified,
            )
        }
    }

    override suspend fun write(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean): FileEntry =
        withContext(Dispatchers.IO) {
            val bound = writeReceipt(sessionId, path, bytes, expectedSha256, createOnly)
            bound.recording?.let { return@withContext it }
            val receipt = bound.receipt
            val mount = bound.mount
            val relative = bound.relativePath
            FileEntry(
                ExternalMountPaths.uuid(mount, relative), parentUuid = ExternalMountPaths.uuid(mount, relative.substringBeforeLast('/').ifEmpty { "/" }),
                name = receipt.name, hash = externalSha256(bytes), sizeBytes = bytes.size.toLong(),
                physicalRootPath = "", materializedPath = path, createdAt = mount.createdAt, updatedAt = receipt.lastModified,
            )
        }

    private data class BoundWrite(
        val mount: ExternalMount,
        val relativePath: String,
        val receipt: ExternalFolderEntry,
        val recording: FileEntry? = null,
    )

    internal suspend fun isWritableKnowledgeRecording(sessionId: String, virtualPath: String): Boolean = withContext(Dispatchers.IO) {
        if (fileOperations == null || ExternalMountPaths.parse(virtualPath) == null) return@withContext false
        val (mount, relative) = resolve(sessionId, virtualPath)
        mount.isKnowledgeBaseSource && knowledgeBaseAllowed(sessionId, mount.sourceId) && recordingEntry(mount, relative) != null
    }

    private suspend fun knowledgeEntry(mount: ExternalMount, path: String): FileEntry? =
        workspace.getByMaterializedPath(mount.sourceId, path)?.takeIf {
            it.workspaceRootUuid == mount.sourceId && it.materializedPath == path && !it.inRecycleBin
        }

    private suspend fun recordingEntry(mount: ExternalMount, relative: String): FileEntry? =
        if (mount.isKnowledgeBaseSource && relative.endsWith(".md", ignoreCase = true))
            knowledgeEntry(mount, relative)?.takeIf(FileEntry::isActiveRecording) else null

    internal suspend fun requireKnowledgeMutation(sessionId: String, path: String, recordingEdit: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val (mount, relative) = resolve(sessionId, path)
            requireKnowledgeAccess(sessionId, mount.sourceId)
            rejectRecordingAudio(mount, relative)
            val recording = recordingEdit && fileOperations != null && recordingEntry(mount, relative) != null
            if (!recording) mount.requireWritable()
            requireKnowledgeWritable(sessionId, mount, relative)
            recording
        }

    internal suspend fun isKnowledgePathWritable(sessionId: String, path: String): Boolean = try {
        requireKnowledgeMutation(sessionId, path, recordingEdit = true)
        true
    } catch (failure: ExternalFolderException) {
        if (failure.code !in setOf("READ_ONLY", "ACCESS_DENIED")) throw failure
        false
    } catch (_: com.promenar.infinity.startup.StartupIsolationException) {
        false
    }

    private suspend fun rejectRecordingAudio(mount: ExternalMount, relative: String) {
        if (!relative.endsWith(".wav", ignoreCase = true)) return
        val reader = knowledgeReader(mount, knowledgeDisk(mount, knowledgeBaseRoot(mount.sourceId)))
        if (reader is KnowledgeBaseRecordingBackend && reader.isVirtualAudio(relative)) {
            fail("READ_ONLY", "录音原始音频为只读")
        }
    }

    private suspend fun requireKnowledgeAccess(sessionId: String, rootId: String) {
        if (!knowledgeBaseAllowed(sessionId, rootId)) fail("ACCESS_DENIED", "当前会话的资料范围不允许访问该知识库挂载")
    }

    private suspend fun requireKnowledgeWritable(sessionId: String, mount: ExternalMount, relative: String) {
        requireKnowledgeAccess(sessionId, mount.sourceId)
        val root = knowledgeBaseRoot(mount.sourceId)
        StartupWriteBarrier.requireWritable(sessionId = sessionId, rootId = mount.sourceId, path = root.path)
        StartupWriteBarrier.requireWritable(sessionId = sessionId, path = File(root, relative.removePrefix("/")).canonicalPath)
    }

    private suspend fun writeReceipt(sessionId: String, path: String, bytes: ByteArray, expectedSha256: String?, createOnly: Boolean, bindings: MutableMap<String, String>? = null): BoundWrite =
        withContext(Dispatchers.IO) { mutex.withLock {
            val target = resolve(sessionId, path)
            if (target.first.isKnowledgeBaseSource) requireKnowledgeAccess(sessionId, target.first.sourceId)
            val files = fileOperations
            val recording = if (!createOnly && files != null) recordingEntry(target.first, target.second) else null
            access(sessionId, path, bindings, writing = recording == null, resolved = target) { mount, backend, relative ->
                if (relative == "/") fail("INVALID_PATH", "不能覆盖挂载根目录")
                if (recording != null && files != null) {
                    return@access writeRecording(sessionId, mount, path, relative, recording.uuid, bytes, expectedSha256, files)
                }
                val receipt = backend.write(relative, bytes, expectedSha256, createOnly).copy(path = path, sha256 = externalSha256(bytes))
                remember(mount.id, relative, bytes)
                BoundWrite(mount, relative, receipt)
            }
        } }

    private suspend fun writeRecording(
        sessionId: String,
        mount: ExternalMount,
        path: String,
        relative: String,
        documentId: String,
        bytes: ByteArray,
        expectedSha256: String?,
        files: IFileOperationRepository,
    ): BoundWrite = recordingLibraryDocumentLock(documentId).withLock {
        val entry = recordingEntry(mount, relative)?.takeIf { it.uuid == documentId }
            ?: fail("CONFLICT", "录音文档位置或身份已变化，请重新读取目标")
        requireKnowledgeWritable(sessionId, mount, relative)
        val content = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            fail("INVALID_TEXT", "录音文档必须是有效 UTF-8 文本")
        }
        WorkspaceTextContentPolicy().validate(entry.name, entry.mimeType, bytes, WorkspaceTextOperation.WRITE)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            when (val result = files.writeFileAtomicExact(mount.sourceId, entry.uuid, content, sessionId, expectedSha256 ?: entry.hash)) {
                is WriteResult.Success -> {
                    remember(mount.id, relative, bytes)
                    onRecordingDocumentSaved?.invoke(entry.uuid, content, result.newHash)
                    if (!result.indexQueued) fail("INDEX_PENDING", "录音整理稿已保存并同步工作台，索引等待重试。sha256: ${result.newHash}")
                    val saved = workspace.getByUuid(mount.sourceId, entry.uuid)
                        ?: fail("NOT_FOUND", "录音文档写入后不可用")
                    BoundWrite(mount, relative, ExternalFolderEntry(path, saved.name, false, saved.sizeBytes,
                        saved.updatedAt, result.newHash, saved.uuid), saved)
                }
                is WriteResult.Conflict -> fail("CONFLICT", result.message)
                WriteResult.NotFound -> fail("NOT_FOUND", "录音文档不存在")
            }
        }
    }

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
        namespace(sessionId).filter { it.isKnowledgeBaseSource && it.sourceId == workspaceRootUuid }
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

    internal suspend fun moveKnowledgeDocument(sessionId: String, source: String, target: String, expectedHash: String?) =
        withContext(Dispatchers.IO) { mutex.withLock {
            access(sessionId, source, writing = true) { sourceMount, _, relative ->
                val (destinationMount, destination) = resolve(sessionId, target)
                if (sourceMount.id != destinationMount.id) fail("UNSUPPORTED", "资料库文档移动必须在同一知识库内")
                if (sourceMount.sourceType != ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE) fail("READ_ONLY", "知识库挂载为只读")
                val root = knowledgeBaseRoot(sourceMount.sourceId)
                managedKnowledgeBackend(sourceMount, sessionId, knowledgeReader(sourceMount, knowledgeDisk(sourceMount, root)))
                    .moveExact(relative, destination, expectedHash)
            }
        } }

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
            access(sessionId, path, bindings = bindings) { mount, backend, relative -> backend.read(relative, maxBytes).also { rememberRead(mount, relative, it, generation) } }
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
        if (mount.isKnowledgeBaseSource) {
            requireKnowledgeAccess(sessionId, mount.sourceId)
            if (relative.endsWith(".wav", ignoreCase = true)) return@withContext null
        }
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

    private fun rememberRead(mount: ExternalMount, path: String, bytes: ByteArray, generation: Long) {
        // 音频可通过流读取，版本缓存不留存原始录音的副本。
        if (mount.isKnowledgeBaseSource && path.endsWith(".wav", ignoreCase = true)) return
        remember(mount.id, path, bytes, generation)
    }

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
        if (ExternalMountPaths.isLibraryScope(sessionId) && !ExternalMountPaths.isAttachableLibrarySource(mount.sourceType)) fail("UNSUPPORTED", "资料页只支持外部文件夹")
        val bound = currentCoroutineContext()[ExternalMountOperationBindings]?.mountIds
        if (bound != null && bound[alias] != mount.id) fail("CONFLICT", "操作期间挂载身份发生变化，请重新读取目标")
        return mount to relative
    }
    private suspend fun <T> access(sessionId: String, path: String, bindings: MutableMap<String, String>? = null, writing: Boolean = false,
        resolved: Pair<ExternalMount, String>? = null, block: suspend (ExternalMount, ExternalFolderBackend, String) -> T): T {
        val (mount, relative) = resolved ?: resolve(sessionId, path)
        if (mount.isKnowledgeBaseSource) {
            requireKnowledgeAccess(sessionId, mount.sourceId)
            if (writing) rejectRecordingAudio(mount, relative)
        }
        if (writing) mount.requireWritable()
        if (bindings != null) synchronized(bindings) {
            val previous = bindings.putIfAbsent(mount.alias, mount.id)
            if (previous != null && previous != mount.id) fail("CONFLICT", "操作期间挂载身份发生变化，已取消后续访问")
        }
        try {
            val effective = if (mount.isSftpRemoteSource) effectiveSftpMount(mount) else mount
            if (writing && effective.isSftpRemoteSource) effective.requireWritable()
            val result = block(effective, sourceBackend(effective, sessionId), relative)
            if (mount.isSftpRemoteSource) {
                if (mount.unavailableReason != null) updateSftpAvailability(mount, null)
                return result
            }
            if (mount.unavailableReason != null && !mount.isWorkspaceRoot && !mount.isUserSkills && !mount.isKnowledgeBaseSource) dao.update(mount.copy(unavailableReason = null).entity())
            return result
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (mount.isSftpRemoteSource) {
                if (failure is ExternalFolderException && failure.code in setOf("SSH_HOST_MISSING", "LIBRARY_SOURCE_MISSING", "LIBRARY_SOURCE_CHANGED", "INVALID_MOUNT_SPEC")) {
                    updateSftpAvailability(mount, sftpAvailabilityMessage(failure))
                }
                throw failure
            }
            val unavailable = failure is SecurityException || failure is ExternalFolderException && failure.code == "UNAVAILABLE"
            if (unavailable && mount.isUserSkills) fail("UNAVAILABLE", "用户级技能目录不可用，请检查应用存储")
            if (unavailable && mount.isWorkspaceRoot) {
                val reason = (failure as? ExternalFolderException)?.message ?: "目录访问被拒绝"
                fail("UNAVAILABLE", "当前工作区不可用（$reason），不会改用会话私有工作区；请检查目录与授权，或在会话设置中更换工作区")
            }
            if (unavailable && !mount.isKnowledgeBaseSource) {
                dao.update(mount.copy(unavailableReason = "授权已失效、提供商不可用或存储已卸载，请重新授权").entity())
                fail("UNAVAILABLE", "挂载不可用，请重新授权或检查存储连接")
            }
            throw failure
        }
    }
    private fun sftpAvailabilityMessage(failure: Exception): String =
        (failure as? ExternalFolderException)?.message ?: "远程目录当前不可用，请刷新重试"

    private suspend fun sourceBackend(mount: ExternalMount, sessionId: String? = null): ExternalFolderBackend {
        fun guarded(backend: ExternalFolderBackend, physicalRoot: File? = null): ExternalFolderBackend {
            val startupGuard = if (sessionId == null || ExternalMountPaths.isLibraryScope(sessionId)) backend else com.promenar.infinity.startup.StartupGuardedExternalFolderBackend(backend, sessionId) { physicalRoot }
            return if (mount.readOnly || mount.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE) ReadOnlyExternalFolderBackend(startupGuard) else startupGuard
        }
        currentCoroutineContext()[com.promenar.infinity.data.branch.BranchLinuxWorkspaceOperation]
            ?.takeIf { sessionId != null && !ExternalMountPaths.isLibraryScope(sessionId) && it.sessionId == sessionId && it.mount.id == mount.id && !mount.isKnowledgeBaseSource }
            ?.let { return guarded(it.backend, it.baseRoot) }
        if (mount.isKnowledgeBaseSource) {
            if (sessionId != null) requireKnowledgeAccess(sessionId, mount.sourceId)
            val root = knowledgeBaseRoot(mount.sourceId)
            val disk = knowledgeDisk(mount, root)
            val reader = knowledgeReader(mount, disk)
            if (mount.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE) {
                val session = sessionId ?: fail("ACCESS_DENIED", "读写知识库必须绑定到获准会话")
                return guarded(managedKnowledgeBackend(mount, session, reader), root)
            }
            return guarded(reader, root)
        }
        if (mount.sourceType == ExternalMountPaths.WORKSPACE_SOURCE) {
            val resolver = workspaces ?: fail("UNAVAILABLE", "工作区不可用")
            return when (val current = resolver.resolveWorkspace(mount.sourceId)) {
                is SessionWorkspace.Directory -> guarded(backendOverride?.invoke(mount) ?: resolver.backend(current), current.root)
                is SessionWorkspace.Unavailable -> fail("UNAVAILABLE", current.reason)
                SessionWorkspace.Private -> fail("UNAVAILABLE", "工作区不可用")
            }
        }
        if (mount.isSftpRemoteSource) {
            val spec = sftpSpec(mount)
            if (sftpHostExists?.invoke(spec.hostAlias) == false) fail("SSH_HOST_MISSING", "SSH 主机配置缺失")
            val backend = sftpBackendFactory?.invoke(mount, spec) ?: backendOverride?.invoke(mount)
                ?: fail("UNSUPPORTED", "未配置 SFTP 挂载后端")
            return guarded(backend)
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
    private fun managedKnowledgeBackend(mount: ExternalMount, sessionId: String, reader: ExternalFolderBackend) =
        KnowledgeBaseManagedBackend(
            reader, PrivateWorkspaceTransferBackend(workspace, mount.sourceId, indexEvents),
            mount.sourceId, sessionId, workspace, fileOperations, indexEvents,
            find = { knowledgeEntry(mount, it) },
            checkWrite = {
                rejectRecordingAudio(mount, it)
                requireKnowledgeWritable(sessionId, mount, it)
            },
            onRecordingDocumentsDeleted = onRecordingDocumentsDeleted,
        )

    private fun knowledgeDisk(mount: ExternalMount, root: File): ExternalFolderBackend =
        backendOverride?.invoke(mount) ?: if (mount.sourceType == ExternalMountPaths.KNOWLEDGE_BASE_RW_SOURCE)
            PrivateWorkspaceTransferBackend(workspace, mount.sourceId) else RealPathExternalFolderBackend(root)

    private fun knowledgeReader(mount: ExternalMount, disk: ExternalFolderBackend): ExternalFolderBackend {
        if (disk is KnowledgeBaseRecordingBackend) return disk
        val audio = recordingAudioFile ?: return disk
        return KnowledgeBaseRecordingBackend(disk, mount.sourceId, workspace, audio)
    }

    private suspend fun knowledgeBaseRoot(rootUuid: String): File {
        if (rootUuid !in knowledgeBaseRoots()) fail("UNAVAILABLE", "根目录不属于可用知识库")
        val root = workspace.getByUuid(rootUuid, rootUuid) ?: fail("UNAVAILABLE", "知识库工作区根不可用")
        if (rootUuid.isBlank() || root.uuid != rootUuid || root.workspaceRootUuid != rootUuid || root.parentUuid != null ||
            !root.isDirectory || root.materializedPath != "/" || root.inRecycleBin || root.physicalRootPath.isBlank()
        ) fail("UNAVAILABLE", "知识库工作区根身份无效")
        val physicalRoot = File(root.physicalRootPath)
        if (!physicalRoot.isAbsolute || java.nio.file.Files.isSymbolicLink(physicalRoot.toPath())) {
            fail("UNAVAILABLE", "知识库工作区根路径无效")
        }
        return physicalRoot.canonicalFile
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

private fun FileEntry.isActiveRecording() = mimeType == LOCAL_RECORDING_MIME && !inRecycleBin && !isDirectory

/** 音频通过真实路径后端的只读检查读取，虚拟路径由知识库中的录音身份确定。 */
internal class KnowledgeBaseRecordingBackend(
    private val disk: ExternalFolderBackend,
    private val rootId: String,
    private val workspace: IWorkspaceRepository,
    private val audioFile: (String) -> File?,
    private val audioBackend: (File) -> ExternalFolderBackend = { RealPathExternalFolderBackend(it) },
) : ExternalFolderBackend by ReadOnlyExternalFolderBackend(disk) {
    private suspend fun recordings(parent: String): Sequence<FileEntry> {
        val parentId = if (parent == "/") rootId else workspace.getByMaterializedPath(rootId, parent)
            ?.takeIf { it.workspaceRootUuid == rootId && it.isDirectory && !it.inRecycleBin }?.uuid ?: return emptySequence()
        return workspace.observeChildren(rootId, parentId).first().asSequence()
            .filter { it.workspaceRootUuid == rootId && it.parentUuid == parentId && it.isActiveRecording() }
    }

    private fun FileEntry.wavName() = (if (name.endsWith(".md", ignoreCase = true)) name.dropLast(3) else name) + ".wav"

    private fun audioSourceFile(entry: FileEntry): File? = audioFile(entry.uuid)?.takeIf { file ->
        file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.parentFile?.let { parent -> parent.isDirectory && !java.nio.file.Files.isSymbolicLink(parent.toPath()) } == true
    }

    private fun source(entry: FileEntry) = audioSourceFile(entry)
        ?.let { audioBackend(it.parentFile!!.canonicalFile) to "/${it.name}" }

    private suspend fun virtualSource(path: String): Pair<ExternalFolderBackend, String>? {
        val name = path.substringAfterLast('/')
        if (!name.endsWith(".wav") || disk.stat(path) != null) return null
        return recordings(path.substringBeforeLast('/').ifEmpty { "/" }).filter { it.wavName() == name }.firstNotNullOfOrNull(::source)
    }

    internal suspend fun isVirtualAudio(path: String): Boolean = virtualSource(ExternalFolderPaths.normalize(path)) != null

    override suspend fun stat(path: String): ExternalFolderEntry? {
        val normalized = ExternalFolderPaths.normalize(path)
        val (backend, sourcePath) = virtualSource(normalized) ?: return disk.stat(normalized)
        return backend.stat(sourcePath)?.copy(path = normalized, name = normalized.substringAfterLast('/'))
    }

    override suspend fun list(path: String): List<ExternalFolderEntry> {
        val normalized = ExternalFolderPaths.normalize(path)
        val physical = disk.list(normalized)
        val names = physical.mapTo(mutableSetOf()) { it.name }
        val virtual = recordings(normalized).mapNotNull { entry ->
            val name = entry.wavName()
            if (name in names) return@mapNotNull null
            val file = audioSourceFile(entry) ?: return@mapNotNull null
            names.add(name)
            ExternalFolderEntry(normalized.trimEnd('/') + "/$name", name, false, file.length(), file.lastModified())
        }.toList()
        return (physical + virtual).sortedWith(compareByDescending<ExternalFolderEntry> { it.isDirectory }.thenBy { it.name })
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray {
        val normalized = ExternalFolderPaths.normalize(path)
        val (backend, sourcePath) = virtualSource(normalized) ?: (disk to normalized)
        return backend.read(sourcePath, maxBytes)
    }

    override suspend fun <T> readStream(path: String, maxBytes: Long, consume: suspend (java.io.InputStream) -> T): T {
        val normalized = ExternalFolderPaths.normalize(path)
        val (backend, sourcePath) = virtualSource(normalized) ?: (disk to normalized)
        return backend.readStream(sourcePath, maxBytes, consume)
    }
}

/** 受管资料库的变更经 Room 工作区事务，音频投影只提供读能力。 */
private class KnowledgeBaseManagedBackend(
    private val reader: ExternalFolderBackend,
    private val transfer: PrivateWorkspaceTransferBackend,
    private val rootId: String,
    private val sessionId: String,
    private val workspace: IWorkspaceRepository,
    private val files: IFileOperationRepository?,
    private val indexEvents: FileIndexEventSink,
    private val find: suspend (String) -> FileEntry?,
    private val checkWrite: suspend (String) -> Unit,
    private val onRecordingDocumentsDeleted: (List<String>) -> Unit,
) : ExternalFolderBackend by ReadOnlyExternalFolderBackend(reader) {
    override suspend fun availableBytes(path: String) = transfer.availableBytes(path)

    override suspend fun write(path: String, content: ByteArray, expectedSha256: String?, createOnly: Boolean): ExternalFolderEntry {
        val target = mutationPath(path)
        val current = find(target)
        if (current?.isDirectory == true) reject("IS_DIRECTORY", "目标是目录")
        if (current?.isActiveRecording() == true) reject("READ_ONLY", "录音整理稿须通过带版本的录音整理稿写入入口")
        WorkspaceTextContentPolicy().validate(target.substringAfterLast('/'), current?.mimeType, content, WorkspaceTextOperation.WRITE)
        if (createOnly) {
            if (current != null) reject("CONFLICT", "目标已存在")
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                val created = transfer.write(target, content, expectedSha256, true)
                if (!transfer.confirmCreated()) reject("INDEX_PENDING", "资料库文档已创建，索引等待重试")
                created
            }
        }
        val entry = current ?: reject("NOT_FOUND", "资料库文档不存在")
        val expected = expectedSha256 ?: reject("MISSING_VERSION", "资料库文档写入必须提供 expectedHash")
        val repository = files ?: reject("UNAVAILABLE", "资料库文件写入服务不可用")
        checkWrite(target)
        if (find(target)?.uuid != entry.uuid) reject("CONFLICT", "目标已移动或替换，请重新读取")
        return when (val result = repository.writeFileAtomicExact(rootId, entry.uuid, content.decodeToString(), sessionId, expected)) {
            is WriteResult.Success -> {
                if (!result.indexQueued) reject("INDEX_PENDING", "资料库文档已保存，索引等待重试。sha256: ${result.newHash}")
                ExternalFolderEntry(target, entry.name, false, content.size.toLong(), result.targetEpoch ?: entry.updatedAt, result.newHash, entry.uuid)
            }
            is WriteResult.Conflict -> reject("CONFLICT", result.message)
            WriteResult.NotFound -> reject("NOT_FOUND", "资料库文档不存在")
        }
    }

    override suspend fun createDirectory(path: String): ExternalFolderEntry {
        val target = mutationPath(path)
        if (find(target) != null) reject("CONFLICT", "目标已存在")
        currentCoroutineContext().ensureActive()
        return withContext(NonCancellable) {
            transfer.createDirectory(target).also { transfer.confirmCreated() }
        }
    }

    override suspend fun move(sourcePath: String, targetPath: String) = moveExact(sourcePath, targetPath, null)

    suspend fun moveExact(sourcePath: String, targetPath: String, expectedHash: String?) {
        val source = mutationPath(sourcePath)
        val target = mutationPath(targetPath)
        val entry = find(source) ?: reject("NOT_FOUND", "来源条目不存在")
        if (source == target) {
            if (expectedHash != null && (entry.isDirectory || entry.hash != expectedHash)) reject("CONFLICT", "expected_sha256 与来源不一致")
            return
        }
        val parent = find(target.substringBeforeLast('/').ifEmpty { "/" }) ?: reject("NOT_FOUND", "目标目录不存在")
        if (!parent.isDirectory) reject("NOT_DIRECTORY", "目标父项不是目录")
        if (find(target) != null) reject("CONFLICT", "目标已存在")
        if (entry.isActiveRecording() && !target.endsWith(".md", ignoreCase = true)) reject("INVALID_PATH", "录音整理稿须保留 .md 扩展名")
        if (entry.isDirectory && expectedHash != null) reject("INVALID_ARGUMENT", "目录移动不支持文件 SHA-256 条件")
        val name = target.substringAfterLast('/')
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            checkWrite(source)
            checkWrite(target)
            if (find(source)?.uuid != entry.uuid || find(target.substringBeforeLast('/').ifEmpty { "/" })?.uuid != parent.uuid) {
                reject("CONFLICT", "来源或目标目录已移动，请重新读取")
            }
            val result = try {
                if (entry.parentUuid == parent.uuid && expectedHash == null) workspace.rename(rootId, entry.uuid, name, entry.name)
                else workspace.relocate(rootId, entry.uuid, parent.uuid, name, entry.name, expectedHash)
            } catch (failure: IllegalStateException) {
                if (failure.message.orEmpty().contains("expected_sha256")) reject("CONFLICT", "expected_sha256 与来源不一致")
                throw failure
            }
            when (result) {
                is RenameResult.Success -> {
                    var pending = false
                    for (item in result.affectedTargets) {
                        try {
                            indexEvents.publish(FileIndexEvent.Changed(rootId, item.fileUuid, item.targetHash, item.targetEpoch))
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Exception) {
                            pending = true
                        }
                    }
                    if (pending) reject("INDEX_PENDING", "资料库条目已移动，部分索引等待重试")
                }
                is RenameResult.Conflict -> reject("CONFLICT", "来源名称已变化，请重新读取")
                RenameResult.NotFound -> reject("NOT_FOUND", "来源条目不存在")
            }
        }
    }

    override suspend fun delete(path: String, expectedSha256: String?) {
        val target = mutationPath(path)
        val entry = find(target) ?: reject("NOT_FOUND", "删除目标不存在")
        if (entry.isDirectory && expectedSha256 != null) reject("INVALID_ARGUMENT", "目录删除不支持文件 SHA-256 条件")
        val recordings = recordingIds(entry)
        currentCoroutineContext().ensureActive()
        withContext(NonCancellable) {
            checkWrite(target)
            if (find(target)?.uuid != entry.uuid) reject("CONFLICT", "删除目标已移动或替换，请重新读取")
            if (entry.isDirectory || (expectedSha256 == null && !entry.isActiveRecording())) {
                workspace.permanentDelete(rootId, entry.uuid)
            } else try {
                workspace.permanentDeleteIfHash(rootId, entry.uuid, expectedSha256 ?: entry.hash)
            } catch (failure: IllegalStateException) {
                if (failure.message.orEmpty().contains("expected_sha256")) reject("CONFLICT", "删除前来源已改变")
                throw failure
            }
            val removed = recordings.filter { workspace.getByUuid(rootId, it) == null }
            if (removed.isNotEmpty()) onRecordingDocumentsDeleted(removed)
        }
    }

    private suspend fun recordingIds(entry: FileEntry): List<String> {
        val pending = ArrayDeque(listOf(entry))
        val recordings = mutableListOf<String>()
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val item = pending.removeFirst()
            if (item.isActiveRecording()) recordings += item.uuid
            if (item.isDirectory) pending.addAll(workspace.observeChildren(rootId, item.uuid).first().filter {
                it.workspaceRootUuid == rootId && it.parentUuid == item.uuid && !it.inRecycleBin
            })
        }
        return recordings
    }

    private suspend fun mutationPath(path: String): String {
        val normalized = ExternalFolderPaths.normalize(path)
        if (normalized == "/") reject("INVALID_PATH", "不能变更知识库根目录")
        checkWrite(normalized)
        return normalized
    }

    private fun reject(code: String, message: String): Nothing = throw ExternalFolderException(code, message)
}

private fun ExternalMountEntity.model() = ExternalMount(id, alias, sourceType, sourceId, scopeKey, createdAt, unavailableReason,
    readOnly = readOnly || sourceType == ExternalMountPaths.KNOWLEDGE_BASE_SOURCE)
private fun ExternalMount.entity() = ExternalMountEntity(id, alias, sourceType, sourceId, sessionId, readOnly, createdAt, unavailableReason)

internal fun externalSnapshotMime(name: String): String = com.promenar.infinity.share.core.AndroidSafImportRequestFactory.resolveMime(null, name)
