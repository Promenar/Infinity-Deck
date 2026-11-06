package com.promenar.infinity.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "kg_collections")
data class KgCollectionEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    @ColumnInfo(name = "root_ref") val rootRef: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Entity(tableName = "kg_folder_settings")
data class KgFolderSettingEntity(
    @PrimaryKey @ColumnInfo(name = "folder_id") val folderId: String,
    val mode: String,
)

@Entity(tableName = "kg_document_settings")
data class KgDocumentSettingEntity(
    @PrimaryKey @ColumnInfo(name = "doc_id") val docId: String,
    val mode: String,
    @ColumnInfo(name = "target_collection_id") val targetCollectionId: String? = null,
)

@Entity(tableName = "kg_collection_members", indices = [Index("collection_id")])
data class KgCollectionMemberEntity(
    @PrimaryKey @ColumnInfo(name = "doc_id") val docId: String,
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "order_key") val orderKey: String? = null,
    @ColumnInfo(name = "order_source") val orderSource: String? = null,
)
