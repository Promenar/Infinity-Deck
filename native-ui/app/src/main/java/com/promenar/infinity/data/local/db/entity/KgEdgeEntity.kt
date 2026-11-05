package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.promenar.infinity.data.local.db.KgIdentity

@Entity(
    tableName = "kg_edges",
    foreignKeys = [
        ForeignKey(entity = KgNodeEntity::class, parentColumns = ["collection_id", "id"], childColumns = ["collection_id", "source_id"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = KgNodeEntity::class, parentColumns = ["collection_id", "id"], childColumns = ["collection_id", "target_id"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("source_id"), Index("target_id"), Index(value = ["collection_id", "target_id"]), Index(value = ["collection_id", "source_id", "target_id", "normalized_relation"], unique = true)],
)
data class KgEdgeEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "source_id") val sourceId: String,
    @ColumnInfo(name = "target_id") val targetId: String,
    val relation: String,
    val weight: Double = 1.0,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "normalized_relation") val normalizedRelation: String = KgIdentity.normalize(relation),
    @ColumnInfo(name = "collection_id") val collectionId: String = "",
)
