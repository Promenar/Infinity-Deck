package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.promenar.infinity.data.local.db.KgIdentity

/** 实体身份限定在所属图谱；来源生命周期由 kg_evidence 管理。 */
@Entity(tableName = "kg_nodes", indices = [
    Index(value = ["collection_id", "normalized_name", "type"], unique = true),
    Index(value = ["collection_id", "id"], unique = true),
])
data class KgNodeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String = "concept",
    val metadata: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,
    @ColumnInfo(name = "normalized_name") val normalizedName: String = KgIdentity.normalize(name),
    @ColumnInfo(name = "collection_id") val collectionId: String = "",
)
