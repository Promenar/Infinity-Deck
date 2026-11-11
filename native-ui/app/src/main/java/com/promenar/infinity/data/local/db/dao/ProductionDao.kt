package com.promenar.infinity.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.promenar.infinity.data.local.db.entity.ProductionEntity
import com.promenar.infinity.data.local.db.entity.SessionProductionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProductionDao {
    @Query("SELECT * FROM productions WHERE id = :id") suspend fun get(id: String): ProductionEntity?
    @Query("SELECT productions.* FROM productions INNER JOIN session_productions ON productions.id = session_productions.production_id WHERE session_productions.session_id = :sessionId AND session_productions.active = 1")
    suspend fun getForSession(sessionId: String): ProductionEntity?
    @Query("SELECT productions.* FROM productions INNER JOIN session_productions ON productions.id = session_productions.production_id WHERE session_productions.session_id = :sessionId AND session_productions.active = 1")
    fun observeForSession(sessionId: String): Flow<ProductionEntity?>
    @Query("SELECT EXISTS(SELECT 1 FROM session_productions WHERE session_id = :sessionId)") fun observeEverBound(sessionId: String): Flow<Boolean>
    @Query("SELECT session_id FROM session_productions WHERE production_id = :id AND active = 1 ORDER BY bound_at LIMIT 1") suspend fun activeSession(id: String): String?
    @Query("SELECT session_id FROM session_productions WHERE production_id = :id AND active = 1 AND session_id != :sessionId LIMIT 1") suspend fun otherActiveSession(id: String, sessionId: String): String?
    @Insert suspend fun insert(value: ProductionEntity)
    @Update suspend fun update(value: ProductionEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun bind(value: SessionProductionEntity)
    @Query("UPDATE session_productions SET active = 0 WHERE session_id = :sessionId") suspend fun unbind(sessionId: String)
}
