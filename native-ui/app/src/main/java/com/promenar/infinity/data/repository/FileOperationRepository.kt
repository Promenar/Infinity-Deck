package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.FileVersionDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.FileVersionEntity
import com.promenar.infinity.domain.repository.DiffHunk
import com.promenar.infinity.domain.repository.DiffLine
import com.promenar.infinity.domain.repository.DiffResult
import com.promenar.infinity.domain.repository.IFileOperationRepository
import com.promenar.infinity.domain.repository.PatchError
import com.promenar.infinity.domain.repository.PatchOperation
import com.promenar.infinity.domain.repository.PatchResult
import com.promenar.infinity.domain.repository.ReadResult
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.infra.util.MyersDiff
import com.promenar.infinity.infra.util.Sha256Utils
import com.promenar.infinity.data.rag.FileIndexEvent
import com.promenar.infinity.data.rag.FileIndexEventSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import com.promenar.infinity.data.session.SessionExecutionGate

class FileOperationRepository(
    private val dao: FileEntryDao,
    private val versionDao: FileVersionDao,
    private val fileOps: WorkspaceFileOps = SecureWorkspaceFileOps(),
    private val versionCommitter: (suspend (FileVersionEntity, FileEntry) -> Unit)? = null,
    private val indexEventSink: FileIndexEventSink = FileIndexEventSink.None,
    private val clock: () -> Long = System::currentTimeMillis,
    private val executionGate: SessionExecutionGate? = null,
    private val textPolicy: WorkspaceTextContentPolicy = WorkspaceTextContentPolicy(),
    private val identityBaselines: WorkspaceIdentityBaselineStore? = null,
) : IFileOperationRepository {

    override suspend fun writeFileAtomicExact(
        workspaceRootUuid: String,
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String,
    ): WriteResult = writeFile(workspaceRootUuid, uuid, newContent, sessionId, expectedHash, exact = true)

    override suspend fun writeFileAtomic(
        workspaceRootUuid: String,
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String,
    ): WriteResult = writeFile(workspaceRootUuid, uuid, newContent, sessionId, expectedHash, exact = false)

    private suspend fun writeFile(
        workspaceRootUuid: String,
        uuid: String,
        newContent: String,
        sessionId: String,
        expectedHash: String,
        exact: Boolean,
    ): WriteResult = withSessionWorkspaceAdmission(sessionId, workspaceRootUuid) {
        withContext(Dispatchers.IO) {
        val initial = dao.getActiveByUuid(workspaceRootUuid, uuid)
            ?: return@withContext WriteResult.NotFound
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withIndexedMutation(File(initial.physicalRootPath).toPath(), root.hash) withBoundRoot@{
            val entry = dao.getActiveByUuid(workspaceRootUuid, uuid)
                ?: return@withBoundRoot WriteResult.NotFound
            if (entry.isDirectory) return@withBoundRoot WriteResult.NotFound
            val validated = textPolicy.validate(
                entry.name,
                entry.mimeType,
                newContent.toByteArray(Charsets.UTF_8),
                WorkspaceTextOperation.WRITE,
                if (exact) EXACT_MIRROR_BUDGET else WorkspaceTextContentPolicy.budgetFor(WorkspaceTextOperation.WRITE),
            )
            if (entry.hash != expectedHash) {
                return@withBoundRoot WriteResult.Conflict(
                    currentHash = entry.hash,
                    expectedHash = expectedHash,
                    message = "文件已被其他会话修改。请先读取最新版本再重试。",
                )
            }
            val committed = commitContentChange(
                entry, if (exact) newContent else validated.text, sessionId,
                if (exact) EXACT_MIRROR_BUDGET else WorkspaceTextContentPolicy.budgetFor(WorkspaceTextOperation.WRITE),
            )
            val indexTarget = committed ?: entry.currentIndexTargetIfStale()
            val indexQueued = indexTarget == null || publishIndexEvent(workspaceRootUuid, uuid, indexTarget)
            WriteResult.Success(
                newHash = committed?.contentHash ?: entry.hash,
                indexQueued = indexQueued,
                targetEpoch = committed?.targetEpoch ?: entry.updatedAt,
            )
        }
        }
    }

    override suspend fun readFileRange(
        workspaceRootUuid: String,
        uuid: String,
        startLine: Int?,
        endLine: Int?,
    ): ReadResult = withContext(Dispatchers.IO) {
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withBoundRoot(File(root.physicalRootPath).toPath(), root.hash) {
            val entry = requireFile(workspaceRootUuid, uuid)
            val allLines = workspaceTextLines(readContent(entry, WorkspaceTextOperation.READ))
            val total = allLines.size
            val start = startLine?.coerceIn(1, total.coerceAtLeast(1)) ?: 1
            val end = endLine?.coerceIn(1, total.coerceAtLeast(1)) ?: total
            val content = if (total == 0) "" else allLines.subList(start - 1, end).joinToString("\n")
            textPolicy.validateOutput(content, WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET)
            ReadResult(entry.uuid, entry.name, total, start, end, content, entry.hash, entry.updatedAt)
        }
    }

    override suspend fun diffFile(
        workspaceRootUuid: String,
        uuid: String,
        basisHash: String?,
    ): DiffResult = withContext(Dispatchers.IO) {
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withBoundRoot(File(root.physicalRootPath).toPath(), root.hash) {
            val entry = requireFile(workspaceRootUuid, uuid)
            val currentContent = readContent(entry, WorkspaceTextOperation.DIFF)
            val basisContent = when {
                basisHash == null || basisHash == entry.hash -> currentContent
                else -> readVersionContent(entry, basisHash)
            }
            val effectiveBasisHash = basisHash ?: entry.hash
            val hunks = MyersDiff.computeHunks(
                workspaceTextLines(basisContent),
                workspaceTextLines(currentContent),
            )
            textPolicy.validateOutput(
                hunks.asSequence().flatMap { it.lines.asSequence() }.joinToString("\n") { it.content },
                WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET,
            )
            DiffResult(
            uuid = entry.uuid,
            basisHash = effectiveBasisHash,
            currentHash = entry.hash,
            hunks = hunks.map { hunk ->
                DiffHunk(
                    oldStart = hunk.oldStart,
                    oldCount = hunk.oldCount,
                    newStart = hunk.newStart,
                    newCount = hunk.newCount,
                    lines = hunk.lines.map { DiffLine(it.type, it.content) },
                )
            },
            )
        }
    }

    override suspend fun patchFile(
        workspaceRootUuid: String,
        uuid: String,
        operations: List<PatchOperation>,
        expectedHash: String,
    ): PatchResult = withWorkspaceAdmission(workspaceRootUuid) {
        withContext(Dispatchers.IO) {
        val initial = dao.getActiveByUuid(workspaceRootUuid, uuid)
            ?: return@withContext fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
        val root = bindRoot(workspaceRootUuid)
        WorkspaceMutationCoordinator.withIndexedMutation(File(initial.physicalRootPath).toPath(), root.hash) withBoundRoot@{
            val entry = dao.getActiveByUuid(workspaceRootUuid, uuid)
                ?: return@withBoundRoot fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
            if (entry.isDirectory) return@withBoundRoot fileFailure("FILE_NOT_FOUND", "文件不存在: $uuid", uuid)
            if (entry.hash != expectedHash) {
                return@withBoundRoot PatchResult.Failure(PatchError(
                    code = "HASH_MISMATCH",
                    message = "文件已被其他会话修改。请先获取最新差异再重试。",
                    operationIndex = -1,
                    fileUuid = uuid,
                    suggestion = "请先调用 diff_file 获取最新差异。",
                ))
            }

            val currentContent = try {
                readContent(entry, WorkspaceTextOperation.PATCH)
            } catch (failure: WorkspaceTextPolicyException) {
                return@withBoundRoot policyFailure(failure, uuid)
            }
            val newContent = when (val patch = applyWorkspaceLinePatch(currentContent, operations, uuid)) {
                is WorkspaceLinePatchResult.Applied -> patch.content
                is WorkspaceLinePatchResult.Rejected -> return@withBoundRoot PatchResult.Failure(patch.error)
            }
            try {
                textPolicy.validate(
                    entry.name,
                    entry.mimeType,
                    newContent.toByteArray(Charsets.UTF_8),
                    WorkspaceTextOperation.PATCH,
                )
            } catch (failure: WorkspaceTextPolicyException) {
                return@withBoundRoot policyFailure(failure, uuid)
            }
            val committed = commitContentChange(entry, newContent, null)
            val indexTarget = committed ?: entry.currentIndexTargetIfStale()
            val indexQueued = indexTarget == null || publishIndexEvent(workspaceRootUuid, uuid, indexTarget)
            PatchResult.Success(
                newHash = committed?.contentHash ?: entry.hash,
                appliedOperations = operations.size,
                indexQueued = indexQueued,
                targetEpoch = committed?.targetEpoch ?: entry.updatedAt,
            )
        }
        }
    }

    private suspend fun <T> withSessionWorkspaceAdmission(
        sessionId: String,
        workspaceRootUuid: String,
        block: suspend () -> T,
    ): T {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(rootId = workspaceRootUuid)
        val gate = executionGate
        return if (gate == null) block()
        else gate.withSessionWorkspaceAdmission(sessionId, workspaceRootUuid, block)
    }

    private suspend fun <T> withWorkspaceAdmission(
        workspaceRootUuid: String,
        block: suspend () -> T,
    ): T {
        com.promenar.infinity.startup.StartupWriteBarrier.requireWritable(rootId = workspaceRootUuid)
        val gate = executionGate
        return if (gate == null) block() else gate.withWorkspaceAdmission(workspaceRootUuid, block)
    }

    private suspend fun publishIndexEvent(
        workspaceRootUuid: String,
        uuid: String,
        target: CommittedIndexTarget,
    ): Boolean =
        try {
            indexEventSink.publish(
                FileIndexEvent.Changed(workspaceRootUuid, uuid, target.contentHash, target.targetEpoch),
            )
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // 文件与版本记录已经提交；普通发布失败标记为待补偿，取消必须原样传播。
            false
        }

    private suspend fun commitContentChange(
        entry: FileEntry,
        newContent: String,
        sessionId: String?,
        budget: WorkspaceTextBudget = WorkspaceTextContentPolicy.budgetFor(WorkspaceTextOperation.WRITE),
    ): CommittedIndexTarget? {
        val baselines = identityBaselines
        return if (baselines == null) commitContentChangeTracked(entry, newContent, sessionId, budget)
        else baselines.trackMutation(bindRoot(entry.workspaceRootUuid), dao, entry.uuid) {
            commitContentChangeTracked(entry, newContent, sessionId, budget)
        }
    }

    private suspend fun commitContentChangeTracked(
        entry: FileEntry,
        newContent: String,
        sessionId: String?,
        budget: WorkspaceTextBudget,
    ): CommittedIndexTarget? {
        val root = File(entry.physicalRootPath).toPath()
        val old = readRawText(entry, WorkspaceTextOperation.WRITE, budget)
        val oldContent = old.text
        val physicalHash = Sha256Utils.hash(old.bytes)
        if (physicalHash != entry.hash) {
            throw IllegalStateException("文件内容与数据库哈希不一致，已拒绝覆盖: ${entry.uuid}")
        }
        val newBytes = newContent.toByteArray(Charsets.UTF_8)
        val newHash = Sha256Utils.hash(newBytes)
        if (newHash == entry.hash && old.bytes.contentEquals(newBytes)) return null
        val targetEpoch = nextMutationEpoch(entry.updatedAt)

        val versionId = UUID.randomUUID().toString()
        val snapshotRelative = snapshotRelative(entry, versionId)
        ensureDirectories(root, snapshotRelative.dropLast(1))
        fileOps.createFile(root, snapshotRelative, old.bytes)
        val snapshot = root.resolve(snapshotRelative.joinToString(File.separator)).toFile()
        val version = FileVersionEntity(
            id = versionId,
            fileUuid = entry.uuid,
            workspaceRootUuid = entry.workspaceRootUuid,
            hash = entry.hash,
            contentPath = snapshot.absolutePath,
            createdBySessionId = sessionId,
            createdAt = System.currentTimeMillis(),
        )
        val updated = entry.copy(
            hash = newHash,
            sizeBytes = newBytes.size.toLong(),
            lastWriteSessionId = sessionId,
            vectorizedAt = null,
            kgExtractedAt = null,
            updatedAt = targetEpoch,
        )

        var replacement: WorkspaceFileRollback? = null
        var databaseCommitted = false
        try {
            val activeReplacement = fileOps.replaceFile(
                root,
                relative(entry.materializedPath),
                newBytes,
            )
            replacement = activeReplacement
            (versionCommitter ?: versionDao::commitVersionAndFile).invoke(version, updated)
            databaseCommitted = true
            // 备份清理失败不反转已经提交的 DB/物理新版本；残留隐藏备份可由维护任务清理。
            runCatching { activeReplacement.commit() }
            return CommittedIndexTarget(newHash, updated.updatedAt)
        } catch (failure: Throwable) {
            if (!databaseCommitted) {
                if (replacement != null) rollbackReplacement(replacement, failure)
                runCatching { fileOps.delete(root, snapshotRelative) }.exceptionOrNull()?.let(failure::addSuppressed)
            }
            throw failure
        }
    }

    private data class CommittedIndexTarget(
        val contentHash: String,
        val targetEpoch: Long,
    )

    private fun FileEntry.currentIndexTargetIfStale(): CommittedIndexTarget? =
        if (vectorizedAt == null || vectorizedAt < updatedAt) {
            CommittedIndexTarget(hash, updatedAt)
        } else {
            null
        }

    private fun nextMutationEpoch(previousEpoch: Long): Long {
        check(previousEpoch != Long.MAX_VALUE) { "文件修改时间已达上限" }
        return maxOf(clock(), previousEpoch + 1L)
    }

    private fun rollbackReplacement(replacement: WorkspaceFileRollback, failure: Throwable) {
        val first = runCatching { replacement.rollback() }.exceptionOrNull() ?: return
        val second = runCatching { replacement.rollback() }.exceptionOrNull()
        if (second != null) {
            second.addSuppressed(first)
            failure.addSuppressed(second)
        }
    }

    private suspend fun readVersionContent(entry: FileEntry, basisHash: String): String {
        val version = versionDao.getByHash(entry.workspaceRootUuid, entry.uuid, basisHash)
            ?: throw NoSuchElementException("File version not found: ${entry.uuid}@$basisHash")
        val rootPath = File(entry.physicalRootPath).canonicalFile.toPath()
        val expectedDirs = listOf(".infinity_versions", ".nexara_versions").map { rootPath.resolve(it).normalize() }
        val rawSnapshotPath = File(version.contentPath).toPath().toAbsolutePath().normalize()
        if (expectedDirs.none { rawSnapshotPath.startsWith(it) }) {
            throw SecurityException("文件版本快照路径无效")
        }
        val relative = rootPath.relativize(rawSnapshotPath).map { it.toString() }
        val bytes = try {
            fileOps.readLimited(
                rootPath,
                relative,
                WorkspaceTextContentPolicy.DEFAULT_TOOL_BUDGET.maxInputBytes,
            )
        } catch (_: WorkspaceFileTooLargeException) {
            throw WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.INPUT_TOO_LARGE,
                "历史版本超过差异操作上限，请缩小文件后重试。",
            )
        }
        if (Sha256Utils.hash(bytes) != version.hash) {
            throw SecurityException("文件版本快照哈希校验失败")
        }
        return textPolicy.validate(
            entry.name,
            entry.mimeType,
            bytes,
            WorkspaceTextOperation.DIFF,
        ).text
    }

    private fun snapshotRelative(entry: FileEntry, versionId: String): List<String> =
        listOf(".infinity_versions", entry.workspaceRootUuid, entry.uuid, "$versionId.snapshot")

    private fun ensureDirectories(root: java.nio.file.Path, directories: List<String>) {
        directories.indices.forEach { index -> fileOps.ensureDirectory(root, directories.take(index + 1)) }
    }

    private companion object {
        // 画布镜像以 1 MiB 字节上限约束，不能套用交互式文件工具的行数预算。
        val EXACT_MIRROR_BUDGET = WorkspaceTextBudget(1_048_576, 1_048_577, 1_048_576)
    }

    private data class RawText(val bytes: ByteArray, val text: String)

    private fun readContent(entry: FileEntry, operation: WorkspaceTextOperation): String =
        readRawText(entry, operation).text

    private fun readRawText(
        entry: FileEntry,
        operation: WorkspaceTextOperation,
        budget: WorkspaceTextBudget = WorkspaceTextContentPolicy.budgetFor(operation),
    ): RawText {
        textPolicy.requireSupportedType(entry.name, entry.mimeType)
        if (entry.sizeBytes > budget.maxInputBytes) {
            throw WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.INPUT_TOO_LARGE,
                "文件超过本次操作的读取上限，请缩小读取范围或使用专用文件处理能力。",
            )
        }
        val root = File(entry.physicalRootPath).toPath()
        val relative = relative(entry.materializedPath)
        // 隔离读取只观察当前节点，不能顺带恢复临时文件、覆盖目标或清理备份。
        if (!com.promenar.infinity.startup.StartupWriteBarrier.isReadOnly(rootId = entry.workspaceRootUuid, path = entry.physicalRootPath)) {
            fileOps.reconcileFile(root, relative, entry.hash)
        }
        val bytes = try {
            fileOps.readLimited(root, relative, budget.maxInputBytes)
        } catch (_: WorkspaceFileTooLargeException) {
            throw WorkspaceTextPolicyException(
                WorkspaceTextErrorCode.INPUT_TOO_LARGE,
                "文件超过本次操作的读取上限，请缩小读取范围或使用专用文件处理能力。",
            )
        }
        return RawText(bytes, textPolicy.validate(entry.name, entry.mimeType, bytes, operation, budget).text)
    }

    private fun relative(materializedPath: String): List<String> =
        materializedPath.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }

    private suspend fun requireFile(workspaceRootUuid: String, uuid: String): FileEntry =
        bindRoot(workspaceRootUuid).let {
            dao.getActiveByUuid(workspaceRootUuid, uuid)?.takeUnless { it.isDirectory }
        }
            ?: throw NoSuchElementException("File not found: $uuid")

    private suspend fun bindRoot(workspaceRootUuid: String): FileEntry {
        val root = dao.getActiveByUuid(workspaceRootUuid, workspaceRootUuid)
            ?.takeIf { it.isDirectory && it.parentUuid == null }
            ?: throw SecurityException("工作区根不存在或无效")
        return root
    }


    private fun fileFailure(code: String, message: String, uuid: String) =
        PatchResult.Failure(PatchError(code, message, -1, uuid))

    private fun policyFailure(failure: WorkspaceTextPolicyException, uuid: String) =
        PatchResult.Failure(PatchError(
            code = failure.code.name,
            message = failure.safeMessage,
            operationIndex = -1,
            fileUuid = uuid,
            suggestion = "请改用受支持的文本文件，并缩小操作范围。",
        ))

}
