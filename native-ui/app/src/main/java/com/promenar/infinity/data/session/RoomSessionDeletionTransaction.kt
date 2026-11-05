package com.promenar.infinity.data.session

import androidx.room.withTransaction
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.rag.GraphStore
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationPayloadResult
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationStage
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationType
import com.promenar.infinity.data.repository.WorkspaceMutationRecoveryCoordinator
import com.promenar.infinity.infra.util.Sha256Utils
import java.io.File

class RoomSessionDeletionTransaction(private val database: InfinityDatabase) {
    suspend fun deleteKeepingFiles(target: SessionDeletionTarget, operationId: String, register: suspend () -> Unit) {
        database.withTransaction {
            register()
            delete(target, operationId)
        }
    }

    suspend fun deleteWithoutWorkspace(sessionId: String): Boolean = database.withTransaction {
        val dao = database.sessionDeletionDao()
        dao.detachBranches(sessionId)
        dao.deleteAttachments(sessionId)
        dao.deleteToolLedger(sessionId)
        dao.deleteArtifacts(sessionId)
        dao.deleteContextSummaries(sessionId)
        dao.deleteTaskNodes(sessionId)
        dao.deleteAuditLogs(sessionId)
        dao.deleteVectorizationTasksBySession(sessionId)
        dao.deleteVectorsBySession(sessionId)
        GraphStore(database).clearSessionGraph(sessionId)
        dao.invalidateKgJitCache()
        dao.deleteFileVersionsBySession(sessionId)
        dao.deleteMessages(sessionId)
        dao.deleteSession(sessionId) == 1
    }

    suspend fun delete(target: SessionDeletionTarget, operationId: String) {
        database.withTransaction {
            validatePreparedJournal(target, operationId)
            val dao = database.sessionDeletionDao()
            dao.detachBranches(target.sessionId)
            dao.deleteAttachments(target.sessionId)
            dao.deleteToolLedger(target.sessionId)
            dao.deleteArtifacts(target.sessionId)
            dao.deleteContextSummaries(target.sessionId)
            dao.deleteTaskNodes(target.sessionId)
            dao.deleteAuditLogs(target.sessionId)
            dao.deleteVectorizationTasks(target.sessionId, target.workspaceRootUuid)
            dao.deleteVectors(target.sessionId, target.fileUuids)
            dao.deleteDocumentTags(target.fileUuids)
            GraphStore(database).clearSessionGraph(target.sessionId)
            target.fileUuids.forEach { GraphStore(database).clearGraphForDoc(it) }
            dao.invalidateKgJitCache()
            dao.deleteFileVersions(target.sessionId, target.workspaceRootUuid)
            dao.deleteMessages(target.sessionId)
            dao.deleteWorkspaceFiles(target.workspaceRootUuid)
            check(dao.deleteSession(target.sessionId) == 1) { "会话删除目标已变化" }
            check(database.workspaceMutationDao().markDbCommitted(operationId, System.currentTimeMillis()) == 1) {
                "删除 journal 无法进入 DB_COMMITTED"
            }
        }
    }

    private suspend fun validatePreparedJournal(target: SessionDeletionTarget, operationId: String) {
        val journal = database.workspaceMutationDao().get(operationId)
            ?: throw IllegalStateException("删除 journal 不存在")
        check(journal.state == WorkspaceMutationStage.PREPARED && journal.operationType == WorkspaceMutationType.DELETE) {
            "删除 journal 状态无效"
        }
        check(journal.workspaceRootUuid == target.workspaceRootUuid) { "删除 journal 工作区身份不匹配" }
        check(Sha256Utils.hash(journal.payload) == journal.payloadDigest) { "删除 journal 摘要不匹配" }
        val payload = when (val decoded = WorkspaceMutationPayloadCodec.decode(journal.payloadVersion, journal.payload)) {
            is WorkspaceMutationPayloadResult.Valid -> decoded.payload
            is WorkspaceMutationPayloadResult.Invalid -> error(decoded.error.message)
        }
        check(journal.operationId == operationId) { "删除 journal operationId 不匹配" }
        check(payload.targetRelativePath == expectedStageRelativePath(operationId)) {
            "删除 journal stage 目标与 operationId 不匹配"
        }
        check(payload.databaseTargetUuid == target.sessionId) { "删除 journal 会话身份不匹配" }
        check(payload.expectedSha256 == target.rootIdentity) { "删除 journal 根身份不匹配" }
        check(payload.sourceRelativePath == target.physicalRoot.fileName.toString()) { "删除 journal 根路径不匹配" }
        val session = database.sessionDao().getById(target.sessionId)
            ?: throw IllegalStateException("待删除会话不存在")
        check(session.workspaceRootUuid == target.workspaceRootUuid) { "会话工作区 UUID 已变化" }
        check(File(session.workspacePath.orEmpty()).canonicalFile.toPath() == target.physicalRoot.toFile().canonicalFile.toPath()) {
            "会话工作区路径已变化"
        }
    }

    private fun expectedStageRelativePath(operationId: String): String =
        "${WorkspaceMutationRecoveryCoordinator.STAGING_DIRECTORY}/$operationId"
}
