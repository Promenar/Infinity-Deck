package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** 出现记录随其证据删除，避免撤回后继续参与计算。 */
@Entity(
    tableName = "kg_edge_mentions",
    foreignKeys = [
        ForeignKey(entity = KgEdgeEntity::class, parentColumns = ["id"], childColumns = ["edge_id"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = KgEvidenceEntity::class, parentColumns = ["id"], childColumns = ["evidence_id"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("edge_id"), Index("evidence_id"), Index("doc_id")],
)
data class KgEdgeMentionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "edge_id") val edgeId: String,
    @ColumnInfo(name = "evidence_id") val evidenceId: String,
    @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "window_index") val windowIndex: Int,
    @ColumnInfo(name = "char_start") val charStart: Int,
    @ColumnInfo(name = "char_end") val charEnd: Int,
    val kind: String,
    val family: String?,
    val quote: String?,
    @ColumnInfo(name = "task_id") val taskId: String,
    @ColumnInfo(name = "content_hash") val contentHash: String,
    @ColumnInfo(name = "position_coarse") val positionCoarse: Boolean,
    val flashback: Boolean,
)

@Entity(
    tableName = "kg_edge_validity",
    foreignKeys = [ForeignKey(entity = KgEdgeEntity::class, parentColumns = ["id"], childColumns = ["edge_id"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("edge_id"), Index("collection_id")],
)
data class KgEdgeValidityEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "edge_id") val edgeId: String,
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "from_doc_id") val fromDocId: String,
    @ColumnInfo(name = "from_window") val fromWindow: Int,
    @ColumnInfo(name = "from_char") val fromChar: Int,
    @ColumnInfo(name = "to_doc_id") val toDocId: String?,
    @ColumnInfo(name = "to_window") val toWindow: Int?,
    @ColumnInfo(name = "to_char") val toChar: Int?,
)

/** 不同实体的用户判定以记录保存，后续模型判定不得覆盖。 */
@Entity(
    tableName = "kg_entity_links",
    foreignKeys = [
        ForeignKey(entity = KgNodeEntity::class, parentColumns = ["id"], childColumns = ["from_node"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = KgNodeEntity::class, parentColumns = ["id"], childColumns = ["to_node"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("from_node"), Index("to_node"), Index(value = ["collection_id", "from_node", "to_node"], unique = true)],
)
data class KgEntityLinkEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "from_node") val fromNode: String,
    @ColumnInfo(name = "to_node") val toNode: String,
    val verdict: String,
    @ColumnInfo(name = "decided_by") val decidedBy: String,
    @ColumnInfo(name = "model_id") val modelId: String?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
