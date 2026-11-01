package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.WorkspaceSeqDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationType
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import com.promenar.infinity.domain.repository.RenameIndexTarget
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.utils.InfinityLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.io.OutputStream
import com.promenar.infinity.data.session.SessionExecutionGate
import com.promenar.infinity.data.rag.DocumentReferenceExtractor
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import kotlinx.coroutines.CancellationException


interface WorkspaceDeleteBarrierLease {
    suspend fun awaitReady()
    suspend fun commit()
    suspend fun abort()
}

private object NoOpWorkspaceDeleteBarrierLease : WorkspaceDeleteBarrierLease {
    override suspend fun awaitReady() = Unit
    override suspend fun commit() = Unit
    override suspend fun abort() = Unit
}

enum class WorkspaceLifecycleErrorCode {
    ACTIVE_PERMANENT_DELETE_FORBIDDEN,
    CREATION_ROLLBACK_NOT_AUTHORIZED,
    TARGET_COLLISION,
}

class WorkspaceLifecycleException(
    val code: WorkspaceLifecycleErrorCode,
    val safeMessage: String,
) : IllegalStateException(safeMessage)

class WorkspaceRepository(
    private val dao: FileEntryDao,
    private val seqDao: WorkspaceSeqDao,
    private val defaultWorkspaceParent: File? = null,
    private val fileOps: WorkspaceFileOps = SecureWorkspaceFileOps(),
    private val insertCommitter: (suspend (FileEntry) -> Unit)? = null,
    private val updateCommitter: (suspend (List<FileEntry>) -> Unit)? = null,
    private val deleteCommitter: (suspend (String, List<String>) -> Unit)? = null,
    private val beforeDeleteCommitted: suspend (String, List<String>) -> WorkspaceDeleteBarrierLease =
        { _, _ -> NoOpWorkspaceDeleteBarrierLease },
    private val afterDeleteCommitted: suspend (String, List<String>) -> Unit = { _, _ -> },
    private val recycleCommitter: (suspend (String, List<FileEntry>, List<String>) -> Unit)? = null,
    private val restoreCommitter: (suspend (String, List<FileEntry>, List<RenameIndexTarget>) -> Unit)? = null,
    private val beforeRecycleCommitted: suspend (String, List<String>) -> WorkspaceDeleteBarrierLease =
        { _, _ -> NoOpWorkspaceDeleteBarrierLease },
    private val afterRecycleCommitted: suspend (String, List<String>) -> Unit = { _, _ -> },
    private val afterRestoreCommitted: suspend (String, List<RenameIndexTarget>) -> Unit = { _, _ -> },
    private val bulkDeleteSnapshotHook: suspend () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    private val executionGate: SessionExecutionGate? = null,
    private val mutationJournal: WorkspaceFileMutationJournal? = null,
    private val treeScanner: WorkspaceTreeScanner = SecureWorkspaceTreeScanner,
    private val indexEventSink: FileIndexEventSink = FileIndexEventSink.None,
    private val identityBaselines: WorkspaceIdentityBaselineStore? = null,
) : IWorkspaceRepository, WorkspaceCommandHost {
    @Volatile
    private var defaultParentIdentity: String? = null
    private val creationRollbackAuthorizations = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override suspend fun ensureSessionRoot(sessionId: String): FileEntry = withSessionAdmission(sessionId) {
        withContext(Dispatchers.IO) {
        val session = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        val path = session.workspacePath?.takeIf { it.isNotBlank() }
            ?: session.workspaceRootUuid?.let { rootUuid ->
                dao.getActiveByUuid(rootUuid, rootUuid)?.physicalRootPath
            }
            ?: defaultWorkspaceParent?.let { parent ->
                File(parent, Sha256Utils.hash(sessionId)).path
            }
            ?: throw IllegalStateException("Session workspace path is missing: $sessionId")
        ensureSessionRootInternal(sessionId, path)
        }
    }

    /** 已认领根的命令目录解析只核验根身份，不等待索引扫描持有的写锁。 */
    suspend fun sessionRootForCommand(sessionId: String): FileEntry = withSessionAdmission(sessionId) {
        withContext(Dispatchers.IO) {
            val session = dao.getSessionForRoot(sessionId) ?: throw NoSuchElementException("会话不存在")
            val root = session.workspaceRootUuid?.let { requireRoot(it) }
            if (root == null || root.hash.isBlank()) return@withContext ensureSessionRoot(sessionId)
            val path = File(session.workspacePath?.takeIf(String::isNotBlank) ?: root.physicalRootPath).absoluteFile.toPath().normalize()
            if (java.nio.file.Files.isSymbolicLink(path)) throw SecurityException("会话根目录不能为符号链接")
            val physical = path.toFile().canonicalFile
            validateClaimedRoot(root, physical)
            if (dao.countOtherSessionsForPhysicalRoot(physical.path, sessionId) != 0 || dao.countOtherSessionsForRoot(root.uuid, sessionId) != 0) {
                throw SecurityException("工作区根被多个会话共享")
            }
            fileOps.ensureRoot(physical.toPath(), initializeIdentity = false, expectedIdentity = root.hash)
            root
        }
    }

    /** 活跃命令使旧扫描失效；根身份恢复完成后才允许索引提交。 */
    suspend fun beginSessionCommand(sessionId: String): suspend () -> Unit {
        val root = sessionRootForCommand(sessionId)
        val path = File(root.physicalRootPath).toPath()
        val finish = WorkspaceExternalMutationCoordinator.begin(path)
        val identity = try { WorkspaceRootIdentityGuard.capture(path, fileOps.captureRootIdentityRestorer(path)) }
        catch (failure: Throwable) { withContext(NonCancellable) { finish() }; throw failure }
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return {
            if (closed.compareAndSet(false, true)) withContext(NonCancellable + Dispatchers.IO) {
                try { identity.restoreIfChanged() } finally { finish() }
            }
        }
    }

    override suspend fun ensureSessionRoot(
        sessionId: String,
        physicalRootPath: String,
    ): FileEntry = withSessionAdmission(sessionId) {
        withContext(Dispatchers.IO) { ensureSessionRootInternal(sessionId, physicalRootPath) }
    }

    private suspend fun ensureSessionRootInternal(sessionId: String, physicalRootPath: String): FileEntry {
        val declaredRootPath = File(physicalRootPath.trim()).absoluteFile.toPath().normalize()
        if (java.nio.file.Files.isSymbolicLink(declaredRootPath)) {
            throw SecurityException("Session workspace root cannot be a symbolic link")
        }
        val physicalRoot = declaredRootPath.toFile().canonicalFile
        val before = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(sessionId = sessionId, rootId = before.workspaceRootUuid, path = physicalRoot.path)
        val beforeRoot = before.workspaceRootUuid?.let { rootUuid ->
            val claimed = dao.getActiveByUuid(rootUuid, rootUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            validateClaimedRoot(claimed, physicalRoot)
            claimed
        }
        val action: suspend () -> FileEntry = action@{
        val session = dao.getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        if (dao.countOtherSessionsForPhysicalRoot(physicalRoot.path, sessionId) != 0) {
            throw SecurityException("工作区物理根已被其他 Session 使用")
        }
        val existingRoot = session.workspaceRootUuid?.let { rootUuid ->
            val claimed = dao.getActiveByUuid(rootUuid, rootUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            validateClaimedRoot(claimed, physicalRoot)
            claimed
        }
        var parentPath: java.nio.file.Path? = null
        var parentIdentity: String? = null
        if (defaultWorkspaceParent != null && physicalRoot.parentFile == defaultWorkspaceParent.canonicalFile) {
            parentPath = defaultWorkspaceParent.canonicalFile.toPath()
            val expectedParent = defaultParentIdentity
            parentIdentity = fileOps.ensureRoot(
                parentPath,
                initializeIdentity = expectedParent == null,
                expectedIdentity = expectedParent,
                allowUnboundParent = true,
            )
            defaultParentIdentity = parentIdentity
        }
        val ensurePhysical: suspend () -> String = {
            val legacyIdentityAdoption = existingRoot?.hash.isNullOrBlank() &&
                session.workspaceRootUuid != null &&
                isTrustedLegacyRoot(sessionId, declaredRootPath, physicalRoot)
            if (existingRoot?.hash.isNullOrBlank() && session.workspaceRootUuid != null && !legacyIdentityAdoption) {
                throw SecurityException("旧版工作区根不在应用私有目录内")
            }
            fileOps.ensureRoot(
                physicalRoot.toPath(),
                initializeIdentity = session.workspaceRootUuid == null || legacyIdentityAdoption,
                expectedIdentity = existingRoot?.hash?.takeIf { it.isNotBlank() },
            )
        }
        val rootIdentity = if (parentPath != null && parentIdentity != null) {
            WorkspaceMutationCoordinator.withBoundRoot(parentPath, parentIdentity, ensurePhysical)
        } else ensurePhysical()
        WorkspaceMutationCoordinator.bindIdentityWhileHeld(physicalRoot.toPath(), rootIdentity)
        fileOps.migrateInternalStorage(physicalRoot.toPath())
        session.workspacePath?.takeIf { it.isNotBlank() }?.let { declaredPath ->
            if (File(declaredPath.trim()).canonicalFile != physicalRoot) {
                throw SecurityException("Session workspace path cannot be changed")
            }
        }
        session.workspaceRootUuid?.let { existingUuid ->
            if (dao.countOtherSessionsForRoot(existingUuid, sessionId) != 0) {
                throw SecurityException("工作区根被多个 Session 共享")
            }
            var existing = dao.getActiveByUuid(existingUuid, existingUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            if (File(existing.physicalRootPath).canonicalFile != physicalRoot) {
                throw SecurityException("Session workspace root path cannot be changed")
            }
            val canonicalizedAt = clock()
            if (existing.physicalRootPath != physicalRoot.path) {
                dao.canonicalizeWorkspacePhysicalRootPath(existingUuid, physicalRoot.path, canonicalizedAt)
            }
            if (existing.hash.isBlank() || existing.physicalRootPath != physicalRoot.path) {
                existing = existing.copy(
                    hash = rootIdentity,
                    physicalRootPath = physicalRoot.path,
                    updatedAt = canonicalizedAt,
                )
                dao.update(existing)
            }
            dao.canonicalizeSessionRootClaim(sessionId, existingUuid, physicalRoot.path)
            return@action existing
        }

        val rootUuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.ensureSessionRoot(
            candidate = FileEntry(
                uuid = rootUuid,
                workspaceRootUuid = rootUuid,
                parentUuid = null,
                name = physicalRoot.name.ifBlank { "workspace" },
                hash = rootIdentity,
                isDirectory = true,
                physicalRootPath = physicalRoot.path,
                materializedPath = "/",
                createdAt = now,
                updatedAt = now,
            ),
            sessionId = sessionId,
        ).also { claimed ->
            if (File(claimed.physicalRootPath).canonicalFile != physicalRoot) {
                throw SecurityException("Concurrent workspace root path mismatch")
            }
            if (claimed.hash != rootIdentity) throw SecurityException("Concurrent workspace root identity mismatch")
        }
        }
        return if (beforeRoot?.hash?.isNotBlank() == true) {
            WorkspaceMutationCoordinator.withBoundRoot(physicalRoot.toPath(), beforeRoot.hash, action)
        } else {
            WorkspaceMutationCoordinator.withRoot(physicalRoot.toPath(), action)
        }
    }

    private fun validateClaimedRoot(root: FileEntry, physicalRoot: File) {
        if (
            root.uuid != root.workspaceRootUuid ||
            root.parentUuid != null ||
            !root.isDirectory ||
            root.materializedPath != "/"
        ) {
            throw SecurityException("Session workspace root record is invalid")
        }
        if (File(root.physicalRootPath).canonicalFile != physicalRoot) {
            throw SecurityException("Session workspace root path cannot be changed")
        }
    }

    private fun isTrustedLegacyRoot(
        sessionId: String,
        declaredRootPath: java.nio.file.Path,
        root: File,
    ): Boolean {
        val sessionParent = defaultWorkspaceParent ?: return false
        val appFilesDir = sessionParent.parentFile ?: return false
        return LegacyWorkspaceRootPolicy.isTrusted(
            sessionId = sessionId,
            declaredRootPath = declaredRootPath,
            root = root,
            defaultWorkspaceParent = sessionParent,
            filesDir = appFilesDir,
        )
    }

    override fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.observeRoots(workspaceRootUuid)

    override fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank() || parentUuid.isBlank()) emptyFlow()
        else dao.observeChildren(workspaceRootUuid, parentUuid)

    override fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.observeRecycleBin(workspaceRootUuid)

    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry? =
        if (workspaceRootUuid.isBlank() || uuid.isBlank()) null else dao.getActiveByUuid(workspaceRootUuid, uuid)

    override suspend fun getByMaterializedPath(
        workspaceRootUuid: String,
        materializedPath: String,
    ): FileEntry? = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        dao.getActiveByMaterializedPath(workspaceRootUuid, validateMaterializedPath(root, materializedPath))
    }

    override suspend fun getSubtree(
        workspaceRootUuid: String,
        materializedPathPrefix: String,
        inRecycleBin: Boolean,
    ): List<FileEntry> = withContext(Dispatchers.IO) {
        val root = requireRoot(workspaceRootUuid)
        dao.getSubtree(
            workspaceRootUuid,
            validateMaterializedPath(root, materializedPathPrefix),
            inRecycleBin,
        )
    }

    override fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>> =
        if (workspaceRootUuid.isBlank()) emptyFlow() else dao.searchByName(workspaceRootUuid, query)

    override suspend fun createFileInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        materializedPath: String,
    ): FileEntry = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) { root ->
        val parent = requireParent(root, parentUuid)
        validateName(name)
        val normalizedPath = validateMaterializedPath(root, materializedPath)
        if (normalizedPath.substringAfterLast('/') != name) throw SecurityException("文件名与路径不一致")
        if (normalizedPath != joinMaterializedPath(parent.materializedPath, name)) {
            throw SecurityException("文件路径与父目录不一致")
        }
        if (dao.getActiveByMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区文件已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        requirePhysicalTargetAbsent(rootPath, relative, normalizedPath)
        val staged = prepareMutation(root, WorkspaceMutationType.CREATE, normalizedPath, normalizedPath, uuid)
        prepareCreateOwnership(rootPath, staged)
        val bytes = content.toByteArray(Charsets.UTF_8)
        var databaseCommitted = false
        var placement: WorkspaceFileRollback? = null
        try {
            placement = createAndPlace(rootPath, relative, staged) { location ->
                fileOps.createFile(rootPath, location, bytes)
            }.second
        } catch (failure: Throwable) {
            handleCreateAttemptFailure(rootPath, relative, staged, failure)
            throw failure
        }
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = Sha256Utils.hash(content),
            mimeType = "text/plain",
            sizeBytes = bytes.size.toLong(),
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        )
        try {
            commitDatabaseMutation(staged) { (insertCommitter ?: dao::insertAbort).invoke(entry) }
            databaseCommitted = true
            placement?.commit()
            completeCreatedMutation(rootPath, staged)
            entry
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                rollbackCreatedPlacement(rootPath, relative, staged, placement, failure)
            }
            throw failure
        }
    } }

    override suspend fun createFileInWorkspaceStreaming(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        mimeType: String,
        parentUuid: String?,
        materializedPath: String,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
    ): FileEntry = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) { root ->
        require(maxBytes > 0) { "maxBytes 必须大于 0" }
        val parent = requireParent(root, parentUuid)
        validateName(name)
        val normalizedPath = validateMaterializedPath(root, materializedPath)
        if (normalizedPath.substringAfterLast('/') != name) throw SecurityException("文件名与路径不一致")
        if (normalizedPath != joinMaterializedPath(parent.materializedPath, name)) {
            throw SecurityException("文件路径与父目录不一致")
        }
        if (dao.getActiveByMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区文件已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        requirePhysicalTargetAbsent(rootPath, relative, normalizedPath)
        val staged = prepareMutation(root, WorkspaceMutationType.CREATE_STREAMING, normalizedPath, normalizedPath, uuid)
        prepareCreateOwnership(rootPath, staged)
        var placement: WorkspaceFileRollback? = null
        val writeResult = try {
            createAndPlace(rootPath, relative, staged) { location ->
                fileOps.createFileStreaming(rootPath, location, maxBytes, writer)
            }.also { placement = it.second }.first
        } catch (failure: Throwable) {
            handleCreateAttemptFailure(rootPath, relative, staged, failure)
            throw failure
        }
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = writeResult.sha256,
            mimeType = mimeType,
            sizeBytes = writeResult.sizeBytes,
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        )
        var databaseCommitted = false
        try {
            commitDatabaseMutation(staged) {
                (insertCommitter ?: dao::insertAbort).invoke(entry)
                creationRollbackAuthorizations += creationRollbackKey(workspaceRootUuid, entry.uuid)
            }
            databaseCommitted = true
            placement?.commit()
            completeCreatedMutation(rootPath, staged)
            entry
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                creationRollbackAuthorizations.remove(creationRollbackKey(workspaceRootUuid, entry.uuid))
                rollbackCreatedPlacement(rootPath, relative, staged, placement, failure)
            }
            throw failure
        }
    } }

    override suspend fun createDirectoryInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        parentUuid: String?,
        materializedPath: String,
    ): FileEntry = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) { root ->
        val parent = requireParent(root, parentUuid)
        validateName(name)
        val normalizedPath = validateMaterializedPath(root, materializedPath)
        if (normalizedPath.substringAfterLast('/') != name) throw SecurityException("目录名与路径不一致")
        if (normalizedPath != joinMaterializedPath(parent.materializedPath, name)) {
            throw SecurityException("目录路径与父目录不一致")
        }
        if (dao.getActiveByMaterializedPath(workspaceRootUuid, normalizedPath) != null) {
            throw IllegalStateException("工作区目录已存在: $normalizedPath")
        }
        val rootPath = File(root.physicalRootPath).toPath()
        val relative = relative(normalizedPath)
        requirePhysicalTargetAbsent(rootPath, relative, normalizedPath)
        val staged = prepareMutation(root, WorkspaceMutationType.MKDIR, normalizedPath, normalizedPath, uuid)
        prepareCreateOwnership(rootPath, staged)
        var placement: WorkspaceFileRollback? = null
        try {
            placement = createAndPlace(rootPath, relative, staged) { location ->
                fileOps.createDirectory(rootPath, location)
            }.second
        } catch (failure: Throwable) {
            handleCreateAttemptFailure(rootPath, relative, staged, failure)
            throw failure
        }
        val now = System.currentTimeMillis()
        val entry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = parentUuid,
            name = name,
            hash = "",
            isDirectory = true,
            physicalRootPath = root.physicalRootPath,
            materializedPath = normalizedPath,
            createdAt = now,
            updatedAt = now,
        )
        var databaseCommitted = false
        try {
            commitDatabaseMutation(staged) { (insertCommitter ?: dao::insertAbort).invoke(entry) }
            databaseCommitted = true
            placement?.commit()
            completeCreatedMutation(rootPath, staged)
            entry
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                rollbackCreatedPlacement(rootPath, relative, staged, placement, failure)
            }
            throw failure
        }
    } }

    override suspend fun moveToRecycleBin(workspaceRootUuid: String, uuid: String) = recycleIfHash(workspaceRootUuid, uuid, null)

    override suspend fun moveToRecycleBinIfHash(workspaceRootUuid: String, uuid: String, expectedSha256: String) =
        recycleIfHash(workspaceRootUuid, uuid, expectedSha256)

    override suspend fun moveEmptyDirectoryToRecycleBin(workspaceRootUuid: String, uuid: String) =
        recycleIfHash(workspaceRootUuid, uuid, null, emptyOnly = true)

    private suspend fun recycleIfHash(workspaceRootUuid: String, uuid: String, expectedSha256: String?, emptyOnly: Boolean = false) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) { root ->
        val entry = dao.getAnyStateByUuidForLifecycle(workspaceRootUuid, uuid) ?: return@withRootMutation
        if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可回收")
        if (entry.inRecycleBin) return@withRootMutation
        if (expectedSha256 != null) {
            if (entry.isDirectory) throw IllegalArgumentException("目录删除不支持文件 SHA-256 条件")
            val path = validateMaterializedPath(root, entry.materializedPath)
            if (fileOps.inspect(File(root.physicalRootPath).toPath(), relative(path)).sha256 != expectedSha256) {
                throw IllegalStateException("expected_sha256 与当前文件不一致，删除已拒绝")
            }
        }
        if (emptyOnly && (!entry.isDirectory || dao.observeChildren(workspaceRootUuid, entry.uuid).first().isNotEmpty() ||
                fileOps.listChildren(File(root.physicalRootPath).toPath(), relative(entry.materializedPath)).isNotEmpty())) {
            throw com.promenar.infinity.domain.storage.ExternalFolderException("CONFLICT", "来源目录含新增条目")
        }
        val originalPath = entry.materializedPath
        // 物理移动目标始终位于已存在的回收站目录直属层，避免隐式创建父链。
        val recyclePath = "/.recycle_bin/${entry.uuid}"
        val recycleParent = resolveOrCreateRecycleBinDir(entry)
        val now = System.currentTimeMillis()
        val updates = mutableListOf(entry.copy(
            inRecycleBin = true,
            recycledAt = now,
            originalParentUuid = entry.parentUuid,
            originalMaterializedPath = originalPath,
            parentUuid = recycleParent,
            materializedPath = recyclePath,
            updatedAt = now,
        ))
        if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, originalPath, false)
                .filterNot { it.uuid == uuid }
                .forEach { child ->
                    val childPath = recyclePath + child.materializedPath.removePrefix(originalPath)
                    updates += child.copy(
                        inRecycleBin = true,
                        recycledAt = now,
                        originalParentUuid = child.parentUuid,
                        originalMaterializedPath = child.materializedPath,
                        materializedPath = childPath,
                        updatedAt = now,
                    )
                }
        }
        commitRecycleMove(entry, originalPath, recyclePath, updates)
    } }

    override suspend fun restoreFromRecycleBin(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) {
        val entry = dao.getAnyStateByUuidForLifecycle(workspaceRootUuid, uuid) ?: return@withRootMutation
        if (!entry.inRecycleBin) return@withRootMutation
        val originalPath = entry.originalMaterializedPath
            ?: throw IllegalStateException("回收站记录缺少原始路径，无法恢复。")
        val originalParent = entry.originalParentUuid
            ?: throw IllegalStateException("回收站记录缺少原父目录，无法恢复。")
        val activeOriginalParent = dao.getActiveByUuid(workspaceRootUuid, originalParent)
            ?.takeIf { it.isDirectory }
            ?: throw SecurityException("原父目录不属于当前工作区")
        val recycledSubtree = if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, entry.materializedPath, true)
        } else listOf(entry)
        val subtreeIds = recycledSubtree.mapTo(mutableSetOf()) { it.uuid }
        val subtreeById = recycledSubtree.associateBy { it.uuid }
        val workspaceRoot = requireRoot(workspaceRootUuid)
        val validatedRootOriginal = validateMaterializedPath(workspaceRoot, originalPath)
        validateName(entry.name)
        if (validatedRootOriginal != joinMaterializedPath(activeOriginalParent.materializedPath, entry.name)) {
            throw IllegalStateException("回收根原父目录、名称与原始路径不一致，无法恢复。")
        }
        val originalPaths = recycledSubtree.associate { child ->
            val raw = child.originalMaterializedPath
                ?: throw IllegalStateException("回收子树缺少原始路径，无法恢复。")
            child.uuid to validateMaterializedPath(workspaceRoot, raw)
        }
        recycledSubtree.forEach { child ->
            val validated = originalPaths.getValue(child.uuid)
            val expected = if (child.uuid == entry.uuid) {
                validatedRootOriginal
            } else {
                validatedRootOriginal + child.materializedPath.removePrefix(entry.materializedPath)
            }
            if (validated != expected || !validated.startsWith(validatedRootOriginal.trimEnd('/') + "/") && child.uuid != entry.uuid) {
                throw IllegalStateException("回收子树原始路径结构不一致，无法恢复。")
            }
            val originalParentUuid = child.originalParentUuid
                ?: throw IllegalStateException("回收子树缺少原父目录，无法恢复。")
            val restoredParentPath = if (originalParentUuid in subtreeIds) {
                val parent = subtreeById[originalParentUuid]
                    ?.takeIf { it.isDirectory }
                    ?: throw IllegalStateException("回收子树原父目录无效，无法恢复。")
                if (child.uuid != entry.uuid && child.parentUuid != originalParentUuid) {
                    throw IllegalStateException("回收子树 parentUuid 层级不一致，无法恢复。")
                }
                originalPaths.getValue(parent.uuid)
            } else {
                dao.getActiveByUuid(workspaceRootUuid, originalParentUuid)
                    ?.takeIf { it.isDirectory }
                    ?.materializedPath
                    ?: throw IllegalStateException("回收子树原父目录无效，无法恢复。")
            }
            validateName(child.name)
            if (validated != joinMaterializedPath(restoredParentPath, child.name)) {
                throw IllegalStateException("回收子树 parent、name 与原始路径不一致，无法恢复。")
            }
        }
        if (originalPaths.values.toSet().size != originalPaths.size) {
            throw IllegalStateException("回收子树原始路径重复，无法恢复。")
        }
        originalPaths.values.forEach { path ->
            val occupied = dao.getActiveByMaterializedPath(workspaceRootUuid, path)
            if (occupied != null && occupied.uuid !in subtreeIds) throw WorkspaceLifecycleException(
                WorkspaceLifecycleErrorCode.TARGET_COLLISION,
                "恢复目标路径已被占用。",
            )
        }
        requireMoveTargetAbsent(entry, originalPath)
        val now = System.currentTimeMillis()
        val updates = mutableListOf(entry.copy(
            inRecycleBin = false,
            recycledAt = null,
            originalParentUuid = null,
            originalMaterializedPath = null,
            parentUuid = originalParent,
            materializedPath = originalPath,
            updatedAt = now,
        ))
        if (entry.isDirectory) {
            recycledSubtree
                .filterNot { it.uuid == uuid }
                .forEach { child ->
                    val childOriginal = originalPaths.getValue(child.uuid)
                    updates += child.copy(
                        inRecycleBin = false,
                        recycledAt = null,
                        originalParentUuid = null,
                        originalMaterializedPath = null,
                        materializedPath = childOriginal,
                        updatedAt = now,
                    )
                }
        }
        commitRestoreMove(entry, entry.materializedPath, originalPath, updates)
    } }

    override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) {
        permanentDeleteLocked(workspaceRootUuid, uuid)
    } }

    override suspend fun rollbackCreatedEntry(workspaceRootUuid: String, uuid: String) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) {
            val key = creationRollbackKey(workspaceRootUuid, uuid)
            if (!creationRollbackAuthorizations.remove(key)) {
                throw WorkspaceLifecycleException(
                    WorkspaceLifecycleErrorCode.CREATION_ROLLBACK_NOT_AUTHORIZED,
                    "该文件不属于当前未确认的创建事务，已拒绝回滚删除。",
                )
            }
            permanentDeleteLocked(workspaceRootUuid, uuid, allowActiveCreationRollback = true)
        }
    }

    override suspend fun confirmCreatedEntry(workspaceRootUuid: String, uuid: String) {
        creationRollbackAuthorizations.remove(creationRollbackKey(workspaceRootUuid, uuid))
    }

    private suspend fun permanentDeleteLocked(
        workspaceRootUuid: String,
        uuid: String,
        allowActiveCreationRollback: Boolean = false,
    ) {
        val entry = dao.getAnyStateByUuidForLifecycle(workspaceRootUuid, uuid) ?: return
        if (entry.uuid == workspaceRootUuid) throw SecurityException("工作区根目录不可删除")
        if (!entry.inRecycleBin && !allowActiveCreationRollback) {
            throw WorkspaceLifecycleException(
                WorkspaceLifecycleErrorCode.ACTIVE_PERMANENT_DELETE_FORBIDDEN,
                "活动文件只能先移入回收站，再从回收站永久删除。",
            )
        }
        val children = if (entry.isDirectory) {
            dao.getSubtree(workspaceRootUuid, entry.materializedPath, entry.inRecycleBin)
                .filterNot { it.uuid == uuid }
                .sortedByDescending { it.materializedPath.length }
        } else emptyList()
        reconcileEntryTree(entry)
        val root = File(entry.physicalRootPath).toPath()
        val rootEntry = requireRoot(workspaceRootUuid)
        val deletionToken = workspaceDeletionToken(entry.uuid)
        val journalStage = prepareMutation(
            rootEntry,
            WorkspaceMutationType.DELETE,
            entry.materializedPath,
            ".infinity_tombstones/$deletionToken",
            entry.uuid,
        )
        val stagedRollbacks = mutableListOf<WorkspaceFileRollback>()
        val ids = children.map { it.uuid } + uuid
        var barrier: WorkspaceDeleteBarrierLease? = null
        var databaseCommitted = false
        var physicalStageInProgress = false
        try {
            physicalStageInProgress = true
            stagedRollbacks += fileOps.stageDelete(
                root,
                relative(entry.materializedPath),
                deletionToken,
            )
            physicalStageInProgress = false
            ids.forEach { fileUuid ->
                val versionPath = workspaceVersionSnapshotDirectory(workspaceRootUuid, fileUuid)
                if (fileOps.exists(root, versionPath)) {
                    physicalStageInProgress = true
                    stagedRollbacks += fileOps.stageDelete(
                        root,
                        versionPath,
                        workspaceVersionDeletionToken(fileUuid),
                    )
                    physicalStageInProgress = false
                }
            }
            barrier = beforeDeleteCommitted(workspaceRootUuid, ids)
            barrier.awaitReady()
            commitDatabaseMutation(journalStage) {
                requireNotNull(deleteCommitter) {
                    "永久删除必须配置包含派生数据清理的事务提交器"
                }.invoke(workspaceRootUuid, ids)
            }
            databaseCommitted = true
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                var rollbackFailed = physicalStageInProgress
                stagedRollbacks.asReversed().forEach { rollback ->
                    runCatching { rollback.rollback() }.exceptionOrNull()?.let { rollbackFailure ->
                        rollbackFailed = true
                        failure.addSuppressed(rollbackFailure)
                    }
                }
                if (!rollbackFailed) abortMutation(journalStage, failure)
            }
            withContext(NonCancellable) {
                if (!databaseCommitted) {
                    runCatching { barrier?.abort() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
        withContext(NonCancellable) {
            checkNotNull(barrier).commit()
            runCatching { afterDeleteCommitted(workspaceRootUuid, ids) }
                .exceptionOrNull()
                ?.let { failure ->
                    runCatching { InfinityLogger.logError("WorkspaceDelete.afterCommitted", failure) }
                }
        }
        // 清理失败时保留 DB_COMMITTED journal 与 tombstone，由启动恢复幂等收敛。
        stagedRollbacks.forEach { rollback -> rollback.commit() }
        completeMutation(journalStage)
        Unit
    }

    override suspend fun emptyRecycleBin(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid) {
            val recycleRoot = dao.getAnyStateByMaterializedPathForLifecycle(workspaceRootUuid, "/.recycle_bin")
                ?: return@withRootMutation
            val topLevel = dao.observeRecycleBin(workspaceRootUuid).first()
                .filter { it.parentUuid == recycleRoot.uuid }
            bulkDeleteSnapshotHook()
            topLevel.forEach { permanentDeleteLocked(workspaceRootUuid, it.uuid) }
        }
    }

    suspend fun cleanupStaleRecycleBin(workspaceRootUuid: String, cutoff: Long) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid) { root ->
            recoverPendingTombstonesLocked(root)
            val staleFiles = dao.observeRecycleBin(workspaceRootUuid).first()
                .filter { (it.recycledAt ?: 0) < cutoff }
            val staleRoots = staleFiles.filter { candidate ->
                staleFiles.none { ancestor ->
                    ancestor.uuid != candidate.uuid &&
                        candidate.materializedPath.startsWith(ancestor.materializedPath.trimEnd('/') + "/")
                }
            }
            bulkDeleteSnapshotHook()
            staleRoots.forEach { permanentDeleteLocked(workspaceRootUuid, it.uuid) }
        }
    }

    suspend fun cleanupPendingTombstones(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid, preserveBaseline = true) { root ->
            recoverPendingTombstonesLocked(root)
        }
    }

    private suspend fun recoverPendingTombstonesLocked(root: FileEntry): TombstoneRecoveryReport {
        val restorePaths = buildMap {
            dao.getAllStatesByWorkspaceRootForCleanup(root.uuid).forEach { entry ->
                put(workspaceDeletionToken(entry.uuid), relative(entry.materializedPath))
                put(workspaceVersionDeletionToken(entry.uuid), workspaceVersionSnapshotDirectory(root.uuid, entry.uuid))
            }
        }
        return fileOps.recoverTombstones(File(root.physicalRootPath).toPath(), restorePaths::get).also { report ->
            if (report.attentionTokens.isNotEmpty()) {
                InfinityLogger.log("[WorkspaceDeleteRecovery] root=${root.uuid} attention=${report.attentionTokens.size}")
            }
        }
    }

    override suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String) =
        withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) {
            val entry = dao.getActiveByUuid(workspaceRootUuid, uuid) ?: return@withRootMutation
            val parent = dao.getActiveByUuid(workspaceRootUuid, newParentUuid)
                ?: throw SecurityException("目标父目录不属于当前工作区")
            if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("目标父节点不可用")
            if (entry.uuid == workspaceRootUuid || newParentUuid == uuid ||
                parent.materializedPath.startsWith(entry.materializedPath.trimEnd('/') + "/")) {
                throw SecurityException("不允许形成循环目录关系")
            }
            val newPath = joinMaterializedPath(parent.materializedPath, entry.name)
            if (newPath == entry.materializedPath) return@withRootMutation
            requireMoveTargetAbsent(entry, newPath)
            val now = System.currentTimeMillis()
            val oldPrefix = entry.materializedPath
            val updates = mutableListOf(entry.copy(parentUuid = newParentUuid, materializedPath = newPath, updatedAt = now))
            if (entry.isDirectory) {
                dao.getSubtree(workspaceRootUuid, oldPrefix, entry.inRecycleBin)
                    .filterNot { it.uuid == uuid }
                    .forEach { child ->
                        updates += child.copy(
                            materializedPath = newPath + child.materializedPath.removePrefix(oldPrefix),
                            updatedAt = now,
                        )
                    }
            }
            commitMove(WorkspaceMutationType.MOVE, entry, oldPrefix, newPath, updates)
        } }

    override suspend fun rename(
        workspaceRootUuid: String,
        uuid: String,
        newName: String,
        expectedName: String?,
    ): RenameResult = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) {
                    validateName(newName)
                    val entry = dao.getActiveByUuid(workspaceRootUuid, uuid)
                        ?: return@withRootMutation RenameResult.NotFound
                    if (entry.uuid == workspaceRootUuid) {
                        throw SecurityException("工作区根目录不可重命名")
                    }
                    if (entry.name == newName) {
                        return@withRootMutation RenameResult.Success(
                            name = entry.name,
                            targetHash = entry.hash,
                            targetEpoch = entry.updatedAt,
                            changed = false,
                        )
                    }
                    if (expectedName != null && entry.name != expectedName) {
                        return@withRootMutation RenameResult.Conflict(
                            expected = expectedName,
                            current = entry.name,
                        )
                    }
                    val parent = entry.parentUuid?.let { dao.getActiveByUuid(workspaceRootUuid, it) }
                        ?: throw SecurityException("父目录不属于当前工作区")
                    val newPath = joinMaterializedPath(parent.materializedPath, newName)
                    if (dao.getActiveByMaterializedPath(workspaceRootUuid, newPath) != null) {
                        throw WorkspaceLifecycleException(WorkspaceLifecycleErrorCode.TARGET_COLLISION, "目标名称已存在")
                    }
                    val commitClock = clock()
                    val nextEpoch = nextMutationEpoch(commitClock, entry.updatedAt)
                    val oldPrefix = entry.materializedPath
                    val updates = mutableListOf(
                        entry.copy(
                            name = newName,
                            materializedPath = newPath,
                            updatedAt = nextEpoch,
                            vectorizedAt = null,
                            kgExtractedAt = null,
                        ),
                    )
                    if (entry.isDirectory) {
                        dao.getSubtree(workspaceRootUuid, oldPrefix, entry.inRecycleBin)
                            .filterNot { it.uuid == uuid }
                            .forEach { child ->
                                updates += child.copy(
                                    materializedPath = newPath + child.materializedPath.removePrefix(oldPrefix),
                                    updatedAt = nextMutationEpoch(commitClock, child.updatedAt),
                                    vectorizedAt = null,
                                    kgExtractedAt = null,
                                )
                            }
                    }
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        commitMove(WorkspaceMutationType.RENAME, entry, oldPrefix, newPath, updates)
                        RenameResult.Success(
                            name = newName,
                            targetHash = entry.hash,
                            targetEpoch = nextEpoch,
                            changed = true,
                            affectedTargets = updates.filterNot { it.isDirectory }.map { updated ->
                                RenameIndexTarget(
                                    fileUuid = updated.uuid,
                                    targetHash = updated.hash,
                                    targetEpoch = updated.updatedAt,
                                )
                            },
                        )
                    }
                }
            }

    override suspend fun relocate(
        workspaceRootUuid: String,
        uuid: String,
        newParentUuid: String,
        newName: String,
        expectedName: String?,
        expectedHash: String?,
    ): RenameResult = withContext(Dispatchers.IO) {
        withRootMutation(workspaceRootUuid, baselineEntryUuid = uuid) {
            validateName(newName)
            val entry = dao.getActiveByUuid(workspaceRootUuid, uuid) ?: return@withRootMutation RenameResult.NotFound
            val parent = dao.getActiveByUuid(workspaceRootUuid, newParentUuid)
                ?: throw SecurityException("目标父目录不属于当前工作区")
            if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("目标父节点不可用")
            if (entry.uuid == workspaceRootUuid || newParentUuid == uuid ||
                parent.materializedPath.startsWith(entry.materializedPath.trimEnd('/') + "/")) {
                throw SecurityException("不允许形成循环目录关系")
            }
            val destination = joinMaterializedPath(parent.materializedPath, newName)
            if (destination == entry.materializedPath) return@withRootMutation RenameResult.Success(entry.name, entry.hash, entry.updatedAt, false)
            if (expectedName != null && entry.name != expectedName) return@withRootMutation RenameResult.Conflict(expectedName, entry.name)
            if (expectedHash != null && (entry.isDirectory || entry.hash != expectedHash ||
                    fileOps.inspect(File(entry.physicalRootPath).toPath(), relative(entry.materializedPath)).sha256 != expectedHash)) {
                throw IllegalStateException("expected_sha256 与来源不一致，未执行移动")
            }
            requireMoveTargetAbsent(entry, destination)
            val commitClock = clock()
            val epoch = nextMutationEpoch(commitClock, entry.updatedAt)
            val oldPrefix = entry.materializedPath
            val updates = mutableListOf(entry.copy(
                parentUuid = newParentUuid, name = newName, materializedPath = destination,
                updatedAt = epoch, vectorizedAt = null, kgExtractedAt = null,
            ))
            if (entry.isDirectory) {
                dao.getSubtree(workspaceRootUuid, oldPrefix, entry.inRecycleBin).filterNot { it.uuid == uuid }.forEach { child ->
                    updates += child.copy(
                        materializedPath = destination + child.materializedPath.removePrefix(oldPrefix),
                        updatedAt = nextMutationEpoch(commitClock, child.updatedAt), vectorizedAt = null, kgExtractedAt = null,
                    )
                }
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                commitMove(WorkspaceMutationType.MOVE, entry, oldPrefix, destination, updates)
                RenameResult.Success(newName, entry.hash, epoch, true, updates.filterNot { it.isDirectory }.map {
                    RenameIndexTarget(it.uuid, it.hash, it.updatedAt)
                })
            }
        }
    }

    override suspend fun <T> runCommandInWorkspace(
        workspaceRootUuid: String,
        sessionId: String?,
        block: suspend (physicalRoot: File) -> T,
    ): WorkspaceCommandOutcome<T> = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) { root ->
        val rootPath = File(root.physicalRootPath).toPath()
        val identity = WorkspaceRootIdentityGuard.capture(rootPath, fileOps.captureRootIdentityRestorer(rootPath))
        // 标记先于前快照写入，否则对账会把它当作命令改动的保留路径。
        if (!fileOps.exists(rootPath, listOf(COMMAND_PENDING_MARKER))) {
            fileOps.createFile(rootPath, listOf(COMMAND_PENDING_MARKER), byteArrayOf())
        }
        val before = try {
            treeScanner.scan(rootPath)
        } catch (failure: Throwable) {
            runCatching { fileOps.delete(rootPath, listOf(COMMAND_PENDING_MARKER)) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        var failure: Throwable? = null
        var value: Result<T>? = null
        try {
            value = Result.success(block(rootPath.toFile()))
        } catch (thrown: Throwable) {
            failure = thrown
        }
        val reconciled = withContext(NonCancellable) {
            runCatching {
                val identityRestored = identity.restoreIfChanged()
                reconcileCommandLocked(root, before, sessionId, externalCommitHeld = true).let { report ->
                    if (identityRestored) {
                        report.copy(reservedChanges = (report.reservedChanges + "/${WORKSPACE_ROOT_IDENTITY_MARKER}").distinct().sorted())
                    } else report
                }
            }.onSuccess {
                runCatching { fileOps.delete(rootPath, listOf(COMMAND_PENDING_MARKER)) }
                    .exceptionOrNull()?.let { InfinityLogger.logError("WorkspaceCommand.clearMarker", it) }
            }.onFailure { InfinityLogger.logError("WorkspaceCommand.reconcile", it) }
        }
        failure?.let { thrown ->
            reconciled.exceptionOrNull()?.let(thrown::addSuppressed)
            throw thrown
        }
        WorkspaceCommandOutcome(
            value = checkNotNull(value).getOrThrow(),
            changes = reconciled.getOrNull(),
            reconcileError = reconciled.exceptionOrNull()?.let { error ->
                (error as? SecurityException)?.message ?: "工作区索引对账失败（${error::class.java.simpleName}）"
            },
        )
    } }

    /** 终端与 Linux 直接绑定写入后，以磁盘实际内容收敛私有空间索引。 */
    suspend fun reconcileSessionWorkspace(workspaceRootUuid: String, sessionId: String?): WorkspaceChangeReport =
        withContext(Dispatchers.IO) {
            val path = File(requireRoot(workspaceRootUuid).physicalRootPath).toPath()
            WorkspaceExternalMutationCoordinator.awaitIdle(path)
            withRootMutation(workspaceRootUuid, indexScan = true) { root ->
                reconcileCommandLocked(root, before = null, sessionId = sessionId)
            }
        }

    /** 后台命令使用独立副本；复制与基线采集期间只占用一次根锁。 */
    suspend fun prepareBackgroundWorkspace(
        workspaceRootUuid: String,
        sessionId: String?,
        jobId: String,
        staging: File,
    ): File = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid, preserveBaseline = true) { root ->
        val physical = File(root.physicalRootPath)
        BackgroundWorkspace(fileOps).prepare(physical, workspaceRootUuid, sessionId, jobId, staging)
        physical
    } }

    /** 运行中仅发布日志；结束后合入没有前台冲突的结果，异常恢复不合入半成品。 */
    suspend fun reconcileBackgroundWorkspace(
        workspaceRootUuid: String,
        sessionId: String?,
        jobId: String,
        staging: File,
        finished: Boolean,
        interrupted: Boolean = false,
        reconcileIndex: Boolean = true,
    ): String? = withContext(Dispatchers.IO) { withRootMutation(workspaceRootUuid) { root ->
        val background = BackgroundWorkspace(fileOps)
        val warnings = background.synchronize(
            File(root.physicalRootPath), workspaceRootUuid, sessionId, jobId, staging, finished, interrupted,
        )
        // 合入与冲突结果在根锁内确定；应用层可将全量索引扫描交给合并调度器。
        if (reconcileIndex) reconcileCommandLocked(root, before = null, sessionId = sessionId, externalCommitHeld = true)
        if (finished || interrupted) background.complete(File(root.physicalRootPath), jobId)
        warnings
    } }

    /** 上次命令未完成对账（进程被杀或崩溃）时，下一次修改工作区前先按磁盘收敛索引。 */
    private suspend fun recoverInterruptedCommandLocked(root: FileEntry, externalCommitHeld: Boolean) {
        val rootPath = File(root.physicalRootPath).toPath()
        if (!fileOps.exists(rootPath, listOf(COMMAND_PENDING_MARKER))) return
        try {
            withContext(NonCancellable) {
                reconcileCommandLocked(root, before = null, sessionId = null, externalCommitHeld = externalCommitHeld)
                fileOps.delete(rootPath, listOf(COMMAND_PENDING_MARKER))
            }
        } catch (failure: Exception) {
            InfinityLogger.logError("WorkspaceCommand.recover", failure)
        }
    }

    private suspend fun reconcileCommandLocked(
        root: FileEntry,
        before: WorkspaceTreeSnapshot?,
        sessionId: String?,
        externalCommitHeld: Boolean = false,
    ): WorkspaceChangeReport {
        val rootPath = File(root.physicalRootPath).toPath()
        val scanEpoch = if (externalCommitHeld) 0L else WorkspaceExternalMutationCoordinator.scanEpoch(rootPath)
        val effectiveBefore = before ?: identityBaselines?.load(root)
        val after = treeScanner.scan(rootPath)
        val active = dao.getAllStatesByWorkspaceRootForCleanup(root.uuid).filter { entry ->
            !entry.inRecycleBin && entry.uuid != root.uuid && entry.parentUuid != null &&
                relative(entry.materializedPath).none(::isReservedWorkspaceName)
        }
        val byUuid = active.associateBy { it.uuid }
        val plan = WorkspaceReconcilePlanner.plan(
            indexed = active.map { entry ->
                IndexedWorkspaceNode(
                    uuid = entry.uuid,
                    parentUuid = entry.parentUuid,
                    path = normalizeMaterializedPath(entry.materializedPath),
                    isDirectory = entry.isDirectory,
                    sizeBytes = entry.sizeBytes,
                    hash = entry.hash,
                )
            },
            before = effectiveBefore,
            after = after,
        ) { path ->
            runCatching { fileOps.inspect(rootPath, relative(path)) }.getOrNull()
                ?.takeIf { it.kind == "file" }?.sha256
        }
        val stagedBaseline = identityBaselines?.stage(root, after)
        val apply: suspend () -> WorkspaceChangeReport = {
            // 任何部分提交或进程中断都不能让旧身份映射跨越索引事务。
            identityBaselines?.invalidate(root)
            if (!plan.isEmpty) applyCommandReconcile(root, plan, byUuid, sessionId)
            stagedBaseline?.publish()
            WorkspaceChangeReport.from(plan)
        }
        return try {
            if (externalCommitHeld) apply() else WorkspaceExternalMutationCoordinator.commit(rootPath, scanEpoch, apply)
        } finally {
            stagedBaseline?.close()
        }
    }

    private suspend fun applyCommandReconcile(
        root: FileEntry,
        plan: WorkspaceReconcilePlan,
        byUuid: Map<String, FileEntry>,
        sessionId: String?,
    ) {
        val rootPath = File(root.physicalRootPath).toPath()
        val deletedIds = plan.deletes.map { it.uuid }
        if (deletedIds.isNotEmpty()) {
            val barrier = beforeDeleteCommitted(root.uuid, deletedIds)
            try {
                barrier.awaitReady()
                requireNotNull(deleteCommitter) { "命令对账删除必须配置包含派生数据清理的事务提交器" }
                    .invoke(root.uuid, deletedIds)
            } catch (failure: Throwable) {
                withContext(NonCancellable) {
                    runCatching { barrier.abort() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
                throw failure
            }
            withContext(NonCancellable) {
                barrier.commit()
                runCatching { afterDeleteCommitted(root.uuid, deletedIds) }
                    .exceptionOrNull()?.let { InfinityLogger.logError("WorkspaceCommand.afterDelete", it) }
            }
            deletedIds.forEach { fileUuid ->
                val versions = workspaceVersionSnapshotDirectory(root.uuid, fileUuid)
                runCatching { if (fileOps.exists(rootPath, versions)) fileOps.delete(rootPath, versions) }
            }
        }

        val deleted = deletedIds.toSet()
        val pathToUuid = HashMap<String, String>()
        pathToUuid["/"] = root.uuid
        byUuid.values.filter { it.uuid !in deleted }.forEach { pathToUuid[normalizeMaterializedPath(it.materializedPath)] = it.uuid }
        plan.moves.forEach { pathToUuid.remove(it.from) }
        plan.moves.forEach { pathToUuid[it.to] = it.uuid }
        val insertedIds = plan.inserts.associate { it.path to UUID.randomUUID().toString() }
        pathToUuid.putAll(insertedIds)
        fun parentOf(path: String): String = pathToUuid[parentPath(path)]
            ?: throw IllegalStateException("命令对账无法定位父目录")

        val now = clock()
        val inserts = plan.inserts.map { insert ->
            val name = insert.path.substringAfterLast('/')
            FileEntry(
                uuid = insertedIds.getValue(insert.path),
                workspaceRootUuid = root.uuid,
                parentUuid = parentOf(insert.path),
                name = name,
                hash = insert.hash,
                mimeType = if (insert.isDirectory) null else WorkspaceCommandMimeTypes.guess(name, insert.sizeBytes) {
                    runCatching {
                        fileOps.readLimited(rootPath, relative(insert.path), WorkspaceCommandMimeTypes.SNIFF_LIMIT_BYTES)
                    }.getOrNull()
                },
                sizeBytes = insert.sizeBytes,
                isDirectory = insert.isDirectory,
                physicalRootPath = root.physicalRootPath,
                materializedPath = insert.path,
                lastWriteSessionId = sessionId,
                createdAt = now,
                updatedAt = now,
            )
        }
        val updates = LinkedHashMap<String, FileEntry>()
        plan.updates.forEach { update ->
            val entry = byUuid.getValue(update.uuid)
            updates[entry.uuid] = entry.copy(
                hash = update.hash,
                sizeBytes = update.sizeBytes,
                lastWriteSessionId = sessionId,
                vectorizedAt = null,
                kgExtractedAt = null,
                updatedAt = nextMutationEpoch(now, entry.updatedAt),
            )
        }
        plan.moves.forEach { move ->
            val entry = updates[move.uuid] ?: byUuid.getValue(move.uuid)
            updates[move.uuid] = entry.copy(
                name = move.to.substringAfterLast('/'),
                materializedPath = move.to,
                parentUuid = parentOf(move.to),
                updatedAt = nextMutationEpoch(now, entry.updatedAt),
            )
        }
        byUuid.values.filter { it.uuid !in deleted }.forEach { original ->
            val entry = updates[original.uuid] ?: original
            val expectedParent = parentOf(normalizeMaterializedPath(entry.materializedPath))
            if (entry.parentUuid != expectedParent) updates[entry.uuid] = entry.copy(parentUuid = expectedParent)
        }
        dao.commitCommandReconcile(inserts, updates.values.toList())

        (inserts + updates.values)
            .filter { DocumentReferenceExtractor.supports(it) && !isGitInternalWorkspacePath(it.materializedPath) }
            .forEach { entry ->
                try {
                    indexEventSink.publish(FileIndexEvent.Changed(root.uuid, entry.uuid, entry.hash, entry.updatedAt.coerceAtLeast(1)))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    // 索引记录已提交；未发布的事件由待处理索引协调器按 vectorized_at 补偿。
                    InfinityLogger.logError("WorkspaceCommand.publishIndex", failure)
                }
            }
    }

    override suspend fun getNextSeqForDate(dateKey: String): Int = seqDao.getNextSeqForDate(dateKey)

    override suspend fun resetAllRAGStatus(workspaceRootUuid: String) = withContext(Dispatchers.IO) {
        requireRoot(workspaceRootUuid)
        dao.resetAllRAGStatus(workspaceRootUuid)
    }

    private suspend fun requireRoot(workspaceRootUuid: String): FileEntry {
        if (workspaceRootUuid.isBlank()) throw SecurityException("workspaceRootUuid 不能为空")
        return dao.getActiveByUuid(workspaceRootUuid, workspaceRootUuid)
            ?.takeIf { it.isDirectory && it.parentUuid == null && it.workspaceRootUuid == workspaceRootUuid }
            ?: throw SecurityException("工作区根不存在或无效")
    }

    private suspend fun <T> withRootMutation(
        workspaceRootUuid: String,
        indexScan: Boolean = false,
        baselineEntryUuid: String? = null,
        preserveBaseline: Boolean = false,
        block: suspend (FileEntry) -> T,
    ): T {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(rootId = workspaceRootUuid)
        val gate = executionGate
        return if (gate == null) {
            withAdmittedRootMutation(workspaceRootUuid, indexScan, baselineEntryUuid, preserveBaseline, block)
        } else {
            gate.withWorkspaceAdmission(workspaceRootUuid) {
                withAdmittedRootMutation(workspaceRootUuid, indexScan, baselineEntryUuid, preserveBaseline, block)
            }
        }
    }

    private suspend fun <T> withAdmittedRootMutation(
        workspaceRootUuid: String,
        indexScan: Boolean,
        baselineEntryUuid: String?,
        preserveBaseline: Boolean,
        block: suspend (FileEntry) -> T,
    ): T {
        val initial = requireRoot(workspaceRootUuid)
        val prepare: suspend () -> Unit = {
            val currentRoot = requireRoot(workspaceRootUuid)
            fileOps.migrateInternalStorage(File(currentRoot.physicalRootPath).toPath())
            recoverPendingTombstonesLocked(currentRoot)
            cleanupCompletedOwnersBeforeMutation(currentRoot)
            recoverInterruptedCommandLocked(currentRoot, externalCommitHeld = true)
        }
        val path = File(initial.physicalRootPath).toPath()
        return if (indexScan) WorkspaceMutationCoordinator.withBoundRoot(path, initial.hash) {
            // 前置恢复会修改物理元数据，必须与命令启动互斥；全树扫描不持此门。
            WorkspaceExternalMutationCoordinator.indexedMutation(path, prepare)
            block(requireRoot(workspaceRootUuid))
        } else WorkspaceMutationCoordinator.withIndexedMutation(path, initial.hash) {
            prepare()
            val root = requireRoot(workspaceRootUuid)
            val baselines = identityBaselines
            if (baselines == null || preserveBaseline) block(root)
            else baselines.trackMutation(root, dao, baselineEntryUuid) { block(root) }
        }
    }

    private suspend fun cleanupCompletedOwnersBeforeMutation(root: FileEntry) {
        val rootPath = File(root.physicalRootPath).toPath()
        val ownerRoot = listOf(CREATE_OWNERSHIP_DIRECTORY)
        if (!fileOps.exists(rootPath, ownerRoot)) return
        for (operationId in creationOwnershipOperationIds(fileOps, rootPath)) {
            if (mutationJournal?.isPending(operationId) != false) {
                throw WorkspaceMutationRecoveryException("存在尚未收敛的创建操作，请先恢复后再修改工作区")
            }
            cleanupCompletedCreateOwnership(fileOps, rootPath, ownerRoot + operationId)
        }
    }

    private suspend fun <T> withSessionAdmission(sessionId: String, block: suspend () -> T): T {
        val gate = executionGate
        return if (gate == null) block() else gate.withSessionAdmission(sessionId, block)
    }

    private suspend fun requireParent(root: FileEntry, parentUuid: String?): FileEntry {
        val parentId = parentUuid ?: throw SecurityException("非根节点必须指定父目录")
        val parent = dao.getActiveByUuid(root.uuid, parentId)
            ?: throw SecurityException("父目录不属于当前工作区")
        if (!parent.isDirectory || parent.inRecycleBin) throw SecurityException("父节点不是可用目录")
        return parent
    }

    private suspend fun resolveOrCreateRecycleBinDir(entry: FileEntry): String {
        dao.getAnyStateByMaterializedPathForLifecycle(entry.workspaceRootUuid, "/.recycle_bin")?.let { return it.uuid }
        val rootEntry = requireRoot(entry.workspaceRootUuid)
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val root = File(entry.physicalRootPath).toPath()
        if (fileOps.exists(root, listOf(".recycle_bin"))) {
            throw WorkspaceLifecycleException(
                WorkspaceLifecycleErrorCode.TARGET_COLLISION,
                "回收站物理目录缺少数据库归属，已拒绝自动收养。",
            )
        }
        val staged = prepareMutation(
            rootEntry,
            WorkspaceMutationType.MKDIR,
            "/.recycle_bin",
            "/.recycle_bin",
            uuid,
        )
        prepareCreateOwnership(root, staged)
        var placement: WorkspaceFileRollback? = null
        try {
            placement = createAndPlace(root, listOf(".recycle_bin"), staged) { location ->
                fileOps.createDirectory(root, location)
            }.second
        } catch (failure: Throwable) {
            handleCreateAttemptFailure(root, listOf(".recycle_bin"), staged, failure)
            throw failure
        }
        val recycleEntry = FileEntry(
            uuid = uuid,
            workspaceRootUuid = entry.workspaceRootUuid,
            parentUuid = entry.workspaceRootUuid,
            name = ".recycle_bin",
            hash = "",
            isDirectory = true,
            physicalRootPath = entry.physicalRootPath,
            materializedPath = "/.recycle_bin",
            inRecycleBin = true,
            createdAt = now,
            updatedAt = now,
        )
        var databaseCommitted = false
        try {
            commitDatabaseMutation(staged) { (insertCommitter ?: dao::insertAbort).invoke(recycleEntry) }
            databaseCommitted = true
            placement?.commit()
            completeCreatedMutation(root, staged)
        } catch (failure: Throwable) {
            // 并发赢家存在时复用；其它失败保留系统目录但不继续业务移动。
            dao.getAnyStateByMaterializedPathForLifecycle(entry.workspaceRootUuid, "/.recycle_bin")?.let { winner ->
                rollbackCreatedPlacement(root, listOf(".recycle_bin"), staged, placement, failure)
                return winner.uuid
            }
            if (!databaseCommitted) rollbackCreatedPlacement(root, listOf(".recycle_bin"), staged, placement, failure)
            throw failure
        }
        return uuid
    }

    private suspend fun commitMove(
        type: WorkspaceMutationType,
        entry: FileEntry,
        sourcePath: String,
        targetPath: String,
        updates: List<FileEntry>,
    ) {
        requireMoveTargetAbsent(entry, targetPath)
        reconcileEntryTree(entry)
        val root = requireRoot(entry.workspaceRootUuid)
        val staged = prepareMutation(root, type, sourcePath, targetPath, entry.uuid)
        val rollback = try {
            fileOps.move(
                File(entry.physicalRootPath).toPath(),
                relative(sourcePath),
                relative(targetPath),
            )
        } catch (failure: Throwable) {
            // move 抛错时无法从返回值判定物理状态，保留 journal 供恢复器核对 source/target。
            throw failure
        }
        var databaseCommitted = false
        try {
            commitDatabaseMutation(staged) { (updateCommitter ?: dao::updateAll).invoke(updates) }
            databaseCommitted = true
            rollback.commit()
            completeMutation(staged)
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                rollbackPhysicalOrPreserveJournal(rollback, staged, failure)
            }
            throw failure
        }
    }

    private suspend fun commitRecycleMove(
        entry: FileEntry,
        sourcePath: String,
        targetPath: String,
        updates: List<FileEntry>,
    ) {
        requireMoveTargetAbsent(entry, targetPath)
        val ids = updates.map { it.uuid }
        val root = requireRoot(entry.workspaceRootUuid)
        val staged = prepareMutation(
            root,
            WorkspaceMutationType.RECYCLE,
            sourcePath,
            targetPath,
            entry.uuid,
        )
        val barrier = beforeRecycleCommitted(entry.workspaceRootUuid, ids)
        var rollback: WorkspaceFileRollback? = null
        var databaseCommitted = false
        var physicalMutationAttempted = false
        try {
            barrier.awaitReady()
            reconcileEntryTree(entry)
            physicalMutationAttempted = true
            rollback = fileOps.move(
                File(entry.physicalRootPath).toPath(),
                relative(sourcePath),
                relative(targetPath),
            )
            val commit = recycleCommitter
            commitDatabaseMutation(staged) {
                if (commit == null) (updateCommitter ?: dao::updateAll).invoke(updates)
                else commit(entry.workspaceRootUuid, updates, ids)
            }
            databaseCommitted = true
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                when {
                    rollback != null -> rollbackPhysicalOrPreserveJournal(rollback, staged, failure)
                    !physicalMutationAttempted -> abortMutation(staged, failure)
                    // move 已尝试但未返回 rollback；保留 journal 供 source/target 核对。
                }
            }
            withContext(NonCancellable) {
                if (!databaseCommitted) {
                    runCatching { barrier.abort() }.exceptionOrNull()?.let(failure::addSuppressed)
                }
            }
            throw failure
        }
        withContext(NonCancellable) {
            barrier.commit()
            runCatching { afterRecycleCommitted(entry.workspaceRootUuid, ids) }
                .onFailure { InfinityLogger.logError("WorkspaceRecycle.afterCommitted", it) }
            rollback?.commit()
            completeMutation(staged)
        }
    }

    private suspend fun commitRestoreMove(
        entry: FileEntry,
        sourcePath: String,
        targetPath: String,
        updates: List<FileEntry>,
    ) {
        requireMoveTargetAbsent(entry, targetPath)
        reconcileEntryTree(entry)
        val root = requireRoot(entry.workspaceRootUuid)
        val staged = prepareMutation(
            root,
            WorkspaceMutationType.RESTORE,
            sourcePath,
            targetPath,
            entry.uuid,
        )
        val rollback = try {
            fileOps.move(
                File(entry.physicalRootPath).toPath(),
                relative(sourcePath),
                relative(targetPath),
            )
        } catch (failure: Throwable) {
            // 保留 PREPARED，启动恢复器以 DB 与 source/target 实际布局为准。
            throw failure
        }
        val targets = updates.filterNot { it.isDirectory }.map { updated ->
            RenameIndexTarget(updated.uuid, updated.hash, updated.updatedAt)
        }
        var databaseCommitted = false
        try {
            val commit = restoreCommitter
            commitDatabaseMutation(staged) {
                if (commit == null) (updateCommitter ?: dao::updateAll).invoke(updates)
                else commit(entry.workspaceRootUuid, updates, targets)
            }
            databaseCommitted = true
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                rollbackPhysicalOrPreserveJournal(rollback, staged, failure)
            }
            throw failure
        }
        withContext(NonCancellable) {
            runCatching { afterRestoreCommitted(entry.workspaceRootUuid, targets) }
                .onFailure { InfinityLogger.logError("WorkspaceRestore.afterCommitted", it) }
            rollback.commit()
            completeMutation(staged)
        }
    }

    private suspend fun prepareMutation(
        root: FileEntry,
        type: WorkspaceMutationType,
        sourcePath: String,
        targetPath: String,
        targetUuid: String,
    ): StagedWorkspaceFileMutation? = mutationJournal?.prepare(
        workspaceRootUuid = root.uuid,
        operationType = type,
        sourceRelativePath = journalRelative(sourcePath),
        targetRelativePath = journalRelative(targetPath),
        databaseTargetUuid = targetUuid,
        rootIdentity = root.hash,
    )

    private suspend fun commitDatabaseMutation(
        staged: StagedWorkspaceFileMutation?,
        mutation: suspend () -> Unit,
    ) {
        if (staged == null) mutation() else mutationJournal!!.commitDatabase(staged, mutation)
    }

    private suspend fun completeMutation(staged: StagedWorkspaceFileMutation?) {
        if (staged != null) mutationJournal!!.complete(staged)
    }

    private suspend fun abortMutation(staged: StagedWorkspaceFileMutation?, failure: Throwable) {
        if (staged != null) {
            runCatching { mutationJournal!!.abort(staged) }.exceptionOrNull()?.let(failure::addSuppressed)
        }
    }

    private fun <T> createAndPlace(
        root: java.nio.file.Path,
        target: List<String>,
        staged: StagedWorkspaceFileMutation?,
        create: (List<String>) -> T,
    ): Pair<T, WorkspaceFileRollback?> {
        if (staged == null) return create(target) to null
        val stagedNode = createOwnershipRelative(staged) + CREATE_STAGED_NODE
        val result = create(stagedNode)
        val identity = fileOps.inspect(root, stagedNode)
        val token = UUID.randomUUID().toString()
        fileOps.retainCreationProof(root, stagedNode, createOwnershipRelative(staged), token)
        val manifest = WorkspaceCreateOwnershipManifest(target.joinToString("/"), identity, token)
        fileOps.createFile(
            root,
            createOwnershipRelative(staged) + CREATE_MANIFEST,
            encodeCreateOwnershipManifest(manifest),
        )
        fileOps.verifyCreationProof(root, stagedNode, createOwnershipRelative(staged), token, identity)
        val placement = fileOps.move(root, stagedNode, target)
        fileOps.verifyCreationProof(root, target, createOwnershipRelative(staged), token, identity)
        return result to placement
    }

    private suspend fun handleCreateAttemptFailure(
        root: java.nio.file.Path,
        target: List<String>,
        staged: StagedWorkspaceFileMutation?,
        failure: Throwable,
    ) {
        if (staged == null) return
        val targetExists = runCatching { fileOps.exists(root, target) }.getOrDefault(true)
        if (targetExists) return // 物理布局有歧义，保留 journal 交由恢复器按 manifest 身份核验。
        val cleanupFailure = runCatching { cleanupCreateOwnership(root, staged) }.exceptionOrNull()
        if (cleanupFailure == null) abortMutation(staged, failure) else failure.addSuppressed(cleanupFailure)
    }

    private suspend fun rollbackCreatedPlacement(
        root: java.nio.file.Path,
        target: List<String>,
        staged: StagedWorkspaceFileMutation?,
        placement: WorkspaceFileRollback?,
        failure: Throwable,
    ) {
        val cleanupFailure = runCatching {
            if (placement != null && staged != null) {
                val ownership = createOwnershipRelative(staged)
                val manifest = decodeCreateOwnershipManifest(fileOps.read(root, ownership + CREATE_MANIFEST))
                    ?: throw SecurityException("创建回滚缺少归属 manifest")
                requireCurrentCreationProof(manifest)
                val token = manifest.proofToken ?: throw SecurityException("创建回滚缺少持续归属证明")
                fileOps.deleteCreatedNode(root, target, ownership, token, manifest.identity)
            }
            else if (staged == null && fileOps.exists(root, target)) fileOps.delete(root, target)
            cleanupCreateOwnership(root, staged)
        }.exceptionOrNull()
        if (cleanupFailure == null) abortMutation(staged, failure) else failure.addSuppressed(cleanupFailure)
    }

    private suspend fun prepareCreateOwnership(
        root: java.nio.file.Path,
        staged: StagedWorkspaceFileMutation?,
    ) {
        if (staged == null) return
        try {
            fileOps.ensureDirectory(root, listOf(CREATE_OWNERSHIP_DIRECTORY))
            fileOps.createDirectory(root, createOwnershipRelative(staged))
        } catch (failure: Throwable) {
            runCatching { mutationJournal?.abort(staged) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    private suspend fun completeCreatedMutation(
        root: java.nio.file.Path,
        staged: StagedWorkspaceFileMutation?,
    ) {
        if (staged == null) return
        completeMutation(staged)
        runCatching { cleanupCompletedCreateOwnership(fileOps, root, createOwnershipRelative(staged)) }
            .onFailure { InfinityLogger.logError("WorkspaceCreate.cleanupOwnership", it) }
    }

    private fun cleanupCreateOwnership(root: java.nio.file.Path, staged: StagedWorkspaceFileMutation?) {
        if (staged == null) return
        val ownership = createOwnershipRelative(staged)
        if (fileOps.exists(root, ownership)) {
            val manifestPath = ownership + CREATE_MANIFEST
            if (fileOps.exists(root, manifestPath)) {
                val manifest = decodeCreateOwnershipManifest(fileOps.readLimited(root, manifestPath, 8192))
                    ?: throw SecurityException("创建回滚缺少有效manifest")
                requireCurrentCreationProof(manifest)
                val token = manifest.proofToken ?: throw SecurityException("旧创建回滚缺少持续证明")
                listOf(CREATE_STAGED_NODE, CREATE_ROLLBACK_NODE).forEach { name ->
                    val node = ownership + name
                    if (fileOps.exists(root, node)) fileOps.deleteCreatedNode(root, node, ownership, token, manifest.identity)
                }
                cleanupCompletedCreateOwnership(fileOps, root, ownership, rolledBack = true)
            } else {
                listOf(CREATE_STAGED_NODE, CREATE_ROLLBACK_NODE).forEach { name ->
                    val node = ownership + name
                    if (fileOps.exists(root, node)) runCatching { fileOps.deleteNonRecursive(root, node) }
                }
                fileOps.deleteNonRecursive(root, ownership)
            }
        }
    }

    private fun createOwnershipRelative(staged: StagedWorkspaceFileMutation): List<String> =
        listOf(CREATE_OWNERSHIP_DIRECTORY, staged.operationId)

    private fun requirePhysicalTargetAbsent(
        root: java.nio.file.Path,
        target: List<String>,
        displayPath: String,
    ) {
        if (fileOps.exists(root, target)) throw WorkspaceLifecycleException(
            WorkspaceLifecycleErrorCode.TARGET_COLLISION,
            "目标路径已存在，未执行文件操作: $displayPath",
        )
    }

    private suspend fun requireMoveTargetAbsent(entry: FileEntry, targetPath: String) {
        val databaseTarget = dao.getActiveByMaterializedPath(entry.workspaceRootUuid, targetPath)
        if (databaseTarget != null && databaseTarget.uuid != entry.uuid) throw WorkspaceLifecycleException(
            WorkspaceLifecycleErrorCode.TARGET_COLLISION,
            "目标路径已被占用，未执行移动。",
        )
        val root = File(entry.physicalRootPath).toPath()
        val target = relative(targetPath)
        if (fileOps.exists(root, target) && normalizeMaterializedPath(entry.materializedPath) != normalizeMaterializedPath(targetPath)) {
            throw WorkspaceLifecycleException(
                WorkspaceLifecycleErrorCode.TARGET_COLLISION,
                "目标物理路径已存在，未执行移动。",
            )
        }
    }

    private suspend fun rollbackPhysicalOrPreserveJournal(
        rollback: WorkspaceFileRollback,
        staged: StagedWorkspaceFileMutation?,
        failure: Throwable,
    ) {
        val rollbackFailure = runCatching { rollback.rollback() }.exceptionOrNull()
        if (rollbackFailure == null) {
            abortMutation(staged, failure)
        } else {
            failure.addSuppressed(rollbackFailure)
        }
    }

    private fun journalRelative(materializedPath: String): String =
        relative(materializedPath).joinToString("/").takeIf(String::isNotBlank)
            ?: throw SecurityException("工作区 journal 不允许根路径")

    private fun relative(materializedPath: String): List<String> =
        materializedPath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }

    private fun validateMaterializedPath(root: FileEntry, path: String): String {
        val trimmed = path.trim()
        if (trimmed.isEmpty() || '\u0000' in trimmed) throw SecurityException("工作区路径无效")
        val rootPath = File(root.physicalRootPath).canonicalFile.path
        if (trimmed == rootPath || trimmed.startsWith(rootPath + File.separator) ||
            Regex("^[A-Za-z]:[\\\\/].*").matches(trimmed) || trimmed.startsWith("\\\\")) {
            throw SecurityException("禁止使用物理绝对路径")
        }
        val segments = trimmed.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) throw SecurityException("工作区路径越界: $path")
        if (com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.containsReservedPath(trimmed)) {
            throw SecurityException("工作区路径属于系统保留目录")
        }
        return "/" + segments.joinToString("/")
    }

    private fun validateName(name: String) {
        if (name.isBlank() || name == "." || name == ".." || '/' in name || '\\' in name || '\u0000' in name ||
            com.promenar.infinity.domain.workspace.WorkspaceInternalPaths.isReservedName(name)) {
            throw SecurityException("工作区名称无效")
        }
    }

    private fun nextMutationEpoch(clockValue: Long, previousEpoch: Long): Long {
        check(previousEpoch != Long.MAX_VALUE) { "文件修改时间已达上限" }
        return maxOf(clockValue, previousEpoch + 1L)
    }

    private suspend fun reconcileEntryTree(entry: FileEntry) {
        val files = if (entry.isDirectory) {
            dao.getSubtree(entry.workspaceRootUuid, entry.materializedPath, entry.inRecycleBin)
                .filterNot { it.isDirectory }
        } else listOf(entry)
        val root = File(entry.physicalRootPath).toPath()
        files.forEach { file ->
            fileOps.reconcileFile(root, relative(file.materializedPath), file.hash)
        }
    }

    private fun normalizeMaterializedPath(path: String): String = "/" + path.replace('\\', '/').trim('/')
    private fun joinMaterializedPath(parentPath: String, name: String): String =
        normalizeMaterializedPath(parentPath).trimEnd('/') + "/" + name

}

/** 命令运行期间存在于工作区根；进程被杀后由下一次工作区修改触发恢复对账。 */
internal const val COMMAND_PENDING_MARKER = ".infinity_command_pending"

internal fun workspaceVersionDeletionToken(fileUuid: String): String =
    Sha256Utils.hash("workspace-version:$fileUuid").take(24)

internal fun workspaceVersionSnapshotDirectory(workspaceRootUuid: String, fileUuid: String): List<String> =
    listOf(".infinity_versions", workspaceRootUuid, fileUuid)

internal fun workspaceDeletionToken(fileUuid: String): String = Sha256Utils.hash(fileUuid).take(24)

private fun creationRollbackKey(workspaceRootUuid: String, fileUuid: String): String =
    "$workspaceRootUuid\u0000$fileUuid"
