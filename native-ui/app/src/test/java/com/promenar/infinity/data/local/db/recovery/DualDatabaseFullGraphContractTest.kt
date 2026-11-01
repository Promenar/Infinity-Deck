package com.promenar.infinity.data.local.db.recovery

import com.promenar.infinity.data.local.db.seedKgNode
import com.promenar.infinity.data.local.db.seedKgEdge

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.local.db.InfinityDatabase
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.ArtifactEntity
import com.promenar.infinity.data.local.db.entity.AttachmentEntity
import com.promenar.infinity.data.local.db.entity.AuditLogEntity
import com.promenar.infinity.data.local.db.entity.ContextSummaryEntity
import com.promenar.infinity.data.local.db.entity.CustomSkillEntity
import com.promenar.infinity.data.local.db.entity.DocumentTagEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.FileVersionEntity
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgJitCacheEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.McpServerEntity
import com.promenar.infinity.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.TagEntity
import com.promenar.infinity.data.local.db.entity.TaskNodeEntity
import com.promenar.infinity.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.infinity.data.local.db.entity.ToolLedgerStatus
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Path

/**
 * 以 Room 生成的真实 v20 schema 验证双库全图合并契约。
 *
 * 夹具故意同时在 current 与 legacy 使用相同业务 ID；旧侧数据必须进入稳定命名空间，
 * 但 MCP 远端工具名属于业务协议名称，工具调用 JSON 中的引用则必须跟随对应 ID 映射。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class)
class DualDatabaseFullGraphContractTest {
    @Test
    fun `real v20 composite file task and complete graph references survive merge`() {
        withDatabasePair(includeVectorizationTask = true) { currentPath, legacyPath ->
            val result = merge(currentPath, legacyPath)

            SQLiteDatabase.openDatabase(currentPath.toString(), null, SQLiteDatabase.OPEN_READONLY).use { database ->
                assertThat(count(database, "sessions")).isEqualTo(2)
                val legacyUser = RecoveryStableId.map("messages", "id", USER_MESSAGE_ID)
                val legacyAssistantContext = RecoveryStableId.map("messages", "id", ASSISTANT_ID)
                assertThat(value(database, "SELECT turn_context FROM messages WHERE id=?", arrayOf(USER_MESSAGE_ID)))
                    .isEqualTo("  current context $USER_MESSAGE_ID\r\n🧪\n")
                assertThat(value(database, "SELECT turn_context FROM messages WHERE id=?", arrayOf(legacyUser)))
                    .isEqualTo("  legacy context $USER_MESSAGE_ID\r\n🧪\n")
                assertThat(value(database, "SELECT request_instruction FROM messages WHERE id=?", arrayOf(legacyAssistantContext)))
                    .isEqualTo("  legacy instruction $ASSISTANT_ID\r\n🧪\n")
                assertThat(count(database, "workspace_files")).isEqualTo(4)
                assertThat(count(database, "vectorization_tasks")).isEqualTo(2)
                assertThat(count(database, "kg_jit_cache")).isEqualTo(0)
                assertThat(count(database, "audit_logs")).isEqualTo(2)

                val legacyRoot = RecoveryStableId.map("workspace_files", "uuid", ROOT_ID)
                val legacyFile = RecoveryStableId.map("workspace_files", "uuid", FILE_ID)
                database.rawQuery(
                    "SELECT workspace_root_uuid,doc_id,status FROM vectorization_tasks WHERE id=?",
                    arrayOf(RecoveryStableId.map("vectorization_tasks", "id", VECTOR_TASK_ID)),
                ).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo(legacyRoot)
                    assertThat(cursor.getString(1)).isEqualTo(legacyFile)
                    assertThat(cursor.getString(2)).isEqualTo("failed")
                }
                assertThat(foreignKeyViolations(database)).isEmpty()
            }
            assertThat(result.mappingCount).isGreaterThan(0)
            assertThat(result.unresolvedReferences.filter { it.startsWith("required:") }).isEmpty()
            assertThat(result.unresolvedReferences).contains("preserved-derived-cache:kg_jit_cache")
            assertLegacyDerivedEvidence(legacyPath)
        }
    }

    @Test
    fun `real v20 mcp document rag and closed tool references remain reachable`() {
        withDatabasePair(includeVectorizationTask = false) { currentPath, legacyPath ->
            val result = merge(currentPath, legacyPath)
            assertThat(result.unresolvedReferences.filter { it.startsWith("required:") }).isEmpty()

            SQLiteDatabase.openDatabase(currentPath.toString(), null, SQLiteDatabase.OPEN_READONLY).use { database ->
                val legacyServer = RecoveryStableId.map("mcp_servers", "id", SERVER_ID)
                val legacySession = RecoveryStableId.map("sessions", "id", SESSION_ID)
                val legacyFile = RecoveryStableId.map("workspace_files", "uuid", FILE_ID)
                val legacyRoot = RecoveryStableId.map("workspace_files", "uuid", ROOT_ID)
                val legacyTag = RecoveryStableId.map("tags", "id", TAG_ID)
                val legacyAssistant = RecoveryStableId.map("messages", "id", ASSISTANT_ID)
                val legacyToolCall = RecoveryStableId.map("tool_execution_ledger", "tool_call_id", TOOL_CALL_ID)
                val legacyToolResult = RecoveryStableId.map("messages", "id", TOOL_RESULT_ID)

                assertThat(count(database, "sessions")).isEqualTo(2)
                assertThat(value(database, "SELECT content FROM messages WHERE id=?", arrayOf(ASSISTANT_ID)))
                    .isEqualTo("current assistant")
                assertThat(
                    value(database, "SELECT content FROM messages WHERE id=?", arrayOf(legacyAssistant)),
                ).isEqualTo("legacy assistant")

                database.rawQuery(
                    "SELECT remote_tool_name FROM mcp_tool_snapshots WHERE server_id IN (?,?) ORDER BY remote_tool_name",
                    arrayOf(SERVER_ID, legacyServer),
                ).use { cursor ->
                    val names = buildList {
                        while (cursor.moveToNext()) add(cursor.getString(0))
                    }
                    assertThat(names).containsExactly("read_file", "read_file")
                }

                assertThat(
                    value(
                        database,
                        "SELECT doc_id FROM document_tags WHERE tag_id=?",
                        arrayOf(legacyTag),
                    ),
                ).isEqualTo(legacyFile)
                assertThat(
                    count(
                        database,
                        "SELECT COUNT(*) FROM document_tags AS tags " +
                            "JOIN workspace_files AS files ON files.uuid=tags.doc_id " +
                            "WHERE tags.tag_id=? AND files.workspace_root_uuid=?",
                        arrayOf(legacyTag, legacyRoot),
                    ),
                ).isEqualTo(1)
                assertThat(
                    value(
                        database,
                        "SELECT doc_id FROM vectors WHERE id=?",
                        arrayOf(RecoveryStableId.map("vectors", "id", VECTOR_ID)),
                    ),
                ).isEqualTo(legacyFile)
                assertThat(
                    value(
                        database,
                        "SELECT file_uuid FROM vectors WHERE id=?",
                        arrayOf(RecoveryStableId.map("vectors", "id", VECTOR_ID)),
                    ),
                ).isEqualTo(legacyFile)

                val ragOptions = value(
                    database,
                    "SELECT rag_options FROM sessions WHERE id=?",
                    arrayOf(legacySession),
                )
                assertThat(ragOptions).contains(legacyFile)
                assertThat(ragOptions).contains(legacyRoot)
                assertThat(ragOptions).doesNotContain("\"$FILE_ID\"")
                assertThat(ragOptions).doesNotContain("\"$ROOT_ID\"")

                database.rawQuery(
                    "SELECT tool_call_id,result_message_id,status FROM tool_execution_ledger " +
                        "WHERE session_id=? AND assistant_message_id=?",
                    arrayOf(legacySession, legacyAssistant),
                ).use { cursor ->
                    assertThat(cursor.moveToFirst()).isTrue()
                    assertThat(cursor.getString(0)).isEqualTo(legacyToolCall)
                    assertThat(cursor.getString(1)).isEqualTo(legacyToolResult)
                    assertThat(cursor.getString(2)).isEqualTo(ToolLedgerStatus.SUCCEEDED.name)
                }

                val toolCalls = value(
                    database,
                    "SELECT tool_calls FROM messages WHERE id=?",
                    arrayOf(legacyAssistant),
                )
                assertThat(toolCalls).contains(legacyToolCall)
                assertThat(toolCalls).doesNotContain("\"$TOOL_CALL_ID\"")
                val pendingToolIds = value(
                    database,
                    "SELECT pending_approval_tool_ids FROM messages WHERE id=?",
                    arrayOf(legacyAssistant),
                )
                assertThat(pendingToolIds).contains(legacyToolCall)
                assertThat(pendingToolIds).doesNotContain("\"$TOOL_CALL_ID\"")
                val toolResults = value(
                    database,
                    "SELECT tool_results FROM messages WHERE id=?",
                    arrayOf(legacyToolResult),
                )
                assertThat(toolResults).contains(legacyToolCall)
                assertThat(toolResults).contains(legacyToolResult)
                assertThat(toolResults).doesNotContain("\"$TOOL_CALL_ID\"")
                assertThat(toolResults).doesNotContain("\"$TOOL_RESULT_ID\"")
                assertThat(
                    value(
                        database,
                        "SELECT tool_call_id FROM messages WHERE id=?",
                        arrayOf(legacyToolResult),
                    ),
                ).isEqualTo(legacyToolCall)

                val approvalRequest = nullableValue(
                    database,
                    "SELECT approval_request FROM sessions WHERE id=?",
                    arrayOf(legacySession),
                )
                assertThat(approvalRequest).isNull()
                assertThat(foreignKeyViolations(database)).isEmpty()
                assertThat(count(database, "kg_jit_cache")).isEqualTo(0)
                assertThat(count(database, "audit_logs")).isEqualTo(2)
            }
            assertThat(result.unresolvedReferences).contains("preserved-derived-cache:kg_jit_cache")
            assertLegacyDerivedEvidence(legacyPath)
        }
    }

    private fun merge(current: Path, legacy: Path): DualDatabaseMergeResult =
        DualDatabaseGraphMerger(RecoveryPathRewriter { _, _, value -> value })
            .merge(current, legacy, "full-graph-contract")

    private fun withDatabasePair(
        includeVectorizationTask: Boolean,
        block: (current: Path, legacy: Path) -> Unit,
    ) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val currentName = "full-graph-current-${System.nanoTime()}.db"
        val legacyName = "full-graph-legacy-${System.nanoTime()}.db"
        val currentPath = context.getDatabasePath(currentName).toPath()
        val legacyPath = context.getDatabasePath(legacyName).toPath()
        var current: InfinityDatabase? = null
        var legacy: InfinityDatabase? = null
        try {
            current = openDatabase(context, currentName)
            legacy = openDatabase(context, legacyName)
            runBlocking {
                seed(current!!, "current", includeVectorizationTask)
                seed(legacy!!, "legacy", includeVectorizationTask)
            }
            current!!.close()
            legacy!!.close()
            current = null
            legacy = null
            block(currentPath, legacyPath)
        } finally {
            current?.close()
            legacy?.close()
            context.deleteDatabase(currentName)
            context.deleteDatabase(legacyName)
        }
    }

    private fun openDatabase(
        context: android.content.Context,
        name: String,
    ): InfinityDatabase = Room.databaseBuilder(context, InfinityDatabase::class.java, name)
        .allowMainThreadQueries()
        .build()

    private suspend fun seed(database: InfinityDatabase, label: String, includeVectorizationTask: Boolean) {
        val now = if (label == "current") 1_000L else 2_000L
        database.skillDao().insertCustomSkill(
            CustomSkillEntity(
                id = SKILL_ID,
                name = "read skill $label",
                description = "synthetic",
                parametersSchema = "{}",
                code = "return null",
                createdAt = now,
            ),
        )
        database.skillDao().insertMcpServer(
            McpServerEntity(
                id = SERVER_ID,
                name = "server $label",
                url = "https://example.invalid/$label",
                createdAt = now,
            ),
        )
        database.skillDao().insertMcpToolSnapshots(
            listOf(
                McpToolSnapshotEntity(
                    serverId = SERVER_ID,
                    remoteToolName = REMOTE_TOOL_NAME,
                    description = "read a file",
                    inputSchemaJson = "{}",
                    syncedAt = now,
                ),
            ),
        )
        database.agentDao().insert(
            AgentEntity(
                id = AGENT_ID,
                name = "agent $label",
                createdAt = now,
                skillIds = "[\"$SKILL_ID\"]",
                mcpServerIds = "[\"$SERVER_ID\"]",
            ),
        )
        database.fileEntryDao().insert(
            FileEntry(
                uuid = ROOT_ID,
                workspaceRootUuid = ROOT_ID,
                parentUuid = null,
                name = "root",
                hash = "root-$label",
                isDirectory = true,
                physicalRootPath = "/synthetic/$label",
                materializedPath = "/",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.fileEntryDao().insert(
            FileEntry(
                uuid = FILE_ID,
                workspaceRootUuid = ROOT_ID,
                parentUuid = ROOT_ID,
                name = "document.txt",
                hash = "file-$label",
                mimeType = "text/plain",
                sizeBytes = 12,
                physicalRootPath = "/synthetic/$label",
                materializedPath = "/document.txt",
                lastWriteSessionId = SESSION_ID,
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.sessionDao().insert(
            SessionEntity(
                id = SESSION_ID,
                agentId = AGENT_ID,
                title = "session $label",
                approvalRequest = "{\"toolCallId\":\"$TOOL_CALL_ID\",\"toolName\":\"$REMOTE_TOOL_NAME\"}",
                ragOptions = "{\"activeDocIds\":[\"$FILE_ID\"],\"activeFolderIds\":[\"$ROOT_ID\"]}",
                activeMcpServerIds = "[\"$SERVER_ID\"]",
                activeSkillIds = "[\"$SKILL_ID\"]",
                workspacePath = "/synthetic/$label",
                workspaceRootUuid = ROOT_ID,
                activeTaskTreeId = TASK_NODE_ID,
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.messageDao().insert(
            MessageEntity(
                id = USER_MESSAGE_ID,
                sessionId = SESSION_ID,
                role = "user",
                content = "$label user",
                turnContext = "  $label context $USER_MESSAGE_ID\r\n🧪\n",
                createdAt = now,
            ),
        )
        database.messageDao().insert(
            MessageEntity(
                id = ASSISTANT_ID,
                sessionId = SESSION_ID,
                role = "assistant",
                content = "$label assistant",
                requestInstruction = "  $label instruction $ASSISTANT_ID\r\n🧪\n",
                ragReferences = "[{\"documentId\":\"$FILE_ID\",\"messageId\":\"$USER_MESSAGE_ID\"}]",
                toolCalls = "[{\"id\":\"$TOOL_CALL_ID\",\"name\":\"$REMOTE_TOOL_NAME\",\"arguments\":\"{}\"}]",
                pendingApprovalToolIds = "[\"$TOOL_CALL_ID\"]",
                createdAt = now + 1,
            ),
        )
        database.messageDao().insert(
            MessageEntity(
                id = TOOL_RESULT_ID,
                sessionId = SESSION_ID,
                role = "tool",
                content = "$label tool result",
                toolCallId = TOOL_CALL_ID,
                parentMessageId = ASSISTANT_ID,
                toolResults = "[{\"toolCallId\":\"$TOOL_CALL_ID\",\"resultMessageId\":\"$TOOL_RESULT_ID\"}]",
                createdAt = now + 2,
            ),
        )
        database.attachmentDao().insert(
            AttachmentEntity(
                id = ATTACHMENT_ID,
                messageId = USER_MESSAGE_ID,
                type = "text",
                uri = "file:///synthetic/$label/document.txt",
            ),
        )
        database.vectorDao().insert(
            VectorEntity(
                id = VECTOR_ID,
                docId = FILE_ID,
                sessionId = SESSION_ID,
                content = "$label vector",
                embedding = byteArrayOf(1, 2, 3),
                metadata = "{\"type\":\"document\"}",
                startMessageId = USER_MESSAGE_ID,
                endMessageId = ASSISTANT_ID,
                createdAt = now,
                fileUuid = FILE_ID,
            ),
        )
        database.contextSummaryDao().insert(
            ContextSummaryEntity(
                id = SUMMARY_ID,
                sessionId = SESSION_ID,
                startMessageId = USER_MESSAGE_ID,
                endMessageId = ASSISTANT_ID,
                summaryContent = "$label summary",
                createdAt = now,
            ),
        )
        database.tagDao().insert(TagEntity(TAG_ID, "tag-$label", createdAt = now))
        database.documentTagDao().insert(DocumentTagEntity(FILE_ID, TAG_ID, now))
        database.seedKgNode(
            KgNodeEntity(
                id = KG_NODE_A_ID,
                name = "node-a-$label",
                createdAt = now,
            ), sessionId = SESSION_ID, docId = FILE_ID, workspaceDocument = true)
        database.seedKgNode(
            KgNodeEntity(
                id = KG_NODE_B_ID,
                name = "node-b-$label",
                createdAt = now,
            ), sessionId = SESSION_ID, docId = FILE_ID, workspaceDocument = true)
        database.seedKgEdge(
            KgEdgeEntity(
                id = KG_EDGE_ID,
                sourceId = KG_NODE_A_ID,
                targetId = KG_NODE_B_ID,
                relation = "references",
                createdAt = now,
            ), sessionId = SESSION_ID, docId = FILE_ID, workspaceDocument = true)
        database.taskNodeDao().upsert(
            TaskNodeEntity(
                id = TASK_NODE_ID,
                sessionId = SESSION_ID,
                title = "task $label",
                artifactFileUuids = "[\"$FILE_ID\"]",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.toolExecutionLedgerDao().insert(
            ToolExecutionLedgerEntity(
                sessionId = SESSION_ID,
                assistantMessageId = ASSISTANT_ID,
                toolCallId = TOOL_CALL_ID,
                toolName = REMOTE_TOOL_NAME,
                runtimeToolId = "runtime-$label",
                argumentsDigest = "args-$label",
                definitionDigest = "definition-$label",
                requiresApproval = true,
                status = ToolLedgerStatus.SUCCEEDED,
                resultMessageId = TOOL_RESULT_ID,
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.fileVersionDao().insert(
            FileVersionEntity(
                id = FILE_VERSION_ID,
                fileUuid = FILE_ID,
                workspaceRootUuid = ROOT_ID,
                hash = "version-$label",
                contentPath = "/synthetic/$label/.versions/document.txt",
                createdBySessionId = SESSION_ID,
                createdAt = now,
            ),
        )
        database.auditLogDao().insert(
            AuditLogEntity(
                id = AUDIT_ID,
                action = "seed",
                resourceType = "file",
                resourcePath = "/document.txt",
                sessionId = SESSION_ID,
                agentId = AGENT_ID,
                skillId = SKILL_ID,
                status = "ok",
                createdAt = now,
            ),
        )
        database.kgJitCacheDao().insert(
            KgJitCacheEntity(
                cacheKey = "kg-cache-$label",
                queryHash = "query-$label",
                chunkIdsHash = "chunks-$label",
                resultJson = "{\"report\":\"$label\"}",
                createdAt = now,
                expiresAt = now + 60_000L,
            ),
        )
        database.artifactDao().insert(
            ArtifactEntity(
                id = ARTIFACT_ID,
                type = "text",
                title = "artifact $label",
                content = "$label artifact",
                sessionId = SESSION_ID,
                messageId = ASSISTANT_ID,
                workspacePath = "/document.txt",
                createdAt = now,
                updatedAt = now,
            ),
        )
        database.workspaceSeqDao().getNextSeqForDate("2026-09-22")
        if (includeVectorizationTask) {
            database.vectorizationTaskDao().insert(
                VectorizationTaskEntity(
                    id = VECTOR_TASK_ID,
                    type = "document_reference",
                    status = "pending",
                    docId = FILE_ID,
                    docTitle = "document.txt",
                    workspaceRootUuid = ROOT_ID,
                    sessionId = SESSION_ID,
                    userMessageId = USER_MESSAGE_ID,
                    assistantMessageId = ASSISTANT_ID,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun count(database: SQLiteDatabase, table: String): Int =
        database.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getInt(0)
        }

    private fun count(database: SQLiteDatabase, sql: String, args: Array<String>): Int =
        database.rawQuery(sql, args).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getInt(0)
        }

    private fun value(database: SQLiteDatabase, sql: String, args: Array<String>): String =
        database.rawQuery(sql, args).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getString(0)
        }

    private fun nullableValue(database: SQLiteDatabase, sql: String, args: Array<String>): String? =
        database.rawQuery(sql, args).use { cursor ->
            assertThat(cursor.moveToFirst()).isTrue()
            cursor.getString(0)
        }

    private fun assertLegacyDerivedEvidence(path: Path) {
        SQLiteDatabase.openDatabase(path.toString(), null, SQLiteDatabase.OPEN_READONLY).use { database ->
            assertThat(count(database, "kg_jit_cache")).isEqualTo(1)
            assertThat(count(database, "audit_logs")).isEqualTo(1)
        }
    }

    private fun foreignKeyViolations(database: SQLiteDatabase): List<String> =
        database.rawQuery("PRAGMA foreign_key_check", null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        listOf(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                            .joinToString(":"),
                    )
                }
            }
        }

    private companion object {
        const val AGENT_ID = "same-agent"
        const val SKILL_ID = "same-skill"
        const val SERVER_ID = "same-server"
        const val REMOTE_TOOL_NAME = "read_file"
        const val ROOT_ID = "same-root"
        const val FILE_ID = "same-file"
        const val SESSION_ID = "same-session"
        const val USER_MESSAGE_ID = "same-user"
        const val ASSISTANT_ID = "same-assistant"
        const val TOOL_RESULT_ID = "same-tool-result"
        const val TOOL_CALL_ID = "same-tool-call"
        const val ATTACHMENT_ID = "same-attachment"
        const val VECTOR_ID = "same-vector"
        const val SUMMARY_ID = "same-summary"
        const val TAG_ID = "same-tag"
        const val KG_NODE_A_ID = "same-kg-a"
        const val KG_NODE_B_ID = "same-kg-b"
        const val KG_EDGE_ID = "same-kg-edge"
        const val TASK_NODE_ID = "same-task"
        const val FILE_VERSION_ID = "same-version"
        const val AUDIT_ID = "same-audit"
        const val ARTIFACT_ID = "same-artifact"
        const val VECTOR_TASK_ID = "same-vector-task"
    }
}
