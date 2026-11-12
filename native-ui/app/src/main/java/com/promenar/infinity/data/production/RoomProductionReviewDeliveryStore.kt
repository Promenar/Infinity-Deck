package com.promenar.infinity.data.production

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.repository.ProductionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 活跃绑定复用制作仓库的清单同步；无绑定的保留项目只更新数据库快照。 */
class RoomProductionReviewDeliveryStore(
    private val database: InfinityDatabase,
    private val repository: ProductionRepository,
) : ProductionReviewDeliveryStore {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    override suspend fun get(sessionId: String) = repository.get(sessionId)
    override suspend fun getById(productionId: String): ProductionSnapshot? = withContext(Dispatchers.IO) {
        database.productionDao().get(productionId)?.let { row ->
            ProductionSnapshot(json.decodeFromString<Production>(row.snapshot), row.revision, ManifestSync.Pending)
        }
    }
    override suspend fun apply(sessionId: String, productionId: String, revision: Long, action: ProductionUserAction) = withContext(Dispatchers.IO) {
        val dao = database.productionDao()
        val active = dao.activeSession(productionId)
        if (action is ProductionUserAction.MarkReviewQueued && active != sessionId) throw ProductionRevisionConflictException()
        if (active != null) {
            try { repository.applyForProduction(active, productionId, revision, action) }
            catch (failure: IllegalArgumentException) {
                if (dao.get(productionId) == null) return@withContext
                if (dao.activeSession(productionId) != active) throw ProductionRevisionConflictException()
                throw failure
            }
        } else {
            // 无绑定项目仅核对数据库；回执不等待清单写入，也不访问已经删除的工作区。
            repository.get(sessionId)
            val next = database.withTransaction {
                if (dao.activeSession(productionId) != null) throw ProductionRevisionConflictException()
                val row = dao.get(productionId) ?: return@withTransaction null
                val snapshot = ProductionSnapshot(json.decodeFromString<Production>(row.snapshot), row.revision, ManifestSync.Pending)
                val updated = ProductionUserEngine.apply(snapshot, revision, action, System.currentTimeMillis())
                dao.update(row.copy(revision = updated.revision, snapshot = json.encodeToString(updated.production), updatedAt = updated.production.updatedAt))
                updated
            } ?: return@withContext
            // 复读数据库核对回执结果；清单同步仅合并清单字段，保留已提交的业务修订。
            repository.get(sessionId)
            val committed = dao.get(productionId) ?: return@withContext
            val production = json.decodeFromString<Production>(committed.snapshot)
            val settled = when (action) {
                is ProductionUserAction.MarkReviewSent -> production.nextReviewRound > action.round && production.queuedReviewRound != action.round
                is ProductionUserAction.RevertReviewQueued -> production.queuedReviewRound != action.round
                else -> error("无绑定项目只能处理审核回执。")
            }
            if (committed.revision < next.revision || !settled) throw ProductionRevisionConflictException()
        }
        Unit
    }
    override suspend fun queuedProjects(): List<Pair<String, String?>> = withContext(Dispatchers.IO) {
        val ids = database.query(SimpleSQLiteQuery("SELECT id, snapshot FROM productions")).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (runCatching { json.decodeFromString<Production>(cursor.getString(1)).queuedReviewRound }.getOrNull() != null) add(cursor.getString(0))
                }
            }
        }
        ids.map { id ->
            val session = database.query(SimpleSQLiteQuery(
                "SELECT session_id FROM session_productions WHERE production_id = ? ORDER BY active DESC, bound_at DESC LIMIT 1", arrayOf(id),
            )).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            id to session
        }
    }
}
