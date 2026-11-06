package com.promenar.infinity.data.local.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 应用打开当前库时注册的全部前进迁移；设备测试打开冻结的旧版本库时复用同一份列表。 */
val INFINITY_DATABASE_MIGRATIONS: Array<Migration>
    get() = arrayOf(
        MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_18, MIGRATION_17_18,
        MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22, MIGRATION_22_23, MIGRATION_23_24,
        MIGRATION_24_25, MIGRATION_25_26, MIGRATION_26_27, MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30,
        MIGRATION_30_31, MIGRATION_31_32, MIGRATION_32_33, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36_TIMELINE,
    )

/** 引用记录随来源删除；记忆向量仅是可重建缓存。 */
val MIGRATION_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `knowledge_citations` (
            `id` TEXT NOT NULL, `document_id` TEXT NOT NULL, `chunk_index` INTEGER,
            `vector_id` TEXT, `source_kind` TEXT NOT NULL, `session_id` TEXT,
            `message_id` TEXT, `ask_query` TEXT, `label` TEXT NOT NULL, `cited_at` INTEGER NOT NULL,
            PRIMARY KEY(`id`),
            FOREIGN KEY(`document_id`) REFERENCES `workspace_files`(`uuid`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(`message_id`) REFERENCES `messages`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_citations_document_id_cited_at` ON `knowledge_citations` (`document_id`, `cited_at`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_citations_session_id` ON `knowledge_citations` (`session_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_knowledge_citations_message_id` ON `knowledge_citations` (`message_id`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `memory_embedding_cache` (
            `memory_id` TEXT NOT NULL, `content_hash` TEXT NOT NULL, `model_id` TEXT NOT NULL,
            `embedding` BLOB NOT NULL, PRIMARY KEY(`memory_id`),
            FOREIGN KEY(`memory_id`) REFERENCES `long_term_memories`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent())
    }
}

/** 模型侧轮次上下文与生成提醒分别保存原文，历史消息保持为空。 */
val MIGRATION_31_32 = object : Migration(31, 32) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN turn_context TEXT")
        db.execSQL("ALTER TABLE messages ADD COLUMN request_instruction TEXT")
    }
}

/** 用户消息的引用批注以可空 JSON 文本保存。 */
val MIGRATION_30_31 = object : Migration(30, 31) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN quotes TEXT")
    }
}

/** 既有智能体保持新会话开启工具的默认行为。 */
val MIGRATION_29_30 = object : Migration(29, 30) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN tools_enabled_default INTEGER NOT NULL DEFAULT 1")
    }
}

/** 分支关系没有级联外键，删除主会话时由删除事务保留并解除子会话引用。 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN parent_session_id TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN branch_plan_json TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN worktree_directory TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN worktree_branch TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN worktree_base TEXT")
        db.execSQL("ALTER TABLE sessions ADD COLUMN parent_session_deleted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN session_auto_wake_count INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE sessions ADD COLUMN session_auto_processing_paused INTEGER NOT NULL DEFAULT 0")
    }
}

/** 历史会话不推断执行环境，由首次进入会话时的检查决定。 */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE sessions ADD COLUMN run_mode TEXT")
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE vectorization_tasks ADD COLUMN target_content_hash TEXT",
        )
        db.execSQL(
            "ALTER TABLE vectorization_tasks ADD COLUMN target_epoch INTEGER NOT NULL DEFAULT 0",
        )
        db.execSQL(
            """
            UPDATE vectorization_tasks
            SET target_content_hash = (
                    SELECT workspace_files.hash
                    FROM workspace_files
                    WHERE workspace_files.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
                      AND workspace_files.uuid = vectorization_tasks.doc_id
                ),
                target_epoch = COALESCE((
                    SELECT workspace_files.updated_at
                    FROM workspace_files
                    WHERE workspace_files.workspace_root_uuid = vectorization_tasks.workspace_root_uuid
                      AND workspace_files.uuid = vectorization_tasks.doc_id
                ), 0)
            WHERE type = 'document_reference'
              AND workspace_root_uuid IS NOT NULL
              AND doc_id IS NOT NULL
            """.trimIndent(),
        )
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'semi'")
        db.execSQL("ALTER TABLE tool_execution_ledger ADD COLUMN runtime_tool_id TEXT NOT NULL DEFAULT ''")
        db.execSQL("UPDATE tool_execution_ledger SET runtime_tool_id = tool_name")
        db.execSQL("ALTER TABLE tool_execution_ledger ADD COLUMN arguments_digest TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE tool_execution_ledger ADD COLUMN definition_digest TEXT NOT NULL DEFAULT ''")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS workspace_mutations (
                operation_id TEXT NOT NULL,
                workspace_root_uuid TEXT NOT NULL,
                operation_type TEXT NOT NULL,
                payload_version INTEGER NOT NULL,
                payload TEXT NOT NULL,
                payload_digest TEXT NOT NULL,
                state TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(operation_id)
            )""",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_workspace_mutations_workspace_root_uuid ON workspace_mutations(workspace_root_uuid)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_workspace_mutations_state ON workspace_mutations(state)",
        )
        changeSessionExecutionModeDefault(db)
    }

    private fun changeSessionExecutionModeDefault(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA legacy_alter_table=ON")
        try {
            db.execSQL(
                "ALTER TABLE sessions RENAME TO sessions_v2",
            )
            db.execSQL(
                """CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT NOT NULL,
                    agent_id TEXT NOT NULL,
                    title TEXT NOT NULL,
                    last_message TEXT,
                    time TEXT,
                    unread INTEGER NOT NULL,
                    model_id TEXT,
                    custom_prompt TEXT,
                    is_pinned INTEGER NOT NULL,
                    scroll_offset REAL,
                    draft TEXT,
                    execution_mode TEXT NOT NULL DEFAULT 'semi',
                    loop_status TEXT NOT NULL DEFAULT 'idle',
                    pending_intervention TEXT,
                    approval_request TEXT,
                    rag_options TEXT,
                    inference_params TEXT,
                    active_task TEXT,
                    stats TEXT,
                    options TEXT,
                    active_mcp_server_ids TEXT,
                    active_skill_ids TEXT,
                    workspace_path TEXT,
                    workspace_root_uuid TEXT,
                    active_task_tree_id TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    PRIMARY KEY(id)
                )""".trimIndent(),
            )
            db.execSQL(
                """INSERT INTO sessions(
                    id, agent_id, title, last_message, time, unread, model_id, custom_prompt,
                    is_pinned, scroll_offset, draft, execution_mode, loop_status,
                    pending_intervention, approval_request, rag_options, inference_params,
                    active_task, stats, options, active_mcp_server_ids, active_skill_ids,
                    workspace_path, workspace_root_uuid, active_task_tree_id, created_at, updated_at
                )
                SELECT
                    id, agent_id, title, last_message, time, unread, model_id, custom_prompt,
                    is_pinned, scroll_offset, draft, execution_mode, loop_status,
                    pending_intervention, approval_request, rag_options, inference_params,
                    active_task, stats, options, active_mcp_server_ids, active_skill_ids,
                    workspace_path, workspace_root_uuid, active_task_tree_id, created_at, updated_at
                FROM sessions_v2""".trimIndent(),
            )
            db.execSQL("DROP TABLE sessions_v2")
        } finally {
            db.execSQL("PRAGMA legacy_alter_table=OFF")
        }
    }
}

/** v3 已发布，Agent 的 Skill/MCP 默认选择必须通过独立 v4 迁移追加。 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN skill_ids TEXT NOT NULL DEFAULT '[]'")
        db.execSQL("ALTER TABLE agents ADD COLUMN mcp_server_ids TEXT NOT NULL DEFAULT '[]'")
    }
}

/** MCP discovery 是可重建派生缓存，v5 仅追加该表及 server_id 索引。 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS mcp_tool_snapshots (
                server_id TEXT NOT NULL,
                remote_tool_name TEXT NOT NULL,
                description TEXT NOT NULL,
                input_schema_json TEXT NOT NULL,
                synced_at INTEGER NOT NULL,
                PRIMARY KEY(server_id, remote_tool_name),
                FOREIGN KEY(server_id) REFERENCES mcp_servers(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_mcp_tool_snapshots_server_id ON mcp_tool_snapshots(server_id)",
        )
    }
}

/** v5 提升到公开版之后的版本空间，并增加旧附件 JSON 的无损保留列。 */
val MIGRATION_5_18 = object : Migration(5, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE messages ADD COLUMN legacy_attachments TEXT")
    }
}

/** SSH 主机配置独立持久化，认证材料只由 SecretStore 保存。 */
val MIGRATION_20_21 = object : Migration(20, 21) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS ssh_hosts (
                id TEXT NOT NULL,
                alias TEXT NOT NULL,
                hostname TEXT NOT NULL,
                port INTEGER NOT NULL DEFAULT 22,
                username TEXT NOT NULL,
                auth_type TEXT NOT NULL DEFAULT 'KEY',
                jump_alias TEXT,
                trusted_key_type TEXT,
                trusted_fingerprint TEXT,
                keep_terminal INTEGER NOT NULL DEFAULT 0,
                local_forward_port INTEGER,
                forward_host TEXT NOT NULL DEFAULT '127.0.0.1',
                forward_port INTEGER,
                PRIMARY KEY(id)
            )""".trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ssh_hosts_alias ON ssh_hosts(alias)")
    }
}

/**
 * 自选工作区登记、会话与 Agent 的工作区绑定、Agent 用户规则及其历史版本。
 * 新列均可空或带默认值，既有会话继续使用私有工作区，既有 Agent 规则为空。
 */
val MIGRATION_21_22 = object : Migration(21, 22) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `workspaces` (
                `id` TEXT NOT NULL,
                `kind` TEXT NOT NULL,
                `display_name` TEXT NOT NULL,
                `location` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )""".trimIndent(),
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workspaces_kind_location` ON `workspaces` (`kind`, `location`)")
        db.execSQL("ALTER TABLE `agents` ADD COLUMN `default_workspace_id` TEXT")
        db.execSQL("ALTER TABLE `agents` ADD COLUMN `user_rules` TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE `sessions` ADD COLUMN `workspace_id` TEXT")
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS `agent_rule_revisions` (
                `id` TEXT NOT NULL,
                `agent_id` TEXT NOT NULL,
                `content` TEXT NOT NULL,
                `content_sha256` TEXT NOT NULL,
                `source` TEXT NOT NULL,
                `created_at` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )""".trimIndent(),
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_agent_rule_revisions_agent_id_created_at` " +
                "ON `agent_rule_revisions` (`agent_id`, `created_at`)",
        )
    }
}

/**
 * 会话图谱抽取：证据记录所属轮次的助手消息 id，并为按助手筛选会话加索引。
 * 既有证据的 message_id 为空，可见性不变。
 */
val MIGRATION_22_23 = object : Migration(22, 23) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `kg_evidence` ADD COLUMN `message_id` TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_kg_evidence_message_id` ON `kg_evidence` (`message_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_sessions_agent_id` ON `sessions` (`agent_id`)")
    }
}

/**
 * 推理强度与参数方案：助手可保存完整采样参数与默认推理强度，新增按模型保存的参数方案表。
 * 旧数据恢复为模型默认：助手的温度、Top P、最大输出清空；会话里由旧档位写入的采样参数与思考档位一并清空，
 * 流式超时、自动摘要与保留轮数等非采样设置保持不变。
 */
val MIGRATION_23_24 = object : Migration(23, 24) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "top_k INTEGER", "frequency_penalty REAL", "presence_penalty REAL",
            "repetition_penalty REAL", "reasoning_effort TEXT",
        ).forEach { column -> db.execSQL("ALTER TABLE `agents` ADD COLUMN $column") }
        db.execSQL("UPDATE `agents` SET `temperature` = NULL, `top_p` = NULL, `max_tokens` = NULL")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `parameter_schemes` (`id` TEXT NOT NULL, `model_key` TEXT NOT NULL, " +
                "`name` TEXT NOT NULL, `params` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_parameter_schemes_model_key` ON `parameter_schemes` (`model_key`)")
        resetSessionSamplingParams(db)
    }
}

/** 旧会话参数中会被视为采样设置的键。 */
internal val LEGACY_SESSION_SAMPLING_KEYS = setOf(
    "temperature", "topP", "maxTokens", "frequencyPenalty", "presencePenalty",
    "topK", "repetitionPenalty", "thinkingLevel",
)

/** 无法解析的 JSON 保持原样，读取时按默认值处理。 */
internal fun resetSessionSamplingJson(raw: String): String? {
    val parsed = runCatching { Json.parseToJsonElement(raw) }.getOrNull()
    val obj = parsed as? JsonObject ?: return null
    if (obj.keys.none { it in LEGACY_SESSION_SAMPLING_KEYS }) return null
    return JsonObject(obj - LEGACY_SESSION_SAMPLING_KEYS).toString()
}

private fun resetSessionSamplingParams(db: SupportSQLiteDatabase) {
    val updates = mutableListOf<Pair<String, String>>()
    db.query("SELECT `id`, `inference_params` FROM `sessions` WHERE `inference_params` IS NOT NULL").use { cursor ->
        while (cursor.moveToNext()) {
            resetSessionSamplingJson(cursor.getString(1))?.let { updates += cursor.getString(0) to it }
        }
    }
    updates.forEach { (id, params) ->
        db.execSQL("UPDATE `sessions` SET `inference_params` = ? WHERE `id` = ?", arrayOf<Any>(params, id))
    }
}

/**
 * 公开 v0.1 实际发布库为 v17。该迁移保留其业务数据，并补齐当前工具链、工作区与消息树契约。
 */
val MIGRATION_17_18 = object : Migration(17, 18) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("PRAGMA legacy_alter_table=ON")
        try {
            preparePublishedWorkspaceRootMap(db)
            stageAndDropPublishedSessionGraph(db)
            migratePublishedAgents(db)
            migratePublishedSessions(db)
            migratePublishedMessages(db)
            recreatePublishedSessionChildren(db)
            migratePublishedWorkspaceFiles(db)
            migratePublishedVectorizationTasks(db)
            migratePublishedVectorFts(db)
            createPostPublishedTables(db)
        } finally {
            db.execSQL("PRAGMA legacy_alter_table=OFF")
        }
    }
}

val MIGRATION_19_20 = object : Migration(19, 20) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS external_mounts (
                id TEXT NOT NULL,
                alias TEXT NOT NULL,
                source_type TEXT NOT NULL,
                source_id TEXT NOT NULL,
                scope_key TEXT NOT NULL,
                read_only INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                unavailable_reason TEXT,
                PRIMARY KEY(id)
            )""".trimIndent(),
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS index_external_mounts_scope_key_alias " +
                "ON external_mounts(scope_key, alias)",
        )
        db.execSQL(
            """CREATE TABLE IF NOT EXISTS external_snapshots (
                mount_id TEXT NOT NULL,
                source_path TEXT NOT NULL,
                workspace_root_uuid TEXT NOT NULL,
                file_uuid TEXT NOT NULL,
                source_modified INTEGER NOT NULL,
                content_hash TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                PRIMARY KEY(mount_id, source_path, workspace_root_uuid),
                FOREIGN KEY(mount_id) REFERENCES external_mounts(id) ON UPDATE NO ACTION ON DELETE CASCADE
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_external_snapshots_mount_id ON external_snapshots(mount_id)")
    }
}

private fun preparePublishedWorkspaceRootMap(db: SupportSQLiteDatabase) {
    val sharedSessionPath = db.query(
        """SELECT workspace_path FROM sessions
            WHERE workspace_path IS NOT NULL
            GROUP BY workspace_path HAVING COUNT(*) > 1 LIMIT 1""".trimIndent(),
    ).use { it.moveToFirst() }
    if (sharedSessionPath) {
        throw IllegalStateException("公开 v17 存在共享工作区路径，已中止迁移以保全原库")
    }
    val ambiguousCanonicalRoot = db.query(
        """SELECT physical_root_path FROM workspace_files
            WHERE parent_uuid IS NULL AND is_directory = 1 AND materialized_path = '/'
            GROUP BY physical_root_path HAVING COUNT(*) > 1 LIMIT 1""".trimIndent(),
    ).use { it.moveToFirst() }
    if (ambiguousCanonicalRoot) {
        throw IllegalStateException("公开 v17 工作区存在重复规范根，已中止迁移以保全原库")
    }
    db.execSQL(
        """CREATE TEMP TABLE migration17_workspace_roots (
            physical_root_path TEXT NOT NULL PRIMARY KEY,
            workspace_root_uuid TEXT NOT NULL,
            had_canonical_root INTEGER NOT NULL
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO migration17_workspace_roots
            SELECT physical_root_path, MIN(uuid), 1
            FROM workspace_files
            WHERE parent_uuid IS NULL AND is_directory = 1 AND materialized_path = '/'
            GROUP BY physical_root_path""".trimIndent(),
    )
    db.execSQL(
        """INSERT OR IGNORE INTO migration17_workspace_roots
            SELECT workspace_path, 'legacy-root-' || lower(hex(randomblob(16))), 0
            FROM sessions WHERE workspace_path IS NOT NULL
            GROUP BY workspace_path""".trimIndent(),
    )
    db.execSQL(
        """INSERT OR IGNORE INTO migration17_workspace_roots
            SELECT physical_root_path, 'legacy-root-' || lower(hex(randomblob(16))), 0
            FROM workspace_files GROUP BY physical_root_path""".trimIndent(),
    )
}

private fun stageAndDropPublishedSessionGraph(db: SupportSQLiteDatabase) {
    listOf(
        "agents",
        "sessions",
        "messages",
        "attachments",
        "vectors",
        "context_summaries",
        "vectorization_tasks",
        "artifacts",
        "task_nodes",
    ).forEach { table ->
        db.execSQL("CREATE TEMP TABLE migration17_$table AS SELECT * FROM $table")
    }
    listOf(
        "vectors_fts",
        "attachments",
        "vectorization_tasks",
        "messages",
        "vectors",
        "context_summaries",
        "artifacts",
        "task_nodes",
        "sessions",
        "agents",
    ).forEach { table -> db.execSQL("DROP TABLE $table") }
}

private fun migratePublishedAgents(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS agents (
            id TEXT NOT NULL,
            name TEXT NOT NULL,
            description TEXT NOT NULL,
            name_customized INTEGER NOT NULL,
            description_customized INTEGER NOT NULL,
            system_prompt TEXT NOT NULL,
            model TEXT NOT NULL,
            icon TEXT NOT NULL,
            color TEXT NOT NULL,
            avatar_path TEXT,
            is_pinned INTEGER NOT NULL,
            created_at INTEGER NOT NULL,
            temperature REAL,
            top_p REAL,
            max_tokens INTEGER,
            rag_config TEXT,
            retrieval_config TEXT,
            use_inherited_config INTEGER NOT NULL,
            execution_mode TEXT NOT NULL DEFAULT 'semi',
            skill_ids TEXT NOT NULL DEFAULT '[]',
            mcp_server_ids TEXT NOT NULL DEFAULT '[]',
            PRIMARY KEY(id)
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO agents(
            id, name, description, name_customized, description_customized, system_prompt,
            model, icon, color, avatar_path, is_pinned, created_at, temperature, top_p,
            max_tokens, rag_config, retrieval_config, use_inherited_config, execution_mode,
            skill_ids, mcp_server_ids
        )
        SELECT id, name, description, 1, 1, system_prompt, model, icon, color, avatar_path,
            is_pinned, created_at, temperature, top_p, max_tokens, rag_config,
            retrieval_config, use_inherited_config, 'semi', '[]', '[]'
        FROM migration17_agents""".trimIndent(),
    )
}

private fun migratePublishedSessions(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS sessions (
            id TEXT NOT NULL,
            agent_id TEXT NOT NULL,
            title TEXT NOT NULL,
            last_message TEXT,
            time TEXT,
            unread INTEGER NOT NULL,
            model_id TEXT,
            custom_prompt TEXT,
            is_pinned INTEGER NOT NULL,
            scroll_offset REAL,
            draft TEXT,
            execution_mode TEXT NOT NULL DEFAULT 'semi',
            loop_status TEXT NOT NULL DEFAULT 'idle',
            pending_intervention TEXT,
            approval_request TEXT,
            rag_options TEXT,
            inference_params TEXT,
            active_task TEXT,
            stats TEXT,
            options TEXT,
            active_mcp_server_ids TEXT,
            active_skill_ids TEXT,
            workspace_path TEXT,
            workspace_root_uuid TEXT,
            active_task_tree_id TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(id)
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO sessions(
            id, agent_id, title, last_message, time, unread, model_id, custom_prompt,
            is_pinned, scroll_offset, draft, execution_mode, loop_status, pending_intervention,
            approval_request, rag_options, inference_params, active_task, stats, options,
            active_mcp_server_ids, active_skill_ids, workspace_path, workspace_root_uuid,
            active_task_tree_id, created_at, updated_at
        )
        SELECT id, agent_id, title, last_message, time, unread, model_id, custom_prompt,
            is_pinned, scroll_offset, draft, execution_mode, loop_status, pending_intervention,
            approval_request, rag_options, inference_params, active_task, stats, options,
            active_mcp_server_ids, active_skill_ids, workspace_path, workspace_root_uuid,
            active_task_tree_id, created_at, updated_at
        FROM migration17_sessions""".trimIndent(),
    )
}

private fun migratePublishedMessages(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS messages (
            id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            role TEXT NOT NULL,
            content TEXT NOT NULL,
            model_id TEXT,
            status TEXT,
            reasoning TEXT,
            thought_signature TEXT,
            images TEXT,
            tokens TEXT,
            citations TEXT,
            rag_references TEXT,
            kg_paths TEXT,
            rag_progress TEXT,
            rag_metadata TEXT,
            rag_references_loading INTEGER NOT NULL DEFAULT 0,
            execution_steps TEXT,
            tool_calls TEXT,
            pending_approval_tool_ids TEXT,
            tool_call_id TEXT,
            parent_message_id TEXT,
            name TEXT,
            planning_task TEXT,
            is_archived INTEGER NOT NULL DEFAULT 0,
            vectorization_status TEXT,
            layout_height REAL,
            tool_results TEXT,
            files TEXT,
            legacy_attachments TEXT,
            user_images TEXT,
            is_error INTEGER NOT NULL DEFAULT 0,
            error_message TEXT,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(id),
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(session_id, parent_message_id) REFERENCES messages(session_id, id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO messages(
            id, session_id, role, content, model_id, status, reasoning, thought_signature,
            images, tokens, citations, rag_references, kg_paths, rag_progress, rag_metadata,
            rag_references_loading, execution_steps, tool_calls, pending_approval_tool_ids,
            tool_call_id, parent_message_id, name, planning_task, is_archived,
            vectorization_status, layout_height, tool_results, files, legacy_attachments,
            user_images, is_error,
            error_message, created_at
        )
        SELECT id, session_id, role, content, model_id, status, reasoning, thought_signature,
            images, tokens, citations, rag_references, NULL, rag_progress, rag_metadata,
            rag_references_loading, execution_steps, tool_calls, pending_approval_tool_ids,
            tool_call_id, NULL, name, planning_task, is_archived, vectorization_status,
            layout_height, tool_results, files, attachments, user_images, is_error,
            error_message, created_at
        FROM migration17_messages""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_id ON messages(session_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_id_created_at ON messages(session_id, created_at)")
    db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_messages_session_id_id ON messages(session_id, id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_messages_session_id_parent_message_id ON messages(session_id, parent_message_id)")
}

private fun recreatePublishedSessionChildren(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS attachments (
            id TEXT NOT NULL,
            message_id TEXT NOT NULL,
            type TEXT NOT NULL,
            uri TEXT NOT NULL,
            local_uri TEXT,
            PRIMARY KEY(id),
            FOREIGN KEY(message_id) REFERENCES messages(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO attachments(id, message_id, type, uri, local_uri)
            SELECT id, message_id, type, uri, local_uri FROM migration17_attachments""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_attachments_message_id ON attachments(message_id)")

    db.execSQL(
        """CREATE TABLE IF NOT EXISTS vectors (
            id TEXT NOT NULL,
            doc_id TEXT,
            session_id TEXT,
            content TEXT NOT NULL,
            embedding BLOB NOT NULL,
            metadata TEXT,
            start_message_id TEXT,
            end_message_id TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER,
            stale INTEGER NOT NULL DEFAULT 0,
            version INTEGER NOT NULL DEFAULT 1,
            file_uuid TEXT,
            PRIMARY KEY(id),
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO vectors(
            id, doc_id, session_id, content, embedding, metadata, start_message_id,
            end_message_id, created_at, updated_at, stale, version, file_uuid
        ) SELECT id, doc_id, session_id, content, embedding, metadata, start_message_id,
            end_message_id, created_at, updated_at, stale, version, file_uuid
        FROM migration17_vectors""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_vectors_doc_id ON vectors(doc_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_vectors_session_id ON vectors(session_id)")

    db.execSQL(
        """CREATE TABLE IF NOT EXISTS context_summaries (
            id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            start_message_id TEXT NOT NULL,
            end_message_id TEXT NOT NULL,
            summary_content TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            token_usage INTEGER,
            PRIMARY KEY(id),
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO context_summaries(
            id, session_id, start_message_id, end_message_id, summary_content, created_at, token_usage
        ) SELECT id, session_id, start_message_id, end_message_id, summary_content, created_at, token_usage
        FROM migration17_context_summaries""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_context_summaries_session_id ON context_summaries(session_id)")

    db.execSQL(
        """CREATE TABLE IF NOT EXISTS artifacts (
            id TEXT NOT NULL,
            type TEXT NOT NULL,
            title TEXT NOT NULL,
            content TEXT NOT NULL,
            preview_image TEXT,
            session_id TEXT NOT NULL,
            message_id TEXT NOT NULL,
            workspace_path TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            tags TEXT,
            PRIMARY KEY(id),
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO artifacts(
            id, type, title, content, preview_image, session_id, message_id,
            workspace_path, created_at, updated_at, tags
        ) SELECT id, type, title, content, preview_image, session_id, message_id,
            workspace_path, created_at, updated_at, tags FROM migration17_artifacts""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_session_id ON artifacts(session_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_type ON artifacts(type)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_artifacts_created_at ON artifacts(created_at)")

    db.execSQL(
        """CREATE TABLE IF NOT EXISTS task_nodes (
            id TEXT NOT NULL,
            session_id TEXT NOT NULL,
            parent_id TEXT,
            sort_order INTEGER NOT NULL DEFAULT 0,
            title TEXT NOT NULL,
            description TEXT NOT NULL DEFAULT '',
            status TEXT NOT NULL DEFAULT 'pending',
            note TEXT,
            artifact_file_uuids TEXT,
            is_collapsed INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(id),
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO task_nodes(
            id, session_id, parent_id, sort_order, title, description, status, note,
            artifact_file_uuids, is_collapsed, created_at, updated_at
        ) SELECT id, session_id, parent_id, sort_order, title, description, status, note,
            artifact_file_uuids, is_collapsed, created_at, updated_at FROM migration17_task_nodes""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_task_nodes_session_id ON task_nodes(session_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_task_nodes_parent_id ON task_nodes(parent_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_task_nodes_status ON task_nodes(status)")
}

private fun migratePublishedWorkspaceFiles(db: SupportSQLiteDatabase) {
    db.execSQL("ALTER TABLE workspace_files RENAME TO workspace_files_v17")
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS workspace_files (
            uuid TEXT NOT NULL,
            workspace_root_uuid TEXT NOT NULL,
            parent_uuid TEXT,
            name TEXT NOT NULL,
            hash TEXT NOT NULL,
            mime_type TEXT,
            size_bytes INTEGER NOT NULL,
            is_directory INTEGER NOT NULL,
            physical_root_path TEXT NOT NULL,
            materialized_path TEXT NOT NULL,
            vectorized_at INTEGER,
            vector_version INTEGER NOT NULL,
            kg_extracted_at INTEGER,
            kg_version INTEGER NOT NULL,
            last_write_session_id TEXT,
            locked_by_session_id TEXT,
            lock_expires_at INTEGER,
            in_recycle_bin INTEGER NOT NULL,
            recycled_at INTEGER,
            original_parent_uuid TEXT,
            original_materialized_path TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(uuid)
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO workspace_files(
            uuid, workspace_root_uuid, parent_uuid, name, hash, mime_type, size_bytes,
            is_directory, physical_root_path, materialized_path, vectorized_at,
            vector_version, kg_extracted_at, kg_version, last_write_session_id,
            locked_by_session_id, lock_expires_at, in_recycle_bin, recycled_at,
            original_parent_uuid, original_materialized_path, created_at, updated_at
        )
        SELECT file.uuid, root.workspace_root_uuid,
            CASE
                WHEN file.uuid = root.workspace_root_uuid THEN NULL
                WHEN file.parent_uuid IS NULL THEN root.workspace_root_uuid
                ELSE file.parent_uuid
            END,
            file.name, file.hash, file.mime_type, file.size_bytes,
            file.is_directory, file.physical_root_path, file.materialized_path,
            file.vectorized_at, file.vector_version, file.kg_extracted_at, file.kg_version,
            file.last_write_session_id, file.locked_by_session_id, file.lock_expires_at,
            file.in_recycle_bin, file.recycled_at, file.original_parent_uuid,
            file.original_materialized_path, file.created_at, file.updated_at
        FROM workspace_files_v17 AS file
        JOIN migration17_workspace_roots AS root
          ON root.physical_root_path = file.physical_root_path""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO workspace_files(
            uuid, workspace_root_uuid, parent_uuid, name, hash, mime_type, size_bytes,
            is_directory, physical_root_path, materialized_path, vectorized_at,
            vector_version, kg_extracted_at, kg_version, last_write_session_id,
            locked_by_session_id, lock_expires_at, in_recycle_bin, recycled_at,
            original_parent_uuid, original_materialized_path, created_at, updated_at
        )
        SELECT root.workspace_root_uuid, root.workspace_root_uuid, NULL,
            root.physical_root_path, '', NULL, 0, 1, root.physical_root_path, '/',
            NULL, 1, NULL, 1, NULL, NULL, NULL, 0, NULL, NULL, NULL,
            COALESCE(
                (SELECT MIN(created_at) FROM workspace_files_v17 AS file
                 WHERE file.physical_root_path = root.physical_root_path),
                (SELECT MIN(created_at) FROM migration17_sessions AS session
                 WHERE session.workspace_path = root.physical_root_path),
                0
            ),
            COALESCE(
                (SELECT MAX(updated_at) FROM workspace_files_v17 AS file
                 WHERE file.physical_root_path = root.physical_root_path),
                (SELECT MAX(updated_at) FROM migration17_sessions AS session
                 WHERE session.workspace_path = root.physical_root_path),
                0
            )
        FROM migration17_workspace_roots AS root
        WHERE root.had_canonical_root = 0""".trimIndent(),
    )
    db.execSQL("DROP TABLE workspace_files_v17")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_files_parent_uuid ON workspace_files(parent_uuid)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_files_materialized_path ON workspace_files(materialized_path)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_files_hash ON workspace_files(hash)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_files_is_directory ON workspace_files(is_directory)")
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_workspace_files_in_recycle_bin_physical_root_path_recycled_at " +
            "ON workspace_files(in_recycle_bin, physical_root_path, recycled_at)",
    )
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_workspace_files_workspace_root_uuid_uuid " +
            "ON workspace_files(workspace_root_uuid, uuid)",
    )
    db.execSQL(
        """UPDATE sessions SET workspace_root_uuid = (
            SELECT root.workspace_root_uuid FROM migration17_workspace_roots AS root
            WHERE root.physical_root_path = sessions.workspace_path
        ) WHERE workspace_path IS NOT NULL""".trimIndent(),
    )
}

private fun migratePublishedVectorizationTasks(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS vectorization_tasks (
            id TEXT NOT NULL,
            type TEXT NOT NULL,
            status TEXT NOT NULL,
            doc_id TEXT,
            doc_title TEXT,
            workspace_root_uuid TEXT,
            session_id TEXT,
            user_content TEXT,
            ai_content TEXT,
            user_message_id TEXT,
            assistant_message_id TEXT,
            last_chunk_index INTEGER NOT NULL,
            total_chunks INTEGER,
            progress REAL NOT NULL,
            error TEXT,
            kg_strategy TEXT,
            skip_vectorization INTEGER NOT NULL,
            sub_status TEXT,
            source_mime_type TEXT,
            content_truncated INTEGER NOT NULL,
            target_content_hash TEXT,
            target_epoch INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(id),
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(workspace_root_uuid, doc_id) REFERENCES workspace_files(workspace_root_uuid, uuid) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL(
        """INSERT INTO vectorization_tasks(
            id, type, status, doc_id, doc_title, workspace_root_uuid, session_id,
            user_content, ai_content, user_message_id, assistant_message_id,
            last_chunk_index, total_chunks, progress, error, kg_strategy,
            skip_vectorization, sub_status, source_mime_type, content_truncated,
            target_content_hash, target_epoch, created_at, updated_at
        )
        SELECT task.id, task.type,
            CASE WHEN task.status IN (
                'pending', 'processing', 'extracting_source', 'chunking',
                'vectorizing', 'saving', 'extracting', 'interrupted'
            ) AND (
                file.uuid IS NULL OR EXISTS(
                    SELECT 1 FROM migration17_vectorization_tasks AS newer
                    WHERE newer.doc_id = task.doc_id AND newer.type = task.type
                      AND (newer.updated_at > task.updated_at OR
                           (newer.updated_at = task.updated_at AND newer.id < task.id))
                )
            ) THEN 'failed' ELSE task.status END,
            task.doc_id, task.doc_title,
            CASE WHEN file.uuid IS NOT NULL AND NOT EXISTS(
                SELECT 1 FROM migration17_vectorization_tasks AS newer
                WHERE newer.doc_id = task.doc_id AND newer.type = task.type
                  AND (newer.updated_at > task.updated_at OR
                       (newer.updated_at = task.updated_at AND newer.id < task.id))
            ) THEN file.workspace_root_uuid ELSE NULL END,
            task.session_id, task.user_content, task.ai_content, task.user_message_id,
            task.assistant_message_id, task.last_chunk_index, task.total_chunks,
            task.progress,
            CASE WHEN task.status IN (
                'pending', 'processing', 'extracting_source', 'chunking',
                'vectorizing', 'saving', 'extracting', 'interrupted'
            ) AND (
                file.uuid IS NULL OR EXISTS(
                    SELECT 1 FROM migration17_vectorization_tasks AS newer
                    WHERE newer.doc_id = task.doc_id AND newer.type = task.type
                      AND (newer.updated_at > task.updated_at OR
                           (newer.updated_at = task.updated_at AND newer.id < task.id))
                )
            ) THEN COALESCE(task.error, 'v17 迁移已终止重复或失效任务') ELSE task.error END,
            NULL, 0, NULL, NULL, 0,
            file.hash, COALESCE(file.updated_at, 0), task.created_at, task.updated_at
        FROM migration17_vectorization_tasks AS task
        LEFT JOIN workspace_files AS file ON file.uuid = task.doc_id""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_vectorization_tasks_status ON vectorization_tasks(status)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_vectorization_tasks_doc_id ON vectorization_tasks(doc_id)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_vectorization_tasks_session_id ON vectorization_tasks(session_id)")
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_vectorization_tasks_workspace_root_uuid_doc_id " +
            "ON vectorization_tasks(workspace_root_uuid, doc_id)",
    )
    db.execSQL(
        "CREATE UNIQUE INDEX IF NOT EXISTS index_vectorization_tasks_workspace_root_uuid_doc_id_type " +
            "ON vectorization_tasks(workspace_root_uuid, doc_id, type)",
    )
}

private fun migratePublishedVectorFts(db: SupportSQLiteDatabase) {
    db.execSQL("DROP TABLE IF EXISTS vectors_fts")
    db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS vectors_fts USING FTS4(content TEXT NOT NULL, content=`vectors`)")
    db.execSQL("INSERT INTO vectors_fts(vectors_fts) VALUES('rebuild')")
}

private fun createPostPublishedTables(db: SupportSQLiteDatabase) {
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS mcp_tool_snapshots (
            server_id TEXT NOT NULL,
            remote_tool_name TEXT NOT NULL,
            description TEXT NOT NULL,
            input_schema_json TEXT NOT NULL,
            synced_at INTEGER NOT NULL,
            PRIMARY KEY(server_id, remote_tool_name),
            FOREIGN KEY(server_id) REFERENCES mcp_servers(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_mcp_tool_snapshots_server_id ON mcp_tool_snapshots(server_id)")
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS tool_execution_ledger (
            session_id TEXT NOT NULL,
            assistant_message_id TEXT NOT NULL,
            tool_call_id TEXT NOT NULL,
            tool_name TEXT NOT NULL,
            runtime_tool_id TEXT NOT NULL DEFAULT '',
            arguments_digest TEXT NOT NULL DEFAULT '',
            definition_digest TEXT NOT NULL DEFAULT '',
            requires_approval INTEGER NOT NULL,
            status TEXT NOT NULL,
            result_message_id TEXT,
            error TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(session_id, assistant_message_id, tool_call_id),
            FOREIGN KEY(session_id) REFERENCES sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE,
            FOREIGN KEY(session_id, assistant_message_id) REFERENCES messages(session_id, id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_tool_execution_ledger_session_id ON tool_execution_ledger(session_id)")
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_tool_execution_ledger_session_id_assistant_message_id " +
            "ON tool_execution_ledger(session_id, assistant_message_id)",
    )
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS file_versions (
            id TEXT NOT NULL,
            file_uuid TEXT NOT NULL,
            workspace_root_uuid TEXT NOT NULL,
            hash TEXT NOT NULL,
            content_path TEXT NOT NULL,
            created_by_session_id TEXT,
            created_at INTEGER NOT NULL,
            PRIMARY KEY(id)
        )""".trimIndent(),
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_file_versions_file_uuid ON file_versions(file_uuid)")
    db.execSQL("CREATE INDEX IF NOT EXISTS index_file_versions_workspace_root_uuid ON file_versions(workspace_root_uuid)")
    db.execSQL(
        """CREATE TABLE IF NOT EXISTS workspace_mutations (
            operation_id TEXT NOT NULL,
            workspace_root_uuid TEXT NOT NULL,
            operation_type TEXT NOT NULL,
            payload_version INTEGER NOT NULL,
            payload TEXT NOT NULL,
            payload_digest TEXT NOT NULL,
            state TEXT NOT NULL,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            PRIMARY KEY(operation_id)
        )""".trimIndent(),
    )
    db.execSQL(
        "CREATE INDEX IF NOT EXISTS index_workspace_mutations_workspace_root_uuid " +
            "ON workspace_mutations(workspace_root_uuid)",
    )
    db.execSQL("CREATE INDEX IF NOT EXISTS index_workspace_mutations_state ON workspace_mutations(state)")
}
