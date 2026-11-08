package com.promenar.infinity.data.backup

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.AgentRuleRevisionEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity
import com.promenar.infinity.data.local.db.entity.ArtifactEntity
import com.promenar.infinity.data.local.db.entity.AttachmentEntity
import com.promenar.infinity.data.local.db.entity.ContextSummaryEntity
import com.promenar.infinity.data.local.db.entity.CustomSkillEntity
import com.promenar.infinity.data.local.db.entity.DocumentTagEntity
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.ExternalSnapshotEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.McpServerEntity
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.TagEntity
import com.promenar.infinity.data.local.db.entity.TaskNodeEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationType
import com.promenar.infinity.data.model.KgNode
import com.promenar.infinity.data.model.KgPath
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageDocumentAttachment
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.toDomain
import com.promenar.infinity.data.model.toEntity
import com.promenar.infinity.domain.media.MediaKind
import com.promenar.infinity.domain.media.MediaRef
import com.promenar.infinity.domain.media.MediaViewMode
import com.promenar.infinity.domain.media.ViewedMedia
import com.promenar.infinity.data.security.SecretCatalog
import com.promenar.infinity.data.security.SecretId
import com.promenar.infinity.data.security.SecretStore
import com.promenar.infinity.data.repository.FileOperationRepository
import com.promenar.infinity.data.repository.PersistentFileIdentity
import com.promenar.infinity.data.repository.PathWorkspaceTreeScanner
import com.promenar.infinity.data.repository.TestWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceRepository
import com.promenar.infinity.data.repository.ToolExecutionKey
import com.promenar.infinity.data.repository.ToolExecutionLedgerRepository
import com.promenar.infinity.data.repository.ToolInvocationIdentityFactory
import com.promenar.infinity.data.repository.ToolInvocationIdentityResolution
import com.promenar.infinity.data.repository.ToolLedgerState
import com.promenar.infinity.data.generation.SessionToolResolver
import com.promenar.infinity.data.storage.JvmBoundRealPathFileSystem
import com.promenar.infinity.data.model.ToolCall
import com.promenar.infinity.data.model.ToolResult
import com.promenar.infinity.ui.chat.ChatStore
import com.promenar.infinity.ui.chat.manager.MessageManager
import com.promenar.infinity.ui.chat.manager.ToolExecutor
import com.promenar.infinity.ui.chat.manager.registry.SkillDefinition
import com.promenar.infinity.ui.chat.manager.registry.SkillExecutionContext
import com.promenar.infinity.ui.chat.manager.registry.SkillRegistry
import com.promenar.infinity.ui.chat.manager.registry.toProtocolTool
import com.promenar.infinity.domain.repository.WriteResult
import com.promenar.infinity.domain.workspace.WorkspaceInternalPaths
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomBackupDataSourceTest {
    @Test
    fun `完整备份保留图谱来源向量字节全文索引和完成标记`() = runBlocking {
        seedCompleteGraph()
        val sqlite = db.openHelper.writableDatabase
        preferences.snapshot = preferences.snapshot.copy(entries = preferences.snapshot.entries +
            BackupPreferenceEntry("settings", "preset_embedding_model", "model-fixture"))
        sqlite.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('work:file-1','work','文档','file-1',100)")
        sqlite.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('file-1','work:file-1')")
        sqlite.execSQL("INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES('n1','甲','person','甲',100,'work:file-1'),('n2','乙','person','乙',100,'work:file-1')")
        sqlite.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,normalized_relation,created_at,collection_id) VALUES('e1','n1','n2','认识',0.8,'认识',100,'work:file-1')")
        sqlite.execSQL("INSERT INTO kg_evidence(id,node_id,source_type,doc_id,content_hash,task_id,created_at,workspace_document) VALUES('p1','n1','document','file-1','','doc-task',100,1),('p2','n2','document','file-1','','doc-task',100,1)")
        sqlite.execSQL("INSERT INTO kg_evidence(id,edge_id,source_type,doc_id,content_hash,task_id,created_at,workspace_document) VALUES('p3','e1','document','file-1','','doc-task',100,1)")
        sqlite.execSQL("INSERT INTO kg_edge_mentions(id,edge_id,evidence_id,doc_id,window_index,char_start,char_end,kind,family,quote,task_id,content_hash,position_coarse,flashback) VALUES('mention','e1','p3','file-1',0,1,3,'state','relationship','认识','doc-task','',0,0)")
        sqlite.execSQL("INSERT INTO kg_edge_validity(id,edge_id,collection_id,from_doc_id,from_window,from_char) VALUES('validity','e1','work:file-1','file-1',0,1)")
        sqlite.execSQL("INSERT INTO kg_entity_links(id,collection_id,from_node,to_node,verdict,decided_by,created_at) VALUES('link','work:file-1','n1','n2','same','user',100)")
        com.promenar.infinity.data.rag.KgTimelineStore(db).recompute("work:file-1")
        fun timelineRows(): Map<String, List<List<String?>>> = listOf("kg_edge_mentions", "kg_edge_validity", "kg_entity_links").associateWith { table ->
            sqlite.query("SELECT * FROM `$table` ORDER BY id").use { cursor -> buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { index -> if (cursor.isNull(index)) null else cursor.getString(index) })
            } }
        }
        val beforeTimeline = timelineRows()
        val embedding = byteArrayOf(0, 0, 0, 1, 2, 3, 4, 5)
        sqlite.execSQL("INSERT INTO vectors(id,doc_id,file_uuid,content,embedding,metadata,created_at,stale,version) VALUES(?,?,?,?,?,?,?,0,1)", arrayOf<Any?>("v1", "file-1", "file-1", "restorable keyword", embedding, "{\"type\":\"document\"}", 100L))
        sqlite.execSQL("INSERT INTO vectors(id,session_id,content,embedding,metadata,start_message_id,end_message_id,created_at,stale,version) VALUES(?,?,?,?,?,?,?,?,0,1)", arrayOf<Any?>("v2", "session-1", "memory keyword", embedding, "{\"type\":\"memory\",\"embeddingSpaceKey\":\"fixture-space\"}", "message-1", "message-1", 100L))
        val source = newDataSource()
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        assertThat(snapshot.embedding?.modelId).isEqualTo("model-fixture")
        assertThat(snapshot.embedding?.dimension).isEqualTo(2)
        db.clearAllTables()
        source.restore(validated(snapshot))
        assertThat(rowCount("kg_nodes")).isEqualTo(2)
        assertThat(rowCount("kg_edges")).isEqualTo(1)
        assertThat(rowCount("kg_edge_mentions")).isEqualTo(1)
        assertThat(rowCount("kg_edge_validity")).isEqualTo(1)
        assertThat(rowCount("kg_entity_links")).isEqualTo(1)
        assertThat(timelineRows()).isEqualTo(beforeTimeline)
        sqlite.query("SELECT id FROM kg_evidence ORDER BY id").use { cursor ->
            val ids = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
            assertThat(ids).containsExactly("p1", "p2", "p3")
        }
        sqlite.query("SELECT embedding FROM vectors WHERE id='v1'").use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            assertThat(cursor.getBlob(0)).isEqualTo(embedding)
        }
        assertThat(rowCount("vectors")).isEqualTo(2)
        assertThat(db.vectorDao().getByDocId("file-1").map { it.id }).containsExactly("v1")
        sqlite.query("SELECT COUNT(*) FROM vectors_fts WHERE vectors_fts MATCH 'keyword'").use { cursor ->
            check(cursor.moveToFirst()); assertThat(cursor.getInt(0)).isEqualTo(2)
        }
        assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")!!.vectorizedAt).isEqualTo(100L)
        assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")!!.kgExtractedAt).isEqualTo(100L)
        assertThat(db.messageDao().getById("message-1")!!.vectorizationStatus).isEqualTo("completed")
        val events = mutableListOf<com.promenar.infinity.data.rag.FileIndexEvent>()
        val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
        val fileOperations = FileOperationRepository(db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps(),
            indexEventSink = com.promenar.infinity.data.rag.FileIndexEventSink { events += it })
        assertThat(fileOperations.writeFileAtomic("root-1", "file-1", "complete-graph", "session-1", file.hash)).isInstanceOf(WriteResult.Success::class.java)
        assertThat(events).isEmpty()
        snapshot.wipe()
    }

    @Test
    fun `仅恢复知识层丢弃会话来源证据和向量并回收孤立实体`() = runBlocking {
        seedCompleteGraph()
        val knowledgeRoot = Files.createDirectory(sourceBase.resolve("knowledge-root"))
        val bytes = "knowledge contents".toByteArray()
        Files.write(knowledgeRoot.resolve("note.txt"), bytes)
        db.sessionDao().insert(SessionEntity("knowledge-session", BackupLayerPolicy.KNOWLEDGE_AGENT,
            workspacePath = knowledgeRoot.toString(), workspaceRootUuid = "knowledge-root", createdAt = 100, updatedAt = 100))
        db.fileEntryDao().insert(FileEntry(uuid = "knowledge-root", workspaceRootUuid = "knowledge-root", parentUuid = null, name = "存储空间",
            hash = "", isDirectory = true, physicalRootPath = knowledgeRoot.toString(), materializedPath = "/", createdAt = 100, updatedAt = 100))
        db.fileEntryDao().insert(FileEntry(uuid = "knowledge-file", workspaceRootUuid = "knowledge-root", parentUuid = "knowledge-root", name = "note.txt",
            hash = sha256(bytes), sizeBytes = bytes.size.toLong(), mimeType = "text/plain", physicalRootPath = knowledgeRoot.toString(), materializedPath = "/note.txt", vectorizedAt = 100, kgExtractedAt = 100, createdAt = 100, updatedAt = 100))
        preferences.snapshot = preferences.snapshot.copy(entries = preferences.snapshot.entries + BackupPreferenceEntry("settings", "preset_embedding_model", "model-fixture"))
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('work:knowledge-file','work','资料','knowledge-file',100)")
        sqlite.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('knowledge-file','work:knowledge-file')")
        sqlite.execSQL("INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES('doc-node','文档','concept','文档',100,'work:knowledge-file'),('session-node','会话','concept','会话',100,'work:knowledge-file')")
        sqlite.execSQL("INSERT INTO kg_evidence(id,node_id,source_type,doc_id,content_hash,task_id,created_at,workspace_document) VALUES('doc-proof','doc-node','document','knowledge-file','','doc-task',100,1)")
        sqlite.execSQL("INSERT INTO kg_evidence(id,node_id,source_type,session_id,message_id,content_hash,task_id,created_at,workspace_document) VALUES('session-proof','session-node','session','session-1','message-1','','turn-task',100,0)")
        sqlite.execSQL("INSERT INTO vectors(id,doc_id,file_uuid,content,embedding,metadata,created_at,stale,version) VALUES('doc-vector','knowledge-file','knowledge-file','keyword',X'00000001',?,100,0,1)", arrayOf("{\"type\":\"document\"}"))
        sqlite.execSQL("INSERT INTO vectors(id,session_id,content,embedding,created_at,stale,version) VALUES('memory-vector','session-1','memory',X'00000001',100,0,1)")
        val cache = Files.createTempDirectory(sourceBase, "knowledge-graph-backup")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.KNOWLEDGE), cache)
            val file = cache.resolve("knowledge.iwb")
            try { Files.newOutputStream(file).use { DefaultBackupPackageCodec().encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            db.clearAllTables()
            Files.newInputStream(file).use { DefaultBackupPackageCodec().decodeFrom(it, null, cache).use { backup -> source.restore(backup) } }
            sqlite.query("SELECT id FROM kg_nodes").use { cursor ->
                val ids = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertThat(ids).containsExactly("doc-node")
            }
            sqlite.query("SELECT id FROM kg_evidence").use { cursor ->
                val ids = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertThat(ids).containsExactly("doc-proof")
            }
            sqlite.query("SELECT id FROM vectors").use { cursor ->
                val ids = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                assertThat(ids).containsExactly("doc-vector")
            }
            assertThat(db.vectorDao().getByDocId("knowledge-file").map { it.id }).containsExactly("doc-vector")
            assertThat(db.fileEntryDao().getByUuid("knowledge-root", "knowledge-file")!!.vectorizedAt).isEqualTo(100L)
            assertThat(db.fileEntryDao().getUnvectorizedSupportedFiles(listOf("text/plain")).map { it.uuid }).doesNotContain("knowledge-file")
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `嵌入模型不一致时恢复向量不参与检索并清除完成标记`() = runBlocking {
        seedCompleteGraph()
        preferences.snapshot = preferences.snapshot.copy(entries = preferences.snapshot.entries + BackupPreferenceEntry("settings", "preset_embedding_model", "model-a"))
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO vectors(id,doc_id,file_uuid,content,embedding,metadata,created_at,stale,version) VALUES('v1','file-1','file-1','keyword',X'00000001',?,100,0,1)", arrayOf("{\"type\":\"document\"}"))
        sqlite.execSQL("INSERT INTO vectors(id,session_id,content,embedding,metadata,start_message_id,end_message_id,created_at,stale,version) VALUES('v2','session-1','memory',X'00000001',?,'message-1','message-1',100,0,1)", arrayOf("{\"type\":\"memory\",\"embeddingSpaceKey\":\"fixture-space\"}"))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val backup = validated(snapshot)
        val mismatched = ValidatedBackup(backup.manifest.copy(embedding = snapshot.embedding!!.copy(modelId = "model-b")),
            backup.database, backup.preferences, backup.files, backup.secrets)
        db.clearAllTables()
        newDataSource().restore(mismatched)
        assertThat(db.vectorDao().getByDocId("file-1")).isEmpty()
        assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")!!.vectorizedAt).isNull()
        assertThat(db.vectorDao().reviveRestoredMemory()).isEqualTo(0)
        assertThat(db.vectorDao().getBySessionId("session-1")).isEmpty()
        sqlite.query("SELECT stale,hex(embedding) FROM vectors WHERE id='v2'").use {
            check(it.moveToFirst()); assertThat(it.getInt(0)).isEqualTo(2); assertThat(it.getString(1)).isEqualTo("00000001")
        }
        snapshot.wipe()
    }

    @Test
    fun `备份校验接受图谱向量表并拒绝未知表`() = runBlocking {
        seedCompleteGraph()
        val source = newDataSource()
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        source.validateStaged(validated(snapshot))
        val payload = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = payload.getValue("tables").jsonObject
        val altered = JsonObject(payload + ("tables" to JsonObject(tables + ("unknown_table" to JsonArray(emptyList())))))
        assertThat(runCatching { source.validateStaged(validated(snapshot, database = altered.toString().toByteArray())) }.exceptionOrNull())
            .isInstanceOf(BackupValidationException::class.java)
        val missing = JsonObject(payload + ("tables" to JsonObject(tables - "kg_nodes")))
        assertThat(runCatching { source.restore(validated(snapshot, database = missing.toString().toByteArray())) }.exceptionOrNull())
            .isInstanceOf(BackupValidationException::class.java)
        snapshot.wipe()
    }

    @Test
    fun `v15备份仍清空原有图谱向量和完成标记`() = runBlocking {
        seedCompleteGraph()
        val source = newDataSource()
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        val payload = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val newTables = setOf("kg_collections", "kg_collection_members", "kg_nodes", "kg_edges", "kg_evidence", "vectors")
        val old = JsonObject(payload + mapOf(
            "schemaVersion" to JsonPrimitive(15),
            "tables" to JsonObject(payload.getValue("tables").jsonObject - newTables),
        ))
        seedDerivedRows()
        val current = validated(snapshot, database = old.toString().toByteArray())
        val legacy = ValidatedBackup(current.manifest.copy(databaseSchemaVersion = 15, embedding = null),
            current.database, current.preferences, current.files, current.secrets)
        source.restore(legacy)
        assertThat(rowCount("kg_nodes")).isEqualTo(0)
        assertThat(rowCount("kg_edges")).isEqualTo(0)
        assertThat(rowCount("kg_evidence")).isEqualTo(0)
        assertThat(rowCount("vectors")).isEqualTo(0)
        assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")!!.vectorizedAt).isNull()
        snapshot.wipe()
    }

    @Test
    fun `完整备份往返文件夹和文档图谱设置`() = runBlocking {
        seedCompleteGraph()
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO kg_folder_settings(folder_id,mode) VALUES('root-1','SHARED'),('missing','WORK'),('file-1','DOC_COLLECTION:work:root-1')")
        val source = newDataSource()
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        val payload = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = payload.getValue("tables").jsonObject
        assertThat(tables.getValue("kg_folder_settings").jsonArray).hasSize(2)
        listOf("kg_collections", "kg_collection_members", "kg_nodes", "kg_edges", "kg_evidence", "vectors").forEach {
            assertThat(tables.containsKey(it)).isTrue()
        }
        assertThat(tables.containsKey("kg_jit_cache")).isFalse()
        db.clearAllTables()
        source.restore(validated(snapshot))
        sqlite.query("SELECT folder_id,mode FROM kg_folder_settings ORDER BY folder_id").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("file-1")
            assertThat(it.getString(1)).isEqualTo("DOC_COLLECTION:work:root-1")
            assertThat(it.moveToNext()).isTrue()
            assertThat(it.getString(0)).isEqualTo("root-1")
            assertThat(it.getString(1)).isEqualTo("SHARED")
            assertThat(it.moveToNext()).isFalse()
        }
        snapshot.wipe()
    }

    @Test
    fun `分层备份按文件所在层恢复文件夹设置`() = runBlocking {
        seedCompleteGraph()
        val root = requireNotNull(db.fileEntryDao().getByUuid("root-1", "root-1"))
        db.fileEntryDao().insert(root.copy(uuid = "knowledge-root", workspaceRootUuid = "knowledge-root"))
        db.sessionDao().insert(SessionEntity(id = "knowledge-session", agentId = "__system__", workspaceRootUuid = "knowledge-root", createdAt = 100, updatedAt = 100))
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO kg_folder_settings(folder_id,mode) VALUES('root-1','WORK'),('knowledge-root','SHARED')")
        val cache = Files.createTempDirectory(sourceBase, "folder-settings")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.WORKSPACE_FILES), cache)
            val file = cache.resolve("settings.iwb")
            try { Files.newOutputStream(file).use { DefaultBackupPackageCodec().encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            sqlite.execSQL("UPDATE kg_folder_settings SET mode='MERGE_PARENT'")
            Files.newInputStream(file).use { DefaultBackupPackageCodec().decodeFrom(it, null, cache).use { backup -> source.restore(backup) } }
            sqlite.query("SELECT folder_id,mode FROM kg_folder_settings ORDER BY folder_id").use {
                assertThat(it.moveToFirst()).isTrue()
                assertThat(it.getString(0)).isEqualTo("knowledge-root")
                assertThat(it.getString(1)).isEqualTo("MERGE_PARENT")
                assertThat(it.moveToNext()).isTrue()
                assertThat(it.getString(0)).isEqualTo("root-1")
                assertThat(it.getString(1)).isEqualTo("WORK")
                assertThat(it.moveToNext()).isFalse()
            }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `旧版备份没有文件夹设置时可以恢复`() = runBlocking {
        seedCompleteGraph()
        val source = newDataSource()
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        val payload = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val old = JsonObject(payload + ("schemaVersion" to JsonPrimitive(14)) +
            ("tables" to JsonObject(payload.getValue("tables").jsonObject - "kg_folder_settings")))
        db.clearAllTables()
        source.restore(validated(snapshot, database = old.toString().toByteArray(Charsets.UTF_8)))
        assertThat(rowCount("kg_folder_settings")).isEqualTo(0)
        assertThat(db.fileEntryDao().getByUuid("root-1", "root-1")).isNotNull()
        snapshot.wipe()
    }

    @Test
    fun `旧版分层备份无文件夹设置也可以恢复`() = runBlocking {
        seedCompleteGraph()
        val cache = Files.createTempDirectory(sourceBase, "legacy-folder-settings")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.WORKSPACE_FILES), cache)
            val legacy = snapshot.copy(databaseSchemaVersion = 14,
                streamEntries = snapshot.streamEntries.filterNot { it.path.startsWith("database/kg_folder_settings/") })
            val file = cache.resolve("legacy.iwb")
            try { Files.newOutputStream(file).use { DefaultBackupPackageCodec().encodeTo(legacy, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            db.clearAllTables()
            Files.newInputStream(file).use { DefaultBackupPackageCodec().decodeFrom(it, null, cache).use { backup -> source.restore(backup) } }
            assertThat(rowCount("kg_folder_settings")).isEqualTo(0)
            assertThat(db.fileEntryDao().getByUuid("root-1", "root-1")).isNotNull()
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `仅恢复会话层时恢复对话引用且文档缺失时报告降级`() = runBlocking {
        seedCompleteGraph()
        db.knowledgeCitationDao().insertAll(listOf(com.promenar.infinity.data.library.ask.KnowledgeCitationEntity(
            "citation-session", "file-1", 1, "vector-1", "CHAT", "session-1", "message-1", null, "K1", 100,
        )))
        val cache = Files.createTempDirectory(sourceBase, "session-citation")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.SESSIONS), cache)
            val file = cache.resolve("sessions.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            db.knowledgeCitationDao().deleteForMessage("message-1")
            Files.newInputStream(file).use { input ->
                codec.decodeFrom(input, null, cache).use { source.validateStaged(it); source.restoreWithResult(it, "session-citation-1") }
            }
            assertThat(rowCount("knowledge_citations")).isEqualTo(1)
            db.openHelper.writableDatabase.execSQL("DELETE FROM workspace_files WHERE uuid = 'file-1'")
            val result = Files.newInputStream(file).use { input ->
                codec.decodeFrom(input, null, cache).use { source.validateStaged(it); source.restoreWithResult(it, "session-citation-2") }
            }
            assertThat(rowCount("knowledge_citations")).isEqualTo(0)
            assertThat(result.summary.degraded.map { it.category }).contains("citation_document_unavailable")
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `旧版分层备份保留未改变会话的本地引用并报告变化来源`() = runBlocking {
        seedCompleteGraph()
        db.knowledgeCitationDao().insertAll(listOf(com.promenar.infinity.data.library.ask.KnowledgeCitationEntity(
            "legacy-local", "file-1", 1, "vector-1", "CHAT", "session-1", "message-1", null, "K1", 100,
        )))
        val cache = Files.createTempDirectory(sourceBase, "legacy-session-citation")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.SESSIONS), cache)
            val legacy = snapshot.copy(databaseSchemaVersion = 13,
                streamEntries = snapshot.streamEntries.filterNot { it.path.startsWith("database/knowledge_citations/") })
            val file = cache.resolve("legacy-sessions.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(legacy, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            Files.newInputStream(file).use { input ->
                codec.decodeFrom(input, null, cache).use { source.validateStaged(it); source.restoreWithResult(it, "legacy-citation-1") }
            }
            assertThat(rowCount("knowledge_citations")).isEqualTo(1)
            db.openHelper.writableDatabase.execSQL("UPDATE messages SET content = '变更后的回答' WHERE id = 'message-1'")
            val result = Files.newInputStream(file).use { input ->
                codec.decodeFrom(input, null, cache).use { source.validateStaged(it); source.restoreWithResult(it, "legacy-citation-2") }
            }
            assertThat(rowCount("knowledge_citations")).isEqualTo(0)
            assertThat(result.summary.degraded.map { it.category }).contains("legacy_citation_source_changed")
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `旧版文件层恢复保留仍存在文档的问资料引用`() = runBlocking {
        seedCompleteGraph()
        db.knowledgeCitationDao().insertAll(listOf(com.promenar.infinity.data.library.ask.KnowledgeCitationEntity(
            "legacy-ask", "file-1", 1, "vector-1", "ASK", null, null, "问题", "1", 100,
        )))
        val cache = Files.createTempDirectory(sourceBase, "legacy-file-citation")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.WORKSPACE_FILES), cache)
            val legacy = snapshot.copy(databaseSchemaVersion = 13,
                streamEntries = snapshot.streamEntries.filterNot { it.path.startsWith("database/knowledge_citations/") })
            val file = cache.resolve("legacy-files.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(legacy, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            Files.newInputStream(file).use { input ->
                codec.decodeFrom(input, null, cache).use { source.validateStaged(it); source.restoreWithResult(it, "legacy-file-citation") }
            }
            assertThat(rowCount("knowledge_citations")).isEqualTo(1)
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `未选密钥层时核心导出恢复不读取或准备密钥事务`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        val cache = Files.createTempDirectory(sourceBase, "unselected-secrets")
        val id = SecretCatalog.providerApiKey("p1")
        val before = secrets.get(id)!!
        secrets.failSnapshot = true
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE), cache)
            val file = cache.resolve("core.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            Files.newInputStream(file).use { codec.decodeFrom(it, null, cache).use { source.restore(it) } }
            assertThat(secrets.get(id)).isEqualTo(before)
            assertThat(secrets.receivedPlaintextRefs).isEmpty()
        } finally { before.fill(0); secrets.failSnapshot = false; BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `可选文件超过单项协议上限时明确失败`() = runBlocking {
        val graph = seedCompleteGraph()
        val size = BackupPackageLimits.MAX_ENTRY_BYTES + 1
        java.io.RandomAccessFile(graph.filePath.toFile(), "rw").use { it.setLength(size) }
        db.openHelper.writableDatabase.execSQL("UPDATE workspace_files SET size_bytes = ?,hash = '' WHERE uuid = 'file-1'", arrayOf<Any?>(size))
        val cache = Files.createTempDirectory(sourceBase, "entry-overflow")
        try {
            val error = try { newDataSource().snapshotTo(setOf(BackupLayer.CORE, BackupLayer.WORKSPACE_FILES), cache); null }
                catch (error: BackupValidationException) { error }
            assertThat(error?.reason).isEqualTo(BackupFailureReason.CAPACITY_EXCEEDED)
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `仅恢复工作区文件时保留会话Artifact路径同步且文件可达`() = runBlocking {
        seedCompleteGraph()
        db.knowledgeCitationDao().insertAll(listOf(com.promenar.infinity.data.library.ask.KnowledgeCitationEntity(
            "citation-files", "file-1", 1, "vector-1", "CHAT", "session-1", "message-1", null, "K1", 100,
        )))
        val cache = Files.createTempDirectory(sourceBase, "retained-artifact")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.WORKSPACE_FILES), cache)
            val file = cache.resolve("files.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            Files.newInputStream(file).use { codec.decodeFrom(it, null, cache).use { source.restore(it) } }
            assertThat(rowCount("knowledge_citations")).isEqualTo(1)
            val root = db.sessionDao().getById("session-1")!!.workspacePath!!
            db.openHelper.writableDatabase.query("SELECT workspace_path FROM artifacts WHERE id = 'artifact-1'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                val path = Path.of(cursor.getString(0))
                assertThat(path.startsWith(Path.of(root))).isTrue()
                assertThat(Files.exists(path)).isTrue()
            }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `核心恢复清除图谱并保留未选层既有向量`() = runBlocking {
        seedCompleteGraph()
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("INSERT INTO vectors(id,doc_id,content,embedding,created_at,stale,version) VALUES(?,?,?,?,?,?,?)", arrayOf<Any?>("retained-vector", "file-1", "索引", byteArrayOf(1, 2), 100L, 0, 1))
        sqlite.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('work:file-1','work','a.txt','file-1',100)")
        sqlite.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('file-1','work:file-1')")
        sqlite.execSQL("INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES('retained-node','节点','concept','节点',100,'work:file-1')")
        sqlite.execSQL("INSERT INTO kg_evidence(id,node_id,source_type,doc_id,content_hash,task_id,created_at,workspace_document) VALUES('retained-evidence','retained-node','document','file-1','hash','fixture',100,1)")
        val cache = Files.createTempDirectory(sourceBase, "retained-index")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE), cache)
            val file = cache.resolve("core.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            Files.newInputStream(file).use { codec.decodeFrom(it, null, cache).use { source.restore(it) } }
            assertThat(rowCount("vectors")).isEqualTo(1)
            assertThat(rowCount("kg_evidence")).isEqualTo(0)
            sqlite.query("SELECT vectorized_at,kg_extracted_at FROM workspace_files WHERE uuid = 'file-1'").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(100L)
                assertThat(cursor.isNull(1)).isTrue()
            }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `核心恢复保留缺失的未选文件登记与会话工具账本`() = runBlocking {
        val graph = seedCompleteGraph()
        val cache = Files.createTempDirectory(sourceBase, "retained-files")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE), cache)
            val file = cache.resolve("core.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            Files.delete(graph.filePath)
            db.openHelper.writableDatabase.execSQL("INSERT INTO tool_execution_ledger(session_id,assistant_message_id,tool_call_id,tool_name,requires_approval,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                arrayOf<Any?>("session-1", "message-1", "waiting", "ask_user", 0, "WAITING_INPUT", 100L, 100L))
            Files.newInputStream(file).use { codec.decodeFrom(it, null, cache).use { source.restore(it) } }
            assertThat(rowCount("tool_execution_ledger")).isEqualTo(1)
            assertThat(rowCount("workspace_files")).isEqualTo(2)
            assertThat(db.sessionDao().getById("session-1")!!.workspaceRootUuid).isEqualTo("root-1")
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `遗漏文件层只对实际缺失引用计数且摘要进入完成回执`() = runBlocking {
        seedCompleteGraph()
        db.openHelper.writableDatabase.execSQL("UPDATE messages SET rag_references = ? WHERE id = ?", arrayOf<Any?>("[{\"documentId\":\"missing-knowledge\"}]", "message-1"))
        val cache = Files.createTempDirectory(sourceBase, "degraded-summary")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.SESSIONS), cache)
            val file = cache.resolve("session.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            db.clearAllTables()
            val result = Files.newInputStream(file).use { codec.decodeFrom(it, null, cache).use { source.restoreWithResult(it, "degraded-op") } }
            assertThat(result.summary.degraded).contains(BackupDegradedReference("session_workspace_unavailable", 1))
            assertThat(result.summary.degraded).contains(BackupDegradedReference("knowledge_document_unavailable", 1))
            assertThat(source.completedRestoreSummary("degraded-op")).isEqualTo(result.summary)
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `现有编码器产生的v2全量包可以经流式入口恢复`() = runBlocking {
        val snapshot = emptyWorkspaceBackup()
        val codec = DefaultBackupPackageCodec()
        val encoded = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        val root = Files.createTempDirectory(sourceBase, "legacy-v2")
        val file = root.resolve("legacy.iwb")
        try {
            java.util.zip.ZipInputStream(encoded.inputStream()).use { source ->
                java.util.zip.ZipOutputStream(Files.newOutputStream(file)).use { zip ->
                    while (true) {
                        val entry = source.nextEntry ?: break
                        val bytes = source.readBytes()
                        try {
                            val value = if (entry.name == "manifest.json") bytes.toString(Charsets.UTF_8).replace("\"formatVersion\":3", "\"formatVersion\":2").toByteArray() else bytes
                            try { zip.putNextEntry(java.util.zip.ZipEntry(entry.name)); zip.write(value); zip.closeEntry() }
                            finally { if (value !== bytes) value.fill(0) }
                        } finally { bytes.fill(0) }
                    }
                }
            }
            db.clearAllTables()
            Files.newInputStream(file).use { input -> codec.decodeFrom(input, null, root).use { backup ->
                assertThat(backup.manifest.formatVersion).isEqualTo(2)
                newDataSource().restore(backup)
            } }
            assertThat(db.agentDao().getAll().single().id).isEqualTo("agent-empty")
        } finally { snapshot.wipe(); encoded.fill(0); BackupStreams.deleteTree(root) }
    }

    @Test
    fun `全部层组合的物理外键完整且未包含的会话保持`() = runBlocking {
        val cache = Files.createTempDirectory(sourceBase, "layer-matrix")
        try {
            for (mask in 0 until 16) {
                db.clearAllTables()
                BackupStreams.deleteTree(sourceRoot); Files.createDirectory(sourceRoot)
                seedCompleteGraph()
                db.messageDao().insert(MessageEntity("child-message", "session-1", "assistant", "父子链回归", parentMessageId = "message-1", createdAt = 101L))
                val optional = listOf(BackupLayer.KNOWLEDGE, BackupLayer.SESSIONS, BackupLayer.WORKSPACE_FILES, BackupLayer.SECRETS)
                val layers = setOf(BackupLayer.CORE) + optional.filterIndexed { index, _ -> mask and (1 shl index) != 0 }
                val hasSecrets = BackupLayer.SECRETS in layers
                val password = if (hasSecrets) "matrix".toCharArray() else null
                val source = newDataSource()
                val snapshot = source.snapshotTo(layers, cache)
                val file = cache.resolve("matrix.iwb")
                val codec = DefaultBackupPackageCodec()
                try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT + if (hasSecrets) setOf(BackupContent.SECRETS) else emptySet(), hasSecrets, password), it) } }
                finally { snapshot.wipe() }
                db.sessionDao().insert(SessionEntity("retained", "agent-1", createdAt = 1, updatedAt = 1))
                val restored = Files.newInputStream(file).use { input -> codec.decodeFrom(input, password, cache).use { source.restoreWithResult(it, "matrix-$mask") } }
                assertThat(db.sessionDao().getById("retained") != null).isEqualTo(BackupLayer.SESSIONS !in layers)
                db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.moveToFirst()).isFalse() }
                assertThat(restored.summary.layers.keys).containsExactlyElementsIn(layers)
            }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `知识库系统会话归属虚拟智能体也能随资料层恢复`() = runBlocking {
        val cache = Files.createTempDirectory(sourceBase, "knowledge-system-session")
        try {
            seedCompleteGraph()
            db.sessionDao().insert(SessionEntity("__infinity_rag_workspace__", BackupLayerPolicy.KNOWLEDGE_AGENT, title = "RAG Workspace", createdAt = 1, updatedAt = 1))
            val source = newDataSource()
            val snapshot = source.snapshotTo(BackupLayer.DEFAULT, cache)
            val file = cache.resolve("knowledge.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT, false, null), it) } }
            finally { snapshot.wipe() }

            db.clearAllTables()
            db.agentDao().insert(AgentEntity("fresh-agent", "新设备智能体", createdAt = 3))
            db.sessionDao().insert(SessionEntity("__infinity_rag_workspace__", BackupLayerPolicy.KNOWLEDGE_AGENT, title = "RAG Workspace", createdAt = 3, updatedAt = 3))
            val result = Files.newInputStream(file).use { input -> codec.decodeFrom(input, null, cache).use { source.validateStaged(it); source.restoreWithResult(it, "knowledge-system") } }

            assertThat(db.sessionDao().getById("__infinity_rag_workspace__")?.agentId).isEqualTo(BackupLayerPolicy.KNOWLEDGE_AGENT)
            // 虚拟智能体不是丢失的智能体，恢复结果不能提示“会话的智能体不可用”。
            assertThat(result.summary.degraded.map { it.category }).doesNotContain("session_agent_unavailable")
            assertThat(db.agentDao().getAll().map { it.id }).containsExactly("agent-1")
            Unit
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `会话旧根只有属性身份和内部目录时分层恢复可提交并再次恢复`() = runBlocking {
        val bases = seedSessionWorkspaceRoots()
        val cache = Files.createTempDirectory(sourceBase, "session-roots")
        try {
            val source = newDataSource(trustedBases = bases)
            val file = writeLayeredWorkspaceBackup(source, cache)
            Files.writeString(sourceRoot.resolve("docs/a.txt"), "本机旧文件")
            val siblingIgnore = bases.last().resolve(".infinity/.gitignore")

            Files.newInputStream(file).use { input ->
                DefaultBackupPackageCodec().decodeFrom(input, null, cache).use {
                    source.validateStaged(it)
                    source.restore(it, "session-roots-first")
                }
            }

            assertThat(Files.exists(sourceRoot)).isFalse()
            assertThat(readUtf8(siblingIgnore)).isEqualTo("*\n")
            assertThat(source.hasCompletedRestore("session-roots-first")).isTrue()
            assertRestoredSessionWorkspaceFiles()

            // 第二次恢复清理的是带所有者标记的 restore 容器，覆盖 baseIndex=-1 的清点。
            val restored = Path.of(db.sessionDao().getById("session-1")!!.workspacePath!!)
            Files.createDirectories(restored.resolve(".infinity"))
            Files.writeString(restored.resolve(".infinity/.gitignore"), "*\n")
            Files.newInputStream(file).use { input ->
                DefaultBackupPackageCodec().decodeFrom(input, null, cache).use { source.restore(it, "session-roots-second") }
            }
            assertThat(Files.exists(restored.parent)).isFalse()
            assertThat(source.hasCompletedRestore("session-roots-second")).isTrue()
            assertRestoredSessionWorkspaceFiles()
            assertThat(FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator).read()).isNull()
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `会话旧根未登记用户文件在准备前或提交前出现均拒绝且保全原数据`() = runBlocking {
        val bases = seedSessionWorkspaceRoots()
        val cache = Files.createTempDirectory(sourceBase, "session-unmanaged")
        try {
            val file = writeLayeredWorkspaceBackup(newDataSource(trustedBases = bases), cache)
            Files.writeString(sourceRoot.resolve("docs/a.txt"), "本机旧文件")
            val beforePreferences = preferences.snapshot
            val beforeSession = db.sessionDao().getById("session-1")
            val beforeRoot = db.fileEntryDao().getByUuid("root-1", "root-1")
            for (beforePrepare in listOf(true, false)) {
                val sentinel = sourceRoot.resolve(if (beforePrepare) "用户笔记.txt" else ".infinity-notes.txt")
                if (beforePrepare) Files.writeString(sentinel, "用户未登记内容")
                val operations = TestRestoreFileOperations()
                val injectingOperations = object : RestoreFileOperations by operations {
                    override fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>) {
                        operations.verifyAndSync(root, expected)
                        if (!beforePrepare && root.fileName.toString().startsWith("restore-")) {
                            Files.writeString(sentinel, "用户未登记内容")
                        }
                    }
                }
                val source = newDataSource(fileOperations = injectingOperations, trustedBases = bases)
                val failure = runCatching {
                    Files.newInputStream(file).use { input ->
                        DefaultBackupPackageCodec().decodeFrom(input, null, cache).use {
                            source.validateStaged(it)
                            source.restore(it, "session-unmanaged-$beforePrepare")
                        }
                    }
                }.exceptionOrNull()

                assertThat(failure).isInstanceOf(BackupValidationException::class.java)
                assertThat(failure?.message).contains("未登记、缺失或异常内容")
                assertThat(readUtf8(sentinel)).isEqualTo("用户未登记内容")
                assertThat(readUtf8(sourceRoot.resolve("docs/a.txt"))).isEqualTo("本机旧文件")
                assertThat(db.sessionDao().getById("session-1")).isEqualTo(beforeSession)
                assertThat(db.fileEntryDao().getByUuid("root-1", "root-1")).isEqualTo(beforeRoot)
                assertThat(preferences.snapshot).isEqualTo(beforePreferences)
                assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
                Files.delete(sentinel)
            }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `会话旧根的新旧内部目录与实际存在的身份文件均可随根清理`() = runBlocking {
        val bases = seedSessionWorkspaceRoots()
        val legacy = Files.createDirectories(sourceRoot.resolve("docs/.nexara/cache"))
        Files.writeString(legacy.resolve("state"), "内部状态")
        WorkspaceInternalPaths.tombstoneDirectories.forEach { directory ->
            val tombstone = directory.fold(sourceRoot) { current, segment -> current.resolve(segment) }.resolve("c".repeat(24))
            Files.createDirectories(tombstone)
            Files.writeString(tombstone.resolve("deleted.txt"), "内部删除暂存")
        }
        val key = Files.readAttributes(sourceRoot, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        val marker = PersistentFileIdentity.marker(PersistentFileIdentity.nonce(PersistentFileIdentity.read(sourceRoot)), key)
        WorkspaceInternalPaths.rootIdentityNames.forEach { Files.writeString(sourceRoot.resolve(it), marker) }
        val cache = Files.createTempDirectory(sourceBase, "session-internal-markers")
        try {
            val source = newDataSource(trustedBases = bases)
            val file = writeLayeredWorkspaceBackup(source, cache)
            Files.newInputStream(file).use { input ->
                DefaultBackupPackageCodec().decodeFrom(input, null, cache).use { source.restore(it) }
            }
            assertThat(Files.exists(sourceRoot)).isFalse()
            assertRestoredSessionWorkspaceFiles()
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `会话旧根内部目录类型错误和内部子树符号链接均失败关闭`() = runBlocking {
        val bases = seedSessionWorkspaceRoots()
        val cache = Files.createTempDirectory(sourceBase, "session-internal-invalid")
        try {
            val source = newDataSource(trustedBases = bases)
            val file = writeLayeredWorkspaceBackup(source, cache)
            val internal = sourceRoot.resolve(".infinity")
            Files.delete(internal.resolve(".gitignore"))
            Files.delete(internal)
            Files.writeString(internal, "目录被替换成文件")
            val marker = sourceRoot.resolve(".infinity_root_identity")
            for (kind in listOf("directory-as-file", "marker-as-directory", "internal-symlink")) {
                when (kind) {
                    "marker-as-directory" -> {
                        Files.delete(internal)
                        Files.createDirectory(internal)
                        Files.createDirectory(marker)
                    }
                    "internal-symlink" -> {
                        Files.delete(marker)
                        Files.createSymbolicLink(internal.resolve("link"), sourceRoot.resolve("docs/a.txt"))
                    }
                }
                val failure = runCatching {
                    Files.newInputStream(file).use { input ->
                        DefaultBackupPackageCodec().decodeFrom(input, null, cache).use { source.restore(it) }
                    }
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(BackupValidationException::class.java)
                assertThat(readUtf8(sourceRoot.resolve("docs/a.txt"))).isEqualTo("complete-graph")
                assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
            }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `会话旧根登记文件缺失或类型异常时内部目录不能掩盖不一致`() = runBlocking {
        val bases = seedSessionWorkspaceRoots()
        val cache = Files.createTempDirectory(sourceBase, "session-missing")
        try {
            val source = newDataSource(trustedBases = bases)
            val file = writeLayeredWorkspaceBackup(source, cache)
            val registered = sourceRoot.resolve("docs/a.txt")
            Files.delete(registered)
            for (directoryInstead in listOf(false, true)) {
                if (directoryInstead) Files.createDirectory(registered)
                val failure = runCatching {
                    Files.newInputStream(file).use { input ->
                        DefaultBackupPackageCodec().decodeFrom(input, null, cache).use { source.restore(it) }
                    }
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(BackupValidationException::class.java)
                assertThat(failure?.message).contains("未登记、缺失或异常内容")
                assertThat(db.sessionDao().getById("session-1")!!.workspacePath).isEqualTo(sourceRoot.toString())
                assertThat(Files.exists(sourceRoot.resolve(".infinity/.gitignore"))).isTrue()
                assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
            }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `会话旧根内部条目随journal在中断后回滚或重放且清理可续跑`() = runBlocking {
        val bases = seedSessionWorkspaceRoots()
        val cache = Files.createTempDirectory(sourceBase, "session-journal")
        try {
            val file = writeLayeredWorkspaceBackup(newDataSource(trustedBases = bases), cache)
            Files.writeString(sourceRoot.resolve("docs/a.txt"), "本机旧文件")
            val journal = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator)
            val points = listOf(RestoreCrashPoint.FILES_MOVED, RestoreCrashPoint.EXTERNAL_COMMITTED, RestoreCrashPoint.ROOM_COMMITTED)
            for (point in points) {
                val operationId = "session-journal-${point.name.lowercase().replace('_', '-')}"
                val failure = runCatching {
                    Files.newInputStream(file).use { input ->
                        DefaultBackupPackageCodec().decodeFrom(input, null, cache).use {
                            newDataSource(point, trustedBases = bases).restore(it, operationId)
                        }
                    }
                }.exceptionOrNull()
                assertThat(failure).isInstanceOf(SimulatedRestoreProcessDeath::class.java)
                val record = journal.read()!!
                // 会话目录追加为第四个受信 base，持久身份格式与索引语义保持兼容。
                assertThat(record.oldRootIdentity.split(',').all { it.startsWith("3:") }).isTrue()
                preferences = preferences.reopen()
                secrets = secrets.reopen()
                if (point == RestoreCrashPoint.ROOM_COMMITTED) {
                    val operations = TestRestoreFileOperations().apply { failDeleteAt = 1 }
                    assertFails { newDataSource(fileOperations = operations, trustedBases = bases).recoverInterruptedRestore() }
                    assertThat(journal.read()).isNotNull()
                    operations.clearDeleteFailure()
                    newDataSource(fileOperations = operations, trustedBases = bases).recoverInterruptedRestore()
                    assertThat(Files.exists(sourceRoot)).isFalse()
                    assertRestoredSessionWorkspaceFiles()
                    assertThat(newDataSource(trustedBases = bases).hasCompletedRestore(operationId)).isTrue()
                } else {
                    newDataSource(trustedBases = bases).recoverInterruptedRestore()
                    assertThat(readUtf8(sourceRoot.resolve("docs/a.txt"))).isEqualTo("本机旧文件")
                    assertThat(readUtf8(sourceRoot.resolve(".infinity/.gitignore"))).isEqualTo("*\n")
                    assertThat(db.sessionDao().getById("session-1")!!.workspacePath).isEqualTo(sourceRoot.toString())
                    assertThat(Files.exists(restoreParent.resolve(record.newRootIdentity))).isFalse()
                }
                newDataSource(trustedBases = bases).recoverInterruptedRestore()
                assertThat(journal.read()).isNull()
            }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `工作区文件缺失与悬空头像引用都列为跳过项`() = runBlocking {
        val graph = seedCompleteGraph()
        Files.delete(graph.filePath)
        val cache = Files.createTempDirectory(sourceBase, "skipped-export")
        val avatar = Files.createDirectory(sourceBase.resolve("avatars"))
        try {
            val source = newDataSource(avatarRoot = avatar)
            val snapshot = source.snapshotTo(BackupLayer.DEFAULT + BackupLayer.WORKSPACE_FILES, cache)
            assertThat(snapshot.skipped.single().layer).isEqualTo(BackupLayer.WORKSPACE_FILES)
            assertThat(snapshot.streamEntries.none { it.path == "files/file-1" }).isTrue()
            snapshot.wipe()
            val agent = db.agentDao().getAll().single()
            db.agentDao().update(agent.copy(avatarPath = avatar.resolve("missing.img").toString()))
            val core = source.snapshotTo(setOf(BackupLayer.CORE), cache)
            try {
                assertThat(core.skipped).containsExactly(BackupSkippedItem(BackupLayer.CORE, "missing.img", BackupSkippedItem.REASON_AVATAR_DANGLING))
                assertThat(core.streamEntries.none { it.path.startsWith("avatars/") }).isTrue()
            } finally { core.wipe() }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `流式恢复提交前失败与提交中断均按主journal回滚`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        val cache = Files.createTempDirectory(sourceBase, "stream-journal")
        try {
            val source = newDataSource()
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE), cache)
            val file = cache.resolve("core.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } } finally { snapshot.wipe() }
            val agent = db.agentDao().getAll().single()
            db.agentDao().update(agent.copy(name = "本机保留"))
            Files.newInputStream(file).use { input ->
                val validated = codec.decodeFrom(input, null, cache)
                try { newDataSource(crashPoint = RestoreCrashPoint.JOURNAL_PREPARED).restore(validated, "stream-crash") }
                catch (_: SimulatedRestoreProcessDeath) { }
            }
            source.recoverInterruptedRestore()
            assertThat(db.agentDao().getAll().single().name).isEqualTo("本机保留")
            assertThat(source.hasCompletedRestore("stream-crash")).isFalse()
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `只恢复核心层保留本机会话`() = runBlocking {
        emptyWorkspaceBackup()
        val cache = Files.createTempDirectory(sourceBase, "backup-core")
        val source = newDataSource()
        try {
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE), cache)
            try {
                val file = cache.resolve("core.iwb")
                val codec = DefaultBackupPackageCodec()
                Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) }
                db.sessionDao().insert(SessionEntity("local-session", "agent-empty", createdAt = 1, updatedAt = 1))
                Files.newInputStream(file).use { input ->
                    codec.decodeFrom(input, null, cache).use { source.restore(it) }
                }
                assertThat(db.sessionDao().getById("local-session")).isNotNull()
            } finally { snapshot.wipe() }
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `轮次上下文备份恢复保持完整原文字节且正文独立`() = runBlocking {
        emptyWorkspaceBackup()
        db.sessionDao().insert(SessionEntity("context-session", "agent-empty", createdAt = 1, updatedAt = 1))
        val raw = "  工作区：/项目/甲\r\n<state>\n🧪 & data\n</state>\n\n"
        db.messageDao().insert(Message("context-user", MessageRole.USER, "用户正文", turnContext = raw).toEntity("context-session"))
        db.messageDao().insert(Message("context-assistant", MessageRole.ASSISTANT, "助手正文", requestInstruction = raw).toEntity("context-session"))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val row = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
            .getValue("tables").jsonObject.getValue("messages").jsonArray.first { it.jsonObject["id"]?.jsonPrimitive?.content == "context-user" }.jsonObject
        assertThat(row.getValue("turn_context").jsonPrimitive.content).isEqualTo(raw)
        db.messageDao().deleteById("context-user")
        db.messageDao().deleteById("context-assistant")
        newDataSource().restore(validated(snapshot))
        val restored = db.messageDao().getById("context-user")!!.toDomain()
        assertThat(restored.turnContext!!.toByteArray(Charsets.UTF_8)).isEqualTo(raw.toByteArray(Charsets.UTF_8))
        assertThat(restored.content).isEqualTo("用户正文")
        val assistant = db.messageDao().getById("context-assistant")!!.toDomain()
        assertThat(assistant.requestInstruction!!.toByteArray(Charsets.UTF_8)).isEqualTo(raw.toByteArray(Charsets.UTF_8))
        assertThat(assistant.content).isEqualTo("助手正文")
    }

    @Test
    fun `v12备份缺轮次上下文列时恢复为空且保留正文`() = runBlocking {
        emptyWorkspaceBackup()
        db.sessionDao().insert(SessionEntity("context-session", "agent-empty", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(MessageEntity("context-user", "context-session", "user", "旧正文", createdAt = 1))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val legacy = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(12),
            "tables" to JsonObject(tables + ("messages" to JsonArray(tables.getValue("messages").jsonArray.map {
                JsonObject(it.jsonObject - "turn_context" - "request_instruction")
            }))))).toString().toByteArray()
        newDataSource().restore(validated(snapshot, database = legacy))
        assertThat(db.messageDao().getById("context-user")!!.toDomain().turnContext).isNull()
        assertThat(db.messageDao().getById("context-user")!!.toDomain().requestInstruction).isNull()
        assertThat(db.messageDao().getById("context-user")!!.content).isEqualTo("旧正文")
    }

    @Test
    fun `带引用且正文为空的消息备份恢复后保持结构化引用`() = runBlocking {
        emptyWorkspaceBackup()
        db.sessionDao().insert(SessionEntity("quote-session", "agent-empty", createdAt = 1, updatedAt = 1))
        val quote = com.promenar.infinity.data.model.MessageQuote("original", "引文\n第二行", "批注")
        db.messageDao().insert(Message("quote-user", MessageRole.USER, "", quotes = listOf(quote)).toEntity("quote-session"))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val row = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
            .getValue("tables").jsonObject.getValue("messages").jsonArray.single().jsonObject
        assertThat(row["quotes"]).isNotEqualTo(JsonNull)
        assertThat(row["quotes"]).isNotNull()
        db.messageDao().deleteById("quote-user")
        newDataSource().restore(validated(snapshot))
        val restored = db.messageDao().getById("quote-user")!!.toDomain()
        assertThat(restored.content).isEmpty()
        assertThat(restored.quotes).containsExactly(quote).inOrder()
    }

    @Test
    fun `旧备份缺引用列时恢复为空且保留正文`() = runBlocking {
        emptyWorkspaceBackup()
        db.sessionDao().insert(SessionEntity("quote-session", "agent-empty", createdAt = 1, updatedAt = 1))
        db.messageDao().insert(MessageEntity("quote-user", "quote-session", "user", "旧正文", createdAt = 1))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val legacy = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(11),
            "tables" to JsonObject(tables + ("messages" to JsonArray(tables.getValue("messages").jsonArray.map {
                JsonObject(it.jsonObject - "quotes")
            }))))).toString().toByteArray()
        newDataSource().restore(validated(snapshot, database = legacy))
        assertThat(db.messageDao().getById("quote-user")!!.toDomain().quotes).isNull()
        assertThat(db.messageDao().getById("quote-user")!!.content).isEqualTo("旧正文")
    }

    @Test
    fun `旧应用偏好别名经完整数据库恢复转换为逻辑命名空间`() = runBlocking {
        val snapshot = emptyWorkspaceBackup()
        val old = BackupPreferenceSnapshot(listOf(
            BackupPreferenceEntry("nexara_settings", "user_name", "迁移用户"),
            BackupPreferenceEntry("nexara_search", "result_count", "8", PreferenceValueType.INT),
        ), emptySet())
        newDataSource().restore(validated(snapshot, preferencesBytes = Json.encodeToString(old).toByteArray(), secrets = emptyMap()))
        assertThat(preferences.snapshot.entries.map { it.namespace }).containsExactly("settings", "search")
        assertThat(preferences.snapshot.entries.first { it.key == "user_name" }.value).isEqualTo("迁移用户")
    }

    @Test
    fun `历史备份缺工具默认开关时恢复为开启`() = runBlocking {
        val snapshot = emptyWorkspaceBackup()
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val legacy = JsonObject(root + mapOf(
            "schemaVersion" to JsonPrimitive(10),
            "tables" to JsonObject(tables + ("agents" to JsonArray(tables.getValue("agents").jsonArray.map {
                JsonObject(it.jsonObject - "tools_enabled_default")
            }))),
        )).toString().toByteArray()
        db.clearAllTables()

        newDataSource().restore(validated(snapshot, database = legacy))

        assertThat(db.agentDao().getById("agent-empty")!!.toolsEnabledByDefault).isTrue()
    }

    @Test
    fun `工具默认关闭经过完整备份恢复保持关闭`() = runBlocking {
        db.agentDao().insert(AgentEntity("disabled", "智能体", createdAt = 1, toolsEnabledByDefault = false))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val agent = root.getValue("tables").jsonObject.getValue("agents").jsonArray.single().jsonObject
        assertThat(agent["tools_enabled_default"]).isEqualTo(JsonPrimitive(0))
        db.clearAllTables()

        newDataSource().restore(validated(snapshot))

        assertThat(db.agentDao().getById("disabled")!!.toolsEnabledByDefault).isFalse()
    }

    @Test
    fun `当前备份缺工具默认开关时拒绝恢复并保留数据`() = runBlocking {
        val snapshot = emptyWorkspaceBackup()
        val missing = mutateRows(snapshot, "agents") { rows ->
            JsonArray(rows.map { JsonObject(it.jsonObject - "tools_enabled_default") })
        }

        assertThat(runCatching { newDataSource().restore(missing) }.exceptionOrNull())
            .isInstanceOf(BackupValidationException::class.java)
        assertThat(db.agentDao().getById("agent-empty")).isNotNull()
    }

    @Test
    fun `备份导出包含智能体的新会话工具默认开关`() = runBlocking {
        db.agentDao().insert(AgentEntity("tools-default", "智能体", createdAt = 1))

        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val agent = root.getValue("tables").jsonObject.getValue("agents").jsonArray.single().jsonObject

        assertThat(agent["tools_enabled_default"]).isEqualTo(JsonPrimitive(1))
    }

    @Test
    fun `智能体检索方式与知识库访问设置经过完整备份恢复保持一致`() = runBlocking {
        val configs = com.promenar.infinity.data.agent.AgentRetrievalMode.entries.flatMap { mode ->
            com.promenar.infinity.data.agent.KnowledgeFileAccess.entries.map { access ->
                com.promenar.infinity.data.agent.AgentRetrievalConfig(retrievalMode = mode, knowledgeFileAccess = access)
            }
        }
        configs.forEachIndexed { index, config ->
            db.agentDao().insert(AgentEntity("retrieval-$index", "智能体$index", createdAt = 1, retrievalConfig = config))
        }
        db.agentDao().insert(AgentEntity("legacy-null", "旧智能体", createdAt = 1))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()

        newDataSource().restore(validated(snapshot))

        configs.forEachIndexed { index, config ->
            assertThat(db.agentDao().getById("retrieval-$index")!!.retrievalConfig).isEqualTo(config)
        }
        assertThat(db.agentDao().getById("legacy-null")!!.retrievalConfig).isNull()
    }

    @Test
    fun `分支备份恢复保留父子关系方案worktree与自动处理状态`() = runBlocking {
        seedCompleteGraph()
        val branch = SessionEntity("branch", "agent-1", createdAt = 1, updatedAt = 2,
            parentSessionId = "session-1", branchPlanJson = "{\"topic\":\"审查\"}",
            worktreeDirectory = "/project/feature", worktreeBranch = "feature", worktreeBase = "abc123",
            sessionAutoWakeCount = 8, sessionAutoProcessingPaused = true)
        db.sessionDao().insert(branch)
        db.messageDao().insert(MessageEntity("branch-message", "branch", "assistant", "分支记录", createdAt = 2))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()

        newDataSource().restore(validated(snapshot))

        assertThat(db.sessionDao().getById("branch")).isEqualTo(branch)
        assertThat(db.messageDao().getById("branch-message")!!.content).isEqualTo("分支记录")
    }

    @Test
    fun `恢复缺失父会话时解除引用并标记原主会话已删除`() = runBlocking {
        seedCompleteGraph()
        val branch = SessionEntity("branch", "agent-1", createdAt = 1, updatedAt = 2,
            parentSessionId = "missing-parent", branchPlanJson = "{}", worktreeDirectory = "/project/feature",
            worktreeBranch = "feature", worktreeBase = "abc123")
        db.sessionDao().insert(branch)
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()

        newDataSource().restore(validated(snapshot))

        assertThat(db.sessionDao().getById("branch")).isEqualTo(branch.copy(parentSessionId = null, parentSessionDeleted = true))
    }

    @Test
    fun `恢复解除跨助手自引用循环与超过一层的分支关系`() = runBlocking {
        seedCompleteGraph()
        db.agentDao().insert(AgentEntity("other-agent", "另一个助手", createdAt = 1))
        val valid = SessionEntity("valid-branch", "agent-1", parentSessionId = "session-1", branchPlanJson = "{}", createdAt = 1, updatedAt = 2)
        val orphan = SessionEntity("orphan-branch", "agent-1", branchPlanJson = "{}", parentSessionDeleted = true, createdAt = 1, updatedAt = 2)
        val planOnly = SessionEntity("plan-parent", "agent-1", branchPlanJson = "{}", createdAt = 1, updatedAt = 2)
        val invalid = listOf(
            valid.copy(id = "cross-agent", agentId = "other-agent"),
            valid.copy(id = "second-level", parentSessionId = valid.id),
            valid.copy(id = "self", parentSessionId = "self"),
            valid.copy(id = "cycle-a", parentSessionId = "cycle-b"),
            valid.copy(id = "cycle-b", parentSessionId = "cycle-a"),
            valid.copy(id = "orphan-child", parentSessionId = orphan.id),
            valid.copy(id = "plan-child", parentSessionId = planOnly.id),
            valid.copy(id = "deleted-parent-mark", parentSessionDeleted = true),
        )
        (listOf(valid, orphan, planOnly) + invalid).forEach { db.sessionDao().insert(it) }
        db.messageDao().insert(MessageEntity("isolated-message", "cross-agent", "assistant", "保留私有分支记录", createdAt = 3))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()

        newDataSource().restore(validated(snapshot))

        assertThat(db.sessionDao().getById(valid.id)).isEqualTo(valid)
        assertThat(db.sessionDao().getById(orphan.id)).isEqualTo(orphan)
        invalid.forEach { before ->
            assertThat(db.sessionDao().getById(before.id)).isEqualTo(before.copy(parentSessionId = null, parentSessionDeleted = true))
        }
        assertThat(db.sessionDao().getAll().filter { it.parentSessionId == "session-1" }.map { it.id }).containsExactly(valid.id)
        assertThat(db.messageDao().getById("isolated-message")!!.content).isEqualTo("保留私有分支记录")
    }

    @Test
    fun `父会话删除后已批准工具统一对账的修改及新增文件可备份恢复`() = runBlocking {
        seedCompleteGraph()
        val initial = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()
        newDataSource().restore(validated(initial))
        val registry = WorkspaceEntity("branch-shared-root", "branch_shared", "共用分支工作区", "root-1", 2)
        db.workspaceDao().insert(registry)
        val child = SessionEntity("shared-child", "agent-1", parentSessionId = "session-1", branchPlanJson = "{}",
            workspaceId = registry.id, createdAt = 2, updatedAt = 3)
        db.sessionDao().insert(child)
        db.messageDao().insert(MessageEntity("shared-child-message", child.id, "assistant", "保留分支", createdAt = 3))
        assertThat(com.promenar.infinity.data.session.RoomSessionDeletionTransaction(db).deleteWithoutWorkspace("session-1")).isTrue()
        assertThat(db.sessionDao().getAll().none { it.workspaceRootUuid == registry.location }).isTrue()
        val sharedRoot = db.fileEntryDao().getByUuid(registry.location, registry.location)!!
        val oldFile = db.fileEntryDao().getByUuid(registry.location, "file-1")!!
        val backend = JvmBoundRealPathFileSystem().backend(java.io.File(sharedRoot.physicalRootPath))
        val updatedContent = "父会话删除后修改的内容"
        val addedContent = "孤立分支新增的内容"
        val workspace = WorkspaceRepository(db.fileEntryDao(), db.workspaceSeqDao(),
            fileOps = TestWorkspaceFileOps(), treeScanner = PathWorkspaceTreeScanner)
        val privateRoot = workspace.ensureSessionRoot(child.id, sourceBase.resolve("branch-private").toString())
        assertThat(privateRoot.uuid).isNotEqualTo(registry.location)
        val call = ToolCall("approved-write", "write_file", "{}")
        var toolStage = "未进入技能"
        val skill = object : SkillDefinition {
            override val id = "write_file"
            override val name = "write_file"
            override val description = "测试共用分支文件写入"
            override val mcpServerId: String? = null
            override val parametersSchema = """{"type":"object","properties":{}}"""
            override val risk = com.promenar.infinity.domain.tool.ToolRisk.FILE_WRITE
            override suspend fun execute(args: JsonObject, context: SkillExecutionContext): ToolResult {
                assertThat(context.sessionId).isEqualTo(child.id)
                assertThat(context.isApprovalBoundExecution).isTrue()
                toolStage = "修改已有文件"
                backend.write("/docs/a.txt", updatedContent.toByteArray(), expectedSha256 = oldFile.hash)
                toolStage = "新增文件"
                backend.write("/docs/new.txt", addedContent.toByteArray(), createOnly = true)
                toolStage = "验证对账前的索引与备份失败"
                assertThat(db.fileEntryDao().getByUuid(registry.location, "file-1")!!.hash).isEqualTo(oldFile.hash)
                assertThat(db.fileEntryDao().getActiveByMaterializedPath(registry.location, "/docs/new.txt")).isNull()
                assertThat(runCatching { newDataSource().snapshot(CANONICAL_CONTENT) }.exceptionOrNull())
                    .isInstanceOf(BackupValidationException::class.java)
                toolStage = "写入完成"
                return ToolResult(call.id, "写入完成", status = "success")
            }
        }
        val prepared = skill.toProtocolTool()
        val skills = object : SkillRegistry {
            override fun getSkill(name: String) = skill.takeIf { it.name == name }
            override fun getAllSkills() = listOf(skill)
            override fun getAllTools(allowedIds: List<String>?) = listOf(prepared)
        }
        val ledger = ToolExecutionLedgerRepository(db)
        val key = ToolExecutionKey(child.id, "shared-child-message", call.id)
        val identity = (ToolInvocationIdentityFactory.fromPreparedToolCall(call, prepared, true)
            as ToolInvocationIdentityResolution.Valid).identity
        ledger.register(key, identity)
        assertThat(ledger.approve(setOf(key))).isEqualTo(1)
        val store = ChatStore().apply {
            upsertSession(db.sessionDao().getById(child.id)!!.toDomain().copy(
                messages = listOf(db.messageDao().getById("shared-child-message")!!.toDomain())))
        }
        var reconciled: com.promenar.infinity.data.repository.WorkspaceChangeReport? = null
        var captured: BackupSnapshot? = null
        val finalized = mutableListOf<String>()
        val executor = ToolExecutor(store, io.mockk.mockk<MessageManager>(relaxed = true), skills,
            SessionToolResolver { listOf(prepared) }, ledger = ledger, afterTools = { sessionId ->
                finalized += sessionId
                reconciled = workspace.reconcileSessionWorkspace(registry.location, sessionId)
                captured = newDataSource().snapshot(CANONICAL_CONTENT)
            })

        executor.executeTools(child.id, "shared-child-message", listOf(call), preparedTools = listOf(prepared))

        assertThat(finalized).containsExactly(child.id)
        val toolResults = db.messageDao().getBySession(child.id).filter { it.role == "tool" && it.toolCallId == call.id }.map { it.content }
        com.google.common.truth.Truth.assertWithMessage("工具执行阶段：%s；持久化工具结果：%s", toolStage, toolResults)
            .that(ledger.state(key)).isEqualTo(ToolLedgerState.SUCCEEDED)
        val changes = requireNotNull(reconciled)
        assertThat(changes.modified).contains("/docs/a.txt")
        assertThat(changes.added).contains("/docs/new.txt")
        val addedFile = db.fileEntryDao().getActiveByMaterializedPath(registry.location, "/docs/new.txt")!!
        assertThat(addedFile.lastWriteSessionId).isEqualTo(child.id)
        assertThat(db.fileEntryDao().getByUuid(registry.location, "file-1")!!.lastWriteSessionId).isEqualTo(child.id)
        val snapshot = requireNotNull(captured)
        db.clearAllTables()

        newDataSource().restore(validated(snapshot))

        val restoredRegistry = db.workspaceDao().getById(registry.id)!!
        val restoredChild = db.sessionDao().getById(child.id)!!
        val restoredRoot = db.fileEntryDao().getByUuid(restoredRegistry.location, restoredRegistry.location)!!
        val restoredFile = db.fileEntryDao().getByUuid(restoredRegistry.location, "file-1")!!
        assertThat(restoredRegistry).isEqualTo(registry)
        assertThat(restoredChild.copy(workspacePath = null)).isEqualTo(child.copy(
            parentSessionId = null, parentSessionDeleted = true, workspaceRootUuid = privateRoot.uuid))
        val restoredPrivateRoot = db.fileEntryDao().getByUuid(privateRoot.uuid, privateRoot.uuid)!!
        assertThat(restoredChild.workspacePath).isEqualTo(restoredPrivateRoot.physicalRootPath)
        assertThat(restoredRoot.uuid).isEqualTo("root-1")
        assertThat(restoredRoot.physicalRootPath).isNotEqualTo(sourceRoot.toString())
        assertThat(restoredFile.physicalRootPath).isEqualTo(restoredRoot.physicalRootPath)
        assertThat(restoredFile.hash).isEqualTo(sha256(updatedContent.toByteArray()))
        assertThat(readUtf8(java.nio.file.Paths.get(restoredRoot.physicalRootPath).resolve("docs/a.txt"))).isEqualTo(updatedContent)
        val restoredAdded = db.fileEntryDao().getByUuid(restoredRegistry.location, addedFile.uuid)!!
        assertThat(restoredAdded.hash).isEqualTo(sha256(addedContent.toByteArray()))
        assertThat(restoredAdded.lastWriteSessionId).isEqualTo(child.id)
        assertThat(readUtf8(java.nio.file.Paths.get(restoredAdded.physicalRootPath).resolve("docs/new.txt"))).isEqualTo(addedContent)
        assertThat(db.sessionDao().getById("session-1")).isNull()
        assertThat(db.messageDao().getById("shared-child-message")!!.content).isEqualTo("保留分支")
    }

    @Test
    fun `v9备份补齐分支默认值后恢复旧会话`() = runBlocking {
        seedCompleteGraph()
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val fields = setOf("parent_session_id", "branch_plan_json", "worktree_directory", "worktree_branch", "worktree_base",
            "parent_session_deleted", "session_auto_wake_count", "session_auto_processing_paused")
        val legacy = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(9), "tables" to JsonObject(tables +
            ("sessions" to JsonArray(tables.getValue("sessions").jsonArray.map { JsonObject(it.jsonObject - fields) }))))).toString().toByteArray()
        db.clearAllTables()

        newDataSource().restore(validated(snapshot, database = legacy))

        val session = db.sessionDao().getById("session-1")!!
        assertThat(session.parentSessionId).isNull()
        assertThat(session.parentSessionDeleted).isFalse()
        assertThat(session.sessionAutoWakeCount).isEqualTo(0)
        assertThat(session.sessionAutoProcessingPaused).isFalse()
        assertThat(db.messageDao().getById("message-1")).isNotNull()
    }

    @Test
    fun `缺父引用规范化不能掩盖当前备份的缺列或类型错误`() = runBlocking {
        seedCompleteGraph()
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        for (missing in listOf(true, false)) {
            val malformed = mutateRows(snapshot, "sessions") { rows -> JsonArray(rows.map { raw ->
                val row = raw.jsonObject + ("parent_session_id" to JsonPrimitive("missing-parent"))
                JsonObject(if (missing) row - "parent_session_deleted" else row + ("parent_session_deleted" to JsonPrimitive("invalid")))
            }) }
            assertThat(runCatching { newDataSource().restore(malformed) }.exceptionOrNull())
                .isInstanceOf(BackupValidationException::class.java)
            assertThat(db.sessionDao().getById("session-1")!!.parentSessionDeleted).isFalse()
        }
    }

    @Test fun `恢复拒绝超长空白标题和无界id并保持原数据`() = runBlocking {
        seedCompleteGraph()
        insertMemory("sentinel")
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val original = tables.getValue("long_term_memories").jsonArray.single().jsonObject
        for ((field, value) in listOf("title" to (" ".repeat(100000) + "偏好"), "id" to "x".repeat(100000))) {
            val row = JsonObject(original + (field to JsonPrimitive(value)))
            val malformed = JsonObject(root + ("tables" to JsonObject(tables +
                ("long_term_memories" to JsonArray(listOf(row)))))).toString().toByteArray()
            val error = runCatching { newDataSource().restore(validated(snapshot, database = malformed)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(BackupValidationException::class.java)
            assertThat(rowCount("long_term_memories")).isEqualTo(1)
            assertThat(db.messageDao().getById("message-1")).isNotNull()
        }
    }

    @Test
    fun `备份恢复保留固定模式且v6和v7缺字段按旧会话恢复`() = runBlocking {
        seedCompleteGraph()
        db.sessionDao().updateRunMode("session-1", "LINUX", 2)
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()
        newDataSource().restore(validated(snapshot))
        assertThat(db.sessionDao().getById("session-1")!!.runMode).isEqualTo("LINUX")
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        for (version in listOf(6, 7)) {
            val legacyTables = ((if (version == 6) tables - "state_board_versions" else tables) - "long_term_memories") +
                ("sessions" to JsonArray(tables.getValue("sessions").jsonArray.map {
                    JsonObject(it.jsonObject - "run_mode")
                }))
            val historical = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(version),
                "tables" to JsonObject(legacyTables))).toString().toByteArray()
            db.clearAllTables()
            newDataSource().restore(validated(snapshot, database = historical))
            assertThat(db.sessionDao().getById("session-1")!!.runMode).isNull()
            assertThat(db.messageDao().getById("message-1")).isNotNull()
        }
    }

    @Test
    fun `v8至v13备份缺运行模式列时拒绝恢复且保留当前数据`() = runBlocking {
        seedCompleteGraph()
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        for (version in 8..13) {
            val legacyTables = (if (version == 8) tables - "long_term_memories" else tables) - "knowledge_citations"
            val missingMode = JsonObject(root + mapOf(
                "schemaVersion" to JsonPrimitive(version),
                "tables" to JsonObject(legacyTables + ("sessions" to JsonArray(tables.getValue("sessions").jsonArray.map {
                    JsonObject(it.jsonObject - "run_mode")
                }))),
            )).toString().toByteArray()
            assertThat(runCatching { newDataSource().restore(validated(snapshot, database = missingMode)) }.exceptionOrNull())
                .isInstanceOf(BackupValidationException::class.java)
        }
        assertThat(db.messageDao().getById("message-1")).isNotNull()
    }

    @Test
    fun `备份恢复保留长期记忆全部字段且来源缺失不影响恢复`() = runBlocking {
        seedCompleteGraph()
        insertMemory("memory-1", sourceSessionId = "deleted-session")
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        assertThat(snapshot.databaseSchemaVersion).isEqualTo(18)
        db.clearAllTables()
        newDataSource().restore(validated(snapshot))
        db.openHelper.writableDatabase.query("SELECT * FROM long_term_memories WHERE id=?", arrayOf(memoryId("memory-1"))).use {
            check(it.moveToFirst())
            assertThat(it.getString(it.getColumnIndexOrThrow("kind"))).isEqualTo("user")
            assertThat(it.getString(it.getColumnIndexOrThrow("title"))).isEqualTo("长期偏好")
            assertThat(it.getString(it.getColumnIndexOrThrow("content"))).isEqualTo("使用简体中文")
            assertThat(it.getString(it.getColumnIndexOrThrow("source_agent_id"))).isEqualTo("deleted-agent")
            assertThat(it.getString(it.getColumnIndexOrThrow("source_session_id"))).isEqualTo("deleted-session")
            assertThat(it.getString(it.getColumnIndexOrThrow("source_message_id"))).isEqualTo("deleted-message")
            assertThat(it.getLong(it.getColumnIndexOrThrow("created_at"))).isEqualTo(10L)
            assertThat(it.getLong(it.getColumnIndexOrThrow("updated_at"))).isEqualTo(20L)
        }
        db.sessionDao().deleteById("session-1")
        assertThat(rowCount("long_term_memories")).isEqualTo(1)
    }

    @Test fun `备份恢复保留主动记忆隔离归属`() = runBlocking<Unit> {
        seedCompleteGraph()
        insertMemory("memory-1", sourceSessionId = "session-1")
        db.openHelper.writableDatabase.execSQL("UPDATE long_term_memories SET owner_session_id=? WHERE id=?", arrayOf("session-1", memoryId("memory-1")))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()
        newDataSource().restore(validated(snapshot))
        assertThat(db.longTermMemoryDao().get(memoryId("memory-1"))!!.ownerSessionId).isEqualTo("session-1")
    }

    @Test fun `旧分层会话备份恢复保留本机隔离记忆`() = runBlocking<Unit> {
        seedCompleteGraph()
        val cache = Files.createTempDirectory(sourceBase, "old-memory-layer")
        val source = newDataSource()
        val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.SESSIONS), cache)
        try {
            insertMemory("isolated", sourceSessionId = "session-1")
            db.openHelper.writableDatabase.execSQL("UPDATE long_term_memories SET owner_session_id=? WHERE id=?", arrayOf("session-1", memoryId("isolated")))
            val entries = snapshot.streamEntries.filterNot { it.path.startsWith("database/long_term_memories/") && it.layer == BackupLayer.SESSIONS }
            val manifest = BackupManifest(formatVersion = 3, databaseSchemaVersion = 16, appVersion = snapshot.appVersion,
                createdAt = snapshot.createdAt, encrypted = false, containsSecrets = false,
                layers = mapOf(BackupLayer.CORE to BackupLayerStats(), BackupLayer.SESSIONS to BackupLayerStats()),
                entries = entries.map { BackupManifestEntry(it.path, it.blob.size, BackupStreams.digest(it.blob), it.layer) })
            ValidatedBackup(manifest = manifest, blobs = entries.associate { it.path to it.blob }).use { source.restore(it) }
            assertThat(db.longTermMemoryDao().get(memoryId("isolated"))!!.ownerSessionId).isEqualTo("session-1")
        } finally { snapshot.wipe(); BackupStreams.deleteTree(cache) }
    }

    @Test fun `旧资料层恢复保留归属会话在备份中缺失的隔离记忆`() = runBlocking<Unit> {
        seedCompleteGraph()
        val cache = Files.createTempDirectory(sourceBase, "old-knowledge-memory")
        val source = newDataSource()
        val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.KNOWLEDGE), cache)
        try {
            db.sessionDao().insert(SessionEntity("knowledge-owner", BackupLayerPolicy.KNOWLEDGE_AGENT, createdAt = 1, updatedAt = 1))
            insertMemory("isolated", sourceSessionId = "knowledge-owner")
            db.openHelper.writableDatabase.execSQL("UPDATE long_term_memories SET owner_session_id=? WHERE id=?", arrayOf("knowledge-owner", memoryId("isolated")))
            val entries = snapshot.streamEntries.filterNot { it.path.startsWith("database/long_term_memories/") && it.layer == BackupLayer.KNOWLEDGE }
            val manifest = BackupManifest(formatVersion = 3, databaseSchemaVersion = 16, appVersion = snapshot.appVersion,
                createdAt = snapshot.createdAt, encrypted = false, containsSecrets = false,
                layers = mapOf(BackupLayer.CORE to BackupLayerStats(), BackupLayer.KNOWLEDGE to BackupLayerStats()),
                entries = entries.map { BackupManifestEntry(it.path, it.blob.size, BackupStreams.digest(it.blob), it.layer) })
            ValidatedBackup(manifest = manifest, blobs = entries.associate { it.path to it.blob }).use { source.restore(it) }
            assertThat(db.longTermMemoryDao().get(memoryId("isolated"))!!.ownerSessionId).isEqualTo("knowledge-owner")
        } finally { snapshot.wipe(); BackupStreams.deleteTree(cache) }
    }

    @Test fun `v16备份恢复将历史会话和主动记忆迁移到新权限`() = runBlocking<Unit> {
        seedCompleteGraph()
        insertMemory("memory-1", sourceSessionId = "session-1")
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("UPDATE agents SET retrieval_config=? WHERE id=?", arrayOf("""{"memoryPolicy":{"shareWithOtherAgents":false,"memoryCap":"AGENT"}}""", "agent-1"))
        sqlite.execSQL("UPDATE sessions SET rag_options=? WHERE id=?", arrayOf("""{"memoryScope":"ALL_AGENTS","isGlobal":true}""", "session-1"))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val memories = JsonArray(tables.getValue("long_term_memories").jsonArray.map { JsonObject(it.jsonObject - "owner_session_id") })
        val old = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(16), "tables" to JsonObject(tables + ("long_term_memories" to memories))))
        db.clearAllTables()
        newDataSource().restore(validated(snapshot, database = old.toString().toByteArray()))
        val policy = db.agentDao().getById("agent-1")!!.retrievalConfig!!.memoryPolicy
        assertThat(policy.globalMemory.name).isEqualTo("FORBID")
        assertThat(policy.activeMemoryDefault.name).isEqualTo("ISOLATED")
        val options = db.sessionDao().getById("session-1")!!.toDomain().ragOptions!!
        assertThat(options.globalMemoryEnabled).isFalse()
        assertThat(options.effectiveMemoryScope.name).isEqualTo("SESSION")
        assertThat(db.longTermMemoryDao().get(memoryId("memory-1"))!!.ownerSessionId).isNull()
    }

    @Test
    fun `v6至v8历史备份恢复补空长期记忆并清除现有记忆`() = runBlocking {
        seedCompleteGraph()
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject - "long_term_memories"
        for (version in 6..8) {
            val historical = JsonObject(root + mapOf(
                "schemaVersion" to JsonPrimitive(version),
                "tables" to JsonObject(if (version == 6) tables - "state_board_versions" else tables),
            )).toString().toByteArray()
            insertMemory("to-clear-$version")
            newDataSource().restore(validated(snapshot, database = historical))
            assertThat(rowCount("long_term_memories")).isEqualTo(0)
            assertThat(db.messageDao().getById("message-1")).isNotNull()
        }
    }

    @Test
    fun `当前备份缺长期记忆表时拒绝且保留当前数据`() = runBlocking {
        seedCompleteGraph()
        insertMemory("sentinel")
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val malformed = JsonObject(root + ("tables" to JsonObject(root.getValue("tables").jsonObject - "long_term_memories")))
            .toString().toByteArray()
        assertThat(runCatching { newDataSource().restore(validated(snapshot, database = malformed)) }.exceptionOrNull())
            .isInstanceOf(BackupValidationException::class.java)
        assertThat(rowCount("long_term_memories")).isEqualTo(1)
        assertThat(db.messageDao().getById("message-1")).isNotNull()
    }

    @Test
    fun `长期记忆恢复允许200条且拒绝超过上限的载荷`() = runBlocking {
        seedCompleteGraph()
        repeat(200) { insertMemory("memory-$it") }
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()
        newDataSource().restore(validated(snapshot))
        assertThat(rowCount("long_term_memories")).isEqualTo(200)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val memories = tables.getValue("long_term_memories").jsonArray
        val extra = JsonObject(memories.first().jsonObject + ("id" to JsonPrimitive("over-limit")))
        val malformed = JsonObject(root + ("tables" to JsonObject(tables +
            ("long_term_memories" to JsonArray(memories + extra))))).toString().toByteArray()
        assertThat(runCatching { newDataSource().restore(validated(snapshot, database = malformed)) }.exceptionOrNull())
            .isInstanceOf(BackupValidationException::class.java)
        assertThat(rowCount("long_term_memories")).isEqualTo(200)
    }

    @Test
    fun `长期记忆恢复拒绝无效种类长度和凭据且错误不回显输入`() = runBlocking {
        seedCompleteGraph()
        insertMemory("sentinel")
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val original = tables.getValue("long_term_memories").jsonArray.single().jsonObject
        val invalidFields = listOf(
            "kind" to "unknown-private-kind",
            "title" to "   ",
            "content" to "\n\t",
            "title" to "长".repeat(81),
            "content" to "长".repeat(2001),
            "content" to "api_key=synthetic-memory-secret-1234567890",
        )
        for ((field, value) in invalidFields) {
            val row = JsonObject(original + (field to JsonPrimitive(value)))
            val malformed = JsonObject(root + ("tables" to JsonObject(tables +
                ("long_term_memories" to JsonArray(listOf(row)))))).toString().toByteArray()
            val error = runCatching { newDataSource().restore(validated(snapshot, database = malformed)) }.exceptionOrNull()
            assertThat(error).isInstanceOf(BackupValidationException::class.java)
            assertThat(error!!.message).doesNotContain(value)
            generateSequence(error) { it.cause }.take(8).forEach { cause -> assertThat(cause.message).doesNotContain(value) }
            assertThat(rowCount("long_term_memories")).isEqualTo(1)
            assertThat(db.messageDao().getById("message-1")).isNotNull()
        }
    }

    @Test
    fun `备份拒绝非规范化记忆字段且允许规范化长度边界`() = runBlocking {
        seedCompleteGraph()
        insertMemory("trim-boundary", title = "  " + "题".repeat(80) + "  ", content = "\n" + "文".repeat(2000) + "\n")
        assertThat(runCatching { newDataSource().snapshot(CANONICAL_CONTENT) }.exceptionOrNull()).isInstanceOf(BackupValidationException::class.java)
        db.openHelper.writableDatabase.execSQL("DELETE FROM long_term_memories")
        insertMemory("canonical-boundary", title = "题".repeat(80), content = "文".repeat(2000))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()
        newDataSource().restore(validated(snapshot))
        assertThat(rowCount("long_term_memories")).isEqualTo(1)
    }

    @Test
    fun `备份拒绝数据库中绕过仓库写入的无效长期记忆`() = runBlocking {
        seedCompleteGraph()
        insertMemory("invalid-memory", content = "api_key=synthetic-memory-secret-1234567890")
        val error = runCatching { newDataSource().snapshot(CANONICAL_CONTENT) }.exceptionOrNull()
        assertThat(error).isInstanceOf(BackupValidationException::class.java)
        assertThat(error!!.message).doesNotContain("synthetic-memory-secret")
        generateSequence(error) { it.cause }.take(8).forEach { cause -> assertThat(cause.message).doesNotContain("synthetic-memory-secret") }
    }

    private fun memoryId(label: String) = java.util.UUID.nameUUIDFromBytes(label.toByteArray()).toString()

    private fun insertMemory(
        id: String,
        title: String = "长期偏好",
        content: String = "使用简体中文",
        sourceSessionId: String? = null,
    ) {
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO long_term_memories " +
                "(id,kind,title,content,source_agent_id,source_session_id,source_message_id,created_at,updated_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(memoryId(id), "user", title, content, "deleted-agent", sourceSessionId, "deleted-message", 10L, 20L),
        )
    }

    @Test
    fun `SSH主机配置和认证材料不进入备份`() = runBlocking {
        seedCompleteGraph()
        db.sshHostDao().insert(com.promenar.infinity.data.local.db.entity.SshHostEntity(
            id = "ssh-backup", alias = "termux", hostname = "private-ssh.invalid", username = "ssh-user",
            trustedKeyType = "ssh-ed25519", trustedFingerprint = "SHA256:synthetic-trust",
        ))
        secrets.put(SecretCatalog.sshCredential("ssh-backup"), "synthetic-ssh-secret".toByteArray())
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        assertThat(snapshot.database.toString(Charsets.UTF_8)).doesNotContain("ssh_hosts")
        assertThat(snapshot.database.toString(Charsets.UTF_8)).doesNotContain("private-ssh.invalid")
        assertThat(snapshot.database.toString(Charsets.UTF_8)).doesNotContain("SHA256:synthetic-trust")
        assertThat(snapshot.secrets.keys.map { it.value }).doesNotContain(SecretCatalog.sshCredential("ssh-backup").value)
        snapshot.wipe()
    }

    private lateinit var db: InfinityDatabase
    private lateinit var sourceRoot: Path
    private lateinit var sourceBase: Path
    private lateinit var restoreParent: Path
    private lateinit var preferences: FakePreferenceStore
    private lateinit var secrets: FakeSecretStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val testBase = Files.createTempDirectory(Path.of(System.getProperty("user.dir")), ".infinity-backup-test")
        sourceBase = Files.createDirectory(testBase.resolve("source"))
        sourceRoot = Files.createDirectory(sourceBase.resolve("root-1"))
        restoreParent = Files.createDirectory(testBase.resolve("restore"))
        preferences = FakePreferenceStore(
            BackupPreferenceSnapshot(
                entries = listOf(
                    BackupPreferenceEntry("provider", "provider_p1_name", "Provider One"),
                    BackupPreferenceEntry("provider", "provider_p1_base_url", "https://example.invalid/v1"),
                    BackupPreferenceEntry("provider", "provider_p1_api_key", "MUST_NOT_LEAK"),
                    BackupPreferenceEntry("settings", "language", "zh-CN"),
                    BackupPreferenceEntry("search", "tavily_api_key", "MUST_NOT_LEAK"),
                ),
                providerIds = setOf("p1"),
            )
        )
        secrets = FakeSecretStore().apply {
            put(SecretCatalog.providerApiKey("p1"), "provider-secret".toByteArray())
            put(SecretCatalog.automaticBackupPassword, "never-export".toByteArray())
            put(SecretId("unrelated"), "never-export-either".toByteArray())
        }
    }

    @After
    fun tearDown() {
        db.close()
        sourceBase.parent.toFile().deleteRecursively()
    }

    @Test
    fun `实际备份恢复保留查看描述文档与历史载荷`() = runBlocking {
        seedCompleteGraph()
        val viewed = listOf(
            ViewedMedia(
                ref = MediaRef(
                    path = "/media/图片.png",
                    mimeType = "image/png",
                    kind = MediaKind.IMAGE,
                    sizeBytes = 9L,
                    sha256 = "a".repeat(64),
                    width = 3,
                    height = 3,
                ),
            ),
            ViewedMedia(
                ref = MediaRef(
                    path = "/media/视频.mp4",
                    mimeType = "video/mp4",
                    kind = MediaKind.VIDEO,
                    sizeBytes = 42L,
                    sha256 = "b".repeat(64),
                    durationMs = 5_000L,
                ),
                mode = MediaViewMode.KEYFRAMES,
                frameTimestampsSeconds = listOf(1.25, 3.75),
            ),
        )
        val message = Message(
            id = "view-media-message",
            role = MessageRole.TOOL,
            content = "已查看图片与视频关键帧",
            userDocuments = listOf(
                MessageDocumentAttachment(
                    id = "view-note",
                    name = "说明.md",
                    mimeType = "text/markdown",
                    content = "# 媒体说明",
                    sizeBytes = 14L,
                    sha256 = "document-hash",
                    estimatedTokens = 4,
                ),
            ),
            legacyFilesPayload = """[{"uri":"legacy.pdf"}]""",
            legacyAttachmentsPayload = """[{"uri":"content://legacy"}]""",
            toolCallId = "view-call",
            createdAt = 101L,
            viewedMedia = viewed,
        )
        val entity = message.toEntity("session-1")
        db.messageDao().insert(entity)
        val source = newDataSource()

        val snapshot = source.snapshot(CANONICAL_CONTENT)
        val backedUpRow = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8))
            .jsonObject.getValue("tables").jsonObject.getValue("messages").jsonArray
            .single { it.jsonObject.getValue("id").jsonPrimitive.content == message.id }.jsonObject
        assertThat(backedUpRow.getValue("files").jsonPrimitive.content).isEqualTo(entity.files)
        db.clearAllTables()
        assertThat(db.messageDao().getById(message.id)).isNull()

        source.restore(validated(snapshot))

        val restored = db.messageDao().getById(message.id)!!
        assertThat(restored.files).isEqualTo(entity.files)
        assertThat(restored.toDomain()).isEqualTo(message)
        assertThat(restored.toDomain().viewedMedia).containsExactlyElementsIn(viewed).inOrder()
        assertThat(db.messageDao().getById("message-1")!!.toDomain().viewedMedia).isNull()
        snapshot.wipe()
    }

    @Test
    fun `snapshot refuses to begin while workspace mutation journal is unfinished`() = runBlocking {
        seedCompleteGraph()
        insertPreparedWorkspaceMutation("snapshot-pending")

        assertFails { newDataSource().snapshot(CANONICAL_CONTENT) }

        assertThat(db.workspaceMutationDao().get("snapshot-pending")).isNotNull()
    }

    @Test
    fun `backup excludes device mounts and restore clears their derived snapshot references`() = runBlocking {
        seedCompleteGraph()
        val mount = ExternalMountEntity("local-mount", "资料", "saf_tree", "content://test/tree/local", "", false, 1)
        db.externalMountDao().insert(mount)
        db.externalMountDao().putSnapshot(
            ExternalSnapshotEntity("local-mount", "/文件.txt", "root-1", "file-1", 2, "old-hash", 3),
        )
        val source = newDataSource()
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        val tables = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8))
            .jsonObject.getValue("tables").jsonObject
        assertThat(tables.keys).containsNoneOf("external_mounts", "external_snapshots")

        source.restore(validated(snapshot))

        assertThat(db.externalMountDao().get("local-mount")).isEqualTo(mount)
        assertThat(db.externalMountDao().snapshots("local-mount", "/文件.txt")).isEmpty()
    }

    @Test
    fun `restore rejects a current database whose Room identity is untrusted`() = runBlocking {
        seedCompleteGraph()
        val source = newDataSource()
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE room_master_table SET identity_hash='untrusted-room-identity' WHERE id=42",
        )

        assertFails { source.restore(validated(snapshot)) }

        assertThat(db.agentDao().getById("agent-1")).isNotNull()
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
    }

    @Test
    fun `restore rejects a historical version header even with the current Room identity`() = runBlocking {
        seedCompleteGraph()
        val source = newDataSource()
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        db.openHelper.writableDatabase.execSQL("PRAGMA user_version = 19")

        assertFails { source.restore(validated(snapshot)) }

        assertThat(db.agentDao().getById("agent-1")).isNotNull()
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
    }

    @Test
    fun `snapshot retries when Provider configuration revision changes across stores`() = runBlocking {
        seedCompleteGraph()
        val reads = java.util.concurrent.atomic.AtomicInteger(0)
        val revisions = longArrayOf(0L, 2L, 2L, 2L)

        val snapshot = newDataSource(
            configurationRevision = {
                revisions[reads.getAndIncrement().coerceAtMost(revisions.lastIndex)]
            },
        ).snapshot(CANONICAL_CONTENT)

        assertThat(reads.get()).isEqualTo(4)
        assertThat(preferences.snapshotCalls).isEqualTo(2)
        snapshot.wipe()
    }

    @Test
    fun `snapshot fails closed before reading stores while Provider configuration is mutating`() = runBlocking {
        seedCompleteGraph()

        assertFails {
            newDataSource(configurationRevision = { 1L }).snapshot(CANONICAL_CONTENT)
        }

        assertThat(preferences.snapshotCalls).isEqualTo(0)
    }

    @Test
    fun `cross-device backup strips device-local agent avatar paths`() = runBlocking {
        seedCompleteGraph()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE agents SET avatar_path = ? WHERE id = ?",
            arrayOf<Any?>("/data/user/0/old.install/files/avatars/agent-1.img", "agent-1"),
        )

        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val agent = root.getValue("tables").jsonObject.getValue("agents").jsonArray.single().jsonObject

        assertThat(agent.getValue("avatar_path")).isEqualTo(JsonNull)
        newDataSource().restore(validated(snapshot), "avatar-path-sanitize")
        assertThat(db.agentDao().getAll().single().avatarPath).isNull()
    }

    @Test
    fun `restore refuses to begin while workspace mutation journal is unfinished`() = runBlocking {
        seedCompleteGraph()
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        insertPreparedWorkspaceMutation("restore-pending")

        assertFails { newDataSource().restore(validated(backup)) }

        assertThat(db.workspaceMutationDao().get("restore-pending")).isNotNull()
    }

    @Test
    fun `restore revalidates old managed root immediately before database commit`() = runBlocking {
        seedCompleteGraph()
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        db.openHelper.writableDatabase.execSQL(
            "UPDATE agents SET name = ? WHERE id = ?",
            arrayOf<Any?>("Current", "agent-1"),
        )
        val racingOps = SourceMutatingRestoreFileOperations(sourceRoot)

        assertFails {
            newDataSource(fileOperations = racingOps).restore(validated(backup))
        }

        assertThat(db.agentDao().getAll().single().name).isEqualTo("Current")
    }

    @Test
    fun `snapshot and restore preserve source rows real files and safe preferences`() {
        runBlocking {
        val now = 100L
        val agent = AgentEntity(
            id = "agent-1",
            name = "Agent",
            description = "Customized description",
            nameCustomized = true,
            descriptionCustomized = true,
            createdAt = now,
        )
        val session = SessionEntity(
            id = "session-1",
            agentId = agent.id,
            workspaceRootUuid = "root-1",
            workspacePath = sourceRoot.toString(),
            createdAt = now,
            updatedAt = now,
        )
        val message = MessageEntity(
            id = "message-1",
            sessionId = session.id,
            role = "user",
            content = "hello",
            kgPaths = kotlinx.serialization.json.Json.encodeToString(
                listOf(
                    KgPath(
                        queryKeywords = listOf("Infinity Deck"),
                        nodes = listOf(KgNode("n1", "Infinity Deck", "project")),
                        edges = emptyList(),
                    )
                )
            ),
            createdAt = now,
        )
        val bytes = byteArrayOf(0, 1, 2, 127, -1)
        Files.createDirectories(sourceRoot.resolve("docs"))
        Files.write(sourceRoot.resolve("docs/a.bin"), bytes)
        val root = FileEntry(
            uuid = "root-1",
            workspaceRootUuid = "root-1",
            parentUuid = null,
            name = "workspace",
            hash = "",
            isDirectory = true,
            physicalRootPath = sourceRoot.toString(),
            materializedPath = "/",
            createdAt = now,
            updatedAt = now,
        )
        val file = FileEntry(
            uuid = "file-1",
            workspaceRootUuid = "root-1",
            parentUuid = root.uuid,
            name = "a.bin",
            hash = sha256(bytes),
            sizeBytes = bytes.size.toLong(),
            physicalRootPath = sourceRoot.toString(),
            materializedPath = "/docs/a.bin",
            createdAt = now,
            updatedAt = now,
        )
        db.agentDao().insert(agent)
        db.sessionDao().insert(session)
        db.messageDao().insert(message)
        db.fileEntryDao().insert(root)
        db.fileEntryDao().insert(file)

        val dataSource = newDataSource()
        val snapshot = dataSource.snapshot(
            setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES, BackupContent.SECRETS)
        )

        assertThat(snapshot.files.keys).containsExactly("file-1")
        assertThat(snapshot.files.getValue("file-1")).isEqualTo(bytes)
        assertThat(snapshot.preferences.toString(Charsets.UTF_8)).contains("provider_p1_name")
        assertThat(snapshot.preferences.toString(Charsets.UTF_8)).doesNotContain("api_key")
        assertThat(snapshot.preferences.toString(Charsets.UTF_8)).doesNotContain("MUST_NOT_LEAK")
        assertThat(snapshot.secrets.keys).containsExactly(SecretCatalog.providerApiKey("p1"))

        db.clearAllTables()
        preferences.snapshot = BackupPreferenceSnapshot(emptyList(), emptySet())
        dataSource.restore(validated(snapshot))

        assertThat(db.agentDao().getAll()).containsExactly(agent)
        assertThat(db.sessionDao().getAll()).containsExactly(session.copy(workspacePath = restoredRoot().toString()))
        assertThat(db.messageDao().getBySession(session.id)).containsExactly(message)
        assertThat(db.messageDao().getBySession(session.id).single().toDomain().kgPaths)
            .containsExactlyElementsIn(message.toDomain().kgPaths)
        val restoredFile = db.fileEntryDao().getByUuid(file.workspaceRootUuid, file.uuid)!!
        assertThat(Path.of(restoredFile.physicalRootPath).startsWith(restoreParent)).isTrue()
        assertThat(Files.readAllBytes(Path.of(restoredFile.physicalRootPath).resolve("docs/a.bin"))).isEqualTo(bytes)
            assertThat(preferences.snapshot.entries.map { it.key }).containsExactly(
                "provider_p1_name", "provider_p1_base_url", "language"
            )
        }
    }

    @Test
    fun `restore topologically inserts child even when payload lists it before parent`() = runBlocking {
        seedCompleteGraph()
        db.messageDao().insert(
            MessageEntity(
                id = "tool-child",
                sessionId = "session-1",
                role = "tool",
                content = "result",
                parentMessageId = "message-1",
                createdAt = 101L,
            ),
        )
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val childFirst = mutateRows(snapshot, "messages") { rows -> JsonArray(rows.reversed()) }

        db.clearAllTables()
        newDataSource().restore(childFirst)

        assertThat(db.messageDao().getById("tool-child")?.parentMessageId).isEqualTo("message-1")
    }

    @Test
    fun `restore rejects self reference and arbitrary parent cycle`() = runBlocking {
        seedCompleteGraph()
        db.messageDao().insert(
            MessageEntity(
                id = "tool-child",
                sessionId = "session-1",
                role = "tool",
                content = "result",
                parentMessageId = "message-1",
                createdAt = 101L,
            ),
        )
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val selfCycle = mutateRows(snapshot, "messages") { rows ->
            JsonArray(rows.map { element ->
                val row = element.jsonObject
                if (row.getValue("id").jsonPrimitive.content == "message-1") {
                    JsonObject(row + ("parent_message_id" to JsonPrimitive("message-1")))
                } else element
            })
        }
        val arbitraryCycle = mutateRows(snapshot, "messages") { rows ->
            JsonArray(rows.map { element ->
                val row = element.jsonObject
                when (row.getValue("id").jsonPrimitive.content) {
                    "message-1" -> JsonObject(row + ("parent_message_id" to JsonPrimitive("tool-child")))
                    "tool-child" -> JsonObject(row + ("parent_message_id" to JsonPrimitive("message-1")))
                    else -> element
                }
            })
        }

        assertFails { newDataSource().restore(selfCycle) }
        assertFails { newDataSource().restore(arbitraryCycle) }
    }

    @Test
    fun `restore validates ten thousand deep message chain without stack overflow`() = runBlocking {
        db.clearAllTables()
        db.agentDao().insert(AgentEntity("deep-agent", "Deep", createdAt = 1L))
        db.sessionDao().insert(
            SessionEntity("deep-session", "deep-agent", createdAt = 1L, updatedAt = 1L),
        )
        db.messageDao().insert(
            MessageEntity(
                id = "seed",
                sessionId = "deep-session",
                role = "assistant",
                content = "seed",
                createdAt = 1L,
            ),
        )
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val deep = mutateRows(snapshot, "messages") { rows ->
            val base = rows.single().jsonObject
            JsonArray((0 until 10_000).map { index ->
                JsonObject(
                    base + mapOf(
                        "id" to JsonPrimitive("deep-$index"),
                        "parent_message_id" to if (index == 0) JsonNull else JsonPrimitive("deep-${index - 1}"),
                        "created_at" to JsonPrimitive(index.toLong()),
                    ),
                )
            }.reversed())
        }

        db.clearAllTables()
        newDataSource().restore(deep)

        assertThat(db.messageDao().countBySession("deep-session")).isEqualTo(10_000)
    }

    @Test
    fun `database payload covers every user source table and excludes derived runtime tables`() {
        runBlocking {
            seedCompleteGraph()
            db.knowledgeCitationDao().insertAll(listOf(com.promenar.infinity.data.library.ask.KnowledgeCitationEntity(
                "citation-1", "file-1", 1, "vector-1", "CHAT", "session-1", "message-1", null, "K1", 100,
            )))

            val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
            val text = snapshot.database.toString(Charsets.UTF_8)
            val payload = Json.parseToJsonElement(text).jsonObject

            assertThat(snapshot.databaseSchemaVersion).isEqualTo(18)
            assertThat(payload.getValue("schemaVersion").jsonPrimitive.content).isEqualTo("18")

            listOf(
                "workspaces", "agents", "sessions", "messages", "attachments", "artifacts", "context_summaries",
                "tags", "document_tags", "task_nodes", "custom_skills", "mcp_servers",
                "workspace_files", "workspace_seq", "parameter_schemes", "long_term_memories", "agent_rule_revisions", "knowledge_citations", "kg_folder_settings", "kg_collections", "kg_collection_members", "kg_nodes", "kg_edges", "kg_evidence", "kg_edge_mentions", "kg_edge_validity", "kg_entity_links", "vectors",
            ).forEach { assertThat(text).contains("\"$it\"") }
            listOf(
                "vectors_fts", "kg_jit_cache", "kg_jit_cache_sources",
                "vectorization_tasks", "audit_logs", "tool_execution_ledger", "file_versions",
                "workspace_mutations", "mcp_tool_snapshots", "memory_embedding_cache",
            ).forEach { assertThat(text).doesNotContain("\"$it\"") }

            db.clearAllTables()
            newDataSource().restore(validated(snapshot))
            assertThat(rowCount("knowledge_citations")).isEqualTo(1)
            listOf(
                "workspaces", "agents", "sessions", "messages", "attachments", "artifacts", "context_summaries",
                "tags", "document_tags", "task_nodes", "custom_skills", "mcp_servers",
                "workspace_files", "workspace_seq", "parameter_schemes",
            ).forEach { assertThat(rowCount(it)).isGreaterThan(0) }
            val restoredAgent = db.agentDao().getById("agent-1")!!
            assertThat(restoredAgent.executionMode).isEqualTo("manual")
            assertThat(restoredAgent.skillIds).isEqualTo("[\"read_file\",\"calculator\"]")
            assertThat(restoredAgent.mcpServerIds).isEqualTo("[\"mcp-1\"]")
            assertThat(restoredAgent.defaultWorkspaceId).isEqualTo("ws-1")
            assertThat(restoredAgent.userRules).isEqualTo("先读 AGENTS.md😀")
            assertThat(listOf(restoredAgent.temperature, restoredAgent.topK?.toDouble())).containsExactly(0.3, 40.0).inOrder()
            assertThat(restoredAgent.reasoningEffort).isEqualTo("low")
            assertThat(db.parameterSchemeDao().getById("scheme-1")!!.name).isEqualTo("写代码")
            assertThat(db.sessionDao().getById("session-1")!!.workspaceId).isEqualTo("ws-1")
            assertThat(db.workspaceDao().getById("ws-1")!!.location).isEqualTo("demo")
            assertThat(db.messageDao().getById("message-1")!!.vectorizationStatus).isNull()
            assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")!!.vectorizedAt).isNull()
            assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")!!.kgExtractedAt).isNull()
            assertThat(Path.of(db.artifactDao().getById("artifact-1")!!.workspacePath!!).startsWith(restoreParent))
                .isTrue()
        }
    }

    @Test
    fun v13BackupWithoutCitationTableRestoresExistingData() = runBlocking {
        seedCompleteGraph()
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject - "knowledge_citations"
        val old = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(13), "tables" to JsonObject(tables)))
            .toString().toByteArray()
        db.clearAllTables()
        newDataSource().restore(validated(snapshot, database = old))
        assertThat(db.messageDao().getById("message-1")).isNotNull()
        assertThat(rowCount("knowledge_citations")).isEqualTo(0)
    }

    @Test
    fun `历史备份缺整个必需工作区表时拒绝且保留当前数据`() = runBlocking {
        val snapshot = emptyWorkspaceBackup()
        db.agentDao().insert(AgentEntity("sentinel", "当前数据", createdAt = 2))
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        for (version in 1..3) {
            val malformed = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(version),
                "tables" to JsonObject(historicalV4Tables(tables) - "workspace_files"))).toString().toByteArray()
            val failure = runCatching { newDataSource().restore(validated(snapshot, database = malformed)) }.exceptionOrNull()
            assertThat(failure).isInstanceOf(BackupValidationException::class.java)
            assertThat(db.agentDao().getById("sentinel")).isNotNull()
        }
    }

    @Test
    fun `恢复真实v3工作区行缺建图意图列时仍保全源数据`() = runBlocking {
        seedCompleteGraph()
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = historicalV4Tables(root.getValue("tables").jsonObject)
        val oldFiles = JsonArray(tables.getValue("workspace_files").jsonArray.map { JsonObject(it.jsonObject - "kg_strategy") })
        val oldDatabase = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(3),
            "tables" to JsonObject(tables + ("workspace_files" to oldFiles)))).toString().toByteArray()
        db.clearAllTables()
        newDataSource().restore(validated(snapshot, database = oldDatabase))
        assertThat(rowCount("long_term_memories")).isEqualTo(0)
        assertThat(db.messageDao().getById("message-1")).isNotNull()
        val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
        assertThat(file.kgStrategy).isNull()
        assertThat(file.kgExtractedAt).isNull()
    }

    @Test
    fun `restore upgrades v1 payload without agent execution mode to semi`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val tables = historicalV4Tables(root.getValue("tables").jsonObject)
        val legacyAgents = JsonArray(
            tables.getValue("agents").jsonArray.map { row ->
                JsonObject(row.jsonObject - "execution_mode" - "skill_ids" - "mcp_server_ids")
            },
        )
        val legacyDatabase = JsonObject(
            root + mapOf(
                "schemaVersion" to JsonPrimitive(1),
                "tables" to JsonObject(tables + ("agents" to legacyAgents)),
            ),
        ).toString().toByteArray()

        db.clearAllTables()
        newDataSource().restore(validated(current, database = legacyDatabase))
        assertThat(rowCount("long_term_memories")).isEqualTo(0)

        assertThat(db.agentDao().getById("agent-1")!!.executionMode).isEqualTo("semi")
        assertThat(db.agentDao().getById("agent-1")!!.skillIds).isEqualTo("[]")
        assertThat(db.agentDao().getById("agent-1")!!.mcpServerIds).isEqualTo("[]")
        assertThat(db.sessionDao().getById("session-1")!!.executionMode).isEqualTo("semi")
    }

    @Test
    fun `restore upgrades immutable v2 payload with missing agent selections only`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val tables = historicalV4Tables(root.getValue("tables").jsonObject)
        val baselineV2Agents = Json.parseToJsonElement(
            checkNotNull(javaClass.getResource("/backup/agents-schema-v2.json")).readText(),
        ).jsonArray
        val immutableV2 = JsonObject(
            root + mapOf(
                "schemaVersion" to JsonPrimitive(2),
                "tables" to JsonObject(tables + ("agents" to baselineV2Agents)),
            ),
        ).toString().toByteArray()

        db.clearAllTables()
        newDataSource().restore(validated(current, database = immutableV2))
        assertThat(rowCount("long_term_memories")).isEqualTo(0)

        val restored = db.agentDao().getById("agent-1")!!
        assertThat(restored.executionMode).isEqualTo("manual")
        assertThat(restored.skillIds).isEqualTo("[]")
        assertThat(restored.mcpServerIds).isEqualTo("[]")
        assertThat(db.sessionDao().getById("session-1")!!.executionMode).isEqualTo("semi")
    }

    @Test
    fun `v2 upgrade still rejects unrelated missing or extra agent columns`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val tables = historicalV4Tables(root.getValue("tables").jsonObject)
        val malformedAgents = JsonArray(
            tables.getValue("agents").jsonArray.map { row ->
                JsonObject(
                    (row.jsonObject - "skill_ids" - "mcp_server_ids" - "name") +
                        ("unexpected" to JsonPrimitive("value")),
                )
            },
        )
        val malformedV2 = JsonObject(
            root + mapOf(
                "schemaVersion" to JsonPrimitive(2),
                "tables" to JsonObject(tables + ("agents" to malformedAgents)),
            ),
        ).toString().toByteArray()

        assertFails { newDataSource().restore(validated(current, database = malformedV2)) }
    }

    @Test
    fun `restored workspace identity supports ensure read and write`() = runBlocking<Unit> {
        seedCompleteGraph()
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()

        newDataSource().restore(validated(backup))
        val workspace = WorkspaceRepository(
            db.fileEntryDao(), db.workspaceSeqDao(), fileOps = TestWorkspaceFileOps(),
        )
        val root = workspace.ensureSessionRoot("session-1")
        val file = workspace.getByUuid(root.uuid, "file-1")!!
        val fileOperations = FileOperationRepository(
            db.fileEntryDao(), db.fileVersionDao(), TestWorkspaceFileOps(),
        )

        assertThat(fileOperations.readFileRange(root.uuid, file.uuid).content).isEqualTo("complete-graph")
        assertThat(
            fileOperations.writeFileAtomic(root.uuid, file.uuid, "updated", "session-1", file.hash),
        ).isInstanceOf(WriteResult.Success::class.java)
        assertThat(fileOperations.readFileRange(root.uuid, file.uuid).content).isEqualTo("updated")
        assertThat(Files.exists(Path.of(root.physicalRootPath).resolve(".infinity_root_identity"))).isTrue()
    }

    @Test
    fun `restore clears every excluded derived and runtime table`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            seedDerivedRows()
            db.agentRuleRevisionDao().record(AgentRuleRevisionEntity("revision-1", "agent-1", "旧规则", "0".repeat(64), "user", 1))
            assertThat(rowCount("workspace_mutations")).isEqualTo(1)

            newDataSource().restore(validated(backup))

            listOf(
                "vectors", "vectors_fts", "kg_nodes", "kg_edges", "kg_evidence", "kg_collection_members", "kg_jit_cache", "kg_jit_cache_sources",
                "vectorization_tasks", "tool_execution_ledger", "file_versions", "workspace_mutations",
                "mcp_tool_snapshots",
            ).forEach { table -> assertThat(rowCount(table)).isEqualTo(0) }
            assertThat(rowCount("kg_collections")).isEqualTo(1)
            // 提示词历史属于核心层：恢复为备份时的状态，备份之后新增的版本不保留。
            assertThat(rowCount("agent_rule_revisions")).isEqualTo(0)
            assertThat(rowCount("audit_logs")).isEqualTo(1)
        }
    }

    @Test
    fun `prompt revisions travel with the core layer`() {
        runBlocking {
            seedCompleteGraph()
            db.agentRuleRevisionDao().record(AgentRuleRevisionEntity("revision-1", "agent-1", "第一版规则", "1".repeat(64), "user", 1))
            db.agentRuleRevisionDao().record(AgentRuleRevisionEntity("revision-2", "agent-1", "第二版规则", "2".repeat(64), "agent", 2))
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)

            db.clearAllTables()
            newDataSource().restore(validated(backup))

            val restored = db.agentRuleRevisionDao().observeForAgent("agent-1").first()
            assertThat(restored.map { it.id to it.content }).containsExactly("revision-2" to "第二版规则", "revision-1" to "第一版规则").inOrder()
            assertThat(restored.first().source).isEqualTo("agent")
        }
    }

    @Test
    fun `stable operation id leaves a completed receipt and duplicate restore is a no-op`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            val operationId = "123e4567-e89b-12d3-a456-426614174000"
            val source = newDataSource()

            source.restore(validated(backup), operationId)
            assertThat(source.hasCompletedRestore(operationId)).isTrue()

            db.agentDao().insert(AgentEntity("after-receipt", "must-survive", createdAt = 2))
            source.restore(validated(backup), operationId)

            assertThat(db.agentDao().getAll().map { it.id }).contains("after-receipt")
            assertThat(source.hasCompletedRestore(operationId)).isTrue()
        }
    }

    @Test
    fun `missing source file and symbolic link fail the whole snapshot`() {
        runBlocking {
            val seeded = seedCompleteGraph()
            Files.delete(seeded.filePath)
            assertFails { newDataSource().snapshot(CANONICAL_CONTENT) }

            db.clearAllTables()
            val second = seedCompleteGraph()
            val outside = Files.createTempFile("infinity-outside", ".bin")
            Files.delete(second.filePath)
            Files.createSymbolicLink(second.filePath, outside)
            try {
                assertFails { newDataSource().snapshot(CANONICAL_CONTENT) }
            } finally {
                Files.deleteIfExists(outside)
            }
        }
    }

    @Test
    fun `快照失败按会话文件不一致与容量超限分类`() {
        runBlocking {
            val seeded = seedCompleteGraph()
            Files.write(seeded.filePath, "changed-outside-app".toByteArray())
            val mismatch = assertFailsWith<BackupValidationException> { newDataSource().snapshot(CANONICAL_CONTENT) }
            assertThat(mismatch.reason).isEqualTo(BackupFailureReason.WORKSPACE_FILE_MISMATCH)

            Files.delete(seeded.filePath)
            val missing = assertFailsWith<BackupValidationException> { newDataSource().snapshot(CANONICAL_CONTENT) }
            assertThat(missing.reason).isEqualTo(BackupFailureReason.WORKSPACE_FILE_MISMATCH)

            val overBudget = ByteArray(BackupPackageLimits.MAX_IN_MEMORY_BYTES.toInt())
            Files.write(seeded.filePath, overBudget)
            val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
            db.fileEntryDao().update(file.copy(sizeBytes = overBudget.size.toLong(), hash = sha256(overBudget)))
            val capacity = assertFailsWith<BackupValidationException> { newDataSource().snapshot(CANONICAL_CONTENT) }
            assertThat(capacity.reason).isEqualTo(BackupFailureReason.CAPACITY_EXCEEDED)
        }
    }

    @Test
    fun `snapshot streams files within package limits and materializes an empty hash`() {
        runBlocking {
            seedCompleteGraph()
            val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
            db.fileEntryDao().update(file.copy(hash = ""))

            val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)

            val databaseJson = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
            val rows = databaseJson.getValue("tables").jsonObject.getValue("workspace_files").jsonArray
            val restoredHash = rows.single { it.jsonObject["uuid"]?.toString() == "\"file-1\"" }
                .jsonObject.getValue("hash").toString().trim('"')
            assertThat(restoredHash).isEqualTo(sha256(snapshot.files.getValue("file-1")))
        }
    }

    @Test
    fun `snapshot rejects a file replaced while its channel is being read`() {
        runBlocking {
            val seeded = seedCompleteGraph()

            assertFails {
                newDataSource(snapshotReadHook = { path ->
                    if (path == seeded.filePath) Files.write(path, "changed-after-read".toByteArray())
                }).snapshot(CANONICAL_CONTENT)
            }
        }
    }

    @Test
    fun `restore rejects a pure directory swap before move`() {
        runBlocking {
            seedCompleteGraph()
            db.fileEntryDao().insert(
                FileEntry(
                    uuid = "empty-dir", workspaceRootUuid = "root-1", parentUuid = "root-1",
                    name = "empty", hash = "", isDirectory = true, physicalRootPath = sourceRoot.toString(),
                    materializedPath = "/empty", createdAt = 100L, updatedAt = 100L,
                )
            )
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            val outside = Files.createDirectory(sourceBase.parent.resolve("outside-directory"))
            Files.write(outside.resolve("sentinel"), byteArrayOf(1))

            assertFails {
                newDataSource(fileOperations = SwappingRestoreFileOperations(SwapMode.DIRECTORY, outside))
                    .restore(validated(backup))
            }

            assertThat(Files.exists(outside.resolve("sentinel"))).isTrue()
        }
    }

    @Test
    fun `restore rejects a final file swap after the last write`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            val outside = Files.write(sourceBase.parent.resolve("outside-file"), "unchanged".toByteArray())

            assertFails {
                newDataSource(fileOperations = SwappingRestoreFileOperations(SwapMode.FILE, outside))
                    .restore(validated(backup))
            }

            assertThat(Files.readAllBytes(outside).toString(Charsets.UTF_8)).isEqualTo("unchanged")
        }
    }

    @Test
    fun `snapshot rejects oversized declarations before opening a source file`() {
        runBlocking {
            seedCompleteGraph()
            val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
            db.fileEntryDao().update(file.copy(sizeBytes = BackupPackageLimits.MAX_ENTRY_BYTES + 1))
            var opened = false

            assertFails {
                newDataSource(snapshotReadHook = { opened = true }).snapshot(CANONICAL_CONTENT)
            }

            assertThat(opened).isFalse()
        }
    }

    @Test
    fun `snapshot enforces the conservative Android materialization budget near its boundary`() {
        runBlocking {
            val seeded = seedCompleteGraph()
            val nearLimit = ByteArray(3 * 1024 * 1024) { (it % 251).toByte() }
            Files.write(seeded.filePath, nearLimit)
            val file = db.fileEntryDao().getByUuid("root-1", "file-1")!!
            db.fileEntryDao().update(file.copy(sizeBytes = nearLimit.size.toLong(), hash = sha256(nearLimit)))
            val accepted = newDataSource().snapshot(CANONICAL_CONTENT)
            assertThat(accepted.files.getValue("file-1").size).isEqualTo(nearLimit.size)

            val overBudget = ByteArray(BackupPackageLimits.MAX_IN_MEMORY_BYTES.toInt())
            Files.write(seeded.filePath, overBudget)
            db.fileEntryDao().update(
                file.copy(sizeBytes = overBudget.size.toLong(), hash = sha256(overBudget))
            )
            assertFails { newDataSource().snapshot(CANONICAL_CONTENT) }
        }
    }

    @Test
    fun `only canonical full content packages are accepted before any file or external write`() {
        runBlocking {
            seedCompleteGraph()
            var opened = false
            assertFails {
                newDataSource(snapshotReadHook = { opened = true })
                    .snapshot(setOf(BackupContent.DATABASE, BackupContent.FILES))
            }
            assertThat(opened).isFalse()

            val full = newDataSource().snapshot(CANONICAL_CONTENT)
            val manifestOwner = validated(full)
            val validManifest = manifestOwner.manifest
            val invalidManifest = validated(
                full,
                manifest = validManifest.copy(
                    entries = validManifest.entries.filterNot { it.path == "preferences.json" }
                ),
            )
            manifestOwner.close()
            assertFails { newDataSource().restore(invalidManifest) }
            assertThat(preferences.prepareCalls).isEqualTo(0)
        }
    }

    @Test
    fun `restore consumes and wipes every validated backup byte array on success and failure`() {
        runBlocking {
            seedCompleteGraph()
            val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
            val success = validated(snapshot)
            newDataSource().restore(success)
            assertValidatedBackupWiped(success)

            val failure = validated(snapshot, database = "not-json".toByteArray())
            assertFails { newDataSource().restore(failure) }
            assertValidatedBackupWiped(failure)
        }
    }

    @Test
    fun `parse hash duplicate and foreign key failures leave database preferences and roots unchanged`() {
        runBlocking {
            val seeded = seedCompleteGraph()
            val source = newDataSource().snapshot(
                setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
            )
            val originalPreferences = preferences.snapshot
            val originalAgent = db.agentDao().getAll()

            val failures = listOf(
                validated(source, database = "not-json".toByteArray()),
                validated(source, files = source.files + ("file-1" to byteArrayOf(9))),
                mutateRows(source, "agents") { rows -> JsonArray(rows + rows.first()) },
                mutateRows(source, "messages") { rows ->
                    JsonArray(rows.map { row ->
                        JsonObject(row.jsonObject + ("session_id" to JsonPrimitive("missing-session")))
                    })
                },
                mutateRows(source, "workspace_files") { rows ->
                    JsonArray(rows.map { row ->
                        if (row.jsonObject["uuid"]?.toString() == "\"file-1\"") {
                            JsonObject(row.jsonObject + ("materialized_path" to JsonPrimitive("/../escape.bin")))
                        } else row
                    })
                },
                mutateRows(source, "workspace_files") { rows ->
                    JsonArray(rows.map { row ->
                        if (row.jsonObject["uuid"]?.toString() == "\"file-1\"") {
                            JsonObject(row.jsonObject + ("hash" to JsonPrimitive("")))
                        } else row
                    })
                },
                mutateRows(source, "workspace_files") { rows ->
                    JsonArray(rows.map { row ->
                        if (row.jsonObject["uuid"]?.toString() == "\"file-1\"") {
                            JsonObject(row.jsonObject + ("workspace_root_uuid" to JsonPrimitive("missing-root")))
                        } else row
                    })
                },
            )

            failures.forEach { backup ->
                assertFails { newDataSource().restore(backup) }
                assertThat(db.agentDao().getAll()).containsExactlyElementsIn(originalAgent)
                assertThat(preferences.snapshot).isEqualTo(originalPreferences)
                assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
            }
            assertThat(Files.exists(seeded.filePath)).isTrue()
        }
    }

    @Test
    fun `database insertion failure rolls back rows and deletes staged restore root`() {
        runBlocking {
            seedCompleteGraph()
            val snapshot = newDataSource().snapshot(
                setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
            )
            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "before", createdAt = 1)
            db.agentDao().insert(sentinel)
            val malformed = mutateRows(snapshot, "agents") { rows ->
                JsonArray(rows.map { JsonObject(it.jsonObject + ("unknown_column" to JsonPrimitive("boom"))) })
            }

            assertFails { newDataSource().restore(malformed) }

            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
            assertThat(preferences.prepareCalls).isEqualTo(0)
        }
    }

    @Test
    fun `preference commit failure rolls back Room and preferences and deletes staged root`() {
        runBlocking {
            seedCompleteGraph()
            val snapshot = newDataSource().snapshot(
                setOf(BackupContent.DATABASE, BackupContent.PREFERENCES, BackupContent.FILES)
            )
            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "before", createdAt = 1)
            db.agentDao().insert(sentinel)
            val beforePreferences = BackupPreferenceSnapshot(
                listOf(BackupPreferenceEntry("settings", "language", "en")),
                emptySet(),
            )
            preferences.snapshot = beforePreferences
            preferences.failCommit = true

            assertFails { newDataSource().restore(validated(snapshot)) }

            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(preferences.snapshot).isEqualTo(beforePreferences)
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    @Test
    fun `preference preflight failure occurs before journal and external writes`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "old", createdAt = 1)
            db.agentDao().insert(sentinel)
            val oldPreferences = BackupPreferenceSnapshot(
                listOf(BackupPreferenceEntry("settings", "language", "en")), emptySet()
            )
            preferences.snapshot = oldPreferences
            preferences.failPreflight = true

            assertFails { newDataSource().restore(validated(backup)) }

            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(preferences.prepareCalls).isEqualTo(0)
            assertThat(preferences.snapshot).isEqualTo(oldPreferences)
            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    @Test
    fun `prepare failure before preference record recovers old state across datasource recreation`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "old", createdAt = 1)
            db.agentDao().insert(sentinel)
            val oldPreferences = BackupPreferenceSnapshot(
                listOf(BackupPreferenceEntry("settings", "language", "en")), emptySet()
            )
            preferences.snapshot = oldPreferences
            preferences.prepareFailureBeforeRecord = SimulatedRestoreProcessDeath(RestoreCrashPoint.JOURNAL_PREPARED)

            var interrupted = false
            try {
                newDataSource().restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                interrupted = true
            }
            assertThat(interrupted).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

            preferences = preferences.reopen().also { it.prepareFailureBeforeRecord = null }
            secrets = secrets.reopen()
            newDataSource().recoverInterruptedRestore()

            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(preferences.snapshot).isEqualTo(oldPreferences)
            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    @Test
    fun `interrupted restore recovers to exactly old or new state after datasource recreation`() {
        runBlocking {
            RestoreCrashPoint.entries.filter { it !in setOf(
                RestoreCrashPoint.SKILLS_STAGED, RestoreCrashPoint.SKILLS_OLD_MOVED, RestoreCrashPoint.SKILLS_COMMITTED,
                RestoreCrashPoint.AVATARS_STAGED, RestoreCrashPoint.AVATARS_OLD_MOVED, RestoreCrashPoint.AVATARS_COMMITTED,
            ) }.forEach { point ->
                db.clearAllTables()
                if (!Files.exists(sourceRoot)) Files.createDirectories(sourceRoot)
                seedCompleteGraph()
                preferences.snapshot = BackupPreferenceSnapshot(
                    listOf(BackupPreferenceEntry("settings", "language", "zh")), setOf("p1")
                )
                insertMemory("new-memory", content = "恢复后的偏好")
                val backup = newDataSource().snapshot(CANONICAL_CONTENT)

                db.clearAllTables()
                val sentinel = AgentEntity("sentinel", "old", createdAt = 1)
                db.agentDao().insert(sentinel)
                insertMemory("old-memory", content = "恢复前的偏好")
                preferences.snapshot = BackupPreferenceSnapshot(
                    listOf(BackupPreferenceEntry("settings", "language", "en")), emptySet()
                )

                var interrupted = false
                try {
                    newDataSource(point).restore(validated(backup))
                } catch (_: SimulatedRestoreProcessDeath) {
                    interrupted = true
                }
                assertThat(interrupted).isTrue()

                preferences = preferences.reopen()
                secrets = secrets.reopen()
                newDataSource().recoverInterruptedRestore()

                val committed = point == RestoreCrashPoint.ROOM_COMMITTED ||
                    point == RestoreCrashPoint.JOURNAL_COMMITTED ||
                    point == RestoreCrashPoint.RECEIPT_PERSISTED
                if (committed) {
                    assertThat(db.agentDao().getAll().map { it.id }).containsExactly("agent-1")
                    assertThat(preferences.snapshot.entries.single().value).isEqualTo("zh")
                } else {
                    assertThat(db.agentDao().getAll()).containsExactly(sentinel)
                    assertThat(preferences.snapshot.entries.single().value).isEqualTo("en")
                }
                db.openHelper.writableDatabase.query("SELECT id,content FROM long_term_memories").use { cursor ->
                    assertThat(cursor.count).isEqualTo(1)
                    check(cursor.moveToFirst())
                    assertThat(cursor.getString(0)).isEqualTo(memoryId(if (committed) "new-memory" else "old-memory"))
                    assertThat(cursor.getString(1)).isEqualTo(if (committed) "恢复后的偏好" else "恢复前的偏好")
                }
                assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            }
        }
    }

    @Test
    fun `prepared journal without a moved root always rolls back even when database fingerprint matches`() {
        runBlocking {
            val backup = emptyWorkspaceBackup()
            try {
                newDataSource(RestoreCrashPoint.JOURNAL_PREPARED).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }

            newDataSource().recoverInterruptedRestore()

            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(Files.list(restoreParent).use { it.count() }).isEqualTo(0)
        }
    }

    @Test
    fun `committed journal rolls forward after ordinary database changes`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            try {
                newDataSource(RestoreCrashPoint.JOURNAL_COMMITTED).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }
            db.messageDao().insert(
                MessageEntity("post-commit", "session-1", "user", "later", createdAt = 200L)
            )

            newDataSource().recoverInterruptedRestore()

            assertThat(db.messageDao().getById("post-commit")).isNotNull()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(Files.exists(Path.of(db.fileEntryDao().getByUuid("root-1", "file-1")!!.physicalRootPath))).isTrue()
        }
    }

    @Test
    fun `descriptor delete rejects a sentinel added after the old-root precheck and retains journal`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            try {
                newDataSource(RestoreCrashPoint.JOURNAL_COMMITTED).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }
            val fileOps = TestRestoreFileOperations().apply {
                beforeDelete = { root ->
                    if (root.startsWith(sourceBase)) Files.write(root.resolve("late-sentinel"), byteArrayOf(1))
                }
            }

            assertFails { newDataSource(fileOperations = fileOps).recoverInterruptedRestore() }

            assertThat(Files.newDirectoryStream(sourceBase).use { roots ->
                roots.any { Files.exists(it.resolve("late-sentinel")) }
            }).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()
        }
    }

    @Test
    fun `a restored workspace can be snapshotted and restored again with the same trusted bases`() {
        runBlocking {
            seedCompleteGraph()
            val first = newDataSource().snapshot(CANONICAL_CONTENT)
            newDataSource().restore(validated(first))

            val second = newDataSource().snapshot(CANONICAL_CONTENT)
            newDataSource().restore(validated(second))

            assertThat(db.fileEntryDao().getByUuid("root-1", "file-1")).isNotNull()
            assertThat(second.files.getValue("file-1").toString(Charsets.UTF_8)).isEqualTo("complete-graph")
        }
    }

    @Test
    fun `one datasource serializes concurrent restore calls and leaves no journal`() {
        runBlocking {
            seedCompleteGraph()
            val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
            val dataSource = newDataSource()

            listOf(validated(snapshot), validated(snapshot)).map { backup ->
                async(Dispatchers.Default) { dataSource.restore(backup) }
            }.awaitAll()

            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
            assertThat(db.agentDao().getAll().map { it.id }).containsExactly("agent-1")
        }
    }

    @Test
    fun `recovery refuses a symlink-swapped restore root and keeps its journal`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            db.agentDao().insert(AgentEntity("sentinel", "old", createdAt = 1))

            try {
                newDataSource(RestoreCrashPoint.FILES_MOVED).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程已退出。
            }
            val record = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator).read()!!
            val finalRoot = restoreParent.resolve(record.newRootIdentity)
            finalRoot.toFile().deleteRecursively()
            val outside = Files.createDirectory(sourceBase.parent.resolve("outside-keep"))
            Files.write(outside.resolve("sentinel"), "keep".toByteArray())
            Files.createSymbolicLink(finalRoot, outside)

            assertFails { newDataSource().recoverInterruptedRestore() }

            assertThat(Files.exists(outside.resolve("sentinel"))).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()
            Files.delete(finalRoot)
            newDataSource().recoverInterruptedRestore()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `备份恢复中断后设备号变化可回滚新根并保留旧根`() = runBlocking {
        seedCompleteGraph()
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        try {
            newDataSource(RestoreCrashPoint.FILES_MOVED).restore(validated(backup))
        } catch (_: SimulatedRestoreProcessDeath) {
            // 模拟新根已发布后进程退出。
        }
        val journal = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator)
        val record = journal.read()!!
        val finalRoot = restoreParent.resolve(record.newRootIdentity)
        journal.write(record.copy(newRootFileKey = identityAfterDeviceChange(finalRoot)))

        newDataSource().recoverInterruptedRestore()

        assertThat(Files.exists(finalRoot)).isFalse()
        assertThat(Files.isDirectory(sourceRoot)).isTrue()
        assertThat(db.messageDao().getById("message-1")).isNotNull()
        assertThat(journal.read()).isNull()
    }

    @Test
    fun `备份提交后设备号变化可定位并清理旧managed根`() = runBlocking {
        seedCompleteGraph()
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        try {
            newDataSource(RestoreCrashPoint.JOURNAL_COMMITTED).restore(validated(backup))
        } catch (_: SimulatedRestoreProcessDeath) {
            // 模拟数据库与journal均提交后进程退出。
        }
        val journal = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator)
        val record = journal.read()!!
        val parts = record.oldRootIdentity.split(':', limit = 3)
        val oldKey = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(identityAfterDeviceChange(sourceRoot).toByteArray())
        journal.write(record.copy(
            newRootFileKey = identityAfterDeviceChange(restoreParent.resolve(record.newRootIdentity)),
            oldRootIdentity = "${parts[0]}:$oldKey:${parts[2]}",
        ))

        newDataSource().recoverInterruptedRestore()

        assertThat(Files.exists(sourceRoot)).isFalse()
        assertThat(Files.isDirectory(restoreParent.resolve(record.newRootIdentity))).isTrue()
        assertThat(db.messageDao().getById("message-1")).isNotNull()
        assertThat(journal.read()).isNull()
    }

    @Test
    fun `旧managed根同inode的nonce变化时保留journal且修复后可收尾`() = runBlocking {
        assertOldRootIdentityFailureKeepsJournal("22222222-2222-4222-8222-222222222222".toByteArray())
    }

    @Test
    fun `旧managed根nonce属性损坏时保留journal且修复后可收尾`() = runBlocking {
        assertOldRootIdentityFailureKeepsJournal("invalid-nonce".toByteArray())
    }

    private suspend fun assertOldRootIdentityFailureKeepsJournal(value: ByteArray) {
        seedCompleteGraph()
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        try {
            newDataSource(RestoreCrashPoint.JOURNAL_COMMITTED).restore(validated(backup))
        } catch (_: SimulatedRestoreProcessDeath) {
            // 模拟数据库已提交、旧根尚待清理的状态。
        }
        val journal = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator)
        val record = journal.read()!!
        val view = Files.getFileAttributeView(sourceRoot, java.nio.file.attribute.UserDefinedFileAttributeView::class.java)
        val name = view.list().single { it == "infinity_persistent_identity" }
        val original = java.nio.ByteBuffer.allocate(view.size(name))
        view.read(name, original)
        view.write(name, java.nio.ByteBuffer.wrap(value))

        val failure = runCatching { newDataSource().recoverInterruptedRestore() }.exceptionOrNull()

        assertThat(failure).isInstanceOf(BackupValidationException::class.java)
        assertThat(Files.isDirectory(sourceRoot)).isTrue()
        assertThat(readUtf8(sourceRoot.resolve("docs/a.txt"))).isEqualTo("complete-graph")
        assertThat(journal.read()).isEqualTo(record)
        assertThat(newDataSource().hasCompletedRestore(record.txId)).isFalse()

        original.flip()
        view.write(name, original)
        newDataSource().recoverInterruptedRestore()

        assertThat(Files.exists(sourceRoot)).isFalse()
        assertThat(journal.read()).isNull()
        assertThat(newDataSource().hasCompletedRestore(record.txId)).isTrue()
    }

    private fun identityAfterDeviceChange(path: Path): String {
        val identity = PersistentFileIdentity.read(path)
        val inode = PersistentFileIdentity.inode(Files.readAttributes(
            path, java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey())
        return PersistentFileIdentity.encode(PersistentFileIdentity.nonce(identity), "(dev=999,$inode)")
    }

    @Test
    fun `cleanup keeps owner marker until deep deletion succeeds and retry completes after reopen`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            db.agentDao().insert(AgentEntity("sentinel", "old", createdAt = 1L))
            val fileOps = TestRestoreFileOperations()
            try {
                newDataSource(RestoreCrashPoint.FILES_MOVED, fileOperations = fileOps).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }
            val record = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator).read()!!
            fileOps.failDeleteAt = 1

            assertFails { newDataSource(fileOperations = fileOps).recoverInterruptedRestore() }

            val cleanupRoot = Files.list(restoreParent).use { entries ->
                entries.filter { it.fileName.toString().startsWith(".restore-delete-${record.txId}-new") }
                    .findFirst().orElseThrow()
            }
            assertThat(Files.exists(cleanupRoot.resolve(".restore-owner"))).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

            preferences = preferences.reopen()
            secrets = secrets.reopen()
            fileOps.clearDeleteFailure()
            newDataSource(fileOperations = fileOps).recoverInterruptedRestore()
            assertThat(Files.exists(cleanupRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `cleanup retries an empty markerless tombstone after final directory deletion fails`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            db.clearAllTables()
            db.agentDao().insert(AgentEntity("sentinel", "old", createdAt = 1L))
            val fileOps = TestRestoreFileOperations().apply { failFinalRmdirOnce = true }
            try {
                newDataSource(RestoreCrashPoint.FILES_MOVED, fileOperations = fileOps).restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }

            assertFails { newDataSource(fileOperations = fileOps).recoverInterruptedRestore() }
            val record = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator).read()!!
            val tombstone = restoreParent.resolve(".restore-delete-${record.txId}-new")
            assertThat(Files.isDirectory(tombstone)).isTrue()
            assertThat(Files.list(tombstone).use { it.count() }).isEqualTo(0)
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

            newDataSource(fileOperations = fileOps).recoverInterruptedRestore()

            assertThat(Files.exists(tombstone)).isFalse()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `空tombstone被复制nonce的另一inode替换后保留journal并拒绝删除`() = runBlocking {
        seedCompleteGraph()
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        val operations = TestRestoreFileOperations().apply { failFinalRmdirOnce = true }
        try {
            newDataSource(RestoreCrashPoint.FILES_MOVED, fileOperations = operations).restore(validated(backup))
        } catch (_: SimulatedRestoreProcessDeath) {
            // 模拟文件已发布后退出，再中断最后的目录删除。
        }
        assertFails { newDataSource(fileOperations = operations).recoverInterruptedRestore() }
        val journal = FileRestoreJournal(restoreParent, TestRestoreJournalAuthenticator)
        val record = journal.read()!!
        val tombstone = restoreParent.resolve(".restore-delete-${record.txId}-new")
        val original = Files.move(tombstone, sourceBase.parent.resolve("original-tombstone"))
        Files.createDirectory(tombstone)
        val from = Files.getFileAttributeView(original, java.nio.file.attribute.UserDefinedFileAttributeView::class.java)
        val to = Files.getFileAttributeView(tombstone, java.nio.file.attribute.UserDefinedFileAttributeView::class.java)
        from.list().forEach { name ->
            val bytes = java.nio.ByteBuffer.allocate(from.size(name))
            from.read(name, bytes)
            bytes.flip()
            to.write(name, bytes)
        }

        assertFails { newDataSource(fileOperations = operations).recoverInterruptedRestore() }

        assertThat(Files.isDirectory(tombstone)).isTrue()
        assertThat(Files.isDirectory(original)).isTrue()
        assertThat(journal.read()).isEqualTo(record)
    }

    @Test
    fun `committed recovery preserves journal and old root when unmanaged sentinel appears`() {
        runBlocking {
            seedCompleteGraph()
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)
            val fileOps = TestRestoreFileOperations()
            try {
                newDataSource(RestoreCrashPoint.JOURNAL_COMMITTED, fileOperations = fileOps)
                    .restore(validated(backup))
            } catch (_: SimulatedRestoreProcessDeath) {
                // 模拟进程退出。
            }
            val sentinel = Files.write(sourceRoot.resolve("unmanaged-sentinel"), byteArrayOf(7))

            assertFails { newDataSource(fileOperations = fileOps).recoverInterruptedRestore() }

            assertThat(Files.exists(sentinel)).isTrue()
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()
            Files.delete(sentinel)
            newDataSource(fileOperations = fileOps).recoverInterruptedRestore()
            assertThat(Files.exists(sourceRoot, java.nio.file.LinkOption.NOFOLLOW_LINKS)).isFalse()
        }
    }

    @Test
    fun `restore replaces the complete eligible secret set and never touches automatic password`() {
        runBlocking {
            seedCompleteGraph()
            val providerId = SecretCatalog.providerApiKey("p1")
            val removedProviderId = SecretCatalog.providerApiKey("removed-provider")
            secrets.put(providerId, "new-provider".toByteArray())
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)

            preferences.snapshot = preferences.snapshot.copy(providerIds = setOf("removed-provider"))
            secrets.put(providerId, "old-provider".toByteArray())
            secrets.put(removedProviderId, "stale-removed-provider".toByteArray())
            secrets.put(SecretCatalog.tavilyApiKey, "stale-tavily".toByteArray())
            secrets.put(SecretCatalog.embeddingApiKey, "stale-embedding".toByteArray())
            secrets.put(SecretCatalog.automaticBackupPassword, "keep-local".toByteArray())

            newDataSource().restore(validated(backup))

            assertThat(secrets.get(providerId)!!.toString(Charsets.UTF_8)).isEqualTo("new-provider")
            assertThat(secrets.get(removedProviderId)).isNull()
            assertThat(secrets.get(SecretCatalog.tavilyApiKey)).isNull()
            assertThat(secrets.get(SecretCatalog.embeddingApiKey)).isNull()
            assertThat(secrets.get(SecretCatalog.automaticBackupPassword)!!.toString(Charsets.UTF_8))
                .isEqualTo("keep-local")
            assertThat(secrets.receivedPlaintextRefs.flatten().all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
        }
    }

    @Test
    fun `secret second write remove and rollback failures preserve a recoverable journal`() {
        runBlocking {
            seedCompleteGraph()
            val providerId = SecretCatalog.providerApiKey("p1")
            secrets.put(providerId, "new-provider".toByteArray())
            secrets.put(SecretCatalog.tavilyApiKey, "new-tavily".toByteArray())
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)

            db.clearAllTables()
            val sentinel = AgentEntity("sentinel", "old", createdAt = 1)
            db.agentDao().insert(sentinel)
            secrets.put(providerId, "old-provider".toByteArray())
            secrets.put(SecretCatalog.tavilyApiKey, "old-tavily".toByteArray())
            secrets.failWriteAt = 1
            secrets.failRollback = true

            assertFails { newDataSource().restore(validated(backup)) }
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isTrue()

            secrets.failWriteAt = null
            secrets.failRollback = false
            newDataSource().recoverInterruptedRestore()
            assertThat(db.agentDao().getAll()).containsExactly(sentinel)
            assertThat(secrets.get(providerId)!!.toString(Charsets.UTF_8)).isEqualTo("old-provider")
            assertThat(secrets.get(SecretCatalog.tavilyApiKey)!!.toString(Charsets.UTF_8)).isEqualTo("old-tavily")
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `secret second write or remove failure compensates to the complete old set`() {
        runBlocking {
            seedCompleteGraph()
            val providerId = SecretCatalog.providerApiKey("p1")
            secrets.put(providerId, "new-provider".toByteArray())
            secrets.put(SecretCatalog.tavilyApiKey, "new-tavily".toByteArray())
            val backup = newDataSource().snapshot(CANONICAL_CONTENT)

            listOf("second-write", "remove").forEach { failure ->
                db.clearAllTables()
                db.agentDao().insert(AgentEntity("sentinel", "old", createdAt = 1))
                secrets.put(providerId, "old-provider".toByteArray())
                secrets.put(SecretCatalog.tavilyApiKey, "old-tavily".toByteArray())
                secrets.failWriteAt = if (failure == "second-write") 1 else null
                secrets.failRemove = failure == "remove"

                assertFails { newDataSource().restore(validated(backup)) }

                assertThat(secrets.get(providerId)!!.toString(Charsets.UTF_8)).isEqualTo("old-provider")
                assertThat(secrets.get(SecretCatalog.tavilyApiKey)!!.toString(Charsets.UTF_8)).isEqualTo("old-tavily")
                assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
                secrets.failWriteAt = null
                secrets.failRemove = false
            }
        }
    }

    @Test
    fun `技能分区经真实包往返后整体替换含空目录的技能树`(): Unit = runBlocking {
        val skills = sourceBase.resolve("agent_skills")
        Files.createDirectories(skills.resolve("alpha/scripts"))
        Files.createDirectories(skills.resolve("beta/empty"))
        writeUtf8(skills.resolve("alpha/SKILL.md"), "甲")
        writeUtf8(skills.resolve("alpha/scripts/run.py"), "print(1)")
        writeUtf8(skills.resolve("beta/SKILL.md"), "乙")
        val source = newDataSource(userSkillsRoot = skills)
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        val codec = DefaultBackupPackageCodec()
        val packageBytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        snapshot.wipe()
        skills.toFile().deleteRecursively()
        Files.createDirectories(skills)
        writeUtf8(skills.resolve("old"), "旧内容")

        val result = source.restoreWithResult(codec.decode(packageBytes, null), "skills-roundtrip")

        assertThat(readUtf8(skills.resolve("alpha/SKILL.md"))).isEqualTo("甲")
        assertThat(readUtf8(skills.resolve("alpha/scripts/run.py"))).isEqualTo("print(1)")
        assertThat(readUtf8(skills.resolve("beta/SKILL.md"))).isEqualTo("乙")
        assertThat(Files.isDirectory(skills.resolve("beta/empty"))).isTrue()
        assertThat(Files.exists(skills.resolve("old"))).isFalse()
        assertThat(result.skills?.skillCount).isEqualTo(2)
        assertThat(Files.list(sourceBase).use { it.map { p -> p.fileName.toString() }.toList() })
            .containsExactly("root-1", "agent_skills")
    }

    @Test
    fun `旧格式不含技能分区时原技能树不被修改`() = runBlocking {
        val skills = sourceBase.resolve("agent_skills")
        Files.createDirectories(skills)
        writeUtf8(skills.resolve("keep"), "保留")
        val backup = newDataSource().snapshot(CANONICAL_CONTENT)
        val legacy = validated(backup)
        assertThat(legacy.manifest.formatVersion).isEqualTo(1)

        newDataSource(userSkillsRoot = skills).restore(legacy)

        assertThat(readUtf8(skills.resolve("keep"))).isEqualTo("保留")
    }

    @Test
    fun `技能目录在提交失败时回滚且恢复原有符号链接`() = runBlocking {
        val skills = sourceBase.resolve("agent_skills")
        Files.createDirectories(skills)
        writeUtf8(skills.resolve("new"), "新")
        val source = newDataSource(userSkillsRoot = skills)
        val snapshot = source.snapshot(CANONICAL_CONTENT)
        val codec = DefaultBackupPackageCodec()
        val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        snapshot.wipe()
        Files.delete(skills.resolve("new"))
        writeUtf8(skills.resolve("old"), "旧")
        Files.createSymbolicLink(skills.resolve("link"), skills.resolve("old"))
        preferences.failCommit = true

        assertFails { source.restore(codec.decode(bytes, null)) }

        assertThat(readUtf8(skills.resolve("old"))).isEqualTo("旧")
        assertThat(Files.isSymbolicLink(skills.resolve("link"))).isTrue()
        assertThat(Files.exists(skills.resolve("new"))).isFalse()
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
    }

    @Test
    fun `技能目录在每个事务中断点重启后与数据库一致且恢复幂等`() = runBlocking {
        val skills = sourceBase.resolve("agent_skills")
        val codec = DefaultBackupPackageCodec()
        RestoreCrashPoint.entries.filterNot { it.name.startsWith("AVATARS_") }.forEach { point ->
            skills.toFile().deleteRecursively()
            Files.createDirectories(skills)
            writeUtf8(skills.resolve("new"), "新")
            val snapshot = newDataSource(userSkillsRoot = skills).snapshot(CANONICAL_CONTENT)
            val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
            snapshot.wipe()
            Files.delete(skills.resolve("new"))
            writeUtf8(skills.resolve("old"), "旧")
            var interrupted = false
            try { newDataSource(point, userSkillsRoot = skills).restore(codec.decode(bytes, null)) }
            catch (_: SimulatedRestoreProcessDeath) { interrupted = true }
            assertThat(interrupted).isTrue()
            newDataSource(userSkillsRoot = skills).recoverInterruptedRestore()
            newDataSource(userSkillsRoot = skills).recoverInterruptedRestore()
            val committed = point == RestoreCrashPoint.ROOM_COMMITTED ||
                point == RestoreCrashPoint.JOURNAL_COMMITTED || point == RestoreCrashPoint.RECEIPT_PERSISTED
            assertThat(Files.exists(skills.resolve("new"))).isEqualTo(committed)
            assertThat(Files.exists(skills.resolve("old"))).isEqualTo(!committed)
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `技能暂存所有者标记写入失败后回滚不阻断下次启动`(): Unit = runBlocking {
        val skills = sourceBase.resolve("agent_skills")
        Files.createDirectories(skills)
        writeUtf8(skills.resolve("old"), "旧")
        val codec = DefaultBackupPackageCodec()
        val snapshot = newDataSource(userSkillsRoot = skills).snapshot(CANONICAL_CONTENT)
        val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        snapshot.wipe()
        val ops = object : RestoreFileOperations by TestRestoreFileOperations() {
            override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) {
                if (relative == listOf(".restore-owner") && root.fileName.toString().contains("skills-preparing")) {
                    throw java.io.IOException("注入技能所有者标记写入失败")
                }
                TestRestoreFileOperations().writeNew(root, relative, bytes)
            }
        }

        assertFails { newDataSource(fileOperations = ops, userSkillsRoot = skills).restore(codec.decode(bytes, null)) }
        newDataSource(userSkillsRoot = skills).recoverInterruptedRestore()

        assertThat(readUtf8(skills.resolve("old"))).isEqualTo("旧")
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        assertThat(Files.list(sourceBase).use { it.map { p -> p.fileName.toString() }.toList() })
            .containsExactly("root-1", "agent_skills")
    }

    @Test
    fun `技能清理在所有者标记删除后最终 rmdir 失败仍能幂等重试`() = runBlocking {
        val skills = sourceBase.resolve("agent_skills")
        val snapshot = newDataSource(userSkillsRoot = skills).snapshot(CANONICAL_CONTENT)
        val codec = DefaultBackupPackageCodec()
        val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        snapshot.wipe()
        try { newDataSource(RestoreCrashPoint.ROOM_COMMITTED, userSkillsRoot = skills).restore(codec.decode(bytes, null)) }
        catch (_: SimulatedRestoreProcessDeath) { }
        val ops = TestRestoreFileOperations().apply { failFinalRmdirOnce = true }
        assertFails { newDataSource(fileOperations = ops, userSkillsRoot = skills).recoverInterruptedRestore() }

        newDataSource(fileOperations = ops, userSkillsRoot = skills).recoverInterruptedRestore()

        assertThat(Files.isDirectory(skills)).isTrue()
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
    }

    @Test
    fun `技能所有者标记发布前进程中断由主恢复所有者树清理`() = runBlocking {
        val skills = sourceBase.resolve("agent_skills")
        Files.createDirectories(skills)
        writeUtf8(skills.resolve("old"), "旧")
        val codec = DefaultBackupPackageCodec()
        val snapshot = newDataSource(userSkillsRoot = skills).snapshot(CANONICAL_CONTENT)
        val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        snapshot.wipe()
        val ops = object : RestoreFileOperations by TestRestoreFileOperations() {
            override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) {
                if (root.fileName.toString().contains("skills-preparing")) {
                    throw SimulatedRestoreProcessDeath(RestoreCrashPoint.JOURNAL_PREPARED)
                }
                TestRestoreFileOperations().writeNew(root, relative, bytes)
            }
        }
        try { newDataSource(fileOperations = ops, userSkillsRoot = skills).restore(codec.decode(bytes, null)) }
        catch (_: SimulatedRestoreProcessDeath) { }

        newDataSource(userSkillsRoot = skills).recoverInterruptedRestore()

        assertThat(readUtf8(skills.resolve("old"))).isEqualTo("旧")
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        assertThat(Files.list(sourceBase).use { it.anyMatch { p -> p.fileName.toString().startsWith(".infinity-skills-") } })
            .isFalse()
    }

    private fun writeUtf8(path: Path, value: String) = Files.write(path, value.toByteArray(Charsets.UTF_8))
    private fun readUtf8(path: Path): String = Files.readAllBytes(path).toString(Charsets.UTF_8)


    @Test
    fun `头像照片和用户照片跨目录恢复内容一致且文件名重新生成`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        val source = Files.createDirectory(sourceBase.resolve("avatars"))
        val target = sourceBase.resolve("target-avatars")
        val agentPhoto = Files.write(source.resolve("agent-original.img"), byteArrayOf(1, 2, 3))
        val userPhoto = Files.write(source.resolve("user-original.img"), byteArrayOf(4, 5, 6))
        val agent = db.agentDao().getAll().single()
        db.agentDao().update(agent.copy(avatarPath = agentPhoto.toString()))
        preferences.snapshot = BackupPreferenceSnapshot(listOf(BackupPreferenceEntry("settings", "user_avatar", userPhoto.toString())), emptySet())
        val snapshot = newDataSource(avatarRoot = source).snapshot(CANONICAL_CONTENT)
        assertThat(snapshot.avatars.size).isEqualTo(2)
        assertThat(snapshot.database.toString(Charsets.UTF_8)).doesNotContain(source.toString())
        assertThat(snapshot.preferences.toString(Charsets.UTF_8)).doesNotContain(source.toString())
        val codec = DefaultBackupPackageCodec()
        val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        snapshot.wipe()
        db.clearAllTables()
        newDataSource(avatarRoot = target).restore(codec.decode(bytes, null), "avatar-cross-directory")
        val restoredAgent = Path.of(db.agentDao().getAll().single().avatarPath!!)
        val restoredUser = Path.of(preferences.snapshot.entries.single().value)
        assertThat(restoredAgent.parent).isEqualTo(target)
        assertThat(restoredUser.parent).isEqualTo(target)
        assertThat(restoredAgent.fileName.toString()).isNotEqualTo("agent-original.img")
        assertThat(Files.readAllBytes(restoredAgent)).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(Files.readAllBytes(restoredUser)).isEqualTo(byteArrayOf(4, 5, 6))
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
    }

    @Test
    fun `悬空头像引用被清除而链接超限和不稳定源明确失败`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        db.clearAllTables()
        val root = Files.createDirectory(sourceBase.resolve("avatars"))
        val outside = Files.write(sourceBase.resolve("outside.img"), byteArrayOf(1))
        val link = Files.createSymbolicLink(root.resolve("link.img"), outside)
        val big = root.resolve("big.img")
        java.io.RandomAccessFile(big.toFile(), "rw").use { it.setLength(AvatarBackupFiles.MAX_FILE_BYTES + 1) }
        val unstable = Files.write(root.resolve("unstable.img"), byteArrayOf(1))
        listOf(root.resolve("missing.img"), outside).forEachIndexed { i, path ->
            db.agentDao().insert(AgentEntity("dangling-$i", "照片", avatarPath = path.toString(), createdAt = i.toLong()))
            val snapshot = newDataSource(avatarRoot = root).snapshot(CANONICAL_CONTENT)
            val agent = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
                .getValue("tables").jsonObject.getValue("agents").jsonArray.single().jsonObject
            assertThat(agent.getValue("avatar_path")).isEqualTo(JsonNull)
            db.openHelper.writableDatabase.execSQL("DELETE FROM agents")
        }
        listOf(link, big, unstable).forEachIndexed { i, path ->
            db.agentDao().insert(AgentEntity("avatar-$i", "照片", avatarPath = path.toString(), createdAt = i.toLong()))
            assertFails {
                newDataSource(avatarRoot = root, snapshotReadHook = { current ->
                    if (current == unstable) Files.write(current, byteArrayOf(2))
                }).snapshot(CANONICAL_CONTENT)
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM agents")
        }
    }

    @Test
    fun `核心头像总量超过整包上限时明确失败`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        db.clearAllTables()
        val root = Files.createDirectory(sourceBase.resolve("avatars"))
        repeat(5) { index ->
            val file = root.resolve("$index.img")
            java.io.RandomAccessFile(file.toFile(), "rw").use { it.setLength(AvatarBackupFiles.MAX_FILE_BYTES) }
            db.agentDao().insert(AgentEntity("photo-$index", "照片", avatarPath = file.toString(), createdAt = index.toLong()))
        }
        val cache = Files.createTempDirectory(sourceBase, "avatar-overflow")
        try {
            val error = try { newDataSource(avatarRoot = root).snapshotTo(setOf(BackupLayer.CORE), cache); null }
                catch (error: BackupValidationException) { error }
            assertThat(error?.reason).isEqualTo(BackupFailureReason.CAPACITY_EXCEEDED)
        } finally { BackupStreams.deleteTree(cache) }
    }

    @Test
    fun `头像旧格式恢复清空智能体和用户的源设备路径`() = runBlocking {
        val snapshot = emptyWorkspaceBackup()
        val database = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = database.getValue("tables").jsonObject
        val agents = tables.getValue("agents").jsonArray.map { row ->
            JsonObject(row.jsonObject + ("avatar_path" to JsonPrimitive("/old/device/photo.img")))
        }
        val legacyDatabase = JsonObject(database + ("tables" to JsonObject(tables + ("agents" to JsonArray(agents))))).toString().toByteArray()
        val legacyPreferences = Json.encodeToString(BackupPreferenceSnapshot(listOf(
            BackupPreferenceEntry("settings", "user_avatar", "/old/device/user.img")), emptySet())).toByteArray()
        newDataSource().restore(validated(snapshot, database = legacyDatabase, preferencesBytes = legacyPreferences, secrets = emptyMap()))
        assertThat(db.agentDao().getAll().single().avatarPath).isNull()
        assertThat(preferences.snapshot.entries.map { it.key }).doesNotContain("user_avatar")
    }

    @Test
    fun `头像哈希不符在落盘前拒绝恢复`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        val root = Files.createDirectory(sourceBase.resolve("avatars"))
        val photo = Files.write(root.resolve("photo.img"), byteArrayOf(1))
        val agent = db.agentDao().getAll().single()
        db.agentDao().update(agent.copy(avatarPath = photo.toString()))
        val snapshot = newDataSource(avatarRoot = root).snapshot(CANONICAL_CONTENT)
        val codec = DefaultBackupPackageCodec()
        val validated = codec.decode(codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT)), null)
        validated.avatars.values.single()[0] = 2
        assertFails { newDataSource(avatarRoot = root).restore(validated, "bad-avatar-hash") }
        assertThat(Files.readAllBytes(photo)).isEqualTo(byteArrayOf(1))
        assertThat(db.agentDao().getAll().single().avatarPath).isEqualTo(photo.toString())
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        snapshot.wipe()
    }

    @Test
    fun `头像恢复偏好提交失败立即回滚文件与数据库`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        val root = Files.createDirectory(sourceBase.resolve("avatars"))
        val photo = Files.write(root.resolve("new.img"), byteArrayOf(1))
        val agent = db.agentDao().getAll().single()
        db.agentDao().update(agent.copy(avatarPath = photo.toString()))
        val codec = DefaultBackupPackageCodec()
        val snapshot = newDataSource(avatarRoot = root).snapshot(CANONICAL_CONTENT)
        val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        snapshot.wipe()
        Files.delete(photo)
        val sentinel = Files.write(root.resolve("old.img"), byteArrayOf(9))
        db.agentDao().update(agent.copy(avatarPath = sentinel.toString()))
        preferences.failCommit = true
        assertFails { newDataSource(avatarRoot = root).restore(codec.decode(bytes, null), "avatar-rollback") }
        assertThat(Files.readAllBytes(sentinel)).isEqualTo(byteArrayOf(9))
        assertThat(db.agentDao().getAll().single().avatarPath).isEqualTo(sentinel.toString())
        assertThat(Files.list(root).use { it.count() }).isEqualTo(1)
        assertThat(Files.exists(root.parent.resolve(".infinity-avatars-restore-avatar-rollback"))).isFalse()
    }

    @Test
    fun `头像恢复每个中断点重启后文件引用一致且可幂等收尾`() = runBlocking {
        val root = sourceBase.resolve("avatars")
        val codec = DefaultBackupPackageCodec()
        RestoreCrashPoint.entries.filterNot { it.name.startsWith("SKILLS_") }.forEach { point ->
            emptyWorkspaceBackup().wipe()
            root.toFile().deleteRecursively()
            Files.createDirectories(root)
            val photo = Files.write(root.resolve("new.img"), byteArrayOf(1, 2))
            val agent = db.agentDao().getAll().single()
            db.agentDao().update(agent.copy(avatarPath = photo.toString()))
            val snapshot = newDataSource(avatarRoot = root).snapshot(CANONICAL_CONTENT)
            val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
            snapshot.wipe()
            Files.delete(photo)
            val old = Files.write(root.resolve("old.img"), byteArrayOf(9))
            db.agentDao().update(agent.copy(avatarPath = old.toString()))
            var interrupted = false
            try { newDataSource(point, avatarRoot = root).restore(codec.decode(bytes, null)) }
            catch (_: SimulatedRestoreProcessDeath) { interrupted = true }
            assertThat(interrupted).isTrue()
            newDataSource(avatarRoot = root).recoverInterruptedRestore()
            newDataSource(avatarRoot = root).recoverInterruptedRestore()
            val committed = point in setOf(RestoreCrashPoint.ROOM_COMMITTED, RestoreCrashPoint.JOURNAL_COMMITTED, RestoreCrashPoint.RECEIPT_PERSISTED)
            val restored = Path.of(db.agentDao().getAll().single().avatarPath!!)
            assertThat(Files.readAllBytes(restored)).isEqualTo(if (committed) byteArrayOf(1, 2) else byteArrayOf(9))
            assertThat(Files.exists(old)).isEqualTo(!committed)
            assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
        }
    }

    @Test
    fun `头像提交中断时技能和照片随主事务一起回滚`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        val root = Files.createDirectory(sourceBase.resolve("avatars"))
        val skills = Files.createDirectory(sourceBase.resolve("agent_skills"))
        val photo = Files.write(root.resolve("new.img"), byteArrayOf(1))
        Files.write(skills.resolve("new.txt"), byteArrayOf(2))
        val agent = db.agentDao().getAll().single()
        db.agentDao().update(agent.copy(avatarPath = photo.toString()))
        val codec = DefaultBackupPackageCodec()
        val snapshot = newDataSource(userSkillsRoot = skills, avatarRoot = root).snapshot(CANONICAL_CONTENT)
        val bytes = codec.encode(snapshot, BackupOptions(CANONICAL_CONTENT))
        snapshot.wipe()
        Files.delete(photo)
        Files.delete(skills.resolve("new.txt"))
        val oldPhoto = Files.write(root.resolve("old.img"), byteArrayOf(9))
        Files.write(skills.resolve("old.txt"), byteArrayOf(8))
        db.agentDao().update(agent.copy(avatarPath = oldPhoto.toString()))
        var interrupted = false
        try { newDataSource(RestoreCrashPoint.AVATARS_COMMITTED, userSkillsRoot = skills, avatarRoot = root).restore(codec.decode(bytes, null)) }
        catch (_: SimulatedRestoreProcessDeath) { interrupted = true }
        assertThat(interrupted).isTrue()
        newDataSource(userSkillsRoot = skills, avatarRoot = root).recoverInterruptedRestore()
        assertThat(Files.readAllBytes(oldPhoto)).isEqualTo(byteArrayOf(9))
        assertThat(Files.readAllBytes(skills.resolve("old.txt"))).isEqualTo(byteArrayOf(8))
        assertThat(Files.exists(skills.resolve("new.txt"))).isFalse()
        assertThat(db.agentDao().getAll().single().avatarPath).isEqualTo(oldPhoto.toString())
        assertThat(Files.exists(restoreParent.resolve(FileRestoreJournal.FILE_NAME))).isFalse()
    }

    @Test
    fun `三十MiB技能与多个核心头像完整流式恢复`() = runBlocking {
        emptyWorkspaceBackup().wipe()
        db.clearAllTables()
        val root = Files.createDirectory(sourceBase.resolve("avatars"))
        val skills = Files.createDirectory(sourceBase.resolve("agent_skills"))
        java.io.RandomAccessFile(skills.resolve("data.bin").toFile(), "rw").use { it.setLength(30L * 1024 * 1024) }
        val expected = BackupStreams.digest(BackupBlob.File(skills.resolve("data.bin")))
        repeat(4) { index ->
            val path = root.resolve("$index.img")
            Files.newOutputStream(path).use { it.write(ByteArray(1024 * 1024) { index.toByte() }) }
            db.agentDao().insert(AgentEntity("photo-$index", "照片", avatarPath = path.toString(), createdAt = index.toLong()))
        }
        val cache = Files.createTempDirectory(sourceBase, "large-export")
        try {
            val source = newDataSource(userSkillsRoot = skills, avatarRoot = root)
            val snapshot = source.snapshotTo(setOf(BackupLayer.CORE), cache)
            val file = cache.resolve("large.iwb")
            val codec = DefaultBackupPackageCodec()
            try { Files.newOutputStream(file).use { codec.encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) } }
            finally { snapshot.wipe() }
            Files.write(skills.resolve("data.bin"), byteArrayOf(1))
            Files.newInputStream(file).use { codec.decodeFrom(it, null, cache).use { source.restore(it) } }
            assertThat(BackupStreams.digest(BackupBlob.File(skills.resolve("data.bin")))).isEqualTo(expected)
            assertThat(db.agentDao().getAll().map { it.avatarPath }.filterNotNull()).hasSize(4)
            db.agentDao().getAll().forEach { agent -> assertThat(Files.size(Path.of(agent.avatarPath!!))).isEqualTo(1024L * 1024) }
        } finally { BackupStreams.deleteTree(cache) }
    }

    private fun newDataSource(
        crashPoint: RestoreCrashPoint? = null,
        snapshotReadHook: (Path) -> Unit = {},
        preferenceStore: FakePreferenceStore = preferences,
        secretStore: FakeSecretStore = secrets,
        fileOperations: RestoreFileOperations = TestRestoreFileOperations(),
        configurationRevision: () -> Long = { 0L },
        userSkillsRoot: Path? = null,
        avatarRoot: Path? = null,
        trustedBases: Set<Path> = setOf(sourceBase),
        embeddingSpaceKey: () -> String? = { "fixture-space" },
    ): RoomBackupDataSource = RoomBackupDataSource(
        database = db,
        preferences = preferenceStore,
        secrets = secretStore,
        trustedSourceBases = trustedBases,
        trustedRestoreBase = restoreParent,
        appVersion = "test",
        crashHook = RestoreCrashHook { point ->
            if (point == crashPoint) throw SimulatedRestoreProcessDeath(point)
        },
        snapshotReadHook = snapshotReadHook,
        configurationRevision = configurationRevision,
        journalAuthenticator = TestRestoreJournalAuthenticator,
        restoreFileOperations = fileOperations,
        userSkillsRoot = userSkillsRoot,
        avatarRoot = avatarRoot,
        embeddingSpaceKey = embeddingSpaceKey,
    )

    private suspend fun emptyWorkspaceBackup(): BackupSnapshot {
        db.clearAllTables()
        db.agentDao().insert(AgentEntity("agent-empty", "Empty", createdAt = 1L))
        return newDataSource().snapshot(CANONICAL_CONTENT)
    }

    private fun validated(
        snapshot: BackupSnapshot,
        manifest: BackupManifest? = null,
        database: ByteArray = snapshot.database,
        preferencesBytes: ByteArray = snapshot.preferences,
        files: Map<String, ByteArray> = snapshot.files,
        secrets: Map<SecretId, ByteArray> = snapshot.secrets,
    ): ValidatedBackup {
        val manifestEntries = buildList {
            add(BackupManifestEntry("database.json", snapshot.database.size.toLong(), sha256(snapshot.database)))
            add(BackupManifestEntry("preferences.json", snapshot.preferences.size.toLong(), sha256(snapshot.preferences)))
            snapshot.files.forEach { (id, bytes) ->
                add(BackupManifestEntry("files/$id", bytes.size.toLong(), sha256(bytes)))
            }
            if (snapshot.secrets.isNotEmpty()) {
                add(BackupManifestEntry("secrets.json", 0, "0".repeat(64)))
            }
        }
        return ValidatedBackup(
        manifest = manifest ?: BackupManifest(
            formatVersion = 1,
            databaseSchemaVersion = snapshot.databaseSchemaVersion,
            appVersion = snapshot.appVersion,
            createdAt = snapshot.createdAt,
            entries = manifestEntries,
            encrypted = false,
            containsSecrets = snapshot.secrets.isNotEmpty(),
            embedding = snapshot.embedding,
        ),
        database = database.copyOf(),
        preferences = preferencesBytes.copyOf(),
        files = files.mapValues { it.value.copyOf() },
        secrets = secrets.mapValues { it.value.copyOf() },
    )
    }

    private fun assertValidatedBackupWiped(backup: ValidatedBackup) {
        val arrays = listOf(backup.database, backup.preferences) + backup.files.values + backup.secrets.values
        assertThat(arrays).isNotEmpty()
        assertThat(arrays.all { bytes -> bytes.all { it == 0.toByte() } }).isTrue()
    }

    private fun restoredRoot(): Path {
        val roots = Files.list(restoreParent).use { it.toList() }
        val container = roots.single()
        return Files.list(container).use { children ->
            children.filter { Files.isDirectory(it) }.toList().single()
        }
    }

    @Test
    fun `状态快照备份恢复保留版本用户标记与级联关系`() = runBlocking {
        seedCompleteGraph()
        val board = com.promenar.infinity.data.state.StateBoard("冒险", listOf(
            com.promenar.infinity.data.state.StateField("hp", "生命值", "number", JsonPrimitive(65), updatedAt = 1),
        ), manualKeys = listOf("hp"))
        db.stateBoardDao().insert(com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity(
            sessionId = "session-1", anchorMessageId = "message-1", source = "user",
            snapshot = kotlinx.serialization.json.Json.encodeToString(com.promenar.infinity.data.state.StateBoard.serializer(), board), createdAt = 1,
        ))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        db.clearAllTables()
        newDataSource().restore(validated(snapshot))
        assertThat(com.promenar.infinity.data.repository.StateBoardRepository(db).get("session-1")!!.board).isEqualTo(board)
        db.messageDao().deleteByIdWithContextSummary("message-1")
        assertThat(db.stateBoardDao().versions("session-1")).isEmpty()
    }

    @Test
    fun `v6历史备份恢复补空状态快照表`() = runBlocking {
        seedCompleteGraph()
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val historical = JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(6),
            "tables" to JsonObject(root.getValue("tables").jsonObject - "state_board_versions" - "long_term_memories"))).toString().toByteArray()
        db.clearAllTables()
        newDataSource().restore(validated(snapshot, database = historical))
        assertThat(db.messageDao().getById("message-1")).isNotNull()
        assertThat(db.stateBoardDao().versions("session-1")).isEmpty()
    }

    @Test
    fun `v7备份状态字段缺省或null时拒绝恢复且保留当前数据`() = runBlocking {
        seedCompleteGraph()
        val board = """{"title":"坏面板","fields":[{"key":"bag","label":"背包","type":"list"}]}"""
        db.stateBoardDao().insert(com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity(
            sessionId = "session-1", anchorMessageId = "message-1", source = "user", snapshot = board, createdAt = 1,
        ))
        val snapshot = newDataSource().snapshot(CANONICAL_CONTENT)
        val failure = runCatching { newDataSource().restore(validated(snapshot)) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(BackupValidationException::class.java)
        assertThat(db.messageDao().getById("message-1")).isNotNull()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Test
    fun `恢复v4备份时工作区绑定与用户规则取空值`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val v4 = JsonObject(root + mapOf(
            "schemaVersion" to JsonPrimitive(4),
            "tables" to historicalV4Tables(root.getValue("tables").jsonObject),
        )).toString().toByteArray()

        db.clearAllTables()
        newDataSource().restore(validated(current, database = v4))
        assertThat(rowCount("long_term_memories")).isEqualTo(0)

        val agent = db.agentDao().getById("agent-1")!!
        assertThat(agent.userRules).isEmpty()
        assertThat(agent.defaultWorkspaceId).isNull()
        assertThat(agent.executionMode).isEqualTo("manual")
        assertThat(db.sessionDao().getById("session-1")!!.workspaceId).isNull()
        assertThat(rowCount("workspaces")).isEqualTo(0)
    }

    @Test
    fun `恢复v5备份时助手与会话采样参数回到模型默认且方案表为空`() = runBlocking {
        seedCompleteGraph()
        db.sessionDao().getById("session-1")!!.let {
            db.sessionDao().update(it.copy(inferenceParams = """{"temperature":1.0,"topK":100,"streamTimeout":90}"""))
        }
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val v5 = JsonObject(root + mapOf(
            "schemaVersion" to JsonPrimitive(5),
            "tables" to historicalV5Tables(root.getValue("tables").jsonObject),
        )).toString().toByteArray()

        db.clearAllTables()
        newDataSource().restore(validated(current, database = v5))
        assertThat(rowCount("long_term_memories")).isEqualTo(0)

        val agent = db.agentDao().getById("agent-1")!!
        assertThat(listOf(agent.temperature, agent.topK?.toDouble(), agent.reasoningEffort)).containsExactly(null, null, null)
        assertThat(agent.userRules).isEqualTo("先读 AGENTS.md😀")
        assertThat(db.sessionDao().getById("session-1")!!.inferenceParams).isEqualTo("""{"streamTimeout":90}""")
        assertThat(rowCount("parameter_schemes")).isEqualTo(0)
    }

    @Test
    fun `v4备份携带工作区表时拒绝且保留当前数据`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val v4 = JsonObject(root + mapOf(
            "schemaVersion" to JsonPrimitive(4),
            "tables" to JsonObject(historicalV4Tables(tables) + ("workspaces" to tables.getValue("workspaces"))),
        )).toString().toByteArray()

        assertFails { newDataSource().restore(validated(current, database = v4))
        assertThat(rowCount("long_term_memories")).isEqualTo(0) }
        assertThat(db.agentDao().getById("agent-1")!!.userRules).isEqualTo("先读 AGENTS.md😀")
    }

    @Test
    fun `v5备份的工作区引用与位置不合法时拒绝`() = runBlocking {
        seedCompleteGraph()
        val current = newDataSource().snapshot(CANONICAL_CONTENT)
        val root = Json.parseToJsonElement(current.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        fun withTable(name: String, transform: (JsonObject) -> JsonObject): ByteArray = JsonObject(root + (
            "tables" to JsonObject(tables + (name to JsonArray(tables.getValue(name).jsonArray.map { transform(it.jsonObject) })))
        )).toString().toByteArray()
        val workspaceRows = tables.getValue("workspaces").jsonArray
        val duplicate = JsonObject(root + ("tables" to JsonObject(tables + ("workspaces" to JsonArray(
            workspaceRows + JsonObject(workspaceRows.single().jsonObject + ("id" to JsonPrimitive("ws-2")))
        ))))).toString().toByteArray()
        listOf(
            withTable("sessions") { JsonObject(it + ("workspace_id" to JsonPrimitive("missing"))) },
            withTable("agents") { JsonObject(it + ("default_workspace_id" to JsonPrimitive("missing"))) },
            withTable("workspaces") { JsonObject(it + ("location" to JsonPrimitive("../escape"))) },
            withTable("workspaces") { JsonObject(it + ("kind" to JsonPrimitive("saf"))) },
            withTable("workspaces") { JsonObject(it + mapOf(
                "kind" to JsonPrimitive("shared"), "location" to JsonPrimitive("/storage/emulated/0/a/../b"),
            )) },
            duplicate,
        ).forEach { malformed ->
            assertFails { newDataSource().restore(validated(current, database = malformed)) }
        }
        assertThat(db.sessionDao().getById("session-1")!!.workspaceId).isEqualTo("ws-1")
    }

    /** 真实 v1–v4 备份没有 v5 的工作区表和工作区绑定、用户规则列。 */
    private fun historicalV4Tables(tables: JsonObject): JsonObject {
        val v5 = historicalV5Tables(tables)
        return JsonObject(
            (v5 - "workspaces") + mapOf(
                "agents" to JsonArray(v5.getValue("agents").jsonArray.map {
                    JsonObject(it.jsonObject - "default_workspace_id" - "user_rules")
                }),
                "sessions" to JsonArray(v5.getValue("sessions").jsonArray.map { JsonObject(it.jsonObject - "workspace_id") }),
            ),
        )
    }

    /** 真实 v5 备份没有参数方案表和助手的扩展采样、推理强度列。 */
    private fun historicalV5Tables(tables: JsonObject): JsonObject = JsonObject(
        (tables - "parameter_schemes" - "state_board_versions" - "long_term_memories") + (
            "agents" to JsonArray(tables.getValue("agents").jsonArray.map {
                JsonObject(it.jsonObject - "top_k" - "frequency_penalty" - "presence_penalty" - "repetition_penalty" - "reasoning_effort")
            })
        ),
    )

    private suspend fun seedCompleteGraph(): SeededGraph {
        val now = 100L
        val bytes = "complete-graph".toByteArray()
        val filePath = sourceRoot.resolve("docs/a.txt")
        Files.createDirectories(filePath.parent)
        Files.write(filePath, bytes)
        val workspace = WorkspaceEntity("ws-1", WorkspaceEntity.KIND_PROJECT, "演示项目", "demo", now)
        val agent = AgentEntity(
            "agent-1",
            "Agent",
            createdAt = now,
            executionMode = "manual",
            skillIds = "[\"read_file\",\"calculator\"]",
            mcpServerIds = "[\"mcp-1\"]",
            defaultWorkspaceId = workspace.id,
            userRules = "先读 AGENTS.md😀",
            temperature = 0.3,
            topK = 40,
            reasoningEffort = "low",
        )
        val session = SessionEntity(
            id = "session-1", agentId = agent.id, workspacePath = sourceRoot.toString(),
            workspaceRootUuid = "root-1", workspaceId = workspace.id, createdAt = now, updatedAt = now,
        )
        val message = MessageEntity(
            "message-1", session.id, "user", "hello",
            vectorizationStatus = "completed", createdAt = now,
        )
        val root = FileEntry(
            uuid = "root-1", workspaceRootUuid = "root-1", parentUuid = null, name = "workspace",
            hash = "", isDirectory = true, physicalRootPath = sourceRoot.toString(),
            materializedPath = "/", createdAt = now, updatedAt = now,
        )
        val file = FileEntry(
            uuid = "file-1", workspaceRootUuid = "root-1", parentUuid = root.uuid, name = "a.txt",
            hash = sha256(bytes), sizeBytes = bytes.size.toLong(), physicalRootPath = sourceRoot.toString(),
            materializedPath = "/docs/a.txt", vectorizedAt = now, kgExtractedAt = now,
            createdAt = now, updatedAt = now,
        )
        db.workspaceDao().insert(workspace)
        db.agentDao().insert(agent)
        db.sessionDao().insert(session)
        db.messageDao().insert(message)
        db.attachmentDao().insert(AttachmentEntity("attachment-1", message.id, "text", "content://a"))
        db.artifactDao().insert(
            ArtifactEntity(
                "artifact-1", "text", "Title", "Body", sessionId = session.id, messageId = message.id,
                workspacePath = filePath.toString(), createdAt = now, updatedAt = now,
            )
        )
        db.contextSummaryDao().insert(ContextSummaryEntity("summary-1", session.id, message.id, message.id, "summary", now))
        db.tagDao().insert(TagEntity("tag-1", "Tag", createdAt = now))
        db.fileEntryDao().insert(root)
        db.fileEntryDao().insert(file)
        db.documentTagDao().insert(DocumentTagEntity(file.uuid, "tag-1", now))
        db.taskNodeDao().upsert(TaskNodeEntity("task-1", session.id, title = "Task", createdAt = now, updatedAt = now))
        db.skillDao().insertCustomSkill(CustomSkillEntity("skill-1", "Skill", "desc", "{}", "return 1", createdAt = now))
        db.skillDao().insertMcpServer(McpServerEntity("mcp-1", "MCP", "https://example.invalid", createdAt = now))
        db.workspaceSeqDao().getNextSeqForDate("20260712")
        db.parameterSchemeDao().upsert(ParameterSchemeEntity("scheme-1", "provider::model", "写代码", "{\"temperature\":0.2}", now, now))
        return SeededGraph(filePath)
    }

    private suspend fun seedSessionWorkspaceRoots(): Set<Path> {
        val sessionBase = Files.createDirectory(sourceBase.resolve("session_workspaces"))
        sourceRoot = Files.move(sourceRoot, sessionBase.resolve("a".repeat(64)))
        seedCompleteGraph()
        val identity = PersistentFileIdentity.read(sourceRoot)
        db.openHelper.writableDatabase.execSQL("UPDATE workspace_files SET hash = ? WHERE uuid = 'root-1'", arrayOf<Any?>(identity))
        Files.createDirectory(sourceRoot.resolve(".infinity"))
        Files.writeString(sourceRoot.resolve(".infinity/.gitignore"), "*\n")
        Files.createDirectory(sessionBase.resolve(".infinity"))
        Files.writeString(sessionBase.resolve(".infinity/.gitignore"), "*\n")

        // 知识库系统会话使用同一父目录中的空根，身份同样只保存在扩展属性中。
        val knowledgeRoot = Files.createDirectory(sessionBase.resolve("b".repeat(64)))
        db.sessionDao().insert(SessionEntity(
            "__infinity_rag_workspace__", BackupLayerPolicy.KNOWLEDGE_AGENT,
            workspacePath = knowledgeRoot.toString(), workspaceRootUuid = "knowledge-root", createdAt = 100, updatedAt = 100,
        ))
        db.fileEntryDao().insert(FileEntry(
            uuid = "knowledge-root", workspaceRootUuid = "knowledge-root", parentUuid = null, name = "知识库",
            hash = PersistentFileIdentity.read(knowledgeRoot), isDirectory = true,
            physicalRootPath = knowledgeRoot.toString(), materializedPath = "/", createdAt = 100, updatedAt = 100,
        ))
        for (root in listOf(sourceRoot, knowledgeRoot)) {
            assertThat(PersistentFileIdentity.isValid(PersistentFileIdentity.read(root))).isTrue()
            WorkspaceInternalPaths.rootIdentityNames.forEach { assertThat(Files.exists(root.resolve(it))).isFalse() }
        }
        return linkedSetOf(sourceBase,
            Files.createDirectory(sourceBase.resolve("legacy-workspaces")),
            Files.createDirectory(sourceBase.resolve("legacy-rag")), sessionBase)
    }

    private suspend fun writeLayeredWorkspaceBackup(source: RoomBackupDataSource, cache: Path): Path {
        val snapshot = source.snapshotTo(setOf(BackupLayer.CORE, BackupLayer.KNOWLEDGE, BackupLayer.WORKSPACE_FILES), cache)
        val file = cache.resolve("session-roots.iwb")
        try {
            assertThat(snapshot.streamEntries.any { it.path == "files/file-1" }).isTrue()
            Files.newOutputStream(file).use { DefaultBackupPackageCodec().encodeTo(snapshot, BackupOptions(CANONICAL_CONTENT), it) }
        } finally { snapshot.wipe() }
        return file
    }

    private suspend fun assertRestoredSessionWorkspaceFiles() {
        val session = db.sessionDao().getById("session-1")!!
        val root = Path.of(session.workspacePath!!)
        assertThat(readUtf8(root.resolve("docs/a.txt"))).isEqualTo("complete-graph")
        assertThat(PersistentFileIdentity.isValid(db.fileEntryDao().getByUuid("root-1", "root-1")!!.hash)).isTrue()
        assertThat(db.sessionDao().getById("__infinity_rag_workspace__")!!.agentId).isEqualTo(BackupLayerPolicy.KNOWLEDGE_AGENT)
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertThat(it.moveToFirst()).isFalse() }
    }

    private suspend fun insertPreparedWorkspaceMutation(operationId: String) {
        db.workspaceMutationDao().insert(
            WorkspaceMutationEntity(
                operationId = operationId,
                workspaceRootUuid = "root-1",
                operationType = WorkspaceMutationType.CREATE,
                payload = "{}",
                payloadDigest = "digest",
                createdAt = 100L,
                updatedAt = 100L,
            ),
        )
    }

    private fun seedDerivedRows() {
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            """INSERT INTO mcp_tool_snapshots(
               server_id,remote_tool_name,description,input_schema_json,synced_at
               ) VALUES(?,?,?,?,?)""",
            arrayOf<Any?>("mcp-1", "search", "Search", "{\"type\":\"object\"}", 100L),
        )
        sqlite.execSQL(
            "INSERT INTO vectors(id, session_id, content, embedding, created_at, stale, version) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>("vector-1", "session-1", "derived", byteArrayOf(1, 2), 100L, 0, 1),
        )
        sqlite.execSQL("INSERT INTO vectors_fts(rowid, content) VALUES(?,?)", arrayOf<Any?>(99, "derived fts"))
        sqlite.execSQL("INSERT OR IGNORE INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('shared','shared','通用知识',NULL,100)")
        sqlite.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('file-1','shared')")
        sqlite.execSQL(
            "INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("node-1", "Node 1", "concept", "node 1", 100L, "shared"),
        )
        sqlite.execSQL(
            "INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("node-2", "Node 2", "concept", "node 2", 100L, "shared"),
        )
        sqlite.execSQL(
            "INSERT INTO kg_edges(id,source_id,target_id,relation,weight,normalized_relation,created_at,collection_id) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("edge-1", "node-1", "node-2", "rel", 1.0, "rel", 100L, "shared"),
        )
        sqlite.execSQL(
            "INSERT INTO kg_jit_cache(cache_key,query_hash,chunk_ids_hash,result_json,created_at,expires_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("cache-1", "q", "c", "{}", 100L, 200L),
        )
        sqlite.execSQL("INSERT INTO kg_evidence(id,node_id,source_type,content_hash,task_id,created_at,workspace_document) VALUES('p-node','node-1','legacy','','fixture',100,0)")
        sqlite.execSQL("INSERT INTO kg_evidence(id,edge_id,source_type,content_hash,task_id,created_at,workspace_document) VALUES('p-edge','edge-1','legacy','','fixture',100,0)")
        sqlite.execSQL("INSERT INTO kg_jit_cache_sources(cache_key,doc_id,content_hash) VALUES('cache-1','file-1','fixture-hash')")
        sqlite.execSQL(
            """INSERT INTO vectorization_tasks(
               id,type,status,last_chunk_index,progress,skip_vectorization,content_truncated,created_at,updated_at
               ) VALUES(?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>("vt-1", "doc", "pending", 0, 0.0, 0, 0, 100L, 100L),
        )
        sqlite.execSQL(
            "INSERT INTO audit_logs(id,action,resource_type,status,created_at) VALUES(?,?,?,?,?)",
            arrayOf<Any?>("audit-1", "read", "file", "ok", 100L),
        )
        sqlite.execSQL(
            "INSERT INTO tool_execution_ledger(session_id,assistant_message_id,tool_call_id,tool_name,requires_approval,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>("session-1", "message-1", "call-1", "tool", 0, "SUCCEEDED", 100L, 100L),
        )
        sqlite.execSQL(
            "INSERT INTO file_versions(id,file_uuid,workspace_root_uuid,hash,content_path,created_at) VALUES(?,?,?,?,?,?)",
            arrayOf<Any?>("version-1", "file-1", "root-1", "hash", "/version", 100L),
        )
        sqlite.execSQL(
            """INSERT INTO workspace_mutations(
               operation_id,workspace_root_uuid,operation_type,payload_version,payload,
               payload_digest,state,created_at,updated_at
               ) VALUES(?,?,?,?,?,?,?,?,?)""",
            arrayOf<Any?>(
                "mutation-1",
                "root-1",
                "CREATE",
                1,
                """{"sourceRelativePath":"docs/a.md"}""",
                "digest-1",
                "DB_COMMITTED",
                100L,
                100L,
            ),
        )
    }

    private fun mutateRows(
        snapshot: BackupSnapshot,
        table: String,
        transform: (JsonArray) -> JsonArray,
    ): ValidatedBackup {
        val root = Json.parseToJsonElement(snapshot.database.toString(Charsets.UTF_8)).jsonObject
        val tables = root.getValue("tables").jsonObject
        val changedTables = JsonObject(tables + (table to transform(tables.getValue(table).jsonArray)))
        val changedRoot = JsonObject(root + ("tables" to changedTables))
        return validated(snapshot, database = changedRoot.toString().toByteArray())
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            if (error is T) return error
            throw error
        }
        throw AssertionError("预期抛出 ${T::class.java.simpleName}")
    }

    private suspend fun assertFails(block: suspend () -> Unit) {
        var failed = false
        try {
            block()
        } catch (_: Exception) {
            failed = true
        }
        assertThat(failed).isTrue()
    }

    private fun rowCount(table: String): Long = db.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM `$table`").use { cursor ->
            cursor.moveToFirst()
            cursor.getLong(0)
        }

    private data class SeededGraph(val filePath: Path)

    private companion object {
        val CANONICAL_CONTENT = setOf(
            BackupContent.DATABASE,
            BackupContent.PREFERENCES,
            BackupContent.FILES,
            BackupContent.SECRETS,
        )
    }
}

private class FakePreferenceStore private constructor(
    private val state: State,
) : TransactionalBackupPreferenceStore {
    constructor(snapshot: BackupPreferenceSnapshot) : this(State(snapshot))

    var snapshot: BackupPreferenceSnapshot
        get() = state.snapshot
        set(value) { state.snapshot = value }
    var failCommit: Boolean
        get() = state.failCommit
        set(value) { state.failCommit = value }
    var failRollback: Boolean
        get() = state.failRollback
        set(value) { state.failRollback = value }
    var failPreflight: Boolean
        get() = state.failPreflight
        set(value) { state.failPreflight = value }
    var prepareFailureBeforeRecord: Throwable?
        get() = state.prepareFailureBeforeRecord
        set(value) { state.prepareFailureBeforeRecord = value }
    var prepareCalls: Int = 0
    var snapshotCalls: Int = 0
    private val prepared get() = state.prepared

    fun reopen(): FakePreferenceStore = FakePreferenceStore(
        state.copy(prepared = state.prepared.toMutableMap())
    )

    override suspend fun snapshot(maxTotalBytes: Long): BackupPreferenceSnapshot {
        snapshotCalls++
        val encodedSize = kotlinx.serialization.json.Json.encodeToString(
            BackupPreferenceSnapshot.serializer(), snapshot
        ).toByteArray().size.toLong()
        if (encodedSize > maxTotalBytes) throw BackupValidationException("偏好快照超过内存预算")
        return snapshot
    }

    override suspend fun preflightRestore(
        txId: String,
        before: BackupPreferenceSnapshot,
        after: BackupPreferenceSnapshot,
    ) {
        if (failPreflight) throw BackupValidationException("injected preference preflight failure")
    }

    override suspend fun prepare(txId: String, before: BackupPreferenceSnapshot, after: BackupPreferenceSnapshot) {
        prepareCalls++
        prepareFailureBeforeRecord?.let { throw it }
        prepared[txId] = before to after
    }

    override suspend fun commitPrepared(txId: String) {
        if (failCommit) throw IllegalStateException("injected preference failure")
        prepared[txId]?.let { snapshot = it.second }
    }

    override suspend fun rollbackPrepared(txId: String) {
        if (failRollback) throw IllegalStateException("injected preference rollback failure")
        prepared[txId]?.let { snapshot = it.first }
    }

    override suspend fun finalizePrepared(txId: String) {
        prepared.remove(txId)
    }

    private data class State(
        var snapshot: BackupPreferenceSnapshot,
        var failCommit: Boolean = false,
        var failRollback: Boolean = false,
        var failPreflight: Boolean = false,
        var prepareFailureBeforeRecord: Throwable? = null,
        val prepared: MutableMap<String, Pair<BackupPreferenceSnapshot, BackupPreferenceSnapshot>> = mutableMapOf(),
    )
}

private class FakeSecretStore private constructor(private val state: State) : TransactionalBackupSecretStore {
    constructor() : this(State())
    private val values get() = state.values
    private val prepared get() = state.prepared
    var failWriteAt: Int?
        get() = state.failWriteAt
        set(value) { state.failWriteAt = value }
    var failRemove: Boolean
        get() = state.failRemove
        set(value) { state.failRemove = value }
    var failRollback: Boolean
        get() = state.failRollback
        set(value) { state.failRollback = value }
    val receivedPlaintextRefs get() = state.receivedPlaintextRefs

    fun reopen(): FakeSecretStore = FakeSecretStore(
        state.copy(
            values = state.values.mapValuesTo(linkedMapOf()) { it.value.copyOf() },
            prepared = state.prepared.mapValuesTo(linkedMapOf()) { (_, pair) ->
                pair.first.mapValues { it.value.copyOf() } to pair.second.mapValues { it.value.copyOf() }
            },
            receivedPlaintextRefs = state.receivedPlaintextRefs.toMutableList(),
        )
    )

    fun put(id: SecretId, value: ByteArray) {
        values[id] = value.copyOf()
    }

    fun get(id: SecretId): ByteArray? = values[id]?.copyOf()

    var failSnapshot = false

    override suspend fun snapshot(ids: Set<SecretId>, maxTotalBytes: Long): Map<SecretId, ByteArray> {
        if (failSnapshot) throw BackupValidationException("合成测试：密钥不可用")
        val result = values.filterKeys { it in ids }.mapValues { it.value.copyOf() }
        if (result.values.sumOf { it.size.toLong() } > maxTotalBytes) {
            result.values.forEach { it.fill(0) }
            throw BackupValidationException("密钥快照超过内存预算")
        }
        return result
    }

    override suspend fun prepare(
        txId: String,
        before: Map<SecretId, ByteArray>,
        after: Map<SecretId, ByteArray>,
    ) {
        receivedPlaintextRefs += before.values.toList()
        receivedPlaintextRefs += after.values.toList()
        prepared[txId] = before.mapValues { it.value.copyOf() } to after.mapValues { it.value.copyOf() }
    }

    override suspend fun commitPrepared(txId: String) {
        val (before, after) = prepared[txId] ?: return
        val eligible = before.keys + after.keys
        eligible.forEach { values.remove(it)?.fill(0) }
        after.entries.forEachIndexed { index, (id, value) ->
            if (failWriteAt == index) throw IllegalStateException("injected secret write failure")
            values[id] = value.copyOf()
        }
        if (failRemove) throw IllegalStateException("injected secret remove failure")
    }

    override suspend fun rollbackPrepared(txId: String) {
        if (failRollback) throw IllegalStateException("injected secret rollback failure")
        val (before, after) = prepared[txId] ?: return
        (before.keys + after.keys).forEach { values.remove(it)?.fill(0) }
        before.forEach { (id, value) -> values[id] = value.copyOf() }
    }

    override suspend fun finalizePrepared(txId: String) {
        prepared.remove(txId)?.let { (before, after) ->
            before.values.forEach { it.fill(0) }
            after.values.forEach { it.fill(0) }
        }
    }

    private data class State(
        val values: LinkedHashMap<SecretId, ByteArray> = linkedMapOf(),
        val prepared: LinkedHashMap<String, Pair<Map<SecretId, ByteArray>, Map<SecretId, ByteArray>>> = linkedMapOf(),
        var failWriteAt: Int? = null,
        var failRemove: Boolean = false,
        var failRollback: Boolean = false,
        val receivedPlaintextRefs: MutableList<List<ByteArray>> = mutableListOf(),
    )
}

internal class TestRestoreFileOperations : RestoreFileOperations {
    var failDeleteAt: Int? = null
    var failFinalRmdirOnce: Boolean = false
    var beforeDelete: ((Path) -> Unit)? = null
    private var deleteCount = 0

    fun clearDeleteFailure() {
        failDeleteAt = null
        deleteCount = 0
    }

    override fun createTransactionRoot(parent: Path, name: String) {
        Files.createDirectory(parent.resolve(name))
    }

    override fun createDirectory(root: Path, relative: List<String>) {
        Files.createDirectory(relative.fold(root) { current, segment -> current.resolve(segment) })
    }

    override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) {
        val target = relative.fold(root) { current, segment -> current.resolve(segment) }
        Files.createDirectories(target.parent)
        java.nio.channels.FileChannel.open(
            target,
            java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).use { channel ->
            channel.write(java.nio.ByteBuffer.wrap(bytes))
            channel.force(true)
        }
    }

    override fun writeStream(root: Path, relative: List<String>, blob: BackupBlob) {
        val target = relative.fold(root) { current, segment -> current.resolve(segment) }
        Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE).use { output ->
            blob.open().use { input -> BackupStreams.copy(input, output, blob.size) }
        }
    }

    override fun initializeWorkspaceRootIdentity(root: Path, relative: List<String>, nonce: String): String {
        val target = relative.fold(root) { current, segment -> current.resolve(segment) }
        val key = Files.readAttributes(
            target,
            java.nio.file.attribute.BasicFileAttributes::class.java,
            java.nio.file.LinkOption.NOFOLLOW_LINKS,
        ).fileKey() ?: error("测试 workspace root 缺少 fileKey")
        writeNew(root, relative + ".infinity_root_identity", PersistentFileIdentity.marker(nonce, key).toByteArray())
        return PersistentFileIdentity.encode(nonce, key)
    }

    override fun deleteTree(
        parent: Path,
        childName: String,
        expectedFileKey: String,
        marker: Pair<String, String>?,
        expectedInventory: Set<RestoreTreeEntry>?,
    ) {
        val root = parent.resolve(childName)
        if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
        if (Files.isSymbolicLink(root)) throw BackupValidationException("测试恢复目录为符号链接")
        beforeDelete?.also { beforeDelete = null }?.invoke(root)
        val actualKey = PersistentFileIdentity.read(root)
        if (actualKey != expectedFileKey) throw BackupValidationException("测试恢复目录 fileKey 已变化")
        expectedInventory?.let { expected ->
            if (inventory(parent, childName) != expected) {
                throw BackupValidationException("测试待删除目录 descriptor inventory 已变化")
            }
        }
        marker?.let { (name, value) ->
            if (Files.readAllBytes(root.resolve(name)).toString(Charsets.UTF_8) != value) {
                throw BackupValidationException("测试恢复目录 owner marker 无效")
            }
        }
        val markerName = marker?.first
        Files.walk(root).use { stream ->
            val paths = stream.sorted(Comparator.reverseOrder()).toList()
            val ordered = paths.filter { it != root && it.fileName?.toString() != markerName } +
                paths.filter { it.fileName?.toString() == markerName } + listOf(root)
            ordered.forEach { path ->
                if (failDeleteAt == deleteCount++) throw java.io.IOException("injected deep delete failure")
                if (path == root && failFinalRmdirOnce) {
                    failFinalRmdirOnce = false
                    throw java.io.IOException("injected final rmdir failure")
                }
                Files.deleteIfExists(path)
            }
        }
    }

    override fun moveTree(parent: Path, sourceName: String, targetName: String) {
        Files.move(
            parent.resolve(sourceName), parent.resolve(targetName),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    override fun moveTreeBetween(sourceParent: Path, sourceName: String, targetParent: Path, targetName: String) {
        Files.move(sourceParent.resolve(sourceName), targetParent.resolve(targetName), java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    override fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry> {
        val root = parent.resolve(childName)
        return Files.walk(root).use { stream ->
            stream.filter { it != root }.map { path ->
                if (Files.isSymbolicLink(path)) throw BackupValidationException("测试 inventory 包含符号链接")
                RestoreTreeEntry(
                    root.relativize(path).joinToString("/") { it.toString() },
                    Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS),
                )
            }.toList().toSet()
        }
    }

    override fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>) {
        if (inventory(root.parent, root.fileName.toString()) != expected) {
            throw BackupValidationException("测试 staging inventory 不一致")
        }
        Files.walk(root).use { stream ->
            stream.filter { Files.isDirectory(it, java.nio.file.LinkOption.NOFOLLOW_LINKS) }
                .forEach(FileRestoreJournal::syncDirectory)
        }
    }
}

private class SourceMutatingRestoreFileOperations(
    private val sourceRoot: Path,
) : RestoreFileOperations {
    private val delegate = TestRestoreFileOperations()
    private var verificationCount = 0

    override fun createTransactionRoot(parent: Path, name: String) = delegate.createTransactionRoot(parent, name)
    override fun createDirectory(root: Path, relative: List<String>) = delegate.createDirectory(root, relative)
    override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) =
        delegate.writeNew(root, relative, bytes)
    override fun initializeWorkspaceRootIdentity(root: Path, relative: List<String>, nonce: String): String =
        delegate.initializeWorkspaceRootIdentity(root, relative, nonce)
    override fun moveTree(parent: Path, sourceName: String, targetName: String) =
        delegate.moveTree(parent, sourceName, targetName)
    override fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry> =
        delegate.inventory(parent, childName)
    override fun deleteTree(
        parent: Path,
        childName: String,
        expectedFileKey: String,
        marker: Pair<String, String>?,
        expectedInventory: Set<RestoreTreeEntry>?,
    ) = delegate.deleteTree(parent, childName, expectedFileKey, marker, expectedInventory)

    override fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>) {
        delegate.verifyAndSync(root, expected)
        verificationCount += 1
        if (verificationCount == 2) {
            Files.write(sourceRoot.resolve("raced.txt"), byteArrayOf(1))
        }
    }
}

private enum class SwapMode { DIRECTORY, FILE }

private class SwappingRestoreFileOperations(
    private val mode: SwapMode,
    private val outside: Path,
) : RestoreFileOperations {
    private val delegate = TestRestoreFileOperations()
    private var swapped = false

    override fun createTransactionRoot(parent: Path, name: String) = delegate.createTransactionRoot(parent, name)
    override fun createDirectory(root: Path, relative: List<String>) = delegate.createDirectory(root, relative)
    override fun writeNew(root: Path, relative: List<String>, bytes: ByteArray) = delegate.writeNew(root, relative, bytes)
    override fun initializeWorkspaceRootIdentity(root: Path, relative: List<String>, nonce: String): String =
        delegate.initializeWorkspaceRootIdentity(root, relative, nonce)
    override fun moveTree(parent: Path, sourceName: String, targetName: String) =
        delegate.moveTree(parent, sourceName, targetName)
    override fun inventory(parent: Path, childName: String): Set<RestoreTreeEntry> = delegate.inventory(parent, childName)
    override fun deleteTree(
        parent: Path, childName: String, expectedFileKey: String, marker: Pair<String, String>?,
        expectedInventory: Set<RestoreTreeEntry>?,
    ) = delegate.deleteTree(parent, childName, expectedFileKey, marker, expectedInventory)

    override fun verifyAndSync(root: Path, expected: Set<RestoreTreeEntry>) {
        if (!swapped) {
            swapped = true
            val suffix = if (mode == SwapMode.DIRECTORY) "/empty" else "/docs/a.txt"
            val entry = expected.single { it.path.endsWith(suffix) }
            val target = entry.path.split('/').fold(root) { current, segment -> current.resolve(segment) }
            if (mode == SwapMode.DIRECTORY) Files.delete(target) else Files.delete(target)
            Files.createSymbolicLink(target, outside)
        }
        delegate.verifyAndSync(root, expected)
    }
}
