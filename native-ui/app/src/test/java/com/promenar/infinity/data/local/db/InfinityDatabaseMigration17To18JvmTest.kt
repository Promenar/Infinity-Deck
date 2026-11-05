package com.promenar.infinity.data.local.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.repository.TestWorkspaceFileOps
import com.promenar.infinity.data.repository.WorkspaceRepository
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class InfinityDatabaseMigration17To18JvmTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migrationPreservesPublishedRowsAndValidatesCurrentRoomSchema() {
        createPublishedV17Database()

        val room = Room.databaseBuilder(context, InfinityDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
            .allowMainThreadQueries()
            .build()
        try {
            val database = room.openHelper.writableDatabase
            assertThat(database.longQuery("PRAGMA user_version")).isEqualTo(34L)
            assertThat(database.stringQuery("SELECT name FROM agents WHERE id='legacy-agent'"))
                .isEqualTo("Legacy Agent")
            assertThat(database.longQuery("SELECT name_customized FROM agents WHERE id='legacy-agent'"))
                .isEqualTo(1L)
            assertThat(database.stringQuery("SELECT execution_mode FROM sessions WHERE id='legacy-session'"))
                .isEqualTo("auto")
            assertThat(database.stringQuery("SELECT legacy_attachments FROM messages WHERE id='legacy-message'"))
                .isEqualTo(LEGACY_ATTACHMENTS_JSON)
            assertThat(database.stringQuery("SELECT uri FROM attachments WHERE id='relational-attachment'"))
                .isEqualTo("content://legacy/relational")
            assertThat(database.stringQuery("SELECT summary_content FROM context_summaries WHERE id='legacy-summary'"))
                .isEqualTo("Legacy summary")
            assertThat(database.stringQuery("SELECT title FROM artifacts WHERE id='legacy-artifact'"))
                .isEqualTo("Legacy artifact")
            assertThat(database.stringQuery("SELECT title FROM task_nodes WHERE id='legacy-node'"))
                .isEqualTo("Legacy task")
            assertThat(database.stringQuery("SELECT workspace_root_uuid FROM workspace_files WHERE uuid='legacy-file'"))
                .isEqualTo("legacy-root")
            assertThat(database.stringQuery("SELECT workspace_root_uuid FROM vectorization_tasks WHERE id='legacy-task'"))
                .isEqualTo("legacy-root")
            assertThat(database.stringQuery("SELECT target_content_hash FROM vectorization_tasks WHERE id='legacy-task'"))
                .isEqualTo("legacy-hash")
            assertThat(database.longQuery("SELECT target_epoch FROM vectorization_tasks WHERE id='legacy-task'"))
                .isEqualTo(17L)
            assertThat(database.longQuery("SELECT COUNT(*) FROM vectors_fts WHERE vectors_fts MATCH 'legacy'"))
                .isEqualTo(1L)
            database.execSQL(
                """INSERT INTO vectors(id,content,embedding,created_at)
                    VALUES('dynamic-vector','dynamic first',X'01',20)""".trimIndent(),
            )
            assertThat(database.longQuery("SELECT COUNT(*) FROM vectors_fts WHERE vectors_fts MATCH 'first'"))
                .isEqualTo(1L)
            database.execSQL("UPDATE vectors SET content='dynamic second' WHERE id='dynamic-vector'")
            assertThat(database.longQuery("SELECT COUNT(*) FROM vectors_fts WHERE vectors_fts MATCH 'first'"))
                .isEqualTo(0L)
            assertThat(database.longQuery("SELECT COUNT(*) FROM vectors_fts WHERE vectors_fts MATCH 'second'"))
                .isEqualTo(1L)
            database.execSQL("DELETE FROM vectors WHERE id='dynamic-vector'")
            assertThat(database.longQuery("SELECT COUNT(*) FROM vectors_fts WHERE vectors_fts MATCH 'second'"))
                .isEqualTo(0L)
            assertThat(database.stringColumnQuery("SELECT name FROM sqlite_master WHERE type='table'"))
                .containsAtLeast(
                    "mcp_tool_snapshots",
                    "tool_execution_ledger",
                    "file_versions",
                    "workspace_mutations",
                )
            assertThat(database.longQuery("PRAGMA foreign_key_check")).isEqualTo(0L)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrationKeepsDuplicateHistoricalVectorizationTasksWithoutBreakingUniqueIndex() {
        createPublishedV17Database()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            sqlite.execSQL(
                """INSERT INTO vectorization_tasks(
                    id,type,status,doc_id,doc_title,session_id,last_chunk_index,progress,created_at,updated_at
                ) VALUES('older-task','document_reference','pending','legacy-file','Legacy',
                    'legacy-session',0,0.5,4,16)""".trimIndent(),
            )
            listOf(
                "processing", "extracting_source", "chunking", "vectorizing", "saving", "extracting",
                "interrupted",
            )
                .forEachIndexed { index, status ->
                    sqlite.execSQL(
                        """INSERT INTO vectorization_tasks(
                            id,type,status,doc_id,doc_title,session_id,last_chunk_index,progress,created_at,updated_at
                        ) VALUES('older-task-$index','document_reference','$status','legacy-file','Legacy',
                            'legacy-session',0,0.5,4,${10 + index})""".trimIndent(),
                    )
                }
            sqlite.execSQL(
                """INSERT INTO vectorization_tasks(
                    id,type,status,doc_id,doc_title,session_id,last_chunk_index,progress,created_at,updated_at
                ) VALUES('missing-interrupted','document_reference','interrupted','missing-file','Missing',
                    'legacy-session',0,0.5,4,19)""".trimIndent(),
            )
        }

        val room = Room.databaseBuilder(context, InfinityDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
            .allowMainThreadQueries()
            .build()
        try {
            val database = room.openHelper.writableDatabase
            assertThat(database.longQuery("SELECT COUNT(*) FROM vectorization_tasks WHERE doc_id='legacy-file'"))
                .isEqualTo(9L)
            assertThat(database.longQuery("SELECT COUNT(*) FROM vectorization_tasks WHERE workspace_root_uuid='legacy-root'"))
                .isEqualTo(1L)
            assertThat(database.stringQuery("SELECT status FROM vectorization_tasks WHERE id='older-task'"))
                .isEqualTo("failed")
            assertThat(database.longQuery(
                "SELECT COUNT(*) FROM vectorization_tasks WHERE id LIKE 'older-task-%' AND status='failed'",
            )).isEqualTo(7L)
            assertThat(database.stringQuery("SELECT status FROM vectorization_tasks WHERE id='missing-interrupted'"))
                .isEqualTo("failed")
        } finally {
            room.close()
        }
    }

    @Test
    fun migrationSynthesizesRootAndReparentsTopLevelRowsWhenPublishedRootIsInvalid() {
        createPublishedV17Database()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            sqlite.execSQL("UPDATE workspace_files SET materialized_path='/legacy-root' WHERE uuid='legacy-root'")
        }

        val room = Room.databaseBuilder(context, InfinityDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
            .allowMainThreadQueries()
            .build()
        try {
            val database = room.openHelper.writableDatabase
            val synthesizedRoot = database.stringQuery(
                "SELECT workspace_root_uuid FROM sessions WHERE id='legacy-session'",
            )
            assertThat(synthesizedRoot).startsWith("legacy-root-")
            assertThat(database.stringQuery("SELECT workspace_root_uuid FROM workspace_files WHERE uuid='legacy-file'"))
                .isEqualTo(synthesizedRoot)
            assertThat(database.stringQuery("SELECT parent_uuid FROM workspace_files WHERE uuid='legacy-root'"))
                .isEqualTo(synthesizedRoot)
            assertThat(database.longQuery(
                """SELECT COUNT(*) FROM workspace_files
                    WHERE uuid='$synthesizedRoot' AND workspace_root_uuid='$synthesizedRoot'
                      AND parent_uuid IS NULL AND is_directory=1 AND materialized_path='/'""".trimIndent(),
            )).isEqualTo(1L)
        } finally {
            room.close()
        }
    }

    @Test
    fun migrationThenRepositoryAdoptsExactPublishedSessionWorkspaceAndKeepsFilesReachable() = runBlocking<Unit> {
        createPublishedV17Database()
        val publishedParent = File(context.filesDir, "workspaces").apply { mkdirs() }
        val publishedRoot = File(publishedParent, "legacy-session").apply { mkdirs() }
        val userFile = File(publishedRoot, "legacy.txt").apply { writeText("legacy") }
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            sqlite.execSQL(
                "UPDATE sessions SET workspace_path=? WHERE id='legacy-session'",
                arrayOf(publishedRoot.canonicalPath),
            )
            sqlite.execSQL(
                "UPDATE workspace_files SET physical_root_path=? WHERE uuid IN ('legacy-root','legacy-file')",
                arrayOf(publishedRoot.canonicalPath),
            )
        }
        val room = Room.databaseBuilder(context, InfinityDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
            .allowMainThreadQueries()
            .build()
        try {
            room.openHelper.writableDatabase
            val repository = WorkspaceRepository(
                room.fileEntryDao(),
                room.workspaceSeqDao(),
                File(context.filesDir, "session_workspaces"),
                TestWorkspaceFileOps(),
            )

            val root = repository.ensureSessionRoot("legacy-session")

            assertThat(root.uuid).isEqualTo("legacy-root")
            assertThat(root.hash).isNotEmpty()
            assertThat(userFile.readText()).isEqualTo("legacy")
            assertThat(repository.getByUuid(root.uuid, "legacy-file")!!.materializedPath)
                .isEqualTo("/legacy.txt")
        } finally {
            room.close()
            publishedRoot.deleteRecursively()
            publishedParent.delete()
        }
    }

    @Test
    fun migrationFailsClosedWhenPublishedSessionsShareTheSamePhysicalWorkspacePath() {
        createPublishedV17Database()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            sqlite.execSQL(
                """INSERT INTO sessions(
                    id,agent_id,title,unread,is_pinned,execution_mode,workspace_path,created_at,updated_at
                ) VALUES('duplicate-session','legacy-agent','Duplicate',0,0,'auto','/legacy',20,20)""".trimIndent(),
            )
        }
        val room = Room.databaseBuilder(context, InfinityDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24, MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34)
            .allowMainThreadQueries()
            .build()

        val failure = runCatching { room.openHelper.writableDatabase }.exceptionOrNull()
        room.close()

        assertThat(failure).isNotNull()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { sqlite ->
            assertThat(sqlite.version).isEqualTo(17)
            sqlite.rawQuery("SELECT COUNT(*) FROM sessions WHERE workspace_path='/legacy'", null).use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(2L)
            }
        }
    }

    private fun createPublishedV17Database() {
        Room.databaseBuilder(context, InfinityV18RecoveryDatabase::class.java, DATABASE_NAME)
            .allowMainThreadQueries()
            .build()
            .also { it.openHelper.writableDatabase }
            .close()

        SQLiteDatabase.openDatabase(
            context.getDatabasePath(DATABASE_NAME).path,
            null,
            SQLiteDatabase.OPEN_READWRITE,
        ).use { sqlite ->
            sqlite.execSQL("PRAGMA foreign_keys=OFF")
            replaceKgTablesWithV18(sqlite)
            listOf(
                "mcp_tool_snapshots",
                "tool_execution_ledger",
                "file_versions",
                "workspace_mutations",
                "vectorization_tasks",
                "messages",
                "workspace_files",
                "sessions",
                "agents",
                "vectors_fts",
            ).forEach { table -> sqlite.execSQL("DROP TABLE IF EXISTS `$table`") }

            createPublishedTables(sqlite)
            insertPublishedRows(sqlite)
            sqlite.execSQL("UPDATE room_master_table SET identity_hash='published-v17' WHERE id=42")
            sqlite.version = 17
        }
    }

    private fun createPublishedTables(sqlite: SQLiteDatabase) {
        sqlite.execSQL(
            """CREATE TABLE agents (
                id TEXT NOT NULL, name TEXT NOT NULL, description TEXT NOT NULL,
                system_prompt TEXT NOT NULL, model TEXT NOT NULL, icon TEXT NOT NULL,
                color TEXT NOT NULL, avatar_path TEXT, is_pinned INTEGER NOT NULL,
                created_at INTEGER NOT NULL, temperature REAL, top_p REAL, max_tokens INTEGER,
                rag_config TEXT, retrieval_config TEXT, use_inherited_config INTEGER NOT NULL,
                PRIMARY KEY(id)
            )""".trimIndent(),
        )
        sqlite.execSQL(
            """CREATE TABLE sessions (
                id TEXT NOT NULL, agent_id TEXT NOT NULL, title TEXT NOT NULL,
                last_message TEXT, time TEXT, unread INTEGER NOT NULL, model_id TEXT,
                custom_prompt TEXT, is_pinned INTEGER NOT NULL, scroll_offset REAL, draft TEXT,
                execution_mode TEXT NOT NULL DEFAULT 'auto', loop_status TEXT NOT NULL DEFAULT 'idle',
                pending_intervention TEXT, approval_request TEXT, rag_options TEXT,
                inference_params TEXT, active_task TEXT, stats TEXT, options TEXT,
                active_mcp_server_ids TEXT, active_skill_ids TEXT, workspace_path TEXT,
                workspace_root_uuid TEXT, active_task_tree_id TEXT, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, PRIMARY KEY(id)
            )""".trimIndent(),
        )
        sqlite.execSQL(
            """CREATE TABLE messages (
                id TEXT NOT NULL, session_id TEXT NOT NULL, role TEXT NOT NULL, content TEXT NOT NULL,
                model_id TEXT, status TEXT, reasoning TEXT, thought_signature TEXT, images TEXT,
                tokens TEXT, citations TEXT, rag_references TEXT, rag_progress TEXT, rag_metadata TEXT,
                rag_references_loading INTEGER NOT NULL DEFAULT 0, execution_steps TEXT, tool_calls TEXT,
                pending_approval_tool_ids TEXT, tool_call_id TEXT, name TEXT, planning_task TEXT,
                is_archived INTEGER NOT NULL DEFAULT 0, vectorization_status TEXT, layout_height REAL,
                tool_results TEXT, files TEXT, user_images TEXT, attachments TEXT,
                is_error INTEGER NOT NULL DEFAULT 0, error_message TEXT, created_at INTEGER NOT NULL,
                PRIMARY KEY(id), FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX index_messages_session_id ON messages(session_id)")
        sqlite.execSQL("CREATE INDEX index_messages_session_id_created_at ON messages(session_id, created_at)")
        sqlite.execSQL(
            """CREATE TABLE workspace_files (
                uuid TEXT NOT NULL, parent_uuid TEXT, name TEXT NOT NULL, hash TEXT NOT NULL,
                mime_type TEXT, size_bytes INTEGER NOT NULL, is_directory INTEGER NOT NULL,
                physical_root_path TEXT NOT NULL, materialized_path TEXT NOT NULL,
                vectorized_at INTEGER, vector_version INTEGER NOT NULL, kg_extracted_at INTEGER,
                kg_version INTEGER NOT NULL, last_write_session_id TEXT, locked_by_session_id TEXT,
                lock_expires_at INTEGER, in_recycle_bin INTEGER NOT NULL, recycled_at INTEGER,
                original_parent_uuid TEXT, original_materialized_path TEXT,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, PRIMARY KEY(uuid)
            )""".trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX index_workspace_files_parent_uuid ON workspace_files(parent_uuid)")
        sqlite.execSQL("CREATE INDEX index_workspace_files_materialized_path ON workspace_files(materialized_path)")
        sqlite.execSQL("CREATE INDEX index_workspace_files_hash ON workspace_files(hash)")
        sqlite.execSQL("CREATE INDEX index_workspace_files_is_directory ON workspace_files(is_directory)")
        sqlite.execSQL(
            "CREATE INDEX index_workspace_files_in_recycle_bin_physical_root_path_recycled_at " +
                "ON workspace_files(in_recycle_bin, physical_root_path, recycled_at)",
        )
        sqlite.execSQL(
            """CREATE TABLE vectorization_tasks (
                id TEXT NOT NULL, type TEXT NOT NULL, status TEXT NOT NULL, doc_id TEXT,
                doc_title TEXT, session_id TEXT, user_content TEXT, ai_content TEXT,
                user_message_id TEXT, assistant_message_id TEXT, last_chunk_index INTEGER NOT NULL,
                total_chunks INTEGER, progress REAL NOT NULL, error TEXT, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, PRIMARY KEY(id),
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent(),
        )
        sqlite.execSQL("CREATE INDEX index_vectorization_tasks_status ON vectorization_tasks(status)")
        sqlite.execSQL("CREATE INDEX index_vectorization_tasks_doc_id ON vectorization_tasks(doc_id)")
        sqlite.execSQL("CREATE INDEX index_vectorization_tasks_session_id ON vectorization_tasks(session_id)")
        sqlite.execSQL("CREATE VIRTUAL TABLE vectors_fts USING FTS4(content, content=vectors)")
    }

    private fun insertPublishedRows(sqlite: SQLiteDatabase) {
        sqlite.execSQL(
            """INSERT INTO agents(
                id,name,description,system_prompt,model,icon,color,is_pinned,created_at,use_inherited_config
            ) VALUES('legacy-agent','Legacy Agent','Legacy description','','legacy-model','icon','color',0,1,1)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO sessions(
                id,agent_id,title,unread,is_pinned,execution_mode,workspace_path,workspace_root_uuid,created_at,updated_at
            ) VALUES('legacy-session','legacy-agent','Legacy Session',0,0,'auto','/legacy','legacy-root',2,3)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO messages(
                id,session_id,role,content,attachments,rag_references_loading,is_archived,is_error,created_at
            ) VALUES('legacy-message','legacy-session','user','Legacy message',?,0,0,0,4)""".trimIndent(),
            arrayOf(LEGACY_ATTACHMENTS_JSON),
        )
        sqlite.execSQL(
            """INSERT INTO attachments(id,message_id,type,uri,local_uri)
                VALUES('relational-attachment','legacy-message','file','content://legacy/relational',NULL)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO context_summaries(
                id,session_id,start_message_id,end_message_id,summary_content,created_at
            ) VALUES('legacy-summary','legacy-session','legacy-message','legacy-message','Legacy summary',4)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO artifacts(
                id,type,title,content,session_id,message_id,created_at,updated_at
            ) VALUES('legacy-artifact','text','Legacy artifact','content','legacy-session','legacy-message',4,5)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO task_nodes(
                id,session_id,title,description,status,created_at,updated_at
            ) VALUES('legacy-node','legacy-session','Legacy task','','pending',4,5)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO workspace_files(
                uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,
                vector_version,kg_version,in_recycle_bin,created_at,updated_at
            ) VALUES('legacy-root',NULL,'legacy','',0,1,'/legacy','/',1,1,0,5,6)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO workspace_files(
                uuid,parent_uuid,name,hash,size_bytes,is_directory,physical_root_path,materialized_path,
                vector_version,kg_version,in_recycle_bin,created_at,updated_at
            ) VALUES('legacy-file','legacy-root','legacy.txt','legacy-hash',6,0,
                '/legacy','/legacy.txt',1,1,0,7,17)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO vectorization_tasks(
                id,type,status,doc_id,doc_title,session_id,last_chunk_index,progress,created_at,updated_at
            ) VALUES('legacy-task','document_reference','completed','legacy-file','Legacy',
                'legacy-session',0,1.0,8,18)""".trimIndent(),
        )
        sqlite.execSQL(
            """INSERT INTO vectors(
                id,doc_id,session_id,content,embedding,created_at,stale,version,file_uuid
            ) VALUES('legacy-vector','legacy-file','legacy-session','legacy searchable content',?,9,0,1,'legacy-file')""".trimIndent(),
            arrayOf(byteArrayOf(1, 2, 3, 4)),
        )
    }

    private fun SupportSQLiteDatabase.stringQuery(sql: String): String =
        query(sql).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.longQuery(sql: String): Long =
        query(sql).use { cursor ->
            if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
        }

    private fun SupportSQLiteDatabase.stringColumnQuery(sql: String): List<String> =
        query(sql).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }

    private companion object {
        const val DATABASE_NAME = "migration-17-18-jvm.db"
        const val LEGACY_ATTACHMENTS_JSON =
            "[{\"uri\":\"content://legacy/column\",\"fileName\":\"legacy.txt\",\"sizeBytes\":6}]"
    }
}
