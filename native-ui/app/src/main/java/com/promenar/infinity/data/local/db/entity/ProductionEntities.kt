package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "productions")
data class ProductionEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "root_path") val rootPath: String,
    val revision: Long,
    val snapshot: String,
    @ColumnInfo(name = "manifest_sha256") val manifestSha256: String?,
    @ColumnInfo(name = "manifest_error") val manifestError: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)

@Entity(tableName = "session_productions", foreignKeys = [
    ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["session_id"], onDelete = ForeignKey.CASCADE),
    ForeignKey(entity = ProductionEntity::class, parentColumns = ["id"], childColumns = ["production_id"], onDelete = ForeignKey.CASCADE),
], indices = [Index("production_id")])
data class SessionProductionEntity(
    @PrimaryKey @ColumnInfo(name = "session_id") val sessionId: String,
    @ColumnInfo(name = "production_id") val productionId: String,
    val active: Boolean,
    @ColumnInfo(name = "bound_at") val boundAt: Long,
)
