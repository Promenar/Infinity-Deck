package com.promenar.infinity.data.repository

import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.domain.repository.IWorkspaceRepository
import com.promenar.infinity.domain.repository.RenameResult
import java.io.File
import java.io.OutputStream
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

object CompositeWorkspaceConstants {
    const val VIRTUAL_SESSION_WORKSPACES_ROOT_UUID = "__virtual_session_workspaces_root__"
    const val VIRTUAL_SESSION_PREFIX = "__virtual_session_"
    const val GLOBAL_KNOWLEDGE_SESSION_ID = "__infinity_rag_workspace__"

    fun isVirtualSessionWorkspacesRoot(uuid: String?): Boolean =
        uuid == VIRTUAL_SESSION_WORKSPACES_ROOT_UUID

    fun isVirtualSessionFolder(uuid: String?): Boolean =
        uuid != null && uuid.startsWith(VIRTUAL_SESSION_PREFIX)

    fun isVirtualNode(uuid: String?): Boolean =
        isVirtualSessionWorkspacesRoot(uuid) || isVirtualSessionFolder(uuid)

    fun extractSessionId(uuid: String): String =
        uuid.removePrefix(VIRTUAL_SESSION_PREFIX)

    fun virtualSessionFolderUuid(sessionId: String): String =
        "$VIRTUAL_SESSION_PREFIX$sessionId"
}

class CompositeGlobalWorkspaceRepository(
    private val baseRepo: IWorkspaceRepository,
    private val sessionDao: SessionDao,
    private val fileEntryDao: FileEntryDao,
    private val globalKnowledgeSessionId: String = CompositeWorkspaceConstants.GLOBAL_KNOWLEDGE_SESSION_ID,
    private val onTransferToKnowledgeBaseCommitted: (suspend (FileEntry) -> Unit)? = null,
    /** 虚拟根目录与无标题会话的显示名由界面按当前语言传入；数据层不持有语言资源。 */
    private val sessionWorkspacesRootName: String = "会话工作区",
    private val untitledSessionName: String = "未命名会话",
) : IWorkspaceRepository {

    private fun createVirtualSessionWorkspacesRoot(workspaceRootUuid: String): FileEntry {
        return FileEntry(
            uuid = CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
            workspaceRootUuid = workspaceRootUuid,
            parentUuid = workspaceRootUuid,
            name = sessionWorkspacesRootName,
            hash = "virtual:session_workspaces_root",
            isDirectory = true,
            physicalRootPath = "",
            materializedPath = "/$sessionWorkspacesRootName",
            createdAt = 0L,
            updatedAt = 0L,
        )
    }

    private fun createVirtualSessionFolder(session: SessionEntity, workspaceRootUuid: String): FileEntry {
        val title = session.title.trim().ifBlank { "$untitledSessionName ${session.id.take(8)}" }
        return FileEntry(
            uuid = CompositeWorkspaceConstants.virtualSessionFolderUuid(session.id),
            workspaceRootUuid = session.workspaceRootUuid ?: workspaceRootUuid,
            parentUuid = CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID,
            name = title,
            hash = "virtual:session:${session.id}",
            isDirectory = true,
            physicalRootPath = session.workspacePath ?: "",
            materializedPath = "/$sessionWorkspacesRootName/$title",
            lastWriteSessionId = session.id,
            createdAt = session.createdAt,
            updatedAt = session.updatedAt,
        )
    }

    override fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>> {
        return baseRepo.observeRoots(workspaceRootUuid)
    }

    override fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>> {
        return when {
            parentUuid == workspaceRootUuid -> {
                combine(
                    baseRepo.observeChildren(workspaceRootUuid, workspaceRootUuid),
                    sessionDao.observeAll(),
                ) { baseChildren, sessions ->
                    val hasSessions = sessions.any { it.id != globalKnowledgeSessionId }
                    if (hasSessions) {
                        val sessionRoot = createVirtualSessionWorkspacesRoot(workspaceRootUuid)
                        listOf(sessionRoot) + baseChildren
                    } else {
                        baseChildren
                    }
                }.flowOn(Dispatchers.IO)
            }

            parentUuid == CompositeWorkspaceConstants.VIRTUAL_SESSION_WORKSPACES_ROOT_UUID -> {
                sessionDao.observeAll().map { sessions ->
                    sessions
                        .filter { it.id != globalKnowledgeSessionId }
                        .map { session ->
                            createVirtualSessionFolder(session, workspaceRootUuid)
                        }
                }.flowOn(Dispatchers.IO)
            }

            CompositeWorkspaceConstants.isVirtualSessionFolder(parentUuid) -> {
                val sessionId = CompositeWorkspaceConstants.extractSessionId(parentUuid)
                flow {
                    val session = sessionDao.getById(sessionId)
                    val sessionRootUuid = session?.workspaceRootUuid
                    if (!sessionRootUuid.isNullOrBlank()) {
                        emitAll(baseRepo.observeChildren(sessionRootUuid, sessionRootUuid))
                    } else {
                        emit(emptyList())
                    }
                }.flowOn(Dispatchers.IO)
            }

            else -> {
                flow {
                    val entry = fileEntryDao.findActiveByUuid(parentUuid)
                    val targetRoot = entry?.workspaceRootUuid ?: workspaceRootUuid
                    emitAll(baseRepo.observeChildren(targetRoot, parentUuid))
                }.flowOn(Dispatchers.IO)
            }
        }
    }

    override suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry? {
        if (CompositeWorkspaceConstants.isVirtualSessionWorkspacesRoot(uuid)) {
            return createVirtualSessionWorkspacesRoot(workspaceRootUuid)
        }
        if (CompositeWorkspaceConstants.isVirtualSessionFolder(uuid)) {
            val sessionId = CompositeWorkspaceConstants.extractSessionId(uuid)
            val session = sessionDao.getById(sessionId) ?: return null
            return createVirtualSessionFolder(session, workspaceRootUuid)
        }
        val fromBase = baseRepo.getByUuid(workspaceRootUuid, uuid)
        if (fromBase != null) return fromBase
        val entry = fileEntryDao.findActiveByUuid(uuid) ?: return null
        return baseRepo.getByUuid(entry.workspaceRootUuid, uuid)
    }

    override suspend fun getByMaterializedPath(workspaceRootUuid: String, materializedPath: String): FileEntry? {
        if (materializedPath == "/会话工作区") {
            return createVirtualSessionWorkspacesRoot(workspaceRootUuid)
        }
        return baseRepo.getByMaterializedPath(workspaceRootUuid, materializedPath)
    }

    override suspend fun getSubtree(
        workspaceRootUuid: String,
        materializedPathPrefix: String,
        inRecycleBin: Boolean,
    ): List<FileEntry> {
        return baseRepo.getSubtree(workspaceRootUuid, materializedPathPrefix, inRecycleBin)
    }

    override fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>> {
        return baseRepo.searchByName(workspaceRootUuid, query)
    }

    override fun observeRecycleBin(workspaceRootUuid: String): Flow<List<FileEntry>> {
        return baseRepo.observeRecycleBin(workspaceRootUuid)
    }

    override suspend fun createFileInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        content: String,
        parentUuid: String?,
        materializedPath: String,
    ): FileEntry {
        val safeParentUuid = if (CompositeWorkspaceConstants.isVirtualNode(parentUuid)) {
            workspaceRootUuid
        } else parentUuid
        val targetRoot = safeParentUuid?.let { fileEntryDao.findActiveByUuid(it)?.workspaceRootUuid } ?: workspaceRootUuid
        return baseRepo.createFileInWorkspace(targetRoot, uuid, name, content, safeParentUuid, materializedPath)
    }

    override suspend fun createFileInWorkspaceStreaming(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        mimeType: String,
        parentUuid: String?,
        materializedPath: String,
        maxBytes: Long,
        writer: (OutputStream) -> Unit,
    ): FileEntry {
        val safeParentUuid = if (CompositeWorkspaceConstants.isVirtualNode(parentUuid)) {
            workspaceRootUuid
        } else parentUuid
        val targetRoot = safeParentUuid?.let { fileEntryDao.findActiveByUuid(it)?.workspaceRootUuid } ?: workspaceRootUuid
        return baseRepo.createFileInWorkspaceStreaming(
            targetRoot,
            uuid,
            name,
            mimeType,
            safeParentUuid,
            materializedPath,
            maxBytes,
            writer,
        )
    }

    override suspend fun createDirectoryInWorkspace(
        workspaceRootUuid: String,
        uuid: String,
        name: String,
        parentUuid: String?,
        materializedPath: String,
    ): FileEntry {
        val safeParentUuid = if (CompositeWorkspaceConstants.isVirtualNode(parentUuid)) {
            workspaceRootUuid
        } else parentUuid
        val targetRoot = safeParentUuid?.let { fileEntryDao.findActiveByUuid(it)?.workspaceRootUuid } ?: workspaceRootUuid
        return baseRepo.createDirectoryInWorkspace(targetRoot, uuid, name, safeParentUuid, materializedPath)
    }

    override suspend fun moveToRecycleBin(workspaceRootUuid: String, uuid: String) {
        if (CompositeWorkspaceConstants.isVirtualNode(uuid)) {
            throw SecurityException("系统虚拟目录受保护，不可移入回收站")
        }
        val entry = getByUuid(workspaceRootUuid, uuid)
        val targetRoot = entry?.workspaceRootUuid ?: workspaceRootUuid
        baseRepo.moveToRecycleBin(targetRoot, uuid)
    }

    override suspend fun restoreFromRecycleBin(workspaceRootUuid: String, uuid: String) {
        if (CompositeWorkspaceConstants.isVirtualNode(uuid)) return
        val entry = fileEntryDao.getAnyStateByUuidForLifecycle(workspaceRootUuid, uuid)
        val targetRoot = entry?.workspaceRootUuid ?: workspaceRootUuid
        baseRepo.restoreFromRecycleBin(targetRoot, uuid)
    }

    override suspend fun permanentDelete(workspaceRootUuid: String, uuid: String) {
        if (CompositeWorkspaceConstants.isVirtualNode(uuid)) {
            throw SecurityException("系统虚拟目录受保护，不可删除")
        }
        val entry = fileEntryDao.getAnyStateByUuidForLifecycle(workspaceRootUuid, uuid)
        val targetRoot = entry?.workspaceRootUuid ?: workspaceRootUuid
        baseRepo.permanentDelete(targetRoot, uuid)
    }

    override suspend fun rollbackCreatedEntry(workspaceRootUuid: String, uuid: String) {
        baseRepo.rollbackCreatedEntry(workspaceRootUuid, uuid)
    }

    override suspend fun confirmCreatedEntry(workspaceRootUuid: String, uuid: String) {
        baseRepo.confirmCreatedEntry(workspaceRootUuid, uuid)
    }

    override suspend fun emptyRecycleBin(workspaceRootUuid: String) {
        baseRepo.emptyRecycleBin(workspaceRootUuid)
    }

    override suspend fun updateParent(workspaceRootUuid: String, uuid: String, newParentUuid: String) {
        if (CompositeWorkspaceConstants.isVirtualNode(uuid)) {
            throw SecurityException("系统虚拟目录受保护，不可移动")
        }
        val entry = getByUuid(workspaceRootUuid, uuid)
        val targetRoot = entry?.workspaceRootUuid ?: workspaceRootUuid
        baseRepo.updateParent(targetRoot, uuid, newParentUuid)
    }

    override suspend fun rename(
        workspaceRootUuid: String,
        uuid: String,
        newName: String,
        expectedName: String?,
    ): RenameResult {
        if (CompositeWorkspaceConstants.isVirtualSessionWorkspacesRoot(uuid)) {
            return RenameResult.Conflict(expected = "会话工作区", current = "会话工作区")
        }
        if (CompositeWorkspaceConstants.isVirtualSessionFolder(uuid)) {
            val sessionId = CompositeWorkspaceConstants.extractSessionId(uuid)
            val now = System.currentTimeMillis()
            sessionDao.updateTitle(sessionId, newName, now)
            return RenameResult.Success(
                name = newName,
                targetHash = "virtual:session:$sessionId",
                targetEpoch = now,
                changed = true,
            )
        }
        val entry = getByUuid(workspaceRootUuid, uuid)
        val targetRoot = entry?.workspaceRootUuid ?: workspaceRootUuid
        return baseRepo.rename(targetRoot, uuid, newName, expectedName)
    }

    override suspend fun getNextSeqForDate(dateKey: String): Int {
        return baseRepo.getNextSeqForDate(dateKey)
    }

    override suspend fun resetAllRAGStatus(workspaceRootUuid: String) {
        baseRepo.resetAllRAGStatus(workspaceRootUuid)
    }

    override suspend fun ensureSessionRoot(sessionId: String): FileEntry {
        return baseRepo.ensureSessionRoot(sessionId)
    }

    override suspend fun ensureSessionRoot(sessionId: String, physicalRootPath: String): FileEntry {
        return baseRepo.ensureSessionRoot(sessionId, physicalRootPath)
    }

    /**
     * 将会话工作区内的文件转存沉淀至全局知识库中。
     */
    suspend fun transferFileToKnowledgeBase(
        sourceFileUuid: String,
        targetKnowledgeBaseRootUuid: String,
        targetFolderUuid: String? = null,
    ): FileEntry = withContext(Dispatchers.IO) {
        val sourceEntry = fileEntryDao.findActiveByUuid(sourceFileUuid)
            ?: throw NoSuchElementException("源文件不存在: $sourceFileUuid")

        if (sourceEntry.workspaceRootUuid == targetKnowledgeBaseRootUuid) {
            return@withContext sourceEntry
        }

        val rawFile = File(sourceEntry.physicalRootPath)
        val sourceFile = when {
            rawFile.isFile -> rawFile
            rawFile.isDirectory -> File(rawFile, sourceEntry.materializedPath.trimStart('/'))
            else -> {
                val candidate = File(rawFile, sourceEntry.materializedPath.trimStart('/'))
                if (candidate.exists()) candidate else rawFile
            }
        }
        val parentUuid = targetFolderUuid ?: targetKnowledgeBaseRootUuid
        val newUuid = UUID.randomUUID().toString()
        val matPath = if (parentUuid == targetKnowledgeBaseRootUuid) {
            "/${sourceEntry.name}"
        } else {
            val parentEntry = getByUuid(targetKnowledgeBaseRootUuid, parentUuid)
            "${parentEntry?.materializedPath ?: ""}/${sourceEntry.name}"
        }

        val created = baseRepo.createFileInWorkspaceStreaming(
            workspaceRootUuid = targetKnowledgeBaseRootUuid,
            uuid = newUuid,
            name = sourceEntry.name,
            mimeType = sourceEntry.mimeType ?: "application/octet-stream",
            parentUuid = parentUuid,
            materializedPath = matPath,
            maxBytes = sourceEntry.sizeBytes.coerceAtLeast(1024L * 1024L * 50L),
        ) { outStream ->
            if (sourceFile.exists() && sourceFile.isFile) {
                sourceFile.inputStream().use { input ->
                    input.copyTo(outStream)
                }
            }
        }

        onTransferToKnowledgeBaseCommitted?.invoke(created)
        created
    }
}
