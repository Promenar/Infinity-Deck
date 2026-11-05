package com.promenar.infinity.data.session

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationPayload
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationPayloadCodec
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationType
import com.promenar.infinity.infra.util.Sha256Utils
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.file.Paths
import java.nio.file.Files

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class RoomSessionDeletionTransactionTest {
    private lateinit var database: InfinityDatabase

    @Test
    fun `工作区删除事务保留分支消息并解除父引用`() = runBlocking {
        seedAllRelations()
        val child = com.promenar.infinity.data.local.db.entity.SessionEntity("child", "agent", createdAt = 1, updatedAt = 2,
            parentSessionId = SESSION, branchPlanJson = "{}", worktreeDirectory = "/project/feature", worktreeBranch = "feature")
        database.sessionDao().insert(child)
        database.messageDao().insert(com.promenar.infinity.data.local.db.entity.MessageEntity("child-message", "child", "user", "保留分支消息", createdAt = 2))
        val raw = WorkspaceMutationPayloadCodec.encode(WorkspaceMutationPayload(
            sourceRelativePath = "root", targetRelativePath = ".infinity_session_deletions/branch-delete",
            databaseTargetUuid = SESSION, expectedSha256 = "identity"))
        database.workspaceMutationDao().insert(WorkspaceMutationEntity(operationId = "branch-delete", workspaceRootUuid = ROOT,
            operationType = WorkspaceMutationType.DELETE, payload = raw, payloadDigest = Sha256Utils.hash(raw), createdAt = 1, updatedAt = 1))

        RoomSessionDeletionTransaction(database).delete(target(), "branch-delete")

        assertThat(database.sessionDao().getById("child")).isEqualTo(child.copy(parentSessionId = null, parentSessionDeleted = true))
        assertThat(database.messageDao().getById("child-message")!!.content).isEqualTo("保留分支消息")
        assertThat(database.sessionDao().getById(SESSION)).isNull()
    }

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, InfinityDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `事务显式删除会话全部关联数据并保留已提交journal`() = runBlocking {
        seedAllRelations()
        val payload = WorkspaceMutationPayload(
            sourceRelativePath = "root",
            targetRelativePath = ".infinity_session_deletions/op",
            databaseTargetUuid = SESSION,
            expectedSha256 = "identity",
        )
        val raw = WorkspaceMutationPayloadCodec.encode(payload)
        database.workspaceMutationDao().insert(
            WorkspaceMutationEntity(
                operationId = "op",
                workspaceRootUuid = ROOT,
                operationType = WorkspaceMutationType.DELETE,
                payload = raw,
                payloadDigest = Sha256Utils.hash(raw),
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        val kept = com.promenar.infinity.data.local.db.entity.WorkspaceEntity("kept", "project", "保留文件", "session-20261024-0809", 1)
        RoomSessionDeletionTransaction(database).deleteKeepingFiles(target(), "op") {
            database.workspaceDao().insert(kept)
        }
        assertThat(database.workspaceDao().getById("kept")).isEqualTo(kept)

        listOf(
            "sessions", "messages", "attachments", "vectors", "vectors_fts",
            "context_summaries", "document_tags", "kg_nodes", "kg_edges", "kg_evidence", "kg_collection_members", "kg_collections", "kg_jit_cache_sources", "kg_jit_cache",
            "vectorization_tasks", "audit_logs", "artifacts", "workspace_files", "task_nodes",
            "tool_execution_ledger", "file_versions",
        ).forEach { table ->
            assertThat(count(table)).isEqualTo(0)
        }
        assertThat(database.workspaceMutationDao().get("op")?.state?.name)
            .isEqualTo("DB_COMMITTED")
    }

    @Test
    fun `无工作区会话只清理会话关联数据并保留独立工作区记录`() = runBlocking {
        seedAllRelations()
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sessions SET workspace_path = NULL, workspace_root_uuid = NULL WHERE id = '$SESSION'",
        )

        assertThat(RoomSessionDeletionTransaction(database).deleteWithoutWorkspace(SESSION)).isTrue()

        listOf(
            "sessions", "messages", "attachments", "vectors", "context_summaries",
            "kg_nodes", "kg_edges", "kg_evidence", "kg_collection_members", "kg_collections", "kg_jit_cache_sources", "vectorization_tasks", "audit_logs", "artifacts",
            "task_nodes", "tool_execution_ledger", "file_versions",
        ).forEach { table -> assertThat(count(table)).isEqualTo(0) }
        assertThat(count("workspace_files")).isEqualTo(2)
        assertThat(count("document_tags")).isEqualTo(1)
    }

    @Test
    fun `不存在或身份不匹配的journal使整个删除事务回滚`() = runBlocking {
        seedAllRelations()
        val failure = runCatching {
            RoomSessionDeletionTransaction(database).delete(target(), "missing-op")
        }.exceptionOrNull()

        assertThat(failure).isNotNull()
        assertThat(count("sessions")).isEqualTo(1)
        assertThat(count("workspace_files")).isEqualTo(2)
        assertThat(count("tool_execution_ledger")).isEqualTo(1)
    }

    @Test
    fun `保留登记与删除数据库事务一起回滚`() = runBlocking {
        seedAllRelations()
        val kept = com.promenar.infinity.data.local.db.entity.WorkspaceEntity("kept", "project", "保留文件", "session-20261024-0809", 1)
        val failure = runCatching {
            RoomSessionDeletionTransaction(database).deleteKeepingFiles(target(), "missing-op") {
                database.workspaceDao().insert(kept)
            }
        }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(database.workspaceDao().getById("kept")).isNull()
        assertThat(database.sessionDao().getById(SESSION)).isNotNull()
    }

    @Test
    fun `数据库删除事务拒绝payload stage与row operationId错配`() = runBlocking {
        seedAllRelations()
        val raw = WorkspaceMutationPayloadCodec.encode(
            WorkspaceMutationPayload(
                sourceRelativePath = "root",
                targetRelativePath = ".infinity_session_deletions/op-b",
                databaseTargetUuid = SESSION,
                expectedSha256 = "identity",
            ),
        )
        database.workspaceMutationDao().insert(
            WorkspaceMutationEntity(
                operationId = "op-a",
                workspaceRootUuid = ROOT,
                operationType = WorkspaceMutationType.DELETE,
                payload = raw,
                payloadDigest = Sha256Utils.hash(raw),
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        assertThat(runCatching { RoomSessionDeletionTransaction(database).delete(target(), "op-a") }.exceptionOrNull())
            .isNotNull()
        assertThat(count("sessions")).isEqualTo(1)
        assertThat(count("workspace_files")).isEqualTo(2)
        assertThat(database.workspaceMutationDao().get("op-a")?.state?.name).isEqualTo("PREPARED")
    }

    @Test
    fun `完整journal删除同时清理工作区树及物理版本快照`() = runBlocking {
        val parent = Files.createTempDirectory("session-delete-parent")
        val root = Files.createDirectories(parent.resolve("root"))
        val snapshot = Files.createDirectories(root.resolve(".infinity_versions").resolve(ROOT))
            .resolve("version.snapshot")
        Files.write(snapshot, "version".toByteArray())
        Files.write(root.resolve("doc.txt"), "doc".toByteArray())
        seedAllRelations(root.toString(), snapshot.toString())
        val target = target(root.toString())
        val journal = RoomSessionWorkspaceMutationJournal(
            database = database,
            workspaceParent = parent,
            operationIdFactory = { "physical-op" },
            verifyIdentity = { _, _ -> },
        )

        val staged = journal.stage(target)
        RoomSessionDeletionTransaction(database).delete(target, staged.operationId)
        journal.complete(staged)

        assertThat(Files.exists(root)).isFalse()
        assertThat(Files.exists(snapshot)).isFalse()
        assertThat(database.workspaceMutationDao().get("physical-op")).isNull()
        Files.deleteIfExists(parent.resolve(".infinity_session_deletions"))
        Files.deleteIfExists(parent)
        Unit
    }

    @Test
    fun `身份marker删除后收尾IO失败仍可由DB_COMMITTED journal恢复`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("session-delete-partial")
        val root = Files.createDirectories(parent.resolve("root"))
        val marker = root.resolve(".infinity_root_identity")
        Files.write(marker, "identity".toByteArray())
        Files.write(root.resolve("payload.txt"), "payload".toByteArray())
        seedAllRelations(root.toString(), root.resolve("version.snapshot").toString())
        val target = target(root.toString())
        val journal = RoomSessionWorkspaceMutationJournal(
            database = database,
            workspaceParent = parent,
            operationIdFactory = { "partial-op" },
            verifyIdentity = { _, _ -> },
            afterIdentityMarkerDeleted = { throw java.io.IOException("after marker") },
        )
        val staged = journal.stage(target)
        RoomSessionDeletionTransaction(database).delete(target, staged.operationId)

        assertThat(runCatching { journal.complete(staged) }.exceptionOrNull())
            .isInstanceOf(java.io.IOException::class.java)
        val stagedRoot = parent.resolve(".infinity_session_deletions/partial-op")
        assertThat(Files.exists(stagedRoot)).isTrue()
        assertThat(Files.exists(stagedRoot.resolve(marker.fileName))).isFalse()
        val recovery = com.promenar.infinity.data.repository.WorkspaceMutationRecoveryCoordinator(
            workspaceParent = parent,
            loadUnfinished = { database.workspaceMutationDao().getUnfinished() },
            sessionExists = { false },
            deletePrepared = { check(database.workspaceMutationDao().deletePrepared(it) == 1) },
            deleteCommitted = { check(database.workspaceMutationDao().deleteCommitted(it) == 1) },
            verifyRootIdentity = { _, _ -> error("marker 已删时应使用 durable staging ownership") },
        )

        recovery.recoverOrThrow()

        assertThat(Files.exists(stagedRoot)).isFalse()
        assertThat(database.workspaceMutationDao().get("partial-op")).isNull()
        Files.deleteIfExists(parent.resolve(".infinity_session_deletions"))
        Files.deleteIfExists(parent)
    }

    @Test
    fun `物理收尾拒绝payload stage与row operationId错配`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("session-delete-mismatch")
        val root = Files.createDirectories(parent.resolve("root"))
        Files.write(root.resolve("sentinel.txt"), "keep".toByteArray())
        val journal = RoomSessionWorkspaceMutationJournal(
            database = database,
            workspaceParent = parent,
            operationIdFactory = { "op-a" },
            verifyIdentity = { _, _ -> },
        )
        val staged = journal.stage(target(root.toString()))
        check(database.workspaceMutationDao().markDbCommitted(staged.operationId, 2) == 1)
        val mismatchedRaw = WorkspaceMutationPayloadCodec.encode(
            WorkspaceMutationPayload(
                sourceRelativePath = "root",
                targetRelativePath = ".infinity_session_deletions/op-b",
                databaseTargetUuid = SESSION,
                expectedSha256 = "identity",
            ),
        )
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workspace_mutations SET payload = ?, payload_digest = ? WHERE operation_id = ?",
            arrayOf(mismatchedRaw, Sha256Utils.hash(mismatchedRaw), staged.operationId),
        )
        val stagedRoot = parent.resolve(".infinity_session_deletions/op-a")

        assertThat(runCatching { journal.complete(staged) }.exceptionOrNull()).isNotNull()
        assertThat(Files.readAllBytes(stagedRoot.resolve("sentinel.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")
        assertThat(database.workspaceMutationDao().get("op-a")).isNotNull()

        Files.walk(stagedRoot).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        Files.deleteIfExists(parent.resolve(".infinity_session_deletions"))
        Files.deleteIfExists(parent)
    }

    @Test
    fun `物理收尾拒绝journal payload摘要损坏`() = runBlocking<Unit> {
        val parent = Files.createTempDirectory("session-delete-digest")
        val root = Files.createDirectories(parent.resolve("root"))
        Files.write(root.resolve("sentinel.txt"), "keep".toByteArray())
        val journal = RoomSessionWorkspaceMutationJournal(
            database = database,
            workspaceParent = parent,
            operationIdFactory = { "digest-op" },
            verifyIdentity = { _, _ -> },
        )
        val staged = journal.stage(target(root.toString()))
        check(database.workspaceMutationDao().markDbCommitted(staged.operationId, 2) == 1)
        database.openHelper.writableDatabase.execSQL(
            "UPDATE workspace_mutations SET payload_digest = ? WHERE operation_id = ?",
            arrayOf("wrong-digest", staged.operationId),
        )
        val stagedRoot = parent.resolve(".infinity_session_deletions/digest-op")

        assertThat(runCatching { journal.complete(staged) }.exceptionOrNull()).isNotNull()
        assertThat(Files.readAllBytes(stagedRoot.resolve("sentinel.txt")).toString(Charsets.UTF_8)).isEqualTo("keep")
        assertThat(database.workspaceMutationDao().get("digest-op")).isNotNull()

        Files.walk(stagedRoot).sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        Files.deleteIfExists(parent.resolve(".infinity_session_deletions"))
        Files.deleteIfExists(parent)
    }

    private fun seedAllRelations(
        rootPath: String = "/tmp/root",
        versionPath: String = "/tmp/root/.infinity_versions/version.snapshot",
    ) {
        val db = database.openHelper.writableDatabase
        db.execSQL("INSERT INTO sessions(id,agent_id,title,unread,is_pinned,created_at,updated_at,workspace_path,workspace_root_uuid) VALUES('$SESSION','agent','title',0,0,1,1,'$rootPath','$ROOT')")
        db.execSQL("INSERT INTO workspace_files(uuid,workspace_root_uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at) VALUES('$ROOT','$ROOT',NULL,'root','identity',0,1,'$rootPath','/',1,1,0,1,1)")
        db.execSQL("INSERT INTO workspace_files(uuid,workspace_root_uuid,parent_uuid,name,hash,mime_type,size_bytes,is_directory,physical_root_path,materialized_path,vector_version,kg_version,in_recycle_bin,created_at,updated_at) VALUES('$FILE','$ROOT','$ROOT','doc.txt','hash','text/plain',4,0,'$rootPath','/doc.txt',1,1,0,1,1)")
        db.execSQL("INSERT INTO messages(id,session_id,role,content,created_at) VALUES('$MESSAGE','$SESSION','assistant','content',1)")
        db.execSQL("INSERT INTO attachments(id,message_id,type,uri) VALUES('attachment','$MESSAGE','file','uri')")
        db.execSQL("INSERT INTO vectors(id,doc_id,session_id,content,embedding,metadata,start_message_id,end_message_id,created_at,file_uuid) VALUES('vector','$FILE','$SESSION','content',X'00','{}','$MESSAGE','$MESSAGE',1,'$FILE')")
        db.execSQL("INSERT INTO context_summaries(id,session_id,start_message_id,end_message_id,summary_content,created_at) VALUES('summary','$SESSION','$MESSAGE','$MESSAGE','summary',1)")
        db.execSQL("INSERT INTO tags(id,name,color,created_at) VALUES('tag','tag','#fff',1)")
        db.execSQL("INSERT INTO document_tags(doc_id,tag_id,created_at) VALUES('$FILE','tag',1)")
        db.execSQL("INSERT INTO kg_collections(id,kind,title,root_ref,created_at) VALUES('work:$FILE','work','doc.txt','$FILE',1)")
        db.execSQL("INSERT INTO kg_collection_members(doc_id,collection_id) VALUES('$FILE','work:$FILE')")
        db.execSQL("INSERT INTO kg_nodes(id,name,type,normalized_name,created_at,collection_id) VALUES('node','node','entity','node',1,'work:$FILE')")
        db.execSQL("INSERT INTO kg_edges(id,source_id,target_id,relation,weight,normalized_relation,created_at,collection_id) VALUES('edge','node','node','rel',1.0,'rel',1,'work:$FILE')")
        db.execSQL("INSERT INTO kg_evidence(id,node_id,source_type,doc_id,session_id,content_hash,task_id,created_at,workspace_document) VALUES('node-evidence','node','document','$FILE','$SESSION','hash','fixture',1,1)")
        db.execSQL("INSERT INTO kg_evidence(id,edge_id,source_type,doc_id,session_id,content_hash,task_id,created_at,workspace_document) VALUES('edge-evidence','edge','document','$FILE','$SESSION','hash','fixture',1,1)")
        db.execSQL("INSERT INTO kg_jit_cache(cache_key,query_hash,chunk_ids_hash,result_json,created_at,expires_at) VALUES('cache','q','c','{}',1,2)")
        db.execSQL("INSERT INTO kg_jit_cache_sources(cache_key,doc_id,content_hash) VALUES('cache','$FILE','hash')")
        db.execSQL("INSERT INTO vectorization_tasks(id,type,status,doc_id,workspace_root_uuid,session_id,last_chunk_index,progress,skip_vectorization,content_truncated,target_epoch,created_at,updated_at) VALUES('vector-task','document_reference','pending','$FILE','$ROOT','$SESSION',0,0,0,0,1,1,1)")
        db.execSQL("INSERT INTO audit_logs(id,action,resource_type,session_id,status,created_at) VALUES('audit','delete','session','$SESSION','ok',1)")
        db.execSQL("INSERT INTO artifacts(id,type,title,content,session_id,message_id,created_at,updated_at) VALUES('artifact','text','title','body','$SESSION','$MESSAGE',1,1)")
        db.execSQL("INSERT INTO task_nodes(id,session_id,title,created_at,updated_at) VALUES('task','$SESSION','task',1,1)")
        db.execSQL("INSERT INTO task_nodes(id,session_id,parent_id,title,created_at,updated_at) VALUES('step','$SESSION','task','step',1,1)")
        db.execSQL("INSERT INTO tool_execution_ledger(session_id,assistant_message_id,tool_call_id,tool_name,runtime_tool_id,arguments_digest,definition_digest,requires_approval,status,created_at,updated_at) VALUES('$SESSION','$MESSAGE','call','tool','tool:id','args','definition',1,'PENDING_APPROVAL',1,1)")
        db.execSQL("INSERT INTO file_versions(id,file_uuid,workspace_root_uuid,hash,content_path,created_by_session_id,created_at) VALUES('version','$FILE','$ROOT','hash','$versionPath','$SESSION',1)")
    }

    private fun target(rootPath: String = "/tmp/root") = SessionDeletionTarget(
        sessionId = SESSION,
        workspaceRootUuid = ROOT,
        physicalRoot = Paths.get(rootPath),
        rootIdentity = "identity",
        fileUuids = listOf(ROOT, FILE),
    )

    private fun count(table: String): Long = database.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getLong(0)
        }

    companion object {
        private const val SESSION = "session-1"
        private const val ROOT = "root-1"
        private const val FILE = "file-1"
        private const val MESSAGE = "message-1"
    }
}
