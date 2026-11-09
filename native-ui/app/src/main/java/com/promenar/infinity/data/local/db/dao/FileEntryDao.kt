package com.promenar.infinity.data.local.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.VectorEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.coroutines.flow.Flow

@Dao
interface FileEntryDao {
    @Query("SELECT COUNT(*) FROM workspace_files WHERE workspace_root_uuid = :root AND in_recycle_bin = 0 AND is_directory = 0 AND vectorized_at IS NOT NULL")
    fun observeLibraryIndexedCount(root: String): Flow<Int>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :root AND in_recycle_bin = 0 AND is_directory = 0 ORDER BY updated_at DESC, uuid ASC")
    fun observeLibraryFiles(root: String): Flow<List<FileEntry>>

    /** 含回收站记录；仅供删除、恢复与启动恢复流程使用。 */
    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid")
    suspend fun getAllStatesByWorkspaceRootForCleanup(workspaceRootUuid: String): List<FileEntry>
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: FileEntry)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAbort(entry: FileEntry)

    @Update
    suspend fun update(entry: FileEntry)

    @Update
    suspend fun updateAll(entries: List<FileEntry>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAllAbort(entries: List<FileEntry>)

    /** 命令对账的新增与更新在同一事务提交，避免索引停在半套状态。 */
    @Transaction
    suspend fun commitCommandReconcile(inserts: List<FileEntry>, updates: List<FileEntry>) {
        if (inserts.isNotEmpty()) insertAllAbort(inserts)
        if (updates.isNotEmpty()) updateAll(updates)
    }

    @Query("SELECT COUNT(*) FROM vectorization_tasks WHERE workspace_root_uuid = :root AND doc_id = :id AND status IN ('pending', 'running', 'processing', 'extracting_source', 'chunking', 'vectorizing', 'saving', 'extracting')")
    suspend fun countActivePathIndexTasks(root: String, id: String): Int

    @Query("SELECT * FROM vectors WHERE file_uuid = :id OR (file_uuid IS NULL AND doc_id = :id)")
    suspend fun vectorsForPathChange(id: String): List<VectorEntity>

    @Query("UPDATE vectors SET metadata = :metadata WHERE id = :id")
    suspend fun updatePathVectorMetadata(id: String, metadata: String)

    /** 路径提交、任务状态判断与向量标题更新在同一数据库事务内完成。 */
    @Transaction
    suspend fun commitPathChanges(
        inserts: List<FileEntry>, updates: List<FileEntry>, originals: List<FileEntry>, verified: Set<String>,
    ): List<FileEntry> {
        val previous = originals.associateBy { it.uuid }
        val reindex = mutableListOf<FileEntry>()
        val finalUpdates = updates.map { proposed ->
            val read = previous[proposed.uuid]
            if (read == null || proposed.isDirectory) proposed else {
                // 索引任务可能在根锁外写回完成时间：以事务内的最新行为准，内容哈希变了就不复用。
                val old = getActiveByUuid(read.workspaceRootUuid, read.uuid) ?: read
                // 从未索引过的文件（如 SFTP 临时文件改名落位）照旧排队；已索引的沿用结果，已过期的保持过期。
                val indexed = old.vectorizedAt != null || old.kgExtractedAt != null
                val reusable = indexed && old.uuid in verified && old.hash == read.hash &&
                    countActivePathIndexTasks(old.workspaceRootUuid, old.uuid) == 0
                val final = if (reusable) proposed.copy(
                    vectorizedAt = old.vectorizedAt?.let { if (it >= old.updatedAt) proposed.updatedAt else it },
                    kgExtractedAt = old.kgExtractedAt?.let { if (it >= old.updatedAt) proposed.updatedAt else it },
                ) else proposed.copy(vectorizedAt = null, kgExtractedAt = null).also { reindex += it }
                if (old.name != final.name) {
                    vectorsForPathChange(old.uuid).forEach { vector ->
                        val json = runCatching { Json.parseToJsonElement(vector.metadata ?: "").jsonObject }.getOrNull()
                        if (json != null && "docTitle" in json) {
                            updatePathVectorMetadata(vector.id, JsonObject(json + ("docTitle" to JsonPrimitive(final.name))).toString())
                        }
                    }
                }
                final
            }
        }
        if (inserts.isNotEmpty()) insertAllAbort(inserts)
        if (finalUpdates.isNotEmpty()) updateAll(finalUpdates)
        return reindex
    }

    @Delete
    suspend fun delete(entry: FileEntry)

    /** 含回收站记录；仅供生命周期、恢复和测试状态断言使用。 */
    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid")
    suspend fun getAnyStateByUuidForLifecycle(workspaceRootUuid: String, uuid: String): FileEntry?

    /** 测试夹具兼容入口；生产消费者不得使用。 */
    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid")
    suspend fun getByUuid(workspaceRootUuid: String, uuid: String): FileEntry?

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid AND in_recycle_bin = 0")
    suspend fun getActiveByUuid(workspaceRootUuid: String, uuid: String): FileEntry?

    @Query("SELECT * FROM workspace_files WHERE uuid = :uuid AND in_recycle_bin = 0 LIMIT 1")
    suspend fun findActiveByUuid(uuid: String): FileEntry?

    @Query("""
        SELECT * FROM workspace_files
        WHERE uuid = workspace_root_uuid
          AND parent_uuid IS NULL
          AND is_directory = 1
          AND in_recycle_bin = 0
          AND materialized_path = '/'
        ORDER BY uuid
    """)
    suspend fun getStructurallyValidWorkspaceRootCandidates(): List<FileEntry>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid IN (:uuids)")
    suspend fun getByUuids(workspaceRootUuid: String, uuids: List<String>): List<FileEntry>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid")
    fun observeByUuid(workspaceRootUuid: String, uuid: String): Flow<FileEntry?>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid AND in_recycle_bin = 0")
    fun observeActiveByUuid(workspaceRootUuid: String, uuid: String): Flow<FileEntry?>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND parent_uuid = :parentUuid AND in_recycle_bin = 0 ORDER BY is_directory DESC, name ASC")
    fun observeChildren(workspaceRootUuid: String, parentUuid: String): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :workspaceRootUuid AND parent_uuid IS NULL AND in_recycle_bin = 0")
    fun observeRoots(workspaceRootUuid: String): Flow<List<FileEntry>>

    @Query("SELECT * FROM workspace_files WHERE uuid = workspace_root_uuid AND workspace_root_uuid != '' AND parent_uuid IS NULL")
    suspend fun getAllWorkspaceRootsForMaintenance(): List<FileEntry>

    @Query("""
        SELECT * FROM workspace_files
        WHERE (workspace_root_uuid NOT IN (SELECT workspace_root_uuid FROM sessions WHERE agent_id != '__system__'))
          AND is_directory = 0
          AND in_recycle_bin = 0
          AND (vectorized_at IS NULL OR updated_at > vectorized_at)
          AND mime_type IN (:mimeTypes)
        ORDER BY created_at ASC
    """)
    suspend fun getUnvectorizedSupportedFiles(mimeTypes: List<String>): List<FileEntry>

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND materialized_path = :path AND in_recycle_bin = 0 LIMIT 1")
    suspend fun getActiveByMaterializedPath(workspaceRootUuid: String, path: String): FileEntry?

    /** 含回收站记录；仅供生命周期路径冲突与恢复使用。 */
    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND materialized_path = :path LIMIT 1")
    suspend fun getAnyStateByMaterializedPathForLifecycle(workspaceRootUuid: String, path: String): FileEntry?

    @Query("SELECT * FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND name LIKE '%' || :query || '%' AND in_recycle_bin = 0")
    fun searchByName(workspaceRootUuid: String, query: String): Flow<List<FileEntry>>

    @Query("""
        SELECT * FROM workspace_files
        WHERE workspace_root_uuid = :workspaceRootUuid
          AND in_recycle_bin = :inRecycleBin
          AND (
            (:prefix = '/' AND substr(materialized_path, 1, 1) = '/')
            OR materialized_path = :prefix
            OR substr(materialized_path, 1, length(:prefix) + 1) = :prefix || '/'
          )
    """)
    suspend fun getSubtree(workspaceRootUuid: String, prefix: String, inRecycleBin: Boolean): List<FileEntry>

    @Transaction
    @Query("DELETE FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid = :uuid")
    suspend fun deleteByUuid(workspaceRootUuid: String, uuid: String)

    @Query("DELETE FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid AND uuid IN (:uuids)")
    suspend fun deleteByUuids(workspaceRootUuid: String, uuids: List<String>)

    @Query("DELETE FROM workspace_files WHERE workspace_root_uuid = :workspaceRootUuid")
    suspend fun deleteByRoot(workspaceRootUuid: String): Int

    @Query("""DELETE FROM workspace_files
        WHERE workspace_root_uuid = :workspaceRootUuid
          AND (
            (:prefix = '/' AND substr(materialized_path, 1, 1) = '/')
            OR materialized_path = :prefix
            OR substr(materialized_path, 1, length(:prefix) + 1) = :prefix || '/'
          )""")
    suspend fun deleteSubtree(workspaceRootUuid: String, prefix: String): Int

    @Query("UPDATE workspace_files SET vectorized_at = NULL, kg_extracted_at = NULL WHERE workspace_root_uuid = :workspaceRootUuid")
    suspend fun resetAllRAGStatus(workspaceRootUuid: String)

    @Query("SELECT * FROM sessions WHERE id = :sessionId LIMIT 1")
    suspend fun getSessionForRoot(sessionId: String): SessionEntity?

    @Query("SELECT COUNT(*) FROM sessions WHERE workspace_root_uuid = :workspaceRootUuid AND id != :sessionId")
    suspend fun countOtherSessionsForRoot(workspaceRootUuid: String, sessionId: String): Int

    @Query("""
        SELECT COUNT(*) FROM sessions AS s
        INNER JOIN workspace_files AS f
          ON f.uuid = s.workspace_root_uuid AND f.workspace_root_uuid = f.uuid
        WHERE f.physical_root_path = :canonicalPath AND s.id != :sessionId
    """)
    suspend fun countOtherSessionsForPhysicalRoot(canonicalPath: String, sessionId: String): Int

    // 认领与复核会话根目录是元数据维护，不推进首页排序用的 updated_at。
    @Query("""
        UPDATE sessions
        SET workspace_root_uuid = :workspaceRootUuid,
            workspace_path = :physicalRootPath
        WHERE id = :sessionId
    """)
    suspend fun canonicalizeSessionRootClaim(
        sessionId: String,
        workspaceRootUuid: String,
        physicalRootPath: String,
    )

    @Query("""
        UPDATE workspace_files
        SET physical_root_path = :physicalRootPath,
            updated_at = :updatedAt
        WHERE workspace_root_uuid = :workspaceRootUuid
    """)
    suspend fun canonicalizeWorkspacePhysicalRootPath(
        workspaceRootUuid: String,
        physicalRootPath: String,
        updatedAt: Long,
    )

    @Query("""
        UPDATE sessions
        SET workspace_root_uuid = :workspaceRootUuid,
            workspace_path = :physicalRootPath
        WHERE id = :sessionId AND workspace_root_uuid IS NULL
    """)
    suspend fun claimSessionRoot(
        sessionId: String,
        workspaceRootUuid: String,
        physicalRootPath: String,
    ): Int

    @Transaction
    suspend fun ensureSessionRoot(candidate: FileEntry, sessionId: String): FileEntry {
        val before = getSessionForRoot(sessionId)
            ?: throw NoSuchElementException("Session not found: $sessionId")
        before.workspaceRootUuid?.let { existingUuid ->
            if (countOtherSessionsForRoot(existingUuid, sessionId) != 0) {
                throw SecurityException("Workspace root is referenced by multiple sessions")
            }
            val existing = getAnyStateByUuidForLifecycle(existingUuid, existingUuid)
                ?: throw IllegalStateException("Session workspace root reference is invalid: $sessionId")
            if (countOtherSessionsForPhysicalRoot(existing.physicalRootPath, sessionId) != 0) {
                throw SecurityException("Workspace physical root is referenced by another session")
            }
            canonicalizeSessionRootClaim(sessionId, existingUuid, existing.physicalRootPath)
            return existing
        }

        if (countOtherSessionsForPhysicalRoot(candidate.physicalRootPath, sessionId) != 0) {
            throw SecurityException("Workspace physical root is referenced by another session")
        }

        insertAbort(candidate)
        if (claimSessionRoot(sessionId, candidate.uuid, candidate.physicalRootPath) == 1) return candidate

        deleteByUuid(candidate.workspaceRootUuid, candidate.uuid)
        val winnerUuid = getSessionForRoot(sessionId)?.workspaceRootUuid
            ?: throw IllegalStateException("Session workspace root claim failed: $sessionId")
        return getAnyStateByUuidForLifecycle(winnerUuid, winnerUuid)
            ?: throw IllegalStateException("Claimed workspace root is missing: $winnerUuid")
    }
}
