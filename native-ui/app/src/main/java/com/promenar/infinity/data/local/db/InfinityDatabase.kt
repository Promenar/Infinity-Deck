package com.promenar.infinity.data.local.db

import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.promenar.infinity.data.local.db.dao.AgentDao
import com.promenar.infinity.data.local.db.dao.AgentRuleRevisionDao
import com.promenar.infinity.data.local.db.dao.ArtifactDao
import com.promenar.infinity.data.local.db.dao.AttachmentDao
import com.promenar.infinity.data.local.db.dao.AuditLogDao
import com.promenar.infinity.data.local.db.dao.ContextSummaryDao
import com.promenar.infinity.data.local.db.dao.DocumentTagDao
import com.promenar.infinity.data.local.db.dao.ExternalMountDao
import com.promenar.infinity.data.local.db.dao.FileEntryDao
import com.promenar.infinity.data.local.db.dao.FileVersionDao
import com.promenar.infinity.data.local.db.dao.KgEdgeDao
import com.promenar.infinity.data.local.db.dao.KgJitCacheDao
import com.promenar.infinity.data.local.db.dao.KgNodeDao
import com.promenar.infinity.data.local.db.dao.KgEvidenceDao
import com.promenar.infinity.data.local.db.dao.MessageDao
import com.promenar.infinity.data.local.db.dao.ParameterSchemeDao
import com.promenar.infinity.data.local.db.dao.SessionDao
import com.promenar.infinity.data.local.db.dao.SessionDeletionDao
import com.promenar.infinity.data.local.db.dao.SkillDao
import com.promenar.infinity.data.local.db.dao.SshHostDao
import com.promenar.infinity.data.local.db.dao.TagDao
import com.promenar.infinity.data.local.db.dao.TaskNodeDao
import com.promenar.infinity.data.local.db.dao.ToolExecutionLedgerDao
import com.promenar.infinity.data.local.db.dao.VectorDao
import com.promenar.infinity.data.local.db.dao.VectorizationTaskDao
import com.promenar.infinity.data.local.db.dao.WorkspaceSeqDao
import com.promenar.infinity.data.local.db.dao.WorkspaceMutationDao
import com.promenar.infinity.data.local.db.dao.WorkspaceDao
import com.promenar.infinity.data.local.db.entity.AgentEntity
import com.promenar.infinity.data.local.db.entity.AgentRuleRevisionEntity
import com.promenar.infinity.data.local.db.entity.ParameterSchemeEntity
import com.promenar.infinity.data.local.db.entity.ArtifactEntity
import com.promenar.infinity.data.local.db.entity.AttachmentEntity
import com.promenar.infinity.data.local.db.entity.AuditLogEntity
import com.promenar.infinity.data.local.db.entity.ContextSummaryEntity
import com.promenar.infinity.data.local.db.entity.CustomSkillEntity
import com.promenar.infinity.data.local.db.entity.DocumentTagEntity
import com.promenar.infinity.data.local.db.entity.ExternalMountEntity
import com.promenar.infinity.data.local.db.entity.ExternalSnapshotEntity
import com.promenar.infinity.data.local.db.entity.FileEntry
import com.promenar.infinity.data.local.db.entity.FileVersionEntity
import com.promenar.infinity.data.local.db.entity.KgEdgeEntity
import com.promenar.infinity.data.local.db.entity.KgJitCacheEntity
import com.promenar.infinity.data.local.db.entity.KgNodeEntity
import com.promenar.infinity.data.local.db.entity.KgEvidenceEntity
import com.promenar.infinity.data.local.db.entity.KgJitCacheSourceEntity
import com.promenar.infinity.data.local.db.entity.McpServerEntity
import com.promenar.infinity.data.local.db.entity.McpToolSnapshotEntity
import com.promenar.infinity.data.local.db.entity.MessageEntity
import com.promenar.infinity.data.local.db.entity.SessionEntity
import com.promenar.infinity.data.local.db.entity.SshHostEntity
import com.promenar.infinity.data.local.db.entity.TagEntity
import com.promenar.infinity.data.local.db.entity.TaskNodeEntity
import com.promenar.infinity.data.local.db.entity.ToolExecutionLedgerEntity
import com.promenar.infinity.data.local.db.entity.VectorEntity
import com.promenar.infinity.data.local.db.entity.VectorFtsEntity
import com.promenar.infinity.data.local.db.entity.VectorizationTaskEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceSeqEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceMutationEntity
import com.promenar.infinity.data.local.db.entity.WorkspaceEntity

@Database(
    entities = [
        com.promenar.infinity.data.local.db.entity.ScheduledTaskEntity::class,
        AgentEntity::class,
        com.promenar.infinity.data.memory.LongTermMemoryEntity::class,
        com.promenar.infinity.data.library.ask.KnowledgeCitationEntity::class,
        com.promenar.infinity.data.library.ask.MemoryEmbeddingEntity::class,
        SessionEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        VectorEntity::class,
        VectorFtsEntity::class,
        ContextSummaryEntity::class,
        TagEntity::class,
        DocumentTagEntity::class,
        KgNodeEntity::class,
        KgEdgeEntity::class,
        com.promenar.infinity.data.local.db.entity.KgCollectionEntity::class,
        com.promenar.infinity.data.local.db.entity.KgFolderSettingEntity::class,
        com.promenar.infinity.data.local.db.entity.KgCollectionMemberEntity::class,
        KgJitCacheEntity::class,
        KgEvidenceEntity::class,
        KgJitCacheSourceEntity::class,
        VectorizationTaskEntity::class,
        AuditLogEntity::class,
        ArtifactEntity::class,
        CustomSkillEntity::class,
        McpServerEntity::class,
        McpToolSnapshotEntity::class,
        FileEntry::class,
        WorkspaceSeqEntity::class,
        TaskNodeEntity::class,
        ToolExecutionLedgerEntity::class,
        FileVersionEntity::class,
        WorkspaceMutationEntity::class,
        ExternalMountEntity::class,
        ExternalSnapshotEntity::class,
        SshHostEntity::class,
        WorkspaceEntity::class,
        AgentRuleRevisionEntity::class,
        ParameterSchemeEntity::class,
        com.promenar.infinity.data.local.db.entity.StateBoardVersionEntity::class,
    ],
    version = 35,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class InfinityDatabase : RoomDatabase() {
    abstract fun knowledgeCitationDao(): com.promenar.infinity.data.library.ask.KnowledgeCitationDao
    abstract fun longTermMemoryDao(): com.promenar.infinity.data.memory.LongTermMemoryDao
    abstract fun scheduledTaskDao(): com.promenar.infinity.data.local.db.dao.ScheduledTaskDao
    abstract fun agentDao(): AgentDao
    abstract fun sessionDao(): SessionDao
    abstract fun sessionDeletionDao(): SessionDeletionDao
    abstract fun messageDao(): MessageDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun vectorDao(): VectorDao
    abstract fun contextSummaryDao(): ContextSummaryDao
    abstract fun tagDao(): TagDao
    abstract fun documentTagDao(): DocumentTagDao
    abstract fun kgNodeDao(): KgNodeDao
    abstract fun kgEdgeDao(): KgEdgeDao
    abstract fun kgJitCacheDao(): KgJitCacheDao
    abstract fun kgEvidenceDao(): KgEvidenceDao
    abstract fun vectorizationTaskDao(): VectorizationTaskDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun artifactDao(): ArtifactDao
    abstract fun skillDao(): SkillDao
    abstract fun fileEntryDao(): FileEntryDao
    abstract fun workspaceSeqDao(): WorkspaceSeqDao
    abstract fun taskNodeDao(): TaskNodeDao
    abstract fun toolExecutionLedgerDao(): ToolExecutionLedgerDao
    abstract fun fileVersionDao(): FileVersionDao
    abstract fun workspaceMutationDao(): WorkspaceMutationDao
    abstract fun sshHostDao(): SshHostDao
    abstract fun externalMountDao(): ExternalMountDao
    abstract fun workspaceDao(): WorkspaceDao
    abstract fun agentRuleRevisionDao(): AgentRuleRevisionDao
    abstract fun stateBoardDao(): com.promenar.infinity.data.local.db.dao.StateBoardDao
    abstract fun parameterSchemeDao(): ParameterSchemeDao
}

internal const val INFINITY_SCHEMA_V21_IDENTITY_HASH = "b59d4265cdf25db536fb91226cd85ab3"
internal const val INFINITY_SCHEMA_V22_IDENTITY_HASH = "44c225495bedbde35d20c36ffbddf6a4"
internal const val INFINITY_SCHEMA_V23_IDENTITY_HASH = "7d4eb1523842672010345f378c8b7bd9"
internal const val INFINITY_SCHEMA_V26_IDENTITY_HASH = "d7823d310bc01ce689665a8f95f25c08"
internal const val INFINITY_SCHEMA_V27_IDENTITY_HASH = "58972c98147d4a85a83a42d7257d0535"
internal const val INFINITY_SCHEMA_V25_IDENTITY_HASH = "3345ac5e27ee342bbe8512e82146c406"
internal const val INFINITY_SCHEMA_V24_IDENTITY_HASH = "25746d91c031c8c926030a9e148b20a1"

internal fun SQLiteDatabase.hasSshHostContract(): Boolean {
    val expectedColumns = mapOf(
        "id" to SshColumnContract("TEXT", true, primaryKeyPosition = 1),
        "alias" to SshColumnContract("TEXT", true),
        "hostname" to SshColumnContract("TEXT", true),
        "port" to SshColumnContract("INTEGER", true, defaultValue = "22"),
        "username" to SshColumnContract("TEXT", true),
        "auth_type" to SshColumnContract("TEXT", true, defaultValue = "'KEY'"),
        "jump_alias" to SshColumnContract("TEXT", false),
        "trusted_key_type" to SshColumnContract("TEXT", false),
        "trusted_fingerprint" to SshColumnContract("TEXT", false),
        "keep_terminal" to SshColumnContract("INTEGER", true, defaultValue = "0"),
        "local_forward_port" to SshColumnContract("INTEGER", false),
        "forward_host" to SshColumnContract("TEXT", true, defaultValue = "'127.0.0.1'"),
        "forward_port" to SshColumnContract("INTEGER", false),
    )
    val columns = rawQuery("PRAGMA table_info(ssh_hosts)", null).use { cursor ->
        buildMap {
            while (cursor.moveToNext()) {
                put(cursor.getString(1), SshColumnContract(
                    type = cursor.getString(2), notNull = cursor.getInt(3) == 1,
                    primaryKeyPosition = cursor.getInt(5), defaultValue = cursor.getString(4),
                ))
            }
        }
    }
    if (columns != expectedColumns) return false
    return rawQuery("PRAGMA index_list(ssh_hosts)", null).use { cursor ->
        var uniqueAlias = false
        while (cursor.moveToNext()) {
            if (cursor.getString(1) == "index_ssh_hosts_alias" && cursor.getInt(2) == 1 &&
                cursor.getInt(cursor.getColumnIndexOrThrow("partial")) == 0
            ) uniqueAlias = true
        }
        uniqueAlias
    } && rawQuery("PRAGMA index_info(index_ssh_hosts_alias)", null).use { cursor ->
        cursor.moveToFirst() && cursor.getString(2) == "alias" && !cursor.moveToNext()
    }
}

private data class SshColumnContract(
    val type: String,
    val notNull: Boolean,
    val primaryKeyPosition: Int = 0,
    val defaultValue: String? = null,
)

/** v25 → v26：仅保存在本机的定时任务与持久化执行占位。 */
val MIGRATION_25_26 = object : androidx.room.migration.Migration(25, 26) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `scheduled_tasks` (
            `id` TEXT NOT NULL, `session_id` TEXT NOT NULL, `name` TEXT NOT NULL, `prompt` TEXT NOT NULL,
            `trigger_type` TEXT NOT NULL, `trigger_value` TEXT NOT NULL, `zone_id` TEXT NOT NULL,
            `exact_requested` INTEGER NOT NULL, `exact_effective` INTEGER NOT NULL, `enabled` INTEGER NOT NULL,
            `next_fire_at` INTEGER, `run_count` INTEGER NOT NULL, `max_runs` INTEGER, `expires_at` INTEGER,
            `last_fired_at` INTEGER, `last_result` TEXT, `created_at` INTEGER NOT NULL, `job_id` INTEGER NOT NULL,
            `claim_token` TEXT, `catch_up` INTEGER NOT NULL, `stop_reason` INTEGER, `last_run_token` TEXT,
            PRIMARY KEY(`id`), FOREIGN KEY(`session_id`) REFERENCES `sessions`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_scheduled_tasks_session_id` ON `scheduled_tasks` (`session_id`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_scheduled_tasks_job_id` ON `scheduled_tasks` (`job_id`)")
    }
}

internal const val INFINITY_SCHEMA_V28_IDENTITY_HASH = "3ecd53e343d156d2165df7963419da0c"
internal const val INFINITY_SCHEMA_V29_IDENTITY_HASH = "9d0bef4ef8c9c7850437f0141ebd97b4"
internal const val INFINITY_SCHEMA_V30_IDENTITY_HASH = "ccac3b935d14b590734779048ef2eaf2"

internal const val INFINITY_SCHEMA_V31_IDENTITY_HASH = "f4d6fe7e2fb95b1bb98f165bb840d058"

internal const val INFINITY_SCHEMA_V32_IDENTITY_HASH = "1ecb27d0ac1c4dbf13e23fd519b8cfe3"
internal const val INFINITY_SCHEMA_V33_IDENTITY_HASH = "2f0e094301cf97331d95b088d15627b0"
internal const val INFINITY_SCHEMA_V34_IDENTITY_HASH = "055dbc210dde0552322f3e8615f95568"
internal const val INFINITY_SCHEMA_V35_IDENTITY_HASH = "b12dbad2ee38d5515f4051a7da0ab9f1"
